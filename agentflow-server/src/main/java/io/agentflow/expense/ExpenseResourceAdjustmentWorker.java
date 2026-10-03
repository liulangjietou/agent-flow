package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.AccountingPeriodPort;
import io.agentflow.finance.BudgetConsumptionReversalOperation;
import io.agentflow.finance.BudgetConsumptionReversalPort;
import io.agentflow.finance.JdbcBudgetConsumptionReversalRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 只读准备、已授权预算外发和本地资源执行分批有界处理，网络调用不持有数据库事务。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseResourceAdjustmentWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ExpenseResourceAdjustmentWorker.class);
    private final JdbcExpenseResourceAdjustmentPreparationRepository preparations;
    private final ExpenseResourceAdjustmentPreparationService preparing;
    private final AccountingPeriodPort periods;
    private final JdbcBudgetConsumptionReversalRepository budgets;
    private final ExpenseResourceAdjustmentBudgetExecution budgetExecution;
    private final BudgetConsumptionReversalPort budgetPort;
    private final JdbcExpenseResourceAdjustmentRepository adjustments;
    private final ExpenseResourceAdjustmentExecution resourceExecution;
    /** 领取与完成通过事务代理分开执行，网络端口只在两者之间调用。 */
    public ExpenseResourceAdjustmentWorker(JdbcExpenseResourceAdjustmentPreparationRepository preparations, ExpenseResourceAdjustmentPreparationService preparing,
            AccountingPeriodPort periods, JdbcBudgetConsumptionReversalRepository budgets, ExpenseResourceAdjustmentBudgetExecution budgetExecution,
            BudgetConsumptionReversalPort budgetPort, JdbcExpenseResourceAdjustmentRepository adjustments, ExpenseResourceAdjustmentExecution resourceExecution) {
        this.preparations = preparations; this.preparing = preparing; this.periods = periods; this.budgets = budgets; this.budgetExecution = budgetExecution;
        this.budgetPort = budgetPort; this.adjustments = adjustments; this.resourceExecution = resourceExecution;
    }
    /** 单批各最多十笔，准备读取完成仍等待独立财务明确授权。 */
    public void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Expense resource adjustment worker must run outside a database transaction");
        prepare(); budget(); resources();
    }
    private void prepare() {
        for (var candidate : preparations.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                var claimed = preparing.claim(candidate.tenantId(), candidate.id(), Instant.now()); if (claimed == null) continue;
                try {
                    var input = claimed.input(); var result = periods.period(candidate.tenantId(), input.basis().consumption().input().targetDigest(), input.periodRequest());
                    preparing.finish(claimed, result, Instant.now());
                } catch (RuntimeException failed) { preparing.fail(claimed, Instant.now()); log("ADJUSTMENT_PREPARATION_FAILURE", candidate.id(), failed); }
            } catch (RuntimeException failed) { log("ADJUSTMENT_PREPARATION_WORKER_FAILURE", candidate.id(), failed); }
        }
    }
    private void budget() {
        for (var candidate : budgets.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                var claimed = budgetExecution.claim(candidate.tenantId(), candidate.id(), Instant.now()); if (claimed == null) continue;
                try {
                    var input = claimed.input(); var result = claimed.status() == BudgetConsumptionReversalOperation.Status.EXECUTING
                            ? budgetPort.execute(input.targetDigest(), input.command()) : budgetPort.query(input.targetDigest(), input.command());
                    budgetExecution.finish(claimed, result, Instant.now());
                } catch (RuntimeException failed) { budgetExecution.fail(claimed, Instant.now()); log("ADJUSTMENT_BUDGET_DISPATCH_FAILURE", candidate.id(), failed); }
            } catch (RuntimeException failed) { log("ADJUSTMENT_BUDGET_WORKER_FAILURE", candidate.id(), failed); }
        }
    }
    private void resources() {
        for (var candidate : adjustments.ready()) {
            if (Thread.currentThread().isInterrupted()) return;
            try { resourceExecution.apply(candidate); }
            catch (DomainException problem) {
                try { if (!"CONCURRENCY_CONFLICT".equals(problem.code())) resourceExecution.block(candidate, problem.code()); }
                catch (RuntimeException failed) { log("ADJUSTMENT_RESOURCE_BLOCK_FAILURE", candidate.id(), failed); }
            } catch (RuntimeException failed) { log("ADJUSTMENT_RESOURCE_FAILURE", candidate.id(), failed); }
        }
    }
    private static void log(String code, java.util.UUID id, RuntimeException failure) {
        LOG.error("Expense resource adjustment worker failed, errorCode={}, adjustmentId={}", failure instanceof DomainException problem ? problem.code() : code, id);
    }
}
