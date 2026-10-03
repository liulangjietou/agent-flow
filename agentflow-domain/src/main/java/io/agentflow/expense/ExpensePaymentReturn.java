package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.ExpensePaymentReturnPort;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.util.UUID;

/**
 * 独立财务采纳的累计报销退回，实际资金与原费用核销事实分别保留。
 * @author owlzhangfq@gmail.com
 */
public record ExpensePaymentReturn(UUID id, String tenantId, UUID checkId, ExpensePaymentReturnPort.Receipt receipt,
                                   String registeredBy, Instant registeredAt, String evidenceReference, String reason) {
    /** 申请人和原出纳不得自行登记，人工说明不能代替完整入款和应付分录。 */
    public ExpensePaymentReturn {
        if (id == null || checkId == null || StringUtils.isBlank(tenantId) || tenantId.length() > 64 || receipt == null
                || !tenantId.equals(receipt.request().command().tenantId()) || receipt.status() == ExpensePaymentReturnPort.Status.UNRESOLVED
                || !receipt.matches(receipt.request(), registeredAt) || StringUtils.isBlank(registeredBy) || registeredBy.length() > 128
                || registeredBy.equals(receipt.request().command().payee().employeeId()) || registeredBy.equals(receipt.request().command().authorization().executedBy())
                || StringUtils.isBlank(evidenceReference) || evidenceReference.length() > 128 || evidenceReference.chars().anyMatch(Character::isISOControl)
                || StringUtils.isBlank(reason) || reason.length() > 2000) throw invalid();
        evidenceReference = evidenceReference.trim(); reason = reason.trim();
    }

    /** 实际退回只冻结后续结算，已消耗的预算、发票和借款冲销均保持原样。 */
    public ExpenseSettlement applyTo(ExpenseSettlement settlement) {
        requireSettlement(settlement);
        return receipt.returns().isEmpty() ? settlement : settlement.requireReview("EXPENSE_PAYMENT_RETURNED", registeredAt);
    }

    /** 只能影响原成功付款对应的原轮次结算，不能借当前单据修改历史到账金额。 */
    public void requireSettlement(ExpenseSettlement settlement) {
        if (settlement == null) throw invalid();
        var input = settlement.input(); var source = input.source(); var payment = input.payment();
        var command = receipt.request().command(); var binding = command.binding(); var original = receipt.request().original();
        if (payment == null || !source.tenantId().equals(tenantId) || !source.businessId().equals(binding.businessId())
                || !source.applicationId().equals(binding.applicationId()) || source.roundNo() != binding.roundNo()
                || source.applicationVersion() != binding.applicationVersion() || source.businessVersion() != binding.businessVersion()
                || !source.employeeId().equals(command.payee().employeeId()) || !payment.operationId().equals(command.id())
                || !payment.commandDigest().equals(command.digest()) || !input.payable().equals(command.amount())
                || !payment.paymentReference().equals(original.paymentReference()) || !payment.receiptReference().equals(original.receiptReference())
                || !payment.completedAt().equals(original.completedAt()) || registeredAt.isBefore(settlement.updatedAt())) throw invalid();
    }

    @Override public String toString() { return "ExpensePaymentReturn[id=" + id + ", paymentId=" + receipt.request().command().id() + "]"; }
    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_PAYMENT_RETURN", "Expense payment return requires fresh original evidence and an independent finance registration"); }
}
