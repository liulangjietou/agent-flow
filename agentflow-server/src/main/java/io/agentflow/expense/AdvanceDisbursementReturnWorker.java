package io.agentflow.expense;

import io.agentflow.finance.AdvanceDisbursementReturnPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;

/**
 * 后台读取原放款与真实退回依据，查询不会自动裁决或发送资金。
 * @author owlzhangfq@gmail.com
 */
@Service
public class AdvanceDisbursementReturnWorker {
    private static final Logger LOG = LoggerFactory.getLogger(AdvanceDisbursementReturnWorker.class);
    private final JdbcDisbursementReturnCheckRepository checks;
    private final AdvanceDisbursementReturnService service;
    private final AdvanceDisbursementReturnPort port;
    /** 队列和短事务编排与外部传输分离。 */
    public AdvanceDisbursementReturnWorker(JdbcDisbursementReturnCheckRepository checks, AdvanceDisbursementReturnService service, AdvanceDisbursementReturnPort port) { this.checks = checks; this.service = service; this.port = port; }
    /** 每批至多十笔，外部异常不输出凭据正文。 */
    public void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Disbursement worker must execute outside a database transaction");
        for (var candidate : checks.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                var claimed = service.claim(candidate.tenantId(), candidate.id(), Instant.now()); if (claimed == null) continue;
                try {
                    var input = claimed.input(); service.finish(claimed, port.query(input.tenantId(), input.targetDigest(), input.request()), Instant.now());
                } catch (RuntimeException failure) {
                    service.fail(claimed, Instant.now()); LOG.error("Disbursement return read failed, errorCode={}, checkId={}", "DISBURSEMENT_RETURN_READ_FAILURE", candidate.id());
                }
            } catch (RuntimeException failure) { LOG.error("Disbursement return worker failed, errorCode={}, checkId={}", "DISBURSEMENT_RETURN_WORKER_FAILURE", candidate.id()); }
        }
    }
}
