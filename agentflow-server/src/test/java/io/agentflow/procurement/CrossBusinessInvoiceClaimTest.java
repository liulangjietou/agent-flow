package io.agentflow.procurement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentflow.approval.JdbcApplicationRepository;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.*;
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.finance.JdbcBudgetConsumptionReversalRepository;
import io.agentflow.finance.Money;
import io.agentflow.organization.InitiatorContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/**
 * 采购原应付与员工报销使用真实仓储争抢同一票号，核验失败回滚及历史识别不随付款撤销。
 * @author owlzhangfq@gmail.com
 */
class CrossBusinessInvoiceClaimTest {
    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    private final String tenant = "invoice-claim-" + UUID.randomUUID();
    private final UUID entity = UUID.randomUUID();
    private final InvoiceKey key = new InvoiceKey(InvoiceKey.Type.DIGITAL, null, "00000000000000000001");
    private final JsonUtil json = new JsonUtil(new ObjectMapper().registerModule(new JavaTimeModule())
            .registerModule(new FinanceJsonConfiguration().financeMoneyModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private JdbcInvoiceRepository invoices;
    private JdbcExpenseReportRepository expenses;
    private JdbcApplicationRepository applications;
    private JdbcProcurementPaymentRepository requests;
    private JdbcProcurementPayableReservationRepository reservations;

    @BeforeEach void database() {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_INVOICE_CLAIM_TEST_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_INVOICE_CLAIM_TEST_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_INVOICE_CLAIM_TEST_PASSWORD", ""));
        Flyway.configure().dataSource(source).load().migrate(); jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source)); applications = new JdbcApplicationRepository(jdbc, json);
        expenses = new JdbcExpenseReportRepository(jdbc, json);
        invoices = new JdbcInvoiceRepository(new FinancialResourceStore(jdbc,
                new FinancialResourceReversalJournal(jdbc, json, expenses, new JdbcBudgetConsumptionReversalRepository(jdbc, json))), jdbc, json);
        requests = new JdbcProcurementPaymentRepository(jdbc, json);
        reservations = new JdbcProcurementPayableReservationRepository(jdbc, json, requests, new JdbcProcurementInvoiceClaims(jdbc), new SupplierPayableReturnGuard(jdbc));
    }

    @Test void procurementRecognitionRejectsAnExpenseCopyAndRollsBackItsVersionAndJournal() {
        var request = procurement("AP-1"); submit(request, List.of(key));
        var invoice = invoice(key); var use = use();
        fails(() -> occupy(invoice, use));
        assertThat(invoices.find(tenant, invoice.id()).orElseThrow().state()).isEqualTo(invoice.state());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finance_resource_revision WHERE tenant_id=? AND resource_id=?", Integer.class, tenant, invoice.id().toString())).isEqualTo(2);
        assertThat(reservations.active(tenant, request.id())).isPresent();
    }

    @Test void expenseOccupationAndConsumptionBothRejectProcurementWithoutFreezingTheRequest() {
        var invoice = invoice(key); var use = use(); occupy(invoice, use);
        for (boolean consumed : List.of(false, true)) {
            if (consumed) {
                var value = invoices.find(tenant, invoice.id()).orElseThrow(); value.consume(value.version(), use, NOW);
                tx.executeWithoutResult(status -> invoices.update(value, value.version() - 1, "fixture", "CONSUME"));
            }
            var request = procurement("AP-" + consumed); var original = request.state();
            fails(() -> submit(request, List.of(key)));
            assertThat(requests.find(tenant, request.id()).orElseThrow().state()).isEqualTo(original);
            assertThat(reservations.history(tenant, request.id())).isEmpty();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM procurement_payment_revision WHERE tenant_id=? AND request_id=?", Integer.class, tenant, request.id().toString())).isEqualTo(1);
        }
    }

