package io.agentflow.agent;
import static io.agentflow.agent.InvoiceExtractionRun.Status.*;
import static io.agentflow.agent.InvoiceExtractionService.ReviewAction.*;
import static io.agentflow.agent.InvoiceExtractionSuggestion.Field.INVOICE_NUMBER;
import static io.agentflow.agent.InvoiceExtractionSuggestion.Method.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.sun.net.httpserver.HttpServer;

import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.InvoiceOriginal;
import io.agentflow.expense.InvoiceOriginalFiles;
import io.agentflow.expense.InvoiceWalletService;
import io.agentflow.observability.DiagnosticContext;
import io.agentflow.organization.OrganizationService;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 实际原件、数据库和回环模型验证持久抽取、身份隔离、租约及不修改财务状态。
 *
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${AGENTFLOW_EXTRACTION_TEST_URL:jdbc:h2:mem:invoice-extraction;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${AGENTFLOW_EXTRACTION_TEST_USER:sa}",
        "spring.datasource.password=${AGENTFLOW_EXTRACTION_TEST_PASSWORD:}",
        "spring.datasource.driver-class-name=${AGENTFLOW_EXTRACTION_TEST_DRIVER:org.h2.Driver}",
        "agentflow.auth.demo-enabled=true", "agentflow.assist.enabled=false", "agentflow.assist.worker-enabled=false",
        "agentflow.invoices.extraction-worker-enabled=false", "agentflow.invoices.verification-worker-enabled=false"})
class InvoiceExtractionIntegrationTest {
    private static final JsonUtil JSON = new JsonUtil(new com.fasterxml.jackson.databind.ObjectMapper());
    private static final byte[] LOCAL = ("<EInvoice><Header><Version>0.31</Version></Header>"
            + "<TaxSupervisionInfo><InvoiceNumber>000077</InvoiceNumber></TaxSupervisionInfo></EInvoice>").getBytes(StandardCharsets.UTF_8);
    private static final byte[] FALLBACK = "<Invoice><Number>000077</Number></Invoice>".getBytes(StandardCharsets.UTF_8);
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final AtomicReference<String> LAST_TRACE = new AtomicReference<>();
    private static final AtomicReference<String> LAST_BODY = new AtomicReference<>("");
    private static final HttpServer MODEL_SERVER = model();
    private static final Path DIRECTORY = Path.of("/fyoung/tmp/agentflow-extraction-" + UUID.randomUUID());
    private final List<JdbcInvoiceExtractionRunRepository.Candidate> queued = new ArrayList<>();
    @Autowired CurrentActor actors;
    @Autowired InvoiceWalletService wallet;
    @Autowired InvoiceExtractionService service;
    @Autowired InvoiceExtractionWorker worker;
    @Autowired JdbcInvoiceExtractionRunRepository runs;
    @Autowired InvoiceExtractionPort engine;
    @Autowired AssistConfiguration configuration;
    @Autowired OrganizationService organization;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean InvoiceOriginalFiles files;

