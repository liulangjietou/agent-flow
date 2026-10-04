package io.agentflow.approval;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import io.agentflow.expense.*;
import io.agentflow.finance.*;
import io.agentflow.form.FieldVisibility;
import io.agentflow.form.FormSchema;
import io.agentflow.organization.OrganizationService;
import io.agentflow.organization.OrganizationUnit;
import io.agentflow.organization.OrganizationRepository;
import io.agentflow.organization.ApprovalProxyService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.flowable.engine.TaskService;
import org.flowable.engine.RuntimeService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 真实预检、提交事务和原生 ALL 验证项目责任；财务端口明确使用合成来源。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:expense-project-runtime;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "agentflow.auth.demo-enabled=true",
        "agentflow.finance-gateway.enabled=true", "agentflow.timers.enabled=false", "agentflow.sla.reminders-enabled=false",
        "agentflow.vouchers.preparation-worker-enabled=false", "agentflow.vouchers.worker-enabled=false", "agentflow.payments.worker-enabled=false",
        "agentflow.payments.request-worker-enabled=false", "agentflow.invoices.verification-worker-enabled=false",
        "agentflow.expenses.precheck-worker-enabled=false", "agentflow.budgets.worker-enabled=false",
        "agentflow.expenses.budget-review-worker-enabled=false", "agentflow.expenses.settlement-worker-enabled=false", "agentflow.expenses.archive-worker-enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExpenseProjectApprovalIntegrationTest {
    private static final Actor ADMIN = new Actor("demo", "admin", Set.of("ADMIN", "PROCESS_ADMIN"));
    private static final ExpenseSubprocessGatewayFixture GATEWAY = new ExpenseSubprocessGatewayFixture();
    private final Map<String, String> tokens = new ConcurrentHashMap<>();
    private UUID entity, appointment, manager, finance;
    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @Autowired AuthService auth;
    @Autowired CurrentActor actors;
    @Autowired OrganizationService organization;
    @Autowired DefinitionApplicationService definitions;
    @Autowired ApplicationRepository applications;
    @Autowired ExpenseReportRepository reports;
    @Autowired ExpensePrecheckWorker precheckWorker;
    @Autowired JdbcExpensePrecheckRepository prechecks;
    @Autowired FinanceGatewayConfiguration financeConfiguration;
    @Autowired JdbcBudgetOccupationRepository occupations;
    @Autowired JdbcBudgetOperationRepository operations;
    @Autowired BudgetOperationService budgetExecution;
    @Autowired BudgetSystemPort budgetPort;
    @Autowired TaskService tasks;
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcExpenseProjectApprovalRepository projects;
    @Autowired OrganizationRepository organizationRecords;
    @Autowired ApprovalProxyService proxies;
    @Autowired RuntimeService runtime;

    @DynamicPropertySource static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("agentflow.finance-gateway.tenants.demo.endpoint", GATEWAY::endpoint);
        registry.add("agentflow.finance-gateway.tenants.demo.allow-unauthenticated-loopback", () -> true);
        registry.add("agentflow.attachments.directory", () -> "/fyoung/tmp/agentflow-project-approval-http-fixtures");
    }
    @BeforeEach void organizationAndSource() {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM organization_directory WHERE tenant_id='demo'", Integer.class) == 0) organization.initialize(ADMIN);
        entity = organization.createUnit(ADMIN, OrganizationUnit.Kind.LEGAL_ENTITY, "合成项目法人", null, null, true).id();
        var department = organization.createUnit(ADMIN, OrganizationUnit.Kind.DEPARTMENT, "合成部门", entity, null, true);
        var position = organization.createUnit(ADMIN, OrganizationUnit.Kind.POSITION, "合成岗位", entity, null, true);
        var selected = organization.createAppointment(ADMIN, person("alice", true), department.id(), position.id(), true);
        appointment = selected.id(); manager = person("manager", true); finance = person("finance", true); person("bob", true);
        var superior = organization.createAppointment(ADMIN, manager, department.id(), position.id(), true);
        organization.setSupervisor(ADMIN, appointment, superior.id(), selected.revision());
        GATEWAY.use(json, entity, "76543210987654321000"); source("manager", "bob");
    }
    @AfterEach void clearActorAndCheckGateway() { actors.clear(); assertThat(GATEWAY.failure()).isNull(); }
    @AfterAll static void stopOwnedServer() { GATEWAY.close(); }

    @Test void missingTrustedOwnerBlocksPrecheckWithoutStartingBudgetOrApproval() throws Exception {
        source(null, "bob"); var report = draft(definition(false), true); var checked = check(report);
        assertThat(checked.status()).isEqualTo(ExpensePrecheckJob.Status.BLOCKED);
        assertThat(checked.result().findings()).extracting(ExpensePrecheckJob.Finding::code).contains("EXPENSE_PROJECT_OWNER_UNAVAILABLE");
        assertThat(current(report).rounds()).isEmpty(); assertThat(occupations.find("demo", report.id())).isEmpty();
    }

    @Test void actualPrecheckCarriesEverySelectedProjectAndOriginalOwner() throws Exception {
        var report = draft(definition(false), true); var checked = check(report);
        assertThat(checked.status()).isEqualTo(ExpensePrecheckJob.Status.READY);
        assertThat(checked.result().evidence().projectOwners()).isNotNull();
        assertThat(checked.result().evidence().projectOwners().projects()).extracting(FinanceCatalog.Project::ownerSubject).containsExactly("manager", "bob");
    }

    @Test void oldDefinitionCannotSilentlyStartNewProjectExpensesWithoutTheDedicatedReview() throws Exception {
        var report = draft(definition(false), true); var checked = check(report);
        var response = raw(path(report) + "/submit", "alice", submitInput(report, checked.input().id()));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(422);
        assertThat(application(report).status()).isEqualTo(ApplicationStatus.DRAFT);
        assertThat(current(report).rounds()).isEmpty(); assertThat(projects.find("demo", report.id(), 1)).isEmpty();
        assertThat(occupations.find("demo", report.id())).isEmpty();
    }

    @Test void everyDistinctOwnerMustApproveAndFixedMembershipCannotBeReduced() throws Exception {
        var report = draft(definition(true), true); submit(report); approve(report, "business", "manager");
        var pending = projectTasks(report); assertThat(pending).extracting(Task::getAssignee).containsExactlyInAnyOrder("manager", "bob");
        var managerTask = pending.stream().filter(task -> "manager".equals(task.getAssignee())).findFirst().orElseThrow();
        var bobTask = pending.stream().filter(task -> "bob".equals(task.getAssignee())).findFirst().orElseThrow();
        var view = request("/api/v1/tasks/" + managerTask.getId() + "/countersign-members", "manager", 200);
        assertThat(view.path("canChange").asBoolean()).isFalse(); assertThat(view.path("canAdd").asBoolean()).isFalse();
        assertThat(view.path("issue").asText()).isEqualTo("EXPENSE_PROJECT_MEMBERS_FIXED");
        long version = application(report).version();
        send("/api/v1/tasks/" + managerTask.getId() + "/countersign-changes", "manager",
                Map.of("action", "REMOVE", "targetTaskId", bobTask.getId(), "reason", "不能移除项目责任", "expectedVersion", version), 409);
        send("/api/v1/tasks/" + managerTask.getId() + "/countersign-changes", "manager",
                Map.of("action", "ADD", "targetUser", "admin", "reason", "不能增加替代责任", "expectedVersion", version), 409);
        assertThat(application(report).version()).isEqualTo(version);
        approve(report, "projects", "manager"); assertThat(projectTasks(report)).extracting(Task::getAssignee).containsExactly("bob");
        approve(report, "projects", "bob"); assertThat(pending(report)).extracting(Task::getTaskDefinitionKey).containsExactly("receipt");
        assertThat(projects.find("demo", report.id(), 1).orElseThrow().owners().subjects()).containsExactly("bob", "manager");
    }

    @Test void twoProjectsWithOneOwnerRetainBothMappingsWithoutDuplicateResponsibilities() throws Exception {
        source("manager", "manager"); var report = draft(definition(true), true); submit(report); approve(report, "business", "manager");
        assertThat(projectTasks(report)).extracting(Task::getAssignee).containsExactly("manager");
        assertThat(projects.find("demo", report.id(), 1).orElseThrow().owners().projects()).hasSize(2);
        approve(report, "projects", "manager"); assertThat(pending(report)).extracting(Task::getTaskDefinitionKey).containsExactly("receipt");
    }

    @Test void noProjectUsesTheFalseBranchWithoutCreatingAnEmptyOrInventedResponsibility() throws Exception {
        var report = draft(definition(true), false); submit(report); approve(report, "business", "manager");
        assertThat(projectTasks(report)).isEmpty(); assertThat(pending(report)).extracting(Task::getTaskDefinitionKey).containsExactly("receipt");
        assertThat(projects.find("demo", report.id(), 1).orElseThrow().hasProjects()).isFalse();
        assertThat(application(report).payload()).containsEntry("hasProjectAllocation", false);
    }

    @Test void originalOwnersAndSelectedSupervisorSurviveDirectoryChangesBeforeActivation() throws Exception {
        source("alice", "bob"); var report = draft(definition(true), true); submit(report);
        var original = projects.find("demo", report.id(), 1).orElseThrow();
        var selected = organizationRecords.appointment("demo", appointment).orElseThrow();
        var replacement = organization.createAppointment(ADMIN, person("bob", true), selected.departmentId(), selected.positionId(), true);
        organization.setSupervisor(ADMIN, appointment, replacement.id(), selected.revision());
        source("finance", "finance"); approve(report, "business", "manager");
        assertThat(projectTasks(report)).extracting(Task::getAssignee).containsExactlyInAnyOrder("manager", "bob");
        assertThat(projects.find("demo", report.id(), 1)).contains(original);
        assertThat(original.owners().projects()).extracting(FinanceCatalog.Project::ownerSubject).containsExactly("alice", "bob");
        var frozen = selection(report);
        assertThat(frozen.initiator().appointmentId()).isEqualTo(appointment);
        assertThat(frozen.node("projects").originalSubjects()).containsExactly("alice", "bob");
        assertThat(frozen.node("projects").escalation().replacementSubject()).isEqualTo("manager");
        assertThat(frozen.node("projects").escalation().supervisorAppointmentId()).isEqualTo(selected.supervisorAppointmentId());
        action(report, assigned(report, "manager"), "manager", "DELEGATE", "alice", 422);
        approve(report, "projects", "manager"); approve(report, "projects", "bob");
        assertThat(pending(report)).extracting(Task::getTaskDefinitionKey).containsExactly("receipt");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void missingOrInactiveSelectedSuperiorRollsBackTheWholeSubmission(boolean inactive) throws Exception {
        source("alice", "bob");
        var selected = organizationRecords.appointment("demo", appointment).orElseThrow();
        if (inactive) {
            var superior = organizationRecords.appointment("demo", selected.supervisorAppointmentId()).orElseThrow();
            organization.updateAppointment(ADMIN, superior.id(), false, superior.revision());
        } else organization.setSupervisor(ADMIN, appointment, null, selected.revision());
        var report = draft(definition(true), true); var checked = check(report); var before = current(report).state();
        var input = submitInput(report, checked.input().id());
        assertThat(send(path(report)+"/submit", "alice", input, 422).path("code").asText()).isEqualTo("APPROVER_NOT_FOUND");
        assertThat(current(report).state()).isEqualTo(before); assertThat(application(report).version()).isEqualTo(input.get("applicationVersion"));
        assertThat(projects.find("demo", report.id(), 1)).isEmpty(); assertThat(pending(report)).isEmpty();
        assertThat(occupations.find("demo", report.id())).isEmpty();
    }

    @Test void unregisteredOwnerCannotBeDroppedFromAnOtherwiseValidSource() throws Exception {
        source("manager", "missing-project-owner"); var report = draft(definition(true), true); var checked = check(report);
        assertThat(checked.status()).isEqualTo(ExpensePrecheckJob.Status.READY);
        assertThat(send(path(report)+"/submit", "alice", submitInput(report, checked.input().id()), 422).path("code").asText())
                .isEqualTo("EXPENSE_PROJECT_OWNER_UNAVAILABLE");
        assertThat(application(report).status()).isEqualTo(ApplicationStatus.DRAFT); assertThat(current(report).rounds()).isEmpty();
        assertThat(projects.find("demo", report.id(), 1)).isEmpty(); assertThat(occupations.find("demo", report.id())).isEmpty();
    }

    @Test void separationConflictCannotSilentlyRemoveARequiredProjectOwner() throws Exception {
        var report = draft(definition(true, Map.of("differentApproverFrom", "business")), true); submit(report);
        var task = pending(report).get(0); var app = application(report); var budget = occupations.find("demo", report.id()).orElseThrow();
        var original = projects.find("demo", report.id(), 1).orElseThrow();
        assertThat(action(report, task, "manager", "APPROVE", null, 422).path("code").asText()).isEqualTo("APPROVAL_RESPONSIBILITY_CONFLICT");
        assertThat(application(report).version()).isEqualTo(app.version()); assertThat(pending(report)).extracting(Task::getId).containsExactly(task.getId());
        assertThat(projectTasks(report)).isEmpty(); assertThat(occupations.find("demo", report.id())).contains(budget);
        assertThat(projects.find("demo", report.id(), 1)).contains(original);
        assertThat(auditCount(report, "APPROVE")).isZero();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void eligibilityLossBlocksActivationOrTheOriginalMemberWithoutReplacingThem(boolean activated) throws Exception {
        var report = draft(definition(true), true); submit(report);
        if (activated) approve(report, "business", "manager");
        var original = projects.find("demo", report.id(), 1).orElseThrow();
        var bob = organizationRecords.personBySubject("demo", "bob").orElseThrow();
        organization.updatePerson(ADMIN, bob.id(), bob.displayName(), true, false, bob.revision());
        try {
            long version = application(report).version();
            if (activated) {
                action(report, assigned(report, "bob"), "bob", "APPROVE", null, 403);
                assertThat(projectTasks(report)).extracting(Task::getAssignee).containsExactlyInAnyOrder("manager", "bob");
            } else assertThat(action(report, pending(report).get(0), "manager", "APPROVE", null, 422).path("code").asText())
                    .isEqualTo("EXPENSE_APPROVER_UNAVAILABLE");
            assertThat(application(report).version()).isEqualTo(version); assertThat(projects.find("demo", report.id(), 1)).contains(original);
        } finally {
            var current = organizationRecords.person("demo", bob.id()).orElseThrow();
            organization.updatePerson(ADMIN, bob.id(), bob.displayName(), true, true, current.revision());
        }
        if (!activated) approve(report, "business", "manager");
        approve(report, "projects", "manager"); approve(report, "projects", "bob");
    }

    @Test void delegatedAssistanceReturnsToOriginalOwnerAndCannotTransferOrConsumeAnotherMember() throws Exception {
        person("admin", true); var report = draft(definition(true), true); submit(report); approve(report, "business", "manager");
        var task = assigned(report, "manager");
        for (String action : List.of("TRANSFER", "RELEASE", "CLAIM"))
            assertThat(action(report, task, "manager", action, "admin", 409).path("code").asText()).isEqualTo("COUNTERSIGN_ASSIGNMENT_FIXED");
        action(report, task, "manager", "DELEGATE", "admin", 200);
        action(report, task, "admin", "APPROVE", null, 409);
        action(report, task, "admin", "RESOLVE", null, 200);
        assertThat(assigned(report, "manager").getId()).isEqualTo(task.getId());
        var input = Map.of("action", "APPROVE", "expectedVersion", application(report).version()); String key = UUID.randomUUID().toString();
        var first = raw(actionPath(task), "manager", input, key); assertThat(first.getStatus()).isEqualTo(200);
        var replay = raw(actionPath(task), "manager", input, key);
        assertThat(replay.getStatus()).isEqualTo(200); assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(projectTasks(report)).extracting(Task::getAssignee).containsExactly("bob");
        approve(report, "projects", "bob");
    }

    @Test void oneProxyDecisionKeepsTheOtherOriginalResponsibilityAndRecordsItsPrincipal() throws Exception {
        var substitute = person("admin", true); var definition = definition(true); var report = draft(definition, true);
        submit(report); approve(report, "business", "manager");
        var first = proxies.create(ADMIN, definition.id(), manager, substitute, Instant.now(), Instant.now().plusSeconds(3600), "合成项目代理");
        var second = proxies.create(ADMIN, definition.id(), person("bob", true), substitute, Instant.now(), Instant.now().plusSeconds(3600), "合成另一项目代理");
        var managerTask = assigned(report, "manager");
        send(actionPath(managerTask), "admin", Map.of("action", "APPROVE", "proxyId", first.id(), "expectedVersion", application(report).version()), 200);
        assertThat(projectTasks(report)).extracting(Task::getAssignee).containsExactly("bob");
        var bobTask = assigned(report, "bob");
        for (UUID proxy : List.of(first.id(), second.id()))
            send(actionPath(bobTask), "admin", Map.of("action", "APPROVE", "proxyId", proxy, "expectedVersion", application(report).version()), 403);
        var audit = json.read(jdbc.queryForObject("SELECT payload_json FROM audit_event WHERE tenant_id='demo' AND aggregate_id=? AND action='APPROVE'",
                String.class, managerTask.getId()), JsonNode.class);
        assertThat(audit.path("actor").asText()).isEqualTo("admin"); assertThat(audit.at("/proxyUse/principal").asText()).isEqualTo("manager");
        approve(report, "projects", "bob");
    }

    @Test void pausedAndTerminatedInstanceRetainsTheOriginalProjectSource() throws Exception {
        var report = draft(definition(true), true); submit(report); approve(report, "business", "manager");
        var original = projects.find("demo", report.id(), 1).orElseThrow(); var task = assigned(report, "manager");
        String control = "/api/v1/applications/"+report.applicationId()+"/rounds/1/runtime";
        send(control+"/pause", "admin", Map.of("expectedVersion", application(report).version(), "reason", "合成暂停"), 200);
        action(report, task, "manager", "APPROVE", null, 404);
        send(control+"/resume", "admin", Map.of("expectedVersion", application(report).version(), "reason", "合成恢复"), 200);
        assertThat(assigned(report, "manager").getId()).isEqualTo(task.getId()); approve(report, "projects", "manager");
        send(control+"/pause", "admin", Map.of("expectedVersion", application(report).version(), "reason", "合成暂停待结束"), 200);
        send(control+"/terminate", "admin", Map.of("expectedVersion", application(report).version(), "reason", "合成结束原轮次"), 200); budgets(report);
        assertThat(application(report).status()).isEqualTo(ApplicationStatus.CANCELLED); assertThat(pending(report)).isEmpty();
        assertThat(projects.find("demo", report.id(), 1)).contains(original);
        assertThat(occupations.find("demo", report.id()).orElseThrow().status()).isEqualTo(BudgetOccupation.Status.RELEASED);
    }

    @Test void threeRoundsRefreshOwnersOnlyOnResubmissionAndRetainWithdrawnAndReturnedEvidence() throws Exception {
        var report = draft(definition(true), true); submit(report); approve(report, "business", "manager");
        var first = projects.find("demo", report.id(), 1).orElseThrow();
        send(path(report)+"/withdraw", "alice", lifecycle(report), 200); budgets(report); source("bob", "bob");
        submit(report); approve(report, "business", "manager");
        var second = projects.find("demo", report.id(), 2).orElseThrow();
        assertThat(projectTasks(report)).extracting(Task::getAssignee).containsExactly("bob");
        action(report, assigned(report, "bob"), "bob", "RETURN", null, 200); budgets(report); source("alice", "bob");
        submit(report); approve(report, "business", "manager");
        assertThat(application(report).roundNo()).isEqualTo(3);
        assertThat(projectTasks(report)).extracting(Task::getAssignee).containsExactlyInAnyOrder("manager", "bob");
        assertThat(projects.find("demo", report.id(), 1)).contains(first); assertThat(projects.find("demo", report.id(), 2)).contains(second);
        assertThat(projects.find("demo", report.id(), 3).orElseThrow().owners().subjects()).containsExactly("alice", "bob");
        assertThat(current(report).rounds()).hasSize(3);
    }

    @Test void reductionToZeroPreservesOriginalProjectRoutingAndRequiresBudgetReconfirmation() throws Exception {
        var report = draft(definition(true), true); submit(report); approve(report, "business", "manager");
        approve(report, "projects", "manager"); approve(report, "projects", "bob");
        var original = projects.find("demo", report.id(), 1).orElseThrow(); var receipt = pending(report).get(0);
        action(report, receipt, "finance", "APPROVE", null, 422);
        send(path(report)+"/tasks/"+receipt.getId()+"/receive", "finance", lifecycle(report), 200); approve(report, "receipt", "finance");
        var task = pending(report).get(0);
        send(path(report)+"/tasks/"+task.getId()+"/reduce", "finance", Map.of("applicationVersion", application(report).version(),
                "financialVersion", current(report).version(), "lines", List.of(Map.of("lineNo", 1, "approvedGross", "0.00", "approvedTax", "0.00")),
                "reasonCode", "INELIGIBLE_COST", "comment", "合成全额核减"), 200);
        assertThat(application(report).payload()).containsEntry("hasProjectAllocation", true).containsEntry("amount", "0.00");
        assertThat(projects.find("demo", report.id(), 1)).contains(original);
        action(report, task, "finance", "APPROVE", null, 422); budgets(report); approve(report, "finance", "finance");
        assertThat(application(report).status()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(projects.find("demo", report.id(), 1)).contains(original);
    }

    @Test void failureAfterEngineStartRollsBackProjectSourceAndRecoversWithTheOriginalSubmissionKey() throws Exception {
        var report = draft(definition(true), true); var input = submitInput(report, check(report).input().id());
        var original = current(report).state(); String key = UUID.randomUUID().toString();
        jdbc.execute("ALTER TABLE budget_occupation ADD CONSTRAINT project_fixture_budget_failure CHECK(report_id<>'"+report.id()+"')");
        try {
            assertThatThrownBy(() -> raw(path(report)+"/submit", "alice", input, key)).isInstanceOf(jakarta.servlet.ServletException.class)
                    .hasCauseInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(projects.find("demo", report.id(), 1)).isEmpty(); assertThat(pending(report)).isEmpty();
            assertThat(current(report).state()).isEqualTo(original); assertThat(application(report).version()).isEqualTo(input.get("applicationVersion"));
            assertThat(occupations.find("demo", report.id())).isEmpty();
        } finally { jdbc.execute("ALTER TABLE budget_occupation DROP CONSTRAINT project_fixture_budget_failure"); }
        var first = raw(path(report)+"/submit", "alice", input, key); assertThat(first.getStatus()).as(first.getContentAsString()).isEqualTo(200);
        var replay = raw(path(report)+"/submit", "alice", input, key);
        assertThat(replay.getStatus()).isEqualTo(200); assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_project_approval WHERE report_id=?", Integer.class, report.id().toString())).isEqualTo(1);
        budgets(report); approve(report, "business", "manager"); assertThat(projectTasks(report)).hasSize(2);
    }

    @Test void concurrentMembersKeepOneDecisionAndLoserCanRetryWithTheNewVersion() throws Exception {
        var report = draft(definition(true), true); submit(report); approve(report, "business", "manager");
        var managerTask = assigned(report, "manager"); var bobTask = assigned(report, "bob");
        var input = Map.of("action", "APPROVE", "expectedVersion", application(report).version());
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2); var gate = new java.util.concurrent.CyclicBarrier(2);
        try {
            var first = pool.submit(() -> { gate.await(); return raw(actionPath(managerTask), "manager", input).getStatus(); });
            var second = pool.submit(() -> { gate.await(); return raw(actionPath(bobTask), "bob", input).getStatus(); });
            assertThat(List.of(first.get(20, java.util.concurrent.TimeUnit.SECONDS), second.get(20, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        } finally { pool.shutdownNow(); }
        assertThat(projectTasks(report)).hasSize(1); var remaining = projectTasks(report).get(0);
        action(report, remaining, remaining.getAssignee(), "APPROVE", null, 200);
        assertThat(pending(report)).extracting(Task::getTaskDefinitionKey).containsExactly("receipt");
        assertThat(auditCount(report, "APPROVE")).isEqualTo(3);
    }

    @Test void oldNoProjectDefinitionKeepsItsFourFieldPayloadWithExplicitEmptyNewSource() throws Exception {
        var report = draft(definition(false), false); submit(report); approve(report, "business", "manager");
        assertThat(application(report).payload()).containsOnlyKeys("expenseDetails", "amount", "currency", "overPolicy");
        var original = projects.find("demo", report.id(), 1).orElseThrow(); assertThat(original.hasProjects()).isFalse(); assertThat(original.nodeId()).isNull();
        assertThat(pending(report)).extracting(Task::getTaskDefinitionKey).containsExactly("receipt");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void legacyReadyEvidenceWithoutOwnerFieldIsAcceptedOnlyByTheOldNoProjectContract(boolean controlled) throws Exception {
        var report = draft(definition(controlled), false); var checked = check(report);
        // 还原升级前的 READY 编码；固定包升级与进程恢复另由运行验收覆盖。
        var legacy = (com.fasterxml.jackson.databind.node.ObjectNode) json.read(json.write(checked), JsonNode.class);
        ((com.fasterxml.jackson.databind.node.ObjectNode) legacy.at("/result/evidence")).remove("projectOwners");
        jdbc.update("UPDATE expense_precheck_job SET state_json=? WHERE tenant_id='demo' AND id=?", json.write(legacy), checked.input().id().toString());
        jdbc.update("UPDATE expense_precheck_revision SET state_json=? WHERE tenant_id='demo' AND job_id=? AND version=?",
                json.write(legacy), checked.input().id().toString(), checked.version());
        var response = send(path(report)+"/submit", "alice", submitInput(report, checked.input().id()), controlled ? 422 : 200);
        assertThat(projects.find("demo", report.id(), 1)).isEmpty();
        if (controlled) {
            assertThat(response.path("code").asText()).isEqualTo("EXPENSE_PROJECT_PRECHECK_REQUIRED");
            assertThat(current(report).rounds()).isEmpty(); assertThat(occupations.find("demo", report.id())).isEmpty();
        } else {
            budgets(report); approve(report, "business", "manager");
            assertThat(application(report).payload()).containsOnlyKeys("expenseDetails", "amount", "currency", "overPolicy");
            assertThat(pending(report)).extracting(Task::getTaskDefinitionKey).containsExactly("receipt");
        }
    }

    private void source(String first, String second) {
        GATEWAY.projects("project-catalog-v1", List.of(new FinanceCatalog.Project(entity, "A", "项目A", first), new FinanceCatalog.Project(entity, "B", "项目B", second)));
    }
    private DefinitionDraft definition(boolean projectReview) {
        return definition(projectReview, Map.of());
    }
    private DefinitionDraft definition(boolean projectReview, Map<String, String> projectProperties) {
        var nodes = new ArrayList<>(List.of(new Node("start", "开始", NodeType.START, Map.of(ExpenseSelfApprovalPolicy.PROPERTY, ExpenseSelfApprovalPolicy.ESCALATE_SUPERVISOR,
                ExpenseDuplicateApprovalPolicy.PROPERTY, ExpenseDuplicateApprovalPolicy.AUTO_PASS_ADJACENT)),
                new Node("business", "业务审批", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_"+manager)),
                new Node("receipt", "纸件签收", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_"+finance, "expenseStage", "RECEIPT")),
                new Node("finance", "财务复核", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_"+finance, "expenseStage", "FINANCE_REVIEW")),
                new Node("end", "结束", NodeType.END, Map.of())));
        var edges = new ArrayList<>(List.of(new Edge("a", "start", "business", ""), new Edge("b", "business", projectReview ? "project_gate" : "receipt", ""),
                new Edge("c", "receipt", "finance", ""), new Edge("d", "finance", "end", "")));
        var visibility = new HashMap<>(Map.of("business", FieldVisibility.READ_ONLY, "receipt", FieldVisibility.READ_ONLY, "finance", FieldVisibility.READ_ONLY));
        if (projectReview) {
            nodes.add(new Node("project_gate", "项目分摊", NodeType.EXCLUSIVE_GATEWAY, Map.of()));
            var properties = new HashMap<>(Map.of("assigneeRule", "expense:projectOwners", "expenseStage", "PROJECT_REVIEW", "approvalMode", "ALL"));
            properties.putAll(projectProperties); nodes.add(new Node("projects", "项目负责人", NodeType.USER_TASK, properties));
            edges.add(new Edge("with_projects", "project_gate", "projects", "hasProjectAllocation == true"));
            edges.add(new Edge("no_projects", "project_gate", "receipt", "", true)); edges.add(new Edge("project_done", "projects", "receipt", ""));
            visibility.put("projects", FieldVisibility.READ_ONLY);
        }
        var fields = new ArrayList<>(List.of(new FormSchema.Field("expenseDetails", "费用明细", FormSchema.FieldType.TEXT, true,
                null, null, null, null, null, null, null, true, visibility), field("amount", FormSchema.FieldType.NUMBER),
                field("currency", FormSchema.FieldType.TEXT), field("overPolicy", FormSchema.FieldType.BOOLEAN)));
        if (projectReview) fields.add(field("hasProjectAllocation", FormSchema.FieldType.BOOLEAN));
        var draft = definitions.create("demo", "project-review-"+UUID.randomUUID(), "合成项目流程", new Graph(nodes, edges, 2), new FormSchema(2, fields), null);
        return definitions.publish(ADMIN, draft.id(), draft.revision(), "合成验收");
    }
    private ExpenseReport draft(DefinitionDraft definition, boolean allocated) throws Exception {
        var allocations = allocated ? List.of(new CostAllocation("IT", "A", money("40")), new CostAllocation("IT", "B", money("60"))) : List.of(new CostAllocation("IT", null, money("100")));
        var line = new ExpenseLine(1, "OFFICE", LocalDate.now(), null, "SH", BigDecimal.ONE, ExpenseLine.Unit.ITEM, money("100"), money("6"),
                List.of(), null, allocations, "合成项目费用", null);
        var created = send("/api/v1/expense-reports", "alice", Map.of("businessNo", "PROJECT-"+UUID.randomUUID(), "processKey", definition.key(),
                "definitionVersion", definition.version(), "content", new ExpenseContent(entity, ExpenseContent.Type.DAILY, "合成项目报销", List.of(line), List.of())), 201);
        return reports.find("demo", UUID.fromString(created.path("id").asText())).orElseThrow();
    }
    private ExpensePrecheckJob check(ExpenseReport report) throws Exception {
        var response = send(path(report)+"/precheck", "alice", Map.of("applicationVersion", application(report).version(), "financialVersion", current(report).version(),
                "initiatorAppointmentId", appointment, "accountingDate", LocalDate.now(), "targetDigest", financeConfiguration.destination("demo").orElseThrow().digest("demo")), 202);
        precheckWorker.poll(); return prechecks.find("demo", UUID.fromString(response.path("id").asText())).orElseThrow();
    }
    private void submit(ExpenseReport report) throws Exception {
        var checked = check(report); assertThat(checked.status()).as(json.write(checked.result())).isEqualTo(ExpensePrecheckJob.Status.READY);
        send(path(report)+"/submit", "alice", submitInput(report, checked.input().id()), 200); budgets(report);
    }
    private void budgets(ExpenseReport report) throws InterruptedException {
        UUID id = occupations.find("demo", report.id()).orElseThrow().pendingOperationId(); if (id == null) return;
        var operation = operations.find("demo", id).orElseThrow(); long wait = java.time.Duration.between(Instant.now(), operation.nextAttemptAt()).toMillis();
        if (wait >= 0) Thread.sleep(wait + 1);
        var claimed = budgetExecution.claim("demo", id, Instant.now()); var input = claimed.input();
        var result = claimed.status() == BudgetOperation.Status.QUERYING ? budgetPort.query(input.targetDigest(), input.command()) : budgetPort.execute(input.targetDigest(), input.command());
        budgetExecution.finish(claimed, result, Instant.now());
    }
    private void approve(ExpenseReport report, String node, String user) throws Exception {
        var task = pending(report).stream().filter(value -> value.getTaskDefinitionKey().equals(node) && (value.getAssignee() == null || user.equals(value.getAssignee()))).findFirst().orElseThrow();
        send("/api/v1/tasks/"+task.getId()+"/actions", user, Map.of("action", "APPROVE", "comment", "合成审批", "expectedVersion", application(report).version()), 200);
    }
    private List<Task> pending(ExpenseReport report) { return tasks.createTaskQuery().processVariableValueEquals("applicationId", report.applicationId().toString()).list(); }
    private Task assigned(ExpenseReport report, String user) { return projectTasks(report).stream().filter(task -> user.equals(task.getAssignee())).findFirst().orElseThrow(); }
    private JsonNode action(ExpenseReport report, Task task, String user, String action, String target, int expected) throws Exception {
        var input = new HashMap<String, Object>(Map.of("action", action, "comment", "合成原责任操作", "expectedVersion", application(report).version()));
        if (target != null) input.put("targetUser", target); return send(actionPath(task), user, input, expected);
    }
    private static String actionPath(Task task) { return "/api/v1/tasks/"+task.getId()+"/actions"; }
    private Map<String, Object> lifecycle(ExpenseReport report) { return Map.of("applicationVersion", application(report).version(), "financialVersion", current(report).version(), "comment", "合成轮次操作"); }
    private int auditCount(ExpenseReport report, String action) { return jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE tenant_id='demo' AND application_id=? AND action=?", Integer.class, report.applicationId().toString(), action); }
    private ExpenseSelfApprovalSnapshot selection(ExpenseReport report) {
        return json.read((String) runtime.getVariableLocal(pending(report).get(0).getProcessInstanceId(), ExpenseSelfApprovalBindings.VARIABLE), ExpenseSelfApprovalSnapshot.class);
    }
    private List<Task> projectTasks(ExpenseReport report) { return pending(report).stream().filter(task -> task.getTaskDefinitionKey().equals("projects")).toList(); }
    private Map<String, Object> submitInput(ExpenseReport report, UUID checked) { return Map.of("applicationVersion", application(report).version(), "financialVersion", current(report).version(), "precheckId", checked); }
    private Application application(ExpenseReport report) { return applications.findById("demo", report.applicationId()).orElseThrow(); }
    private ExpenseReport current(ExpenseReport report) { return reports.find("demo", report.id()).orElseThrow(); }
    private UUID person(String user, boolean approver) { var found = jdbc.queryForList("SELECT id FROM organization_person WHERE tenant_id='demo' AND subject=?", String.class, user);
        return found.isEmpty() ? organization.createPerson(ADMIN, user, "合成"+user, true, approver).id() : UUID.fromString(found.get(0)); }
    private FormSchema.Field field(String key, FormSchema.FieldType type) { return new FormSchema.Field(key, key, type, true, null, null, null, null, null); }
    private JsonNode request(String path, String user, int expected) throws Exception {
        var response = mvc.perform(get(path).header("Authorization", token(user))).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected); return json.read(response.getContentAsString(StandardCharsets.UTF_8), JsonNode.class);
    }
    private String token(String user) { return "Bearer " + tokens.computeIfAbsent(user, value -> auth.login("demo", value, "demo").token()); }
    private MockHttpServletResponse raw(String path, String user, Object input) throws Exception {
        return raw(path, user, input, UUID.randomUUID().toString());
    }
    private MockHttpServletResponse raw(String path, String user, Object input, String key) throws Exception {
        return mvc.perform(post(path).header("Authorization", token(user)).header("Idempotency-Key", key)
                .contentType("application/json").content(json.write(input))).andReturn().getResponse();
    }
    private JsonNode send(String path, String user, Object input, int expected) throws Exception {
        var response = raw(path, user, input); assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected);
        return json.read(response.getContentAsString(StandardCharsets.UTF_8), JsonNode.class);
    }
    private static String path(ExpenseReport report) { return "/api/v1/expense-reports/" + report.id(); }
    private static Money money(String amount) { return new Money(new BigDecimal(amount), "CNY"); }
}
