package io.agentflow.approval.history;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 两个读入口必须区分预留首轮、真实提交和已缺失的旧历史，作废不得掩盖曾提交事实。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:submission-history-gap;DB_CLOSE_DELAY=-1", "agentflow.auth.demo-enabled=true"})
@AutoConfigureMockMvc
class SubmissionHistoryGapIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;
    @Autowired JdbcTemplate jdbc;
    @Autowired DefinitionApplicationService definitions;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void neverSubmittedDraftDoesNotHaveMissingHistoryEvenAfterCancellation(boolean cancelled) throws Exception {
        var definition = definition();
        var application = create(definition);
        if (cancelled) application = change(application, "cancel");
        assertThat(application.path("roundNo").asInt()).isEqualTo(1);
        assertGap(definition, 0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_submission_round WHERE application_id=?",
                Long.class, application.path("id").asText())).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"RETURN", "WITHDRAW"})
    void cancelledSubmittedApplicationKeepsItsMissingLegacyRound(String action) throws Exception {
        var definition = definition();
        var application = change(create(definition), "submit");
        if (action.equals("WITHDRAW")) {
            application = change(application, "withdraw");
        } else {
            var all = json.read(mvc.perform(get("/api/v1/tasks").header("Authorization", token("manager")))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), JsonNode.class);
            String id = application.path("id").asText();
            var task = java.util.stream.StreamSupport.stream(all.spliterator(), false)
                    .filter(item -> item.path("applicationId").asText().equals(id)).findFirst().orElseThrow();
            mvc.perform(post("/api/v1/tasks/" + task.path("taskId").asText() + "/actions")
                    .header("Authorization", token("manager")).contentType(MediaType.APPLICATION_JSON)
                    .content(json.write(Map.of("action", action, "expectedVersion", task.path("version").asLong(), "comment", "历史缺口验收"))))
                    .andExpect(status().isOk());
            application = application(id);
        }
        assertGap(definition, 0);
        application = change(application, "cancel");
        assertGap(definition, 0);
        // 模拟升级前缺少快照和提交审计的旧申请；作废审计仍证明来源为退回或撤回。
        String id = application.path("id").asText();
        jdbc.update("DELETE FROM approval_submission_round WHERE application_id=?", id);
        jdbc.update("DELETE FROM audit_event WHERE application_id=? AND action<>'CANCEL'", id);
        assertGap(definition, 1);
    }

    @Test
    void cancellationNeedsMatchingAndReadableAuditBeforeExcludingReservedRound() throws Exception {
        var definition = definition();
        var application = change(create(definition), "cancel");
        String id = application.path("id").asText();
        var audit = jdbc.queryForMap("SELECT id,payload_json FROM audit_event WHERE application_id=? AND action='CANCEL'", id);
        assertGap(definition, 0);
        jdbc.update("UPDATE audit_event SET tenant_id='other' WHERE id=?", audit.get("id"));
        assertGap(definition, 1);
        jdbc.update("UPDATE audit_event SET tenant_id='demo',aggregate_version=999 WHERE id=?", audit.get("id"));
        assertGap(definition, 1);
        jdbc.update("UPDATE audit_event SET aggregate_version=?,payload_json='broken' WHERE id=?", application.path("version").asLong(), audit.get("id"));
        assertGap(definition, 1);
        jdbc.update("UPDATE audit_event SET payload_json=? WHERE id=?", audit.get("payload_json"), audit.get("id"));
        assertGap(definition, 0);
        var mismatched = json.map(audit.get("payload_json").toString());
        mismatched.put("applicationId", UUID.randomUUID().toString());
        jdbc.update("UPDATE audit_event SET payload_json=? WHERE id=?", json.write(mismatched), audit.get("id"));
        assertGap(definition, 1);
        jdbc.update("UPDATE audit_event SET payload_json=? WHERE id=?", audit.get("payload_json"), audit.get("id"));
        jdbc.update("""
                INSERT INTO audit_event(id,tenant_id,event_id,aggregate_type,aggregate_id,aggregate_version,application_id,action,payload_json)
                SELECT ?,tenant_id,?,aggregate_type,aggregate_id,aggregate_version,application_id,action,payload_json
                FROM audit_event WHERE id=?
                """, UUID.randomUUID().toString(), UUID.randomUUID().toString(), audit.get("id"));
        assertGap(definition, 1); // 同一变更出现多份证据时不擅自选一份抹去缺口。
    }

    private DefinitionDraft definition() {
        var graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("review", "经理审批", NodeType.USER_TASK, Map.of("assigneeRule", "user:manager")),
                new Node("end", "结束", NodeType.END, Map.of())), List.of(new Edge("a", "start", "review", ""), new Edge("b", "review", "end", "")));
        var definition = definitions.create("demo", "history-gap-" + UUID.randomUUID(), "提交历史缺口验收", graph);
        definitions.publish(new Actor("demo", "admin", Set.of("ADMIN")), definition.id(), 0, "验证未提交草稿");
        return definition;
    }

    private JsonNode create(DefinitionDraft definition) throws Exception {
        return json.read(mvc.perform(post("/api/v1/applications").header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("processKey", definition.key(), "definitionVersion", 1,
                        "businessNo", UUID.randomUUID().toString(), "title", "历史缺口验收申请", "payload", Map.of()))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), JsonNode.class);
    }

    private JsonNode change(JsonNode application, String action) throws Exception {
        return json.read(mvc.perform(post("/api/v1/applications/" + application.path("id").asText() + "/" + action)
                .header("Authorization", token("alice")).contentType(MediaType.APPLICATION_JSON)
                .content(json.write(Map.of("expectedVersion", application.path("version").asLong(), "comment", "历史缺口验收"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), JsonNode.class);
    }

    private JsonNode application(String id) throws Exception {
        return json.read(mvc.perform(get("/api/v1/applications/" + id).header("Authorization", token("alice")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), JsonNode.class);
    }

    private void assertGap(DefinitionDraft definition, long expected) throws Exception {
        JsonNode guide = json.read(mvc.perform(get("/api/v1/system/first-workflow").param("definitionId", definition.id().toString())
                .header("Authorization", token("admin"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), JsonNode.class);
        JsonNode operations = json.read(mvc.perform(get("/api/v1/operations/approvals").param("processKey", definition.key()).param("definitionVersion", "1")
                .header("Authorization", token("admin"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), JsonNode.class);
        assertSoftly(softly -> {
            softly.assertThat(guide.path("unrecordedHistoricalRounds").asLong()).as("首次流程引导").isEqualTo(expected);
            softly.assertThat(operations.path("unrecordedHistoricalRounds").asLong()).as("审批运营统计").isEqualTo(expected);
        });
    }

    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
}
