package io.agentflow.approval.history;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.approval.process.FlowableOrganizationMembers;
import io.agentflow.organization.OrganizationAssigneeResolver;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Comparator;
import org.flowable.bpmn.model.*;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 从轮次实际引擎定义生成图；平台后续发布与同号全局模板不能替换历史拓扑。
 * @author owlzhangfq@gmail.com
 */
@Component
public class FlowableRoundDiagramAdapter implements RoundDiagramPort {
    private static final String SEQUENCE_FLOW = "sequenceFlow";
    private final HistoryService history;
    private final RepositoryService repository;
    private final RuntimeService runtime;
    private final TaskService tasks;
    private final JsonUtil json;

    /** 只依赖引擎公开查询接口，不直接读取或修改其内部表。 */
    public FlowableRoundDiagramAdapter(HistoryService history, RepositoryService repository,
                                       RuntimeService runtime, TaskService tasks, JsonUtil json) {
        this.history = history; this.repository = repository; this.runtime = runtime; this.tasks = tasks; this.json = json;
    }

    @Override
    public Diagram read(Application application, SubmissionRound round) {
        String tenant = application.tenantId();
        var instance = history.createHistoricProcessInstanceQuery().processInstanceId(round.processInstanceId())
                .variableValueEquals("tenantId", tenant)
                .variableValueEquals("applicationId", application.id().toString())
                .variableValueEquals("roundNo", round.roundNo()).singleResult();
        if (instance == null || !matchesTenant(instance.getTenantId(), tenant)
                || !application.processKey().equals(instance.getProcessDefinitionKey())
                || (instance.getProcessDefinitionVersion() == null || instance.getProcessDefinitionVersion().longValue() != round.definitionVersion())
                || application.definitionVersion() != round.definitionVersion()
                || application.runtimeDefinitionId() != null
                    && !application.runtimeDefinitionId().equals(instance.getProcessDefinitionId())) throw unavailable();
        var definition = repository.createProcessDefinitionQuery().processDefinitionId(instance.getProcessDefinitionId()).singleResult();
        if (definition == null || !matchesTenant(definition.getTenantId(), tenant)
                || !application.processKey().equals(definition.getKey()) || round.definitionVersion() != definition.getVersion()) throw unavailable();
        // 无租户定义仅允许已明确保留的内置报销 v1，不能放宽到任意全局流程。
        if ((definition.getTenantId() == null || definition.getTenantId().isEmpty())
                && !("expense-reimbursement".equals(definition.getKey()) && definition.getVersion() == 1)) throw unavailable();
        var model = repository.getBpmnModel(definition.getId());
        var process = model == null ? null : model.getProcessById(definition.getKey());
        if (process == null) throw unavailable();
        List<FlowNode> flowNodes = process.getFlowElements().stream().filter(FlowNode.class::isInstance).map(FlowNode.class::cast).toList();
        // 当前平台只发布平面受限图；遇到外部子流程时明确不可用，避免把缺失结构显示成完整流程。
        if (flowNodes.isEmpty() || flowNodes.stream().anyMatch(SubProcess.class::isInstance)) throw unavailable();
        var activities = history.createHistoricActivityInstanceQuery().processInstanceId(instance.getId()).list().stream()
                .filter(activity -> matchesTenant(activity.getTenantId(), tenant))
                .collect(Collectors.groupingBy(HistoricActivityInstance::getActivityId));
        var activeTasks = tasks.createTaskQuery().processInstanceId(instance.getId()).list().stream()
                .filter(task -> matchesTenant(task.getTenantId(), tenant))
                .collect(Collectors.groupingBy(org.flowable.task.api.Task::getTaskDefinitionKey, Collectors.counting()));
        var live = runtime.createProcessInstanceQuery().processInstanceId(instance.getId()).singleResult();
        Set<String> active = live == null ? Set.of() : new HashSet<>(runtime.getActiveActivityIds(instance.getId()));
        var candidates = candidateSnapshots(instance.getId());
        List<Node> nodes = flowNodes.stream().map(node -> {
            var evidence = activities.getOrDefault(node.getId(), List.of());
            long count = activeTasks.getOrDefault(node.getId(), 0L);
            State state = active.contains(node.getId()) || count > 0 ? State.ACTIVE : evidence.isEmpty() ? State.NOT_REACHED : State.LEFT;
            Instant entered = evidence.stream().map(HistoricActivityInstance::getStartTime).filter(Objects::nonNull).min(Date::compareTo).map(Date::toInstant).orElse(null);
            Instant left = evidence.stream().map(HistoricActivityInstance::getEndTime).filter(Objects::nonNull).max(Date::compareTo).map(Date::toInstant).orElse(null);
            return new Node(node.getId(), node.getName() == null ? node.getId() : node.getName(), type(node), state, count, entered, left,
                    node instanceof UserTask ? candidates.getOrDefault(node.getId(), List.of()) : List.of());
        }).toList();
        Set<String> ids = nodes.stream().map(Node::id).collect(Collectors.toSet());
        List<Edge> edges = process.getFlowElements().stream().filter(SequenceFlow.class::isInstance).map(SequenceFlow.class::cast)
                .filter(edge -> ids.contains(edge.getSourceRef()) && ids.contains(edge.getTargetRef()))
                .map(edge -> edge(edge, activities.getOrDefault(edge.getId(), List.of()).stream()
                        .filter(activity -> SEQUENCE_FLOW.equals(activity.getActivityType())
                                && definition.getId().equals(activity.getProcessDefinitionId())).toList()))
                .toList();
        return new Diagram(application.id(), round.roundNo(), round.definitionVersion(), round.status(), Instant.now(), nodes, edges);
    }

