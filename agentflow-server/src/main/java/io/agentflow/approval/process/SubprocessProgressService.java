package io.agentflow.approval.process;

import io.agentflow.approval.SubprocessExecutionLocks;
import io.agentflow.approval.SubprocessStartService;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.approval.model.SubprocessCall;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.approval.repository.SubprocessCallRepository;
import io.agentflow.approval.service.ApplicationAuditPort;
import io.agentflow.common.DomainException;
import io.agentflow.notification.ApprovalNotificationService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原生命令返回后按真实结论从叶到根收尾；业务版本、财务收尾、审计和通知共用原事务。
 * @author owlzhangfq@gmail.com
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class SubprocessProgressService {
    private final ApplicationRepository applications;
    private final SubmissionRoundRepository rounds;
    private final SubprocessCallRepository calls;
    private final SubprocessExecutionLocks locks;
    private final ApprovalCompletionService completion;
    private final RuntimeService runtime;
    private final HistoryService history;
    private final ApprovalNotificationService notifications;
    private final ApplicationAuditPort audit;

    /** 复用既有财务收尾与消息编排，不维护第二套任务或审批结论。 */
    public SubprocessProgressService(ApplicationRepository applications, SubmissionRoundRepository rounds,
            SubprocessCallRepository calls, SubprocessExecutionLocks locks, ApprovalCompletionService completion,
            RuntimeService runtime, HistoryService history, ApprovalNotificationService notifications, ApplicationAuditPort audit) {
        this.applications = applications; this.rounds = rounds; this.calls = calls; this.locks = locks;
        this.completion = completion; this.runtime = runtime; this.history = history;
        this.notifications = notifications; this.audit = audit;
    }

    /** 调用方已持有根到当前申请的锁；仅保存推进前事实，不把快照交给客户端重放。 */
    public Before before(Application application) {
        var ancestry = locks.ancestry(application.tenantId(), application.id());
        var root = ancestry.isEmpty() ? null : ancestry.get(ancestry.size() - 1);
        UUID rootId = root == null ? application.id() : root.parentApplicationId();
        int rootRound = root == null ? (application.editable() ? application.nextSubmissionRound() : application.roundNo()) : root.parentRoundNo();
        var states = new LinkedHashMap<UUID, State>();
        for (var node : tree(application.tenantId(), rootId, rootRound).values()) {
            states.put(node.application().id(), new State(node.application().status(), notifications.pendingTaskIds(node.application())));
        }
        return new Before(application.tenantId(), rootId, rootRound, Map.copyOf(states));
    }

    /** 根提交已保存轮次和原提交通知后，补齐实际新建子申请及可能同步结束的调用。 */
    public void afterSubmit(Before before, Application application) { settle(before, application, true); }

    /** 当前任务或等待用例已保存自己的结论；只协调新子申请及受到影响的祖先。 */
    public void afterAdvance(Before before, Application application) { settle(before, application, false); }

    /** 等待本身不是批准；真实本流程人工意见或已批准的直接子轮次才能提供审批依据。 */
    public boolean hasApprovalEvidence(Application application, String instanceId) {
        if (history.createHistoricTaskInstanceQuery().processInstanceId(instanceId).finished().list().stream()
                .anyMatch(task -> task.getDeleteReason() == null && task.getAssignee() != null)) return true;
        return calls.findByParentRound(application.tenantId(), application.id(), application.roundNo()).stream().anyMatch(call ->
                applications.findById(application.tenantId(), call.childApplicationId()).filter(child -> child.status() == ApplicationStatus.APPROVED)
                        .isPresent() && rounds.findByRound(application.tenantId(), call.childApplicationId(), SubprocessCall.CHILD_ROUND)
                        .filter(round -> round.status() == SubmissionRound.Status.APPROVED
                                && round.processInstanceId().equals(call.childProcessInstanceId())).isPresent());
    }

    private void settle(Before before, Application primary, boolean submitted) {
        var tree = tree(before.tenant, before.rootId, before.rootRound);
        if (tree.size() == 1) return;
        // 当前用例的实体可能已变更，保留同一实例，确保同步收尾也反映到原操作回执。
        var source = tree.get(primary.id());
        tree.put(primary.id(), new Node(primary, source.roundNo(), source.origin()));
        var completed = new HashSet<UUID>();
        var order = new ArrayList<>(tree.values());
        for (int index = order.size() - 1; index >= 0; index--) {
            var node = order.get(index); var application = node.application();
            var previous = before.states.get(application.id());
            boolean created = previous == null;
            if (created) notifications.submitted(application, SubprocessStartService.SYSTEM_ACTOR);
            if (application.status() == ApplicationStatus.APPROVED) {
                if (previous == null || previous.status() != ApplicationStatus.APPROVED) completed.add(application.id());
                continue;
            }
            if (application.status() != ApplicationStatus.IN_APPROVAL || application.id().equals(primary.id()) && !submitted) continue;
            var finishedCalls = tree.values().stream().filter(child -> child.origin() != null
                    && child.origin().parentApplicationId().equals(application.id()) && completed.contains(child.application().id()))
                    .map(Node::origin).toList();
            if (!created && finishedCalls.isEmpty()) continue;
            var round = rounds.findByRound(before.tenant, application.id(), node.roundNo()).orElseThrow(SubprocessProgressService::changed);
            boolean ended = normallyEnded(application, round);
            if (!ended && finishedCalls.isEmpty()) continue;
            if (ended && !hasApprovalEvidence(application, round.processInstanceId())) {
                throw new DomainException("SUBPROCESS_REQUIRES_APPROVAL", "A subprocess cannot replace actual human approval");
            }
            long version = application.version();
            application.recordRuntimeAction(version);
            String reason = "Completed subprocess calls: " + finishedCalls.stream().map(call -> call.id().toString()).toList();
            completion.persistProgress(application, version, round.processInstanceId(), ended, SubprocessStartService.SYSTEM_ACTOR, reason);
            audit.record(new ApplicationAuditPort.ApplicationOperation(before.tenant, application.id(), application.version(), node.roundNo(),
                    round.processInstanceId(), SubprocessStartService.SYSTEM_ACTOR, ApplicationAuditPort.Action.SUBPROCESS_COMPLETED,
                    ApplicationStatus.IN_APPROVAL, application.status(), reason));
            notifications.processAdvanced(application, SubprocessStartService.SYSTEM_ACTOR, null, null,
                    previous == null ? Set.of() : previous.taskIds());
            if (ended) completed.add(application.id());
        }
    }

    private boolean normallyEnded(Application application, SubmissionRound round) {
        if (round.status() != SubmissionRound.Status.IN_APPROVAL || round.roundNo() != application.roundNo()
                || round.definitionVersion() != application.definitionVersion()) throw changed();
        var running = runtime.createProcessInstanceQuery().processInstanceId(round.processInstanceId()).singleResult();
        if (running != null) {
            if (!application.tenantId().equals(running.getTenantId()) || !application.runtimeDefinitionId().equals(running.getProcessDefinitionId())) throw changed();
            return false;
        }
        var finished = history.createHistoricProcessInstanceQuery().processInstanceId(round.processInstanceId())
                .variableValueEquals("tenantId", application.tenantId()).variableValueEquals("applicationId", application.id().toString())
                .variableValueEquals("roundNo", application.roundNo()).singleResult();
        if (finished == null || finished.getEndTime() == null || finished.getDeleteReason() != null
                || !application.tenantId().equals(finished.getTenantId())
                || !application.runtimeDefinitionId().equals(finished.getProcessDefinitionId())) throw changed();
        return true;
    }

    private LinkedHashMap<UUID, Node> tree(String tenant, UUID rootId, int rootRound) {
        var result = new LinkedHashMap<UUID, Node>();
        visit(tenant, rootId, rootRound, null, result);
        return result;
    }

    private void visit(String tenant, UUID id, int roundNo, SubprocessCall origin, LinkedHashMap<UUID, Node> result) {
        var application = applications.findById(tenant, id).orElseThrow(SubprocessProgressService::changed);
        if (result.putIfAbsent(id, new Node(application, roundNo, origin)) != null) throw changed();
        for (var call : calls.findByParentRound(tenant, id, roundNo)) {
            visit(tenant, call.childApplicationId(), SubprocessCall.CHILD_ROUND, call, result);
        }
    }

    private static DomainException changed() {
        return new DomainException("SUBPROCESS_RUNTIME_MISMATCH", "Subprocess runtime and application round do not match");
    }

    /** 仅在当前事务命令前后传递，构造入口不对其他调用方开放。
     * @author owlzhangfq@gmail.com
     */
    public static final class Before {
        private final String tenant;
        private final UUID rootId;
        private final int rootRound;
        private final Map<UUID, State> states;
        private Before(String tenant, UUID rootId, int rootRound, Map<UUID, State> states) {
            this.tenant = tenant; this.rootId = rootId; this.rootRound = rootRound; this.states = states;
        }
    }
    /** @author owlzhangfq@gmail.com */
    private record State(ApplicationStatus status, Set<String> taskIds) { }
    /** @author owlzhangfq@gmail.com */
    private record Node(Application application, int roundNo, SubprocessCall origin) { }
}
