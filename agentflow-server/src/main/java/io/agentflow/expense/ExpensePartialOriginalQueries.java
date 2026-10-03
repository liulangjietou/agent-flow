package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.*;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 初始准备和活动调整恢复共用原号只读查询，三个原件的精确版本共同提交。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpensePartialOriginalQueries {
    private final JdbcPaymentOperationRepository payments;
    private final JdbcVoucherOperationRepository vouchers;
    private final PaymentOperationService paymentQueries;
    private final VoucherOperationService voucherQueries;
    /** 复用原状态机和事件，不在请求事务中访问银行或 ERP。 */
    public ExpensePartialOriginalQueries(JdbcPaymentOperationRepository payments, JdbcVoucherOperationRepository vouchers,
            PaymentOperationService paymentQueries, VoucherOperationService voucherQueries) {
        this.payments = payments; this.vouchers = vouchers; this.paymentQueries = paymentQueries; this.voucherQueries = voucherQueries;
    }

    /** 调用方先锁原报销并完成权限检查；缺失、变化或正在执行的原件使整次登记回滚。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void query(ExpenseSettlement settlement, long accrualVersion, long paymentVersion, long paymentVoucherVersion, Instant at) {
        var originals = originals(settlement); var tenant = settlement.input().source().tenantId();
        if (originals.accrual().version() != accrualVersion || (originals.payment() == null ? 0 : originals.payment().version()) != paymentVersion
                || (originals.paymentVoucher() == null ? 0 : originals.paymentVoucher().version()) != paymentVoucherVersion) throw conflict();
        if (originals.payment() != null) paymentQueries.query(tenant, originals.payment().input().command().id(), paymentVersion, at);
        voucherQueries.query(tenant, originals.accrual().input().command().id(), accrualVersion, at);
        if (originals.paymentVoucher() != null) voucherQueries.query(tenant, originals.paymentVoucher().input().command().id(), paymentVoucherVersion, at);
    }

    /** 页面预览只调用纯领域转换，不发起查询或修改旧证据。 */
    public boolean available(ExpenseSettlement settlement, Instant at) {
        try {
            var originals = originals(settlement);
            originals.accrual().requestQuery(at);
            if (originals.payment() != null) originals.payment().requestQuery(at);
            if (originals.paymentVoucher() != null) originals.paymentVoucher().requestQuery(at);
            return true;
        } catch (DomainException unavailable) { return false; }
    }
    private Originals originals(ExpenseSettlement settlement) {
        var source = settlement.input().source(); var tenant = source.tenantId();
        // 零核定结算没有原挂账，不属于可查询原财务的部分调整范围。
        if (settlement.input().voucherOperationId() == null) throw conflict();
        var accrual = vouchers.find(tenant, settlement.input().voucherOperationId()).orElseThrow(ExpensePartialOriginalQueries::conflict);
        var payment = settlement.input().payment() == null ? null : payments.find(tenant, settlement.input().payment().operationId()).orElseThrow(ExpensePartialOriginalQueries::conflict);
        var paymentVoucher = payment == null ? null : vouchers.forRound(tenant, source.applicationId(), source.roundNo(), VoucherCommand.Kind.PAYMENT).orElseThrow(ExpensePartialOriginalQueries::conflict);
        return new Originals(accrual, payment, paymentVoucher);
    }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Displayed original expense finance changed"); }
    /**
     * 同一原结算的最小内部查询组合，不作为公开响应输出。
     * @author owlzhangfq@gmail.com
     */
    private record Originals(VoucherOperation accrual, PaymentOperation payment, VoucherOperation paymentVoucher) { }
}
