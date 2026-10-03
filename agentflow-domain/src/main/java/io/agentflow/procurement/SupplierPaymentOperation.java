package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceResult;
import io.agentflow.finance.PaymentObservation;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 供应商原授权的银行执行状态，复查、可能发送和原交易查询分别持久化。
 * @author owlzhangfq@gmail.com
 */
public record SupplierPaymentOperation(SupplierPaymentCommand command, long version, Status status, int attempts, int dispatches,
        Instant createdAt, Instant updatedAt, Instant nextAttemptAt, Instant leaseUntil, SupplierPaymentEvidence evidence,
        PaymentObservation observation, PaymentObservation conflictingObservation, long highestRevision, Failure failure) {
    private static final long INITIAL_BACKOFF_SECONDS = 5;
    private static final long MAX_BACKOFF_SECONDS = 300;
    public static final Duration DISPUTE_EVIDENCE_LIFETIME = Duration.ofMinutes(5);

    /** 恢复原命令时保留发送记录与银行事实，不能把超时或查无改写成尚未发送。 */
    public SupplierPaymentOperation {
        if (command == null || version < 1 || status == null || attempts < 0 || dispatches < 0 || dispatches > attempts
                || createdAt == null || updatedAt == null || updatedAt.isBefore(createdAt) || createdAt.isBefore(command.registeredAt()) || highestRevision < 0) throw invalid();
        boolean running = status == Status.CHECKING || status == Status.SENDING || status == Status.QUERYING;
        boolean scheduled = status == Status.QUEUED || status == Status.UNKNOWN;
        if (running ? attempts == 0 || leaseUntil == null || !leaseUntil.isAfter(updatedAt) || failure != null : leaseUntil != null) throw invalid();
        if (scheduled ? nextAttemptAt == null || nextAttemptAt.isBefore(updatedAt) : nextAttemptAt != null) throw invalid();
        if ((observation != null || conflictingObservation != null) && dispatches == 0
                || observation != null && (!command.matches(observation, true, updatedAt) || observation.revision() > highestRevision)
                || conflictingObservation != null && (!command.matches(conflictingObservation, true, updatedAt) || conflictingObservation.revision() > highestRevision)
                || observation == null && conflictingObservation == null && highestRevision != 0) throw invalid();
        if ((status == Status.QUEUED || status == Status.CHECKING || status == Status.SENDING)
                && (highestRevision != 0 || conflictingObservation != null || observation != null && observation.status() != PaymentObservation.Status.NOT_FOUND)) throw invalid();
        if ((status == Status.QUEUED || status == Status.CHECKING) && (evidence != null || dispatches > 0 && observation == null)) throw invalid();
        if (status == Status.SENDING) {
            if (dispatches == 0 || evidence == null || !evidence.matches(command, updatedAt) || dispatches > 1 && observation == null) throw invalid();
            command.requireSendAt(updatedAt);
        }
        if ((status == Status.UNKNOWN || status == Status.QUERYING || status == Status.RECONCILING) && dispatches == 0) throw invalid();
        if (status == Status.SUCCEEDED || status == Status.FAILED || status == Status.REVERSED || status == Status.NOT_FOUND) {
            if (dispatches == 0 || observation == null || !observation.status().name().equals(status.name())
                    || conflictingObservation != null || failure != null || highestRevision != observation.revision()) throw invalid();
        }
        if (status == Status.UNKNOWN && failure == null && (observation == null || observation.status() != PaymentObservation.Status.PENDING)
                || status == Status.RECONCILING && (conflictingObservation == null || failure == null)
                || status == Status.EXPIRED && (failure != Failure.AUTHORIZATION_EXPIRED || highestRevision != 0 || conflictingObservation != null)
                || status == Status.VOIDED && (failure != Failure.SOURCE_CHANGED && failure != Failure.EVIDENCE_CHANGED || highestRevision != 0 || conflictingObservation != null)) throw invalid();
        if (version == 1 && (status != Status.QUEUED || attempts != 0 || dispatches != 0 || !createdAt.equals(updatedAt)
                || !createdAt.equals(nextAttemptAt) || observation != null || conflictingObservation != null || failure != null)) throw invalid();
    }

    /** 保存出纳命令后建立队列，预留或财务授权自身不会自动产生付款指令。 */
    public static SupplierPaymentOperation queue(SupplierPaymentCommand command, Instant now) {
        command.requireSendAt(now);
        return new SupplierPaymentOperation(command, 1, Status.QUEUED, 0, 0, now, now, now, null, null, null, null, 0, null);
    }

    /** 未发送队列先复查，可能外发的记录只领取原交易查询，授权过期不停止查询。 */
    public SupplierPaymentOperation claim(Instant now, Duration lease) {
        requireTime(now);
        if ((status != Status.QUEUED && status != Status.UNKNOWN) || now.isBefore(nextAttemptAt) || lease == null || lease.isZero() || lease.isNegative()) throw conflict();
        if (status == Status.QUEUED && !now.isBefore(command.holdCommand().authorization().expiresAt())) return expireAuthorization(now);
        return new SupplierPaymentOperation(command, Math.incrementExact(version), status == Status.QUEUED ? Status.CHECKING : Status.QUERYING,
                Math.incrementExact(attempts), dispatches, createdAt, now, null, now.plus(lease), evidence, observation, conflictingObservation, highestRevision, null);
    }

    /** 只有本次领取后的新鲜复查可进入可能发送；应用层同时锁定并复核实际批准、授权和任职。 */
    public SupplierPaymentOperation readyToSend(SupplierPaymentEvidence checked, Instant now) {
        requireTime(now); if (status != Status.CHECKING) throw conflict(); if (leaseExpired(now)) return expireLease(now);
        if (!now.isBefore(command.holdCommand().authorization().expiresAt())) return expireAuthorization(now);
        if (checked == null || checked.checkedAt().isBefore(updatedAt) || !checked.matches(command, now)) {
            throw new DomainException("SUPPLIER_PAYMENT_EVIDENCE_CHANGED", "Fresh original payable, hold and cashier accounts are required before sending");
        }
        return new SupplierPaymentOperation(command, Math.incrementExact(version), Status.SENDING, attempts, Math.incrementExact(dispatches),
                createdAt, now, null, leaseUntil, checked, observation, conflictingObservation, highestRevision, null);
    }

    /** 只读阶段故障可以重新读取原选择，不增加发送次数。 */
    public SupplierPaymentOperation unavailableBeforeSend(Failure reason, Instant now) {
        requireTime(now); if (status != Status.CHECKING) throw conflict(); if (leaseExpired(now)) return expireLease(now);
        if (reason == null || reason == Failure.SOURCE_CHANGED || reason == Failure.EVIDENCE_CHANGED || reason == Failure.AUTHORIZATION_EXPIRED) throw conflict();
        return changed(Status.QUEUED, now, retryAt(now), null, null, observation, conflictingObservation, highestRevision, reason);
    }

    /** 原依据变化只停止尚未发送的步骤，原 ERP 预留继续保留，不能据此静默释放应付。 */
    public SupplierPaymentOperation voidBeforeSend(Failure reason, Instant now) {
        requireTime(now);
        if (status != Status.QUEUED && status != Status.CHECKING || reason != Failure.SOURCE_CHANGED && reason != Failure.EVIDENCE_CHANGED) throw conflict();
        return changed(Status.VOIDED, now, null, null, null, observation, conflictingObservation, highestRevision, reason);
    }

    /** 复查租约只重读，可能发送或查询租约到期则仅恢复原交易查询。 */
    public SupplierPaymentOperation expireLease(Instant now) {
        if (!leaseExpired(now)) throw conflict();
        return changed(status == Status.CHECKING ? Status.QUEUED : Status.UNKNOWN, now, now, null,
                status == Status.CHECKING ? null : evidence, observation, conflictingObservation, highestRevision, Failure.LEASE_EXPIRED);
    }

    /** 精确匹配的银行事实才能改变付款结果，冲突记录保持粘性，不能通过普通查询洗掉。 */
    public SupplierPaymentOperation complete(FinanceResult<PaymentObservation> result, Instant now) {
        requireTime(now); if (status != Status.SENDING && status != Status.QUERYING) throw conflict(); if (leaseExpired(now)) return expireLease(now);
        if (!(result instanceof FinanceResult.Success<PaymentObservation> success)) return unavailable(result instanceof FinanceResult.Unavailable<PaymentObservation> problem
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
        return changed(incoming.status() == PaymentObservation.Status.PENDING ? Status.UNKNOWN : Status.valueOf(incoming.status().name()), now,
                incoming.status() == PaymentObservation.Status.PENDING ? retryAt(now) : null, null, evidence, accepted, null, highest, null);
    }

    /** 超时和无效响应不代表未付款，固定原编号进入查询恢复。 */
    public SupplierPaymentOperation unavailable(Failure reason, Instant now) {
        requireTime(now); if (status != Status.SENDING && status != Status.QUERYING) throw conflict(); if (leaseExpired(now)) return expireLease(now);
        return changed(Status.UNKNOWN, now, retryAt(now), null, evidence, observation, conflictingObservation, highestRevision, Objects.requireNonNull(reason));
    }

    /** 已停止新发送也可继续查询；已有争议和最高外部版本不会被重置。 */
    public SupplierPaymentOperation requestQuery(Instant now) {
        requireTime(now); if (running() || status == Status.QUEUED || dispatches == 0) throw conflict();
        return changed(Status.UNKNOWN, now, now, null, evidence, observation, conflictingObservation, highestRevision, Failure.RECHECK_REQUESTED);
    }

    /** 已认证信号优先复查原交易，查无后的排队或账户复查不能继续自动进入重发。 */
    public SupplierPaymentOperation requestCallbackQuery(Instant now) {
        requireTime(now); if (status == Status.SENDING || status == Status.QUERYING || dispatches == 0) throw conflict();
        return changed(Status.UNKNOWN, now, now, null, evidence, observation, conflictingObservation, highestRevision, Failure.RECHECK_REQUESTED);
    }

    /** 权威查无须人工明确重试，同一命令重新核对原预留和账户，仍不得延长授权。 */
    public SupplierPaymentOperation retryNotFound(Instant now) {
        requireTime(now); if (status != Status.NOT_FOUND || highestRevision != 0 || conflictingObservation != null) throw conflict();
        command.requireSendAt(now);
        return changed(Status.QUEUED, now, now, null, null, observation, null, 0, null);
    }

    /** 实际网络调用前再次检查租约、财务授权以及全部短期复查依据。 */
    public void requireSendAt(Instant now) {
        requireTime(now); if (status != Status.SENDING || leaseExpired(now) || !evidence.matches(command, now)) throw conflict();
        command.requireSendAt(now);
    }
    public boolean running() { return status == Status.CHECKING || status == Status.SENDING || status == Status.QUERYING; }
    public boolean leaseExpired(Instant now) { return running() && !leaseUntil.isAfter(now); }
    /** 仅无争议的精确到账结果可以支持后续原 ERP 应付结算。 */
    public boolean settleable() { return status == Status.SUCCEEDED; }

    /** 裁决只采用近期原号查询的最高终态，已经确认的到账及退回必须继续保留。 */
    public ResolutionIssue resolutionIssue(ResolutionHistory history, Instant now) {
        if (status != Status.RECONCILING) return ResolutionIssue.NOT_DISPUTED;
        var candidate = conflictingObservation;
        if (!terminal(candidate)) return ResolutionIssue.NON_TERMINAL;
        if (candidate.revision() != highestRevision || observation != null && candidate.observedAt().isBefore(observation.observedAt())) return ResolutionIssue.STALE_EVIDENCE;
        if (now.isBefore(updatedAt) || !now.isBefore(candidate.observedAt().plus(DISPUTE_EVIDENCE_LIFETIME))) return ResolutionIssue.EXPIRED_EVIDENCE;
        if (history.firstSuccess() != null && !command.matches(history.firstSuccess(), true, now)
                || history.firstReturn() != null && !command.matches(history.firstReturn(), true, now)) return ResolutionIssue.HISTORY_CHANGED;
        if (observation != null && observation.paymentReference() != null && !observation.paymentReference().equals(candidate.paymentReference())) return ResolutionIssue.DIFFERENT_PAYMENT;
        if (candidate.status() == PaymentObservation.Status.FAILED && (history.fundingObserved() || funding(observation))) return ResolutionIssue.FUNDING_ALREADY_OBSERVED;
        var returned = history.firstReturn() != null ? history.firstReturn() : observation != null && observation.status() == PaymentObservation.Status.REVERSED ? observation : null;
        if (returned != null && (candidate.status() != PaymentObservation.Status.REVERSED || !sameSettlement(returned, candidate))) return ResolutionIssue.RETURN_ALREADY_OBSERVED;
        var paid = history.firstSuccess() != null ? history.firstSuccess() : observation != null && observation.status() == PaymentObservation.Status.SUCCEEDED ? observation : null;
        if (paid != null && (candidate.status() == PaymentObservation.Status.SUCCEEDED && !sameSettlement(paid, candidate)
                || candidate.status() == PaymentObservation.Status.REVERSED && (!paid.paymentReference().equals(candidate.paymentReference())
                    || candidate.completedAt().isBefore(paid.completedAt())))) return ResolutionIssue.DIFFERENT_SETTLEMENT;
        return null;
    }

    /** 仅改变原付款的当前核对状态，资金命令、ERP 预留及已保存的核销均不在此处重建。 */
    public SupplierPaymentOperation resolveDispute(PaymentObservation.Status outcome, ResolutionHistory history, Instant now) {
        requireTime(now);
        if (resolutionIssue(history, now) != null || conflictingObservation.status() != outcome) {
            throw new DomainException("SUPPLIER_PAYMENT_DISPUTE_UNRESOLVABLE", "Recent terminal evidence must preserve the original supplier payment and historical funding");
        }
        return changed(Status.valueOf(outcome.name()), now, null, null, evidence, conflictingObservation, null, highestRevision, null);
    }

    private static boolean terminal(PaymentObservation value) {
        return value.status() == PaymentObservation.Status.SUCCEEDED || value.status() == PaymentObservation.Status.FAILED || value.status() == PaymentObservation.Status.REVERSED;
    }
    private static boolean funding(PaymentObservation value) {
        return value != null && (value.status() == PaymentObservation.Status.SUCCEEDED || value.status() == PaymentObservation.Status.REVERSED);
    }

    /**
     * 仓储逐条读取历史后提供最小事实，冲突中曾出现的资金证据也禁止裁决成从未付款。
     * @author owlzhangfq@gmail.com
     */
    public record ResolutionHistory(PaymentObservation firstSuccess, PaymentObservation firstReturn, boolean fundingObserved) {
        /** 首次确认的资金与退回须保持各自终态，不能用其他回执伪装历史。 */
        public ResolutionHistory {
            if (firstSuccess != null && firstSuccess.status() != PaymentObservation.Status.SUCCEEDED
                    || firstReturn != null && firstReturn.status() != PaymentObservation.Status.REVERSED
                    || !fundingObserved && (firstSuccess != null || firstReturn != null)) throw invalid();
        }
    }

    /**
     * 原交易仍不可裁决时给出业务原因，不泄露银行响应原文。
     * @author owlzhangfq@gmail.com
     */
    public enum ResolutionIssue { NOT_DISPUTED, NON_TERMINAL, STALE_EVIDENCE, EXPIRED_EVIDENCE, HISTORY_CHANGED,
        DIFFERENT_PAYMENT, FUNDING_ALREADY_OBSERVED, DIFFERENT_SETTLEMENT, RETURN_ALREADY_OBSERVED }

    private SupplierPaymentOperation expireAuthorization(Instant now) {
        return changed(Status.EXPIRED, now, null, null, null, observation, conflictingObservation, highestRevision, Failure.AUTHORIZATION_EXPIRED);
    }
    private SupplierPaymentOperation reconcile(PaymentObservation incoming, long highest, Failure reason, Instant now) {
        return changed(Status.RECONCILING, now, null, null, evidence, observation, incoming, highest, reason);
    }
    private SupplierPaymentOperation changed(Status next, Instant now, Instant retry, Instant lease, SupplierPaymentEvidence checked,
            PaymentObservation accepted, PaymentObservation disputed, long highest, Failure reason) {
        return new SupplierPaymentOperation(command, Math.incrementExact(version), next, attempts, dispatches, createdAt, now, retry, lease, checked, accepted, disputed, highest, reason);
    }
    private static boolean allowed(PaymentObservation before, PaymentObservation after) {
        if (before.status() == PaymentObservation.Status.NOT_FOUND) return true;
        if (!Objects.equals(before.paymentReference(), after.paymentReference())) return false;
        return switch (before.status()) {
            case PENDING -> after.status() != PaymentObservation.Status.NOT_FOUND;
            case SUCCEEDED -> after.status() == PaymentObservation.Status.SUCCEEDED && sameSettlement(before, after)
                    || after.status() == PaymentObservation.Status.REVERSED && after.paidAmount().equals(before.paidAmount())
                    && after.accountDigest().equals(before.accountDigest()) && !after.completedAt().isBefore(before.completedAt());
            case FAILED -> after.status() == PaymentObservation.Status.FAILED && before.failure() == after.failure();
            case REVERSED -> after.status() == PaymentObservation.Status.REVERSED && sameSettlement(before, after);
            case NOT_FOUND -> true;
        };
    }
    private static boolean sameFact(PaymentObservation before, PaymentObservation after) {
        return before.authorizationId().equals(after.authorizationId()) && before.commandDigest().equals(after.commandDigest())
                && before.status() == after.status() && before.revision().equals(after.revision()) && sameSettlement(before, after) && before.failure() == after.failure();
    }
    private static boolean sameSettlement(PaymentObservation before, PaymentObservation after) {
        return Objects.equals(before.paymentReference(), after.paymentReference()) && Objects.equals(before.paidAmount(), after.paidAmount())
                && Objects.equals(before.accountDigest(), after.accountDigest()) && Objects.equals(before.completedAt(), after.completedAt()) && Objects.equals(before.receiptReference(), after.receiptReference());
    }
    private Instant retryAt(Instant now) { return now.plusSeconds(Math.min(MAX_BACKOFF_SECONDS, INITIAL_BACKOFF_SECONDS << Math.min(attempts - 1, 6))); }
    private void requireTime(Instant now) { if (now == null || now.isBefore(updatedAt)) throw conflict(); }
    private static DomainException invalid() { return new DomainException("INVALID_SUPPLIER_PAYMENT_OPERATION", "Supplier payment recovery must preserve the original command and bank facts"); }
    private static DomainException conflict() { return new DomainException("SUPPLIER_PAYMENT_STATE_CONFLICT", "Supplier payment no longer allows this transition"); }
    /** 日志只保留操作标识和状态，不展开金融资料。 */
    @Override public String toString() { return "SupplierPaymentOperation[id=" + command.id() + ", version=" + version + ", status=" + status + "]"; }

    /**
     * 银行成功与 ERP 结算分开；停止或失败也不会自动释放原应付预留。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { QUEUED, CHECKING, SENDING, UNKNOWN, QUERYING, SUCCEEDED, FAILED, REVERSED, NOT_FOUND, RECONCILING, EXPIRED, VOIDED }

    /**
     * 闭集原因区分复查失败与银行未知结果，不保存远端正文。
     * @author owlzhangfq@gmail.com
     */
    public enum Failure { NOT_CONFIGURED, TARGET_CHANGED, TIMEOUT, CONNECTION, AUTHENTICATION, REMOTE_FAILURE, INVALID_RESPONSE,
        RESPONSE_TOO_LARGE, INTERNAL_ERROR, LEASE_EXPIRED, SOURCE_CHANGED, EVIDENCE_CHANGED, AUTHORIZATION_EXPIRED,
        STALE_OBSERVATION, INCONSISTENT_OBSERVATION, RECHECK_REQUESTED }
}
