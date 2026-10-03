package io.agentflow.event;

import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * 收件处理状态只描述这一条已认证事件，不能替代申请和原生等待状态。
 * @author owlzhangfq@gmail.com
 */
public record EventInboxItem(UUID id, ReceivedEvent input, long version, Status status, Instant receivedAt,
                             Instant updatedAt, Instant nextAttemptAt, int failures, Reason reason, String errorCode,
                             String requestedBy, String requestReason) {
    public static final int MAX_FAILURES = 10;
    private static final long WAIT_SECONDS = 30;
    private static final long MAX_BACKOFF_SECONDS = 300;
    private static final String PROCESSING_ERROR = "EVENT_PROCESSING_FAILED";
    private static final Set<Reason> WAIT_REASONS = Set.of(Reason.PAUSED, Reason.CONTRACT_DISABLED, Reason.SOURCE_DISABLED);

    /** 持久状态要求期限、故障及人工恢复身份与状态一致。 */
    public EventInboxItem {
        if (id == null || input == null || version < 1 || status == null || receivedAt == null || updatedAt == null
                || updatedAt.isBefore(receivedAt) || failures < 0 || failures > MAX_FAILURES
                || (status == Status.RECEIVED || status == Status.WAITING) != (nextAttemptAt != null)
                || nextAttemptAt != null && nextAttemptAt.isBefore(updatedAt)
                || (reason == Reason.PROCESSING_FAILED) != (errorCode != null)
                || errorCode != null && !PROCESSING_ERROR.equals(errorCode)
                || (requestedBy == null) != (requestReason == null)
                || requestedBy != null && (requestedBy.isBlank() || requestedBy.length() > 128 || requestReason.isBlank() || requestReason.length() > 500)) {
            throw new IllegalStateException("Invalid persisted event inbox state");
        }
        boolean validReason = switch (status) {
            case RECEIVED -> reason == null;
            case WAITING -> reason != null && (WAIT_REASONS.contains(reason) || reason == Reason.PROCESSING_FAILED);
            case CONSUMED -> reason == Reason.MATCHED;
            case IGNORED -> reason == Reason.TARGET_STALE || reason == Reason.CONTRACT_MISMATCH;
            case REVIEW_REQUIRED -> reason == Reason.SOURCE_CHANGED || reason == Reason.PROCESSING_FAILED;
        };
        if (!validReason) throw new IllegalStateException("Invalid event inbox reason");
    }

    /** 首次接收只持久化信号，实际推进留给后台短事务。 */
    public static EventInboxItem receive(ReceivedEvent input, Instant now) {
        return new EventInboxItem(UUID.randomUUID(), input, 1, Status.RECEIVED, now, now, now, 0, null, null, null, null);
    }
    /** 原等待暂停或来源暂不可用时保留原件，恢复后仍按原身份判断。 */
    public EventInboxItem waitFor(Reason issue, Instant now) {
        if (!WAIT_REASONS.contains(issue)) throw conflict();
        return changed(Status.WAITING, now, now.plusSeconds(WAIT_SECONDS), failures, issue, null);
    }
    /** 原生等待确已推进后才能在同一事务内确认消费。 */
    public EventInboxItem consumed(Instant now) { return changed(Status.CONSUMED, now, null, failures, Reason.MATCHED, null); }
    /** 旧激活或契约不匹配是终态，不允许管理员把它改成新的等待。 */
    public EventInboxItem ignored(Reason issue, Instant now) {
        if (issue != Reason.TARGET_STALE && issue != Reason.CONTRACT_MISMATCH) throw conflict();
        return changed(Status.IGNORED, now, null, failures, issue, null);
    }
    /** 信任修订不同需要人工检查，恢复也必须重新匹配原配置修订。 */
    public EventInboxItem sourceChanged(Instant now) { return changed(Status.REVIEW_REQUIRED, now, null, failures, Reason.SOURCE_CHANGED, null); }
    /** 推进已回滚后记录有界退避，达到上限后停止自动重试。 */
    public EventInboxItem failed(Instant now) {
        int count = Math.min(MAX_FAILURES, failures + 1);
        return changed(count == MAX_FAILURES ? Status.REVIEW_REQUIRED : Status.WAITING, now,
                count == MAX_FAILURES ? null : now.plusSeconds(Math.min(MAX_BACKOFF_SECONDS, 5L << (count - 1))), count, Reason.PROCESSING_FAILED, PROCESSING_ERROR);
    }
    /** 明确人工恢复只重新排队原信号，旧请求与原身份都不会被替换。 */
    public EventInboxItem retry(String actor, String explanation, Instant now) {
        if (status != Status.REVIEW_REQUIRED || now.isBefore(updatedAt)) throw conflict();
        return new EventInboxItem(id, input, Math.incrementExact(version), Status.RECEIVED, receivedAt, now, now, 0, null, null, actor, explanation);
    }
    public boolean pending() { return status == Status.RECEIVED || status == Status.WAITING; }
    private EventInboxItem changed(Status next, Instant now, Instant due, int count, Reason issue, String error) {
        if (!pending() || now.isBefore(updatedAt)) throw conflict();
        return new EventInboxItem(id, input, Math.incrementExact(version), next, receivedAt, now, due, count, issue, error, requestedBy, requestReason);
    }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Event processing state changed"); }
    /** @author owlzhangfq@gmail.com */
    public enum Status { RECEIVED, WAITING, CONSUMED, IGNORED, REVIEW_REQUIRED }
    /** @author owlzhangfq@gmail.com */
    public enum Reason { MATCHED, PAUSED, CONTRACT_DISABLED, SOURCE_DISABLED, SOURCE_CHANGED, TARGET_STALE, CONTRACT_MISMATCH, PROCESSING_FAILED }
}
