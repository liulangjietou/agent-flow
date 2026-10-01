package io.agentflow.approval.process;

import io.agentflow.approval.ApprovalApplicationFacade;
import io.agentflow.approval.SubprocessExecutionLocks;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.approval.service.ApplicationAuditPort;
import io.agentflow.common.DomainException;
import io.agentflow.definition.DefinitionDraftRepository;
import io.agentflow.definition.DefinitionModels;
import io.agentflow.definition.EventWaitPolicy;
import io.agentflow.event.EventContractRepository;
import io.agentflow.notification.ApprovalNotificationService;
import org.flowable.engine.RuntimeService;
import org.flowable.eventsubscription.api.EventSubscription;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 事件只能消费指定轮次的原生消息订阅；本用例不提供来源认证，也不是可直接提交事件的 HTTP 入口。
 * @author owlzhangfq@gmail.com
 */
@Service
public class EventWaitService {
    private static final String MESSAGE = "message";
    private final RuntimeService runtime;
    private final ApplicationRepository applications;
    private final SubmissionRoundRepository rounds;
    private final DefinitionDraftRepository definitions;
    private final EventContractRepository contracts;
    private final ApprovalApplicationFacade reads;
    private final ApprovalCompletionService completion;
    private final SubprocessProgressService subprocesses;
    private final ApprovalNotificationService notifications;
    private final ApplicationAuditPort audit;

    /** 复用原审批的业务收尾与事务通知，不建立第二套引擎等待状态机。 */
    public EventWaitService(RuntimeService runtime, ApplicationRepository applications,
            SubmissionRoundRepository rounds, DefinitionDraftRepository definitions, EventContractRepository contracts,
            ApprovalApplicationFacade reads, ApprovalCompletionService completion, ApprovalNotificationService notifications, ApplicationAuditPort audit,
            SubprocessProgressService subprocesses) {
        this.runtime = runtime; this.applications = applications; this.rounds = rounds;
        this.definitions = definitions; this.contracts = contracts; this.reads = reads; this.completion = completion;
        this.notifications = notifications; this.audit = audit;
        this.subprocesses = subprocesses;
    }

    /** 由可信收件编排调用；消息不携带流程变量，不能覆盖表单、审批结果或财务状态。 */
    @Transactional
    public Outcome advance(Command command) {
        var initial = applications.findById(command.tenantId(), command.applicationId()).orElse(null);
        if (initial == null) return Outcome.STALE;
        var locked = completion.lockForProgress(initial);
        if (locked.ancestors() == SubprocessExecutionLocks.AncestorState.STALE) return Outcome.STALE;
        var application = locked.application();
        var binding = binding(application, command.roundNo(), command.waitId());
        if (binding == null) return Outcome.STALE;
        var policy = EventWaitPolicy.fromProperties(binding.node().properties());
        if (!policy.contractKey().equals(command.contractKey()) || policy.contractVersion() != command.contractVersion()) return Outcome.MISMATCH;
        var referenced = contracts.lockVersion(command.tenantId(), policy.contractKey(), policy.contractVersion()).orElse(null);
        if (referenced == null) return Outcome.CONTRACT_UNAVAILABLE;
        var contract = referenced.contract();
        if (!contract.sourceKey().equals(command.sourceKey()) || !contract.eventType().equals(command.eventType())
                || contract.envelopeVersion() != command.envelopeVersion()) return Outcome.MISMATCH;
        if (!referenced.availability().enabled()) return Outcome.CONTRACT_UNAVAILABLE;
        if (binding.suspended() || locked.ancestors() == SubprocessExecutionLocks.AncestorState.PAUSED) return Outcome.PAUSED;
        var before = subprocesses.before(application);
        long previous = application.version(); application.recordRuntimeAction(previous);
        var previousTasks = notifications.pendingTaskIds(application);
        // 明确 executionId 是必需参数；不使用广播 API，也不向原生事件传递外部正文。
        runtime.messageEventReceived(binding.subscription().getEventName(), binding.subscription().getExecutionId());
        boolean ended = runtime.createProcessInstanceQuery().processInstanceId(binding.round().processInstanceId()).singleResult() == null;
        if (ended && !subprocesses.hasApprovalEvidence(application, binding.round().processInstanceId())) {
            throw new DomainException("EVENT_REQUIRES_APPROVAL", "An event wait cannot substitute for human approval");
        }
        String actor = "system:event:" + contract.sourceKey();
        String reason = "eventId=" + command.eventId() + ", waitId=" + command.waitId();
        completion.persistProgress(application, previous, binding.round().processInstanceId(), ended, actor, reason);
        audit.record(new ApplicationAuditPort.ApplicationOperation(application.tenantId(), application.id(), application.version(), application.roundNo(),
                binding.round().processInstanceId(), actor, ApplicationAuditPort.Action.EVENT_RECEIVED, ApplicationStatus.IN_APPROVAL, application.status(), reason));
        subprocesses.afterAdvance(before, application);
        notifications.processAdvanced(application, actor, null, binding.node().name(), previousTasks);
        return Outcome.ADVANCED;
    }

