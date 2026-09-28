package io.agentflow.expense;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.finance.FinanceGatewayConfiguration;
import io.agentflow.finance.FinanceResult;
import io.agentflow.finance.Money;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * 真正经过认证、数据库、文件系统和本机 HTTP 网关；票面及法人均为合成夹具。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "agentflow.finance-gateway.enabled=true",
        "agentflow.invoices.verification-worker-enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class InvoiceVerificationIntegrationTest {
    private static final Path DIRECTORY = Path.of("/fyoung/tmp/agentflow-invoice-verification-" + UUID.randomUUID());
    private static final byte[] PDF = "%PDF-1.7\nsynthetic-verification-original\n%%EOF".getBytes(StandardCharsets.UTF_8);
    private static final UUID ENTITY = UUID.randomUUID();
    private static final HttpServer SERVER = server();
    private static final String ENDPOINT = "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/finance";
    private static final AtomicReference<Function<JsonNode, String>> RESPONDER = new AtomicReference<>();
    private static final AtomicReference<JsonNode> RECEIVED = new AtomicReference<>();
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final AtomicInteger STATUS = new AtomicInteger(200);
    private static JsonUtil wire;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("agentflow.attachments.directory", DIRECTORY::toString);
        registry.add("agentflow.finance-gateway.tenants.demo.endpoint", () -> ENDPOINT);
        registry.add("agentflow.finance-gateway.tenants.demo.allow-unauthenticated-loopback", () -> "true");
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_VERIFICATION_TEST_URL", "jdbc:h2:mem:invoice-verification;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_VERIFICATION_TEST_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_VERIFICATION_TEST_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_VERIFICATION_TEST_PASSWORD", ""));
    }
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;
    @Autowired CurrentActor actors;
    @Autowired InvoiceWalletService wallet;
    @Autowired InvoiceVerificationService execution;
    @Autowired InvoiceVerificationWorker worker;
    @Autowired InvoiceVerificationPort gateway;
    @Autowired InvoiceRepository invoices;
    @Autowired JdbcInvoiceVerificationRepository jobs;
    @Autowired JdbcInvoiceOriginalRepository originals;
    @Autowired InvoiceOriginalFiles files;
    @Autowired FinanceGatewayConfiguration configuration;
    @Autowired JdbcTemplate jdbc;
    @Autowired ExpenseReportRepository reports;
    @Autowired ApplicationRepository applications;

    @BeforeEach void reset() {
        wire = json; CALLS.set(0); STATUS.set(200); RECEIVED.set(null);
        configuration.setEnabled(true); configuration.getTenants().get("demo").setEndpoint(ENDPOINT);
        RESPONDER.set(request -> success(request, facts(request.at("/data/originalDigest").asText(), "100")));
    }
    @AfterEach void settleTestJobs() {
        configuration.setEnabled(true); configuration.getTenants().get("demo").setEndpoint(ENDPOINT);
        for (String id : jdbc.queryForList("SELECT id FROM invoice_verification_job WHERE status IN ('QUEUED','RUNNING')", String.class)) {
            var job = jobs.find("demo", UUID.fromString(id)).orElseThrow();
            if (job.status() == InvoiceVerificationJob.Status.QUEUED) job = execution.claim("demo", job.input().id(), Instant.now());
            if (job != null) execution.fail(job, InvoiceVerificationJob.Failure.INTERNAL_ERROR, Instant.now());
        }
        actors.clear();
    }
    @AfterAll static void closeServer() { SERVER.stop(0); }

    @Test
    void optionsLocateActiveJobWithoutDependingOnUuidHistoryOrder() throws Exception {
        UUID invoice = original(true);
        assertThat(tree(read(invoice, "/verification-options", "alice")).path("activeVerificationId").isMissingNode()).isTrue();
        UUID id = id(queue(invoice, "alice", UUID.randomUUID().toString(), input(invoice), 202));
        assertThat(tree(read(invoice, "/verification-options", "alice")).path("activeVerificationId").asText()).isEqualTo(id.toString());
        assertThat(read(invoice, "/verification-options", "admin").getStatus()).isEqualTo(404);
        worker.poll();
        assertThat(tree(read(invoice, "/verification-options", "alice")).path("activeVerificationId").isMissingNode()).isTrue();
    }

    @Test
    void persistedQueueUsesActualOriginalOutsideTransactionAndReplaysWithoutAnotherExternalCall() throws Exception {
        UUID invoice = original(true); String key = UUID.randomUUID().toString(); var input = input(invoice);
        var first = queue(invoice, "alice", key, input, 202); UUID id = id(first);
        assertThat(CALLS.get()).isZero(); assertThat(job(id).status()).isEqualTo(InvoiceVerificationJob.Status.QUEUED);
        var recovered = new JdbcInvoiceVerificationRepository(jdbc, json);
        new InvoiceVerificationWorker(recovered, execution, originals, files, gateway, configuration).poll();
        assertThat(job(id).status()).isEqualTo(InvoiceVerificationJob.Status.SUCCEEDED);
        assertThat(invoice(invoice).verification()).isEqualTo(Invoice.Verification.VERIFIED);
        assertThat(job(id).resultingInvoiceVersion()).isEqualTo(invoice(invoice).version());
        assertThat(Base64.getDecoder().decode(RECEIVED.get().at("/data/original").asText())).isEqualTo(PDF);
        assertThat(RECEIVED.get().at("/data/employeeId").asText()).isEqualTo("alice");
        assertThat(RECEIVED.get().at("/data/originalFileId").asText()).isEqualTo(invoice(invoice).originalFileId().toString());
        assertThat(revisions(id)).isEqualTo(3);
        configuration.setEnabled(false);
        var replay = queue(invoice, "alice", key, input, 202);
        assertThat(replay.getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
        worker.poll(); assertThat(CALLS.get()).isEqualTo(1); assertThat(revisions(id)).isEqualTo(3);
    }

    @Test
    void ownershipAndExplicitTargetApplyToEveryReadAndWrite() throws Exception {
        UUID invoice = original(true); var input = input(invoice);
        for (String user : List.of("admin", "manager")) {
            queue(invoice, user, UUID.randomUUID().toString(), input, 404);
            for (String suffix : List.of("/verification-options", "/verifications")) assertThat(read(invoice, suffix, user).getStatus()).isEqualTo(404);
        }
        queue(invoice, "alice", UUID.randomUUID().toString(), new InvoiceVerificationService.QueueInput(1L, ENTITY, "0".repeat(64)), 409);
        UUID job = id(queue(invoice, "alice", UUID.randomUUID().toString(), input, 202));
        assertThat(read(invoice, "/verifications/" + job, "admin").getStatus()).isEqualTo(404);
        assertThat(read(original(true), "/verifications/" + job, "alice").getStatus()).isEqualTo(404);
        assertThat(read(invoice, "/verifications?ownerId=alice", "alice").getStatus()).isEqualTo(400);
        assertThat(read(invoice, "/verifications?limit=101", "alice").getStatus()).isEqualTo(400);
        assertThat(read(invoice, "/verifications?beforeId=1-1-1-1-1", "alice").getStatus()).isEqualTo(400);
        assertThat(read(invoice, "/verifications/" + job, "alice").getHeader("Cache-Control")).isEqualTo("no-store");
        actors.set(new Actor("foreign-tenant", "alice", Set.of("ADMIN")));
        try { assertThatThrownBy(() -> execution.get(invoice, job)).isInstanceOf(DomainException.class).hasMessageContaining("not found"); }
        finally { actors.clear(); }
    }

    @Test
    void incompleteOriginalAndUnexpectedFieldsCannotCreateTasks() throws Exception {
        UUID invoice = original(false);
        assertThat(tree(read(invoice, "/verification-options", "alice")).path("enabled").asBoolean()).isFalse();
        queue(invoice, "alice", UUID.randomUUID().toString(), input(invoice), 422);
        UUID ready = original(true);
        var input = json.map(json.write(input(ready))); input.put("status", "SUCCEEDED");
        queue(ready, "alice", UUID.randomUUID().toString(), input, 400);
        queue(ready, "alice", UUID.randomUUID().toString(), Map.of("expectedInvoiceVersion", 0, "legalEntityId", ENTITY, "targetDigest", "a".repeat(64)), 400);
        assertThat(jobs.list("demo", ready, null, 10)).isEmpty(); assertThat(CALLS.get()).isZero();
    }

    @Test
    void transportFailureAndLegalEntityRejectionDoNotInvalidateOrReleaseOccupiedInvoice() throws Exception {
        UUID id = original(true); enqueue(id); worker.poll(); var invoice = invoice(id);
        var use = new ExpenseUse(report().id(), 1, 1); invoice.occupy(2, use, "alice", ENTITY, Instant.now()); invoices.update(invoice, 2, "alice", "OCCUPY");
        var occupied = invoice(id).state(); var claim = jdbc.queryForList("SELECT * FROM invoice_active_claim WHERE invoice_id=?", id.toString());
        STATUS.set(503); UUID unavailable = enqueue(id); worker.poll();
        assertThat(job(unavailable).failure()).isEqualTo(InvoiceVerificationJob.Failure.REMOTE_FAILURE); assertThat(invoice(id).state()).isEqualTo(occupied);
        STATUS.set(200); reject("LEGAL_ENTITY_UNAVAILABLE"); UUID rejected = enqueue(id); worker.poll();
        assertThat(job(rejected).status()).isEqualTo(InvoiceVerificationJob.Status.REJECTED); assertThat(job(rejected).resultingInvoiceVersion()).isNull();
        assertThat(invoice(id).state()).isEqualTo(occupied);
        reject("INVOICE_CANCELLED"); UUID cancelled = enqueue(id); worker.poll();
        assertThat(job(cancelled).rejection()).isEqualTo(InvoiceVerificationJob.Rejection.INVOICE_CANCELLED);
        assertThat(invoice(id).verification()).isEqualTo(Invoice.Verification.FAILED); assertThat(invoice(id).facts()).isEqualTo(occupied.facts());
        assertThat(invoice(id).occupation()).isEqualTo(Invoice.Occupation.OCCUPIED); assertThat(invoice(id).use()).isEqualTo(use);
        assertThat(jdbc.queryForList("SELECT * FROM invoice_active_claim WHERE invoice_id=?", id.toString())).isEqualTo(claim);
    }

    @Test
    void lateHttpResultCannotOverwriteAConcurrentFinancialChange() throws Exception {
        UUID invoice = original(true); UUID id = enqueue(invoice);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        RESPONDER.set(request -> { entered.countDown(); await(release); return success(request, facts(request.at("/data/originalDigest").asText(), "100")); });
        var pool = Executors.newFixedThreadPool(2);
        try {
            var work = pool.submit(worker::poll); assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            // 若 HTTP 期间仍持有发票行锁，此更新无法及时提交。
            pool.submit(() -> { var value = invoice(invoice); value.invalidated(1, "SYNTHETIC_CONCURRENT_REJECTION", Instant.now());
                invoices.update(value, 1, "alice", "FIXTURE_REJECT"); }).get(3, TimeUnit.SECONDS);
            release.countDown(); work.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); pool.shutdownNow(); }
        assertThat(job(id).failure()).isEqualTo(InvoiceVerificationJob.Failure.INVOICE_CHANGED);
        assertThat(invoice(invoice).failureCode()).isEqualTo("SYNTHETIC_CONCURRENT_REJECTION"); assertThat(invoice(invoice).version()).isEqualTo(2);
    }

    @Test
    void expiredClaimSurvivesReloadWithoutResendingAndIgnoresItsLateSuccessAfterRetry() throws Exception {
        UUID invoice = original(true); UUID id = enqueue(invoice); var claimed = execution.claim("demo", id, Instant.now());
        assertThat(claimed).isNotNull();
        execution.claim("demo", id, claimed.leaseUntil());
        assertThat(job(id).failure()).isEqualTo(InvoiceVerificationJob.Failure.TIMEOUT); assertThat(CALLS.get()).isZero();
        UUID retry = enqueue(invoice); worker.poll(); assertThat(job(retry).status()).isEqualTo(InvoiceVerificationJob.Status.SUCCEEDED);
        var resulting = invoice(invoice).state();
        execution.finish(claimed, new FinanceResult.Success<>(facts(resulting.originalDigest(), "100")), Instant.now());
        assertThat(invoice(invoice).state()).isEqualTo(resulting); assertThat(job(id).failure()).isEqualTo(InvoiceVerificationJob.Failure.TIMEOUT);
        assertThat(revisions(id)).isEqualTo(3); assertThat(CALLS.get()).isEqualTo(1);
    }

    @Test
    void changedTargetStopsQueuedOriginalBeforeAnyExternalCall() throws Exception {
        UUID invoice = original(true); UUID id = enqueue(invoice);
        configuration.getTenants().get("demo").setEndpoint(ENDPOINT + "/changed"); worker.poll();
        assertThat(job(id).failure()).isEqualTo(InvoiceVerificationJob.Failure.TARGET_CHANGED);
        assertThat(CALLS.get()).isZero(); assertThat(invoice(invoice).version()).isEqualTo(1);
    }

    @Test
    void changedOriginalBytesNeverReachTheVerifier() throws Exception {
        UUID invoice = original(true); UUID id = enqueue(invoice);
        Files.write(DIRECTORY.resolve(invoice(invoice).originalFileId() + ".bin"), "%PDF-damaged".getBytes(StandardCharsets.UTF_8));
        worker.poll();
        assertThat(job(id).failure()).isEqualTo(InvoiceVerificationJob.Failure.ORIGINAL_UNAVAILABLE);
        assertThat(invoice(invoice).verification()).isEqualTo(Invoice.Verification.PENDING); assertThat(CALLS.get()).isZero();
    }

    @Test
    void confirmedFactsAndBuyerCannotBeReplacedByAnotherVerification() throws Exception {
        UUID invoice = original(true); enqueue(invoice); worker.poll(); var verified = invoice(invoice).state();
        queue(invoice, "alice", UUID.randomUUID().toString(), new InvoiceVerificationService.QueueInput(2L, UUID.randomUUID(), digestTarget()), 422);
        RESPONDER.set(request -> success(request, facts(request.at("/data/originalDigest").asText(), "200")));
        UUID changed = enqueue(invoice); worker.poll();
        assertThat(job(changed).failure()).isEqualTo(InvoiceVerificationJob.Failure.INVALID_RESPONSE); assertThat(invoice(invoice).state()).isEqualTo(verified);
    }

    @Test
    void twoCallersQueueOnlyOneJobAndTwoWorkersClaimOnlyOneExecution() throws Exception {
        UUID invoice = original(true); var input = input(invoice); var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> concurrentQueue(invoice, input, start)); var b = pool.submit(() -> concurrentQueue(invoice, input, start)); start.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder("QUEUED", "INVOICE_VERIFICATION_ACTIVE");
            var id = jobs.list("demo", invoice, null, 10).get(0).input().id(); var claimStart = new CountDownLatch(1);
            var first = pool.submit(() -> { await(claimStart); return execution.claim("demo", id, Instant.now()) != null; });
            var second = pool.submit(() -> { await(claimStart); return execution.claim("demo", id, Instant.now()) != null; }); claimStart.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
            assertThat(revisions(id)).isEqualTo(2); assertThat(CALLS.get()).isZero();
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    @Test
    void historyPaginationDoesNotSkipOrRepeatAttemptsAndContainsNoRawInputs() throws Exception {
        UUID invoice = original(true);
        for (int index = 0; index < 3; index++) { UUID id = enqueue(invoice); var claimed = execution.claim("demo", id, Instant.now()); execution.fail(claimed, InvoiceVerificationJob.Failure.CONNECTION, Instant.now()); }
        var first = tree(read(invoice, "/verifications?limit=2", "alice"));
        var second = tree(read(invoice, "/verifications?limit=2&beforeId=" + first.path("nextBeforeId").asText(), "alice"));
        assertThat(first.path("items")).hasSize(2); assertThat(second.path("items")).hasSize(1);
        assertThat(first.path("items").findValuesAsText("id")).doesNotContain(second.at("/items/0/id").asText());
        assertThat(first.toString()).doesNotContain("originalDigest", "targetDigest", "input", "token", "originalId");
    }

    private String concurrentQueue(UUID invoice, InvoiceVerificationService.QueueInput input, CountDownLatch start) {
        await(start); actors.set(new Actor("demo", "alice", Set.of("EMPLOYEE")));
        try { execution.queue(invoice, input); return "QUEUED"; } catch (DomainException failure) { return failure.code(); } finally { actors.clear(); }
    }
    private UUID original(boolean publish) throws Exception {
        actors.set(new Actor("demo", "alice", Set.of("EMPLOYEE")));
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(PDF));
            UUID id = wallet.reserve(new InvoiceWalletService.UploadInput("合成查验.pdf", (long) PDF.length, digest, InvoiceOriginal.Format.PDF)).id();
            if (publish) wallet.upload(id, new ByteArrayInputStream(PDF)); return id;
        } finally { actors.clear(); }
    }
    private InvoiceVerificationService.QueueInput input(UUID invoice) { return new InvoiceVerificationService.QueueInput(invoice(invoice).version(), ENTITY, digestTarget()); }
    private String digestTarget() { return configuration.destination("demo").orElseThrow().digest("demo"); }
    private UUID enqueue(UUID invoice) throws Exception { return id(queue(invoice, "alice", UUID.randomUUID().toString(), input(invoice), 202)); }
    private Invoice invoice(UUID id) { return invoices.find("demo", id).orElseThrow(); }
    private InvoiceVerificationJob job(UUID id) { return jobs.find("demo", id).orElseThrow(); }
    private int revisions(UUID id) { return jdbc.queryForObject("SELECT COUNT(*) FROM invoice_verification_revision WHERE tenant_id='demo' AND job_id=?", Integer.class, id.toString()); }
    private MockHttpServletResponse queue(UUID invoice, String user, String key, Object input, int expected) throws Exception {
        var response = mvc.perform(post("/api/v1/invoices/" + invoice + "/verifications").header("Authorization", token(user)).header("Idempotency-Key", key)
                .contentType("application/json").content(json.write(input))).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected); return response;
    }
    private MockHttpServletResponse read(UUID invoice, String suffix, String user) throws Exception {
        return mvc.perform(get("/api/v1/invoices/" + invoice + suffix).header("Authorization", token(user))).andReturn().getResponse();
    }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private UUID id(MockHttpServletResponse response) throws Exception { return UUID.fromString(tree(response).path("id").asText()); }
    private JsonNode tree(MockHttpServletResponse response) throws Exception { return json.read(response.getContentAsString(StandardCharsets.UTF_8), JsonNode.class); }
    private Invoice.VerifiedFacts facts(String digest, String gross) {
        return new Invoice.VerifiedFacts(new InvoiceKey(InvoiceKey.Type.DIGITAL, null, "12345678901234567890"), ENTITY,
                new Money(new BigDecimal(gross), "CNY"), new Money(new BigDecimal("6"), "CNY"), LocalDate.now(), digest,
                "synthetic-check", Instant.now().minusSeconds(1), Instant.now().plusSeconds(300));
    }
    private String success(JsonNode request, Invoice.VerifiedFacts facts) {
        return json.write(Map.of("contractVersion", 1, "tenantId", request.path("tenantId").asText(), "requestId", request.path("requestId").asText(), "outcome", "SUCCESS", "data", facts));
    }
    private void reject(String reason) {
        RESPONDER.set(request -> json.write(Map.of("contractVersion", 1, "tenantId", request.path("tenantId").asText(), "requestId", request.path("requestId").asText(), "outcome", "REJECTED", "reason", reason)));
    }
    private ExpenseReport report() {
        UUID id = UUID.randomUUID();
        var application = Application.draftBusiness(UUID.randomUUID(), "demo", "SYNTHETIC-" + UUID.randomUUID(), "fixture", 1, "alice", "查验占用测试", Map.of(), null, null, null,
                new BusinessReference(BusinessReference.Type.EXPENSE, id)); applications.save(application);
        var report = ExpenseReport.draft(id, "demo", application.id(), "alice", new ExpenseContent(ENTITY, ExpenseContent.Type.DAILY, "合成费用", List.of(), List.of()));
        reports.create(report, "alice"); return report;
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic verification test timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }
    private static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/finance/invoice-verification", exchange -> {
                CALLS.incrementAndGet();
                var request = wire.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonNode.class); RECEIVED.set(request);
                byte[] body = RESPONDER.get().apply(request).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(STATUS.get(), body.length);
                try { exchange.getResponseBody().write(body); } finally { exchange.close(); }
            });
            server.start(); return server;
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
}
