package io.agentflow.organization;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.approval.process.FlowableProcessRuntimeAdapter;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import io.agentflow.definition.SubprocessPolicy;
import io.agentflow.form.FormSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 真实发布和提交验证表单来源、整轮名单、组织漂移、会签及原生子流程隔离。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=false", "agentflow.sla.reminders-enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class FormAssigneeIntegrationTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_FORM_ASSIGNEE_URL", "jdbc:h2:mem:form-assignees;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_FORM_ASSIGNEE_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_FORM_ASSIGNEE_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_FORM_ASSIGNEE_PASSWORD", ""));
    }

    @Autowired MockMvc mvc;
    @MockitoSpyBean AuthService auth;
    @Autowired JsonUtil json;
    @Autowired OrganizationService organization;
    @Autowired DefinitionApplicationService definitions;
    @Autowired RuntimeService runtime;
    @Autowired TaskService tasks;
    @Autowired HistoryService history;
    private Actor admin;
    private OrganizationUnit department;
    private OrganizationUnit position;
    private OrganizationPerson first;
    private OrganizationPerson second;
    private OrganizationPerson gate;
    private OrganizationAppointment firstJob;
    private OrganizationAppointment secondJob;
    private String applicantToken;
    private String firstToken;
    private String secondToken;
    private String gateToken;

    @BeforeEach
    void setup() {
        admin = new Actor("field-" + UUID.randomUUID(), "admin", Set.of("ADMIN"));
        organization.initialize(admin);
        var legal = organization.createUnit(admin, OrganizationUnit.Kind.LEGAL_ENTITY, "法人", null, null, true);
        department = organization.createUnit(admin, OrganizationUnit.Kind.DEPARTMENT, "选定部门", legal.id(), null, true);
        position = organization.createUnit(admin, OrganizationUnit.Kind.POSITION, "选定岗位", legal.id(), null, true);
        first = organization.createPerson(admin, "issuer:first/审批人", "原负责人", true, true);
        second = organization.createPerson(admin, "issuer:second/审批人", "新负责人", true, true);
        gate = organization.createPerson(admin, "gate", "前置审批人", true, true);
        firstJob = organization.createAppointment(admin, first.id(), department.id(), position.id(), true);
        secondJob = organization.createAppointment(admin, second.id(), department.id(), position.id(), true);
        department = organization.setDepartmentHead(admin, department.id(), firstJob.id(), department.revision());
        applicantToken = identity("applicant", Set.of("EMPLOYEE"));
        firstToken = identity(first.subject(), Set.of("APPROVER"));
        secondToken = identity(second.subject(), Set.of("APPROVER"));
        gateToken = identity(gate.subject(), Set.of("APPROVER"));
    }

    @Test
    void headIsFrozenAtSubmissionAndAResubmissionGetsANewSnapshot() throws Exception {
        var definition = publish(graph("DEPARTMENT_HEAD", "SINGLE", true, Map.of()), schema(department.id()));
        String id = submit(definition, department.id());
        Task before = pending(id).get(0);
        String firstInstance = before.getProcessInstanceId();
        String frozen = (String) runtime.getVariable(firstInstance, FlowableProcessRuntimeAdapter.FORM_ASSIGNEES);
        assertThat(frozen).contains(first.subject()).doesNotContain(second.subject());
        department = organization.setDepartmentHead(admin, department.id(), secondJob.id(), department.revision());
        action(id, before, gateToken, "APPROVE", 200);
        Task review = pending(id).get(0);
        assertThat(candidates(review)).containsExactly(first.subject());
        read("/tasks/" + review.getId(), secondToken, 403);
        action(id, review, firstToken, "RETURN", 200);
        assertThat(read("/applications/" + id, applicantToken, 200).path("status").asText()).isEqualTo("RETURNED");
        postBody("/applications/" + id + "/submit", applicantToken, Map.of("expectedVersion", version(id)), 200);
        assertThat((String) history.createHistoricVariableInstanceQuery().processInstanceId(firstInstance)
                .variableName(FlowableProcessRuntimeAdapter.FORM_ASSIGNEES).singleResult().getValue()).isEqualTo(frozen);
        Task nextGate = pending(id).get(0);
        assertThat((String) runtime.getVariable(nextGate.getProcessInstanceId(), FlowableProcessRuntimeAdapter.FORM_ASSIGNEES))
                .contains(second.subject()).doesNotContain(first.subject());
        action(id, nextGate, gateToken, "APPROVE", 200);
        assertThat(candidates(pending(id).get(0))).containsExactly(second.subject());
        action(id, pending(id).get(0), secondToken, "APPROVE", 200);
        assertThat(read("/applications/" + id, applicantToken, 200).path("status").asText()).isEqualTo("APPROVED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ALL", "ANY", "PERCENT"})
    void countersignKeepsSubmissionMembersWhenAppointmentsChange(String mode) throws Exception {
        var definition = publish(graph("POSITION_MEMBERS", mode, true, mode.equals("PERCENT") ? Map.of("approvalPercentage", "50") : Map.of()), schema(position.id()));
        String id = submit(definition, position.id());
        organization.updateAppointment(admin, firstJob.id(), false, firstJob.revision());
        var newcomer = organization.createPerson(admin, "new", "新成员", true, true);
        organization.createAppointment(admin, newcomer.id(), department.id(), position.id(), true);
        action(id, pending(id).get(0), gateToken, "APPROVE", 200);
        var reviews = pending(id);
        assertThat(reviews).hasSize(2).extracting(Task::getAssignee).containsExactlyInAnyOrder(first.subject(), second.subject());
        read("/tasks/" + reviews.get(0).getId(), identity(newcomer.subject(), Set.of("APPROVER")), 403);
        action(id, reviews.stream().filter(task -> first.subject().equals(task.getAssignee())).findFirst().orElseThrow(), firstToken, "APPROVE", 200);
        if (mode.equals("ALL")) {
            assertThat(pending(id)).hasSize(1);
            action(id, pending(id).get(0), secondToken, "APPROVE", 200);
        }
        assertThat(pending(id)).isEmpty();
        assertThat(read("/applications/" + id, applicantToken, 200).path("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void lostPersonEligibilityBlocksTheWholeActivationAndKeepsPreviousDecisionUncommitted() throws Exception {
        var definition = publish(graph("DEPARTMENT_MEMBERS", "ALL", true, Map.of()), schema(department.id()));
        String id = submit(definition, department.id()); Task before = pending(id).get(0);
        organization.updatePerson(admin, first.id(), first.displayName(), true, false, first.revision());
        var failure = action(id, before, gateToken, "APPROVE", 422);
        assertThat(failure.path("code").asText()).isEqualTo("FORM_ASSIGNEE_UNAVAILABLE");
        assertThat(version(id)).isEqualTo(2);
        assertThat(pending(id)).extracting(Task::getId).containsExactly(before.getId());
        assertThat(history.createHistoricTaskInstanceQuery().taskId(before.getId()).singleResult().getEndTime()).isNull();
        organization.updatePerson(admin, first.id(), first.displayName(), true, true, first.revision() + 1);
        action(id, before, gateToken, "APPROVE", 200);
        assertThat(pending(id)).hasSize(2);
    }

    @Test
    void missingFrozenSelectionCannotFallBackToCurrentDirectory() throws Exception {
        String id = submit(publish(graph("PERSON", "SINGLE", true, Map.of()), schema(first.id())), first.id());
        Task before = pending(id).get(0);
        runtime.removeVariable(before.getProcessInstanceId(), FlowableProcessRuntimeAdapter.FORM_ASSIGNEES);
        assertThat(action(id, before, gateToken, "APPROVE", 422).path("code").asText()).isEqualTo("FORM_ASSIGNEE_SNAPSHOT_MISSING");
        assertThat(pending(id)).extracting(Task::getId).containsExactly(before.getId());
    }

    @Test
    void unavailableSelectionRollsBackSubmissionBeforeAnyTaskOrRoundExists() throws Exception {
        var definition = publish(graph("DEPARTMENT_HEAD", "SINGLE", true, Map.of()), schema(department.id()));
        String id = draft(definition, department.id());
        organization.setDepartmentHead(admin, department.id(), null, department.revision());
        assertThat(postBody("/applications/" + id + "/submit", applicantToken, Map.of("expectedVersion", 1), 422)
                .path("code").asText()).isEqualTo("ORGANIZATION_NO_APPROVERS");
        assertThat(version(id)).isEqualTo(1);
        assertThat(pending(id)).isEmpty();
        assertThat(read("/applications/" + id + "/rounds", applicantToken, 200)).isEmpty();
    }

    @Test
    void publicationChecksEveryOptionKindAndTenantWhileDraftsRemainRepairable() {
        var graph = graph("PERSON", "SINGLE", false, Map.of());
        for (UUID invalid : List.of(department.id(), UUID.randomUUID())) {
            var form = schema(first.id(), invalid);
            var draft = definitions.create(admin.tenantId(), "field-" + UUID.randomUUID(), "待修复来源", graph, form);
            assertThat(definitions.inspect(admin.tenantId(), graph, form).errors()).contains("FORM_ASSIGNEE_OPTION_UNAVAILABLE:review");
            assertThatThrownBy(() -> definitions.publish(admin, draft.id(), draft.revision(), "不能发布无效选项"))
                    .isInstanceOf(DomainException.class);
        }
        String foreign = "foreign-" + UUID.randomUUID();
        assertThat(definitions.inspect(foreign, graph, schema(first.id())).errors()).contains("FORM_ASSIGNEE_OPTION_UNAVAILABLE:review");
    }

    @Test
    void responsibilityExclusionsApplyAfterReadingFrozenMembers() throws Exception {
        organization.createAppointment(admin, gate.id(), department.id(), position.id(), true);
        var applicant = organization.createPerson(admin, "applicant", "申请人", true, true);
        organization.createAppointment(admin, applicant.id(), department.id(), position.id(), true);
        var graph = graph("DEPARTMENT_MEMBERS", "ALL", true, Map.of("excludeApplicant", "true", "differentApproverFrom", "before"));
        String id = submit(publish(graph, schema(department.id())), department.id());
        action(id, pending(id).get(0), gateToken, "APPROVE", 200);
        assertThat(pending(id)).hasSize(2).extracting(Task::getAssignee).containsExactlyInAnyOrder(first.subject(), second.subject());
    }

    @Test
    void childFreezesItsMappedInputInAnIsolatedSnapshot() throws Exception {
        var child = publish(graph("PERSON", "SINGLE", false, Map.of()), schema(first.id()));
        var childInput = new FormSchema(1, List.of(select("childPerson", first.id()), select("responsible", second.id())));
        var policy = new SubprocessPolicy(child.key(), child.version(), Map.of("responsible", "childPerson"));
        var parent = publish(new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("review", "父审批", NodeType.USER_TASK, Map.of("assigneeRule", "field:responsible:PERSON")),
                new Node("call", "子审批", NodeType.SUB_PROCESS, policy.properties()), new Node("end", "结束", NodeType.END, Map.of())),
                List.of(edge("a", "start", "review"), edge("b", "review", "call"), edge("c", "call", "end"))), childInput);
        String id = postBody("/applications", applicantToken, Map.of("businessNo", UUID.randomUUID().toString(), "title", "父子表单选人",
                "processKey", parent.key(), "definitionVersion", parent.version(), "payload", Map.of("childPerson", first.id().toString(), "responsible", second.id().toString())), 201).path("id").asText();
        postBody("/applications/" + id + "/submit", applicantToken, Map.of("expectedVersion", 1), 200);
        Task parentTask = pending(id).get(0); String parentInstance = parentTask.getProcessInstanceId();
        assertThat(candidates(parentTask)).containsExactly(second.subject());
        action(id, parentTask, secondToken, "APPROVE", 200);
        var childInstance = runtime.createProcessInstanceQuery().superProcessInstanceId(parentInstance).singleResult();
        Task childTask = tasks.createTaskQuery().processInstanceId(childInstance.getId()).singleResult();
        assertThat(candidates(childTask)).containsExactly(first.subject());
        assertThat((String) runtime.getVariable(childInstance.getId(), FlowableProcessRuntimeAdapter.FORM_ASSIGNEES))
                .contains(first.subject()).doesNotContain(second.subject());
        assertThat((String) runtime.getVariable(parentInstance, FlowableProcessRuntimeAdapter.FORM_ASSIGNEES))
                .contains(second.subject()).doesNotContain(first.subject());
    }

    @Test
    void formDirectoryIsRestrictedToDesignersAndContainsOnlyOrganizationReferences() throws Exception {
        String path = "/process-definitions/form-assignee-options";
        var response = mvc.perform(get("/api/v1" + path).header("Authorization", "Bearer " + identity("designer", Set.of("PROCESS_ADMIN"))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString();
        assertThat(response).contains(first.id().toString(), department.id().toString(), position.id().toString())
                .doesNotContain(first.subject(), second.subject(), "subject", "appointmentId");
        var options = json.read(response, JsonNode.class);
        assertThat(options.findValuesAsText("kind")).contains("PERSON", "DEPARTMENT", "POSITION");
        assertThat(options.findValues("headAvailable")).anyMatch(JsonNode::asBoolean);
        read(path, applicantToken, 403); read(path, firstToken, 403);
        String foreign = UUID.randomUUID().toString();
        doReturn(new Actor("other-" + UUID.randomUUID(), "designer", Set.of("PROCESS_ADMIN"))).when(auth).authenticate(foreign);
        assertThat(read(path, foreign, 200)).isEmpty();
    }

    private DefinitionDraft publish(Graph graph, FormSchema schema) {
        var draft = definitions.create(admin.tenantId(), "field-" + UUID.randomUUID(), "表单选人", graph, schema);
        return definitions.publish(admin, draft.id(), draft.revision(), "验证本轮选人");
    }
    private Graph graph(String relation, String mode, boolean preceding, Map<String, String> extra) {
        var properties = new java.util.HashMap<>(extra);
        properties.put("assigneeRule", "field:responsible:" + relation); properties.put("approvalMode", mode);
        var nodes = new ArrayList<>(List.of(new Node("start", "开始", NodeType.START, Map.of()), new Node("review", "表单审批", NodeType.USER_TASK, properties), new Node("end", "结束", NodeType.END, Map.of())));
        var edges = new ArrayList<>(List.of(edge("a", "start", preceding ? "before" : "review"), edge("c", "review", "end")));
        if (preceding) {
            nodes.add(new Node("before", "前置审批", NodeType.USER_TASK, Map.of("assigneeRule", "role:" + LocalOrganizationDirectory.PERSON_ROLE + gate.id())));
            edges.add(edge("b", "before", "review"));
        }
        return new Graph(nodes, edges);
    }
    private static Edge edge(String id, String source, String target) { return new Edge(id, source, target, ""); }
    private static FormSchema schema(UUID... ids) { return new FormSchema(1, List.of(select("responsible", ids))); }
    private static FormSchema.Field select(String key, UUID... ids) {
        return new FormSchema.Field(key, "所选组织", FormSchema.FieldType.SELECT, true, null, null, null, null,
                java.util.Arrays.stream(ids).map(id -> new FormSchema.Option(id.toString(), "组织选项")).toList());
    }
    private String draft(DefinitionDraft definition, UUID selected) throws Exception {
        return postBody("/applications", applicantToken, Map.of("businessNo", UUID.randomUUID().toString(), "title", "表单选人申请", "processKey", definition.key(), "definitionVersion", definition.version(), "payload", Map.of("responsible", selected.toString())), 201).path("id").asText();
    }
    private String submit(DefinitionDraft definition, UUID selected) throws Exception {
        String id = draft(definition, selected);
        postBody("/applications/" + id + "/submit", applicantToken, Map.of("expectedVersion", 1), 200); return id;
    }
    private List<Task> pending(String id) { return tasks.createTaskQuery().processVariableValueEquals("applicationId", id).list(); }
    private List<String> candidates(Task task) { return tasks.getIdentityLinksForTask(task.getId()).stream().filter(link -> "candidate".equals(link.getType())).map(org.flowable.identitylink.api.IdentityLink::getUserId).sorted().toList(); }
    private long version(String id) throws Exception { return read("/applications/" + id, applicantToken, 200).path("version").asLong(); }
    private JsonNode action(String id, Task task, String token, String action, int status) throws Exception {
        return postBody("/tasks/" + task.getId() + "/actions", token, Map.of("action", action, "expectedVersion", version(id), "comment", "验证表单选人"), status);
    }
    private String identity(String subject, Set<String> roles) {
        String token = UUID.randomUUID().toString(); doReturn(new Actor(admin.tenantId(), subject, roles)).when(auth).authenticate(token); return token;
    }
    private JsonNode read(String path, String token, int expected) throws Exception {
        return json.read(mvc.perform(get("/api/v1" + path).header("Authorization", "Bearer " + token)).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString(), JsonNode.class);
    }
    private JsonNode postBody(String path, String token, Object body, int expected) throws Exception {
        return json.read(mvc.perform(post("/api/v1" + path).header("Authorization", "Bearer " + token).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(json.write(body))).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString(), JsonNode.class);
    }
}
