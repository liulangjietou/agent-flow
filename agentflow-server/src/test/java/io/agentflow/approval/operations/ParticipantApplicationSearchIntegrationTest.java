package io.agentflow.approval.operations;

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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static io.agentflow.definition.DefinitionModels.*;
import static io.agentflow.support.MutationRequests.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 通过真实审批动作对照既有详情授权，验证参与者分页不会扩大或丢失可见范围。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${AGENTFLOW_SEARCH_TEST_URL:jdbc:h2:mem:participant-search;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${AGENTFLOW_SEARCH_TEST_USER:sa}",
        "spring.datasource.password=${AGENTFLOW_SEARCH_TEST_PASSWORD:}",
        "spring.datasource.driver-class-name=${AGENTFLOW_SEARCH_TEST_DRIVER:org.h2.Driver}",
        "agentflow.auth.demo-enabled=true"})
@AutoConfigureMockMvc
class ParticipantApplicationSearchIntegrationTest {
    private static final String PATH = "/api/v1/applications/search";
    private static final Instant SEED_CREATED_AT = Instant.parse("2020-01-01T00:00:00Z");
    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @Autowired JdbcTemplate jdbc;
    @Autowired DefinitionApplicationService definitions;
    @Autowired TaskService tasks;
    @MockitoSpyBean AuthService auth;

    @Test
    void scopesDraftsToApplicantUnlessTenantAdminAndReadsOnlySummaryColumns() throws Exception {
        String marker = "scope-" + UUID.randomUUID();
        String mine = seed("demo", "alice", marker), other = seed("demo", "bob", marker);
        String foreign = seed("foreign", "alice", marker);
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        assertThat(ids(read("alice", Map.of("q", marker)))).containsExactly(mine);
        assertThat(ids(read("admin", Map.of("q", marker)))).containsExactlyInAnyOrder(mine, other);
        doReturn(new Actor("demo", "designer", Set.of("PROCESS_ADMIN"))).when(auth).authenticate("designer-token");
        assertThat(ids(readToken("designer-token", Map.of("q", marker)))).isEmpty();
        doReturn(new Actor("foreign", "alice", Set.of("APPLICANT"))).when(auth).authenticate("foreign-token");
        assertThat(ids(readToken("foreign-token", Map.of("q", marker)))).containsExactly(foreign);
        assertThat(read("alice", Map.of("q", marker)).toString()).doesNotContain("payload", "formSchema", "not-json", other, foreign);
        mvc.perform(get(PATH).param("tenantId", "foreign").header("Authorization", token("alice")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/operations/applications").header("Authorization", token("alice"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/operations/applications/export").header("Authorization", token("alice"))).andExpect(status().isForbidden());
    }

    @Test
    void candidatesFollowLiveIdentityLinksAndClaimReleaseDoesNotCreatePermanentAccess() throws Exception {
        String id = draft("role:MANAGER"); submit(id);
        assertVisibility(id, "manager", true); assertVisibility(id, "employee", false);
        tasks.addCandidateUser(task(id), "employee");
        assertVisibility(id, "employee", true);
        tasks.deleteCandidateUser(task(id), "employee");
        assertVisibility(id, "employee", false);
        act(id, "manager", "CLAIM", null, 2);
        assertVisibility(id, "manager", true);
        act(id, "manager", "RELEASE", null, 3);
        assertVisibility(id, "manager", true);
        withdraw(id, 4);
        assertVisibility(id, "manager", false); assertVisibility(id, "alice", true);
    }

