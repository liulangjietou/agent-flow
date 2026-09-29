package io.agentflow.expense;

import io.agentflow.approval.history.AuditHistoryPort;
import io.agentflow.approval.history.HistoryEvent;
import io.agentflow.approval.history.ProcessHistoryPort;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.common.DomainException;
import io.agentflow.finance.*;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 从真实轮次和已确认会计/资金事实组装清单；读取原件字节另由事务外执行器承担。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseArchiveSources {
    private final ApplicationRepository applications;
    private final SubmissionRoundRepository rounds;
    private final ExpenseReportRepository reports;
    private final JdbcExpenseSettlementRepository settlements;
    private final JdbcExpenseSubmissionControlRepository controls;
    private final JdbcExpensePrecheckRepository prechecks;
    private final JdbcInvoiceOriginalRepository originals;
    private final JdbcBudgetOperationRepository budgets;
    private final JdbcBudgetOccupationRepository occupations;
    private final JdbcVoucherOperationRepository vouchers;
    private final JdbcPaymentOperationRepository payments;
    private final ExpenseSettlementSources funding;
    private final ProcessHistoryPort processHistory;
    private final AuditHistoryPort auditHistory;

    /** 本服务只负责跨聚合证据关联，不作网络调用、领域状态转换或权限推断。 */
    public ExpenseArchiveSources(ApplicationRepository applications, SubmissionRoundRepository rounds, ExpenseReportRepository reports,
            JdbcExpenseSettlementRepository settlements, JdbcExpenseSubmissionControlRepository controls, JdbcExpensePrecheckRepository prechecks,
            JdbcInvoiceOriginalRepository originals, JdbcBudgetOperationRepository budgets, JdbcBudgetOccupationRepository occupations,
            JdbcVoucherOperationRepository vouchers, JdbcPaymentOperationRepository payments, ExpenseSettlementSources funding,
            ProcessHistoryPort processHistory, AuditHistoryPort auditHistory) {
        this.applications = applications; this.rounds = rounds; this.reports = reports; this.settlements = settlements; this.controls = controls;
        this.prechecks = prechecks; this.originals = originals; this.budgets = budgets; this.occupations = occupations; this.vouchers = vouchers;
        this.payments = payments; this.funding = funding; this.processHistory = processHistory; this.auditHistory = auditHistory;
    }

    /** 在原申请/报销锁内读取完整证据；调用方会在文件校验后再次核对相同清单。 */
    public ExpenseArchive.Manifest capture(String tenant, UUID reportId) {
        var settlement = settlements.find(tenant, reportId).orElseThrow(() -> issue("ARCHIVE_SETTLEMENT_REQUIRED"));
        var proof = requireCurrent(settlement); var source = settlement.input().source();
        var report = reports.find(tenant, reportId).orElseThrow(); var app = applications.findById(tenant, source.applicationId()).orElseThrow();
        var round = rounds.findByRound(tenant, app.id(), source.roundNo()).orElseThrow(() -> issue("ARCHIVE_APPROVAL_REQUIRED"));
        var control = controls.find(tenant, reportId, source.roundNo()).orElseThrow(() -> issue("ARCHIVE_SOURCE_CHANGED"));
        var precheck = prechecks.find(tenant, control.input().precheckId()).orElseThrow(() -> issue("ARCHIVE_SOURCE_CHANGED"));
        if (precheck.status() != ExpensePrecheckJob.Status.READY || !precheck.input().reportId().equals(reportId)
                || !precheck.input().applicationId().equals(app.id()) || precheck.input().roundNo() != source.roundNo()
                || precheck.input().financialVersion() + 1 != control.input().submittedFinancialVersion()) throw issue("ARCHIVE_SOURCE_CHANGED");
        var ids = report.currentRound().originalLines().stream().flatMap(line -> line.original().invoiceIds().stream()).sorted().toList();
        var files = originals.findAll(tenant, ids); var frozenReceipts = precheck.result().evidence().invoices();
        var originalsList = new ArrayList<ExpenseArchive.Original>();
        for (var id : ids) {
            var file = files.get(id); var receipt = frozenReceipts.stream().filter(value -> value.invoiceId().equals(id)).findFirst().orElseThrow(() -> issue("ARCHIVE_ORIGINAL_REQUIRED"));
            if (file == null || file.status() != InvoiceOriginal.Status.READY) throw issue("ARCHIVE_ORIGINAL_REQUIRED");
            originalsList.add(new ExpenseArchive.Original(file, receipt));
        }
        var process = processHistory.read(tenant, app.id(), List.of(round));
        var history = new ArrayList<>(process.events()); history.addAll(auditHistory.read(tenant, app.id(), process, List.of(round)));
        var selected = history.stream().filter(value -> value.roundNo() != null && value.roundNo() == source.roundNo())
                .sorted(Comparator.comparing(HistoryEvent::occurredAt).thenComparing(HistoryEvent::id)).toList();
        return new ExpenseArchive.Manifest(app.businessNo(), report.currentRound(), round, selected, control, settlement,
                proof.budget(), proof.vouchers(), originalsList);
    }

    /** 已归档后也核对当前事实；发现争议只返回提示，不修改原档案或旧资金账。 */
    public Proof requireCurrent(ExpenseSettlement recorded) {
        var source = recorded.input().source(); String tenant = source.tenantId(); UUID reportId = source.businessId();
        var current = settlements.find(tenant, reportId).orElseThrow(() -> issue("ARCHIVE_SETTLEMENT_REQUIRED"));
        if (current.status() != ExpenseSettlement.Status.SETTLED || !current.input().equals(recorded.input())) throw issue("ARCHIVE_SETTLEMENT_REQUIRED");
        var report = reports.find(tenant, reportId).orElseThrow(); current.requireReport(report);
        var app = applications.findById(tenant, source.applicationId()).orElseThrow();
        if (app.status() != ApplicationStatus.APPROVED || app.version() != source.applicationVersion() || app.roundNo() != source.roundNo()) throw issue("ARCHIVE_APPROVAL_REQUIRED");
        var control = controls.find(tenant, reportId, source.roundNo()).orElseThrow(() -> issue("ARCHIVE_SOURCE_CHANGED"));
        if (!control.paperReady()) throw issue("ARCHIVE_PAPER_REQUIRED");
        var budget = budgets.find(tenant, current.budgetOperationId()).orElseThrow(() -> issue("ARCHIVE_BUDGET_REQUIRED"));
        var ledger = occupations.find(tenant, reportId).orElseThrow(() -> issue("ARCHIVE_BUDGET_REQUIRED")); var budgetCommand = budget.input().command();
        if (budget.status() != BudgetOperation.Status.APPLIED || budgetCommand.action() != BudgetCommand.Action.CONSUME
                || !budgetCommand.position().reportId().equals(reportId) || budgetCommand.position().roundNo() != source.roundNo()
                || budgetCommand.position().financialVersion() != source.businessVersion()
                || ledger.status() != BudgetOccupation.Status.CONSUMED || !ledger.confirmed().position().equals(budgetCommand.position())
                || ledger.confirmed().revision() != budget.observation().ledgerRevision()
                || !ledger.confirmed().reference().equals(budget.observation().reference())) throw issue("ARCHIVE_BUDGET_REQUIRED");
        var proofs = new ArrayList<ExpenseArchive.Voucher>(); var input = current.input();
        if (input.voucherOperationId() != null) {
            var accrual = vouchers.find(tenant, input.voucherOperationId()).orElseThrow(() -> issue("ARCHIVE_ACCRUAL_REQUIRED"));
            if (!accrual.usablePosted() || !accrual.input().command().digest().equals(input.voucherDigest())) throw issue("ARCHIVE_ACCRUAL_REQUIRED");
            proofs.add(new ExpenseArchive.Voucher(VoucherCommand.Kind.EXPENSE_ACCRUAL, accrual.version(), accrual.observation()));
        }
        if (input.payment() != null) {
            var payment = payments.find(tenant, input.payment().operationId()).orElseThrow(() -> issue("ARCHIVE_PAYMENT_REQUIRED"));
            if (!payment.settleable() || !funding.paid(payment, report).equals(input)) throw issue("ARCHIVE_PAYMENT_REQUIRED");
            var voucher = vouchers.forRound(tenant, source.applicationId(), source.roundNo(), VoucherCommand.Kind.PAYMENT).orElseThrow(() -> issue("ARCHIVE_PAYMENT_VOUCHER_REQUIRED"));
            var command = voucher.input().command();
            if (!voucher.usablePosted() || !command.payment().command().equals(payment.input().command())
                    || !command.payment().receipt().receiptReference().equals(input.payment().receiptReference())
                    || !command.payment().receipt().paymentReference().equals(input.payment().paymentReference())
                    || !command.payment().receipt().completedAt().equals(input.payment().completedAt())) throw issue("ARCHIVE_PAYMENT_VOUCHER_REQUIRED");
            proofs.add(new ExpenseArchive.Voucher(VoucherCommand.Kind.PAYMENT, voucher.version(), voucher.observation()));
        }
        return new Proof(budget.observation(), List.copyOf(proofs));
    }
    private static DomainException issue(String code) { return new DomainException(code, "Original expense evidence is not ready for archive"); }
    /**
     * 当前会计事实投影，用于初次封存和归档后的独立状态提示。
     * @author owlzhangfq@gmail.com
     */
    public record Proof(BudgetObservation budget, List<ExpenseArchive.Voucher> vouchers) { }
}
