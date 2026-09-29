package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.AccountMappingPort;
import io.agentflow.finance.BudgetCommand;
import io.agentflow.finance.BudgetOperation;
import io.agentflow.finance.BudgetPrecheckPort;
import io.agentflow.finance.ExpensePaymentReturnPort;
import io.agentflow.finance.VoucherCommand;
import io.agentflow.finance.VoucherOperation;
import io.agentflow.finance.VoucherReversalRecord;
import java.time.Instant;
import java.util.UUID;

/**
 * 全额取消已核销报销的不可变依据：原预算消费、已登记挂账冲销及全部实际付款退回。
 * @author owlzhangfq@gmail.com
 */
public record ExpenseResourceAdjustmentBasis(ExpenseSettlement settlement, BudgetOperation consumption,
        VoucherReversalRecord accrualReversal, ExpensePaymentReturns paymentReturns,
        VoucherOperation paymentVoucher, VoucherReversalRecord paymentVoucherReversal) {
    /** 原费用和原借款冲销由挂账反向分录处理，付款部分由已配对的银行入款与应付贷方处理。 */
    public ExpenseResourceAdjustmentBasis {
        if (settlement == null || !settlement.resourcesConsumed() || settlement.status() != ExpenseSettlement.Status.REVIEW_REQUIRED
                || settlement.input().gross().value().signum() == 0 || consumption == null || consumption.status() != BudgetOperation.Status.APPLIED
                || !consumption.input().command().id().equals(settlement.budgetOperationId()) || accrualReversal == null) throw changed();
        var input = settlement.input(); var source = input.source(); var budget = consumption.input().command();
        var voucher = accrualReversal.receipt().request().command(); var binding = voucher.binding(); var position = budget.position();
        if (budget.action() != BudgetCommand.Action.CONSUME || !budget.tenantId().equals(source.tenantId())
                || !position.reportId().equals(source.businessId()) || position.roundNo() != source.roundNo() || position.financialVersion() != source.businessVersion()
                || !position.employeeId().equals(source.employeeId()) || !position.total().equals(input.gross())
                || !voucher.id().equals(input.voucherOperationId()) || !voucher.digest().equals(input.voucherDigest())
                || voucher.kind() != VoucherCommand.Kind.EXPENSE_ACCRUAL || !voucher.tenantId().equals(source.tenantId())
                || !voucher.legalEntityId().equals(position.legalEntityId()) || !voucher.employeeId().equals(source.employeeId())
                || !binding.businessId().equals(source.businessId()) || !binding.applicationId().equals(source.applicationId())
                || binding.roundNo() != source.roundNo() || binding.applicationVersion() != source.applicationVersion() || binding.businessVersion() != source.businessVersion()
                || !voucher.totals().gross().equals(input.gross()) || !voucher.totals().offset().equals(input.offsets())) throw changed();
        if (input.payment() == null) {
            if (paymentReturns != null || paymentVoucher != null || paymentVoucherReversal != null) throw changed();
        } else {
            if (paymentReturns == null || !paymentReturns.totalReturned().equals(input.payable())) throw changed();
            requirePayment(input, voucher, paymentReturns.request());
            requirePaymentAccounting(paymentReturns, paymentVoucher, paymentVoucherReversal);
        }
    }

    /** 锁内核对当前冻结分摊，不能仅凭金额相等替换费用类别或成本对象。 */
    public void requireReport(ExpenseReport report) {
        settlement.requireReport(report);
        var position = consumption.input().command().position();
        if (!BudgetPrecheckPort.Request.fromCurrent(report, position.accountingDate()).equals(position)) throw changed();
    }

    /** 后续授权不能早于全部原事实，申请人和原出纳不能确认自己的资源冲回。 */
    public void requireAuthorization(String actor, Instant at) {
        if (actor == null || actor.equals(settlement.input().source().employeeId())
                || paymentReturns != null && actor.equals(paymentReturns.request().command().authorization().executedBy())
                || at == null || at.isBefore(settlement.updatedAt()) || at.isBefore(consumption.updatedAt())
                || at.isBefore(accrualReversal.recordedAt()) || paymentReturns != null && at.isBefore(paymentReturns.updatedAt())
                || paymentVoucher != null && at.isBefore(paymentVoucher.updatedAt())
                || paymentVoucherReversal != null && at.isBefore(paymentVoucherReversal.recordedAt())) throw changed();
    }

    public String tenantId() { return settlement.input().source().tenantId(); }
    public UUID reportId() { return settlement.input().source().businessId(); }
    public UUID legalEntityId() { return consumption.input().command().position().legalEntityId(); }

    private static void requirePayment(ExpenseSettlement.Input settlement, VoucherCommand voucher, ExpensePaymentReturnPort.Request returns) {
        var payment = settlement.payment(); var command = returns.command(); var original = returns.original(); var binding = command.binding(); var source = settlement.source();
        var accounts = voucher.lines().stream().filter(line -> line.account().role() == AccountMappingPort.Role.EMPLOYEE_PAYABLE)
                .map(line -> voucher.mapping().account(line.account())).distinct().toList();
        if (!command.id().equals(payment.operationId()) || !command.digest().equals(payment.commandDigest())
                || !command.tenantId().equals(source.tenantId()) || !command.amount().equals(payment.amount())
                || !command.payee().employeeId().equals(source.employeeId()) || !command.payee().legalEntityId().equals(voucher.legalEntityId())
                || !binding.businessId().equals(source.businessId()) || !binding.applicationId().equals(source.applicationId())
                || binding.roundNo() != source.roundNo() || binding.applicationVersion() != source.applicationVersion() || binding.businessVersion() != source.businessVersion()
                || !original.completedAt().equals(payment.completedAt()) || !original.paymentReference().equals(payment.paymentReference())
                || !original.receiptReference().equals(payment.receiptReference()) || accounts.size() != 1 || !accounts.get(0).equals(returns.payableAccountCode())) throw changed();
    }
    private static void requirePaymentAccounting(ExpensePaymentReturns returns, VoucherOperation voucher, VoucherReversalRecord reversal) {
        if (voucher == null || voucher.input().command().kind() != VoucherCommand.Kind.PAYMENT
                || !voucher.input().command().payment().command().equals(returns.request().command())) throw changed();
        var command = voucher.input().command(); var receipt = command.payment().receipt(); var original = returns.request().original();
        var payableAccounts = command.lines().stream().filter(line -> line.account().role() == AccountMappingPort.Role.EMPLOYEE_PAYABLE)
                .map(line -> command.mapping().account(line.account())).distinct().toList();
        if (!receipt.paymentReference().equals(original.paymentReference()) || !receipt.receiptReference().equals(original.receiptReference())
                || !receipt.completedAt().equals(original.completedAt()) || payableAccounts.size() != 1
                || !payableAccounts.get(0).equals(returns.request().payableAccountCode())) throw changed();
        if (voucher.usablePosted()) {
            if (reversal != null) throw changed();
            return;
        }
        if (reversal == null || !reversal.stillAppliesTo(voucher)) throw changed();
        // 付款凭证已反向过账时，只能采用同一批应付贷方作为银行退回证明，另记一批会把资金冲销两次。
        var posting = reversal.receipt().reversal();
        var credits = posting.lines().stream().filter(line -> line.side() == VoucherCommand.Side.CREDIT).toList();
        if (credits.size() != returns.entries().size()) throw changed();
        for (var entry : returns.entries()) {
            var proof = entry.proof().posting();
            if (!proof.voucherReference().equals(posting.voucherReference()) || !proof.accountingDate().equals(posting.accountingDate())
                    || !proof.postedAt().equals(posting.postedAt()) || credits.stream().noneMatch(line -> line.entryReference().equals(proof.entryReference())
                    && line.accountCode().equals(proof.accountCode()) && line.amount().equals(proof.amount()))) throw changed();
        }
    }
    private static DomainException changed() {
        return new DomainException("EXPENSE_ADJUSTMENT_BASIS_CHANGED", "Full expense cancellation requires original settled budget, accrual reversal and all actual payment returns");
    }
    @Override public String toString() { return "ExpenseResourceAdjustmentBasis[reportId=" + reportId() + "]"; }
}
