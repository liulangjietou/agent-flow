package io.agentflow.approval;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.service.ApplicationAuditPort;
import io.agentflow.approval.service.ProcessRuntimePort;
import io.agentflow.common.JsonUtil;
import io.agentflow.common.DomainException;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 作废的真实事务、权限和幂等回归；保留旧轮次，提交竞争不能留下孤立流程。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:application-cancellation;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "agentflow.auth.demo-enabled=true"})
@AutoConfigureMockMvc
class ApplicationCancellationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;
    @Autowired JdbcTemplate jdbc;
    @Autowired RuntimeService runtime;
    @Autowired TaskService tasks;
    @MockitoSpyBean ApplicationRepository applications;
    @MockitoSpyBean ApplicationAuditPort audit;
    @MockitoSpyBean ProcessRuntimePort processRuntime;

    @Test
    void incompleteDraftCanBeCancelledOnceWithoutSubmissionAndRemainsReadable() throws Exception {
        JsonNode draft = draft(); String id = draft.path("id").asText(), key = UUID.randomUUID().toString();
        var first = cancel(id, "alice", 1, "不再申请", key);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        JsonNode cancelled = tree(first); assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.path("version").asLong()).isEqualTo(2); assertThat(cancelled.path("roundNo").asInt()).isEqualTo(1);
        assertThat(cancelled.path("payload")).isEqualTo(draft.path("payload"));
        var replay = cancel(id, "alice", 1, "不再申请", key);
        assertThat(tree(replay)).isEqualTo(cancelled); assertThat(replay.getResponse().getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(auditCount(id)).isEqualTo(1); assertNoProcess(id); assertThat(rounds(id)).isEmpty();
        assertThat(read("/api/v1/applications/" + id)).isEqualTo(cancelled);
        var event = read("/api/v1/applications/" + id + "/audit?action=CANCEL").path("items").get(0);
        assertThat(event.path("actor").asText()).isEqualTo("alice"); assertThat(event.path("previousStatus").asText()).isEqualTo("DRAFT");
        assertThat(event.path("currentStatus").asText()).isEqualTo("CANCELLED"); assertThat(event.path("comment").asText()).isEqualTo("不再申请");
        assertThat(read("/api/v1/workspace/applications?status=CANCELLED").path("items").findValuesAsText("id")).contains(id);
        assertThat(read("/api/v1/workspace/applications?status=DRAFT").path("items").findValuesAsText("id")).doesNotContain(id);
        mvc.perform(write(post("/api/v1/applications/" + id + "/submit"), "alice", UUID.randomUUID().toString(), Map.of("expectedVersion", 2)))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(write(put("/api/v1/applications/" + id), "alice", UUID.randomUUID().toString(), Map.of("expectedVersion", 2, "title", "不得覆盖", "payload", Map.of())))
                .andExpect(status().isUnprocessableEntity());
        assertThat(cancel(id, "alice", 2, null, UUID.randomUUID().toString()).getResponse().getStatus()).isEqualTo(422);
        assertThat(auditCount(id)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RETURN", "WITHDRAW"})
    void cancellationPreservesRealCompletedRoundsAndDoesNotChangeOperationalConclusions(String action) throws Exception {
        String id = draft().path("id").asText();
        mvc.perform(write(post("/api/v1/applications/" + id + "/submit"), "alice", UUID.randomUUID().toString(), Map.of("expectedVersion", 1))).andExpect(status().isOk());
        var task = tasks.createTaskQuery().processVariableValueEquals("applicationId", id).singleResult();
        if (action.equals("RETURN")) {
            mvc.perform(write(post("/api/v1/tasks/" + task.getId() + "/actions"), "finance", UUID.randomUUID().toString(), Map.of("action", "RETURN", "expectedVersion", 2, "comment", "材料待补充"))).andExpect(status().isOk());
        } else {
            mvc.perform(write(post("/api/v1/applications/" + id + "/withdraw"), "alice", UUID.randomUUID().toString(), Map.of("expectedVersion", 2, "comment", "计划变更"))).andExpect(status().isOk());
        }
        JsonNode before = rounds(id), operations = readAs("/api/v1/operations/approvals", "admin");
        assertThat(before).hasSize(1);
        assertThat(cancel(id, "alice", 3, "无需重提", UUID.randomUUID().toString()).getResponse().getStatus()).isEqualTo(200);
        assertThat(rounds(id)).isEqualTo(before); assertNoProcess(id);
        var after = readAs("/api/v1/operations/approvals", "admin");
        assertThat(after.path("metrics")).isEqualTo(operations.path("metrics"));
        assertThat(after.path("pendingTasks")).isEqualTo(operations.path("pendingTasks"));
        if (action.equals("RETURN")) {
            mvc.perform(get("/api/v1/applications/" + id).header("Authorization", token("finance"))).andExpect(status().isOk());
        }
    }

    @Test
    void cancellationRequiresApplicantTenantVersionAndValidRequest() throws Exception {
        String id = draft().path("id").asText();
        for (String user : List.of("bob", "manager", "admin")) assertThat(cancel(id, user, 1, null, UUID.randomUUID().toString()).getResponse().getStatus()).isEqualTo(403);
        var foreign = Application.draft(UUID.randomUUID(), "foreign", "FOREIGN-" + UUID.randomUUID(), "leave", 1, "alice", "别的租户", Map.of());
        applications.save(foreign);
        assertThat(cancel(foreign.id().toString(), "alice", 1, null, UUID.randomUUID().toString()).getResponse().getStatus()).isEqualTo(404);
        assertThat(cancel(id, "alice", 0, null, UUID.randomUUID().toString()).getResponse().getStatus()).isEqualTo(409);
        assertThat(cancel(id, "alice", 1, "字".repeat(2001), UUID.randomUUID().toString()).getResponse().getStatus()).isEqualTo(400);
        mvc.perform(write(post("/api/v1/applications/" + id + "/cancel"), "alice", UUID.randomUUID().toString(), Map.of())).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/applications/" + id + "/cancel").header("Authorization", token("alice")).contentType("application/json").content(json.write(Map.of("expectedVersion", 1)))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/applications/" + id + "/cancel").contentType("application/json").content(json.write(Map.of("expectedVersion", 1)))).andExpect(status().isUnauthorized());
        assertThat(read("/api/v1/applications/" + id).path("status").asText()).isEqualTo("DRAFT"); assertThat(auditCount(id)).isZero();
    }

    @Test
    void activeApprovalCannotBeCancelledOrHaveItsTaskRemoved() throws Exception {
        String id = draft().path("id").asText();
        mvc.perform(write(post("/api/v1/applications/" + id + "/submit"), "alice", UUID.randomUUID().toString(), Map.of("expectedVersion", 1))).andExpect(status().isOk());
        String taskId = tasks.createTaskQuery().processVariableValueEquals("applicationId", id).singleResult().getId();
        assertThat(cancel(id, "alice", 2, null, UUID.randomUUID().toString()).getResponse().getStatus()).isEqualTo(422);
        assertThat(tasks.createTaskQuery().taskId(taskId).count()).isEqualTo(1); assertThat(rounds(id).get(0).path("status").asText()).isEqualTo("IN_APPROVAL");
        assertThat(auditCount(id)).isZero();
    }

    @Test
    void auditFailureRollsBackCancellationAndOriginalIdempotencyKeyCanRetry() throws Exception {
        String id = draft().path("id").asText(), key = UUID.randomUUID().toString();
        AtomicBoolean failing = new AtomicBoolean(true);
        doAnswer(invocation -> {
            ApplicationAuditPort.ApplicationOperation operation = invocation.getArgument(0);
            if (operation.applicationId().toString().equals(id) && operation.action() == ApplicationAuditPort.Action.CANCEL && failing.get()) {
                invocation.callRealMethod();
                throw new DomainException("DEPENDENCY_UNAVAILABLE", "Injected cancellation audit failure");
            }
            return invocation.callRealMethod();
        }).when(audit).record(any());
        assertThat(cancel(id, "alice", 1, "回滚验收", key).getResponse().getStatus()).isEqualTo(503);
        assertThat(read("/api/v1/applications/" + id).path("status").asText()).isEqualTo("DRAFT"); assertThat(auditCount(id)).isZero();
        failing.set(false);
        assertThat(cancel(id, "alice", 1, "回滚验收", key).getResponse().getStatus()).isEqualTo(200);
        assertThat(auditCount(id)).isEqualTo(1);
    }

    @Test
    void concurrentSameKeyCancellationProducesOneVersionAndOneAudit() throws Exception {
        String id = draft().path("id").asText(), key = UUID.randomUUID().toString();
        var executor = Executors.newFixedThreadPool(4);
        var start = new CountDownLatch(1);
        try {
            var futures = java.util.stream.IntStream.range(0, 4).mapToObj(index -> executor.submit(() -> { start.await(); return cancel(id, "alice", 1, "并发验收", key); })).toList();
            start.countDown();
            for (var future : futures) {
                var result = future.get(20, TimeUnit.SECONDS);
                assertThat(result.getResponse().getStatus()).isEqualTo(200); assertThat(tree(result).path("version").asLong()).isEqualTo(2);
            }
        } finally { executor.shutdownNow(); }
        assertThat(auditCount(id)).isEqualTo(1); assertNoProcess(id);
    }

    @Test
    void concurrentSubmitAndCancelHaveOneWinnerWithoutOrphanedRuntime() throws Exception {
        String id = draft().path("id").asText();
        var executor = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var cancelled = executor.submit(() -> { start.await(); return cancel(id, "alice", 1, null, UUID.randomUUID().toString()).getResponse().getStatus(); });
            var submitted = executor.submit(() -> { start.await(); return mvc.perform(write(post("/api/v1/applications/" + id + "/submit"), "alice", UUID.randomUUID().toString(), Map.of("expectedVersion", 1))).andReturn().getResponse().getStatus(); });
            start.countDown(); assertThat(List.of(cancelled.get(20, TimeUnit.SECONDS), submitted.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        } finally { executor.shutdownNow(); }
        var stored = read("/api/v1/applications/" + id); assertThat(stored.path("version").asLong()).isEqualTo(2);
        if (stored.path("status").asText().equals("CANCELLED")) { assertNoProcess(id); assertThat(rounds(id)).isEmpty(); assertThat(auditCount(id)).isEqualTo(1); }
        else {
            assertThat(stored.path("status").asText()).isEqualTo("IN_APPROVAL");
            assertThat(runtime.createProcessInstanceQuery().variableValueEquals("applicationId", id).count()).isEqualTo(1);
            assertThat(rounds(id)).hasSize(1); assertThat(auditCount(id)).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cancellationWaitsForSubmissionAndDoesNotLeaveAnOrphanedRuntime(boolean failSubmission) throws Exception {
        String id = draft().path("id").asText();
        var engineStarted = new CountDownLatch(1); var continueSubmission = new CountDownLatch(1);
        var cancellationWaiting = new CountDownLatch(1); var submissionLocked = new AtomicBoolean();
        ApplicationRepository target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(applications);
        doAnswer(invocation -> {
            if (!submissionLocked.compareAndSet(false, true)) cancellationWaiting.countDown();
            return invocation.callRealMethod();
        }).when(target).lockById("demo", UUID.fromString(id));
        doAnswer(invocation -> {
            var result = invocation.callRealMethod();
            ProcessRuntimePort.StartProcessCommand command = invocation.getArgument(0);
            if (command.applicationId().toString().equals(id)) {
                engineStarted.countDown();
                if (!continueSubmission.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Submission barrier timed out");
                if (failSubmission) throw new DomainException("DEPENDENCY_UNAVAILABLE", "Injected failure after engine start");
            }
            return result;
        }).when(processRuntime).start(any());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var submission = executor.submit(() -> mvc.perform(write(post("/api/v1/applications/" + id + "/submit"), "alice", UUID.randomUUID().toString(), Map.of("expectedVersion", 1))).andReturn());
            assertThat(engineStarted.await(15, TimeUnit.SECONDS)).isTrue();
            var cancellation = executor.submit(() -> cancel(id, "alice", 1, "并发作废", UUID.randomUUID().toString()));
            assertThat(cancellationWaiting.await(15, TimeUnit.SECONDS)).isTrue();
            assertThat(cancellation.isDone()).isFalse();
            continueSubmission.countDown();
            assertThat(submission.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(failSubmission ? 503 : 200);
            assertThat(cancellation.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(failSubmission ? 200 : 409);
        } finally {
            continueSubmission.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        if (failSubmission) {
            assertNoProcess(id); assertThat(rounds(id)).isEmpty(); assertThat(auditCount(id)).isEqualTo(1);
        } else {
            assertThat(runtime.createProcessInstanceQuery().variableValueEquals("applicationId", id).count()).isEqualTo(1);
            assertThat(tasks.createTaskQuery().processVariableValueEquals("applicationId", id).count()).isEqualTo(1);
            assertThat(rounds(id)).hasSize(1); assertThat(auditCount(id)).isZero();
        }
        var application = read("/api/v1/applications/" + id);
        assertThat(application.path("status").asText()).isEqualTo(failSubmission ? "CANCELLED" : "IN_APPROVAL");
        assertThat(application.path("version").asLong()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='SUBMIT'", Integer.class, id))
                .isEqualTo(failSubmission ? 0 : 1);
    }

    private JsonNode draft() throws Exception {
        return tree(mvc.perform(write(post("/api/v1/applications"), "alice", UUID.randomUUID().toString(), Map.of("businessNo", "CANCEL-" + UUID.randomUUID(),
                "processKey", "expense-reimbursement", "definitionVersion", 1, "title", "作废验收", "payload", Map.of("amount", 1))))
                .andExpect(status().isCreated()).andReturn());
    }
    private MvcResult cancel(String id, String user, long version, String comment, String key) throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>(); body.put("expectedVersion", version); body.put("comment", comment);
        return mvc.perform(write(post("/api/v1/applications/" + id + "/cancel"), user, key, body)).andReturn();
    }
    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, String user, String key, Object body) { return request.header("Authorization", token(user)).header("Idempotency-Key", key).contentType("application/json").content(json.write(body)); }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private JsonNode tree(MvcResult result) throws Exception { return json.read(result.getResponse().getContentAsString(), JsonNode.class); }
    private JsonNode read(String path) throws Exception { return readAs(path, "alice"); }
    private JsonNode readAs(String path, String user) throws Exception { return tree(mvc.perform(get(path).header("Authorization", token(user))).andExpect(status().isOk()).andReturn()); }
    private JsonNode rounds(String id) throws Exception { return read("/api/v1/applications/" + id + "/rounds"); }
    private int auditCount(String id) { return jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='CANCEL'", Integer.class, id); }
    private void assertNoProcess(String id) {
        assertThat(runtime.createProcessInstanceQuery().variableValueEquals("applicationId", id).count()).isZero();
        assertThat(tasks.createTaskQuery().processVariableValueEquals("applicationId", id).count()).isZero();
    }
}
