package io.agentflow.agent;

import io.agentflow.approval.ApprovalApplicationFacade;
import io.agentflow.approval.ApplicationFieldViews;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.calendar.BusinessCalendarRepository;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.ExpenseContent;
import io.agentflow.expense.ExpenseFormContract;
import io.agentflow.expense.ExpenseLine;
import io.agentflow.expense.ExpenseReport;
import io.agentflow.expense.ExpenseReportRepository;
import io.agentflow.expense.ExpenseRiskEvidence;
import io.agentflow.expense.ExpenseRound;
import io.agentflow.expense.Invoice;
import io.agentflow.expense.InvoiceRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

/**
 * 风险来源的读取边界：当前决定权、逐单原轮次明细权限与可信票据事实都在进入 Agent 上下文前核验。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseRiskAccess {
    private final ExpenseReportRepository reports;
    private final ApprovalApplicationFacade applications;
    private final ApplicationFieldViews fields;
    private final SubmissionRoundRepository rounds;
    private final AssistInputService decisions;
    private final InvoiceRepository invoices;
    private final BusinessCalendarRepository calendars;
    private final ExpenseRiskSources sources;
    private final JsonUtil json;

    /** 沿用申请、敏感字段、任务和工作日历边界，不用 ADMIN 或 FINANCE 建立跨单读取特权。 */
    public ExpenseRiskAccess(ExpenseReportRepository reports, ApprovalApplicationFacade applications, ApplicationFieldViews fields,
            SubmissionRoundRepository rounds, AssistInputService decisions, InvoiceRepository invoices,
            BusinessCalendarRepository calendars, ExpenseRiskSources sources, JsonUtil json) {
        this.reports = reports; this.applications = applications; this.fields = fields; this.rounds = rounds; this.decisions = decisions;
        this.invoices = invoices; this.calendars = calendars; this.sources = sources; this.json = json;
    }

    /** 只读取操作者列出的行；跨单比较限同申请人和法人，避免去标识后误把无关人员费用聚合。 */
    @Transactional(readOnly = true)
    public ExpenseRiskSources.Catalog available(Actor actor, UUID primaryReportId, String taskId, Selection selection, Instant at) {
        if (!selection.documents().get(0).reportId().equals(primaryReportId)) throw invalid();
        var primary = readable(actor, selection.documents().get(0));
        decisions.requireDecision(primary.application(), taskId, actor);
        if (primary.application().roundNo() != primary.round().roundNo()) throw changed();
        var selected = new ArrayList<Readable>(); selected.add(primary);
        for (int index = 1; index < selection.documents().size(); index++) {
            var comparison = readable(actor, selection.documents().get(index));
            if (!comparison.report().employeeId().equals(primary.report().employeeId())
                    || !comparison.round().content().legalEntityId().equals(primary.round().content().legalEntityId())) throw invalid();
            selected.add(comparison);
        }
        // 所有单据先授权，再批量读取所选行引用的票据，权限失败不能带出其他票面事实。
        var invoiceIds = selected.stream().flatMap(value -> value.lines().stream()).flatMap(line -> line.invoiceIds().stream()).distinct().toList();
        var invoiceMap = invoices.findAll(actor.tenantId(), invoiceIds);
        var lines = new ArrayList<ExpenseRiskEvidence.Line>(); var verified = new ArrayList<ExpenseRiskEvidence.Invoice>();
        var documents = new ArrayList<ExpenseRiskInput.Document>();
        for (int index = 0; index < selected.size(); index++) {
            int ordinal = index + 1; var value = selected.get(index); var bindings = new ArrayList<InvoiceBinding>();
            for (var line : value.lines()) {
                var lineId = new ExpenseRiskEvidence.LineId(ordinal, line.lineNo());
                lines.add(new ExpenseRiskEvidence.Line(lineId, line.categoryCode(), line.incurredOn(), line.claimedGross(), line.invoiceIds().size()));
                for (int position = 0; position < line.invoiceIds().size(); position++) {
                    UUID id = line.invoiceIds().get(position); var invoice = invoiceMap.get(id);
                    bindings.add(new InvoiceBinding(id, invoice == null ? null : invoice.state()));
                    var facts = verifiedFacts(invoice, value, at);
                    if (facts != null) verified.add(new ExpenseRiskEvidence.Invoice(new ExpenseRiskEvidence.InvoiceId(lineId, position + 1), facts.key()));
                }
            }
            documents.add(new ExpenseRiskInput.Document(ordinal, value.report().id(), value.application().id(), value.application().version(),
                    value.round().roundNo(), value.report().version(), AssistConfiguration.digest(json.write(new Snapshot(value.round(), bindings))),
                    value.lines().stream().map(ExpenseLine::lineNo).toList()));
        }
        var calendar = selection.calendarId() == null ? null : calendars.find(actor.tenantId(), selection.calendarId()).orElseThrow(ExpenseRiskAccess::notFound);
        var calendarReference = calendar == null ? null : new ExpenseRiskInput.CalendarReference(calendar.id(), calendar.revision(), sources.calendarDigest(calendar.rules()));
        return sources.available(documents, calendarReference, new ExpenseRiskEvidence.Input(lines, verified, calendar == null ? null : calendar.rules()));
    }

    /** 历史正文仍逐单检查当下权限；历史读取不要求旧版本仍可采纳，也不从当前轮次借用字段资格。 */
    @Transactional(readOnly = true)
    public void requireReadable(Actor actor, ExpenseRiskInput input) {
        for (var document : input.documents()) {
            var readable = readable(actor, new SelectedDocument(document.reportId(), document.roundNo(), document.lineNos()));
            if (!readable.application().id().equals(document.applicationId())) throw notFound();
        }
    }

    private Readable readable(Actor actor, SelectedDocument selection) {
        var report = reports.find(actor.tenantId(), selection.reportId()).orElseThrow(ExpenseRiskAccess::notFound);
        var application = applications.getForActor(actor, report.applicationId());
        var round = report.rounds().stream().filter(value -> value.roundNo() == selection.roundNo()).findFirst().orElseThrow(ExpenseRiskAccess::notFound);
        var submitted = rounds.findByRound(actor.tenantId(), application.id(), round.roundNo()).orElseThrow(ExpenseRiskAccess::notFound);
        var projection = fields.attachmentViewForActor(actor, application, round.roundNo());
        if (!ExpenseFormContract.detailsReadable(submitted.formSchema(), projection.schema())) {
            throw new DomainException("FORBIDDEN", "All selected expense details must remain fully readable");
        }
        var lines = round.content().lines().stream().filter(line -> selection.lineNos().contains(line.lineNo()))
                .sorted(java.util.Comparator.comparingInt(ExpenseLine::lineNo)).toList();
        if (lines.size() != selection.lineNos().size()) throw invalid();
        return new Readable(report, application, round, lines);
    }

    private static Invoice.VerifiedFacts verifiedFacts(Invoice invoice, Readable source, Instant at) {
        if (invoice == null || !invoice.ownerId().equals(source.report().employeeId())) return null;
        try {
            var facts = invoice.requireVerified(at);
            return facts.legalEntityId().equals(source.round().content().legalEntityId()) ? facts : null;
        } catch (DomainException unavailable) {
            if (!unavailable.code().equals("INVOICE_VERIFICATION_REQUIRED")) throw unavailable;
            return null;
        }
    }

    private static DomainException invalid() { return new DomainException("INVALID_AGENT_INPUT", "Select bounded existing expense lines for the same applicant and legal entity"); }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Expense risk source not found"); }
    private static DomainException changed() { return new DomainException("AGENT_INPUT_CHANGED", "The primary expense submission round changed"); }

    /**
     * 操作者明确指定对照范围，不执行全公司、管理员或财务角色的自动单据扫描。
     * @author owlzhangfq@gmail.com
     */
    public record Selection(List<SelectedDocument> documents, UUID calendarId) {
        /** 限制总单据、总行数及重复引用；第一份始终是当前审批单据。 */
        public Selection {
            if (CollectionUtils.isEmpty(documents) || documents.size() > ExpenseRiskEvidence.MAX_DOCUMENTS
                    || documents.stream().anyMatch(Objects::isNull)
                    || documents.stream().map(SelectedDocument::reportId).distinct().count() != documents.size()
                    || documents.stream().mapToInt(document -> document.lineNos().size()).sum() > ExpenseRiskEvidence.MAX_SELECTED_LINES) throw invalid();
            documents = List.copyOf(documents);
        }
    }

    /**
     * 客户端只能指定现有来源位置，不能提交费用正文、票面结论或计算结果。
     * @author owlzhangfq@gmail.com
     */
    public record SelectedDocument(UUID reportId, int roundNo, List<Integer> lineNos) {
        /** 在来源入口一次性校验行号，不将空选择解释成全量读取。 */
        public SelectedDocument {
            if (reportId == null || roundNo < 1 || CollectionUtils.isEmpty(lineNos) || lineNos.size() > ExpenseContent.MAX_LINES
                    || lineNos.stream().anyMatch(line -> line == null || line < 1 || line > ExpenseContent.MAX_LINES)
                    || new HashSet<>(lineNos).size() != lineNos.size()) throw invalid();
            lineNos = lineNos.stream().sorted().toList();
        }
    }

    /**
     * 仅在读取编排期间使用，不作为模型请求或公开接口返回。
     * @author owlzhangfq@gmail.com
     */
    private record Readable(ExpenseReport report, Application application, ExpenseRound round, List<ExpenseLine> lines) { }

    /**
     * 完整原轮次和票据状态共同绑定本地输入，付款账户等原文仅参与摘要计算。
     * @author owlzhangfq@gmail.com
     */
    private record Snapshot(ExpenseRound round, List<InvoiceBinding> invoices) { }

    /**
     * 缺失、过期或失败票据也保留版本绑定；重新查验后旧输入需要重新确认。
     * @author owlzhangfq@gmail.com
     */
    private record InvoiceBinding(UUID id, Invoice.State state) { }
}
