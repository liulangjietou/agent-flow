package io.agentflow.integration;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 使用真实审批、事务、SQL 和 HTTP 权限边界验证 outbox 与人工重试。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=${AGENTFLOW_WEBHOOK_TEST_URL:jdbc:h2:mem:webhooks;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000}",
        "spring.datasource.username=${AGENTFLOW_WEBHOOK_TEST_USER:sa}",
        "spring.datasource.password=${AGENTFLOW_WEBHOOK_TEST_PASSWORD:}",
        "spring.datasource.driver-class-name=${AGENTFLOW_WEBHOOK_TEST_DRIVER:org.h2.Driver}",
        "agentflow.auth.demo-enabled=true", "agentflow.webhooks.worker-enabled=false",
        "agentflow.webhooks.targets.erp.tenant-id=demo", "agentflow.webhooks.targets.erp.label=演示 ERP",
        "agentflow.webhooks.targets.erp.url=https://example.invalid/webhook", "agentflow.webhooks.targets.erp.enabled=true",
        "agentflow.webhooks.targets.erp.signing-secret=whsec_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@AutoConfigureMockMvc
class WebhookIntegrationTest {
    private static final String ROOT = "/api/v1/integrations/webhooks";
    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebhookTargets targets;
    @Autowired PlatformTransactionManager transactions;
    @Autowired RuntimeService runtime;
    @Autowired TaskService tasks;
    @Autowired io.agentflow.definition.DefinitionApplicationService definitions;
    @MockitoSpyBean AuthService auth;
    @MockitoSpyBean JdbcWebhookStore store;

