package io.agentflow.agent;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.InvoiceOriginal;
import io.agentflow.expense.InvoiceOriginalFiles;
import io.agentflow.expense.InvoiceWalletService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 本人公开入口、事务外准备、原请求恢复及不含票面正文的原子审计。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${AGENTFLOW_EXTRACTION_API_URL:jdbc:h2:mem:invoice-extraction-api;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000}",
        "spring.datasource.username=${AGENTFLOW_EXTRACTION_TEST_USER:sa}",
        "spring.datasource.password=${AGENTFLOW_EXTRACTION_TEST_PASSWORD:}",
        "spring.datasource.driver-class-name=${AGENTFLOW_EXTRACTION_TEST_DRIVER:org.h2.Driver}",
        "agentflow.auth.demo-enabled=true", "agentflow.assist.enabled=false", "agentflow.assist.worker-enabled=false",
        "agentflow.invoices.extraction-worker-enabled=false", "agentflow.invoices.verification-worker-enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class InvoiceExtractionApiIntegrationTest {
    private static final byte[] LOCAL = ("<EInvoice><Header><Version>0.31</Version></Header>"
            + "<TaxSupervisionInfo><InvoiceNumber>000077</InvoiceNumber></TaxSupervisionInfo></EInvoice>").getBytes(StandardCharsets.UTF_8);
    private static final byte[] FALLBACK = "<Invoice><Number>000077</Number></Invoice>".getBytes(StandardCharsets.UTF_8);
    private static final Path DIRECTORY = Path.of("/fyoung/tmp/agentflow-extraction-api-" + UUID.randomUUID());
    @Autowired MockMvc mvc;
    @Autowired CurrentActor actors;
    @MockitoSpyBean AuthService auth;
    @Autowired JsonUtil json;
    @Autowired JdbcTemplate jdbc;
    @Autowired InvoiceWalletService wallet;
    @Autowired InvoiceExtractionService service;
    @Autowired InvoiceExtractionWorker worker;
    @Autowired JdbcInvoiceExtractionRunRepository runs;
    @Autowired AssistConfiguration configuration;
    @MockitoSpyBean InvoiceOriginalFiles files;

    @DynamicPropertySource static void storage(DynamicPropertyRegistry values) { values.add("agentflow.attachments.directory", DIRECTORY::toString); }
    @BeforeEach void setup() {
        configuration.setEnabled(false);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return call.callRealMethod();
        }).when(files).read(any());
    }
    @AfterEach void settle() {
        reset(files, auth); configuration.setEnabled(false); actors.clear();
        for (var value : runs.due(Instant.now())) {
            service.claim(value.tenantId(), value.id(), Instant.now());
            service.finish(value.tenantId(), value.id(), null, InvoiceExtractionRun.Failure.INPUT_UNAVAILABLE, Instant.now());
        }
    }

    @Test void localInputQueueAndEditedConfirmationPreserveFinanceAndMinimalAudit() throws Exception {
        UUID invoice = original(LOCAL); var financial = finance(invoice);
        var input = read(path(invoice) + "/input", "alice", 200);
        assertThat(input.path("method").asText()).isEqualTo("STRUCTURED_XML");
        assertThat(input.path("transmission").asText()).isEqualTo("NONE");
        assertThat(input.path("enabled").asBoolean()).isTrue(); assertThat(input.path("targetDigest").isNull()).isTrue();
        assertThat(input.path("supportedFormats").toString()).contains("XML", "PDF", "OFD");
        var receipt = send(path(invoice), queueBody(input), key(), 202); String run = node(receipt).path("id").asText();
        assertThat(receipt.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(receipt.getHeader("Idempotency-Replayed")).isEqualTo("false");
        worker.poll(); var detail = read(path(invoice) + "/" + run, "alice", 200);
        assertThat(detail.path("status").asText()).isEqualTo("COMPLETED");
        var review = Map.of("expectedRunVersion", 3, "action", "CONFIRM", "selected", List.of(Map.of("field", "INVOICE_NUMBER", "value", "000088")), "comment", "对照原件后修订");
        String requestKey = key(); var reviewed = send(path(invoice) + "/" + run + "/review", review, requestKey, 200);
        assertThat(node(reviewed).path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(node(send(path(invoice) + "/" + run + "/review", review, requestKey, 200))).isEqualTo(node(reviewed));
        detail = read(path(invoice) + "/" + run, "alice", 200);
        assertThat(detail.at("/suggestion/proposals/0/value").asText()).isEqualTo("000077");
        assertThat(detail.at("/review/selected/0/value").asText()).isEqualTo("000088");
        assertThat(finance(invoice)).isEqualTo(financial);
        var audit = jdbc.queryForList("SELECT action,actor_id,payload_json FROM audit_event WHERE aggregate_id=? ORDER BY aggregate_version", run);
        assertThat(audit).hasSize(2); assertThat(audit.toString()).contains("INVOICE_EXTRACTION_QUEUE", "INVOICE_EXTRACTION_CONFIRM", "alice")
                .doesNotContain("000077", "000088", "对照原件后修订");
        var search = read("/api/v1/operations/audit?source=InvoiceExtractionRun&actor=alice&action=INVOICE_EXTRACTION_CONFIRM", "admin", 200);
        assertThat(search.toString()).contains(run).doesNotContain("000077", "000088", "payload");
    }

    @Test void originalReceiptReplaysWithoutFileReadAndDifferentOrExpiredRequestsFailBeforePreparation() throws Exception {
        UUID invoice = original(LOCAL); var body = queueBody(read(path(invoice) + "/input", "alice", 200)); String requestKey = key();
        var first = send(path(invoice), body, requestKey, 202);
        doThrow(new DomainException("FILE_INTEGRITY_FAILED", "Fixture original is unavailable")).when(files).read(any());
        var replay = send(path(invoice), body, requestKey, 202);
        assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(replay.getHeader("Idempotency-Replayed")).isEqualTo("true");
        var changed = new LinkedHashMap<>(body); changed.put("expectedOriginalDigest", "a".repeat(64));
        assertThat(node(send(path(invoice), changed, requestKey, 409)).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        jdbc.update("UPDATE request_idempotency SET expires_at=? WHERE tenant_id='demo' AND idempotency_key=?", java.sql.Timestamp.from(Instant.now().minusSeconds(1)), requestKey);
        assertThat(node(send(path(invoice), body, requestKey, 409)).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_EXPIRED");
    }

    @Test void missingKeyOrFailedPreparationNeverCreatesAnIdempotencyReceipt() throws Exception {
        UUID invoice = original(LOCAL); var body = queueBody(read(path(invoice) + "/input", "alice", 200)); String requestKey = key();
        doThrow(new DomainException("FILE_STORAGE_UNAVAILABLE", "Fixture unavailable")).when(files).read(any());
        assertThat(node(send(path(invoice), body, null, 400)).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        send(path(invoice), body, requestKey, 503); assertThat(receipts(requestKey)).isZero(); assertThat(runCount(invoice)).isZero();
        reset(files); send(path(invoice), body, requestKey, 202); assertThat(receipts(requestKey)).isOne();
    }

    @Test void concurrentSameKeyPreparationsQueueOnceAndReturnTheSameReceipt() throws Exception {
        UUID invoice = original(LOCAL); var body = queueBody(read(path(invoice) + "/input", "alice", 200)); String requestKey = key();
        var barrier = new CyclicBarrier(2);
        doAnswer(call -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); barrier.await(10, TimeUnit.SECONDS); return call.callRealMethod(); }).when(files).read(any());
        var threads = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<MockHttpServletResponse> operation = () -> send(path(invoice), body, requestKey, 202);
            var a = threads.submit(operation); var b = threads.submit(operation);
            var first = a.get(20, TimeUnit.SECONDS); var second = b.get(20, TimeUnit.SECONDS);
            assertThat(first.getContentAsString()).isEqualTo(second.getContentAsString());
            assertThat(List.of(first.getHeader("Idempotency-Replayed"), second.getHeader("Idempotency-Replayed"))).containsExactlyInAnyOrder("true", "false");
            assertThat(runCount(invoice)).isOne(); assertThat(receipts(requestKey)).isOne();
        } finally { threads.shutdownNow(); reset(files); }
    }

    @Test void queueAuditFailureRollsBackRunTraceAndReceipt() throws Exception {
        UUID invoice = original(LOCAL); var body = queueBody(read(path(invoice) + "/input", "alice", 200)); String requestKey = key();
        jdbc.execute("ALTER TABLE audit_event ADD CONSTRAINT ck_extract_queue_fixture CHECK(payload_json NOT LIKE '%" + invoice + "%')");
        try { assertThatThrownBy(() -> send(path(invoice), body, requestKey, 500)).isInstanceOf(jakarta.servlet.ServletException.class); }
        finally { jdbc.execute("ALTER TABLE audit_event DROP CONSTRAINT ck_extract_queue_fixture"); }
        assertThat(runCount(invoice)).isZero(); assertThat(receipts(requestKey)).isZero();
        send(path(invoice), body, requestKey, 202); assertThat(runCount(invoice)).isOne();
    }

    @Test void preparationFailureStillRestoresAConcurrentWinner() throws Exception {
        UUID invoice = original(LOCAL); var body = queueBody(read(path(invoice) + "/input", "alice", 200)); String requestKey = key();
        var entered = new CountDownLatch(1); var completed = new CountDownLatch(1); var calls = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown(); assertThat(completed.await(15, TimeUnit.SECONDS)).isTrue();
                throw new DomainException("FILE_STORAGE_UNAVAILABLE", "Concurrent fixture source unavailable");
            }
            return call.callRealMethod();
        }).when(files).read(any());
        var threads = Executors.newSingleThreadExecutor();
        try {
            var loser = threads.submit(() -> send(path(invoice), body, requestKey, 202));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var winner = send(path(invoice), body, requestKey, 202); completed.countDown();
            var replay = loser.get(15, TimeUnit.SECONDS);
            assertThat(replay.getHeader("Idempotency-Replayed")).isEqualTo("true");
            assertThat(replay.getContentAsString()).isEqualTo(winner.getContentAsString());
            assertThat(runCount(invoice)).isOne();
        } finally { completed.countDown(); threads.shutdownNow(); reset(files); }
    }

    @Test void modelTransmissionNeedsExplicitConsentCurrentTargetAndDisplayedOriginal() throws Exception {
        UUID invoice = original(FALLBACK); var disabled = read(path(invoice) + "/input", "alice", 200);
        assertThat(disabled.path("enabled").asBoolean()).isFalse();
        assertThat(disabled.path("unavailableCode").asText()).isEqualTo("AGENT_MODEL_DISABLED");
        assertThat(disabled.path("transmission").asText()).isEqualTo("XML_TEXT");
        configuration.setEnabled(true); configuration.setEndpoint("http://127.0.0.1:9/chat"); configuration.setProviderId("fixture"); configuration.setModel("fixture-model");
        var input = read(path(invoice) + "/input", "alice", 200); var body = queueBody(input);
        assertThat(input.path("destination").asText()).isEqualTo("127.0.0.1:9");
        var invalid = new LinkedHashMap<>(body); invalid.put("externalSendConfirmed", false);
        send(path(invoice), invalid, key(), 422);
        invalid.put("externalSendConfirmed", true); invalid.put("targetDigest", null); send(path(invoice), invalid, key(), 422);
        invalid = new LinkedHashMap<>(body); invalid.remove("externalSendConfirmed"); send(path(invoice), invalid, key(), 400);
        invalid = new LinkedHashMap<>(body); invalid.put("expectedOriginalId", key()); send(path(invoice), invalid, key(), 409);
        invalid = new LinkedHashMap<>(body); invalid.put("expectedOriginalDigest", "b".repeat(64)); send(path(invoice), invalid, key(), 409);
        configuration.setModel("changed-model"); send(path(invoice), body, key(), 409); configuration.setModel("fixture-model");
        send(path(invoice), body, key(), 202); assertThat(runCount(invoice)).isOne();
        assertThat(jdbc.queryForObject("SELECT status FROM agent_invoice_extraction_run WHERE invoice_id=?", String.class, invoice.toString())).isEqualTo("QUEUED");
    }

    @Test void ofdInputBindsAllPagesAndConsentWhileOriginalKeyReplaysWithoutRendering() throws Exception {
        byte[] bytes = InvoiceOfdArchiveTest.zip(InvoiceOfdRendererTest.fixture(2));
        UUID invoice = original(bytes, InvoiceOriginal.Format.OFD); var financial = finance(invoice);
        var disabled = read(path(invoice) + "/input", "alice", 200);
        assertThat(disabled.path("enabled").asBoolean()).isFalse();
        assertThat(disabled.path("transmission").asText()).isEqualTo("RENDERED_PAGES");
        assertThat(disabled.at("/input/pageCount").asInt()).isEqualTo(2);
        assertThat(disabled.at("/input/originalBytes").asInt()).isEqualTo(bytes.length);
        configuration.setEnabled(true); configuration.setEndpoint("http://127.0.0.1:9/chat"); configuration.setProviderId("fixture"); configuration.setModel("fixture-model");
        var input = read(path(invoice) + "/input", "alice", 200); var body = queueBody(input);
        var denied = new LinkedHashMap<>(body); denied.put("externalSendConfirmed", false); send(path(invoice), denied, key(), 422);
        denied = new LinkedHashMap<>(body); denied.put("pageCount", 1); send(path(invoice), denied, key(), 400);
        read(path(invoice) + "/input", "admin", 404); read(path(invoice) + "/input", "bob", 404);
        String requestKey = key(); var first = send(path(invoice), body, requestKey, 202);
        String run = node(first).path("id").asText();
        assertThat(read(path(invoice) + "/" + run, "alice", 200).at("/input/pageCount").asInt()).isEqualTo(2);
        doThrow(new DomainException("FILE_INTEGRITY_FAILED", "Fixture original is unavailable")).when(files).read(any());
        var replay = send(path(invoice), body, requestKey, 202);
        assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(replay.getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(runCount(invoice)).isOne(); assertThat(finance(invoice)).isEqualTo(financial);
    }

    @Test void aLaterOfdPageFailureProducesNoQueueReceiptOrFinancialChange() throws Exception {
        var contents = InvoiceOfdRendererTest.fixture(2);
        InvoiceOfdRendererTest.page(contents, 1, "", "<ofd:UnknownObject/>");
        UUID invoice = original(InvoiceOfdArchiveTest.zip(contents), InvoiceOriginal.Format.OFD); var financial = finance(invoice);
        assertThat(read(path(invoice) + "/input", "alice", 422).path("code").asText()).isEqualTo("INVOICE_EXTRACTION_SOURCE_UNAVAILABLE");
        assertThat(runCount(invoice)).isZero(); assertThat(finance(invoice)).isEqualTo(financial);
    }

    @Test void ownerTenantAndAdminIsolationApplyToEveryEntryWhileReplayRetainsOriginalRoleBinding() throws Exception {
        UUID invoice = original(LOCAL); var body = queueBody(read(path(invoice) + "/input", "alice", 200)); String requestKey = key();
        String run = node(send(path(invoice), body, requestKey, 202)).path("id").asText(); worker.poll();
        for (String other : List.of("bob", "admin")) {
            read(path(invoice) + "/input", other, 404); read(path(invoice), other, 404); read(path(invoice) + "/" + run, other, 404);
            sendAs(post(path(invoice)), other, body, key(), 404);
            sendAs(post(path(invoice) + "/" + run + "/review"), other, Map.of("expectedRunVersion", 3, "action", "DISMISS"), key(), 404);
            sendAs(post(path(invoice)), other, body, requestKey, 409);
        }
        doReturn(new Actor("foreign", "alice", Set.of("EMPLOYEE"))).when(auth).authenticate("foreign-fixture");
        mvc.perform(get(path(invoice)).header("Authorization", "Bearer foreign-fixture")).andExpect(status().isNotFound());
        doReturn(new Actor("demo", "alice", Set.of("EMPLOYEE", "ADMIN"))).when(auth).authenticate("roles-fixture");
        mvc.perform(post(path(invoice)).header("Authorization", "Bearer roles-fixture").header("Idempotency-Key", requestKey)
                .contentType(MediaType.APPLICATION_JSON).content(json.write(body))).andExpect(status().isForbidden());
        mvc.perform(get(path(invoice))).andExpect(status().isUnauthorized());
        read(path(original(LOCAL)) + "/" + run, "alice", 404);
    }

    @Test void queryAndBodyBoundariesRejectIdentityOverridesUnknownFieldsAndNumericInvoiceValues() throws Exception {
        UUID invoice = original(LOCAL); var body = queueBody(read(path(invoice) + "/input", "alice", 200));
        for (String query : List.of("?page=-1", "?page=1.5", "?page=1000001", "?pageSize=51", "?page=0&page=1", "?owner=bob")) read(path(invoice) + query, "alice", 400);
        read(path(invoice) + "/input?tenant=foreign", "alice", 400);
        send(path(invoice) + "?owner=bob", body, key(), 400);
        var invalid = new LinkedHashMap<>(body); invalid.put("endpoint", "https://untrusted.invalid"); send(path(invoice), invalid, key(), 400);
        invalid = new LinkedHashMap<>(body); invalid.put("externalSendConfirmed", true); send(path(invoice), invalid, key(), 422);
        String run = node(send(path(invoice), body, key(), 202)).path("id").asText(); worker.poll();
        String review = path(invoice) + "/" + run + "/review";
        send(review, Map.of("expectedRunVersion", 3, "action", "CONFIRM", "selected", List.of(Map.of("field", "INVOICE_NUMBER", "value", 77))), key(), 400);
        send(review, Map.of("expectedRunVersion", 3, "action", "CONFIRM", "selected", List.of(Map.of("field", "BUYER_NAME", "value", "未经建议的名称"))), key(), 422);
        send(review, Map.of("expectedRunVersion", 3, "action", "DISMISS", "verified", true), key(), 400);
        send(review, Map.of("expectedRunVersion", 2, "action", "DISMISS"), key(), 409);
        read(path(invoice) + "/" + run + "?owner=bob", "alice", 400);
        var page = read(path(invoice) + "?page=0&pageSize=1", "alice", 200);
        assertThat(page.path("total").asInt()).isOne(); assertThat(page.path("items").size()).isOne();
        assertThat(page.toString()).doesNotContain("000077", "suggestion", "review", "originalDigest");
        assertThat(read(path(invoice) + "?page=1&pageSize=1", "alice", 200).path("items")).isEmpty();
    }

    @Test void externalConfirmationMustBeALiteralJsonBoolean() throws Exception {
        UUID invoice = original(LOCAL); var body = queueBody(read(path(invoice) + "/input", "alice", 200));
        body.put("externalSendConfirmed", 0);
        send(path(invoice), body, key(), 400);
        body.put("externalSendConfirmed", "false"); send(path(invoice), body, key(), 400);
        assertThat(runCount(invoice)).isZero();
    }

    @Test void reviewVersionMustBeAnIntegerWithoutRoundingOrTextConversion() throws Exception {
        UUID invoice = original(LOCAL); var body = queueBody(read(path(invoice) + "/input", "alice", 200));
        String run = node(send(path(invoice), body, key(), 202)).path("id").asText(); worker.poll();
        for (Object version : List.of(3.9, "3")) send(path(invoice) + "/" + run + "/review", Map.of("expectedRunVersion", version, "action", "DISMISS"), key(), 400);
        assertThat(read(path(invoice) + "/" + run, "alice", 200).path("status").asText()).isEqualTo("COMPLETED");
    }

    @Test void reviewAuditFailureRollsBackConfirmationAndCanRetryTheOriginalRequest() throws Exception {
        UUID invoice = original(LOCAL); var body = queueBody(read(path(invoice) + "/input", "alice", 200));
        String run = node(send(path(invoice), body, key(), 202)).path("id").asText(); worker.poll();
        var review = Map.of("expectedRunVersion", 3, "action", "DISMISS"); String requestKey = key();
        jdbc.execute("ALTER TABLE audit_event ADD CONSTRAINT ck_extract_review_fixture CHECK(aggregate_id<>'" + run + "' OR action<>'INVOICE_EXTRACTION_DISMISS')");
        try { assertThatThrownBy(() -> send(path(invoice) + "/" + run + "/review", review, requestKey, 500)).isInstanceOf(jakarta.servlet.ServletException.class); }
        finally { jdbc.execute("ALTER TABLE audit_event DROP CONSTRAINT ck_extract_review_fixture"); }
        assertThat(read(path(invoice) + "/" + run, "alice", 200).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(receipts(requestKey)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_invoice_extraction_transition WHERE run_id=? AND run_version=4", Long.class, run)).isZero();
        send(path(invoice) + "/" + run + "/review", review, requestKey, 200);
    }

    private UUID original(byte[] bytes) { return original(bytes, InvoiceOriginal.Format.XML); }
    private UUID original(byte[] bytes, InvoiceOriginal.Format format) {
        actors.set(new Actor("demo", "alice", Set.of("EMPLOYEE")));
        try {
            String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            var original = wallet.reserve(new InvoiceWalletService.UploadInput("original." + format.name().toLowerCase(java.util.Locale.ROOT),
                    (long) bytes.length, digest, format));
            wallet.upload(original.id(), new ByteArrayInputStream(bytes)); return original.id();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        finally { actors.clear(); }
    }
    private static String key() { return UUID.randomUUID().toString(); }
    private static String path(UUID invoice) { return "/api/v1/invoices/" + invoice + "/extraction-runs"; }
    private Map<String, Object> queueBody(JsonNode input) {
        var body = new LinkedHashMap<String, Object>(); body.put("expectedOriginalId", input.at("/input/originalId").asText());
        body.put("expectedOriginalDigest", input.at("/input/originalDigest").asText()); body.put("method", input.path("method").asText());
        body.put("targetDigest", input.path("targetDigest").isNull() ? null : input.path("targetDigest").asText());
        body.put("externalSendConfirmed", !input.path("method").asText().equals("STRUCTURED_XML")); return body;
    }
    private JsonNode read(String path, String user, int expected) throws Exception {
        var response = mvc.perform(get(path).header("Authorization", token(user))).andExpect(status().is(expected)).andReturn().getResponse();
        if (expected == 200) assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        return node(response);
    }
    private MockHttpServletResponse send(String path, Object body, String key, int expected) throws Exception {
        return sendAs(post(path), "alice", body, key, expected);
    }
    private MockHttpServletResponse sendAs(MockHttpServletRequestBuilder request, String user, Object body, String key, int expected) throws Exception {
        request.header("Authorization", token(user)).contentType(MediaType.APPLICATION_JSON).content(json.write(body));
        if (key != null) request.header("Idempotency-Key", key);
        return mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse();
    }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private JsonNode node(MockHttpServletResponse response) throws Exception { return json.read(response.getContentAsString(), JsonNode.class); }
    private long receipts(String key) { return jdbc.queryForObject("SELECT COUNT(*) FROM request_idempotency WHERE tenant_id='demo' AND idempotency_key=?", Long.class, key); }
    private long runCount(UUID invoice) { return jdbc.queryForObject("SELECT COUNT(*) FROM agent_invoice_extraction_run WHERE invoice_id=?", Long.class, invoice.toString()); }
    private Map<String, Object> finance(UUID invoice) { return jdbc.queryForMap("SELECT * FROM finance_resource WHERE resource_type='INVOICE' AND id=?", invoice.toString()); }
}
