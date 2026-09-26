package io.agentflow.notification;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.common.JsonUtil;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.agentflow.support.MutationRequests.post;
import static io.agentflow.support.MutationRequests.put;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 从定义配置到真实审批消息验证文案快照，不使用模拟发送器。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:notification-texts;DB_CLOSE_DELAY=-1", "agentflow.auth.demo-enabled=true"})
@AutoConfigureMockMvc
class NotificationTextsIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;
    @Autowired TaskService tasks;
    @Autowired JdbcTemplate jdbc;

    @Test
    void legacyUpdatesPreserveConfigurationAndExplicitEmptyClearsIt() throws Exception {
        String key = "notice-" + UUID.randomUUID();
        JsonNode draft = write(post("/api/v1/process-definitions"), "admin", Map.of("key", key,
                "name", "通知", "graph", graph(), "notificationTexts", Map.of("approved", "已完成审批")), 200);
        String path = "/api/v1/process-definitions/" + draft.path("id").asText();
        JsonNode retained = write(put(path), "admin", Map.of("name", "兼容旧客户端", "graph", graph(), "expectedRevision", 0), 200);
        assertThat(retained.path("notificationTexts").path("approved").asText()).isEqualTo("已完成审批");
        JsonNode cleared = write(put(path), "admin", Map.of("name", "恢复平台提示", "graph", graph(), "expectedRevision", 1,
                "notificationTexts", Map.of()), 200);
        assertThat(cleared.path("notificationTexts").path("approved").asText()).isEmpty();
        write(put(path), "employee", Map.of("name", "无权覆盖", "graph", graph(), "expectedRevision", 2,
                "notificationTexts", Map.of("approved", "任意文案")), 403);
        write(post(path + "/publish?expectedRevision=2"), "admin", Map.of("changeNote", "配置确认"), 200);
        write(put(path), "admin", Map.of("name", "非法修改发布版本", "graph", graph(), "expectedRevision", 3,
                "notificationTexts", Map.of("approved", "任意文案")), 422);
    }

    @Test
    void rejectsInvalidConfigurationBeforeCreatingDefinitions() throws Exception {
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM approval_definition", Long.class);
        for (Object invalid : List.of(Map.of("approve", "拼写错误"), Map.of("approved", 42),
                Map.of("approved", "字".repeat(501)), Map.of("returned", "\u0000"), List.of("非对象"))) {
            mvc.perform(post("/api/v1/process-definitions").header("Authorization", token("admin"))
                    .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("key", "invalid-" + UUID.randomUUID(),
                            "name", "非法文案", "graph", graph(), "notificationTexts", invalid))))
                    .andExpect(status().is4xxClientError());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_definition", Long.class)).isEqualTo(before);
    }

    @Test
    void copiedTemplateUsesVersionTwoTextsAndRejectsTheStaleCatalogVersion() throws Exception {
        String key = "copy-" + UUID.randomUUID();
        JsonNode copy = write(post("/api/v1/process-templates/leave-request/copy"), "admin",
                Map.of("key", key, "name", "通知模板", "templateVersion", 2), 200);
        assertThat(copy.path("notificationTexts").path("submitted").asText()).isEqualTo("您的请假申请已提交，等待审批。");
        write(post("/api/v1/process-templates/leave-request/copy"), "admin",
                Map.of("key", key, "name", "旧目录版本", "templateVersion", 1), 409);
    }

    @Test
    void existingApplicationRetainsItsPublishedVersionAcrossReturnAndResubmission() throws Exception {
        String key = "versioned-notice-" + UUID.randomUUID();
        String applicationId = null;
        for (int version = 1; version <= 2; version++) {
            JsonNode definition = write(post("/api/v1/process-definitions"), "admin", Map.of("key", key, "name", "通知版本",
                    "graph", graph(), "notificationTexts", Map.of("submitted", "提交 V" + version, "returned", "退回 V" + version)), 200);
            write(post("/api/v1/process-definitions/" + definition.path("id").asText() + "/publish?expectedRevision=0"),
                    "admin", Map.of("changeNote", "发布通知 V" + version), 200);
            if (version == 1) {
                applicationId = write(post("/api/v1/applications"), "alice", Map.of("businessNo", key, "processKey", key,
                        "definitionVersion", 1, "title", "旧版申请", "payload", Map.of()), 201).path("id").asText();
            }
        }
        String path = "/api/v1/applications/" + applicationId;
        write(post(path + "/submit"), "alice", Map.of("expectedVersion", 1), 200);
        assertThat(message(applicationId, "APPLICATION_SUBMITTED").path("content").asText()).isEqualTo("提交 V1");
        String taskId = tasks.createTaskQuery().processVariableValueEquals("applicationId", applicationId).singleResult().getId();
        write(post("/api/v1/tasks/" + taskId + "/actions"), "manager", Map.of("action", "RETURN", "comment", "请补正", "expectedVersion", 2), 200);
        JsonNode returned = message(applicationId, "APPLICATION_RETURNED");
        assertThat(returned.path("content").asText()).isEqualTo("退回 V1");
        long version = jdbc.queryForObject("SELECT version FROM approval_application WHERE id=?", Long.class, applicationId);
        String replayKey = UUID.randomUUID().toString();
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path + "/submit")
                .header("Idempotency-Key", replayKey);
        JsonNode submitted = write(request, "alice", Map.of("expectedVersion", version), 200);
        JsonNode replay = write(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path + "/submit")
                .header("Idempotency-Key", replayKey), "alice", Map.of("expectedVersion", version), 200);
        assertThat(replay).isEqualTo(submitted);
        assertThat(message(applicationId, "APPLICATION_SUBMITTED").path("content").asText()).isEqualTo("提交 V1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE application_id=? AND kind='APPLICATION_SUBMITTED'",
                Integer.class, applicationId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT content FROM notification_inbox WHERE id=?", String.class, returned.path("id").asText())).isEqualTo("退回 V1");
    }

    @Test
    void publishedTextsAreDeliveredAsLiteralSnapshotsToTheApplicant() throws Exception {
        String key = "notice-" + UUID.randomUUID();
        Map<String, String> texts = Map.of("submitted", "已收到申请，请等待办理。", "returned", "请补充说明。",
                "approved", "<b>已批准</b> ${amount} 保留为原文。");
        JsonNode definition = write(post("/api/v1/process-definitions"), "admin", Map.of(
                "key", key, "name", "通知文案", "graph", graph(), "notificationTexts", texts), 200);
        assertThat(definition.path("notificationTexts")).isEqualTo(json.read(json.write(texts), JsonNode.class));
        write(post("/api/v1/process-definitions/" + definition.path("id").asText() + "/publish?expectedRevision=0"),
                "admin", Map.of("changeNote", "验证文案冻结"), 200);
        JsonNode application = write(post("/api/v1/applications"), "alice", Map.of("businessNo", key, "processKey", key,
                "definitionVersion", 1, "title", "文案申请", "payload", Map.of("amount", "机密正文")), 201);
        String id = application.path("id").asText();
        write(post("/api/v1/applications/" + id + "/submit"), "alice", Map.of("expectedVersion", 1), 200);
        assertThat(message(id, "APPLICATION_SUBMITTED").path("content").asText()).isEqualTo(texts.get("submitted"));
        String taskId = tasks.createTaskQuery().processVariableValueEquals("applicationId", id).singleResult().getId();
        write(post("/api/v1/tasks/" + taskId + "/actions"), "manager", Map.of("action", "APPROVE", "comment", "同意", "expectedVersion", 2), 200);
        JsonNode approved = message(id, "APPLICATION_APPROVED");
        assertThat(approved.path("content").asText()).isEqualTo(texts.get("approved"));
        assertThat(approved.toString()).doesNotContain("机密正文");
        JsonNode read = write(post("/api/v1/notifications/" + approved.path("id").asText() + "/read"), "alice", Map.of(), 200);
        assertThat(read.path("content")).isEqualTo(approved.path("content"));
    }

    private JsonNode message(String applicationId, String kind) throws Exception {
        JsonNode page = json.read(mvc.perform(get("/api/v1/notifications").header("Authorization", token("alice")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), JsonNode.class);
        for (JsonNode item : page.path("items")) {
            if (item.path("applicationId").asText().equals(applicationId) && item.path("kind").asText().equals(kind)) return item;
        }
        throw new AssertionError("Expected applicant message: " + kind);
    }

    private JsonNode write(MockHttpServletRequestBuilder request, String user, Object body, int expected) throws Exception {
        return json.read(mvc.perform(request.header("Authorization", token(user)).contentType(MediaType.APPLICATION_JSON)
                .content(json.write(body))).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString(), JsonNode.class);
    }

    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }

    private Map<String, Object> graph() {
        return Map.of("nodes", List.of(Map.of("id", "start", "name", "开始", "type", "START"),
                Map.of("id", "review", "name", "审批", "type", "USER_TASK", "properties", Map.of("assigneeRule", "user:manager")),
                Map.of("id", "end", "name", "结束", "type", "END")), "edges", List.of(
                Map.of("id", "a", "source", "start", "target", "review"), Map.of("id", "b", "source", "review", "target", "end")));
    }
}
