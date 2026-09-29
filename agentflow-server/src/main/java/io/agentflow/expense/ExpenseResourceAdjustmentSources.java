package io.agentflow.expense;

import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.common.DomainException;
import io.agentflow.finance.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 调整只采用原已核销报销的正式财务记录，来源读取和锁顺序集中在应用层。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseResourceAdjustmentSources {
    private final ExpenseReportRepository reports;
    private final ApplicationRepository applications;
    private final JdbcExpenseSettlementRepository settlements;
    private final JdbcBudgetOperationRepository budgets;
    private final JdbcVoucherOperationRepository vouchers;
    private final JdbcVoucherReversalRecordRepository reversals;
    private final JdbcExpensePaymentReturnsRepository returns;
    private final JdbcExpensePaymentReturnRepository registrations;
    private final JdbcPaymentOperationRepository payments;

    /** 只读持久凭据，不使用新的主数据映射重新解释原过账。 */
    public ExpenseResourceAdjustmentSources(ExpenseReportRepository reports, ApplicationRepository applications, JdbcExpenseSettlementRepository settlements,
            JdbcBudgetOperationRepository budgets, JdbcVoucherOperationRepository vouchers, JdbcVoucherReversalRecordRepository reversals,
            JdbcExpensePaymentReturnsRepository returns, JdbcExpensePaymentReturnRepository registrations, JdbcPaymentOperationRepository payments) {
        this.reports = reports; this.applications = applications; this.settlements = settlements; this.budgets = budgets; this.vouchers = vouchers;
        this.reversals = reversals; this.returns = returns; this.registrations = registrations; this.payments = payments;
    }

    /** 与原付款、结算及资源保持申请在前、报销在后的锁顺序。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public ExpenseResourceAdjustmentBasis locked(String tenant, UUID reportId) {
        reports.lock(tenant, reportId); return find(tenant, reportId);
    }

    /** 全部原凭据满足才返回取消依据，未完成或冲突状态不能由客户端补成成功。 */
    public ExpenseResourceAdjustmentBasis find(String tenant, UUID reportId) {
        var report = reports.find(tenant, reportId).orElseThrow(ExpenseResourceAdjustmentSources::changed);
        var settlement = settlements.find(tenant, reportId).orElseThrow(ExpenseResourceAdjustmentSources::changed);
        var source = settlement.input().source(); var application = applications.findById(tenant, source.applicationId()).orElseThrow(ExpenseResourceAdjustmentSources::changed);
        if (settlement.budgetOperationId() == null || settlement.input().voucherOperationId() == null || application.status() != ApplicationStatus.APPROVED
                || application.businessReference() == null || application.businessReference().type() != BusinessReference.Type.EXPENSE
                || !application.businessReference().id().equals(reportId) || application.roundNo() != source.roundNo() || application.version() != source.applicationVersion()) throw changed();
        var consumption = budgets.find(tenant, settlement.budgetOperationId()).orElseThrow(ExpenseResourceAdjustmentSources::changed);
        var accrual = vouchers.find(tenant, settlement.input().voucherOperationId()).orElseThrow(ExpenseResourceAdjustmentSources::changed);
        var accrualReversal = reversals.forOperation(tenant, settlement.input().voucherOperationId()).orElseThrow(ExpenseResourceAdjustmentSources::changed);
        if (!accrualReversal.stillAppliesTo(accrual)) throw changed();
        ExpensePaymentReturns bankReturns = null; VoucherOperation paymentVoucher = null; VoucherReversalRecord paymentReversal = null;
        if (settlement.input().payment() != null) {
            bankReturns = returns.find(tenant, reportId).orElseThrow(ExpenseResourceAdjustmentSources::changed);
            var payment = payments.find(tenant, settlement.input().payment().operationId()).orElseThrow(ExpenseResourceAdjustmentSources::changed);
            var decisions = registrations.history(tenant, reportId);
            if (payment.status() != PaymentOperation.Status.REVERSED || decisions.isEmpty()) throw changed();
            var last = decisions.get(decisions.size() - 1).receipt();
            if (last.status() != ExpensePaymentReturnPort.Status.RETURNED || !last.samePaymentFacts(payment.observation())
                    || last.current().revision() > payment.observation().revision() || !last.request().equals(bankReturns.request())
                    || !java.util.Set.copyOf(last.returns()).equals(java.util.Set.copyOf(bankReturns.entries().stream().map(ExpensePaymentReturns.Entry::proof).toList()))) throw changed();
            paymentVoucher = vouchers.forRound(tenant, source.applicationId(), source.roundNo(), VoucherCommand.Kind.PAYMENT).orElseThrow(ExpenseResourceAdjustmentSources::changed);
            paymentReversal = reversals.forOperation(tenant, paymentVoucher.input().command().id()).orElse(null);
        }
        var basis = new ExpenseResourceAdjustmentBasis(settlement, consumption, accrualReversal, bankReturns, paymentVoucher, paymentReversal);
        basis.requireReport(report); return basis;
    }

    /** 准备、授权和首次发送各自重读当前原凭据，已发送未知结果的查询不受这个守卫阻断。 */
    public void requireCurrent(ExpenseResourceAdjustmentBasis expected) {
        if (!find(expected.tenantId(), expected.reportId()).equals(expected)) throw changed();
    }
    private static DomainException changed() { return new DomainException("EXPENSE_ADJUSTMENT_SOURCE_CHANGED", "Original expense, budget, accrual reversal or full payment return changed before resource adjustment"); }
}
