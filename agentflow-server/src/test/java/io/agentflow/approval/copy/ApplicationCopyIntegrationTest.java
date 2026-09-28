package io.agentflow.approval.copy;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.common.JsonUtil;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实引擎节点、站内通知和独立轮次入口验证抄送不会变成普通申请访问权。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ApplicationCopyIntegrationTest {
    private static final String ATTACHMENT_DIRECTORY = "/fyoung/tmp/agentflow-copy-files-" + UUID.randomUUID();
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_COPY_TEST_URL", "jdbc:h2:mem:copy-tests;DB_CLOSE_DELAY=-1"));
        properties.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_COPY_TEST_DRIVER", "org.h2.Driver"));
        properties.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_COPY_TEST_USER", "sa"));
        properties.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_COPY_TEST_PASSWORD", ""));
        properties.add("agentflow.attachments.directory", () -> ATTACHMENT_DIRECTORY);
    }
    private static final String BOB = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1";
    private static final String MANAGER = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2";
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TaskService tasks;
    @Autowired CopyDeliveryService delivery;
    @Autowired CopyReadService copies;
    @Autowired io.agentflow.common.CurrentActor actors;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    @BeforeEach
    void directory() {
        jdbc.update("INSERT INTO organization_directory SELECT 'demo',1,'admin',CURRENT_TIMESTAMP WHERE NOT EXISTS (SELECT 1 FROM organization_directory WHERE tenant_id='demo')");
        for (var person : Map.of(BOB, "bob", MANAGER, "manager").entrySet()) {
            jdbc.update("INSERT INTO organization_person(tenant_id,id,subject,display_name,active,approval_eligible,revision) SELECT 'demo',?,?,?,TRUE,?,1 WHERE NOT EXISTS (SELECT 1 FROM organization_person WHERE tenant_id='demo' AND id=?)",
                    person.getKey(), person.getValue(), person.getValue(), person.getValue().equals("manager"), person.getKey());
        }
        jdbc.update("UPDATE organization_person SET active=TRUE WHERE tenant_id='demo'");
    }

    @Test
    void nonApproverReadsOnlyCopiedRoundAndFieldProjectionWithoutReceivingApprovalRights() throws Exception {
        assertThat(read("/process-definitions/copy-options", "admin", 200).toString()).contains(BOB);
        assertThat(read("/process-definitions/assignee-options", "admin", 200).toString()).doesNotContain(BOB);
        read("/process-definitions/copy-options", "bob", 403);
        String app = create();
        read(copyPath(app, 1), "bob", 404);
        write(post("/applications/" + app + "/submit"), "alice", Map.of("expectedVersion", 1), 200);
        var copied = read(copyPath(app, 1), "bob", 200);
        assertThat(copied.at("/payload/publicText").asText()).isEqualTo("第一轮公开内容");
        assertThat(copied.at("/payload/masked").asText()).isEqualTo("已脱敏");
        assertThat(copied.path("payload").has("secret")).isFalse();
        assertThat(copied.toString()).doesNotContain("第一轮秘密", "123456789");
        read("/applications/" + app, "bob", 404);
        read("/applications/" + app + "/rounds", "bob", 404);
        read(copyPath(app, 1), "finance", 404);
        read(copyPath(app, 1), "admin", 404);
        var task = tasks.createTaskQuery().processVariableValueEquals("applicationId", app).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("review");
        write(post("/tasks/" + task.getId() + "/actions"), "bob", Map.of("action", "APPROVE", "expectedVersion", 2), 403);
        write(post("/applications/" + app + "/withdraw"), "alice", Map.of("expectedVersion", 2, "comment", "补正"), 200);
        write(put("/applications/" + app), "alice", Map.of("expectedVersion", 3, "title", "新草稿标题", "payload", Map.of("publicText", "尚未提交的新内容")), 200);
        assertThat(read(copyPath(app, 1), "bob", 200).path("title").asText()).isEqualTo("抄送测试");
        assertThat(read(copyPath(app, 1), "bob", 200).toString()).doesNotContain("尚未提交", "新草稿标题");
        read(copyPath(app, 2), "bob", 404);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE application_id=? AND kind='APPLICATION_COPIED' AND recipient_id='bob'", Integer.class, app)).isEqualTo(1);
    }

    @Test
    void schemalessCopyPreservesNullableSchemaContract() throws Exception {
        String app = create(true, false, false);
        write(post("/applications/" + app + "/submit"), "alice", Map.of("expectedVersion", 1), 200);
        var copied = read(copyPath(app, 1), "bob", 200);
        assertThat(copied.has("formSchema")).isTrue();
        assertThat(copied.path("formSchema").isNull()).isTrue();
        assertThat(copied.at("/payload/publicText").asText()).isEqualTo("第一轮公开内容");
    }

    @Test
    void trailingCopyFailureRollsBackApprovalAndSuccessFinishesRound() throws Exception {
        String app = create(false, false);
        write(post("/applications/" + app + "/submit"), "alice", Map.of("expectedVersion", 1), 200);
        read(copyPath(app, 1), "bob", 404);
        String task = tasks.createTaskQuery().processVariableValueEquals("applicationId", app).singleResult().getId();
        jdbc.update("UPDATE organization_person SET active=FALSE WHERE tenant_id='demo' AND id=?", BOB);
        write(post("/tasks/" + task + "/actions"), "manager", Map.of("action", "APPROVE", "expectedVersion", 2), 422);
        assertThat(tasks.createTaskQuery().taskId(task).count()).isEqualTo(1);
        assertThat(read("/applications/" + app, "alice", 200).path("version").asLong()).isEqualTo(2);
        jdbc.update("UPDATE organization_person SET active=TRUE WHERE tenant_id='demo' AND id=?", BOB);
        write(post("/tasks/" + task + "/actions"), "manager", Map.of("action", "APPROVE", "expectedVersion", 2), 200);
        assertThat(read(copyPath(app, 1), "bob", 200).path("status").asText()).isEqualTo("APPROVED");
        var diagram = read("/applications/" + app + "/rounds/1/diagram", "alice", 200);
        assertThat(diagram.toString()).contains("\"type\":\"COPY\"");
    }

    @Test
    void repeatedNodeDoesNotReResolveRecipientsOrRepeatMessageAndTenantCannotRead() throws Exception {
        String app = create();
        write(post("/applications/" + app + "/submit"), "alice", Map.of("expectedVersion", 1), 200);
        var task = tasks.createTaskQuery().processVariableValueEquals("applicationId", app).singleResult();
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status ->
                delivery.deliver("demo", UUID.fromString(app), 1, task.getProcessInstanceId(), "copy", "copy",
                        "role:ORG_PERSON_" + MANAGER, null));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_copy_recipient WHERE application_id=?", Integer.class, app)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE application_id=? AND kind='APPLICATION_COPIED'", Integer.class, app)).isEqualTo(1);
        read(copyPath(app, 1), "manager", 404);
        actors.set(new io.agentflow.common.Actor("foreign", "bob", java.util.Set.of("ADMIN")));
        try { org.assertj.core.api.Assertions.assertThatThrownBy(() -> copies.get(UUID.fromString(app), 1)).hasMessageContaining("not found"); }
        finally { actors.clear(); }
    }

    @Test
    void copiedAttachmentReadsOnlyVisibleFrozenReferencesAfterDraftChanges() throws Exception {
        String app = create(true, true);
        byte[] bytes = "immutable attachment".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String visible = upload(app, "proof", bytes), hidden = upload(app, "privateProof", bytes);
        write(put("/applications/" + app), "alice", Map.of("expectedVersion", 1, "title", "附件抄送", "payload", Map.of("proof", List.of(visible), "privateProof", List.of(hidden))), 200);
        write(post("/applications/" + app + "/submit"), "alice", Map.of("expectedVersion", 2), 200);
        read(copyPath(app, 1) + "/attachments/" + visible, "bob", 200);
        read(copyPath(app, 1) + "/attachments/" + hidden, "bob", 403);
        read(copyPath(app, 1) + "/attachments/" + hidden + "/content", "bob", 403);
        read(copyPath(app, 2) + "/attachments/" + visible, "bob", 404);
        read("/applications/" + app + "/attachments/" + visible, "bob", 404);
        write(post("/applications/" + app + "/withdraw"), "alice", Map.of("expectedVersion", 3, "comment", "补正"), 200);
        write(put("/applications/" + app), "alice", Map.of("expectedVersion", 4, "title", "已移除当前引用", "payload", Map.of()), 200);
        var response = mvc.perform(get("/api/v1" + copyPath(app, 1) + "/attachments/" + visible + "/content").header("Authorization", token("bob")))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getContentAsByteArray()).isEqualTo(bytes);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("Content-Disposition")).startsWith("attachment;");
    }

    private String upload(String app, String path, byte[] bytes) throws Exception {
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        String id = write(post("/applications/" + app + "/attachments"), "alice", Map.of("expectedVersion", 1, "fieldPath", path,
                "filename", path + ".txt", "size", bytes.length, "sha256", digest), 201).path("id").asText();
        mvc.perform(put("/api/v1/applications/" + app + "/attachments/" + id + "/content").header("Authorization", token("alice"))
                .header("X-Application-Version", 1).contentType(MediaType.APPLICATION_OCTET_STREAM).content(bytes)).andExpect(status().isOk());
        return id;
    }

    @Test
    void inactiveRecipientsBlockNodeAtomicallyAndLoseExistingReadAccess() throws Exception {
        String app = create();
        jdbc.update("UPDATE organization_person SET active=FALSE WHERE tenant_id='demo' AND id=?", BOB);
        write(post("/applications/" + app + "/submit"), "alice", Map.of("expectedVersion", 1), 422);
        assertThat(read("/applications/" + app, "alice", 200).path("status").asText()).isEqualTo("DRAFT");
        assertThat(tasks.createTaskQuery().processVariableValueEquals("applicationId", app).count()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_copy_recipient WHERE application_id=?", Integer.class, app)).isZero();
        jdbc.update("UPDATE organization_person SET active=TRUE WHERE tenant_id='demo' AND id=?", BOB);
        write(post("/applications/" + app + "/submit"), "alice", Map.of("expectedVersion", 1), 200);
        read(copyPath(app, 1), "bob", 200);
        jdbc.update("UPDATE organization_person SET active=FALSE WHERE tenant_id='demo' AND id=?", BOB);
        read(copyPath(app, 1), "bob", 404);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_copy_recipient WHERE application_id=?", Integer.class, app)).isEqualTo(1);
    }

    private String create() throws Exception {
        return create(true, false);
    }
    private String create(boolean copyBeforeReview, boolean attachments) throws Exception {
        return create(copyBeforeReview, attachments, true);
    }
    private String create(boolean copyBeforeReview, boolean attachments, boolean withSchema) throws Exception {
        String key = "copy-" + UUID.randomUUID();
        var graph = Map.of("nodes", List.of(node("start", "START", Map.of()),
                node("copy", "COPY", Map.of("recipientRule", "role:ORG_PERSON_" + BOB)),
                node("review", "USER_TASK", Map.of("assigneeRule", "role:ORG_PERSON_" + MANAGER)), node("end", "END", Map.of())),
                "edges", copyBeforeReview ? List.of(edge("e1", "start", "copy"), edge("e2", "copy", "review"), edge("e3", "review", "end"))
                        : List.of(edge("e1", "start", "review"), edge("e2", "review", "copy"), edge("e3", "copy", "end")));
        var fields = new java.util.ArrayList<Map<String,Object>>(List.of(
                Map.of("key", "publicText", "label", "公开内容", "type", "TEXT", "required", false),
                Map.of("key", "masked", "label", "脱敏内容", "type", "TEXT", "required", false, "nodeAccess", Map.of("copy", "MASKED")),
                Map.of("key", "secret", "label", "秘密", "type", "TEXT", "required", false, "sensitive", true, "nodeAccess", Map.of("copy", "HIDDEN"))));
        if (attachments) {
            fields.add(Map.of("key", "proof", "label", "公开附件", "type", "ATTACHMENT", "required", false));
            fields.add(Map.of("key", "privateProof", "label", "隐藏附件", "type", "ATTACHMENT", "required", false, "nodeAccess", Map.of("copy", "HIDDEN")));
        }
        var schema = Map.of("schemaVersion", 1, "fields", fields);
        var definition = new java.util.LinkedHashMap<String, Object>(Map.of("key", key, "name", "抄送流程", "graph", graph));
        if (withSchema) definition.put("formSchema", schema);
        var draft = write(post("/process-definitions"), "admin", definition, 200);
        write(post("/process-definitions/" + draft.path("id").asText() + "/publish?expectedRevision=" + draft.path("revision").asLong()), "admin", Map.of("changeNote", "抄送验收"), 200);
        return write(post("/applications"), "alice", Map.of("businessNo", UUID.randomUUID().toString(), "processKey", key, "definitionVersion", 1,
                "title", "抄送测试", "payload", Map.of("publicText", "第一轮公开内容", "masked", "123456789", "secret", "第一轮秘密")), 201).path("id").asText();
    }
    private Map<String,Object> node(String id, String type, Map<String,String> properties) { return Map.of("id", id, "name", id, "type", type, "properties", properties); }
    private Map<String,Object> edge(String id, String source, String target) { return Map.of("id", id, "source", source, "target", target, "condition", ""); }
    private String copyPath(String app, int round) { return "/copies/" + app + "/rounds/" + round; }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private JsonNode read(String path, String user, int expected) throws Exception {
        return json.read(mvc.perform(get("/api/v1" + path).header("Authorization", token(user))).andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
    }
    private JsonNode write(MockHttpServletRequestBuilder request, String user, Object body, int expected) throws Exception {
        request.with(servlet -> { servlet.setRequestURI("/api/v1" + servlet.getRequestURI()); return servlet; });
        return json.read(mvc.perform(request.header("Authorization", token(user)).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(json.write(body))).andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
    }
}
