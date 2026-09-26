package io.agentflow.organization;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.approval.process.FlowableOrganizationMembers;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import org.flowable.engine.TaskService;
import org.flowable.engine.RuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 演示登录关闭，通过明确的测试认证主体验证本地组织、真实发布、候选快照及当前资格。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=false", "agentflow.sla.reminders-enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class OrganizationApprovalIntegrationTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("agentflow.organization-test.jdbc-url", "jdbc:h2:mem:organization-approval;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"));
        registry.add("spring.datasource.driver-class-name", () -> System.getProperty("agentflow.organization-test.jdbc-driver", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getProperty("agentflow.organization-test.jdbc-user", "sa"));
        registry.add("spring.datasource.password", () -> System.getProperty("agentflow.organization-test.jdbc-password", ""));
    }

    @Autowired MockMvc mvc;
    @MockitoSpyBean AuthService auth;
    @Autowired JsonUtil json;
    @Autowired OrganizationService organization;
    @Autowired LocalOrganizationDirectory directory;
    @Autowired DefinitionApplicationService definitions;
    @Autowired TaskService tasks;
    @Autowired RuntimeService runtime;
    private Actor admin;
    private OrganizationUnit department;
    private OrganizationUnit position;
    private OrganizationPerson first;
    private OrganizationPerson second;
    private OrganizationAppointment firstJob;
    private String applicantToken;
    private String firstToken;
    private String secondToken;

    @BeforeEach
    void setup() {
        String tenant = "local-" + UUID.randomUUID();
        admin = new Actor(tenant, "administrator", Set.of("ADMIN"));
        organization.initialize(admin);
        var legal = organization.createUnit(admin, OrganizationUnit.Kind.LEGAL_ENTITY, "法人", null, null, true);
        department = organization.createUnit(admin, OrganizationUnit.Kind.DEPARTMENT, "审核部", legal.id(), null, true);
        position = organization.createUnit(admin, OrganizationUnit.Kind.POSITION, "审核岗", legal.id(), null, true);
        first = organization.createPerson(admin, "issuer:first/审批人", "甲", true, true);
        second = organization.createPerson(admin, "issuer:second/审批人", "乙", true, true);
        firstJob = organization.createAppointment(admin, first.id(), department.id(), position.id(), true);
        organization.createAppointment(admin, second.id(), department.id(), position.id(), true);
        applicantToken = identity("applicant", Set.of("EMPLOYEE"));
        firstToken = identity(first.subject(), Set.of("EMPLOYEE", "APPROVER"));
        secondToken = identity(second.subject(), Set.of("EMPLOYEE", "APPROVER"));
    }

    @Test
    void departmentCandidatesAreFrozenAndInactivePeopleLoseCurrentTaskAccess() throws Exception {
        String key = publish("role:" + LocalOrganizationDirectory.UNIT_ROLE + department.id(), "SINGLE");
        JsonNode application = submit(key);
        String appId = application.path("id").asText();
        var task = tasks.createTaskQuery().processVariableValueEquals("applicationId", appId).singleResult();
        assertThat(tasks.getIdentityLinksForTask(task.getId())).extracting(org.flowable.identitylink.api.IdentityLink::getUserId)
                .contains(first.subject(), second.subject());
        String snapshot = (String) runtime.getVariable(task.getExecutionId(), FlowableOrganizationMembers.SNAPSHOT_PREFIX + "review");
        assertThat(snapshot).contains(first.subject(), second.subject(), "directoryRevision");
        organization.updateAppointment(admin, firstJob.id(), false, 1);
        var third = organization.createPerson(admin, "third", "新任职人员", true, true);
        organization.createAppointment(admin, third.id(), department.id(), position.id(), true);
        assertThat(runtime.getVariable(task.getExecutionId(), FlowableOrganizationMembers.SNAPSHOT_PREFIX + "review")).isEqualTo(snapshot);
        String thirdToken = identity(third.subject(), Set.of("APPROVER"));
        read("/tasks/" + task.getId(), thirdToken, 403);
        // 任职变化不移走旧责任，人员资格停用才使办理失效。
        read("/tasks/" + task.getId(), firstToken, 200);
        organization.updatePerson(admin, first.id(), first.displayName(), false, true, 1);
        read("/tasks/" + task.getId(), firstToken, 403);
        assertThat(read("/tasks", firstToken, 200)).isEmpty();
        assertThat(read("/workspace/tasks", firstToken, 200).path("items")).isEmpty();
        write(post("/api/v1/tasks/" + task.getId() + "/actions"), firstToken, Map.of("action", "APPROVE", "expectedVersion", 2), 403);
        write(post("/api/v1/tasks/" + task.getId() + "/actions"), secondToken, Map.of("action", "APPROVE", "expectedVersion", 2), 200);
        assertThat(read("/applications/" + appId, applicantToken, 200).path("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void personRuleHandlesOpaqueOidcSubjectsButNeverGrantsMissingSystemRoles() throws Exception {
        assertThat(directory.options(admin.tenantId())).anyMatch(option -> option.rule().equals("role:" + LocalOrganizationDirectory.PERSON_ROLE + first.id()));
        String key = publish("role:" + LocalOrganizationDirectory.PERSON_ROLE + first.id(), "SINGLE");
        var app = submit(key);
        var task = tasks.createTaskQuery().processVariableValueEquals("applicationId", app.path("id").asText()).singleResult();
        read("/tasks/" + task.getId(), identity(first.subject(), Set.of("EMPLOYEE")), 403);
        read("/tasks/" + task.getId(), firstToken, 200);
        assertThat(directory.members("foreign-" + admin.tenantId(), Set.of(first.subject()), Set.of())).isEmpty();
    }

    @Test
    void allCountersignResolvesEachLocalPersonOnceAndKeepsItsOriginalMembership() throws Exception {
        String key = publish("role:" + LocalOrganizationDirectory.UNIT_ROLE + position.id(), "ALL");
        var app = submit(key);
        var pending = tasks.createTaskQuery().processVariableValueEquals("applicationId", app.path("id").asText()).list();
        assertThat(pending).hasSize(2).extracting(org.flowable.task.api.Task::getAssignee).containsExactlyInAnyOrder(first.subject(), second.subject());
        organization.updateAppointment(admin, firstJob.id(), false, 1);
        var own = pending.stream().filter(value -> first.subject().equals(value.getAssignee())).findFirst().orElseThrow();
        write(post("/api/v1/tasks/" + own.getId() + "/actions"), firstToken, Map.of("action", "APPROVE", "expectedVersion", 2), 200);
        var last = tasks.createTaskQuery().processVariableValueEquals("applicationId", app.path("id").asText()).singleResult();
        write(post("/api/v1/tasks/" + last.getId() + "/actions"), secondToken, Map.of("action", "APPROVE", "expectedVersion", 3), 200);
        assertThat(read("/applications/" + app.path("id").asText(), applicantToken, 200).path("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void noMembersAtActivationRollsBackSubmissionInsteadOfSkippingApproval() throws Exception {
        String key = publish("role:" + LocalOrganizationDirectory.PERSON_ROLE + first.id(), "SINGLE");
        organization.updatePerson(admin, first.id(), first.displayName(), false, true, 1);
        var app = write(post("/api/v1/applications"), applicantToken, Map.of("businessNo", UUID.randomUUID().toString(), "title", "无人不可跳过", "processKey", key, "definitionVersion", 1, "payload", Map.of()), 201);
        write(post("/api/v1/applications/" + app.path("id").asText() + "/submit"), applicantToken, Map.of("expectedVersion", 1), 422);
        assertThat(read("/applications/" + app.path("id").asText(), applicantToken, 200)).isEqualTo(app);
        assertThat(tasks.createTaskQuery().processVariableValueEquals("applicationId", app.path("id").asText()).count()).isZero();
    }

    private String identity(String subject, Set<String> roles) {
        String token = UUID.randomUUID().toString();
        doReturn(new Actor(admin.tenantId(), subject, roles)).when(auth).authenticate(token); return token;
    }
    private String publish(String rule, String mode) {
        var graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()), new Node("review", "组织审核", NodeType.USER_TASK, Map.of("assigneeRule", rule, "approvalMode", mode)), new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("e1", "start", "review", ""), new Edge("e2", "review", "end", "")));
        var draft = definitions.create(admin.tenantId(), "org-" + UUID.randomUUID(), "组织流程", graph, null, null);
        return definitions.publish(admin, draft.id(), draft.revision(), "本地组织实际审批验证").key();
    }
    private JsonNode submit(String key) throws Exception {
        var app = write(post("/api/v1/applications"), applicantToken, Map.of("businessNo", UUID.randomUUID().toString(), "title", "组织流程申请", "processKey", key, "definitionVersion", 1, "payload", Map.of()), 201);
        return write(post("/api/v1/applications/" + app.path("id").asText() + "/submit"), applicantToken, Map.of("expectedVersion", 1), 200);
    }
    private JsonNode read(String path, String token, int expected) throws Exception {
        return json.read(mvc.perform(get("/api/v1" + path).header("Authorization", "Bearer " + token)).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString(), JsonNode.class);
    }
    private JsonNode write(MockHttpServletRequestBuilder request, String token, Object body, int expected) throws Exception {
        return json.read(mvc.perform(request.header("Authorization", "Bearer " + token).header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json.write(body)))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString(), JsonNode.class);
    }
}
