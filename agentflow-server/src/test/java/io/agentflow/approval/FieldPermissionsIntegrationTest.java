package io.agentflow.approval;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.agent.AssistInput;
import io.agentflow.agent.AssistRun;
import io.agentflow.agent.AssistRunRepository;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import io.agentflow.definition.DefinitionValidator;
import io.agentflow.form.FieldVisibility;
import io.agentflow.form.FormSchema;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 通过真实认证、流程节点和多个读取出口验证字段权限，原文只能保留在授权业务链路中。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:field-permissions;DB_CLOSE_DELAY=-1", "agentflow.auth.demo-enabled=true"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class FieldPermissionsIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;
    @Autowired DefinitionApplicationService definitions;
    @Autowired TaskService tasks;
    @Autowired RuntimeService runtime;
    @Autowired JdbcTemplate jdbc;
    @Autowired AssistRunRepository runs;

    @Test
    void nodeVisibilityProtectsDetailsRoundsLegacyListAndAmountSearchWithoutChangingEngineFacts() throws Exception {
        String id = createAndSubmit();
        var manager = read("/applications/" + id, "manager", 200);
        assertThat(manager.at("/payload/amount").asText()).isEqualTo("98231.45");
        assertThat(manager.path("payload").has("confidential")).isFalse();
        assertThat(manager.at("/payload/items/0/account").asText()).isEqualTo("已脱敏");
        assertThat(manager.toString()).doesNotContain("private-note", "secret-account");
        var admin = read("/applications/" + id, "admin", 200);
        assertThat(admin.at("/payload/amount").asText()).isEqualTo("已脱敏");
        assertThat(admin.toString()).doesNotContain("98231.45", "private-note", "secret-account");
        assertThat(read("/applications", "admin", 200).toString()).doesNotContain("98231.45", "private-note", "secret-account");
        assertThat(read("/applications/" + id + "/rounds", "admin", 200).toString()).doesNotContain("98231.45", "private-note", "secret-account");
        assertThat(read("/applications/" + id, "bob", 404).path("code").asText()).isEqualTo("NOT_FOUND");
        var task = tasks.createTaskQuery().processVariableValueEquals("applicationId", id).singleResult();
        assertThat(runtime.getVariable(task.getExecutionId(), "formData")).isEqualTo(values("98231.45"));
        assertThat(jdbc.queryForObject("SELECT search_amount FROM approval_application WHERE id=?", java.math.BigDecimal.class, id)).isNull();
        assertThat(read("/workspace/tasks?processKey=" + manager.path("processKey").asText(), "manager", 200).toString()).doesNotContain("98231.45");
        assertThat(read("/workspace/tasks?processKey=" + manager.path("processKey").asText() + "&minAmount=1", "manager", 200).path("items")).isEmpty();
        write(put("/api/v1/applications/" + id), "admin", Map.of("expectedVersion", 2, "title", "越权", "payload", values("0")), 403);
        write(put("/api/v1/applications/" + id), "alice", Map.of("expectedVersion", 2, "title", "审批中不可编辑", "payload", values("0")), 422);
        write(post("/api/v1/tasks/" + task.getId() + "/actions"), "manager", Map.of("action", "APPROVE", "expectedVersion", 2), 200);
        var finance = read("/applications/" + id, "finance", 200);
        assertThat(finance.at("/payload/amount").asText()).isEqualTo("已脱敏");
        assertThat(finance.at("/payload/confidential").asText()).isEqualTo("private-note");
    }

    @Test
    void returnedEditsDoNotBorrowPreviousNodeRightsAndAssistDetailCannotBypassFieldRestrictions() throws Exception {
        String id = createAndSubmit();
        var input = new AssistInput(UUID.fromString(id), 2, 1, List.of(new AssistInput.Reference("form:amount", "a".repeat(64))));
        var run = AssistRun.queue(UUID.randomUUID(), "demo", "alice", Instant.now(), input, "field-test");
        runs.create(run);
        read("/applications/" + id + "/assist-runs/" + run.id(), "admin", 403);
        read("/applications/" + id + "/assist-runs/" + run.id(), "manager", 403);
        read("/applications/" + id + "/assist-runs/" + run.id(), "alice", 200);
        // 目录页只有状态和版本，不携带输入摘要或生成正文。
        assertThat(read("/applications/" + id + "/assist-runs", "admin", 200).toString()).doesNotContain("contentDigest", "suggestion");
        var task = tasks.createTaskQuery().processVariableValueEquals("applicationId", id).singleResult();
        write(post("/api/v1/tasks/" + task.getId() + "/actions"), "manager", Map.of("action", "RETURN", "comment", "补正", "expectedVersion", 2), 200);
        write(put("/api/v1/applications/" + id), "alice", Map.of("expectedVersion", 3, "title", "已补正", "payload", values("123456.78")), 200);
        assertThat(read("/applications/" + id, "manager", 200).at("/payload/amount").asText()).isEqualTo("已脱敏");
        assertThat(read("/applications/" + id + "/rounds", "manager", 200).get(0).at("/payload/amount").asText()).isEqualTo("98231.45");
        assertThat(read("/applications/" + id, "alice", 200).at("/payload/amount").asText()).isEqualTo("123456.78");
        write(post("/api/v1/applications/" + id + "/submit"), "alice", Map.of("expectedVersion", 4), 200);
        var rounds = read("/applications/" + id + "/rounds", "alice", 200);
        assertThat(rounds.get(0).at("/payload/amount").asText()).isEqualTo("98231.45");
        assertThat(rounds.get(1).at("/payload/amount").asText()).isEqualTo("123456.78");
    }

    @Test
    void permissionsRejectUnknownNodesAndResolveParallelConflictsConservatively() {
        var field = field("amount", "NUMBER", true, Map.of("review", FieldVisibility.READ_ONLY, "finance", FieldVisibility.HIDDEN));
        assertThat(field.visibility(Set.of("review", "finance"))).isEqualTo(FieldVisibility.HIDDEN);
        assertThat(field.visibility(Set.of())).isEqualTo(FieldVisibility.HIDDEN);
        assertThat(field.visibility(Set.of("review"))).isEqualTo(FieldVisibility.READ_ONLY);
        var unknown = field("amount", "NUMBER", false, Map.of("removed", FieldVisibility.HIDDEN));
        assertThat(new DefinitionValidator().validate(graph(), new FormSchema(1, List.of(unknown)))).contains("FIELD_PERMISSION_NODE_INVALID:amount:removed");
    }

    private String createAndSubmit() throws Exception {
        var item = new FormSchema.Field("items", "明细", FormSchema.FieldType.TABLE, false, null, null, null, null, null,
                List.of(field("name", "TEXT", false, null), field("account", "TEXT", true, null)), 10);
        var schema = new FormSchema(2, List.of(field("amount", "NUMBER", true, Map.of("review", FieldVisibility.READ_ONLY)),
                field("confidential", "TEXT", false, Map.of("review", FieldVisibility.HIDDEN, "finance", FieldVisibility.READ_ONLY)), item));
        var draft = definitions.create("demo", "fields-" + UUID.randomUUID(), "字段权限验收", graph(), schema, null);
        var published = definitions.publish(new Actor("demo", "admin", Set.of("ADMIN", "PROCESS_ADMIN")), draft.id(), draft.revision(), "权限验证");
        var app = write(post("/api/v1/applications"), "alice", Map.of("businessNo", UUID.randomUUID().toString(), "title", "权限申请",
                "processKey", published.key(), "definitionVersion", 1, "payload", values("98231.45")), 201);
        String id = app.path("id").asText();
        write(post("/api/v1/applications/" + id + "/submit"), "alice", Map.of("expectedVersion", 1), 200);
        return id;
    }

    private static FormSchema.Field field(String key, String type, boolean sensitive, Map<String, FieldVisibility> access) {
        return new FormSchema.Field(key, key, FormSchema.FieldType.valueOf(type), false, null, null, null, null, null, null, null, sensitive, access);
    }
    private static Map<String, Object> values(String amount) {
        return Map.of("amount", amount, "confidential", "private-note", "items", List.of(Map.of("name", "公用名称", "account", "secret-account")));
    }
    private static Graph graph() {
        return new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("review", "主管", NodeType.USER_TASK, Map.of("assigneeRule", "role:MANAGER")),
                new Node("finance", "财务", NodeType.USER_TASK, Map.of("assigneeRule", "role:FINANCE")), new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("e1", "start", "review", ""), new Edge("e2", "review", "finance", ""), new Edge("e3", "finance", "end", "")));
    }
    private JsonNode read(String path, String user, int status) throws Exception {
        return json.read(mvc.perform(get("/api/v1" + path).header("Authorization", "Bearer " + auth.login("demo", user, "demo").token()))
                .andExpect(status().is(status)).andReturn().getResponse().getContentAsString(), JsonNode.class);
    }
    private JsonNode write(MockHttpServletRequestBuilder request, String user, Object body, int status) throws Exception {
        return json.read(mvc.perform(request.header("Authorization", "Bearer " + auth.login("demo", user, "demo").token())
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json.write(body)))
                .andExpect(status().is(status)).andReturn().getResponse().getContentAsString(), JsonNode.class);
    }
}
