package io.agentflow.approval.process;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.TaskAction;
import io.agentflow.approval.model.TaskDelegation;
import io.agentflow.approval.model.CountersignProgress;
import io.agentflow.approval.service.TaskRecipientDirectory;
import org.flowable.task.api.DelegationState;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.approval.service.ProcessRuntimePort;
import io.agentflow.approval.service.TaskAuditPort;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.notification.ApprovalNotificationService;
import io.agentflow.expense.ExpenseApprovalService;
import io.agentflow.expense.ExpenseReleaseService;
import io.agentflow.expense.ExpensePlanApprovalService;
import io.agentflow.expense.AdvanceRequestApprovalService;
import io.agentflow.procurement.ProcurementPaymentApprovalService;
import io.agentflow.procurement.ProcurementPayableReservations;
import io.agentflow.finance.VoucherPreparationService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * 任务应用服务，集中执行租户、候选关系和乐观版本校验，再调用流程防腐层。
 * @author owlzhangfq@gmail.com
 */
@Service
public class FlowableTaskFacade {
    private final TaskService taskService;
    private final TaskRecipientDirectory recipients;
    private final CurrentActor currentActor;
    private final ApplicationRepository applicationRepository;
    private final ProcessRuntimePort processRuntime;
    private final TaskAuditPort auditPort;
    private final SubmissionRoundRepository rounds;
    private final ApprovalNotificationService notifications;
    private final FlowableTaskAuthorization authorization;
    private final ExpenseApprovalService expenses;
    private final ExpenseReleaseService expenseReleases;
    private final ExpensePlanApprovalService expensePlans;
    private final AdvanceRequestApprovalService advanceRequests;
    private final ProcurementPaymentApprovalService procurementPayments;
    private final ProcurementPayableReservations procurementReservations;
    private final VoucherPreparationService voucherPreparation;

    /** 创建任务服务。 */
    public FlowableTaskFacade(TaskService taskService, CurrentActor currentActor,
                              ApplicationRepository applicationRepository, ProcessRuntimePort processRuntime,
                              TaskAuditPort auditPort, SubmissionRoundRepository rounds, TaskRecipientDirectory recipients,
                              ApprovalNotificationService notifications, FlowableTaskAuthorization authorization,
                              ExpenseApprovalService expenses, ExpenseReleaseService expenseReleases, ExpensePlanApprovalService expensePlans,
                              AdvanceRequestApprovalService advanceRequests, VoucherPreparationService voucherPreparation,
                              ProcurementPaymentApprovalService procurementPayments, ProcurementPayableReservations procurementReservations) {
        this.taskService = taskService;
        this.recipients = recipients;
        this.currentActor = currentActor;
        this.applicationRepository = applicationRepository;
        this.processRuntime = processRuntime;
        this.auditPort = auditPort;
        this.rounds = rounds;
        this.notifications = notifications;
        this.authorization = authorization; this.expenses = expenses; this.expenseReleases = expenseReleases;
        this.expensePlans = expensePlans;
        this.advanceRequests = advanceRequests;
        this.procurementPayments = procurementPayments;
        this.procurementReservations = procurementReservations;
        this.voucherPreparation = voucherPreparation;
    }

    /** 只返回当前主体可领取或已指派给自己的待办。 */
    public List<TaskView> list(String status) {
        Actor actor = currentActor.actor();
        // Flowable 任务租户字段在部分版本不会随流程变量传播；租户边界统一由 canAct 的受控变量校验保证。
        var query = taskService.createTaskQuery().processVariableValueEquals("tenantId", actor.tenantId()).includeProcessVariables().includeIdentityLinks();
        String normalizedStatus = status == null || status.isBlank() ? "PENDING" : status.toUpperCase(Locale.ROOT);
        if (!"PENDING".equals(normalizedStatus)) {
            throw new DomainException("INVALID_REQUEST", "Only pending task status is supported");
        }
        query.active();
        if (!actor.hasRole("APPROVER") || !recipients.eligible(actor.tenantId(), actor.userId())) return List.of();
        return query.list().stream().filter(task -> authorization.canAct(actor, task)).map(task -> view(actor, task)).toList();
    }

    /** 按标识重新取得可操作任务，打开列表或旧消息时不依赖全量队列。 */
    public TaskView get(String taskId) {
        return get(taskId, currentActor.actor());
    }

    /** 后台摘要复核已认证的发起主体，继续使用当前任务与组织事实，不修改线程认证上下文。 */
    public TaskView get(String taskId, Actor actor) {
        actor.requireRole("APPROVER");
        Task task = authorization.require(taskId, actor);
        if (task.isSuspended() || authorization.application(actor, task).status() != ApplicationStatus.IN_APPROVAL) {
            throw new DomainException("NOT_FOUND", "Active task not found");
        }
        return view(actor, task);
    }

