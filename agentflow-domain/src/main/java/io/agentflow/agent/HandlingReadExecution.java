package io.agentflow.agent;

import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.UUID;

/**
 * 只读工具的原步骤。重试复用身份和输入，租约只允许一个外部读取者。
 * @author owlzhangfq@gmail.com
 */
public record HandlingReadExecution(UUID id, String inputDigest, String authorizationDigest, long version,
        Status status, Instant leaseUntil, String preparedJson, String receiptJson, String failureCode) {
    public static final int LEASE_SECONDS = 180;
    /** 调用前建立身份；此时尚未执行任何外部读取。 */
    public static HandlingReadExecution start(UUID id, String inputDigest, String authorizationDigest, Instant now) {
        return new HandlingReadExecution(id, inputDigest, authorizationDigest, 1, Status.RUNNING, now.plusSeconds(LEASE_SECONDS), null, null, null);
    }
    /** 原输入和原权限范围不能被同键的新请求替换。 */
    public void requireMatches(String input, String authorization) {
        if (!inputDigest.equals(input) || !authorizationDigest.equals(authorization)) {
            throw new DomainException("IDEMPOTENCY_CONFLICT", "Handling read input or authorization changed");
        }
    }
    /** 只读调用允许原步骤重试；已经保存的读取结果直接复用。 */
    public HandlingReadExecution retry(Instant now) {
        if (status == Status.PREPARED || status == Status.RECORDED) return this;
        if (status == Status.RUNNING && leaseUntil.isAfter(now)) {
            throw new DomainException("CONCURRENCY_CONFLICT", "Original handling read is still running");
        }
        return new HandlingReadExecution(id, inputDigest, authorizationDigest, version + 1, Status.RUNNING, now.plusSeconds(LEASE_SECONDS), null, null, null);
    }
    /** 租约过期的迟到响应不能覆盖另一次领取。 */
    public HandlingReadExecution prepared(String value, Instant now) {
        requireRunning(now);
        return new HandlingReadExecution(id, inputDigest, authorizationDigest, version + 1, Status.PREPARED, null, value, null, null);
    }
    /** 失败保留原步骤，网络错误不会消失为未发生的调用。 */
    public HandlingReadExecution failed(String code, Instant now) {
        requireRunning(now);
        return new HandlingReadExecution(id, inputDigest, authorizationDigest, version + 1, Status.FAILED, null, null, null, code);
    }
    /** 业务登记和原回执同事务保存，重放不追加第二个步骤。 */
    public HandlingReadExecution recorded(String value) {
        if (status != Status.PREPARED) throw new DomainException("CONCURRENCY_CONFLICT", "Handling read has no prepared result");
        return new HandlingReadExecution(id, inputDigest, authorizationDigest, version + 1, Status.RECORDED, null, preparedJson, value, null);
    }
    private void requireRunning(Instant now) {
        if (status != Status.RUNNING || !leaseUntil.isAfter(now)) throw new DomainException("CONCURRENCY_CONFLICT", "Handling read lease expired");
    }
    /**
     * 读取结果与办理登记分开持久，崩溃后可以辨认原执行位置。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { RUNNING, PREPARED, RECORDED, FAILED }
}
