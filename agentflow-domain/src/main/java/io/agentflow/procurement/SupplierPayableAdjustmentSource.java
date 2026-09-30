package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 实际回款后的独立账务来源，原预留、原核销与每次新增入款分别保留。
 * @author owlzhangfq@gmail.com
 */
public record SupplierPayableAdjustmentSource(SupplierPaymentReturns returns, OriginalSettlement settlement, Previous previous) {
    /** 未核销时首次调整同时确认原付款与回款；后续调整只能处理尚未入账的新资金。 */
    public SupplierPayableAdjustmentSource {
        if (returns == null || returns.entries().isEmpty()) throw invalid();
        var payment = returns.request().command();
        if (settlement != null && (!settlement.command().payment().equals(payment)
                || !settlement.command().paid().paymentReference().equals(returns.request().original().paymentReference())
                || !settlement.command().paid().receiptReference().equals(returns.request().original().receiptReference())
                || !settlement.command().paid().completedAt().equals(returns.request().original().completedAt()))) throw invalid();
        if (previous != null) {
            if (!previous.paymentId().equals(payment.id()) || !previous.paymentDigest().equals(payment.digest())
                    || !Objects.equals(previous.originalSettlementId(), settlement == null ? null : settlement.command().id())
                    || !returns.entries().containsAll(previous.entries()) || returns.entries().size() <= previous.entries().size()
                    || !previous.observation().posting().holdReference().equals(payment.held().holdReference())
                    || !previous.observation().posting().netPaid().plus(previous.observation().posting().totalReturned()).equals(payment.amount())
                    || settlement != null && !previous.observation().posting().recognitionVoucherReference().equals(settlement.observation().posting().voucherReference())) throw invalid();
        }
    }

    /** 第一次且原应付未核销时，ERP 原子结清原预留、确认原付款并登记回款。 */
    public boolean recognizesOriginalPayment() { return settlement == null && previous == null; }
    /** 每笔真实入款只允许在一次独立 ERP 调整中出现。 */
    public List<SupplierPaymentReturns.Entry> newReturns() {
        return previous == null ? returns.entries() : returns.entries().stream().filter(entry -> !previous.entries().contains(entry)).toList();
    }
    public Money newReturned() { return total(newReturns(), returns.request().command().amount().currency()); }
    public Money netPaid() { return returns.request().command().amount().minus(returns.totalReturned()); }
    /** 登记和发送都不能早于原资金、原核销及上一次账务事实。 */
    public Instant latestFactAt() {
        var latest = returns.updatedAt();
        if (settlement != null && settlement.observation().observedAt().isAfter(latest)) latest = settlement.observation().observedAt();
        if (previous != null && previous.observation().observedAt().isAfter(latest)) latest = previous.observation().observedAt();
        return latest;
    }
    private static Money total(List<SupplierPaymentReturns.Entry> entries, String currency) {
        return entries.stream().map(entry -> entry.proof().amount()).reduce(Money.zero(currency), Money::plus);
    }
    private static DomainException invalid() { return new DomainException("INVALID_SUPPLIER_PAYABLE_ADJUSTMENT_SOURCE", "Supplier adjustment must preserve the original payment, settlement and previously accounted bank receipts"); }
    @Override public String toString() { return "SupplierPayableAdjustmentSource[paymentId=" + returns.request().command().id() + "]"; }

    /**
     * 首次成功的原核销修订不可被当前查询覆盖，过期不撤销历史记账事实。
     * @author owlzhangfq@gmail.com
     */
    public record OriginalSettlement(long version, SupplierPayableSettlementCommand command, SupplierPayableSettlementObservation observation) {
        public OriginalSettlement {
            if (version < 1 || command == null || observation == null || observation.status() != SupplierPayableSettlementObservation.Status.SETTLED
                    || !command.matches(observation, true, observation.observedAt())) throw invalid();
        }
        @Override public String toString() { return "OriginalSupplierSettlement[operationId=" + command.id() + "]"; }
    }

    /**
     * 上一次已完成调整的扁平引用，保存累计入款而不递归嵌套全部历史命令。
     * @author owlzhangfq@gmail.com
     */
    public record Previous(UUID paymentId, String paymentDigest, UUID originalSettlementId, long version,
                           List<SupplierPaymentReturns.Entry> entries, SupplierPayableAdjustmentObservation observation) {
        public Previous {
            if (paymentId == null || paymentDigest == null || !paymentDigest.matches("[a-f0-9]{64}") || version < 1
                    || entries == null || entries.isEmpty() || entries.size() > SupplierPaymentReturnPort.MAX_RETURN_ENTRIES
                    || entries.stream().anyMatch(Objects::isNull) || observation == null || observation.status() != SupplierPayableAdjustmentObservation.Status.ADJUSTED
                    || entries.stream().map(entry -> entry.proof().transactionReference()).distinct().count() != entries.size()) throw invalid();
            entries = List.copyOf(entries);
            var posting = observation.posting();
            if (!total(entries, posting.totalReturned().currency()).equals(posting.totalReturned())
                    || entries.stream().anyMatch(entry -> entry.proof().receivedAt().isAfter(posting.adjustedAt()))) throw invalid();
            for (var line : posting.entries()) if (entries.stream().noneMatch(entry -> entry.proof().transactionReference().equals(line.transactionReference())
                    && entry.proof().amount().equals(line.amount()))) throw invalid();
        }
        /** 新鲜查询只更新观察修订，不能改写已确认的原调整凭据或累计金额。 */
        public boolean matches(SupplierPayableAdjustmentObservation current) {
            return current != null && current.operationId().equals(observation.operationId()) && current.commandDigest().equals(observation.commandDigest())
                    && current.status() == SupplierPayableAdjustmentObservation.Status.ADJUSTED && current.revision() >= observation.revision()
                    && !current.observedAt().isBefore(observation.observedAt()) && current.posting().equals(observation.posting());
        }
        @Override public String toString() { return "PreviousSupplierAdjustment[operationId=" + observation.operationId() + "]"; }
    }
}
