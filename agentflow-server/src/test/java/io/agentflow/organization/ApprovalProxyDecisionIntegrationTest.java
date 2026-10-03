package io.agentflow.organization;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.api.idempotency.JdbcIdempotencyRepository;
import io.agentflow.approval.process.ApprovalCompletionService;
import io.agentflow.approval.process.FlowableCountersignRuntime;
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
import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.AopTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实代理决策必须同事务保留实际批准人与原授权，撤销不能抹去已经发生的历史。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=false", "agentflow.sla.reminders-enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ApprovalProxyDecisionIntegrationTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_PROXY_DECISION_TEST_URL", "jdbc:h2:mem:approval-proxy-decisions;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_PROXY_TEST_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_PROXY_TEST_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_PROXY_TEST_PASSWORD", ""));
    }

    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @Autowired OrganizationService organization;
    @Autowired ApprovalProxyService proxies;
    @Autowired DefinitionApplicationService definitions;
    @Autowired TaskService tasks;
    @Autowired HistoryService history;
    @Autowired FlowableCountersignRuntime countersign;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean AuthService auth;
    @MockitoSpyBean ApprovalCompletionService completion;
    @MockitoSpyBean JdbcApprovalProxyRepository proxyRecords;
    @MockitoSpyBean JdbcIdempotencyRepository idempotency;
    private Actor admin;
    private OrganizationPerson principal;
    private OrganizationPerson substitute;
    private String applicantToken;
    private String principalToken;
    private String substituteToken;
    private DefinitionDraft definition;

    @BeforeEach
    void setup() {
        admin = new Actor("proxy-decision-" + UUID.randomUUID(), "administrator", Set.of("ADMIN"));
        organization.initialize(admin);
        principal = organization.createPerson(admin, "principal", "原审批人", true, true);
        substitute = organization.createPerson(admin, "substitute", "代理人", true, true);
        organization.createPerson(admin, "applicant", "申请人", true, true);
        applicantToken = identity("applicant", Set.of("EMPLOYEE", "APPROVER"));
        principalToken = identity(principal.subject(), Set.of("APPROVER"));
        substituteToken = identity(substitute.subject(), Set.of("APPROVER"));
        definition = publish(List.of(new Node("review", "审批", NodeType.USER_TASK, Map.of("assigneeRule", personRule(principal)))));
    }

    @Test
    void approvalKeepsActualNativeAssigneeAndOriginalProxyEvidenceAndHistoricalAccess() throws Exception {
        var application = submit(definition);
        Task task = task(application);
        tasks.claim(task.getId(), principal.subject());
        Date dueAt = Date.from(Instant.now().plusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        tasks.setDueDate(task.getId(), dueAt);
        var proxy = grant(definition, principal, substitute);
        write("/tasks/" + task.getId() + "/actions", substituteToken,
                Map.of("action", "APPROVE", "expectedVersion", 2, "proxyId", proxy.id()), 200);
        var event = audit(task);
        assertThat(event.path("actor").asText()).isEqualTo(substitute.subject());
        assertThat(event.path("proxyUse").path("proxyId").asText()).isEqualTo(proxy.id().toString());
        assertThat(event.path("proxyUse").path("principal").asText()).isEqualTo(principal.subject());
        assertThat(event.path("proxyUse").path("revision").asLong()).isEqualTo(1);
        assertThat(Instant.parse(event.path("proxyUse").path("authorizedAt").asText())).isAfterOrEqualTo(proxy.createdAt()).isBefore(proxy.endsAt());
        assertThat(history.createHistoricTaskInstanceQuery().taskId(task.getId()).singleResult().getAssignee()).isEqualTo(substitute.subject());
        assertThat(history.createHistoricTaskInstanceQuery().taskId(task.getId()).singleResult().getDueDate()).isEqualTo(dueAt);
        var timeline = read("/applications/" + application.path("id").asText() + "/timeline", substituteToken, 200);
        var decisions = java.util.stream.StreamSupport.stream(timeline.path("items").spliterator(), false)
                .filter(item -> item.path("action").asText().equals("APPROVE")).toList();
        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).path("actor").asText()).isEqualTo(substitute.subject());
        assertThat(decisions.get(0).path("proxyUse")).isEqualTo(event.path("proxyUse"));
        proxies.revoke(admin, proxy.id(), 1, "代理结束");
        read("/applications/" + application.path("id").asText(), principalToken, 200);
        read("/applications/" + application.path("id").asText(), substituteToken, 200);
        assertThat(audit(task)).isEqualTo(event);
    }

    @Test
    void completedProxyAndPrincipalRetainOnlyTheOriginalNodeFieldProjectionAfterRevocation() throws Exception {
        var schema = new FormSchema(2, List.of(
                new FormSchema.Field("secret", "敏感", FormSchema.FieldType.TEXT, false, null, null, null, null, null,
                        null, null, true, Map.of("review", FieldVisibility.MASKED)),
                new FormSchema.Field("hidden", "隐藏", FormSchema.FieldType.TEXT, false, null, null, null, null, null,
                        null, null, false, Map.of("review", FieldVisibility.HIDDEN))));
        var draft = definitions.create(admin.tenantId(), "proxy-history-" + UUID.randomUUID(), "代理历史权限", definition.graph(), schema, null);
        var scope = definitions.publish(admin, draft.id(), draft.revision(), "历史字段验证");
        var proxy = grant(scope, principal, substitute);
        var application = write("/applications", applicantToken, Map.of("businessNo", UUID.randomUUID().toString(), "title", "敏感字段申请",
                "processKey", scope.key(), "definitionVersion", scope.version(), "payload", Map.of("secret", "private-original", "hidden", "hidden-original")), 201);
        String id = application.path("id").asText();
        write("/applications/" + id + "/submit", applicantToken, Map.of("expectedVersion", 1), 200);
        Task task = task(application);
        write("/tasks/" + task.getId() + "/actions", substituteToken, Map.of("action", "APPROVE", "expectedVersion", 2), 200);
        proxies.revoke(admin, proxy.id(), 1, "历史办理后撤销");
        for (String token : List.of(principalToken, substituteToken)) {
            var projected = read("/applications/" + id, token, 200);
            assertThat(projected.path("payload").path("secret").asText()).isEqualTo("已脱敏");
            assertThat(projected.path("payload").has("hidden")).isFalse();
            assertThat(projected.toString()).doesNotContain("private-original", "hidden-original");
            assertThat(read("/applications/" + id + "/rounds", token, 200).toString()).doesNotContain("private-original", "hidden-original");
        }
    }

    @Test
    void returnAndRejectKeepTheReasonActualActorAndOriginalResponsibility() throws Exception {
        grant(definition, principal, substitute);
        for (String action : List.of("RETURN", "REJECT")) {
            var application = submit(definition);
            Task task = task(application);
            write("/tasks/" + task.getId() + "/actions", substituteToken, Map.of("action", action, "expectedVersion", 2), 422);
            var receipt = write("/tasks/" + task.getId() + "/actions", substituteToken,
                    Map.of("action", action, "expectedVersion", 2, "comment", "请补充真实依据"), 200);
            assertThat(receipt.path("applicationStatus").asText()).isEqualTo(action.equals("RETURN") ? "RETURNED" : "REJECTED");
            assertThat(history.createHistoricTaskInstanceQuery().taskId(task.getId()).singleResult().getAssignee()).isEqualTo(substitute.subject());
            read("/applications/" + application.path("id").asText(), principalToken, 200);
            var event = taskEvent(task, action);
            assertThat(event.path("comment").asText()).isEqualTo("请补充真实依据");
            assertThat(event.path("proxyUse").path("principal").asText()).isEqualTo(principal.subject());
        }
    }

    @Test
    void multipleOriginalPrincipalsRequireSelectionAndOnlyTheSelectedPrincipalGainsHistory() throws Exception {
        var other = organization.createPerson(admin, "other", "另一原审批人", true, true);
        String otherToken = identity(other.subject(), Set.of("APPROVER"));
        var shared = shared(other, "SINGLE");
        grant(shared, principal, substitute);
        var selected = grant(shared, other, substitute);
        var application = submit(shared);
        Task task = task(application);
        var options = read("/tasks/" + task.getId(), substituteToken, 200);
        assertThat(options.path("proxyOptions")).hasSize(2);
        assertThat(write("/tasks/" + task.getId() + "/actions", substituteToken,
                Map.of("action", "APPROVE", "expectedVersion", 2), 422).path("code").asText()).isEqualTo("APPROVAL_PROXY_SELECTION_REQUIRED");
        write("/tasks/" + task.getId() + "/actions", substituteToken,
                Map.of("action", "APPROVE", "expectedVersion", 2, "proxyId", UUID.randomUUID()), 403);
        var unrelated = grant(definition, principal, substitute);
        write("/tasks/" + task.getId() + "/actions", substituteToken,
                Map.of("action", "APPROVE", "expectedVersion", 2, "proxyId", unrelated.id()), 403);
        write("/tasks/" + task.getId() + "/actions", substituteToken,
                Map.of("action", "APPROVE", "expectedVersion", 2, "proxyId", selected.id()), 200);
        assertThat(audit(task).path("proxyUse").path("principal").asText()).isEqualTo(other.subject());
        read("/applications/" + application.path("id").asText(), otherToken, 200);
        read("/applications/" + application.path("id").asText(), principalToken, 404);
    }

    @Test
    void nativeAuthorityTakesPrecedenceButInvalidExplicitProxyNeverFallsBack() throws Exception {
        var other = organization.createPerson(admin, "other", "另一原审批人", true, true);
        var shared = shared(other, "SINGLE");
        var proxy = grant(shared, other, principal);
        var application = submit(shared);
        Task task = task(application);
        assertThat(read("/tasks/" + task.getId(), principalToken, 200).path("canActDirectly").asBoolean()).isTrue();
        write("/tasks/" + task.getId() + "/actions", principalToken,
                Map.of("action", "APPROVE", "expectedVersion", 2, "proxyId", UUID.randomUUID()), 403);
        for (String action : List.of("CLAIM", "RELEASE", "TRANSFER", "DELEGATE", "RESOLVE")) {
            write("/tasks/" + task.getId() + "/actions", principalToken,
                    Map.of("action", action, "expectedVersion", 2, "proxyId", proxy.id(), "targetUser", substitute.subject(), "comment", "不能延长代理权限"), 403);
        }
        write("/tasks/" + task.getId() + "/actions", principalToken, Map.of("action", "APPROVE", "expectedVersion", 2), 200);
        assertThat(audit(task).has("proxyUse")).isFalse();
        Task second = task(submit(shared));
        write("/tasks/" + second.getId() + "/actions", principalToken,
                Map.of("action", "APPROVE", "expectedVersion", 2, "proxyId", proxy.id()), 200);
        assertThat(audit(second).path("proxyUse").path("principal").asText()).isEqualTo(other.subject());
    }

    @Test
    void committedRevocationWinsAgainstADecisionWaitingForTheSameProxyRow() throws Exception {
        Task task = task(submit(definition));
        var proxy = grant(definition, principal, substitute);
        var once = new AtomicBoolean();
        var revokedRowLocked = new CountDownLatch(1);
        var decisionWaiting = new CountDownLatch(1);
        var allowRevoke = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (once.compareAndSet(false, true)) {
                var locked = invocation.callRealMethod();
                revokedRowLocked.countDown();
                assertThat(allowRevoke.await(10, TimeUnit.SECONDS)).isTrue();
                return locked;
            }
            decisionWaiting.countDown();
            return invocation.callRealMethod();
        }).when(proxyRecords).lock(admin.tenantId(), proxy.id());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var revocation = pool.submit(() -> proxies.revoke(admin, proxy.id(), 1, "实际并发撤销"));
            assertThat(revokedRowLocked.await(10, TimeUnit.SECONDS)).isTrue();
            var decision = pool.submit(() -> write("/tasks/" + task.getId() + "/actions", substituteToken,
                    Map.of("action", "APPROVE", "expectedVersion", 2, "proxyId", proxy.id()), 403));
            assertThat(decisionWaiting.await(10, TimeUnit.SECONDS)).isTrue();
            allowRevoke.countDown();
            assertThat(revocation.get(20, TimeUnit.SECONDS).revocation()).isNotNull();
            assertThat(decision.get(20, TimeUnit.SECONDS).path("code").asText()).isEqualTo("FORBIDDEN");
        } finally { allowRevoke.countDown(); pool.shutdownNow(); }
        assertThat(tasks.createTaskQuery().taskId(task.getId()).singleResult()).isNotNull();
        assertThat(eventCount(task)).isZero();
    }

    @Test
    void simultaneousProxyDecisionsAtOneApplicationVersionHaveOnlyOneWinner() throws Exception {
        Task task = task(submit(definition));
        var proxy = grant(definition, principal, substitute);
        var bothRead = new CountDownLatch(2);
        doAnswer(invocation -> {
            bothRead.countDown();
            assertThat(bothRead.await(10, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(AopTestUtils.<ApprovalCompletionService>getUltimateTargetObject(completion)).lock(any());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> decisionStatus(task, proxy, "APPROVE"));
            var second = pool.submit(() -> decisionStatus(task, proxy, "REJECT"));
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        } finally { pool.shutdownNow(); }
        assertThat(eventCount(task)).isEqualTo(1);
        assertThat(tasks.createTaskQuery().taskId(task.getId()).count()).isZero();
        assertThat(history.createHistoricTaskInstanceQuery().taskId(task.getId()).finished().singleResult().getAssignee())
                .isEqualTo(substitute.subject());
    }

    @Test
    void expirationWhileWaitingForTheProxyLockRejectsTheDecision() throws Exception {
        Task task = task(submit(definition));
        Instant end = Instant.now().plusSeconds(3);
        var proxy = proxies.create(admin, definition.id(), principal.id(), substitute.id(), Instant.now().minusSeconds(60), end, "短期代理");
        // 延迟同一授权入口的行锁返回，稳定覆盖初次判断有效而等待后已到期的情况。
        doAnswer(invocation -> {
            var locked = invocation.callRealMethod();
            long wait = java.time.Duration.between(Instant.now(), proxy.endsAt()).toMillis();
            if (wait > 0) Thread.sleep(wait + 20);
            return locked;
        }).when(proxyRecords).lock(admin.tenantId(), proxy.id());
        write("/tasks/" + task.getId() + "/actions", substituteToken,
                Map.of("action", "APPROVE", "expectedVersion", 2, "proxyId", proxy.id()), 403);
        assertThat(eventCount(task)).isZero();
        assertThat(tasks.createTaskQuery().taskId(task.getId()).singleResult().getAssignee()).isNull();
    }

    @Test
    void auditAndNativeHistoryRollbackTogetherAndTheOriginalRequestRecoversOnce() throws Exception {
        var application = submit(definition);
        Task task = task(application);
        var proxy = grant(definition, principal, substitute);
        String key = UUID.randomUUID().toString();
        var body = Map.of("action", "APPROVE", "expectedVersion", 2, "proxyId", proxy.id());
        doThrow(new IllegalStateException("receipt unavailable")).when(idempotency).complete(eq(admin.tenantId()), eq(key), anyInt(), anyString());
        assertThatThrownBy(() -> writeWithKey("/tasks/" + task.getId() + "/actions", substituteToken, body, key, 200))
                .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(eventCount(task)).isZero();
        assertThat(tasks.createTaskQuery().taskId(task.getId()).singleResult().getAssignee()).isNull();
        assertThat(tasks.getIdentityLinksForTask(task.getId())).noneMatch(link -> "approvalProxyPrincipal".equals(link.getType()));
        assertThat(read("/applications/" + application.path("id").asText(), applicantToken, 200).path("version").asLong()).isEqualTo(2);
        doCallRealMethod().when(idempotency).complete(eq(admin.tenantId()), eq(key), anyInt(), anyString());
        var original = writeWithKey("/tasks/" + task.getId() + "/actions", substituteToken, body, key, 200);
        proxies.revoke(admin, proxy.id(), 1, "原决策后撤销");
        assertThat(writeWithKey("/tasks/" + task.getId() + "/actions", substituteToken, body, key, 200)).isEqualTo(original);
        assertThat(eventCount(task)).isEqualTo(1);
    }

    @Test
    void downstreamResponsibilityExcludesTheActualProxyApproverInsteadOfTheOriginalPrincipal() throws Exception {
        var legal = organization.createUnit(admin, OrganizationUnit.Kind.LEGAL_ENTITY, "法人", null, null, true);
        var department = organization.createUnit(admin, OrganizationUnit.Kind.DEPARTMENT, "复核部", legal.id(), null, true);
        var position = organization.createUnit(admin, OrganizationUnit.Kind.POSITION, "复核岗", legal.id(), null, true);
        for (var person : List.of(principal, substitute)) organization.createAppointment(admin, person.id(), department.id(), position.id(), true);
        var scope = publish(List.of(new Node("first", "初审", NodeType.USER_TASK, Map.of("assigneeRule", personRule(principal))),
                new Node("review", "独立复核", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_UNIT_" + department.id(), "differentApproverFrom", "first"))));
        grant(scope, principal, substitute);
        var application = submit(scope);
        Task first = task(application);
        write("/tasks/" + first.getId() + "/actions", substituteToken, Map.of("action", "APPROVE", "expectedVersion", 2), 200);
        Task next = task(application);
        read("/tasks/" + next.getId(), substituteToken, 403);
        assertThat(tasks.getIdentityLinksForTask(next.getId()).stream().filter(link -> "candidate".equals(link.getType())).map(link -> link.getUserId()))
                .containsExactly(principal.subject());
        write("/tasks/" + next.getId() + "/actions", principalToken, Map.of("action", "APPROVE", "expectedVersion", 3), 200);
    }

    @Test
    void countersignKeepsOneVoteAndCannotAddTheRepresentedPrincipalOrActualApproverAgain() throws Exception {
        var other = organization.createPerson(admin, "other", "独立会签人", true, true);
        String otherToken = identity(other.subject(), Set.of("APPROVER"));
        var scope = shared(other, "ALL");
        grant(scope, principal, substitute); grant(scope, other, substitute);
        var application = submit(scope);
        Task first = member(application, principal.subject());
        write("/tasks/" + first.getId() + "/actions", substituteToken, Map.of("action", "APPROVE", "expectedVersion", 2), 200);
        Task remaining = member(application, other.subject());
        read("/tasks/" + remaining.getId(), substituteToken, 403);
        write("/tasks/" + remaining.getId() + "/actions", substituteToken, Map.of("action", "APPROVE", "expectedVersion", 3), 403);
        var state = countersign.read(remaining).membership();
        assertThat(state.completedUsers()).containsExactly(substitute.subject());
        assertThat(state.completedResponsibilities()).containsExactly(principal.subject());
        for (String target : List.of(principal.subject(), substitute.subject())) {
            assertThat(write("/tasks/" + remaining.getId() + "/countersign-changes", otherToken,
                    Map.of("action", "ADD", "targetUser", target, "reason", "不能重复责任", "expectedVersion", 3), 409)
                    .path("code").asText()).isEqualTo("COUNTERSIGN_MEMBER_EXISTS");
        }
        var members = read("/tasks/" + remaining.getId() + "/countersign-members", otherToken, 200);
        assertThat(members.path("additions").isArray()).isTrue();
        assertThat(members.path("additions").toString()).doesNotContain("principal", "substitute");
        write("/tasks/" + remaining.getId() + "/actions", otherToken, Map.of("action", "APPROVE", "expectedVersion", 3), 200);
        assertThat(read("/applications/" + application.path("id").asText(), applicantToken, 200).path("status").asText()).isEqualTo("APPROVED");
    }

    private JsonNode audit(Task task) {
        return taskEvent(task, "APPROVE");
    }

    private int decisionStatus(Task task, ApprovalProxy proxy, String action) throws Exception {
        return mvc.perform(post("/api/v1/tasks/" + task.getId() + "/actions").header("Authorization", "Bearer " + substituteToken)
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.write(Map.of("action", action, "expectedVersion", 2, "comment", "独立的并发决定", "proxyId", proxy.id()))))
                .andReturn().getResponse().getStatus();
    }

    private JsonNode taskEvent(Task task, String action) {
        return json.read(jdbc.queryForObject("SELECT payload_json FROM audit_event WHERE tenant_id=? AND aggregate_type='Task' AND aggregate_id=? AND action=?",
                String.class, admin.tenantId(), task.getId(), action), JsonNode.class);
    }

    private int eventCount(Task task) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE tenant_id=? AND aggregate_type='Task' AND aggregate_id=?",
                Integer.class, admin.tenantId(), task.getId());
    }

    private Task member(JsonNode application, String user) {
        return tasks.createTaskQuery().processVariableValueEquals("applicationId", application.path("id").asText()).taskAssignee(user).singleResult();
    }

    private DefinitionDraft shared(OrganizationPerson other, String mode) {
        var legal = organization.createUnit(admin, OrganizationUnit.Kind.LEGAL_ENTITY, "法人", null, null, true);
        var department = organization.createUnit(admin, OrganizationUnit.Kind.DEPARTMENT, "共同部门", legal.id(), null, true);
        var position = organization.createUnit(admin, OrganizationUnit.Kind.POSITION, "共同岗位", legal.id(), null, true);
        for (var person : List.of(principal, other)) organization.createAppointment(admin, person.id(), department.id(), position.id(), true);
        return publish(List.of(new Node("review", "共同审批", NodeType.USER_TASK,
                Map.of("assigneeRule", "role:ORG_UNIT_" + department.id(), "approvalMode", mode))));
    }

    private String personRule(OrganizationPerson person) { return "role:ORG_PERSON_" + person.id(); }

    private DefinitionDraft publish(List<Node> reviews) {
        var nodes = new java.util.ArrayList<Node>();
        nodes.add(new Node("start", "开始", NodeType.START, Map.of())); nodes.addAll(reviews);
        nodes.add(new Node("end", "结束", NodeType.END, Map.of()));
        var edges = new java.util.ArrayList<Edge>();
        for (int index = 1; index < nodes.size(); index++) edges.add(new Edge("e" + index, nodes.get(index - 1).id(), nodes.get(index).id(), ""));
        var draft = definitions.create(admin.tenantId(), "proxy-decision-" + UUID.randomUUID(), "代理办理", new Graph(nodes, edges));
        return definitions.publish(admin, draft.id(), draft.revision(), "代理决策验证");
    }

    private ApprovalProxy grant(DefinitionDraft scope, OrganizationPerson from, OrganizationPerson to) {
        return proxies.create(admin, scope.id(), from.id(), to.id(), Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), "临时代理");
    }

    private String identity(String subject, Set<String> roles) {
        String token = UUID.randomUUID().toString();
        doReturn(new Actor(admin.tenantId(), subject, roles)).when(auth).authenticate(token);
        return token;
    }

    private Task task(JsonNode application) {
        return tasks.createTaskQuery().processVariableValueEquals("applicationId", application.path("id").asText()).singleResult();
    }

    private JsonNode submit(DefinitionDraft scope) throws Exception {
        var app = write("/applications", applicantToken, Map.of("businessNo", UUID.randomUUID().toString(), "title", "代理决策申请",
                "processKey", scope.key(), "definitionVersion", scope.version(), "payload", Map.of()), 201);
        return write("/applications/" + app.path("id").asText() + "/submit", applicantToken, Map.of("expectedVersion", 1), 200);
    }

    private JsonNode read(String path, String token, int expected) throws Exception {
        return json.read(mvc.perform(get("/api/v1" + path).header("Authorization", "Bearer " + token))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString(), JsonNode.class);
    }

    private JsonNode write(String path, String token, Object body, int expected) throws Exception {
        return writeWithKey(path, token, body, UUID.randomUUID().toString(), expected);
    }

    private JsonNode writeWithKey(String path, String token, Object body, String key, int expected) throws Exception {
        return json.read(mvc.perform(post("/api/v1" + path).header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json.write(body)))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString(), JsonNode.class);
    }
}
