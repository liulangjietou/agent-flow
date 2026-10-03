package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.BudgetCommand;
import io.agentflow.finance.BudgetOperation;
import io.agentflow.finance.BudgetOperationCompleted;
import io.agentflow.finance.BudgetOperationService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 本地资源核销、预算消费登记及结算状态共用短事务，任何一笔失败全部回滚。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseSettlementService {
    private static final String SYSTEM_ACTOR = "expense-settlement";
    private final ExpenseReportRepository reports;
    private final JdbcExpenseSettlementRepository settlements;
    private final ExpenseSettlementSources sources;
    private final ExpensePrecheckResources resources;
    private final ExpenseResourceChanges changes;
    private final JdbcInvoiceVerificationRepository verifications;
    private final BudgetOperationService budgets;
    private final ExpenseSettlementChanges settlementChanges;

    /** 实际资源仍由各自领域对象转换，跨聚合事务由本服务拥有。 */
    public ExpenseSettlementService(ExpenseReportRepository reports, JdbcExpenseSettlementRepository settlements, ExpenseSettlementSources sources,
            ExpensePrecheckResources resources, ExpenseResourceChanges changes, JdbcInvoiceVerificationRepository verifications, BudgetOperationService budgets, ExpenseSettlementChanges settlementChanges) {
        this.reports = reports; this.settlements = settlements; this.sources = sources; this.resources = resources;
        this.changes = changes; this.verifications = verifications; this.budgets = budgets; this.settlementChanges = settlementChanges;
    }

    /** 锁后验证完整来源，已消费资源在预算重试时绝不重复扣减。 */
    @Transactional
    public void consume(JdbcExpenseSettlementRepository.Candidate candidate) {
        var current = locked(candidate.tenantId(), candidate.reportId());
        if (current.version() != candidate.version() || current.status() != ExpenseSettlement.Status.QUEUED) return;
        var report = reports.find(candidate.tenantId(), candidate.reportId()).orElseThrow(ExpenseSettlementService::conflict);
        sources.requireCurrent(current, report); Instant now;
        if (!current.resourcesConsumed()) {
            resources.lockReferences(report.tenantId(), ExpensePrecheckResources.versions(resources.loadReserved(report)));
            now = now();
            var plan = new ExpenseSettlementResources().plan(report, resources.loadReserved(report), now);
            var ids = plan.invoices().stream().map(change -> change.after().id()).toList();
            if (verifications.settlementReceipts(report.tenantId(), ids).size() != ids.size()) {
                throw new DomainException("INVOICE_VERIFICATION_REQUIRED", "Every consumed invoice requires the latest successful verification");
            }
            changes.persist(plan, SYSTEM_ACTOR);
        } else now = now();
        var operation = budgets.finalizeOccupation(report.tenantId(), report.id(), BudgetCommand.Action.CONSUME, now);
        settlementChanges.persist(current, current.consumed(operation.input().command().id(), now));
    }

    /** 消费事务已经回滚后记录阻塞；旧执行者不能覆盖重试或新的回执。 */
    @Transactional
    public void block(JdbcExpenseSettlementRepository.Candidate candidate, String issue) {
        var current = locked(candidate.tenantId(), candidate.reportId());
        if (current.version() == candidate.version() && current.status() == ExpenseSettlement.Status.QUEUED) settlementChanges.persist(current, current.block(issue, now()));
    }

    /** 入口已核实财务范围、期望版本和人工原因，重试仅重新排队未完成部分。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public ExpenseSettlement retry(String tenant, UUID reportId, long expectedVersion) {
        var current = locked(tenant, reportId); if (current.version() != expectedVersion) throw conflict();
        var next = current.retry(now()); settlementChanges.persist(current, next); return next;
    }

    /** 预算实际占用与结算完成同事务；迟到的旧命令不能结束后续重试。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void budgetCompleted(BudgetOperationCompleted event) {
        var operation = event.operation(); var command = operation.input().command();
        if (command.action() != BudgetCommand.Action.CONSUME) return;
        reports.lock(command.tenantId(), command.position().reportId());
        var current = settlements.find(command.tenantId(), command.position().reportId()).orElse(null);
        if (current == null || !command.id().equals(current.budgetOperationId()) || current.status() != ExpenseSettlement.Status.BUDGET_PENDING) return;
        boolean applied = operation.status() == BudgetOperation.Status.APPLIED;
        settlementChanges.persist(current, current.budgetResolved(command.id(), applied, applied ? null : "BUDGET_" + operation.observation().rejection().name(), now()));
    }
    private ExpenseSettlement locked(String tenant, UUID id) { reports.lock(tenant, id); return settlements.find(tenant, id).orElseThrow(ExpenseSettlementService::conflict); }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Expense settlement version changed"); }
}
