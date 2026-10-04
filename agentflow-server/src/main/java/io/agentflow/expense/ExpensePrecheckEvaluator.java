package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.BudgetPrecheckPort;
import io.agentflow.finance.EmployeeAccountPort;
import io.agentflow.finance.ExchangeRatePort;
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.finance.FinanceGatewayConfiguration;
import io.agentflow.finance.FinanceMasterDataPort;
import io.agentflow.finance.FinanceResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static io.agentflow.expense.ExpensePrecheckJob.*;

/**
 * 在事务外获取提交所需的只读事实；只有所有行和整单检查通过才产出候选快照。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpensePrecheckEvaluator {
    private final ExpenseReportRepository reports;
    private final ExpensePrecheckResources resources;
    private final JdbcInvoiceOriginalRepository originals;
    private final JdbcInvoiceVerificationRepository verifications;
    private final InvoiceOriginalFiles files;
    private final FinanceMasterDataPort masterData;
    private final EmployeeAccountPort accounts;
    private final ExchangeRatePort rates;
    private final ExpensePolicyPort policies;
    private final BudgetPrecheckPort budgets;
    private final FinanceGatewayConfiguration configuration;
    private final ExpensePrecheckObservations observations;

    /** 端口调用与本地事务分离，领域计划只在副本上运行。 */
    public ExpensePrecheckEvaluator(ExpenseReportRepository reports, ExpensePrecheckResources resources,
            JdbcInvoiceOriginalRepository originals, JdbcInvoiceVerificationRepository verifications, InvoiceOriginalFiles files,
            FinanceMasterDataPort masterData, EmployeeAccountPort accounts, ExchangeRatePort rates, ExpensePolicyPort policies,
            BudgetPrecheckPort budgets, FinanceGatewayConfiguration configuration, ExpensePrecheckObservations observations) {
        this.reports = reports; this.resources = resources; this.originals = originals; this.verifications = verifications;
        this.files = files; this.masterData = masterData; this.accounts = accounts; this.rates = rates; this.policies = policies;
        this.budgets = budgets; this.configuration = configuration; this.observations = observations;
    }

    /** 不占用或修改原资源；不可用与业务拒绝只保存稳定分类。 */
    public Result evaluate(ExpensePrecheckJob job) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Expense precheck must execute outside a database transaction");
        try {
            ensureLive(job); var input = job.input();
            var report = reports.find(input.tenantId(), input.reportId()).orElseThrow(() -> unavailable(Stage.CONTEXT, "CONTEXT_CHANGED"));
            if (report.version() != input.financialVersion() || !report.employeeId().equals(input.employeeId())
                    || !report.applicationId().equals(input.applicationId())) throw unavailable(Stage.CONTEXT, "CONTEXT_CHANGED");
            var capture = observations.capture(report, Instant.now());
            Result result;
            try { result = assess(job, report, capture); }
            catch (CheckFailure failed) { result = new Result(null, List.of(failed.finding)); }
            return result.observed(capture.observation());
        }
        catch (CheckFailure failed) { return new Result(null, List.of(failed.finding)); }
    }

    private Result assess(ExpensePrecheckJob job, ExpenseReport report, ExpensePrecheckObservations.Capture capture) {
        var input = job.input();
        if (report.content().lines().isEmpty()) throw rejected(Stage.INPUT, null, "EXPENSE_LINES_REQUIRED");
        var selectedPolicy = capture.policy();
        ExpenseSubmissionResources.Resources loaded;
        try { loaded = resources.load(report); }
        catch (DomainException invalid) { throw rejected(Stage.RESOURCES, null, invalid.code()); }
        var ids = report.content().lines().stream().flatMap(line -> line.invoiceIds().stream()).toList();
        var storedOriginals = originals.findAll(input.tenantId(), ids);
        var receipts = verifications.currentReceipts(input.tenantId(), ids);
        var catalog = value(masterData.catalog(input.tenantId(), input.employeeId()), Stage.CATALOG, null);
        capture.limit(catalog.validUntil());
        FinanceCatalog.LegalEntity entity;
        try { entity = catalog.legalEntity(report.content().legalEntityId()); }
        catch (DomainException unavailable) { throw rejected(Stage.CATALOG, null, "LEGAL_ENTITY_UNAVAILABLE"); }
        ZoneId zone = ZoneId.of(entity.timeZone()); LocalDate rateDate = LocalDate.ofInstant(Instant.now(), zone);
        capture.limit(rateDate.plusDays(1).atStartOfDay(zone).toInstant());
        ensureLive(job);
        var account = value(accounts.primaryAccount(input.tenantId(), input.employeeId(), entity.id()), Stage.ACCOUNT, null);
        capture.limit(account.validUntil());
        var assessments = new HashMap<Integer, ExpenseAssessment>();
        var exchangeRates = new HashMap<String, ExpenseExchangeRate>();
        var invoiceEvidence = new ArrayList<ExpensePrecheckEvidence.InvoiceReceipt>();
        var findings = new ArrayList<Finding>();
        for (var line : report.content().lines()) {
            ensureLive(job);
            try {
                requireCatalogLine(catalog, entity, line);
                var invoiceFacts = invoiceFacts(job, line, loaded, storedOriginals, receipts, capture);
                var rate = exchangeRates.get(line.claimedGross().currency());
                if (rate == null) {
                    ensureLive(job);
                    rate = value(rates.rate(input.tenantId(), entity.id(), line.claimedGross().currency(), entity.baseCurrency(), rateDate), Stage.RATE, line.lineNo());
                    exchangeRates.put(line.claimedGross().currency(), rate);
                }
                ensureLive(job);
                ManagedExpensePolicy managed;
                try { managed = selectedPolicy.forLine(line); }
                catch (DomainException invalid) { throw rejected(Stage.POLICY, line.lineNo(), invalid.code()); }
                if (line.allowance() != null && (managed == null || !managed.selection().equals(line.allowance().policy().selection()))) {
                    throw rejected(Stage.POLICY, line.lineNo(), "ALLOWANCE_RECALCULATION_REQUIRED");
                }
                var policy = value(policies.assess(input.tenantId(), new ExpensePolicyPort.Request(input.employeeId(), entity.id(),
                        report.content().type(), line, rate, invoiceFacts, managed)), Stage.POLICY, line.lineNo());
                capture.limit(policy.validUntil());
                try { line.requireCurrentAllowance(managed, entity.id(), policy.policy(), rate, policy.deductibleTax()); }
                catch (DomainException invalid) { throw rejected(Stage.POLICY, line.lineNo(), invalid.code()); }
                if (policy.priorRequestRequired() && line.priorRequest() == null) throw rejected(Stage.POLICY, line.lineNo(), "PRIOR_REQUEST_REQUIRED");
                assessments.put(line.lineNo(), new ExpenseAssessment(rate, policy.policy(), policy.deductibleTax()));
                for (var fact : invoiceFacts) {
                    var invoice = loaded.invoices().get(fact.invoiceId()); var receipt = receipts.get(fact.invoiceId());
                    invoiceEvidence.add(new ExpensePrecheckEvidence.InvoiceReceipt(invoice.id(), invoice.version(), receipt.input().id(), invoice.originalFileId(), fact.facts()));
                }
            } catch (CheckFailure failed) { findings.add(failed.finding); }
        }
        if (!findings.isEmpty()) return new Result(null, findings);
        try { report.freeze(input.financialVersion(), input.roundNo(), entity.baseCurrency(), account.snapshot(), assessments, input.employeeId(), Instant.now()); }
        catch (DomainException invalid) { throw rejected(Stage.INPUT, null, invalid.code()); }
        ExpenseSubmissionResources.Plan resourcePlan;
        try { resourcePlan = new ExpenseSubmissionResources().plan(report, loaded, Instant.now()); resources.requireClaimsAvailable(input.tenantId(), resourcePlan); }
        catch (DomainException invalid) { throw rejected(Stage.RESOURCES, null, invalid.code()); }
        ensureLive(job);
        var budget = value(budgets.precheck(input.tenantId(), BudgetPrecheckPort.Request.from(report, input.accountingDate())), Stage.BUDGET, null);
        capture.limit(budget.validUntil());
        ensureLive(job);
        if (!capture.validUntil().isAfter(Instant.now())) throw unavailable(Stage.CONTEXT, "FACTS_EXPIRED");
        return new Result(new ExpensePrecheckEvidence(catalog.sourceVersion(), entity, rateDate, budget, report.currentRound(),
                ExpensePrecheckResources.versions(loaded), invoiceEvidence, capture.validUntil(), selectedPolicy.selection(), resourcePlan.priorControls()), List.of());
    }

    private List<ExpensePolicyPort.InvoiceEvidence> invoiceFacts(ExpensePrecheckJob job, ExpenseLine line,
            ExpenseSubmissionResources.Resources loaded, Map<UUID, InvoiceOriginal> storedOriginals, Map<UUID, InvoiceVerificationJob> receipts,
            ExpensePrecheckObservations.Capture capture) {
        var result = new ArrayList<ExpensePolicyPort.InvoiceEvidence>();
        for (UUID id : line.invoiceIds()) {
            ensureLive(job);
            var invoice = Invoice.restore(loaded.invoices().get(id)); var original = storedOriginals.get(id); var receipt = receipts.get(id);
            if (receipt == null || receipt.resultingInvoiceVersion() != invoice.version()
                    || !receipt.input().targetDigest().equals(job.input().targetDigest())) throw rejected(Stage.INVOICE, line.lineNo(), "INVOICE_VERIFICATION_REQUIRED");
            if (original == null || original.status() != InvoiceOriginal.Status.READY || !original.id().equals(invoice.originalFileId())
                    || !original.sha256().equals(invoice.originalDigest()) || !original.ownerId().equals(job.input().employeeId())) {
                throw rejected(Stage.INVOICE, line.lineNo(), "INVOICE_ORIGINAL_NOT_READY");
            }
            Invoice.VerifiedFacts facts;
            try { facts = invoice.requireVerified(Instant.now()); }
            catch (DomainException invalid) { throw rejected(Stage.INVOICE, line.lineNo(), invalid.code()); }
            capture.limit(facts.validUntil());
            if (!facts.legalEntityId().equals(job.input().initiator().legalEntityId())) throw rejected(Stage.INVOICE, line.lineNo(), "INVOICE_TITLE_MISMATCH");
            try { files.read(original); }
            catch (DomainException unavailable) { throw new CheckFailure(new Finding(Stage.INVOICE, line.lineNo(), Nature.UNAVAILABLE, "INVOICE_ORIGINAL_UNAVAILABLE")); }
            result.add(new ExpensePolicyPort.InvoiceEvidence(id, facts));
        }
        return List.copyOf(result);
    }

    private static void requireCatalogLine(FinanceCatalog catalog, FinanceCatalog.LegalEntity entity, ExpenseLine line) {
        if (catalog.categories().stream().noneMatch(value -> value.code().equals(line.categoryCode()) && value.units().contains(line.unit()))) {
            throw rejected(Stage.CATALOG, line.lineNo(), "EXPENSE_CATEGORY_UNAVAILABLE");
        }
        if (catalog.cities().stream().noneMatch(value -> value.code().equals(line.cityCode()))) throw rejected(Stage.CATALOG, line.lineNo(), "EXPENSE_CITY_UNAVAILABLE");
        for (var allocation : line.allocations()) {
            if (catalog.costCenters().stream().noneMatch(value -> value.legalEntityId().equals(entity.id()) && value.code().equals(allocation.costCenter()))
                    || allocation.projectCode() != null && catalog.projects().stream().noneMatch(value -> value.legalEntityId().equals(entity.id()) && value.code().equals(allocation.projectCode()))) {
                throw rejected(Stage.CATALOG, line.lineNo(), "COST_OBJECT_UNAVAILABLE");
            }
        }
    }

    private void ensureLive(ExpensePrecheckJob job) {
        if (Thread.currentThread().isInterrupted() || job.expired(Instant.now())) throw unavailable(Stage.SYSTEM, "TIMEOUT");
        var destination = configuration.destination(job.input().tenantId()).orElse(null);
        if (destination == null) throw unavailable(Stage.CONTEXT, "NOT_CONFIGURED");
        if (!destination.digest(job.input().tenantId()).equals(job.input().targetDigest())) throw unavailable(Stage.CONTEXT, "TARGET_CHANGED");
    }
    private static <T> T value(FinanceResult<T> result, Stage stage, Integer lineNo) {
        if (result instanceof FinanceResult.Success<T> success) return success.value();
        if (result instanceof FinanceResult.Rejected<T> rejection) throw rejected(stage, lineNo, rejection.reason().name());
        var failure = (FinanceResult.Unavailable<T>) result;
        throw new CheckFailure(new Finding(stage, lineNo, Nature.UNAVAILABLE, failure.failure().name()));
    }
    private static CheckFailure rejected(Stage stage, Integer lineNo, String code) { return new CheckFailure(new Finding(stage, lineNo, Nature.REJECTED, code)); }
    private static CheckFailure unavailable(Stage stage, String code) { return new CheckFailure(new Finding(stage, null, Nature.UNAVAILABLE, code)); }

    /**
     * 内部控制流程只携带受控错误，不保留外部响应正文。
     * @author owlzhangfq@gmail.com
     */
    private static final class CheckFailure extends RuntimeException {
        private final Finding finding;
        private CheckFailure(Finding finding) { super(finding.code()); this.finding = finding; }
    }
}
