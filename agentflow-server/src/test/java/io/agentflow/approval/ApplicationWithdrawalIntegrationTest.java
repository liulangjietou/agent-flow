package io.agentflow.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.service.ApplicationAuditPort;
import io.agentflow.approval.service.ProcessRuntimePort;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.definition.DefinitionApplicationService;
import org.apache.ibatis.exceptions.PersistenceException;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTransactionRollbackException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static io.agentflow.definition.DefinitionModels.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static io.agentflow.support.MutationRequests.post;
import static io.agentflow.support.MutationRequests.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实引擎和 HTTP 撤回回归，覆盖事务边界、实例绑定与并发批准。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:application-withdrawal;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
        "agentflow.auth.demo-enabled=true", "agentflow.auth.demo-tenant=demo"
})
@AutoConfigureMockMvc
class ApplicationWithdrawalIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AuthService auth;
    @Autowired RuntimeService runtime;
    @Autowired TaskService tasks;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean ApplicationRepository applications;
    @Autowired ApprovalApplicationFacade facade;
    @Autowired CurrentActor currentActor;
    @Autowired DefinitionApplicationService definitions;
    @MockitoSpyBean ApplicationAuditPort audit;
    @MockitoSpyBean ProcessRuntimePort processRuntime;

    @Test
    void applicantCanWithdrawReviseAndResubmitWithoutRewritingTheWithdrawnRound() throws Exception {
        String key = "withdrawal-version-" + UUID.randomUUID();
        publish(key, "first-approval");
        JsonNode draft = draft(key, Map.of("amount", 6000, "lines", List.of(Map.of("amount", 6000))));
        String id = draft.path("id").asText();
        submit(id, 1).andExpect(status().isOk());
        Task originalTask = task(id);
        JsonNode pending = rounds(id).get(0);

        withdraw(id, "alice", 2, "需要补充材料")
                .andExpect(status().isOk()).andExpect(jsonPath("status").value("WITHDRAWN"))
                .andExpect(jsonPath("version").value(3)).andExpect(jsonPath("roundNo").value(1));
        assertNoActiveProcess(id);
        JsonNode withdrawn = rounds(id).get(0);
        assertThat(withdrawn.path("status").asText()).isEqualTo("WITHDRAWN");
        assertThat(withdrawn.path("reason").asText()).isEqualTo("需要补充材料");
        assertThat(withdrawn.path("completedBy").asText()).isEqualTo("alice");
        assertThat(Instant.parse(withdrawn.path("completedAt").asText()))
                .isAfterOrEqualTo(Instant.parse(pending.path("submittedAt").asText()));
        assertThat(withdrawn.path("payload")).isEqualTo(pending.path("payload"));
        assertThat(withdrawn.path("processInstanceId").asText()).isEqualTo(originalTask.getProcessInstanceId());
        assertThat(withdrawalAudits(id)).isEqualTo(1);
        JsonNode event = mapper.readTree(jdbc.queryForObject("""
                SELECT payload_json FROM audit_event WHERE aggregate_type='Application' AND action='WITHDRAW' AND aggregate_id=?
                """, String.class, id));
        assertThat(event.path("action").asText()).isEqualTo("WITHDRAW");
        assertThat(event.path("actor").asText()).isEqualTo("alice");
        assertThat(event.path("processInstanceId").asText()).isEqualTo(originalTask.getProcessInstanceId());
        assertThat(event.path("comment").asText()).isEqualTo("需要补充材料");
        assertThat(event.path("roundNo").asInt()).isEqualTo(1);

        mvc.perform(put("/api/v1/applications/" + id).header("Authorization", token("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"expectedVersion":3,"title":"补正后重新申请","payload":{"amount":900,"lines":[{"amount":900}]}}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("roundNo").value(1))
                .andExpect(jsonPath("status").value("WITHDRAWN")).andExpect(jsonPath("version").value(4));
        publish(key, "second-version-approval");
        submit(id, 4).andExpect(status().isOk()).andExpect(jsonPath("roundNo").value(2))
                .andExpect(jsonPath("definitionVersion").value(1));
        Task newTask = task(id);
        assertThat(newTask.getTaskDefinitionKey()).isEqualTo("first-approval");
        assertThat(newTask.getProcessInstanceId()).isNotEqualTo(originalTask.getProcessInstanceId());
        assertThat(runtime.getVariable(newTask.getProcessInstanceId(), "formData"))
                .isEqualTo(Map.of("amount", 900, "lines", List.of(Map.of("amount", 900))));
        JsonNode recorded = rounds(id);
        assertThat(recorded).hasSize(2);
        assertThat(recorded.get(0)).isEqualTo(withdrawn);
        assertThat(recorded.get(0).path("payload").path("lines").get(0).path("amount").asInt()).isEqualTo(6000);
        assertThat(recorded.get(1).path("processInstanceId").asText()).isEqualTo(newTask.getProcessInstanceId());
        assertThat(recorded.get(1).path("definitionVersion").asInt()).isEqualTo(1);
        assertThat(withdrawalAudits(id)).isEqualTo(1);
    }

    @Test
    void onlyApplicantInTheSameTenantCanWithdraw() throws Exception {
        String id = submitted().path("id").asText();
        for (String user : List.of("bob", "finance", "admin")) {
            withdraw(id, user, 2, null).andExpect(status().isForbidden());
        }
        Application foreign = Application.draft(UUID.randomUUID(), "other-tenant", "WITHDRAW-" + UUID.randomUUID(),
                "expense-reimbursement", 1, "alice", "其他租户", Map.of("amount", 1));
        applications.save(foreign);
        currentActor.set(new Actor("other-tenant", "alice", Set.of("EMPLOYEE")));
        try { facade.submit(foreign.id(), 1); } finally { currentActor.clear(); }
        Task foreignTask = task(foreign.id().toString());
        withdraw(foreign.id().toString(), "alice", 2, null).andExpect(status().isNotFound());
        assertPending(id);
        Application foreignAfterAttempt = applications.findById("other-tenant", foreign.id()).orElseThrow();
        assertThat(foreignAfterAttempt.version()).isEqualTo(2);
        assertThat(foreignAfterAttempt.status().name()).isEqualTo("IN_APPROVAL");
        assertThat(task(foreign.id().toString()).getId()).isEqualTo(foreignTask.getId());
        assertThat(jdbc.queryForObject("SELECT status FROM approval_submission_round WHERE tenant_id=? AND application_id=?",
                String.class, "other-tenant", foreign.id().toString())).isEqualTo("IN_APPROVAL");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE tenant_id=? AND aggregate_type='Application' AND action='WITHDRAW' AND aggregate_id=?",
                Integer.class, "other-tenant", foreign.id().toString())).isZero();
    }

    @Test
    void staleVersionAndOversizedCommentCannotTerminateTheProcess() throws Exception {
        String id = submitted().path("id").asText();
        withdraw(id, "alice", 1, null).andExpect(status().isConflict());
        withdraw(id, "alice", 2, "字".repeat(2001)).andExpect(status().isBadRequest());
        assertPending(id);
    }

    @Test
    void formPayloadCannotOverrideSystemTenantApplicationOrRoundVariables() throws Exception {
        Map<String, Object> forged = Map.of("amount", 10, "tenantId", "other-tenant",
                "applicationId", "forged-application", "roundNo", 900);
        JsonNode draft = draft("expense-reimbursement", forged);
        String id = draft.path("id").asText();
        submit(id, 1).andExpect(status().isOk());
        String instanceId = task(id).getProcessInstanceId();
        assertThat(runtime.getVariable(instanceId, "tenantId")).isEqualTo("demo");
        assertThat(runtime.getVariable(instanceId, "applicationId")).isEqualTo(id);
        assertThat(runtime.getVariable(instanceId, "roundNo")).isEqualTo(1);
        assertThat(runtime.getVariable(instanceId, "formData")).isEqualTo(forged);
        withdraw(id, "alice", 2, null).andExpect(status().isOk());
        assertNoActiveProcess(id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "RETURNED", "REJECTED", "APPROVED", "WITHDRAWN"})
    void onlyAnInApprovalApplicationCanBeWithdrawn(String targetStatus) throws Exception {
        JsonNode created = draft();
        String id = created.path("id").asText();
        long version = 1;
        if (!targetStatus.equals("DRAFT")) {
            submit(id, 1).andExpect(status().isOk());
            if (targetStatus.equals("WITHDRAWN")) {
                withdraw(id, "alice", 2, null).andExpect(status().isOk());
            } else {
                decide(task(id), targetStatus.equals("RETURNED") ? "RETURN"
                        : targetStatus.equals("REJECTED") ? "REJECT" : "APPROVE", 2)
                        .andExpect(status().isOk());
            }
            version = applications.findById("demo", UUID.fromString(id)).orElseThrow().version();
        }
        withdraw(id, "alice", version, "不可再次撤回").andExpect(status().isUnprocessableEntity());
        assertThat(applications.findById("demo", UUID.fromString(id)).orElseThrow().status().name()).isEqualTo(targetStatus);
        if (!targetStatus.equals("DRAFT")) assertThat(rounds(id).get(0).path("status").asText()).isEqualTo(targetStatus);
        assertNoActiveProcess(id);
    }

    @Test
    void legacyApplicationWithoutSnapshotUsesExactVariablesWithoutInventingHistory() throws Exception {
        JsonNode application = submitted();
        String id = application.path("id").asText();
        Task original = task(id);
        assertThat(runtime.createProcessInstanceQuery().processInstanceId(original.getProcessInstanceId()).singleResult().getTenantId())
                .isNullOrEmpty();
        jdbc.update("DELETE FROM approval_submission_round WHERE tenant_id=? AND application_id=?", "demo", id);
        // 同一业务号的无关引擎实例不能被误撤回；旧记录也必须使用租户、申请和轮次定位。
        String otherId = processRuntime.start(new ProcessRuntimePort.StartProcessCommand("demo", UUID.randomUUID(),
                "expense-reimbursement", 1, 1, application.path("businessNo").asText(), Map.of("amount", 10)))
                .processInstanceId();
        String unrelatedTaskId = tasks.createTaskQuery().processInstanceId(otherId).singleResult().getId();
        withdraw(id, "alice", 2, null).andExpect(status().isOk());
        assertNoActiveProcess(id);
        assertThat(runtime.createProcessInstanceQuery().processInstanceId(otherId).count()).isEqualTo(1);
        assertThat(tasks.createTaskQuery().taskId(unrelatedTaskId).count()).isEqualTo(1);
        assertThat(rounds(id)).isEmpty();
        assertThat(withdrawalAudits(id)).isEqualTo(1);
        assertThat(tasks.createTaskQuery().taskId(original.getId()).count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING", "MULTIPLE", "BINDING", "ROUND", "TENANT", "ENGINE_TENANT"})
    void inconsistentProcessBindingCannotReportSuccessfulWithdrawal(String inconsistency) throws Exception {
        JsonNode application = submitted();
        String id = application.path("id").asText();
        String instanceId = task(id).getProcessInstanceId();
        switch (inconsistency) {
            case "MISSING" -> runtime.deleteProcessInstance(instanceId, "Test missing runtime");
            case "MULTIPLE" -> processRuntime.start(new ProcessRuntimePort.StartProcessCommand("demo", UUID.fromString(id),
                    "expense-reimbursement", 1, 1, application.path("businessNo").asText(), Map.of("amount", 6000)));
            case "BINDING" -> jdbc.update("UPDATE approval_submission_round SET process_instance_id=? WHERE application_id=?",
                    "wrong-instance", id);
            case "ROUND" -> runtime.setVariable(instanceId, "roundNo", 2);
            case "TENANT" -> runtime.setVariable(instanceId, "tenantId", "other-tenant");
            case "ENGINE_TENANT" -> jdbc.update("UPDATE ACT_RU_EXECUTION SET TENANT_ID_=? WHERE PROC_INST_ID_=?",
                    "other-tenant", instanceId);
            default -> throw new AssertionError("Unknown test case");
        }
        withdraw(id, "alice", 2, null).andExpect(status().isConflict())
                .andExpect(jsonPath("code").value("CONCURRENCY_CONFLICT"));
        assertThat(applications.findById("demo", UUID.fromString(id)).orElseThrow().version()).isEqualTo(2);
        assertThat(rounds(id).get(0).path("status").asText()).isEqualTo("IN_APPROVAL");
        assertThat(withdrawalAudits(id)).isZero();
        if (!inconsistency.equals("MISSING")) {
            assertThat(runtime.createProcessInstanceQuery().processInstanceId(instanceId).count()).isEqualTo(1);
        }
    }

    @Test
    void auditWriteFailureRollsBackApplicationProcessRoundAndAuditTogether() throws Exception {
        String id = submitted().path("id").asText();
        String taskId = task(id).getId();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new DomainException("DEPENDENCY_UNAVAILABLE", "Audit storage is unavailable");
        }).when(audit).record(any());
        withdraw(id, "alice", 2, "回滚验证").andExpect(status().isServiceUnavailable());
        assertPending(id);
        assertThat(task(id).getId()).isEqualTo(taskId);
        assertThat(rounds(id).get(0).path("reason").isNull()).isTrue();
    }

    @Test
    void wrappedDatabaseRollbackReturnsConflictWithoutLeavingWithdrawalWrites() throws Exception {
        String id = submitted().path("id").asText();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new PersistenceException("Transaction was rolled back",
                    new SQLTransactionRollbackException("Deadlock victim", "40001"));
        }).when(audit).record(any());
        withdraw(id, "alice", 2, "数据库回滚")
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("CONCURRENCY_CONFLICT"));
        assertPending(id);
    }

    @Test
    void unrelatedDatabaseFailureIsNotReportedAsConcurrencyConflict() throws Exception {
        String id = submitted().path("id").asText();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new PersistenceException("Statement is invalid", new SQLSyntaxErrorException("Invalid SQL", "42000"));
        }).when(audit).record(any());
        assertThatThrownBy(() -> withdraw(id, "alice", 2, "无关数据库错误"))
                .hasRootCauseInstanceOf(SQLSyntaxErrorException.class);
        assertPending(id);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void simultaneousWithdrawalAndFinalApprovalHaveExactlyOneConclusion(boolean withdrawalWins) throws Exception {
        String id = submitted().path("id").asText();
        Task original = task(id);
        var loserReady = new CountDownLatch(1);
        var winnerCommitted = new CountDownLatch(1);
        String loser = withdrawalWins ? "finance" : "alice";
        ApplicationRepository target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(applications);
        // 两笔请求都先通过授权，再让胜者提交；同步点不能放在已持有排他锁的引擎调用中。
        doAnswer(invocation -> {
            if (currentActor.actor().userId().equals(loser)) {
                loserReady.countDown();
                if (!winnerCommitted.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent winner did not commit");
            }
            return invocation.callRealMethod();
        }).when(target).lockById("demo", UUID.fromString(id));
        var executor = Executors.newSingleThreadExecutor();
        int withdrawalStatus;
        try {
            var losingRequest = executor.submit(() -> withdrawalWins
                    ? decide(original, "APPROVE", 2).andReturn().getResponse()
                    : withdraw(id, "alice", 2, "并发撤回").andReturn().getResponse());
            assertThat(loserReady.await(15, TimeUnit.SECONDS)).isTrue();
            var winningResponse = withdrawalWins ? withdraw(id, "alice", 2, "并发撤回").andReturn().getResponse()
                    : decide(original, "APPROVE", 2).andReturn().getResponse();
            winnerCommitted.countDown();
            var losingResponse = losingRequest.get(15, TimeUnit.SECONDS);
            var responses = List.of(winningResponse, losingResponse);
            withdrawalStatus = withdrawalWins ? winningResponse.getStatus() : losingResponse.getStatus();
            assertThat(responses).extracting(response -> response.getStatus())
                    .containsExactlyInAnyOrder(200, 409);
            for (var response : responses) {
                if (response.getStatus() == 409) {
                    assertThat(mapper.readTree(response.getContentAsString()).path("code").asText())
                            .isEqualTo("CONCURRENCY_CONFLICT");
                }
            }
        } finally {
            winnerCommitted.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        Application result = applications.findById("demo", UUID.fromString(id)).orElseThrow();
        assertThat(result.status().name()).isEqualTo(withdrawalStatus == 200 ? "WITHDRAWN" : "APPROVED");
        assertThat(result.version()).isEqualTo(result.status().name().equals("WITHDRAWN") ? 3 : 4);
        assertThat(result.roundNo()).isEqualTo(1);
        assertNoActiveProcess(id);
        JsonNode recorded = rounds(id);
        assertThat(recorded).hasSize(1);
        JsonNode round = recorded.get(0);
        assertThat(round.path("status").asText()).isEqualTo(result.status().name());
        assertThat(round.path("completedBy").asText()).isEqualTo(result.status().name().equals("WITHDRAWN") ? "alice" : "finance");
        assertThat(round.path("reason").asText()).isEqualTo(result.status().name().equals("WITHDRAWN") ? "并发撤回" : "审批意见");
        assertThat(withdrawalAudits(id)).isEqualTo(result.status().name().equals("WITHDRAWN") ? 1 : 0);
        Integer taskAudits = jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE aggregate_type='Task' AND aggregate_id=?",
                Integer.class, original.getId());
        assertThat(taskAudits).isEqualTo(result.status().name().equals("APPROVED") ? 1 : 0);
    }

    private void assertPending(String id) throws Exception {
        Application application = applications.findById("demo", UUID.fromString(id)).orElseThrow();
        assertThat(application.version()).isEqualTo(2);
        assertThat(application.status().name()).isEqualTo("IN_APPROVAL");
        assertThat(runtime.createProcessInstanceQuery().variableValueEquals("applicationId", id).count()).isEqualTo(1);
        assertThat(task(id)).isNotNull();
        assertThat(rounds(id).get(0).path("status").asText()).isEqualTo("IN_APPROVAL");
        assertThat(withdrawalAudits(id)).isZero();
        assertThat(jdbc.queryForList("SELECT action FROM audit_event WHERE tenant_id='demo' AND aggregate_type='Application' AND aggregate_id=? ORDER BY aggregate_version",
                String.class, id)).containsExactly("CREATE", "SUBMIT");
    }

    private void assertNoActiveProcess(String id) {
        assertThat(runtime.createProcessInstanceQuery().variableValueEquals("applicationId", id).count()).isZero();
        assertThat(tasks.createTaskQuery().processVariableValueEquals("applicationId", id).count()).isZero();
    }

    private int withdrawalAudits(String id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE tenant_id='demo' AND aggregate_type='Application' AND action='WITHDRAW' AND aggregate_id=?",
                Integer.class, id);
    }

    private ResultActions withdraw(String id, String actor, long version, String comment) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("expectedVersion", version);
        body.put("comment", comment);
        return mvc.perform(post("/api/v1/applications/" + id + "/withdraw").header("Authorization", token(actor))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
    }

    private JsonNode rounds(String id) throws Exception {
        var result = mvc.perform(get("/api/v1/applications/" + id + "/rounds").header("Authorization", token("alice")))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private Task task(String id) {
        return tasks.createTaskQuery().processVariableValueEquals("applicationId", id).singleResult();
    }

    private ResultActions decide(Task task, String decision, long version) throws Exception {
        return mvc.perform(post("/api/v1/tasks/" + task.getId() + "/actions").header("Authorization", token("finance"))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
                        "action", decision, "expectedVersion", version, "comment", "审批意见"))));
    }

    private JsonNode submitted() throws Exception {
        JsonNode application = draft();
        var result = submit(application.path("id").asText(), 1).andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private ResultActions submit(String id, long version) throws Exception {
        return mvc.perform(post("/api/v1/applications/" + id + "/submit").header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("expectedVersion", version))));
    }

    private JsonNode draft() throws Exception {
        return draft("expense-reimbursement", Map.of("amount", 6000, "lines", List.of(Map.of("amount", 6000))));
    }

    private JsonNode draft(String key, Map<String, Object> payload) throws Exception {
        var created = mvc.perform(post("/api/v1/applications").header("Authorization", token("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("businessNo", "WITHDRAW-" + UUID.randomUUID(),
                                "processKey", key, "definitionVersion", 1, "title", "撤回测试申请",
                                "payload", payload))))
                .andExpect(status().isCreated()).andReturn();
        return mapper.readTree(created.getResponse().getContentAsString());
    }

    private void publish(String key, String taskId) {
        Graph graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node(taskId, "财务审批", NodeType.USER_TASK, Map.of("assigneeRule", "role:FINANCE")),
                new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("a", "start", taskId, ""), new Edge("b", taskId, "end", "")));
        var draft = definitions.create("demo", key, "撤回重提版本测试", graph);
        definitions.publish(new io.agentflow.common.Actor("demo", "test-admin", java.util.Set.of("ADMIN")), draft.id(), draft.revision(), "集成测试发布");
    }

    private String token(String username) {
        return "Bearer " + auth.login("demo", username, "demo").token();
    }
}
