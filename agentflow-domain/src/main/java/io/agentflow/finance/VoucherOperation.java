package io.agentflow.finance;

import io.agentflow.common.DomainException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 凭证副作用的持久状态机，保留最高外部版本和既有事实，未知结果只查询原操作。
 * @author owlzhangfq@gmail.com
 */
public record VoucherOperation(Input input, long version, Status status, int attempts, Instant createdAt, Instant updatedAt,
                               Instant nextAttemptAt, Instant leaseUntil, VoucherObservation observation,
                               VoucherObservation conflictingObservation, long highestRevision, Failure failure) {
    private static final long INITIAL_BACKOFF_SECONDS = 5;
    private static final long MAX_BACKOFF_SECONDS = 300;

    /** 重启不能把曾经发送或已过账的操作还原成可再次首次发送。 */
    public VoucherOperation {
        if (input == null || status == null || version < 1 || attempts < 0 || createdAt == null || updatedAt == null || updatedAt.isBefore(createdAt)
                || createdAt.isBefore(input.command().createdAt()) || highestRevision < 0) throw invalid();
        boolean running = status == Status.POSTING || status == Status.QUERYING;
        boolean scheduled = status == Status.QUEUED || status == Status.UNKNOWN;
        if (running ? attempts == 0 || leaseUntil == null || !leaseUntil.isAfter(updatedAt) || failure != null : leaseUntil != null) throw invalid();
        if (scheduled ? nextAttemptAt == null || nextAttemptAt.isBefore(updatedAt) : nextAttemptAt != null) throw invalid();
        if (observation != null && (!observation.matches(input.command(), true, updatedAt) || observation.revision() > highestRevision)
                || conflictingObservation != null && (!conflictingObservation.matches(input.command(), true, updatedAt) || conflictingObservation.revision() > highestRevision)
                || observation == null && highestRevision != 0 && conflictingObservation == null) throw invalid();
        if ((status == Status.QUEUED || status == Status.POSTING) && (conflictingObservation != null || highestRevision != 0 || failure != null
                || attempts == 0 && observation != null || observation != null && observation.status() != VoucherObservation.Status.NOT_FOUND)) throw invalid();
        if (status == Status.QUEUED && attempts > 0 && observation == null || status == Status.POSTING && attempts > 1 && observation == null) throw invalid();
        if (status == Status.POSTED || status == Status.FAILED || status == Status.REVERSED || status == Status.NOT_FOUND) {
            if (observation == null || !observation.status().name().equals(status.name()) || conflictingObservation != null || failure != null
                    || highestRevision != observation.revision()) throw invalid();
        }
        if (status == Status.UNKNOWN && failure == null && (observation == null || observation.status() != VoucherObservation.Status.PENDING)
                || status == Status.RECONCILING && (conflictingObservation == null || failure == null)
                || status == Status.EXPIRED && (failure != Failure.EVIDENCE_EXPIRED || highestRevision != 0 || conflictingObservation != null
                    || observation != null && observation.status() != VoucherObservation.Status.NOT_FOUND)
                || status == Status.VOIDED && (failure != Failure.SOURCE_CHANGED || highestRevision != 0 || conflictingObservation != null
                    || observation != null && observation.status() != VoucherObservation.Status.NOT_FOUND)) throw invalid();
    }

    /** 只登记原始命令，事务提交前不能有 ERP 副作用。 */
    public static VoucherOperation queue(Input input, Instant now) {
        input.command().requireSendAt(now);
        return new VoucherOperation(input, 1, Status.QUEUED, 0, now, now, now, null, null, null, 0, null);
    }

    /** 首次发送和明确查无后人工重发以外，所有恢复都使用只读查询。 */
    public VoucherOperation claim(Instant now, Duration lease) {
        requireTime(now);
        if ((status != Status.QUEUED && status != Status.UNKNOWN) || now.isBefore(nextAttemptAt) || lease.isZero() || lease.isNegative()) throw conflict();
        if (status == Status.QUEUED && !now.isBefore(input.command().expiresAt())) return changed(Status.EXPIRED, now, null, observation, conflictingObservation, highestRevision, Failure.EVIDENCE_EXPIRED);
        return new VoucherOperation(input, Math.incrementExact(version), status == Status.QUEUED ? Status.POSTING : Status.QUERYING,
                Math.incrementExact(attempts), createdAt, now, null, now.plus(lease), observation, conflictingObservation, highestRevision, null);
    }

    /** 领取超时无法证明 ERP 未执行，保留原过账事实和最高版本，立即转查询。 */
    public VoucherOperation expire(Instant now) {
        if (!expired(now)) throw conflict();
        return changed(Status.UNKNOWN, now, now, observation, conflictingObservation, highestRevision, Failure.LEASE_EXPIRED);
    }

    /** 当前有效领取才能接收结果，事实倒退或矛盾不会覆盖先前已经确认的过账。 */
    public VoucherOperation complete(FinanceResult<VoucherObservation> result, Instant now) {
        requireTime(now); if (!running()) throw conflict(); if (expired(now)) return expire(now);
        if (!(result instanceof FinanceResult.Success<VoucherObservation> success)) {
            return unavailable(result instanceof FinanceResult.Unavailable<VoucherObservation> problem
                    ? Failure.valueOf(problem.failure().name()) : Failure.INVALID_RESPONSE, now);
        }
        var incoming = success.value();
        if (!incoming.matches(input.command(), status == Status.QUERYING, now)) return unavailable(Failure.INVALID_RESPONSE, now);
        long highest = Math.max(highestRevision, incoming.revision());
        if (incoming.revision() < highestRevision) return reconcile(incoming, highest, Failure.STALE_OBSERVATION, now);
        if (conflictingObservation != null || observation != null && (incoming.revision() == observation.revision()
                ? !sameFact(observation, incoming) : incoming.observedAt().isBefore(observation.observedAt()) || !allowed(observation, incoming))) {
            return reconcile(incoming, highest, Failure.INCONSISTENT_OBSERVATION, now);
        }
        var accepted = observation != null && sameFact(observation, incoming) && incoming.observedAt().isBefore(observation.observedAt()) ? observation : incoming;
        return switch (incoming.status()) {
            case PENDING -> changed(Status.UNKNOWN, now, retryAt(now), accepted, null, highest, null);
            case POSTED -> changed(Status.POSTED, now, null, accepted, null, highest, null);
            case FAILED -> changed(Status.FAILED, now, null, accepted, null, highest, null);
            case REVERSED -> changed(Status.REVERSED, now, null, accepted, null, highest, null);
            case NOT_FOUND -> changed(Status.NOT_FOUND, now, null, accepted, null, highest, null);
        };
    }

    /** 本地或传输失败仍是未知，不产生未过账保证。 */
    public VoucherOperation unavailable(Failure problem, Instant now) {
        requireTime(now); if (!running()) throw conflict(); if (expired(now)) return expire(now);
        return changed(Status.UNKNOWN, now, retryAt(now), observation, conflictingObservation, highestRevision, Objects.requireNonNull(problem));
    }

    /** 显式对账可查询终态，但不能改变原命令或清除矛盾记录。 */
    public VoucherOperation requestQuery(Instant now) {
        requireTime(now); if (running() || status == Status.QUEUED) throw conflict();
        return changed(Status.UNKNOWN, now, now, observation, conflictingObservation, highestRevision, Failure.RECHECK_REQUESTED);
    }

    /** 只有权威查无且从未见过外部交易版本，才允许明确重发原编号和原内容。 */
    public VoucherOperation retryNotFound(Instant now) {
        requireTime(now); if (status != Status.NOT_FOUND || highestRevision != 0 || conflictingObservation != null) throw conflict();
        input.command().requireSendAt(now);
        return changed(Status.QUEUED, now, now, observation, null, highestRevision, null);
    }

    /** 仅尚未发送或原操作权威查无的排队命令，允许因批准依据变化而停止发送。 */
    public VoucherOperation voidBeforeSend(Instant now) {
        requireTime(now); if (status != Status.QUEUED) throw conflict();
        return changed(Status.VOIDED, now, null, observation, conflictingObservation, highestRevision, Failure.SOURCE_CHANGED);
    }

    public boolean running() { return status == Status.POSTING || status == Status.QUERYING; }
    public boolean expired(Instant now) { return running() && !leaseUntil.isAfter(now); }
    /** 待对账、冲销和读取中的既有凭证不能作为新的付款授权依据。 */
    public boolean usablePosted() { return status == Status.POSTED; }

    private VoucherOperation reconcile(VoucherObservation incoming, long highest, Failure problem, Instant now) {
        return changed(Status.RECONCILING, now, null, observation, incoming, highest, problem);
    }
    private VoucherOperation changed(Status next, Instant now, Instant retryAt, VoucherObservation accepted, VoucherObservation disputed, long highest, Failure problem) {
        requireTime(now);
        return new VoucherOperation(input, Math.incrementExact(version), next, attempts, createdAt, now, retryAt, null, accepted, disputed, highest, problem);
    }
    private static boolean allowed(VoucherObservation before, VoucherObservation after) {
        if (before.status() == VoucherObservation.Status.NOT_FOUND) return true;
        if (!Objects.equals(before.postingReference(), after.postingReference())) return false;
        return switch (before.status()) {
            case PENDING -> after.status() != VoucherObservation.Status.NOT_FOUND;
            case POSTED -> (after.status() == VoucherObservation.Status.POSTED || after.status() == VoucherObservation.Status.REVERSED) && samePosting(before, after);
            case FAILED -> after.status() == VoucherObservation.Status.FAILED && before.failure() == after.failure();
            case REVERSED -> after.status() == VoucherObservation.Status.REVERSED && samePosting(before, after);
            case NOT_FOUND -> true;
        };
    }
    private static boolean sameFact(VoucherObservation before, VoucherObservation after) {
        return before.operationId().equals(after.operationId()) && before.commandDigest().equals(after.commandDigest()) && before.status() == after.status()
                && before.revision().equals(after.revision()) && samePosting(before, after) && before.failure() == after.failure();
    }
    private static boolean samePosting(VoucherObservation before, VoucherObservation after) {
        return Objects.equals(before.postingReference(), after.postingReference()) && Objects.equals(before.voucherReference(), after.voucherReference())
                && Objects.equals(before.periodReference(), after.periodReference()) && Objects.equals(before.accountingDate(), after.accountingDate())
                && Objects.equals(before.debitTotal(), after.debitTotal()) && Objects.equals(before.creditTotal(), after.creditTotal()) && Objects.equals(before.postedAt(), after.postedAt());
    }
    private Instant retryAt(Instant now) { return now.plusSeconds(Math.min(MAX_BACKOFF_SECONDS, INITIAL_BACKOFF_SECONDS << Math.min(attempts - 1, 6))); }
    private void requireTime(Instant now) { if (now == null || now.isBefore(updatedAt)) throw conflict(); }
    private static DomainException invalid() { return new DomainException("INVALID_VOUCHER_OPERATION", "Voucher operation must preserve original input, external revisions and recovery state"); }
    private static DomainException conflict() { return new DomainException("VOUCHER_OPERATION_STATE_CONFLICT", "Voucher operation can no longer perform the requested transition"); }

    /**
     * 命令和财务目标一旦登记就不可替换。
     * @author owlzhangfq@gmail.com
     */
    public record Input(VoucherCommand command, String targetDigest) {
        /** 目标摘要来自服务端配置，不能由 ERP 回执更换。 */
        public Input { if (command == null || targetDigest == null || !targetDigest.matches("[a-f0-9]{64}")) throw invalid(); }
    }
    /**
     * 查无不自动重发，矛盾和冲销必须保留给财务对账。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { QUEUED, POSTING, QUERYING, UNKNOWN, POSTED, FAILED, NOT_FOUND, EXPIRED, VOIDED, RECONCILING, REVERSED }
    /**
     * 只存封闭错误码，不存远端响应正文或账户信息。
     * @author owlzhangfq@gmail.com
     */
    public enum Failure { NOT_CONFIGURED, TARGET_CHANGED, TIMEOUT, CONNECTION, AUTHENTICATION, REMOTE_FAILURE, INVALID_RESPONSE,
        RESPONSE_TOO_LARGE, LEASE_EXPIRED, INTERNAL_ERROR, EVIDENCE_EXPIRED, SOURCE_CHANGED, STALE_OBSERVATION, INCONSISTENT_OBSERVATION, RECHECK_REQUESTED }
}
