package io.agentflow.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.api.idempotency.JdbcIdempotencyRepository;
import io.agentflow.approval.service.ApplicationAuditPort;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实 HTTP 与共享数据库验证业务写接口的幂等事务边界。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:request-idempotency;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
        "agentflow.auth.demo-enabled=true", "agentflow.auth.demo-tenant=demo"
})
@AutoConfigureMockMvc
class IdempotencyIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired RuntimeService runtime;
    @Autowired TaskService tasks;
    @Autowired RepositoryService definitions;
    @MockitoSpyBean AuthService auth;
    @MockitoSpyBean JsonUtil json;
    @MockitoSpyBean JdbcIdempotencyRepository records;
    @MockitoSpyBean ApplicationAuditPort applicationAudit;

    @Test
    void applicationMutationRequiresAnIdempotencyKey() throws Exception {
        assertThat(send("POST", "/api/v1/applications", "alice", null, createBody()).getStatus()).isEqualTo(400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"revise", "submit", "withdraw", "task", "definition-create", "definition-update", "definition-publish"})
    void everyOtherBusinessMutationAlsoRequiresAKey(String operation) throws Exception {
        String id = UUID.randomUUID().toString();
        String method = operation.equals("revise") || operation.equals("definition-update") ? "PUT" : "POST";
        String path = switch (operation) {
            case "revise" -> "/api/v1/applications/" + id;
            case "submit", "withdraw" -> "/api/v1/applications/" + id + "/" + operation;
            case "task" -> "/api/v1/tasks/" + id + "/actions";
            case "definition-create" -> "/api/v1/process-definitions";
            case "definition-update" -> "/api/v1/process-definitions/" + id;
            default -> "/api/v1/process-definitions/" + id + "/publish?expectedRevision=1";
        };
        String body = switch (operation) {
            case "revise" -> "{\"expectedVersion\":1,\"title\":\"修改\",\"payload\":{}}";
            case "submit", "withdraw" -> "{\"expectedVersion\":1}";
            case "task" -> "{\"expectedVersion\":1,\"action\":\"APPROVE\"}";
            case "definition-create", "definition-update" -> definitionBody("missing-key", operation.equals("definition-update"));
            default -> "{\"changeNote\":\"测试发布\"}";
        };
        MockHttpServletResponse response = send(method, path, "admin", null, body);
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(tree(response).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "has space", "非法", "bad/key", "bad,key"})
    void keyFormatRejectsAmbiguousOrNonAsciiValues(String key) throws Exception {
        MockHttpServletResponse response = send("POST", "/api/v1/applications", "alice", key, createBody());
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(tree(response).path("code").asText()).isEqualTo("INVALID_IDEMPOTENCY_KEY");
    }

    @Test
    void keyLengthAndDuplicateHeadersHaveExplicitBoundaries() throws Exception {
        assertThat(send("POST", "/api/v1/applications", "alice", "a".repeat(129), createBody()).getStatus()).isEqualTo(400);
        assertThat(send("POST", "/api/v1/applications", "alice", "a".repeat(128), createBody()).getStatus()).isEqualTo(201);
        mvc.perform(post("/api/v1/applications").header("Authorization", token("alice"))
                        .header("Idempotency-Key", "first", "second").contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void successfulApplicationWritesReplayTheirOriginalStatusAndBodyWithoutRepeatingSideEffects() throws Exception {
        String createKey = key();
        String createBody = createBody();
        MockHttpServletResponse created = twice("POST", "/api/v1/applications", "alice", createKey, createBody, 201);
        String id = tree(created).path("id").asText();
        String path = "/api/v1/applications/" + id;
        twice("PUT", path, "alice", key(), "{\"expectedVersion\":1,\"title\":\"已修改\",\"payload\":{\"amount\":100}}", 200);
        String submitKey = key();
        String submitBody = "{\"expectedVersion\":2}";
        MockHttpServletResponse submitted = twice("POST", path + "/submit", "alice", submitKey, submitBody, 200);
        twice("POST", path + "/withdraw", "alice", key(), "{\"expectedVersion\":3,\"comment\":\"撤回重试\"}", 200);
        // 原提交成功响应可在实例终止后回放，但不会把当前申请改回审批中。
        assertThat(send("POST", path + "/submit", "alice", submitKey, submitBody).getContentAsString())
                .isEqualTo(submitted.getContentAsString());
        assertThat(send("POST", "/api/v1/applications", "alice", createKey, createBody).getContentAsString())
                .isEqualTo(created.getContentAsString());
        assertThat(jdbc.queryForObject("SELECT version FROM approval_application WHERE id=?", Long.class, id)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT status FROM approval_application WHERE id=?", String.class, id)).isEqualTo("WITHDRAWN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_submission_round WHERE application_id=?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT action FROM audit_event WHERE application_id=?", String.class, id))
                .containsExactlyInAnyOrder("CREATE", "REVISE", "SUBMIT", "WITHDRAW");
        assertThat(runtime.createProcessInstanceQuery().variableValueEquals("applicationId", id).count()).isZero();
    }

    @Test
    void completedTaskActionReplaysEvenThoughTheRuntimeTaskNoLongerExists() throws Exception {
        String id = draft();
        assertThat(send("POST", application(id, "submit"), "alice", key(), "{\"expectedVersion\":1}").getStatus()).isEqualTo(200);
        var task = tasks.createTaskQuery().processVariableValueEquals("applicationId", id).singleResult();
        twice("POST", "/api/v1/tasks/" + task.getId() + "/actions", "finance", key(),
                "{\"expectedVersion\":2,\"action\":\"APPROVE\",\"comment\":\"只批准一次\"}", 200);
        assertThat(tasks.createTaskQuery().taskId(task.getId()).count()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='APPROVE'", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM approval_application WHERE id=?", Long.class, id)).isEqualTo(4);
    }

    @Test
    void definitionMutationsAndPublicationReplayWithoutASecondEngineDeployment() throws Exception {
        String processKey = "idempotent-" + UUID.randomUUID();
        JsonNode created = tree(twice("POST", "/api/v1/process-definitions", "admin", key(), definitionBody(processKey, false), 200));
        String path = "/api/v1/process-definitions/" + created.path("id").asText();
        twice("PUT", path, "admin", key(), definitionBody(processKey, true), 200);
        String publishKey = key();
        twice("POST", path + "/publish?expectedRevision=1", "admin", publishKey, "{\"changeNote\":\"首次发布\"}", 200);
        assertThat(definitions.createProcessDefinitionQuery().processDefinitionKey(processKey).processDefinitionTenantId("demo").count()).isEqualTo(1);
        assertConflict(send("POST", path + "/publish?expectedRevision=01", "admin", publishKey, "{\"changeNote\":\"首次发布\"}"), "IDEMPOTENCY_KEY_REUSED");
        assertConflict(send("POST", path + "/publish?expectedRevision=1", "admin", publishKey, "{\"changeNote\":\"修改说明\"}"), "IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void authenticationValidationAndSimulationDoNotRequireBusinessMutationKeys() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"demo\",\"username\":\"alice\",\"password\":\"demo\"}"))
                .andExpect(status().isOk());
        String body = definitionBody("readonly-" + UUID.randomUUID(), false);
        assertThat(send("POST", "/api/v1/process-definitions/validate", "admin", null, body).getStatus()).isEqualTo(200);
        String id = tree(send("POST", "/api/v1/process-definitions", "admin", key(), body)).path("id").asText();
        assertThat(send("POST", "/api/v1/process-definitions/" + id + "/simulate", "admin", null, "{}").getStatus()).isEqualTo(200);
    }

    @Test
    void originalBodyUnknownFieldsWhitespaceAndLongSuffixesArePartOfTheFingerprint() throws Exception {
        String body = createBody();
        String large = body.substring(0, body.length() - 1) + ",\"ignored\":\"" + "x".repeat(120_000) + "A\"}";
        String requestKey = key();
        MockHttpServletResponse first = send("POST", "/api/v1/applications", "alice", requestKey, large);
        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(send("POST", "/api/v1/applications", "alice", requestKey, large).getContentAsString()).isEqualTo(first.getContentAsString());
        assertConflict(send("POST", "/api/v1/applications", "alice", requestKey, large.replace("A\"}", "B\"}")), "IDEMPOTENCY_KEY_REUSED");
        assertConflict(send("POST", "/api/v1/applications", "alice", requestKey, " " + large), "IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void actualPathMethodAndRawQueryAreBoundToTheKey() throws Exception {
        String body = createBody();
        String requestKey = key();
        JsonNode created = tree(send("POST", "/api/v1/applications?a=1&b=2", "alice", requestKey, body));
        assertConflict(send("POST", "/api/v1/applications?b=2&a=1", "alice", requestKey, body), "IDEMPOTENCY_KEY_REUSED");
        String update = "{\"expectedVersion\":1,\"title\":\"修改\",\"payload\":{}}";
        assertConflict(send("PUT", "/api/v1/applications/" + created.path("id").asText(), "alice", requestKey, update), "IDEMPOTENCY_KEY_REUSED");
        String updateKey = key();
        assertThat(send("PUT", "/api/v1/applications/" + created.path("id").asText(), "alice", updateKey, update).getStatus()).isEqualTo(200);
        assertConflict(send("PUT", "/api/v1/applications/" + draft(), "alice", updateKey, update), "IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void actorAndRoleChangesCannotExposeCachedResponsesAndTenantsHaveIndependentKeys() throws Exception {
        String requestKey = key();
        String body = createBody();
        String id = tree(send("POST", "/api/v1/applications", "alice", requestKey, body)).path("id").asText();
        MockHttpServletResponse otherActor = send("POST", "/api/v1/applications", "bob", requestKey, body);
        assertConflict(otherActor, "IDEMPOTENCY_KEY_REUSED");
        assertThat(otherActor.getContentAsString()).doesNotContain(id);
        doReturn(new Actor("demo", "alice", Set.of("EMPLOYEE"))).when(auth).authenticate("changed-role");
        MockHttpServletResponse changed = sendToken("POST", "/api/v1/applications", "Bearer changed-role", requestKey, body);
        assertThat(changed.getStatus()).isEqualTo(403);
        assertThat(changed.getContentAsString()).doesNotContain(id);
        doReturn(new Actor("other-tenant", "alice", Set.of("EMPLOYEE", "APPROVER"))).when(auth).authenticate("other-tenant");
        MockHttpServletResponse otherTenant = sendToken("POST", "/api/v1/applications", "Bearer other-tenant", requestKey, body);
        assertThat(otherTenant.getStatus()).isEqualTo(201);
        assertThat(tree(otherTenant).path("id").asText()).isNotEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_idempotency WHERE idempotency_key=?", Integer.class, requestKey)).isEqualTo(2);
    }

    @Test
    void definitionManagementPermissionIsStillCheckedBeforeReplay() throws Exception {
        String requestKey = key();
        String body = definitionBody("permission-" + UUID.randomUUID(), false);
        JsonNode created = tree(send("POST", "/api/v1/process-definitions", "admin", requestKey, body));
        doReturn(new Actor("demo", "admin", Set.of("EMPLOYEE", "APPROVER"))).when(auth).authenticate("former-admin");
        MockHttpServletResponse response = sendToken("POST", "/api/v1/process-definitions", "Bearer former-admin", requestKey, body);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).doesNotContain(created.path("id").asText());
    }

    @Test
    void expiredKeyDoesNotReplayOrRepeatBusinessWork() throws Exception {
        String requestKey = key();
        String body = createBody();
        String id = tree(send("POST", "/api/v1/applications", "alice", requestKey, body)).path("id").asText();
        jdbc.update("UPDATE request_idempotency SET expires_at=? WHERE tenant_id='demo' AND idempotency_key=?",
                Instant.now().minusSeconds(1).atOffset(ZoneOffset.UTC), requestKey);
        MockHttpServletResponse response = send("POST", "/api/v1/applications", "alice", requestKey, body);
        assertConflict(response, "IDEMPOTENCY_KEY_EXPIRED");
        assertThat(response.getContentAsString()).doesNotContain(id);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void unsuccessfulAuthorizationAndBusinessResponsesLeaveNoKeyAndCanBeRetried() throws Exception {
        String id = draft();
        send("POST", application(id, "submit"), "alice", key(), "{\"expectedVersion\":1}");
        String requestKey = key();
        String body = "{\"expectedVersion\":2}";
        assertThat(sendToken("POST", application(id, "withdraw"), "Bearer invalid", requestKey, body).getStatus()).isEqualTo(401);
        assertNoRecord(requestKey);
        assertThat(send("POST", application(id, "withdraw"), "bob", requestKey, body).getStatus()).isEqualTo(403);
        assertNoRecord(requestKey);
        String staleKey = key();
        assertThat(send("POST", application(id, "withdraw"), "alice", staleKey, "{\"expectedVersion\":1}").getStatus()).isEqualTo(409);
        assertNoRecord(staleKey);
        assertThat(send("POST", application(id, "withdraw"), "alice", requestKey, body).getStatus()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SERIALIZATION", "RESPONSE_RECORD"})
    void failuresAfterBusinessWorkRollBackFlowableSnapshotAuditAndClaimTogether(String stage) throws Exception {
        String id = draft();
        String requestKey = key();
        AtomicBoolean failOnce = new AtomicBoolean(true);
        if (stage.equals("SERIALIZATION")) {
            doAnswer(invocation -> {
                Object result = invocation.callRealMethod();
                if (failOnce.getAndSet(false)) throw new DomainException("DEPENDENCY_UNAVAILABLE", "Response serialization failed");
                return result;
            }).when(json).write(isA(ApplicationResponse.class));
        } else {
            doAnswer(invocation -> {
                invocation.callRealMethod();
                if (failOnce.getAndSet(false)) throw new DomainException("DEPENDENCY_UNAVAILABLE", "Response storage failed");
                return null;
            }).when(records).complete(eq("demo"), eq(requestKey), anyInt(), anyString());
        }
        String body = "{\"expectedVersion\":1}";
        assertThat(send("POST", application(id, "submit"), "alice", requestKey, body).getStatus()).isEqualTo(503);
        assertNoRecord(requestKey);
        assertThat(jdbc.queryForObject("SELECT status FROM approval_application WHERE id=?", String.class, id)).isEqualTo("DRAFT");
        assertThat(runtime.createProcessInstanceQuery().variableValueEquals("applicationId", id).count()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_submission_round WHERE application_id=?", Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=?", Integer.class, id)).isEqualTo(1);
        assertThat(send("POST", application(id, "submit"), "alice", requestKey, body).getStatus()).isEqualTo(200);
        assertThat(runtime.createProcessInstanceQuery().variableValueEquals("applicationId", id).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_submission_round WHERE application_id=?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='SUBMIT'", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void concurrentSameKeyExecutesExactlyOnceAndBothRequestsReceiveTheSameResponse() throws Exception {
        String requestKey = key();
        String body = createBody();
        CyclicBarrier beforeClaim = new CyclicBarrier(2);
        doAnswer(invocation -> {
            beforeClaim.await(5, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(records).claim(eq("demo"), eq(requestKey), anyString(), anyString(), anyString(), any(), any());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> send("POST", "/api/v1/applications", "alice", requestKey, body));
            var second = executor.submit(() -> send("POST", "/api/v1/applications", "alice", requestKey, body));
            MockHttpServletResponse a = first.get(15, TimeUnit.SECONDS);
            MockHttpServletResponse b = second.get(15, TimeUnit.SECONDS);
            assertThat(List.of(a.getStatus(), b.getStatus())).containsExactly(201, 201);
            assertThat(a.getContentAsString()).isEqualTo(b.getContentAsString());
            assertThat(List.of(a.getHeader("Idempotency-Replayed"), b.getHeader("Idempotency-Replayed")))
                    .containsExactlyInAnyOrder("false", "true");
            String id = tree(a).path("id").asText();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=?", Integer.class, id)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_idempotency WHERE tenant_id='demo' AND idempotency_key=?", Integer.class, requestKey)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentDifferentBodiesUsingTheSameKeyCommitOnlyTheWinningRequest() throws Exception {
        String requestKey = key();
        String firstBody = createBody();
        String secondBody = createBody();
        String firstBusinessNo = mapper.readTree(firstBody).path("businessNo").asText();
        String secondBusinessNo = mapper.readTree(secondBody).path("businessNo").asText();
        CyclicBarrier beforeClaim = new CyclicBarrier(2);
        doAnswer(invocation -> {
            beforeClaim.await(5, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(records).claim(eq("demo"), eq(requestKey), anyString(), anyString(), anyString(), any(), any());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> send("POST", "/api/v1/applications", "alice", requestKey, firstBody));
            var second = executor.submit(() -> send("POST", "/api/v1/applications", "alice", requestKey, secondBody));
            MockHttpServletResponse a = first.get(15, TimeUnit.SECONDS);
            MockHttpServletResponse b = second.get(15, TimeUnit.SECONDS);
            assertThat(List.of(a.getStatus(), b.getStatus())).containsExactlyInAnyOrder(201, 409);
            MockHttpServletResponse winner = a.getStatus() == 201 ? a : b;
            MockHttpServletResponse loser = a.getStatus() == 409 ? a : b;
            String winnerBody = a.getStatus() == 201 ? firstBody : secondBody;
            String loserBody = a.getStatus() == 409 ? firstBody : secondBody;
            String winnerBusinessNo = a.getStatus() == 201 ? firstBusinessNo : secondBusinessNo;
            assertConflict(loser, "IDEMPOTENCY_KEY_REUSED");
            String id = tree(winner).path("id").asText();
            assertThat(loser.getContentAsString()).doesNotContain(id);
            assertThat(tree(winner).path("businessNo").asText()).isEqualTo(winnerBusinessNo);
            assertThat(winner.getHeader("Idempotency-Replayed")).isEqualTo("false");
            assertThat(jdbc.queryForList("SELECT business_no FROM approval_application WHERE tenant_id='demo' AND business_no IN (?,?)",
                    String.class, firstBusinessNo, secondBusinessNo)).containsExactly(winnerBusinessNo);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=?", Integer.class, id)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_idempotency WHERE tenant_id='demo' AND idempotency_key=?",
                    Integer.class, requestKey)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT response_body FROM request_idempotency WHERE tenant_id='demo' AND idempotency_key=?",
                    String.class, requestKey)).isEqualTo(winner.getContentAsString());
            assertThat(send("POST", "/api/v1/applications", "alice", requestKey, winnerBody).getContentAsString())
                    .isEqualTo(winner.getContentAsString());
            assertConflict(send("POST", "/api/v1/applications", "alice", requestKey, loserBody), "IDEMPOTENCY_KEY_REUSED");
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void duplicateKeyRaisedByBusinessWorkIsNotMistakenForAnIdempotencyClaimConflict() throws Exception {
        String id = draft();
        String requestKey = key();
        DuplicateKeyException businessConflict = new DuplicateKeyException("Business audit duplicate");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw businessConflict;
        }).when(applicationAudit).record(any());

        Throwable failure = catchThrowable(() -> send("POST", application(id, "submit"), "alice",
                requestKey, "{\"expectedVersion\":1}"));

        assertThat(failure).isInstanceOf(jakarta.servlet.ServletException.class);
        assertThat(failure.getCause()).isSameAs(businessConflict);
        assertNoRecord(requestKey);
        assertThat(jdbc.queryForObject("SELECT status FROM approval_application WHERE id=?", String.class, id)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT version FROM approval_application WHERE id=?", Long.class, id)).isEqualTo(1);
        assertThat(runtime.createProcessInstanceQuery().variableValueEquals("applicationId", id).count()).isZero();
        assertThat(tasks.createTaskQuery().processVariableValueEquals("applicationId", id).count()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_submission_round WHERE application_id=?", Integer.class, id)).isZero();
        assertThat(jdbc.queryForList("SELECT action FROM audit_event WHERE application_id=?", String.class, id)).containsExactly("CREATE");
    }

    @Test
    void waitingConcurrentRequestCanExecuteWhenTheFirstTransactionRollsBack() throws Exception {
        String requestKey = key();
        String body = createBody();
        CountDownLatch firstClaimed = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        AtomicBoolean failOnce = new AtomicBoolean(true);
        doAnswer(invocation -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 2) secondAttempted.countDown();
            Object result = invocation.callRealMethod();
            if (attempt == 1) firstClaimed.countDown();
            return result;
        }).when(records).claim(eq("demo"), eq(requestKey), anyString(), anyString(), anyString(), any(), any());
        doAnswer(invocation -> {
            invocation.callRealMethod();
            if (failOnce.getAndSet(false)) {
                assertThat(secondAttempted.await(5, TimeUnit.SECONDS)).isTrue();
                throw new DomainException("DEPENDENCY_UNAVAILABLE", "First response write failed");
            }
            return null;
        }).when(records).complete(eq("demo"), eq(requestKey), anyInt(), anyString());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> send("POST", "/api/v1/applications", "alice", requestKey, body));
            assertThat(firstClaimed.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> send("POST", "/api/v1/applications", "alice", requestKey, body));
            assertThat(first.get(15, TimeUnit.SECONDS).getStatus()).isEqualTo(503);
            MockHttpServletResponse success = second.get(15, TimeUnit.SECONDS);
            assertThat(success.getStatus()).isEqualTo(201);
            assertThat(success.getHeader("Idempotency-Replayed")).isEqualTo("false");
            assertThat(attempts.get()).isEqualTo(2);
            assertThat(send("POST", "/api/v1/applications", "alice", requestKey, body).getContentAsString()).isEqualTo(success.getContentAsString());
            String id = tree(success).path("id").asText();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=?", Integer.class, id)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private MockHttpServletResponse twice(String method, String path, String user, String key, String body, int status) throws Exception {
        MockHttpServletResponse first = send(method, path, user, key, body);
        MockHttpServletResponse replay = send(method, path, user, key, body);
        assertThat(first.getStatus()).isEqualTo(status);
        assertThat(replay.getStatus()).isEqualTo(status);
        assertThat(first.getHeader("Idempotency-Replayed")).isEqualTo("false");
        assertThat(replay.getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(replay.getContentAsByteArray()).isEqualTo(first.getContentAsByteArray());
        assertThat(replay.getContentType()).startsWith("application/json");
        return first;
    }

    private void assertNoRecord(String key) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_idempotency WHERE tenant_id='demo' AND idempotency_key=?", Integer.class, key)).isZero();
    }

    private void assertConflict(MockHttpServletResponse response, String code) throws Exception {
        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(tree(response).path("code").asText()).isEqualTo(code);
    }

    private MockHttpServletResponse send(String method, String path, String user, String key, String body) throws Exception {
        return sendToken(method, path, token(user), key, body);
    }

    private MockHttpServletResponse sendToken(String method, String path, String token, String key, String body) throws Exception {
        var request = request(HttpMethod.valueOf(method), path).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) request.header("Idempotency-Key", key);
        return mvc.perform(request).andReturn().getResponse();
    }

    private JsonNode tree(MockHttpServletResponse response) throws Exception {
        return mapper.readTree(response.getContentAsString());
    }

    private String draft() throws Exception {
        MockHttpServletResponse response = send("POST", "/api/v1/applications", "alice", key(), createBody());
        assertThat(response.getStatus()).isEqualTo(201);
        return tree(response).path("id").asText();
    }

    private String application(String id, String action) {
        return "/api/v1/applications/" + id + "/" + action;
    }

    private String createBody() throws Exception {
        return mapper.writeValueAsString(Map.of("businessNo", "IDEMPOTENT-" + UUID.randomUUID(), "processKey", "expense-reimbursement",
                "definitionVersion", 1, "title", "幂等申请", "payload", Map.of("amount", 6000)));
    }

    private String definitionBody(String key, boolean update) throws Exception {
        Map<String, Object> graph = Map.of("nodes", List.of(
                Map.of("id", "start", "name", "开始", "type", "START", "properties", Map.of()),
                Map.of("id", "approval", "name", "审批", "type", "USER_TASK", "properties", Map.of("assigneeRule", "role:FINANCE")),
                Map.of("id", "end", "name", "结束", "type", "END", "properties", Map.of())),
                "edges", List.of(Map.of("id", "a", "source", "start", "target", "approval", "condition", ""),
                        Map.of("id", "b", "source", "approval", "target", "end", "condition", "")));
        return mapper.writeValueAsString(update ? Map.of("name", "更新定义", "graph", graph, "expectedRevision", 0)
                : Map.of("key", key, "name", "幂等定义", "graph", graph));
    }

    private String token(String actor) {
        return "Bearer " + auth.login("demo", actor, "demo").token();
    }

    private String key() {
        return UUID.randomUUID().toString();
    }
}
