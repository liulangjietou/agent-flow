package io.agentflow.notification;

import java.time.Instant;
import java.util.UUID;

/**
 * 单个接收人的站内消息；业务事实不可覆盖，已读时间只记录第一次阅读。
 * @author owlzhangfq@gmail.com
 */
public record InboxMessage(UUID id, String tenantId, String recipient, UUID applicationId, String title,
                           String businessNo, Kind kind, String actor, String taskId, String nodeName,
                           int roundNo, Instant createdAt, Instant readAt, String content) {
    /** 历史消息未记录文案时保持缺失，不能从当前配置补写。 */
    public InboxMessage(UUID id, String tenantId, String recipient, UUID applicationId, String title,
                        String businessNo, Kind kind, String actor, String taskId, String nodeName,
                        int roundNo, Instant createdAt, Instant readAt) {
        this(id, tenantId, recipient, applicationId, title, businessNo, kind, actor, taskId, nodeName,
                roundNo, createdAt, readAt, null);
    }
    /** 重复标记保持第一次阅读时间，不改变消息对应的业务事实。 */
    public InboxMessage markRead(Instant time) {
        if (readAt != null) return this;
        return new InboxMessage(id, tenantId, recipient, applicationId, title, businessNo, kind, actor,
                taskId, nodeName, roundNo, createdAt, time, content);
    }

    /**
     * 站内消息只表示已经提交的业务事实，不表示接收人仍然拥有任务操作权。
     * @author owlzhangfq@gmail.com
     */
    public enum Kind {
        SUPPLIER_SETTLEMENT_RESULT, SUPPLIER_SETTLEMENT_ATTENTION,
        EXPENSE_SETTLEMENT_RESULT, EXPENSE_SETTLEMENT_ATTENTION,
        REVERSAL_RESULT, REVERSAL_ATTENTION, REVERSAL_CHECK_RESULT, REVERSAL_CHECK_ATTENTION,
        BUDGET_RESULT, BUDGET_ATTENTION,
        VOUCHER_RESULT, VOUCHER_ATTENTION,
        SUPPLIER_PAYMENT_RESULT, SUPPLIER_PAYMENT_ATTENTION, PAYMENT_RESULT, PAYMENT_ATTENTION, ADVANCE_OVERDUE, TASK_ESCALATED, COMMENT_MENTIONED, APPLICATION_SUBMITTED, TASK_PENDING, APPLICATION_RETURNED, APPLICATION_REJECTED,
        APPLICATION_APPROVED, APPLICATION_WITHDRAWN, APPLICATION_CANCELLED, TASK_TRANSFERRED, TASK_DELEGATED, TASK_RESOLVED, TASK_OVERDUE, APPLICATION_COPIED, EXPENSE_ADJUSTED, TASK_COUNTERSIGN_REMOVED, TASK_COUNTERSIGN_COMPLETED, APPLICATION_PAUSED, APPLICATION_RESUMED
    }
}