    /** 已核对申请及轮次后读取历史快照，绝不查询当前组织来补写责任。 */
    private Map<String, List<CandidateSnapshot>> candidateSnapshots(String instanceId) {
        var result = new HashMap<String, List<CandidateSnapshot>>();
        String prefix = FlowableOrganizationMembers.SNAPSHOT_PREFIX;
        var values = history.createHistoricVariableInstanceQuery().processInstanceId(instanceId)
                .variableNameLike(prefix + "%").list();
        for (var value : values) {
            // LIKE 中的下划线是通配符，仍需精确前缀和执行作用域检查。
            if (!value.getVariableName().startsWith(prefix) || value.getTaskId() != null) continue;
            if (!(value.getValue() instanceof String text)) throw unavailable();
            var snapshot = json.read(text, OrganizationAssigneeResolver.Selection.class);
            if (snapshot.directoryRevision() < 1 || snapshot.subjects().isEmpty()) throw unavailable();
            String nodeId = value.getVariableName().substring(prefix.length());
            result.computeIfAbsent(nodeId, ignored -> new ArrayList<>())
                    .add(new CandidateSnapshot(value.getId(), snapshot.directoryRevision(), snapshot.subjects()));
        }
        result.replaceAll((node, records) -> records.stream().sorted(Comparator.comparing(CandidateSnapshot::id)).toList());
        return result;
    }

    /** 仅使用引擎的连线历史，两个端点都已到达也不能证明这条连线被执行。 */
    private Edge edge(SequenceFlow flow, List<HistoricActivityInstance> evidence) {
        Instant first = evidence.stream().map(HistoricActivityInstance::getStartTime).filter(Objects::nonNull)
                .min(Date::compareTo).map(Date::toInstant).orElse(null);
        Instant last = evidence.stream().map(HistoricActivityInstance::getStartTime).filter(Objects::nonNull)
                .max(Date::compareTo).map(Date::toInstant).orElse(null);
        boolean defaultBranch = flow.getSourceFlowElement() instanceof Gateway gateway && flow.getId().equals(gateway.getDefaultFlow());
        return new Edge(flow.getId(), flow.getSourceRef(), flow.getTargetRef(), defaultBranch,
                evidence.isEmpty() ? EdgeState.NOT_RECORDED : EdgeState.TAKEN, evidence.size(), first, last);
    }

    private String type(FlowNode node) {
        if (node instanceof StartEvent) return "START";
        if (node instanceof EndEvent) return "END";
        if (node instanceof UserTask) return "USER_TASK";
        if (node instanceof ExclusiveGateway) return "EXCLUSIVE_GATEWAY";
        if (node instanceof ParallelGateway) return "PARALLEL_GATEWAY";
        return "OTHER";
    }

    private boolean matchesTenant(String owner, String tenant) { return owner == null || owner.isEmpty() || owner.equals(tenant); }
    private DomainException unavailable() { return new DomainException("NOT_FOUND", "Bound process diagram is not available"); }
}
