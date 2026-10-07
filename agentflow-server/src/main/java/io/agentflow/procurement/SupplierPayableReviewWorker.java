package io.agentflow.procurement;

import io.agentflow.observability.DiagnosticContext;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 原应付复核只调用已批准目标的读取端口，成功读取不产生任何 ERP 预留。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierPayableReviewWorker {
    private static final Logger LOG = LoggerFactory.getLogger(SupplierPayableReviewWorker.class);
    private final JdbcSupplierPayableReviewRepository reviews;
    private final SupplierPayableReviewService execution;
    private final ProcurementPayablePort gateway;

    /** 数据库领取和结果保存由独立事务代理完成，网络等待不占用申请锁。 */
    public SupplierPayableReviewWorker(JdbcSupplierPayableReviewRepository reviews, SupplierPayableReviewService execution, ProcurementPayablePort gateway) {
        this.reviews = reviews; this.execution = execution; this.gateway = gateway;
    }

    /** 按原财务目标、申请人、法人和供应商应付号读取，不接受页面传入 URL 或账户。 */
    public void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Supplier payable review worker must execute outside a database transaction");
        for (var candidate : reviews.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try (var scope = DiagnosticContext.restored(candidate.traceId(), candidate.tenantId(), "supplier-payable-review", candidate.id().toString())
                    .withBusiness(candidate.businessNo(), candidate.processInstanceId(), null).open()) {
                try {
                    var claimed = execution.claim(candidate.tenantId(), candidate.id(), Instant.now()); if (claimed == null) continue;
                    LOG.info("Supplier execution started, errorCode={}, source={}, operationId={}", "NONE", "supplier-payable-review", candidate.id());
                    try {
                        var source = claimed.input().source().reservation().source(); var round = source.round();
                        var result = gateway.payable(source.tenantId(), round.targetDigest(), round.payable().request()); execution.finish(claimed, result, Instant.now());
                    } catch (RuntimeException failed) {
                        execution.fail(claimed, Instant.now()); LOG.error("Supplier payable review failed, errorCode={}, reviewId={}", "REVIEW_FAILURE", candidate.id());
                    }
                } catch (RuntimeException failed) { LOG.error("Supplier payable review worker failed, errorCode={}, reviewId={}", "REVIEW_WORKER_FAILURE", candidate.id()); }
            }
        }
    }
}
