package io.agentflow.finance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;

/**
 * 后台读取真实原凭证与独立反向分录，查询不会自动登记或发送会计命令。
 * @author owlzhangfq@gmail.com
 */
@Service
public class VoucherReversalWorker {
    private static final Logger LOG = LoggerFactory.getLogger(VoucherReversalWorker.class);
    private final JdbcVoucherReversalCheckRepository checks;
    private final VoucherReversalService service;
    private final VoucherReversalPort port;
    /** 队列和短事务编排与外部传输分离。 */
    public VoucherReversalWorker(JdbcVoucherReversalCheckRepository checks, VoucherReversalService service, VoucherReversalPort port) { this.checks = checks; this.service = service; this.port = port; }
    /** 每批至多十笔，外部异常不输出凭据正文。 */
    public void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Voucher reversal worker must execute outside a database transaction");
        for (var candidate : checks.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                var claimed = service.claim(candidate.tenantId(), candidate.id(), Instant.now()); if (claimed == null) continue;
                try {
                    var input = claimed.input(); service.finish(claimed, port.query(input.tenantId(), input.targetDigest(), input.request()), Instant.now());
                } catch (RuntimeException failure) {
                    service.fail(claimed, Instant.now()); LOG.error("Voucher reversal read failed, errorCode={}, checkId={}", "VOUCHER_REVERSAL_READ_FAILURE", candidate.id());
                }
            } catch (RuntimeException failure) { LOG.error("Voucher reversal worker failed, errorCode={}, checkId={}", "VOUCHER_REVERSAL_WORKER_FAILURE", candidate.id()); }
        }
    }
}
