package io.agentflow.finance;

import io.agentflow.observability.DiagnosticContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;

/**
 * 预算外发和查询均在事务外执行，后台线程中断或故障后由持久任务恢复。
 * @author owlzhangfq@gmail.com
 */
@Service
public class BudgetOperationWorker {
    private static final Logger LOG = LoggerFactory.getLogger(BudgetOperationWorker.class);
    private static final String TRACE_SOURCE = "budget-operation";
    private final JdbcBudgetOperationRepository operations;
    private final BudgetOperationService execution;
    private final BudgetSystemPort budget;
    /** 领取和完成分别经过事务代理，HTTP 不持有单据锁。 */
    public BudgetOperationWorker(JdbcBudgetOperationRepository operations, BudgetOperationService execution, BudgetSystemPort budget) {
        this.operations = operations; this.execution = execution; this.budget = budget;
    }
    /** 单次最多处理十条到期任务；日志仅包含稳定分类与操作编号。 */
    public void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Budget worker must execute outside a database transaction");
        for (var candidate : operations.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try (var trace = DiagnosticContext.restored(candidate.traceId(), candidate.tenantId(),
                    TRACE_SOURCE, candidate.id().toString())
                    .withBusiness(candidate.businessNo(), null, null).open()) {
                try {
                    var claimed = execution.claim(candidate.tenantId(), candidate.id(), Instant.now()); if (claimed == null) continue;
                    LOG.info("Finance execution claimed, errorCode={}, source={}, operationId={}", "NONE", TRACE_SOURCE, candidate.id());
                    try {
                        var input = claimed.input();
                        var result = claimed.status() == BudgetOperation.Status.EXECUTING ? budget.execute(input.targetDigest(), input.command()) : budget.query(input.targetDigest(), input.command());
                        execution.finish(claimed, result, Instant.now());
                    } catch (RuntimeException failed) {
                        execution.fail(claimed, Instant.now());
                        LOG.error("Budget dispatch failed, errorCode={}, operationId={}", "INTERNAL_ERROR", candidate.id());
                    }
                } catch (RuntimeException failed) {
                    LOG.error("Budget worker failed, errorCode={}, operationId={}", "WORKER_FAILURE", candidate.id());
                }
            }
        }
    }
}
