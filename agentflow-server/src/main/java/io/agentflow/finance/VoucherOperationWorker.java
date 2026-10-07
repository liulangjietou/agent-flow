package io.agentflow.finance;

import io.agentflow.observability.DiagnosticContext;
import io.agentflow.common.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;

/**
 * 凭证外部副作用在领取事务提交之后执行，未知结果按原编号恢复。
 * @author owlzhangfq@gmail.com
 */
@Service
public class VoucherOperationWorker {
    private static final Logger LOG = LoggerFactory.getLogger(VoucherOperationWorker.class);
    private static final String TRACE_SOURCE = "voucher-operation";
    private final JdbcVoucherOperationRepository operations;
    private final VoucherOperationService execution;
    private final AccountingVoucherPort accounting;
    /** 领取和落库经过独立事务代理，HTTP 等待不占申请锁。 */
    public VoucherOperationWorker(JdbcVoucherOperationRepository operations, VoucherOperationService execution, AccountingVoucherPort accounting) {
        this.operations = operations; this.execution = execution; this.accounting = accounting;
    }
    /** 每批最多十个到期任务，接到线程中断后不再领取。 */
    public void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Voucher worker must execute outside a database transaction");
        for (var candidate : operations.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try (var trace = DiagnosticContext.restored(candidate.traceId(), candidate.tenantId(),
                    TRACE_SOURCE, candidate.id().toString()).open()) {
                try {
                    var claimed = execution.claim(candidate.tenantId(), candidate.id(), Instant.now()); if (claimed == null) continue;
                    try {
                        var input = claimed.input();
                        var result = claimed.status() == VoucherOperation.Status.POSTING ? accounting.post(input.targetDigest(), input.command()) : accounting.query(input.targetDigest(), input.command());
                        execution.finish(claimed, result, Instant.now());
                    } catch (RuntimeException failed) {
                        var reason = failed instanceof DomainException domain && "VOUCHER_EVIDENCE_EXPIRED".equals(domain.code())
                                ? VoucherOperation.Failure.EVIDENCE_EXPIRED : VoucherOperation.Failure.INTERNAL_ERROR;
                        execution.fail(claimed, reason, Instant.now());
                        LOG.error("Voucher dispatch failed, errorCode={}, operationId={}", reason, candidate.id());
                    }
                } catch (RuntimeException failed) { LOG.error("Voucher worker failed, errorCode={}, operationId={}", "WORKER_FAILURE", candidate.id()); }
            }
        }
    }
}
