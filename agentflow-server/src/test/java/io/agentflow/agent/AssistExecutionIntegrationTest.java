package io.agentflow.agent;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.common.JsonUtil;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 从真实待办发起摘要，验证选择性输入、异步运行与人工复核不会改变审批。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${AGENTFLOW_EXECUTION_TEST_URL:jdbc:h2:mem:agent-execution;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${AGENTFLOW_EXECUTION_TEST_USER:sa}",
        "spring.datasource.password=${AGENTFLOW_EXECUTION_TEST_PASSWORD:}",
        "spring.datasource.driver-class-name=${AGENTFLOW_EXECUTION_TEST_DRIVER:org.h2.Driver}",
        "agentflow.auth.demo-enabled=true", "agentflow.assist.enabled=true", "agentflow.assist.worker-enabled=false",
        "agentflow.assist.endpoint=http://127.0.0.1:18219/v1/chat/completions", "agentflow.assist.model=fixture-model"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class AssistExecutionIntegrationTest {
    private static final JsonUtil FIXTURE_JSON = new JsonUtil(new com.fasterxml.jackson.databind.ObjectMapper());
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final AtomicReference<String> LAST_BODY = new AtomicReference<>("");
    private static final AtomicReference<String> MODE = new AtomicReference<>("success");
    private static final java.util.concurrent.ExecutorService HTTP_THREADS = Executors.newFixedThreadPool(2);
    private static final HttpServer MODEL = model();
    @DynamicPropertySource
    static void endpoint(DynamicPropertyRegistry properties) {
        properties.add("agentflow.assist.endpoint", () -> "http://127.0.0.1:" + MODEL.getAddress().getPort() + "/v1/chat/completions");
        properties.add("agentflow.assist.api-key", () -> "fixture-key");
        properties.add("agentflow.assist.timeout-seconds", () -> 1);
    }
    @AfterAll static void stop() { MODEL.stop(0); HTTP_THREADS.shutdownNow(); }
    @BeforeEach void reset() { MODE.set("success"); }

    private static HttpServer model() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(HTTP_THREADS);
            server.createContext("/v1/chat/completions", exchange -> {
                REQUESTS.incrementAndGet(); String mode = MODE.get();
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8); LAST_BODY.set(body);
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer fixture-key");
                if (mode.equals("timeout")) {
                    try { Thread.sleep(1500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
                var request = FIXTURE_JSON.read(body, JsonNode.class);
                var input = FIXTURE_JSON.read(request.at("/messages/1/content").asText(), JsonNode.class);
                var reference = input.at("/sources/0/reference");
                Object evidence = mode.equals("foreign-source") ? Map.of("sourceId", "form:secret", "contentDigest", "b".repeat(64)) : reference;
                var claim = new java.util.LinkedHashMap<String, Object>(Map.of("text", mode.equals("numeric-text") ? 123 : "合成模型摘要：采购设备，需人工核对。", "evidence", List.of(evidence)));
                if (mode.equals("extra-claim-field")) claim.put("action", "APPROVE");
                var output = Map.of("claims", List.of(claim), "confidence", 0.75);
                var message = new java.util.LinkedHashMap<String,Object>(Map.of("role", "assistant", "content", FIXTURE_JSON.write(output)));
                if (mode.equals("tool")) message.put("tool_calls", List.of(Map.of("id", "bad", "type", "function")));
                String response = mode.equals("oversized") ? "x".repeat(300_000)
                        : FIXTURE_JSON.write(Map.of("model", "fixture-model-2026", "choices", List.of(Map.of("finish_reason", "stop", "message", message))));
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                try { exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); }
                finally { exchange.close(); }
            });
            server.start(); return server;
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;
    @Autowired TaskService tasks;
    @Autowired AssistWorker worker;
    @Autowired AssistExecutionService execution;
    @Autowired AssistRunRepository runs;
    @Autowired JdbcAssistJobRepository jobs;
    @Autowired AssistConfiguration configuration;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void queuesOnlyExplicitReadableSourcesWithoutCallingModelOrApproving() throws Exception {
        String app = application(); int requestsBefore = REQUESTS.get();
        String task = tasks.createTaskQuery().processVariableValueEquals("applicationId", app).singleResult().getId();
        var input = read(get(path(app) + "/input").param("taskId", task), "manager", 200);
        assertThat(input.path("sources").toString()).contains("form:reason").doesNotContain("form:secret", "form:account", "form:proof");
        var queued = write(post(path(app)), "manager", Map.of("taskId", task, "expectedVersion", 2, "targetDigest", configuration.targetDigest(), "sourceIds", List.of("form:reason")), 202);
        assertThat(queued.path("status").asText()).isEqualTo("QUEUED");
        assertThat(REQUESTS.get()).isEqualTo(requestsBefore);
        worker.poll();
        assertThat(tasks.createTaskQuery().taskId(task).count()).isEqualTo(1);
        assertThat(read(get("/api/v1/applications/" + app), "alice", 200).path("version").asLong()).isEqualTo(2);
    }

    @Test
    void realHttpKeepsSelectedEvidenceAndHumanReviewSeparateFromApproval() throws Exception {
        String app = application(), task = task(app);
        var queued = queue(app, task); String id = queued.path("id").asText();
        worker.poll();
        var detail = read(get(path(app) + "/" + id), "manager", 200);
        assertThat(detail.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(detail.path("suggestion").path("modelVersion").asText()).isEqualTo("fixture-model-2026");
        assertThat(LAST_BODY.get()).contains("form:reason", "为项目采购设备").doesNotContain("不可发送秘密", "不可发送账户", "采购说明");
        var before = read(get("/api/v1/applications/" + app), "alice", 200);
        var body = Map.of("taskId", task, "expectedVersion", 2, "expectedRunVersion", 3, "action", "ADOPT", "acceptedText", "人工核对后的修订摘要", "comment", "已核对原文");
        String key = UUID.randomUUID().toString();
        var accepted = read(post(path(app) + "/" + id + "/review").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json.write(body)), "manager", 200);
        assertThat(accepted.path("status").asText()).isEqualTo("ADOPTED");
        assertThat(read(post(path(app) + "/" + id + "/review").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json.write(body)), "manager", 200)).isEqualTo(accepted);
        var reviewed = read(get(path(app) + "/" + id), "manager", 200);
        assertThat(reviewed.at("/review/acceptedText").asText()).isEqualTo("人工核对后的修订摘要");
        assertThat(reviewed.path("suggestion")).isEqualTo(detail.path("suggestion"));
        assertThat(read(get("/api/v1/applications/" + app), "alice", 200)).isEqualTo(before);
        assertThat(tasks.createTaskQuery().taskId(task).count()).isEqualTo(1);
    }

    @Test
    void deniesHiddenSourcesAndNonCurrentReviewersAndMakesQueueIdempotent() throws Exception {
        String app = application(), task = task(app);
        read(get(path(app) + "/input").param("taskId", task), "admin", 403);
        read(get(path(app) + "/input").param("taskId", task), "alice", 403);
        write(post(path(app)), "manager", Map.of("taskId", task, "expectedVersion", 2, "targetDigest", configuration.targetDigest(), "sourceIds", List.of("form:secret")), 403);
        write(post(path(app)), "manager", Map.of("taskId", task, "expectedVersion", 1, "targetDigest", configuration.targetDigest(), "sourceIds", List.of("form:reason")), 409);
        var body = Map.of("taskId", task, "expectedVersion", 2, "targetDigest", configuration.targetDigest(), "sourceIds", List.of("form:reason")); String key = UUID.randomUUID().toString();
        var first = read(post(path(app)).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json.write(body)), "manager", 202);
        assertThat(read(post(path(app)).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json.write(body)), "manager", 202)).isEqualTo(first);
        write(post(path(app)), "manager", body, 409);
        worker.poll();
        write(post(path(app) + "/" + first.path("id").asText() + "/review"), "admin",
                Map.of("taskId", task, "expectedVersion", 2, "expectedRunVersion", 3, "action", "DISMISS"), 403);
    }

    @Test
    void changedTaskIsNotSentAndModelTargetChangesCannotRedirectQueuedInput() throws Exception {
        String app = application(), task = task(app); var run = queue(app, task); int before = REQUESTS.get();
        write(post("/api/v1/tasks/" + task + "/actions"), "manager", Map.of("action", "TRANSFER", "targetUser", "finance", "expectedVersion", 2), 200);
        worker.poll();
        assertThat(REQUESTS.get()).isEqualTo(before);
        assertThat(runs.find("demo", UUID.fromString(run.path("id").asText())).orElseThrow().failure()).isEqualTo(AssistRun.Failure.INPUT_UNAVAILABLE);
        String second = application(); var changedTarget = queue(second, task(second));
        String model = configuration.getModel(); configuration.setModel("changed-target");
        try { worker.poll(); } finally { configuration.setModel(model); }
        assertThat(REQUESTS.get()).isEqualTo(before);
        assertThat(read(get(path(second) + "/" + changedTarget.path("id").asText()), "manager", 200).path("failure").asText()).isEqualTo("MODEL_UNAVAILABLE");
    }

    @Test
    void expiredClaimDoesNotResendAndLateResultCannotOverwriteFailure() throws Exception {
        String app = application(); var queued = queue(app, task(app)); UUID id = UUID.fromString(queued.path("id").asText());
        Instant now = Instant.now(); var job = execution.claim("demo", id, now);
        assertThat(job).isNotNull(); assertThat(execution.claim("demo", id, now)).isNull();
        assertThat(execution.claim("demo", id, now.plusSeconds(180))).isNull();
        execution.finish(job, null, AssistRun.Failure.MODEL_UNAVAILABLE, now.plusSeconds(181));
        assertThat(runs.find("demo", id).orElseThrow().failure()).isEqualTo(AssistRun.Failure.MODEL_TIMEOUT);
        var retry = queue(app, task(app)); assertThat(retry.path("id").asText()).isNotEqualTo(id.toString());
        worker.poll();
        assertThat(read(get(path(app) + "/" + retry.path("id").asText()), "manager", 200).path("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void modelTimeoutOversizeForeignEvidenceAndToolCallsBecomeStableFailures() throws Exception {
        for (String mode : List.of("timeout", "oversized", "foreign-source", "tool", "numeric-text", "extra-claim-field")) {
            MODE.set(mode); String app = application(); var run = queue(app, task(app)); worker.poll();
            var detail = read(get(path(app) + "/" + run.path("id").asText()), "manager", 200);
            assertThat(detail.path("status").asText()).as(mode).isEqualTo("FAILED");
            assertThat(detail.path("failure").asText()).as(mode).isEqualTo(mode.equals("timeout") ? "MODEL_TIMEOUT" : "INVALID_MODEL_OUTPUT");
            assertThat(detail.path("suggestion").isNull()).isTrue();
        }
    }

    @Test
    void finishAfterLeaseDeadlineRecordsTimeoutWithoutWaitingForAnotherWorker() throws Exception {
        String app = application(); var queued = queue(app, task(app)); UUID id = UUID.fromString(queued.path("id").asText());
        Instant now = Instant.now(); var job = execution.claim("demo", id, now);
        var result = new AssistSuggestion("fixture", "fixture-model", AssistConfiguration.PROMPT_VERSION,
                List.of(new AssistSuggestion.Claim("迟到结果", List.of(job.sources().get(0).reference()))), java.math.BigDecimal.ONE);
        execution.finish(job, result, null, now.plusSeconds(180));
        assertThat(runs.find("demo", id).orElseThrow().failure()).isEqualTo(AssistRun.Failure.MODEL_TIMEOUT);
    }

    @Test
    void historicalQueuedRunWithoutSendAuthorizationDoesNotBlockNewExplicitRun() throws Exception {
        String app = application();
        var historical = AssistRun.queue(UUID.randomUUID(), "demo", "alice", Instant.now(),
                new AssistInput(UUID.fromString(app), 2, 1, List.of(new AssistInput.Reference("application:title", "a".repeat(64)))), "legacy-prompt");
        runs.create(historical);
        int before = REQUESTS.get();
        var queued = queue(app, task(app)); worker.poll();
        assertThat(REQUESTS.get()).isEqualTo(before + 1);
        assertThat(read(get(path(app) + "/" + queued.path("id").asText()), "manager", 200).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(runs.find("demo", historical.id()).orElseThrow().status()).isEqualTo(AssistRun.Status.QUEUED);
        assertThat(jobs.find("demo", historical.id())).isEmpty();
    }

    @Test
    void concurrentClaimsHaveOneOwnerAndDisabledOrganizationCannotSend() throws Exception {
        String app = application(); var queued = queue(app, task(app)); UUID id = UUID.fromString(queued.path("id").asText());
        var executor = Executors.newFixedThreadPool(2); var start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<JdbcAssistJobRepository.Job> claim = () -> { start.await(); return execution.claim("demo", id, Instant.now()); };
            var first = executor.submit(claim); var second = executor.submit(claim); start.countDown();
            var owners = java.util.stream.Stream.of(first.get(10, java.util.concurrent.TimeUnit.SECONDS), second.get(10, java.util.concurrent.TimeUnit.SECONDS))
                    .filter(java.util.Objects::nonNull).toList();
            assertThat(owners).hasSize(1);
            execution.finish(owners.get(0), null, AssistRun.Failure.MODEL_UNAVAILABLE, Instant.now());
        } finally { executor.shutdownNow(); }
        String next = application(); UUID blocked = UUID.fromString(queue(next, task(next)).path("id").asText()); int before = REQUESTS.get();
        // 此测试数据库原来使用演示目录；启用本地权威目录模拟即时撤销资格，最后恢复夹具边界。
        jdbc.update("INSERT INTO organization_directory VALUES('demo',1,'admin',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO organization_person VALUES('demo',?,'manager','经理',true,false,1)", UUID.randomUUID().toString());
        try { worker.poll(); } finally {
            jdbc.update("DELETE FROM organization_person WHERE tenant_id='demo'");
            jdbc.update("DELETE FROM organization_directory WHERE tenant_id='demo'");
        }
        assertThat(REQUESTS.get()).isEqualTo(before);
        assertThat(runs.find("demo", blocked).orElseThrow().failure()).isEqualTo(AssistRun.Failure.INPUT_UNAVAILABLE);
    }

    @Test
    void onlyReadableOrdinaryTableColumnsAreSelectableAndResultReadUsesActualSources() throws Exception {
        String app = application(), task = task(app);
        var available = read(get(path(app) + "/input").param("taskId", task), "manager", 200);
        assertThat(available.path("sources").toString()).contains("form:items.name", "form:reviewOnly")
                .doesNotContain("form:items.account", "form:items.masked", "不可发送列", "不可发送脱敏");
        var queued = write(post(path(app)), "manager", Map.of("taskId", task, "expectedVersion", 2, "targetDigest", configuration.targetDigest(),
                "sourceIds", List.of("form:reviewOnly", "form:items.name")), 202);
        worker.poll(); String id = queued.path("id").asText();
        assertThat(LAST_BODY.get()).contains("审批内部依据", "计算机").doesNotContain("不可发送列", "不可发送脱敏", "为项目采购设备");
        read(get(path(app) + "/" + id), "manager", 200);
        read(get(path(app) + "/" + id), "alice", 200);
        read(get(path(app) + "/" + id), "admin", 403);
        read(get(path(app) + "/" + id), "bob", 404);
        assertThat(read(get(path(app)), "admin", 200).toString()).doesNotContain("审批内部依据", "contentDigest");
    }

    @Test
    void staleSuggestionCannotBeAdoptedButCurrentDecisionMakerCanRecordDismissal() throws Exception {
        String app = application(), task = task(app); var run = queue(app, task); worker.poll(); String id = run.path("id").asText();
        write(post("/api/v1/tasks/" + task + "/actions"), "manager", Map.of("action", "TRANSFER", "targetUser", "finance", "expectedVersion", 2), 200);
        assertThat(read(get(path(app) + "/" + id), "finance", 200).path("inputCurrent").asBoolean()).isFalse();
        write(post(path(app) + "/" + id + "/review"), "finance", Map.of("taskId", task, "expectedVersion", 3,
                "expectedRunVersion", 3, "action", "ADOPT", "acceptedText", "旧稿"), 409);
        var dismissed = write(post(path(app) + "/" + id + "/review"), "finance", Map.of("taskId", task, "expectedVersion", 3,
                "expectedRunVersion", 3, "action", "DISMISS", "comment", "旧版本摘要，重新核对"), 200);
        assertThat(dismissed.path("status").asText()).isEqualTo("DISMISSED");
        assertThat(tasks.createTaskQuery().taskId(task).count()).isEqualTo(1);
    }

    @Test
    void previewTargetMustRemainCurrentAndDisabledModelReturnsExplicitAvailability() throws Exception {
        String app = application(), task = task(app); String digest = configuration.targetDigest(); String model = configuration.getModel();
        configuration.setModel("other-model");
        try {
            var rejected = write(post(path(app)), "manager", Map.of("taskId", task, "expectedVersion", 2, "targetDigest", digest, "sourceIds", List.of("form:reason")), 409);
            assertThat(rejected.path("code").asText()).isEqualTo("AGENT_TARGET_CHANGED");
        } finally { configuration.setModel(model); }
        configuration.setEnabled(false);
        try {
            var input = read(get(path(app) + "/input").param("taskId", task), "manager", 200);
            assertThat(input.path("enabled").asBoolean()).isFalse();
            assertThat(input.path("unavailableCode").asText()).isEqualTo("AGENT_MODEL_DISABLED");
            assertThat(input.path("targetDigest").isNull()).isTrue();
            write(post(path(app)), "manager", Map.of("taskId", task, "expectedVersion", 2, "targetDigest", digest, "sourceIds", List.of("form:reason")), 503);
        } finally { configuration.setEnabled(true); }
    }

    private String task(String app) { return tasks.createTaskQuery().processVariableValueEquals("applicationId", app).singleResult().getId(); }
    private JsonNode queue(String app, String task) throws Exception {
        return write(post(path(app)), "manager", Map.of("taskId", task, "expectedVersion", 2, "targetDigest", configuration.targetDigest(), "sourceIds", List.of("form:reason")), 202);
    }

    private String application() throws Exception {
        String key = "assist-" + UUID.randomUUID();
        var graph = Map.of("nodes", List.of(node("start", "START", Map.of()),
                node("review", "USER_TASK", Map.of("assigneeRule", "user:manager")), node("finalCheck", "USER_TASK", Map.of("assigneeRule", "user:finance")), node("end", "END", Map.of())),
                "edges", List.of(edge("e1", "start", "review"), edge("e2", "review", "finalCheck"), edge("e3", "finalCheck", "end")));
        var schema = Map.of("schemaVersion", 2, "fields", List.of(
                Map.of("key", "reason", "label", "公开说明", "type", "TEXT", "required", false),
                Map.of("key", "secret", "label", "内部秘密", "type", "TEXT", "required", false, "nodeAccess", Map.of("review", "HIDDEN")),
                Map.of("key", "account", "label", "敏感账户", "type", "TEXT", "required", false, "sensitive", true),
                Map.of("key", "proof", "label", "材料", "type", "ATTACHMENT", "required", false),
                Map.of("key", "reviewOnly", "label", "审批内部依据", "type", "TEXT", "required", false, "nodeAccess", Map.of("review", "READ_ONLY", "finalCheck", "HIDDEN")),
                Map.of("key", "items", "label", "明细", "type", "TABLE", "required", false, "maxRows", 10, "columns", List.of(
                        Map.of("key", "name", "label", "名称", "type", "TEXT", "required", false),
                        Map.of("key", "account", "label", "账户", "type", "TEXT", "required", false, "sensitive", true),
                        Map.of("key", "masked", "label", "脱敏列", "type", "TEXT", "required", false, "nodeAccess", Map.of("review", "MASKED"))))));
        var definition = write(post("/api/v1/process-definitions"), "admin", Map.of("key", key, "name", "Agent 验收", "graph", graph, "formSchema", schema), 200);
        write(post("/api/v1/process-definitions/" + definition.path("id").asText() + "/publish?expectedRevision=" + definition.path("revision").asLong()),
                "admin", Map.of("changeNote", "合成数据验收"), 200);
        String app = write(post("/api/v1/applications"), "alice", Map.of("businessNo", UUID.randomUUID().toString(), "processKey", key,
                "definitionVersion", 1, "title", "采购说明", "payload", Map.of("reason", "为项目采购设备", "secret", "不可发送秘密", "account", "不可发送账户", "reviewOnly", "审批内部依据", "items", List.of(Map.of("name", "计算机", "account", "不可发送列", "masked", "不可发送脱敏")))), 201).path("id").asText();
        write(post("/api/v1/applications/" + app + "/submit"), "alice", Map.of("expectedVersion", 1), 200);
        return app;
    }
    private static Map<String, Object> node(String id, String type, Map<String, String> properties) { return Map.of("id", id, "name", id, "type", type, "properties", properties); }
    private static Map<String, String> edge(String id, String source, String target) { return Map.of("id", id, "source", source, "target", target, "condition", ""); }
    private static String path(String app) { return "/api/v1/applications/" + app + "/assist-runs"; }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private JsonNode read(MockHttpServletRequestBuilder request, String user, int expected) throws Exception {
        return json.read(mvc.perform(request.header("Authorization", token(user))).andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
    }
    private JsonNode write(MockHttpServletRequestBuilder request, String user, Object body, int expected) throws Exception {
        return read(request.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json.write(body)), user, expected);
    }
}
