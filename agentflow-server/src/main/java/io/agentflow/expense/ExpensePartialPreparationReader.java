package io.agentflow.expense;

import io.agentflow.finance.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 原回款、原凭证和期间分别只读，网络等待不持有报销锁或改写原财务命令。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpensePartialPreparationReader {
    private final ExpensePaymentReturnPort returns;
    private final AccountingVoucherPort vouchers;
    private final AccountingPeriodPort periods;
    /** 所有读取沿实际原命令目的地，不由页面输入地址或操作内容。 */
    public ExpensePartialPreparationReader(ExpensePaymentReturnPort returns, AccountingVoucherPort vouchers, AccountingPeriodPort periods) {
        this.returns = returns; this.vouchers = vouchers; this.periods = periods;
    }
    /** 独立保留每个读取结果，后段失败不把已经查询到的原件变化伪装为旧成功。 */
    public Snapshot read(ExpensePartialAdjustmentPreparation claimed) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Partial preparation reads must run outside a database transaction");
        var source = claimed.readSource(); var financial = source.financial(); var tenant = claimed.input().adjustment().input().basis().tenantId();
        var bank = source.payment() == null ? null : returns.query(tenant, source.payment().input().targetDigest(), source.returns().request());
        var accrual = vouchers.query(financial.accrual().input().targetDigest(), financial.accrual().input().command());
        var payment = source.paymentVoucher() == null ? null : vouchers.query(source.paymentVoucher().input().targetDigest(), source.paymentVoucher().input().command());
        var target = claimed.input().side() == ExpensePartialAdjustmentPreparation.Side.BUDGET ? financial.consumption().input().targetDigest() : financial.accrual().input().targetDigest();
        var period = periods.period(tenant, target, claimed.input().periodRequest());
        return new Snapshot(bank, accrual, payment, period);
    }
    /**
     * 网络结果只在当前领取版本和原来源仍相同的事务中接受，不能直接作为写入授权。
     * @author owlzhangfq@gmail.com
     */
    public record Snapshot(FinanceResult<ExpensePaymentReturnPort.Receipt> bank, FinanceResult<VoucherObservation> accrual,
            FinanceResult<VoucherObservation> paymentVoucher, FinanceResult<AccountingPeriodPort.OpenPeriod> period) { }
}
