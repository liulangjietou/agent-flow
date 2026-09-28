package io.agentflow.expense;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.finance.BudgetPrecheckPort;
import io.agentflow.finance.EmployeeAccountPort;
import io.agentflow.finance.EmployeeAccountSnapshot;
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.finance.FinanceGatewayConfiguration;
import io.agentflow.finance.Money;
import io.agentflow.organization.OrganizationAppointment;
import io.agentflow.organization.OrganizationService;
import io.agentflow.organization.OrganizationUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import static io.agentflow.expense.ExpensePrecheckJob.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 认证、持久任务、真实 HTTP 财务适配器和原件文件的消费者回归；外部业务数据均为合成夹具。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "agentflow.finance-gateway.enabled=true",
        "agentflow.invoices.verification-worker-enabled=false", "agentflow.expenses.precheck-worker-enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ExpensePrecheckIntegrationTest {
    private static final Path DIRECTORY = Path.of("/fyoung/tmp/agentflow-expense-precheck-" + UUID.randomUUID());
    private static final HttpServer SERVER = server();
    private static final String ENDPOINT = "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/finance";
    private static final AtomicReference<BiFunction<String, JsonNode, String>> RESPONDER = new AtomicReference<>();
    private static final Map<String, AtomicInteger> CALLS = new ConcurrentHashMap<>();
    private static final AtomicReference<JsonNode> LAST_BUDGET = new AtomicReference<>();
    private static final AtomicInteger SERIAL = new AtomicInteger();
    private static final String ZONE = "Pacific/Kiritimati";
    private static JsonUtil wire;
    private final Actor admin = new Actor("demo", "admin", Set.of("ADMIN"));
    private UUID entity;
    private OrganizationAppointment appointment;
    private String invoiceNumber;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("agentflow.attachments.directory", DIRECTORY::toString);
        registry.add("agentflow.finance-gateway.tenants.demo.endpoint", () -> ENDPOINT);
        registry.add("agentflow.finance-gateway.tenants.demo.allow-unauthenticated-loopback", () -> "true");
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_PRECHECK_TEST_URL", "jdbc:h2:mem:expense-precheck;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_PRECHECK_TEST_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_PRECHECK_TEST_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_PRECHECK_TEST_PASSWORD", ""));
    }
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;
    @Autowired CurrentActor actors;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired OrganizationService organization;
    @Autowired ApplicationRepository applications;
    @Autowired ExpenseReportRepository reports;
    @Autowired InvoiceRepository invoices;
    @Autowired ExpenseRequestRepository requests;
    @Autowired EmployeeAdvanceRepository advances;
    @Autowired InvoiceWalletService wallet;
    @Autowired InvoiceVerificationService verification;
    @Autowired InvoiceVerificationWorker invoiceWorker;
    @Autowired ExpensePrecheckService execution;
    @Autowired ExpensePrecheckWorker worker;
    @Autowired ExpensePrecheckEvaluator evaluator;
    @Autowired JdbcExpensePrecheckRepository jobs;
    @Autowired JdbcInvoiceVerificationRepository verificationJobs;
    @Autowired ExpensePrecheckResources resourceSnapshots;
    @Autowired FinanceGatewayConfiguration configuration;

    @BeforeEach void setup() {
        wire = json; CALLS.clear(); LAST_BUDGET.set(null);
        configuration.setEnabled(true); configuration.getTenants().get("demo").setEndpoint(ENDPOINT);
        invoiceNumber = String.format("1234567890%010d", SERIAL.incrementAndGet());
        if (jdbc.queryForObject("SELECT COUNT(*) FROM organization_directory WHERE tenant_id='demo'", Integer.class) == 0) organization.initialize(admin);
        entity = organization.createUnit(admin, OrganizationUnit.Kind.LEGAL_ENTITY, "合成预检法人", null, null, true).id();
        var department = organization.createUnit(admin, OrganizationUnit.Kind.DEPARTMENT, "合成部门", entity, null, true);
        var position = organization.createUnit(admin, OrganizationUnit.Kind.POSITION, "合成岗位", entity, null, true);
        var people = jdbc.queryForList("SELECT id FROM organization_person WHERE tenant_id='demo' AND subject='alice'", String.class);
        UUID person = people.isEmpty() ? organization.createPerson(admin, "alice", "测试员工", true, false).id() : UUID.fromString(people.get(0));
        appointment = organization.createAppointment(admin, person, department.id(), position.id(), true);
        RESPONDER.set(this::normal);
    }
    @AfterEach void settleJobs() {
        for (String id : jdbc.queryForList("SELECT id FROM expense_precheck_job WHERE status IN ('QUEUED','RUNNING')", String.class)) {
            var job = job(UUID.fromString(id));
            if (job.status() == Status.QUEUED) job = execution.claim("demo", job.input().id(), Instant.now());
            if (job != null) execution.finish(job, Result.unavailable(Stage.SYSTEM, "INTERNAL_ERROR"), Instant.now());
        }
        actors.clear();
    }
    @AfterAll static void closeServer() { SERVER.stop(0); }

    @Test
    void completeReadonlyPrecheckUsesActualPortsAndKeepsEveryFinancialResourceUnchanged() throws Exception {
        var fixture = fixture(true); var report = fixture.report();
        var source = report.state(); var invoice = invoices.find("demo", fixture.invoice()).orElseThrow().state();
        var prior = requests.find("demo", fixture.prior().id()).orElseThrow().state();
        var advance = advances.find("demo", fixture.advance().id()).orElseThrow().state();
        String key = UUID.randomUUID().toString(); var first = queue(report, "alice", key, input(report), 202); UUID id = id(first);
        assertThat(count("catalog")).isZero();
        new ExpensePrecheckWorker(new JdbcExpensePrecheckRepository(jdbc, json), execution, evaluator).poll();
        var job = job(id); assertThat(job.status()).isEqualTo(Status.READY);
        var evidence = job.result().evidence();
        assertThat(evidence.preview().approvedGross()).isEqualTo(money("710", "CNY"));
        assertThat(evidence.preview().approvedTax()).isEqualTo(money("42.60", "CNY"));
        assertThat(evidence.preview().payable()).isEqualTo(money("610", "CNY"));
        assertThat(evidence.budget().request().total()).isEqualTo(money("710", "CNY"));
        assertThat(evidence.rateDate()).isEqualTo(LocalDate.ofInstant(evidence.preview().submittedAt(), ZoneId.of(ZONE)));
        assertThat(evidence.preview().originalLines().get(0).assessment().exchangeRate().rateDate()).isEqualTo(evidence.rateDate());
        assertThat(evidence.validUntil()).isBeforeOrEqualTo(evidence.rateDate().plusDays(1).atStartOfDay(ZoneId.of(ZONE)).toInstant());
        assertThat(evidence.resources()).hasSize(3); assertThat(evidence.invoices()).hasSize(1);
        assertThat(reports.find("demo", report.id()).orElseThrow().state()).isEqualTo(source);
        assertThat(invoices.find("demo", fixture.invoice()).orElseThrow().state()).isEqualTo(invoice);
        assertThat(requests.find("demo", fixture.prior().id()).orElseThrow().state()).isEqualTo(prior);
        assertThat(advances.find("demo", fixture.advance().id()).orElseThrow().state()).isEqualTo(advance);
        assertThat(applications.findById("demo", report.applicationId()).orElseThrow().status()).isEqualTo(ApplicationStatus.DRAFT);
        assertThat(journal(id)).isEqualTo(3);
        var view = tree(read(report, "/prechecks/" + id, "alice"));
        assertThat(view.path("usable").asBoolean()).isTrue();
        assertThat(view.toString()).doesNotContain("private-account", "accountDigest", "targetDigest", "originalDigest");
        assertThat(view.at("/preview/maskedAccount").asText()).isEqualTo("****1234");
        var replay = queue(report, "alice", key, input(report), 202);
        assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(replay.getHeader("Idempotency-Replayed")).isEqualTo("true");
        worker.poll(); assertThat(count("budget-precheck")).isEqualTo(1);
    }

    @Test
    void everyRouteIsOwnerOnlyAndRejectsForgedConclusionsAndStaleInputs() throws Exception {
        var report = fixture(false).report(); var input = input(report);
        for (String user : List.of("admin", "manager", "bob")) {
            queue(report, user, UUID.randomUUID().toString(), input, 404);
            assertThat(read(report, "/precheck-options", user).getStatus()).isEqualTo(404);
            assertThat(read(report, "/prechecks", user).getStatus()).isEqualTo(404);
        }
        queue(report, "alice", UUID.randomUUID().toString(), new ExpensePrecheckService.QueueInput(2L, 1L, appointment.id(), LocalDate.now(), target()), 409);
        queue(report, "alice", UUID.randomUUID().toString(), new ExpensePrecheckService.QueueInput(1L, 1L, appointment.id(), LocalDate.now(), "0".repeat(64)), 409);
        queue(report, "alice", UUID.randomUUID().toString(), new ExpensePrecheckService.QueueInput(1L, 1L, UUID.randomUUID(), LocalDate.now(), target()), 422);
        var forged = json.map(json.write(input)); forged.put("status", "READY");
        queue(report, "alice", UUID.randomUUID().toString(), forged, 400);
        assertThat(jobs.list("demo", report.id(), null, 10)).isEmpty();
        UUID id = id(queue(report, "alice", UUID.randomUUID().toString(), input, 202));
        assertThat(read(report, "/prechecks/" + id, "admin").getStatus()).isEqualTo(404);
        assertThat(read(fixture(false).report(), "/prechecks/" + id, "alice").getStatus()).isEqualTo(404);
        for (String query : List.of("?employeeId=alice", "?limit=101", "?beforeId=1-1-1-1-1")) assertThat(read(report, "/prechecks" + query, "alice").getStatus()).isEqualTo(400);
        assertThat(read(report, "/prechecks/" + id, "alice").getHeader("Cache-Control")).isEqualTo("no-store");
        actors.set(new Actor("foreign", "alice", Set.of("ADMIN")));
        try { assertThatThrownBy(() -> execution.get(report.id(), id)).isInstanceOf(DomainException.class); } finally { actors.clear(); }
    }

    @Test
    void newerAttemptSupersedesOldReadyEvenWhenBudgetLaterRejects() throws Exception {
        var report = fixture(false).report(); UUID first = enqueue(report); worker.poll();
        assertThat(job(first).status()).isEqualTo(Status.READY);
        RESPONDER.set((operation, request) -> operation.equals("budget-precheck") ? rejected(request, "BUDGET_INSUFFICIENT") : normal(operation, request));
        UUID next = enqueue(report);
        assertThat(tree(read(report, "/prechecks/" + first, "alice")).path("unavailableCode").asText()).isEqualTo("PRECHECK_SUPERSEDED");
        worker.poll(); assertThat(job(next).status()).isEqualTo(Status.BLOCKED);
        assertThat(job(next).input().attempt()).isEqualTo(2);
        assertThat(job(next).result().findings()).containsExactly(new Finding(Stage.BUDGET, null, Nature.REJECTED, "BUDGET_INSUFFICIENT"));
        assertThat(tree(read(report, "/prechecks/" + first, "alice")).path("usable").asBoolean()).isFalse();
        var page = tree(read(report, "/prechecks?limit=1", "alice"));
        assertThat(page.path("items")).hasSize(1); assertThat(page.hasNonNull("nextBeforeId")).isTrue();
        assertThat(page.toString()).doesNotContain("preview", "private-account", "findings");
    }

    @Test
    void anotherVerificationAttemptMakesOldSuccessUnusableWithoutChangingTheInvoiceVersion() throws Exception {
        var fixture = fixture(true); var report = fixture.report(); UUID ready = enqueue(report); worker.poll();
        long version = invoices.find("demo", fixture.invoice()).orElseThrow().version();
        actors.set(new Actor("demo", "alice", Set.of("EMPLOYEE")));
        UUID retry;
        try { retry = verification.queue(fixture.invoice(), new InvoiceVerificationService.QueueInput(version, entity, target())).id(); }
        finally { actors.clear(); }
        assertThat(tree(read(report, "/prechecks/" + ready, "alice")).path("unavailableCode").asText()).isEqualTo("RESOURCES_CHANGED");
        var claimed = verification.claim("demo", retry, Instant.now()); verification.fail(claimed, InvoiceVerificationJob.Failure.TIMEOUT, Instant.now());
        assertThat(invoices.find("demo", fixture.invoice()).orElseThrow().version()).isEqualTo(version);
        UUID blocked = enqueue(report); worker.poll();
        assertThat(job(blocked).result().findings()).containsExactly(new Finding(Stage.INVOICE, 1, Nature.REJECTED, "INVOICE_VERIFICATION_REQUIRED"));
        verify(fixture.invoice()); UUID fresh = enqueue(report); worker.poll(); assertThat(job(fresh).status()).isEqualTo(Status.READY);
    }

    @Test
    void externalWaitDoesNotHoldApplicationLocksAndDraftChangeDiscardsTheResult() throws Exception {
        var report = fixture(false).report(); UUID id = enqueue(report);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        RESPONDER.set((operation, request) -> { if (operation.equals("budget-precheck")) { entered.countDown(); await(release); } return normal(operation, request); });
        var pool = Executors.newFixedThreadPool(2);
        try {
            var run = pool.submit(worker::poll); assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            pool.submit(() -> { report.revise(1, report.content()); reports.update(report, 1, "alice", "FIXTURE_REVISE"); }).get(3, TimeUnit.SECONDS);
            release.countDown(); run.get(10, TimeUnit.SECONDS);
            assertThat(job(id).status()).isEqualTo(Status.UNAVAILABLE);
            assertThat(job(id).result().findings()).containsExactly(new Finding(Stage.CONTEXT, null, Nature.UNAVAILABLE, "CONTEXT_CHANGED"));
            assertThat(reports.find("demo", report.id()).orElseThrow().version()).isEqualTo(2);
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test
    void organizationTargetAndResourceChangesInvalidatePendingOrReadyEvidence() throws Exception {
        var report = fixture(false).report(); UUID pending = enqueue(report);
        organization.updateAppointment(admin, appointment.id(), false, appointment.revision());
        worker.poll(); assertThat(job(pending).result().findings().get(0).code()).isEqualTo("INITIATOR_CHANGED");
        organization.updateAppointment(admin, appointment.id(), true, appointment.revision() + 1);
        UUID targetChange = enqueue(report); configuration.getTenants().get("demo").setEndpoint(ENDPOINT + "-changed");
        worker.poll(); assertThat(job(targetChange).result().findings().get(0).code()).isEqualTo("TARGET_CHANGED");
        configuration.getTenants().get("demo").setEndpoint(ENDPOINT);
        var fixture = fixture(true); UUID ready = enqueue(fixture.report()); worker.poll();
        var another = fixture(false).report(); var advance = advances.find("demo", fixture.advance().id()).orElseThrow();
        advance.reserve(advance.version(), new ExpenseUse(another.id(), 1, 0), money("1", "CNY")); advances.update(advance, 1, "alice", "FIXTURE_RESERVE");
        assertThat(tree(read(fixture.report(), "/prechecks/" + ready, "alice")).path("unavailableCode").asText()).isEqualTo("RESOURCES_CHANGED");
    }

    @Test
    void claimedCrashTimesOutWithoutResendAndRequiresExplicitNewAttempt() throws Exception {
        var report = fixture(false).report(); UUID id = enqueue(report);
        var running = execution.claim("demo", id, Instant.now()); assertThat(running.status()).isEqualTo(Status.RUNNING);
        assertThat(execution.claim("demo", id, running.leaseUntil().minusMillis(1))).isNull();
        assertThat(execution.claim("demo", id, running.leaseUntil())).isNull();
        assertThat(job(id).result().findings()).containsExactly(new Finding(Stage.SYSTEM, null, Nature.UNAVAILABLE, "TIMEOUT"));
        worker.poll(); assertThat(count("catalog")).isZero();
        UUID retry = enqueue(report); worker.poll(); assertThat(job(retry).status()).isEqualTo(Status.READY);
        execution.finish(running, job(retry).result(), Instant.now()); assertThat(job(id).status()).isEqualTo(Status.UNAVAILABLE);
    }

    @Test
    void twoConcurrentWorkersCannotExecuteTheSamePrecheckTwice() throws Exception {
        var report = fixture(false).report(); UUID id = enqueue(report); var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> { await(start); worker.poll(); }); var two = pool.submit(() -> { await(start); worker.poll(); });
            start.countDown(); one.get(15, TimeUnit.SECONDS); two.get(15, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertThat(job(id).status()).isEqualTo(Status.READY); assertThat(count("catalog")).isEqualTo(1); assertThat(count("budget-precheck")).isEqualTo(1); assertThat(journal(id)).isEqualTo(3);
    }

    @Test
    void linePolicyAndCatalogFailuresNeverReachBudgetAndUnavailableIsNotARejection() throws Exception {
        var report = report(new ExpenseContent(entity, ExpenseContent.Type.DAILY, "逐行检查", List.of(line(1, "FORBIDDEN", List.of(), null), line(2, "OFFICE", List.of(), null)), List.of()));
        RESPONDER.set((operation, request) -> operation.equals("expense-policy") ? rejected(request, "PRIOR_REQUEST_REQUIRED") : normal(operation, request));
        UUID id = enqueue(report); worker.poll();
        assertThat(job(id).result().findings()).containsExactly(new Finding(Stage.CATALOG, 1, Nature.REJECTED, "EXPENSE_CATEGORY_UNAVAILABLE"), new Finding(Stage.POLICY, 2, Nature.REJECTED, "PRIOR_REQUEST_REQUIRED"));
        assertThat(count("budget-precheck")).isZero();
        RESPONDER.set((operation, request) -> operation.equals("catalog") ? "{}" : normal(operation, request));
        UUID unavailable = enqueue(report); worker.poll(); assertThat(job(unavailable).status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(job(unavailable).result().findings()).containsExactly(new Finding(Stage.CATALOG, null, Nature.UNAVAILABLE, "INVALID_RESPONSE"));
    }

    @Test
    void canonicalOccupationCorruptOriginalAndMissingReceiptCannotPass() throws Exception {
        var first = fixture(true); var invoice = invoices.find("demo", first.invoice()).orElseThrow();
        invoice.occupy(invoice.version(), new ExpenseUse(first.report().id(), 1, 1), "alice", entity, Instant.now()); invoices.update(invoice, 2, "alice", "FIXTURE_OCCUPY");
        var duplicate = fixture(true); UUID occupied = enqueue(duplicate.report()); worker.poll();
        assertThat(job(occupied).result().findings()).containsExactly(new Finding(Stage.RESOURCES, null, Nature.REJECTED, "INVOICE_OCCUPIED"));
        UUID invoiceId = duplicate.invoice(); Files.write(DIRECTORY.resolve(invoices.find("demo", invoiceId).orElseThrow().originalFileId() + ".bin"), new byte[]{1, 2, 3});
        UUID corrupt = enqueue(duplicate.report()); worker.poll();
        assertThat(job(corrupt).result().findings()).containsExactly(new Finding(Stage.INVOICE, 1, Nature.UNAVAILABLE, "INVOICE_ORIGINAL_UNAVAILABLE"));
        var missing = Invoice.uploaded(UUID.randomUUID(), "demo", "alice", UUID.randomUUID(), "a".repeat(64)); invoices.create(missing, "fixture");
        missing.verified(1, facts("a".repeat(64))); invoices.update(missing, 1, "fixture", "FIXTURE_VERIFY");
        var forged = report(new ExpenseContent(entity, ExpenseContent.Type.DAILY, "没有查验任务", List.of(line(1, "OFFICE", List.of(missing.id()), null)), List.of()));
        UUID noReceipt = enqueue(forged); worker.poll();
        assertThat(job(noReceipt).result().findings()).containsExactly(new Finding(Stage.INVOICE, 1, Nature.REJECTED, "INVOICE_VERIFICATION_REQUIRED"));
    }

    @Test
    void externalEvaluationRefusesAnEnclosingDatabaseTransaction() throws Exception {
        var report = fixture(false).report(); UUID id = enqueue(report); var running = execution.claim("demo", id, Instant.now());
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> evaluator.evaluate(running))).isInstanceOf(IllegalStateException.class);
        assertThat(count("catalog")).isZero();
    }

    @Test
    void returnedApplicationPrechecksSecondRoundWithoutMovingExistingReservations() throws Exception {
        var fixture = fixture(true); var report = fixture.report(); UUID first = enqueue(report); worker.poll();
        var preview = job(first).result().evidence().preview();
        var assessments = preview.originalLines().stream().collect(java.util.stream.Collectors.toMap(value -> value.original().lineNo(), ExpenseRound.FrozenLine::assessment));
        var loaded = resourceSnapshots.load(report);
        report.freeze(1, 1, preview.baseCurrency(), preview.account(), assessments, "alice", Instant.now());
        var plan = new ExpenseSubmissionResources().plan(report, loaded, Instant.now());
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            for (var change : plan.invoices()) invoices.update(Invoice.restore(change.after()), change.after().version() - 1, "fixture", "FIXTURE_RESERVE");
            for (var change : plan.requests()) requests.update(ExpenseRequest.restore(change.after()), change.after().version() - 1, "fixture", "FIXTURE_RESERVE");
            for (var change : plan.advances()) advances.update(EmployeeAdvance.restore(change.after()), change.after().version() - 1, "fixture", "FIXTURE_RESERVE");
            reports.update(report, 1, "fixture", "FIXTURE_FREEZE");
            var application = applications.findById("demo", report.applicationId()).orElseThrow(); application.submit(1); applications.update(application, 1);
            application.returnToApplicant(2); applications.update(application, 2);
        });
        report.revise(2, report.content()); reports.update(report, 2, "alice", "FIXTURE_REVISE");
        verify(fixture.invoice()); var before = resourceSnapshots.load(report); var reportBefore = report.state();
        UUID second = enqueue(report); worker.poll();
        assertThat(job(second).status()).isEqualTo(Status.READY); assertThat(job(second).input().roundNo()).isEqualTo(2);
        assertThat(job(second).result().evidence().preview().roundNo()).isEqualTo(2);
        assertThat(resourceSnapshots.load(report)).isEqualTo(before); assertThat(reports.find("demo", report.id()).orElseThrow().state()).isEqualTo(reportBefore);
        assertThat(invoices.find("demo", fixture.invoice()).orElseThrow().use().roundNo()).isEqualTo(1);
        assertThat(requests.find("demo", fixture.prior().id()).orElseThrow().balance(1).reservations().get(0).use().roundNo()).isEqualTo(1);
    }

    @Test
    void changingResourceDuringBudgetCallDiscardsOtherwiseSuccessfulEvidence() throws Exception {
        var fixture = fixture(true); UUID id = enqueue(fixture.report()); var another = fixture(false).report();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        RESPONDER.set((operation, request) -> { if (operation.equals("budget-precheck")) { entered.countDown(); await(release); } return normal(operation, request); });
        var pool = Executors.newSingleThreadExecutor();
        try {
            var run = pool.submit(worker::poll); assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var advance = advances.find("demo", fixture.advance().id()).orElseThrow();
            advance.reserve(1, new ExpenseUse(another.id(), 1, 0), money("1", "CNY")); advances.update(advance, 1, "alice", "FIXTURE_RESERVE");
            release.countDown(); run.get(10, TimeUnit.SECONDS);
            assertThat(job(id).result().findings()).containsExactly(new Finding(Stage.RESOURCES, null, Nature.UNAVAILABLE, "RESOURCES_CHANGED"));
            assertThat(invoices.find("demo", fixture.invoice()).orElseThrow().occupation()).isEqualTo(Invoice.Occupation.AVAILABLE);
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test
    void anotherOriginalCanInvalidateReadyByCanonicalClaimWithoutChangingAnyReferencedVersion() throws Exception {
        var fixture = fixture(true); UUID ready = enqueue(fixture.report()); worker.poll();
        var before = resourceSnapshots.load(fixture.report());
        var competitor = fixture(true); var invoice = invoices.find("demo", competitor.invoice()).orElseThrow();
        invoice.occupy(invoice.version(), new ExpenseUse(competitor.report().id(), 1, 1), "alice", entity, Instant.now());
        invoices.update(invoice, 2, "alice", "FIXTURE_OCCUPY");
        assertThat(resourceSnapshots.load(fixture.report())).isEqualTo(before);
        var view = tree(read(fixture.report(), "/prechecks/" + ready, "alice"));
        assertThat(view.path("usable").asBoolean()).isFalse();
        assertThat(view.path("unavailableCode").asText()).isEqualTo("RESOURCES_CHANGED");
    }

    private Fixture fixture(boolean withResources) throws Exception {
        if (!withResources) return new Fixture(report(new ExpenseContent(entity, ExpenseContent.Type.DAILY, "合成预检", List.of(line(1, "OFFICE", List.of(), null)), List.of())), null, null, null);
        UUID invoice = original(); verify(invoice);
        var priorApplication = Application.restore(UUID.randomUUID(), "demo", "SYNTHETIC-" + UUID.randomUUID(), "prior", 1, "alice", "合成已批准事前申请", Map.of(), ApplicationStatus.APPROVED, 1, 1);
        applications.save(priorApplication);
        var prior = new ExpenseRequest(UUID.randomUUID(), "demo", priorApplication.id(), entity, "alice", List.of(new ExpenseRequest.ApprovedLine(1, money("1000", "CNY"), BigDecimal.ZERO, "synthetic"))); requests.create(prior, "fixture");
        var advance = new EmployeeAdvance(UUID.randomUUID(), "demo", entity, "alice", money("500", "CNY"), "synthetic-payment-" + UUID.randomUUID(), LocalDate.now(), LocalDate.now().plusDays(30)); advances.create(advance, "fixture");
        var content = new ExpenseContent(entity, ExpenseContent.Type.DAILY, "合成完整预检", List.of(line(1, "OFFICE", List.of(invoice), prior.id())), List.of(new AdvanceOffset(advance.id(), money("100", "CNY"))));
        return new Fixture(report(content), invoice, prior, advance);
    }
    private ExpenseReport report(ExpenseContent content) {
        UUID id = UUID.randomUUID(); var application = Application.draftBusiness(UUID.randomUUID(), "demo", "SYNTHETIC-" + UUID.randomUUID(), "fixture", 1, "alice", content.title(), Map.of(), null, null, null, new BusinessReference(BusinessReference.Type.EXPENSE, id));
        applications.save(application); var report = ExpenseReport.draft(id, "demo", application.id(), "alice", content); reports.create(report, "alice"); return report;
    }
    private ExpenseLine line(int number, String category, List<UUID> invoiceIds, UUID prior) {
        return new ExpenseLine(number, category, LocalDate.parse("2026-01-02"), null, "SH", BigDecimal.ONE, ExpenseLine.Unit.ITEM, money("100", "USD"), money("6", "USD"), invoiceIds,
                prior == null ? null : new ExpenseLine.PriorRequestLine(prior, 1), List.of(new CostAllocation("IT", null, money("100", "USD"))), "合成费用", null);
    }
    private UUID original() throws Exception {
        byte[] bytes = ("%PDF-1.7\nsynthetic-precheck-" + UUID.randomUUID() + "\n%%EOF").getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); actors.set(new Actor("demo", "alice", Set.of("EMPLOYEE")));
        try { UUID id = wallet.reserve(new InvoiceWalletService.UploadInput("合成预检.pdf", (long) bytes.length, digest, InvoiceOriginal.Format.PDF)).id(); wallet.upload(id, new ByteArrayInputStream(bytes)); return id; }
        finally { actors.clear(); }
    }
    private void verify(UUID invoice) {
        actors.set(new Actor("demo", "alice", Set.of("EMPLOYEE"))); UUID id;
        try { id = verification.queue(invoice, new InvoiceVerificationService.QueueInput(invoices.find("demo", invoice).orElseThrow().version(), entity, target())).id(); }
        finally { actors.clear(); }
        invoiceWorker.poll(); assertThat(verificationJobs.find("demo", id).orElseThrow().status()).isEqualTo(InvoiceVerificationJob.Status.SUCCEEDED);
    }
    private ExpensePrecheckService.QueueInput input(ExpenseReport report) { return new ExpensePrecheckService.QueueInput(applications.findById("demo", report.applicationId()).orElseThrow().version(), report.version(), appointment.id(), LocalDate.now(), target()); }
    private UUID enqueue(ExpenseReport report) throws Exception { return id(queue(report, "alice", UUID.randomUUID().toString(), input(report), 202)); }
    private MockHttpServletResponse queue(ExpenseReport report, String user, String key, Object input, int status) throws Exception {
        var response = mvc.perform(post(path(report) + "/precheck").header("Authorization", token(user)).header("Idempotency-Key", key).contentType("application/json").content(json.write(input))).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status); return response;
    }
    private MockHttpServletResponse read(ExpenseReport report, String suffix, String user) throws Exception { return mvc.perform(get(path(report) + suffix).header("Authorization", token(user))).andReturn().getResponse(); }
    private String path(ExpenseReport report) { return "/api/v1/expense-reports/" + report.id(); }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private String target() { return configuration.destination("demo").orElseThrow().digest("demo"); }
    private JsonNode tree(MockHttpServletResponse response) throws Exception { return json.read(response.getContentAsString(StandardCharsets.UTF_8), JsonNode.class); }
    private UUID id(MockHttpServletResponse response) throws Exception { return UUID.fromString(tree(response).path("id").asText()); }
    private ExpensePrecheckJob job(UUID id) { return jobs.find("demo", id).orElseThrow(); }
    private int journal(UUID id) { return jdbc.queryForObject("SELECT COUNT(*) FROM expense_precheck_revision WHERE tenant_id='demo' AND job_id=?", Integer.class, id.toString()); }
    private static Money money(String value, String currency) { return new Money(new BigDecimal(value), currency); }
    private static int count(String operation) { return CALLS.getOrDefault(operation, new AtomicInteger()).get(); }
    private Invoice.VerifiedFacts facts(String digest) { return new Invoice.VerifiedFacts(new InvoiceKey(InvoiceKey.Type.DIGITAL, null, invoiceNumber), entity, money("100", "USD"), money("6", "USD"), LocalDate.now(), digest, "synthetic-invoice", Instant.now().minusSeconds(1), Instant.now().plusSeconds(600)); }
    private String normal(String operation, JsonNode request) {
        JsonNode data = request.path("data"); Object value = switch (operation) {
            case "catalog" -> new FinanceCatalog("alice", "synthetic-v1", Instant.now().plusSeconds(600), List.of(new FinanceCatalog.LegalEntity(entity, "合成法人", "CNY", true, "v1", ZONE)), List.of(new FinanceCatalog.Category("OFFICE", "办公", List.of(ExpenseLine.Unit.ITEM))), List.of(new FinanceCatalog.CostCenter(entity, "IT", "研发")), List.of(), List.of(new FinanceCatalog.City("SH", "上海")));
            case "employee-account" -> new EmployeeAccountPort.Account(new EmployeeAccountSnapshot(entity, "alice", "private-account", "****1234", "a".repeat(64), "v1"), Instant.now().plusSeconds(600));
            case "exchange-rate" -> new ExpenseExchangeRate(data.path("fromCurrency").asText(), data.path("toCurrency").asText(), new BigDecimal("7.1"), "synthetic-rate", LocalDate.parse(data.path("rateDate").asText()));
            case "invoice-verification" -> facts(data.path("originalDigest").asText());
            case "expense-policy" -> {
                var input = json.read(data.toString(), ExpensePolicyPort.Request.class); var amount = input.exchangeRate().convert(input.line().claimedGross());
                yield new ExpensePolicyPort.Assessment(new ExpensePolicySnapshot(UUID.randomUUID(), 1, amount, amount, ExpensePolicySnapshot.Decision.WITHIN_LIMIT, "synthetic-tax", "synthetic-policy"), input.exchangeRate().convert(input.line().claimedTax()), false, Instant.now().plusSeconds(600));
            }
            case "budget-precheck" -> { LAST_BUDGET.set(data); yield new BudgetPrecheckPort.Assessment(json.read(data.toString(), BudgetPrecheckPort.Request.class), "synthetic-budget", Instant.now().minusSeconds(1), Instant.now().plusSeconds(600)); }
            default -> throw new IllegalArgumentException("Unknown synthetic finance operation");
        };
        return json.write(Map.of("contractVersion", 1, "tenantId", request.path("tenantId").asText(), "requestId", request.path("requestId").asText(), "outcome", "SUCCESS", "data", value));
    }
    private String rejected(JsonNode request, String reason) { return json.write(Map.of("contractVersion", 1, "tenantId", request.path("tenantId").asText(), "requestId", request.path("requestId").asText(), "outcome", "REJECTED", "reason", reason)); }
    private static void await(CountDownLatch latch) { try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic precheck wait timed out"); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); } }
    private static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/finance/", exchange -> {
                String operation = exchange.getRequestURI().getPath().substring("/finance/".length()); CALLS.computeIfAbsent(operation, key -> new AtomicInteger()).incrementAndGet();
                var request = wire.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonNode.class);
                byte[] bytes = RESPONDER.get().apply(operation, request).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length);
                try { exchange.getResponseBody().write(bytes); } finally { exchange.close(); }
            }); server.start(); return server;
        } catch (java.io.IOException failed) { throw new IllegalStateException(failed); }
    }
    /**
     * 合成外部事实的本地关联，不代表正式提交或真实放款联调。
     * @author owlzhangfq@gmail.com
     */
    private record Fixture(ExpenseReport report, UUID invoice, ExpenseRequest prior, EmployeeAdvance advance) { }
}
