package io.agentflow.expense;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.agentflow.agent.*;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.finance.FinanceGatewayConfiguration;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * 从本人 HTTP 预览到真实回环目录和模型，验证授权、精确同意、事务外调用和逐项确认。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${AGENTFLOW_EXPENSE_DRAFT_TEST_URL:jdbc:h2:mem:expense-draft-assist;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${AGENTFLOW_EXPENSE_DRAFT_TEST_USER:sa}", "spring.datasource.password=${AGENTFLOW_EXPENSE_DRAFT_TEST_PASSWORD:}",
        "spring.datasource.driver-class-name=${AGENTFLOW_EXPENSE_DRAFT_TEST_DRIVER:org.h2.Driver}",
        "agentflow.auth.demo-enabled=true", "agentflow.assist.enabled=true", "agentflow.assist.worker-enabled=false",
        "agentflow.assist.model=synthetic-draft", "agentflow.assist.timeout-seconds=2", "agentflow.finance-gateway.enabled=true"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ExpenseDraftAssistIntegrationTest {
    private static final AtomicInteger MODEL_CALLS = new AtomicInteger(), CATALOG_CALLS = new AtomicInteger(), OBSERVATIONS = new AtomicInteger();
    private static final AtomicReference<String> MODEL_MODE = new AtomicReference<>("success"), CATALOG_MODE = new AtomicReference<>("success");
    private static final AtomicReference<Runnable> DURING_CATALOG = new AtomicReference<>();
    private static final AtomicReference<String> LAST_MODEL = new AtomicReference<>();
    private static final AtomicReference<UUID> ENTITY = new AtomicReference<>();
    private static final UUID FOREIGN_ENTITY = UUID.randomUUID();
    private static final java.util.concurrent.ExecutorService HTTP_THREADS = Executors.newFixedThreadPool(4);
    private static final HttpServer SERVER = server();
    private static final String ORIGIN = "http://127.0.0.1:" + SERVER.getAddress().getPort();
    private static final Path OBSERVATION_DIRECTORY = Path.of("/fyoung/tmp/agentflow-remaining-20260928", "a03-backend-observations-" + UUID.randomUUID());
    private static JsonUtil wire;
    private final List<UUID> queued = new ArrayList<>();
    @Autowired MockMvc mvc;
    @MockitoSpyBean AuthService auth;
    @Autowired JsonUtil json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationRepository applications;
    @Autowired ExpenseReportRepository reports;
    @Autowired ExpenseDraftAssistService service;
    @Autowired ExpenseDraftAssistWorker worker;
    @Autowired JdbcExpenseDraftAssistRepository runs;
    @Autowired AssistConfiguration configuration;
    @Autowired FinanceGatewayConfiguration finance;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("agentflow.assist.endpoint", () -> ORIGIN + "/model");
        properties.add("agentflow.finance-gateway.tenants.demo.endpoint", () -> ORIGIN + "/finance");
        properties.add("agentflow.finance-gateway.tenants.demo.allow-unauthenticated-loopback", () -> true);
        properties.add("agentflow.attachments.directory", () -> OBSERVATION_DIRECTORY.resolve("attachments").toString());
    }
    @BeforeEach void setup() throws Exception {
        wire = json; Files.createDirectories(OBSERVATION_DIRECTORY); ENTITY.set(UUID.randomUUID());
        configuration.setEnabled(true); configuration.setEndpoint(ORIGIN + "/model"); configuration.setModel("synthetic-draft"); configuration.setTimeoutSeconds(2);
        finance.getTenants().get("demo").setEndpoint(ORIGIN + "/finance"); MODEL_MODE.set("success"); CATALOG_MODE.set("success"); DURING_CATALOG.set(null);
    }
    @AfterEach void settleRemaining() {
        configuration.setEnabled(true); configuration.setModel("synthetic-draft"); DURING_CATALOG.set(null);
        for (var id : queued) {
            var run = runs.find("demo", id).orElseThrow();
            if (run.state().status() == ExpenseDraftAssistRun.Status.QUEUED) service.claim("demo", id, Instant.now());
            service.finish("demo", id, null, AssistRun.Failure.INPUT_UNAVAILABLE, Instant.now());
        }
    }
    @AfterAll static void stop() throws Exception {
        SERVER.stop(0); HTTP_THREADS.shutdownNow();
        Files.writeString(OBSERVATION_DIRECTORY.resolve("manifest.json"), wire.write(Map.of("directory", OBSERVATION_DIRECTORY.toString(),
                "observations", OBSERVATIONS.get(), "modelRequests", MODEL_CALLS.get(), "catalogRequests", CATALOG_CALLS.get())));
        System.out.println("Expense draft observations=" + OBSERVATION_DIRECTORY);
    }

    @Test void previewQueueAndGranularConfirmationPreserveFinancialFactsAndIdempotentReplay() throws Exception {
        var report = report(); var beforeApp = jdbc.queryForMap("SELECT * FROM approval_application WHERE id=?", report.applicationId().toString());
        var beforeReport = jdbc.queryForMap("SELECT * FROM expense_report WHERE id=?", report.id().toString());
        int modelBefore = MODEL_CALLS.get(); var preview = preview(report);
        assertThat(preview.at("/input/sources")).hasSize(3);
        assertThat(preview.at("/input/sources").toString()).contains("HOTEL", "现场调研").doesNotContain("不可发送", "FOREIGN", report.id().toString());
        assertThat(MODEL_CALLS.get()).isEqualTo(modelBefore);
        var body = generation(preview); String key = UUID.randomUUID().toString(); var receipt = send(report, "", "alice", body, key, 202); UUID id = remember(receipt);
        int reads = CATALOG_CALLS.get(); CATALOG_MODE.set("denied");
        assertThat(send(report, "", "alice", body, key, 202)).isEqualTo(receipt); assertThat(CATALOG_CALLS.get()).isEqualTo(reads);
        CATALOG_MODE.set("success"); send(report, "", "alice", body, UUID.randomUUID().toString(), 409); worker.poll();
        var detail = read(report, "/" + id, "alice", 200); assertThat(detail.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(detail.path("canConfirm").asBoolean()).isTrue(); assertThat(MODEL_CALLS.get()).isEqualTo(modelBefore + 1);
        assertThat(LAST_MODEL.get()).doesNotContain(report.id().toString(), report.applicationId().toString(), "不可发送", "targetDigest", "alice", "FOREIGN");
        String reviewKey = UUID.randomUUID().toString(); var confirmed = send(report, "/" + id + "/confirm", "alice", confirmation(), reviewKey, 200);
        assertThat(confirmed.path("status").asText()).isEqualTo("CONFIRMED"); reads = CATALOG_CALLS.get(); CATALOG_MODE.set("denied");
        assertThat(send(report, "/" + id + "/confirm", "alice", confirmation(), reviewKey, 200)).isEqualTo(confirmed);
        assertThat(CATALOG_CALLS.get()).isEqualTo(reads);
        var reviewed = read(report, "/" + id, "alice", 200); assertThat(reviewed.at("/review/selected/0/parts").toString()).contains("ITINERARY", "CATEGORY").doesNotContain("ALLOCATION");
        assertThat(reviewed.path("suggestion")).isEqualTo(detail.path("suggestion")); assertThat(transitions(id)).isEqualTo(4);
        assertThat(jdbc.queryForMap("SELECT * FROM approval_application WHERE id=?", report.applicationId().toString())).isEqualTo(beforeApp);
        assertThat(jdbc.queryForMap("SELECT * FROM expense_report WHERE id=?", report.id().toString())).isEqualTo(beforeReport);
        assertThat(read(report, "?page=0&pageSize=1", "alice", 200).path("items")).hasSize(1);
        assertThat(read(report, "?page=1&pageSize=1", "alice", 200).path("items")).isEmpty();
        var editorPage = read(report, "?page=0&pageSize=20", "alice", 200);
        assertThat(editorPage.path("pageSize").asInt()).isEqualTo(20);
        assertThat(editorPage.path("items")).hasSize(1);
    }

    @Test void otherUsersAdminsAndTenantsCannotPreviewReadOrReplayAnotherApplicantsData() throws Exception {
        var report = report(); var preview = preview(report); var body = generation(preview); String key = UUID.randomUUID().toString();
        UUID id = remember(send(report, "", "alice", body, key, 202)); int reads = CATALOG_CALLS.get();
        for (String user : List.of("bob", "admin")) {
            send(report, "/preview", user, input(), UUID.randomUUID().toString(), 404); read(report, "", user, 404);
            read(report, "/" + id, user, 404); send(report, "", user, body, key, 404);
        }
        org.mockito.Mockito.doReturn(new Actor("foreign", "alice", Set.of("EMPLOYEE"))).when(auth).authenticate("foreign-draft-token");
        read(report, "/" + id, "foreign-draft-token", 404);
        var other = report(); read(other, "/" + id, "alice", 404);
        org.mockito.Mockito.doReturn(new Actor("demo", "alice", Set.of("EMPLOYEE", "AUDITOR"))).when(auth).authenticate("changed-draft-token");
        send(report, "", "changed-draft-token", body, key, 403); assertThat(CATALOG_CALLS.get()).isEqualTo(reads);
    }

    @ParameterizedTest @ValueSource(strings = {"brief", "catalog-version", "catalog-name", "expiry", "model", "finance", "application", "financial"})
    void changedPreviewCannotQueueUnderTheOriginalConsent(String variant) throws Exception {
        var report = report(); var body = generation(preview(report));
        switch (variant) {
            case "brief" -> ((ObjectNode) body.path("input")).put("brief", "改变了发送正文");
            case "catalog-version" -> CATALOG_MODE.set("v2");
            case "catalog-name" -> CATALOG_MODE.set("renamed");
            case "expiry" -> body.put("validUntil", Instant.now().minusSeconds(1).toString());
            case "model" -> configuration.setModel("changed-model");
            case "finance" -> finance.getTenants().get("demo").setEndpoint(ORIGIN + "/changed-finance");
            case "application" -> jdbc.update("UPDATE approval_application SET version=version+1 WHERE id=?", report.applicationId().toString());
            case "financial" -> { report.revise(1, report.content()); reports.update(report, 1, "alice", "REVISE"); }
            default -> throw new AssertionError(variant);
        }
        int before = MODEL_CALLS.get(); send(report, "", "alice", body, UUID.randomUUID().toString(), 409);
        assertThat(runs.page("demo", report.id(), 0, 20).total()).isZero(); assertThat(MODEL_CALLS.get()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"denied", "renamed", "v2", "app-during-catalog", "model", "finance", "expiry", "expiry-after-claim"})
    void queuedSourceChangesAreStoppedBeforeModelHttp(String variant) throws Exception {
        var report = report(); UUID id = queue(report); int before = MODEL_CALLS.get();
        if (Set.of("denied", "renamed", "v2").contains(variant)) CATALOG_MODE.set(variant);
        if (variant.equals("app-during-catalog")) DURING_CATALOG.set(() -> jdbc.update("UPDATE approval_application SET version=version+1 WHERE id=?", report.applicationId().toString()));
        if (variant.equals("model")) configuration.setModel("changed-model");
        if (variant.equals("finance")) finance.getTenants().get("demo").setEndpoint(ORIGIN + "/changed-finance");
        if (variant.equals("expiry")) assertThat(service.claim("demo", id, runs.find("demo", id).orElseThrow().context().input().validUntil())).isNull();
        else if (variant.equals("expiry-after-claim")) {
            Instant expiry = runs.find("demo", id).orElseThrow().context().input().validUntil();
            // 单独覆盖目录到期：租约须晚于目录有效期，避免先命中执行超时。
            var context = service.claim("demo", id, expiry.minusSeconds(1));
            assertThat(service.sendable(context, context.input().validUntil())).isFalse();
        }
        else worker.poll();
        var run = runs.find("demo", id).orElseThrow(); assertThat(run.state().status()).isEqualTo(ExpenseDraftAssistRun.Status.FAILED);
        assertThat(run.state().failure()).isEqualTo(variant.equals("model") ? AssistRun.Failure.MODEL_UNAVAILABLE : AssistRun.Failure.INPUT_UNAVAILABLE);
        assertThat(MODEL_CALLS.get()).isEqualTo(before); assertThat(transitions(id)).isEqualTo(3);
    }

    @Test void sourceChangesPreventConfirmationButExpiredOrDisabledSuggestionsCanBeDismissed() throws Exception {
        var report = report(); UUID id = queue(report); worker.poll(); var detail = read(report, "/" + id, "alice", 200);
        CATALOG_MODE.set("v2"); send(report, "/" + id + "/confirm", "alice", confirmation(), UUID.randomUUID().toString(), 409);
        assertThat(transitions(id)).isEqualTo(3); int reads = CATALOG_CALLS.get(); configuration.setEnabled(false);
        send(report, "/" + id + "/dismiss", "alice", Map.of("expectedRunVersion", 3), UUID.randomUUID().toString(), 200);
        assertThat(CATALOG_CALLS.get()).isEqualTo(reads); assertThat(read(report, "/" + id, "alice", 200).path("suggestion")).isEqualTo(detail.path("suggestion"));
    }

    @Test void emptyResultIsAnExplicitAbstentionAndCanOnlyBeDismissed() throws Exception {
        var report = report(); UUID id = queue(report); MODEL_MODE.set("empty"); worker.poll();
        var detail = read(report, "/" + id, "alice", 200); assertThat(detail.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(detail.path("canConfirm").asBoolean()).isFalse(); assertThat(detail.path("unavailableCode").asText()).isEqualTo("AGENT_NO_SUGGESTIONS");
        send(report, "/" + id + "/confirm", "alice", confirmation(), UUID.randomUUID().toString(), 422);
        send(report, "/" + id + "/dismiss", "alice", Map.of("expectedRunVersion", 3), UUID.randomUUID().toString(), 200);
    }

    @ParameterizedTest @ValueSource(strings = {"application", "financial", "approval"})
    void changedReportAfterGenerationPreventsConfirmationBeforeAnyDirectoryRead(String variant) throws Exception {
        var report = report(); UUID id = queue(report); worker.poll(); int reads = CATALOG_CALLS.get();
        if (variant.equals("financial")) { report.revise(1, report.content()); reports.update(report, 1, "alice", "REVISE"); }
        else if (variant.equals("application")) jdbc.update("UPDATE approval_application SET version=version+1 WHERE id=?", report.applicationId().toString());
        else jdbc.update("UPDATE approval_application SET status='IN_APPROVAL' WHERE id=?", report.applicationId().toString());
        assertThat(read(report, "/" + id, "alice", 200).path("canConfirm").asBoolean()).isFalse();
        send(report, "/" + id + "/confirm", "alice", confirmation(), UUID.randomUUID().toString(), 409);
        assertThat(CATALOG_CALLS.get()).isEqualTo(reads); assertThat(transitions(id)).isEqualTo(3);
        send(report, "/" + id + "/dismiss", "alice", Map.of("expectedRunVersion", 3), UUID.randomUUID().toString(), 200);
    }

    @Test void optionalProjectRemainsAbsentWhenOnlyItineraryAndAllocationAreConfirmed() throws Exception {
        var report = report(); UUID id = queue(report); MODEL_MODE.set("no-project"); worker.poll();
        var detail = read(report, "/" + id, "alice", 200);
        assertThat(detail.at("/suggestion/lines/0/allocations/0/projectCode").asText("")).isEmpty();
        send(report, "/" + id + "/confirm", "alice", Map.of("expectedRunVersion", 3, "applicationVersion", 1, "financialVersion", 1,
                "selected", List.of(Map.of("proposalId", "line1", "parts", List.of("ITINERARY", "ALLOCATION")))), UUID.randomUUID().toString(), 200);
        assertThat(read(report, "/" + id, "alice", 200).at("/review/selected/0/parts").toString()).contains("ALLOCATION").doesNotContain("CATEGORY");
    }

    @ParameterizedTest @ValueSource(strings = {"extra", "http-error"})
    void rejectedModelRepliesBecomeStableFailuresWithoutImplicitRetry(String mode) throws Exception {
        var report = report(); UUID id = queue(report); MODEL_MODE.set(mode); int before = MODEL_CALLS.get(); worker.poll(); worker.poll();
        assertThat(runs.find("demo", id).orElseThrow().state().failure()).isEqualTo(mode.equals("extra") ? AssistRun.Failure.INVALID_MODEL_OUTPUT : AssistRun.Failure.MODEL_UNAVAILABLE);
        assertThat(MODEL_CALLS.get()).isEqualTo(before + 1);
    }

    @Test void concurrentClaimsAndLeaseRecoveryCannotResendOrAcceptALateModelResult() throws Exception {
        var report = report(); UUID id = queue(report); var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { start.await(); return service.claim("demo", id, Instant.now()); });
            var second = pool.submit(() -> { start.await(); return service.claim("demo", id, Instant.now()); }); start.countDown();
            assertThat(java.util.stream.Stream.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)).filter(java.util.Objects::nonNull).count()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
        var original = runs.find("demo", id).orElseThrow(); int before = MODEL_CALLS.get();
        assertThat(service.claim("demo", id, original.state().leaseUntil())).isNull();
        service.finish("demo", id, new ExpenseDraftSuggestion("synthetic", "v1", ExpenseDraftAssistRun.PROMPT_VERSION, List.of()), null, original.state().leaseUntil().plusSeconds(1));
        worker.poll(); assertThat(MODEL_CALLS.get()).isEqualTo(before);
        assertThat(runs.find("demo", id).orElseThrow().state().failure()).isEqualTo(AssistRun.Failure.MODEL_TIMEOUT); assertThat(transitions(id)).isEqualTo(3);
    }

    @Test void failedConfirmationTraceRollsBackIdempotencyAndSameKeyMayRetryOnce() throws Exception {
        var report = report(); UUID id = queue(report); worker.poll(); String key = UUID.randomUUID().toString();
        jdbc.execute("ALTER TABLE agent_expense_draft_transition ADD CONSTRAINT ck_fixture_draft_review CHECK(run_id<>'" + id + "' OR status<>'CONFIRMED')");
        try {
            String bearer = token("alice");
            assertThatThrownBy(() -> mvc.perform(post(path(report) + "/" + id + "/confirm").header("Authorization", bearer)
                    .header("Idempotency-Key", key).contentType("application/json").content(json.write(confirmation()))))
                    .isInstanceOf(jakarta.servlet.ServletException.class).hasCauseInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(runs.find("demo", id).orElseThrow().state().status()).isEqualTo(ExpenseDraftAssistRun.Status.COMPLETED); assertThat(transitions(id)).isEqualTo(3);
        } finally { jdbc.execute("ALTER TABLE agent_expense_draft_transition DROP CONSTRAINT ck_fixture_draft_review"); }
        var success = send(report, "/" + id + "/confirm", "alice", confirmation(), key, 200);
        assertThat(send(report, "/" + id + "/confirm", "alice", confirmation(), key, 200)).isEqualTo(success); assertThat(transitions(id)).isEqualTo(4);
    }

    @Test void concurrentConfirmationsKeepOneReviewAndOneConflict() throws Exception {
        var report = report(); UUID id = queue(report); worker.poll(); var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        String bearer = token("alice");
        try {
            java.util.concurrent.Callable<Integer> confirm = () -> {
                start.await(); return mvc.perform(post(path(report) + "/" + id + "/confirm").header("Authorization", bearer)
                        .header("Idempotency-Key", UUID.randomUUID()).contentType("application/json").content(json.write(confirmation()))).andReturn().getResponse().getStatus();
            };
            var first = pool.submit(confirm); var second = pool.submit(confirm); start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        } finally { pool.shutdownNow(); }
        assertThat(transitions(id)).isEqualTo(4);
    }

    @Test void requestShapesAndQueriesCannotSmuggleFinancialFieldsOrDirectoryOverrides() throws Exception {
        var report = report(); int reads = CATALOG_CALLS.get();
        for (String variant : List.of("top", "leg", "catalog")) {
            var body = input(); ObjectNode target = variant.equals("leg") ? (ObjectNode) body.at("/itinerary/0") : variant.equals("catalog") ? (ObjectNode) body.path("catalog") : body;
            target.put("claimedGross", "100"); send(report, "/preview", "alice", body, UUID.randomUUID().toString(), 400);
        }
        for (String query : List.of("?page=-1", "?page=01", "?page=0&page=1", "?pageSize=51", "?tenantId=demo")) read(report, query, "alice", 400);
        assertThat(CATALOG_CALLS.get()).isEqualTo(reads);
        var body = input(); ((ObjectNode) body.path("catalog")).putArray("costCenterCodes").add("FOREIGN");
        send(report, "/preview", "alice", body, UUID.randomUUID().toString(), 403);
    }

    private ExpenseReport report() {
        UUID id = UUID.randomUUID(); var content = new ExpenseContent(ENTITY.get(), ExpenseContent.Type.TRAVEL, "不可发送费用标题", List.of(), List.of());
        var application = Application.draftBusiness(UUID.randomUUID(), "demo", "SYNTHETIC-" + UUID.randomUUID(), "fixture", 1, "alice", content.title(),
                Map.of("account", "不可发送账户"), null, null, null, new BusinessReference(BusinessReference.Type.EXPENSE, id));
        applications.save(application); var report = ExpenseReport.draft(id, "demo", application.id(), "alice", content); reports.create(report, "alice"); return report;
    }
    private ObjectNode input() {
        return json.read(json.write(Map.of("applicationVersion", 1, "financialVersion", 1, "brief", "请整理行程住宿，全部归研发调研项目",
                "itinerary", List.of(Map.of("id", 1, "startsOn", "2026-10-01", "endsOn", "2026-10-03", "cityCode", "SH", "purpose", "现场调研")),
                "catalog", Map.of("categoryCodes", List.of("HOTEL"), "costCenterCodes", List.of("IT"), "projectCodes", List.of("P01")))), ObjectNode.class);
    }
    private JsonNode preview(ExpenseReport report) throws Exception { return send(report, "/preview", "alice", input(), UUID.randomUUID().toString(), 200); }
    private ObjectNode generation(JsonNode preview) {
        return json.read(json.write(Map.of("input", input(), "validUntil", preview.at("/input/validUntil").asText(),
                "targetDigest", preview.path("targetDigest").asText(), "consentDigest", preview.path("consentDigest").asText())), ObjectNode.class);
    }
    private UUID queue(ExpenseReport report) throws Exception { return remember(send(report, "", "alice", generation(preview(report)), UUID.randomUUID().toString(), 202)); }
    private UUID remember(JsonNode response) { UUID id = UUID.fromString(response.path("id").asText()); queued.add(id); return id; }
    private static Map<String, Object> confirmation() {
        return Map.of("expectedRunVersion", 3, "applicationVersion", 1, "financialVersion", 1,
                "selected", List.of(Map.of("proposalId", "line1", "parts", List.of("ITINERARY", "CATEGORY"))), "comment", "分摊另行核对");
    }
    private JsonNode read(ExpenseReport report, String suffix, String user, int status) throws Exception {
        var response = mvc.perform(get(path(report) + suffix).header("Authorization", token(user))).andReturn().getResponse();
        var body = json.read(response.getContentAsString(StandardCharsets.UTF_8), JsonNode.class); observe("GET", suffix, null, response.getStatus(), body);
        assertThat(response.getStatus()).as(body.toString()).isEqualTo(status); if (status == 200) assertThat(response.getHeader("Cache-Control")).contains("no-store"); return body;
    }
    private JsonNode send(ExpenseReport report, String suffix, String user, Object input, String key, int status) throws Exception {
        var response = mvc.perform(post(path(report) + suffix).header("Authorization", token(user)).header("Idempotency-Key", key)
                .contentType("application/json").content(json.write(input))).andReturn().getResponse();
        var body = json.read(response.getContentAsString(StandardCharsets.UTF_8), JsonNode.class); observe("POST", suffix, input, response.getStatus(), body);
        assertThat(response.getStatus()).as(body.toString()).isEqualTo(status);
        if (status < 300 && suffix.equals("/preview")) assertThat(response.getHeader("Cache-Control")).contains("no-store"); return body;
    }
    private void observe(String method, String suffix, Object request, int status, JsonNode response) throws Exception {
        var observation = new java.util.LinkedHashMap<String, Object>(); observation.put("method", method); observation.put("suffix", suffix);
        observation.put("request", request); observation.put("status", status); observation.put("response", response);
        Files.writeString(OBSERVATION_DIRECTORY.resolve(String.format("%04d.json", OBSERVATIONS.incrementAndGet())), json.write(observation));
    }
    private String token(String user) { return user.endsWith("-draft-token") ? "Bearer " + user : "Bearer " + auth.login("demo", user, "demo").token(); }
    private static String path(ExpenseReport report) { return "/api/v1/expense-reports/" + report.id() + "/draft-assists"; }
    private long transitions(UUID id) { return jdbc.queryForObject("SELECT COUNT(*) FROM agent_expense_draft_transition WHERE tenant_id='demo' AND run_id=?", Long.class, id.toString()); }
    private static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(HTTP_THREADS);
            com.sun.net.httpserver.HttpHandler catalog = exchange -> {
                CATALOG_CALLS.incrementAndGet(); var request = wire.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonNode.class);
                var result = new java.util.LinkedHashMap<String, Object>(); result.put("contractVersion", 1); result.put("tenantId", request.path("tenantId").asText()); result.put("requestId", request.path("requestId").asText());
                if (CATALOG_MODE.get().equals("denied")) { result.put("outcome", "REJECTED"); result.put("reason", "EMPLOYEE_UNAVAILABLE"); }
                else { result.put("outcome", "SUCCESS"); result.put("data", catalog(request.at("/data/employeeId").asText())); }
                var during = DURING_CATALOG.getAndSet(null); if (during != null) during.run(); respond(exchange, 200, result);
            };
            server.createContext("/finance/catalog", catalog); server.createContext("/changed-finance/catalog", catalog);
            server.createContext("/model", exchange -> {
                MODEL_CALLS.incrementAndGet(); String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8); LAST_MODEL.set(body);
                var request = wire.read(body, JsonNode.class); var input = wire.read(request.at("/messages/1/content").asText(), JsonNode.class);
                var evidence = java.util.stream.StreamSupport.stream(input.path("sources").spliterator(), false)
                        .filter(value -> Set.of("expense:itinerary[1]", ExpenseDraftAssistInput.CATALOG).contains(value.at("/reference/sourceId").asText()))
                        .map(value -> value.path("reference")).toList();
                var line = wire.read(wire.write(Map.of("id", "line1", "itineraryId", 1, "categoryCode", "HOTEL", "unit", "NIGHT", "description", "现场调研住宿",
                        "allocations", List.of(Map.of("costCenter", "IT", "projectCode", "P01", "percent", "100")), "evidence", evidence)), ObjectNode.class);
                if (MODEL_MODE.get().equals("extra")) line.put("claimedGross", "100");
                if (MODEL_MODE.get().equals("no-project")) ((ObjectNode) line.at("/allocations/0")).putNull("projectCode");
                var output = Map.of("lines", MODEL_MODE.get().equals("empty") ? List.of() : List.of(line));
                respond(exchange, MODEL_MODE.get().equals("http-error") ? 503 : 200, Map.of("model", "synthetic-draft-v1", "choices",
                        List.of(Map.of("finish_reason", "stop", "message", Map.of("role", "assistant", "content", wire.write(output))))));
            }); server.start(); return server;
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
    private static FinanceCatalog catalog(String employee) {
        return new FinanceCatalog(employee, CATALOG_MODE.get().equals("v2") ? "catalog-v2" : "catalog-v1", Instant.now().plusSeconds(600),
                List.of(new FinanceCatalog.LegalEntity(ENTITY.get(), "合成法人", "CNY", false, "v1", "UTC"), new FinanceCatalog.LegalEntity(FOREIGN_ENTITY, "其他法人", "CNY", false, "v1", "UTC")),
                List.of(new FinanceCatalog.Category("HOTEL", "住宿", List.of(ExpenseLine.Unit.NIGHT)), new FinanceCatalog.Category("OFFICE", "办公", List.of(ExpenseLine.Unit.ITEM))),
                List.of(new FinanceCatalog.CostCenter(ENTITY.get(), "IT", CATALOG_MODE.get().equals("renamed") ? "目录已更名" : "研发"), new FinanceCatalog.CostCenter(FOREIGN_ENTITY, "FOREIGN", "其他法人归属")),
                List.of(new FinanceCatalog.Project(ENTITY.get(), "P01", "调研项目")), List.of(new FinanceCatalog.City("SH", "上海")));
    }
    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, Object value) throws java.io.IOException {
        byte[] body = wire.write(value).getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length); try { exchange.getResponseBody().write(body); } finally { exchange.close(); }
    }
}
