package io.agentflow.organization;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.approval.process.FlowableApprovalProxyAccess;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.approval.service.ProcessRuntimePort;
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
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 从真实发布、提交和原生待办验证代理读取；代理不能把限时权利转成永久责任。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=false", "agentflow.sla.reminders-enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ApprovalProxyTaskReadIntegrationTest {
    private static final Path FILES = Path.of("/fyoung/tmp/approval-proxy-read-files-" + UUID.randomUUID());
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("agentflow.attachments.directory", FILES::toString);
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_PROXY_TASK_TEST_URL", "jdbc:h2:mem:approval-proxy-task-read;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_PROXY_TEST_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_PROXY_TEST_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_PROXY_TEST_PASSWORD", ""));
    }

    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @Autowired OrganizationService organization;
    @Autowired OrganizationRepository directory;
    @Autowired ApprovalProxyService proxies;
    @Autowired DefinitionApplicationService definitions;
    @Autowired TaskService tasks;
    @Autowired RuntimeService runtime;
    @Autowired FlowableApprovalProxyAccess access;
    @Autowired ApplicationRepository applications;
    @Autowired SubmissionRoundRepository rounds;
    @Autowired ProcessRuntimePort engine;
    @MockitoSpyBean AuthService auth;
    private Actor admin;
    private OrganizationPerson principal;
    private OrganizationPerson substitute;
    private String applicantToken;
    private String principalToken;
    private String substituteToken;
    private DefinitionDraft definition;

    @BeforeEach
    void setup() {
        admin = new Actor("proxy-task-" + UUID.randomUUID(), "administrator", Set.of("ADMIN"));
        organization.initialize(admin);
        principal = organization.createPerson(admin, "principal", "原审批人", true, true);
        substitute = organization.createPerson(admin, "substitute", "代理人", true, true);
        organization.createPerson(admin, "applicant", "申请人", true, true);
        applicantToken = identity("applicant", Set.of("EMPLOYEE", "APPROVER"));
        principalToken = identity(principal.subject(), Set.of("APPROVER"));
        substituteToken = identity(substitute.subject(), Set.of("APPROVER"));
        definition = publish(Map.of("assigneeRule", "role:ORG_PERSON_" + principal.id()));
    }

    @Test
    void effectiveProxyReadsTheNativeTaskAndApplicationWithoutClaimingIt() throws Exception {
        var application = submit(definition);
        Task task = task(application);
        var proxy = grant(definition, principal, substitute);
        var detail = read("/tasks/" + task.getId(), substituteToken, 200);
        assertThat(detail.path("applicationId").asText()).isEqualTo(application.path("id").asText());
        assertThat(detail.path("allowedActions").toString()).isEqualTo("[\"APPROVE\",\"RETURN\",\"REJECT\"]");
        assertThat(detail.path("canActDirectly").asBoolean()).isFalse();
        assertThat(read("/tasks", substituteToken, 200)).hasSize(1);
        assertThat(read("/workspace/tasks", substituteToken, 200).path("total").asLong()).isEqualTo(1);
        read("/applications/" + application.path("id").asText(), substituteToken, 200);
        assertThat(tasks.createTaskQuery().taskId(task.getId()).singleResult().getAssignee()).isNull();
        for (String action : List.of("CLAIM", "RELEASE", "TRANSFER", "DELEGATE", "RESOLVE")) {
            write("/tasks/" + task.getId() + "/actions", substituteToken,
                    Map.of("action", action, "expectedVersion", 2, "comment", "无办理授权", "targetUser", "principal"), 403);
        }
        read("/tasks/" + task.getId() + "/recipients", substituteToken, 403);
        proxies.revoke(admin, proxy.id(), 1, "原审批人返岗");
        read("/tasks/" + task.getId(), substituteToken, 403);
        assertThat(read("/workspace/tasks", substituteToken, 200).path("total").asLong()).isZero();
        read("/applications/" + application.path("id").asText(), substituteToken, 404);
        read("/tasks/" + task.getId(), principalToken, 200);
    }

    @Test
    void actualRoleAndBothPeopleRemainRequiredAndNoOtherTenantCanBorrowTheGrant() throws Exception {
        var application = submit(definition);
        Task task = task(application);
        grant(definition, principal, substitute);
        String adminOnly = identity(substitute.subject(), Set.of("ADMIN"));
        read("/tasks/" + task.getId(), adminOnly, 403);
        assertThat(read("/workspace/tasks", adminOnly, 200).path("items")).isEmpty();
        String foreign = UUID.randomUUID().toString();
        doReturn(new Actor("unrelated-tenant", substitute.subject(), Set.of("APPROVER"))).when(auth).authenticate(foreign);
        read("/tasks/" + task.getId(), foreign, 404);
        read("/applications/" + application.path("id").asText(), foreign, 404);
        organization.updatePerson(admin, principal.id(), principal.displayName(), false, true, 1);
        assertDenied(task);
        organization.updatePerson(admin, principal.id(), principal.displayName(), true, true, 2);
        read("/tasks/" + task.getId(), substituteToken, 200);
        organization.updatePerson(admin, substitute.id(), substitute.displayName(), true, false, 1);
        assertDenied(task);
        organization.updatePerson(admin, substitute.id(), substitute.displayName(), true, true, 2);
        read("/tasks/" + task.getId(), substituteToken, 200);
        doReturn(new Actor(admin.tenantId(), substitute.subject(), Set.of("EMPLOYEE"))).when(auth).authenticate(substituteToken);
        assertDenied(task);
    }

    @Test
    void scheduledAndExactVersionScopesUseInclusiveStartExclusiveEndWithoutAJob() throws Exception {
        Task first = task(submit(definition));
        grant(definition, principal, substitute);
        var draft = definitions.create(admin.tenantId(), definition.key(), "代理第二版", definition.graph());
        var secondVersion = definitions.publish(admin, draft.id(), draft.revision(), "独立版本");
        Task second = task(submit(secondVersion));
        read("/tasks/" + first.getId(), substituteToken, 200);
        read("/tasks/" + second.getId(), substituteToken, 403);
        Instant start = Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.MICROS);
        var scheduled = proxies.create(admin, secondVersion.id(), principal.id(), substitute.id(), start, start.plusSeconds(600), "未来代理");
        read("/tasks/" + second.getId(), substituteToken, 403);
        var actor = new Actor(admin.tenantId(), substitute.subject(), Set.of("APPROVER"));
        Task loaded = loaded(second.getId());
        assertThat(access.forActor(actor, start.minusNanos(1)).canRead(loaded)).isFalse();
        assertThat(access.forActor(actor, start).canRead(loaded)).isTrue();
        assertThat(access.forActor(actor, scheduled.endsAt().minusNanos(1)).canRead(loaded)).isTrue();
        assertThat(access.forActor(actor, scheduled.endsAt()).canRead(loaded)).isFalse();
        assertThat(access.forActor(actor, scheduled.endsAt()).taskIds()).doesNotContain(second.getId());
    }

    @Test
    void roundBindingSuspensionAndUnrecordedInstancesCannotBecomeProxyTasks() throws Exception {
        var application = submit(definition);
        Task task = task(application);
        grant(definition, principal, substitute);
        runtime.setVariable(task.getProcessInstanceId(), "roundNo", 2);
        assertDenied(task);
        runtime.setVariable(task.getProcessInstanceId(), "roundNo", 1);
        runtime.suspendProcessInstanceById(task.getProcessInstanceId());
        read("/tasks/" + task.getId(), substituteToken, 404);
        assertThat(read("/workspace/tasks", substituteToken, 200).path("total").asLong()).isZero();
        read("/applications/" + application.path("id").asText(), substituteToken, 404);
        runtime.activateProcessInstanceById(task.getProcessInstanceId());
        read("/tasks/" + task.getId(), substituteToken, 200);
        var unbound = runtime.startProcessInstanceById(task.getProcessDefinitionId(), Map.of("tenantId", admin.tenantId(),
                "applicationId", application.path("id").asText(), "roundNo", 1));
        Task other = tasks.createTaskQuery().processInstanceId(unbound.getId()).singleResult();
        read("/tasks/" + other.getId(), substituteToken, 403);
        assertThat(read("/workspace/tasks", substituteToken, 200).path("total").asLong()).isEqualTo(1);
    }

    @Test
    void directProxyDoesNotChainOrTakeOverPendingNativeDelegation() throws Exception {
        var third = organization.createPerson(admin, "third", "第三人", true, true);
        String thirdToken = identity(third.subject(), Set.of("APPROVER"));
        Task task = task(submit(definition));
        grant(definition, principal, substitute);
        grant(definition, substitute, third);
        read("/tasks/" + task.getId(), thirdToken, 403);
        write("/tasks/" + task.getId() + "/actions", principalToken,
                Map.of("action", "DELEGATE", "expectedVersion", 2, "targetUser", third.subject()), 200);
        assertDenied(task);
        write("/tasks/" + task.getId() + "/actions", thirdToken,
                Map.of("action", "RESOLVE", "expectedVersion", 3, "comment", "完成协助"), 200);
        read("/tasks/" + task.getId(), substituteToken, 200);
        write("/tasks/" + task.getId() + "/actions", principalToken,
                Map.of("action", "TRANSFER", "expectedVersion", 4, "targetUser", third.subject()), 200);
        assertDenied(task);
    }

    @Test
    void originalNativeRoleMembershipIsNotInferredFromLocalApprovalEligibility() throws Exception {
        Task task = task(submit(definition));
        grant(definition, principal, substitute);
        tasks.deleteCandidateUser(task.getId(), principal.subject());
        tasks.addCandidateGroup(task.getId(), "FINANCE");
        assertDenied(task);
        // 角色不是本地目录可证明的事实；只有原任务明确指派该人后才满足直接代理依据。
        tasks.setAssignee(task.getId(), principal.subject());
        Date dueAt = Date.from(Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.MILLIS));
        tasks.setDueDate(task.getId(), dueAt);
        read("/tasks/" + task.getId(), substituteToken, 200);
        Task unchanged = tasks.createTaskQuery().taskId(task.getId()).singleResult();
        assertThat(unchanged.getAssignee()).isEqualTo(principal.subject());
        assertThat(unchanged.getOwner()).isNull();
        assertThat(unchanged.getDueDate()).isEqualTo(dueAt);
    }

    @Test
    void selfApprovalAndPreviousActualApproverExclusionsApplyToTheSubstitute() throws Exception {
        var selfGuard = publish(Map.of("assigneeRule", personRule(principal), "excludeApplicant", "true"));
        var applicant = organizationPerson("applicant");
        grant(selfGuard, principal, applicant);
        Task self = task(submit(selfGuard));
        read("/tasks/" + self.getId(), applicantToken, 403);
        var chain = publish(List.of(new Node("first", "初审", NodeType.USER_TASK, Map.of("assigneeRule", personRule(substitute))),
                new Node("review", "复核", NodeType.USER_TASK, Map.of("assigneeRule", personRule(principal), "differentApproverFrom", "first"))), null);
        grant(chain, principal, substitute);
        var app = submit(chain);
        Task first = task(app);
        write("/tasks/" + first.getId() + "/actions", substituteToken, Map.of("action", "APPROVE", "expectedVersion", 2), 200);
        assertDenied(task(app));
    }

    @Test
    void countersignProxyCannotBorrowAnotherVoteWhileResponsibleOrAfterApproving() throws Exception {
        var own = publish(Map.of("assigneeRule", unitRule(List.of(principal, substitute)), "approvalMode", "ALL"));
        grant(own, principal, substitute);
        var app = submit(own);
        Task original = member(app, principal.subject());
        read("/tasks/" + original.getId(), substituteToken, 403);
        Task mine = member(app, substitute.subject());
        read("/tasks/" + mine.getId(), substituteToken, 200);
        write("/tasks/" + mine.getId() + "/actions", substituteToken, Map.of("action", "APPROVE", "expectedVersion", 2), 200);
        assertDenied(original);
        var third = organization.createPerson(admin, "third", "独立会签人", true, true);
        var separate = publish(Map.of("assigneeRule", unitRule(List.of(principal, third)), "approvalMode", "ALL"));
        grant(separate, principal, substitute);
        var independent = submit(separate);
        read("/tasks/" + member(independent, principal.subject()).getId(), substituteToken, 200);
        read("/tasks/" + member(independent, third.subject()).getId(), substituteToken, 403);
        assertThat(read("/workspace/tasks", substituteToken, 200).path("total").asLong()).isEqualTo(1);
    }

    @Test
    void multiplePrincipalsDoNotDuplicateRowsAndAuthorizationPrecedesPaginationAndFiltering() throws Exception {
        var third = organization.createPerson(admin, "third", "共同候选人", true, true);
        var shared = publish(Map.of("assigneeRule", unitRule(List.of(principal, third))));
        var firstGrant = grant(shared, principal, substitute);
        grant(shared, third, substitute);
        for (int index = 0; index < 3; index++) submit(shared);
        var first = read("/workspace/tasks?limit=2", substituteToken, 200);
        assertThat(first.path("total").asLong()).isEqualTo(3);
        assertThat(first.path("items")).hasSize(2);
        String next = "/workspace/tasks?limit=2&cursor=" + first.path("nextCursor").asText();
        var last = read(next, substituteToken, 200);
        assertThat(last.path("total").asLong()).isEqualTo(3);
        assertThat(last.path("items")).hasSize(1);
        String lastTask = last.path("items").get(0).path("taskId").asText();
        assertThat(first.path("items").toString()).doesNotContain(lastTask);
        tasks.claim(lastTask, principal.subject());
        assertThat(read("/workspace/tasks?assignment=assigned", substituteToken, 200).path("total").asLong()).isEqualTo(1);
        assertThat(read("/workspace/tasks?assignment=unclaimed", substituteToken, 200).path("total").asLong()).isEqualTo(2);
        assertThat(read("/workspace/tasks?processKey=unrelated", substituteToken, 200).path("total").asLong()).isZero();
        proxies.revoke(admin, firstGrant.id(), 1, "原责任恢复");
        var emptyAfter = read(next, substituteToken, 200);
        assertThat(emptyAfter.path("items")).isEmpty();
        assertThat(emptyAfter.path("total").asLong()).isEqualTo(2);
    }

    @Test
    void explicitFinanceNodeRequiresTheSubstitutesOwnFinanceRole() throws Exception {
        var schema = new FormSchema(2, List.of(new FormSchema.Field("expenseDetails", "费用明细", FormSchema.FieldType.TEXT, true,
                null, null, null, null, null, null, null, true, Map.of("review", FieldVisibility.READ_ONLY)),
                new FormSchema.Field("amount", "金额", FormSchema.FieldType.NUMBER, true, null, null, null, null, null),
                new FormSchema.Field("currency", "币种", FormSchema.FieldType.TEXT, true, null, null, null, null, null),
                new FormSchema.Field("overPolicy", "超标", FormSchema.FieldType.BOOLEAN, true, null, null, null, null, null)));
        var finance = publish(List.of(new Node("review", "财务复核", NodeType.USER_TASK,
                Map.of("assigneeRule", personRule(principal), "expenseStage", "FINANCE_REVIEW"))), schema);
        // 只为读取边界准备原生任务及冻结轮次；此夹具不代替报销预检、提交或财务办理验收。
        String nativeId = engine.resolveDefinition(admin.tenantId(), finance.key(), finance.version(), false);
        var application = Application.restore(UUID.randomUUID(), admin.tenantId(), UUID.randomUUID().toString(), finance.key(),
                finance.version(), "applicant", "财务读取边界", Map.of("expenseDetails", "财务依据", "amount", "10", "currency", "CNY", "overPolicy", false),
                ApplicationStatus.IN_APPROVAL, 1, 2, schema, nativeId);
        applications.save(application);
        var instance = runtime.startProcessInstanceById(nativeId, Map.of("tenantId", admin.tenantId(), "applicationId", application.id().toString(), "roundNo", 1));
        rounds.append(SubmissionRound.submitted(application, instance.getId(), "applicant", Instant.now()));
        Task task = tasks.createTaskQuery().processInstanceId(instance.getId()).singleResult();
        grant(finance, principal, substitute);
        assertDenied(task);
        doReturn(new Actor(admin.tenantId(), substitute.subject(), Set.of("APPROVER", "FINANCE"))).when(auth).authenticate(substituteToken);
        assertThat(read("/tasks/" + task.getId(), substituteToken, 200).path("allowedActions").toString()).contains("APPROVE");
        assertThat(read("/workspace/tasks", substituteToken, 200).path("total").asLong()).isEqualTo(1);
    }

    @Test
    void fieldProjectionAndOriginalAttachmentsUseTheSameRevocableNodePermission() throws Exception {
        var schema = new FormSchema(2, List.of(field("visible", FormSchema.FieldType.TEXT, FieldVisibility.READ_ONLY),
                field("hidden", FormSchema.FieldType.TEXT, FieldVisibility.HIDDEN),
                field("masked", FormSchema.FieldType.TEXT, FieldVisibility.MASKED),
                field("proof", FormSchema.FieldType.ATTACHMENT, FieldVisibility.READ_ONLY),
                field("hiddenProof", FormSchema.FieldType.ATTACHMENT, FieldVisibility.HIDDEN)));
        var scope = publish(List.of(new Node("review", "原字段规则", NodeType.USER_TASK, Map.of("assigneeRule", personRule(principal)))), schema);
        String app = create(scope).path("id").asText();
        byte[] bytes = new byte[] {1, 2, 3};
        String proof = upload(app, "proof", bytes);
        String hidden = upload(app, "hiddenProof", bytes);
        var payload = Map.of("visible", "可读原文", "hidden", "隐藏原文", "masked", "脱敏原文", "proof", List.of(proof), "hiddenProof", List.of(hidden));
        mvc.perform(put("/api/v1/applications/" + app).header("Authorization", "Bearer " + applicantToken)
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.write(Map.of("expectedVersion", 1, "title", "字段和附件", "payload", payload)))).andExpect(status().isOk());
        write("/applications/" + app + "/submit", applicantToken, Map.of("expectedVersion", 2), 200);
        var proxy = grant(scope, principal, substitute);
        var projected = read("/applications/" + app, substituteToken, 200);
        assertThat(projected.path("payload").path("visible").asText()).isEqualTo("可读原文");
        assertThat(projected.path("payload").path("masked").asText()).isEqualTo("已脱敏");
        assertThat(projected.toString()).doesNotContain("隐藏原文", "脱敏原文", hidden);
        assertThat(read("/applications/" + app + "/rounds", substituteToken, 200).toString()).doesNotContain("隐藏原文", "脱敏原文", hidden);
        var response = mvc.perform(get("/api/v1/applications/" + app + "/attachments/" + proof + "/content")
                .header("Authorization", "Bearer " + substituteToken)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getContentAsByteArray()).isEqualTo(bytes);
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        read("/applications/" + app + "/attachments/" + hidden, substituteToken, 403);
        proxies.revoke(admin, proxy.id(), 1, "结束访问");
        read("/applications/" + app + "/attachments/" + proof, substituteToken, 404);
        mvc.perform(get("/api/v1/applications/" + app + "/attachments/" + proof + "/content?roundNo=1")
                .header("Authorization", "Bearer " + substituteToken)).andExpect(status().isNotFound());
    }

    private DefinitionDraft publish(Map<String, String> properties) {
        return publish(List.of(new Node("review", "审批", NodeType.USER_TASK, properties)), null);
    }

    private DefinitionDraft publish(List<Node> reviews, FormSchema schema) {
        var nodes = new ArrayList<Node>();
        nodes.add(new Node("start", "开始", NodeType.START, Map.of()));
        nodes.addAll(reviews);
        nodes.add(new Node("end", "结束", NodeType.END, Map.of()));
        var edges = new ArrayList<Edge>();
        for (int index = 1; index < nodes.size(); index++) edges.add(new Edge("e" + index, nodes.get(index - 1).id(), nodes.get(index).id(), ""));
        var draft = definitions.create(admin.tenantId(), "proxy-read-" + UUID.randomUUID(), "代理读取", new Graph(nodes, edges), schema, null);
        return definitions.publish(admin, draft.id(), draft.revision(), "代理任务验证");
    }

    private String personRule(OrganizationPerson person) { return "role:ORG_PERSON_" + person.id(); }

    private String unitRule(List<OrganizationPerson> members) {
        var legal = organization.createUnit(admin, OrganizationUnit.Kind.LEGAL_ENTITY, "测试法人", null, null, true);
        var department = organization.createUnit(admin, OrganizationUnit.Kind.DEPARTMENT, "会签部门", legal.id(), null, true);
        var position = organization.createUnit(admin, OrganizationUnit.Kind.POSITION, "会签岗位", legal.id(), null, true);
        for (var person : members) organization.createAppointment(admin, person.id(), department.id(), position.id(), true);
        return "role:ORG_UNIT_" + department.id();
    }

    private OrganizationPerson organizationPerson(String subject) {
        return directory.personBySubject(admin.tenantId(), subject).orElseThrow();
    }

    private Task member(JsonNode application, String subject) {
        return tasks.createTaskQuery().processVariableValueEquals("applicationId", application.path("id").asText()).taskAssignee(subject).singleResult();
    }

    private Task loaded(String id) { return tasks.createTaskQuery().taskId(id).includeProcessVariables().includeIdentityLinks().singleResult(); }

    private void assertDenied(Task task) throws Exception {
        read("/tasks/" + task.getId(), substituteToken, 403);
        assertThat(read("/workspace/tasks", substituteToken, 200).path("total").asLong()).isZero();
        assertThat(read("/tasks", substituteToken, 200)).isEmpty();
    }

    private FormSchema.Field field(String key, FormSchema.FieldType type, FieldVisibility visibility) {
        return new FormSchema.Field(key, key, type, false, null, null, null, null, null, null, null, true, Map.of("review", visibility));
    }

    private String upload(String app, String field, byte[] bytes) throws Exception {
        var metadata = Map.of("fieldPath", field, "filename", field + ".bin", "size", bytes.length,
                "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), "expectedVersion", 1);
        String id = write("/applications/" + app + "/attachments", applicantToken, metadata, 201).path("id").asText();
        mvc.perform(put("/api/v1/applications/" + app + "/attachments/" + id + "/content")
                .header("Authorization", "Bearer " + applicantToken).header("X-Application-Version", 1)
                .contentType(MediaType.APPLICATION_OCTET_STREAM).content(bytes)).andExpect(status().isOk());
        return id;
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
        var app = create(scope);
        return write("/applications/" + app.path("id").asText() + "/submit", applicantToken, Map.of("expectedVersion", 1), 200);
    }

    private JsonNode create(DefinitionDraft scope) throws Exception {
        return write("/applications", applicantToken, Map.of("businessNo", UUID.randomUUID().toString(), "title", "代理读取申请",
                "processKey", scope.key(), "definitionVersion", scope.version(), "payload", Map.of()), 201);
    }

    private JsonNode read(String path, String token, int expected) throws Exception {
        return json.read(mvc.perform(get("/api/v1" + path).header("Authorization", "Bearer " + token))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString(), JsonNode.class);
    }

    private JsonNode write(String path, String token, Object body, int expected) throws Exception {
        return json.read(mvc.perform(post("/api/v1" + path).header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json.write(body)))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString(), JsonNode.class);
    }
}
