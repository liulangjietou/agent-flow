package io.agentflow.finance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;

/**
 * 财务主动复核后的事务外本人账户读取，与资金执行队列独立。
 * @author owlzhangfq@gmail.com
 */
@Service
public class PaymentPayeeReviewWorker {
    private static final Logger LOG = LoggerFactory.getLogger(PaymentPayeeReviewWorker.class);
    private final JdbcPaymentPayeeReviewRepository reviews;
    private final PaymentPayeeReviewService service;
    private final PaymentAccountsPort accounts;
    /** 所有外部读取均由已保存的原授权条款派生，前端不传入查询目标。 */
    public PaymentPayeeReviewWorker(JdbcPaymentPayeeReviewRepository reviews, PaymentPayeeReviewService service, PaymentAccountsPort accounts) {
        this.reviews = reviews; this.service = service; this.accounts = accounts;
    }
    /** 每批至多十条，短事务领取后释放数据库连接再等待账户接口。 */
    public void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Payee review worker must execute outside a database transaction");
        for (var candidate : reviews.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                var claimed = service.claim(candidate.tenantId(), candidate.id(), Instant.now()); if (claimed == null) continue;
                try {
                    var original = claimed.input().original(); var payee = original.payee();
                    var result = accounts.currentPayee(original.tenantId(), original.targetDigest(), new PaymentAccountsPort.PayeeRequest(payee.legalEntityId(), payee.employeeId()));
                    service.finish(claimed, result, Instant.now());
                } catch (RuntimeException failed) {
                    service.fail(claimed, Instant.now());
                    LOG.error("Payee review read failed, errorCode={}, reviewId={}", "ACCOUNT_READ_FAILURE", candidate.id());
                }
            } catch (RuntimeException failed) { LOG.error("Payee review worker failed, errorCode={}, reviewId={}", "REVIEW_WORKER_FAILURE", candidate.id()); }
        }
    }
}