    /** 等待身份来自实际引擎订阅，读取不创建事件、不授予外部来源发送权限。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View read(UUID applicationId, int roundNo) {
        var application = reads.get(applicationId);
        var round = rounds.findByRound(application.tenantId(), applicationId, roundNo)
                .orElseThrow(() -> new DomainException("NOT_FOUND", "Submission round not found"));
        var definition = definitions.findPublished(application.tenantId(), application.processKey(), round.definitionVersion()).orElse(null);
        var instance = runtime.createProcessInstanceQuery().processInstanceId(round.processInstanceId()).singleResult();
        var entries = new ArrayList<Entry>();
        if (definition != null && instance != null && application.tenantId().equals(instance.getTenantId())
                && instance.getProcessDefinitionId().equals(application.runtimeDefinitionId())) {
            for (var subscription : runtime.createEventSubscriptionQuery().processInstanceId(round.processInstanceId()).eventType(MESSAGE).list()) {
                var node = definition.graph().node(subscription.getActivityId());
                if (node == null || node.type() != DefinitionModels.NodeType.EVENT_WAIT || !application.tenantId().equals(subscription.getTenantId())
                        || !instance.getProcessDefinitionId().equals(subscription.getProcessDefinitionId())) continue;
                var policy = EventWaitPolicy.fromProperties(node.properties());
                var contract = contracts.find(application.tenantId(), policy.contractKey(), policy.contractVersion()).orElse(null);
                entries.add(new Entry(subscription.getId(), node.id(), node.name(), policy.contractKey(), policy.contractVersion(),
                        subscription.getCreated().toInstant(), instance.isSuspended(), contract != null && contract.availability().enabled()));
            }
        }
        entries.sort(Comparator.comparing(Entry::createdAt).thenComparing(Entry::waitId));
        return new View(applicationId, roundNo, application.version(), List.copyOf(entries));
    }

    private Binding binding(Application application, int roundNo, String waitId) {
        if (application.status() != ApplicationStatus.IN_APPROVAL || application.roundNo() != roundNo) return null;
        var round = rounds.findByRound(application.tenantId(), application.id(), roundNo).orElse(null);
        if (round == null || round.status() != SubmissionRound.Status.IN_APPROVAL || round.definitionVersion() != application.definitionVersion()) return null;
        var subscription = runtime.createEventSubscriptionQuery().id(waitId).eventType(MESSAGE).tenantId(application.tenantId()).singleResult();
        if (subscription == null || !round.processInstanceId().equals(subscription.getProcessInstanceId())
                || !subscription.getProcessDefinitionId().equals(application.runtimeDefinitionId())) return null;
        var instance = runtime.createProcessInstanceQuery().processInstanceId(round.processInstanceId()).includeProcessVariables().singleResult();
        if (instance == null || !application.tenantId().equals(instance.getTenantId()) || !application.runtimeDefinitionId().equals(instance.getProcessDefinitionId())
                || !application.tenantId().equals(instance.getProcessVariables().get("tenantId"))
                || !application.id().toString().equals(instance.getProcessVariables().get("applicationId"))
                || !Integer.valueOf(roundNo).equals(instance.getProcessVariables().get("roundNo"))) return null;
        var definition = definitions.findPublished(application.tenantId(), application.processKey(), application.definitionVersion()).orElse(null);
        var node = definition == null ? null : definition.graph().node(subscription.getActivityId());
        var execution = runtime.createExecutionQuery().executionId(subscription.getExecutionId()).singleResult();
        if (node == null || node.type() != DefinitionModels.NodeType.EVENT_WAIT || execution == null
                || !node.id().equals(execution.getActivityId()) || !round.processInstanceId().equals(execution.getProcessInstanceId())) return null;
        return new Binding(round, node, subscription, instance.isSuspended());
    }

    /** @author owlzhangfq@gmail.com */
    private record Binding(SubmissionRound round, DefinitionModels.Node node, EventSubscription subscription, boolean suspended) { }
    /** @author owlzhangfq@gmail.com */
    public enum Outcome { ADVANCED, STALE, MISMATCH, PAUSED, CONTRACT_UNAVAILABLE }
    /**
     * 收件适配器在认证、信封和幂等校验后映射的内部命令；客户端不能直接调用 advance。
     * @author owlzhangfq@gmail.com
     */
    public record Command(String tenantId, String sourceKey, String eventType, int envelopeVersion, String eventId,
                          UUID applicationId, int roundNo, String waitId, String contractKey, long contractVersion) { }
    /** @author owlzhangfq@gmail.com */
    public record Entry(String waitId, String nodeId, String nodeName, String contractKey, long contractVersion, Instant createdAt,
                        boolean suspended, boolean contractEnabled) { }
    /** @author owlzhangfq@gmail.com */
    public record View(UUID applicationId, int roundNo, long applicationVersion, List<Entry> items) { }
}
