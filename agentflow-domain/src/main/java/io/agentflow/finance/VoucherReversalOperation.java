package io.agentflow.finance;

import io.agentflow.common.DomainException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 独立冲销写入的持久状态机，未知结果查询原编号，任何较旧或矛盾结果保留人工核对。
 * @author owlzhangfq@gmail.com
 */
public record VoucherReversalOperation(Input input, long version, Status status, int attempts, Instant createdAt, Instant updatedAt,
                                       Instant nextAttemptAt, Instant leaseUntil, VoucherReversalObservation observation,
                                       VoucherReversalObservation conflictingObservation, long highestRevision, Failure failure) {
    private static final long INITIAL_BACKOFF_SECONDS = 5;
    private static final long MAX_BACKOFF_SECONDS = 300;
    /** 恢复快照也执行不变量检查，曾发送的命令不能重建为首次发送。 */
    public VoucherReversalOperation {
        if (input == null || version < 1 || status == null || attempts < 0 || createdAt == null || updatedAt == null
                || createdAt.isBefore(input.command().createdAt()) || updatedAt.isBefore(createdAt) || highestRevision < 0) throw invalid();
        boolean running = status == Status.POSTING || status == Status.QUERYING;
        boolean scheduled = status == Status.QUEUED || status == Status.UNKNOWN;
        if (running ? attempts < 1 || leaseUntil == null || !leaseUntil.isAfter(updatedAt) || failure != null : leaseUntil != null) throw invalid();
        if (scheduled ? nextAttemptAt == null || nextAttemptAt.isBefore(updatedAt) : nextAttemptAt != null) throw invalid();
        if (observation != null && (!observation.matches(input.command(), true, updatedAt) || observation.revision() > highestRevision)
                || conflictingObservation != null && (!conflictingObservation.matches(input.command(), true, updatedAt) || conflictingObservation.revision() > highestRevision)
                || highestRevision > 0 && observation == null && conflictingObservation == null) throw invalid();
        if ((status == Status.QUEUED || status == Status.POSTING) && (highestRevision != 0 || conflictingObservation != null || failure != null
                || observation != null && observation.status() != VoucherReversalObservation.Status.NOT_FOUND
                || attempts == 0 && observation != null)) throw invalid();
        if (status == Status.QUEUED && attempts > 0 && observation == null || status == Status.POSTING && attempts > 1 && observation == null) throw invalid();
        if (status == Status.POSTED || status == Status.FAILED || status == Status.NOT_FOUND) {
            if (observation == null || !status.name().equals(observation.status().name()) || highestRevision != observation.revision()
                    || conflictingObservation != null || failure != null) throw invalid();
        }
        if (status == Status.UNKNOWN && failure == null && (observation == null || observation.status() != VoucherReversalObservation.Status.PENDING)
                || status == Status.RECONCILING && (conflictingObservation == null || failure == null)
                || status == Status.EXPIRED && failure != Failure.EVIDENCE_EXPIRED || status == Status.VOIDED && failure != Failure.SOURCE_CHANGED) throw invalid();
        if ((status == Status.EXPIRED || status == Status.VOIDED) && (highestRevision != 0 || conflictingObservation != null
                || observation != null && observation.status() != VoucherReversalObservation.Status.NOT_FOUND)) throw invalid();
    }
    /** 独立财务确认后登记命令，数据库提交前不产生外部副作用。 */
    public static VoucherReversalOperation queue(Input input, Instant now) {
        input.command().requireSendAt(now);
        return new VoucherReversalOperation(input, 1, Status.QUEUED, 0, now, now, now, null, null, null, 0, null);
    }
    /** 首次发送才检查新过账时效，未知结果即使过期也沿原编号查询。 */
    public VoucherReversalOperation claim(Instant now, Duration lease) {
        requireTime(now);
        if ((status != Status.QUEUED && status != Status.UNKNOWN) || now.isBefore(nextAttemptAt) || lease == null || lease.isZero() || lease.isNegative()) throw conflict();
        if (status == Status.QUEUED && !now.isBefore(input.command().expiresAt())) return changed(Status.EXPIRED, now, null, observation, null, highestRevision, Failure.EVIDENCE_EXPIRED);
        return new VoucherReversalOperation(input, Math.incrementExact(version), status == Status.QUEUED ? Status.POSTING : Status.QUERYING,
                Math.incrementExact(attempts), createdAt, now, null, now.plus(lease), observation, conflictingObservation, highestRevision, null);
    }
    /** 租约过期不证明 ERP 没有执行，后续只能读取实际结果。 */
    public VoucherReversalOperation expire(Instant now) {
        if (!expired(now)) throw conflict();
        return changed(Status.UNKNOWN, now, now, observation, conflictingObservation, highestRevision, Failure.LEASE_EXPIRED);
    }
    /** 已保存的最高版本、受理编号和完整反向凭证不能被迟到或矛盾响应覆盖。 */
    public VoucherReversalOperation complete(FinanceResult<VoucherReversalObservation> result, Instant now) {
        requireTime(now); if (!running()) throw conflict(); if (expired(now)) return expire(now);
        if (!(result instanceof FinanceResult.Success<VoucherReversalObservation> success)) return unavailable(result instanceof FinanceResult.Unavailable<VoucherReversalObservation> problem
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
        return changed(incoming.status() == VoucherReversalObservation.Status.PENDING ? Status.UNKNOWN : Status.valueOf(incoming.status().name()),
                now, incoming.status() == VoucherReversalObservation.Status.PENDING ? retryAt(now) : null, accepted, null, highest, null);
    }
    /** 连接或本地保存失败只产生未知结果，不能放开原件或重新生成命令。 */
    public VoucherReversalOperation unavailable(Failure issue, Instant now) {
        requireTime(now); if (!running()) throw conflict(); if (expired(now)) return expire(now);
        return changed(Status.UNKNOWN, now, retryAt(now), observation, conflictingObservation, highestRevision, Objects.requireNonNull(issue));
    }
    /** 显式查询保留所有旧证据，不能清除已有争议。 */
    public VoucherReversalOperation requestQuery(Instant now) {
        requireTime(now); if (running() || status == Status.QUEUED) throw conflict();
        return changed(Status.UNKNOWN, now, now, observation, conflictingObservation, highestRevision, Failure.RECHECK_REQUESTED);
    }
    /** 只在从未见过受理事实且权威查无时允许人工重发相同命令；授权不可延长。 */
    public VoucherReversalOperation retryNotFound(Instant now) {
        requireTime(now); if (status != Status.NOT_FOUND || highestRevision != 0 || conflictingObservation != null) throw conflict();
        input.command().requireSendAt(now);
        return changed(Status.QUEUED, now, now, observation, null, 0, null);
    }
    /** 发送前资格或原件变化只停止未发送的排队命令，不能撤销已经发生的会计事实。 */
    public VoucherReversalOperation voidBeforeSend(Instant now) {
        requireTime(now); if (status != Status.QUEUED) throw conflict();
        return changed(Status.VOIDED, now, null, observation, null, highestRevision, Failure.SOURCE_CHANGED);
    }
    public boolean running() { return status == Status.POSTING || status == Status.QUERYING; }
    public boolean expired(Instant now) { return running() && !leaseUntil.isAfter(now); }
    private VoucherReversalOperation changed(Status next, Instant at, Instant nextAt, VoucherReversalObservation accepted, VoucherReversalObservation disputed, long highest, Failure issue) {
        requireTime(at);
        return new VoucherReversalOperation(input, Math.incrementExact(version), next, attempts, createdAt, at, nextAt, null, accepted, disputed, highest, issue);
    }
    private static boolean allowed(VoucherReversalObservation before, VoucherReversalObservation after) {
        if (before.status() == VoucherReversalObservation.Status.NOT_FOUND) return true;
        if (!Objects.equals(before.acceptanceReference(), after.acceptanceReference())) return false;
        return switch (before.status()) {
            case PENDING -> after.status() != VoucherReversalObservation.Status.NOT_FOUND;
            case POSTED -> after.status() == VoucherReversalObservation.Status.POSTED && samePosting(before, after);
            case FAILED -> after.status() == VoucherReversalObservation.Status.FAILED && before.rejection() == after.rejection();
            case NOT_FOUND -> true;
        };
    }
    private static boolean sameFact(VoucherReversalObservation before, VoucherReversalObservation after) {
        return before.status() == after.status() && before.revision() == after.revision() && Objects.equals(before.acceptanceReference(), after.acceptanceReference())
                && before.rejection() == after.rejection() && samePosting(before, after);
    }
    private static boolean samePosting(VoucherReversalObservation before, VoucherReversalObservation after) {
        if (before.posting() == null || after.posting() == null) return before.posting() == after.posting();
        var previous = before.posting(); var incoming = after.posting();
        return incoming.preservesReversal(previous) && incoming.current().revision() >= previous.current().revision()
                && !incoming.current().observedAt().isBefore(previous.current().observedAt());
    }
    private Instant retryAt(Instant now) { return now.plusSeconds(Math.min(MAX_BACKOFF_SECONDS, INITIAL_BACKOFF_SECONDS << Math.min(attempts - 1, 6))); }
    private void requireTime(Instant now) { if (now == null || now.isBefore(updatedAt)) throw conflict(); }
    private static DomainException invalid() { return new DomainException("INVALID_VOUCHER_REVERSAL_OPERATION", "Reversal execution must preserve original command, accepted posting and highest external revision"); }
    private static DomainException conflict() { return new DomainException("VOUCHER_REVERSAL_OPERATION_CONFLICT", "Reversal operation can no longer perform this transition"); }
    /**
     * 本地原修订、命令和目的地从登记开始不可替换。
     * @author owlzhangfq@gmail.com
     */
    public record Input(long originalVersion, VoucherReversalCommand command, String targetDigest) {
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
        RESPONSE_TOO_LARGE, LEASE_EXPIRED, INTERNAL_ERROR, EVIDENCE_EXPIRED, SOURCE_CHANGED, STALE_OBSERVATION, INCONSISTENT_OBSERVATION, RECHECK_REQUESTED }
}
