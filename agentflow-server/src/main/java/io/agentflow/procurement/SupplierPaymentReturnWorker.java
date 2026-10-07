package io.agentflow.procurement;

import io.agentflow.observability.DiagnosticContext;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 事务外读取固定原付款的真实回款，每次人工请求至多查询一次。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierPaymentReturnWorker {
    private static final Logger LOG = LoggerFactory.getLogger(SupplierPaymentReturnWorker.class);
    private final JdbcSupplierPaymentReturnCheckRepository checks;
    private final SupplierPaymentReturnService service;
    private final SupplierPaymentReturnPort gateway;

    /** 原输入来自已提交队列，网关无法创建新的资金或 ERP 命令。 */
    public SupplierPaymentReturnWorker(JdbcSupplierPaymentReturnCheckRepository checks, SupplierPaymentReturnService service, SupplierPaymentReturnPort gateway) {
        this.checks = checks; this.service = service; this.gateway = gateway;
    }

    /** 每批最多十笔，异常只保存稳定分类，不记录金融响应正文。 */
    public void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Supplier return reads must execute outside a database transaction");
        for (var candidate : checks.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try (var scope = DiagnosticContext.restored(candidate.traceId(), candidate.tenantId(), "supplier-payment-return", candidate.id().toString())
                    .withBusiness(candidate.businessNo(), candidate.processInstanceId(), null).open()) {
                try {
                    var claimed = service.claim(candidate.tenantId(), candidate.id(), Instant.now()); if (claimed == null) continue;
                    LOG.info("Supplier execution started, errorCode={}, source={}, operationId={}", "NONE", "supplier-payment-return", candidate.id());
                    try { service.finish(claimed, gateway.query(claimed.input().request()), Instant.now()); }
                    catch (RuntimeException failure) {
                        service.fail(claimed, Instant.now()); LOG.error("Supplier return read failed, errorCode={}, checkId={}", "SUPPLIER_PAYMENT_RETURN_READ_FAILURE", candidate.id());
                    }
                } catch (RuntimeException failure) { LOG.error("Supplier return worker failed, errorCode={}, checkId={}", "SUPPLIER_PAYMENT_RETURN_WORKER_FAILURE", candidate.id()); }
            }
        }
    }
}
