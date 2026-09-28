package io.agentflow.expense;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.common.DomainException;
import io.agentflow.finance.EmployeeAccountSnapshot;
import io.agentflow.finance.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 实际仓储消费资源计划，验证规范票号替换、连续版本审计和最后一步冲突的整单回滚。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${AGENTFLOW_RESOURCE_PLAN_TEST_URL:jdbc:h2:mem:expense-resource-plan;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${AGENTFLOW_RESOURCE_PLAN_TEST_USER:sa}",
        "spring.datasource.password=${AGENTFLOW_RESOURCE_PLAN_TEST_PASSWORD:}",
        "spring.datasource.driver-class-name=${AGENTFLOW_RESOURCE_PLAN_TEST_DRIVER:org.h2.Driver}"})
class ExpenseResourcePlanIntegrationTest {
    private static final UUID ENTITY = UUID.randomUUID();
    private static final LocalDate DATE = LocalDate.of(2026, 9, 28);
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    private final ExpenseSubmissionResources planner = new ExpenseSubmissionResources();
    @Autowired InvoiceRepository invoices;
    @Autowired ExpenseRequestRepository requests;
    @Autowired EmployeeAdvanceRepository advances;
    @Autowired ExpenseReportRepository reports;
    @Autowired ApplicationRepository applications;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;

    @Test
    void actualDatabaseAcceptsCanonicalReplacementAndPreservesEveryRedistributionRevision() {
        String number = UUID.randomUUID().toString().replaceAll("[^0-9]", "").concat("0".repeat(20)).substring(0, 20);
        var old = invoice(number); var replacement = invoice(number); var prior = request();
        var report = report(List.of(line(1, "80", List.of(old.id()), prior.id()), line(2, "20", List.of(), prior.id())), List.of());
        freeze(report); var initial = resources(List.of(old, replacement), List.of(prior), List.of());
        persist(report, planner.plan(report, initial, NOW));
        report.revise(report.version(), content(List.of(line(1, "90", List.of(replacement.id()), prior.id()), line(2, "10", List.of(), prior.id())), List.of()));
        reports.update(report, report.version() - 1, "alice", "FIXTURE_REVISE");
        freeze(report);
        var current = resources(List.of(invoices.find("demo", old.id()).orElseThrow(), invoices.find("demo", replacement.id()).orElseThrow()),
                List.of(requests.find("demo", prior.id()).orElseThrow()), List.of());
        persist(report, planner.plan(report, current, NOW.plusSeconds(20)));
        assertThat(invoices.find("demo", old.id()).orElseThrow().occupation()).isEqualTo(Invoice.Occupation.AVAILABLE);
        assertThat(invoices.find("demo", replacement.id()).orElseThrow().use()).isEqualTo(new ExpenseUse(report.id(), 2, 1));
        assertThat(jdbc.queryForObject("SELECT invoice_id FROM invoice_active_claim WHERE tenant_id='demo' AND invoice_key=?", String.class, "D:" + number)).isEqualTo(replacement.id().toString());
        var loaded = requests.find("demo", prior.id()).orElseThrow(); assertThat(loaded.version()).isEqualTo(5);
        assertThat(loaded.balance(1).reservedFor(new ExpenseUse(report.id(), 2, 1))).isEqualTo(money("90"));
        assertThat(loaded.balance(1).reservedFor(new ExpenseUse(report.id(), 2, 2))).isEqualTo(money("10"));
        assertThat(loaded.balance(1).available()).isEqualTo(money("0"));
        assertThat(journal("PRIOR_REQUEST", prior.id())).isEqualTo(5);
        assertThat(reports.find("demo", report.id()).orElseThrow().currentRound().roundNo()).isEqualTo(2);
    }

    @Test
    void lateAdvanceVersionConflictRollsBackInvoiceClaimPriorReservationAndReportFreezeTogether() {
        var invoice = invoice("12345678901234567890"); var prior = request();
        var advance = new EmployeeAdvance(UUID.randomUUID(), "demo", ENTITY, "alice", money("100"), "synthetic-" + UUID.randomUUID(), DATE, DATE.plusDays(30)); advances.create(advance, "fixture");
        var report = report(List.of(line(1, "100", List.of(invoice.id()), prior.id())), List.of(new AdvanceOffset(advance.id(), money("50"))));
        freeze(report); var plan = planner.plan(report, resources(List.of(invoice), List.of(prior), List.of(advance)), NOW);
        var other = report(List.of(), List.of());
        advance.reserve(1, new ExpenseUse(other.id(), 1, 0), money("10")); advances.update(advance, 1, "alice", "OTHER_RESERVE");
        assertThatExceptionOfType(DomainException.class).isThrownBy(() -> persist(report, plan))
                .satisfies(failure -> assertThat(failure.code()).isEqualTo("CONCURRENCY_CONFLICT"));
        assertThat(invoices.find("demo", invoice.id()).orElseThrow().occupation()).isEqualTo(Invoice.Occupation.AVAILABLE);
        assertThat(jdbc.queryForList("SELECT invoice_id FROM invoice_active_claim WHERE tenant_id='demo' AND invoice_id=?", String.class, invoice.id().toString())).isEmpty();
        assertThat(requests.find("demo", prior.id()).orElseThrow().balance(1).available()).isEqualTo(money("100"));
        assertThat(advances.find("demo", advance.id()).orElseThrow().balance().available()).isEqualTo(money("90"));
        assertThat(journal("INVOICE", invoice.id())).isEqualTo(2); assertThat(journal("PRIOR_REQUEST", prior.id())).isEqualTo(1);
        var unchanged = reports.find("demo", report.id()).orElseThrow(); assertThat(unchanged.version()).isEqualTo(1); assertThat(unchanged.rounds()).isEmpty();
    }

