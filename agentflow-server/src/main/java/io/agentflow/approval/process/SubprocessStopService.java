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
import io.agentflow.expense.ExpenseReleaseService;
import io.agentflow.notification.ApprovalNotificationService;
import io.agentflow.notification.TaskAudiencePort;
import io.agentflow.procurement.ProcurementPayableReservations;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 根调用停止时协调真实后代结论；源人工意见只保存在原申请，其他分支取消不伪造意见。
 * @author owlzhangfq@gmail.com
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class SubprocessStopService {
    private final ApplicationRepository applications;
    private final SubmissionRoundRepository rounds;
    private final SubprocessCallRepository calls;
    private final SubprocessExecutionLocks locks;
    private final ApprovalCompletionService completion;
    private final RuntimeService runtime;
    private final HistoryService history;
    private final ApprovalNotificationService notifications;
    private final ApplicationAuditPort audit;
    private final ExpenseReleaseService expenses;
    private final ProcurementPayableReservations procurement;

    /** 跨申请、引擎及财务资源属于应用编排，申请自身状态仍由领域实体变更。 */
    public SubprocessStopService(ApplicationRepository applications, SubmissionRoundRepository rounds,
            SubprocessCallRepository calls, SubprocessExecutionLocks locks, ApprovalCompletionService completion,
            RuntimeService runtime, HistoryService history, ApprovalNotificationService notifications,
            ApplicationAuditPort audit, ExpenseReleaseService expenses, ProcurementPayableReservations procurement) {
        this.applications = applications;
        this.rounds = rounds;
        this.calls = calls;
        this.locks = locks;
        this.completion = completion;
        this.runtime = runtime;
        this.history = history;
        this.notifications = notifications;
        this.audit = audit;
        this.expenses = expenses;
        this.procurement = procurement;
    }

    /** 调用方已经授权并取得根路径锁；停止之前锁定整棵原调用树并冻结原任务接收人。 */
    public Plan before(Application source) {
        var ancestry = locks.ancestry(source.tenantId(), source.id());
        if (ancestry.isEmpty() && calls.findByParentRound(source.tenantId(), source.id(), source.roundNo()).isEmpty()) {
            return new Plan(source.id(), List.of(), Set.of());
        }
        var origin = ancestry.isEmpty() ? null : ancestry.get(ancestry.size() - 1);
        UUID rootId = origin == null ? source.id() : origin.parentApplicationId();
        int rootRound = origin == null ? source.roundNo() : origin.parentRoundNo();
        var root = applications.lockById(source.tenantId(), rootId).orElseThrow(SubprocessStopService::changed);
        if (root.status() != ApplicationStatus.IN_APPROVAL || root.roundNo() != rootRound) throw changed();
        // 子申请不承载结构化财务业务；根申请的既有业务资源必须在引擎停止之前锁定。
        completion.lock(root);
        var nodes = new LinkedHashMap<UUID, Node>();
        collect(source.tenantId(), rootId, rootRound, null, nodes);
        if (!nodes.containsKey(source.id())) throw changed();
        var ancestors = ancestry.stream().map(SubprocessCall::parentApplicationId).collect(Collectors.toUnmodifiableSet());
        return new Plan(source.id(), List.copyOf(nodes.values()), ancestors);
    }

    /** 原用例已记录源操作结论；这里只追加祖先与未决定后代的真实系统联动。 */
    public void after(Plan plan, Application source, String sourceActor) {
        if (plan.nodes.isEmpty()) return;
        if (!plan.sourceId.equals(source.id()) || source.status() != ApplicationStatus.REJECTED
                && source.status() != ApplicationStatus.RETURNED && source.status() != ApplicationStatus.WITHDRAWN
                && source.status() != ApplicationStatus.CANCELLED) throw changed();
        for (var node : plan.nodes) {
            if (node.application().status() == ApplicationStatus.IN_APPROVAL) requireStopped(node);
        }
        String actor = SubprocessStartService.SYSTEM_ACTOR;
        // 不把源申请的自由文本理由复制到其他申请；来源身份和实际结论足以关联原操作。
        String reason = "Subprocess tree stopped after application " + source.id() + " concluded " + source.status();
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        for (var node : plan.nodes) {
            var application = node.application();
            if (application.id().equals(source.id())) {
                if (source.status() != ApplicationStatus.WITHDRAWN && source.status() != ApplicationStatus.CANCELLED) {
                    notifications.subprocessStopped(source, sourceActor, node.audience(), false);
                }
                continue;
            }
            if (application.status() != ApplicationStatus.IN_APPROVAL) continue;
            long version = application.version();
            if (plan.ancestors.contains(application.id()) && source.status() == ApplicationStatus.RETURNED) {
                application.returnToApplicant(version);
            } else if (plan.ancestors.contains(application.id()) && source.status() == ApplicationStatus.REJECTED) {
                application.reject(version);
            } else {
                application.cancelWithParent(version);
            }
            applications.update(application, version);
            rounds.complete(application.tenantId(), application.id(), application.roundNo(), node.round().processInstanceId(),
                    SubmissionRound.Status.valueOf(application.status().name()), reason, actor, now);
            if (application.status() == ApplicationStatus.REJECTED || application.status() == ApplicationStatus.CANCELLED) {
                expenses.release(application, actor, now);
                procurement.releaseStopped(application, actor, now);
            }
            audit.record(new ApplicationAuditPort.ApplicationOperation(application.tenantId(), application.id(), application.version(),
                    application.roundNo(), node.round().processInstanceId(), actor, ApplicationAuditPort.Action.SUBPROCESS_STOPPED,
                    ApplicationStatus.IN_APPROVAL, application.status(), reason));
            notifications.subprocessStopped(application, actor, node.audience(), true);
        }
    }

    private void collect(String tenant, UUID id, int roundNo, SubprocessCall origin, LinkedHashMap<UUID, Node> nodes) {
        if (nodes.containsKey(id)) throw changed();
        var application = applications.lockById(tenant, id).orElseThrow(SubprocessStopService::changed);
        var round = rounds.findByRound(tenant, id, roundNo).orElseThrow(SubprocessStopService::changed);
        if (application.roundNo() != roundNo || round.definitionVersion() != application.definitionVersion()
                || !round.status().name().equals(application.status().name())) throw changed();
        if (origin != null && (!origin.childRuntimeDefinitionId().equals(application.runtimeDefinitionId())
                || !origin.childProcessInstanceId().equals(round.processInstanceId()) || application.businessReference() != null)) throw changed();
        var instance = runtime.createProcessInstanceQuery().processInstanceId(round.processInstanceId())
                .variableValueEquals("tenantId", tenant).variableValueEquals("applicationId", id.toString())
                .variableValueEquals("roundNo", roundNo).singleResult();
        if (application.status() == ApplicationStatus.IN_APPROVAL && (instance == null
                || !tenant.equals(instance.getTenantId()) || !application.runtimeDefinitionId().equals(instance.getProcessDefinitionId()))) throw changed();
        if (application.status() != ApplicationStatus.IN_APPROVAL && instance != null) throw changed();
        nodes.put(id, new Node(application, round, notifications.unfinishedAudience(application)));
        for (var call : calls.findByParentRound(tenant, id, roundNo)) {
            if (!call.parentProcessInstanceId().equals(round.processInstanceId())
                    || !call.parentRuntimeDefinitionId().equals(application.runtimeDefinitionId())) throw changed();
            collect(tenant, call.childApplicationId(), SubprocessCall.CHILD_ROUND, call, nodes);
        }
    }

    private void requireStopped(Node node) {
        String instanceId = node.round().processInstanceId();
        if (runtime.createProcessInstanceQuery().processInstanceId(instanceId).count() != 0) throw changed();
        var stopped = history.createHistoricProcessInstanceQuery().processInstanceId(instanceId).singleResult();
        if (stopped == null || stopped.getEndTime() == null || stopped.getDeleteReason() == null
                || !node.application().tenantId().equals(stopped.getTenantId())
                || !node.application().runtimeDefinitionId().equals(stopped.getProcessDefinitionId())) throw changed();
    }

    private static DomainException changed() {
        return new DomainException("SUBPROCESS_STOP_MISMATCH", "Subprocess stop no longer matches the original application tree");
    }

    /** 当前事务内的原调用树，不能由客户端构造或用于另一个轮次。
     * @author owlzhangfq@gmail.com
     */
    public static final class Plan {
        private final UUID sourceId;
        private final List<Node> nodes;
        private final Set<UUID> ancestors;

        private Plan(UUID sourceId, List<Node> nodes, Set<UUID> ancestors) {
            this.sourceId = sourceId;
            this.nodes = nodes;
            this.ancestors = ancestors;
        }

        /** 存在调用关系时必须从根实例停止，避免只删除子实例却让父调用悬空。 */
        public String instanceToStop(String sourceInstanceId) {
            return nodes.isEmpty() ? sourceInstanceId : nodes.get(0).round().processInstanceId();
        }
    }

    /** @author owlzhangfq@gmail.com */
    private record Node(Application application, SubmissionRound round, List<TaskAudiencePort.Audience> audience) { }
}
