package io.agentflow.expense;

import io.agentflow.observability.DiagnosticContext;

import io.agentflow.finance.AdvanceRepaymentPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;

/**
 * 后台只读取原资金系统收款凭据，成功也仍需独立财务确认。
 * @author owlzhangfq@gmail.com
 */
@Service
public class AdvanceRepaymentWorker {
    private static final Logger LOG = LoggerFactory.getLogger(AdvanceRepaymentWorker.class);
    private final JdbcAdvanceRepaymentCheckRepository checks;
    private final AdvanceRepaymentService service;
    private final AdvanceRepaymentPort port;
    /** 队列和短事务编排与外部传输分离。 */
    public AdvanceRepaymentWorker(JdbcAdvanceRepaymentCheckRepository checks, AdvanceRepaymentService service, AdvanceRepaymentPort port) { this.checks = checks; this.service = service; this.port = port; }
    /** 每批至多十笔，外部异常不输出凭据正文。 */
    public void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Repayment worker must execute outside a database transaction");
        for (var candidate : checks.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try (var scope = DiagnosticContext.restored(candidate.traceId(), candidate.tenantId(), "advance-repayment-check", candidate.id().toString()).open()) {
                try {
                    var claimed = service.claim(candidate.tenantId(), candidate.id(), Instant.now()); if (claimed == null) continue;
                    try {
                        var input = claimed.input(); service.finish(claimed, port.query(input.tenantId(), input.targetDigest(), input.request()), Instant.now());
                    } catch (RuntimeException failure) {
                        service.fail(claimed, Instant.now()); LOG.error("Advance repayment read failed, errorCode={}, checkId={}", "REPAYMENT_READ_FAILURE", candidate.id());
                    }
                } catch (RuntimeException failure) { LOG.error("Advance repayment worker failed, errorCode={}, checkId={}", "REPAYMENT_WORKER_FAILURE", candidate.id()); }
            }
        }
    }
}
