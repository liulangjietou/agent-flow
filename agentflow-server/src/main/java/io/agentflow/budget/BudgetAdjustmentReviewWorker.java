package io.agentflow.budget;

import io.agentflow.observability.DiagnosticContext;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 财务台账读取在短事务外执行，保存成功读取后等待明确授权，不触发额度变动。
 * @author owlzhangfq@gmail.com
 */
@Service
public class BudgetAdjustmentReviewWorker {
    private static final Logger LOG = LoggerFactory.getLogger(BudgetAdjustmentReviewWorker.class);
    private final JdbcBudgetAdjustmentReviewRepository reviews;
    private final BudgetAdjustmentReviewService service;
    private final BudgetLedgerPort ledger;
    /** 数据库领取和结果保存交给独立事务代理。 */
    public BudgetAdjustmentReviewWorker(JdbcBudgetAdjustmentReviewRepository reviews, BudgetAdjustmentReviewService service, BudgetLedgerPort ledger) {
        this.reviews = reviews; this.service = service; this.ledger = ledger;
    }
    /** 按原批准中的目标、预算引用和申请人读取，不能接受任意页面 URL。 */
    public void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Budget review worker must run outside a database transaction");
        for (var candidate : reviews.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try (var scope = DiagnosticContext.restored(candidate.traceId(), candidate.tenantId(), "budget-adjustment-review", candidate.id().toString())
                    .withBusiness(candidate.businessNo(), candidate.processInstanceId(), null).open()) {
                try {
                    var claimed = service.claim(candidate.tenantId(), candidate.id(), Instant.now()); if (claimed == null) continue;
                    String instance = candidate.businessNo() == null ? null : reviews.findProcessInstance(claimed.input().source());
                    try (var business = DiagnosticContext.capture().withBusiness(candidate.businessNo(), instance, null).open()) {
                        process(candidate, claimed);
                    }
                } catch (RuntimeException failed) { LOG.error("Budget review worker failed, errorCode={}, reviewId={}", "REVIEW_WORKER_FAILURE", candidate.id()); }
            }
        }
    }
    /** 失败回写再次异常时也在原轮次作用域内记录，避免外层日志丢失实例。 */
    private void process(JdbcBudgetAdjustmentReviewRepository.Candidate candidate, BudgetAdjustmentReview claimed) {
        try {
            try {
                LOG.info("Financial review execution claimed, errorCode={}, source={}, operationId={}", "NONE", "budget-adjustment-review", candidate.id());
                var source = claimed.input().source();
                var result = ledger.read(source.tenantId(), source.round().targetDigest(), source.round().content().ledgerRequest(source.employeeId()));
                service.finish(claimed, result, Instant.now());
            } catch (RuntimeException failed) {
                service.fail(claimed, Instant.now()); LOG.error("Budget review failed, errorCode={}, reviewId={}", "REVIEW_FAILURE", candidate.id());
            }
        } catch (RuntimeException failed) {
            LOG.error("Budget review worker failed, errorCode={}, operationId={}", "REVIEW_WORKER_FAILURE", candidate.id());
        }
    }

}
