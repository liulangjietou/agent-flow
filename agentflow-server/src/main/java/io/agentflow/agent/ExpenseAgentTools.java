package io.agentflow.agent;

import io.agentflow.common.DomainException;
import io.agentflow.expense.ExpenseDraftService;
import io.agentflow.expense.ExpensePrecheckService;
import io.agentflow.expense.InvoiceWalletService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 固定工具目录的实际调用和模型投影；外部结果中的身份或动作不会成为执行参数。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseAgentTools {
    private final ExpenseDraftService drafts;
    private final InvoiceWalletService invoices;
    private final ExpensePrecheckService checks;
    private final HandlingReadService reads;
    private final ExpenseHandlingService handling;
    private final InvoiceExtractionService extraction;
    private final ExpenseDraftAssistService draftAssists;
    /** 全部工具复用原用例授权，没有数据库通用查询或任意网络入口。 */
    public ExpenseAgentTools(ExpenseDraftService drafts, InvoiceWalletService invoices, ExpensePrecheckService checks,
            HandlingReadService reads, ExpenseHandlingService handling, InvoiceExtractionService extraction, ExpenseDraftAssistService draftAssists) {
        this.drafts = drafts; this.invoices = invoices; this.checks = checks; this.reads = reads; this.handling = handling;
        this.extraction = extraction; this.draftAssists = draftAssists;
    }
    /** 授权预览展示实际费用和已选择票据，制度只允许查询明确费用行。 */
    public Object preview(UUID reportId, ExpenseAgentRun.Scope scope) {
        var expense = drafts.read(reportId, null);
        if (!scope.policyLineNos().stream().allMatch(number -> expense.content().lines().stream().anyMatch(line -> line.lineNo() == number))) throw changed();
        var selectedInvoices = scope.invoiceIds().stream().map(id -> invoice(invoices.get(id))).toList();
        var selectedChecks = scope.precheckIds().stream().map(id -> {
            var value = checks.get(reportId, id);
            if (value.job().applicationVersion() != expense.applicationVersion() || value.job().financialVersion() != expense.financialVersion()) throw changed();
            return Map.of("job", value.job(), "findings", value.findings());
        }).toList();
        return Map.of("expense", expense.content(), "invoices", selectedInvoices, "prechecks", selectedChecks);
    }
    /** 每次查询保留原模型步骤的键和持久工具回执，成功读取后只向模型投影获准字段。 */
    public Object execute(ExpenseAgentRun run) {
        var c = run.context(); var decision = run.last().decision();
        var current = handling.requireReadable(c.reportId(), c.taskId(), null);
        var result = reads.execute(c.reportId(), c.taskId(), "agent." + run.last().id(),
                new ExpenseHandlingController.Inspect(current.state().version(), ExpenseHandlingService.ReadTool.valueOf(decision.action().name()), decision.referenceId(), decision.lineNo())).result();
        return switch (result.tool()) {
            case EXPENSE -> result.expense().content();
            case INVOICE -> invoice(result.invoice());
            case POLICY -> result.policy().guidance();
            case PRECHECK_RESULT -> Map.of("job", result.precheck().job(), "findings", result.precheck().findings());
        };
    }
    /** 读取明确绑定的原子任务，确认值始终标明为本人确认，不能替代税务查验。 */
    public Child child(ExpenseAgentRun run) {
        if (run.state().childAction() == ExpenseAgentRun.Action.EXTRACT_INVOICE) {
            var value = extraction.get(run.last().decision().referenceId(), run.state().childId());
            return new Child(value.status().name(), value.review() == null ? null : Map.of("kind", "EMPLOYEE_CONFIRMED_INVOICE_FIELDS", "selected", value.review().selected()),
                    value.failure() == null ? null : value.failure().name());
        }
        var value = draftAssists.get(run.context().reportId(), run.state().childId());
        return new Child(value.status().name(), value.review(), value.failure() == null ? null : value.failure().name());
    }
    private static Object invoice(InvoiceWalletService.Item invoice) {
        var value = new LinkedHashMap<String, Object>(); value.put("id", invoice.id()); value.put("version", invoice.version());
        value.put("verification", invoice.verification()); value.put("verifiedFacts", invoice.facts()); return value;
    }
    private static DomainException changed() { return new DomainException("AGENT_INPUT_CHANGED", "Agent scope does not match saved expense facts"); }
    /**
     * 子运行结果保留类型及人工确认状态。
     * @author owlzhangfq@gmail.com
     */
    public record Child(String status, Object confirmed, String failure) { }
}
