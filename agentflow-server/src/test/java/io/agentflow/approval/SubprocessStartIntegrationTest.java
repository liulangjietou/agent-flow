package io.agentflow.approval;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.approval.model.SubprocessCall;
import io.agentflow.approval.process.FlowableTaskFacade;
import io.agentflow.approval.process.TimerWaitService;
import io.agentflow.approval.process.EventWaitService;
import io.agentflow.approval.process.InstanceControlService;
import io.agentflow.approval.process.FlowableTaskDeadlineListener;
import io.agentflow.approval.process.FlowableTaskDeadlineReminders;
import io.agentflow.calendar.BusinessCalendar;
import io.agentflow.calendar.BusinessCalendarRepository;
import io.agentflow.calendar.CalendarRules;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.approval.repository.SubprocessCallRepository;
import io.agentflow.attachment.AttachmentService;
import io.agentflow.attachment.JdbcAttachmentRepository;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.auth.AuthService;
import io.agentflow.definition.DefinitionApplicationService;
import io.agentflow.definition.DefinitionAvailabilityService;
import io.agentflow.definition.DefinitionDraftRepository;
import io.agentflow.definition.SubprocessPolicy;
import io.agentflow.form.FormSchema;
import io.agentflow.event.EventContractService;
import io.agentflow.notification.InboxMessage;
import io.agentflow.notification.InboxRepository;
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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.EnumMap;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.ManagementService;
import org.flowable.engine.ProcessEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;

