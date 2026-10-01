package io.agentflow.approval.process;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.CountersignMembership;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.approval.service.TaskAuditPort;
import io.agentflow.approval.service.TaskRecipientDirectory;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.notification.ApprovalNotificationService;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.apache.commons.lang3.StringUtils;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 当前责任人发起会签增减的应用用例；申请、引擎、审计和通知在一个事务内共同提交。
 * @author owlzhangfq@gmail.com
 */
@Service
public class CountersignMembershipService {
    private final CurrentActor actors;
    private final FlowableTaskAuthorization authorization;
    private final ApplicationRepository applications;
    private final SubmissionRoundRepository rounds;
    private final FlowableCountersignRuntime runtime;
    private final TaskRecipientDirectory recipients;
    private final TaskAuditPort audit;
    private final ApprovalNotificationService notifications;

    /** 跨聚合和外部事实由应用层编排，成员自身规则仍由领域模型判断。 */
    public CountersignMembershipService(CurrentActor actors, FlowableTaskAuthorization authorization,
            ApplicationRepository applications, SubmissionRoundRepository rounds, FlowableCountersignRuntime runtime,
            TaskRecipientDirectory recipients, TaskAuditPort audit, ApprovalNotificationService notifications) {
        this.actors = actors; this.authorization = authorization; this.applications = applications; this.rounds = rounds;
        this.runtime = runtime; this.recipients = recipients; this.audit = audit; this.notifications = notifications;
    }

    /** 成功回执重放也要求当前资格，不能要求已经结束的原任务仍然存活。 */
    public Actor requireActor() {
        Actor actor = actors.actor();
        actor.requireRole("APPROVER");
        if (!recipients.eligible(actor.tenantId(), actor.userId())) throw new DomainException("FORBIDDEN", "Current organization approval eligibility is missing");
        return actor;
    }

    /** 查询仅投影本轮实际会签成员，不写入，不携带表单或引擎变量。 */
    @Transactional(readOnly = true)
    public View read(String taskId) {
        Actor actor = actors.actor();
        Task task = authorization.require(taskId, actor);
        Application application = authorization.application(actor, task);
        requireBinding(application, task);
        var scope = runtime.read(task);
        var state = scope.membership();
        var operator = state.pending().stream().filter(member -> taskId.equals(member.taskId())).findFirst().orElseThrow(CountersignMembershipService::changed);
        boolean canChange = !operator.delegated() && operator.user().equals(actor.userId());
        var available = canChange && state.total() < CountersignMembership.MAX_MEMBERS
                ? recipients.approvers(actor.tenantId()).stream().filter(user -> !state.completedUsers().contains(user)
                    && state.pending().stream().noneMatch(member -> member.user().equals(user))).distinct().sorted().toList()
                : List.<String>of();
        var pending = state.pending().stream().sorted(Comparator.comparing(CountersignMembership.Member::taskId))
                .map(member -> new Member(member.taskId(), member.user(), member.assignee(), member.delegated(),
                        canChange && state.pending().size() > 1 && !member.taskId().equals(taskId) && !member.delegated())).toList();
        // 读取期间另一人可能刚完成审批；不返回混合了两个版本的可办理名单。
        var latest = applications.findById(actor.tenantId(), application.id()).orElseThrow(CountersignMembershipService::changed);
        if (latest.version() != application.version() || latest.status() != ApplicationStatus.IN_APPROVAL) throw changed();
        return new View(taskId, application.id(), application.roundNo(), application.version(), task.getProcessInstanceId(),
                task.getTaskDefinitionKey(), scope.executionId(), scope.originalMembers(), state.total(), state.completed(),
                pending, state.completedUsers().stream().sorted().toList(), canChange,
                operator.delegated() ? "TASK_DELEGATION_PENDING" : null, !available.isEmpty(), available);
    }

