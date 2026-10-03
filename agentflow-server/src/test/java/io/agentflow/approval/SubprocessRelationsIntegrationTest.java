package io.agentflow.approval;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.repository.SubprocessCallRepository;
import io.agentflow.approval.process.FlowableTaskFacade;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionDeploymentPort;
import io.agentflow.definition.DefinitionDraftRepository;
import io.agentflow.definition.SubprocessPolicy;
import io.agentflow.form.FieldVisibility;
import io.agentflow.form.FormSchema;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 从实际原生调用查询精确轮次；父子关联不授予另一份申请或敏感字段的权限。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "agentflow.sla.reminders-enabled=false", "agentflow.timers.enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class SubprocessRelationsIntegrationTest {
    @Autowired ApprovalApplicationFacade applications;
    @Autowired SubprocessCallRepository calls;
    @Autowired DefinitionDraftRepository definitions;
    @Autowired DefinitionDeploymentPort deployment;
    @Autowired TransactionTemplate transactions;
    @Autowired TaskService tasks;
    @Autowired FlowableTaskFacade actions;
    @Autowired CurrentActor actors;
    @Autowired MockMvc mvc;
    @MockitoSpyBean AuthService auth;
    @Autowired JsonUtil json;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_RELATIONS_TEST_URL", "jdbc:h2:mem:subprocess-relations;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_RELATIONS_TEST_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_RELATIONS_TEST_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_RELATIONS_TEST_PASSWORD", ""));
    }

    @Test
    void readsOnlyActualDirectCallsAndAuthorizedOriginalRoundReferences() throws Exception {
        var parent = fixture(1, false);
        var initial = read(parent.id(), 1, "", "alice", 200);
        assertThat(initial.path("children")).isEmpty();
        assertThat(initial.path("childApplication").asBoolean()).isFalse();
        assertThat(initial.path("parent").isNull()).isTrue();
        approveParent(parent.id());
        var call = calls.findByParentRound("demo", parent.id(), 1).get(0);
        var before = counts();
        var page = read(parent.id(), 1, "", "alice", 200);
        assertThat(page.path("children")).hasSize(1);
        assertThat(page.at("/children/0/id").asText()).isEqualTo(call.id().toString());
        assertThat(page.at("/children/0/target/applicationId").asText()).isEqualTo(call.childApplicationId().toString());
        assertThat(page.at("/children/0/target/roundNo").asInt()).isEqualTo(1);
        assertThat(page.toString()).doesNotContain("private-secret", "payload", "policy", "activationId", "runtimeDefinitionId");
        var child = read(call.childApplicationId(), 1, "", "alice", 200);
        assertThat(child.path("childApplication").asBoolean()).isTrue();
        assertThat(child.at("/parent/applicationId").asText()).isEqualTo(parent.id().toString());
        assertThat(child.at("/parent/roundNo").asInt()).isEqualTo(1);
        assertThat(child.path("children")).isEmpty();
        assertThat(counts()).isEqualTo(before);
    }

    @Test
    void parentAndChildParticipantsDoNotAcquireEachOthersApplicationAccess() throws Exception {
        var parent = fixture(1, true);
        var call = calls.findByParentRound("demo", parent.id(), 1).get(0);
        var manager = read(parent.id(), 1, "", "manager", 200);
        assertThat(manager.at("/children/0/target").isNull()).isTrue();
        assertThat(manager.toString()).doesNotContain(call.childApplicationId().toString(), call.policy().processKey());
        var finance = read(call.childApplicationId(), 1, "", "finance", 200);
        assertThat(finance.path("childApplication").asBoolean()).isTrue();
        assertThat(finance.path("parent").isNull()).isTrue();
        assertThat(finance.toString()).doesNotContain(parent.id().toString(), parent.businessNo(), call.nodeId());
        read(call.childApplicationId(), 1, "", "manager", 404);
        read(parent.id(), 1, "", "finance", 404);
        read(parent.id(), 1, "", "bob", 404);
        doReturn(new Actor("other", "alice", Set.of("ADMIN"))).when(auth).authenticate("foreign-relations");
        mvc.perform(get(path(parent.id(), 1)).header("Authorization", "Bearer foreign-relations")).andExpect(status().isNotFound());
    }

    @Test
    void oldChildrenPointToTheOriginalParentRoundAfterWithdrawalAndResubmission() throws Exception {
        var parent = fixture(1, true);
        var original = calls.findByParentRound("demo", parent.id(), 1).get(0);
        as("alice", () -> applications.withdraw(parent.id(), applications.get(parent.id()).version(), "补正后重提"));
        as("alice", () -> applications.submit(parent.id(), applications.get(parent.id()).version(), null));
        approveParent(parent.id());
        var fresh = calls.findByParentRound("demo", parent.id(), 2).get(0);
        assertThat(fresh.childApplicationId()).isNotEqualTo(original.childApplicationId());
        var oldPage = read(parent.id(), 1, "", "alice", 200);
        assertThat(oldPage.at("/children/0/target/applicationId").asText()).isEqualTo(original.childApplicationId().toString());
        assertThat(oldPage.at("/children/0/target/status").asText()).isEqualTo("CANCELLED");
        var newPage = read(parent.id(), 2, "", "alice", 200);
        assertThat(newPage.at("/children/0/target/applicationId").asText()).isEqualTo(fresh.childApplicationId().toString());
        assertThat(read(original.childApplicationId(), 1, "", "alice", 200).at("/parent/status").asText()).isEqualTo("WITHDRAWN");
        assertThat(read(fresh.childApplicationId(), 1, "", "alice", 200).at("/parent/roundNo").asInt()).isEqualTo(2);
    }

    @Test
    void paginatesEqualTimestampsWithoutGapsAndRejectsCursorsFromAnotherParentOrRound() throws Exception {
        var parent = fixture(5, true);
        jdbc.update("UPDATE approval_subprocess_call SET created_at=? WHERE parent_application_id=?", OffsetDateTime.parse("2026-10-01T12:00:00Z"), parent.id().toString());
        var seen = new HashSet<String>(); String after = ""; String firstCursor = null;
        for (int page = 0; page < 3; page++) {
            var value = read(parent.id(), 1, "?limit=2" + (after.isEmpty() ? "" : "&afterId=" + after), "alice", 200);
            assertThat(value.path("children").size()).isEqualTo(page < 2 ? 2 : 1);
            value.path("children").forEach(item -> assertThat(seen.add(item.path("id").asText())).isTrue());
            after = value.path("nextAfterId").isNull() ? "" : value.path("nextAfterId").asText();
            if (page == 0) firstCursor = after;
        }
        assertThat(after).isEmpty(); assertThat(seen).hasSize(5);
        var other = fixture(1, true);
        read(other.id(), 1, "?afterId=" + firstCursor, "alice", 400);
        read(parent.id(), 1, "?afterId=" + UUID.randomUUID(), "alice", 400);
    }

    @Test
    void invalidQueriesAndMissingRoundsDoNotWriteOrInventRelationships() throws Exception {
        var parent = fixture(1, true); var before = counts();
        mvc.perform(get(path(parent.id(), 1))).andExpect(status().isUnauthorized());
        read(parent.id(), 2, "", "alice", 404);
        for (String query : List.of("?limit=0", "?limit=101", "?limit=01", "?limit=2&limit=3", "?afterId=no", "?tenant=other", "?afterId=")) {
            read(parent.id(), 1, query, "alice", 400);
        }
        for (String number : List.of("0", "-1", "01", "2147483648")) {
            mvc.perform(get("/api/v1/applications/" + parent.id() + "/rounds/" + number + "/subprocesses")
                    .header("Authorization", token("alice"))).andExpect(status().isBadRequest());
        }
        assertThat(counts()).isEqualTo(before);
    }

    @Test
    void administratorNavigationDoesNotBypassSensitiveFieldProjection() throws Exception {
        var parent = fixture(1, true);
        var page = read(parent.id(), 1, "", "admin", 200);
        String child = page.at("/children/0/target/applicationId").asText();
        assertThat(child).isNotEmpty();
        var detail = mvc.perform(get("/api/v1/applications/" + child + "/rounds").header("Authorization", token("admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(detail).doesNotContain("private-secret").contains("已脱敏");
        assertThat(page.toString()).doesNotContain("private-secret", "formSchema", "payload", "reason", "initiatorContext");
    }

    @Test
    void inconsistentParentInstanceBindingFailsClosed() throws Exception {
        var parent = fixture(1, true);
        jdbc.update("UPDATE approval_subprocess_call SET parent_instance_id=? WHERE parent_application_id=?", "unrelated-instance", parent.id().toString());
        assertThat(read(parent.id(), 1, "", "alice", 422).path("code").asText()).isEqualTo("SUBPROCESS_RELATION_UNAVAILABLE");
    }

    private Application fixture(int count, boolean activate) {
        var childGraph = new Graph(List.of(node("start", NodeType.START), new Node("review", "子审批", NodeType.USER_TASK, Map.of("assigneeRule", "user:finance")), node("end", NodeType.END)),
                List.of(edge("a", "start", "review"), edge("b", "review", "end")));
        var child = publish("子流程读取验收", childGraph, schema(Map.of("review", FieldVisibility.READ_ONLY)));
        var nodes = new ArrayList<>(List.of(node("start", NodeType.START), new Node("before", "父流程先审", NodeType.USER_TASK, Map.of("assigneeRule", "user:manager")),
                node("fork", NodeType.PARALLEL_GATEWAY), node("join", NodeType.PARALLEL_GATEWAY), node("end", NodeType.END)));
        var edges = new ArrayList<>(List.of(edge("a", "start", "before"), edge("b", "before", "fork"), edge("c", "join", "end")));
        var access = new HashMap<String, FieldVisibility>();
        for (int i = 0; i < count; i++) {
            String id = "call" + i; access.put(id, FieldVisibility.READ_ONLY);
            nodes.add(new Node(id, "材料核对 " + i, NodeType.SUB_PROCESS, new SubprocessPolicy(child.key(), child.version(), Map.of("secret", "secret")).properties()));
            edges.add(edge("in" + i, "fork", id)); edges.add(edge("out" + i, id, "join"));
        }
        var parent = publish("父流程读取验收", new Graph(nodes, edges), schema(access));
        var application = as("alice", () -> applications.create("RELATION-" + UUID.randomUUID(), parent.key(), parent.version(), "父子关系验收", Map.of("secret", "private-secret")));
        as("alice", () -> applications.submit(application.id(), 1, null));
        if (activate) approveParent(application.id());
        return application;
    }

    /** 内部发布夹具使用正式部署适配器；公开子流程发布门禁继续保留。 */
    private DefinitionDraft publish(String name, Graph graph, FormSchema schema) {
        return transactions.execute(status -> {
            var draft = DefinitionDraft.create(UUID.randomUUID(), "demo", "relations-" + UUID.randomUUID(), name, graph, schema);
            definitions.save(draft); draft.publish(0, 1); definitions.save(draft); deployment.deploy(draft); return draft;
        });
    }
    private void approveParent(UUID id) {
        var task = tasks.createTaskQuery().processVariableValueEquals("applicationId", id.toString()).singleResult();
        as("manager", () -> actions.action(task.getId(), "APPROVE", "父审批通过", null, applications.get(id).version()));
    }
    private <T> T as(String user, Supplier<T> work) {
        actors.set(new Actor("demo", user, Set.of("alice".equals(user) ? "EMPLOYEE" : "APPROVER")));
        try { return work.get(); } finally { actors.clear(); }
    }
    private FormSchema schema(Map<String, FieldVisibility> access) {
        return new FormSchema(1, List.of(new FormSchema.Field("secret", "敏感材料", FormSchema.FieldType.TEXT, false, null, null, null, null, null, null, null, true, access)));
    }
    private Node node(String id, NodeType type) { return new Node(id, id, type, Map.of()); }
    private Edge edge(String id, String source, String target) { return new Edge(id, source, target, ""); }
    private String path(UUID id, int round) { return "/api/v1/applications/" + id + "/rounds/" + round + "/subprocesses"; }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private JsonNode read(UUID id, int round, String query, String user, int expected) throws Exception {
        var result = mvc.perform(get(path(id, round) + query).header("Authorization", token(user))).andExpect(status().is(expected));
        if (expected == 200) result.andExpect(header().string("Cache-Control", "no-store"));
        String body = result.andReturn().getResponse().getContentAsString();
        assertThat(body).isNotBlank();
        return json.read(body, JsonNode.class);
    }
    private List<Long> counts() {
        return List.of("approval_application", "approval_submission_round", "approval_subprocess_call", "request_idempotency", "audit_event", "act_ru_execution", "act_ru_task")
                .stream().map(table -> jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)).toList();
    }
}