    @Test
    void realTransferDelegationAndReturnPreserveParticipantsAcrossRounds() throws Exception {
        String id = draft("user:manager"); submit(id);
        act(id, "manager", "TRANSFER", "bob", 2);
        assertVisibility(id, "manager", true); assertVisibility(id, "bob", true);
        act(id, "bob", "DELEGATE", "finance", 3);
        assertVisibility(id, "bob", true); assertVisibility(id, "finance", true);
        act(id, "finance", "RESOLVE", null, 4);
        act(id, "bob", "RETURN", null, 5);
        for (String user : List.of("manager", "bob", "finance")) assertVisibility(id, user, true);
        assertVisibility(id, "employee", false);
        mvc.perform(post("/api/v1/applications/" + id + "/submit").header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":6}"))
                .andExpect(status().isOk());
        for (String user : List.of("manager", "bob", "finance")) assertVisibility(id, user, true);
        assertThat(read("finance", Map.of("q", id)).path("items").get(0).path("roundNo").asInt()).isEqualTo(2);
    }

    @Test
    void historicAssigneeRemainsVisibleEvenWithoutTaskAuditAndUnclaimedRoleDoesNot() throws Exception {
        String id = draft("user:manager"); submit(id); act(id, "manager", "APPROVE", null, 2);
        jdbc.update("DELETE FROM audit_event WHERE application_id=? AND aggregate_type='Task'", id);
        assertVisibility(id, "manager", true); assertVisibility(id, "finance", false);
        String unclaimed = draft("role:MANAGER"); submit(unclaimed); withdraw(unclaimed, 2);
        assertVisibility(unclaimed, "manager", false);
    }

    @Test
    void participationBindsTenantOnTheSameProcessAndIgnoresTaskLocalVariables() throws Exception {
        String id = draft("user:manager"); submit(id);
        String taskId = task(id);
        String process = tasks.createTaskQuery().taskId(taskId).singleResult().getProcessInstanceId();
        jdbc.update("UPDATE ACT_RU_VARIABLE SET TEXT_='foreign' WHERE PROC_INST_ID_=? AND NAME_='tenantId'", process);
        jdbc.update("UPDATE ACT_HI_VARINST SET TEXT_='foreign' WHERE PROC_INST_ID_=? AND NAME_='tenantId'", process);
        assertVisibility(id, "manager", false);

        // 同一任务的局部租户值与其他流程的合法租户值，均不能替代该流程的租户绑定。
        tasks.setVariableLocal(taskId, "tenantId", "demo");
        String other = draft("user:manager"); submit(other);
        assertVisibility(id, "manager", false);
        assertVisibility(other, "manager", true);

        jdbc.update("UPDATE ACT_RU_VARIABLE SET TEXT_='demo' WHERE PROC_INST_ID_=? AND NAME_='tenantId' AND TASK_ID_ IS NULL", process);
        jdbc.update("UPDATE ACT_HI_VARINST SET TEXT_='demo' WHERE PROC_INST_ID_=? AND NAME_='tenantId' AND TASK_ID_ IS NULL", process);
        assertVisibility(id, "manager", true);
        jdbc.update("UPDATE ACT_RU_VARIABLE SET TYPE_='long' WHERE PROC_INST_ID_=? AND NAME_='applicationId'", process);
        jdbc.update("UPDATE ACT_HI_VARINST SET VAR_TYPE_='long' WHERE PROC_INST_ID_=? AND NAME_='applicationId'", process);
        assertVisibility(id, "manager", false);
    }

    @Test
    void multipleBindingsAndIdentitiesRemainOneApplicationAndDoNotRequireRootExecution() throws Exception {
        String id = draft("role:MANAGER"); submit(id);
        String taskId = task(id);
        String process = tasks.createTaskQuery().taskId(taskId).singleResult().getProcessInstanceId();
        tasks.addCandidateUser(taskId, "manager");
        // 参与关系沿用详情的流程变量语义，不能套用待办的根执行与当前轮次限制。
        jdbc.update("INSERT INTO ACT_RU_VARIABLE (ID_,REV_,TYPE_,NAME_,PROC_INST_ID_,TEXT_) VALUES (?,1,'string','applicationId',?,?)",
                UUID.randomUUID().toString(), process, id);
        jdbc.update("INSERT INTO ACT_RU_VARIABLE (ID_,REV_,TYPE_,NAME_,PROC_INST_ID_,TEXT_) VALUES (?,1,'string','tenantId',?,'demo')",
                UUID.randomUUID().toString(), process);
        assertThat(ids(read("manager", Map.of("q", id, "limit", "1")))).containsExactly(id);
        assertThat(read("manager", Map.of("q", id, "limit", "1")).path("nextCursor").asText("")).isEmpty();
        jdbc.update("DELETE FROM ACT_RU_VARIABLE WHERE PROC_INST_ID_=? AND EXECUTION_ID_ IS NOT NULL AND NAME_ IN ('applicationId','tenantId')", process);
        assertThat(ids(read("manager", Map.of("q", id)))).containsExactly(id);
        doReturn(new Actor("demo", "manager", Set.of())).when(auth).authenticate("no-role-token");
        // 无角色仍可读取指派给自己的申请，空角色集合不能生成无效 IN 子句。
        String assigned = draft("user:manager"); submit(assigned);
        assertThat(ids(readToken("no-role-token", Map.of("q", assigned)))).containsExactly(assigned);
    }

    @Test
    void filtersBeforePagingWithoutLeakingOrSkippingInterleavedInvisibleApplications() throws Exception {
        String marker = "paging-" + UUID.randomUUID(); var expected = new ArrayList<String>();
        for (int index = 0; index < 7; index++) {
            expected.add(seed("demo", "alice", marker + "100%_!")); seed("demo", "bob", marker + "100%_!");
        }
        seed("demo", "alice", marker + "100xyz");
        var filters = new HashMap<>(Map.of("q", marker + "100%_!", "limit", "2", "status", "DRAFT", "applicant", "alice", "from", "2020-01-01", "to", "2020-01-01"));
        var found = new ArrayList<String>(); String cursor;
        do {
            var page = read("alice", filters); found.addAll(ids(page)); cursor = page.path("nextCursor").asText("");
            if (!cursor.isEmpty()) { assertThat(ids(page)).hasSize(2); filters.put("cursor", cursor); }
        } while (!cursor.isEmpty());
        assertThat(found).hasSize(7).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
        assertThat(ids(read("alice", Map.of("q", marker, "applicant", "bob")))).isEmpty();
    }

    @Test
    void cursorBindsRolesAndRechecksLiveParticipationOnEveryPage() throws Exception {
        String a = draft("role:MANAGER"), b = draft("role:MANAGER"); submit(a); submit(b);
        String marker = "live-" + UUID.randomUUID();
        jdbc.update("UPDATE approval_application SET title=? WHERE id IN (?,?)", marker, a, b);
        var first = read("manager", Map.of("q", marker, "limit", "1"));
        String cursor = first.path("nextCursor").asText(); assertThat(cursor).isNotEmpty();
        String unread = ids(first).contains(a) ? b : a;
        tasks.deleteCandidateGroup(task(unread), "MANAGER");
        assertThat(ids(read("manager", Map.of("q", marker, "limit", "1", "cursor", cursor)))).isEmpty();
        doReturn(new Actor("demo", "manager", Set.of("APPROVER"))).when(auth).authenticate("changed-roles");
        mvc.perform(get(PATH).param("q", marker).param("cursor", cursor).header("Authorization", "Bearer changed-roles"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(PATH).param("q", marker).param("cursor", cursor).header("Authorization", token("bob")))
                .andExpect(status().isBadRequest());
    }

    private String seed(String tenant, String user, String title) {
        String id = UUID.randomUUID().toString();
        // 日期筛选使用 UTC；显式绑定同一时间点，避免 SQL 字面量随 JVM 本地时区漂移。
        jdbc.update("INSERT INTO approval_application (id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,created_at,updated_at) VALUES (?,?,?,'search',1,?,?,'not-json','DRAFT',0,1,?,?)",
                id, tenant, "SEARCH-" + id, user, title, Timestamp.from(SEED_CREATED_AT), Timestamp.from(SEED_CREATED_AT));
        return id;
    }
    private String draft(String assignee) throws Exception {
        String key = "participant-" + UUID.randomUUID();
        var definition = definitions.create("demo", key, "参与者检索", new Graph(List.of(
                new Node("start", "开始", NodeType.START, Map.of()),
                new Node("review", "审批", NodeType.USER_TASK, Map.of("assigneeRule", assignee)),
                new Node("end", "结束", NodeType.END, Map.of())), List.of(
                new Edge("begin", "start", "review", ""), new Edge("finish", "review", "end", ""))));
        definitions.publish(new Actor("demo", "admin", Set.of("ADMIN")), definition.id(), 0, "检索验证");
        var row = json.read(mvc.perform(post("/api/v1/applications").header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("businessNo", "PS-" + UUID.randomUUID(),
                        "processKey", key, "definitionVersion", 1, "title", "参与者检索申请", "payload", Map.of("secret", "private")))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), JsonNode.class);
        String id = row.path("id").asText(); jdbc.update("UPDATE approval_application SET title=? WHERE id=?", id, id); return id;
    }
    private void submit(String id) throws Exception {
        mvc.perform(post("/api/v1/applications/" + id + "/submit").header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}")) .andExpect(status().isOk());
    }
    private void withdraw(String id, long version) throws Exception {
        mvc.perform(post("/api/v1/applications/" + id + "/withdraw").header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("expectedVersion", version, "comment", "撤回"))))
                .andExpect(status().isOk());
    }
    private String task(String id) { return tasks.createTaskQuery().processVariableValueEquals("applicationId", id).singleResult().getId(); }
    private void act(String id, String user, String action, String target, long version) throws Exception {
        var body = new HashMap<String, Object>(Map.of("action", action, "expectedVersion", version, "comment", "检索授权验证"));
        if (target != null) body.put("targetUser", target);
        mvc.perform(post("/api/v1/tasks/" + task(id) + "/actions").header("Authorization", token(user))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(body))).andExpect(status().isOk());
    }
    private void assertVisibility(String id, String user, boolean visible) throws Exception {
        assertThat(ids(read(user, Map.of("q", id)))).as(user + " list visibility").isEqualTo(visible ? List.of(id) : List.of());
        mvc.perform(get("/api/v1/applications/" + id).header("Authorization", token(user)))
                .andExpect(status().is(visible ? 200 : 404));
    }
    private List<String> ids(JsonNode page) { return page.path("items").findValuesAsText("id"); }
    private JsonNode read(String user, Map<String, String> filters) throws Exception { return readToken(auth.login("demo", user, "demo").token(), filters); }
    private JsonNode readToken(String bearer, Map<String, String> filters) throws Exception {
        var request = get(PATH).header("Authorization", "Bearer " + bearer); filters.forEach(request::param);
        return json.read(mvc.perform(request).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
    }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
}
