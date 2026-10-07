package io.agentflow.approval.process;

import io.agentflow.approval.SubprocessExecutionLocks;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.approval.service.ApplicationAuditPort;
import io.agentflow.common.DomainException;
import io.agentflow.definition.DefinitionModels;
import io.agentflow.notification.ApprovalNotificationService;
import io.agentflow.servicetask.ServiceTaskCommand;
import io.agentflow.servicetask.ServiceTaskDefinitionSnapshots;
import io.agentflow.servicetask.ServiceTaskOperation;
import org.flowable.engine.RuntimeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台结果只推进原等待，复用申请根锁、人工批准证据、财务收尾和原通知事务。
 * @author owlzhangfq@gmail.com
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ServiceTaskWaitService {
    private final ApplicationRepository applications;
    private final SubmissionRoundRepository rounds;
    private final RuntimeService runtime;
    private final ServiceTaskDefinitionSnapshots definitions;
    private final ApprovalCompletionService completion;
    private final SubprocessProgressService subprocesses;
    private final ApprovalNotificationService notifications;
    private final ApplicationAuditPort audit;

    /** 保持与事件、定时等待一致的锁序和业务推进职责。 */
    public ServiceTaskWaitService(ApplicationRepository applications, SubmissionRoundRepository rounds, RuntimeService runtime,
            ServiceTaskDefinitionSnapshots definitions, ApprovalCompletionService completion, SubprocessProgressService subprocesses,
            ApprovalNotificationService notifications, ApplicationAuditPort audit) {
        this.applications = applications; this.rounds = rounds; this.runtime = runtime; this.definitions = definitions;
        this.completion = completion; this.subprocesses = subprocesses; this.notifications = notifications; this.audit = audit;
    }

    /** 锁后识别暂停与过期；执行令牌复用到另一个节点不再属于原命令。 */
    public Context lock(ServiceTaskCommand command) {
        var origin = command.binding();
        var initial = applications.findById(command.tenantId(), origin.applicationId()).orElse(null);
        if (initial == null) return new Context(State.STALE, null, null);
        var locked = completion.lockForProgress(initial); var application = locked.application();
        if (locked.ancestors() == SubprocessExecutionLocks.AncestorState.STALE || application.status() != ApplicationStatus.IN_APPROVAL
                || application.roundNo() != origin.roundNo() || application.definitionVersion() != origin.definitionVersion()
                || !application.processKey().equals(origin.processKey())) return new Context(State.STALE, application, null);
        var round = rounds.findByRound(command.tenantId(), origin.applicationId(), origin.roundNo()).orElse(null);
        if (round == null || round.status() != SubmissionRound.Status.IN_APPROVAL || !origin.processInstanceId().equals(round.processInstanceId())
                || round.definitionVersion() != origin.definitionVersion()) return new Context(State.STALE, application, null);
        var instance = runtime.createProcessInstanceQuery().processInstanceId(origin.processInstanceId()).includeProcessVariables().singleResult();
        var execution = runtime.createExecutionQuery().executionId(origin.executionId()).singleResult();
        if (instance == null || execution == null || !command.tenantId().equals(instance.getTenantId()) || !command.tenantId().equals(execution.getTenantId())
                || !instance.getProcessDefinitionId().equals(application.runtimeDefinitionId()) || !origin.processInstanceId().equals(execution.getProcessInstanceId())
                || !origin.nodeId().equals(execution.getActivityId()) || !command.tenantId().equals(instance.getProcessVariables().get("tenantId"))
                || !origin.applicationId().toString().equals(instance.getProcessVariables().get("applicationId"))
                || !Integer.valueOf(origin.roundNo()).equals(instance.getProcessVariables().get("roundNo"))) return new Context(State.STALE, application, null);
        var definition = definitions.find(command.tenantId(), origin.processKey(), origin.definitionVersion());
        var node = definition.graph().node(origin.nodeId());
        if (!definition.digest().equals(origin.definitionDigest()) || node == null || node.type() != DefinitionModels.NodeType.SERVICE_TASK) return new Context(State.STALE, application, null);
        return new Context(instance.isSuspended() || locked.ancestors() == SubprocessExecutionLocks.AncestorState.PAUSED ? State.PAUSED : State.ACTIVE, application, node);
    }

    /** 原服务结果不写流程变量，也不能替代人工审批或财务批准。 */
    public void advance(Context context, ServiceTaskOperation operation) {
        if (context.state() != State.ACTIVE || operation.status() != ServiceTaskOperation.Status.APPLIED) throw new IllegalStateException("An applied operation and active original service wait are required");
        var command = operation.input().command(); var origin = command.binding(); var application = context.application();
        var before = subprocesses.before(application); var previousTasks = notifications.pendingTaskIds(application);
        long previous = application.version(); application.recordRuntimeAction(previous);
        runtime.trigger(origin.executionId());
        boolean ended = runtime.createProcessInstanceQuery().processInstanceId(origin.processInstanceId()).singleResult() == null;
        if (ended && !subprocesses.hasApprovalEvidence(application, origin.processInstanceId())) {
            throw new DomainException("SERVICE_TASK_REQUIRES_APPROVAL", "A service task cannot substitute for human approval");
        }
        String actor = "system:service:" + command.contract().key(); String reason = "operationId=" + command.id() + ", nodeId=" + origin.nodeId();
        completion.persistProgress(application, previous, origin.processInstanceId(), ended, actor, reason);
        audit.record(new ApplicationAuditPort.ApplicationOperation(application.tenantId(), application.businessNo(), application.id(), application.version(), application.roundNo(),
                origin.processInstanceId(), actor, ApplicationAuditPort.Action.SERVICE_TASK_COMPLETED, ApplicationStatus.IN_APPROVAL, application.status(), reason));
        subprocesses.afterAdvance(before, application);
        notifications.processAdvanced(application, actor, null, context.node().name(), previousTasks);
    }

    /** @author owlzhangfq@gmail.com */
    public enum State { ACTIVE, PAUSED, STALE }
    /** @author owlzhangfq@gmail.com */
    public record Context(State state, Application application, DefinitionModels.Node node) { }
}