    private void persist(ExpenseReport report, ExpenseSubmissionResources.Plan plan) {
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            for (var change : plan.invoices()) invoices.update(Invoice.restore(change.after()), change.after().version() - 1, "alice", "SUBMIT_" + change.operation().name());
            for (var change : plan.requests()) requests.update(ExpenseRequest.restore(change.after()), change.after().version() - 1, "alice", "SUBMIT_" + change.operation().name());
            for (var change : plan.advances()) advances.update(EmployeeAdvance.restore(change.after()), change.after().version() - 1, "alice", "SUBMIT_" + change.operation().name());
            reports.update(report, report.version() - 1, "alice", "FIXTURE_FREEZE");
        });
    }
    private ExpenseReport report(List<ExpenseLine> lines, List<AdvanceOffset> offsets) {
        UUID id = UUID.randomUUID(); var app = Application.draftBusiness(UUID.randomUUID(), "demo", "SYNTHETIC-" + UUID.randomUUID(), "fixture", 1, "alice", "资源计划", Map.of(), null, null, null,
                new BusinessReference(BusinessReference.Type.EXPENSE, id)); applications.save(app);
        var report = ExpenseReport.draft(id, "demo", app.id(), "alice", content(lines, offsets)); reports.create(report, "alice"); return report;
    }
    private void freeze(ExpenseReport report) {
        var assessments = new HashMap<Integer, ExpenseAssessment>();
        for (var line : report.content().lines()) assessments.put(line.lineNo(), new ExpenseAssessment(new ExpenseExchangeRate("CNY", "CNY", BigDecimal.ONE, "synthetic-rate", DATE),
                new ExpensePolicySnapshot(UUID.randomUUID(), 1, line.claimedGross(), line.claimedGross(), ExpensePolicySnapshot.Decision.WITHIN_LIMIT, "synthetic-tax", "synthetic-policy"), money("0")));
        report.freeze(report.version(), report.rounds().size() + 1, "CNY", new EmployeeAccountSnapshot(ENTITY, "alice", "synthetic-account", "****1234", "a".repeat(64), "v1"), assessments, "alice", NOW);
    }
    private Invoice invoice(String number) {
        var invoice = Invoice.uploaded(UUID.randomUUID(), "demo", "alice", UUID.randomUUID(), "a".repeat(64)); invoices.create(invoice, "alice");
        invoice.verified(1, new Invoice.VerifiedFacts(new InvoiceKey(InvoiceKey.Type.DIGITAL, null, number), ENTITY, money("100"), money("0"), DATE, "a".repeat(64), "synthetic", NOW, NOW.plusSeconds(300)));
        invoices.update(invoice, 1, "fixture", "FIXTURE_VERIFY"); return invoice;
    }
    private ExpenseRequest request() {
        var app = Application.restore(UUID.randomUUID(), "demo", "SYNTHETIC-" + UUID.randomUUID(), "prior", 1, "alice", "事前申请夹具", Map.of(), ApplicationStatus.APPROVED, 1, 1); applications.save(app);
        var request = new ExpenseRequest(UUID.randomUUID(), "demo", app.id(), ENTITY, "alice", List.of(new ExpenseRequest.ApprovedLine(1, money("100"), BigDecimal.ZERO, "synthetic-policy"))); requests.create(request, "fixture"); return request;
    }
    private ExpenseContent content(List<ExpenseLine> lines, List<AdvanceOffset> offsets) { return new ExpenseContent(ENTITY, ExpenseContent.Type.DAILY, "合成资源计划", lines, offsets); }
    private ExpenseLine line(int no, String gross, List<UUID> invoices, UUID request) {
        return new ExpenseLine(no, "DAILY", DATE, null, "SH", BigDecimal.ONE, ExpenseLine.Unit.ITEM, money(gross), money("0"), invoices, new ExpenseLine.PriorRequestLine(request, 1),
                List.of(new CostAllocation("IT", null, money(gross))), "合成明细", null);
    }
    private ExpenseSubmissionResources.Resources resources(List<Invoice> invoices, List<ExpenseRequest> requests, List<EmployeeAdvance> advances) {
        var i = new HashMap<UUID, Invoice.State>(); invoices.forEach(value -> i.put(value.id(), value.state()));
        var r = new HashMap<UUID, ExpenseRequest.State>(); requests.forEach(value -> r.put(value.id(), value.state()));
        var a = new HashMap<UUID, EmployeeAdvance.State>(); advances.forEach(value -> a.put(value.id(), value.state()));
        return new ExpenseSubmissionResources.Resources(i, r, a);
    }
    private Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
    private int journal(String kind, UUID id) { return jdbc.queryForObject("SELECT COUNT(*) FROM finance_resource_revision WHERE tenant_id='demo' AND resource_type=? AND resource_id=?", Integer.class, kind, id.toString()); }
}
