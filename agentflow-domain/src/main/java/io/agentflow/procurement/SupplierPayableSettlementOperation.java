package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceResult;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 原供应商应付结算独立恢复；复查、可能核销及原结算查询分别持久化。
 * @author owlzhangfq@gmail.com
 */
public record SupplierPayableSettlementOperation(SupplierPayableSettlementCommand command, long version, Status status, int attempts, int dispatches,
        Instant createdAt, Instant updatedAt, Instant nextAttemptAt, Instant leaseUntil, SupplierPayableSettlementEvidence evidence,
        SupplierPayableSettlementObservation observation, SupplierPayableSettlementObservation conflictingObservation, long highestRevision, Failure failure) {
    private static final long INITIAL_BACKOFF_SECONDS = 5;
    private static final long MAX_BACKOFF_SECONDS = 300;

    /** 恢复原命令时保留发送记录与 ERP 事实，不能把超时或查无改写成尚未发送。 */
    public SupplierPayableSettlementOperation {
        if (command == null || version < 1 || status == null || attempts < 0 || dispatches < 0 || dispatches > attempts
                || createdAt == null || updatedAt == null || updatedAt.isBefore(createdAt) || createdAt.isBefore(command.registeredAt()) || highestRevision < 0) throw invalid();
        boolean running = status == Status.CHECKING || status == Status.SETTLING || status == Status.QUERYING;
        boolean scheduled = status == Status.QUEUED || status == Status.UNKNOWN;
        if (running ? attempts == 0 || leaseUntil == null || !leaseUntil.isAfter(updatedAt) || failure != null : leaseUntil != null) throw invalid();
        if (scheduled ? nextAttemptAt == null || nextAttemptAt.isBefore(updatedAt) : nextAttemptAt != null) throw invalid();
        if ((observation != null || conflictingObservation != null) && dispatches == 0
                || observation != null && (!command.matches(observation, true, updatedAt) || observation.revision() > highestRevision)
                || conflictingObservation != null && (!command.matches(conflictingObservation, true, updatedAt) || conflictingObservation.revision() > highestRevision)
                || observation == null && conflictingObservation == null && highestRevision != 0) throw invalid();
        if ((status == Status.QUEUED || status == Status.CHECKING || status == Status.SETTLING)
                && (highestRevision != 0 || conflictingObservation != null || observation != null && observation.status() != SupplierPayableSettlementObservation.Status.NOT_FOUND)) throw invalid();
        if ((status == Status.QUEUED || status == Status.CHECKING) && (evidence != null || dispatches > 0 && observation == null)) throw invalid();
        if (status == Status.SETTLING) {
            if (dispatches == 0 || evidence == null || !evidence.matches(command, updatedAt) || dispatches > 1 && observation == null) throw invalid();
        }
        if ((status == Status.UNKNOWN || status == Status.QUERYING || status == Status.RECONCILING) && dispatches == 0) throw invalid();
        if (status == Status.SETTLED || status == Status.REJECTED || status == Status.NOT_FOUND) {
            if (dispatches == 0 || observation == null || !observation.status().name().equals(status.name())
                    || conflictingObservation != null || failure != null || highestRevision != observation.revision()) throw invalid();
        }
        if (status == Status.UNKNOWN && failure == null && (observation == null || observation.status() != SupplierPayableSettlementObservation.Status.PENDING)
                || status == Status.RECONCILING && (conflictingObservation == null || failure == null)
                || status == Status.VOIDED && (failure != Failure.SOURCE_CHANGED && failure != Failure.EVIDENCE_CHANGED || highestRevision != 0 || conflictingObservation != null)) throw invalid();
        if (version == 1 && (status != Status.QUEUED || attempts != 0 || dispatches != 0 || !createdAt.equals(updatedAt)
                || !createdAt.equals(nextAttemptAt) || observation != null || conflictingObservation != null || failure != null)) throw invalid();
    }

    /** 财务确认原成功银行修订及指定会计日期后只建立结算队列。 */
    public static SupplierPayableSettlementOperation queue(SupplierPayableSettlementCommand command, Instant now) {
        return new SupplierPayableSettlementOperation(command, 1, Status.QUEUED, 0, 0, now, now, now, null, null, null, null, 0, null);
    }

    /** 未发送队列先复查，可能外发的记录只领取原结算查询，期间关闭不停止查询。 */
    public SupplierPayableSettlementOperation claim(Instant now, Duration lease) {
        requireTime(now);
        if ((status != Status.QUEUED && status != Status.UNKNOWN) || now.isBefore(nextAttemptAt) || lease == null || lease.isZero() || lease.isNegative()) throw conflict();
        return new SupplierPayableSettlementOperation(command, Math.incrementExact(version), status == Status.QUEUED ? Status.CHECKING : Status.QUERYING,
                Math.incrementExact(attempts), dispatches, createdAt, now, null, now.plus(lease), evidence, observation, conflictingObservation, highestRevision, null);
    }

    /** 只有本次领取后的新鲜复查可进入可能发送；应用层同时锁定并复核原银行状态、预留和财务任职。 */
    public SupplierPayableSettlementOperation readyToSend(SupplierPayableSettlementEvidence checked, Instant now) {
        requireTime(now); if (status != Status.CHECKING) throw conflict(); if (leaseExpired(now)) return expireLease(now);
        if (checked == null || checked.checkedAt().isBefore(updatedAt) || !checked.matches(command, now)) {
            throw new DomainException("SUPPLIER_PAYABLE_SETTLEMENT_EVIDENCE_CHANGED", "Fresh original held payable, bank receipt and accounting period are required before settlement");
        }
        return new SupplierPayableSettlementOperation(command, Math.incrementExact(version), Status.SETTLING, attempts, Math.incrementExact(dispatches),
                createdAt, now, null, leaseUntil, checked, observation, conflictingObservation, highestRevision, null);
    }

    /** 只读阶段故障可以重新读取原选择，不增加发送次数。 */
    public SupplierPayableSettlementOperation unavailableBeforeSend(Failure reason, Instant now) {
        requireTime(now); if (status != Status.CHECKING) throw conflict(); if (leaseExpired(now)) return expireLease(now);
        if (reason == null || reason == Failure.SOURCE_CHANGED || reason == Failure.EVIDENCE_CHANGED) throw conflict();
        return changed(Status.QUEUED, now, retryAt(now), null, null, observation, conflictingObservation, highestRevision, reason);
    }

    /** 原依据变化只停止新的结算发送，已到账银行事实及原预留继续保留。 */
    public SupplierPayableSettlementOperation voidBeforeSend(Failure reason, Instant now) {
        requireTime(now);
        if (status != Status.QUEUED && status != Status.CHECKING || reason != Failure.SOURCE_CHANGED && reason != Failure.EVIDENCE_CHANGED) throw conflict();
        return changed(Status.VOIDED, now, null, null, null, observation, conflictingObservation, highestRevision, reason);
    }

    /** 复查租约只重读，可能发送或查询租约到期则仅恢复原交易查询。 */
    public SupplierPayableSettlementOperation expireLease(Instant now) {
        if (!leaseExpired(now)) throw conflict();
        return changed(status == Status.CHECKING ? Status.QUEUED : Status.UNKNOWN, now, now, null,
                status == Status.CHECKING ? null : evidence, observation, conflictingObservation, highestRevision, Failure.LEASE_EXPIRED);
    }

    /** 精确匹配的 ERP 结算事实才能改变结果，冲突不能通过普通查询清除。 */
    public SupplierPayableSettlementOperation complete(FinanceResult<SupplierPayableSettlementObservation> result, Instant now) {
        requireTime(now); if (status != Status.SETTLING && status != Status.QUERYING) throw conflict(); if (leaseExpired(now)) return expireLease(now);
        if (!(result instanceof FinanceResult.Success<SupplierPayableSettlementObservation> success)) return unavailable(result instanceof FinanceResult.Unavailable<SupplierPayableSettlementObservation> problem
                ? Failure.valueOf(problem.failure().name()) : Failure.INVALID_RESPONSE, now);
        var incoming = success.value();
        if (!command.matches(incoming, status == Status.QUERYING, now)) return unavailable(Failure.INVALID_RESPONSE, now);
        long highest = Math.max(highestRevision, incoming.revision());
        if (incoming.revision() < highestRevision) return reconcile(incoming, highest, Failure.STALE_OBSERVATION, now);
        if (conflictingObservation != null || observation != null && (incoming.revision().equals(observation.revision())
                ? !sameFact(observation, incoming) : incoming.observedAt().isBefore(observation.observedAt()) || !allowed(observation, incoming))) {
            return reconcile(incoming, highest, Failure.INCONSISTENT_OBSERVATION, now);
        }
        var accepted = observation != null && sameFact(observation, incoming) && incoming.observedAt().isBefore(observation.observedAt()) ? observation : incoming;
        return changed(incoming.status() == SupplierPayableSettlementObservation.Status.PENDING ? Status.UNKNOWN : Status.valueOf(incoming.status().name()), now,
                incoming.status() == SupplierPayableSettlementObservation.Status.PENDING ? retryAt(now) : null, null, evidence, accepted, null, highest, null);
    }

    /** 超时和无效响应不代表未结算，固定原编号进入查询恢复。 */
    public SupplierPayableSettlementOperation unavailable(Failure reason, Instant now) {
        requireTime(now); if (status != Status.SETTLING && status != Status.QUERYING) throw conflict(); if (leaseExpired(now)) return expireLease(now);
        return changed(Status.UNKNOWN, now, retryAt(now), null, evidence, observation, conflictingObservation, highestRevision, Objects.requireNonNull(reason));
    }

    /** 已停止新发送也可继续查询；已有争议和最高外部版本不会被重置。 */
    public SupplierPayableSettlementOperation requestQuery(Instant now) {
        requireTime(now); if (running() || status == Status.QUEUED || dispatches == 0) throw conflict();
        return changed(Status.UNKNOWN, now, now, null, evidence, observation, conflictingObservation, highestRevision, Failure.RECHECK_REQUESTED);
    }

    /** 权威查无须人工明确重试，同一命令重新核对原预留、银行回单和原记账日期。 */
    public SupplierPayableSettlementOperation retryNotFound(Instant now) {
        requireTime(now); if (status != Status.NOT_FOUND || highestRevision != 0 || conflictingObservation != null) throw conflict();
        return changed(Status.QUEUED, now, now, null, null, observation, null, 0, null);
    }

    /** 实际网络调用前再次检查租约及全部短期复查依据，付款授权到期不阻止已付款核销。 */
    public void requireSendAt(Instant now) {
        requireTime(now); if (status != Status.SETTLING || leaseExpired(now) || !evidence.matches(command, now)) throw conflict();
    }
    public boolean running() { return status == Status.CHECKING || status == Status.SETTLING || status == Status.QUERYING; }
    public boolean leaseExpired(Instant now) { return running() && !leaseUntil.isAfter(now); }
    /** 仅原预留核销及对应付款凭证均确认后表示结算完成。 */
    public boolean settled() { return status == Status.SETTLED; }

    private SupplierPayableSettlementOperation reconcile(SupplierPayableSettlementObservation incoming, long highest, Failure reason, Instant now) {
        return changed(Status.RECONCILING, now, null, null, evidence, observation, incoming, highest, reason);
    }
    private SupplierPayableSettlementOperation changed(Status next, Instant now, Instant retry, Instant lease, SupplierPayableSettlementEvidence checked,
            SupplierPayableSettlementObservation accepted, SupplierPayableSettlementObservation disputed, long highest, Failure reason) {
        return new SupplierPayableSettlementOperation(command, Math.incrementExact(version), next, attempts, dispatches, createdAt, now, retry, lease, checked, accepted, disputed, highest, reason);
    }
    private static boolean allowed(SupplierPayableSettlementObservation before, SupplierPayableSettlementObservation after) {
        return switch (before.status()) {
            case NOT_FOUND -> true;
            case PENDING -> after.status() != SupplierPayableSettlementObservation.Status.NOT_FOUND;
            case SETTLED, REJECTED -> before.status() == after.status() && sameEvidence(before, after);
        };
    }
    private static boolean sameFact(SupplierPayableSettlementObservation before, SupplierPayableSettlementObservation after) {
        return before.status() == after.status() && before.revision().equals(after.revision()) && sameEvidence(before, after);
    }
    private static boolean sameEvidence(SupplierPayableSettlementObservation before, SupplierPayableSettlementObservation after) {
        return Objects.equals(before.posting(), after.posting()) && before.rejection() == after.rejection();
    }
    private Instant retryAt(Instant now) { return now.plusSeconds(Math.min(MAX_BACKOFF_SECONDS, INITIAL_BACKOFF_SECONDS << Math.min(attempts - 1, 6))); }
    private void requireTime(Instant now) { if (now == null || now.isBefore(updatedAt)) throw conflict(); }
    private static DomainException invalid() { return new DomainException("INVALID_SUPPLIER_PAYABLE_SETTLEMENT_OPERATION", "Payable settlement recovery must preserve the original command and posting facts"); }
    private static DomainException conflict() { return new DomainException("SUPPLIER_PAYABLE_SETTLEMENT_STATE_CONFLICT", "Payable settlement no longer allows this transition"); }
    /** 日志只保留操作标识和状态，不展开金融资料。 */
    @Override public String toString() { return "SupplierPayableSettlementOperation[id=" + command.id() + ", version=" + version + ", status=" + status + "]"; }

    /**
     * 结算终态独立于银行成功；停止或拒绝均不自动释放原预留或重建银行付款。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { QUEUED, CHECKING, SETTLING, UNKNOWN, QUERYING, SETTLED, REJECTED, NOT_FOUND, RECONCILING, VOIDED }

    /**
     * 闭集原因区分复查失败与结算未知结果，不保存远端正文。
     * @author owlzhangfq@gmail.com
     */
    public enum Failure { NOT_CONFIGURED, TARGET_CHANGED, TIMEOUT, CONNECTION, AUTHENTICATION, REMOTE_FAILURE, INVALID_RESPONSE,
        RESPONSE_TOO_LARGE, INTERNAL_ERROR, LEASE_EXPIRED, SOURCE_CHANGED, EVIDENCE_CHANGED,
        STALE_OBSERVATION, INCONSISTENT_OBSERVATION, RECHECK_REQUESTED }
}
