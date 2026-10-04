package io.agentflow.finance;

import io.agentflow.common.DomainException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 预算副作用的持久执行状态；未知结果必须先查询，领取版本隔离迟到执行者。
 * @author owlzhangfq@gmail.com
 */
public record BudgetOperation(Input input, long version, Status status, int attempts, Instant createdAt, Instant updatedAt,
        Instant nextAttemptAt, Instant leaseUntil, BudgetObservation observation, Failure failure) {
    private static final long INITIAL_BACKOFF_SECONDS = 5;
    private static final long MAX_BACKOFF_SECONDS = 300;

    /** 持久状态只允许可恢复的组合，不能将未知状态反序列化成待首次发送。 */
    public BudgetOperation {
        Objects.requireNonNull(input); Objects.requireNonNull(status); Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
        boolean running = status == Status.EXECUTING || status == Status.QUERYING;
        boolean terminal = status == Status.APPLIED || status == Status.REJECTED;
        if (version < 1 || attempts < 0 || updatedAt.isBefore(createdAt)
                || running && (attempts == 0 || leaseUntil == null || !leaseUntil.isAfter(updatedAt) || nextAttemptAt != null || observation != null || failure != null)
                || !running && leaseUntil != null
                || !running && !terminal && (nextAttemptAt == null || nextAttemptAt.isBefore(updatedAt))
                || terminal && (nextAttemptAt != null || observation == null || failure != null || !observation.status().name().equals(status.name()))
                || status == Status.UNKNOWN && (observation == null) == (failure == null)
                || status == Status.UNKNOWN && observation != null && observation.status() != BudgetObservation.Status.PENDING
                || status == Status.QUEUED && (failure != null || attempts == 0 && observation != null
                        || attempts > 0 && (observation == null || observation.status() != BudgetObservation.Status.NOT_FOUND))
                || observation != null && !observation.matches(input.command(), true, updatedAt)
                || input.command().exceptionApproval() != null && input.command().exceptionApproval().approvedAt().isAfter(createdAt)) throw invalid();
    }

    /** 本地事务只排队，未提交的事务没有任何外部预算效果。 */
    public static BudgetOperation queue(Input input, Instant now) {
        return new BudgetOperation(input, 1, Status.QUEUED, 0, now, now, now, null, null, null);
    }

    /** 首次或查无原操作后才可执行；其余恢复一律领取为只读查询。 */
    public BudgetOperation claim(Instant now, Duration lease) {
        if ((status != Status.QUEUED && status != Status.UNKNOWN) || now.isBefore(nextAttemptAt)
                || lease.isNegative() || lease.isZero()) throw conflict();
        return new BudgetOperation(input, Math.incrementExact(version), status == Status.QUEUED ? Status.EXECUTING : Status.QUERYING,
                Math.incrementExact(attempts), createdAt, now, null, now.plus(lease), null, null);
    }

    /** 崩溃或超期领取无法证明未执行，下一步立即查询，绝不重新直接发送。 */
    public BudgetOperation expire(Instant now) {
        if (!expired(now)) throw conflict();
        return changed(Status.UNKNOWN, now, now, null, Failure.LEASE_EXPIRED);
    }

    /** 真实结果也须匹配仍有效的领取；超期结果交由原操作查询恢复。 */
    public BudgetOperation complete(FinanceResult<BudgetObservation> result, Instant now) {
        if (!running()) throw conflict();
        if (expired(now)) return expire(now);
        if (result instanceof FinanceResult.Success<BudgetObservation> success) {
            var value = success.value();
            if (!value.matches(input.command(), status == Status.QUERYING, now)) return unavailable(Failure.INVALID_RESPONSE, now);
            return switch (value.status()) {
                case APPLIED -> changed(Status.APPLIED, now, null, value, null);
                case REJECTED -> changed(Status.REJECTED, now, null, value, null);
                case PENDING -> changed(Status.UNKNOWN, now, retryAt(now), value, null);
                case NOT_FOUND -> changed(Status.QUEUED, now, retryAt(now), value, null);
            };
        }
        if (result instanceof FinanceResult.Unavailable<BudgetObservation> unavailable) return unavailable(Failure.valueOf(unavailable.failure().name()), now);
        return unavailable(Failure.INVALID_RESPONSE, now);
    }

    /** 本地执行异常也不能推断外部未完成，保留原命令以便后续查询。 */
    public BudgetOperation unavailable(Failure value, Instant now) {
        if (!running()) throw conflict();
        if (expired(now)) return expire(now);
        return changed(Status.UNKNOWN, now, retryAt(now), null, Objects.requireNonNull(value));
    }

    public boolean terminal() { return status == Status.APPLIED || status == Status.REJECTED; }
    public boolean running() { return status == Status.EXECUTING || status == Status.QUERYING; }
    public boolean expired(Instant now) { return running() && !leaseUntil.isAfter(now); }

    private BudgetOperation changed(Status next, Instant now, Instant retryAt, BudgetObservation value, Failure problem) {
        return new BudgetOperation(input, Math.incrementExact(version), next, attempts, createdAt, now, retryAt, null, value, problem);
    }
    private Instant retryAt(Instant now) {
        return now.plusSeconds(Math.min(MAX_BACKOFF_SECONDS, INITIAL_BACKOFF_SECONDS << Math.min(attempts - 1, 6)));
    }
    private static DomainException invalid() { return new DomainException("INVALID_BUDGET_OPERATION", "Budget operation state or observation is inconsistent"); }
    private static DomainException conflict() { return new DomainException("BUDGET_OPERATION_STATE_CONFLICT", "Budget operation is no longer executable"); }

    /**
     * 目标和业务命令一起持久化；任何重试都不能更换它们。
     * @author owlzhangfq@gmail.com
     */
    public record Input(BudgetCommand command, String targetDigest) {
        /** 目标摘要来自服务端租户配置。 */
        public Input {
            if (command == null || targetDigest == null || !targetDigest.matches("[a-f0-9]{64}")) throw invalid();
            if (command.exceptionApproval() != null && !targetDigest.equals(command.exceptionApproval().targetDigest())) throw invalid();
        }
    }
    /**
     * UNKNOWN 永远没有已冻结含义，QUERYING 只读取原操作事实。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { QUEUED, EXECUTING, UNKNOWN, QUERYING, APPLIED, REJECTED }
    /**
     * 稳定依赖分类与本地恢复原因，禁止保存远端错误正文。
     * @author owlzhangfq@gmail.com
     */
    public enum Failure { NOT_CONFIGURED, TARGET_CHANGED, TIMEOUT, CONNECTION, AUTHENTICATION, REMOTE_FAILURE,
        INVALID_RESPONSE, RESPONSE_TOO_LARGE, LEASE_EXPIRED, INTERNAL_ERROR }
}
