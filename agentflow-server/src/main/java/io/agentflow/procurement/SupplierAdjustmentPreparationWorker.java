package io.agentflow.procurement;

import io.agentflow.observability.DiagnosticContext;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 已持久财务意图的只读消费者，完整命令由领取和登记事务共同约束。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierAdjustmentPreparationWorker {
    private static final Logger LOG = LoggerFactory.getLogger(SupplierAdjustmentPreparationWorker.class);
    private final JdbcSupplierAdjustmentPreparationRepository preparations;
    private final SupplierAdjustmentPreparationService execution;
    private final SupplierAdjustmentEvidenceReader reader;

    /** 外部读取位于两个事务代理调用之间，不持有原申请锁。 */
    public SupplierAdjustmentPreparationWorker(JdbcSupplierAdjustmentPreparationRepository preparations,
            SupplierAdjustmentPreparationService execution, SupplierAdjustmentEvidenceReader reader) {
        this.preparations = preparations; this.execution = execution; this.reader = reader;
    }

    /** 有界扫描只消费明确的财务日期意图；银行回款本身不会自动创建调整。 */
    public void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Supplier adjustment preparation must run outside a database transaction");
        for (var candidate : preparations.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try (var scope = DiagnosticContext.restored(candidate.traceId(), candidate.tenantId(), "supplier-adjustment-preparation", candidate.id().toString())
                    .withBusiness(candidate.businessNo(), candidate.processInstanceId(), null).open()) {
                try {
                    var claimed = execution.claim(candidate.tenantId(), candidate.id(), Instant.now()); if (claimed == null) continue;
                    LOG.info("Supplier execution started, errorCode={}, source={}, operationId={}", "NONE", "supplier-adjustment-preparation", candidate.id());
                    try {
                        var input = claimed.input(); execution.finish(claimed, reader.read(input.source(), input.accountingDate()), Instant.now());
                    } catch (RuntimeException failed) {
                        execution.fail(claimed, Instant.now()); LOG.error("Supplier adjustment preparation failed, errorCode={}, preparationId={}", "SUPPLIER_ADJUSTMENT_READ_FAILURE", candidate.id());
                    }
                } catch (RuntimeException failed) { LOG.error("Supplier adjustment preparation worker failed, errorCode={}, preparationId={}", "SUPPLIER_ADJUSTMENT_PREPARATION_FAILURE", candidate.id()); }
            }
        }
    }
}
