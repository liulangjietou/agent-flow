package io.agentflow.expense;

import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.common.DomainException;
import io.agentflow.finance.*;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 独立部分调整从本地真实批准、核销和财务账本恢复来源，不采用客户端提供的成功快照。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpensePartialAdjustmentSources {
    private final ExpenseReportRepository reports;
    private final ApplicationRepository applications;
    private final JdbcExpenseSettlementRepository settlements;
    private final JdbcBudgetOperationRepository budgets;
    private final JdbcVoucherOperationRepository vouchers;
    private final JdbcPaymentOperationRepository payments;
    private final JdbcExpensePaymentReturnsRepository returns;
    private final JdbcExpensePaymentReturnRepository registrations;
    private final JdbcVoucherReversalRecordRepository reversals;

    /** 网络证据由已有持久账本提供，此处只读取同一租户的原业务和准确修订。 */
    public ExpensePartialAdjustmentSources(ExpenseReportRepository reports, ApplicationRepository applications, JdbcExpenseSettlementRepository settlements,
            JdbcBudgetOperationRepository budgets, JdbcVoucherOperationRepository vouchers, JdbcPaymentOperationRepository payments,
            JdbcExpensePaymentReturnsRepository returns, JdbcExpensePaymentReturnRepository registrations, JdbcVoucherReversalRecordRepository reversals) {
        this.reports = reports; this.applications = applications; this.settlements = settlements; this.budgets = budgets;
        this.vouchers = vouchers; this.payments = payments; this.returns = returns; this.registrations = registrations; this.reversals = reversals;
    }

    /** 金额意图仍绑定完整原报销；此前回款的真实完成归属由调整仓储另行核对。 */
    public ExpenseAdjustmentFundingSource find(String tenant, ExpenseAdjustmentAmounts.Change change,
            List<ExpensePaymentReturns.Entry> previous, List<ExpensePaymentReturns.Entry> selected) {
        var id = change.before().original().id(); var report = reports.find(tenant, id).orElseThrow(ExpensePartialAdjustmentSources::changed);
        if (!report.state().equals(change.before().original())) throw changed();
        var settlement = settlements.find(tenant, id).orElseThrow(ExpensePartialAdjustmentSources::changed);
        var source = settlement.input().source();
        var application = applications.findById(tenant, source.applicationId()).orElseThrow(ExpensePartialAdjustmentSources::changed);
        if (application.status() != ApplicationStatus.APPROVED || application.businessReference() == null
                || application.businessReference().type() != BusinessReference.Type.EXPENSE || !application.businessReference().id().equals(id)
                || application.roundNo() != source.roundNo() || application.version() != source.applicationVersion()
                || settlement.budgetOperationId() == null || settlement.input().voucherOperationId() == null) throw changed();
        var budget = budgets.find(tenant, settlement.budgetOperationId()).orElseThrow(ExpensePartialAdjustmentSources::changed);
        var accrual = vouchers.find(tenant, settlement.input().voucherOperationId()).orElseThrow(ExpensePartialAdjustmentSources::changed);
        var financial = new ExpenseAdjustmentFinancialSource(change, settlement, budget, accrual);
        if (settlement.input().payment() == null) return new ExpenseAdjustmentFundingSource(financial, null, null, null, null, null, previous, selected);
        var bank = payments.find(tenant, settlement.input().payment().operationId()).orElseThrow(ExpensePartialAdjustmentSources::changed);
        var ledger = returns.find(tenant, id).orElseThrow(ExpensePartialAdjustmentSources::changed);
        var recorded = registrations.history(tenant, id); if (recorded.isEmpty()) throw changed();
        var voucher = vouchers.forRound(tenant, source.applicationId(), source.roundNo(), VoucherCommand.Kind.PAYMENT).orElseThrow(ExpensePartialAdjustmentSources::changed);
        var reversal = reversals.forOperation(tenant, voucher.input().command().id()).orElse(null);
        return new ExpenseAdjustmentFundingSource(financial, ledger, recorded.get(recorded.size() - 1), bank, voucher, reversal, previous, selected);
    }

    /** 建立新意图时要求所有输入等于当前持久事实，不能冒用领域上合法但尚未落库的准备结果。 */
    public void requireCurrent(ExpenseAdjustmentFundingSource expected) {
        if (!current(expected).equals(expected)) throw changed();
    }

    /** 后续授权可以读取同一原件的较新确认，原命令、已登记回款和原件身份不能替换。 */
    public ExpenseAdjustmentFundingSource current(ExpenseAdjustmentFundingSource expected) {
        var financial = expected.financial();
        var value = find(financial.change().before().original().tenantId(), financial.change(), expected.previousReturns(), expected.selectedReturns());
        requireContinuation(expected, value); return value;
    }

    /** 完成时保存的新来源仍须承接原固定意图，读取历史证明不依赖当前外部状态。 */
    public static void requireContinuation(ExpenseAdjustmentFundingSource expected, ExpenseAdjustmentFundingSource value) {
        expected.requireContinuation(value);
    }
    private static DomainException changed() { return new DomainException("EXPENSE_PARTIAL_ADJUSTMENT_SOURCE_CHANGED", "Current persisted expense, settlement, budget, accounting or registered bank evidence changed"); }
}
