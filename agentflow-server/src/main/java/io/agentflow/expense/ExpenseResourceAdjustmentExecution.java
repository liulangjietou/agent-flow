package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.JdbcBudgetConsumptionReversalRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

/**
 * 预算已经实际冲正后，在原报销锁内完成本地资源计划、反向明细和调整状态。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseResourceAdjustmentExecution {
    private final ApplicationEventPublisher events;
    private static final String SYSTEM_ACTOR = "expense-resource-adjustment";
    private final ExpenseReportRepository reports;
    private final JdbcExpenseResourceAdjustmentRepository adjustments;
    private final ExpenseResourceAdjustmentSources sources;
    private final JdbcBudgetConsumptionReversalRepository budgets;
    private final ExpensePrecheckResources resources;
    private final ExpenseResourceChanges changes;
    private final ExpenseResourceReversal rules = new ExpenseResourceReversal();

    /** 跨聚合原子性归应用层，各资源自身的状态转换仍由领域对象负责。 */
    public ExpenseResourceAdjustmentExecution(ExpenseReportRepository reports, JdbcExpenseResourceAdjustmentRepository adjustments,
            ExpenseResourceAdjustmentSources sources, JdbcBudgetConsumptionReversalRepository budgets, ExpensePrecheckResources resources, ExpenseResourceChanges changes, ApplicationEventPublisher events) {
        this.events = events;
        this.reports = reports; this.adjustments = adjustments; this.sources = sources; this.budgets = budgets; this.resources = resources; this.changes = changes;
    }
    /** 任一引用变化或明细冲突都回滚全部资源；重复或迟到候选没有第二次资源效果。 */
    @Transactional
    public void apply(JdbcExpenseResourceAdjustmentRepository.Candidate candidate) {
        var current = locked(candidate); if (!matches(current, candidate)) return;
        sources.requireSupported(current.input().basis());
        current.requireAcceptedBudget(budgets.find(candidate.tenantId(), candidate.id()).orElseThrow(ExpenseResourceAdjustmentExecution::conflict));
        var report = reports.find(candidate.tenantId(), candidate.reportId()).orElseThrow(ExpenseResourceAdjustmentExecution::conflict);
        resources.lockReferences(candidate.tenantId(), ExpensePrecheckResources.versions(resources.loadReserved(report)));
        var now = now(); var plan = rules.plan(report, resources.loadReserved(report), current.id(), now);
        changes.persist(plan, SYSTEM_ACTOR); persist(current.applied(now));
    }
    /** 原资源事务回滚后独立记录原因，已经完成的候选不会被旧失败结果覆盖。 */
    @Transactional
    public void block(JdbcExpenseResourceAdjustmentRepository.Candidate candidate, String issue) {
        var current = locked(candidate); if (matches(current, candidate)) persist(current.requireReview(issue, now()));
    }
    private void persist(ExpenseResourceAdjustment value) {
        adjustments.update(value); events.publishEvent(new ExpenseAdjustmentChanged.Resources(value));
    }
    private ExpenseResourceAdjustment locked(JdbcExpenseResourceAdjustmentRepository.Candidate candidate) {
        reports.lock(candidate.tenantId(), candidate.reportId());
        var value = adjustments.find(candidate.tenantId(), candidate.id()).orElseThrow(ExpenseResourceAdjustmentExecution::conflict);
        if (!value.input().basis().reportId().equals(candidate.reportId())) throw conflict(); return value;
    }
    private static boolean matches(ExpenseResourceAdjustment current, JdbcExpenseResourceAdjustmentRepository.Candidate candidate) {
        return current.version() == candidate.version() && current.status() == ExpenseResourceAdjustment.Status.READY;
    }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Expense resource adjustment changed before execution"); }
}
