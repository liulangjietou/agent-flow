package io.agentflow.expense;

import io.agentflow.finance.AdvanceRepaymentAdjustmentPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;

/**
 * 后台读取原还款与真实退回依据，查询不会自动裁决或发送资金。
 * @author owlzhangfq@gmail.com
 */
@Service
public class AdvanceRepaymentReviewWorker {
    private static final Logger LOG = LoggerFactory.getLogger(AdvanceRepaymentReviewWorker.class);
    private final JdbcRepaymentReviewCheckRepository checks;
    private final AdvanceRepaymentReviewService service;
    private final AdvanceRepaymentAdjustmentPort port;
    /** 队列和短事务编排与外部传输分离。 */
    public AdvanceRepaymentReviewWorker(JdbcRepaymentReviewCheckRepository checks, AdvanceRepaymentReviewService service, AdvanceRepaymentAdjustmentPort port) { this.checks = checks; this.service = service; this.port = port; }
    /** 每批至多十笔，外部异常不输出凭据正文。 */
    public void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Repayment worker must execute outside a database transaction");
        for (var candidate : checks.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                var claimed = service.claim(candidate.tenantId(), candidate.id(), Instant.now()); if (claimed == null) continue;
                try {
                    var input = claimed.input(); service.finish(claimed, port.query(input.tenantId(), input.targetDigest(), input.request()), Instant.now());
                } catch (RuntimeException failure) {
                    service.fail(claimed, Instant.now()); LOG.error("Repayment review read failed, errorCode={}, checkId={}", "REPAYMENT_REVIEW_READ_FAILURE", candidate.id());
                }
            } catch (RuntimeException failure) { LOG.error("Repayment review worker failed, errorCode={}, checkId={}", "REPAYMENT_REVIEW_WORKER_FAILURE", candidate.id()); }
        }
    }
}
