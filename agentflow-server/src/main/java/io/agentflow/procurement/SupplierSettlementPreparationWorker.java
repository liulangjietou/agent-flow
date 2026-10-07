package io.agentflow.procurement;

import io.agentflow.observability.DiagnosticContext;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 已保存财务意图的准备消费者，只读取原三项证据，不执行任何资金写入。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierSettlementPreparationWorker {
    private static final Logger LOG = LoggerFactory.getLogger(SupplierSettlementPreparationWorker.class);
    private final JdbcSupplierSettlementPreparationRepository preparations;
    private final SupplierSettlementPreparationService execution;
    private final SupplierSettlementEvidenceReader reader;

    /** 事务代理分别保存领取与登记，中间网络读取不持有业务锁。 */
    public SupplierSettlementPreparationWorker(JdbcSupplierSettlementPreparationRepository preparations,
            SupplierSettlementPreparationService execution, SupplierSettlementEvidenceReader reader) {
        this.preparations = preparations; this.execution = execution; this.reader = reader;
    }

    /** 每次有界扫描；没有财务明确意图时银行成功也不会自行指定记账日期。 */
    public void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Supplier settlement preparation must run outside a database transaction");
        for (var candidate : preparations.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try (var scope = DiagnosticContext.restored(candidate.traceId(), candidate.tenantId(), "supplier-settlement-preparation", candidate.id().toString())
                    .withBusiness(candidate.businessNo(), candidate.processInstanceId(), null).open()) {
                try {
                    var claimed = execution.claim(candidate.tenantId(), candidate.id(), Instant.now()); if (claimed == null) continue;
                    LOG.info("Supplier execution started, errorCode={}, source={}, operationId={}", "NONE", "supplier-settlement-preparation", candidate.id());
                    try {
                        var input = claimed.input(); var result = reader.read(input.payment().command(), input.accountingDate()); execution.finish(claimed, result, Instant.now());
                    } catch (RuntimeException failed) {
                        execution.fail(claimed, Instant.now()); LOG.error("Supplier settlement preparation failed, errorCode={}, preparationId={}", "SUPPLIER_SETTLEMENT_READ_FAILURE", candidate.id());
                    }
                } catch (RuntimeException failed) { LOG.error("Supplier settlement preparation worker failed, errorCode={}, preparationId={}", "SUPPLIER_SETTLEMENT_PREPARATION_FAILURE", candidate.id()); }
            }
        }
    }
}
