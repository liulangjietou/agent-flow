package io.agentflow.finance;

import io.agentflow.common.DomainException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 独立挂账差额写入的持久状态机，未知结果查询原编号，任何较旧或矛盾结果保留人工核对。
 * @author owlzhangfq@gmail.com
 */
public record ExpenseAccrualReductionOperation(Input input, long version, Status status, int attempts, Instant createdAt, Instant updatedAt,
                                       Instant nextAttemptAt, Instant leaseUntil, ExpenseAccrualReductionObservation observation,
                                       ExpenseAccrualReductionObservation conflictingObservation, long highestRevision, Failure failure) {
    private static final long INITIAL_BACKOFF_SECONDS = 5;
    private static final long MAX_BACKOFF_SECONDS = 300;
    /** 恢复快照也执行不变量检查，曾发送的命令不能重建为首次发送。 */
    public ExpenseAccrualReductionOperation {
        if (input == null || version < 1 || status == null || attempts < 0 || createdAt == null || updatedAt == null
                || createdAt.isBefore(input.command().createdAt()) || updatedAt.isBefore(createdAt) || highestRevision < 0) throw invalid();
        if (attempts == 0 && (observation != null || conflictingObservation != null || highestRevision != 0)) throw invalid();
        boolean running = status == Status.POSTING || status == Status.QUERYING;
        boolean scheduled = status == Status.QUEUED || status == Status.UNKNOWN;
        if (running ? attempts < 1 || leaseUntil == null || !leaseUntil.isAfter(updatedAt) || failure != null : leaseUntil != null) throw invalid();
        if (scheduled ? nextAttemptAt == null || nextAttemptAt.isBefore(updatedAt) : nextAttemptAt != null) throw invalid();
        if (observation != null && (!observation.matches(input.command(), true, updatedAt) || observation.revision() > highestRevision)
                || conflictingObservation != null && (!conflictingObservation.matches(input.command(), true, updatedAt) || conflictingObservation.revision() > highestRevision)
                || highestRevision > 0 && observation == null && conflictingObservation == null) throw invalid();
        if ((status == Status.QUEUED || status == Status.POSTING) && (highestRevision != 0 || conflictingObservation != null || failure != null
                || observation != null && observation.status() != ExpenseAccrualReductionObservation.Status.NOT_FOUND
                || attempts == 0 && observation != null)) throw invalid();
        if (status == Status.QUEUED && attempts > 0 && observation == null || status == Status.POSTING && attempts > 1 && observation == null) throw invalid();
        if (status == Status.POSTED || status == Status.FAILED || status == Status.NOT_FOUND) {
            if (observation == null || !status.name().equals(observation.status().name()) || highestRevision != observation.revision()
                    || conflictingObservation != null || failure != null) throw invalid();
        }
        if (status == Status.UNKNOWN && failure == null && (observation == null || observation.status() != ExpenseAccrualReductionObservation.Status.PENDING)
                || status == Status.RECONCILING && (conflictingObservation == null || failure == null)
                || status == Status.EXPIRED && failure != Failure.EVIDENCE_EXPIRED || status == Status.VOIDED && failure != Failure.SOURCE_CHANGED && failure != Failure.FINANCE_RETIRED
                || failure == Failure.FINANCE_RETIRED && (status != Status.VOIDED || attempts != 0 || observation != null)) throw invalid();
        if ((status == Status.EXPIRED || status == Status.VOIDED) && (highestRevision != 0 || conflictingObservation != null
                || observation != null && observation.status() != ExpenseAccrualReductionObservation.Status.NOT_FOUND)) throw invalid();
    }
    /** 独立财务确认后登记命令，数据库提交前不产生外部副作用。 */
    public static ExpenseAccrualReductionOperation queue(Input input, Instant now) {
        input.command().requireSendAt(now);
        return new ExpenseAccrualReductionOperation(input, 1, Status.QUEUED, 0, now, now, now, null, null, null, 0, null);
    }
    /** 首次发送才检查新过账时效，未知结果即使过期也沿原编号查询。 */
    public ExpenseAccrualReductionOperation claim(Instant now, Duration lease) {
        requireTime(now);
        if ((status != Status.QUEUED && status != Status.UNKNOWN) || now.isBefore(nextAttemptAt) || lease == null || lease.isZero() || lease.isNegative()) throw conflict();
        if (status == Status.QUEUED && !now.isBefore(input.command().expiresAt())) return changed(Status.EXPIRED, now, null, observation, null, highestRevision, Failure.EVIDENCE_EXPIRED);
        return new ExpenseAccrualReductionOperation(input, Math.incrementExact(version), status == Status.QUEUED ? Status.POSTING : Status.QUERYING,
                Math.incrementExact(attempts), createdAt, now, null, now.plus(lease), observation, conflictingObservation, highestRevision, null);
    }
    /** 租约过期不证明 ERP 没有执行，后续只能读取实际结果。 */
    public ExpenseAccrualReductionOperation expire(Instant now) {
        if (!expired(now)) throw conflict();
        return changed(Status.UNKNOWN, now, now, observation, conflictingObservation, highestRevision, Failure.LEASE_EXPIRED);
    }
    /** 已保存的最高版本、受理编号和本次反向凭证及完整剩余额不能被迟到或矛盾响应覆盖。 */
    public ExpenseAccrualReductionOperation complete(FinanceResult<ExpenseAccrualReductionObservation> result, Instant now) {
        requireTime(now); if (!running()) throw conflict(); if (expired(now)) return expire(now);
        if (!(result instanceof FinanceResult.Success<ExpenseAccrualReductionObservation> success)) return unavailable(result instanceof FinanceResult.Unavailable<ExpenseAccrualReductionObservation> problem
                ? Failure.valueOf(problem.failure().name()) : Failure.INVALID_RESPONSE, now);
        var incoming = success.value();
        if (!incoming.matches(input.command(), status == Status.QUERYING, now)) return unavailable(Failure.INVALID_RESPONSE, now);
        long highest = Math.max(highestRevision, incoming.revision());
        if (incoming.revision() < highestRevision) return changed(Status.RECONCILING, now, null, observation, incoming, highest, Failure.STALE_OBSERVATION);
        if (conflictingObservation != null || observation != null && (incoming.revision() == observation.revision() ? !sameFact(observation, incoming)
                : incoming.observedAt().isBefore(observation.observedAt()) || !allowed(observation, incoming))) {
            return changed(Status.RECONCILING, now, null, observation, incoming, highest, Failure.INCONSISTENT_OBSERVATION);
        }
        var accepted = observation != null && sameFact(observation, incoming) && incoming.observedAt().isBefore(observation.observedAt()) ? observation : incoming;
        return changed(incoming.status() == ExpenseAccrualReductionObservation.Status.PENDING ? Status.UNKNOWN : Status.valueOf(incoming.status().name()),
                now, incoming.status() == ExpenseAccrualReductionObservation.Status.PENDING ? retryAt(now) : null, accepted, null, highest, null);
    }
    /** 连接或本地保存失败只产生未知结果，不能放开原件或重新生成命令。 */
    public ExpenseAccrualReductionOperation unavailable(Failure issue, Instant now) {
        requireTime(now); if (!running()) throw conflict(); if (expired(now)) return expire(now);
        return changed(Status.UNKNOWN, now, retryAt(now), observation, conflictingObservation, highestRevision, Objects.requireNonNull(issue));
    }
    /** 显式查询保留所有旧证据，不能清除已有争议。 */
    public ExpenseAccrualReductionOperation requestQuery(Instant now) {
        requireTime(now); if (running() || status == Status.QUEUED) throw conflict();
        return changed(Status.UNKNOWN, now, now, observation, conflictingObservation, highestRevision, Failure.RECHECK_REQUESTED);
    }
    /** 只在从未见过受理事实且权威查无时允许人工重发相同命令；授权不可延长。 */
    public ExpenseAccrualReductionOperation retryNotFound(Instant now) {
        requireTime(now); if (status != Status.NOT_FOUND || highestRevision != 0 || conflictingObservation != null) throw conflict();
        input.command().requireSendAt(now);
        return changed(Status.QUEUED, now, now, observation, null, 0, null);
    }
    /** 发送前资格或原件变化只停止未发送的排队命令，不能撤销已经发生的会计事实。 */
    public ExpenseAccrualReductionOperation voidBeforeSend(Instant now) {
        requireTime(now); if (status != Status.QUEUED) throw conflict();
        return changed(Status.VOIDED, now, null, observation, null, highestRevision, Failure.SOURCE_CHANGED);
    }
    /** 只允许结束从未领取发送的命令，或原件未发生变化且 ERP 在过账前明确拒绝的命令。 */
    public RetirementBasis retirementBasis() {
        if (status == Status.FAILED && switch (observation.rejection()) {
            case ACCOUNTING_PERIOD_CLOSED, LEGAL_ENTITY_UNAVAILABLE, ACCOUNT_UNAVAILABLE, AUTHORIZATION_REJECTED -> true;
            case ORIGINAL_NOT_POSTED, ORIGINAL_CHANGED, ADJUSTMENT_VERSION_CONFLICT -> false;
        }) return RetirementBasis.CONFIRMED_FAILED;
        if (attempts == 0 && highestRevision == 0 && observation == null && conflictingObservation == null
                && (status == Status.QUEUED || status == Status.VOIDED || status == Status.EXPIRED)) return RetirementBasis.NEVER_DISPATCHED;
        return null;
    }
    /** 财务明确结束使原排队命令失效，既有终态失败回执和原命令字节保持。 */
    public ExpenseAccrualReductionOperation stopForRetirement(Instant now) {
        requireTime(now); if (retirementBasis() == null) throw new DomainException("EXPENSE_ACCRUAL_REDUCTION_RETIREMENT_UNSAFE", "Accrual reduction is not proven safely finished");
        return status == Status.QUEUED ? changed(Status.VOIDED, now, null, null, null, 0, Failure.FINANCE_RETIRED) : this;
    }
    public boolean running() { return status == Status.POSTING || status == Status.QUERYING; }
    public boolean expired(Instant now) { return running() && !leaseUntil.isAfter(now); }
    private ExpenseAccrualReductionOperation changed(Status next, Instant at, Instant nextAt, ExpenseAccrualReductionObservation accepted, ExpenseAccrualReductionObservation disputed, long highest, Failure issue) {
        requireTime(at);
        return new ExpenseAccrualReductionOperation(input, Math.incrementExact(version), next, attempts, createdAt, at, nextAt, null, accepted, disputed, highest, issue);
    }
    private static boolean allowed(ExpenseAccrualReductionObservation before, ExpenseAccrualReductionObservation after) {
        if (before.status() == ExpenseAccrualReductionObservation.Status.NOT_FOUND) return true;
        if (!Objects.equals(before.acceptanceReference(), after.acceptanceReference())) return false;
        return switch (before.status()) {
            case PENDING -> after.status() != ExpenseAccrualReductionObservation.Status.NOT_FOUND;
            case POSTED -> after.status() == ExpenseAccrualReductionObservation.Status.POSTED && samePosting(before, after);
            case FAILED -> after.status() == ExpenseAccrualReductionObservation.Status.FAILED && before.rejection() == after.rejection();
            case NOT_FOUND -> true;
        };
    }
    private static boolean sameFact(ExpenseAccrualReductionObservation before, ExpenseAccrualReductionObservation after) {
        return before.status() == after.status() && before.revision() == after.revision() && Objects.equals(before.acceptanceReference(), after.acceptanceReference())
                && before.rejection() == after.rejection() && samePosting(before, after);
    }
    private static boolean samePosting(ExpenseAccrualReductionObservation before, ExpenseAccrualReductionObservation after) {
        if (before.posting() == null || after.posting() == null) return before.posting() == after.posting();
        var previous = before.posting(); var incoming = after.posting();
        return incoming.adjustmentRevision() == previous.adjustmentRevision() && incoming.beforeDigest().equals(previous.beforeDigest())
                && incoming.afterDigest().equals(previous.afterDigest()) && incoming.voucher().equals(previous.voucher())
                && incoming.original().revision() >= previous.original().revision()
                && !incoming.original().observedAt().isBefore(previous.original().observedAt());
    }
    private Instant retryAt(Instant now) { return now.plusSeconds(Math.min(MAX_BACKOFF_SECONDS, INITIAL_BACKOFF_SECONDS << Math.min(attempts - 1, 6))); }
    private void requireTime(Instant now) { if (now == null || now.isBefore(updatedAt)) throw conflict(); }
    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_ACCRUAL_REDUCTION_OPERATION", "Accrual reduction execution must preserve original command, accepted posting and highest external revision"); }
    private static DomainException conflict() { return new DomainException("EXPENSE_ACCRUAL_REDUCTION_OPERATION_CONFLICT", "Accrual reduction operation can no longer perform this transition"); }
    /**
     * 本地原修订、命令和目的地从登记开始不可替换。
     * @author owlzhangfq@gmail.com
     */
    public record Input(long originalVersion, ExpenseAccrualReductionCommand command, String targetDigest) {
        /** 原修订由仓储外键与实际读取再次绑定。 */
        public Input { if (originalVersion < 1 || command == null || targetDigest == null || !targetDigest.matches("[a-f0-9]{64}")) throw invalid(); }
    }
    /**
     * 查无不自动重发，争议不自动解除，原独立命令保留唯一身份。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { QUEUED, POSTING, QUERYING, UNKNOWN, POSTED, FAILED, NOT_FOUND, EXPIRED, VOIDED, RECONCILING }
    /**
     * 持久状态只保存稳定错误分类，不保留远端响应正文。
     * @author owlzhangfq@gmail.com
     */
    public enum Failure { NOT_CONFIGURED, TARGET_CHANGED, TIMEOUT, CONNECTION, AUTHENTICATION, REMOTE_FAILURE, INVALID_RESPONSE,
        RESPONSE_TOO_LARGE, LEASE_EXPIRED, INTERNAL_ERROR, EVIDENCE_EXPIRED, SOURCE_CHANGED, STALE_OBSERVATION, INCONSISTENT_OBSERVATION, RECHECK_REQUESTED, FINANCE_RETIRED }
    /**
     * 查无和原件变化都不是可以重新创建命令的依据。
     * @author owlzhangfq@gmail.com
     */
    public enum RetirementBasis { NEVER_DISPATCHED, CONFIRMED_FAILED }
}