    @Test
    void requestTraceSurvivesStoredOutboxReloadAndRetryWithoutChangingTheOriginalEvent() throws Exception {
        String app = draft();
        var response = mvc.perform(post("/api/v1/applications/" + app + "/submit")
                        .header("Authorization", token("alice")).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}"))
                .andExpect(status().isOk()).andReturn().getResponse();
        String trace = response.getHeader("X-Trace-Id");
        assertThat(trace).isNotBlank();
        var id = deliveries(app).get(0);
        var stored = store.get("demo", id);
        assertThat(json.read(stored.body(), JsonNode.class).path("traceId").asText()).isEqualTo(trace);
        assertThat(json.read(jdbc.queryForObject("SELECT payload_json FROM audit_event WHERE application_id=? AND action='SUBMIT'", String.class, app),
                JsonNode.class).path("traceId").asText()).isEqualTo(trace);
        doReturn(List.of(id)).when(store).due(any());
        var received = new ArrayList<Map<String, String>>();
        WebhookTransport transport = (target, event, body) -> {
            assertThat(event).isEqualTo(stored.eventId());
            assertThat(body).isEqualTo(stored.body());
            received.add(org.slf4j.MDC.getCopyOfContextMap());
            return DeliveryProgress.Outcome.http(received.size() == 1 ? 503 : 204);
        };
        new WebhookWorker(store, targets, transport, json).poll();
        jdbc.update("UPDATE webhook_delivery SET next_attempt_at=CURRENT_TIMESTAMP WHERE id=?", id.toString());
        // 新 worker 从 SQL 记录恢复，不继承请求线程的内存。
        new WebhookWorker(store, targets, transport, json).poll();
        assertThat(received).hasSize(2).allSatisfy(context -> {
            assertThat(context).containsEntry("traceId", trace).containsEntry("tenantId", "demo");
        });
        assertThat(org.slf4j.MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void submittedAndApprovedEventsShareAuditIdentityAndExcludePrivateBodies() throws Exception {
        String app = draft();
        assertThat(deliveries(app)).isEmpty();
        String key = UUID.randomUUID().toString();
        write("/api/v1/applications/" + app + "/submit", "alice", Map.of("expectedVersion", 1), key, 200);
        write("/api/v1/applications/" + app + "/submit", "alice", Map.of("expectedVersion", 1), key, 200);
        assertThat(deliveries(app)).hasSize(1);
        var first = store.get("demo", deliveries(app).get(0));
        assertThat(first.eventType()).isEqualTo("ApplicationSubmitted");
        assertThat(first.eventId()).isEqualTo(jdbc.queryForObject("SELECT event_id FROM audit_event WHERE application_id=? AND action='SUBMIT'", String.class, app));
        var task = tasks.createTaskQuery().processVariableValueEquals("applicationId", app).singleResult();
        write("/api/v1/tasks/" + task.getId() + "/actions", "finance", Map.of("expectedVersion", 2, "action", "APPROVE", "comment", "private-comment"), UUID.randomUUID().toString(), 200);
        assertThat(jdbc.queryForList("SELECT event_type FROM webhook_delivery WHERE application_id=?", String.class, app))
                .containsExactlyInAnyOrder("ApplicationSubmitted", "TaskActionAccepted", "ApplicationApproved");
        for (UUID id : deliveries(app)) {
            var body = store.get("demo", id).body();
            assertThat(body).doesNotContain("private-form", "private-comment", "signing", "amount");
            var event = json.read(body, JsonNode.class);
            assertThat(event.path("payloadVersion").asInt()).isEqualTo(1);
            assertThat(event.path("payload").path("applicationId").asText()).isEqualTo(app);
            assertThat(event.path("traceId").asText()).isNotBlank();
        }
    }

    @Test
    void allCountersignOnlyEmitsConclusionAfterEveryApprovalAndRejectEndsTheRound() throws Exception {
        for (boolean reject : List.of(false, true)) {
            String processKey = "webhook-all-" + UUID.randomUUID();
            var graph = new io.agentflow.definition.DefinitionModels.Graph(List.of(
                    new io.agentflow.definition.DefinitionModels.Node("start", "开始", io.agentflow.definition.DefinitionModels.NodeType.START, Map.of()),
                    new io.agentflow.definition.DefinitionModels.Node("approval", "会签", io.agentflow.definition.DefinitionModels.NodeType.USER_TASK,
                            Map.of("assigneeRule", "role:FINANCE", "approvalMode", "ALL")),
                    new io.agentflow.definition.DefinitionModels.Node("end", "结束", io.agentflow.definition.DefinitionModels.NodeType.END, Map.of())),
                    List.of(new io.agentflow.definition.DefinitionModels.Edge("a", "start", "approval", ""), new io.agentflow.definition.DefinitionModels.Edge("b", "approval", "end", "")));
            var definition = definitions.create("demo", processKey, "Webhook 会签验证", graph);
            definitions.publish(new Actor("demo", "admin", Set.of("ADMIN")), definition.id(), definition.revision(), "验证会签事件");
            String app = json.read(write("/api/v1/applications", "alice", Map.of("businessNo", "WH-" + UUID.randomUUID(), "processKey", processKey,
                    "definitionVersion", 1, "title", "会签投递", "payload", Map.of("amount", 100)), UUID.randomUUID().toString(), 201), JsonNode.class).path("id").asText();
            write("/api/v1/applications/" + app + "/submit", "alice", Map.of("expectedVersion", 1), UUID.randomUUID().toString(), 200);
            var first = tasks.createTaskQuery().processVariableValueEquals("applicationId", app).taskAssignee("admin").singleResult();
            write("/api/v1/tasks/" + first.getId() + "/actions", "admin", Map.of("expectedVersion", 2, "action", reject ? "REJECT" : "APPROVE", "comment", "处理会签"), UUID.randomUUID().toString(), 200);
            if (!reject) {
                assertThat(jdbc.queryForList("SELECT event_type FROM webhook_delivery WHERE application_id=?", String.class, app)).containsExactlyInAnyOrder("ApplicationSubmitted", "TaskActionAccepted");
                var second = tasks.createTaskQuery().processVariableValueEquals("applicationId", app).taskAssignee("finance").singleResult();
                write("/api/v1/tasks/" + second.getId() + "/actions", "finance", Map.of("expectedVersion", 3, "action", "APPROVE"), UUID.randomUUID().toString(), 200);
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM webhook_delivery WHERE application_id=? AND event_type=?", Integer.class, app, reject ? "ApplicationRejected" : "ApplicationApproved")).isEqualTo(1);
            assertThat(tasks.createTaskQuery().processVariableValueEquals("applicationId", app).count()).isZero();
        }
    }

    @Test
    void enqueueFailureRollsBackApplicationFlowableAuditAndEveryQueuedEvent() throws Exception {
        String app = draft();
        doAnswer(call -> { call.callRealMethod(); throw new DomainException("DEPENDENCY_UNAVAILABLE", "Outbox unavailable"); })
                .when(org.springframework.test.util.AopTestUtils.<JdbcWebhookStore>getUltimateTargetObject(store)).append(anyString(), any(), anyString(), anyString(), any(), anyLong(), anyString(), any());
        write("/api/v1/applications/" + app + "/submit", "alice", Map.of("expectedVersion", 1), UUID.randomUUID().toString(), 503);
        assertThat(deliveries(app)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM approval_application WHERE id=?", String.class, app)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='SUBMIT'", Integer.class, app)).isZero();
        assertThat(runtime.createProcessInstanceQuery().variableValueEquals("applicationId", app).count()).isZero();
    }

    @Test
    void oneConcurrentLeaseWinsAndLateResponseCannotOverwriteRecoveredAttempt() throws Exception {
        UUID id = enqueue("demo"); Instant now = Instant.now().plusSeconds(1);
        var executor = Executors.newFixedThreadPool(2); var start = new CyclicBarrier(2);
        try {
            Callable<JdbcWebhookStore.Delivery> claim = () -> { start.await(); return store.claim(id, now); };
            var one = executor.submit(claim); var two = executor.submit(claim);
            var claims = Arrays.asList(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
            assertThat(claims.stream().filter(Objects::nonNull)).hasSize(1);
            var first = claims.stream().filter(Objects::nonNull).findFirst().orElseThrow();
            assertThat(store.attempts(id)).hasSize(1);
            var recovered = store.claim(id, now.plusSeconds(31));
            assertThat(store.finish(first, DeliveryProgress.Outcome.http(204), now.plusSeconds(32))).isFalse();
            assertThat(store.finish(recovered, DeliveryProgress.Outcome.http(204), now.plusSeconds(32))).isTrue();
            assertThat(store.attempts(id)).extracting(JdbcWebhookStore.Attempt::result).containsExactly("DELIVERED", "OUTCOME_UNKNOWN");
            assertThat(store.get("demo", id).body()).isEqualTo(first.body());
        } finally { executor.shutdownNow(); }
    }

    @Test
    void receiverDeadlineSurvivesDatabaseReloadAndPreventsEarlyClaim() throws Exception {
        UUID id = enqueue("demo");
        Instant now = Instant.now().plusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant deadline = now.plusSeconds(120);
        var claimed = store.claim(id, now);
        assertThat(store.finish(claimed, DeliveryProgress.Outcome.http(429, deadline), now)).isTrue();
        var waiting = store.get("demo", id);
        assertThat(waiting.progress().nextAttemptAt()).isEqualTo(deadline);
        assertThat(store.due(deadline.minusMillis(1))).doesNotContain(id);
        assertThat(store.claim(id, deadline.minusMillis(1))).isNull();
        assertThat(store.attempts(id)).hasSize(1);
        var detail = json.read(mvc.perform(get(ROOT + "/deliveries/" + id).header("Authorization", token("admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), JsonNode.class);
        assertThat(detail.path("delivery").path("nextAttemptAt").asText()).isEqualTo(deadline.toString());
        var retried = store.claim(id, deadline);
        assertThat(retried).isNotNull();
        assertThat(retried.eventId()).isEqualTo(claimed.eventId());
        assertThat(retried.body()).isEqualTo(claimed.body());
        assertThat(store.finish(retried, DeliveryProgress.Outcome.http(204), deadline)).isTrue();
        assertThat(store.attempts(id)).extracting(JdbcWebhookStore.Attempt::httpStatus).containsExactly(204, 429);
    }

    @Test
    void workerSendsOutsideTransactionAndRetriesStableEventWhileApprovalIsUnchanged() {
        UUID id = enqueue("demo"); var calls = new ArrayList<String>();
        // 本测试仅领取指定记录，避免与其他测试的待发送数据混合。
        doReturn(List.of(id)).when(store).due(any());
        var worker = new WebhookWorker(store, targets, (target, event, body) -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            calls.add(event + body); return DeliveryProgress.Outcome.http(calls.size() == 1 ? 503 : 204);
        }, json);
        worker.poll(); var failed = store.get("demo", id);
        assertThat(failed.progress().status()).isEqualTo(DeliveryProgress.Status.RETRY_WAIT);
        jdbc.update("UPDATE webhook_delivery SET next_attempt_at=CURRENT_TIMESTAMP WHERE id=?", id.toString());
        worker.poll();
        assertThat(store.get("demo", id).progress().status()).isEqualTo(DeliveryProgress.Status.DELIVERED);
        assertThat(calls).hasSize(2); assertThat(calls.get(0)).isEqualTo(calls.get(1));
    }

    @Test
    void changedDestinationFailsWithoutContactingNewAddressOrAllowingManualRetry() throws Exception {
        UUID id = enqueue("demo"); doReturn(List.of(id)).when(store).due(any());
        jdbc.update("UPDATE webhook_delivery SET destination_digest=? WHERE id=?", "0".repeat(64), id.toString());
        WebhookTransport transport = mock(WebhookTransport.class);
        new WebhookWorker(store, targets, transport, json).poll(); verifyNoInteractions(transport);
        assertThat(store.get("demo", id).progress().errorCode()).isEqualTo("TARGET_CHANGED");
        write(ROOT + "/deliveries/" + id + "/retry", "admin", Map.of("expectedVersion", 3), UUID.randomUUID().toString(), 409);
    }

    @Test
    void manualRetryIsVersionedIdempotentTenantScopedAndNeverResetsAttemptHistory() throws Exception {
        UUID id = enqueue("demo");
        var claim = store.claim(id, Instant.now().plusSeconds(1)); store.finish(claim, DeliveryProgress.Outcome.http(400), Instant.now());
        String path = ROOT + "/deliveries/" + id + "/retry", key = UUID.randomUUID().toString();
        String response = write(path, "admin", Map.of("expectedVersion", 3), key, 200);
        assertThat(write(path, "admin", Map.of("expectedVersion", 3), key, 200)).isEqualTo(response);
        assertThat(store.retries(id)).hasSize(1); assertThat(store.attempts(id)).hasSize(1);
        assertThat(store.get("demo", id).progress().attempts()).isEqualTo(1);
        write(path, "admin", Map.of("expectedVersion", 3), UUID.randomUUID().toString(), 409);
        write(path, "alice", Map.of("expectedVersion", 4), UUID.randomUUID().toString(), 403);
        mvc.perform(post(path).header("Authorization", token("admin")).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":4}"))
                .andExpect(status().isBadRequest());
        doReturn(new Actor("other", "admin", Set.of("ADMIN"))).when(auth).authenticate("other-token");
        mvc.perform(get(ROOT + "/deliveries/" + id).header("Authorization", "Bearer other-token")).andExpect(status().isNotFound());
        mvc.perform(post(path).header("Authorization", "Bearer other-token").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":4}")).andExpect(status().isNotFound());
        String detail = mvc.perform(get(ROOT + "/deliveries/" + id).header("Authorization", token("admin")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString();
        assertThat(detail).contains("requestedBy", "admin").doesNotContain("private-body", "example.invalid", "whsec_", "payload_json", "leaseToken");
    }

    @Test
    void pagesEqualTimeRecordsAndRejectsChangedScopeOrUnsafeFilters() throws Exception {
        String app = UUID.randomUUID().toString(); Instant time = Instant.parse("2020-01-01T00:00:00Z");
        for (int i = 0; i < 3; i++) enqueue("demo", UUID.fromString(app), time);
        var page = read("?applicationId=" + app + "&limit=1"); var ids = new HashSet<String>();
        String cursor = page.path("nextCursor").asText(); ids.add(page.path("items").get(0).path("id").asText());
        while (!cursor.isEmpty()) {
            page = read("?applicationId=" + app + "&limit=1&cursor=" + cursor);
            assertThat(ids.add(page.path("items").get(0).path("id").asText())).isTrue(); cursor = page.path("nextCursor").asText("");
        }
        assertThat(ids).hasSize(3);
        cursor = read("?applicationId=" + app + "&limit=1").path("nextCursor").asText();
        for (String query : List.of("?tenantId=other", "?limit=101", "?status=UNKNOWN", "?target=bad!", "?applicationId=1-1-1-1-1", "?cursor=" + cursor))
            mvc.perform(get(ROOT + "/deliveries" + query).header("Authorization", token("admin"))).andExpect(status().isBadRequest());
        doReturn(new Actor("demo", "designer", Set.of("PROCESS_ADMIN"))).when(auth).authenticate("designer-token");
        for (String suffix : List.of("", "/deliveries", "/deliveries/" + ids.iterator().next())) {
            mvc.perform(get(ROOT + suffix)).andExpect(status().isUnauthorized());
            mvc.perform(get(ROOT + suffix).header("Authorization", "Bearer designer-token")).andExpect(status().isForbidden());
        }
        String targetList = mvc.perform(get(ROOT).header("Authorization", token("admin"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(targetList).contains("erp").doesNotContain("url", "secret", "whsec_");
    }

    @Test
    void overviewCountsAllMatchingDeliveriesAcrossPagesAndKeepsHistoricalTargets() throws Exception {
        UUID app = UUID.randomUUID(); Instant time = Instant.now().minusSeconds(60);
        String historic = "historic-" + UUID.randomUUID().toString().substring(0, 8);
        for (int index = 0; index < 36; index++) {
            UUID id = enqueueOverview("demo", "erp", app, UUID.randomUUID().toString(), time);
            if (index % 5 == 0) continue;
            var claim = store.claim(id, time.plusSeconds(1));
            if (index % 5 != 1) store.finish(claim, DeliveryProgress.Outcome.http(index % 5 == 2 ? 503 : index % 5 == 3 ? 204 : 400), time.plusSeconds(2));
        }
        String event = UUID.randomUUID().toString();
        enqueueOverview("demo", historic, app, event, time);
        enqueueOverview("other", "erp", app, event, time);
        var before = jdbc.queryForList("SELECT * FROM webhook_delivery WHERE application_id=? ORDER BY tenant_id,id", app.toString());
        assertThat(read("?applicationId=" + app).path("items").size()).isEqualTo(30);
        var all = overview("?applicationId=" + app, token("admin"));
        assertCounts(all, 37, 9, 7, 7, 7, 7);
        assertCounts(overview("?applicationId=" + app + "&target=erp", token("admin")), 36, 8, 7, 7, 7, 7);
        assertCounts(overview("?applicationId=" + app + "&target=" + historic, token("admin")), 1, 1, 0, 0, 0, 0);
        assertThat(Instant.parse(all.path("queriedAt").asText())).isBeforeOrEqualTo(Instant.now());
        assertThat(all.toString()).doesNotContain("private-body", "example.invalid", "whsec_", "leaseToken", "eventId");
        assertThat(jdbc.queryForList("SELECT * FROM webhook_delivery WHERE application_id=? ORDER BY tenant_id,id", app.toString())).isEqualTo(before);
    }

    @Test
    void overviewCountsDestinationDeliveriesAndLatestStateInsteadOfEventsOrAttempts() throws Exception {
        UUID app = UUID.randomUUID(); Instant time = Instant.now().minusSeconds(60);
        String event = UUID.randomUUID().toString();
        UUID first = enqueueOverview("demo", "erp", app, event, time);
        UUID second = enqueueOverview("demo", "archived", app, event, time);
        assertCounts(overview("?applicationId=" + app, token("admin")), 2, 2, 0, 0, 0, 0);
        var claim = store.claim(first, time.plusSeconds(1));
        assertCounts(overview("?applicationId=" + app, token("admin")), 2, 1, 1, 0, 0, 0);
        store.finish(claim, DeliveryProgress.Outcome.http(503), time.plusSeconds(2));
        assertCounts(overview("?applicationId=" + app, token("admin")), 2, 1, 0, 1, 0, 0);
        claim = store.claim(first, time.plusSeconds(8));
        store.finish(claim, DeliveryProgress.Outcome.http(400), time.plusSeconds(9));
        var secondClaim = store.claim(second, time.plusSeconds(1));
        store.finish(secondClaim, DeliveryProgress.Outcome.http(204), time.plusSeconds(2));
        assertCounts(overview("?applicationId=" + app, token("admin")), 2, 0, 0, 0, 1, 1);
        write(ROOT + "/deliveries/" + first + "/retry", "admin", Map.of("expectedVersion", 5), UUID.randomUUID().toString(), 200);
        assertCounts(overview("?applicationId=" + app, token("admin")), 2, 1, 0, 0, 1, 0);
        assertThat(store.attempts(first)).hasSize(2); assertThat(store.retries(first)).hasSize(1);
    }

    @Test
    void overviewEnforcesTenantAdministratorAndScopeFiltersWithoutInventingEmptyCounts() throws Exception {
        UUID app = UUID.randomUUID(); Instant time = Instant.now().minusSeconds(60);
        enqueueOverview("demo", "erp", app, UUID.randomUUID().toString(), time);
        UUID other = enqueueOverview("other", "erp", app, UUID.randomUUID().toString(), time);
        store.finish(store.claim(other, time.plusSeconds(1)), DeliveryProgress.Outcome.http(400), time.plusSeconds(2));
        doReturn(new Actor("other", "admin", Set.of("ADMIN"))).when(auth).authenticate("overview-other");
        doReturn(new Actor("demo", "designer", Set.of("PROCESS_ADMIN"))).when(auth).authenticate("overview-designer");
        assertCounts(overview("?applicationId=" + app, token("admin")), 1, 1, 0, 0, 0, 0);
        assertCounts(overview("?applicationId=" + app, "Bearer overview-other"), 1, 0, 0, 0, 0, 1);
        assertCounts(overview("?applicationId=" + UUID.randomUUID(), token("admin")), 0, 0, 0, 0, 0, 0);
        mvc.perform(get(ROOT + "/overview")).andExpect(status().isUnauthorized());
        for (String actor : List.of(token("alice"), "Bearer overview-designer"))
            mvc.perform(get(ROOT + "/overview").header("Authorization", actor)).andExpect(status().isForbidden());
        for (String query : List.of("?tenantId=other", "?status=FAILED", "?limit=1", "?cursor=abc", "?target=bad!", "?applicationId=1-1-1-1-1"))
            mvc.perform(get(ROOT + "/overview" + query).header("Authorization", token("admin")))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_WEBHOOK_QUERY"));
    }

    private UUID enqueueOverview(String tenant, String targetId, UUID app, String eventId, Instant time) {
        var configured = targets.find("demo", "erp").orElseThrow();
        var target = new WebhookTargets.Destination(targetId, tenant, "概况测试", configured.uri(), configured.key(), true, configured.digest());
        new TransactionTemplate(transactions).executeWithoutResult(status -> store.append(tenant, target, eventId, "ApplicationSubmitted", app, 2, "private-body", time));
        return UUID.fromString(jdbc.queryForObject("SELECT id FROM webhook_delivery WHERE tenant_id=? AND target_id=? AND event_id=?", String.class, tenant, targetId, eventId));
    }
    private JsonNode overview(String query, String authorization) throws Exception {
        return json.read(mvc.perform(get(ROOT + "/overview" + query).header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString(), JsonNode.class);
    }
    private void assertCounts(JsonNode value, long total, long pending, long inFlight, long retryWait, long delivered, long failed) {
        assertThat(value.path("total").asLong(-1)).isEqualTo(total);
        assertThat(value.path("pending").asLong(-1)).isEqualTo(pending);
        assertThat(value.path("inFlight").asLong(-1)).isEqualTo(inFlight);
        assertThat(value.path("retryWait").asLong(-1)).isEqualTo(retryWait);
        assertThat(value.path("delivered").asLong(-1)).isEqualTo(delivered);
        assertThat(value.path("failed").asLong(-1)).isEqualTo(failed);
    }

    private UUID enqueue(String tenant) { return enqueue(tenant, UUID.randomUUID(), Instant.now()); }
    private UUID enqueue(String tenant, UUID app, Instant time) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> store.append(tenant, targets.find("demo", "erp").orElseThrow(), UUID.randomUUID().toString(), "ApplicationSubmitted", app, 2, "private-body", time));
        return deliveries(app.toString()).get(0);
    }
    private List<UUID> deliveries(String app) { return jdbc.queryForList("SELECT id FROM webhook_delivery WHERE application_id=? ORDER BY occurred_at,id", String.class, app).stream().map(UUID::fromString).toList(); }
    private String draft() throws Exception {
        return json.read(write("/api/v1/applications", "alice", Map.of("businessNo", "WH-" + UUID.randomUUID(), "processKey", "expense-reimbursement", "definitionVersion", 1,
                "title", "Webhook 验证", "payload", Map.of("amount", 6000, "privateValue", "private-form")), UUID.randomUUID().toString(), 201), JsonNode.class).path("id").asText();
    }
    private String write(String path, String user, Object body, String key, int expected) throws Exception {
        return mvc.perform(post(path).header("Authorization", token(user)).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json.write(body)))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }
    private JsonNode read(String query) throws Exception { return json.read(mvc.perform(get(ROOT + "/deliveries" + query).header("Authorization", token("admin"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), JsonNode.class); }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
}
