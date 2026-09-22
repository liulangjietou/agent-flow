package io.agentflow.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.definition.DefinitionApplicationService;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 使用真实 HTTP 和 Flowable 验证补正、重提与不可变轮次快照。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:application-resubmission;DB_CLOSE_DELAY=-1",
        "agentflow.auth.demo-enabled=true", "agentflow.auth.demo-tenant=demo"
})
@AutoConfigureMockMvc
class ApplicationResubmissionIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AuthService auth;
    @Autowired RuntimeService runtime;
    @Autowired TaskService tasks;
    @Autowired DefinitionApplicationService definitions;
    @Autowired ApplicationRepository applications;
    @Autowired ApprovalApplicationFacade facade;
    @Autowired CurrentActor currentActor;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean SubmissionRoundRepository roundRepository;

    @Test
    void applicantCanReviseADraftWithoutStartingAnotherRound() throws Exception {
        JsonNode draft = createDraft("expense-reimbursement");

        mvc.perform(put("/api/v1/applications/" + draft.path("id").asText())
                        .header("Authorization", token("alice")).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("expectedVersion", 1,
                                "title", "补正标题", "payload", Map.of("amount", 900)))))
                .andExpect(status().isOk()).andExpect(jsonPath("title").value("补正标题"))
                .andExpect(jsonPath("payload.amount").value(900)).andExpect(jsonPath("version").value(2))
                .andExpect(jsonPath("roundNo").value(1)).andExpect(jsonPath("status").value("DRAFT"));
    }

    @Test
    void draftHasNoSubmittedRounds() throws Exception {
        JsonNode draft = createDraft("expense-reimbursement");

        mvc.perform(get("/api/v1/applications/" + draft.path("id").asText() + "/rounds")
                        .header("Authorization", token("alice")))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void returnedApplicantCanClearAnOptionalFieldAndResubmitExplicitNull() throws Exception {
        String key = "nullable-revision-" + UUID.randomUUID();
        publishDefinition(key, false);
        JsonNode draft = createDraft(key, Map.of("amount", 6000, "description", "原始说明"));
        String id = draft.path("id").asText();
        JsonNode submitted = submit(id, 1);
        JsonNode returned = decide(task(id), "manager", "RETURN", submitted.path("version").asLong(), "请清空无效说明");
        JsonNode originalRound = rounds(id, "alice").get(0);

        var revision = mvc.perform(put("/api/v1/applications/" + id).header("Authorization", token("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"expectedVersion":%d,"title":"清空可选字段",
                                 "payload":{"amount":900,"description":null,"details":{"note":null}}}
                                """.formatted(returned.path("version").asLong())))
                .andExpect(status().isOk()).andReturn();
        JsonNode revised = mapper.readTree(revision.getResponse().getContentAsString());
        assertExplicitNullPayload(revised.path("payload"));
        assertThat(applications.findById("demo", UUID.fromString(id)).orElseThrow().payload())
                .containsEntry("description", null);

        JsonNode resubmitted = submit(id, revised.path("version").asLong());
        assertExplicitNullPayload(resubmitted.path("payload"));
        JsonNode recorded = rounds(id, "alice");
        assertThat(recorded.get(0)).isEqualTo(originalRound);
        assertThat(recorded.get(0).path("payload").path("description").asText()).isEqualTo("原始说明");
        assertExplicitNullPayload(recorded.get(1).path("payload"));
        Task manager = task(id);
        Map<?, ?> formData = (Map<?, ?>) runtime.getVariable(manager.getProcessInstanceId(), "formData");
        assertThat(formData.containsKey("description")).isTrue();
        assertThat(formData.get("description")).isNull();
        decide(manager, "manager", "APPROVE", resubmitted.path("version").asLong(), "补正通过");
        assertThat(task(id).getTaskDefinitionKey()).isEqualTo("low");
    }

    @Test
    void createAndSubmitPreserveExplicitNullInStorageSnapshotAndConditionalRouting() throws Exception {
        String key = "nullable-create-" + UUID.randomUUID();
        publishDefinition(key, false);
        var result = mvc.perform(post("/api/v1/applications").header("Authorization", token("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"businessNo":"NULL-%s","processKey":"%s","definitionVersion":1,
                                 "title":"含可空字段的申请","payload":{"amount":900,"description":null,"details":{"note":null}}}
                                """.formatted(UUID.randomUUID(), key)))
                .andExpect(status().isCreated()).andReturn();
        JsonNode created = mapper.readTree(result.getResponse().getContentAsString());
        assertExplicitNullPayload(created.path("payload"));
        String id = created.path("id").asText();
        assertThat(applications.findById("demo", UUID.fromString(id)).orElseThrow().payload())
                .containsEntry("description", null);

        JsonNode submitted = submit(id, created.path("version").asLong());
        assertExplicitNullPayload(submitted.path("payload"));
        assertExplicitNullPayload(rounds(id, "alice").get(0).path("payload"));
        Task manager = task(id);
        Map<?, ?> formData = (Map<?, ?>) runtime.getVariable(manager.getProcessInstanceId(), "formData");
        assertThat(formData.containsKey("description")).isTrue();
        assertThat(formData.get("description")).isNull();
        decide(manager, "manager", "APPROVE", submitted.path("version").asLong(), "可空字段不影响金额路由");
        assertThat(task(id).getTaskDefinitionKey()).isEqualTo("low");
    }

    private void assertExplicitNullPayload(JsonNode payload) {
        assertThat(payload.has("description")).isTrue();
        assertThat(payload.path("description").isNull()).isTrue();
        assertThat(payload.path("details").has("note")).isTrue();
        assertThat(payload.path("details").path("note").isNull()).isTrue();
    }

    @Test
    void returnedContentCanBeCorrectedAndResubmittedWithoutChangingTheFirstRound() throws Exception {
        String key = "resubmission-" + UUID.randomUUID();
        publishDefinition(key, false);
        JsonNode draft = createDraft(key);
        String id = draft.path("id").asText();
        JsonNode submitted = submit(id, 1);
        Task firstTask = task(id);
        JsonNode firstPending = rounds(id, "alice").get(0);
        assertThat(firstPending.path("processInstanceId").asText()).isEqualTo(firstTask.getProcessInstanceId());
        assertThat(firstPending.path("submittedBy").asText()).isEqualTo("alice");
        assertThat(Instant.parse(firstPending.path("submittedAt").asText())).isBeforeOrEqualTo(Instant.now());
        assertThat(firstPending.get("completedBy").isNull()).isTrue();
        assertThat(firstPending.get("completedAt").isNull()).isTrue();
        assertThat(firstPending.get("reason").isNull()).isTrue();

        JsonNode returned = decide(firstTask, "manager", "RETURN", submitted.path("version").asLong(), "请补充明细");
        JsonNode frozenFirst = rounds(id, "alice").get(0);
        assertThat(frozenFirst.path("status").asText()).isEqualTo("RETURNED");
        assertThat(frozenFirst.path("reason").asText()).isEqualTo("请补充明细");
        assertThat(frozenFirst.path("completedBy").asText()).isEqualTo("manager");
        assertThat(Instant.parse(frozenFirst.path("completedAt").asText()))
                .isAfterOrEqualTo(Instant.parse(frozenFirst.path("submittedAt").asText()));
        assertThatThrownBy(() -> roundRepository.complete("demo", UUID.fromString(id), 1,
                firstTask.getProcessInstanceId(), SubmissionRound.Status.REJECTED, "不得覆盖", "finance", Instant.now()))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo("CONCURRENCY_CONFLICT"));

        Map<String, Object> correctedPayload = Map.of("amount", 900, "lines", List.of(Map.of("amount", 900)));
        var revisedResult = mvc.perform(put("/api/v1/applications/" + id)
                        .header("Authorization", token("alice")).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("expectedVersion", returned.path("version").asLong(),
                                "title", "已补正申请", "payload", correctedPayload))))
                .andExpect(status().isOk()).andExpect(jsonPath("roundNo").value(1))
                .andExpect(jsonPath("status").value("RETURNED"))
                .andExpect(jsonPath("definitionVersion").value(1))
                .andExpect(jsonPath("businessNo").value(draft.path("businessNo").asText())).andReturn();
        JsonNode revised = mapper.readTree(revisedResult.getResponse().getContentAsString());
        assertThat(rounds(id, "alice").get(0)).isEqualTo(frozenFirst);
        publishDefinition(key, true);

        JsonNode resubmitted = submit(id, revised.path("version").asLong());
        assertThat(resubmitted.path("roundNo").asInt()).isEqualTo(2);
        assertThat(resubmitted.path("definitionVersion").asInt()).isEqualTo(1);
        Task nextTask = task(id);
        assertThat(nextTask.getTaskDefinitionKey()).isEqualTo("manager");
        assertThat(nextTask.getProcessInstanceId()).isNotEqualTo(firstTask.getProcessInstanceId());
        assertThat(runtime.getVariable(nextTask.getProcessInstanceId(), "formData")).isEqualTo(correctedPayload);
        JsonNode pendingRounds = rounds(id, "alice");
        assertThat(pendingRounds).hasSize(2);
        assertThat(pendingRounds.get(0)).isEqualTo(frozenFirst);
        assertThat(pendingRounds.get(0).path("payload").path("lines").get(0).path("amount").asInt()).isEqualTo(6000);
        assertThat(pendingRounds.get(1).path("payload")).isEqualTo(mapper.valueToTree(correctedPayload));
        assertThat(pendingRounds.get(1).path("definitionVersion").asLong()).isEqualTo(1);
        assertThat(pendingRounds.get(1).path("title").asText()).isEqualTo("已补正申请");
        assertThat(pendingRounds.get(1).path("processInstanceId").asText()).isEqualTo(nextTask.getProcessInstanceId());

        JsonNode managerApproved = decide(nextTask, "manager", "APPROVE", resubmitted.path("version").asLong(), "补正已确认");
        assertThat(rounds(id, "alice").get(1).path("status").asText()).isEqualTo("IN_APPROVAL");
        Task financeTask = task(id);
        assertThat(financeTask.getTaskDefinitionKey()).isEqualTo("low");
        decide(financeTask, "finance", "APPROVE", managerApproved.path("version").asLong(), "通过");
        JsonNode completedRounds = rounds(id, "manager");
        assertThat(completedRounds.get(0)).isEqualTo(frozenFirst);
        assertThat(completedRounds.get(1).path("status").asText()).isEqualTo("APPROVED");
        assertThat(completedRounds.get(1).path("completedBy").asText()).isEqualTo("finance");
    }

    @Test
    void unauthorizedAndCrossTenantUsersCannotReviseOrReadRounds() throws Exception {
        JsonNode draft = createDraft("expense-reimbursement");
        String id = draft.path("id").asText();
        submit(id, 1);
        for (String username : List.of("bob", "finance", "admin")) {
            mvc.perform(put("/api/v1/applications/" + id).header("Authorization", token(username))
                            .contentType(MediaType.APPLICATION_JSON).content(revision(2)))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/applications/" + id + "/rounds").header("Authorization", token("bob")))
                .andExpect(status().isNotFound());
        assertThat(rounds(id, "finance")).hasSize(1);

        Application foreign = Application.draft(UUID.randomUUID(), "other-tenant", "FOREIGN-" + UUID.randomUUID(),
                "expense-reimbursement", 1, "alice", "其他租户", Map.of("amount", 1));
        applications.save(foreign);
        currentActor.set(new Actor("other-tenant", "alice", Set.of("EMPLOYEE")));
        try { facade.submit(foreign.id(), 1); } finally { currentActor.clear(); }
        mvc.perform(put("/api/v1/applications/" + foreign.id()).header("Authorization", token("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(revision(2)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/applications/" + foreign.id() + "/rounds").header("Authorization", token("alice")))
                .andExpect(status().isNotFound());
    }

    @Test
    void staleEditsAndInApprovalEditsCannotChangeSubmissionSnapshots() throws Exception {
        JsonNode draft = createDraft("expense-reimbursement");
        String id = draft.path("id").asText();
        mvc.perform(put("/api/v1/applications/" + id).header("Authorization", token("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(revision(99)))
                .andExpect(status().isConflict());
        submit(id, 1);
        mvc.perform(put("/api/v1/applications/" + id).header("Authorization", token("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(revision(2)))
                .andExpect(status().isUnprocessableEntity());
        assertThat(rounds(id, "alice").get(0).path("payload").path("amount").asInt()).isEqualTo(6000);
        assertThat(applications.findById("demo", UUID.fromString(id)).orElseThrow().version()).isEqualTo(2);
    }

    @Test
    void duplicateSubmissionDoesNotCreateAnotherRoundOrInstance() throws Exception {
        JsonNode draft = createDraft("expense-reimbursement");
        String id = draft.path("id").asText();
        submit(id, 1);
        mvc.perform(post("/api/v1/applications/" + id + "/submit").header("Authorization", token("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/applications/" + id + "/submit").header("Authorization", token("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":2}"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(rounds(id, "alice")).hasSize(1);
        assertThat(runtime.createProcessInstanceQuery().variableValueEquals("applicationId", id).count()).isEqualTo(1);
    }

    @Test
    void failedEngineStartLeavesNoRoundAndKeepsTheDraftVersion() throws Exception {
        JsonNode draft = createDraft("missing-" + UUID.randomUUID());
        String id = draft.path("id").asText();
        mvc.perform(post("/api/v1/applications/" + id + "/submit").header("Authorization", token("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("code").value("PROCESS_DEFINITION_NOT_FOUND"));
        assertDraftWithoutRoundOrInstance(id);
    }

    @Test
    void failedSnapshotWriteRollsBackTheAlreadyStartedEngineAndApplication() throws Exception {
        JsonNode draft = createDraft("expense-reimbursement");
        String id = draft.path("id").asText();
        doThrow(new DomainException("DEPENDENCY_UNAVAILABLE", "Snapshot storage is unavailable"))
                .when(roundRepository).append(any());
        mvc.perform(post("/api/v1/applications/" + id + "/submit").header("Authorization", token("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}"))
                .andExpect(status().isServiceUnavailable());
        assertDraftWithoutRoundOrInstance(id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RETURN", "REJECT", "APPROVE"})
    void legacyApplicationsWithoutSnapshotsCanStillFinishWithoutInventedHistory(String decision) throws Exception {
        JsonNode draft = createDraft("expense-reimbursement");
        String id = draft.path("id").asText();
        submit(id, 1);
        jdbc.update("DELETE FROM approval_submission_round WHERE tenant_id=? AND application_id=?", "demo", id);

        JsonNode result = decide(task(id), "finance", decision, 2, "旧版本申请处理");

        assertThat(result.path("applicationStatus").asText()).isEqualTo(switch (decision) {
            case "RETURN" -> "RETURNED";
            case "REJECT" -> "REJECTED";
            default -> "APPROVED";
        });
        assertThat(rounds(id, "alice")).isEmpty();
    }

    @Test
    void rejectedRoundRecordsItsConclusion() throws Exception {
        JsonNode draft = createDraft("expense-reimbursement");
        String id = draft.path("id").asText();
        submit(id, 1);
        decide(task(id), "finance", "REJECT", 2, "不符合申请条件");
        JsonNode round = rounds(id, "alice").get(0);
        assertThat(round.path("status").asText()).isEqualTo("REJECTED");
        assertThat(round.path("reason").asText()).isEqualTo("不符合申请条件");
        assertThat(round.path("completedBy").asText()).isEqualTo("finance");
        assertThat(Instant.parse(round.path("completedAt").asText())).isBeforeOrEqualTo(Instant.now());
    }

    private void assertDraftWithoutRoundOrInstance(String id) throws Exception {
        Application persisted = applications.findById("demo", UUID.fromString(id)).orElseThrow();
        assertThat(persisted.status().name()).isEqualTo("DRAFT");
        assertThat(persisted.version()).isEqualTo(1);
        assertThat(persisted.roundNo()).isEqualTo(1);
        assertThat(rounds(id, "alice")).isEmpty();
        assertThat(runtime.createProcessInstanceQuery().variableValueEquals("applicationId", id).count()).isZero();
        assertThat(tasks.createTaskQuery().processVariableValueEquals("applicationId", id).count()).isZero();
    }

    private JsonNode submit(String id, long version) throws Exception {
        var result = mvc.perform(post("/api/v1/applications/" + id + "/submit").header("Authorization", token("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("expectedVersion", version))))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode decide(Task task, String user, String action, long version, String comment) throws Exception {
        var result = mvc.perform(post("/api/v1/tasks/" + task.getId() + "/actions").header("Authorization", token(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("action", action, "expectedVersion", version, "comment", comment))))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode rounds(String id, String user) throws Exception {
        var result = mvc.perform(get("/api/v1/applications/" + id + "/rounds").header("Authorization", token(user)))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private Task task(String id) {
        return tasks.createTaskQuery().processVariableValueEquals("applicationId", id).singleResult();
    }

    private String revision(long version) throws Exception {
        return mapper.writeValueAsString(Map.of("expectedVersion", version, "title", "补正", "payload", Map.of("amount", 100)));
    }

    private void publishDefinition(String key, boolean changedVersion) {
        Graph graph = changedVersion
                ? new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                        new Node("new-version-task", "新版审批", NodeType.USER_TASK, Map.of("assigneeRule", "role:FINANCE")),
                        new Node("end", "结束", NodeType.END, Map.of())),
                        List.of(new Edge("a", "start", "new-version-task", ""), new Edge("b", "new-version-task", "end", "")))
                : new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                        new Node("manager", "部门审批", NodeType.USER_TASK, Map.of("assigneeRule", "role:MANAGER")),
                        new Node("gate", "金额条件", NodeType.EXCLUSIVE_GATEWAY, Map.of()),
                        new Node("low", "普通复核", NodeType.USER_TASK, Map.of("assigneeRule", "role:FINANCE")),
                        new Node("high", "高额复核", NodeType.USER_TASK, Map.of("assigneeRule", "role:FINANCE")),
                        new Node("end", "结束", NodeType.END, Map.of())),
                        List.of(new Edge("a", "start", "manager", ""), new Edge("b", "manager", "gate", ""),
                                new Edge("c", "gate", "high", "amount >= 5000"), new Edge("d", "gate", "low", "", true),
                                new Edge("e", "high", "end", ""), new Edge("f", "low", "end", "")));
        var draft = definitions.create("demo", key, "补正轮次测试", graph);
        definitions.publish("demo", draft.id(), draft.revision());
    }

    private JsonNode createDraft(String key) throws Exception {
        return createDraft(key, Map.of("amount", 6000, "lines", java.util.List.of(Map.of("amount", 6000))));
    }

    private JsonNode createDraft(String key, Map<String, Object> payload) throws Exception {
        var result = mvc.perform(post("/api/v1/applications").header("Authorization", token("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("businessNo", "RESUBMIT-" + UUID.randomUUID(),
                                "processKey", key, "definitionVersion", 1, "title", "原始申请",
                                "payload", payload))))
                .andExpect(status().isCreated()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private String token(String username) {
        return "Bearer " + auth.login("demo", username, "demo").token();
    }
}
