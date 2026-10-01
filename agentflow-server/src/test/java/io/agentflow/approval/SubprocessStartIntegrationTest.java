package io.agentflow.approval;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.approval.model.SubprocessCall;
import io.agentflow.approval.process.FlowableTaskFacade;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.approval.repository.SubprocessCallRepository;
import io.agentflow.attachment.AttachmentService;
import io.agentflow.attachment.JdbcAttachmentRepository;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.definition.DefinitionApplicationService;
import io.agentflow.definition.DefinitionAvailabilityService;
import io.agentflow.definition.DefinitionDraftRepository;
import io.agentflow.definition.SubprocessPolicy;
import io.agentflow.form.FormSchema;
import io.agentflow.organization.LocalOrganizationDirectory;
import io.agentflow.organization.OrganizationService;
import io.agentflow.organization.OrganizationUnit;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;

/**
 * 实际申请提交和任务办理驱动原生调用；父子结论协调仍单独验收，当前发布入口保持关闭。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "agentflow.sla.reminders-enabled=false"})
class SubprocessStartIntegrationTest {
    private static final Actor ADMIN = new Actor("demo", "admin", Set.of("ADMIN"));
    private static final Path FILES = Path.of("/fyoung/tmp/agentflow-subprocess-start-" + UUID.randomUUID());
    private String tenant = "demo";
    @Autowired ApprovalApplicationFacade applications;
    @Autowired ApplicationRepository repository;
    @Autowired SubmissionRoundRepository rounds;
    @Autowired SubprocessCallRepository calls;
    @Autowired DefinitionDraftRepository drafts;
    @Autowired DefinitionApplicationService definitions;
    @Autowired DefinitionAvailabilityService availability;
    @Autowired RepositoryService engine;
    @Autowired RuntimeService runtime;
    @Autowired TaskService tasks;
    @Autowired FlowableTaskFacade actions;
    @Autowired CurrentActor actors;
    @Autowired OrganizationService organization;
    @Autowired AttachmentService attachments;
    @Autowired JdbcAttachmentRepository files;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean SubprocessStartService starts;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("agentflow.attachments.directory", FILES::toString);
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_SUBPROCESS_START_URL", "jdbc:h2:mem:subprocess-start;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_SUBPROCESS_START_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_SUBPROCESS_START_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_SUBPROCESS_START_PASSWORD", ""));
    }

    @AfterEach void clearActor() { actors.clear(); }

    @Test
    void initialSubmissionStartsTheFixedChildVersionWithSeparateIdentityAndOnlyMappedInputs() {
        var childDefinition = child(key(), schema("total"), "user:manager");
        var parent = parent(childDefinition, schema("amount", "privateNote"), Map.of("total", "amount"), false, false);
        child(childDefinition.key(), schema("newField"), "user:finance");
        var application = create(parent, Map.of("amount", "12.300", "privateNote", "999"));
        var submitted = as("alice", () -> applications.submit(application.id(), 1));
        var call = onlyCall(application.id()); var child = repository.findById("demo", call.childApplicationId()).orElseThrow();
        assertThat(submitted.status()).isEqualTo(ApplicationStatus.IN_APPROVAL);
        assertThat(child.status()).isEqualTo(ApplicationStatus.IN_APPROVAL);
        assertThat(child.createdBy()).isEqualTo("alice");
        assertThat(child.definitionVersion()).isEqualTo(1);
        assertThat(child.id()).isNotEqualTo(application.id());
        assertThat(child.payload()).containsExactlyEntriesOf(Map.of("total", "12.300"));
        var childInstance = runtime.createProcessInstanceQuery().processInstanceId(call.childProcessInstanceId()).singleResult();
        assertThat(runtime.createProcessInstanceQuery().superProcessInstanceId(call.parentProcessInstanceId()).singleResult().getId())
                .isEqualTo(childInstance.getId());
        assertThat(childInstance.getProcessDefinitionId()).isEqualTo(nativeId(childDefinition));
        assertThat(runtime.getVariables(childInstance.getId())).containsEntry("applicationId", child.id().toString())
                .containsEntry("formData", child.payload()).doesNotContainKeys("privateNote", "agentflowPreparedSubprocess");
        assertThat(tasks.createTaskQuery().processInstanceId(call.parentProcessInstanceId()).count()).isZero();
        assertThat(tasks.createTaskQuery().processInstanceId(call.childProcessInstanceId()).singleResult().getAssignee()).isEqualTo("manager");
        assertThat(rounds.findByRound("demo", child.id(), 1).orElseThrow().submittedBy()).isEqualTo(SubprocessStartService.SYSTEM_ACTOR);
        assertThat(jdbc.queryForList("SELECT action FROM audit_event WHERE application_id=? AND actor_id=? ORDER BY aggregate_version", String.class,
                child.id().toString(), SubprocessStartService.SYSTEM_ACTOR)).containsExactly("CREATE", "SUBMIT");
        assertThat(rounds.findByRound("demo", application.id(), 1).orElseThrow().payload()).isEqualTo(application.payload());
    }

    @Test
    void laterActivationUsesTheOriginalInitiatorSnapshotInsteadOfTheCurrentApproverOrDirectoryNames() {
        // 本地目录启用后不会回退演示选人；独立租户避免该状态影响其他用例的演示规则。
        tenant = "subcontext-" + UUID.randomUUID();
        var admin = new Actor(tenant, "admin", Set.of("ADMIN"));
        organization.initialize(admin);
        var legal = organization.createUnit(admin, OrganizationUnit.Kind.LEGAL_ENTITY, "子流程测试法人", null, null, true);
        var department = organization.createUnit(admin, OrganizationUnit.Kind.DEPARTMENT, "原任职部门", legal.id(), null, true);
        var position = organization.createUnit(admin, OrganizationUnit.Kind.POSITION, "原岗位", legal.id(), null, true);
        var alice = organization.createPerson(admin, "alice", "申请人", true, false);
        var manager = organization.createPerson(admin, "manager", "主管", true, true);
        organization.createPerson(admin, "finance", "财务", true, true);
        var job = organization.createAppointment(admin, alice.id(), department.id(), position.id(), true);
        var supervisor = organization.createAppointment(admin, manager.id(), department.id(), position.id(), true);
        organization.setSupervisor(admin, job.id(), supervisor.id(), job.revision());
        var childDefinition = child(key(), schema("total"), LocalOrganizationDirectory.SUPERVISOR_RULE + "1");
        var parent = parent(childDefinition, schema("amount"), Map.of("total", "amount"), true, false);
        var application = create(parent, Map.of("amount", "8"));
        as("alice", () -> applications.submit(application.id(), 1, job.id()));
        var frozen = rounds.findByRound(tenant, application.id(), 1).orElseThrow().initiatorContext();
        assertThat(calls.findByParentRound(tenant, application.id(), 1)).isEmpty();
        organization.updateUnit(admin, department.id(), "修改后的目录名称", null, true, department.revision());
        var before = tasks.createTaskQuery().processVariableValueEquals("applicationId", application.id().toString()).singleResult();
        as("manager", () -> actions.action(before.getId(), "APPROVE", "进入独立核对", null, 2L));
        var call = onlyCall(application.id());
        assertThat(repository.findById(tenant, call.childApplicationId()).orElseThrow().createdBy()).isEqualTo("alice");
        assertThat(rounds.findByRound(tenant, call.childApplicationId(), 1).orElseThrow().initiatorContext()).isEqualTo(frozen);
        assertThat(frozen.departmentName()).isEqualTo("原任职部门");
        assertThat(tasks.createTaskQuery().processInstanceId(call.childProcessInstanceId()).taskCandidateUser("manager").count()).isEqualTo(1);
    }

    @Test
    void failureAfterChildPersistenceRollsBackBothNativeInstancesAndAllBusinessRowsThenAllowsOneRetry() {
        var childDefinition = child(key(), schema("total"), "user:manager");
        var parent = parent(childDefinition, schema("amount"), Map.of("total", "amount"), false, false);
        var application = create(parent, Map.of("amount", "10"));
        int beforeApplications = count("approval_application"); int beforeRounds = count("approval_submission_round");
        int beforeCalls = count("approval_subprocess_call"); int beforeAudit = count("audit_event");
        long beforeNative = runtime.createProcessInstanceQuery().count();
        SubprocessStartService target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(starts);
        doAnswer(invocation -> { invocation.callRealMethod(); throw new IllegalStateException("Injected failure after child persistence"); })
                .when(target).persist(any(), anyString());
        try {
            assertThatThrownBy(() -> as("alice", () -> applications.submit(application.id(), 1)))
                    .hasStackTraceContaining("Injected failure after child persistence");
        } finally { doCallRealMethod().when(target).persist(any(), anyString()); }
        assertThat(repository.findById("demo", application.id()).orElseThrow().status()).isEqualTo(ApplicationStatus.DRAFT);
        assertThat(count("approval_application")).isEqualTo(beforeApplications);
        assertThat(count("approval_submission_round")).isEqualTo(beforeRounds);
        assertThat(count("approval_subprocess_call")).isEqualTo(beforeCalls);
        assertThat(count("audit_event")).isEqualTo(beforeAudit);
        assertThat(runtime.createProcessInstanceQuery().count()).isEqualTo(beforeNative);
        as("alice", () -> applications.submit(application.id(), 1));
        assertThat(calls.findByParentRound("demo", application.id(), 1)).hasSize(1);
        assertThat(count("approval_application")).isEqualTo(beforeApplications + 1);
    }

    @Test
    void rebindsRealUploadedFilesBeforeCreatingTheChildHumanTask() throws Exception {
        var childSchema = new FormSchema(2, List.of(file("document")));
        var childDefinition = child(key(), childSchema, "user:manager");
        var parent = parent(childDefinition, new FormSchema(2, List.of(file("proof"))), Map.of("document", "proof"), false, false);
        var application = create(parent, Map.of()); byte[] content = {3, 1, 4, 1};
        var registered = as("alice", () -> attachments.reserve(application.id(), new AttachmentService.UploadInput(1L, "proof", "原证明.bin", 4L,
                digest(content))));
        as("alice", () -> attachments.upload(application.id(), registered.id(), 1, new ByteArrayInputStream(content)));
        as("alice", () -> applications.revise(application.id(), 1, "父申请附件", Map.of("proof", List.of(registered.id().toString()))));
        as("alice", () -> applications.submit(application.id(), 2));
        var call = onlyCall(application.id()); var child = repository.findById("demo", call.childApplicationId()).orElseThrow();
        UUID alias = UUID.fromString((String) ((List<?>) child.payload().get("document")).get(0));
        assertThat(alias).isNotEqualTo(registered.id());
        assertThat(files.get("demo", child.id(), alias).contentId()).isEqualTo(registered.id());
        assertThat(files.frozen(files.get("demo", child.id(), alias), 1)).isTrue();
        assertThat(Files.exists(FILES.resolve(alias + ".bin"))).isFalse();
        as("manager", () -> {
            assertThat(attachments.download(child.id(), alias, 1).content()).isEqualTo(content);
            failure(() -> attachments.download(application.id(), registered.id(), 1), "NOT_FOUND");
            return null;
        });
    }

    @Test
    void aDisabledOriginalChildVersionRollsBackTheParentDecisionAndKeepsItsTask() {
        var child = child(key(), schema("total"), "user:manager");
        var parent = parent(child, schema("amount"), Map.of("total", "amount"), true, false);
        var application = create(parent, Map.of("amount", "6"));
        as("alice", () -> applications.submit(application.id(), 1));
        var task = tasks.createTaskQuery().processVariableValueEquals("applicationId", application.id().toString()).singleResult();
        availability.change(ADMIN, child.id(), child.revision(), false, "停用原版子流程");
        failure(() -> as("manager", () -> actions.action(task.getId(), "APPROVE", "核对通过", null, 2L)), "DEFINITION_DISABLED");
        assertThat(tasks.createTaskQuery().taskId(task.getId()).count()).isEqualTo(1);
        assertThat(repository.findById("demo", application.id()).orElseThrow().version()).isEqualTo(2);
        assertThat(calls.findByParentRound("demo", application.id(), 1)).isEmpty();
    }

    @Test
    void refusesNativeVariableInheritanceAndWrongVersionBeforeCreatingAChildApplication() {
        var first = child(key(), schema("total"), "user:manager");
        var parent = parent(first, schema("amount"), Map.of("total", "amount"), false, true);
        var application = create(parent, Map.of("amount", "5"));
        failure(() -> as("alice", () -> applications.submit(application.id(), 1)), "SUBPROCESS_VARIABLE_BINDING_INVALID");
        assertThat(calls.findByParentRound("demo", application.id(), 1)).isEmpty();
        var second = child(first.key(), schema("total"), "user:manager");
        var wrong = parent(first, schema("amount"), Map.of("total", "amount"), false, false, nativeId(second));
        var wrongApplication = create(wrong, Map.of("amount", "5"));
        failure(() -> as("alice", () -> applications.submit(wrongApplication.id(), 1)), "SUBPROCESS_DEFINITION_MISMATCH");
        assertThat(runtime.createProcessInstanceQuery().variableValueEquals("applicationId", wrongApplication.id().toString()).count()).isZero();
        assertThat(calls.findByParentRound("demo", wrongApplication.id(), 1)).isEmpty();
    }

    @Test
    void nestedCallsUseIndependentRoundsAndPersistTheIntermediateParentBeforeItsOwnChildStarts() {
        var leaf = child(key(), schema("total"), "user:manager");
        var middle = parent(leaf, schema("amount"), Map.of("total", "amount"), false, false);
        var root = parent(middle, schema("rootAmount"), Map.of("amount", "rootAmount"), false, false);
        var application = create(root, Map.of("rootAmount", "2"));
        as("alice", () -> applications.submit(application.id(), 1));
        var first = onlyCall(application.id()); var second = onlyCall(first.childApplicationId());
        assertThat(second.parentProcessInstanceId()).isEqualTo(first.childProcessInstanceId());
        assertThat(second.childApplicationId()).isNotEqualTo(first.childApplicationId()).isNotEqualTo(application.id());
        assertThat(rounds.findByRound("demo", first.childApplicationId(), 1)).isPresent();
        assertThat(rounds.findByRound("demo", second.childApplicationId(), 1)).isPresent();
        assertThat(tasks.createTaskQuery().processInstanceId(first.childProcessInstanceId()).count()).isZero();
        assertThat(tasks.createTaskQuery().processInstanceId(second.childProcessInstanceId()).count()).isEqualTo(1);
    }

    @Test
    void refusesAnEnginePayloadThatDiffersFromTheParentApplicationAndKeepsTheOriginalHumanTask() {
        var child = child(key(), schema("total"), "user:manager");
        var parent = parent(child, schema("amount"), Map.of("total", "amount"), true, false);
        var application = create(parent, Map.of("amount", "6"));
        as("alice", () -> applications.submit(application.id(), 1));
        var task = tasks.createTaskQuery().processVariableValueEquals("applicationId", application.id().toString()).singleResult();
        runtime.setVariable(task.getProcessInstanceId(), "formData", Map.of("amount", "999"));
        failure(() -> as("manager", () -> actions.action(task.getId(), "APPROVE", "进入子流程", null, 2L)), "SUBPROCESS_PARENT_MISMATCH");
        assertThat(tasks.createTaskQuery().taskId(task.getId()).count()).isEqualTo(1);
        assertThat(repository.findById("demo", application.id()).orElseThrow().payload()).isEqualTo(application.payload());
        assertThat(calls.findByParentRound("demo", application.id(), 1)).isEmpty();
        runtime.setVariable(task.getProcessInstanceId(), "formData", application.payload());
        as("manager", () -> actions.action(task.getId(), "APPROVE", "恢复原数据后进入", null, 2L));
        var call = onlyCall(application.id());
        assertThat(repository.findById("demo", call.childApplicationId()).orElseThrow().payload()).containsEntry("total", "6");
    }

    @Test
    void activationAfterResubmissionBindsTheNewParentRoundAndKeepsTheWithdrawnRound() {
        var child = child(key(), schema("total"), "user:manager");
        var parent = parent(child, schema("amount"), Map.of("total", "amount"), true, false);
        var application = create(parent, Map.of("amount", "7"));
        as("alice", () -> applications.submit(application.id(), 1));
        var withdrawn = as("alice", () -> applications.withdraw(application.id(), 2, "调用前撤回补正"));
        var resubmitted = as("alice", () -> applications.submit(application.id(), withdrawn.version()));
        var task = tasks.createTaskQuery().processVariableValueEquals("applicationId", application.id().toString()).singleResult();
        as("manager", () -> actions.action(task.getId(), "APPROVE", "新轮次进入子流程", null, resubmitted.version()));
        assertThat(calls.findByParentRound("demo", application.id(), 1)).isEmpty();
        var call = calls.findByParentRound("demo", application.id(), 2).get(0);
        assertThat(call.parentProcessInstanceId()).isEqualTo(task.getProcessInstanceId());
        assertThat(rounds.findByRound("demo", application.id(), 1).orElseThrow().status()).isEqualTo(SubmissionRound.Status.WITHDRAWN);
        assertThat(rounds.findByRound("demo", call.childApplicationId(), 1).orElseThrow().status()).isEqualTo(SubmissionRound.Status.IN_APPROVAL);
        assertThat(repository.findById("demo", call.childApplicationId()).orElseThrow().roundNo()).isEqualTo(1);
    }

    @Test
    void publicDraftEntryStaysClosedUntilTheWholeRuntimeLifecycleIsImplemented() {
        var child = child(key(), schema("total"), "user:manager");
        var graph = parentGraph(new SubprocessPolicy(child.key(), 1, Map.of("total", "amount")), false);
        assertThatThrownBy(() -> definitions.create("demo", key(), "尚未开放", graph, schema("amount")))
                .isInstanceOf(io.agentflow.definition.DefinitionValidationException.class);
        assertThat(definitions.validate(graph, schema("amount"))).contains("SUBPROCESS_RUNTIME_NOT_READY:call");
    }

    private DefinitionDraft child(String key, FormSchema schema, String assignee) {
        var graph = new Graph(List.of(new Node("start", "发起", NodeType.START, Map.of()),
                new Node("review", "独立人工核对", NodeType.USER_TASK, Map.of("assigneeRule", assignee)),
                new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("a", "start", "review", ""), new Edge("b", "review", "end", "")));
        var draft = definitions.create(tenant, key, "固定子流程", graph, schema);
        return definitions.publish(new Actor(tenant, "admin", Set.of("ADMIN")), draft.id(), draft.revision(), "实际启动验收");
    }

    private DefinitionDraft parent(DefinitionDraft child, FormSchema schema, Map<String, String> inputs, boolean before, boolean inherit) {
        return parent(child, schema, inputs, before, inherit, nativeId(child));
    }

    /** 内部固定夹具绕过尚未开放的发布入口，运行时仍必须核对实际引擎标识和平台定义。 */
    private DefinitionDraft parent(DefinitionDraft child, FormSchema schema, Map<String, String> inputs, boolean before, boolean inherit, String calledId) {
        var policy = new SubprocessPolicy(child.key(), child.version(), inputs);
        var draft = DefinitionDraft.create(UUID.randomUUID(), tenant, key(), "父流程", parentGraph(policy, before), schema);
        drafts.save(draft); draft.publish(0, 1); drafts.save(draft);
        String preceding = before ? "<userTask id=\"before\" name=\"父流程先审\" flowable:assignee=\"manager\"/><sequenceFlow id=\"toCall\" sourceRef=\"before\" targetRef=\"call\"/>" : "";
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="http://agentflow.io/test">
                  <process id="%s" isExecutable="true"><startEvent id="start"/>
                    <sequenceFlow id="a" sourceRef="start" targetRef="%s"/>%s
                    <callActivity id="call" calledElement="%s" flowable:calledElementType="id" flowable:inheritVariables="%s" flowable:fallbackToDefaultTenant="false"/>
                    <sequenceFlow id="b" sourceRef="call" targetRef="after"/>
                    <userTask id="after" name="父流程后审" flowable:assignee="finance"/>
                    <sequenceFlow id="c" sourceRef="after" targetRef="end"/><endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(draft.key(), before ? "before" : "call", preceding, calledId, inherit);
        engine.createDeployment().tenantId(tenant).addString(draft.key() + ".bpmn20.xml", xml).deploy();
        return draft;
    }

    private Graph parentGraph(SubprocessPolicy policy, boolean before) {
        var nodes = new ArrayList<>(List.of(new Node("start", "发起", NodeType.START, Map.of()),
                new Node("call", "独立材料核对", NodeType.SUB_PROCESS, policy.properties()),
                new Node("after", "父流程后审", NodeType.USER_TASK, Map.of("assigneeRule", "user:finance")),
                new Node("end", "结束", NodeType.END, Map.of())));
        var edges = new ArrayList<>(List.of(new Edge("a", "start", before ? "before" : "call", ""),
                new Edge("b", "call", "after", ""), new Edge("c", "after", "end", "")));
        if (before) { nodes.add(new Node("before", "父流程先审", NodeType.USER_TASK, Map.of("assigneeRule", "user:manager"))); edges.add(new Edge("toCall", "before", "call", "")); }
        return new Graph(nodes, edges);
    }

    private Application create(DefinitionDraft parent, Map<String, Object> payload) {
        return as("alice", () -> applications.create("subprocess-test-" + UUID.randomUUID(), parent.key(), parent.version(), "父申请", payload));
    }
    private SubprocessCall onlyCall(UUID parent) {
        var result = calls.findByParentRound(tenant, parent, 1); assertThat(result).hasSize(1); return result.get(0);
    }
    private String nativeId(DefinitionDraft definition) {
        return engine.createProcessDefinitionQuery().processDefinitionTenantId(tenant).processDefinitionKey(definition.key()).processDefinitionVersion((int) definition.version()).singleResult().getId();
    }
    private <T> T as(String user, Supplier<T> work) {
        actors.set(new Actor(tenant, user, Set.of("alice".equals(user) ? "EMPLOYEE" : "APPROVER")));
        try { return work.get(); } finally { actors.clear(); }
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private String key() { return "sub" + UUID.randomUUID().toString().replace("-", ""); }
    private FormSchema schema(String... fields) { return new FormSchema(2, java.util.Arrays.stream(fields).map(key ->
            new FormSchema.Field(key, key, FormSchema.FieldType.NUMBER, true, null, null, null, null, null)).toList()); }
    private FormSchema.Field file(String key) { return new FormSchema.Field(key, key, FormSchema.FieldType.ATTACHMENT, true, null, null, null, null, null); }
    private String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private void failure(Runnable work, String code) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo(code));
    }
}