    @DynamicPropertySource static void directory(DynamicPropertyRegistry values) { values.add("agentflow.attachments.directory", DIRECTORY::toString); }
    @BeforeEach void setup() {
        actor("demo", "alice"); configuration.setEnabled(true); configuration.setProviderId("fixture-provider");
        configuration.setModel("fixture-model"); configuration.setTimeoutSeconds(1);
        configuration.setEndpoint("http://127.0.0.1:" + MODEL_SERVER.getAddress().getPort() + "/v1/chat/completions");
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return invocation.callRealMethod();
        }).when(files).read(any());
    }
    @AfterEach void settle() {
        reset(files);
        for (var value : queued) {
            service.claim(value.tenantId(), value.id(), Instant.now());
            service.finish(value.tenantId(), value.id(), null, InvoiceExtractionRun.Failure.MODEL_UNAVAILABLE, Instant.now());
        }
        actors.clear();
    }
    @AfterAll static void stop() { MODEL_SERVER.stop(0); }

    @Test
    void tracePersistsThroughQueueAndReachesModel() throws Exception {
        UUID invoice = original(FALLBACK);
        var prepared = service.prepare(invoice);
        String expectedTrace = UUID.randomUUID().toString();
        UUID id;
        try (var ignored = new DiagnosticContext(expectedTrace, "demo").open()) {
            id = queue(prepared, target());
        }
        actors.clear();
        assertThat(DiagnosticContext.validTrace(expectedTrace)).isTrue();
        assertThat(MDC.get(DiagnosticContext.TRACE_ID)).isNull();
        assertThat(jdbc.queryForMap(
                                        "SELECT * FROM agent_invoice_extraction_run WHERE"
                                                + " tenant_id='demo' AND id=?", id.toString())
                .get("TRACE_ID")).isEqualTo(expectedTrace);
        LAST_TRACE.set(null);
        worker.poll();
        assertThat(LAST_TRACE.get()).isEqualTo(expectedTrace);
        assertThat(jdbc.queryForMap(
                                        "SELECT * FROM agent_invoice_extraction_run WHERE"
                                                + " tenant_id='demo' AND id=?", id.toString())
                .get("TRACE_ID")).isEqualTo(expectedTrace);
        assertThat(MDC.get(DiagnosticContext.TRACE_ID)).isNull();
        assertThat(MDC.get(DiagnosticContext.TENANT_ID)).isNull();
    }

    @Test void localXmlCompletesWithModelDisabledAndConfirmationNeverChangesInvoiceState() {
        configuration.setEnabled(false); int requests = REQUESTS.get();
        UUID invoice = original(LOCAL); var financial = financial(invoice);
        var prepared = service.prepare(invoice); assertThat(prepared.method()).isEqualTo(STRUCTURED_XML);
        UUID id = queue(prepared, null); actors.clear(); worker.poll(); actor("demo", "alice");
        var detail = service.get(invoice, id);
        assertThat(detail.status()).isEqualTo(COMPLETED); assertThat(detail.canConfirm()).isTrue();
        assertThat(detail.suggestion().proposals()).singleElement().satisfies(value -> {
            assertThat(value.value()).isEqualTo("000077"); assertThat(value.evidence().get(0).xmlPath()).contains("TaxSupervisionInfo[1]");
        });
        service.review(invoice, id, 3, CONFIRM, List.of(new InvoiceExtractionSuggestion.Selection(INVOICE_NUMBER, "000088")), "本人已对照");
        var saved = service.get(invoice, id);
        assertThat(saved.status()).isEqualTo(CONFIRMED); assertThat(saved.canConfirm()).isFalse();
        assertThat(saved.suggestion()).isEqualTo(detail.suggestion());
        assertThat(saved.review().selected().get(0).value()).isEqualTo("000088");
        assertThat(financial(invoice)).isEqualTo(financial);
        assertThat(transitions(id)).containsExactly("QUEUED", "RUNNING", "COMPLETED", "CONFIRMED");
        assertThat(REQUESTS.get()).isEqualTo(requests);
        assertThat(runs.activeId("demo", invoice)).isEmpty();
    }

    @Test void modelQueueRequiresExplicitMethodAndFrozenTargetAndDoesNotSendUntilClaimed() {
        UUID invoice = original(FALLBACK); var prepared = service.prepare(invoice); int requests = REQUESTS.get();
        assertThat(prepared.method()).isEqualTo(MODEL);
        fails("AGENT_INPUT_CHANGED", () -> service.queue(prepared, STRUCTURED_XML, null));
        fails("AGENT_TARGET_CHANGED", () -> service.queue(prepared, MODEL, "a".repeat(64)));
        UUID id = queue(prepared, target());
        assertThat(REQUESTS.get()).isEqualTo(requests);
        fails("AGENT_RUN_ACTIVE", () -> service.queue(prepared, MODEL, target()));
        actors.clear(); worker.poll(); actor("demo", "alice");
        assertThat(service.get(invoice, id).status()).isEqualTo(COMPLETED);
        assertThat(REQUESTS.get()).isEqualTo(requests + 1);
        assertThat(LAST_BODY.get()).contains("000077", "originalDigest").doesNotContain("alice", "invoice-original.xml", "tenantId", DIRECTORY.toString());
        service.review(invoice, id, 3, DISMISS, null, "不采用");
        worker.poll(); assertThat(REQUESTS.get()).isEqualTo(requests + 1);
        assertThat(service.list(invoice, 0, 1).total()).isOne();
    }

    @Test void preparedInputHistoryAndConfirmationAreBoundToOriginalOwnerAndTenant() {
        UUID invoice = original(LOCAL); var prepared = service.prepare(invoice); UUID id = queue(prepared, null); worker.poll();
        for (String other : List.of("bob", "admin")) {
            actor("demo", other);
            fails("NOT_FOUND", () -> service.prepare(invoice));
            fails("NOT_FOUND", () -> service.queue(prepared, STRUCTURED_XML, null));
            fails("NOT_FOUND", () -> service.get(invoice, id));
            fails("NOT_FOUND", () -> service.list(invoice, 0, 10));
            fails("NOT_FOUND", () -> service.review(invoice, id, 3, DISMISS, null, null));
        }
        actor("foreign", "alice"); fails("NOT_FOUND", () -> service.get(invoice, id));
        assertThat(runs.find("foreign", id)).isEmpty();
        actor("demo", "alice"); UUID other = original(LOCAL);
        fails("NOT_FOUND", () -> service.get(other, id));
        fails("INVALID_REQUEST", () -> service.list(invoice, 0, 51));
        service.review(invoice, id, 3, DISMISS, null, null);
        fails("CONCURRENCY_CONFLICT", () -> service.review(invoice, id, 3, DISMISS, null, null));
    }

    @Test void disabledModelOrChangedTargetSettlesFailureWithoutExternalRequests() {
        int requests = REQUESTS.get(); UUID invoice = original(FALLBACK); UUID id = queue(service.prepare(invoice), target());
        configuration.setModel("changed-model"); worker.poll();
        assertThat(service.get(invoice, id).failure()).isEqualTo(InvoiceExtractionRun.Failure.MODEL_UNAVAILABLE);
        assertThat(transitions(id)).containsExactly("QUEUED", "RUNNING", "FAILED");
        assertThat(REQUESTS.get()).isEqualTo(requests);
        UUID next = queue(service.prepare(invoice), target()); configuration.setEnabled(false); worker.poll();
        assertThat(service.get(invoice, next).failure()).isEqualTo(InvoiceExtractionRun.Failure.MODEL_UNAVAILABLE);
        fails("AGENT_MODEL_DISABLED", () -> service.queue(service.prepare(invoice), MODEL, target()));
        assertThat(REQUESTS.get()).isEqualTo(requests);
    }

    @Test void onlyOneConcurrentClaimWinsAndExpiredLeaseNeverResendsOrAcceptsLateResult() throws Exception {
        UUID invoice = original(FALLBACK); UUID id = queue(service.prepare(invoice), target()); int requests = REQUESTS.get();
        var start = new CountDownLatch(1); var threads = Executors.newFixedThreadPool(2);
        InvoiceExtractionRun.Context claimed;
        try {
            java.util.concurrent.Callable<InvoiceExtractionRun.Context> claim = () -> { start.await(); return service.claim("demo", id, Instant.now()); };
            var first = threads.submit(claim); var second = threads.submit(claim); start.countDown();
            var a = first.get(10, TimeUnit.SECONDS); var b = second.get(10, TimeUnit.SECONDS);
            assertThat((a == null) != (b == null)).isTrue(); claimed = a == null ? b : a;
        } finally { threads.shutdownNow(); }
        Instant expired = runs.lease("demo", id).plusSeconds(1);
        assertThat(service.claim("demo", id, expired)).isNull();
        service.finish("demo", id, proposal(claimed), null, expired);
        worker.poll();
        assertThat(service.get(invoice, id).failure()).isEqualTo(InvoiceExtractionRun.Failure.EXECUTION_TIMEOUT);
        assertThat(transitions(id)).containsExactly("QUEUED", "RUNNING", "FAILED");
        assertThat(REQUESTS.get()).isEqualTo(requests);
        UUID next = queue(service.prepare(invoice), target()); assertThat(next).isNotEqualTo(id);
        worker.poll(); assertThat(service.get(invoice, next).status()).isEqualTo(COMPLETED);
        assertThat(REQUESTS.get()).isEqualTo(requests + 1);
    }

    @Test void localExecutionFailuresDoNotClaimThatAModelWasCalled() {
        configuration.setEnabled(false);
        int requests = REQUESTS.get(); UUID invoice = original(LOCAL); UUID id = queue(service.prepare(invoice), null);
        service.claim("demo", id, Instant.now());
        service.claim("demo", id, runs.lease("demo", id).plusSeconds(1));
        assertThat(service.get(invoice, id).failure().name()).isEqualTo("EXECUTION_TIMEOUT");
        UUID next = queue(service.prepare(invoice), null); service.claim("demo", next, Instant.now());
        service.finish("demo", next, null, null, Instant.now());
        assertThat(service.get(invoice, next).failure().name()).isEqualTo("INVALID_RESULT");
        UUID late = queue(service.prepare(invoice), null); var context = service.claim("demo", late, Instant.now());
        var suggestion = engine.generate(context);
        service.finish("demo", late, suggestion, null, runs.lease("demo", late).plusSeconds(1));
        assertThat(service.get(invoice, late).failure().name()).isEqualTo("EXECUTION_TIMEOUT");
        assertThat(REQUESTS.get()).isEqualTo(requests);
    }

    @Test void unexpectedWorkerFailureKeepsOriginalLeaseAndDoesNotAutomaticallyExecuteAgain() {
        UUID invoice = original(FALLBACK); UUID id = queue(service.prepare(invoice), target()); int requests = REQUESTS.get();
        doThrow(new IllegalStateException("Fixture parser interrupted")).when(files).read(any());
        worker.poll(); assertThat(service.get(invoice, id).status()).isEqualTo(RUNNING);
        Instant lease = runs.lease("demo", id);
        reset(files); worker.poll();
        assertThat(runs.lease("demo", id)).isEqualTo(lease);
        assertThat(service.get(invoice, id).status()).isEqualTo(RUNNING);
        service.claim("demo", id, lease.plusSeconds(1)); worker.poll();
        assertThat(service.get(invoice, id).failure()).isEqualTo(InvoiceExtractionRun.Failure.EXECUTION_TIMEOUT);
        assertThat(transitions(id)).containsExactly("QUEUED", "RUNNING", "FAILED");
        assertThat(REQUESTS.get()).isEqualTo(requests);
    }

    @Test void concurrentQueuesWithDifferentRequestIdentitiesStillCreateOneActiveRun() throws Exception {
        var prepared = service.prepare(original(LOCAL)); var start = new CountDownLatch(1); var threads = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<String> queue = () -> {
                actor("demo", "alice"); start.await();
                try { return service.queue(prepared, STRUCTURED_XML, null).id().toString(); }
                catch (DomainException failure) { return failure.code(); }
                finally { actors.clear(); }
            };
            var first = threads.submit(queue); var second = threads.submit(queue); start.countDown();
            var results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(results).containsOnlyOnce("AGENT_RUN_ACTIVE");
            UUID id = UUID.fromString(results.stream().filter(value -> !value.equals("AGENT_RUN_ACTIVE")).findFirst().orElseThrow());
            queued.add(new JdbcInvoiceExtractionRunRepository.Candidate("demo", id, null));
            assertThat(service.list(prepared.input().invoiceId(), 0, 10).total()).isOne();
        } finally { threads.shutdownNow(); }
    }

    @Test void directoryRevocationPreventsClaimAndSendingEvenForNonApproverOwners() {
        String tenant = "extract-" + UUID.randomUUID(); var admin = new Actor(tenant, "admin", Set.of("ADMIN"));
        organization.initialize(admin); var person = organization.createPerson(admin, "owner", "普通员工", true, false);
        actor(tenant, "owner"); UUID invoice = original(FALLBACK); UUID id = queue(service.prepare(invoice), target()); int requests = REQUESTS.get();
        var changed = organization.updatePerson(admin, person.id(), person.displayName(), false, false, person.revision());
        worker.poll(); assertThat(runs.find(tenant, id).orElseThrow().state().failure()).isEqualTo(InvoiceExtractionRun.Failure.INPUT_UNAVAILABLE);
        fails("FORBIDDEN", () -> service.get(invoice, id));
        var active = organization.updatePerson(admin, changed.id(), changed.displayName(), true, false, changed.revision());
        UUID next = queue(service.prepare(invoice), target()); var context = service.claim(tenant, next, Instant.now());
        organization.updatePerson(admin, active.id(), active.displayName(), false, false, active.revision());
        assertThatThrownBy(() -> engine.generate(context)).isInstanceOfSatisfying(AssistModelPort.ModelFailure.class,
                failure -> assertThat(failure.failure()).isEqualTo(AssistRun.Failure.INPUT_UNAVAILABLE));
        service.finish(tenant, next, null, InvoiceExtractionRun.Failure.INPUT_UNAVAILABLE, Instant.now());
        assertThat(REQUESTS.get()).isEqualTo(requests);
    }

    @Test void nonApproverDemoAccountCanExtractOwnLocalXml() {
        actor("demo", "cashier"); UUID invoice = original(LOCAL); UUID id = queue(service.prepare(invoice), null);
        worker.poll(); assertThat(service.get(invoice, id).status()).isEqualTo(COMPLETED);
    }

    @Test void contentFailureAndInvalidProducerResultDoNotBecomeSuccessfulRuns() {
        UUID invoice = original(FALLBACK); UUID id = queue(service.prepare(invoice), target()); int requests = REQUESTS.get();
        doThrow(new DomainException("FILE_INTEGRITY_FAILED", "Fixture original changed")).when(files).read(any());
        worker.poll(); assertThat(service.get(invoice, id).failure()).isEqualTo(InvoiceExtractionRun.Failure.INPUT_UNAVAILABLE);
        assertThat(REQUESTS.get()).isEqualTo(requests); reset(files);
        UUID next = queue(service.prepare(invoice), target()); service.claim("demo", next, Instant.now());
        service.finish("demo", next, null, null, Instant.now());
        assertThat(service.get(invoice, next).failure()).isEqualTo(InvoiceExtractionRun.Failure.INVALID_RESULT);
    }

    @Test void journalFailureRollsBackStageLeaseAndActiveKeyAndOldVersionsCannotWin() {
        UUID invoice = original(LOCAL); UUID id = queue(service.prepare(invoice), null);
        jdbc.update("INSERT INTO agent_invoice_extraction_transition VALUES('demo',?,2,'RUNNING','{}')", id.toString());
        assertThatThrownBy(() -> service.claim("demo", id, Instant.now())).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(runs.find("demo", id).orElseThrow().state().status()).isEqualTo(QUEUED);
        assertThat(runs.lease("demo", id)).isNull(); assertThat(runs.activeId("demo", invoice)).contains(id);
        jdbc.update("DELETE FROM agent_invoice_extraction_transition WHERE run_id=? AND run_version=2", id.toString());
        var first = runs.find("demo", id).orElseThrow(); var second = runs.find("demo", id).orElseThrow();
        var now = Instant.now(); first.start(1, now); second.start(1, now);
        runs.update(first, 1, now.plusSeconds(60));
        fails("CONCURRENCY_CONFLICT", () -> runs.update(second, 1, now.plusSeconds(60)));
        service.finish("demo", id, null, InvoiceExtractionRun.Failure.INPUT_UNAVAILABLE, Instant.now());
        assertThat(transitions(id)).containsExactly("QUEUED", "RUNNING", "FAILED");
    }

    @Test void changedContextAndForeignOriginalCannotBePersistedAsTheSameRun() {
        var prepared = service.prepare(original(LOCAL)); UUID id = queue(prepared, null); var original = runs.find("demo", id).orElseThrow();
        var context = original.context();
        var replacement = new InvoiceExtractionRun(new InvoiceExtractionRun.Context(id, "demo", "alice", context.createdAt(),
                prepared.input(), MODEL, target()));
        replacement.start(1, Instant.now());
        fails("CONCURRENCY_CONFLICT", () -> runs.update(replacement, 1, Instant.now().plusSeconds(60)));
        var foreign = new InvoiceExtractionRun(new InvoiceExtractionRun.Context(UUID.randomUUID(), "foreign", "alice", Instant.now(), prepared.input(), STRUCTURED_XML, null));
        fails("AGENT_INPUT_CHANGED", () -> runs.create(foreign));
        var wrongOwner = new InvoiceExtractionRun(new InvoiceExtractionRun.Context(UUID.randomUUID(), "demo", "bob", Instant.now(), prepared.input(), STRUCTURED_XML, null));
        fails("AGENT_INPUT_CHANGED", () -> runs.create(wrongOwner));
        assertThat(runs.find("demo", id).orElseThrow().context()).isEqualTo(context);
    }

    @Test void preparationAndWorkerRejectDatabaseTransactionsAndStoredRunsRestoreWithNewRepositoryInstance() {
        UUID invoice = original(LOCAL); var transaction = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> service.prepare(invoice))).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> worker.poll())).isInstanceOf(IllegalStateException.class);
        UUID id = queue(service.prepare(invoice), null); worker.poll();
        var reopened = new JdbcInvoiceExtractionRunRepository(
                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                (jdbc).getDataSource(),
                                io.agentflow.agent.mapper.InvoiceExtractionRunRepositoryMapper
                                        .class), new JsonUtil(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()));
        assertThat(reopened.find("demo", id).orElseThrow().state()).isEqualTo(runs.find("demo", id).orElseThrow().state());
        assertThat(reopened.due(Instant.now())).noneMatch(value -> value.id().equals(id));
    }

    private void actor(String tenant, String owner) { actors.set(new Actor(tenant, owner, Set.of("EMPLOYEE"))); }
    private UUID original(byte[] bytes) {
        var receipt = wallet.reserve(new InvoiceWalletService.UploadInput("invoice-original.xml", (long) bytes.length,
                AssistConfiguration.digest(new String(bytes, StandardCharsets.UTF_8)), InvoiceOriginal.Format.XML));
        wallet.upload(receipt.id(), new ByteArrayInputStream(bytes)); return receipt.id();
    }
    private UUID queue(InvoiceExtractionService.Prepared prepared, String target) {
        var receipt = service.queue(prepared, prepared.method(), target);
        queued.add(new JdbcInvoiceExtractionRunRepository.Candidate(actors.actor().tenantId(), receipt.id(), null)); return receipt.id();
    }
    private String target() { return configuration.targetDigest(InvoiceExtractionRun.CONTRACT_VERSION); }
    private Map<String, Object> financial(UUID invoice) { return jdbc.queryForMap("SELECT * FROM finance_resource WHERE resource_type='INVOICE' AND id=?", invoice.toString()); }
    private List<String> transitions(UUID id) { return jdbc.queryForList(
                "SELECT status FROM agent_invoice_extraction_transition WHERE run_id=? ORDER BY"
                        + " run_version", String.class, id.toString()); }
    private static void fails(String code, Runnable action) { assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, failure -> assertThat(failure.code()).isEqualTo(code)); }
    private static InvoiceExtractionSuggestion proposal(InvoiceExtractionRun.Context context) {
        return new InvoiceExtractionSuggestion(MODEL, "fixture-provider", "fixture-model", InvoiceExtractionRun.CONTRACT_VERSION,
                List.of(new InvoiceExtractionSuggestion.Proposal(INVOICE_NUMBER, "000077", InvoiceExtractionSuggestion.Confidence.MEDIUM,
                        List.of(new InvoiceExtractionSuggestion.Evidence(context.input().originalId(), context.input().originalDigest(), 1, "000077")))));
    }
    private static HttpServer model() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                LAST_BODY.set(request); LAST_TRACE.set(exchange.getRequestHeaders().getFirst(DiagnosticContext.HEADER)); REQUESTS.incrementAndGet();
                var body = JSON.read(request, com.fasterxml.jackson.databind.JsonNode.class);
                var metadata = JSON.read(body.at("/messages/1/content/0/text").asText(), com.fasterxml.jackson.databind.JsonNode.class).path("source");
                var result = Map.of("proposals", List.of(Map.of("field", "INVOICE_NUMBER", "value", "000077", "confidence", "MEDIUM", "evidence", List.of(Map.of(
                        "originalId", metadata.path("originalId").asText(), "originalDigest", metadata.path("originalDigest").asText(), "page", 1, "quote", "000077")))));
                byte[] reply = JSON.write(Map.of("model", "fixture-model", "choices", List.of(Map.of("finish_reason", "stop",
                        "message", Map.of("role", "assistant", "content", JSON.write(result)))))).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, reply.length);
                exchange.getResponseBody().write(reply); exchange.close();
            });
            server.start(); return server;
        } catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
}
