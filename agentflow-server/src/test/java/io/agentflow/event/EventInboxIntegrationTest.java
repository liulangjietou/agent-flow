package io.agentflow.event;

import io.agentflow.observability.DiagnosticContext;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import io.agentflow.notification.InboxMessage;
import io.agentflow.notification.InboxRepository;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static io.agentflow.definition.DefinitionModels.*;
import static io.agentflow.event.EventTestRequests.request;
import static io.agentflow.support.MutationRequests.post;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 实际签名 HTTP、原生 Flowable 与持久收件的原子性；同一断言在 H2/PostgreSQL 验证。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "agentflow.events.enabled=true", "agentflow.events.worker-enabled=false",
        "agentflow.timers.enabled=false", "agentflow.webhooks.worker-enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class EventInboxIntegrationTest {
    private static final String PATH = EventIngressVerifier.PATH;
    private static final Actor ADMIN = new Actor("demo", "admin", Set.of("ADMIN"));
    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @MockitoSpyBean AuthService auth;
    @Autowired DefinitionApplicationService definitions;
    @Autowired EventContractService contracts;
    @Autowired EventInboxService service;
    @MockitoSpyBean EventInboxRepository inbox;
    @MockitoSpyBean EventIngressVerifier verifier;
    @MockitoSpyBean InboxRepository notifications;
    @Autowired RuntimeService runtime;
    @Autowired TaskService tasks;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_EVENT_INBOX_URL", "jdbc:h2:mem:event-inbox;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_EVENT_INBOX_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_EVENT_INBOX_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_EVENT_INBOX_PASSWORD", ""));
        registry.add("agentflow.events.sources.erp.tenant-id", () -> "demo");
        registry.add("agentflow.events.sources.erp.source-key", () -> "erp");
        registry.add("agentflow.events.sources.erp.trust-revision", () -> 1);
        registry.add("agentflow.events.sources.erp.enabled", () -> true);
        registry.add("agentflow.events.sources.erp.signing-secrets[0]", () -> EventTestRequests.SECRET);
    }
    @BeforeEach void identities() { doReturn(new Actor("foreign", "admin", Set.of("ADMIN"))).when(auth).authenticate("event-foreign"); }

    @Test void signedReceptionIsDurableBeforeProgressAndRepeatedDeliveryKeepsTheSameReceipt() throws Exception {
        String key = contract(); String app = waiting(key); var signal = signal(app, key); String event = UUID.randomUUID().toString();
        UUID id = receive(signal, event);
        assertThat(state(id).status()).isEqualTo(EventInboxItem.Status.RECEIVED);
        assertThat(tasksFor(app)).isEmpty(); assertThat(application(app).path("version").asLong()).isEqualTo(2);
        process(id, Instant.now()); assertThat(state(id).status()).isEqualTo(EventInboxItem.Status.CONSUMED);
        assertThat(receive(signal, event)).isEqualTo(id); process(id, Instant.now());
        assertThat(tasksFor(app)).hasSize(1); assertThat(application(app).path("version").asLong()).isEqualTo(3);
        assertThat(auditCount(app)).isEqualTo(1); assertThat(history(id)).hasSize(2);
        assertThat(read(PATH + "/" + id, "admin").toString()).doesNotContain("payloadDigest", "signingSecret", "input", "payload");
    }
    @Test void signedEventIdsCannotBeReusedForDifferentBodiesOrTrustRevisions() throws Exception {
        String key = contract(); String app = waiting(key); var signal = signal(app, key); String event = UUID.randomUUID().toString(); UUID id = receive(signal, event);
        var changed = new EventSignal(1, "demo", "erp", "GoodsAccepted", signal.applicationId(), 2, signal.waitId(), key, 1);
        mvc.perform(request(json.write(changed), event, Instant.now())).andExpect(status().isConflict());
        var input = state(id).input();
        assertThatThrownBy(() -> service.accept(new ReceivedEvent(event, input.payloadDigest(), 2, signal), Instant.now()))
                .isInstanceOfSatisfying(DomainException.class, failure -> assertThat(failure.code()).isEqualTo("EVENT_ID_CONFLICT"));
        assertThat(state(id).input()).isEqualTo(input); assertThat(history(id)).hasSize(1);
    }
    @Test void unconfiguredAndUnsignedRequestsCannotWriteOrUseAdministratorRecovery() throws Exception {
        String key = contract(); var signal = signal(waiting(key), key);
        int count = jdbc.queryForObject("SELECT count(*) FROM event_inbox", Integer.class);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(PATH).contentType(MediaType.APPLICATION_JSON).content(json.write(signal)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH + "/" + UUID.randomUUID() + "/retry").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1,\"reason\":\"伪造恢复\"}")).andExpect(status().isUnauthorized());
        mvc.perform(request(json.write(signal).replace("GoodsAccepted", "OtherEvent"), UUID.randomUUID().toString(), Instant.now())).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_inbox", Integer.class)).isEqualTo(count);
    }
    @Test void concurrentReceptionOfOneEventReturnsOnePersistentIdentity() throws Exception {
        String key = contract(); var signal = signal(waiting(key), key); String event = UUID.randomUUID().toString();
        var barrier = new CyclicBarrier(2); var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { barrier.await(); return receive(signal, event); });
            var second = pool.submit(() -> { barrier.await(); return receive(signal, event); });
            assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(second.get(30, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_inbox WHERE tenant_id='demo' AND source_key='erp' AND event_id=?", Integer.class, event)).isEqualTo(1);
    }
    @Test void concurrentConsumersCommitOneNativeAdvanceAndOneConsumedRevision() throws Exception {
        String key = contract(); String app = waiting(key); UUID id = receive(signal(app, key), UUID.randomUUID().toString());
        var barrier = new CyclicBarrier(2); var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { barrier.await(); process(id, Instant.now()); return true; });
            var second = pool.submit(() -> { barrier.await(); process(id, Instant.now()); return true; });
            assertThat(first.get(30, TimeUnit.SECONDS)).isTrue(); assertThat(second.get(30, TimeUnit.SECONDS)).isTrue();
        } finally { pool.shutdownNow(); }
        assertThat(tasksFor(app)).hasSize(1); assertThat(state(id).status()).isEqualTo(EventInboxItem.Status.CONSUMED);
        assertThat(history(id)).hasSize(2); assertThat(auditCount(app)).isEqualTo(1);
    }
    @Test void consumedMarkerFailureRollsBackNativeProgressAndAllBusinessEffects() throws Exception {
        String key = contract(); String app = waiting(key); var signal = signal(app, key); UUID id = receive(signal, UUID.randomUUID().toString());
        doAnswer(call -> {
            call.callRealMethod();
            if (((EventInboxItem) call.getArgument(0)).status() == EventInboxItem.Status.CONSUMED) throw new DomainException("DEPENDENCY_UNAVAILABLE", "Simulated receipt persistence failure");
            return null;
        }).when(inboxTarget()).update(any(EventInboxItem.class));
        try { assertThatThrownBy(() -> process(id, Instant.now())).isInstanceOf(DomainException.class); }
        finally { doCallRealMethod().when(inboxTarget()).update(any(EventInboxItem.class)); }
        assertThat(state(id).version()).isEqualTo(1); assertThat(history(id)).hasSize(1);
        assertThat(signal(app, key).waitId()).isEqualTo(signal.waitId()); assertThat(tasksFor(app)).isEmpty();
        assertThat(application(app).path("version").asLong()).isEqualTo(2); assertThat(auditCount(app)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_inbox WHERE application_id=? AND kind='TASK_PENDING'", Integer.class, app)).isZero();
        process(id, Instant.now()); assertThat(state(id).status()).isEqualTo(EventInboxItem.Status.CONSUMED);
    }
    @Test void notificationFailureRetainsTheOriginalMessageAndLateFailureCannotOverwriteSuccess() throws Exception {
        String key = contract(); String app = waiting(key); UUID id = receive(signal(app, key), UUID.randomUUID().toString());
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (((InboxMessage) call.getArgument(1)).kind() == InboxMessage.Kind.TASK_PENDING) throw new DomainException("DEPENDENCY_UNAVAILABLE", "Simulated notification failure");
            return result;
        }).when(notifications).append(anyString(), any(InboxMessage.class));
        try { assertThatThrownBy(() -> process(id, Instant.now())).isInstanceOf(DomainException.class); }
        finally { doCallRealMethod().when(notifications).append(anyString(), any(InboxMessage.class)); }
        var original = state(id).input(); service.failed(candidate(id), 1, Instant.now());
        assertThat(state(id).status()).isEqualTo(EventInboxItem.Status.WAITING); assertThat(state(id).failures()).isEqualTo(1);
        process(id, state(id).nextAttemptAt()); assertThat(state(id).input()).isEqualTo(original);
        long version = state(id).version(); service.failed(candidate(id), 1, state(id).updatedAt());
        assertThat(state(id).version()).isEqualTo(version); assertThat(state(id).status()).isEqualTo(EventInboxItem.Status.CONSUMED);
    }
    @Test void pausedAndDisabledWaitsResumeWithTheOriginalEventAndActivation() throws Exception {
        String key = contract(); String app = waiting(key); var signal = signal(app, key); UUID id = receive(signal, UUID.randomUUID().toString());
        String instance = runtime.createProcessInstanceQuery().variableValueEquals("applicationId", app).singleResult().getId();
        runtime.suspendProcessInstanceById(instance); process(id, Instant.now());
        assertThat(state(id).reason()).isEqualTo(EventInboxItem.Reason.PAUSED);
        runtime.activateProcessInstanceById(instance); contracts.changeAvailability(ADMIN, key, 1, 1, false, "临时停用");
        process(id, state(id).nextAttemptAt()); assertThat(state(id).reason()).isEqualTo(EventInboxItem.Reason.CONTRACT_DISABLED);
        assertThat(signal(app, key).waitId()).isEqualTo(signal.waitId());
        contracts.changeAvailability(ADMIN, key, 1, 2, true, "恢复原契约");
        process(id, state(id).nextAttemptAt()); assertThat(state(id).status()).isEqualTo(EventInboxItem.Status.CONSUMED);
        assertThat(state(id).failures()).isZero(); assertThat(auditCount(app)).isEqualTo(1);
    }
    @Test void trustRevisionChangeRequiresAuditedRetryAndCannotBeOverriddenByItsRequest() throws Exception {
        String key = contract(); String app = waiting(key); UUID id = receive(signal(app, key), UUID.randomUUID().toString());
        doReturn(EventIngressVerifier.Availability.CHANGED).when(verifier).availability(any(ReceivedEvent.class));
        process(id, Instant.now()); assertThat(state(id).status()).isEqualTo(EventInboxItem.Status.REVIEW_REQUIRED);
        assertThat(tasksFor(app)).isEmpty();
        mvc.perform(post(PATH + "/" + id + "/retry").header("Authorization", token("admin")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":2,\"reason\":\"替换配置\",\"trustRevision\":2}")).andExpect(status().isBadRequest());
        doCallRealMethod().when(verifier).availability(any(ReceivedEvent.class));
        var response = retry(id, 2, "原信任配置已恢复", "restore-" + UUID.randomUUID());
        assertThat(response.path("requestedBy").asText()).isEqualTo("admin");
        assertThat(response.path("trustRevision").asLong()).isEqualTo(1);
        process(id, Instant.now()); assertThat(state(id).status()).isEqualTo(EventInboxItem.Status.CONSUMED);
        assertThat(history(id)).extracting(EventInboxItem::status).containsExactly(EventInboxItem.Status.CONSUMED, EventInboxItem.Status.RECEIVED,
                EventInboxItem.Status.REVIEW_REQUIRED, EventInboxItem.Status.RECEIVED);
    }
    @Test void boundedFailuresCanBeRetriedIdempotentlyButRoleIsRecheckedBeforeReplay() throws Exception {
        String key = contract(); UUID id = receive(signal(waiting(key), key), UUID.randomUUID().toString());
        for (int i = 0; i < EventInboxItem.MAX_FAILURES; i++) { var current = state(id); service.failed(candidate(id), current.version(), current.updatedAt()); }
        var current = state(id); assertThat(current.status()).isEqualTo(EventInboxItem.Status.REVIEW_REQUIRED);
        String replayKey = "retry-" + UUID.randomUUID(); var first = retry(id, current.version(), "依赖恢复后重试原件", replayKey);
        assertThat(retry(id, current.version(), "依赖恢复后重试原件", replayKey)).isEqualTo(first);
        assertThat(state(id).version()).isEqualTo(current.version() + 1);
        String adminToken = auth.login("demo", "admin", "demo").token();
        doReturn(new Actor("demo", "admin", Set.of("EMPLOYEE"))).when(auth).authenticate(adminToken);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(PATH + "/" + id + "/retry").header("Authorization", "Bearer " + adminToken).header("Idempotency-Key", replayKey)
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("expectedVersion", current.version(), "reason", "依赖恢复后重试原件"))))
                .andExpect(status().isForbidden());
    }
    @Test void messagesForOldRoundsAndDifferentContractsAreVisibleButNeverRetargeted() throws Exception {
        String key = contract(); String app = waiting(key); var old = signal(app, key); UUID id = receive(old, UUID.randomUUID().toString());
        mvc.perform(post("/api/v1/applications/" + app + "/withdraw").header("Authorization", token("alice")).contentType(MediaType.APPLICATION_JSON)
                .content(json.write(Map.of("expectedVersion", 2, "comment", "重提新轮次")))).andExpect(status().isOk());
        submit(app, 3); process(id, Instant.now());
        assertThat(state(id).reason()).isEqualTo(EventInboxItem.Reason.TARGET_STALE);
        var now = signal(app, key); assertThat(now.waitId()).isNotEqualTo(old.waitId());
        String other = contract(); var wrong = new EventSignal(1, "demo", "erp", "GoodsAccepted", now.applicationId(), now.roundNo(), now.waitId(), other, 1);
        UUID mismatched = receive(wrong, UUID.randomUUID().toString()); process(mismatched, Instant.now());
        assertThat(state(mismatched).reason()).isEqualTo(EventInboxItem.Reason.CONTRACT_MISMATCH);
        assertThat(signal(app, key).waitId()).isEqualTo(now.waitId()); assertThat(auditCount(app)).isZero();
        mvc.perform(post(PATH + "/" + id + "/retry").header("Authorization", token("admin")).contentType(MediaType.APPLICATION_JSON)
                .content(json.write(Map.of("expectedVersion", state(id).version(), "reason", "不允许改投新轮次")))).andExpect(status().isConflict());
    }
    @Test void adminQueriesAreTenantBoundStrictAndHistoryHasRealPagination() throws Exception {
        String key = contract(); UUID id = receive(signal(waiting(key), key), UUID.randomUUID().toString());
        service.failed(candidate(id), 1, Instant.now());
        mvc.perform(get(PATH).header("Authorization", token("alice"))).andExpect(status().isForbidden());
        mvc.perform(get(PATH + "/" + id).header("Authorization", "Bearer event-foreign")).andExpect(status().isNotFound());
        mvc.perform(get(PATH).header("Authorization", token("admin")).param("limit", "1", "2")).andExpect(status().isBadRequest());
        mvc.perform(get(PATH).header("Authorization", token("admin")).param("tenantId", "foreign")).andExpect(status().isBadRequest());
        var first = read(PATH + "/" + id + "/history?limit=1", "admin");
        assertThat(first.path("items").size()).isEqualTo(1); assertThat(first.path("nextBeforeVersion").asLong()).isEqualTo(2);
        var second = read(PATH + "/" + id + "/history?limit=1&beforeVersion=2", "admin");
        assertThat(second.path("items").get(0).path("version").asLong()).isEqualTo(1);
        assertThat(read(PATH + "/" + id + "/history?beforeVersion=9223372036854775807", "admin").path("items").size()).isEqualTo(2);
    }
    @Test void databaseRejectsMissingTerminalReasonsAndFailureCodes() throws Exception {
        String key = contract(); UUID id = receive(signal(waiting(key), key), UUID.randomUUID().toString()); var initial = state(id);
        for (String assignment : List.of("status='CONSUMED',reason=NULL", "status='REVIEW_REQUIRED',reason='PROCESSING_FAILED',error_code=NULL")) {
            try {
                assertThatThrownBy(() -> jdbc.update("UPDATE event_inbox SET next_attempt_at=NULL," + assignment + " WHERE tenant_id='demo' AND id=?", id.toString()))
                        .isInstanceOf(DataIntegrityViolationException.class);
            } finally {
                jdbc.update("UPDATE event_inbox SET status='RECEIVED',reason=NULL,error_code=NULL,next_attempt_at=? WHERE tenant_id='demo' AND id=?",
                        Timestamp.from(initial.nextAttemptAt()), id.toString());
            }
        }
    }
    private String contract() {
        String key = "accepted-" + UUID.randomUUID(); contracts.publish(ADMIN, key, 0, "验收事件", "erp", "GoodsAccepted", "签名来源验收"); return key;
    }
    private String waiting(String contract) throws Exception {
        String key = "inbox-" + UUID.randomUUID();
        var graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("wait", "等待验收", NodeType.EVENT_WAIT, Map.of("eventContractKey", contract, "eventContractVersion", "1")),
                new Node("review", "审批", NodeType.USER_TASK, Map.of("assigneeRule", "user:finance")), new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("a", "start", "wait", ""), new Edge("b", "wait", "review", ""), new Edge("c", "review", "end", "")));
        var definition = definitions.create("demo", key, "收件验收", graph); definitions.publish(ADMIN, definition.id(), 0, "固定契约");
        var response = mvc.perform(post("/api/v1/applications").header("Authorization", token("alice")).contentType(MediaType.APPLICATION_JSON)
                .content(json.write(Map.of("businessNo", key, "processKey", key, "definitionVersion", 1, "title", "收件验收", "payload", Map.of()))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = json.read(response, JsonNode.class).path("id").asText(); submit(id, 1); return id;
    }
    private void submit(String id, long version) throws Exception {
        mvc.perform(post("/api/v1/applications/" + id + "/submit").header("Authorization", token("alice")).contentType(MediaType.APPLICATION_JSON)
                .content(json.write(Map.of("expectedVersion", version)))).andExpect(status().isOk());
    }
    private EventSignal signal(String id, String key) throws Exception {
        String instance = runtime.createProcessInstanceQuery().variableValueEquals("applicationId", id).singleResult().getId();
        String wait = runtime.createEventSubscriptionQuery().processInstanceId(instance).eventType("message").singleResult().getId();
        return new EventSignal(1, "demo", "erp", "GoodsAccepted", UUID.fromString(id), application(id).path("roundNo").asInt(), wait, key, 1);
    }
    private UUID receive(EventSignal signal, String event) throws Exception {
        return UUID.fromString(json.read(mvc.perform(request(json.write(signal), event, Instant.now())).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString(), JsonNode.class).path("id").asText());
    }
    private JsonNode retry(UUID id, long version, String reason, String key) throws Exception {
        return json.read(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(PATH + "/" + id + "/retry").header("Authorization", token("admin")).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("expectedVersion", version, "reason", reason))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), JsonNode.class);
    }
    private EventInboxItem state(UUID id) { return inbox.get("demo", id); }
    private EventInboxRepository inboxTarget() { return org.springframework.test.util.AopTestUtils.getUltimateTargetObject(inbox); }
    private List<EventInboxItem> history(UUID id) { return inbox.history("demo", id, 100, null); }
    private EventInboxRepository.Candidate candidate(UUID id) { return new EventInboxRepository.Candidate("demo", id, null); }
    private void process(UUID id, Instant now) { service.process(candidate(id), now); }
    private List<org.flowable.task.api.Task> tasksFor(String id) { return tasks.createTaskQuery().processVariableValueEquals("applicationId", id).list(); }
    private JsonNode application(String id) throws Exception { return read("/api/v1/applications/" + id, "alice"); }
    private JsonNode read(String path, String user) throws Exception {
        return json.read(mvc.perform(get(path).header("Authorization", token(user))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), JsonNode.class);
    }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private int auditCount(String app) { return jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE application_id=? AND action='EVENT_RECEIVED'", Integer.class, app); }

    @Test void tracePersistsFromSignedReceptionToProcessAudit() throws Exception {
        String key = contract(), app = waiting(key), event = UUID.randomUUID().toString(); var signal = signal(app, key);
        var response = mvc.perform(request(json.write(signal), event, Instant.now())).andExpect(status().isAccepted()).andReturn().getResponse();
        String trace = response.getHeader(DiagnosticContext.HEADER);
        UUID id = UUID.fromString(json.read(response.getContentAsString(), JsonNode.class).path("id").asText());
        assertThat(DiagnosticContext.validTrace(trace)).isTrue();
        assertThat(jdbc.queryForMap("SELECT * FROM event_inbox WHERE id=?", id.toString())).containsEntry("TRACE_ID", trace);
        var worker = new EventInboxWorker(inbox, service); worker.poll(); worker.poll();
        assertThat(state(id).status()).isEqualTo(EventInboxItem.Status.CONSUMED);
        assertThat(jdbc.queryForList("SELECT payload_json FROM audit_event WHERE application_id=? AND action='EVENT_RECEIVED'", String.class, app))
                .extracting(value -> json.map(value).get("traceId")).containsExactly(trace);
        assertThat(receive(signal, event)).isEqualTo(id);
        assertThat(jdbc.queryForMap("SELECT * FROM event_inbox WHERE id=?", id.toString())).containsEntry("TRACE_ID", trace);
        assertThat(org.slf4j.MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

}
