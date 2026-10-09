package io.agentflow.organization;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import io.agentflow.observability.DiagnosticContext;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 真实鉴权、幂等、工作器和回环来源贯通管理 API，接收事实不代替人工应用。
 *
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:organization-sync-api;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "agentflow.auth.demo-enabled=false", "agentflow.sla.reminders-enabled=false", "agentflow.organization-sync.enabled=true", "agentflow.organization-sync.worker-enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class OrganizationSyncApiIntegrationTest {
    private static final String API = "/api/v1/organization/synchronization";
    private static final List<Exchange> EXCHANGES = new CopyOnWriteArrayList<>();
    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrganizationService organization;
    @Autowired OrganizationSyncService service;
    @Autowired JdbcOrganizationSyncRepository batches;
    @Autowired OrganizationSyncWorker worker;
    @Autowired OrganizationSyncConfiguration configuration;
    @MockitoSpyBean AuthService auth;
    private OrganizationSyncTestSource fixture;
    private OrganizationSyncConfiguration.Target target;
    private Actor admin;
    private String token;

    @BeforeEach void setup() throws Exception {
        admin = new Actor("sync-" + UUID.randomUUID(), "admin", Set.of("ADMIN")); token = UUID.randomUUID().toString();
        doReturn(admin).when(auth).authenticate(token); organization.initialize(admin);
        fixture = new OrganizationSyncTestSource(json); target = fixture.target();
        configuration.setEnabled(true); configuration.setWorkerEnabled(false); configuration.setTenants(Map.of(admin.tenantId(), target)); configuration.validate();
    }
    @AfterEach void close() {
        fixture.close(); batches.pendingId(admin.tenantId()).ifPresent(id -> {
            var batch = batches.find(admin.tenantId(), id).orElseThrow(); service.cancel(admin, id, batch.state().version(), "test cleanup");
        });
    }

    @Test void queuesReadsPreflightsAndExplicitlyAppliesWithDiscoverableAuditsAndIdempotentReceipts() throws Exception {
        var status = read("", 200); assertThat(status.path("initialized").asBoolean()).isTrue(); assertThat(status.path("sourceVersion").asInt()).isZero();
        assertThat(status.toString()).doesNotContain("127.0.0.1", "synthetic-source-token"); assertThat(fixture.calls).isEmpty();
        String key = UUID.randomUUID().toString(), body = queueBody(0);
        var queued = write("/batches", body, key, 202); assertThat(write("/batches", body, key, 202)).isEqualTo(queued);
        String id = queued.path("id").asText(); assertThat(count("organization_sync_batch")).isEqualTo(1); assertThat(fixture.calls).isEmpty();
        worker.poll(); var received = read("/batches/" + id, 200); assertThat(received.at("/state/status").asText()).isEqualTo("RECEIVED");
        assertThat(received.at("/state/delta/people/0/subject").asText()).isEqualTo("source-subject"); assertThat(count("organization_person")).isZero();
        assertThat(read("", 200).path("appliedRevision").asLong()).isZero();
        assertThat(read("/batches/" + id + "/transitions", 200)).hasSize(3);
        String preflight = json.write(Map.of("expectedVersion", 3, "selections", List.of())); String preflightKey = UUID.randomUUID().toString();
        var preview = write("/batches/" + id + "/preflight", preflight, preflightKey, 201); assertThat(preview.path("ready").asBoolean()).isTrue();
        assertThat(write("/batches/" + id + "/preflight", preflight, preflightKey, 201)).isEqualTo(preview);
        String planId = preview.path("id").asText(); var plan = read("/plans/" + planId, 200);
        assertThat(plan.at("/plan/people/0/after/subject").asText()).isEqualTo("source-subject");
        assertThat(read("/batches/" + id + "/plans?page=0&pageSize=1", 200).path("total").asInt()).isEqualTo(1);
        String apply = json.write(Map.of("expectedVersion", 3, "planId", planId, "comment", "已核对来源人员")); String applyKey = UUID.randomUUID().toString();
        var applied = write("/batches/" + id + "/apply", apply, applyKey, 200); assertThat(applied.path("status").asText()).isEqualTo("APPLIED");
        assertThat(write("/batches/" + id + "/apply", apply, applyKey, 200)).isEqualTo(applied); worker.poll();
        assertThat(fixture.calls).hasSize(1); assertThat(count("organization_person")).isEqualTo(1); assertThat(count("organization_change")).isEqualTo(1);
        assertThat(read("", 200).path("appliedRevision").asInt()).isEqualTo(1);
        assertThat(read("/batches/" + id, 200).path("appliedPlanId").asText()).isEqualTo(planId);
        assertThat(read("/batches/" + id + "/transitions", 200)).hasSize(4);
        assertThat(read("/batches?pageSize=1", 200).path("items")).hasSize(1);
    }

    @Test void currentRolePrecedesReplayAndHistoricalSuccessCanRecoverAfterSourceDisablement() throws Exception {
        String body = queueBody(0), key = UUID.randomUUID().toString(); var queued = write("/batches", body, key, 202); String id = queued.path("id").asText();
        doReturn(new Actor(admin.tenantId(), "admin", Set.of("EMPLOYEE"))).when(auth).authenticate(token);
        write("/batches", body, key, 403); read("/batches/" + id, 403); read("/batches", 403);
        doReturn(new Actor(admin.tenantId(), "other-admin", Set.of("ADMIN"))).when(auth).authenticate(token); write("/batches", body, key, 409);
        doReturn(admin).when(auth).authenticate(token); configuration.setEnabled(false);
        assertThat(write("/batches", body, key, 202)).isEqualTo(queued); write("/batches", body, UUID.randomUUID().toString(), 503);
        write("/batches/" + id + "/cancel", json.write(Map.of("expectedVersion", 1, "comment", "停用配置后取消")), UUID.randomUUID().toString(), 200);
        assertThat(fixture.calls).isEmpty(); assertThat(count("organization_person")).isZero();
    }

    @Test void targetChangeFailsWithoutNetworkAndExplicitRetryKeepsOriginalFailure() throws Exception {
        String old = queued(); target.setEndpoint(fixture.endpoint() + "other/"); worker.poll();
        assertThat(read("/batches/" + old, 200).at("/state/failure").asText()).isEqualTo("SOURCE_CHANGED"); assertThat(fixture.calls).isEmpty();
        target.setEndpoint(fixture.endpoint()); target.setSourceKey("another-source");
        write("/batches/" + old + "/retry", retryBody(3), UUID.randomUUID().toString(), 409);
        target.setSourceKey("hr"); var retry = write("/batches/" + old + "/retry", retryBody(3), UUID.randomUUID().toString(), 202);
        worker.poll(); String id = retry.path("id").asText(); var current = read("/batches/" + id, 200);
        assertThat(current.at("/request/retryOf").asText()).isEqualTo(old); assertThat(current.at("/state/status").asText()).isEqualTo("RECEIVED");
        assertThat(read("/batches/" + old, 200).at("/state/failure").asText()).isEqualTo("SOURCE_CHANGED"); assertThat(fixture.calls).hasSize(1);
    }

    @ParameterizedTest @EnumSource(value = OrganizationSyncTestSource.Mode.class, names = {"WRONG_TENANT", "SLOW_BODY", "TOO_LARGE"})
    void failedSourceReadHasStableAuditedFailureAndNoOrganizationWrites(OrganizationSyncTestSource.Mode mode) throws Exception {
        fixture.mode = mode; if (mode == OrganizationSyncTestSource.Mode.SLOW_BODY) target.setTimeoutSeconds(1);
        String id = queued(); worker.poll(); var detail = read("/batches/" + id, 200);
        assertThat(detail.at("/state/status").asText()).isEqualTo("FAILED");
        assertThat(detail.at("/state/failure").asText()).isEqualTo(mode == OrganizationSyncTestSource.Mode.SLOW_BODY ? "SOURCE_TIMEOUT" : "INVALID_SOURCE_DATA");
        worker.poll(); assertThat(fixture.calls).hasSize(1); assertThat(count("organization_person")).isZero();
        assertThat(read("/batches/" + id + "/transitions", 200)).hasSize(3);
    }

    @Test void cancellationWhileHttpIsHeldDoesNotWaitForAnOrganizationTransactionOrAcceptLateData() throws Exception {
        fixture.mode = OrganizationSyncTestSource.Mode.HOLD; String id = queued(); var executor = Executors.newSingleThreadExecutor();
        try {
            var pending = executor.submit(worker::poll); assertThat(fixture.entered.await(5, TimeUnit.SECONDS)).isTrue();
            Instant at = Instant.now(); write("/batches/" + id + "/cancel", json.write(Map.of("expectedVersion", 2, "comment", "取消读取")), UUID.randomUUID().toString(), 200);
            assertThat(Duration.between(at, Instant.now())).isLessThan(Duration.ofSeconds(2)); fixture.release.countDown(); pending.get(5, TimeUnit.SECONDS);
            assertThat(read("/batches/" + id, 200).at("/state/status").asText()).isEqualTo("CANCELLED");
            assertThat(count("organization_person")).isZero(); assertThat(fixture.calls).hasSize(1); assertThat(read("", 200).path("appliedRevision").asInt()).isZero();
        } finally { fixture.release.countDown(); executor.shutdownNow(); assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void restoredOriginalLeaseExpiresWithoutResendingAndLateCompletionCannotReplaceTimeout() throws Exception {
        String id = queued(); var claim = service.claim(admin.tenantId(), UUID.fromString(id), Instant.now());
        assertThat(new JdbcOrganizationSyncRepository(
                                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                                (jdbc).getDataSource(),
                                                io.agentflow.organization.mapper
                                                        .OrganizationSyncRepositoryMapper.class), json).find(admin.tenantId(), UUID.fromString(id)).orElseThrow().state().leaseUntil()).isEqualTo(claim.leaseUntil());
        worker.poll(); assertThat(fixture.calls).isEmpty(); assertThat(service.claim(admin.tenantId(), UUID.fromString(id), claim.leaseUntil())).isNull();
        service.finish(claim, new HttpOrganizationSyncSource.Result(new OrganizationSyncDelta("hr", 0, 1, List.of(), List.of(), List.of()), null), claim.leaseUntil().plusSeconds(1));
        worker.poll(); assertThat(read("/batches/" + id, 200).at("/state/failure").asText()).isEqualTo("SOURCE_TIMEOUT"); assertThat(fixture.calls).isEmpty();
    }

    @Test void competingWorkersCannotSendTheSameClaimTwice() throws Exception {
        fixture.mode = OrganizationSyncTestSource.Mode.HOLD; String id = queued(); var executor = Executors.newSingleThreadExecutor();
        try {
            var pending = executor.submit(worker::poll); assertThat(fixture.entered.await(5, TimeUnit.SECONDS)).isTrue();
            worker.poll(); assertThat(fixture.calls).hasSize(1);
            assertThat(read("/batches/" + id, 200).at("/state/status").asText()).isEqualTo("FETCHING");
            fixture.release.countDown(); pending.get(5, TimeUnit.SECONDS);
            assertThat(read("/batches/" + id, 200).at("/state/status").asText()).isEqualTo("RECEIVED");
            assertThat(read("/batches/" + id + "/transitions", 200)).hasSize(3); assertThat(fixture.calls).hasSize(1);
        } finally { fixture.release.countDown(); executor.shutdownNow(); assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    @ParameterizedTest @ValueSource(strings = {"cancel", "target", "deadline"})
    void claimedWorkRechecksCancellationTargetAndDeadlineBeforeSending(String change) throws Exception {
        String id = queued(); var claim = service.claim(admin.tenantId(), UUID.fromString(id), Instant.now());
        if (change.equals("cancel")) write("/batches/" + id + "/cancel", json.write(Map.of("expectedVersion", 2)), UUID.randomUUID().toString(), 200);
        if (change.equals("target")) target.setEndpoint(fixture.endpoint() + "other/");
        assertThat(service.sendable(claim, change.equals("deadline") ? claim.leaseUntil() : Instant.now())).isFalse();
        var state = read("/batches/" + id, 200).path("state");
        if (change.equals("cancel")) assertThat(state.path("status").asText()).isEqualTo("CANCELLED");
        else assertThat(state.path("failure").asText()).isEqualTo(change.equals("target") ? "SOURCE_CHANGED" : "SOURCE_TIMEOUT");
        worker.poll(); assertThat(fixture.calls).isEmpty(); assertThat(count("organization_person")).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"disabled", "target"})
    void deploymentChangesDuringHttpReadCannotBecomeAcceptedSourceFacts(String change) throws Exception {
        fixture.mode = OrganizationSyncTestSource.Mode.HOLD; String id = queued(); var executor = Executors.newSingleThreadExecutor();
        try {
            var pending = executor.submit(worker::poll); assertThat(fixture.entered.await(5, TimeUnit.SECONDS)).isTrue();
            if (change.equals("disabled")) configuration.setEnabled(false); else target.setEndpoint(fixture.endpoint() + "other/");
            fixture.release.countDown(); pending.get(5, TimeUnit.SECONDS);
            var state = read("/batches/" + id, 200).path("state"); assertThat(state.path("failure").asText()).isEqualTo("SOURCE_CHANGED");
            assertThat(state.has("delta")).isFalse(); assertThat(count("organization_person")).isZero();
            assertThat(read("", 200).path("appliedRevision").asLong()).isZero(); assertThat(fixture.calls).hasSize(1);
        } finally { fixture.release.countDown(); executor.shutdownNow(); assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void queueAndRetryKeepOneActiveBatchAndRequireCurrentVersions() throws Exception {
        String id = queued();
        write("/batches", queueBody(0), UUID.randomUUID().toString(), 409);
        assertThat(write("/batches", queueBody(1), UUID.randomUUID().toString(), 409).path("code").asText()).isEqualTo("ORGANIZATION_SYNC_BATCH_ACTIVE");
        write("/batches/" + id + "/retry", retryBody(1), UUID.randomUUID().toString(), 409);
        write("/batches/" + id + "/cancel", json.write(Map.of("expectedVersion", 1)), UUID.randomUUID().toString(), 200);
        write("/batches/" + id + "/retry", retryBody(1), UUID.randomUUID().toString(), 409);
        String retryKey = UUID.randomUUID().toString(); var retry = write("/batches/" + id + "/retry", retryBody(2), retryKey, 202);
        assertThat(write("/batches/" + id + "/retry", retryBody(2), retryKey, 202)).isEqualTo(retry);
        assertThat(read("/batches/" + retry.path("id").asText(), 200).at("/request/retryOf").asText()).isEqualTo(id);
        assertThat(read("/batches/" + id, 200).at("/state/status").asText()).isEqualTo("CANCELLED");
        assertThat(count("organization_sync_batch")).isEqualTo(2); assertThat(fixture.calls).isEmpty();
    }

    @Test void historyUsesTheAlreadyReadVersionAndRejectsMissingOrMismatchedTransitions() throws Exception {
        String id = queued(); var original = batches.find(admin.tenantId(), UUID.fromString(id)).orElseThrow(); worker.poll();
        assertThat(batches.transitions(original)).hasSize(1); assertThat(read("/batches/" + id + "/transitions", 200)).hasSize(3);
        jdbc.update(
                "UPDATE organization_sync_transition SET status='FAILED' WHERE tenant_id=? AND"
                        + " batch_id=? AND batch_version=2", admin.tenantId(), id);
        assertThatThrownBy(() -> service.transitions(admin, UUID.fromString(id))).isInstanceOf(IllegalStateException.class);
        jdbc.update(
                "UPDATE organization_sync_transition SET status='FETCHING' WHERE tenant_id=? AND"
                        + " batch_id=? AND batch_version=2", admin.tenantId(), id);
        jdbc.update(
                "DELETE FROM organization_sync_transition WHERE tenant_id=? AND batch_id=? AND"
                        + " batch_version=2", admin.tenantId(), id);
        assertThatThrownBy(() -> service.transitions(admin, UUID.fromString(id))).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"preflight", "apply"})
    void receivedFactsCannotBeReviewedOrAppliedAgainstAChangedDeploymentTarget(String stage) throws Exception {
        String id = queued(); worker.poll(); String plan = null;
        if (stage.equals("apply")) plan = write("/batches/" + id + "/preflight", json.write(Map.of("expectedVersion", 3, "selections", List.of())), UUID.randomUUID().toString(), 201).path("id").asText();
        target.setEndpoint(fixture.endpoint() + "other/");
        String body = stage.equals("preflight") ? json.write(Map.of("expectedVersion", 3, "selections", List.of())) : json.write(Map.of("expectedVersion", 3, "planId", plan));
        write("/batches/" + id + "/" + stage, body, UUID.randomUUID().toString(), 409);
        assertThat(read("/batches/" + id, 200).path("reviewable").asBoolean()).isFalse(); if (plan != null) read("/plans/" + plan, 200);
        assertThat(count("organization_person")).isZero(); assertThat(read("", 200).path("appliedRevision").asInt()).isZero();
    }

    @Test void firstQueueFailureRollsBackImplicitSourceRegistrationAndItsAudit() {
        jdbc.execute(
                "ALTER TABLE organization_sync_transition ADD CONSTRAINT refuse_new_sync"
                        + " CHECK(tenant_id<>'"
                        + admin.tenantId() + "')");
        try {
            assertThatThrownBy(() -> service.queue(admin, 0, configuration.require(admin.tenantId()).digest(admin.tenantId()))).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(count("organization_sync_source")).isZero(); assertThat(count("organization_sync_batch")).isZero(); assertThat(count("organization_sync_transition")).isZero();
        } finally { jdbc.execute("ALTER TABLE organization_sync_transition DROP CONSTRAINT refuse_new_sync"); }
    }

    @ParameterizedTest @ValueSource(strings = {"string-number", "fraction", "unknown", "duplicate", "trailing", "null", "missing"})
    void strictCommandsRejectAmbiguousOrCoercedInputBeforeRegisteringASource(String variant) throws Exception {
        String body = queueBody(0);
        body = switch (variant) {
            case "string-number" -> body.replace("\"expectedSourceVersion\":0", "\"expectedSourceVersion\":\"0\"");
            case "fraction" -> body.replace("\"expectedSourceVersion\":0", "\"expectedSourceVersion\":0.5");
            case "unknown" -> body.substring(0, body.length() - 1) + ",\"endpoint\":\"https://untrusted.invalid/\"}";
            case "duplicate" -> body.replace("\"expectedSourceVersion\":0", "\"expectedSourceVersion\":0,\"expectedSourceVersion\":0");
            case "trailing" -> body + " {}";
            case "null" -> body.replace("\"expectedSourceVersion\":0", "\"expectedSourceVersion\":null");
            case "missing" -> "{\"targetDigest\":\"" + configuration.require(admin.tenantId()).digest(admin.tenantId()) + "\"}";
            default -> throw new AssertionError(variant);
        };
        write("/batches", body, UUID.randomUUID().toString(), 400); assertThat(count("organization_sync_source")).isZero(); assertThat(fixture.calls).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"page=-1", "page=01", "pageSize=0", "pageSize=51", "page=0&page=1", "tenantId=foreign", "sourceKey=other"})
    void queryCannotOverrideScopeOrPagination(String query) throws Exception { read("/batches?" + query, 400); assertThat(count("organization_sync_source")).isZero(); }

    @ParameterizedTest @ValueSource(strings = {"enum-number", "revision-string", "unknown-field"})
    void nestedAdoptionSelectionsUseTheSameStrictContract(String variant) throws Exception {
        String id = queued(); worker.poll();
        String selection = "{\"kind\":\"PERSON\",\"externalId\":\"person\",\"localId\":\"" + UUID.randomUUID() + "\",\"expectedRevision\":1}";
        selection = switch (variant) {
            case "enum-number" -> selection.replace("\"PERSON\"", "3");
            case "revision-string" -> selection.replace("\"expectedRevision\":1", "\"expectedRevision\":\"1\"");
            case "unknown-field" -> selection.substring(0, selection.length() - 1) + ",\"roles\":[\"ADMIN\"]}";
            default -> throw new AssertionError(variant);
        };
        write("/batches/" + id + "/preflight", "{\"expectedVersion\":3,\"selections\":[" + selection + "]}", UUID.randomUUID().toString(), 400);
        assertThat(count("organization_sync_plan")).isZero(); assertThat(count("organization_person")).isZero();
    }

    @Test void readsAndPlansAreTenantScopedAndOverviewNeverInitializesAnEmptyTenant() throws Exception {
        String id = queued(); worker.poll(); String plan = write("/batches/" + id + "/preflight", json.write(Map.of("expectedVersion", 3, "selections", List.of())), UUID.randomUUID().toString(), 201).path("id").asText();
        var foreign = new Actor("foreign-" + UUID.randomUUID(), "admin", Set.of("ADMIN")); doReturn(foreign).when(auth).authenticate(token);
        assertThat(read("", 200).path("initialized").asBoolean()).isFalse(); assertThat(read("/batches", 200).path("total").asInt()).isZero();
        read("/batches/" + id, 404); read("/plans/" + plan, 404); read("/batches/" + id + "/plans", 404); read("/batches/" + id + "/transitions", 404);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM organization_directory WHERE tenant_id=?", Long.class, foreign.tenantId())).isZero();
    }

    private String queued() throws Exception { return write("/batches", queueBody(0), UUID.randomUUID().toString(), 202).path("id").asText(); }
    private String queueBody(long version) { return json.write(Map.of("expectedSourceVersion", version, "targetDigest", configuration.require(admin.tenantId()).digest(admin.tenantId()))); }
    private String retryBody(long version) { return json.write(Map.of("expectedVersion", version, "expectedSourceVersion", 1, "targetDigest", configuration.require(admin.tenantId()).digest(admin.tenantId()))); }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id=?", Long.class, admin.tenantId()); }
    private JsonNode read(String path, int expected) throws Exception { return exchange("GET", path, null, null, expected); }
    private JsonNode write(String path, String body, String key, int expected) throws Exception { return exchange("POST", path, body, key, expected); }
    private JsonNode exchange(String method, String path, String body, String key, int expected) throws Exception {
        var request = method.equals("GET") ? get(API + path) : post(API + path);
        request.header("Authorization", "Bearer " + token).accept(MediaType.APPLICATION_JSON);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body); if (key != null) request.header("Idempotency-Key", key);
        var response = mvc.perform(request).andReturn().getResponse(); JsonNode content = json.read(response.getContentAsString(), JsonNode.class);
        String template = (API + path).split("\\?", 2)[0].replaceAll("/batches/[a-f0-9-]{36}", "/batches/{id}").replaceAll("/plans/[a-f0-9-]{36}", "/plans/{planId}");
        EXCHANGES.add(new Exchange(method, template, API + path, response.getStatus(), body, content, response.getHeader("Cache-Control"), response.getHeader("Idempotency-Replayed")));
        assertThat(response.getStatus()).as("%s %s: %s", method, path, content).isEqualTo(expected);
        assertThat(response.getHeader("Cache-Control")).contains("no-store"); return content;
    }
    @AfterAll static void capture() throws Exception {
        String output = System.getProperty("agentflow.organization-sync-contract-output"); if (output == null) return;
        Path path = Path.of(output); Files.createDirectories(path.getParent()); Files.writeString(path, new JsonUtil(new ObjectMapper()).write(EXCHANGES));
    }

    /**
     * 独立契约核对读取实际接口请求和响应，不手写成功响应样例。
     *
     * @author owlzhangfq@gmail.com
     */
    private record Exchange(String method, String template, String path, int status, String rawRequest, JsonNode response, String cacheControl, String replayed) { }

    @Test void tracePersistsFromAuthorizedQueueToReadOnlySource() throws Exception {
        var response = mvc.perform(post(API + "/batches").header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(queueBody(0)))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(202);
        String trace = response.getHeader(DiagnosticContext.HEADER);
        String id = json.read(response.getContentAsString(), JsonNode.class).path("id").asText();
        assertThat(DiagnosticContext.validTrace(trace)).isTrue();
        assertThat(jdbc.queryForMap("SELECT * FROM organization_sync_batch WHERE id=?", id)).containsEntry("TRACE_ID", trace);
        worker.poll(); worker.poll();
        assertThat(read("/batches/" + id, 200).at("/state/status").asText()).isEqualTo("RECEIVED");
        assertThat(fixture.traceIds).containsExactly(trace);
        assertThat(count("organization_person")).isZero();
        assertThat(org.slf4j.MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

}
