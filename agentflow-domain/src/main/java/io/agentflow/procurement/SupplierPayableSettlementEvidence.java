package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.AccountingPeriodPort;
import io.agentflow.finance.PaymentObservation;
import java.time.Instant;

/**
 * 结算前重新读取同一原预留、银行成功回单及指定期间，短期依据不改写原结算命令。
 * @author owlzhangfq@gmail.com
 */
public record SupplierPayableSettlementEvidence(SupplierPayableHoldObservation hold, PaymentObservation paid,
        AccountingPeriodPort.OpenPeriod period, Instant checkedAt) {
    /** 三项远端观察均不得来自未来或超过五分钟，期间必须仍开放。 */
    public SupplierPayableSettlementEvidence {
        if (hold == null || paid == null || period == null || checkedAt == null || hold.status() != SupplierPayableHoldObservation.Status.HELD
                || paid.status() != PaymentObservation.Status.SUCCEEDED || hold.observedAt().isAfter(checkedAt) || paid.observedAt().isAfter(checkedAt)
                || period.observedAt().isAfter(checkedAt) || !period.validUntil().isAfter(checkedAt)
                || !hold.observedAt().plus(ProcurementPayablePort.MAX_EVIDENCE_AGE).isAfter(checkedAt)
                || !paid.observedAt().plus(ProcurementPayablePort.MAX_EVIDENCE_AGE).isAfter(checkedAt)
                || !period.observedAt().plus(ProcurementPayablePort.MAX_EVIDENCE_AGE).isAfter(checkedAt)) throw changed();
    }
    /** 只接收与原固定结算意图对应的复查结果。 */
    public static SupplierPayableSettlementEvidence checked(SupplierPayableSettlementCommand command, SupplierPayableHoldObservation hold,
            PaymentObservation paid, AccountingPeriodPort.OpenPeriod period, Instant now) {
        var evidence = new SupplierPayableSettlementEvidence(hold, paid, period, now);
        if (!evidence.matches(command, now)) throw changed();
        return evidence;
    }
    /** 新的期间观察可更新版本，但不能换法人、币种、记账日期或期间。 */
    public boolean matches(SupplierPayableSettlementCommand command, Instant now) {
        return command != null && now != null && !now.isBefore(checkedAt) && !checkedAt.isBefore(command.registeredAt()) && now.isBefore(validUntil())
                && command.payment().matchesHold(hold, now) && command.matchesPaid(paid, now)
                && period.matches(command.period().request(), now) && period.periodReference().equals(command.period().periodReference())
                && !period.observedAt().isBefore(command.period().observedAt());
    }
    /** 取所有原观察窗口的最早到期点，收到响应不延长旧依据。 */
    public Instant validUntil() {
        var deadline = period.validUntil();
        for (var observed : new Instant[] { hold.observedAt(), paid.observedAt(), period.observedAt() }) {
            var candidate = observed.plus(ProcurementPayablePort.MAX_EVIDENCE_AGE); if (candidate.isBefore(deadline)) deadline = candidate;
        }
        return deadline;
    }
    private static DomainException changed() { return new DomainException("SUPPLIER_PAYABLE_SETTLEMENT_EVIDENCE_CHANGED", "Original held payable, paid bank receipt and accounting period must remain fresh and consistent"); }
    /** 不在日志展开原交易资料。 */
    @Override public String toString() { return "SupplierPayableSettlementEvidence[checkedAt=" + checkedAt + "]"; }
}
