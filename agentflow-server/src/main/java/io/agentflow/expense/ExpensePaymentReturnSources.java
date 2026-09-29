package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 报销退回只引用原结算、首次成功付款及原挂账科目，当前账户与映射不能替换历史事实。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpensePaymentReturnSources {
    private final ExpenseReportRepository reports;
    private final JdbcExpenseSettlementRepository settlements;
    private final ExpenseSettlementSources settlementSources;
    private final JdbcPaymentAuthorizationRepository authorizations;
    private final JdbcPaymentOperationRepository payments;
    private final JdbcVoucherOperationRepository vouchers;
    public ExpensePaymentReturnSources(ExpenseReportRepository reports, JdbcExpenseSettlementRepository settlements, ExpenseSettlementSources settlementSources,
            JdbcPaymentAuthorizationRepository authorizations, JdbcPaymentOperationRepository payments, JdbcVoucherOperationRepository vouchers) {
        this.reports = reports; this.settlements = settlements; this.settlementSources = settlementSources;
        this.authorizations = authorizations; this.payments = payments; this.vouchers = vouchers;
    }
    /** 与结算、原付款、预算及归档共用申请和报销锁顺序。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Source locked(String tenant, UUID reportId) { reports.lock(tenant, reportId); return find(tenant, reportId); }
    /** 有原成功付款才有银行退回，零应付不能创建虚构的退回账本。 */
    public Source find(String tenant, UUID reportId) {
        var report = reports.find(tenant, reportId).orElseThrow(ExpensePaymentReturnSources::changed);
        var settlement = settlements.find(tenant, reportId).orElseThrow(ExpensePaymentReturnSources::changed);
        if (settlement.input().payment() == null) throw changed();
        var id = settlement.input().payment().operationId();
        var payment = payments.find(tenant, id).orElseThrow(ExpensePaymentReturnSources::changed);
        var original = payments.firstSuccessfulRevision(tenant, id).orElseThrow(ExpensePaymentReturnSources::changed);
        var authorization = authorizations.find(tenant, id).orElseThrow(ExpensePaymentReturnSources::changed);
        if (!original.input().equals(payment.input()) || !settlementSources.paid(original, report).equals(settlement.input())) throw changed();
        var voucher = vouchers.find(tenant, authorization.terms().voucherOperationId()).orElseThrow(ExpensePaymentReturnSources::changed).input().command();
        var accounts = voucher.lines().stream().filter(line -> line.account().role() == AccountMappingPort.Role.EMPLOYEE_PAYABLE)
                .map(line -> voucher.mapping().account(line.account())).distinct().toList();
        if (accounts.size() != 1) throw changed();
        return new Source(report, settlement, authorization, payment, original.version(), new ExpensePaymentReturnPort.Request(payment.input().command(), original.observation(), accounts.get(0)));
    }
    /** 后台任务只接受相同原成功修订和原财务目标，当前付款查询可以增加新观察。 */
    public void requireCheck(ExpensePaymentReturnCheck check, Source source) {
        if (!check.input().tenantId().equals(source.report().tenantId()) || check.input().paymentVersion() != source.paymentVersion()
                || !check.input().targetDigest().equals(source.authorization().terms().targetDigest()) || !check.input().request().equals(source.request())) throw changed();
    }
    private static DomainException changed() { return new DomainException("EXPENSE_PAYMENT_RETURN_SOURCE_CHANGED", "Original successful expense payment and settlement source are unavailable or inconsistent"); }
    /**
     * 原结算、当前付款与固定首次成功修订分开，退回不追随当前账户变化。
     * @author owlzhangfq@gmail.com
     */
    public record Source(ExpenseReport report, ExpenseSettlement settlement, PaymentAuthorization authorization, PaymentOperation payment,
                         long paymentVersion, ExpensePaymentReturnPort.Request request) { }
}
