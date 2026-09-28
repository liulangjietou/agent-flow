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
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    private Actor admin;
    private OrganizationUnit department;
    private OrganizationUnit position;
    private OrganizationPerson first;
    private OrganizationPerson second;
    private OrganizationAppointment firstJob;
    private OrganizationAppointment secondJob;
    private String applicantToken;
    private String firstToken;
    private String secondToken;

    @Test
    void applicantSelectsOwnAppointmentAndEachRoundKeepsItsOriginalContext() throws Exception {
        var applicant = organization.createPerson(admin, "applicant", "申请员工", true, false);
        var selected = organization.createAppointment(admin, applicant.id(), department.id(), position.id(), true);
        var anotherDepartment = organization.createUnit(admin, OrganizationUnit.Kind.DEPARTMENT, "兼任部门",
                department.legalEntityId(), null, true);
        var another = organization.createAppointment(admin, applicant.id(), anotherDepartment.id(), position.id(), true);
        var options = read("/organization/my-appointments?limit=1", applicantToken, 200);
        assertThat(options.path("items")).hasSize(1);
        assertThat(options.path("items").get(0).path("subject").asText()).isEqualTo("applicant");
        assertThat(options.path("nextAfterId").asText()).isNotBlank();
        assertThat(read("/organization/my-appointments?limit=1&afterId=" + options.path("nextAfterId").asText(),
                applicantToken, 200).path("items")).hasSize(1);
        read("/organization/my-appointments?subject=" + first.subject(), applicantToken, 400);

        String key = publish("role:" + LocalOrganizationDirectory.PERSON_ROLE + first.id(), "SINGLE");
        var draft = write(post("/api/v1/applications"), applicantToken, Map.of("businessNo", UUID.randomUUID().toString(),
                "title", "任职上下文", "processKey", key, "definitionVersion", 1, "payload", Map.of()), 201);
        String id = draft.path("id").asText();
        var unavailable = write(post("/api/v1/applications/" + id + "/submit"), applicantToken,
                Map.of("expectedVersion", 1, "initiatorAppointmentId", firstJob.id()), 422);
        assertThat(unavailable.path("code").asText()).isEqualTo("INITIATOR_APPOINTMENT_UNAVAILABLE");
        assertThat(read("/applications/" + id, applicantToken, 200).path("version").asLong()).isEqualTo(1);
        write(post("/api/v1/applications/" + id + "/submit"), applicantToken,
                Map.of("expectedVersion", 1, "initiatorAppointmentId", selected.id()), 200);
        var firstContext = read("/applications/" + id + "/rounds", applicantToken, 200).get(0).path("initiatorContext");
        assertThat(firstContext.path("appointmentId").asText()).isEqualTo(selected.id().toString());
        assertThat(firstContext.path("departmentName").asText()).isEqualTo("审核部");
        var task = tasks.createTaskQuery().processVariableValueEquals("applicationId", id).singleResult();
        assertThat(json.read((String) runtime.getVariable(task.getExecutionId(),
                io.agentflow.approval.process.FlowableProcessRuntimeAdapter.INITIATOR_CONTEXT), JsonNode.class)).isEqualTo(firstContext);

        organization.updateUnit(admin, department.id(), "修改后的部门", null, true, department.revision());
        write(post("/api/v1/applications/" + id + "/withdraw"), applicantToken, Map.of("expectedVersion", 2), 200);
        write(post("/api/v1/applications/" + id + "/submit"), applicantToken,
                Map.of("expectedVersion", 3, "initiatorAppointmentId", another.id()), 200);
        var rounds = read("/applications/" + id + "/rounds", applicantToken, 200);
        assertThat(rounds.get(0).path("initiatorContext")).isEqualTo(firstContext);
        assertThat(rounds.get(1).path("initiatorContext").path("appointmentId").asText()).isEqualTo(another.id().toString());
        assertThat(rounds.get(1).path("initiatorContext").path("departmentName").asText()).isEqualTo("兼任部门");
    }

    @Test
    void stoppedOrOtherTenantAppointmentsAreUnavailableAndLegacyRoundsHaveNoInventedContext() throws Exception {
        var applicant = organization.createPerson(admin, "applicant", "申请员工", true, false);
        var selected = organization.createAppointment(admin, applicant.id(), department.id(), position.id(), true);
        organization.updateAppointment(admin, selected.id(), false, selected.revision());
        assertThat(read("/organization/my-appointments", applicantToken, 200).path("items")).isEmpty();
        String key = publish("role:" + LocalOrganizationDirectory.PERSON_ROLE + first.id(), "SINGLE");
        var draft = write(post("/api/v1/applications"), applicantToken, Map.of("businessNo", UUID.randomUUID().toString(),
                "title", "无任职旧流程", "processKey", key, "definitionVersion", 1, "payload", Map.of()), 201);
        String id = draft.path("id").asText();
        write(post("/api/v1/applications/" + id + "/submit"), applicantToken,
                Map.of("expectedVersion", 1, "initiatorAppointmentId", selected.id()), 422);
        String otherToken = UUID.randomUUID().toString();
        doReturn(new Actor("other-" + UUID.randomUUID(), "applicant", Set.of("EMPLOYEE"))).when(auth).authenticate(otherToken);
        assertThat(read("/organization/my-appointments", otherToken, 200).path("items")).isEmpty();
        write(post("/api/v1/applications/" + id + "/submit"), applicantToken, Map.of("expectedVersion", 1), 200);
        assertThat(read("/applications/" + id + "/rounds", applicantToken, 200).get(0).path("initiatorContext").isNull()).isTrue();
    }

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
        secondJob = organization.createAppointment(admin, second.id(), department.id(), position.id(), true);
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
        String diagramPath = "/applications/" + app.path("id").asText() + "/rounds/1/diagram";
        var originalCandidates = candidateSnapshots(read(diagramPath, applicantToken, 200));
        assertThat(originalCandidates).hasSize(1);
        assertThat(originalCandidates.get(0).path("candidateUserIds")).hasSize(2);
        organization.updateAppointment(admin, firstJob.id(), false, 1);
        var own = pending.stream().filter(value -> first.subject().equals(value.getAssignee())).findFirst().orElseThrow();
        write(post("/api/v1/tasks/" + own.getId() + "/actions"), firstToken, Map.of("action", "APPROVE", "expectedVersion", 2), 200);
        var last = tasks.createTaskQuery().processVariableValueEquals("applicationId", app.path("id").asText()).singleResult();
        write(post("/api/v1/tasks/" + last.getId() + "/actions"), secondToken, Map.of("action", "APPROVE", "expectedVersion", 3), 200);
        assertThat(read("/applications/" + app.path("id").asText(), applicantToken, 200).path("status").asText()).isEqualTo("APPROVED");
        assertThat(candidateSnapshots(read(diagramPath, applicantToken, 200))).isEqualTo(originalCandidates);
    }

    @Test
    void applicantReadsFrozenCandidateEvidenceAfterReturnAndResubmission() throws Exception {
        String key = publish("role:" + LocalOrganizationDirectory.UNIT_ROLE + department.id(), "SINGLE");
        var app = submit(key);
        String id = app.path("id").asText();
        String firstDiagram = "/applications/" + id + "/rounds/1/diagram";
        var original = candidateSnapshots(read(firstDiagram, applicantToken, 200));
        assertThat(original).hasSize(1);
        assertThat(original.get(0).path("candidateUserIds")).containsExactlyInAnyOrder(json.read(json.write(first.subject()), JsonNode.class), json.read(json.write(second.subject()), JsonNode.class));
        assertThat(original.get(0).path("directoryRevision").asLong()).isPositive();
        assertThat(original.toString()).doesNotContain("role:", "rule", "tenantId");
        read(firstDiagram, identity("outsider", Set.of("EMPLOYEE")), 404);
        String foreignToken = UUID.randomUUID().toString();
        doReturn(new Actor("foreign", "applicant", Set.of("EMPLOYEE"))).when(auth).authenticate(foreignToken);
        read(firstDiagram, foreignToken, 404);

        var task = tasks.createTaskQuery().processVariableValueEquals("applicationId", id).singleResult();
        write(post("/api/v1/tasks/" + task.getId() + "/actions"), secondToken,
                Map.of("action", "RETURN", "comment", "补充后重提", "expectedVersion", app.path("version").asLong()), 200);
        organization.updateAppointment(admin, firstJob.id(), false, 1);
        var third = organization.createPerson(admin, "new-candidate", "新任职人员", true, true);
        organization.createAppointment(admin, third.id(), department.id(), position.id(), true);
        var returned = read("/applications/" + id, applicantToken, 200);
        write(post("/api/v1/applications/" + id + "/submit"), applicantToken,
                Map.of("expectedVersion", returned.path("version").asLong()), 200);
        assertThat(candidateSnapshots(read(firstDiagram, applicantToken, 200))).isEqualTo(original);
        var current = candidateSnapshots(read("/applications/" + id + "/rounds/2/diagram", applicantToken, 200));
        assertThat(current).hasSize(1);
        assertThat(current.get(0).path("candidateUserIds").toString()).contains(second.subject(), third.subject()).doesNotContain(first.subject());
        task = tasks.createTaskQuery().processVariableValueEquals("applicationId", id).singleResult();
        long version = read("/applications/" + id, applicantToken, 200).path("version").asLong();
        write(post("/api/v1/tasks/" + task.getId() + "/actions"), secondToken, Map.of("action", "APPROVE", "expectedVersion", version), 200);
        assertThat(candidateSnapshots(read("/applications/" + id + "/rounds/2/diagram", applicantToken, 200))).isEqualTo(current);
    }

    private JsonNode candidateSnapshots(JsonNode diagram) {
        for (JsonNode node : diagram.path("nodes")) if ("review".equals(node.path("id").asText())) return node.path("candidateSnapshots");
        throw new AssertionError("Missing review node");
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

    @Test
    void laterDynamicTenantDefinitionCannotImposeContextOnBoundOrHistoricalBundledApplications() throws Exception {
        var bound = write(post("/api/v1/applications"), applicantToken, Map.of("businessNo", UUID.randomUUID().toString(),
                "title", "已绑定内置定义", "processKey", "expense-reimbursement", "definitionVersion", 1, "payload", Map.of("amount", 6000)), 201);
        var legacy = write(post("/api/v1/applications"), applicantToken, Map.of("businessNo", UUID.randomUUID().toString(),
                "title", "历史内置定义", "processKey", "expense-reimbursement", "definitionVersion", 1, "payload", Map.of("amount", 6000)), 201);
        String boundId = bound.path("id").asText(), legacyId = legacy.path("id").asText();
        // 模拟尚未保存引擎定义 ID 的旧申请，重提必须从原轮次恢复来源。
        jdbc.update("UPDATE approval_application SET runtime_definition_id=NULL WHERE id=?", legacyId);
        write(post("/api/v1/applications/" + legacyId + "/submit"), applicantToken, Map.of("expectedVersion", 1), 200);
        String originalDefinition = tasks.createTaskQuery().processVariableValueEquals("applicationId", legacyId).singleResult().getProcessDefinitionId();
        write(post("/api/v1/applications/" + legacyId + "/withdraw"), applicantToken, Map.of("expectedVersion", 2), 200);
        publish("expense-reimbursement", LocalOrganizationDirectory.SUPERVISOR_RULE + "1", "SINGLE");

        write(post("/api/v1/applications/" + boundId + "/submit"), applicantToken, Map.of("expectedVersion", 1), 200);
        write(post("/api/v1/applications/" + legacyId + "/submit"), applicantToken, Map.of("expectedVersion", 3), 200);
        for (String id : List.of(boundId, legacyId)) {
            var task = tasks.createTaskQuery().processVariableValueEquals("applicationId", id).singleResult();
            assertThat(task.getProcessDefinitionId()).isEqualTo(originalDefinition);
            assertThat(task.getTaskDefinitionKey()).isEqualTo("finance-approval");
        }
        assertThat(read("/applications/" + legacyId + "/rounds", applicantToken, 200)).hasSize(2);
        var current = write(post("/api/v1/applications"), applicantToken, Map.of("businessNo", UUID.randomUUID().toString(),
                "title", "新建租户动态流程", "processKey", "expense-reimbursement", "definitionVersion", 1, "payload", Map.of()), 201);
        String currentId = current.path("id").asText();
        assertThat(write(post("/api/v1/applications/" + currentId + "/submit"), applicantToken, Map.of("expectedVersion", 1), 422)
                .path("code").asText()).isEqualTo("INITIATOR_APPOINTMENT_REQUIRED");
        assertThat(read("/applications/" + currentId, applicantToken, 200).path("status").asText()).isEqualTo("DRAFT");
        assertThat(read("/applications/" + currentId + "/rounds", applicantToken, 200)).isEmpty();
    }

    @Test
    void dynamicSupervisorsUseSelectedAppointmentFreezeCandidatesAndRequireContext() throws Exception {
        var applicant = organization.createPerson(admin, "applicant", "多任职申请人", true, false);
        var job = organization.createAppointment(admin, applicant.id(), department.id(), position.id(), true);
        organization.setSupervisor(admin, job.id(), firstJob.id(), 1);
        organization.setSupervisor(admin, firstJob.id(), secondJob.id(), 1);
        String key = publish(LocalOrganizationDirectory.SUPERVISOR_RULE + "2", "ALL");
        var draft = write(post("/api/v1/applications"), applicantToken, Map.of("businessNo", UUID.randomUUID().toString(),
                "title", "二级主管", "processKey", key, "definitionVersion", 1, "payload", Map.of()), 201);
        String id = draft.path("id").asText();
        assertThat(write(post("/api/v1/applications/" + id + "/submit"), applicantToken, Map.of("expectedVersion", 1), 422)
                .path("code").asText()).isEqualTo("INITIATOR_APPOINTMENT_REQUIRED");
        write(post("/api/v1/applications/" + id + "/submit"), applicantToken,
                Map.of("expectedVersion", 1, "initiatorAppointmentId", job.id()), 200);
        var task = tasks.createTaskQuery().processVariableValueEquals("applicationId", id).singleResult();
        assertThat(task.getAssignee()).isEqualTo(second.subject());
        organization.setSupervisor(admin, firstJob.id(), null, 2);
        assertThat(tasks.createTaskQuery().taskId(task.getId()).singleResult().getAssignee()).isEqualTo(second.subject());
        write(post("/api/v1/tasks/" + task.getId() + "/actions"), secondToken, Map.of("action", "APPROVE", "expectedVersion", 2), 200);
        assertThat(read("/applications/" + id, applicantToken, 200).path("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void departmentHeadMustExistAndRelationshipWritesRespectOwnershipRevisionAndCycles() throws Exception {
        var applicant = organization.createPerson(admin, "applicant", "申请人", true, false);
        var job = organization.createAppointment(admin, applicant.id(), department.id(), position.id(), true);
        var token = identity(admin.userId(), Set.of("ADMIN", "PROCESS_ADMIN"));
        write(put("/api/v1/organization/appointments/" + job.id() + "/supervisor"), applicantToken,
                Map.of("appointmentId", firstJob.id(), "expectedRevision", 1), 403);
        write(put("/api/v1/organization/appointments/" + job.id() + "/supervisor"), token,
                Map.of("appointmentId", firstJob.id(), "expectedRevision", 1), 200);
        write(put("/api/v1/organization/appointments/" + job.id() + "/supervisor"), token,
                Map.of("appointmentId", secondJob.id(), "expectedRevision", 1), 409);
        assertThatThrownBy(() -> organization.setSupervisor(admin, firstJob.id(), job.id(), 1))
                .isInstanceOf(io.agentflow.common.DomainException.class);
        organization.setSupervisor(admin, firstJob.id(), secondJob.id(), 1);
        assertThatThrownBy(() -> organization.setSupervisor(admin, secondJob.id(), firstJob.id(), 1))
                .isInstanceOfSatisfying(io.agentflow.common.DomainException.class,
                        error -> assertThat(error.code()).isEqualTo("ORGANIZATION_SUPERVISOR_CYCLE"));
        var foreign = new Actor("foreign-" + UUID.randomUUID(), "other", Set.of("ADMIN"));
        organization.initialize(foreign);
        assertThatThrownBy(() -> organization.setSupervisor(foreign, job.id(), firstJob.id(), 1))
                .isInstanceOf(io.agentflow.common.DomainException.class);
        var anotherDepartment = organization.createUnit(admin, OrganizationUnit.Kind.DEPARTMENT, "其他部门", department.legalEntityId(), null, true);
        assertThatThrownBy(() -> organization.setDepartmentHead(admin, anotherDepartment.id(), firstJob.id(), 1))
                .isInstanceOf(io.agentflow.common.DomainException.class);
        String key = publish(LocalOrganizationDirectory.DEPARTMENT_HEAD_RULE, "SINGLE");
        var draft = write(post("/api/v1/applications"), applicantToken, Map.of("businessNo", UUID.randomUUID().toString(),
                "title", "部门负责人", "processKey", key, "definitionVersion", 1, "payload", Map.of()), 201);
        String id = draft.path("id").asText();
        write(post("/api/v1/applications/" + id + "/submit"), applicantToken,
                Map.of("expectedVersion", 1, "initiatorAppointmentId", job.id()), 422);
        assertThat(read("/applications/" + id, applicantToken, 200).path("version").asLong()).isEqualTo(1);
        write(put("/api/v1/organization/units/" + department.id() + "/head"), token,
                Map.of("appointmentId", firstJob.id(), "expectedRevision", 1), 200);
        write(post("/api/v1/applications/" + id + "/submit"), applicantToken,
                Map.of("expectedVersion", 1, "initiatorAppointmentId", job.id()), 200);
        var task = tasks.createTaskQuery().processVariableValueEquals("applicationId", id).singleResult();
        assertThat(tasks.getIdentityLinksForTask(task.getId())).extracting(org.flowable.identitylink.api.IdentityLink::getUserId).contains(first.subject());
        organization.updateAppointment(admin, firstJob.id(), false, 2);
        // 已经冻结的责任不随任职停用移走；下一次激活会重新检查任职有效性。
        read("/tasks/" + task.getId(), firstToken, 200);
        write(post("/api/v1/applications/" + id + "/withdraw"), applicantToken, Map.of("expectedVersion", 2), 200);
        write(post("/api/v1/applications/" + id + "/submit"), applicantToken,
                Map.of("expectedVersion", 3, "initiatorAppointmentId", job.id()), 422);
        assertThat(read("/applications/" + id + "/rounds", applicantToken, 200)).hasSize(1);
    }

    private String identity(String subject, Set<String> roles) {
        String token = UUID.randomUUID().toString();
        doReturn(new Actor(admin.tenantId(), subject, roles)).when(auth).authenticate(token); return token;
    }
    private String publish(String rule, String mode) {
        return publish("org-" + UUID.randomUUID(), rule, mode);
    }
    private String publish(String key, String rule, String mode) {
        var graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()), new Node("review", "组织审核", NodeType.USER_TASK, Map.of("assigneeRule", rule, "approvalMode", mode)), new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("e1", "start", "review", ""), new Edge("e2", "review", "end", "")));
        var draft = definitions.create(admin.tenantId(), key, "组织流程", graph, null, null);
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
