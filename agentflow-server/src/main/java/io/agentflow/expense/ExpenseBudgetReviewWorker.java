package io.agentflow.expense;

import io.agentflow.observability.DiagnosticContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.agentflow.approval.process.ExpenseBudgetReviewRecovery;
import java.time.Instant;

/**
 * 预算结果已持久化后恢复原生节点。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseBudgetReviewWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ExpenseBudgetReviewWorker.class);
    private final JdbcExpenseBudgetReviewRepository reviews;
    private final ExpenseBudgetReviewRecovery recovery;

    /** 每条记录单独经过事务代理，不让一个失败回滚其他原轮次。 */
    public ExpenseBudgetReviewWorker(JdbcExpenseBudgetReviewRepository reviews, ExpenseBudgetReviewRecovery recovery) {
        this.reviews = reviews; this.recovery = recovery;
    }

    /** 每次最多恢复十条，多个进程通过原申请锁串行化而不重新执行外部命令。 */
    public void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Budget review worker must start outside a transaction");
        for (var candidate : reviews.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try (var scope = DiagnosticContext.restored(candidate.traceId(), candidate.tenantId(), "expense-budget-review", candidate.reportId() + ":" + candidate.roundNo())
                    .withBusiness(candidate.businessNo(), candidate.processInstanceId(), null).open()) {
                try {
                    LOG.info("Expense recovery execution started, errorCode={}, source={}, operationId={}", "NONE", "expense-budget-review", candidate.reportId());
                    recovery.recover(candidate);
                }
                catch (RuntimeException failure) {
                    LOG.error("Budget review recovery failed, errorCode={}, reportId={}", "RECOVERY_FAILURE", candidate.reportId());
                    try { recovery.defer(candidate); }
                    catch (RuntimeException deferred) {
                        LOG.error("Budget review reschedule failed, errorCode={}, reportId={}", "RESCHEDULE_FAILURE", candidate.reportId());
                    }
                }
            }
        }
    }
}