    /** 锁后重新授权并校验绑定；任何审计、通知或版本冲突都回滚引擎变更。 */
    @Transactional
    public Receipt change(String taskId, Input input) {
        Actor actor = actors.actor();
        Task initial = authorization.require(taskId, actor);
        Application found = authorization.application(actor, initial);
        Application application = applications.lockById(actor.tenantId(), found.id()).orElseThrow(CountersignMembershipService::changed);
        Task task = authorization.require(taskId, actor);
        requireBinding(application, task);
        var state = runtime.read(task).membership();
        var operator = state.pending().stream().filter(member -> taskId.equals(member.taskId())).findFirst().orElseThrow(CountersignMembershipService::changed);
        if (operator.delegated()) throw new DomainException("TASK_DELEGATION_PENDING", "Resolve delegated assistance before changing countersign responsibilities");
        if (!actor.userId().equals(operator.user()) || !actor.userId().equals(task.getAssignee())) {
            throw new DomainException("FORBIDDEN", "Only the current countersign responsibility holder can change membership");
        }
        application.recordTaskAction(input.expectedVersion());
        FlowableCountersignRuntime.Change change;
        if (input.action() == Action.ADD) {
            if (!recipients.approvers(actor.tenantId()).contains(input.targetUser())) {
                throw new DomainException("INVALID_TASK_RECIPIENT", "The selected user is not an eligible approver");
            }
            change = runtime.add(task, input.targetUser());
        } else change = runtime.remove(task, input.targetTaskId());
        applications.update(application, input.expectedVersion());
        var facts = new TaskAuditPort.MembershipChange(change.executionId(), change.targetTaskId(), change.totalBefore(), change.totalAfter(), change.completed());
        String eventId = audit.record(new TaskAuditPort.TaskOperation(actor.tenantId(), taskId, application.id(), application.version(),
                application.roundNo(), task.getProcessInstanceId(), actor.userId(), input.action() == Action.ADD ? "ADD_SIGNER" : "REMOVE_SIGNER",
                input.reason().strip(), change.targetUser(), task.getTaskDefinitionKey(), task.getName(),
                ApplicationStatus.IN_APPROVAL, ApplicationStatus.IN_APPROVAL, facts));
        if (input.action() == Action.ADD) notifications.countersignAdded(application, actor.userId(), change.targetTaskId());
        else notifications.countersignRemoved(application, actor.userId(), change.targetUser(), change.targetTaskId(), task.getName());
        return new Receipt(taskId, application.id(), application.roundNo(), application.version(), task.getProcessInstanceId(),
                task.getTaskDefinitionKey(), input.action(), change.executionId(), change.targetTaskId(), change.targetUser(),
                change.totalBefore(), change.totalAfter(), change.completed(), eventId);
    }

    private void requireBinding(Application application, Task task) {
        var round = rounds.findByRound(application.tenantId(), application.id(), application.roundNo()).orElseThrow(CountersignMembershipService::changed);
        Object number = task.getProcessVariables().get("roundNo");
        if (application.status() != ApplicationStatus.IN_APPROVAL || round.status() != SubmissionRound.Status.IN_APPROVAL
                || !application.id().toString().equals(task.getProcessVariables().get("applicationId"))
                || !(number instanceof Number n) || n.doubleValue() != application.roundNo()
                || !round.processInstanceId().equals(task.getProcessInstanceId())
                || round.definitionVersion() != application.definitionVersion()
                || !task.getProcessDefinitionId().equals(application.runtimeDefinitionId())) throw changed();
    }

    private static DomainException changed() {
        return new DomainException("CONCURRENCY_CONFLICT", "The current approval round or countersign responsibility has changed");
    }

    /**
     * 增减只改变责任集合，不表示审批同意或驳回。
     * @author owlzhangfq@gmail.com
     */
    public enum Action { ADD, REMOVE }

    /**
     * 入口只接受一类目标，所有申请、轮次、执行身份与计数由服务器恢复。
     * @author owlzhangfq@gmail.com
     */
    public record Input(@NotNull Action action, @Size(max = 128) String targetUser, @Size(max = 128) String targetTaskId,
                        @NotBlank @Size(max = 2000) String reason, @NotNull @Positive Long expectedVersion) {
        /** 拒绝混合目标，空白另一目标也不能作为第二种选人语义。 */
        @AssertTrue @JsonIgnore
        public boolean isSelectionValid() {
            return action == Action.ADD && StringUtils.isNotBlank(targetUser) && targetTaskId == null
                    || action == Action.REMOVE && StringUtils.isNotBlank(targetTaskId) && targetUser == null;
        }
        /** 防止客户端悄悄提交绑定或计数等未定义属性。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown countersign change field"); }
    }

    /**
     * 责任人与受托处理人分开展示，可减状态只适用于当前查看者。
     * @author owlzhangfq@gmail.com
     */
    public record Member(String taskId, String user, String assignee, boolean delegated, boolean canRemove) { }

    /**
     * 原选人快照与现有必要任务并列，完成成员仅来自真实同意历史。
     * @author owlzhangfq@gmail.com
     */
    public record View(String taskId, UUID applicationId, int roundNo, long applicationVersion, String processInstanceId,
                       String nodeId, String executionId, List<String> originalMembers, int total, int completed,
                       List<Member> pending, List<String> completedUsers, boolean canChange, String issue, boolean canAdd, List<String> additions) { }

    /**
     * 幂等回执保留当时变更的真实身份，任务结束后仍能核对同一次操作。
     * @author owlzhangfq@gmail.com
     */
    public record Receipt(String taskId, UUID applicationId, int roundNo, long applicationVersion, String processInstanceId,
                          String nodeId, Action action, String executionId, String targetTaskId, String targetUser,
                          int totalBefore, int totalAfter, int completed, String auditEventId) { }
}
