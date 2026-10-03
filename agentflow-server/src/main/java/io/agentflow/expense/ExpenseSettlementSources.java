package io.agentflow.expense;

import io.agentflow.approval.model.BusinessReference;
import io.agentflow.common.DomainException;
import io.agentflow.finance.*;
import org.springframework.stereotype.Service;

/**
 * 从原挂账、付款授权和真实回执派生核销依据；登记到账与当前可核销守卫分开。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseSettlementSources {
    private final ApprovedVoucherSources approved;
    private final JdbcVoucherOperationRepository vouchers;
    private final JdbcVoucherPreparationRepository preparations;
    private final JdbcPaymentAuthorizationRepository authorizations;
    private final JdbcPaymentOperationRepository payments;
    private final PaymentPayeeEvidence payeeEvidence;
    private final JdbcExpensePaymentReturnsRepository returns;

    /** 跨财务聚合读取集中在应用层，不让领域模型依赖 JDBC 或外部网关。 */
    public ExpenseSettlementSources(ApprovedVoucherSources approved, JdbcVoucherOperationRepository vouchers,
            JdbcVoucherPreparationRepository preparations, JdbcPaymentAuthorizationRepository authorizations, JdbcPaymentOperationRepository payments, PaymentPayeeEvidence payeeEvidence,
            JdbcExpensePaymentReturnsRepository returns) {
        this.approved = approved; this.vouchers = vouchers; this.preparations = preparations; this.authorizations = authorizations; this.payments = payments; this.payeeEvidence = payeeEvidence;
        this.returns = returns;
    }

    /** 实际到账仍属于原授权；当前审批撤销不能把已经发生的银行事实抹去。 */
    public ExpenseSettlement.Input paid(PaymentOperation payment, ExpenseReport report) {
        var command = payment.input().command(); var binding = command.binding();
        if (!payment.settleable() || command.purpose() != PaymentCommand.Purpose.EXPENSE_REIMBURSEMENT) throw mismatch();
        var authorization = authorizations.find(command.tenantId(), command.id()).orElseThrow(ExpenseSettlementSources::mismatch);
        if (authorization.execution() == null || !authorization.execution().command().equals(command)) throw mismatch();
        var terms = authorization.terms();
        var voucher = vouchers.find(command.tenantId(), terms.voucherOperationId()).orElseThrow(ExpenseSettlementSources::mismatch);
        var source = new VoucherPreparation.Source(command.tenantId(), BusinessReference.Type.EXPENSE, binding.businessId(), binding.applicationId(),
                binding.roundNo(), binding.applicationVersion(), binding.businessVersion(), command.payee().employeeId());
        var round = report.requireFrozenRound();
        if (!approved.reference(voucher.input().command()).equals(source) || !voucher.input().command().digest().equals(terms.voucherCommandDigest())
                || !voucher.input().command().totals().gross().equals(round.approvedGross())
                || !voucher.input().command().totals().offset().equals(round.offsetTotal())) throw mismatch();
        payeeEvidence.requireAuthorizedAccount(authorization, round.account());
        var receipt = payment.observation();
        var input = new ExpenseSettlement.Input(source, round.approvedGross(), round.offsetTotal(), terms.voucherOperationId(), terms.voucherCommandDigest(),
                new ExpenseSettlement.Payment(command.id(), command.digest(), receipt.paidAmount(), receipt.paymentReference(), receipt.receiptReference(), receipt.completedAt()), receipt.completedAt());
        ExpenseSettlement.queue(input, payment.updatedAt()).requireReport(report); return input;
    }

    /** 全额冲销凭已过账挂账凭证登记，仍须真实批准及预算冻结，不能用零额付款代替。 */
    public ExpenseSettlement.Input offset(VoucherOperation voucher, ExpenseReport report) {
        var round = report.requireFrozenRound();
        if (!voucher.usablePosted() || voucher.input().command().kind() != VoucherCommand.Kind.EXPENSE_ACCRUAL
                || round.payable().value().signum() != 0) return null;
        var source = approved.reference(voucher.input().command());
        if (!approved.derive(source).matches(voucher.input().command())) throw mismatch();
        return new ExpenseSettlement.Input(source, round.approvedGross(), round.offsetTotal(), voucher.input().command().id(),
                voucher.input().command().digest(), null, voucher.observation().postedAt());
    }

    /** 全额核减只接受准备服务在真实来源守卫之后记录的 ZERO_AMOUNT。 */
    public ExpenseSettlement.Input zero(VoucherPreparation preparation, ExpenseReport report) {
        if (preparation.status() != VoucherPreparation.Status.NOT_REQUIRED || preparation.input().source().kind() != VoucherCommand.Kind.EXPENSE_ACCRUAL) return null;
        var round = report.requireFrozenRound();
        if (round.approvedGross().value().signum() != 0) throw mismatch();
        requireApproved(preparation.input().source(), true, false);
        return new ExpenseSettlement.Input(preparation.input().source(), round.approvedGross(), round.offsetTotal(), null, null, null, preparation.completedAt());
    }

    /** 核销前重读当前原凭据；已失效的资金或会计依据只暂停核销，不重写到账状态。 */
    public void requireCurrent(ExpenseSettlement settlement, ExpenseReport report) {
        settlement.requireReport(report); var input = settlement.input(); var source = input.source();
        if (returns.find(source.tenantId(), source.businessId()).map(ExpensePaymentReturns::reviewRequired).orElse(false)) {
            throw new DomainException("EXPENSE_PAYMENT_RETURN_REVIEW_REQUIRED", "Expense payment return requires independent review or adjustment before settlement");
        }
        requireApproved(source, input.gross().value().signum() == 0, settlement.resourcesConsumed());
        if (input.voucherOperationId() == null) {
            var preparation = preparations.latest(source).orElseThrow(ExpenseSettlementSources::mismatch);
            if (preparation.status() != VoucherPreparation.Status.NOT_REQUIRED || !preparation.input().source().equals(source)) throw mismatch();
        } else {
            var voucher = vouchers.find(source.tenantId(), input.voucherOperationId()).orElseThrow(ExpenseSettlementSources::mismatch);
            if (!voucher.usablePosted() || !voucher.input().command().digest().equals(input.voucherDigest())) {
                throw new DomainException("EXPENSE_VOUCHER_NOT_CONFIRMED", "Original expense voucher must remain confirmed before consumption");
            }
        }
        if (input.payment() != null) {
            var payment = payments.find(source.tenantId(), input.payment().operationId()).orElseThrow(ExpenseSettlementSources::mismatch);
            if (!payment.settleable() || !paid(payment, report).equals(input)) {
                throw new DomainException("EXPENSE_PAYMENT_NOT_CONFIRMED", "Original expense payment must remain confirmed before consumption");
            }
        }
    }

    private void requireApproved(VoucherPreparation.Source source, boolean zero, boolean consumed) {
        try {
            if (consumed) approved.deriveAfterResourceConsumption(source); else approved.derive(source);
            if (zero) throw mismatch();
        }
        catch (DomainException problem) { if (!zero || !"VOUCHER_ZERO_AMOUNT".equals(problem.code())) throw problem; }
    }
    private static DomainException mismatch() { return new DomainException("EXPENSE_SETTLEMENT_SOURCE_CHANGED", "Settlement must match its persisted original financial source"); }
}
