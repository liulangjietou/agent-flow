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

    /** 撤回前保留当前处理人，避免实例终止后无法定位需要通知的账号。 */
    public List<TaskAudiencePort.Audience> beforeWithdrawal(Application application) {
        return audience.pending(application.tenantId(), application.id());
    }

    /** 撤回通知不赋予历史候选人新的申请读取权限。 */
    public void withdrawn(Application application, String actor, List<TaskAudiencePort.Audience> previous) {
        send(application, actor, application.createdBy(), Kind.APPLICATION_WITHDRAWN, null, null);
        for (var task : previous) for (String user : task.recipients()) {
            if (!user.equals(application.createdBy())) send(application, actor, user, Kind.APPLICATION_WITHDRAWN, task.taskId(), task.nodeName());
        }
    }

    /** 系统退回没有人工任务编号，通知沿用真实申请轮次及系统身份。 */
    public void returned(Application application, String actor) {
        send(application, actor, application.createdBy(), Kind.APPLICATION_RETURNED, null, null);
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

    /** 通知内容取已成功执行后的事实；领取不重复提醒，释放提醒恢复的候选人。 */
    public void taskActed(Application application, String actor, TaskAction action, String taskId, String nodeName,
                          Set<String> previousTaskIds) {
        switch (action) {
            case APPROVE -> {
                if (application.status() == ApplicationStatus.APPROVED) {
                    send(application, actor, application.createdBy(), Kind.APPLICATION_APPROVED, taskId, nodeName);
                } else pending(application, actor, Kind.TASK_PENDING, task -> !previousTaskIds.contains(task.taskId()));
            }
            case RETURN -> send(application, actor, application.createdBy(), Kind.APPLICATION_RETURNED, taskId, nodeName);
            case REJECT -> send(application, actor, application.createdBy(), Kind.APPLICATION_REJECTED, taskId, nodeName);
            case TRANSFER -> pending(application, actor, Kind.TASK_TRANSFERRED, task -> taskId.equals(task.taskId()));
            case DELEGATE -> pending(application, actor, Kind.TASK_DELEGATED, task -> taskId.equals(task.taskId()));
            case RESOLVE -> pending(application, actor, Kind.TASK_RESOLVED, task -> taskId.equals(task.taskId()));
            case RELEASE -> pending(application, actor, Kind.TASK_PENDING, task -> taskId.equals(task.taskId()));
            case CLAIM -> { /* 领取不生成新提醒，原消息仍保留其发生时事实。 */ }
        }
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

    private void send(Application application, String actor, String recipient, Kind kind, String taskId, String nodeName) {
        String eventKey = application.id() + ":" + application.version() + ":" + kind + ":" + (taskId == null ? "" : taskId);
        UUID id = UUID.nameUUIDFromBytes((application.tenantId() + ":" + eventKey + ":" + recipient).getBytes(StandardCharsets.UTF_8));
        inbox.append(eventKey, new InboxMessage(id, application.tenantId(), recipient, application.id(), application.title(),
                application.businessNo(), kind, actor, taskId, nodeName, application.roundNo(), Instant.now(), null,
                application.notificationTexts().forEvent(kind)));
    }
}
