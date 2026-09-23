package io.agentflow.approval;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static io.agentflow.definition.DefinitionModels.*;
import static io.agentflow.support.MutationRequests.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通过真实 HTTP、事务、审计和引擎验证个人工作台及历史办理权限。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:personal-workspace;DB_CLOSE_DELAY=-1",
        "agentflow.auth.demo-enabled=true", "agentflow.auth.demo-tenant=demo"})
@AutoConfigureMockMvc
class PersonalWorkspaceIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;
    @Autowired DefinitionApplicationService definitions;
    @Autowired TaskService tasks;
    @Autowired JdbcTemplate jdbc;
    @Autowired io.agentflow.approval.workspace.WorkspaceReadPort workspace;

    @Test
    void transferKeepsTheActualHandlerAsParticipantWithoutGivingUnrelatedUsersAccess() throws Exception {
        JsonNode application = draft("alice", "转交访问-" + UUID.randomUUID());
        String id = application.path("id").asText();
        submit(id);
        String task = task(id);
        act(task, "manager", "TRANSFER", "bob", 2);
        mvc.perform(get("/api/v1/applications/" + id).header("Authorization", token("manager"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/applications/" + id).header("Authorization", token("employee"))).andExpect(status().isNotFound());
        JsonNode records = read("/api/v1/workspace/handled?q=" + application.path("businessNo").asText(), "manager");
        assertThat(records.path("items").size()).isEqualTo(1);
        assertThat(records.path("items").get(0).path("action").asText()).isEqualTo("TRANSFER");
        assertThat(records.path("items").get(0).path("applicationStatus").asText()).isEqualTo("IN_APPROVAL");
        assertThat(read("/api/v1/workspace/handled", "employee").path("items").isEmpty()).isTrue();
    }

    @Test
    void ownApplicationsAndDraftsNeverExposeAnotherApplicantOrPayload() throws Exception {
        String marker = "personal-" + UUID.randomUUID();
        JsonNode mine = draft("alice", marker), other = draft("bob", marker);
        JsonNode page = read("/api/v1/workspace/applications?q=" + marker, "alice");
        assertThat(page.path("items").size()).isEqualTo(1);
        assertThat(page.path("items").get(0).path("id").asText()).isEqualTo(mine.path("id").asText());
        assertThat(page.toString()).doesNotContain("do-not-list", "payload", "formSchema", other.path("id").asText());
        assertThat(read("/api/v1/workspace/applications?q=" + marker, "admin").path("items").isEmpty()).isTrue();
        submit(mine.path("id").asText());
        assertThat(read("/api/v1/workspace/applications?view=drafts&q=" + marker, "alice").path("items").isEmpty()).isTrue();
        assertThat(read("/api/v1/workspace/applications?status=IN_APPROVAL&q=" + marker, "alice").path("items").size()).isEqualTo(1);
    }

    @Test
    void recordsRealDecisionsAndDoesNotTreatClaimOrCancellationAsApproval() throws Exception {
        String marker = "decisions-" + UUID.randomUUID();
        for (String action : List.of("APPROVE", "RETURN", "REJECT")) {
            JsonNode draft = draft("alice", marker + action);
            String id = draft.path("id").asText(); submit(id);
            act(task(id), "manager", action, null, 2);
        }
        String claimed = draft("alice", marker + "认领后撤回", "role:MANAGER").path("id").asText();
        submit(claimed);
        act(task(claimed), "manager", "CLAIM", null, 2);
        act(task(claimed), "manager", "RELEASE", null, 3);
        mvc.perform(post("/api/v1/applications/" + claimed + "/withdraw").header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":4,\"comment\":\"撤回待办\"}"))
                .andExpect(status().isOk());
        JsonNode result = read("/api/v1/workspace/handled?q=" + marker, "manager");
        assertThat(result.path("items").size()).isEqualTo(3);
        assertThat(result.path("items").findValuesAsText("action")).containsExactlyInAnyOrder("APPROVE", "RETURN", "REJECT");
        assertThat(result.path("items").findValuesAsText("handledStatus")).containsExactlyInAnyOrder("APPROVED", "RETURNED", "REJECTED");
        assertThat(read("/api/v1/workspace/handled?q=" + marker + "&action=RETURN", "manager").path("items").size()).isEqualTo(1);
        assertThat(read("/api/v1/workspace/handled?q=" + marker, "alice").path("items").isEmpty()).isTrue();
        assertThat(read("/api/v1/workspace/handled?q=" + marker, "admin").path("items").isEmpty()).isTrue();
    }

    @Test
    void handledPagesKeepEachActionAndSeparateHistoricalResultFromCurrentStatus() throws Exception {
        JsonNode draft = draft("alice", "办理分页-" + UUID.randomUUID());
        String id = draft.path("id").asText(), businessNo = draft.path("businessNo").asText();
        submit(id);
        String task = task(id);
        act(task, "manager", "TRANSFER", "bob", 2);
        act(task, "bob", "TRANSFER", "manager", 3);
        act(task, "manager", "DELEGATE", "bob", 4);
        act(task, "bob", "TRANSFER", "manager", 5);
        act(task, "manager", "RETURN", null, 6);
        jdbc.update("UPDATE audit_event SET occurred_at=TIMESTAMP '2026-01-01 00:00:00' WHERE application_id=? AND aggregate_type='Task'", id);
        var seen = new java.util.HashSet<String>();
        String cursor = null;
        for (int index = 0; index < 3; index++) {
            JsonNode page = read("/api/v1/workspace/handled?limit=1&q=" + businessNo + (cursor == null ? "" : "&cursor=" + cursor), "manager");
            assertThat(page.path("items").size()).isEqualTo(1);
            JsonNode row = page.path("items").get(0);
            assertThat(seen.add(row.path("id").asText())).isTrue();
            assertThat(row.path("applicationStatus").asText()).isEqualTo("RETURNED");
            if (!row.path("action").asText().equals("RETURN")) assertThat(row.path("handledStatus").asText()).isEqualTo("IN_APPROVAL");
            cursor = page.path("nextCursor").isTextual() ? page.path("nextCursor").asText() : null;
            if (index == 0) {
                mvc.perform(get("/api/v1/workspace/handled?q=" + businessNo + "&action=TRANSFER&cursor=" + cursor)
                        .header("Authorization", token("manager"))).andExpect(status().isBadRequest());
                mvc.perform(get("/api/v1/workspace/handled?q=" + businessNo + "&cursor=" + cursor)
                        .header("Authorization", token("bob"))).andExpect(status().isBadRequest());
            }
        }
        assertThat(cursor).isNull();
        var otherTenant = new Actor("other-tenant", "manager", Set.of("ADMIN"));
        var query = new io.agentflow.approval.workspace.WorkspaceReadPort.Query(false, businessNo, "", "", 30, null, null);
        assertThat(workspace.handled(otherTenant, query)).isEmpty();
        assertThat(workspace.applications(new Actor("other-tenant", "alice", Set.of("ADMIN")), query)).isEmpty();
        assertThat(((io.agentflow.approval.service.ApplicationParticipantPort) workspace).isParticipant("demo", UUID.fromString(id), otherTenant)).isFalse();
    }

    @Test
    void stableCursorDoesNotLoseSameTimestampRowsAndIsBoundToUserAndFilters() throws Exception {
        String marker = "paging-" + UUID.randomUUID();
        JsonNode a = draft("alice", marker), b = draft("alice", marker), c = draft("alice", marker);
        jdbc.update("UPDATE approval_application SET created_at=TIMESTAMP '2026-01-01 00:00:00' WHERE id IN (?,?,?)",
                a.path("id").asText(), b.path("id").asText(), c.path("id").asText());
        var seen = new java.util.HashSet<String>();
        String cursor = null;
        for (int index = 0; index < 3; index++) {
            JsonNode page = read("/api/v1/workspace/applications?limit=1&q=" + marker + (cursor == null ? "" : "&cursor=" + cursor), "alice");
            assertThat(page.path("items").size()).isEqualTo(1);
            assertThat(seen.add(page.path("items").get(0).path("id").asText())).isTrue();
            cursor = page.path("nextCursor").isTextual() ? page.path("nextCursor").asText() : null;
            if (index == 0) {
                mvc.perform(get("/api/v1/workspace/applications?q=" + marker + "&cursor=" + cursor)
                        .header("Authorization", token("bob"))).andExpect(status().isBadRequest());
                mvc.perform(get("/api/v1/workspace/applications?view=drafts&q=" + marker + "&cursor=" + cursor)
                        .header("Authorization", token("alice"))).andExpect(status().isBadRequest());
            }
        }
        assertThat(cursor).isNull();
        assertThat(seen).containsExactlyInAnyOrder(a.path("id").asText(), b.path("id").asText(), c.path("id").asText());
    }

    @Test
    void rejectsUntrustedFiltersAndEscapesSearchWildcards() throws Exception {
        String marker = "literal-" + UUID.randomUUID();
        draft("alice", marker + "%_needle"); draft("alice", marker + "XXneedle");
        mvc.perform(get("/api/v1/workspace/applications")).andExpect(status().isUnauthorized());
        JsonNode matches = json.read(mvc.perform(get("/api/v1/workspace/applications").param("q", marker + "%_")
                .header("Authorization", token("alice"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), JsonNode.class);
        assertThat(matches.path("items").size()).isEqualTo(1);
        for (String invalid : List.of("limit=0", "limit=101", "view=all", "status=wrong", "tenantId=other", "actor=bob", "cursor=garbage", "view=drafts&status=APPROVED")) {
            mvc.perform(get("/api/v1/workspace/applications?" + invalid).header("Authorization", token("alice"))).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/workspace/handled?action=CLAIM").header("Authorization", token("alice"))).andExpect(status().isBadRequest());
    }

    private JsonNode draft(String user, String title) throws Exception {
        return draft(user, title, "user:manager");
    }

    private JsonNode draft(String user, String title, String assigneeRule) throws Exception {
        String key = "workspace-" + UUID.randomUUID();
        var definition = definitions.create("demo", key, "个人工作台流程", new Graph(List.of(
                new Node("start", "开始", NodeType.START, Map.of()),
                new Node("manager", "经理审批", NodeType.USER_TASK, Map.of("assigneeRule", assigneeRule)),
                new Node("end", "结束", NodeType.END, Map.of())), List.of(
                new Edge("begin", "start", "manager", ""), new Edge("finish", "manager", "end", ""))));
        definitions.publish(new Actor("demo", "admin", Set.of("ADMIN")), definition.id(), 0, "工作台验收发布");
        return json.read(mvc.perform(post("/api/v1/applications").header("Authorization", token(user))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("businessNo", "WS-" + UUID.randomUUID(),
                        "processKey", key, "definitionVersion", 1, "title", title, "payload", Map.of("secret", "do-not-list")))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), JsonNode.class);
    }

    private void submit(String id) throws Exception {
        mvc.perform(post("/api/v1/applications/" + id + "/submit").header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}"))
                .andExpect(status().isOk());
    }

    private void act(String task, String actor, String action, String target, long version) throws Exception {
        var body = new java.util.HashMap<String, Object>(Map.of("action", action, "expectedVersion", version, "comment", "实际处理说明"));
        if (target != null) body.put("targetUser", target);
        mvc.perform(post("/api/v1/tasks/" + task + "/actions").header("Authorization", token(actor))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(body))).andExpect(status().isOk());
    }

    private String task(String applicationId) { return tasks.createTaskQuery().processVariableValueEquals("applicationId", applicationId).singleResult().getId(); }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private JsonNode read(String path, String user) throws Exception {
        return json.read(mvc.perform(get(path).header("Authorization", token(user))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
    }
}
