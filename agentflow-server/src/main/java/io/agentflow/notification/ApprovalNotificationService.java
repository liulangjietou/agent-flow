package io.agentflow.notification;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.TaskAction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.UUID;
import static io.agentflow.notification.InboxMessage.Kind;

/**
 * 审批到通知的应用编排：在原业务事务内保存站内消息，不调用外部发送渠道。
 * @author owlzhangfq@gmail.com
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ApprovalNotificationService {
    private final InboxRepository inbox;
    private final TaskAudiencePort audience;

    /** 注入消息仓储和实际任务接收人端口。 */
    public ApprovalNotificationService(InboxRepository inbox, TaskAudiencePort audience) {
        this.inbox = inbox;
        this.audience = audience;
    }

    /** 提交成功后给申请人确认，并提醒实际第一批审批人。 */
    public void submitted(Application application, String actor) {
        send(application, actor, application.createdBy(), Kind.APPLICATION_SUBMITTED, null, null);
        pending(application, actor, Kind.TASK_PENDING, task -> true);
    }

    /** 审批前记录已有任务，审批成功后只提醒本次实际新建的待办。 */
    public Set<String> pendingTaskIds(Application application) {
        return audience.pending(application.tenantId(), application.id()).stream()
                .map(TaskAudiencePort.Audience::taskId).collect(Collectors.toSet());
    }

    /** 状态变更前保留当前处理人，暂停或终止后仍可通知原办理人员。 */
    public List<TaskAudiencePort.Audience> pendingAudience(Application application) {
        return audience.pending(application.tenantId(), application.id());
    }

    /** 终止前包含暂停任务，避免原审批人漏收本轮结束通知。 */
    public List<TaskAudiencePort.Audience> unfinishedAudience(Application application) {
        return audience.unfinished(application.tenantId(), application.id());
    }

    /** 具名终止通知使用实际取消结论，不复制操作原因或新增读取授权。 */
    public void instanceTerminated(Application application, String actor, List<TaskAudiencePort.Audience> previous) {
        instanceChanged(application, actor, Kind.APPLICATION_CANCELLED, previous);
    }

    /** 撤回通知不赋予历史候选人新的申请读取权限。 */
    public void withdrawn(Application application, String actor, List<TaskAudiencePort.Audience> previous) {
        send(application, actor, application.createdBy(), Kind.APPLICATION_WITHDRAWN, null, null);
        for (var task : previous) for (String user : task.recipients()) {
            if (!user.equals(application.createdBy())) send(application, actor, user, Kind.APPLICATION_WITHDRAWN, task.taskId(), task.nodeName());
        }
    }

    /** 暂停通知保留实际接收人；入口指向申请，暂停任务不再提供办理操作。 */
    public void instancePaused(Application application, String actor, List<TaskAudiencePort.Audience> previous) {
        instanceChanged(application, actor, Kind.APPLICATION_PAUSED, previous);
    }

    /** 恢复按当前有效人员重新解析通知，不将被停用人员重新加入办理范围。 */
    public void instanceResumed(Application application, String actor) {
        instanceChanged(application, actor, Kind.APPLICATION_RESUMED, audience.pending(application.tenantId(), application.id()));
    }

    private void instanceChanged(Application application, String actor, Kind kind, List<TaskAudiencePort.Audience> pending) {
        var recipients = new java.util.HashSet<String>(); recipients.add(application.createdBy());
        for (var task : pending) recipients.addAll(task.recipients());
        for (String recipient : recipients) send(application, actor, recipient, kind, null, null);
    }

    /** 系统退回没有人工任务编号，通知沿用真实申请轮次及系统身份。 */
    public void returned(Application application, String actor) {
        send(application, actor, application.createdBy(), Kind.APPLICATION_RETURNED, null, null);
    }

    /** 父子联动只发送本申请实际结论；源操作的申请人消息仍由原用例负责，避免重复通知。 */
    public void subprocessStopped(Application application, String actor, List<TaskAudiencePort.Audience> previous, boolean includeApplicant) {
        Kind kind = switch (application.status()) {
            case RETURNED -> Kind.APPLICATION_RETURNED;
            case REJECTED -> Kind.APPLICATION_REJECTED;
            case CANCELLED -> Kind.APPLICATION_CANCELLED;
            default -> throw new IllegalStateException("Unsupported subprocess stop conclusion");
        };
        var recipients = previous.stream().flatMap(task -> task.recipients().stream()).collect(Collectors.toSet());
        if (includeApplicant) recipients.add(application.createdBy());
        else recipients.remove(application.createdBy());
        recipients.remove(actor);
        for (String recipient : recipients) send(application, actor, recipient, kind, null, null);
    }

    /** 核减通知只提供入口，逐行差额通过财务权限查询展示，不在消息中复制敏感金额。 */
    public void expenseAdjusted(Application application, String actor) {
        send(application, actor, application.createdBy(), Kind.EXPENSE_ADJUSTED, null, null);
    }

    /** 加签只通知本次实际新增待办，已有会签人不重复收到提醒。 */
    public void countersignAdded(Application application, String actor, String targetTaskId) {
        pending(application, actor, Kind.TASK_PENDING, task -> targetTaskId.equals(task.taskId()));
    }

    /** 减签通知保留原任务入口，不复制原因、名单或表单，也不授予新的读取权限。 */
    public void countersignRemoved(Application application, String actor, String targetUser, String targetTaskId, String nodeName) {
        send(application, actor, targetUser, Kind.TASK_COUNTERSIGN_REMOVED, targetTaskId, nodeName);
    }

    /** 达标前保留同一节点其他实际待办的接收人，不能把另一个并行节点当成取消目标。 */
    public List<TaskAudiencePort.Audience> beforeCountersignCompletion(Application application, Set<String> scopeTaskIds) {
        return audience.pending(application.tenantId(), application.id()).stream().filter(task -> scopeTaskIds.contains(task.taskId())).toList();
    }

    /** 只通知本次实际结束的其他待办；消息不表示这些人员已作出批准意见。 */
    public void countersignCompleted(Application application, String actor, List<TaskAudiencePort.Audience> previous) {
        if (previous.isEmpty()) return;
        Set<String> active = pendingTaskIds(application);
        for (var task : previous) if (!active.contains(task.taskId())) {
            for (String recipient : task.recipients()) send(application, actor, recipient, Kind.TASK_COUNTERSIGN_COMPLETED, task.taskId(), task.nodeName());
        }
    }

    /** 通知内容取已成功执行后的事实；领取不重复提醒，释放提醒恢复的候选人。 */
    public void taskActed(Application application, String actor, TaskAction action, String taskId, String nodeName,
                          Set<String> previousTaskIds) {
        switch (action) {
            case APPROVE -> processAdvanced(application, actor, taskId, nodeName, previousTaskIds);
            case RETURN -> send(application, actor, application.createdBy(), Kind.APPLICATION_RETURNED, taskId, nodeName);
            case REJECT -> send(application, actor, application.createdBy(), Kind.APPLICATION_REJECTED, taskId, nodeName);
            case TRANSFER -> pending(application, actor, Kind.TASK_TRANSFERRED, task -> taskId.equals(task.taskId()));
            case DELEGATE -> pending(application, actor, Kind.TASK_DELEGATED, task -> taskId.equals(task.taskId()));
            case RESOLVE -> pending(application, actor, Kind.TASK_RESOLVED, task -> taskId.equals(task.taskId()));
            case RELEASE -> pending(application, actor, Kind.TASK_PENDING, task -> taskId.equals(task.taskId()));
            case CLAIM -> { /* 领取不生成新提醒，原消息仍保留其发生时事实。 */ }
        }
    }

    /** 人工完成或等待推进后只发送实际新待办或最终结论；系统推进没有人工任务编号。 */
    public void processAdvanced(Application application, String actor, String taskId, String nodeName, Set<String> previousTaskIds) {
        if (application.status() == ApplicationStatus.APPROVED) {
            send(application, actor, application.createdBy(), Kind.APPLICATION_APPROVED, taskId, nodeName);
        } else pending(application, actor, Kind.TASK_PENDING, task -> !previousTaskIds.contains(task.taskId()));
    }

    private void pending(Application application, String actor, Kind kind, Predicate<TaskAudiencePort.Audience> affected) {
        for (var task : audience.pending(application.tenantId(), application.id())) {
            if (!affected.test(task)) continue;
            for (String user : task.recipients()) send(application, actor, user, kind, task.taskId(), task.nodeName());
        }
    }

    /** 超时只提醒当时实际处理人；同一任务的事件标识不随申请版本或调度重试变化。 */
    public boolean overdue(Application application, String taskId, Instant occurredAt) {
        var task = audience.pending(application.tenantId(), application.id()).stream()
                .filter(item -> item.taskId().equals(taskId)).findFirst();
        if (task.isEmpty() || task.get().recipients().isEmpty()) return false;
        String eventKey = "task-overdue:" + taskId;
        for (String recipient : task.get().recipients()) {
            UUID id = UUID.nameUUIDFromBytes((application.tenantId() + ":" + eventKey + ":" + recipient).getBytes(StandardCharsets.UTF_8));
            inbox.append(eventKey, new InboxMessage(id, application.tenantId(), recipient, application.id(), application.title(),
                    application.businessNo(), Kind.TASK_OVERDUE, "system:sla", taskId, task.get().nodeName(),
                    application.roundNo(), occurredAt, null, "审批任务已超过处理期限，请查看当前待办。"));
        }
        return true;
    }

    /** 升级对象只收到协调提醒；不新增任务参与、抄送或表单授权事实。 */
    public void escalated(Application application, String taskId, String nodeName, java.util.List<String> recipients, Instant occurredAt) {
        String eventKey = "task-escalated:" + taskId;
        for (String recipient : recipients) {
            UUID id = UUID.nameUUIDFromBytes((application.tenantId() + ":" + eventKey + ":" + recipient).getBytes(StandardCharsets.UTF_8));
            inbox.append(eventKey, new InboxMessage(id, application.tenantId(), recipient, application.id(), application.title(),
                    application.businessNo(), Kind.TASK_ESCALATED, "system:sla", taskId, nodeName, application.roundNo(), occurredAt, null,
                    "审批任务持续超时，请协调原审批人员处理。升级提醒不增加申请读取或审批权限。"));
        }
    }

    private void send(Application application, String actor, String recipient, Kind kind, String taskId, String nodeName) {
        String eventKey = application.id() + ":" + application.version() + ":" + kind + ":" + (taskId == null ? "" : taskId);
        UUID id = UUID.nameUUIDFromBytes((application.tenantId() + ":" + eventKey + ":" + recipient).getBytes(StandardCharsets.UTF_8));
        inbox.append(eventKey, new InboxMessage(id, application.tenantId(), recipient, application.id(), application.title(),
                application.businessNo(), kind, actor, taskId, nodeName, application.roundNo(), Instant.now(), null,
                application.notificationTexts().forEvent(kind)));
    }
}
