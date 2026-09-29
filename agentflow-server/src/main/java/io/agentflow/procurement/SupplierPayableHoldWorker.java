package io.agentflow.procurement;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 原应付预留只在数据库领取提交后外发，故障继续查询原授权而不另造命令。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierPayableHoldWorker {
    private static final Logger LOG = LoggerFactory.getLogger(SupplierPayableHoldWorker.class);
    private final JdbcSupplierPayableHoldRepository operations;
    private final SupplierPayableHoldService execution;
    private final SupplierPayableHoldPort gateway;

    /** 独立代理负责短事务，工作线程不跨网络保留数据库锁。 */
    public SupplierPayableHoldWorker(JdbcSupplierPayableHoldRepository operations, SupplierPayableHoldService execution, SupplierPayableHoldPort gateway) {
        this.operations = operations; this.execution = execution; this.gateway = gateway;
    }

    /** 到期领取由数据库版本协调，单次扫描至多十个原操作。 */
    public void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Supplier payable hold worker must execute outside a database transaction");
        for (var candidate : operations.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                var claimed = execution.claim(candidate.tenantId(), candidate.id(), Instant.now()); if (claimed == null) continue;
                try {
                    if (claimed.status() == SupplierPayableHoldOperation.Status.RESERVING) {
                        claimed.requireSendAt(Instant.now()); execution.finish(claimed, gateway.reserve(claimed.command()), Instant.now());
                    } else execution.finish(claimed, gateway.query(claimed.command()), Instant.now());
                } catch (RuntimeException failed) {
                    execution.fail(claimed, SupplierPayableHoldOperation.Failure.INTERNAL_ERROR, Instant.now());
                    LOG.error("Supplier payable hold dispatch failed, errorCode={}, authorizationId={}", "HOLD_DISPATCH_FAILURE", candidate.id());
                }
            } catch (RuntimeException failed) {
                LOG.error("Supplier payable hold worker failed, errorCode={}, authorizationId={}", "HOLD_WORKER_FAILURE", candidate.id());
            }
        }
    }
}
