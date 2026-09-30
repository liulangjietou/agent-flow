package io.agentflow.finance.callback;

import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.UUID;

/**
 * 已认证回调的持久接收事实；处理完成只表示原交易查询已登记，不表示资金成功。
 * @author owlzhangfq@gmail.com
 */
public record PaymentCallback(UUID id, PaymentCallbackVerifier.Verified input, long version, Status status,
                              Instant receivedAt, Instant updatedAt, Instant nextAttemptAt, int failures,
                              Long queryVersion, Reason reason, String requestedBy, String requestReason) {
    private static final int MAX_FAILURES = 10;

    /** 持久状态与处理证据保持一致，原始事件身份不可由后续处理替换。 */
    public PaymentCallback {
        if (id == null || input == null || version < 1 || status == null || receivedAt == null || updatedAt == null
                || updatedAt.isBefore(receivedAt) || failures < 0 || failures > MAX_FAILURES
                || (status == Status.RECEIVED || status == Status.WAITING) != (nextAttemptAt != null)
                || nextAttemptAt != null && nextAttemptAt.isBefore(updatedAt)
                || (status == Status.QUERY_QUEUED) != (queryVersion != null) || queryVersion != null && queryVersion < 1
                || status == Status.RECEIVED && reason != null || status != Status.RECEIVED && reason == null) {
            throw new IllegalStateException("Invalid persisted payment callback state");
        }
    }

    /** 第一次接收仅入队，任何外部请求都由现有资金工作器执行。 */
    public static PaymentCallback receive(PaymentCallbackVerifier.Verified input, Instant now) {
        return new PaymentCallback(UUID.randomUUID(), input, 1, Status.RECEIVED, now, now, now, 0, null, null, null, null);
    }
    /** 原交易仍在网络执行中，回调保留直到能够登记新的原号查询。 */
    public PaymentCallback waitForOperation(Instant now) { requirePending(); return changed(Status.WAITING, now, now.plusSeconds(5), failures, null, Reason.BUSY, requestedBy); }
    /** 同事务保存实际被唤醒的付款版本，不能把回调中的外部版本当付款版本。 */
    public PaymentCallback queried(long operationVersion, boolean alreadyObserved, Instant now) {
        requirePending();
        return changed(Status.QUERY_QUEUED, now, null, failures, operationVersion,
                alreadyObserved ? Reason.ALREADY_OBSERVED : Reason.QUERY_REQUESTED, requestedBy);
    }
    /** 无法安全自动处理的事件仍可见，不能通过丢弃事件冒充处理成功。 */
    public PaymentCallback review(Reason issue, Instant now) { requirePending(); return changed(Status.REVIEW_REQUIRED, now, null, failures, null, issue, requestedBy); }
    /** 本地处理失败有界退避；达到上限后等待管理员检查及明确重试。 */
    public PaymentCallback failed(Instant now) {
        requirePending();
        int count = Math.min(MAX_FAILURES, failures + 1);
        return changed(count == MAX_FAILURES ? Status.REVIEW_REQUIRED : Status.WAITING, now,
                count == MAX_FAILURES ? null : now.plusSeconds(Math.min(300, 5L << (count - 1))), count, null, Reason.PROCESSING_FAILED, requestedBy);
    }
    /** 管理员只能重新处理原信号，不修改原事件或发送新付款。 */
    public PaymentCallback retry(String actor, String explanation, Instant now) {
        if (status != Status.REVIEW_REQUIRED) throw conflict();
        return new PaymentCallback(id, input, Math.incrementExact(version), Status.RECEIVED, receivedAt, now, now, 0, null, null, actor, explanation);
    }
    public boolean pending() { return status == Status.RECEIVED || status == Status.WAITING; }
    private void requirePending() { if (!pending()) throw conflict(); }
    private PaymentCallback changed(Status next, Instant now, Instant due, int count, Long queried, Reason issue, String actor) {
        if (now.isBefore(updatedAt)) throw conflict();
        return new PaymentCallback(id, input, Math.incrementExact(version), next, receivedAt, now, due, count, queried, issue, actor, requestReason);
    }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Payment callback processing state changed"); }

    /**
     * 状态描述回调投递处理，不描述银行交易结果。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { RECEIVED, WAITING, QUERY_QUEUED, REVIEW_REQUIRED }
    /**
     * 稳定故障分类不包含财务明细、原始响应或凭据。
     * @author owlzhangfq@gmail.com
     */
    public enum Reason { QUERY_REQUESTED, ALREADY_OBSERVED, BUSY, NEVER_DISPATCHED, TARGET_CHANGED, SOURCE_MISSING, PROCESSING_FAILED }
}