    private TaskView view(Actor actor, Task task) {
        Application application = authorization.application(actor, task);
        CountersignProgress countersign = countersign(task);
        return new TaskView(task.getId(), task.getName(), task.getAssignee(), application.id().toString(), task.getCreateTime(),
                application.version(), task.getOwner(), task.getDelegationState() == null ? "NONE" : task.getDelegationState().name(),
                delegation(task).allowedActions(task.getAssignee() != null).stream()
                        .filter(action -> countersign == null || countersign.allows(action)).toList(), countersign,
                task.getDueDate() == null ? null : task.getDueDate().toInstant());
    }

    private CountersignProgress countersign(Task task) {
        var variables = taskService.getVariables(task.getId(), List.of(FlowableCountersignMembers.MEMBERS,
                FlowableCountersignMembers.TOTAL, FlowableCountersignMembers.COMPLETED));
        if (!variables.containsKey(FlowableCountersignMembers.MEMBERS)) return null;
        return new CountersignProgress(((Number) variables.get(FlowableCountersignMembers.TOTAL)).intValue(),
                ((Number) variables.get(FlowableCountersignMembers.COMPLETED)).intValue());
    }

    /** 执行动作；负向决定直接终止实例，避免流程继续流转。 */
    @Transactional
    public ActionResult action(String taskId, String action, String comment, String targetUser, Long expectedVersion) {
        Actor actor = currentActor.actor();
        actor.requireRole("APPROVER");
        if (expectedVersion == null) {
            throw new DomainException("INVALID_REQUEST", "expectedVersion is required");
        }
        Task task = authorization.require(taskId, actor);
        Application application = authorization.application(actor, task);
        expenses.lock(application);
        expensePlans.lock(application);
        advanceRequests.lock(application);
        procurementPayments.lock(application);
        task = authorization.require(taskId, actor);
        application = authorization.application(actor, task);
        ApplicationStatus previousStatus = application.status();
        TaskAction normalized = TaskAction.parse(action);
        delegation(task).requireAction(normalized);
        CountersignProgress countersign = countersign(task);
        if (countersign != null) countersign.requireAction(normalized);
        var previousTaskIds = normalized == TaskAction.APPROVE ? notifications.pendingTaskIds(application) : java.util.Set.<String>of();
        String auditEventId;
        switch (normalized) {
            case CLAIM -> {
                taskService.claim(taskId, actor.userId());
                application.recordTaskAction(expectedVersion);
                applicationRepository.update(application, expectedVersion);
                auditEventId = audit(task, application, actor, normalized.name(), comment, null, previousStatus);
            }
            case RELEASE -> {
                requireAssignee(task, actor);
                taskService.unclaim(taskId);
                application.recordTaskAction(expectedVersion);
                applicationRepository.update(application, expectedVersion);
                auditEventId = audit(task, application, actor, normalized.name(), comment, null, previousStatus);
            }
            case TRANSFER -> {
                requireTarget(actor, targetUser);
                // 转交后原委派关系结束，不能让下一次委派沿用陈旧的 owner。
                task.setOwner(null);
                task.setDelegationState(null);
                task.setAssignee(targetUser);
                taskService.saveTask(task);
                application.recordTaskAction(expectedVersion);
                applicationRepository.update(application, expectedVersion);
                auditEventId = audit(task, application, actor, normalized.name(), comment, targetUser, previousStatus);
            }
            case DELEGATE -> {
                requireTarget(actor, targetUser);
                // 候选组任务还没有 assignee，显式记录实际发起委派的责任人。
                taskService.setOwner(taskId, actor.userId());
                taskService.delegateTask(taskId, targetUser);
                application.recordTaskAction(expectedVersion);
                applicationRepository.update(application, expectedVersion);
                auditEventId = audit(task, application, actor, normalized.name(), comment, targetUser, previousStatus);
            }
            case RESOLVE -> {
                requireComment(comment);
                application.recordTaskAction(expectedVersion);
                taskService.addComment(taskId, task.getProcessInstanceId(), comment);
                taskService.resolveTask(taskId);
                applicationRepository.update(application, expectedVersion);
                auditEventId = audit(task, application, actor, normalized.name(), comment, task.getOwner(), previousStatus);
            }
            case REJECT, RETURN -> {
                requireComment(comment);
                if (normalized == TaskAction.RETURN) {
                    application.returnToApplicant(expectedVersion);
                } else {
                    application.reject(expectedVersion);
                }
                recordDecisionAssignee(task, actor);
                taskService.addComment(task.getId(), task.getProcessInstanceId(), comment);
                processRuntime.terminate(new ProcessRuntimePort.TerminateProcessCommand(
                        actor.tenantId(), task.getProcessInstanceId(), normalized + " by " + actor.userId()));
                applicationRepository.update(application, expectedVersion);
                completeRound(task, application, actor, comment);
                auditEventId = audit(task, application, actor, normalized.name(), comment, null, previousStatus);
            }
            case APPROVE -> {
                expenses.requireApproval(application, task);
                application.recordTaskAction(expectedVersion);
                recordDecisionAssignee(task, actor);
                ProcessRuntimePort.CompletedTask completed = processRuntime.complete(
                        new ProcessRuntimePort.CompleteTaskCommand(actor.tenantId(), taskId, normalized.name(), comment));
                if (completed.processEnded()) {
                    application.approve(application.version());
                    completeRound(task, application, actor, comment);
                }
                applicationRepository.update(application, expectedVersion);
                expensePlans.approved(application, actor.userId());
                advanceRequests.approved(application, actor.userId());
                procurementPayments.approved(application, actor.userId());
                voucherPreparation.approved(application, actor.userId());
                auditEventId = audit(task, application, actor, normalized.name(), comment, null, previousStatus);
            }
            default -> throw new DomainException("INVALID_REQUEST", "Unsupported task action");
        }
        if (normalized == TaskAction.REJECT) {
            expenseReleases.release(application, actor.userId(), Instant.now());
            procurementReservations.releaseStopped(application, actor.userId(), Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        }
        notifications.taskActed(application, actor.userId(), normalized, taskId, task.getName(), previousTaskIds);
        return new ActionResult(taskId, normalized.name(), application.status().name(), application.version(), auditEventId);
    }

    /** 接收人选择只对当前可操作任务开放，执行时再次按同一权威目录复核。 */
    public List<String> recipients(String taskId) {
        Actor actor = currentActor.actor();
        actor.requireRole("APPROVER");
        Task task = authorization.require(taskId, actor);
        delegation(task).requireAction(TaskAction.TRANSFER);
        return recipients.approvers(actor.tenantId()).stream().filter(user -> !actor.userId().equals(user)).toList();
    }

    private TaskDelegation delegation(Task task) {
        return new TaskDelegation(task.getOwner(), task.getDelegationState() == DelegationState.PENDING);
    }

    /** 候选人直接决策时记录实际处理人，使任务结束后的历史参与者授权仍可追溯。 */
    private void recordDecisionAssignee(Task task, Actor actor) {
        if (task.getAssignee() == null) {
            taskService.claim(task.getId(), actor.userId());
        }
    }

    private void completeRound(Task task, Application application, Actor actor, String reason) {
        rounds.complete(actor.tenantId(), application.id(), application.roundNo(), task.getProcessInstanceId(),
                SubmissionRound.Status.valueOf(application.status().name()), reason, actor.userId(), Instant.now());
    }

    private void requireAssignee(Task task, Actor actor) {
        if (!actor.userId().equals(task.getAssignee())) {
            throw new DomainException("FORBIDDEN", "Only the current assignee can release a task");
        }
    }

    private void requireTarget(Actor actor, String targetUser) {
        if (targetUser == null || targetUser.isBlank()) throw new DomainException("INVALID_REQUEST", "targetUser is required");
        if (actor.userId().equals(targetUser) || !recipients.approvers(actor.tenantId()).contains(targetUser)) {
            throw new DomainException("INVALID_TASK_RECIPIENT", "The recipient must be another active approver in this tenant");
        }
    }

    private void requireComment(String comment) {
        if (comment == null || comment.isBlank()) {
            throw new DomainException("DOMAIN_RULE_VIOLATION", "A reason is required");
        }
    }

    private String audit(Task task, Application application, Actor actor, String action, String comment, String targetUser,
                         ApplicationStatus previousStatus) {
        return auditPort.record(new TaskAuditPort.TaskOperation(actor.tenantId(), task.getId(), application.id(),
                application.version(), application.roundNo(), task.getProcessInstanceId(), actor.userId(), action,
                comment, targetUser, task.getTaskDefinitionKey(), task.getName(), previousStatus, application.status()));
    }

    /**
     * 待办视图。
     * @author owlzhangfq@gmail.com
     */
    public record TaskView(String taskId, String taskName, String assignee, String applicationId,
                           java.util.Date createdAt, long version, String owner, String delegationState,
                           List<TaskAction> allowedActions, CountersignProgress countersign, Instant dueAt) { }

    /**
     * 动作结果。
     * @author owlzhangfq@gmail.com
     */
    public record ActionResult(String taskId, String action, String applicationStatus, long version,
                               String auditEventId) { }
}
