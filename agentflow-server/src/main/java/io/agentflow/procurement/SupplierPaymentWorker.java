package io.agentflow.procurement;

import io.agentflow.observability.DiagnosticContext;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 供应商银行执行只消费已登记的原命令，失联恢复不重新读取账户或另建付款身份。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierPaymentWorker {
    private static final Logger LOG = LoggerFactory.getLogger(SupplierPaymentWorker.class);
    private final JdbcSupplierPaymentOperationRepository payments;
    private final SupplierPaymentService execution;
    private final SupplierPaymentEvidenceReader reader;
    private final SupplierPaymentPort gateway;

    /** 事务代理先记录可能发送，再把不可变命令交给固定银行网关。 */
    public SupplierPaymentWorker(JdbcSupplierPaymentOperationRepository payments, SupplierPaymentService execution,
            SupplierPaymentEvidenceReader reader, SupplierPaymentPort gateway) {
        this.payments = payments; this.execution = execution; this.reader = reader; this.gateway = gateway;
    }

    /** 到期恢复由数据库领取协调，网络调用期间不持有任何应用事务。 */
    public void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Supplier bank execution must run outside a database transaction");
        for (var candidate : payments.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try (var scope = DiagnosticContext.restored(candidate.traceId(), candidate.tenantId(), "supplier-payment", candidate.id().toString()).open()) {
                try {
                    var claimed = execution.claim(candidate.tenantId(), candidate.id(), Instant.now()); if (claimed == null) continue;
                    try {
                        if (claimed.status() == SupplierPaymentOperation.Status.CHECKING) {
                            var read = reader.read(claimed.command().holdCommand().authorization(), claimed.command().cashier());
                            claimed = execution.ready(claimed, read, Instant.now()); if (claimed == null) continue;
                            claimed.requireSendAt(Instant.now()); execution.finish(claimed, gateway.execute(claimed.command()), Instant.now());
                        } else execution.finish(claimed, gateway.query(claimed.command()), Instant.now());
                    } catch (RuntimeException failed) {
                        execution.fail(claimed, Instant.now()); LOG.error("Supplier bank dispatch failed, errorCode={}, authorizationId={}", "SUPPLIER_PAYMENT_DISPATCH_FAILURE", candidate.id());
                    }
                } catch (RuntimeException failed) { LOG.error("Supplier bank worker failed, errorCode={}, authorizationId={}", "SUPPLIER_PAYMENT_WORKER_FAILURE", candidate.id()); }
            }
        }
    }
}
