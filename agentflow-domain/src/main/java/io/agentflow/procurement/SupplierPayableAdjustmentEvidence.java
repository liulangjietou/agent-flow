package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.AccountingPeriodPort;
import java.time.Instant;

/**
 * 调整前事务外重读实际入款、原预留或已记账来源和期间，不延长任一旧观察窗口。
 * @author owlzhangfq@gmail.com
 */
public record SupplierPayableAdjustmentEvidence(SupplierPaymentReturnPort.Receipt bank, SupplierPayableHoldObservation hold,
        SupplierPayableSettlementObservation settlement, SupplierPayableAdjustmentObservation previous,
        AccountingPeriodPort.OpenPeriod period, Instant checkedAt) {
    public SupplierPayableAdjustmentEvidence {
        if (bank == null || period == null || checkedAt == null || !bank.matches(bank.request(), checkedAt)
                || bank.status() != SupplierPaymentReturnPort.Status.PARTIALLY_RETURNED && bank.status() != SupplierPaymentReturnPort.Status.RETURNED
                || !period.matches(period.request(), checkedAt) || !fresh(period.observedAt(), checkedAt)
                || hold != null && (hold.status() != SupplierPayableHoldObservation.Status.HELD || !fresh(hold.observedAt(), checkedAt))
                || settlement != null && (settlement.status() != SupplierPayableSettlementObservation.Status.SETTLED || !fresh(settlement.observedAt(), checkedAt))
                || previous != null && (previous.status() != SupplierPayableAdjustmentObservation.Status.ADJUSTED || !fresh(previous.observedAt(), checkedAt))) throw changed();
    }
    /** 原来源只能按固定事实复查，不接收未登记的新入款或另一个核销凭证。 */
    public static SupplierPayableAdjustmentEvidence checked(SupplierPayableAdjustmentCommand command, SupplierPaymentReturnPort.Receipt bank,
            SupplierPayableHoldObservation hold, SupplierPayableSettlementObservation settlement, SupplierPayableAdjustmentObservation previous,
            AccountingPeriodPort.OpenPeriod period, Instant now) {
        var evidence = new SupplierPayableAdjustmentEvidence(bank, hold, settlement, previous, period, now);
        if (!evidence.matches(command, now)) throw changed(); return evidence;
    }
    /** 只有未变更的原件及原会计日期可以越过发送门槛，过期后仍允许查询已发送命令。 */
    public boolean matches(SupplierPayableAdjustmentCommand command, Instant now) {
        return command != null && !checkedAt.isBefore(command.registeredAt()) && matchesSource(command.source(), command.period(), now);
    }
    // 准备读取先于正式命令登记；来源核对相同，发送阶段另要求登记后的新复查。
    boolean matchesSource(SupplierPayableAdjustmentSource source, AccountingPeriodPort.OpenPeriod intendedPeriod, Instant now) {
        if (now == null || now.isBefore(checkedAt) || !now.isBefore(validUntil())) return false;
        var request = source.returns().request();
        if (!bank.matches(request, now) || bank.observedAt().isBefore(source.returns().updatedAt()) || bank.returns().size() != source.returns().entries().size()
                || !bank.returns().containsAll(source.returns().entries().stream().map(SupplierPaymentReturns.Entry::proof).toList())
                || !period.matches(intendedPeriod.request(), now) || !period.periodReference().equals(intendedPeriod.periodReference())
                || period.observedAt().isBefore(intendedPeriod.observedAt()) || (hold != null) != source.recognizesOriginalPayment()
                || (settlement != null) != (source.settlement() != null) || (previous != null) != (source.previous() != null)) return false;
        if (hold != null && !request.command().matchesHold(hold, now)) return false;
        if (settlement != null) {
            var original = source.settlement();
            if (!original.command().matches(settlement, true, now) || settlement.revision() < original.observation().revision()
                    || settlement.observedAt().isBefore(original.observation().observedAt()) || !settlement.posting().equals(original.observation().posting())) return false;
        }
        return previous == null || source.previous().matches(previous);
    }
    /** 依据窗口取实际银行、期间与各 ERP 观察最早到期点。 */
    public Instant validUntil() {
        var end = bank.validUntil().isBefore(period.validUntil()) ? bank.validUntil() : period.validUntil();
        var periodEnd = period.observedAt().plus(ProcurementPayablePort.MAX_EVIDENCE_AGE); if (periodEnd.isBefore(end)) end = periodEnd;
        for (var observed : new Instant[] { hold == null ? null : hold.observedAt(), settlement == null ? null : settlement.observedAt(), previous == null ? null : previous.observedAt() }) {
            if (observed != null && observed.plus(ProcurementPayablePort.MAX_EVIDENCE_AGE).isBefore(end)) end = observed.plus(ProcurementPayablePort.MAX_EVIDENCE_AGE);
        }
        return end;
    }
    private static boolean fresh(Instant observed, Instant now) { return !observed.isAfter(now) && observed.plus(ProcurementPayablePort.MAX_EVIDENCE_AGE).isAfter(now); }
    private static DomainException changed() { return new DomainException("SUPPLIER_PAYABLE_ADJUSTMENT_EVIDENCE_CHANGED", "Fresh bank receipts, original ERP facts and the fixed accounting period are required"); }
    @Override public String toString() { return "SupplierPayableAdjustmentEvidence[checkedAt=" + checkedAt + "]"; }
}
