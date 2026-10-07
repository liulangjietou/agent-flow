package io.agentflow.expense;

import io.agentflow.observability.DiagnosticContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;

/**
 * 有界领取事前计划预检，HTTP 都在短事务之外执行。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpensePlanCheckWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ExpensePlanCheckWorker.class);
    private final JdbcExpensePlanCheckRepository jobs;
    private final ExpensePlanCheckService execution;
    private final ExpensePlanCheckEvaluator evaluator;
    /** 不保存进程内进度，多进程通过同一申请锁和租约协调。 */
    public ExpensePlanCheckWorker(JdbcExpensePlanCheckRepository jobs, ExpensePlanCheckService execution, ExpensePlanCheckEvaluator evaluator) {
        this.jobs = jobs; this.execution = execution; this.evaluator = evaluator;
    }
    /** 失败只记录稳定分类，日志不包含费用正文、账户或外部响应。 */
    public void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Expense plan worker must execute outside a database transaction");
        for (var candidate : jobs.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try (var scope = DiagnosticContext.restored(candidate.traceId(), candidate.tenantId(), "expense-plan-check", candidate.id().toString())
                    .withBusiness(candidate.businessNo(), candidate.processInstanceId(), null).open()) {
                try {
                    var job = execution.claim(candidate.tenantId(), candidate.id(), Instant.now());
                    if (job == null) continue;
                    LOG.info("Financial review execution claimed, errorCode={}, source={}, operationId={}", "NONE", "expense-plan-check", candidate.id());
                    ExpensePlanCheck.Result result;
                    try { result = evaluator.evaluate(job); }
                    catch (RuntimeException failed) {
                        result = ExpensePlanCheck.Result.unavailable("INTERNAL_ERROR");
                        LOG.error("Expense plan check failed, errorCode={}, jobId={}", "INTERNAL_ERROR", candidate.id());
                    }
                    execution.finish(job, result, Instant.now());
                } catch (RuntimeException failed) {
                    LOG.error("Expense plan check execution failed, errorCode={}, jobId={}", "WORKER_FAILURE", candidate.id());
                }
            }
        }
    }
}
