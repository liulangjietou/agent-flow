package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.expense.ExpensePaymentReturn;
import io.agentflow.expense.ExpensePaymentReturns;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;

/**
 * 部分调整采用原付款的完整已登记入款；此前使用记录必须由应用服务从真实完成调整恢复。
 * @author owlzhangfq@gmail.com
 */
public record ExpenseAdjustmentFundingSource(ExpenseAdjustmentFinancialSource financial, ExpensePaymentReturns returns,
        ExpensePaymentReturn latestRegistration, PaymentOperation payment, VoucherOperation paymentVoucher,
        VoucherReversalRecord paymentVoucherReversal, List<ExpensePaymentReturns.Entry> previousReturns,
        List<ExpensePaymentReturns.Entry> selectedReturns) {
    /** 原银行、累计登记、付款会计及前后净应付同时一致；不拆分或改写原银行流水。 */
    public ExpenseAdjustmentFundingSource {
        if (financial == null || invalidEntries(previousReturns) || invalidEntries(selectedReturns)) throw changed();
        previousReturns = List.copyOf(previousReturns); selectedReturns = List.copyOf(selectedReturns);
        if (financial.settlement().input().payment() == null) {
            if (returns != null || latestRegistration != null || payment != null || paymentVoucher != null || paymentVoucherReversal != null
                    || !previousReturns.isEmpty() || !selectedReturns.isEmpty()) throw changed();
        } else {
            requirePayment(financial, returns, latestRegistration, payment);
            requireAccounting(returns, paymentVoucher, paymentVoucherReversal);
            if (!returns.entries().containsAll(previousReturns) || !returns.entries().containsAll(selectedReturns)
                    || previousReturns.stream().anyMatch(selectedReturns::contains)) throw changed();
            var currency = financial.change().gross().currency();
            if (!total(previousReturns, currency).equals(financial.settlement().input().payable().minus(financial.change().before().payable()))
                    || !total(selectedReturns, currency).equals(financial.change().bankReturn())) throw changed();
            // 外部或页面换序不改变本次命令含义，始终沿用第一次登记时的原件顺序。
            var previous = Set.copyOf(previousReturns); var selected = Set.copyOf(selectedReturns);
            previousReturns = returns.entries().stream().filter(previous::contains).toList();
            selectedReturns = returns.entries().stream().filter(selected::contains).toList();
        }
    }

    /** 本次没有采用的新增入款保持待处理，不能因为总额足够就提前将它们全部消费。 */
    public List<ExpensePaymentReturns.Entry> unusedReturns() {
        return returns == null ? List.of() : returns.entries().stream().filter(value -> !previousReturns.contains(value) && !selectedReturns.contains(value)).toList();
    }

    /** 申请人及原出纳不能授权自己的调整，授权时间不得早于任何已采用的持久依据。 */
    public void requireAuthorization(String actor, Instant at) {
        if (StringUtils.isBlank(actor) || actor.length() > 128 || !actor.equals(actor.trim()) || actor.chars().anyMatch(Character::isISOControl)
                || actor.equals(financial.settlement().input().source().employeeId()) || at == null
                || at.isBefore(financial.settlement().updatedAt()) || at.isBefore(financial.consumption().updatedAt()) || at.isBefore(financial.accrual().updatedAt())
                || payment != null && (actor.equals(payment.input().command().authorization().executedBy()) || at.isBefore(payment.updatedAt())
                    || at.isBefore(returns.updatedAt()) || at.isBefore(paymentVoucher.updatedAt()))
                || paymentVoucherReversal != null && at.isBefore(paymentVoucherReversal.recordedAt())) throw changed();
    }

    private static void requirePayment(ExpenseAdjustmentFinancialSource financial, ExpensePaymentReturns returns,
                                       ExpensePaymentReturn registration, PaymentOperation payment) {
        if (returns == null || registration == null || payment == null || payment.status() != PaymentOperation.Status.SUCCEEDED && payment.status() != PaymentOperation.Status.REVERSED) throw changed();
        var input = financial.settlement().input(); var expected = input.payment(); var source = input.source(); var request = returns.request();
        var command = request.command(); var binding = command.binding(); var original = request.original(); var current = payment.observation(); var receipt = registration.receipt();
        if (!command.equals(payment.input().command()) || !command.id().equals(expected.operationId()) || !command.digest().equals(expected.commandDigest())
                || !command.tenantId().equals(source.tenantId()) || !command.amount().equals(input.payable()) || !command.payee().employeeId().equals(source.employeeId())
                || !command.payee().legalEntityId().equals(financial.accrual().input().command().legalEntityId())
                || !binding.businessId().equals(source.businessId()) || !binding.applicationId().equals(source.applicationId())
                || binding.roundNo() != source.roundNo() || binding.applicationVersion() != source.applicationVersion() || binding.businessVersion() != source.businessVersion()
                || !original.paymentReference().equals(expected.paymentReference()) || !original.receiptReference().equals(expected.receiptReference()) || !original.completedAt().equals(expected.completedAt())
                || !command.voucherReference().equals(financial.accrual().observation().voucherReference())
                || !registration.tenantId().equals(source.tenantId()) || !registration.registeredAt().equals(returns.updatedAt())
                || !receipt.request().equals(request) || !receipt.samePaymentFacts(current) || receipt.current().revision() > current.revision()
                || current.observedAt().isBefore(receipt.current().observedAt())
                || !Set.copyOf(receipt.returns()).equals(Set.copyOf(returns.entries().stream().map(ExpensePaymentReturns.Entry::proof).toList()))) throw changed();
        var accounts = financial.accrual().input().command().lines().stream().filter(value -> value.account().role() == AccountMappingPort.Role.EMPLOYEE_PAYABLE)
                .map(value -> financial.accrual().input().command().mapping().account(value.account())).distinct().toList();
        if (accounts.size() != 1 || !accounts.get(0).equals(request.payableAccountCode())) throw changed();
    }

    private static void requireAccounting(ExpensePaymentReturns returns, VoucherOperation voucher, VoucherReversalRecord reversal) {
        if (voucher == null || voucher.input().command().kind() != VoucherCommand.Kind.PAYMENT
                || !voucher.input().command().payment().command().equals(returns.request().command())) throw changed();
        var command = voucher.input().command(); var paid = command.payment().receipt(); var original = returns.request().original();
        if (!paid.paymentReference().equals(original.paymentReference()) || !paid.receiptReference().equals(original.receiptReference())
                || !paid.completedAt().equals(original.completedAt()) || !paid.paidAmount().equals(original.paidAmount()) || !paid.accountDigest().equals(original.accountDigest())) throw changed();
        var accounts = command.lines().stream().filter(value -> value.account().role() == AccountMappingPort.Role.EMPLOYEE_PAYABLE)
                .map(value -> command.mapping().account(value.account())).distinct().toList();
        if (accounts.size() != 1 || !accounts.get(0).equals(returns.request().payableAccountCode())) throw changed();
        if (voucher.usablePosted()) {
            if (reversal != null || returns.entries().stream().anyMatch(value -> value.proof().posting().voucherReference().equals(voucher.observation().voucherReference()))) throw changed();
            return;
        }
        if (reversal == null || !reversal.stillAppliesTo(voucher)) throw changed();
        var posting = reversal.receipt().reversal(); var credits = posting.lines().stream().filter(value -> value.side() == VoucherCommand.Side.CREDIT).toList();
        if (credits.size() != returns.entries().size()) throw changed();
        // 付款凭证已完整反向时，采用同一批应付贷方；另造一批回款分录会重复恢复应付。
        for (var entry : returns.entries()) {
            var proof = entry.proof().posting();
            if (!proof.voucherReference().equals(posting.voucherReference()) || !proof.accountingDate().equals(posting.accountingDate()) || !proof.postedAt().equals(posting.postedAt())
                    || credits.stream().noneMatch(value -> value.entryReference().equals(proof.entryReference()) && value.accountCode().equals(proof.accountCode()) && value.amount().equals(proof.amount()))) throw changed();
        }
    }

    private static boolean invalidEntries(List<ExpensePaymentReturns.Entry> entries) {
        return entries == null || entries.size() > ExpensePaymentReturnPort.MAX_RETURN_ENTRIES || entries.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(entries).size() != entries.size();
    }
    private static Money total(List<ExpensePaymentReturns.Entry> entries, String currency) { return entries.stream().map(value -> value.proof().funding().amount()).reduce(Money.zero(currency), Money::plus); }
    private static DomainException changed() { return new DomainException("EXPENSE_ADJUSTMENT_FUNDING_CHANGED", "Partial adjustment requires exact original payment, registered unconsumed returns and matching payment accounting"); }
    @Override public String toString() { return "ExpenseAdjustmentFundingSource[reportId=" + financial.change().before().original().id() + "]"; }
}
