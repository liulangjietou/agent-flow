package io.agentflow.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.auth.AuthService;
import io.agentflow.approval.service.ApplicationAuditPort;
import io.agentflow.approval.service.ProcessRuntimePort;
import io.agentflow.approval.service.TaskAuditPort;
import io.agentflow.common.DomainException;
import io.agentflow.definition.DefinitionApplicationService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static io.agentflow.definition.DefinitionModels.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static io.agentflow.support.MutationRequests.put;
import static io.agentflow.support.MutationRequests.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 申请和任务审计通过真实 HTTP 与共享数据库验证，不包含表单敏感内容。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:lifecycle-audit;DB_CLOSE_DELAY=-1",
        "agentflow.auth.demo-enabled=true", "agentflow.auth.demo-tenant=demo"
})
@AutoConfigureMockMvc
class LifecycleAuditIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;
    @Autowired RuntimeService runtime;
    @Autowired TaskService tasks;
    @Autowired DefinitionApplicationService definitions;
    @MockitoSpyBean ApplicationAuditPort applicationAudit;
    @MockitoSpyBean TaskAuditPort taskAudit;
    @MockitoSpyBean ProcessRuntimePort processRuntime;

    @Test
    void creatingAnApplicationRecordsTheRealActorAndDraftTransition() throws Exception {
        JsonNode application = mapper.readTree(create("AUDIT-" + UUID.randomUUID()).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        var events = jdbc.queryForList("SELECT payload_json, aggregate_version FROM audit_event WHERE aggregate_type='Application' AND aggregate_id=?",
                application.path("id").asText());
        assertThat(events).hasSize(1);
        JsonNode event = mapper.readTree(events.get(0).get("PAYLOAD_JSON").toString());
        assertThat(event.path("action").asText()).isEqualTo("CREATE");
        assertThat(event.path("actor").asText()).isEqualTo("alice");
        assertThat(event.path("applicationId").asText()).isEqualTo(application.path("id").asText());
        assertThat(event.path("roundNo").asInt()).isEqualTo(1);
        assertThat(event.path("currentStatus").asText()).isEqualTo("DRAFT");
        assertThat(event.path("previousStatus").isNull()).isTrue();
        assertThat(event.path("processInstanceId").isNull()).isTrue();
        assertThat(((Number) events.get(0).get("AGGREGATE_VERSION")).longValue()).isEqualTo(1);
        assertThat(event.has("payload")).isFalse();
        assertThat(event.toString()).doesNotContain("private-secret-value");
    }

    @Test
    void revisionAndResubmissionRecordOnlyTheVersionsAndInstancesActuallyCommitted() throws Exception {
        String id = draft();
        revise(id, 1).andExpect(status().isOk());
        submit(id, 2).andExpect(status().isOk());
        Task first = task(id);
        act(first.getId(), "finance", "RETURN", null, 3).andExpect(status().isOk());
        revise(id, 4).andExpect(status().isOk());
        submit(id, 5).andExpect(status().isOk());
        Task second = task(id);
        var recorded = events(id, "Application");
        assertThat(recorded).extracting(event -> event.path("action").asText())
                .containsExactly("CREATE", "REVISE", "SUBMIT", "REVISE", "SUBMIT");
        assertThat(recorded).extracting(event -> event.path("aggregateVersion").asLong())
                .containsExactly(1L, 2L, 3L, 5L, 6L);
        assertThat(recorded).allSatisfy(event -> {
            assertThat(event.path("actor").asText()).isEqualTo("alice");
            assertThat(event.path("applicationId").asText()).isEqualTo(id);
            assertThat(event.toString()).doesNotContain("private-secret-value");
        });
        assertThat(recorded.get(1).path("previousStatus").asText()).isEqualTo("DRAFT");
        assertThat(recorded.get(1).path("currentStatus").asText()).isEqualTo("DRAFT");
        assertThat(recorded.get(2).path("processInstanceId").asText()).isEqualTo(first.getProcessInstanceId());
        assertThat(recorded.get(2).path("previousStatus").asText()).isEqualTo("DRAFT");
        assertThat(recorded.get(2).path("currentStatus").asText()).isEqualTo("IN_APPROVAL");
        assertThat(recorded.get(4).path("previousStatus").asText()).isEqualTo("RETURNED");
        assertThat(recorded.get(4).path("currentStatus").asText()).isEqualTo("IN_APPROVAL");
        assertThat(recorded.get(4).path("roundNo").asInt()).isEqualTo(2);
        assertThat(recorded.get(4).path("processInstanceId").asText()).isEqualTo(second.getProcessInstanceId())
                .isNotEqualTo(first.getProcessInstanceId());
        JsonNode returned = events(id, "Task").get(0);
        assertThat(returned.path("action").asText()).isEqualTo("RETURN");
        assertThat(returned.path("previousStatus").asText()).isEqualTo("IN_APPROVAL");
        assertThat(returned.path("currentStatus").asText()).isEqualTo("RETURNED");
        assertThat(returned.path("nodeId").asText()).isEqualTo(first.getTaskDefinitionKey());
        assertThat(returned.path("nodeName").asText()).isEqualTo(first.getName());
        assertThat(returned.path("processInstanceId").asText()).isEqualTo(first.getProcessInstanceId());
        assertThat(returned.path("roundNo").asInt()).isEqualTo(1);
        assertThat(returned.path("aggregateVersion").asInt()).isEqualTo(4);
    }

    @Test
    void failedRevisionAndSubmissionDoNotAppendEvents() throws Exception {
        String id = draft();
        var before = events(id, "Application");
        revise(id, 99).andExpect(status().isConflict());
        submit(id, 99).andExpect(status().isConflict());
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new DomainException("DEPENDENCY_UNAVAILABLE", "Runtime transaction failed");
        }).when(processRuntime).start(any());
        submit(id, 1).andExpect(status().isServiceUnavailable());
        assertThat(events(id, "Application")).isEqualTo(before);
        assertDraft(id, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATE", "REVISE", "SUBMIT"})
    void failedApplicationAuditRollsBackTheOperationAndItsEvent(String failedAction) throws Exception {
        String businessNo = "AUDIT-ROLLBACK-" + UUID.randomUUID();
        String id = failedAction.equals("CREATE") ? null : draft();
        var before = id == null ? List.<JsonNode>of() : events(id, "Application");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new DomainException("DEPENDENCY_UNAVAILABLE", "Audit storage is unavailable");
        }).when(applicationAudit).record(any());
        if (failedAction.equals("CREATE")) {
            create(businessNo).andExpect(status().isServiceUnavailable());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_application WHERE tenant_id='demo' AND business_no=?",
                    Integer.class, businessNo)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE tenant_id='demo' AND application_id NOT IN (SELECT id FROM approval_application)",
                    Integer.class)).isZero();
        } else {
            if (failedAction.equals("REVISE")) revise(id, 1).andExpect(status().isServiceUnavailable());
            else submit(id, 1).andExpect(status().isServiceUnavailable());
            assertDraft(id, 1);
            assertThat(events(id, "Application")).isEqualTo(before);
        }
    }

    @Test
    void transferAndDelegationKeepTheOriginalTargetAfterLaterAssignments() throws Exception {
        String id = draft();
        submit(id, 1).andExpect(status().isOk());
        Task task = task(id);
        act(task.getId(), "finance", "TRANSFER", "manager", 2).andExpect(status().isOk());
        JsonNode transferred = events(id, "Task").get(0);
        act(task.getId(), "manager", "DELEGATE", "alice", 3).andExpect(status().isOk());
        JsonNode delegated = events(id, "Task").get(1);
        act(task.getId(), "alice", "RESOLVE", null, 4).andExpect(status().isOk());
        act(task.getId(), "manager", "TRANSFER", "bob", 5).andExpect(status().isOk());
        assertThat(task(id).getAssignee()).isEqualTo("bob");
        var recorded = events(id, "Task");
        assertThat(recorded).hasSize(4);
        assertThat(recorded.get(0)).isEqualTo(transferred);
        assertThat(recorded.get(1)).isEqualTo(delegated);
        assertThat(transferred.path("targetUser").asText()).isEqualTo("manager");
        assertThat(transferred.path("actor").asText()).isEqualTo("finance");
        assertThat(delegated.path("targetUser").asText()).isEqualTo("alice");
        assertThat(delegated.path("actor").asText()).isEqualTo("manager");
        assertThat(recorded).allSatisfy(event -> {
            assertThat(event.path("applicationId").asText()).isEqualTo(id);
            assertThat(event.path("roundNo").asInt()).isEqualTo(1);
            assertThat(event.path("processInstanceId").asText()).isEqualTo(task.getProcessInstanceId());
            assertThat(event.path("nodeId").asText()).isEqualTo(task.getTaskDefinitionKey());
            assertThat(event.path("nodeName").asText()).isEqualTo(task.getName());
        });
    }

    @Test
    void unrelatedTargetParameterIsNotPresentedAsAClaimReleaseOrApprovalTarget() throws Exception {
        String id = draft();
        submit(id, 1).andExpect(status().isOk());
        String taskId = task(id).getId();
        act(taskId, "finance", "CLAIM", "unrelated", 2).andExpect(status().isOk());
        act(taskId, "finance", "RELEASE", "unrelated", 3).andExpect(status().isOk());
        act(taskId, "finance", "APPROVE", "unrelated", 4).andExpect(status().isOk());
        var recorded = events(id, "Task");
        assertThat(recorded).extracting(event -> event.path("action").asText())
                .containsExactly("CLAIM", "RELEASE", "APPROVE");
        assertThat(recorded).allSatisfy(event -> assertThat(event.path("targetUser").isNull()).isTrue());
    }

    @Test
    void intermediateAndFinalApprovalRecordTheirActualStatusTransitions() throws Exception {
        String key = "audit-two-approvals-" + UUID.randomUUID();
        Graph graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("first", "初审", NodeType.USER_TASK, Map.of("assigneeRule", "role:FINANCE")),
                new Node("second", "复审", NodeType.USER_TASK, Map.of("assigneeRule", "role:FINANCE")),
                new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("a", "start", "first", ""), new Edge("b", "first", "second", ""),
                        new Edge("c", "second", "end", "")));
        var definition = definitions.create("demo", key, "两级审批审计", graph);
        definitions.publish(new io.agentflow.common.Actor("demo", "test-admin", java.util.Set.of("ADMIN")), definition.id(), definition.revision(), "集成测试发布");
        String id = mapper.readTree(create("AUDIT-" + UUID.randomUUID(), key).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).path("id").asText();
        submit(id, 1).andExpect(status().isOk());
        act(task(id).getId(), "finance", "APPROVE", null, 2).andExpect(status().isOk());
        act(task(id).getId(), "finance", "APPROVE", null, 3).andExpect(status().isOk());
        var recorded = events(id, "Task");
        assertThat(recorded).hasSize(2);
        assertThat(recorded).allSatisfy(event -> {
            assertThat(event.path("action").asText()).isEqualTo("APPROVE");
            assertThat(event.path("previousStatus").asText()).isEqualTo("IN_APPROVAL");
        });
        assertThat(recorded.get(0).path("currentStatus").asText()).isEqualTo("IN_APPROVAL");
        assertThat(recorded.get(0).path("aggregateVersion").asLong()).isEqualTo(3);
        assertThat(recorded.get(1).path("currentStatus").asText()).isEqualTo("APPROVED");
        assertThat(recorded.get(1).path("aggregateVersion").asLong()).isEqualTo(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRANSFER", "RETURN", "APPROVE"})
    void taskAuditFailureRollsBackAssignmentDecisionAndAggregateVersion(String failedAction) throws Exception {
        String id = draft();
        submit(id, 1).andExpect(status().isOk());
        var before = events(id, "Application");
        String taskId = task(id).getId();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new DomainException("DEPENDENCY_UNAVAILABLE", "Task audit storage is unavailable");
        }).when(taskAudit).record(any());
        act(taskId, "finance", failedAction, "manager", 2).andExpect(status().isServiceUnavailable());
        assertThat(task(id).getAssignee()).isNull();
        assertThat(task(id).getId()).isEqualTo(taskId);
        assertThat(jdbc.queryForObject("SELECT status FROM approval_application WHERE tenant_id='demo' AND id=?",
                String.class, id)).isEqualTo("IN_APPROVAL");
        assertThat(jdbc.queryForObject("SELECT status FROM approval_submission_round WHERE tenant_id='demo' AND application_id=?",
                String.class, id)).isEqualTo("IN_APPROVAL");
        assertThat(jdbc.queryForObject("SELECT version FROM approval_application WHERE tenant_id='demo' AND id=?",
                Long.class, id)).isEqualTo(2);
        assertThat(events(id, "Task")).isEmpty();
        assertThat(events(id, "Application")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND actor_id IS NOT NULL",
                Integer.class, id)).isZero();
    }

    private List<JsonNode> events(String applicationId, String aggregateType) throws Exception {
        var rows = jdbc.queryForList("SELECT payload_json, aggregate_version, action, application_id FROM audit_event WHERE tenant_id='demo' AND application_id=? AND aggregate_type=? ORDER BY aggregate_version",
                applicationId, aggregateType);
        List<JsonNode> events = new java.util.ArrayList<>();
        for (var row : rows) {
            var payload = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(row.get("PAYLOAD_JSON").toString());
            assertThat(payload.path("action").asText()).isEqualTo(row.get("ACTION"));
            assertThat(payload.path("applicationId").asText()).isEqualTo(row.get("APPLICATION_ID"));
            payload.put("aggregateVersion", ((Number) row.get("AGGREGATE_VERSION")).longValue());
            events.add(payload);
        }
        return events;
    }

    private void assertDraft(String id, long version) {
        assertThat(jdbc.queryForObject("SELECT status FROM approval_application WHERE tenant_id='demo' AND id=?", String.class, id)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT version FROM approval_application WHERE tenant_id='demo' AND id=?", Long.class, id)).isEqualTo(version);
        assertThat(jdbc.queryForObject("SELECT title FROM approval_application WHERE tenant_id='demo' AND id=?", String.class, id)).isEqualTo("审计申请");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_submission_round WHERE tenant_id='demo' AND application_id=?", Integer.class, id)).isZero();
        assertThat(runtime.createProcessInstanceQuery().variableValueEquals("applicationId", id).count()).isZero();
    }

    private String draft() throws Exception {
        return mapper.readTree(create("AUDIT-" + UUID.randomUUID()).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).path("id").asText();
    }

    private ResultActions revise(String id, long version) throws Exception {
        return mvc.perform(put("/api/v1/applications/" + id).header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
                        "expectedVersion", version, "title", "修订后的审计申请", "payload", Map.of("amount", 6000)))));
    }

    private ResultActions submit(String id, long version) throws Exception {
        return mvc.perform(post("/api/v1/applications/" + id + "/submit").header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("expectedVersion", version))));
    }

    private ResultActions act(String taskId, String actor, String action, String targetUser, long version) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("action", action);
        body.put("expectedVersion", version);
        body.put("targetUser", targetUser);
        body.put("comment", "审计测试意见");
        return mvc.perform(post("/api/v1/tasks/" + taskId + "/actions").header("Authorization", token(actor))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
    }

    private Task task(String id) {
        return tasks.createTaskQuery().processVariableValueEquals("applicationId", id).singleResult();
    }

    private ResultActions create(String businessNo) throws Exception {
        return create(businessNo, "expense-reimbursement");
    }

    private ResultActions create(String businessNo, String processKey) throws Exception {
        return mvc.perform(post("/api/v1/applications").header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
                        "businessNo", businessNo, "processKey", processKey, "definitionVersion", 1,
                        "title", "审计申请", "payload", Map.of("amount", 6000, "privateValue", "private-secret-value")))));
    }

    private String token(String user) {
        return "Bearer " + auth.login("demo", user, "demo").token();
    }
}
