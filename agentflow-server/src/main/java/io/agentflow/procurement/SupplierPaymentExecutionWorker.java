package io.agentflow.procurement;

import io.agentflow.observability.DiagnosticContext;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 出纳选择只触发只读准备，银行登记由独立事务服务原子完成。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierPaymentExecutionWorker {
    private static final Logger LOG = LoggerFactory.getLogger(SupplierPaymentExecutionWorker.class);
    private final JdbcSupplierPaymentExecutionRepository requests;
    private final SupplierPaymentExecutionService execution;
    private final SupplierPaymentEvidenceReader reader;

    /** 读取三项依据时不持有审批锁，迟到响应由领取版本隔离。 */
    public SupplierPaymentExecutionWorker(JdbcSupplierPaymentExecutionRepository requests, SupplierPaymentExecutionService execution, SupplierPaymentEvidenceReader reader) {
        this.requests = requests; this.execution = execution; this.reader = reader;
    }

    /** 一次扫描至多十个已保存选择，不推断出纳默认账户。 */
    public void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Supplier cashier preparation must run outside a database transaction");
        for (var candidate : requests.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try (var scope = DiagnosticContext.restored(candidate.traceId(), candidate.tenantId(), "supplier-payment-execution", candidate.id().toString()).open()) {
                try {
                    var claimed = execution.claim(candidate.tenantId(), candidate.id(), Instant.now()); if (claimed == null) continue;
                    try {
                        var result = reader.read(execution.authorization(claimed), claimed.input().cashier()); execution.finish(claimed, result, Instant.now());
                    } catch (RuntimeException failed) {
                        execution.fail(claimed, Instant.now()); LOG.error("Supplier cashier preparation failed, errorCode={}, requestId={}", "SUPPLIER_EXECUTION_READ_FAILURE", candidate.id());
                    }
                } catch (RuntimeException failed) { LOG.error("Supplier cashier worker failed, errorCode={}, requestId={}", "SUPPLIER_EXECUTION_WORKER_FAILURE", candidate.id()); }
            }
        }
    }
}