/**
 * 实际提交、人工决定及等待驱动父子调用；覆盖正常接续、停止联动和同事务结论，发布入口保持关闭。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "agentflow.sla.reminders-enabled=false",
        "agentflow.timers.enabled=false",
        "agentflow.webhooks.worker-enabled=false", "agentflow.webhooks.targets.subprocess.tenant-id=demo",
        "agentflow.webhooks.targets.subprocess.label=子流程合成验收", "agentflow.webhooks.targets.subprocess.url=https://example.invalid/webhook",
        "agentflow.webhooks.targets.subprocess.enabled=true", "agentflow.webhooks.targets.subprocess.signing-secret=whsec_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class SubprocessStartIntegrationTest {
    private static final Actor ADMIN = new Actor("demo", "admin", Set.of("ADMIN"));
    private static final Path FILES = Path.of("/fyoung/tmp/agentflow-subprocess-start-" + UUID.randomUUID());
    private String tenant = "demo";
    @Autowired ApprovalApplicationFacade applications;
    @MockitoSpyBean ApplicationRepository repository;
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
    @Autowired TimerWaitService timers;
    @Autowired EventWaitService events;
    @Autowired EventContractService contracts;
    @Autowired InstanceControlService instances;
    @Autowired ManagementService jobs;
    @Autowired ProcessEngine processEngine;
    @Autowired BusinessCalendarRepository calendars;
    @Autowired FlowableTaskDeadlineReminders reminders;
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;
    @MockitoSpyBean SubprocessStartService starts;
    @MockitoSpyBean InboxRepository inbox;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("agentflow.attachments.directory", FILES::toString);
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_SUBPROCESS_START_URL", "jdbc:h2:mem:subprocess-start;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_SUBPROCESS_START_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_SUBPROCESS_START_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_SUBPROCESS_START_PASSWORD", ""));
    }

    @AfterEach void clearActor() { actors.clear(); processEngine.getProcessEngineConfiguration().getClock().reset(); }

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
        failure(() -> as("alice", () -> applications.submit(application.id(), 1)), "INITIATOR_APPOINTMENT_REQUIRED");
        assertThat(repository.findById(tenant, application.id()).orElseThrow().status()).isEqualTo(ApplicationStatus.DRAFT);
        assertThat(rounds.findByRound(tenant, application.id(), 1)).isEmpty();
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
    void childApprovalAdvancesTheParentVersionAndNotifiesItsNewHumanTask() {
        var child = child(key(), schema("total"), "user:manager");
        var parent = parent(child, schema("amount"), Map.of("total", "amount"), false, false);
        var application = create(parent, Map.of("amount", "9"));
        as("alice", () -> applications.submit(application.id(), 1));
        var call = onlyCall(application.id());
        var task = tasks.createTaskQuery().processInstanceId(call.childProcessInstanceId()).singleResult();
        as("manager", () -> actions.action(task.getId(), "APPROVE", "子审批通过", null, 2L));
        var continued = repository.findById("demo", application.id()).orElseThrow();
        assertThat(continued.status()).isEqualTo(ApplicationStatus.IN_APPROVAL);
        assertThat(continued.version()).isEqualTo(3);
        assertThat(repository.findById("demo", call.childApplicationId()).orElseThrow().status()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(tasks.createTaskQuery().processInstanceId(call.parentProcessInstanceId()).singleResult().getTaskDefinitionKey()).isEqualTo("after");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='SUBPROCESS_COMPLETED' AND actor_id=?", Integer.class,
                application.id().toString(), SubprocessStartService.SYSTEM_ACTOR)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE application_id=? AND kind='TASK_PENDING'", Integer.class,
                application.id().toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE application_id=? AND kind='TASK_PENDING'", Integer.class,
                call.childApplicationId().toString())).isEqualTo(1);
    }

    @Test
    void childApprovalCompletesAllEndedAncestorsWithoutInventingParentHumanDecisions() {
        var leaf = child(key(), schema("total"), "user:manager");
        var middle = parent(leaf, schema("amount"), Map.of("total", "amount"), false, false, nativeId(leaf), false);
        var root = parent(middle, schema("rootAmount"), Map.of("amount", "rootAmount"), false, false, nativeId(middle), false);
        var application = create(root, Map.of("rootAmount", "3"));
        as("alice", () -> applications.submit(application.id(), 1));
        var first = onlyCall(application.id()); var second = onlyCall(first.childApplicationId());
        var task = tasks.createTaskQuery().processInstanceId(second.childProcessInstanceId()).singleResult();
        as("manager", () -> actions.action(task.getId(), "APPROVE", "唯一真实人工意见", null, 2L));
        for (UUID id : List.of(application.id(), first.childApplicationId(), second.childApplicationId())) {
            assertThat(repository.findById("demo", id).orElseThrow().status()).isEqualTo(ApplicationStatus.APPROVED);
            assertThat(rounds.findByRound("demo", id, 1).orElseThrow().status()).isEqualTo(SubmissionRound.Status.APPROVED);
            assertThat(runtime.createProcessInstanceQuery().variableValueEquals("applicationId", id.toString()).count()).isZero();
        }
        for (UUID id : List.of(application.id(), first.childApplicationId())) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='SUBPROCESS_COMPLETED'", Integer.class, id.toString())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='APPROVE'", Integer.class, id.toString())).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE application_id=? AND kind='APPLICATION_APPROVED'", Integer.class, id.toString())).isEqualTo(1);
            assertThat(jdbc.queryForList("SELECT event_type FROM webhook_delivery WHERE application_id=?", String.class, id.toString()))
                    .containsExactlyInAnyOrder("ApplicationSubmitted", "SubprocessCompleted", "ApplicationApproved");
        }
    }

    @Test
    void parentNotificationFailureRollsBackTheChildDecisionEngineAndAllAncestorConclusions() {
        var leaf = child(key(), schema("total"), "user:manager");
        var parent = parent(leaf, schema("amount"), Map.of("total", "amount"), false, false, nativeId(leaf), false);
        var application = create(parent, Map.of("amount", "10"));
        as("alice", () -> applications.submit(application.id(), 1));
        var call = onlyCall(application.id());
        var task = tasks.createTaskQuery().processInstanceId(call.childProcessInstanceId()).singleResult();
        int auditBefore = count("audit_event"); int notificationsBefore = count("notification_inbox"); int outboxBefore = count("webhook_delivery");
        InboxRepository target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(inbox);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            InboxMessage message = invocation.getArgument(1);
            if (message.applicationId().equals(application.id()) && message.kind() == InboxMessage.Kind.APPLICATION_APPROVED) {
                throw new IllegalStateException("Injected failure after parent notification");
            }
            return null;
        }).when(target).append(anyString(), any());
        try {
            assertThatThrownBy(() -> as("manager", () -> actions.action(task.getId(), "APPROVE", "真实审批意见", null, 2L)))
                    .hasStackTraceContaining("Injected failure after parent notification");
        } finally { doCallRealMethod().when(target).append(anyString(), any()); }
        for (UUID id : List.of(application.id(), call.childApplicationId())) {
            assertThat(repository.findById("demo", id).orElseThrow().status()).isEqualTo(ApplicationStatus.IN_APPROVAL);
            assertThat(repository.findById("demo", id).orElseThrow().version()).isEqualTo(2);
            assertThat(rounds.findByRound("demo", id, 1).orElseThrow().status()).isEqualTo(SubmissionRound.Status.IN_APPROVAL);
        }
        assertThat(tasks.createTaskQuery().taskId(task.getId()).count()).isEqualTo(1);
        assertThat(count("audit_event")).isEqualTo(auditBefore);
        assertThat(count("notification_inbox")).isEqualTo(notificationsBefore);
        assertThat(count("webhook_delivery")).isEqualTo(outboxBefore);
        as("manager", () -> actions.action(task.getId(), "APPROVE", "原任务重试", null, 2L));
        assertThat(repository.findById("demo", application.id()).orElseThrow().status()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='SUBPROCESS_COMPLETED'", Integer.class,
                application.id().toString())).isEqualTo(1);
    }

    @Test
    void parallelChildrenKeepTheParentWaitingForTheOtherRealDecision() {
        var leaf = child(key(), schema("total"), "user:manager");
        var parent = parallelParent(leaf);
        var application = create(parent, Map.of("amount", "4"));
        as("alice", () -> applications.submit(application.id(), 1));
        var children = calls.findByParentRound("demo", application.id(), 1);
        assertThat(children).hasSize(2);
        var first = tasks.createTaskQuery().processInstanceId(children.get(0).childProcessInstanceId()).singleResult();
        as("manager", () -> actions.action(first.getId(), "APPROVE", "第一条实际意见", null, 2L));
        assertThat(repository.findById("demo", application.id()).orElseThrow().status()).isEqualTo(ApplicationStatus.IN_APPROVAL);
        assertThat(repository.findById("demo", application.id()).orElseThrow().version()).isEqualTo(3);
        assertThat(tasks.createTaskQuery().processInstanceId(children.get(1).childProcessInstanceId()).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE application_id=? AND kind='APPLICATION_APPROVED'", Integer.class,
                application.id().toString())).isZero();
    }

    @Test
    void concurrentChildDecisionsSerializeAtTheRootAndProduceOneFinalConclusion() throws Exception {
        var leaf = child(key(), schema("total"), "user:manager");
        var application = create(parallelParent(leaf), Map.of("amount", "5"));
        as("alice", () -> applications.submit(application.id(), 1));
        var children = calls.findByParentRound("demo", application.id(), 1);
        var taskIds = children.stream().map(call -> tasks.createTaskQuery().processInstanceId(call.childProcessInstanceId()).singleResult().getId()).toList();
        var barrier = new CyclicBarrier(2); var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { barrier.await(); return as("manager", () -> actions.action(taskIds.get(0), "APPROVE", "并行意见一", null, 2L)); });
            var second = pool.submit(() -> { barrier.await(); return as("manager", () -> actions.action(taskIds.get(1), "APPROVE", "并行意见二", null, 2L)); });
            first.get(20, TimeUnit.SECONDS); second.get(20, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        var root = repository.findById("demo", application.id()).orElseThrow();
        assertThat(root.status()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(root.version()).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='SUBPROCESS_COMPLETED'", Integer.class,
                application.id().toString())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE application_id=? AND kind='APPLICATION_APPROVED'", Integer.class,
                application.id().toString())).isEqualTo(1);
    }

    @Test
    void aChildTimerCompletesTheParentAfterTheChildHumanDecision() {
        var leaf = waitingChild(NodeType.TIMER_WAIT, null);
        var parent = parent(leaf, schema("amount"), Map.of("total", "amount"), false, false, nativeId(leaf), false);
        var application = create(parent, Map.of("amount", "6"));
        as("alice", () -> applications.submit(application.id(), 1));
        var call = onlyCall(application.id());
        var task = tasks.createTaskQuery().processInstanceId(call.childProcessInstanceId()).singleResult();
        as("manager", () -> actions.action(task.getId(), "APPROVE", "批准后等待原到期", null, 2L));
        var job = jobs.createTimerJobQuery().processInstanceId(call.childProcessInstanceId()).singleResult();
        actors.set(ADMIN);
        try { instances.pause(application.id(), 1, new InstanceControlService.Input(2L, "原根流程暂停")); }
        finally { actors.clear(); }
        assertThat(timers.advance(job.getId(), job.getDuedate().toInstant())).isFalse();
        var suspended = jobs.createSuspendedJobQuery().processInstanceId(call.childProcessInstanceId()).singleResult();
        assertThat(suspended.getId()).isEqualTo(job.getId()); assertThat(suspended.getDuedate()).isEqualTo(job.getDuedate());
        actors.set(ADMIN);
        try { instances.resume(application.id(), 1, new InstanceControlService.Input(3L, "原根流程恢复")); }
        finally { actors.clear(); }
        assertThat(jobs.createTimerJobQuery().jobId(job.getId()).singleResult().getDuedate()).isEqualTo(job.getDuedate());
        assertThat(timers.advance(job.getId(), job.getDuedate().toInstant())).isTrue();
        assertThat(repository.findById("demo", application.id()).orElseThrow().status()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(timers.advance(job.getId(), job.getDuedate().toInstant())).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='TIMER_ELAPSED'", Integer.class,
                call.childApplicationId().toString())).isEqualTo(1);
    }

    @Test
    void aChildEventCompletesTheParentUsingTheSameRoundAndCannotRepeat() {
        String contract = "sub-event-" + UUID.randomUUID();
        contracts.publish(ADMIN, contract, 0, "子流程事件", "erp", "GoodsAccepted", "明确事件版本");
        var leaf = waitingChild(NodeType.EVENT_WAIT, contract);
        var parent = parent(leaf, schema("amount"), Map.of("total", "amount"), false, false, nativeId(leaf), false);
        var application = create(parent, Map.of("amount", "7"));
        as("alice", () -> applications.submit(application.id(), 1));
        var call = onlyCall(application.id());
        var task = tasks.createTaskQuery().processInstanceId(call.childProcessInstanceId()).singleResult();
        as("manager", () -> actions.action(task.getId(), "APPROVE", "批准后等待实际事件", null, 2L));
        var subscription = runtime.createEventSubscriptionQuery().processInstanceId(call.childProcessInstanceId()).singleResult();
        var command = new EventWaitService.Command("demo", "erp", "GoodsAccepted", 1, UUID.randomUUID().toString(), call.childApplicationId(), 1,
                subscription.getId(), contract, 1);
        actors.set(ADMIN);
        try { instances.pause(application.id(), 1, new InstanceControlService.Input(2L, "等待来源期间暂停")); }
        finally { actors.clear(); }
        assertThat(events.advance(command)).isEqualTo(EventWaitService.Outcome.PAUSED);
        assertThat(runtime.createProcessInstanceQuery().processInstanceId(call.childProcessInstanceId()).singleResult().isSuspended()).isTrue();
        actors.set(ADMIN);
        try { instances.resume(application.id(), 1, new InstanceControlService.Input(3L, "继续原等待")); }
        finally { actors.clear(); }
        assertThat(events.advance(command)).isEqualTo(EventWaitService.Outcome.ADVANCED);
        assertThat(repository.findById("demo", application.id()).orElseThrow().status()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(events.advance(command)).isEqualTo(EventWaitService.Outcome.STALE);
    }

    @Test
    void childCannotBeWithdrawnIndependentlyAndCannotAdvanceWhileItsRootIsPaused() {
        var leaf = child(key(), schema("total"), "user:manager");
        var application = create(parent(leaf, schema("amount"), Map.of("total", "amount"), false, false), Map.of("amount", "8"));
        as("alice", () -> applications.submit(application.id(), 1));
        var call = onlyCall(application.id());
        failure(() -> as("alice", () -> applications.withdraw(call.childApplicationId(), 2, "不能拆开原调用")), "SUBPROCESS_PARENT_CONTROL_REQUIRED");
        actors.set(ADMIN);
        try {
            instances.pause(application.id(), 1, new InstanceControlService.Input(2L, "暂停原根流程"));
            assertThat(instances.read(call.childApplicationId(), 1).canPause()).isFalse();
        } finally { actors.clear(); }
        var task = tasks.createTaskQuery().processInstanceId(call.childProcessInstanceId()).singleResult();
        assertThat(task.isSuspended()).isTrue();
        failure(() -> as("manager", () -> actions.action(task.getId(), "APPROVE", "根流程未恢复", null, 3L)), "NOT_FOUND");
        actors.set(ADMIN);
        try { instances.resume(application.id(), 1, new InstanceControlService.Input(3L, "恢复原根流程")); }
        finally { actors.clear(); }
        as("manager", () -> actions.action(task.getId(), "APPROVE", "恢复后办理", null, 4L));
        assertThat(repository.findById("demo", application.id()).orElseThrow().version()).isEqualTo(5);
    }

    @Test
    void rootTimerAfterAnApprovedChildKeepsThatRealApprovalEvidence() {
        var leaf = child(key(), schema("total"), "user:manager");
        var application = create(parentWithTimer(leaf, false), Map.of("amount", "9"));
        as("alice", () -> applications.submit(application.id(), 1));
        var call = onlyCall(application.id());
        var task = tasks.createTaskQuery().processInstanceId(call.childProcessInstanceId()).singleResult();
        as("manager", () -> actions.action(task.getId(), "APPROVE", "子审批是真实批准依据", null, 2L));
        var job = jobs.createTimerJobQuery().processInstanceId(call.parentProcessInstanceId()).singleResult();
        assertThat(repository.findById("demo", application.id()).orElseThrow().status()).isEqualTo(ApplicationStatus.IN_APPROVAL);
        assertThat(timers.advance(job.getId(), job.getDuedate().toInstant())).isTrue();
        assertThat(repository.findById("demo", application.id()).orElseThrow().status()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='APPROVE'", Integer.class,
                application.id().toString())).isZero();
    }

    @Test
    void rootTimerStartsTheChildWithoutARequestActorAndSendsItsPendingNotification() {
        var leaf = child(key(), schema("total"), "user:manager");
        var application = create(parentWithTimer(leaf, true), Map.of("amount", "10"));
        as("alice", () -> applications.submit(application.id(), 1));
        assertThat(calls.findByParentRound("demo", application.id(), 1)).isEmpty();
        var instance = rounds.findByRound("demo", application.id(), 1).orElseThrow().processInstanceId();
        var job = jobs.createTimerJobQuery().processInstanceId(instance).singleResult();
        assertThat(timers.advance(job.getId(), job.getDuedate().toInstant())).isTrue();
        var call = onlyCall(application.id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE application_id=? AND kind='TASK_PENDING'", Integer.class,
                call.childApplicationId().toString())).isEqualTo(1);
        var task = tasks.createTaskQuery().processInstanceId(call.childProcessInstanceId()).singleResult();
        as("manager", () -> actions.action(task.getId(), "APPROVE", "等待后实际人工办理", null, 2L));
        assertThat(repository.findById("demo", application.id()).orElseThrow().status()).isEqualTo(ApplicationStatus.APPROVED);
    }

    @Test
    void publicDraftEntryStaysClosedUntilTheWholeRuntimeLifecycleIsImplemented() {
        var child = child(key(), schema("total"), "user:manager");
        var graph = parentGraph(new SubprocessPolicy(child.key(), 1, Map.of("total", "amount")), false);
        assertThatThrownBy(() -> definitions.create("demo", key(), "尚未开放", graph, schema("amount")))
                .isInstanceOf(io.agentflow.definition.DefinitionValidationException.class);
        assertThat(definitions.validate(graph, schema("amount"))).contains("SUBPROCESS_RUNTIME_NOT_READY:call");
    }

    @Test
    void rootWithdrawalCancelsOnlyActiveChildrenAndPreservesAnAlreadyApprovedSibling() {
        var leaf = child(key(), schema("total"), "user:manager");
        var application = create(parallelParent(leaf), Map.of("amount", "7"));
        as("alice", () -> applications.submit(application.id(), 1));
        var children = calls.findByParentRound("demo", application.id(), 1);
        var completed = children.get(0); var cancelled = children.get(1);
        var first = tasks.createTaskQuery().processInstanceId(completed.childProcessInstanceId()).singleResult();
        as("manager", () -> actions.action(first.getId(), "APPROVE", "已形成真实意见", null, 2L));
        var approved = repository.findById("demo", completed.childApplicationId()).orElseThrow();
        var originalRound = rounds.findByRound("demo", approved.id(), 1).orElseThrow();
        var parent = repository.findById("demo", application.id()).orElseThrow();
        as("alice", () -> applications.withdraw(parent.id(), parent.version(), "撤回其余未完成部分"));
        assertThat(repository.findById("demo", parent.id()).orElseThrow().status()).isEqualTo(ApplicationStatus.WITHDRAWN);
        var stopped = repository.findById("demo", cancelled.childApplicationId()).orElseThrow();
        assertThat(stopped.status()).isEqualTo(ApplicationStatus.CANCELLED);
        assertThat(rounds.findByRound("demo", stopped.id(), 1).orElseThrow().status().name()).isEqualTo("CANCELLED");
        assertThat(repository.findById("demo", approved.id()).orElseThrow().version()).isEqualTo(approved.version());
        assertThat(rounds.findByRound("demo", approved.id(), 1).orElseThrow()).isEqualTo(originalRound);
        assertThat(runtime.createProcessInstanceQuery().processInstanceId(cancelled.childProcessInstanceId()).count()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND aggregate_type='Task'", Integer.class,
                stopped.id().toString())).isZero();
    }

    @Test
    void childRejectionStopsItsAncestorsAndCancelsTheOtherActiveBranchWithoutInventingOpinions() {
        var leaf = child(key(), schema("total"), "user:manager");
        var middle = parent(leaf, schema("total"), Map.of("total", "total"), false, false, nativeId(leaf), false);
        var application = create(parallelParent(middle), Map.of("amount", "9"));
        as("alice", () -> applications.submit(application.id(), 1));
        var branches = calls.findByParentRound("demo", application.id(), 1);
        var decidedBranch = branches.get(0); var stoppedBranch = branches.get(1);
        var decidedLeaf = onlyCall(decidedBranch.childApplicationId());
        var stoppedLeaf = onlyCall(stoppedBranch.childApplicationId());
        var task = tasks.createTaskQuery().processInstanceId(decidedLeaf.childProcessInstanceId()).singleResult();
        as("manager", () -> actions.action(task.getId(), "REJECT", "仅属于本子申请的具体理由", null, 2L));
        for (var id : List.of(application.id(), decidedBranch.childApplicationId(), decidedLeaf.childApplicationId())) {
            assertThat(repository.findById("demo", id).orElseThrow().status()).isEqualTo(ApplicationStatus.REJECTED);
            assertThat(rounds.findByRound("demo", id, 1).orElseThrow().status()).isEqualTo(SubmissionRound.Status.REJECTED);
        }
        for (var id : List.of(stoppedBranch.childApplicationId(), stoppedLeaf.childApplicationId())) {
            assertThat(repository.findById("demo", id).orElseThrow().status()).isEqualTo(ApplicationStatus.CANCELLED);
            assertThat(rounds.findByRound("demo", id, 1).orElseThrow().status().name()).isEqualTo("CANCELLED");
        }
        for (var id : List.of(application.id(), decidedBranch.childApplicationId(), stoppedBranch.childApplicationId(), stoppedLeaf.childApplicationId())) {
            assertThat(tasks.createTaskQuery().processVariableValueEquals("applicationId", id.toString()).count()).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND aggregate_type='Task'", Integer.class, id.toString())).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND payload_json LIKE ?", Integer.class,
                    id.toString(), "%仅属于本子申请的具体理由%")).isZero();
        }
    }

    @Test
    void childReturnAllowsOnlyRootCorrectionAndKeepsAllOriginalRoundSnapshotsOnResubmission() {
        var leaf = child(key(), schema("total"), "user:manager");
        var middle = parent(leaf, schema("amount"), Map.of("total", "amount"), false, false, nativeId(leaf), false);
        var definition = parent(middle, schema("rootAmount"), Map.of("amount", "rootAmount"), false, false, nativeId(middle), false);
        var application = create(definition, Map.of("rootAmount", "6"));
        as("alice", () -> applications.submit(application.id(), 1));
        var parentCall = onlyCall(application.id()); var childCall = onlyCall(parentCall.childApplicationId());
        var task = tasks.createTaskQuery().processInstanceId(childCall.childProcessInstanceId()).singleResult();
        as("manager", () -> actions.action(task.getId(), "RETURN", "补充原申请材料", null, 2L));
        for (var id : List.of(application.id(), parentCall.childApplicationId(), childCall.childApplicationId())) {
            assertThat(repository.findById("demo", id).orElseThrow().status()).isEqualTo(ApplicationStatus.RETURNED);
        }
        failure(() -> as("alice", () -> applications.revise(childCall.childApplicationId(), 3, "不能拆开修改", Map.of("total", "10"))),
                "SUBPROCESS_PARENT_CONTROL_REQUIRED");
        var frozen = rounds.findByRound("demo", application.id(), 1).orElseThrow();
        as("alice", () -> applications.revise(application.id(), 3, "补正根申请", Map.of("rootAmount", "10")));
        as("alice", () -> applications.submit(application.id(), 4));
        var fresh = calls.findByParentRound("demo", application.id(), 2);
        assertThat(fresh).hasSize(1);
        assertThat(fresh.get(0).childApplicationId()).isNotEqualTo(parentCall.childApplicationId());
        assertThat(rounds.findByRound("demo", application.id(), 1).orElseThrow()).isEqualTo(frozen);
        assertThat(repository.findById("demo", childCall.childApplicationId()).orElseThrow().payload()).containsEntry("total", "6");
    }

    @Test
    void childApprovalWinningWhileWithdrawalWaitsReturnsConflictAndKeepsBothApproved() throws Exception {
        var leaf = child(key(), schema("total"), "user:manager");
        var definition = parent(leaf, schema("amount"), Map.of("total", "amount"), false, false, nativeId(leaf), false);
        var application = create(definition, Map.of("amount", "3"));
        as("alice", () -> applications.submit(application.id(), 1));
        var call = onlyCall(application.id());
        var task = tasks.createTaskQuery().processInstanceId(call.childProcessInstanceId()).singleResult();
        var withdrawalReady = new CountDownLatch(1); var approvalCommitted = new CountDownLatch(1);
        ApplicationRepository target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(repository);
        doAnswer(invocation -> {
            if (actors.actor().userId().equals("alice")) {
                withdrawalReady.countDown();
                if (!approvalCommitted.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Approval did not commit");
            }
            return invocation.callRealMethod();
        }).when(target).lockById("demo", application.id());
        var executor = Executors.newSingleThreadExecutor();
        try {
            var withdrawal = executor.submit(() -> {
                try { as("alice", () -> applications.withdraw(application.id(), 2, "在取锁前等待")); return "ACCEPTED"; }
                catch (DomainException conflict) { return conflict.code(); }
            });
            assertThat(withdrawalReady.await(15, TimeUnit.SECONDS)).isTrue();
            as("manager", () -> actions.action(task.getId(), "APPROVE", "真实最终批准", null, 2L));
            approvalCommitted.countDown();
            assertThat(withdrawal.get(15, TimeUnit.SECONDS)).isEqualTo("CONCURRENCY_CONFLICT");
        } finally {
            approvalCommitted.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            doCallRealMethod().when(target).lockById("demo", application.id());
        }
        assertThat(repository.findById("demo", application.id()).orElseThrow().status()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(repository.findById("demo", call.childApplicationId()).orElseThrow().status()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='WITHDRAW'", Integer.class,
                application.id().toString())).isZero();
    }

    @Test
    void cancellationNotificationFailureRollsBackTheWholeRejectedTreeAndPreservesTheSourceTask() {
        var leaf = child(key(), schema("total"), "user:manager");
        var application = create(parallelParent(leaf), Map.of("amount", "4"));
        as("alice", () -> applications.submit(application.id(), 1));
        var branches = calls.findByParentRound("demo", application.id(), 1);
        var source = branches.get(0); var sibling = branches.get(1);
        var task = tasks.createTaskQuery().processInstanceId(source.childProcessInstanceId()).singleResult();
        int oldAudit = count("audit_event"), oldInbox = count("notification_inbox"), oldOutbox = count("webhook_delivery");
        InboxRepository target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(inbox);
        doAnswer(invocation -> {
            var result = invocation.callRealMethod(); InboxMessage message = invocation.getArgument(1);
            if (message.applicationId().equals(sibling.childApplicationId()) && message.kind() == InboxMessage.Kind.APPLICATION_CANCELLED) {
                throw new IllegalStateException("Injected cancellation notification failure");
            }
            return result;
        }).when(target).append(anyString(), any());
        try {
            assertThatThrownBy(() -> as("manager", () -> actions.action(task.getId(), "REJECT", "不应留下未提交的理由", null, 2L)))
                    .hasStackTraceContaining("Injected cancellation notification failure");
        } finally { doCallRealMethod().when(target).append(anyString(), any()); }
        for (var id : List.of(application.id(), source.childApplicationId(), sibling.childApplicationId())) {
            var kept = repository.findById("demo", id).orElseThrow();
            assertThat(kept.status()).isEqualTo(ApplicationStatus.IN_APPROVAL); assertThat(kept.version()).isEqualTo(2);
            assertThat(rounds.findByRound("demo", id, 1).orElseThrow().status()).isEqualTo(SubmissionRound.Status.IN_APPROVAL);
        }
        assertThat(tasks.createTaskQuery().taskId(task.getId()).count()).isEqualTo(1);
        assertThat(tasks.getTaskComments(task.getId())).isEmpty();
        assertThat(count("audit_event")).isEqualTo(oldAudit); assertThat(count("notification_inbox")).isEqualTo(oldInbox);
        assertThat(count("webhook_delivery")).isEqualTo(oldOutbox);
        as("manager", () -> actions.action(task.getId(), "REJECT", "重新明确驳回", null, 2L));
        assertThat(repository.findById("demo", application.id()).orElseThrow().status()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(repository.findById("demo", sibling.childApplicationId()).orElseThrow().status()).isEqualTo(ApplicationStatus.CANCELLED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='REJECT'", Integer.class,
                source.childApplicationId().toString())).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = NodeType.class, names = {"TIMER_WAIT", "EVENT_WAIT"})
    void cancelledChildRejectsTheOriginalDelayedSignalWithoutChangingTheHumanOpinion(NodeType waitType) {
        String contract = "cancel-event-" + UUID.randomUUID();
        if (waitType == NodeType.EVENT_WAIT) contracts.publish(ADMIN, contract, 0, "原等待事件", "erp", "GoodsAccepted", "固定来源");
        var leaf = waitingChild(waitType, contract);
        var definition = parent(leaf, schema("amount"), Map.of("total", "amount"), false, false, nativeId(leaf), false);
        var application = create(definition, Map.of("amount", "8"));
        as("alice", () -> applications.submit(application.id(), 1));
        var call = onlyCall(application.id());
        var task = tasks.createTaskQuery().processInstanceId(call.childProcessInstanceId()).singleResult();
        as("manager", () -> actions.action(task.getId(), "APPROVE", "真实人工意见需保留", null, 2L));
        var job = jobs.createTimerJobQuery().processInstanceId(call.childProcessInstanceId()).singleResult();
        var subscription = runtime.createEventSubscriptionQuery().processInstanceId(call.childProcessInstanceId()).singleResult();
        as("alice", () -> applications.withdraw(application.id(), 2, "父流程撤回取消原等待"));
        var frozen = rounds.findByRound("demo", call.childApplicationId(), 1).orElseThrow();
        int auditCount = count("audit_event"), inboxCount = count("notification_inbox"), outboxCount = count("webhook_delivery");
        if (waitType == NodeType.TIMER_WAIT) {
            assertThat(job).isNotNull();
            assertThat(timers.advance(job.getId(), job.getDuedate().toInstant())).isFalse();
        } else {
            assertThat(subscription).isNotNull();
            var command = new EventWaitService.Command("demo", "erp", "GoodsAccepted", 1, UUID.randomUUID().toString(),
                    call.childApplicationId(), 1, subscription.getId(), contract, 1);
            assertThat(events.advance(command)).isEqualTo(EventWaitService.Outcome.STALE);
        }
        assertThat(repository.findById("demo", call.childApplicationId()).orElseThrow().status()).isEqualTo(ApplicationStatus.CANCELLED);
        assertThat(frozen.status()).isEqualTo(SubmissionRound.Status.CANCELLED);
        assertThat(rounds.findByRound("demo", call.childApplicationId(), 1).orElseThrow()).isEqualTo(frozen);
        assertThat(jobs.createTimerJobQuery().processInstanceId(call.childProcessInstanceId()).count()).isZero();
        assertThat(runtime.createEventSubscriptionQuery().processInstanceId(call.childProcessInstanceId()).count()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='APPROVE'", Integer.class,
                call.childApplicationId().toString())).isEqualTo(1);
        assertThat(count("audit_event")).isEqualTo(auditCount); assertThat(count("notification_inbox")).isEqualTo(inboxCount);
        assertThat(count("webhook_delivery")).isEqualTo(outboxCount);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rootStopWinningBeforeChildApprovalLeavesNoApprovedOrOrphanedChild(boolean administrator) throws Exception {
        var leaf = child(key(), schema("total"), "user:manager");
        var definition = parent(leaf, schema("amount"), Map.of("total", "amount"), false, false, nativeId(leaf), false);
        var application = create(definition, Map.of("amount", "9"));
        as("alice", () -> applications.submit(application.id(), 1));
        var call = onlyCall(application.id());
        var task = tasks.createTaskQuery().processInstanceId(call.childProcessInstanceId()).singleResult();
        var approvalReady = new CountDownLatch(1); var withdrawalCommitted = new CountDownLatch(1);
        ApplicationRepository target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(repository);
        doAnswer(invocation -> {
            if (actors.actor().userId().equals("manager")) {
                approvalReady.countDown();
                if (!withdrawalCommitted.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Withdrawal did not commit");
            }
            return invocation.callRealMethod();
        }).when(target).lockById("demo", application.id());
        var executor = Executors.newSingleThreadExecutor();
        try {
            var approval = executor.submit(() -> {
                try { as("manager", () -> actions.action(task.getId(), "APPROVE", "失去当前轮次后不得批准", null, 2L)); return "ACCEPTED"; }
                catch (DomainException conflict) { return conflict.code(); }
            });
            assertThat(approvalReady.await(15, TimeUnit.SECONDS)).isTrue();
            if (administrator) terminate(application.id(), 2, "明确先终止");
            else as("alice", () -> applications.withdraw(application.id(), 2, "明确先撤回"));
            withdrawalCommitted.countDown();
            assertThat(approval.get(15, TimeUnit.SECONDS)).isEqualTo(SubprocessExecutionLocks.PARENT_CHANGED);
        } finally {
            withdrawalCommitted.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            doCallRealMethod().when(target).lockById("demo", application.id());
        }
        assertThat(repository.findById("demo", application.id()).orElseThrow().status())
                .isEqualTo(administrator ? ApplicationStatus.CANCELLED : ApplicationStatus.WITHDRAWN);
        assertThat(repository.findById("demo", call.childApplicationId()).orElseThrow().status()).isEqualTo(ApplicationStatus.CANCELLED);
        assertThat(runtime.createProcessInstanceQuery().processInstanceId(call.childProcessInstanceId()).count()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='APPROVE'", Integer.class,
                call.childApplicationId().toString())).isZero();
    }

    @Test
    void rootPauseFreezesEveryNestedParallelChildAndResumesItsOriginalCalendarDeadline() {
        var calendar = calendar(); setTime("2026-09-25T16:30:00Z");
        var leaf = child(key(), schema("total"), Map.of("assigneeRule", "user:manager", "deadlineCalendarId", calendar.id().toString(),
                "deadlineCalendarRevision", "1", "deadlineWorkingMinutes", "60"));
        var middle = parent(leaf, schema("total"), Map.of("total", "total"), false, false, nativeId(leaf), false);
        var application = create(parallelParent(middle), Map.of("amount", "10"));
        as("alice", () -> applications.submit(application.id(), 1));
        var branches = calls.findByParentRound("demo", application.id(), 1);
        var allCalls = new ArrayList<>(branches);
        for (var branch : branches) allCalls.add(onlyCall(branch.childApplicationId()));
        var originalTasks = allCalls.stream().flatMap(call -> tasks.createTaskQuery().processInstanceId(call.childProcessInstanceId()).list().stream()).toList();
        assertThat(originalTasks).hasSize(2);
        var rootRound = rounds.findByRound("demo", application.id(), 1).orElseThrow();
        setTime("2026-09-25T16:30:12.345Z");
        control(application.id(), true, 2, "只留在根申请的维护原因");
        assertThat(runtime.createProcessInstanceQuery().processInstanceId(rootRound.processInstanceId()).singleResult().isSuspended()).isTrue();
        for (var call : allCalls) {
            var instance = runtime.createProcessInstanceQuery().processInstanceId(call.childProcessInstanceId()).singleResult();
            assertThat(instance.isSuspended()).as("child %s is natively paused", call.childApplicationId()).isTrue();
            assertThat(repository.findById("demo", call.childApplicationId()).orElseThrow().version()).isEqualTo(3);
            assertThat(rounds.findByRound("demo", call.childApplicationId(), 1).orElseThrow().status()).isEqualTo(SubmissionRound.Status.IN_APPROVAL);
            actors.set(ADMIN);
            try {
                var view = instances.read(call.childApplicationId(), 1);
                assertThat(view.state()).isEqualTo(InstanceControlService.State.PAUSED);
                assertThat(view.pausedAt()).isEqualTo(Instant.parse("2026-09-25T16:30:12.345Z"));
                assertThat(view.canPause()).isFalse(); assertThat(view.canResume()).isFalse();
                failure(() -> instances.resume(call.childApplicationId(), 1, new InstanceControlService.Input(3L, "不得单独恢复")), "SUBPROCESS_PARENT_CONTROL_REQUIRED");
            } finally { actors.clear(); }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND payload_json LIKE ?", Integer.class,
                    call.childApplicationId().toString(), "%只留在根申请的维护原因%")).isZero();
        }
        for (var original : originalTasks) {
            var paused = tasks.createTaskQuery().taskId(original.getId()).singleResult();
            assertThat(paused.isSuspended()).isTrue(); assertThat(paused.getDueDate()).isEqualTo(original.getDueDate());
            assertThat(tasks.getVariableLocal(paused.getId(), InstanceControlService.PAUSED_DUE_AT)).isEqualTo(original.getDueDate().toInstant().toString());
            assertThat(reminders.remind(paused.getId(), original.getDueDate().toInstant().plusSeconds(1))).isFalse();
        }
        calendars.update(calendar.revise("改变当前日历不改变原修订", new CalendarRules("UTC", Map.of(DayOfWeek.WEDNESDAY,
                List.of(new CalendarRules.Period("12:00", "13:00"))), List.of()), 1, "admin", Instant.now()), 1);
        setTime("2026-09-29T16:30:20.125Z"); control(application.id(), false, 3, "接续每个子任务原时限");
        for (var call : allCalls) {
            assertThat(runtime.createProcessInstanceQuery().processInstanceId(call.childProcessInstanceId()).singleResult().isSuspended()).isFalse();
            assertThat(repository.findById("demo", call.childApplicationId()).orElseThrow().version()).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action IN ('INSTANCE_PAUSE','INSTANCE_RESUME') AND actor_id='system:subprocess'",
                    Integer.class, call.childApplicationId().toString())).isEqualTo(2);
        }
        for (var original : originalTasks) {
            var resumed = tasks.createTaskQuery().taskId(original.getId()).singleResult();
            assertThat(resumed.isSuspended()).isFalse();
            assertThat(resumed.getDueDate().toInstant()).isEqualTo(Instant.parse("2026-09-30T09:30:07.780Z"));
            assertThat(tasks.getVariableLocal(resumed.getId(), FlowableTaskDeadlineListener.CALENDAR_REVISION)).isEqualTo(1L);
            assertThat(tasks.getVariableLocal(resumed.getId(), FlowableTaskDeadlineListener.STARTED_AT)).isEqualTo("2026-09-25T16:30:00Z");
            assertThat(tasks.getVariableLocal(resumed.getId(), InstanceControlService.PAUSED_DUE_AT)).isNull();
        }
        assertThat(rounds.findByRound("demo", application.id(), 1).orElseThrow()).isEqualTo(rootRound);
    }

    @Test
    void treePauseKeepsAlreadyApprovedSiblingAndDoesNotInventADueDateForTheRemainingTask() {
        var leaf = child(key(), schema("total"), "user:manager");
        var application = create(parallelParent(leaf), Map.of("amount", "11"));
        as("alice", () -> applications.submit(application.id(), 1));
        var branches = calls.findByParentRound("demo", application.id(), 1);
        var decided = branches.get(0); var waiting = branches.get(1);
        var finishedTask = tasks.createTaskQuery().processInstanceId(decided.childProcessInstanceId()).singleResult();
        as("manager", () -> actions.action(finishedTask.getId(), "APPROVE", "保留实际批准", null, 2L));
        var approved = repository.findById("demo", decided.childApplicationId()).orElseThrow();
        var oldRound = rounds.findByRound("demo", approved.id(), 1).orElseThrow();
        long rootVersion = repository.findById("demo", application.id()).orElseThrow().version();
        control(application.id(), true, rootVersion, "只冻结待办分支");
        var remaining = tasks.createTaskQuery().processInstanceId(waiting.childProcessInstanceId()).singleResult();
        assertThat(remaining.isSuspended()).isTrue(); assertThat(remaining.getDueDate()).isNull();
        control(application.id(), false, rootVersion + 1, "恢复待办分支");
        assertThat(tasks.createTaskQuery().taskId(remaining.getId()).singleResult().getDueDate()).isNull();
        assertThat(rounds.findByRound("demo", approved.id(), 1).orElseThrow()).isEqualTo(oldRound);
        assertThat(repository.findById("demo", approved.id()).orElseThrow().version()).isEqualTo(approved.version());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action IN ('INSTANCE_PAUSE','INSTANCE_RESUME')", Integer.class,
                approved.id().toString())).isZero();
        as("manager", () -> actions.action(remaining.getId(), "APPROVE", "接续剩余批准", null, 4L));
        assertThat(repository.findById("demo", application.id()).orElseThrow().status()).isEqualTo(ApplicationStatus.APPROVED);
    }

    @ParameterizedTest
    @EnumSource(value = InboxMessage.Kind.class, names = {"APPLICATION_PAUSED", "APPLICATION_RESUMED"})
    void aLateChildNotificationFailureRollsBackTheWholeTreeControlAndOriginalDeadlines(InboxMessage.Kind kind) {
        setTime("2026-09-23T09:00:00Z"); var calendar = calendar();
        var leaf = child(key(), schema("total"), Map.of("assigneeRule", "user:manager", "deadlineCalendarId", calendar.id().toString(),
                "deadlineCalendarRevision", "1", "deadlineWorkingMinutes", "60"));
        var application = create(parallelParent(leaf), Map.of("amount", "12"));
        as("alice", () -> applications.submit(application.id(), 1));
        var branches = calls.findByParentRound("demo", application.id(), 1);
        boolean pause = kind == InboxMessage.Kind.APPLICATION_PAUSED;
        setTime("2026-09-23T09:15:00Z");
        if (!pause) { control(application.id(), true, 2, "保存整棵树的原暂停"); setTime("2026-09-24T09:00:00Z"); }
        long version = pause ? 2 : 3;
        int oldAudit = count("audit_event"), oldInbox = count("notification_inbox"), oldOutbox = count("webhook_delivery");
        var targetChild = branches.get(1).childApplicationId();
        InboxRepository target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(inbox);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod(); InboxMessage message = invocation.getArgument(1);
            if (message.kind() == kind && message.applicationId().equals(targetChild)) throw new IllegalStateException("Injected child runtime notification failure");
            return result;
        }).when(target).append(anyString(), any());
        try {
            assertThatThrownBy(() -> control(application.id(), pause, version, "必须整笔回滚"))
                    .hasStackTraceContaining("Injected child runtime notification failure");
        } finally { doCallRealMethod().when(target).append(anyString(), any()); }
        var ids = new ArrayList<>(branches.stream().map(SubprocessCall::childApplicationId).toList()); ids.add(application.id());
        for (var id : ids) {
            var current = repository.findById("demo", id).orElseThrow();
            assertThat(current.version()).isEqualTo(version); assertThat(current.status()).isEqualTo(ApplicationStatus.IN_APPROVAL);
            var round = rounds.findByRound("demo", id, 1).orElseThrow();
            var instance = runtime.createProcessInstanceQuery().processInstanceId(round.processInstanceId()).singleResult();
            assertThat(instance.isSuspended()).isEqualTo(!pause);
            assertThat(runtime.getVariable(instance.getId(), InstanceControlService.PAUSED_AT))
                    .isEqualTo(pause ? null : "2026-09-23T09:15:00Z");
            for (var task : tasks.createTaskQuery().processInstanceId(instance.getId()).list()) {
                assertThat(task.isSuspended()).isEqualTo(!pause);
                assertThat(task.getDueDate().toInstant()).isEqualTo(Instant.parse("2026-09-23T10:00:00Z"));
                assertThat(tasks.getVariableLocal(task.getId(), InstanceControlService.PAUSED_DUE_AT))
                        .isEqualTo(pause ? null : "2026-09-23T10:00:00Z");
            }
        }
        assertThat(count("audit_event")).isEqualTo(oldAudit); assertThat(count("notification_inbox")).isEqualTo(oldInbox);
        assertThat(count("webhook_delivery")).isEqualTo(oldOutbox);
        control(application.id(), pause, version, "重试同一明确操作");
        for (var id : ids) assertThat(repository.findById("demo", id).orElseThrow().version()).isEqualTo(version + 1);
        if (pause) { setTime("2026-09-24T09:00:00Z"); control(application.id(), false, 3, "恢复实际暂停"); }
        for (var branch : branches) assertThat(tasks.createTaskQuery().processInstanceId(branch.childProcessInstanceId()).singleResult().getDueDate().toInstant())
                .isEqualTo(Instant.parse("2026-09-24T09:45:00Z"));
    }

    @Test
    void treeControlKeepsAnOverdueChildsOriginalDueAndAlreadyDeliveredReminder() {
        setTime("2026-09-23T09:00:00Z"); var calendar = calendar();
        var leaf = child(key(), schema("total"), Map.of("assigneeRule", "user:manager", "deadlineCalendarId", calendar.id().toString(),
                "deadlineCalendarRevision", "1", "deadlineWorkingMinutes", "60"));
        var application = create(parent(leaf, schema("amount"), Map.of("total", "amount"), false, false), Map.of("amount", "13"));
        as("alice", () -> applications.submit(application.id(), 1));
        var call = onlyCall(application.id()); var task = tasks.createTaskQuery().processInstanceId(call.childProcessInstanceId()).singleResult();
        assertThat(reminders.remind(task.getId(), task.getDueDate().toInstant())).isTrue();
        var marker = tasks.getVariableLocal(task.getId(), FlowableTaskDeadlineListener.REMINDED_AT);
        setTime("2026-09-23T10:15:00Z"); control(application.id(), true, 2, "暂停已超时的原任务");
        assertThat(reminders.candidates(Instant.parse("2026-09-24T09:00:00Z"), null)).noneMatch(candidate -> candidate.taskId().equals(task.getId()));
        setTime("2026-09-24T09:00:00Z"); control(application.id(), false, 3, "保持原超时事实");
        assertThat(tasks.createTaskQuery().taskId(task.getId()).singleResult().getDueDate()).isEqualTo(task.getDueDate());
        assertThat(tasks.getVariableLocal(task.getId(), FlowableTaskDeadlineListener.REMINDED_AT)).isEqualTo(marker);
        assertThat(reminders.remind(task.getId(), Instant.parse("2026-09-24T09:00:00Z"))).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE application_id=? AND kind='TASK_OVERDUE'", Integer.class,
                call.childApplicationId().toString())).isEqualTo(1);
    }

    @Test
    void missingChildPauseEvidenceCannotPartiallyResumeTheRoot() {
        var leaf = child(key(), schema("total"), "user:manager");
        var application = create(parent(leaf, schema("amount"), Map.of("total", "amount"), false, false), Map.of("amount", "14"));
        as("alice", () -> applications.submit(application.id(), 1)); var call = onlyCall(application.id());
        control(application.id(), true, 2, "可核验的原暂停");
        Object pausedAt = runtime.getVariable(call.childProcessInstanceId(), InstanceControlService.PAUSED_AT);
        // 原生 API 禁止修改暂停实例变量；直接损坏合成夹具的一列，验证缺失证据必须拒绝，再恢复原值。
        assertThat(jdbc.update("UPDATE ACT_RU_VARIABLE SET TEXT_=NULL WHERE PROC_INST_ID_=? AND EXECUTION_ID_=? AND NAME_=?",
                call.childProcessInstanceId(), call.childProcessInstanceId(), InstanceControlService.PAUSED_AT)).isEqualTo(1);
        int oldAudit = count("audit_event"), oldInbox = count("notification_inbox"), oldOutbox = count("webhook_delivery");
        failure(() -> control(application.id(), false, 3, "缺少子暂停依据必须拒绝"), "CONCURRENCY_CONFLICT");
        for (String instance : List.of(call.parentProcessInstanceId(), call.childProcessInstanceId())) {
            assertThat(runtime.createProcessInstanceQuery().processInstanceId(instance).singleResult().isSuspended()).isTrue();
        }
        assertThat(repository.findById("demo", application.id()).orElseThrow().version()).isEqualTo(3);
        assertThat(repository.findById("demo", call.childApplicationId()).orElseThrow().version()).isEqualTo(3);
        assertThat(count("audit_event")).isEqualTo(oldAudit); assertThat(count("notification_inbox")).isEqualTo(oldInbox);
        assertThat(count("webhook_delivery")).isEqualTo(oldOutbox);
        assertThat(jdbc.update("UPDATE ACT_RU_VARIABLE SET TEXT_=? WHERE PROC_INST_ID_=? AND EXECUTION_ID_=? AND NAME_=?",
                pausedAt, call.childProcessInstanceId(), call.childProcessInstanceId(), InstanceControlService.PAUSED_AT)).isEqualTo(1);
        control(application.id(), false, 3, "恢复已知的合成夹具后再操作");
    }

    @Test
    void aPauseWinningWhileAuthorizedChildApprovalWaitsBlocksThatOldDecision() throws Exception {
        var leaf = child(key(), schema("total"), "user:manager");
        var application = create(parent(leaf, schema("amount"), Map.of("total", "amount"), false, false), Map.of("amount", "15"));
        as("alice", () -> applications.submit(application.id(), 1)); var call = onlyCall(application.id());
        var task = tasks.createTaskQuery().processInstanceId(call.childProcessInstanceId()).singleResult();
        var approvalReady = new CountDownLatch(1); var pauseCommitted = new CountDownLatch(1);
        ApplicationRepository target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(repository);
        doAnswer(invocation -> {
            if (actors.actor().userId().equals("manager")) {
                approvalReady.countDown();
                if (!pauseCommitted.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Pause did not commit");
            }
            return invocation.callRealMethod();
        }).when(target).lockById("demo", application.id());
        var executor = Executors.newSingleThreadExecutor();
        try {
            var approval = executor.submit(() -> {
                try { as("manager", () -> actions.action(task.getId(), "APPROVE", "暂停期间不能接续旧决定", null, 2L)); return "ACCEPTED"; }
                catch (DomainException conflict) { return conflict.code(); }
            });
            assertThat(approvalReady.await(15, TimeUnit.SECONDS)).isTrue();
            control(application.id(), true, 2, "在根锁下先完成暂停"); pauseCommitted.countDown();
            assertThat(approval.get(15, TimeUnit.SECONDS)).isEqualTo(SubprocessExecutionLocks.PARENT_CHANGED);
        } finally {
            pauseCommitted.countDown(); executor.shutdownNow(); assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            doCallRealMethod().when(target).lockById("demo", application.id());
        }
        assertThat(tasks.createTaskQuery().taskId(task.getId()).singleResult().isSuspended()).isTrue();
        assertThat(repository.findById("demo", call.childApplicationId()).orElseThrow().version()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='APPROVE'", Integer.class,
                call.childApplicationId().toString())).isZero();
        control(application.id(), false, 3, "恢复同一子任务");
    }

    @Test
    void originalHttpPauseReplayControlsEachChildOnceAndCannotPauseThemAgainAfterResume() throws Exception {
        var leaf = child(key(), schema("total"), "user:manager");
        var application = create(parallelParent(leaf), Map.of("amount", "16"));
        as("alice", () -> applications.submit(application.id(), 1));
        var branches = calls.findByParentRound("demo", application.id(), 1);
        String token = "Bearer " + auth.login("demo", "admin", "demo").token();
        String key = UUID.randomUUID().toString(), body = json.write(Map.of("expectedVersion", 2, "reason", "原号暂停整棵树"));
        String path = "/api/v1/applications/" + application.id() + "/rounds/1/runtime/pause";
        var first = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .header("Authorization", token).header("Idempotency-Key", key).contentType("application/json").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString();
        control(application.id(), false, 3, "先完成明确恢复");
        var replay = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .header("Authorization", token).header("Idempotency-Key", key).contentType("application/json").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Idempotency-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
        for (var call : branches) {
            assertThat(runtime.createProcessInstanceQuery().processInstanceId(call.childProcessInstanceId()).singleResult().isSuspended()).isFalse();
            assertThat(repository.findById("demo", call.childApplicationId()).orElseThrow().version()).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='INSTANCE_PAUSE'", Integer.class,
                    call.childApplicationId().toString())).isEqualTo(1);
        }
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path.replace(application.id().toString(), branches.get(0).childApplicationId().toString()))
                .header("Authorization", token).header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                .content(json.write(Map.of("expectedVersion", 4, "reason", "不能从子调用控制"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnprocessableEntity())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("code").value("SUBPROCESS_PARENT_CONTROL_REQUIRED"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void namedTerminationCancelsUnfinishedDescendantsAndKeepsCompletedBranchOpinions(boolean paused) {
        var leaf = child(key(), schema("total"), "user:manager");
        var middle = parent(leaf, schema("total"), Map.of("total", "total"), false, false, nativeId(leaf), false);
        var definition = parallelParent(middle);
        var root = create(definition, Map.of("amount", "37"));
        as("alice", () -> applications.submit(root.id(), 1));
        var branches = calls.findByParentRound("demo", root.id(), 1);
        var completed = branches.get(0); var active = branches.get(1);
        var completedLeaf = onlyCall(completed.childApplicationId());
        var activeLeaf = onlyCall(active.childApplicationId());
        var task = tasks.createTaskQuery().processInstanceId(completedLeaf.childProcessInstanceId()).singleResult();
        as("manager", () -> actions.action(task.getId(), "APPROVE", "已经真实批准的意见", null, 2L));
        var completedRound = rounds.findByRound("demo", completed.childApplicationId(), 1).orElseThrow();
        var completedLeafRound = rounds.findByRound("demo", completedLeaf.childApplicationId(), 1).orElseThrow();
        long completedVersion = repository.findById("demo", completed.childApplicationId()).orElseThrow().version();
        var remaining = List.of(root.id(), active.childApplicationId(), activeLeaf.childApplicationId());
        if (paused) control(root.id(), true, repository.findById("demo", root.id()).orElseThrow().version(), "原树暂停");
        failure(() -> terminate(active.childApplicationId(), repository.findById("demo", active.childApplicationId()).orElseThrow().version(), "禁止子流程单独终止"),
                "SUBPROCESS_PARENT_CONTROL_REQUIRED");
        String privateReason = "根申请独有的敏感终止原因";
        var result = terminate(root.id(), repository.findById("demo", root.id()).orElseThrow().version(), privateReason);
        assertThat(result.state()).isEqualTo(InstanceControlService.State.ENDED);
        assertThat(result.canTerminate()).isFalse();
        for (var id : remaining) {
            var application = repository.findById("demo", id).orElseThrow();
            var round = rounds.findByRound("demo", id, 1).orElseThrow();
            assertThat(application.status()).isEqualTo(ApplicationStatus.CANCELLED);
            assertThat(round.status()).isEqualTo(SubmissionRound.Status.CANCELLED);
            assertThat(runtime.createProcessInstanceQuery().processInstanceId(round.processInstanceId()).count()).isZero();
            var history = processEngine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceId(round.processInstanceId()).singleResult();
            assertThat(history.getEndTime()).isNotNull();
            assertThat(history.getDeleteReason()).isNotBlank().doesNotContain(privateReason);
            if (id.equals(root.id())) {
                assertThat(round.reason()).isEqualTo(privateReason); assertThat(round.completedBy()).isEqualTo("admin");
            } else {
                assertThat(round.reason()).doesNotContain(privateReason); assertThat(round.completedBy()).isEqualTo(SubprocessStartService.SYSTEM_ACTOR);
            }
        }
        assertThat(rounds.findByRound("demo", completed.childApplicationId(), 1).orElseThrow()).isEqualTo(completedRound);
        assertThat(rounds.findByRound("demo", completedLeaf.childApplicationId(), 1).orElseThrow()).isEqualTo(completedLeafRound);
        assertThat(repository.findById("demo", completed.childApplicationId()).orElseThrow().version()).isEqualTo(completedVersion);
        assertThat(tasks.getTaskComments(task.getId())).singleElement().extracting(org.flowable.engine.task.Comment::getFullMessage).isEqualTo("已经真实批准的意见");
        assertThat(jdbc.queryForList("SELECT recipient_id FROM notification_inbox WHERE application_id=? AND kind='APPLICATION_CANCELLED' ORDER BY recipient_id",
                String.class, activeLeaf.childApplicationId().toString())).containsExactly("alice", "manager");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedDescendantTerminationNotificationRestoresTheOriginalWholeTree(boolean paused) {
        var leaf = child(key(), schema("total"), "user:manager");
        var root = create(parallelParent(leaf), Map.of("amount", "19"));
        as("alice", () -> applications.submit(root.id(), 1));
        var branches = calls.findByParentRound("demo", root.id(), 1);
        if (paused) control(root.id(), true, 2, "保持原暂停状态");
        long version = paused ? 3 : 2;
        var ids = List.of(root.id(), branches.get(0).childApplicationId(), branches.get(1).childApplicationId());
        var originalRounds = ids.stream().map(id -> rounds.findByRound("demo", id, 1).orElseThrow()).toList();
        int auditCount = count("audit_event"), inboxCount = count("notification_inbox"), outboxCount = count("webhook_delivery");
        InboxRepository target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(inbox);
        doAnswer(invocation -> {
            var result = invocation.callRealMethod(); InboxMessage message = invocation.getArgument(1);
            if (message.applicationId().equals(branches.get(1).childApplicationId()) && message.kind() == InboxMessage.Kind.APPLICATION_CANCELLED) {
                throw new IllegalStateException("Injected termination notification failure");
            }
            return result;
        }).when(target).append(anyString(), any());
        try {
            assertThatThrownBy(() -> terminate(root.id(), version, "不能留下未提交的终止"))
                    .hasStackTraceContaining("Injected termination notification failure");
        } finally { doCallRealMethod().when(target).append(anyString(), any()); }
        for (var round : originalRounds) {
            assertThat(rounds.findByRound("demo", round.applicationId(), 1).orElseThrow()).isEqualTo(round);
            var application = repository.findById("demo", round.applicationId()).orElseThrow();
            assertThat(application.status()).isEqualTo(ApplicationStatus.IN_APPROVAL);
            assertThat(application.version()).isEqualTo(version);
            assertThat(runtime.createProcessInstanceQuery().processInstanceId(round.processInstanceId()).singleResult().isSuspended()).isEqualTo(paused);
        }
        assertThat(count("audit_event")).isEqualTo(auditCount);
        assertThat(count("notification_inbox")).isEqualTo(inboxCount);
        assertThat(count("webhook_delivery")).isEqualTo(outboxCount);
        terminate(root.id(), version, "依赖恢复后重新确认");
    }

    private InstanceControlService.View terminate(UUID application, long version, String reason) {
        actors.set(ADMIN);
        try { return instances.terminate(application, 1, new InstanceControlService.Input(version, reason)); }
        finally { actors.clear(); }
    }

    private BusinessCalendar calendar() {
        var week = new EnumMap<DayOfWeek, List<CalendarRules.Period>>(DayOfWeek.class);
        for (var day : List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)) {
            week.put(day, List.of(new CalendarRules.Period("09:00", "17:00")));
        }
        var rules = new CalendarRules("UTC", week, List.of(new CalendarRules.DayOverride(LocalDate.parse("2026-09-28"), List.of(), "假日")));
        var calendar = BusinessCalendar.create("demo", "sub-pause-" + UUID.randomUUID(), "子任务原日历", rules, "admin", Instant.now());
        calendars.create(calendar); return calendar;
    }

    private void setTime(String value) { processEngine.getProcessEngineConfiguration().getClock().setCurrentTime(Date.from(Instant.parse(value))); }

    private InstanceControlService.View control(UUID application, boolean pause, long version, String reason) {
        actors.set(ADMIN);
        try {
            var input = new InstanceControlService.Input(version, reason);
            return pause ? instances.pause(application, 1, input) : instances.resume(application, 1, input);
        } finally { actors.clear(); }
    }

    private DefinitionDraft child(String key, FormSchema schema, String assignee) {
        return child(key, schema, Map.of("assigneeRule", assignee));
    }

    private DefinitionDraft child(String key, FormSchema schema, Map<String, String> properties) {
        var graph = new Graph(List.of(new Node("start", "发起", NodeType.START, Map.of()),
                new Node("review", "独立人工核对", NodeType.USER_TASK, properties),
                new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("a", "start", "review", ""), new Edge("b", "review", "end", "")));
        var draft = definitions.create(tenant, key, "固定子流程", graph, schema);
        return definitions.publish(new Actor(tenant, "admin", Set.of("ADMIN")), draft.id(), draft.revision(), "实际启动验收");
    }

    private DefinitionDraft waitingChild(NodeType waitType, String contract) {
        Map<String, String> settings = waitType == NodeType.TIMER_WAIT ? Map.of("timerDelaySeconds", "1")
                : Map.of("eventContractKey", contract, "eventContractVersion", "1");
        var graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("review", "真实人工审批", NodeType.USER_TASK, Map.of("assigneeRule", "user:manager")),
                new Node("wait", "等待原始信号", waitType, settings), new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("a", "start", "review", ""), new Edge("b", "review", "wait", ""), new Edge("c", "wait", "end", "")));
        var draft = definitions.create("demo", key(), "子流程等待", graph, schema("total"));
        return definitions.publish(ADMIN, draft.id(), draft.revision(), "等待结束仍需保留真实审批依据");
    }

    private DefinitionDraft parallelParent(DefinitionDraft child) {
        var policy = new SubprocessPolicy(child.key(), child.version(), Map.of("total", "amount"));
        var graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("fork", "并行", NodeType.PARALLEL_GATEWAY, Map.of()),
                new Node("one", "第一条独立子审批", NodeType.SUB_PROCESS, policy.properties()),
                new Node("two", "第二条独立子审批", NodeType.SUB_PROCESS, policy.properties()),
                new Node("join", "全部实际完成", NodeType.PARALLEL_GATEWAY, Map.of()), new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("a", "start", "fork", ""), new Edge("b", "fork", "one", ""), new Edge("c", "fork", "two", ""),
                        new Edge("d", "one", "join", ""), new Edge("e", "two", "join", ""), new Edge("f", "join", "end", "")));
        var draft = DefinitionDraft.create(UUID.randomUUID(), "demo", key(), "并行子审批", graph, schema("amount"));
        drafts.save(draft); draft.publish(0, 1); drafts.save(draft);
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="http://agentflow.io/test">
                  <process id="%s" isExecutable="true"><startEvent id="start"/><parallelGateway id="fork"/>
                    <callActivity id="one" calledElement="%s" flowable:calledElementType="id" flowable:fallbackToDefaultTenant="false"/>
                    <callActivity id="two" calledElement="%s" flowable:calledElementType="id" flowable:fallbackToDefaultTenant="false"/>
                    <parallelGateway id="join"/><endEvent id="end"/>
                    <sequenceFlow id="a" sourceRef="start" targetRef="fork"/><sequenceFlow id="b" sourceRef="fork" targetRef="one"/>
                    <sequenceFlow id="c" sourceRef="fork" targetRef="two"/><sequenceFlow id="d" sourceRef="one" targetRef="join"/>
                    <sequenceFlow id="e" sourceRef="two" targetRef="join"/><sequenceFlow id="f" sourceRef="join" targetRef="end"/>
                  </process>
                </definitions>
                """.formatted(draft.key(), nativeId(child), nativeId(child));
        engine.createDeployment().tenantId("demo").addString(draft.key() + ".bpmn20.xml", xml).deploy();
        return draft;
    }

    private DefinitionDraft parentWithTimer(DefinitionDraft child, boolean before) {
        var policy = new SubprocessPolicy(child.key(), child.version(), Map.of("total", "amount"));
        var first = before ? "wait" : "call"; var second = before ? "call" : "wait";
        var graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("call", "实际子审批", NodeType.SUB_PROCESS, policy.properties()),
                new Node("wait", "根流程等待", NodeType.TIMER_WAIT, Map.of("timerDelaySeconds", "1")),
                new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("a", "start", first, ""), new Edge("b", first, second, ""), new Edge("c", second, "end", "")));
        var draft = DefinitionDraft.create(UUID.randomUUID(), "demo", key(), "子流程与根等待", graph, schema("amount"));
        drafts.save(draft); draft.publish(0, 1); drafts.save(draft);
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="http://agentflow.io/test">
                  <process id="%s" isExecutable="true"><startEvent id="start"/>
                    <callActivity id="call" calledElement="%s" flowable:calledElementType="id" flowable:fallbackToDefaultTenant="false"/>
                    <intermediateCatchEvent id="wait"><timerEventDefinition><timeDuration>PT1S</timeDuration></timerEventDefinition></intermediateCatchEvent>
                    <endEvent id="end"/><sequenceFlow id="a" sourceRef="start" targetRef="%s"/>
                    <sequenceFlow id="b" sourceRef="%s" targetRef="%s"/><sequenceFlow id="c" sourceRef="%s" targetRef="end"/>
                  </process>
                </definitions>
                """.formatted(draft.key(), nativeId(child), first, first, second, second);
        engine.createDeployment().tenantId("demo").addString(draft.key() + ".bpmn20.xml", xml).deploy();
        return draft;
    }

    private DefinitionDraft parent(DefinitionDraft child, FormSchema schema, Map<String, String> inputs, boolean before, boolean inherit) {
        return parent(child, schema, inputs, before, inherit, nativeId(child));
    }

    /** 内部固定夹具绕过尚未开放的发布入口，运行时仍必须核对实际引擎标识和平台定义。 */
    private DefinitionDraft parent(DefinitionDraft child, FormSchema schema, Map<String, String> inputs, boolean before, boolean inherit, String calledId) {
        return parent(child, schema, inputs, before, inherit, calledId, true);
    }

    private DefinitionDraft parent(DefinitionDraft child, FormSchema schema, Map<String, String> inputs, boolean before, boolean inherit, String calledId, boolean after) {
        var policy = new SubprocessPolicy(child.key(), child.version(), inputs);
        var draft = DefinitionDraft.create(UUID.randomUUID(), tenant, key(), "父流程", parentGraph(policy, before, after), schema);
        drafts.save(draft); draft.publish(0, 1); drafts.save(draft);
        String preceding = before ? "<userTask id=\"before\" name=\"父流程先审\" flowable:assignee=\"manager\"/><sequenceFlow id=\"toCall\" sourceRef=\"before\" targetRef=\"call\"/>" : "";
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="http://agentflow.io/test">
                  <process id="%s" isExecutable="true"><startEvent id="start"/>
                    <sequenceFlow id="a" sourceRef="start" targetRef="%s"/>%s
                    <callActivity id="call" calledElement="%s" flowable:calledElementType="id" flowable:inheritVariables="%s" flowable:fallbackToDefaultTenant="false"/>
                    %s<endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(draft.key(), before ? "before" : "call", preceding, calledId, inherit, after
                    ? "<sequenceFlow id=\"b\" sourceRef=\"call\" targetRef=\"after\"/><userTask id=\"after\" name=\"父流程后审\" flowable:assignee=\"finance\"/><sequenceFlow id=\"c\" sourceRef=\"after\" targetRef=\"end\"/>"
                    : "<sequenceFlow id=\"b\" sourceRef=\"call\" targetRef=\"end\"/>");
        engine.createDeployment().tenantId(tenant).addString(draft.key() + ".bpmn20.xml", xml).deploy();
        return draft;
    }

    private Graph parentGraph(SubprocessPolicy policy, boolean before) {
        return parentGraph(policy, before, true);
    }

    private Graph parentGraph(SubprocessPolicy policy, boolean before, boolean after) {
        var nodes = new ArrayList<>(List.of(new Node("start", "发起", NodeType.START, Map.of()),
                new Node("call", "独立材料核对", NodeType.SUB_PROCESS, policy.properties()),
                new Node("end", "结束", NodeType.END, Map.of())));
        var edges = new ArrayList<>(List.of(new Edge("a", "start", before ? "before" : "call", ""),
                new Edge("b", "call", after ? "after" : "end", "")));
        if (after) { nodes.add(new Node("after", "父流程后审", NodeType.USER_TASK, Map.of("assigneeRule", "user:finance"))); edges.add(new Edge("c", "after", "end", "")); }
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
