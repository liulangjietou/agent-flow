package io.agentflow.expense;

import io.agentflow.observability.DiagnosticContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;

/**
 * 有界领取费用预检，文件和 HTTP 都在短事务之外执行。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpensePrecheckWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ExpensePrecheckWorker.class);
    private static final String TRACE_SOURCE = "expense-precheck";
    private final JdbcExpensePrecheckRepository jobs;
    private final ExpensePrecheckService execution;
    private final ExpensePrecheckEvaluator evaluator;
    /** 不保存进程内进度，多进程通过同一申请锁和租约协调。 */
    public ExpensePrecheckWorker(JdbcExpensePrecheckRepository jobs, ExpensePrecheckService execution, ExpensePrecheckEvaluator evaluator) {
        this.jobs = jobs; this.execution = execution; this.evaluator = evaluator;
    }
    /** 失败只记录稳定分类，日志不包含费用正文、账户或外部响应。 */
    public void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Expense worker must execute outside a database transaction");
        for (var candidate : jobs.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try (var trace = DiagnosticContext.restored(candidate.traceId(), candidate.tenantId(),
                    TRACE_SOURCE, candidate.id().toString()).open()) {
                try {
                    var job = execution.claim(candidate.tenantId(), candidate.id(), Instant.now());
                    if (job == null) continue;
                    ExpensePrecheckJob.Result result;
                    try { result = evaluator.evaluate(job); }
                    catch (RuntimeException failed) {
                        result = ExpensePrecheckJob.Result.unavailable(ExpensePrecheckJob.Stage.SYSTEM, "INTERNAL_ERROR");
                        LOG.error("Expense precheck failed, errorCode={}, jobId={}", "INTERNAL_ERROR", candidate.id());
                    }
                    execution.finish(job, result, Instant.now());
                } catch (RuntimeException failed) {
                    LOG.error("Expense precheck execution failed, errorCode={}, jobId={}", "WORKER_FAILURE", candidate.id());
                }
            }
        }
    }
}
