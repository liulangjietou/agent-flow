package io.agentflow.expense;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.finance.Money;
import io.agentflow.finance.ReservedAmount;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/**
 * PostgreSQL/H2 实际约束与双写并发，不能用同一发票对象的乐观锁冒充发票号码互斥测试。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${AGENTFLOW_RESOURCE_TEST_URL:jdbc:h2:mem:finance-resources;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${AGENTFLOW_RESOURCE_TEST_USER:sa}",
        "spring.datasource.password=${AGENTFLOW_RESOURCE_TEST_PASSWORD:}",
        "spring.datasource.driver-class-name=${AGENTFLOW_RESOURCE_TEST_DRIVER:org.h2.Driver}"})
class FinancialResourceRepositoryTest {
    private static final UUID ENTITY = UUID.randomUUID();
    private static final Instant AT = Instant.parse("2026-09-28T10:00:00Z");
    private static final LocalDate DATE = LocalDate.parse("2026-09-28");
    @Autowired InvoiceRepository invoices;
    @Autowired EmployeeAdvanceRepository advances;
    @Autowired ExpenseRequestRepository requests;
    @Autowired ExpenseReportRepository reports;
    @Autowired ApplicationRepository applications;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonUtil json;

    @Test
    void preSettlementStoredAdvanceLoadsWithoutReviewFlagAndRetainsBalances() {
        var advance = advance("demo", "legacy-payment-" + UUID.randomUUID()); advances.create(advance, "fixture");
        var state = json.read(json.write(advance.state()), com.fasterxml.jackson.databind.node.ObjectNode.class);
        state.remove("paymentReviewRequired");
        jdbc.update("UPDATE finance_resource SET state_json=? WHERE tenant_id='demo' AND resource_type='ADVANCE' AND id=?", json.write(state), advance.id().toString());
        var restored = advances.find("demo", advance.id()).orElseThrow();
        assertThat(restored.state()).isEqualTo(advance.state());
        restored.requirePaymentReview(1); advances.update(restored, 1, "payment-settlement", "PAYMENT_REVIEW");
        assertThat(advances.find("demo", advance.id()).orElseThrow().available()).isEqualTo(money("0"));
        assertThat(revisions("ADVANCE", "demo", advance.id())).isEqualTo(2);
    }

    @Test
    void distinctFilesOfTheSameInvoiceRaceForOneCanonicalClaim() throws Exception {
        String number = canonicalNumber();
        var first = verified("demo", number); var second = verified("demo", number);
        first.occupy(2, new ExpenseUse(report("demo").id(), 1, 1), "alice", ENTITY, AT);
        second.occupy(2, new ExpenseUse(report("demo").id(), 1, 1), "alice", ENTITY, AT);
        var results = race(() -> invoices.update(first, 2, "alice", "OCCUPY"), () -> invoices.update(second, 2, "alice", "OCCUPY"));
        assertThat(results).containsExactlyInAnyOrder("OK", "INVOICE_OCCUPIED");
        var persisted = List.of(invoices.find("demo", first.id()).orElseThrow(), invoices.find("demo", second.id()).orElseThrow());
        assertThat(persisted.stream().map(Invoice::occupation)).containsExactlyInAnyOrder(Invoice.Occupation.OCCUPIED, Invoice.Occupation.AVAILABLE);
        assertThat(persisted.stream().map(Invoice::version)).containsExactlyInAnyOrder(3L, 2L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invoice_active_claim WHERE tenant_id='demo' AND invoice_key=?", Integer.class, "D:" + number)).isEqualTo(1);
        var losing = persisted.stream().filter(invoice -> invoice.occupation() == Invoice.Occupation.AVAILABLE).findFirst().orElseThrow();
        assertThat(revisions("INVOICE", "demo", losing.id())).isEqualTo(2);
    }

    @Test
    void releaseAllowsAnotherFileToClaimButConsumedInvoicesKeepTheirPermanentClaim() {
        String number = canonicalNumber(); var first = verified("demo", number); var second = verified("demo", number);
        var use = new ExpenseUse(report("demo").id(), 1, 1);
        first.occupy(2, use, "alice", ENTITY, AT); invoices.update(first, 2, "alice", "OCCUPY");
        first.release(3, use); invoices.update(first, 3, "alice", "RELEASE");
        second.occupy(2, use, "alice", ENTITY, AT); invoices.update(second, 2, "alice", "OCCUPY");
        second.consume(3, use, AT); invoices.update(second, 3, "cashier", "CONSUME");
        assertThat(invoices.find("demo", second.id()).orElseThrow().occupation()).isEqualTo(Invoice.Occupation.CONSUMED);
        var freshFirst = invoices.find("demo", first.id()).orElseThrow();
        freshFirst.occupy(4, new ExpenseUse(report("demo").id(), 1, 1), "alice", ENTITY, AT);
        fails("INVOICE_OCCUPIED", () -> invoices.update(freshFirst, 4, "alice", "OCCUPY"));
        assertThat(invoices.find("demo", first.id()).orElseThrow().version()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT status FROM invoice_active_claim WHERE tenant_id='demo' AND invoice_key=?", String.class, "D:" + number)).isEqualTo("CONSUMED");
    }

    @Test
    void tenantScopedInvoiceKeysCanCoexistButCannotPointAtAForeignReport() {
        String number = canonicalNumber(); var first = verified("tenant-a", number); var second = verified("tenant-b", number);
        var reportA = report("tenant-a"); var reportB = report("tenant-b");
        first.occupy(2, new ExpenseUse(reportA.id(), 1, 1), "alice", ENTITY, AT); invoices.update(first, 2, "alice", "OCCUPY");
        second.occupy(2, new ExpenseUse(reportB.id(), 1, 1), "alice", ENTITY, AT); invoices.update(second, 2, "alice", "OCCUPY");
        assertThat(invoices.find("tenant-a", second.id())).isEmpty();
        var foreign = verified("tenant-b", canonicalNumber());
        foreign.occupy(2, new ExpenseUse(reportA.id(), 1, 1), "alice", ENTITY, AT);
        assertThatThrownBy(() -> invoices.update(foreign, 2, "alice", "OCCUPY")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(invoices.find("tenant-b", foreign.id()).orElseThrow().version()).isEqualTo(2);
        assertThat(revisions("INVOICE", "tenant-b", foreign.id())).isEqualTo(2);
    }

    @Test
    void changedInvoiceOriginalOrApplicantCannotReplacePersistedIdentity() {
        var invoice = verified("demo", canonicalNumber());
        var state = invoice.state();
        var changed = Invoice.restore(new Invoice.State(state.id(), state.tenantId(), "bob", state.originalFileId(), state.originalDigest(),
                state.version(), state.verification(), state.occupation(), state.facts(), state.failureCode(), state.checkedAt(), state.use()));
        changed.invalidated(2, "CANCELLED", AT);
        fails("CONCURRENCY_CONFLICT", () -> invoices.update(changed, 2, "bob", "VERIFY_FAILED"));
        assertThat(invoices.find("demo", invoice.id()).orElseThrow().ownerId()).isEqualTo("alice");
    }

    @Test
    void concurrentAdvanceReservationsCannotSpendTheSameAvailableBalance() throws Exception {
        var advance = advance("demo", "payment-" + UUID.randomUUID()); advances.create(advance, "gateway");
        var first = advances.find("demo", advance.id()).orElseThrow(); var second = advances.find("demo", advance.id()).orElseThrow();
        first.reserve(1, new ExpenseUse(report("demo").id(), 1, 0), money("70"));
        second.reserve(1, new ExpenseUse(report("demo").id(), 1, 0), money("70"));
        assertThat(race(() -> advances.update(first, 1, "alice", "RESERVE"), () -> advances.update(second, 1, "alice", "RESERVE")))
                .containsExactlyInAnyOrder("OK", "CONCURRENCY_CONFLICT");
        var stored = advances.find("demo", advance.id()).orElseThrow();
        assertThat(stored.balance().available()).isEqualTo(money("30"));
        assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM finance_amount_use WHERE tenant_id='demo' AND resource_type='ADVANCE' AND resource_id=?", BigDecimal.class, advance.id().toString())).isEqualByComparingTo("70");
        assertThat(revisions("ADVANCE", "demo", advance.id())).isEqualTo(2);
    }

    @Test
    void consumedAdvanceRoundIsPersistedAndCannotBeReservedAgainAfterReload() {
        var advance = advance("demo", "payment-" + UUID.randomUUID()); advances.create(advance, "gateway");
        var use = new ExpenseUse(report("demo").id(), 1, 0);
        advance.reserve(1, use, money("40")); advances.update(advance, 1, "alice", "RESERVE");
        advance.settle(2, use); advances.update(advance, 2, "cashier", "CONSUME");
        var loaded = advances.find("demo", advance.id()).orElseThrow();
        assertThat(loaded.balance().consumed()).isEqualTo(money("40"));
        fails("RESERVATION_ALREADY_CONSUMED", () -> loaded.reserve(3, use, money("40")));
        assertThat(jdbc.queryForObject("SELECT status FROM finance_amount_use WHERE tenant_id='demo' AND resource_type='ADVANCE' AND resource_id=?", String.class, advance.id().toString())).isEqualTo("CONSUMED");
    }

    @Test
    void externalPaymentCannotCreateTwoAdvancesAndCannotBeIncreasedByRestoringABiggerLimit() {
        String source = "payment-" + UUID.randomUUID(); var advance = advance("demo", source); advances.create(advance, "gateway");
        fails("FINANCIAL_RESOURCE_EXISTS", () -> advances.create(advance("demo", source), "gateway"));
        var state = advance.state();
        var forged = EmployeeAdvance.restore(new EmployeeAdvance.State(state.id(), state.tenantId(), state.legalEntityId(), state.employeeId(), state.paymentReference(),
                state.paidOn(), state.dueOn(), ReservedAmount.available(money("1000")), state.version(), state.paymentReviewRequired()));
        forged.reserve(1, new ExpenseUse(report("demo").id(), 1, 0), money("200"));
        fails("CONCURRENCY_CONFLICT", () -> advances.update(forged, 1, "alice", "RESERVE"));
        assertThat(advances.find("demo", advance.id()).orElseThrow().balance().limit()).isEqualTo(money("100"));
    }

    @Test
    void foreignAmountUseRollsBackTheAggregateAndItsJournal() {
        var advance = advance("tenant-b", "payment-" + UUID.randomUUID()); advances.create(advance, "gateway");
        advance.reserve(1, new ExpenseUse(report("tenant-a").id(), 1, 0), money("10"));
        assertThatThrownBy(() -> advances.update(advance, 1, "alice", "RESERVE")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(advances.find("tenant-b", advance.id()).orElseThrow().balance().available()).isEqualTo(money("100"));
        assertThat(revisions("ADVANCE", "tenant-b", advance.id())).isEqualTo(1);
    }

    @Test
    void approvedPriorRequestAndItsClosedReservationsSurviveReload() {
        var source = sourceApplication(ApplicationStatus.APPROVED);
        var request = request(source.id()); requests.create(request, "approval");
        var use = new ExpenseUse(report("demo").id(), 1, 1);
        request.reserve(1, 1, use, money("40")); requests.update(request, 1, "alice", "RESERVE");
        request.close(2); requests.update(request, 2, "alice", "CLOSE");
        var closed = requests.find("demo", request.id()).orElseThrow();
        assertThat(closed.closed()).isTrue(); assertThat(closed.balance(1).available()).isEqualTo(money("60"));
        closed.consume(3, 1, use); requests.update(closed, 3, "cashier", "CONSUME");
        assertThat(requests.find("demo", request.id()).orElseThrow().balance(1).consumed()).isEqualTo(money("40"));
        assertThat(revisions("PRIOR_REQUEST", "demo", request.id())).isEqualTo(4);
        fails("FINANCIAL_RESOURCE_EXISTS", () -> requests.create(request(source.id()), "approval"));
    }

    @Test
    void anUnapprovedApplicationCannotMaterializeSpendablePriorRequestCredit() {
        var source = sourceApplication(ApplicationStatus.DRAFT);
        fails("PRIOR_REQUEST_NOT_APPROVED", () -> requests.create(request(source.id()), "approval"));
    }

    private List<String> race(Runnable first, Runnable second) throws Exception {
        var executor = Executors.newFixedThreadPool(2); var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try {
            var a = executor.submit(() -> run(first, ready, start)); var b = executor.submit(() -> run(second, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            return List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        } finally { start.countDown(); executor.shutdownNow(); assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }
    private static String run(Runnable action, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown(); if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent test did not start");
        try { action.run(); return "OK"; } catch (DomainException exception) { return exception.code(); }
    }
    private Invoice verified(String tenant, String number) {
        var invoice = Invoice.uploaded(UUID.randomUUID(), tenant, "alice", UUID.randomUUID(), "a".repeat(64)); invoices.create(invoice, "alice");
        invoice.verified(1, new Invoice.VerifiedFacts(new InvoiceKey(InvoiceKey.Type.DIGITAL, null, number), ENTITY, money("100"), money("6"), DATE,
                "a".repeat(64), "fixture-verification", AT, AT.plusSeconds(60))); invoices.update(invoice, 1, "verification", "VERIFY");
        return invoice;
    }
    private ExpenseReport report(String tenant) {
        UUID reportId = UUID.randomUUID();
        var app = Application.draftBusiness(UUID.randomUUID(), tenant, "EXP-" + UUID.randomUUID(), "fixture", 1, "alice", "费用", Map.of(), null, null, null,
                new BusinessReference(BusinessReference.Type.EXPENSE, reportId)); applications.save(app);
        var report = ExpenseReport.draft(reportId, tenant, app.id(), "alice", new ExpenseContent(ENTITY, ExpenseContent.Type.DAILY, "费用", List.of(), List.of()));
        reports.create(report, "alice"); return report;
    }
    private Application sourceApplication(ApplicationStatus status) {
        var source = Application.restore(UUID.randomUUID(), "demo", "PRIOR-" + UUID.randomUUID(), "prior-request-fixture", 1, "alice", "事前申请", Map.of(), status, 1, 1);
        applications.save(source); return source;
    }
    private static ExpenseRequest request(UUID applicationId) { return new ExpenseRequest(UUID.randomUUID(), "demo", applicationId, ENTITY, "alice", List.of(new ExpenseRequest.ApprovedLine(1, money("100"), BigDecimal.ZERO, "policy-fixture-v1"))); }
    private static EmployeeAdvance advance(String tenant, String reference) { return new EmployeeAdvance(UUID.randomUUID(), tenant, ENTITY, "alice", money("100"), reference, DATE, DATE); }
    private static Money money(String amount) { return new Money(new BigDecimal(amount), "CNY"); }
    private static String canonicalNumber() { return UUID.randomUUID().toString().replaceAll("[^0-9]", "").concat("0".repeat(20)).substring(0, 20); }
    private int revisions(String type, String tenant, UUID id) { return jdbc.queryForObject("SELECT COUNT(*) FROM finance_resource_revision WHERE tenant_id=? AND resource_type=? AND resource_id=?", Integer.class, tenant, type, id.toString()); }
    private static void fails(String code, Runnable operation) { assertThatExceptionOfType(DomainException.class).isThrownBy(operation::run).satisfies(error -> assertThat(error.code()).isEqualTo(code)); }
}
