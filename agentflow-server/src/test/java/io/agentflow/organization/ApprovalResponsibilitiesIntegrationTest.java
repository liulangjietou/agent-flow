package io.agentflow.organization;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import org.flowable.engine.TaskService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.HistoryService;
import org.flowable.task.api.Task;
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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实发布、选人和办理链路中的职责约束；兼任申请人不能借转交、委派或加签绕过。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=false", "agentflow.sla.reminders-enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ApprovalResponsibilitiesIntegrationTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("agentflow.organization-test.jdbc-url", System.getenv().getOrDefault("AGENTFLOW_ORGANIZATION_TEST_URL", "jdbc:h2:mem:approval-responsibilities;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")));
        registry.add("spring.datasource.driver-class-name", () -> System.getProperty("agentflow.organization-test.jdbc-driver", System.getenv().getOrDefault("AGENTFLOW_ORGANIZATION_TEST_DRIVER", "org.h2.Driver")));
        registry.add("spring.datasource.username", () -> System.getProperty("agentflow.organization-test.jdbc-user", System.getenv().getOrDefault("AGENTFLOW_ORGANIZATION_TEST_USER", "sa")));
        registry.add("spring.datasource.password", () -> System.getProperty("agentflow.organization-test.jdbc-password", System.getenv().getOrDefault("AGENTFLOW_ORGANIZATION_TEST_PASSWORD", "")));
    }

    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @Autowired OrganizationService organization;
    @Autowired DefinitionApplicationService definitions;
    @Autowired TaskService tasks;
    @Autowired RuntimeService runtime;
    @Autowired HistoryService history;
    @MockitoSpyBean AuthService auth;
    private Actor admin;
    private String rule;
    private OrganizationPerson applicant;
    private String applicantToken;
    private String firstToken;
    private String secondToken;

    @BeforeEach
    void setup() {
        admin = new Actor("responsibilities-" + UUID.randomUUID(), "administrator", Set.of("ADMIN"));
        organization.initialize(admin);
        var legal = organization.createUnit(admin, OrganizationUnit.Kind.LEGAL_ENTITY, "法人", null, null, true);
        var department = organization.createUnit(admin, OrganizationUnit.Kind.DEPARTMENT, "审批部", legal.id(), null, true);
        var position = organization.createUnit(admin, OrganizationUnit.Kind.POSITION, "审批岗", legal.id(), null, true);
        for (String subject : List.of("applicant", "first", "second")) {
            var person = organization.createPerson(admin, subject, subject, true, true);
            organization.createAppointment(admin, person.id(), department.id(), position.id(), true);
            if (subject.equals("applicant")) applicant = person;
        }
        rule = "role:" + LocalOrganizationDirectory.UNIT_ROLE + department.id();
        applicantToken = identity("applicant"); firstToken = identity("first"); secondToken = identity("second");
    }

    @Test
    void selfApprovalIsExcludedFromCandidatesQueuesAndResponsibilityChanges() throws Exception {
        var app = submit(publish(List.of(node("review", "SINGLE", Map.of("excludeApplicant", "true")))), 200);
        Task task = task(app, "review");
        assertThat(candidates(task)).containsExactlyInAnyOrder("first", "second");
        assertThat(read("/workspace/tasks", applicantToken, 200).path("total").asLong()).isZero();
        read("/tasks/" + task.getId(), applicantToken, 403);
        action(task, applicantToken, "APPROVE", null, 2, 403);
        assertThat(read("/tasks/" + task.getId() + "/recipients", firstToken, 200).toString()).doesNotContain("applicant");
        for (String action : List.of("TRANSFER", "DELEGATE")) {
            assertThat(action(task, firstToken, action, "applicant", 2, 422).path("code").asText()).isEqualTo("APPROVAL_RESPONSIBILITY_CONFLICT");
        }
        action(task, firstToken, "APPROVE", null, 2, 200);
        assertThat(read("/applications/" + app.path("id").asText(), applicantToken, 200).path("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void downstreamExcludesActualDecisionMakerRatherThanOriginalAssignee() throws Exception {
        var app = submit(publish(List.of(node("firstReview", "SINGLE", Map.of("excludeApplicant", "true")),
                node("review", "SINGLE", Map.of("excludeApplicant", "true", "differentApproverFrom", "firstReview")))), 200);
        Task first = task(app, "firstReview");
        action(first, firstToken, "TRANSFER", "second", 2, 200);
        action(first, secondToken, "APPROVE", null, 3, 200);
        Task next = task(app, "review");
        assertThat(candidates(next)).containsExactly("first");
        read("/tasks/" + next.getId(), secondToken, 403);
        assertThat(action(next, firstToken, "TRANSFER", "second", 4, 422).path("code").asText()).isEqualTo("APPROVAL_RESPONSIBILITY_CONFLICT");
        action(next, firstToken, "APPROVE", null, 4, 200);
    }

    @Test
    void cancelledAnyMembersAreNotMistakenForActualApprovers() throws Exception {
        var app = submit(publish(List.of(node("firstReview", "ANY", Map.of("excludeApplicant", "true")),
                node("review", "SINGLE", Map.of("excludeApplicant", "true", "differentApproverFrom", "firstReview")))), 200);
        Task first = tasks.createTaskQuery().processVariableValueEquals("applicationId", app.path("id").asText())
                .taskDefinitionKey("firstReview").taskAssignee("first").singleResult();
        action(first, firstToken, "APPROVE", null, 2, 200);
        Task next = task(app, "review");
        assertThat(candidates(next)).containsExactly("second");
        action(next, secondToken, "APPROVE", null, 3, 200);
    }

    @Test
    void countersignFiltersBeforeCountingAndDoesNotAllowAddingExcludedPerson() throws Exception {
        var app = submit(publish(List.of(node("review", "ALL", Map.of("excludeApplicant", "true")))), 200);
        var active = tasks.createTaskQuery().processVariableValueEquals("applicationId", app.path("id").asText()).list();
        assertThat(active).extracting(Task::getAssignee).containsExactlyInAnyOrder("first", "second");
        Task first = active.stream().filter(task -> "first".equals(task.getAssignee())).findFirst().orElseThrow();
        assertThat(write("/tasks/" + first.getId() + "/countersign-changes", firstToken,
                Map.of("action", "ADD", "targetUser", "applicant", "reason", "不能加签申请人", "expectedVersion", 2), 422)
                .path("code").asText()).isEqualTo("APPROVAL_RESPONSIBILITY_CONFLICT");
        action(first, firstToken, "APPROVE", null, 2, 200);
        action(task(app, "review"), secondToken, "APPROVE", null, 3, 200);
    }

    @Test
    void emptyFilteredRosterRollsBackAndLegacyVersionKeepsItsSemantics() throws Exception {
        String self = "role:" + LocalOrganizationDirectory.PERSON_ROLE + applicant.id();
        String guarded = publish(List.of(new Node("review", "审批", NodeType.USER_TASK,
                Map.of("assigneeRule", self, "excludeApplicant", "true"))));
        assertThat(submit(guarded, 422).path("code").asText()).isEqualTo("APPROVAL_RESPONSIBILITY_NO_MEMBERS");
        assertThat(tasks.createTaskQuery().processVariableValueEquals("tenantId", admin.tenantId()).count()).isZero();
        var legacy = submit(publish(List.of(new Node("review", "旧规则", NodeType.USER_TASK, Map.of("assigneeRule", self)))), 200);
        action(task(legacy, "review"), applicantToken, "APPROVE", null, 2, 200);
    }

    @Test
    void historicalCandidateEvidenceUsesEffectiveRosterAndNewRoundHasItsOwnSnapshot() throws Exception {
        var app = submit(publish(List.of(node("review", "SINGLE", Map.of("excludeApplicant", "true")))), 200);
        String path = "/applications/" + app.path("id").asText();
        var original = read(path + "/rounds/1/diagram", applicantToken, 200).path("nodes").get(1).path("candidateSnapshots");
        assertThat(original).hasSize(1);
        assertThat(original.get(0).path("candidateUserIds")).isEqualTo(json.read("[\"first\",\"second\"]", JsonNode.class));
        write(path + "/withdraw", applicantToken, Map.of("expectedVersion", 2), 200);
        var resubmitted = write(path + "/submit", applicantToken, Map.of("expectedVersion", 3), 200);
        action(task(resubmitted, "review"), secondToken, "APPROVE", null, 4, 200);
        assertThat(read(path + "/rounds/1/diagram", applicantToken, 200).path("nodes").get(1).path("candidateSnapshots")).isEqualTo(original);
        var current = read(path + "/rounds/2/diagram", applicantToken, 200).path("nodes").get(1).path("candidateSnapshots");
        assertThat(current.get(0).path("candidateUserIds")).isEqualTo(original.get(0).path("candidateUserIds"));
        assertThat(current.get(0).path("id")).isNotEqualTo(original.get(0).path("id"));
    }

    @Test
    void emptyDownstreamRosterRollsBackDecisionVersionAndNativeCompletion() throws Exception {
        // 指定人员规则使用权威目录暴露的标识；转交后的实际批准人仍可能不同。
        var person = organization.createPerson(admin, "only-reviewer", "独立复核", true, true);
        String onlyRule = "role:" + LocalOrganizationDirectory.PERSON_ROLE + person.id();
        String onlyToken = identity(person.subject());
        var app = submit(publish(List.of(new Node("firstReview", "初审", NodeType.USER_TASK, Map.of("assigneeRule", onlyRule)),
                new Node("review", "复核", NodeType.USER_TASK, Map.of("assigneeRule", onlyRule, "differentApproverFrom", "firstReview")))), 200);
        Task first = task(app, "firstReview");
        assertThat(action(first, onlyToken, "APPROVE", null, 2, 422).path("code").asText()).isEqualTo("APPROVAL_RESPONSIBILITY_NO_MEMBERS");
        assertThat(read("/applications/" + app.path("id").asText(), applicantToken, 200).path("version").asLong()).isEqualTo(2);
        assertThat(history.createHistoricTaskInstanceQuery().processInstanceId(first.getProcessInstanceId()).finished().count()).isZero();
        assertThat(runtime.getVariable(first.getProcessInstanceId(), io.agentflow.approval.process.FlowableApprovalResponsibilities.DECISIONS_PREFIX + "firstReview")).isNull();
        action(first, onlyToken, "TRANSFER", "first", 2, 200);
        action(first, firstToken, "APPROVE", null, 3, 200);
        Task next = task(app, "review");
        assertThat(candidates(next)).containsExactly(person.subject());
        action(next, onlyToken, "APPROVE", null, 4, 200);
    }

    @Test
    void percentageUsesFilteredDenominatorAndAllPreviousApproversAreExcluded() throws Exception {
        var percent = submit(publish(List.of(node("review", "PERCENT", Map.of("excludeApplicant", "true", "approvalPercentage", "67")))), 200);
        Task first = tasks.createTaskQuery().processVariableValueEquals("applicationId", percent.path("id").asText()).taskAssignee("first").singleResult();
        action(first, firstToken, "APPROVE", null, 2, 200);
        assertThat(read("/applications/" + percent.path("id").asText(), applicantToken, 200).path("status").asText()).isEqualTo("IN_APPROVAL");
        action(task(percent, "review"), secondToken, "APPROVE", null, 3, 200);
        assertThat(read("/applications/" + percent.path("id").asText(), applicantToken, 200).path("status").asText()).isEqualTo("APPROVED");

        var all = submit(publish(List.of(node("firstReview", "ALL", Map.of("excludeApplicant", "true")),
                node("review", "SINGLE", Map.of("differentApproverFrom", "firstReview")))), 200);
        first = tasks.createTaskQuery().processVariableValueEquals("applicationId", all.path("id").asText()).taskAssignee("first").singleResult();
        action(first, firstToken, "APPROVE", null, 2, 200);
        action(task(all, "firstReview"), secondToken, "APPROVE", null, 3, 200);
        assertThat(candidates(task(all, "review"))).containsExactly("applicant");
        action(task(all, "review"), applicantToken, "APPROVE", null, 4, 200);
    }

    private Node node(String id, String mode, Map<String, String> responsibility) {
        var properties = new java.util.HashMap<>(responsibility);
        properties.put("assigneeRule", rule); properties.put("approvalMode", mode);
        return new Node(id, id, NodeType.USER_TASK, properties);
    }
    private String publish(List<Node> reviews) {
        var nodes = new java.util.ArrayList<Node>();
        nodes.add(new Node("start", "开始", NodeType.START, Map.of())); nodes.addAll(reviews);
        nodes.add(new Node("end", "结束", NodeType.END, Map.of()));
        var edges = new java.util.ArrayList<Edge>();
        for (int i = 1; i < nodes.size(); i++) edges.add(new Edge("e" + i, nodes.get(i - 1).id(), nodes.get(i).id(), ""));
        var draft = definitions.create(admin.tenantId(), "duties-" + UUID.randomUUID(), "职责分离", new Graph(nodes, edges), null, null);
        return definitions.publish(admin, draft.id(), draft.revision(), "职责分离验证").key();
    }
    private String identity(String subject) {
        String token = UUID.randomUUID().toString();
        doReturn(new Actor(admin.tenantId(), subject, Set.of("EMPLOYEE", "APPROVER"))).when(auth).authenticate(token);
        return token;
    }
    private Task task(JsonNode app, String node) {
        return tasks.createTaskQuery().processVariableValueEquals("applicationId", app.path("id").asText()).taskDefinitionKey(node).singleResult();
    }
    private List<String> candidates(Task task) {
        return tasks.getIdentityLinksForTask(task.getId()).stream().filter(link -> "candidate".equals(link.getType()))
                .map(link -> link.getUserId()).toList();
    }
    private JsonNode submit(String key, int expected) throws Exception {
        var app = write("/applications", applicantToken, Map.of("businessNo", UUID.randomUUID().toString(), "title", "职责分离申请",
                "processKey", key, "definitionVersion", 1, "payload", Map.of()), 201);
        return write("/applications/" + app.path("id").asText() + "/submit", applicantToken, Map.of("expectedVersion", 1), expected);
    }
    private JsonNode action(Task task, String token, String action, String target, long version, int expected) throws Exception {
        var body = new java.util.HashMap<String, Object>(Map.of("action", action, "expectedVersion", version));
        if (target != null) body.put("targetUser", target);
        return write("/tasks/" + task.getId() + "/actions", token, body, expected);
    }
    private JsonNode read(String path, String token, int expected) throws Exception {
        return json.read(mvc.perform(get("/api/v1" + path).header("Authorization", "Bearer " + token)).andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
    }
    private JsonNode write(String path, String token, Object body, int expected) throws Exception {
        return json.read(mvc.perform(post("/api/v1" + path).header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json.write(body)))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString(), JsonNode.class);
    }
}
