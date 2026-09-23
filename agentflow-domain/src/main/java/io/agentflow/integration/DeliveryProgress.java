package io.agentflow.integration;

import io.agentflow.common.DomainException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Webhook 投递状态，只管理投递与租约，不改变关联申请或引擎状态。
 * @author owlzhangfq@gmail.com
 */
public record DeliveryProgress(Status status, long version, int attempts, int cycleAttempts,
                               Instant nextAttemptAt, Instant leaseUntil, String leaseToken,
                               Integer httpStatus, String errorCode) {
    public static final int MAX_CYCLE_ATTEMPTS = 6;
    public static final Duration LEASE = Duration.ofSeconds(30);
    private static final List<Duration> RETRY_DELAYS = List.of(Duration.ofSeconds(5), Duration.ofSeconds(30),
            Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofHours(1));

    /** 新事件从等待投递开始，零次尝试不是成功。 */
    public static DeliveryProgress pending(Instant now) {
        return new DeliveryProgress(Status.PENDING, 1, 0, 0, now, null, null, null, null);
    }

    /** 只有已到期的等待记录或过期租约可以竞争；数据库再用版本号保证单一领取。 */
    public boolean due(Instant now) {
        return (status == Status.PENDING || status == Status.RETRY_WAIT) && !nextAttemptAt.isAfter(now)
                || status == Status.IN_FLIGHT && !leaseUntil.isAfter(now);
    }

    /** 领取消耗一次尝试；崩溃后最后一次租约过期记为结果未知，不能无限重发。 */
    public DeliveryProgress claim(Instant now, String token) {
        if (!due(now)) throw conflict();
        if (cycleAttempts >= MAX_CYCLE_ATTEMPTS) {
            return new DeliveryProgress(Status.FAILED, version + 1, attempts, cycleAttempts, null, null, null, null, "OUTCOME_UNKNOWN");
        }
        return new DeliveryProgress(Status.IN_FLIGHT, version + 1, attempts + 1, cycleAttempts + 1,
                null, now.plus(LEASE), token, httpStatus, errorCode);
    }

    /** 过期的旧 worker 不得覆盖新租约的处理结果；是否仍持有版本由仓储原子复核。 */
    public DeliveryProgress finish(String token, Outcome outcome, Instant now) {
        if (status != Status.IN_FLIGHT || !leaseToken.equals(token)) throw conflict();
        if (outcome.success()) return new DeliveryProgress(Status.DELIVERED, version + 1, attempts, cycleAttempts, null, null, null, outcome.httpStatus(), null);
        boolean retry = outcome.retryable() && cycleAttempts < MAX_CYCLE_ATTEMPTS;
        return new DeliveryProgress(retry ? Status.RETRY_WAIT : Status.FAILED, version + 1, attempts, cycleAttempts,
                retry ? now.plus(RETRY_DELAYS.get(cycleAttempts - 1)) : null, null, null, outcome.httpStatus(), outcome.errorCode());
    }

    /** 人工重试开启新一轮投递，累计次数不清零，事件身份和原始请求体保持不变。 */
    public DeliveryProgress retry(long expectedVersion, Instant now) {
        if (version != expectedVersion || status == Status.PENDING || status == Status.IN_FLIGHT) throw conflict();
        return new DeliveryProgress(Status.PENDING, version + 1, attempts, 0, now, null, null, httpStatus, errorCode);
    }

    private static DomainException conflict() { return new DomainException("WEBHOOK_DELIVERY_CONFLICT", "Webhook delivery state has changed"); }

    /**
     * 外部确认、等待重试和永久失败分开表示。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { PENDING, IN_FLIGHT, RETRY_WAIT, DELIVERED, FAILED }

    /**
     * 传输适配器只提供稳定错误码与状态码，不保留远端响应正文或异常中的密钥。
     * @author owlzhangfq@gmail.com
     */
    public record Outcome(boolean success, boolean retryable, Integer httpStatus, String errorCode) {
        /** 2xx 确认接收；重定向和普通客户端错误不自动重试。 */
        public static Outcome http(int status) {
            boolean success = status >= 200 && status < 300;
            return new Outcome(success, status == 408 || status == 425 || status == 429 || status >= 500,
                    status, success ? null : "HTTP_" + status);
        }
        /** 传输失败由适配器明确标记能否重试。 */
        public static Outcome failed(String code, boolean retryable) { return new Outcome(false, retryable, null, code); }
    }
}
