package io.agentflow.expense;

import org.springframework.context.ApplicationEventPublisher;

import io.agentflow.common.DomainException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 两侧实际成功后在一次短事务完成所有资源、回款归属和接受证明，失败不留下部分资源效果。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpensePartialAdjustmentExecution {
    private final ApplicationEventPublisher events;
    private static final String SYSTEM_ACTOR = "expense-partial-adjustment";
    private final ExpenseReportRepository reports;
    private final JdbcExpensePartialAdjustmentRepository adjustments;
    private final ExpensePrecheckResources resources;
    private final ExpenseResourceChanges changes;
    private final ExpenseResourceReduction rules = new ExpenseResourceReduction();

    /** 跨聚合事务归应用服务，实际净额及资源变化由既有领域模型计算。 */
    public ExpensePartialAdjustmentExecution(ExpenseReportRepository reports, JdbcExpensePartialAdjustmentRepository adjustments,
            ExpensePrecheckResources resources, ExpenseResourceChanges changes, ApplicationEventPublisher events) {
        this.events = events;
        this.reports = reports; this.adjustments = adjustments; this.resources = resources; this.changes = changes;
    }

    /** 同报销串行，重复或过期候选没有第二次效果；原单、资金和凭证均不改写。 */
    @Transactional
    public void apply(JdbcExpensePartialAdjustmentRepository.Candidate candidate) {
        reports.lock(candidate.tenantId(), candidate.reportId());
        var current = adjustments.find(candidate.tenantId(), candidate.id()).orElseThrow(ExpensePartialAdjustmentExecution::conflict);
        if (!current.input().basis().reportId().equals(candidate.reportId())) throw conflict();
        if (current.version() != candidate.version() || current.status() != ExpensePartialAdjustment.Status.READY) return;
        adjustments.requireCompletionSource(current);
        var report = reports.find(candidate.tenantId(), candidate.reportId()).orElseThrow(ExpensePartialAdjustmentExecution::conflict);
        resources.lockReferences(candidate.tenantId(), ExpensePrecheckResources.versions(resources.loadReserved(report)));
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var plan = rules.plan(current.input().basis().funding().financial().change(), resources.loadReserved(report), current.id(), now);
        changes.persist(plan, SYSTEM_ACTOR); var completed = current.completeResources(now); adjustments.completeResources(completed);
        events.publishEvent(new ExpensePartialAdjustmentChanged.Resources(completed));
    }
    /** 可复现的来源冲突等待明确复核，已成功的两侧结果仍保留；迟到失败不冻结较新完成。 */
    @Transactional
    public void block(JdbcExpensePartialAdjustmentRepository.Candidate candidate, String code) {
        reports.lock(candidate.tenantId(), candidate.reportId());
        var current = adjustments.find(candidate.tenantId(), candidate.id()).orElseThrow(ExpensePartialAdjustmentExecution::conflict);
        if (!current.input().basis().reportId().equals(candidate.reportId())) throw conflict();
        if (current.version() == candidate.version() && current.status() == ExpensePartialAdjustment.Status.READY) {
            var blocked = current.requireReview(code, Instant.now().truncatedTo(ChronoUnit.MICROS)); adjustments.update(blocked);
            events.publishEvent(new ExpensePartialAdjustmentChanged.Resources(blocked));
        }
    }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Partial adjustment source or execution candidate changed"); }
}
