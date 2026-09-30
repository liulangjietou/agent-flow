package io.agentflow.procurement;

import io.agentflow.finance.FinanceResult;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 新调整复查后发送，可能写入后的未知只查原号，记账失败仅恢复本地完成。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierAdjustmentWorker {
    private static final Logger LOG = LoggerFactory.getLogger(SupplierAdjustmentWorker.class);
    private final JdbcSupplierPayableAdjustmentRepository operations;
    private final SupplierAdjustmentService execution;
    private final SupplierAdjustmentEvidenceReader reader;
    private final SupplierPayableAdjustmentPort gateway;
    private final SupplierPaymentReturnPort returns;
    private final SupplierAdjustmentCompletionService completion;

    /** ERP 写入、结果持久化和成功后银行复核具有明确事务边界。 */
    public SupplierAdjustmentWorker(JdbcSupplierPayableAdjustmentRepository operations, SupplierAdjustmentService execution,
            SupplierAdjustmentEvidenceReader reader, SupplierPayableAdjustmentPort gateway, SupplierPaymentReturnPort returns,
            SupplierAdjustmentCompletionService completion) {
        this.operations = operations; this.execution = execution; this.reader = reader; this.gateway = gateway; this.returns = returns; this.completion = completion;
    }

    /** 重启沿持久租约恢复，不从 ERP 成功重建另一个写命令。 */
    public void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Supplier adjustment execution must run outside a database transaction");
        for (var candidate : operations.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                var claimed = execution.claim(candidate.tenantId(), candidate.id(), Instant.now()); if (claimed == null) continue;
                try {
                    if (claimed.status() == SupplierPayableAdjustmentOperation.Status.CHECKING) {
                        var command = claimed.command(); var result = reader.read(command.source(), command.period().request().accountingDate());
                        claimed = execution.ready(claimed, result, Instant.now()); if (claimed == null) continue;
                        claimed.requireSendAt(Instant.now()); execution.finish(claimed, gateway.adjust(claimed.command(), claimed.evidence()), Instant.now());
                    } else execution.finish(claimed, gateway.query(claimed.command()), Instant.now());
                } catch (RuntimeException failed) {
                    execution.fail(claimed, Instant.now()); LOG.error("Supplier adjustment dispatch failed, errorCode={}, operationId={}", "SUPPLIER_ADJUSTMENT_DISPATCH_FAILURE", candidate.id());
                }
            } catch (RuntimeException failed) { LOG.error("Supplier adjustment worker failed, errorCode={}, operationId={}", "SUPPLIER_ADJUSTMENT_WORKER_FAILURE", candidate.id()); }
        }
        for (var candidate : operations.awaitingLocalCompletion()) {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                var operation = operations.find(candidate.tenantId(), candidate.id()).orElseThrow(); if (!operation.adjusted()) continue;
                var checked = returns.query(operation.command().source().returns().request());
                if (checked instanceof FinanceResult.Success<SupplierPaymentReturnPort.Receipt> success) completion.complete(operation, success.value(), Instant.now());
                else LOG.error("Supplier adjustment completion bank read failed, errorCode={}, operationId={}", "SUPPLIER_ADJUSTMENT_COMPLETION_BANK_UNAVAILABLE", candidate.id());
            } catch (RuntimeException failed) { LOG.error("Supplier adjustment local completion failed, errorCode={}, operationId={}", "SUPPLIER_ADJUSTMENT_COMPLETION_FAILURE", candidate.id()); }
        }
    }
}
