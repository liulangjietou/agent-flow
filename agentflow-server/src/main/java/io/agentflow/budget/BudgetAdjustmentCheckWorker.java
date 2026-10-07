package io.agentflow.budget;

import io.agentflow.observability.DiagnosticContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;

/**
 * 有界领取预算调整申请预检，HTTP 都在短事务之外执行。
 * @author owlzhangfq@gmail.com
 */
@Service
public class BudgetAdjustmentCheckWorker {
    private static final Logger LOG = LoggerFactory.getLogger(BudgetAdjustmentCheckWorker.class);
    private final JdbcBudgetAdjustmentCheckRepository jobs;
    private final BudgetAdjustmentCheckService execution;
    private final BudgetAdjustmentCheckEvaluator evaluator;
    /** 不保存进程内进度，多进程通过同一申请锁和租约协调。 */
    public BudgetAdjustmentCheckWorker(JdbcBudgetAdjustmentCheckRepository jobs, BudgetAdjustmentCheckService execution, BudgetAdjustmentCheckEvaluator evaluator) {
        this.jobs = jobs; this.execution = execution; this.evaluator = evaluator;
    }
    /** 失败只记录稳定分类，日志不包含预算正文、账户或外部响应。 */
    public void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Budget adjustment worker must execute outside a database transaction");
        for (var candidate : jobs.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try (var scope = DiagnosticContext.restored(candidate.traceId(), candidate.tenantId(), "budget-adjustment-check", candidate.id().toString())
                    .withBusiness(candidate.businessNo(), candidate.processInstanceId(), null).open()) {
                try {
                    var job = execution.claim(candidate.tenantId(), candidate.id(), Instant.now());
                    if (job == null) continue;
                    LOG.info("Financial review execution claimed, errorCode={}, source={}, operationId={}", "NONE", "budget-adjustment-check", candidate.id());
                    BudgetAdjustmentCheck.Result result;
                    try { result = evaluator.evaluate(job); }
                    catch (RuntimeException failed) {
                        result = BudgetAdjustmentCheck.Result.unavailable("INTERNAL_ERROR");
                        LOG.error("Budget adjustment check failed, errorCode={}, jobId={}", "INTERNAL_ERROR", candidate.id());
                    }
                    execution.finish(job, result, Instant.now());
                } catch (RuntimeException failed) {
                    LOG.error("Budget adjustment check execution failed, errorCode={}, jobId={}", "WORKER_FAILURE", candidate.id());
                }
            }
        }
    }
}
