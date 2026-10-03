package io.agentflow.budget;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceResult;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 原子预算指令的执行状态，网络未知只查原号，局部回执或倒退证据不能解除保护。
 * @author owlzhangfq@gmail.com
 */
public record BudgetAdjustmentOperation(BudgetAdjustmentCommand command, long version, Status status, int attempts,
        Instant createdAt, Instant updatedAt, Instant nextAttemptAt, Instant leaseUntil,
        BudgetAdjustmentObservation observation, BudgetAdjustmentObservation conflictingObservation, Failure failure) {
    private static final long INITIAL_BACKOFF_SECONDS = 5;
    private static final long MAX_BACKOFF_SECONDS = 300;

    /** 持久快照同时校验不可变命令、领取次数和外部事实，不能恢复成未发送状态。 */
    public BudgetAdjustmentOperation {
        if (command == null || version < 1 || status == null || attempts < 0 || attempts >= version || createdAt == null || updatedAt == null
                || createdAt.isBefore(command.authorizedAt()) || !createdAt.isBefore(command.expiresAt()) || updatedAt.isBefore(createdAt)) throw invalid();
        boolean running = status == Status.EXECUTING || status == Status.QUERYING;
        boolean scheduled = status == Status.QUEUED || status == Status.UNKNOWN;
        if (running ? attempts < 1 || leaseUntil == null || !leaseUntil.isAfter(updatedAt) || failure != null : leaseUntil != null) throw invalid();
        if (scheduled ? nextAttemptAt == null || nextAttemptAt.isBefore(updatedAt) : nextAttemptAt != null) throw invalid();
        if (attempts == 0 && (observation != null || conflictingObservation != null || status != Status.QUEUED && status != Status.EXPIRED && status != Status.VOIDED)) throw invalid();
        if (version == 1 && (status != Status.QUEUED || attempts != 0 || failure != null || !createdAt.equals(updatedAt) || !createdAt.equals(nextAttemptAt))) throw invalid();
        if (status == Status.QUEUED && attempts == 0 && version != 1) throw invalid();
        if (observation != null && !observation.matches(command, true, updatedAt)
                || conflictingObservation != null && (observation == null || !conflictingObservation.matches(command, true, updatedAt))) throw invalid();
        if ((status == Status.QUEUED || status == Status.EXECUTING) && (conflictingObservation != null || failure != null
                || observation != null && observation.status() != BudgetAdjustmentObservation.Status.NOT_FOUND)) throw invalid();
        if (status == Status.QUEUED && attempts > 0 && observation == null || status == Status.EXECUTING && attempts > 1 && observation == null) throw invalid();
        if ((status == Status.APPLIED || status == Status.REJECTED || status == Status.NOT_FOUND)
                && (observation == null || !status.name().equals(observation.status().name()) || conflictingObservation != null || failure != null)) throw invalid();
        if (status == Status.UNKNOWN && failure == null && (observation == null || observation.status() != BudgetAdjustmentObservation.Status.PENDING || conflictingObservation != null)
                || status == Status.RECONCILING && (observation == null || conflictingObservation == null || failure != Failure.INCONSISTENT_OBSERVATION)
                || status == Status.EXPIRED && (failure != Failure.AUTHORIZATION_EXPIRED || updatedAt.isBefore(command.expiresAt()))
                || status == Status.VOIDED && failure != Failure.SOURCE_CHANGED) throw invalid();
        if ((status == Status.EXPIRED || status == Status.VOIDED) && (conflictingObservation != null
                || observation != null && observation.status() != BudgetAdjustmentObservation.Status.NOT_FOUND)) throw invalid();
    }

    /** 明确授权先入队，事务提交前没有外部预算写入。 */
    public static BudgetAdjustmentOperation queue(BudgetAdjustmentCommand command, Instant now) {
        command.requireSendAt(now);
        return new BudgetAdjustmentOperation(command, 1, Status.QUEUED, 0, now, now, now, null, null, null, null);
    }

    /** 新发送受授权期限约束；未知结果的原号查询不受旧发送期限限制。 */
    public BudgetAdjustmentOperation claim(Instant now, Duration lease) {
        requireTime(now);
        if ((status != Status.QUEUED && status != Status.UNKNOWN) || now.isBefore(nextAttemptAt) || lease == null || lease.isZero() || lease.isNegative()) throw conflict();
        if (status == Status.QUEUED && !now.isBefore(command.expiresAt())) return changed(Status.EXPIRED, now, null, observation, null, Failure.AUTHORIZATION_EXPIRED);
        return new BudgetAdjustmentOperation(command, Math.incrementExact(version), status == Status.QUEUED ? Status.EXECUTING : Status.QUERYING,
                Math.incrementExact(attempts), createdAt, now, null, now.plus(lease), observation, conflictingObservation, null);
    }

    /** 超过领取租约的结果不会覆盖新查询，崩溃也不能证明原指令没有生效。 */
    public BudgetAdjustmentOperation expire(Instant now) {
        if (!expired(now)) throw conflict();
        return changed(Status.UNKNOWN, now, now, observation, conflictingObservation, Failure.LEASE_EXPIRED);
    }

    /** 只有完整两端事实可以确认调拨；原版本倒退、终态变化或已受理后查无进入核对。 */
    public BudgetAdjustmentOperation complete(FinanceResult<BudgetAdjustmentObservation> result, Instant now) {
        requireTime(now); if (!running()) throw conflict(); if (expired(now)) return expire(now);
        if (!(result instanceof FinanceResult.Success<BudgetAdjustmentObservation> success)) {
            return unavailable(result instanceof FinanceResult.Unavailable<BudgetAdjustmentObservation> problem
                    ? Failure.valueOf(problem.failure().name()) : Failure.INVALID_RESPONSE, now);
        }
        var incoming = success.value();
        if (!incoming.matches(command, status == Status.QUERYING, now)) return unavailable(Failure.INVALID_RESPONSE, now);
        if (conflictingObservation != null || observation != null && !allowed(observation, incoming)) {
            return changed(Status.RECONCILING, now, null, observation, incoming, Failure.INCONSISTENT_OBSERVATION);
        }
        boolean pending = incoming.status() == BudgetAdjustmentObservation.Status.PENDING;
        return changed(pending ? Status.UNKNOWN : Status.valueOf(incoming.status().name()), now, pending ? retryAt(now) : null, incoming, null, null);
    }

    /** 传输或本地异常只能保留未知，不能生成新的预算命令。 */
    public BudgetAdjustmentOperation unavailable(Failure issue, Instant now) {
        requireTime(now); if (!running()) throw conflict(); if (expired(now)) return expire(now);
        return changed(Status.UNKNOWN, now, retryAt(now), observation, conflictingObservation, Objects.requireNonNull(issue));
    }

    /** 人工复查已发送的原操作，已有争议不会因普通查询而自动消失。 */
    public BudgetAdjustmentOperation requestQuery(Instant now) {
        requireTime(now); if (running() || status == Status.QUEUED || attempts == 0) throw conflict();
        return changed(Status.UNKNOWN, now, now, observation, conflictingObservation, Failure.RECHECK_REQUESTED);
    }

    /** 权威查无后只能明确重发未过期的同一指令，不能更换预算或金额。 */
    public BudgetAdjustmentOperation retryNotFound(Instant now) {
        requireTime(now); if (status != Status.NOT_FOUND || conflictingObservation != null) throw conflict();
        command.requireSendAt(now); return changed(Status.QUEUED, now, now, observation, null, null);
    }

    /** 原批准或财务资格变化阻止尚待发送的任务，已发送的未知结果继续保留。 */
    public BudgetAdjustmentOperation voidBeforeSend(Instant now) {
        requireTime(now); if (status != Status.QUEUED) throw conflict();
        return changed(Status.VOIDED, now, null, observation, null, Failure.SOURCE_CHANGED);
    }
    public boolean running() { return status == Status.EXECUTING || status == Status.QUERYING; }
    public boolean expired(Instant now) { return running() && !leaseUntil.isAfter(now); }

    /** 只有从未发送或原系统明确无副作用拒绝才能释放本轮执行占用。 */
    public boolean safelyUnexecuted() {
        if (conflictingObservation != null) return false;
        return attempts == 0 && observation == null && (status == Status.QUEUED || status == Status.EXPIRED || status == Status.VOIDED)
                || status == Status.REJECTED;
    }
    private BudgetAdjustmentOperation changed(Status next, Instant at, Instant nextAt, BudgetAdjustmentObservation accepted,
            BudgetAdjustmentObservation disputed, Failure issue) {
        requireTime(at);
        return new BudgetAdjustmentOperation(command, Math.incrementExact(version), next, attempts, createdAt, at, nextAt, null, accepted, disputed, issue);
    }
    private static boolean allowed(BudgetAdjustmentObservation previous, BudgetAdjustmentObservation next) {
        if (next.observedAt().isBefore(previous.observedAt()) || next.revision() < previous.revision()) return false;
        boolean sameFacts = next.status() == previous.status() && Objects.equals(next.reference(), previous.reference())
                && Objects.equals(next.appliedAt(), previous.appliedAt()) && next.changes().equals(previous.changes()) && next.rejection() == previous.rejection();
        if (next.revision() == previous.revision()) return sameFacts;
        return switch (previous.status()) {
            case NOT_FOUND -> true;
            case PENDING -> next.status() != BudgetAdjustmentObservation.Status.NOT_FOUND;
            case REJECTED, APPLIED -> sameFacts;
        };
    }
    private Instant retryAt(Instant now) { return now.plusSeconds(Math.min(MAX_BACKOFF_SECONDS, INITIAL_BACKOFF_SECONDS << Math.min(attempts - 1, 6))); }
    private void requireTime(Instant now) { if (now == null || now.isBefore(updatedAt)) throw conflict(); }
    private static DomainException invalid() { return new DomainException("INVALID_BUDGET_ADJUSTMENT_OPERATION", "Budget execution must preserve its command, attempts and accepted atomic observations"); }
    private static DomainException conflict() { return new DomainException("BUDGET_ADJUSTMENT_OPERATION_CONFLICT", "Budget adjustment cannot perform this transition"); }

    /**
     * 查无等待明确重发，未知只读恢复，矛盾独立核对。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { QUEUED, EXECUTING, QUERYING, UNKNOWN, APPLIED, REJECTED, NOT_FOUND, EXPIRED, VOIDED, RECONCILING }
    /**
     * 只保存稳定失败分类，不保存外部异常正文。
     * @author owlzhangfq@gmail.com
     */
    public enum Failure { NOT_CONFIGURED, TARGET_CHANGED, TIMEOUT, CONNECTION, AUTHENTICATION, REMOTE_FAILURE, INVALID_RESPONSE,
        RESPONSE_TOO_LARGE, LEASE_EXPIRED, INTERNAL_ERROR, AUTHORIZATION_EXPIRED, SOURCE_CHANGED, INCONSISTENT_OBSERVATION, RECHECK_REQUESTED }
}
