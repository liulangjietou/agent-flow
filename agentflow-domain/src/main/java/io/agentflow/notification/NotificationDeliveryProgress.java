package io.agentflow.notification;

import io.agentflow.common.DomainException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** 外部通知的发送事实；结果未知时不自动重发，渠道受理不表示用户已经收到。 @author owlzhangfq@gmail.com */
public record NotificationDeliveryProgress(Status status, long version, int attempts, int cycleAttempts,
                                           Instant nextAttemptAt, Instant leaseUntil, UUID leaseToken,
                                           FailureCode errorCode, Instant changedAt) {
    public static final int MAX_CYCLE_ATTEMPTS = 3;
    public static final Duration LEASE_DURATION = Duration.ofSeconds(60);
    private static final Duration FIRST_RETRY_DELAY = Duration.ofSeconds(30);
    private static final Duration SECOND_RETRY_DELAY = Duration.ofSeconds(120);

    public enum Status { PENDING, IN_FLIGHT, RETRY_WAIT, ACCEPTED, FAILED, UNKNOWN, SUPPRESSED }
    public enum FailureCode {
        CONSENT_REVOKED, RECIPIENT_INACTIVE, MESSAGE_UNAVAILABLE,
        BINDING_NOT_CAPTURED, BINDING_UNAVAILABLE, BINDING_CHANGED, CHANNEL_UNAVAILABLE,
        SMTP_CONNECT_FAILED, SMTP_AUTH_FAILED, SMTP_TEMPORARY_REJECTION, SMTP_PERMANENT_REJECTION,
        SMTP_RESULT_UNKNOWN, IM_TOKEN_UNAVAILABLE, IM_AUTH_FAILED, IM_RECIPIENT_REJECTED,
        IM_TEMPORARY_REJECTION, IM_PERMANENT_REJECTION, IM_RESULT_UNKNOWN, LEASE_EXPIRED, WORKER_RESULT_UNKNOWN
    }
    public enum Result { ACCEPTED, RETRYABLE, FAILED, UNKNOWN }

    /** 传输层只提供固定分类，不持久化服务端错误正文或地址。 */
    public record Outcome(Result result, FailureCode code) {
        public static Outcome accepted() { return new Outcome(Result.ACCEPTED, null); }
        public static Outcome retryable(FailureCode code) { return new Outcome(Result.RETRYABLE, code); }
        public static Outcome failed(FailureCode code) { return new Outcome(Result.FAILED, code); }
        public static Outcome unknown(FailureCode code) { return new Outcome(Result.UNKNOWN, code); }
    }

    /** 只为新的站内事实排队，不追溯补发旧消息。 */
    public static NotificationDeliveryProgress pending(Instant now) {
        return new NotificationDeliveryProgress(Status.PENDING, 1, 0, 0, now, null, null, null, now);
    }

    /** 只有尚未开始或明确允许重试的消息可自动发送。 */
    public boolean due(Instant now) {
        return (status == Status.PENDING || status == Status.RETRY_WAIT) && !nextAttemptAt.isAfter(now);
    }

    /** 开始发送形成独立事实；之后撤销同意无法召回已经开始的网络操作。 */
    public NotificationDeliveryProgress start(Instant now, UUID token) {
        if (!due(now)) throw invalid();
        return new NotificationDeliveryProgress(Status.IN_FLIGHT, version + 1, attempts + 1, cycleAttempts + 1,
                null, now.plus(LEASE_DURATION), token, null, now);
    }

    /** 租约到期可能已经产生外部效果，必须保留未知而不是自动领取重发。 */
    public NotificationDeliveryProgress expire(Instant now) {
        if (status != Status.IN_FLIGHT || leaseUntil.isAfter(now)) return this;
        return terminal(Status.UNKNOWN, FailureCode.LEASE_EXPIRED, now);
    }

    /** 当前版本和租约同时匹配，才允许原发送者确认回执。 */
    public boolean matchesClaim(NotificationDeliveryProgress claimed) {
        return status == Status.IN_FLIGHT && version == claimed.version && leaseToken.equals(claimed.leaseToken);
    }

    /** 明确临时拒绝最多自动尝试三次；连接中断等未知结果不进入自动重试。 */
    public NotificationDeliveryProgress complete(Outcome outcome, Instant now) {
        if (status != Status.IN_FLIGHT) throw invalid();
        return switch (outcome.result()) {
            case ACCEPTED -> terminal(Status.ACCEPTED, null, now);
            case UNKNOWN -> terminal(Status.UNKNOWN, outcome.code(), now);
            case FAILED -> terminal(Status.FAILED, outcome.code(), now);
            case RETRYABLE -> cycleAttempts >= MAX_CYCLE_ATTEMPTS
                    ? terminal(Status.FAILED, outcome.code(), now)
                    : new NotificationDeliveryProgress(Status.RETRY_WAIT, version + 1, attempts, cycleAttempts,
                            now.plus(cycleAttempts == 1 ? FIRST_RETRY_DELAY : SECOND_RETRY_DELAY), null, null, outcome.code(), now);
        };
    }

    /** 发送前不再具备资格时结束排队，既有发送事实不被撤销状态覆盖。 */
    public NotificationDeliveryProgress suppress(FailureCode code, Instant now) {
        if (status != Status.PENDING && status != Status.RETRY_WAIT) return this;
        return terminal(Status.SUPPRESSED, code, now);
    }

    /** 未配置的原绑定无法通过后来改址自动复活。 */
    public NotificationDeliveryProgress failBeforeSend(FailureCode code, Instant now) {
        if (status != Status.PENDING && status != Status.RETRY_WAIT) throw invalid();
        return terminal(Status.FAILED, code, now);
    }

    /** 明确人工重试保持累计次数；未知结果必须明确接受可能重复的风险。 */
    public NotificationDeliveryProgress retry(long expectedVersion, boolean acknowledgePossibleDuplicate, Instant now) {
        if (version != expectedVersion) throw new DomainException("CONCURRENCY_CONFLICT", "Notification delivery changed");
        if (!retryable()) throw invalid();
        if (status == Status.UNKNOWN && !acknowledgePossibleDuplicate)
            throw new DomainException("NOTIFICATION_DUPLICATE_ACK_REQUIRED", "Acknowledge possible duplicate notification before retry");
        return new NotificationDeliveryProgress(Status.PENDING, version + 1, attempts, 0, now, null, null, null, now);
    }

    /** 只有明确失败和结果未知允许申请人工恢复；资格由用例层复核。 */
    public boolean retryable() { return status == Status.FAILED || status == Status.UNKNOWN; }

    private NotificationDeliveryProgress terminal(Status target, FailureCode code, Instant now) {
        return new NotificationDeliveryProgress(target, version + 1, attempts, cycleAttempts, null, null, null, code, now);
    }
    private static DomainException invalid() {
        return new DomainException("NOTIFICATION_DELIVERY_STATE_INVALID", "Notification delivery is not in a permitted state");
    }
}
