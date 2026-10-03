package io.agentflow.procurement;

import io.agentflow.finance.FinanceResult;
import io.agentflow.finance.PaymentAccountsPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 登记及发送前均读取三个原目标事实，网络调用不会跨越数据库事务。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierPaymentEvidenceReader {
    private final SupplierPayableHoldPort holds;
    private final ProcurementPayablePort payables;
    private final PaymentAccountsPort accounts;

    /** 两个真实执行阶段共享相同读取范围，业务判定仍由领域对象完成。 */
    public SupplierPaymentEvidenceReader(SupplierPayableHoldPort holds, ProcurementPayablePort payables, PaymentAccountsPort accounts) {
        this.holds = holds; this.payables = payables; this.accounts = accounts;
    }

    /** 失败即停止后续读取，不以空账户、默认余额或新的财务目标代替失败。 */
    public FinanceResult<Snapshot> read(SupplierPaymentAuthorization authorization, String cashier) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Supplier payment evidence must be read outside a database transaction");
        var source = authorization.source().reservation().source(); var round = source.round();
        var held = holds.query(new SupplierPayableHoldCommand(authorization));
        if (!(held instanceof FinanceResult.Success<SupplierPayableHoldObservation> found)) return problem(held);
        var payable = payables.payable(source.tenantId(), round.targetDigest(), round.payable().request());
        if (!(payable instanceof FinanceResult.Success<ProcurementPayablePort.Payable> current)) return problem(payable);
        var directory = accounts.debitAccounts(source.tenantId(), round.targetDigest(), new PaymentAccountsPort.Request(round.content().legalEntityId(), round.content().amount().currency(), cashier));
        if (!(directory instanceof FinanceResult.Success<PaymentAccountsPort.Directory> available)) return problem(directory);
        return new FinanceResult.Success<>(new Snapshot(found.value(), current.value(), available.value()));
    }
    private static FinanceResult<Snapshot> problem(FinanceResult<?> result) {
        if (result instanceof FinanceResult.Unavailable<?> unavailable) return new FinanceResult.Unavailable<>(unavailable.failure());
        if (result instanceof FinanceResult.Rejected<?> rejected) return new FinanceResult.Rejected<>(rejected.reason());
        return new FinanceResult.Unavailable<>(FinanceResult.Failure.INVALID_RESPONSE);
    }

    /**
     * 临时读取结果不保存整份出款目录；登记后的持久命令只保留实际选择。
     * @author owlzhangfq@gmail.com
     */
    public record Snapshot(SupplierPayableHoldObservation held, ProcurementPayablePort.Payable payable, PaymentAccountsPort.Directory directory) {
        /** 不把完整供应商和账户资料写入日志。 */
        @Override public String toString() { return "SupplierPaymentEvidenceSnapshot[redacted]"; }
    }
}
