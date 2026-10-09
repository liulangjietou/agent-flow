package io.agentflow.expense;

import io.agentflow.agent.AssistExecutionService;
import io.agentflow.agent.PrecheckExplanationService;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 本人补正的跨聚合编排：复核建议、保存费用、登记新预检和回执共同成功或回滚。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseCorrectionService {
    private final CurrentActor actors;
    private final io.agentflow.agent.ExpenseHandlingJournal journal;
    private final PrecheckExplanationService explanations;
    private final ExpenseDraftService drafts;
    private final ExpenseReportRepository reports;
    private final JdbcExpensePrecheckRepository jobs;
    private final ExpensePrecheckService checks;
    private final ExpenseCorrectionRepository corrections;

    /** 各领域仍执行自己的规则；本服务不解释模型文本或直接改变金额。 */
    public ExpenseCorrectionService(CurrentActor actors, PrecheckExplanationService explanations, ExpenseDraftService drafts,
            ExpenseReportRepository reports, JdbcExpensePrecheckRepository jobs, ExpensePrecheckService checks,
            ExpenseCorrectionRepository corrections, io.agentflow.agent.ExpenseHandlingJournal journal) {
        this.actors = actors; this.journal = journal; this.explanations = explanations; this.drafts = drafts; this.reports = reports;
        this.jobs = jobs; this.checks = checks; this.corrections = corrections;
    }

    /** 沿用原预检的任职、会计日期和财务目的地，员工确认的是本次明确的费用正文。 */
    @Transactional
    public Receipt correct(UUID reportId, UUID runId, long runVersion, long applicationVersion, long financialVersion,
            List<String> selectedIssueIds, String comment, ExpenseAllowancePreparation.Prepared prepared) {
        var actor = actors.actor();
        explanations.authorize(reportId);
        reports.lock(actor.tenantId(), reportId);
        var detail = explanations.get(reportId, runId);
        if (detail.result() == ExpensePrecheckJob.Status.READY) {
            throw new DomainException("AGENT_CORRECTION_NOT_REQUIRED", "A successful precheck has no blocking findings to correct");
        }
        if (detail.applicationVersion() != applicationVersion || detail.financialVersion() != financialVersion) {
            throw new DomainException("AGENT_INPUT_CHANGED", "Expense correction versions do not match the explanation");
        }
        var original = jobs.find(actor.tenantId(), detail.precheckId()).orElseThrow().input();
        explanations.review(reportId, runId, runVersion, AssistExecutionService.ReviewAction.ADOPT, selectedIssueIds, comment);
        var expense = drafts.revise(reportId, applicationVersion, financialVersion, prepared);
        var next = checks.queue(reportId, new ExpensePrecheckService.QueueInput(expense.applicationVersion(), expense.financialVersion(),
                original.initiator().appointmentId(), original.accountingDate(), original.targetDigest()));
        corrections.save(actor.tenantId(), new ExpenseCorrection(runId, reportId, expense.applicationId(),
                expense.applicationVersion(), expense.financialVersion(), next.id(), actor.userId(), Instant.now().truncatedTo(ChronoUnit.MILLIS)));
        journal.record(actor.tenantId(), reportId, io.agentflow.agent.ExpenseHandlingTask.Tool.CORRECTION, runId, expense.financialVersion(), "SAVED",
                expense.applicationVersion(), expense.financialVersion(), expense.content(), Instant.now());
        return new Receipt(runId, next.id(), expense);
    }

    /**
     * 预检仅已排队；财务结论仍由后续权威检查产生。
     * @author owlzhangfq@gmail.com
     */
    public record Receipt(UUID runId, UUID precheckId, ExpenseResponse expense) { }
}
