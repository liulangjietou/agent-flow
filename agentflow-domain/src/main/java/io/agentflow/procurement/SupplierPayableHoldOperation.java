package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceResult;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 原应付预留的持久执行状态；领取后可能已经发送，丢失响应只能查询原授权。
 * @author owlzhangfq@gmail.com
 */
public record SupplierPayableHoldOperation(SupplierPayableHoldCommand command, long version, Status status, int attempts, int dispatches,
        Instant createdAt, Instant updatedAt, Instant nextAttemptAt, Instant leaseUntil, SupplierPayableHoldObservation observation,
        SupplierPayableHoldObservation conflictingObservation, long highestRevision, Failure failure) {
    private static final long INITIAL_BACKOFF_SECONDS = 5;
    private static final long MAX_BACKOFF_SECONDS = 300;

    /** 从数据库恢复时仍验证状态组合，未知预留不能降级成首次发送，也不能丢弃已有预留事实。 */
    public SupplierPayableHoldOperation {
        if (command == null || version < 1 || status == null || attempts < 0 || dispatches < 0 || dispatches > attempts
                || createdAt == null || updatedAt == null || createdAt.isBefore(command.authorization().authorizedAt())
                || !createdAt.isBefore(command.sendDeadline()) || updatedAt.isBefore(createdAt) || highestRevision < 0
                || dispatches == 0 && (attempts != 0 || observation != null || conflictingObservation != null
                    || status != Status.QUEUED && status != Status.EXPIRED)) throw invalid();
        boolean running = status == Status.RESERVING || status == Status.QUERYING;
        boolean scheduled = status == Status.QUEUED || status == Status.UNKNOWN;
        if (running ? attempts == 0 || dispatches == 0 || leaseUntil == null || !leaseUntil.isAfter(updatedAt) || failure != null : leaseUntil != null) throw invalid();
        if (scheduled ? nextAttemptAt == null || nextAttemptAt.isBefore(updatedAt) : nextAttemptAt != null) throw invalid();
        if (observation != null && (!matches(command, observation, true, createdAt, updatedAt) || observation.revision() > highestRevision)
                || conflictingObservation != null && (!matches(command, conflictingObservation, true, createdAt, updatedAt) || conflictingObservation.revision() > highestRevision)
                || observation == null && conflictingObservation == null && highestRevision != 0) throw invalid();
        if ((status == Status.QUEUED || status == Status.RESERVING || status == Status.EXPIRED)
                && (highestRevision != 0 || conflictingObservation != null || observation != null && observation.status() != SupplierPayableHoldObservation.Status.NOT_FOUND
                    || dispatches > (status == Status.RESERVING ? 1 : 0) && observation == null)) throw invalid();
        if (status == Status.RESERVING) command.requireSendAt(updatedAt);
        if ((status == Status.UNKNOWN || status == Status.RECONCILING) && dispatches == 0) throw invalid();
        if (status == Status.HELD || status == Status.REJECTED || status == Status.NOT_FOUND) {
            if (dispatches == 0 || observation == null || !observation.status().name().equals(status.name()) || conflictingObservation != null
                    || highestRevision != observation.revision() || failure != null) throw invalid();
        }
        if (status == Status.UNKNOWN && failure == null && (observation == null || observation.status() != SupplierPayableHoldObservation.Status.PENDING)
                || status == Status.RECONCILING && (conflictingObservation == null || failure == null)
                || status == Status.EXPIRED && failure != Failure.SEND_WINDOW_EXPIRED
                || status == Status.QUEUED && failure != null) throw invalid();
    }

    /** 业务事务中仅保存队列，提交前不得调用 ERP。 */
    public static SupplierPayableHoldOperation queue(SupplierPayableHoldCommand command, Instant now) {
        command.requireSendAt(now);
        return new SupplierPayableHoldOperation(command, 1, Status.QUEUED, 0, 0, now, now, now, null, null, null, 0, null);
    }

    /** 首次发送持久记录可能发送，未知状态仅领取查询；窗口到期只禁止新的预留。 */
    public SupplierPayableHoldOperation claim(Instant now, Duration lease) {
        requireTime(now);
        if ((status != Status.QUEUED && status != Status.UNKNOWN) || now.isBefore(nextAttemptAt) || lease == null || lease.isNegative() || lease.isZero()) throw conflict();
        if (status == Status.QUEUED && !now.isBefore(command.sendDeadline())) return changed(Status.EXPIRED, now, null, null, observation, conflictingObservation, highestRevision, Failure.SEND_WINDOW_EXPIRED);
        return new SupplierPayableHoldOperation(command, Math.incrementExact(version), status == Status.QUEUED ? Status.RESERVING : Status.QUERYING,
                Math.incrementExact(attempts), status == Status.QUEUED ? Math.incrementExact(dispatches) : dispatches, createdAt, now, null, now.plus(lease), observation, conflictingObservation, highestRevision, null);
    }

    /** 发送前再检查已保存领取和固定证据窗口，不能延长授权或读取依据。 */
    public void requireSendAt(Instant now) {
        requireTime(now); if (status != Status.RESERVING || expired(now)) throw conflict(); command.requireSendAt(now);
    }

    /** 已领取进程崩溃时，后继执行者只查询；迟到结果不覆盖新领取。 */
    public SupplierPayableHoldOperation expire(Instant now) {
        if (!expired(now)) throw conflict();
        return changed(Status.UNKNOWN, now, now, null, observation, conflictingObservation, highestRevision, Failure.LEASE_EXPIRED);
    }

    /** 接受精确原命令、单调外部版本和一致事实；矛盾保留原预留，不借此重新创建。 */
    public SupplierPayableHoldOperation complete(FinanceResult<SupplierPayableHoldObservation> result, Instant now) {
        requireTime(now); if (!running()) throw conflict(); if (expired(now)) return expire(now);
        if (!(result instanceof FinanceResult.Success<SupplierPayableHoldObservation> success)) return unavailable(result instanceof FinanceResult.Unavailable<SupplierPayableHoldObservation> problem
                ? Failure.valueOf(problem.failure().name()) : Failure.INVALID_RESPONSE, now);
        var incoming = success.value();
        if (!matches(command, incoming, status == Status.QUERYING, createdAt, now)) return unavailable(Failure.INVALID_RESPONSE, now);
        long highest = Math.max(highestRevision, incoming.revision());
        if (incoming.revision() < highestRevision) return reconcile(incoming, highest, Failure.STALE_OBSERVATION, now);
        if (conflictingObservation != null || observation != null && (incoming.revision().equals(observation.revision())
                ? !sameFact(observation, incoming) : incoming.observedAt().isBefore(observation.observedAt()) || !allowed(observation, incoming))) {
            return reconcile(incoming, highest, Failure.INCONSISTENT_OBSERVATION, now);
        }
        var accepted = observation != null && sameFact(observation, incoming) && incoming.observedAt().isBefore(observation.observedAt()) ? observation : incoming;
        return changed(incoming.status() == SupplierPayableHoldObservation.Status.PENDING ? Status.UNKNOWN : Status.valueOf(incoming.status().name()), now,
                incoming.status() == SupplierPayableHoldObservation.Status.PENDING ? retryAt(now) : null, null, accepted, null, highest, null);
    }

    /** 传输故障、笼统拒绝或无效响应不能证明 ERP 未预留，恢复继续使用原命令。 */
    public SupplierPayableHoldOperation unavailable(Failure problem, Instant now) {
        requireTime(now); if (!running()) throw conflict(); if (expired(now)) return expire(now);
        return changed(Status.UNKNOWN, now, retryAt(now), null, observation, conflictingObservation, highestRevision, Objects.requireNonNull(problem));
    }

    /** 授权过期仍可查回原预留，查询不会静默清除既有冲突或自动释放余额。 */
    public SupplierPayableHoldOperation requestQuery(Instant now) {
        requireTime(now); if (running() || status == Status.QUEUED || dispatches == 0) throw conflict();
        return changed(Status.UNKNOWN, now, now, null, observation, conflictingObservation, highestRevision, Failure.RECHECK_REQUESTED);
    }

    /** 权威查无也不自动重发；明确重试沿用同一编号和摘要且仍须处于原读取窗口。 */
    public SupplierPayableHoldOperation retryNotFound(Instant now) {
        requireTime(now); if (status != Status.NOT_FOUND || highestRevision != 0 || conflictingObservation != null) throw conflict();
        command.requireSendAt(now);
        return changed(Status.QUEUED, now, now, null, observation, null, 0, null);
    }

    /** 出纳开始付款必须使用刚查询、无矛盾的同一原预留证据，普通页面历史状态不构成当前余额保证。 */
    public void requireHeldAt(Instant now) {
        requireTime(now);
        if (status != Status.HELD || !now.isBefore(observation.observedAt().plus(ProcurementPayablePort.MAX_EVIDENCE_AGE))) {
            throw new DomainException("SUPPLIER_PAYABLE_HOLD_UNAVAILABLE", "A fresh undisputed original payable hold is required");
        }
    }

    public boolean running() { return status == Status.RESERVING || status == Status.QUERYING; }
    public boolean expired(Instant now) { return running() && !leaseUntil.isAfter(now); }

    private SupplierPayableHoldOperation reconcile(SupplierPayableHoldObservation incoming, long highest, Failure reason, Instant now) {
        return changed(Status.RECONCILING, now, null, null, observation, incoming, highest, reason);
    }
    private SupplierPayableHoldOperation changed(Status next, Instant now, Instant retry, Instant lease, SupplierPayableHoldObservation accepted,
                                                 SupplierPayableHoldObservation disputed, long highest, Failure reason) {
        return new SupplierPayableHoldOperation(command, Math.incrementExact(version), next, attempts, dispatches, createdAt, now, retry, lease, accepted, disputed, highest, reason);
    }
    private static boolean allowed(SupplierPayableHoldObservation before, SupplierPayableHoldObservation after) {
        return switch (before.status()) {
            case NOT_FOUND -> true;
            case PENDING -> after.status() != SupplierPayableHoldObservation.Status.NOT_FOUND;
            case HELD, REJECTED -> before.status() == after.status() && sameEvidence(before, after);
        };
    }
    private static boolean sameFact(SupplierPayableHoldObservation before, SupplierPayableHoldObservation after) {
        return before.status() == after.status() && before.revision().equals(after.revision()) && sameEvidence(before, after);
    }
    private static boolean sameEvidence(SupplierPayableHoldObservation before, SupplierPayableHoldObservation after) {
        return Objects.equals(before.holdReference(), after.holdReference()) && Objects.equals(before.ledgerVersion(), after.ledgerVersion())
                && Objects.equals(before.heldAmount(), after.heldAmount()) && Objects.equals(before.accountDigest(), after.accountDigest())
                && Objects.equals(before.heldAt(), after.heldAt()) && before.rejection() == after.rejection();
    }
    private static boolean matches(SupplierPayableHoldCommand command, SupplierPayableHoldObservation evidence, boolean queried, Instant createdAt, Instant now) {
        return evidence.matches(command, queried, now) && !evidence.observedAt().isBefore(createdAt)
                && (evidence.heldAt() == null || !evidence.heldAt().isBefore(createdAt));
    }
    private Instant retryAt(Instant now) { return now.plusSeconds(Math.min(MAX_BACKOFF_SECONDS, INITIAL_BACKOFF_SECONDS << Math.min(attempts - 1, 6))); }
    private void requireTime(Instant now) { if (now == null || now.isBefore(updatedAt)) throw conflict(); }
    private static DomainException invalid() { return new DomainException("INVALID_SUPPLIER_PAYABLE_HOLD_OPERATION", "Payable hold recovery must preserve the original command and external evidence"); }
    private static DomainException conflict() { return new DomainException("SUPPLIER_PAYABLE_HOLD_STATE_CONFLICT", "Payable hold operation no longer allows this transition"); }

    /** 日志仅保存状态与计数，不打印财务依据。 */
    @Override public String toString() { return "SupplierPayableHoldOperation[id=" + command.id() + ", version=" + version + ", status=" + status + "]"; }

    /**
     * 预留成功仍需后继银行支付及独立 ERP 结算；RECONCILING 保留矛盾而不自动释放或换号。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { QUEUED, RESERVING, UNKNOWN, QUERYING, HELD, REJECTED, NOT_FOUND, EXPIRED, RECONCILING }

    /**
     * 依赖和恢复原因采用封闭分类，不保存远端错误正文。
     * @author owlzhangfq@gmail.com
     */
    public enum Failure { NOT_CONFIGURED, TARGET_CHANGED, TIMEOUT, CONNECTION, AUTHENTICATION, REMOTE_FAILURE, INVALID_RESPONSE,
        RESPONSE_TOO_LARGE, LEASE_EXPIRED, INTERNAL_ERROR, SEND_WINDOW_EXPIRED, STALE_OBSERVATION, INCONSISTENT_OBSERVATION, RECHECK_REQUESTED }
}
