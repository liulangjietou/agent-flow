package io.agentflow.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.agentflow.auth.AuthService;
import io.agentflow.common.JsonUtil;
import io.agentflow.observability.DiagnosticContext;
import org.slf4j.MDC;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实文本协议到草稿保存的边界：选择性外发、原版本人工确认、原子审计和不重复执行。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${AGENTFLOW_DRAFT_TEST_URL:jdbc:h2:mem:draft-assist;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${AGENTFLOW_DRAFT_TEST_USER:sa}",
        "spring.datasource.password=${AGENTFLOW_DRAFT_TEST_PASSWORD:}",
        "spring.datasource.driver-class-name=${AGENTFLOW_DRAFT_TEST_DRIVER:org.h2.Driver}",
        "agentflow.auth.demo-enabled=true", "agentflow.assist.enabled=true", "agentflow.assist.worker-enabled=false",
        "agentflow.assist.model=fixture-model"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class DraftAssistIntegrationTest {
    private static final JsonUtil FIXTURE_JSON = new JsonUtil(new com.fasterxml.jackson.databind.ObjectMapper());
    private static final AtomicReference<String> MODE = new AtomicReference<>("success");
    private static final AtomicReference<String> LAST_TRACE = new AtomicReference<>();
    private String lastRequestTrace;
    private static final AtomicReference<String> LAST_BODY = new AtomicReference<>("");
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final java.util.concurrent.ExecutorService HTTP_THREADS = Executors.newFixedThreadPool(2);
    private static final HttpServer MODEL = model();
    private final List<UUID> queued = new ArrayList<>();
    @Autowired MockMvc mvc;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean AuthService auth;
    @Autowired JsonUtil json;
    @Autowired JdbcTemplate jdbc;
    @Autowired DraftAssistWorker worker;
    @Autowired DraftAssistService execution;
    @Autowired JdbcDraftAssistRunRepository runs;
    @Autowired DraftAssistModelPort model;
    @Autowired AssistConfiguration configuration;

    @DynamicPropertySource
    static void endpoint(DynamicPropertyRegistry properties) {
        properties.add("agentflow.assist.endpoint", () -> "http://127.0.0.1:" + MODEL.getAddress().getPort() + "/v1/chat/completions");
        properties.add("agentflow.assist.api-key", () -> "fixture-key");
        properties.add("agentflow.assist.timeout-seconds", () -> 1);
    }
    @BeforeEach void reset() { MODE.set("success"); }
    @AfterAll static void stop() { MODEL.stop(0); HTTP_THREADS.shutdownNow(); }
    @AfterEach void settleFixtures() {
        for (UUID id : queued) {
            execution.claim("demo", id, Instant.now());
            execution.finish("demo", id, null, AssistRun.Failure.MODEL_UNAVAILABLE, Instant.now());
        }
    }

    @Test
    void tracePersistsThroughQueueAndReachesModel() throws Exception {
        String app = application();
        UUID id = queue(app);
        String expectedTrace = lastRequestTrace;
        assertThat(DiagnosticContext.validTrace(expectedTrace)).isTrue();
        assertThat(MDC.get(DiagnosticContext.TRACE_ID)).isNull();
        assertThat(jdbc.queryForMap("SELECT * FROM agent_draft_assist_run WHERE tenant_id='demo' AND id=?", id.toString())
                .get("TRACE_ID")).isEqualTo(expectedTrace);
        LAST_TRACE.set(null);
        worker.poll();
        assertThat(LAST_TRACE.get()).isEqualTo(expectedTrace);
        assertThat(jdbc.queryForMap("SELECT * FROM agent_draft_assist_run WHERE tenant_id='demo' AND id=?", id.toString())
                .get("TRACE_ID")).isEqualTo(expectedTrace);
        assertThat(MDC.get(DiagnosticContext.TRACE_ID)).isNull();
        assertThat(MDC.get(DiagnosticContext.TENANT_ID)).isNull();
    }

    @Test
    void inputExcludesSensitiveFieldsAttachmentsAndRestrictedTablesAndQueueIsIdempotent() throws Exception {
        String app = application(); int before = REQUESTS.get();
        var input = read(get(path(app) + "/input"), "alice", 200);
        assertThat(input.path("sources").toString()).contains("application:title", "form:reason", "form:other")
                .doesNotContain("不可外发账户", "不可外发列", "form:items", "form:proof");
        assertThat(input.path("targetSchema").toString()).contains("reason", "amount", "lines")
                .doesNotContain("secret", "proof", "items", "nodeAccess");
        var body = generation(List.of("form:reason")); String key = UUID.randomUUID().toString();
        var receipt = send(post(path(app)), "alice", body, key, 202);
        queued.add(UUID.fromString(receipt.path("id").asText()));
        assertThat(send(post(path(app)), "alice", body, key, 202)).isEqualTo(receipt);
        assertThat(receipt.toString()).doesNotContain("sources", "suggestion", "本次", "payload");
        assertThat(REQUESTS.get()).isEqualTo(before);
        assertThat(applicationState(app).path("version").asLong()).isEqualTo(1);
        send(post(path(app)), "alice", body, 409);
        send(post(path(application())), "alice", generation(List.of("form:secret")), 403);
        send(post(path(application())), "alice", generation(List.of("form:items")), 403);
        send(post(path(application())), "alice", generation(List.of("form:reason", "form:reason")), 422);
    }

    @Test
    void onlyExplicitSourcesReachModelAndPartialEditedAdoptionUsesOriginalDraftAudit() throws Exception {
        String app = application(); UUID id = queue(app); var original = applicationState(app);
        worker.poll(); var detail = detail(app, id);
        assertThat(detail.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(detail.path("canAdopt").asBoolean()).isTrue();
        assertThat(LAST_BODY.get()).contains("为项目采购设备", "本次生成要求", "targetSchema")
                .doesNotContain("不可外发账户", "不可外发列", "必须保留原值", "原申请标题", app, "tenantId", "requestedBy");
        assertThat(applicationState(app)).isEqualTo(original);
        var review = adoption(List.of(Map.of("targetId", "form:reason", "value", "人工核对后的说明")));
        String key = UUID.randomUUID().toString();
        var receipt = send(post(path(app) + "/" + id + "/review"), "alice", review, key, 200);
        assertThat(receipt.path("savedApplicationVersion").asLong()).isEqualTo(2);
        assertThat(send(post(path(app) + "/" + id + "/review"), "alice", review, key, 200)).isEqualTo(receipt);
        var saved = applicationState(app);
        assertThat(saved.path("status").asText()).isEqualTo("DRAFT");
        assertThat(saved.path("roundNo")).isEqualTo(original.path("roundNo"));
        assertThat(saved.hasNonNull("processInstanceId")).isFalse();
        assertThat(saved.path("version").asLong()).isEqualTo(2);
        assertThat(saved.path("title").asText()).isEqualTo("原申请标题");
        assertThat(saved.at("/payload/reason").asText()).isEqualTo("人工核对后的说明");
        assertThat(saved.at("/payload/other")).isEqualTo(original.at("/payload/other"));
        assertThat(saved.at("/payload/items")).isEqualTo(original.at("/payload/items"));
        var after = detail(app, id);
        assertThat(after.path("suggestion")).isEqualTo(detail.path("suggestion"));
        assertThat(after.at("/review/actor").asText()).isEqualTo("alice");
        assertThat(after.at("/review/selected/0/value").asText()).isEqualTo("人工核对后的说明");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='REVISE'", Long.class, app)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_draft_assist_transition WHERE run_id=?", Long.class, id.toString())).isEqualTo(4);
    }

    @Test
    void readsAndWritesRequireOriginalApplicantAndQueriesAreBounded() throws Exception {
        String app = application(); UUID id = queue(app); worker.poll();
        for (String user : List.of("admin", "manager", "bob")) {
            read(get(path(app) + "/input"), user, 403);
            read(get(path(app)), user, 403);
            read(get(path(app) + "/" + id), user, 403);
            send(post(path(app) + "/" + id + "/review"), user, Map.of("expectedRunVersion", 3, "action", "DISMISS"), 403);
        }
        read(get(path(application()) + "/" + id), "alice", 404);
        String foreign = UUID.randomUUID().toString();
        org.mockito.Mockito.doReturn(new io.agentflow.common.Actor("foreign", "alice", java.util.Set.of("EMPLOYEE"))).when(auth).authenticate(foreign);
        mvc.perform(get(path(app) + "/" + id).header("Authorization", "Bearer " + foreign)).andExpect(status().isNotFound());
        for (String query : List.of("?page=-1", "?page=0&page=1", "?pageSize=51", "?userId=admin", "?page=01")) read(get(path(app) + query), "alice", 400);
        read(get(path(app) + "/input?userId=alice"), "alice", 400);
        read(get(path(app) + "/" + id + "?tenantId=demo"), "alice", 400);
        var page = read(get(path(app) + "?page=0&pageSize=1"), "alice", 200);
        assertThat(page.path("total").asLong()).isEqualTo(1);
        assertThat(page.path("items").toString()).doesNotContain("suggestion", "sources", "payload");
        var response = mvc.perform(get(path(app) + "/" + id).header("Authorization", token("alice"))).andReturn().getResponse();
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
    }

    @Test
    void changedDraftCannotBeSentOrOverwrittenAndOldSuggestionCanBeDismissed() throws Exception {
        String beforeSend = application(); UUID unsent = queue(beforeSend); int requests = REQUESTS.get();
        revise(beforeSend, "已修改草稿"); worker.poll();
        assertThat(REQUESTS.get()).isEqualTo(requests);
        assertThat(detail(beforeSend, unsent).path("failure").asText()).isEqualTo("INPUT_UNAVAILABLE");
        String app = application(); UUID id = queue(app); worker.poll(); revise(app, "人工更新后的草稿");
        assertThat(detail(app, id).path("canAdopt").asBoolean()).isFalse();
        var body = new LinkedHashMap<>(adoption(List.of(Map.of("targetId", "form:reason", "value", "旧建议"))));
        body.put("expectedApplicationVersion", 2);
        assertThat(send(post(path(app) + "/" + id + "/review"), "alice", body, 409).path("code").asText()).isEqualTo("AGENT_INPUT_CHANGED");
        var original = applicationState(app);
        send(post(path(app) + "/" + id + "/review"), "alice", Map.of("expectedRunVersion", 3, "action", "DISMISS"), 200);
        assertThat(applicationState(app)).isEqualTo(original);
    }

    @Test
    void submittedDraftCannotGenerateAndWithdrawnDraftNeedsANewVersion() throws Exception {
        String app = application(); UUID id = queue(app); int requests = REQUESTS.get();
        send(post("/api/v1/applications/" + app + "/submit"), "alice", Map.of("expectedVersion", 1), 200);
        worker.poll(); assertThat(REQUESTS.get()).isEqualTo(requests);
        assertThat(detail(app, id).path("failure").asText()).isEqualTo("INPUT_UNAVAILABLE");
        read(get(path(app) + "/input"), "alice", 422);
        send(post("/api/v1/applications/" + app + "/withdraw"), "alice", Map.of("expectedVersion", 2), 200);
        var input = read(get(path(app) + "/input"), "alice", 200);
        var body = new LinkedHashMap<>(generation(List.of())); body.put("expectedVersion", input.path("applicationVersion").asLong());
        var receipt = send(post(path(app)), "alice", body, 202); UUID next = UUID.fromString(receipt.path("id").asText()); queued.add(next);
        worker.poll(); assertThat(detail(app, next).path("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void targetChangeBeforeQueueClaimOrSendingNeverForwardsAuthorizedContentElsewhere() throws Exception {
        String app = application();
        var body = new LinkedHashMap<>(generation(List.of())); body.put("targetDigest", "a".repeat(64));
        send(post(path(app)), "alice", body, 409);
        UUID id = queue(app); int requests = REQUESTS.get(); String originalModel = configuration.getModel();
        try {
            configuration.setModel("changed-model"); worker.poll();
            assertThat(detail(app, id).path("failure").asText()).isEqualTo("MODEL_UNAVAILABLE");
        } finally { configuration.setModel(originalModel); }
        UUID second = queue(app); var context = execution.claim("demo", second, Instant.now());
        try {
            configuration.setModel("changed-after-claim");
            assertThatThrownBy(() -> model.generate(context)).isInstanceOf(AssistModelPort.ModelFailure.class);
        } finally { configuration.setModel(originalModel); }
        assertThat(REQUESTS.get()).isEqualTo(requests);
    }

    @Test
    void disablingModelAfterClaimReturnsStableUnavailableWithoutSending() throws Exception {
        String app = application(); UUID id = queue(app); int requests = REQUESTS.get();
        var context = execution.claim("demo", id, Instant.now());
        try {
            configuration.setEnabled(false);
            assertThatThrownBy(() -> model.generate(context)).isInstanceOf(AssistModelPort.ModelFailure.class)
                    .extracting(cause -> ((AssistModelPort.ModelFailure) cause).failure()).isEqualTo(AssistRun.Failure.MODEL_UNAVAILABLE);
        } finally { configuration.setEnabled(true); }
        assertThat(REQUESTS.get()).isEqualTo(requests);
    }

    @Test
    void concurrentAdoptionAndManualEditCannotOverwriteEachOther() throws Exception {
        String app = application(); UUID id = queue(app); worker.poll();
        var executor = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        String bearer = token("alice");
        try {
            var first = executor.submit(() -> {
                start.await();
                return mvc.perform(post(path(app) + "/" + id + "/review").header("Authorization", bearer).header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(json.write(adoption(List.of(Map.of("targetId", "form:reason", "value", "已采纳的说明")))))).andReturn().getResponse().getStatus();
            });
            var second = executor.submit(() -> {
                start.await();
                return mvc.perform(put("/api/v1/applications/" + app).header("Authorization", bearer).header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("expectedVersion", 1, "title", "手工修改", "payload", Map.of("reason", "手工说明"))))).andReturn().getResponse().getStatus();
            });
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        } finally { executor.shutdownNow(); }
        assertThat(applicationState(app).path("version").asLong()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='REVISE'", Long.class, app)).isEqualTo(1);
        if (detail(app, id).path("status").asText().equals("ADOPTED")) assertThat(applicationState(app).at("/payload/reason").asText()).isEqualTo("已采纳的说明");
        else assertThat(applicationState(app).at("/payload/reason").asText()).isEqualTo("手工说明");
    }

    @Test
    void legacyApplicationWithoutVersionedFormHasNoDraftGenerationEntry() throws Exception {
        String app = application(false);
        assertThat(read(get(path(app) + "/input"), "alice", 422).path("code").asText()).isEqualTo("AGENT_DRAFT_UNSUPPORTED");
        assertThat(send(post(path(app)), "alice", generation(List.of()), 422).path("code").asText()).isEqualTo("AGENT_DRAFT_UNSUPPORTED");
        assertThat(runs.page("demo", UUID.fromString(app), 0, 20).total()).isZero();
    }

    @Test
    void unproposedTargetsAndInvalidValuesCannotBypassManualConfirmation() throws Exception {
        String app = application(); UUID id = queue(app); worker.poll();
        for (var selected : List.of(List.of(Map.of("targetId", "form:other", "value", "擅自修改")),
                List.of(Map.of("targetId", "form:reason", "value", 42)), List.of(Map.of("targetId", "application:title", "value", "")),
                List.of(Map.of("targetId", "form:reason", "value", "重复"), Map.of("targetId", "form:reason", "value", "重复")))) {
            send(post(path(app) + "/" + id + "/review"), "alice", adoption(selected), 422);
        }
        send(post(path(app) + "/" + id + "/review"), "alice", Map.of("expectedRunVersion", 3, "action", "ADOPT", "selected", List.of()), 422);
        assertThat(applicationState(app).path("version").asLong()).isEqualTo(1);
        assertThat(detail(app, id).path("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void failedRunPersistenceRollsBackDraftAuditAndIdempotencyReceipt() throws Exception {
        String app = application(); UUID id = queue(app); worker.poll(); var original = applicationState(app);
        var review = adoption(List.of(Map.of("targetId", "form:reason", "value", "不可部分保存"))); String key = UUID.randomUUID().toString();
        jdbc.execute("ALTER TABLE agent_draft_assist_run ADD CONSTRAINT ck_fixture_no_adopt CHECK(id<>'" + id + "' OR status<>'ADOPTED')");
        try {
            assertThatThrownBy(() -> send(post(path(app) + "/" + id + "/review"), "alice", review, key, 500))
                    .isInstanceOf(jakarta.servlet.ServletException.class).hasCauseInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
        finally { jdbc.execute("ALTER TABLE agent_draft_assist_run DROP CONSTRAINT ck_fixture_no_adopt"); }
        assertThat(applicationState(app)).isEqualTo(original);
        assertThat(detail(app, id).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='REVISE'", Long.class, app)).isZero();
        send(post(path(app) + "/" + id + "/review"), "alice", review, key, 200);
        assertThat(applicationState(app).path("version").asLong()).isEqualTo(2);
    }

    @Test
    void concurrentClaimsHaveOneOwnerAndExpiredLeaseNeverResends() throws Exception {
        String app = application(); UUID id = queue(app); int requests = REQUESTS.get();
        var executor = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            java.util.concurrent.Callable<DraftAssistRun.Context> claim = () -> { start.await(); return execution.claim("demo", id, Instant.now()); };
            var first = executor.submit(claim); var second = executor.submit(claim); start.countDown();
            var owners = java.util.stream.Stream.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)).filter(java.util.Objects::nonNull).toList();
            assertThat(owners).hasSize(1);
        } finally { executor.shutdownNow(); }
        assertThat(execution.claim("demo", id, Instant.now().plusSeconds(180))).isNull();
        execution.finish("demo", id, null, AssistRun.Failure.MODEL_UNAVAILABLE, Instant.now().plusSeconds(181)); worker.poll();
        assertThat(detail(app, id).path("failure").asText()).isEqualTo("MODEL_TIMEOUT");
        assertThat(REQUESTS.get()).isEqualTo(requests);
        UUID retry = queue(app); worker.poll(); assertThat(detail(app, retry).path("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void malformedForeignTruncatedAndOversizedOutputsOnlyLeaveStableFailure() throws Exception {
        for (String mode : List.of("foreign-source", "unknown-target", "sensitive-target", "wrong-type", "duplicate-target", "extra-field", "extra-output", "trailing-json", "tool", "refusal", "truncated", "oversized", "timeout")) {
            MODE.set(mode); String app = application(); UUID id = queue(app); worker.poll(); var detail = detail(app, id);
            assertThat(detail.path("status").asText()).as(mode).isEqualTo("FAILED");
            assertThat(detail.path("failure").asText()).as(mode).isEqualTo(mode.equals("timeout") ? "MODEL_TIMEOUT" : "INVALID_MODEL_OUTPUT");
            assertThat(detail.hasNonNull("suggestion")).isFalse();
            assertThat(applicationState(app).path("version").asLong()).isEqualTo(1);
        }
    }

    private UUID queue(String app) throws Exception {
        UUID id = UUID.fromString(send(post(path(app)), "alice", generation(List.of("form:reason")), 202).path("id").asText()); queued.add(id); return id;
    }
    private Map<String, Object> generation(List<String> sources) {
        return Map.of("expectedVersion", 1, "targetDigest", configuration.targetDigest(DraftAssistRun.PROMPT_VERSION),
                "brief", "请根据所选内容整理采购用途，事实不足时省略", "sourceIds", sources);
    }
    private static Map<String, Object> adoption(Object selected) {
        return Map.of("expectedRunVersion", 3, "expectedApplicationVersion", 1, "action", "ADOPT", "selected", selected, "comment", "已人工核对");
    }
    private void revise(String app, String title) throws Exception {
        send(put("/api/v1/applications/" + app), "alice", Map.of("expectedVersion", 1, "title", title, "payload", Map.of("reason", "新说明")), 200);
    }
    private JsonNode applicationState(String app) throws Exception { return read(get("/api/v1/applications/" + app), "alice", 200); }
    private JsonNode detail(String app, UUID id) throws Exception { return read(get(path(app) + "/" + id), "alice", 200); }
    private String application() throws Exception {
        return application(true);
    }
    private String application(boolean withSchema) throws Exception {
        String key = "draft-" + UUID.randomUUID();
        var graph = Map.of("nodes", List.of(node("start", "START", Map.of()), node("review", "USER_TASK", Map.of("assigneeRule", "user:manager")), node("end", "END", Map.of())),
                "edges", List.of(edge("e1", "start", "review"), edge("e2", "review", "end")));
        var fields = List.of(field("reason", "公开说明", "TEXT"), field("other", "其他说明", "TEXT"), field("amount", "金额", "NUMBER"),
                Map.of("key", "secret", "label", "敏感账户", "type", "TEXT", "required", false, "sensitive", true), field("proof", "附件", "ATTACHMENT"),
                Map.of("key", "items", "label", "含敏感列的明细", "type", "TABLE", "required", false, "maxRows", 10, "columns", List.of(field("name", "名称", "TEXT"),
                        Map.of("key", "account", "label", "账户", "type", "TEXT", "required", false, "sensitive", true))),
                Map.of("key", "lines", "label", "普通明细", "type", "TABLE", "required", false, "maxRows", 10, "columns", List.of(field("name", "名称", "TEXT"))));
        var definitionRequest = new LinkedHashMap<String, Object>(Map.of("key", key, "name", "草稿建议验收", "graph", graph));
        if (withSchema) definitionRequest.put("formSchema", Map.of("schemaVersion", 2, "fields", fields));
        var definition = send(post("/api/v1/process-definitions"), "admin", definitionRequest, 200);
        send(post("/api/v1/process-definitions/" + definition.path("id").asText() + "/publish?expectedRevision=" + definition.path("revision").asLong()), "admin", Map.of("changeNote", "合成验收"), 200);
        return send(post("/api/v1/applications"), "alice", Map.of("businessNo", UUID.randomUUID().toString(), "processKey", key, "definitionVersion", 1,
                "title", "原申请标题", "payload", Map.of("reason", "为项目采购设备", "other", "必须保留原值", "secret", "不可外发账户", "items", List.of(Map.of("name", "设备", "account", "不可外发列")))), 201).path("id").asText();
    }
    private static Map<String, Object> field(String key, String label, String type) { return Map.of("key", key, "label", label, "type", type, "required", false); }
    private static Map<String, Object> node(String id, String type, Map<String, String> properties) { return Map.of("id", id, "name", id, "type", type, "properties", properties); }
    private static Map<String, String> edge(String id, String source, String target) { return Map.of("id", id, "source", source, "target", target, "condition", ""); }
    private static String path(String app) { return "/api/v1/applications/" + app + "/draft-assist-runs"; }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private JsonNode read(MockHttpServletRequestBuilder request, String user, int expected) throws Exception {
        var response = mvc.perform(request.header("Authorization", token(user))).andExpect(status().is(expected))
                .andReturn().getResponse();
        lastRequestTrace = response.getHeader(DiagnosticContext.HEADER);
        return json.read(response.getContentAsString(), JsonNode.class);
    }
    private JsonNode send(MockHttpServletRequestBuilder request, String user, Object body, int expected) throws Exception { return send(request, user, body, UUID.randomUUID().toString(), expected); }
    private JsonNode send(MockHttpServletRequestBuilder request, String user, Object body, String key, int expected) throws Exception {
        return read(request.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json.write(body)), user, expected);
    }

    private static HttpServer model() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(HTTP_THREADS);
            server.createContext("/v1/chat/completions", exchange -> {
                REQUESTS.incrementAndGet(); String mode = MODE.get();
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8); LAST_BODY.set(body); LAST_TRACE.set(exchange.getRequestHeaders().getFirst(DiagnosticContext.HEADER));
                if (mode.equals("timeout")) {
                    try { Thread.sleep(1500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
                var request = FIXTURE_JSON.read(body, JsonNode.class);
                var input = FIXTURE_JSON.read(request.at("/messages/1/content").asText(), JsonNode.class);
                Object evidence = mode.equals("foreign-source") ? Map.of("sourceId", "form:secret", "contentDigest", "b".repeat(64)) : input.at("/sources/0/reference");
                String target = switch (mode) { case "unknown-target" -> "form:undeclared"; case "sensitive-target" -> "form:secret"; default -> "form:reason"; };
                var proposal = new LinkedHashMap<String, Object>(Map.of("targetId", target, "value", mode.equals("wrong-type") ? 42 : "模型生成的采购用途", "evidence", List.of(evidence)));
                if (mode.equals("extra-field")) proposal.put("action", "APPROVE");
                Object second = mode.equals("duplicate-target") ? proposal : Map.of("targetId", "application:title", "value", "模型建议标题", "evidence", List.of(evidence));
                var output = new LinkedHashMap<String, Object>(Map.of("proposals", List.of(proposal, second)));
                if (mode.equals("extra-output")) output.put("submit", true);
                var message = new LinkedHashMap<String, Object>(Map.of("role", "assistant", "content", FIXTURE_JSON.write(output) + (mode.equals("trailing-json") ? "{}" : "")));
                if (mode.equals("tool")) message.put("tool_calls", List.of(Map.of("id", "bad", "type", "function")));
                if (mode.equals("refusal")) message.put("refusal", "refused");
                String response = mode.equals("oversized") ? "x".repeat(300_000) : FIXTURE_JSON.write(Map.of("model", "fixture-model-2026", "choices", List.of(
                        Map.of("finish_reason", mode.equals("truncated") ? "length" : "stop", "message", message))));
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().add("Content-Type", "application/json");
                try { exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); } finally { exchange.close(); }
            });
            server.start(); return server;
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
}
