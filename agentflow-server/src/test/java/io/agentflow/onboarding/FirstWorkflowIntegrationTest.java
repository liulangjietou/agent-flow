package io.agentflow.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import org.junit.jupiter.api.Test;
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
import static io.agentflow.support.MutationRequests.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 验证引导进度来自所选版本的真实审批结果，不能由历史版本、模拟或跨租户数据推断。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:first-workflow;DB_CLOSE_DELAY=-1", "agentflow.auth.demo-enabled=true"})
@AutoConfigureMockMvc
class FirstWorkflowIntegrationTest {
    @Autowired MockMvc mvc;
    @MockitoSpyBean AuthService auth;
    @Autowired JsonUtil json;
    @Autowired JdbcTemplate jdbc;
    @Autowired DefinitionApplicationService definitions;
    @Autowired FirstWorkflowReadPort reader;
    private static final Actor ADMIN = new Actor("demo", "admin", Set.of("ADMIN"));

    @Test
    void tracksRealReturnResubmitWithdrawAndApprovalWithoutMutatingHistory() throws Exception {
        var draft = definitions.create("demo", "guide-" + UUID.randomUUID(), "引导真实流程", graph());
        assertThat(report(draft.id()).path("definition").path("version").asLong()).isZero();
        definitions.publish(ADMIN, draft.id(), 0, "首条流程引导验收");
        assertThat(report(draft.id()).path("submittedRounds").asLong()).isZero();
        var application = response(mvc.perform(post("/api/v1/applications").header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("processKey", draft.key(), "definitionVersion", 1,
                        "businessNo", UUID.randomUUID().toString(), "title", "真实引导申请", "payload", Map.of("secret", "do-not-leak")))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        application = submit(application);
        act(application, "RETURN");
        var returned = report(draft.id());
        assertThat(returned.path("submittedRounds").asLong()).isEqualTo(1);
        assertThat(returned.path("approvedRounds").asLong()).isZero();
        assertThat(returned.path("unrecordedHistoricalRounds").asLong()).isZero();
        assertThat(returned.path("latestSubmission").path("status").asText()).isEqualTo("RETURNED");
        application = submit(application(application.path("id").asText()));
        mvc.perform(post("/api/v1/applications/" + application.path("id").asText() + "/withdraw")
                .header("Authorization", token("alice")).contentType(MediaType.APPLICATION_JSON)
                .content(json.write(Map.of("expectedVersion", application.path("version").asLong(), "comment", "补充说明"))))
                .andExpect(status().isOk());
        assertThat(report(draft.id()).path("approvedRounds").asLong()).isZero();
        assertThat(report(draft.id()).path("unrecordedHistoricalRounds").asLong()).isZero();
        application = submit(application(application.path("id").asText()));
        act(application, "APPROVE");
        String appId = application.path("id").asText();
        var before = jdbc.queryForList("SELECT * FROM approval_submission_round WHERE application_id=? ORDER BY round_no", appId);
        var completed = report(draft.id());
        assertThat(completed.path("submittedRounds").asLong()).isEqualTo(3);
        assertThat(completed.path("approvedRounds").asLong()).isEqualTo(1);
        assertThat(completed.path("unrecordedHistoricalRounds").asLong()).isZero();
        assertThat(completed.path("latestApproval").path("roundNo").asInt()).isEqualTo(3);
        assertThat(completed.path("latestApproval").path("applicationId").asText()).isEqualTo(appId);
        assertThat(completed.toString()).doesNotContain("do-not-leak", "payload", "reason");
        assertThat(jdbc.queryForList("SELECT * FROM approval_submission_round WHERE application_id=? ORDER BY round_no", appId)).isEqualTo(before);
        var next = definitions.create("demo", draft.key(), "下一版本草稿", graph());
        assertThat(report(next.id()).path("approvedRounds").asLong()).isZero();
        definitions.publish(ADMIN, next.id(), 0, "验证旧版本不能冒充新版本已运行");
        assertThat(report(next.id()).path("definition").path("version").asLong()).isEqualTo(2);
        assertThat(report(next.id()).path("submittedRounds").asLong()).isZero();
        assertThat(report(next.id()).has("latestApproval")).isFalse();
        assertThat(report(draft.id()).path("approvedRounds").asLong()).isEqualTo(1);
    }

    @Test
    void usesTenantDefinitionAndRoundBindingRatherThanCurrentApplicationStatus() {
        String tenant = "isolated-" + UUID.randomUUID(), key = "shared-key";
        var draft = definitions.create(tenant, key, "私有版本", graph());
        jdbc.update("UPDATE approval_definition SET status='PUBLISHED',version=1 WHERE id=?", draft.id().toString());
        String app = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version)
                VALUES(?,?,?,'shared-key',1,'alice','私有申请','{}','APPROVED',1,2)
                """, app, tenant, app);
        // 当前申请已批准但缺少轮次快照，不得补造一轮已运行事实。
        assertThat(reader.read(tenant, draft.id(), Instant.now()).submittedRounds()).isZero();
        assertThat(reader.read(tenant, draft.id(), Instant.now()).unrecordedHistoricalRounds()).isEqualTo(1);
        jdbc.update("""
                INSERT INTO approval_submission_round(tenant_id,application_id,round_no,process_instance_id,definition_version,title,payload_json,submitted_by,submitted_at,status)
                VALUES('other',?,1,?,1,'他租户损坏绑定','{}','alice',CURRENT_TIMESTAMP,'IN_APPROVAL')
                """, app, UUID.randomUUID().toString());
        assertThat(reader.read(tenant, draft.id(), Instant.now()).submittedRounds()).isZero();
        assertThat(reader.read(tenant, draft.id(), Instant.now()).unrecordedHistoricalRounds()).isEqualTo(1);
        assertThat(reader.read(tenant, null, Instant.now()).definition().id()).isEqualTo(draft.id());
        assertThat(reader.read("empty-" + UUID.randomUUID(), null, Instant.now()).definition()).isNull();
    }

    @Test
    void rejectsUnauthorisedAccountsUnknownParametersAndCrossTenantDefinitions() throws Exception {
        String path = "/api/v1/system/first-workflow";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", token("alice"))).andExpect(status().isForbidden());
        doReturn(new Actor("demo", "designer", Set.of("PROCESS_ADMIN"))).when(auth).authenticate("designer-only");
        mvc.perform(get(path).header("Authorization", "Bearer designer-only")).andExpect(status().isForbidden());
        var other = definitions.create("other", "private", "其他租户", graph());
        for (String id : List.of(other.id().toString(), UUID.randomUUID().toString())) {
            mvc.perform(get(path).param("definitionId", id).header("Authorization", token("admin")))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("code").value("NOT_FOUND"));
        }
        for (String query : List.of("tenantId=other", "definitionId=1-1-1-1-1", "definitionId=", "definitionId=bad", "userId=admin")) {
            mvc.perform(get(path + "?" + query).header("Authorization", token("admin")))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_FIRST_WORKFLOW_QUERY"));
        }
    }

    private Graph graph() {
        return new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("review", "经理审批", NodeType.USER_TASK, Map.of("assigneeRule", "user:manager")),
                new Node("end", "结束", NodeType.END, Map.of())), List.of(new Edge("a", "start", "review", ""), new Edge("b", "review", "end", "")));
    }
    private JsonNode report(UUID id) throws Exception {
        return response(mvc.perform(get("/api/v1/system/first-workflow").param("definitionId", id.toString())
                .header("Authorization", token("admin"))).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString());
    }
    private JsonNode submit(JsonNode application) throws Exception {
        return response(mvc.perform(post("/api/v1/applications/" + application.path("id").asText() + "/submit")
                .header("Authorization", token("alice")).contentType(MediaType.APPLICATION_JSON)
                .content(json.write(Map.of("expectedVersion", application.path("version").asLong()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private JsonNode application(String id) throws Exception {
        return response(mvc.perform(get("/api/v1/applications/" + id).header("Authorization", token("alice")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private void act(JsonNode application, String action) throws Exception {
        JsonNode all = response(mvc.perform(get("/api/v1/tasks").header("Authorization", token("manager")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode task = java.util.stream.StreamSupport.stream(all.spliterator(), false)
                .filter(item -> item.path("applicationId").asText().equals(application.path("id").asText())).findFirst().orElseThrow();
        mvc.perform(post("/api/v1/tasks/" + task.path("taskId").asText() + "/actions").header("Authorization", token("manager"))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("action", action,
                        "expectedVersion", task.path("version").asLong(), "comment", "引导实际审批")))).andExpect(status().isOk());
    }
    private JsonNode response(String value) { return json.read(value, JsonNode.class); }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
}
