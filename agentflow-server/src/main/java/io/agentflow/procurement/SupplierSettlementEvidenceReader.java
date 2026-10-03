package io.agentflow.procurement;

import io.agentflow.finance.AccountingPeriodPort;
import io.agentflow.finance.FinanceResult;
import io.agentflow.finance.PaymentObservation;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 结算准备和发送共用原预留、原银行及明确会计日期的事务外读取，不调用银行付款。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierSettlementEvidenceReader {
    private final SupplierPayableHoldPort holds;
    private final SupplierPaymentPort payments;
    private final AccountingPeriodPort periods;

    /** 三项只读依赖均固定到原财务目标，任何失败不能替换来源。 */
    public SupplierSettlementEvidenceReader(SupplierPayableHoldPort holds, SupplierPaymentPort payments, AccountingPeriodPort periods) {
        this.holds = holds; this.payments = payments; this.periods = periods;
    }

    /** 读取失败立即停止后续调用，期间关闭保持原记账日期等待明确处理。 */
    public FinanceResult<Snapshot> read(SupplierPaymentCommand payment, LocalDate accountingDate) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Supplier settlement evidence must be read outside a database transaction");
        var held = holds.query(payment.holdCommand());
        if (!(held instanceof FinanceResult.Success<SupplierPayableHoldObservation> found)) return problem(held);
        var paid = payments.query(payment);
        if (!(paid instanceof FinanceResult.Success<PaymentObservation> received)) return problem(paid);
        var period = periods.period(payment.tenantId(), payment.targetDigest(), new AccountingPeriodPort.Request(payment.payee().legalEntityId(), payment.amount().currency(), accountingDate));
        if (!(period instanceof FinanceResult.Success<AccountingPeriodPort.OpenPeriod> open)) return problem(period);
        return new FinanceResult.Success<>(new Snapshot(found.value(), received.value(), open.value(), Instant.now().truncatedTo(ChronoUnit.MICROS)));
    }
    private static FinanceResult<Snapshot> problem(FinanceResult<?> result) {
        if (result instanceof FinanceResult.Unavailable<?> unavailable) return new FinanceResult.Unavailable<>(unavailable.failure());
        if (result instanceof FinanceResult.Rejected<?> rejected) return new FinanceResult.Rejected<>(rejected.reason());
        return new FinanceResult.Unavailable<>(FinanceResult.Failure.INVALID_RESPONSE);
    }

    /**
     * 保留实际完成读取时间，排队或入库不能延长旧证据时效。
     * @author owlzhangfq@gmail.com
     */
    public record Snapshot(SupplierPayableHoldObservation hold, PaymentObservation paid, AccountingPeriodPort.OpenPeriod period, Instant checkedAt) {
        /** 领域核验负责判定三项成功事实，查询返回处理中并不等于可以核销。 */
        public SupplierPayableSettlementEvidence evidence() { return new SupplierPayableSettlementEvidence(hold, paid, period, checkedAt); }
        /** 日志不展开资金来源与回单。 */
        @Override public String toString() { return "SupplierSettlementEvidenceSnapshot[checkedAt=" + checkedAt + "]"; }
    }
}
