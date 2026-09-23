package io.agentflow.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.definition.DefinitionApplicationService;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static io.agentflow.support.MutationRequests.post;
import static io.agentflow.support.MutationRequests.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 申请历史查询的真实 HTTP、引擎历史和审计关联回归。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:application-history;DB_CLOSE_DELAY=-1",
        "agentflow.auth.demo-enabled=true", "agentflow.auth.demo-tenant=demo"
})
@AutoConfigureMockMvc
class ApplicationHistoryIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AuthService auth;
    @Autowired RuntimeService runtime;
    @Autowired HistoryService history;
    @Autowired TaskService tasks;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationRepository applications;
    @Autowired ApprovalApplicationFacade facade;
    @Autowired CurrentActor actor;
    @Autowired DefinitionApplicationService definitions;

    @ParameterizedTest
    @ValueSource(strings = {"timeline", "audit"})
    void applicantCanReadTheApplicationHistory(String endpoint) throws Exception {
        String id = draft("expense-reimbursement");
        JsonNode page = page(id, endpoint, "alice", Map.of());
        assertThat(page.path("items")).hasSize(1);
        assertThat(page.path("items").get(0).path("action").asText()).isEqualTo("CREATE");
        assertThat(page.path("items").get(0).path("actor").asText()).isEqualTo("alice");
        assertThat(page.has("nextCursor")).isTrue();
        assertThat(page.path("nextCursor").isNull()).isTrue();
        assertThat(page.toString()).doesNotContain("sensitive-form-value", "payload", "formData", "tenantId");
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVE", "WITHDRAW"})
    void twoRoundsKeepOperationsSeparateFromNodeAndRoundConclusions(String finalAction) throws Exception {
        String key = "history-routing-" + UUID.randomUUID();
        publish(key);
        String id = draft(key);
        submit(id, 1);
        Task first = task(id);
        act(first, "manager", "RETURN", 2);
        revise(id, 3);
        submit(id, 4);
        Task second = task(id);
        act(second, "manager", "APPROVE", 5);
        Task finance = task(id);
        if (finalAction.equals("APPROVE")) act(finance, "finance", "APPROVE", 6);
        else withdraw(id, 6);

        List<JsonNode> events = items(page(id, "timeline", "alice", Map.of("limit", "100")));
        List<JsonNode> engine = events.stream().filter(event -> source(event, "PROCESS_HISTORY")).toList();
        assertThat(engine).anySatisfy(event -> assertThat(event.path("nodeType").asText()).isEqualTo("START"));
        assertThat(engine).anySatisfy(event -> assertThat(event.path("nodeType").asText()).isEqualTo("GATEWAY"));
        assertThat(engine).allSatisfy(event -> {
            assertThat(event.path("action").asText()).isIn("NODE_STARTED", "NODE_ENDED");
            assertThat(event.hasNonNull("actor")).isFalse();
            assertThat(event.hasNonNull("previousStatus")).isFalse();
            assertThat(event.hasNonNull("currentStatus")).isFalse();
        });
        assertThat(events.stream().filter(event -> "SUBMIT".equals(event.path("action").asText())))
                .hasSize(2).allSatisfy(event -> assertThat(event.path("source").asText()).isEqualTo("APPLICATION_AUDIT"));
        assertThat(events.stream().filter(event -> "ROUND_RETURNED".equals(event.path("action").asText())))
                .singleElement().satisfies(event -> {
                    assertThat(event.path("roundNo").asInt()).isEqualTo(1);
                    assertThat(event.path("comment").asText()).isEqualTo("真实处理意见");
                });
        assertThat(events.stream().filter(event -> ("ROUND_" + (finalAction.equals("APPROVE") ? "APPROVED" : "WITHDRAWN"))
                .equals(event.path("action").asText()))).hasSize(1);
        if (finalAction.equals("APPROVE")) {
            assertThat(engine).anySatisfy(event -> assertThat(event.path("nodeType").asText()).isEqualTo("END"));
        }
        assertThat(engine.stream().filter(event -> event.path("roundNo").asInt() == 1))
                .allSatisfy(event -> assertThat(event.path("processInstanceId").asText()).isEqualTo(first.getProcessInstanceId()));
        assertThat(engine.stream().filter(event -> event.path("roundNo").asInt() == 2))
                .allSatisfy(event -> assertThat(event.path("processInstanceId").asText()).isEqualTo(second.getProcessInstanceId()));
        assertThat(page(id, "timeline", "manager", Map.of("roundNo", "1")).path("items"))
                .allSatisfy(event -> assertThat(event.path("roundNo").asInt()).isEqualTo(1));
        assertThat(items(page(id, "audit", "alice", Map.of("limit", "100"))))
                .allSatisfy(event -> assertThat(event.path("source").asText()).isIn("APPLICATION_AUDIT", "TASK_AUDIT"));
        assertThat(events.stream().filter(event -> "RETURN".equals(event.path("action").asText())))
                .singleElement().satisfies(event -> {
                    assertThat(event.path("previousStatus").asText()).isEqualTo("IN_APPROVAL");
                    assertThat(event.path("currentStatus").asText()).isEqualTo("RETURNED");
                });
        assertThat(events.stream().filter(event -> source(event, "SUBMISSION_SNAPSHOT"))).allSatisfy(event -> {
            assertThat(event.hasNonNull("previousStatus")).isFalse();
            assertThat(event.hasNonNull("currentStatus")).isFalse();
        });
        assertThat(events).extracting(event -> event.path("id").asText()).doesNotHaveDuplicates();
    }

    @Test
    void activeNodeHasNoInventedCompletionOrOperator() throws Exception {
        String id = draft("expense-reimbursement");
        submit(id, 1);
        List<JsonNode> nodes = items(page(id, "timeline", "alice", Map.of())).stream()
                .filter(event -> source(event, "PROCESS_HISTORY") && "USER_TASK".equals(event.path("nodeType").asText())).toList();
        assertThat(nodes).singleElement().satisfies(event -> {
            assertThat(event.path("action").asText()).isEqualTo("NODE_STARTED");
            assertThat(event.hasNonNull("actor")).isFalse();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"timeline", "audit"})
    void unauthorizedAndCrossTenantReadersCannotUseHistoryToBypassDetailPermissions(String endpoint) throws Exception {
        String id = draft("expense-reimbursement");
        submit(id, 1);
        mvc.perform(get(path(id, endpoint)).header("Authorization", token("bob"))).andExpect(status().isNotFound());
        page(id, endpoint, "finance", Map.of());
        act(task(id), "finance", "RETURN", 2);
        page(id, endpoint, "finance", Map.of());
        Application foreign = Application.draft(UUID.randomUUID(), "other-tenant", "HISTORY-" + UUID.randomUUID(),
                "expense-reimbursement", 1, "alice", "其他租户申请", Map.of("amount", 1));
        applications.save(foreign);
        actor.set(new Actor("other-tenant", "alice", Set.of("EMPLOYEE")));
        try { facade.submit(foreign.id(), 1); } finally { actor.clear(); }
        mvc.perform(get(path(foreign.id().toString(), endpoint)).header("Authorization", token("alice")))
                .andExpect(status().isNotFound());
    }

    @Test
    void legacyHistoryUsesVerifiedTaskIdsWithoutInventingSnapshotsOrSubmitActions() throws Exception {
        String id = draft("expense-reimbursement");
        submit(id, 1);
        Task original = task(id);
        act(original, "finance", "RETURN", 2);
        assertThat(history.createHistoricProcessInstanceQuery().processInstanceId(original.getProcessInstanceId()).singleResult().getTenantId())
                .isNullOrEmpty();
        jdbc.update("DELETE FROM approval_submission_round WHERE application_id=?", id);
        jdbc.update("DELETE FROM audit_event WHERE aggregate_type='Application' AND application_id=?", id);
        jdbc.update("UPDATE audit_event SET application_id=NULL, action=NULL, payload_json=? WHERE aggregate_type='Task' AND aggregate_id=?",
                mapper.writeValueAsString(Map.of("action", "RETURN", "actor", "finance", "comment", "真实旧事件")), original.getId());
        JsonNode audit = page(id, "audit", "alice", Map.of());
        assertThat(audit.path("items")).singleElement().satisfies(event -> {
            assertThat(event.path("action").asText()).isEqualTo("RETURN");
            assertThat(event.path("comment").asText()).isEqualTo("真实旧事件");
            assertThat(event.path("taskId").asText()).isEqualTo(original.getId());
            assertThat(event.path("roundNo").asInt()).isEqualTo(1);
            assertThat(event.hasNonNull("previousStatus")).isFalse();
            assertThat(event.hasNonNull("currentStatus")).isFalse();
        });
        List<JsonNode> events = items(page(id, "timeline", "alice", Map.of()));
        assertThat(events).noneSatisfy(event -> assertThat(event.path("source").asText()).isEqualTo("SUBMISSION_SNAPSHOT"));
        assertThat(events).noneSatisfy(event -> assertThat(event.path("action").asText()).isIn("SUBMIT", "APPROVE"));
        assertThat(events).anySatisfy(event -> assertThat(event.path("action").asText()).isEqualTo("NODE_ENDED"));
    }

    @Test
    void snapshotSubmissionIsOnlyAFallbackWhenTheActualSubmitAuditIsMissing() throws Exception {
        String id = draft("expense-reimbursement");
        submit(id, 1);
        jdbc.update("DELETE FROM audit_event WHERE application_id=? AND action='SUBMIT'", id);
        assertThat(items(page(id, "timeline", "alice", Map.of())).stream()
                .filter(event -> "SUBMIT".equals(event.path("action").asText())))
                .singleElement().satisfies(event -> assertThat(event.path("source").asText()).isEqualTo("SUBMISSION_SNAPSHOT"));
        assertThat(items(page(id, "audit", "alice", Map.of("action", "SUBMIT")))).isEmpty();
    }

    @Test
    void directlyLinkedAuditsSurviveEngineHistoryRetentionWithoutInventingLegacyLinks() throws Exception {
        String id = draft("expense-reimbursement");
        submit(id, 1);
        Task original = task(id);
        jdbc.update("DELETE FROM approval_submission_round WHERE application_id=?", id);
        act(original, "finance", "CLAIM", 2);
        withdraw(id, 3);
        assertThat(page(id, "audit", "alice", Map.of()).path("items")).hasSize(4);
        history.deleteHistoricProcessInstance(original.getProcessInstanceId());
        insertAudit("demo", null, "Task", original.getId(), null,
                Map.of("action", "REJECT", "actor", "finance", "comment", "old-unverified-event"));

        JsonNode audit = page(id, "audit", "alice", Map.of());
        assertThat(audit.path("items")).hasSize(4);
        assertThat(items(audit)).extracting(event -> event.path("action").asText())
                .containsExactlyInAnyOrder("CREATE", "SUBMIT", "CLAIM", "WITHDRAW");
        assertThat(audit.toString()).doesNotContain("old-unverified-event");
        assertThat(items(audit).stream().filter(event -> "CLAIM".equals(event.path("action").asText())))
                .singleElement().satisfies(event -> {
                    assertThat(event.path("actor").asText()).isEqualTo("finance");
                    assertThat(event.path("taskId").asText()).isEqualTo(original.getId());
                });
    }

    @Test
    void emptyHistoricalEvidenceDoesNotTurnCurrentStateIntoFictionalEvents() throws Exception {
        String id = draft("expense-reimbursement");
        jdbc.update("DELETE FROM audit_event WHERE application_id=?", id);
        for (String endpoint : List.of("timeline", "audit")) {
            JsonNode result = page(id, endpoint, "alice", Map.of());
            assertThat(result.path("items")).isEmpty();
            assertThat(result.path("nextCursor").isNull()).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ENGINE_TENANT", "TENANT_VARIABLE", "APPLICATION_VARIABLE", "ROUND_BINDING"})
    void mismatchedEngineAssociationsCannotExposeNodeHistoryOrLegacyTaskEvents(String mismatch) throws Exception {
        String id = draft("expense-reimbursement");
        submit(id, 1);
        Task original = task(id);
        insertAudit("demo", null, "Task", original.getId(), null,
                Map.of("action", "CLAIM", "actor", "finance", "comment", "untrusted-linked-content"));
        switch (mismatch) {
            case "ENGINE_TENANT" -> jdbc.update("UPDATE ACT_HI_PROCINST SET TENANT_ID_=? WHERE ID_=?", "other-tenant", original.getProcessInstanceId());
            case "TENANT_VARIABLE" -> runtime.setVariable(original.getProcessInstanceId(), "tenantId", "other-tenant");
            case "APPLICATION_VARIABLE" -> runtime.setVariable(original.getProcessInstanceId(), "applicationId", UUID.randomUUID().toString());
            case "ROUND_BINDING" -> runtime.setVariable(original.getProcessInstanceId(), "roundNo", 2);
            default -> throw new AssertionError("Unknown test case");
        }
        JsonNode result = page(id, "timeline", "alice", Map.of());
        assertThat(items(result).stream().filter(event -> source(event, "PROCESS_HISTORY"))).isEmpty();
        assertThat(result.toString()).doesNotContain("untrusted-linked-content");
    }

    @Test
    void auditQueryRejectsForeignTenantTaskAndPayloadAssociations() throws Exception {
        String id = draft("expense-reimbursement");
        submit(id, 1);
        Task ownTask = task(id);
        String otherId = draft("expense-reimbursement");
        submit(otherId, 1);
        Task otherTask = task(otherId);
        // 业务号相同也不能关联别的申请实例。
        jdbc.update("UPDATE ACT_HI_PROCINST SET BUSINESS_KEY_=(SELECT business_no FROM approval_application WHERE id=?) WHERE ID_=?",
                id, otherTask.getProcessInstanceId());
        insertAudit("other-tenant", id, "Application", id, "REVISE", Map.of("action", "REVISE", "comment", "foreign-tenant"));
        insertAudit("demo", null, "Task", otherTask.getId(), null, Map.of("action", "APPROVE", "comment", "foreign-task"));
        insertAudit("demo", id, "Task", otherTask.getId(), "APPROVE", Map.of("action", "APPROVE", "applicationId", id,
                "roundNo", 1, "processInstanceId", otherTask.getProcessInstanceId(), "comment", "foreign-instance"));
        insertAudit("demo", id, "Task", ownTask.getId(), "APPROVE", Map.of("action", "APPROVE", "applicationId", otherId,
                "comment", "foreign-payload"));
        JsonNode result = page(id, "timeline", "alice", Map.of("limit", "100"));
        assertThat(result.toString()).doesNotContain("foreign-tenant", "foreign-task", "foreign-instance", "foreign-payload",
                otherTask.getProcessInstanceId(), otherTask.getId());
    }

    @Test
    void filteringAndStablePaginationKeepEveryEventSharingTheSameTimestamp() throws Exception {
        String id = draft("expense-reimbursement");
        revise(id, 1);
        revise(id, 2);
        Instant exact = Instant.parse("2026-09-22T12:00:00Z");
        jdbc.update("UPDATE audit_event SET occurred_at=? WHERE application_id=?", Timestamp.from(exact), id);
        JsonNode matching = page(id, "audit", "alice", Map.of("from", exact.toString(), "to", exact.toString(), "action", "REVISE", "roundNo", "1"));
        assertThat(matching.path("items")).hasSize(2);
        assertThat(page(id, "audit", "alice", Map.of("from", exact.plusSeconds(1).toString())).path("items")).isEmpty();
        mvc.perform(get(path(id, "audit")).header("Authorization", token("alice"))
                        .param("from", exact.plusSeconds(1).toString()).param("to", exact.toString())).andExpect(status().isBadRequest());
        for (String endpoint : List.of("timeline", "audit")) {
            List<JsonNode> expected = items(page(id, endpoint, "alice", Map.of("limit", "100")));
            List<JsonNode> collected = new ArrayList<>();
            JsonNode current = page(id, endpoint, "alice", Map.of("limit", "1"));
            String firstCursor = current.path("nextCursor").asText();
            for (int pages = 0; pages < 4; pages++) {
                collected.addAll(items(current));
                if (current.path("nextCursor").isNull()) break;
                current = page(id, endpoint, "alice", Map.of("limit", "1", "cursor", current.path("nextCursor").asText()));
            }
            assertThat(collected).isEqualTo(expected);
            assertThat(collected).extracting(event -> event.path("id").asText()).doesNotHaveDuplicates();
            assertThat(collected).extracting(event -> event.path("sequence").asInt()).containsExactly(1, 2, 3);
            String other = draft("expense-reimbursement");
            mvc.perform(get(path(other, endpoint)).header("Authorization", token("alice")).param("cursor", firstCursor))
                    .andExpect(status().isBadRequest());
            mvc.perform(get(path(id, endpoint)).header("Authorization", token("alice")).param("cursor", firstCursor).param("roundNo", "1"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void equalTimestampNodeStartPrecedesItsEndEvenAcrossPageBoundaries() throws Exception {
        String id = draft("expense-reimbursement");
        submit(id, 1);
        Task original = task(id);
        act(original, "finance", "APPROVE", 2);
        Instant sameTime = Instant.now().plusSeconds(10);
        jdbc.update("UPDATE ACT_HI_ACTINST SET START_TIME_=?, END_TIME_=? WHERE PROC_INST_ID_=? AND TASK_ID_=?",
                Timestamp.from(sameTime), Timestamp.from(sameTime), original.getProcessInstanceId(), original.getId());
        List<JsonNode> collected = new ArrayList<>();
        JsonNode current = page(id, "timeline", "alice", Map.of("limit", "1"));
        for (int pageNo = 0; pageNo < 30; pageNo++) {
            collected.addAll(items(current));
            if (current.path("nextCursor").isNull()) break;
            current = page(id, "timeline", "alice", Map.of("limit", "1", "cursor", current.path("nextCursor").asText()));
        }
        assertThat(collected).isEqualTo(items(page(id, "timeline", "alice", Map.of("limit", "100"))));
        assertThat(collected.stream().filter(event -> source(event, "PROCESS_HISTORY")
                && original.getId().equals(event.path("taskId").asText())))
                .extracting(event -> event.path("action").asText()).containsExactly("NODE_STARTED", "NODE_ENDED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"limit=0", "limit=101", "limit=abc", "roundNo=0", "roundNo=-1", "roundNo=1.5",
            "from=invalid", "to=invalid", "action=NODE_ENDED", "cursor=garbage", "sort=id desc", "action="})
    void invalidQueryParametersAreRejectedOnceAtTheHttpBoundary(String parameter) throws Exception {
        String[] pair = parameter.split("=", 2);
        String id = draft("expense-reimbursement");
        mvc.perform(get(path(id, "audit")).header("Authorization", token("alice")).param(pair[0], pair[1]))
                .andExpect(status().isBadRequest());
    }

    @Test
    void v5MigrationPreservesLegacyActionsAndBackfillsOnlyReliableApplicationIdentity() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:history-migration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).target("4").load().migrate();
        JdbcTemplate old = new JdbcTemplate(source);
        String application = UUID.randomUUID().toString();
        String task = UUID.randomUUID().toString();
        old.update("INSERT INTO audit_event(id,tenant_id,event_id,aggregate_type,aggregate_id,aggregate_version,payload_json) VALUES(?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), "demo", UUID.randomUUID().toString(), "Application", application, 3, "{\"action\":\"WITHDRAW\"}");
        old.update("INSERT INTO audit_event(id,tenant_id,event_id,aggregate_type,aggregate_id,aggregate_version,payload_json) VALUES(?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), "demo", UUID.randomUUID().toString(), "Task", task, 2, "{\"action\":\"RETURN\"}");
        Flyway.configure().dataSource(source).target("5").load().migrate();
        assertThat(old.queryForObject("SELECT application_id FROM audit_event WHERE aggregate_id=?", String.class, application)).isEqualTo(application);
        assertThat(old.queryForObject("SELECT application_id FROM audit_event WHERE aggregate_id=?", String.class, task)).isNull();
        assertThat(old.queryForObject("SELECT COUNT(*) FROM audit_event WHERE action IS NOT NULL", Integer.class)).isZero();
        assertThat(old.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE INDEX_NAME='IDX_AUDIT_APPLICATION_TIME'", Integer.class)).isEqualTo(1);
    }

    private void insertAudit(String tenant, String applicationId, String type, String aggregateId, String action, Map<String, Object> payload) throws Exception {
        jdbc.update("INSERT INTO audit_event(id,tenant_id,event_id,aggregate_type,aggregate_id,aggregate_version,payload_json,application_id,action) VALUES(?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), tenant, UUID.randomUUID().toString(), type, aggregateId, 2,
                mapper.writeValueAsString(payload), applicationId, action);
    }

    private boolean source(JsonNode event, String source) {
        return source.equals(event.path("source").asText());
    }

    private List<JsonNode> items(JsonNode page) {
        List<JsonNode> values = new ArrayList<>();
        page.path("items").forEach(values::add);
        return values;
    }

    private JsonNode page(String id, String endpoint, String user, Map<String, String> parameters) throws Exception {
        var request = get(path(id, endpoint)).header("Authorization", token(user));
        parameters.forEach(request::param);
        var result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private String path(String id, String endpoint) {
        return "/api/v1/applications/" + id + "/" + endpoint;
    }

    private Task task(String id) {
        return tasks.createTaskQuery().processVariableValueEquals("applicationId", id).singleResult();
    }

    private void submit(String id, long version) throws Exception {
        mvc.perform(post(path(id, "submit")).header("Authorization", token("alice")).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("expectedVersion", version)))).andExpect(status().isOk());
    }

    private void revise(String id, long version) throws Exception {
        mvc.perform(put("/api/v1/applications/" + id).header("Authorization", token("alice")).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("expectedVersion", version, "title", "补正记录", "payload", Map.of("amount", 6000)))))
                .andExpect(status().isOk());
    }

    private void act(Task task, String user, String action, long version) throws Exception {
        mvc.perform(post("/api/v1/tasks/" + task.getId() + "/actions").header("Authorization", token(user)).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("expectedVersion", version, "action", action, "comment", "真实处理意见"))))
                .andExpect(status().isOk());
    }

    private void withdraw(String id, long version) throws Exception {
        mvc.perform(post(path(id, "withdraw")).header("Authorization", token("alice")).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("expectedVersion", version, "comment", "真实撤回原因")))).andExpect(status().isOk());
    }

    private void publish(String key) {
        Graph graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("manager", "部门审批", NodeType.USER_TASK, Map.of("assigneeRule", "role:MANAGER")),
                new Node("gate", "金额条件", NodeType.EXCLUSIVE_GATEWAY, Map.of()),
                new Node("finance", "财务审批", NodeType.USER_TASK, Map.of("assigneeRule", "role:FINANCE")),
                new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("a", "start", "manager", ""), new Edge("b", "manager", "gate", ""),
                        new Edge("c", "gate", "finance", "amount >= 5000"), new Edge("d", "gate", "end", "", true),
                        new Edge("e", "finance", "end", "")));
        var draft = definitions.create("demo", key, "历史节点测试", graph);
        definitions.publish(new io.agentflow.common.Actor("demo", "test-admin", java.util.Set.of("ADMIN")), draft.id(), draft.revision(), "集成测试发布");
    }

    private String draft(String key) throws Exception {
        var result = mvc.perform(post("/api/v1/applications").header("Authorization", token("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("businessNo", "HISTORY-" + UUID.randomUUID(),
                                "processKey", key, "definitionVersion", 1, "title", "历史查询测试",
                                "payload", Map.of("amount", 6000, "secret", "sensitive-form-value")))))
                .andExpect(status().isCreated()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).path("id").asText();
    }

    private String token(String user) {
        return "Bearer " + auth.login("demo", user, "demo").token();
    }
}
