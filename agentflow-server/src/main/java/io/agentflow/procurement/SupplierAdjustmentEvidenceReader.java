package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.AccountingPeriodPort;
import io.agentflow.finance.FinanceResult;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 准备和发送在事务外核对固定来源，前次调整的查询命令从实际完成证明恢复。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierAdjustmentEvidenceReader {
    private final SupplierPaymentReturnPort returns;
    private final SupplierPayableHoldPort holds;
    private final SupplierPayableSettlementPort settlements;
    private final SupplierPayableAdjustmentPort adjustments;
    private final AccountingPeriodPort periods;
    private final JdbcSupplierAdjustmentCompletions completions;

    /** 所有网络依赖都沿原付款的固定目标读取，完整历史命令不由客户端提供。 */
    public SupplierAdjustmentEvidenceReader(SupplierPaymentReturnPort returns, SupplierPayableHoldPort holds,
            SupplierPayableSettlementPort settlements, SupplierPayableAdjustmentPort adjustments, AccountingPeriodPort periods,
            JdbcSupplierAdjustmentCompletions completions) {
        this.returns = returns; this.holds = holds; this.settlements = settlements; this.adjustments = adjustments;
        this.periods = periods; this.completions = completions;
    }

    /** 任一读取失败立即返回；开放期间只确认财务指定日期，不自动换期或发送调整。 */
    public FinanceResult<Snapshot> read(SupplierPayableAdjustmentSource source, LocalDate accountingDate) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Supplier adjustment evidence must be read outside a database transaction");
        var bank = returns.query(source.returns().request());
        if (!(bank instanceof FinanceResult.Success<SupplierPaymentReturnPort.Receipt> received)) return problem(bank);
        var payment = source.returns().request().command();
        SupplierPayableHoldObservation hold = null;
        if (source.recognizesOriginalPayment()) {
            var result = holds.query(payment.holdCommand());
            if (!(result instanceof FinanceResult.Success<SupplierPayableHoldObservation> found)) return problem(result);
            hold = found.value();
        }
        SupplierPayableSettlementObservation settlement = null;
        if (source.settlement() != null) {
            var result = settlements.query(source.settlement().command());
            if (!(result instanceof FinanceResult.Success<SupplierPayableSettlementObservation> found)) return problem(result);
            settlement = found.value();
        }
        SupplierPayableAdjustmentObservation previous = null;
        if (source.previous() != null) {
            var proof = completions.find(payment.tenantId(), source.previous().observation().operationId())
                    .orElseThrow(() -> new DomainException("SUPPLIER_ADJUSTMENT_SOURCE_CHANGED", "Previous supplier adjustment completion is missing"));
            var result = adjustments.query(proof.operation().command());
            if (!(result instanceof FinanceResult.Success<SupplierPayableAdjustmentObservation> found)) return problem(result);
            previous = found.value();
        }
        var period = periods.period(payment.tenantId(), payment.targetDigest(), new AccountingPeriodPort.Request(payment.payee().legalEntityId(), payment.amount().currency(), accountingDate));
        if (!(period instanceof FinanceResult.Success<AccountingPeriodPort.OpenPeriod> open)) return problem(period);
        return new FinanceResult.Success<>(new Snapshot(received.value(), hold, settlement, previous, open.value(), Instant.now().truncatedTo(ChronoUnit.MICROS)));
    }

    private static FinanceResult<Snapshot> problem(FinanceResult<?> result) {
        if (result instanceof FinanceResult.Unavailable<?> unavailable) return new FinanceResult.Unavailable<>(unavailable.failure());
        if (result instanceof FinanceResult.Rejected<?> rejected) return new FinanceResult.Rejected<>(rejected.reason());
        return new FinanceResult.Unavailable<>(FinanceResult.Failure.INVALID_RESPONSE);
    }

    /**
     * 保留各外部系统实际观察时间，领域在命令登记和发送前分别验证一致性与期限。
     * @author owlzhangfq@gmail.com
     */
    public record Snapshot(SupplierPaymentReturnPort.Receipt bank, SupplierPayableHoldObservation hold, SupplierPayableSettlementObservation settlement,
            SupplierPayableAdjustmentObservation previous, AccountingPeriodPort.OpenPeriod period, Instant checkedAt) {
        /** 读取成功不代表原件一致，领域转换仍可能拒绝未知银行、变化的核销或前次调整。 */
        public SupplierPayableAdjustmentEvidence evidence() { return new SupplierPayableAdjustmentEvidence(bank, hold, settlement, previous, period, checkedAt); }
        @Override public String toString() { return "SupplierAdjustmentEvidenceSnapshot[checkedAt=" + checkedAt + "]"; }
    }
}
