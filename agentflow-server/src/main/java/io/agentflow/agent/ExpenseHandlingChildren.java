package io.agentflow.agent;

import io.agentflow.agent.mapper.ExpenseAgentMapper;
import io.agentflow.common.CurrentActor;
import io.agentflow.expense.ExpenseReportRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原模型子任务在排队事务中绑定本次办理，外发确认仍由各原入口完成。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseHandlingChildren {
    private final CurrentActor actors;
    private final ExpenseReportRepository reports;
    private final ExpenseHandlingService handling;
    private final ExpenseAgentService agent;
    private final ExpenseAgentMapper mapper;
    private final InvoiceExtractionService extraction;
    private final ExpenseDraftAssistService drafts;
    private final ExpenseHandlingJournal journal;
    /** 编排位于应用层，原聚合仍独立验证来源、租约和人工确认。 */
    public ExpenseHandlingChildren(CurrentActor actors, ExpenseReportRepository reports, ExpenseHandlingService handling, ExpenseAgentService agent,
            ExpenseAgentMapper mapper, InvoiceExtractionService extraction, ExpenseDraftAssistService drafts, ExpenseHandlingJournal journal) {
        this.actors = actors; this.reports = reports; this.handling = handling; this.agent = agent; this.mapper = mapper;
        this.extraction = extraction; this.drafts = drafts; this.journal = journal;
    }
    /** 先锁单据后排队，绑定失败使原任务一同回滚，不遗留无人接续的任务。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public InvoiceExtractionService.Receipt invoice(InvoiceExtractionService.Prepared prepared, InvoiceExtractionController.GenerateRequest input) {
        var binding = input.handling();
        if (binding == null) return extraction.queue(prepared, input.method(), input.targetDigest());
        var task = task(binding.reportId(), binding.taskId());
        var receipt = extraction.queue(prepared, input.method(), input.targetDigest());
        agent.bind(binding.reportId(), binding.taskId(), ExpenseAgentRun.Action.EXTRACT_INVOICE, prepared.input().invoiceId(), receipt.id());
        link(task, receipt.id(), ExpenseHandlingTask.Tool.INVOICE_EXTRACTION);
        journal.recordFor(actors.actor().tenantId(), binding.reportId(), binding.taskId(), ExpenseHandlingTask.Tool.INVOICE_EXTRACTION,
                receipt.id(), receipt.version(), receipt.status().name(), task.state().applicationVersion(), task.state().financialVersion(), prepared.input(), java.time.Instant.now());
        return receipt;
    }
    /** 已确认票据后继续原草稿预览和排队，原目录确认摘要仍然必需。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public ExpenseDraftAssistService.Receipt draft(UUID reportId, ExpenseDraftAssistPreparation.Prepared prepared, ExpenseDraftAssistController.GenerateRequest input) {
        if (input.handlingTaskId() == null) return drafts.queue(reportId, prepared, input.targetDigest(), input.consentDigest());
        var task = task(reportId, input.handlingTaskId());
        var receipt = drafts.queue(reportId, prepared, input.targetDigest(), input.consentDigest());
        agent.bind(reportId, input.handlingTaskId(), ExpenseAgentRun.Action.DRAFT, null, receipt.id());
        link(task, receipt.id(), ExpenseHandlingTask.Tool.DRAFT); return receipt;
    }
    private ExpenseHandlingTask task(UUID reportId, UUID taskId) {
        handling.authorizeTask(reportId, taskId); reports.lock(actors.actor().tenantId(), reportId); return handling.requireReadable(reportId, taskId, null);
    }
    private void link(ExpenseHandlingTask task, UUID child, ExpenseHandlingTask.Tool kind) {
        var c = task.context(); mapper.child(c.tenantId(), c.id().toString(), child.toString(), kind.name(), c.reportId().toString(), task.state().applicationVersion(), task.state().financialVersion());
    }
    /**
     * 显式办理归属不能由票据号或当前页面猜测。
     * @author owlzhangfq@gmail.com
     */
    public record Binding(@jakarta.validation.constraints.NotNull UUID reportId, @jakarta.validation.constraints.NotNull UUID taskId) {
        /** 不接受人员或租户覆盖。 */
        @com.fasterxml.jackson.annotation.JsonAnySetter public void unknown(String name, Object value) { throw new IllegalArgumentException("Unknown handling binding field"); }
    }
}