    @Test void cancellingPaymentKeepsOriginalInvoiceRecognitionAndAnotherRequestMayUseOnlyTheSamePayable() {
        var request = procurement("AP-1"); submit(request, List.of(key)); var original = reservations.active(tenant, request.id()).orElseThrow();
        tx.executeWithoutResult(status -> reservations.release(original.release(ProcurementPayableReservation.ReleaseReason.CANCELLED, "alice", NOW.plusSeconds(2))));
        fails(() -> occupy(invoice(key), use()));
        submit(procurement("AP-1"), List.of(key));
        var other = procurement("AP-2"); fails(() -> submit(other, List.of(key)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invoice_active_claim WHERE tenant_id=? AND invoice_key=?", Integer.class, tenant, key.canonical())).isEqualTo(1);
    }

    @Test void aLaterInvoiceConflictRollsBackEveryEarlierClaimAndTheWholeProcurementSubmission() {
        var free = new InvoiceKey(InvoiceKey.Type.DIGITAL, null, "00000000000000000000"); occupy(invoice(key), use());
        var request = procurement("AP-1"); var original = request.state();
        fails(() -> submit(request, List.of(free, key)));
        assertThat(requests.find(tenant, request.id()).orElseThrow().state()).isEqualTo(original);
        assertThat(reservations.history(tenant, request.id())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invoice_active_claim WHERE tenant_id=? AND invoice_key=?", Integer.class, tenant, free.canonical())).isZero();
    }

    @Test void releasedExpenseAllowsProcurementButItsRecognitionAlsoBlocksReadOnlyExpensePrechecks() {
        var invoice = invoice(key); var use = use(); occupy(invoice, use);
        var occupied = invoices.find(tenant, invoice.id()).orElseThrow(); occupied.release(occupied.version(), use);
        tx.executeWithoutResult(status -> invoices.update(occupied, occupied.version() - 1, "alice", "RELEASE"));
        var request = procurement("AP-1"); submit(request, List.of(key));
        var available = invoices.find(tenant, invoice.id()).orElseThrow(); available.occupy(available.version(), use, "alice", entity, NOW);
        var plan = new ExpenseSubmissionResources.Plan(List.of(new ExpenseSubmissionResources.InvoiceChange(available.state(), ExpenseSubmissionResources.Operation.RESERVE)), List.of(), List.of());
        var precheck = new ExpensePrecheckResources(invoices, null, null, null, jdbc);
        fails(() -> precheck.requireClaimsAvailable(tenant, plan));
        assertThat(invoices.find(tenant, invoice.id()).orElseThrow().occupation()).isEqualTo(Invoice.Occupation.AVAILABLE);
    }

    @Test void traditionalCodesAndLeadingZeroesStayDistinctAndOneInvoiceAcrossLinesHasOneOrigin() {
        var traditional = new InvoiceKey(InvoiceKey.Type.TRADITIONAL, "0001", "0002");
        var request = procurement("AP-1"); submit(request, List.of(traditional, traditional));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invoice_active_claim WHERE tenant_id=?", Integer.class, tenant)).isEqualTo(1);
        occupy(invoice(new InvoiceKey(InvoiceKey.Type.TRADITIONAL, "1", "0002")), use());
        occupy(invoice(new InvoiceKey(InvoiceKey.Type.TRADITIONAL, "0001", "2")), use());
        fails(() -> occupy(invoice(traditional), use()));
    }

    @Test void procurementAndExpenseRacingWithoutAnyExistingClaimHaveExactlyOneWinner() throws Exception {
        var request = procurement("AP-1"); var invoice = invoice(key); var use = use();
        var executor = Executors.newFixedThreadPool(2); var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try {
            var procurement = executor.submit(() -> compete(() -> submit(request, List.of(key)), ready, start));
            var expense = executor.submit(() -> compete(() -> occupy(invoice, use), ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(List.of(procurement.get(10, TimeUnit.SECONDS), expense.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "INVOICE_OCCUPIED");
            boolean supplierWon = reservations.active(tenant, request.id()).isPresent();
            assertThat(invoices.find(tenant, invoice.id()).orElseThrow().occupation())
                    .isEqualTo(supplierWon ? Invoice.Occupation.AVAILABLE : Invoice.Occupation.OCCUPIED);
            assertThat(requests.find(tenant, request.id()).orElseThrow().version()).isEqualTo(supplierWon ? 2 : 1);
        } finally { start.countDown(); executor.shutdownNow(); assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    private Invoice invoice(InvoiceKey identity) {
        var value = Invoice.uploaded(UUID.randomUUID(), tenant, "alice", UUID.randomUUID(), "a".repeat(64));
        tx.executeWithoutResult(status -> invoices.create(value, "alice"));
        value.verified(1, new Invoice.VerifiedFacts(identity, entity, money("100"), money("6"), LocalDate.of(2026, 9, 29), "a".repeat(64), "verified", NOW, NOW.plusSeconds(300)));
        tx.executeWithoutResult(status -> invoices.update(value, 1, "fixture", "VERIFY")); return value;
    }
    private ExpenseUse use() {
        var id = UUID.randomUUID();
        var app = Application.draftBusiness(UUID.randomUUID(), tenant, "EXP-" + id, "fixture", 1, "alice", "费用", Map.of(), null, null, null, new BusinessReference(BusinessReference.Type.EXPENSE, id));
        var report = ExpenseReport.draft(id, tenant, app.id(), "alice", new ExpenseContent(entity, ExpenseContent.Type.DAILY, "费用", List.of(), List.of()));
        tx.executeWithoutResult(status -> { applications.save(app); expenses.create(report, "alice"); }); return new ExpenseUse(id, 1, 1);
    }
    private void occupy(Invoice original, ExpenseUse use) {
        var value = Invoice.restore(original.state()); value.occupy(value.version(), use, "alice", entity, NOW);
        tx.executeWithoutResult(status -> invoices.update(value, value.version() - 1, "alice", "OCCUPY"));
    }
    private ProcurementPaymentRequest procurement(String payable) {
        var request = ProcurementPaymentRequest.draft(UUID.randomUUID(), tenant, UUID.randomUUID(), "alice", new ProcurementPaymentContent(entity, "采购付款", "已验收", "supplier", payable, money("10")));
        var app = Application.draftBusiness(request.applicationId(), tenant, "PROC-" + request.id(), "fixture", 1, "alice", "采购付款", Map.of(), null, null, null, new BusinessReference(BusinessReference.Type.PROCUREMENT_PAYMENT, request.id()));
        tx.executeWithoutResult(status -> { applications.save(app); requests.create(request, "alice"); }); return request;
    }
    private void submit(ProcurementPaymentRequest original, List<InvoiceKey> keys) {
        var request = ProcurementPaymentRequest.restore(original.state());
        var lines = java.util.stream.IntStream.range(0, keys.size()).mapToObj(index -> new ProcurementPayablePort.MatchedLine(index + 1, index + 1, "receipt-" + index,
                keys.get(index), index + 1, "a".repeat(64), "verified", "件", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, money("100"), money("100"), money("100"), money("6"))).toList();
        var amount = money(Integer.toString(100 * keys.size()));
        var payable = new ProcurementPayablePort.Payable(request.content().payableRequest("alice"), "v1", NOW, NOW.plusSeconds(300), "供应商",
                new SupplierAccountSnapshot(entity, "supplier", "account", "****1234", "b".repeat(64), "v1"), "contract", "order", "matching", "accrual", "budget", LocalDate.of(2026, 10, 1), amount, money("0"), lines);
        var catalog = new FinanceCatalog("alice", "v1", NOW.plusSeconds(300), List.of(new FinanceCatalog.LegalEntity(entity, "法人", "CNY", false, "v1", "Asia/Shanghai")), List.of(), List.of(), List.of(), List.of());
        var initiator = new InitiatorContext(UUID.randomUUID(), UUID.randomUUID(), "alice", 1, entity, "法人", UUID.randomUUID(), "部门", UUID.randomUUID(), "岗位");
        tx.executeWithoutResult(status -> {
            request.freeze(1, 1, catalog, "c".repeat(64), payable, initiator, NOW.plusSeconds(1)); requests.update(request, 1, "alice", "SUBMIT");
            reservations.create(ProcurementPayableReservation.hold(UUID.randomUUID(), request, NOW.plusSeconds(1)));
        });
    }
    private static String compete(Runnable action, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown(); if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Invoice race did not start");
        try { action.run(); return "OK"; } catch (DomainException conflict) { return conflict.code(); }
    }
    private static Money money(String amount) { return new Money(new BigDecimal(amount), "CNY"); }
    private static void fails(Runnable action) { assertThatExceptionOfType(DomainException.class).isThrownBy(action::run).satisfies(error -> assertThat(error.code()).isEqualTo("INVOICE_OCCUPIED")); }
}
