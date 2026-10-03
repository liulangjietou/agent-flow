package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.JdbcFinanceReceiptCreditRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ERP 成功先持久保存，本地完成再以一次短事务登记原件、资金分录和占用结束。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierAdjustmentCompletionService {
    private final JdbcSupplierAdjustmentSources sources;
    private final JdbcSupplierPayableAdjustmentRepository operations;
    private final JdbcSupplierPaymentOperationRepository payments;
    private final JdbcSupplierPaymentReturnsRepository returns;
    private final JdbcSupplierPaymentReturnCheckRepository checks;
    private final JdbcSupplierAdjustmentCompletions completions;
    private final JdbcFinanceReceiptCreditRepository credits;
    private final JdbcProcurementPayableReservationRepository reservations;

    /** 跨聚合提交由应用服务编排，账本状态变化仍由领域对象验证。 */
    public SupplierAdjustmentCompletionService(JdbcSupplierAdjustmentSources sources, JdbcSupplierPayableAdjustmentRepository operations,
            JdbcSupplierPaymentOperationRepository payments, JdbcSupplierPaymentReturnsRepository returns, JdbcSupplierPaymentReturnCheckRepository checks,
            JdbcSupplierAdjustmentCompletions completions, JdbcFinanceReceiptCreditRepository credits, JdbcProcurementPayableReservationRepository reservations) {
        this.sources = sources; this.operations = operations; this.payments = payments; this.returns = returns; this.checks = checks;
        this.completions = completions; this.credits = credits; this.reservations = reservations;
    }

    /** 外部银行复查在调用前完成；失败只回滚本地完成，重试继续使用已成功的原 ERP 命令。 */
    @Transactional
    public SupplierAdjustmentCompletion complete(SupplierPayableAdjustmentOperation expected, SupplierPaymentReturnPort.Receipt receipt, Instant now) {
        var command = expected.command(); var tenant = command.tenantId(); var paymentId = command.source().returns().request().command().id();
        sources.lock(tenant, paymentId);
        var recorded = completions.find(tenant, command.id());
        if (recorded.isPresent()) {
            var proof = recorded.get();
            if (!proof.operation().command().equals(command) || expected.version() < proof.operation().version()) throw changed();
            return proof;
        }
        var current = operations.find(tenant, command.id()).orElseThrow(SupplierAdjustmentCompletionService::changed);
        if (!current.equals(expected) || !current.adjusted()) throw changed();
        var before = sources.currentForCompletion(command.source());
        var bank = payments.find(tenant, paymentId).orElseThrow(SupplierAdjustmentCompletionService::changed);
        if (checks.history(tenant, paymentId).stream().anyMatch(check -> !receipt.continues(check.receipt()))
                || returns.accountingReceipts(before).stream().anyMatch(prior -> !receipt.continues(prior))) throw changed();
        var proof = SupplierAdjustmentCompletion.from(current, bank, before, receipt, now);
        // 先追加被完成表引用的账本修订，再建立完成外键，最后更新现行账本与所有业务引用。
        returns.stageAccounting(proof); completions.create(proof); returns.completeAccounting(proof);
        credits.account(proof); reservations.completeAdjustment(proof); operations.complete(proof);
        return proof;
    }

    private static DomainException changed() { return new DomainException("SUPPLIER_ADJUSTMENT_COMPLETION_CHANGED", "Current supplier adjustment or previously known bank evidence changed before local accounting"); }
}
