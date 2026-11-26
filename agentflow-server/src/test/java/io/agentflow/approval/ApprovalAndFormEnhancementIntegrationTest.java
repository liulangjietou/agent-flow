package io.agentflow.approval;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import io.agentflow.definition.DefinitionModels.DefinitionDraft;
import io.agentflow.definition.DefinitionModels.Edge;
import io.agentflow.definition.DefinitionModels.Graph;
import io.agentflow.definition.DefinitionModels.Node;
import io.agentflow.definition.DefinitionModels.NodeType;
import io.agentflow.form.FieldVisibility;
import io.agentflow.form.FormSchema;
import io.agentflow.notification.InboxMessage;
import io.agentflow.notification.InboxRepository;
import io.agentflow.organization.OrganizationPerson;
import io.agentflow.organization.OrganizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证第二阶段审批协作与动态表单增强：催办冷却、代理自助、退回指定历史节点与审批节点可编辑字段回填。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {
        "agentflow.auth.demo-enabled=false",
        "agentflow.sla.reminders-enabled=false",
        "agentflow.approval.urge-cooldown-minutes=30"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ApprovalAndFormEnhancementIntegrationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:approval-form-enhancements;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
    }

    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @Autowired OrganizationService organization;
    @Autowired DefinitionApplicationService definitions;
    @Autowired InboxRepository inbox;
    @MockitoSpyBean AuthService auth;

    private String tenantId;
    private Actor admin;
    private Actor applicant;
    private Actor manager;
    private Actor director;
    private Actor substitute;
    private String applicantToken;
    private String managerToken;
    private String directorToken;
    private String substituteToken;
    private OrganizationPerson managerPerson;
    private OrganizationPerson directorPerson;
    private OrganizationPerson substitutePerson;
    private DefinitionDraft definition;

    @BeforeEach
    void setup() {
        tenantId = "phase2-enh-" + UUID.randomUUID();
        admin = new Actor(tenantId, "admin", Set.of("ADMIN", "APPROVER", "USER"));
        applicant = new Actor(tenantId, "alice", Set.of("USER"));
        manager = new Actor(tenantId, "bob", Set.of("APPROVER", "USER"));
        director = new Actor(tenantId, "carol", Set.of("APPROVER", "USER"));
        substitute = new Actor(tenantId, "dave", Set.of("APPROVER", "USER"));
        applicantToken = registerToken(applicant);
        managerToken = registerToken(manager);
        directorToken = registerToken(director);
        substituteToken = registerToken(substitute);

        organization.initialize(admin);
        organization.createPerson(admin, applicant.userId(), "申请人Alice", false, true);
        managerPerson = organization.createPerson(admin, manager.userId(), "经理Bob", true, true);
        directorPerson = organization.createPerson(admin, director.userId(), "总监Carol", true, true);
        substitutePerson = organization.createPerson(admin, substitute.userId(), "代理Dave", true, true);

        var graph = new Graph(
                List.of(
                        new Node("start", "开始", NodeType.START, Map.of()),
                        new Node("dept_review", "部门审批", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_" + managerPerson.id())),
                        new Node("fin_review", "财务复核", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_" + directorPerson.id())),
                        new Node("end", "结束", NodeType.END, Map.of())),
                List.of(
                        new Edge("e1", "start", "dept_review", ""),
                        new Edge("e2", "dept_review", "fin_review", ""),
                        new Edge("e3", "fin_review", "end", "")));
        var schema = new FormSchema(FormSchema.CURRENT_VERSION, List.of(
                new FormSchema.Field("reason", "事由", FormSchema.FieldType.TEXT, true, null, 200, null, null, null, null, null, false,
                        Map.of("dept_review", FieldVisibility.READ_ONLY, "fin_review", FieldVisibility.READ_ONLY)),
                new FormSchema.Field("reviewCode", "复核编号", FormSchema.FieldType.TEXT, false, null, 64, null, null, null, null, null, false,
                        Map.of("dept_review", FieldVisibility.EDITABLE, "fin_review", FieldVisibility.READ_ONLY)),
                new FormSchema.Field("secretNote", "机密备注", FormSchema.FieldType.TEXT, false, null, 200, null, null, null, null, null, true,
                        Map.of("dept_review", FieldVisibility.HIDDEN, "fin_review", FieldVisibility.MASKED))));
        var draft = definitions.create(tenantId, "enh-flow-" + UUID.randomUUID(), "二级审批流程", graph, schema);
        definition = definitions.publish(admin, draft.id(), draft.revision(), "二阶段集成测试");
    }

    @Test
    void applicantCanUrgeInApprovalApplicationWithCooldownAndNonApplicantIsForbidden() throws Exception {
        var submitted = createAndSubmit("URGE-" + UUID.randomUUID(), Map.of("reason", "出差申请", "secretNote", "内部"));
        String appId = submitted.path("id").asText();
        long version = submitted.path("version").asLong();

        // 非申请人催办应返回 403
        mvc.perform(post("/api/v1/applications/" + appId + "/urge")
                        .header("Authorization", "Bearer " + managerToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of("expectedVersion", version, "note", "请加快审批"))))
                .andExpect(status().isForbidden());

        // 申请人首次催办成功
        var urgeResp = json.read(mvc.perform(post("/api/v1/applications/" + appId + "/urge")
                        .header("Authorization", "Bearer " + applicantToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of("expectedVersion", version, "note", "明天出发请尽快审批"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
        assertThat(urgeResp.path("applicationId").asText()).isEqualTo(appId);
        assertThat(urgeResp.path("notifiedCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(urgeResp.path("nextAllowedAt").asText()).isNotBlank();

        // 当前待办审批人收到 TASK_URGED 站内消息
        List<InboxMessage> bobMessages = inbox.list(manager, new InboxRepository.Query(false, 20, null, null));
        assertThat(bobMessages).anyMatch(msg -> msg.kind() == InboxMessage.Kind.TASK_URGED
                && msg.applicationId().toString().equals(appId));

        // 冷却期内重复催办返回 429 APPLICATION_URGE_COOLDOWN
        var cooldownErr = json.read(mvc.perform(post("/api/v1/applications/" + appId + "/urge")
                        .header("Authorization", "Bearer " + applicantToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of("expectedVersion", version, "note", "再次催办"))))
                .andExpect(status().isTooManyRequests())
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
        assertThat(cooldownErr.path("code").asText()).isEqualTo("APPLICATION_URGE_COOLDOWN");
    }

    @Test
    void approverCanSelfManageOwnApprovalProxyWithoutAdminRole() throws Exception {
        Instant startsAt = Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.MICROS);
        Instant endsAt = startsAt.plusSeconds(3600);

        // 非审批人不可访问自助代理
        mvc.perform(get("/api/v1/organization/approval-proxies/self")
                        .header("Authorization", "Bearer " + applicantToken))
                .andExpect(status().isForbidden());

        // 审批人自助创建（不得传 principalId）
        mvc.perform(post("/api/v1/organization/approval-proxies/self")
                        .header("Authorization", "Bearer " + managerToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of(
                                "definitionId", definition.id(),
                                "principalId", directorPerson.id(),
                                "substituteId", substitutePerson.id(),
                                "startsAt", startsAt,
                                "endsAt", endsAt,
                                "reason", "越权指定他人"))))
                .andExpect(status().isBadRequest());

        var created = json.read(mvc.perform(post("/api/v1/organization/approval-proxies/self")
                        .header("Authorization", "Bearer " + managerToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of(
                                "definitionId", definition.id(),
                                "substituteId", substitutePerson.id(),
                                "startsAt", startsAt,
                                "endsAt", endsAt,
                                "reason", "休假委托"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
        String proxyId = created.path("proxyId").asText();
        assertThat(proxyId).isNotBlank();

        // 被代理人与代理人均可看到该条代理
        var managerPage = json.read(mvc.perform(get("/api/v1/organization/approval-proxies/self")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
        assertThat(managerPage.path("items")).hasSize(1);
        assertThat(managerPage.path("items").get(0).path("principal").path("subject").asText()).isEqualTo(manager.userId());

        // 他人（包括代理人自己）不得自助撤销原被代理人的代理
        mvc.perform(post("/api/v1/organization/approval-proxies/self/" + proxyId + "/revoke")
                        .header("Authorization", "Bearer " + substituteToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of("expectedRevision", 1, "reason", "非本人撤销"))))
                .andExpect(status().isForbidden());

        // 被代理人本人自助撤销成功
        var revoked = json.read(mvc.perform(post("/api/v1/organization/approval-proxies/self/" + proxyId + "/revoke")
                        .header("Authorization", "Bearer " + managerToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of("expectedRevision", 1, "reason", "提前结束休假"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
        assertThat(revoked.path("revision").asLong()).isEqualTo(2);
    }

    @Test
    void approvalNodeCanEditDeclaredEditableFieldsAndReturnToHistoricalNode() throws Exception {
        var submitted = createAndSubmit("NODE-" + UUID.randomUUID(), Map.of("reason", "设备采购", "secretNote", "报价底线"));
        String appId = submitted.path("id").asText();
        long version = submitted.path("version").asLong();

        // 1. 部门审批节点（Bob）尝试修改 READ_ONLY 字段 reason -> 403 TASK_FIELD_NOT_EDITABLE
        String deptTaskId = findTaskId(managerToken, appId);
        var forbiddenField = json.read(mvc.perform(patch("/api/v1/tasks/" + deptTaskId + "/fields")
                        .header("Authorization", "Bearer " + managerToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of("expectedVersion", version, "fields", Map.of("reason", "篡改事由")))))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
        assertThat(forbiddenField.path("code").asText()).isEqualTo("TASK_FIELD_NOT_EDITABLE");

        // 2. 部门审批节点（Bob）回填声明为 EDITABLE 的 reviewCode -> 200 并推进版本号
        var patched = json.read(mvc.perform(patch("/api/v1/tasks/" + deptTaskId + "/fields")
                        .header("Authorization", "Bearer " + managerToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of("expectedVersion", version, "fields", Map.of("reviewCode", "DEPT-2026-001")))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
        version = patched.path("version").asLong();
        assertThat(patched.path("action").asText()).isEqualTo("TASK_FIELDS_UPDATED");

        // 申请人可见最新回填值，且原字段未受损
        var appDetail = json.read(mvc.perform(get("/api/v1/applications/" + appId)
                        .header("Authorization", "Bearer " + applicantToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
        assertThat(appDetail.path("payload").path("reviewCode").asText()).isEqualTo("DEPT-2026-001");
        assertThat(appDetail.path("payload").path("reason").asText()).isEqualTo("设备采购");

        // 3. 部门审批通过，进入财务复核节点（Carol）
        var approvedDept = json.read(mvc.perform(post("/api/v1/tasks/" + deptTaskId + "/actions")
                        .header("Authorization", "Bearer " + managerToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of("action", "APPROVE", "comment", "部门通过", "expectedVersion", version))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
        version = approvedDept.path("version").asLong();

        String finTaskId = findTaskId(directorToken, appId);

        // 财务节点 reviewCode 是 READ_ONLY，不得编辑 -> 403
        mvc.perform(patch("/api/v1/tasks/" + finTaskId + "/fields")
                        .header("Authorization", "Bearer " + directorToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of("expectedVersion", version, "fields", Map.of("reviewCode", "FIN-CHANGE")))))
                .andExpect(status().isForbidden());

        // 4. 查询可退回的历史节点 -> 应包含 dept_review
        var returnable = json.read(mvc.perform(get("/api/v1/tasks/" + finTaskId + "/returnable-nodes")
                        .header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
        assertThat(returnable).hasSize(1);
        assertThat(returnable.get(0).path("nodeId").asText()).isEqualTo("dept_review");

        // 退回至不存在节点 -> 422 TASK_RETURN_NODE_INVALID
        var invalidReturn = json.read(mvc.perform(post("/api/v1/tasks/" + finTaskId + "/return-to-node")
                        .header("Authorization", "Bearer " + directorToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of("targetNodeId", "unknown_node", "comment", "退回测试", "expectedVersion", version))))
                .andExpect(status().isUnprocessableEntity())
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
        assertThat(invalidReturn.path("code").asText()).isEqualTo("TASK_RETURN_NODE_INVALID");

        // 5. 财务退回至 dept_review -> 200，应用仍保持 IN_APPROVAL 且 Bob 重新获得待办
        var returned = json.read(mvc.perform(post("/api/v1/tasks/" + finTaskId + "/return-to-node")
                        .header("Authorization", "Bearer " + directorToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of("targetNodeId", "dept_review", "comment", "请部门补充复核编号说明", "expectedVersion", version))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
        assertThat(returned.path("action").asText()).isEqualTo("RETURN_TO_NODE");
        assertThat(returned.path("applicationStatus").asText()).isEqualTo("IN_APPROVAL");

        String reopenedDeptTaskId = findTaskId(managerToken, appId);
        assertThat(reopenedDeptTaskId).isNotBlank().isNotEqualTo(deptTaskId);
    }

    private JsonNode createAndSubmit(String businessNo, Map<String, Object> payload) throws Exception {
        var created = json.read(mvc.perform(post("/api/v1/applications")
                        .header("Authorization", "Bearer " + applicantToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of(
                                "businessNo", businessNo,
                                "processKey", definition.key(),
                                "definitionVersion", definition.version(),
                                "title", "测试单-" + businessNo,
                                "payload", payload))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
        String appId = created.path("id").asText();
        long version = created.path("version").asLong();
        return json.read(mvc.perform(post("/api/v1/applications/" + appId + "/submit")
                        .header("Authorization", "Bearer " + applicantToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of("expectedVersion", version))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
    }

    private String findTaskId(String token, String applicationId) throws Exception {
        var tasks = json.read(mvc.perform(get("/api/v1/tasks")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
        for (JsonNode task : tasks) {
            if (applicationId.equals(task.path("applicationId").asText())) {
                return task.path("taskId").asText();
            }
        }
        throw new AssertionError("No pending task found for application " + applicationId);
    }

    private String registerToken(Actor actor) {
        String token = "tok-" + UUID.randomUUID();
        doReturn(actor).when(auth).authenticate(token);
        return token;
    }
}
