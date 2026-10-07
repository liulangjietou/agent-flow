package io.agentflow.procurement;

import io.agentflow.observability.DiagnosticContext;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 原应付结算先保存可能写入事实再发送；失联只查询，补本地完成不再次触发 ERP。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierSettlementWorker {
    private static final Logger LOG = LoggerFactory.getLogger(SupplierSettlementWorker.class);
    private final JdbcSupplierPayableSettlementRepository settlements;
    private final SupplierSettlementService execution;
    private final SupplierSettlementEvidenceReader reader;
    private final SupplierPayableSettlementPort gateway;

    /** 网关消费固定命令，当前复查依据只作为发送门槛。 */
    public SupplierSettlementWorker(JdbcSupplierPayableSettlementRepository settlements, SupplierSettlementService execution,
            SupplierSettlementEvidenceReader reader, SupplierPayableSettlementPort gateway) {
        this.settlements = settlements; this.execution = execution; this.reader = reader; this.gateway = gateway;
    }

    /** 外部调用均在事务之外，旧租约与迟到回执由数据库版本协调。 */
    public void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Supplier settlement execution must run outside a database transaction");
        for (var candidate : settlements.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try (var scope = DiagnosticContext.restored(candidate.traceId(), candidate.tenantId(), "supplier-settlement", candidate.id().toString()).open()) {
                try {
                    var claimed = execution.claim(candidate.tenantId(), candidate.id(), Instant.now()); if (claimed == null) continue;
                    try {
                        if (claimed.status() == SupplierPayableSettlementOperation.Status.CHECKING) {
                            var command = claimed.command(); var result = reader.read(command.payment(), command.period().request().accountingDate());
                            claimed = execution.ready(claimed, result, Instant.now()); if (claimed == null) continue;
                            claimed.requireSendAt(Instant.now()); execution.finish(claimed, gateway.settle(claimed.command(), claimed.evidence()), Instant.now());
                        } else execution.finish(claimed, gateway.query(claimed.command()), Instant.now());
                    } catch (RuntimeException failed) {
                        execution.fail(claimed, Instant.now()); LOG.error("Supplier settlement dispatch failed, errorCode={}, operationId={}", "SUPPLIER_SETTLEMENT_DISPATCH_FAILURE", candidate.id());
                    }
                } catch (RuntimeException failed) { LOG.error("Supplier settlement worker failed, errorCode={}, operationId={}", "SUPPLIER_SETTLEMENT_WORKER_FAILURE", candidate.id()); }
            }
        }
        for (var candidate : settlements.awaitingLocalCompletion()) {
            if (Thread.currentThread().isInterrupted()) return;
            try (var scope = DiagnosticContext.restored(candidate.traceId(), candidate.tenantId(), "supplier-settlement", candidate.id().toString()).open()) {
                try { execution.completeLocal(candidate.tenantId(), candidate.id(), Instant.now()); }
                catch (RuntimeException failed) { LOG.error("Supplier local settlement completion failed, errorCode={}, operationId={}", "SUPPLIER_SETTLEMENT_COMPLETION_FAILURE", candidate.id()); }
            }
        }
    }
}
