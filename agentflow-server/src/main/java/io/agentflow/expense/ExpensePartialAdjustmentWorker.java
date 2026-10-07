package io.agentflow.expense;

import io.agentflow.observability.DiagnosticContext;

import io.agentflow.common.DomainException;
import io.agentflow.finance.BudgetConsumptionReductionOperation;
import io.agentflow.finance.BudgetConsumptionReductionPort;
import io.agentflow.finance.ExpenseAccrualReductionOperation;
import io.agentflow.finance.ExpenseAccrualReductionPort;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 分别恢复预算、ERP 和本地完成，外部调用在事务外，任一侧失败不会跳过另一侧的恢复。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpensePartialAdjustmentWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ExpensePartialAdjustmentWorker.class);
    private final JdbcExpensePartialAdjustmentRepository adjustments;
    private final ExpensePartialAdjustmentFinance finance;
    private final BudgetConsumptionReductionPort budgets;
    private final ExpenseAccrualReductionPort accruals;
    private final ExpensePartialAdjustmentExecution resources;
    private final JdbcExpensePartialPreparationRepository preparations;
    private final ExpensePartialPreparationService preparing;
    private final ExpensePartialPreparationReader reader;

    /** 事务代理先提交领取，再外发，最后提交回执；本地完成另开事务。 */
    public ExpensePartialAdjustmentWorker(JdbcExpensePartialAdjustmentRepository adjustments, ExpensePartialAdjustmentFinance finance,
            BudgetConsumptionReductionPort budgets, ExpenseAccrualReductionPort accruals, ExpensePartialAdjustmentExecution resources,
            JdbcExpensePartialPreparationRepository preparations, ExpensePartialPreparationService preparing, ExpensePartialPreparationReader reader) {
        this.adjustments = adjustments; this.finance = finance; this.budgets = budgets; this.accruals = accruals; this.resources = resources;
        this.preparations = preparations; this.preparing = preparing; this.reader = reader;
    }

    /** 每侧最多十笔，准备和财务授权不由调度器自动补建。 */
    public void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Partial adjustment worker must run outside a database transaction");
        prepare(); budget(); accrual(); completeResources();
    }
    private void prepare() {
        for (var candidate : preparations.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try (var scope = DiagnosticContext.restored(candidate.traceId(), candidate.tenantId(), "expense-partial-preparation", candidate.id().toString())
                    .withBusiness(candidate.businessNo(), candidate.processInstanceId(), null).open()) {
                try {
                    var claimed = preparing.claim(candidate.tenantId(), candidate.id(), Instant.now()); if (claimed == null) continue;
                    LOG.info("Expense recovery execution started, errorCode={}, source={}, operationId={}", "NONE", "expense-partial-preparation", candidate.id());
                    try { preparing.finish(claimed, reader.read(claimed), Instant.now()); }
                    catch (RuntimeException failed) { preparing.fail(claimed, Instant.now()); log("PARTIAL_PREPARATION_READ_FAILURE", candidate.id(), failed); }
                } catch (RuntimeException failed) { log("PARTIAL_PREPARATION_WORKER_FAILURE", candidate.id(), failed); }
            }
        }
    }
    private void budget() {
        for (var candidate : adjustments.dueBudget(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try (var scope = DiagnosticContext.restored(candidate.traceId(), candidate.tenantId(), "expense-partial-budget", candidate.id().toString())
                    .withBusiness(candidate.businessNo(), candidate.processInstanceId(), null).open()) {
                try {
                    var claimed = finance.claimBudget(candidate.tenantId(), candidate.id(), candidate.operationId(), Instant.now()); if (claimed == null) continue;
                    LOG.info("Expense recovery execution started, errorCode={}, source={}, operationId={}", "NONE", "expense-partial-budget", candidate.id());
                    try {
                        var input = claimed.input(); var result = claimed.status() == BudgetConsumptionReductionOperation.Status.EXECUTING
                                ? budgets.execute(input.targetDigest(), input.command()) : budgets.query(input.targetDigest(), input.command());
                        finance.finishBudget(claimed, result, Instant.now());
                    } catch (RuntimeException failed) { finance.failBudget(claimed, Instant.now()); log("PARTIAL_BUDGET_DISPATCH_FAILURE", candidate.id(), failed); }
                } catch (RuntimeException failed) { log("PARTIAL_BUDGET_WORKER_FAILURE", candidate.id(), failed); }
            }
        }
    }
    private void accrual() {
        for (var candidate : adjustments.dueAccrual(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try (var scope = DiagnosticContext.restored(candidate.traceId(), candidate.tenantId(), "expense-partial-accrual", candidate.id().toString())
                    .withBusiness(candidate.businessNo(), candidate.processInstanceId(), null).open()) {
                try {
                    var claimed = finance.claimAccrual(candidate.tenantId(), candidate.id(), candidate.operationId(), Instant.now()); if (claimed == null) continue;
                    LOG.info("Expense recovery execution started, errorCode={}, source={}, operationId={}", "NONE", "expense-partial-accrual", candidate.id());
                    try {
                        var input = claimed.input(); var result = claimed.status() == ExpenseAccrualReductionOperation.Status.POSTING
                                ? accruals.post(input.targetDigest(), input.command()) : accruals.query(input.targetDigest(), input.command());
                        finance.finishAccrual(claimed, result, Instant.now());
                    } catch (RuntimeException failed) { finance.failAccrual(claimed, Instant.now()); log("PARTIAL_ACCRUAL_DISPATCH_FAILURE", candidate.id(), failed); }
                } catch (RuntimeException failed) { log("PARTIAL_ACCRUAL_WORKER_FAILURE", candidate.id(), failed); }
            }
        }
    }
    private void completeResources() {
        for (var candidate : adjustments.ready()) {
            if (Thread.currentThread().isInterrupted()) return;
            try (var scope = DiagnosticContext.restored(candidate.traceId(), candidate.tenantId(), "expense-partial-adjustment", candidate.id().toString())
                    .withBusiness(candidate.businessNo(), candidate.processInstanceId(), null).open()) {
                try {
                    LOG.info("Expense recovery execution started, errorCode={}, source={}, operationId={}", "NONE", "expense-partial-adjustment", candidate.id());
                    resources.apply(candidate);
                }
                catch (DomainException changed) {
                    try { if (!"CONCURRENCY_CONFLICT".equals(changed.code())) resources.block(candidate, changed.code()); }
                    catch (RuntimeException failed) { log("PARTIAL_RESOURCE_BLOCK_FAILURE", candidate.id(), failed); }
                } catch (RuntimeException failed) { log("PARTIAL_RESOURCE_FAILURE", candidate.id(), failed); }
            }
        }
    }
    private static void log(String code, UUID id, RuntimeException failure) {
        LOG.error("Partial expense adjustment worker failed, errorCode={}, adjustmentId={}", failure instanceof DomainException problem ? problem.code() : code, id);
    }
}
