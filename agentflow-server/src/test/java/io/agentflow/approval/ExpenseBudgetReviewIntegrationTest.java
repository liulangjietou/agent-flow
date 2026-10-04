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
import io.agentflow.finance.BudgetOperation;
import io.agentflow.finance.BudgetObservation;
import io.agentflow.finance.BudgetOperationService;
import io.agentflow.finance.BudgetSystemPort;
import io.agentflow.finance.FinanceGatewayConfiguration;
import io.agentflow.finance.JdbcBudgetOccupationRepository;
import io.agentflow.finance.JdbcBudgetOperationRepository;
import io.agentflow.finance.Money;
import io.agentflow.form.FieldVisibility;
import io.agentflow.form.FormSchema;
import io.agentflow.organization.OrganizationService;
import io.agentflow.organization.OrganizationUnit;
import io.agentflow.organization.ApprovalProxyService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 真实预检与原生任务验证柔性预算审批；外部预算均为显式合成夹具。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:expense-budget-review-runtime;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "agentflow.auth.demo-enabled=true",
        "agentflow.finance-gateway.enabled=true", "agentflow.timers.enabled=false", "agentflow.sla.reminders-enabled=false",
        "agentflow.vouchers.preparation-worker-enabled=false", "agentflow.vouchers.worker-enabled=false", "agentflow.payments.worker-enabled=false",
        "agentflow.payments.request-worker-enabled=false", "agentflow.invoices.verification-worker-enabled=false",
        "agentflow.expenses.precheck-worker-enabled=false", "agentflow.budgets.worker-enabled=false",
        "agentflow.expenses.budget-review-worker-enabled=false", "agentflow.expenses.settlement-worker-enabled=false", "agentflow.expenses.archive-worker-enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExpenseBudgetReviewIntegrationTest {
    private static final Actor ADMIN = new Actor("demo", "admin", Set.of("ADMIN", "PROCESS_ADMIN"));
    private static final ExpenseSubprocessGatewayFixture GATEWAY = new ExpenseSubprocessGatewayFixture();
    private static final java.nio.file.Path RESPONSES = java.nio.file.Path.of("/fyoung/tmp/agentflow-remaining-20260928/f09-budget-responses-" + UUID.randomUUID());
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
    @Autowired JdbcExpenseBudgetReviewRepository reviews;
    @Autowired ExpenseBudgetReviewWorker reviewWorker;
    @Autowired RuntimeService runtime;
    @Autowired ApprovalProxyService proxies;

    @DynamicPropertySource static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("agentflow.finance-gateway.tenants.demo.endpoint", GATEWAY::endpoint);
        registry.add("agentflow.finance-gateway.tenants.demo.allow-unauthenticated-loopback", () -> true);
        registry.add("agentflow.attachments.directory", () -> "/fyoung/tmp/agentflow-budget-review-http-fixtures");
    }
    @BeforeEach void organization() {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM organization_directory WHERE tenant_id='demo'", Integer.class) == 0) organization.initialize(ADMIN);
        entity = organization.createUnit(ADMIN, OrganizationUnit.Kind.LEGAL_ENTITY, "合成事前法人", null, null, true).id();
        var department = organization.createUnit(ADMIN, OrganizationUnit.Kind.DEPARTMENT, "合成部门", entity, null, true);
        var position = organization.createUnit(ADMIN, OrganizationUnit.Kind.POSITION, "合成岗位", entity, null, true);
        appointment = organization.createAppointment(ADMIN, person("alice", false), department.id(), position.id(), true).id();
        manager = person("manager", true); finance = person("finance", true);
        GATEWAY.use(json, entity, "76543210987654321000");
    }
    @AfterEach void clearActorAndCheckGateway() { actors.clear(); assertThat(GATEWAY.failure()).isNull(); }
    @AfterAll static void stopOwnedServer() { GATEWAY.close(); }

    @Test void flexiblePrecheckCannotStartAnOldDefinitionWithoutBudgetCheckpoint() throws Exception {
        GATEWAY.exceptionPolicy("policy-flex-1"); var report = draft(definition(false, false)); var checked = check(report);
        assertThat(checked.status()).isEqualTo(ExpensePrecheckJob.Status.READY);
        var response = raw(path(report)+"/submit", "alice", submitInput(report, checked.input().id()));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(422);
        assertThat(application(report).status()).isEqualTo(ApplicationStatus.DRAFT);
        assertThat(occupations.find("demo", report.id())).isEmpty(); assertThat(current(report).rounds()).isEmpty();
    }

    @Test void confirmedBudgetPassesItsNativeNodeWithoutClaimingHumanApproval() throws Exception {
        var report = draft(definition(true, true)); submit(report);
        assertThat(reviews.find("demo", report.id(), 1).orElseThrow().status()).isEqualTo(ExpenseBudgetReview.Status.CONFIRMED);
        assertThat(occupations.find("demo", report.id()).orElseThrow().frozenFor(
                io.agentflow.finance.BudgetPrecheckPort.Request.fromCurrent(current(report), LocalDate.now()))).isTrue();
        approve(report, "manager");
        assertThat(task(report).getTaskDefinitionKey()).isEqualTo("receipt");
        assertThat(reviews.find("demo", report.id(), 1).orElseThrow().automaticPass()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='AUTO_PASSED_BUDGET' AND actor_id='system:budget'", Integer.class, report.applicationId().toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='APPROVE'", Integer.class, report.applicationId().toString())).isEqualTo(1);
    }

    @Test void pendingBudgetCannotBeManuallyApprovedThroughTheOrdinaryTaskEndpoint() throws Exception {
        GATEWAY.budgetStatus(BudgetObservation.Status.PENDING); var report = draft(definition(true, false)); submit(report);
        approve(report, "manager"); var task = task(report); assertThat(task.getTaskDefinitionKey()).isEqualTo("budget");
        long version = application(report).version();
        var response = raw("/api/v1/tasks/"+task.getId()+"/actions", "manager", Map.of("action", "APPROVE", "expectedVersion", version, "comment", "预算尚未确认"));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(422);
        assertThat(application(report).version()).isEqualTo(version); assertThat(task(report).getId()).isEqualTo(task.getId());
        assertThat(GATEWAY.commands()).hasSize(1);
    }

    @Test void flexibleRefusalNeedsSeparateDecisionAndOriginalKeyCreatesOneAuthorizedCommand() throws Exception {
        GATEWAY.exceptionPolicy("policy-flex-1"); GATEWAY.budgetStatus(BudgetObservation.Status.REJECTED);
        var report = draft(definition(true, false)); submit(report); assertThat(application(report).status()).isEqualTo(ApplicationStatus.IN_APPROVAL);
        approve(report, "manager"); var task = task(report); assertThat(task.getTaskDefinitionKey()).isEqualTo("budget");
        var original = operations.latest("demo", report.id()).orElseThrow(); String key = UUID.randomUUID().toString();
        var input = Map.of("action", "APPROVE", "expectedVersion", application(report).version(), "comment", "合成预算例外批准");
        var first = raw("/api/v1/tasks/"+task.getId()+"/actions", "manager", input, key); assertThat(first.getStatus()).as(first.getContentAsString()).isEqualTo(200);
        var retryId = occupations.find("demo", report.id()).orElseThrow().pendingOperationId(); assertThat(retryId).isNotNull().isNotEqualTo(original.input().command().id());
        var retry = operations.find("demo", retryId).orElseThrow(); assertThat(retry.input().command().position()).isEqualTo(original.input().command().position());
        assertThat(retry.input().command().exceptionApproval().actorId()).isEqualTo("manager");
        var recovered = raw("/api/v1/tasks/"+task.getId()+"/actions", "manager", input, key);
        assertThat(recovered.getStatus()).isEqualTo(200); assertThat(recovered.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(occupations.find("demo", report.id()).orElseThrow().pendingOperationId()).isEqualTo(retryId);
        assertThat(reviews.find("demo", report.id(), 1).orElseThrow().status()).isEqualTo(ExpenseBudgetReview.Status.AUTHORIZED);
        GATEWAY.budgetStatus(BudgetObservation.Status.PENDING); budgets(report);
        assertThat(operations.find("demo", retryId).orElseThrow().status()).isEqualTo(BudgetOperation.Status.UNKNOWN);
        send(path(report)+"/tasks/"+task(report).getId()+"/receive", "finance", lifecycle(report), 200);
        approve(report, "finance"); assertThat(task(report).getTaskDefinitionKey()).isEqualTo("finance");
        var blocked = raw("/api/v1/tasks/"+task(report).getId()+"/actions", "finance",
                Map.of("action", "APPROVE", "expectedVersion", application(report).version(), "comment", "等待真实预算"));
        assertThat(blocked.getStatus()).as(blocked.getContentAsString()).isEqualTo(422);
        GATEWAY.budgetStatus(BudgetObservation.Status.APPLIED); budgets(report);
        assertThat(GATEWAY.writes(retryId)).isEqualTo(1); assertThat(GATEWAY.queries(retryId)).isEqualTo(1);
        assertThat(reviews.find("demo", report.id(), 1).orElseThrow().status()).isEqualTo(ExpenseBudgetReview.Status.CONFIRMED);
        assertThat(operations.find("demo", original.input().command().id()).orElseThrow().status()).isEqualTo(BudgetOperation.Status.REJECTED);
    }

    @Test void lateConfirmedBudgetRecoversOnceAndRespectsSuspendedNativeInstance() throws Exception {
        GATEWAY.budgetStatus(BudgetObservation.Status.PENDING); var report = draft(definition(true, false)); submit(report);
        approve(report, "manager"); String taskId = task(report).getId(); String instanceId = task(report).getProcessInstanceId();
        runtime.suspendProcessInstanceById(instanceId);
        GATEWAY.budgetStatus(BudgetObservation.Status.APPLIED); budgets(report); reviewWorker.poll();
        assertThat(task(report).getId()).isEqualTo(taskId);
        runtime.activateProcessInstanceById(instanceId);
        jdbc.update("UPDATE expense_budget_review SET next_check_at=? WHERE tenant_id='demo' AND report_id=?",
                java.sql.Timestamp.from(Instant.now()), report.id().toString());
        reviewWorker.poll();
        assertThat(task(report).getTaskDefinitionKey()).isEqualTo("receipt");
        var review = reviews.find("demo", report.id(), 1).orElseThrow(); assertThat(review.automaticPass()).isNotNull();
        long version = application(report).version(); reviewWorker.poll();
        assertThat(application(report).version()).isEqualTo(version);
        assertThat(reviews.find("demo", report.id(), 1).orElseThrow()).isEqualTo(review);
    }

    @Test void authorizedRefusalReturnsWithoutAnotherAutomaticBudgetCommand() throws Exception {
        GATEWAY.exceptionPolicy("policy-flex-1"); GATEWAY.budgetStatus(BudgetObservation.Status.REJECTED);
        var report = draft(definition(true, false)); submit(report); approve(report, "manager"); approve(report, "manager");
        UUID retryId = occupations.find("demo", report.id()).orElseThrow().pendingOperationId(); budgets(report); reviewWorker.poll();
        assertThat(application(report).status()).isEqualTo(ApplicationStatus.RETURNED); assertThat(task(report)).isNull();
        var review = reviews.find("demo", report.id(), 1).orElseThrow();
        assertThat(review.status()).isEqualTo(ExpenseBudgetReview.Status.CLOSED);
        assertThat(review.closure()).isEqualTo(ExpenseBudgetReview.Closure.RETURNED);
        assertThat(review.authorizedOperationId()).isEqualTo(retryId);
        assertThat(occupations.find("demo", report.id()).orElseThrow().pendingOperationId()).isNull();
        assertThat(GATEWAY.commands()).hasSize(2);
    }

    @Test void failedAuthorizationPersistenceRollsBackTaskAuditAndBudgetRegistrationTogether() throws Exception {
        GATEWAY.exceptionPolicy("policy-flex-1"); GATEWAY.budgetStatus(BudgetObservation.Status.REJECTED);
        var report = draft(definition(true, false)); submit(report); approve(report, "manager");
        String taskId = task(report).getId(); long version = application(report).version();
        var before = reviews.find("demo", report.id(), 1).orElseThrow();
        jdbc.execute("ALTER TABLE expense_budget_review ADD CONSTRAINT budget_review_fixture_failure CHECK(report_id<>'"+report.id()+"' OR status<>'AUTHORIZED')");
        try {
            assertThatThrownBy(() -> raw("/api/v1/tasks/"+taskId+"/actions", "manager",
                    Map.of("action", "APPROVE", "expectedVersion", version, "comment", "合成回滚")))
                    .isInstanceOf(jakarta.servlet.ServletException.class).hasCauseInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally { jdbc.execute("ALTER TABLE expense_budget_review DROP CONSTRAINT budget_review_fixture_failure"); }
        assertThat(task(report).getId()).isEqualTo(taskId); assertThat(application(report).version()).isEqualTo(version);
        assertThat(reviews.find("demo", report.id(), 1).orElseThrow()).isEqualTo(before);
        assertThat(occupations.find("demo", report.id()).orElseThrow().pendingOperationId()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_operation WHERE tenant_id='demo' AND report_id=?", Integer.class, report.id().toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='APPROVE'", Integer.class, report.applicationId().toString())).isEqualTo(1);
    }

    @Test void historicalRigidInsufficiencyStillReturnsTheNativeRound() throws Exception {
        GATEWAY.budgetStatus(BudgetObservation.Status.REJECTED); var report = draft(definition(false, false)); submit(report);
        assertThat(application(report).status()).isEqualTo(ApplicationStatus.RETURNED); assertThat(task(report)).isNull();
    }

    @Test void withdrawnAuthorizationCannotApproveTheNextSubmittedRound() throws Exception {
        GATEWAY.exceptionPolicy("policy-flex-1"); GATEWAY.budgetStatus(BudgetObservation.Status.REJECTED);
        var report = draft(definition(true, false)); submit(report); approve(report, "manager"); approve(report, "manager");
        var first = reviews.find("demo", report.id(), 1).orElseThrow();
        GATEWAY.budgetStatus(BudgetObservation.Status.APPLIED); budgets(report);
        send(path(report)+"/withdraw", "alice", lifecycle(report), 200);
        assertThat(reviews.find("demo", report.id(), 1).orElseThrow().closure()).isEqualTo(ExpenseBudgetReview.Closure.WITHDRAWN);
        GATEWAY.budgetStatus(BudgetObservation.Status.REJECTED); submit(report);
        var second = reviews.find("demo", report.id(), 2).orElseThrow();
        assertThat(second.input().originalOperationId()).isNotEqualTo(first.input().originalOperationId());
        assertThat(second.status()).isEqualTo(ExpenseBudgetReview.Status.REVIEW_REQUIRED);
        assertThat(second.approval()).isNull(); assertThat(second.authorizedOperationId()).isNull();
        approve(report, "manager"); assertThat(task(report).getTaskDefinitionKey()).isEqualTo("budget");
        assertThat(operations.find("demo", second.input().originalOperationId()).orElseThrow().input().command().action())
                .isEqualTo(io.agentflow.finance.BudgetCommand.Action.ADJUST);
    }

    @Test void concurrentDecisionsRegisterOnlyOneAuthorizationAndRetryCommand() throws Exception {
        GATEWAY.exceptionPolicy("policy-flex-1"); GATEWAY.budgetStatus(BudgetObservation.Status.REJECTED);
        var report = draft(definition(true, false)); submit(report); approve(report, "manager");
        String path = "/api/v1/tasks/"+task(report).getId()+"/actions";
        var input = Map.of("action", "APPROVE", "expectedVersion", application(report).version(), "comment", "合成并发审批");
        var pool = Executors.newFixedThreadPool(2); var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Integer> decision = () -> { ready.countDown(); assertThat(start.await(10, TimeUnit.SECONDS)).isTrue(); return raw(path, "manager", input).getStatus(); };
            var first = pool.submit(decision); var second = pool.submit(decision);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            var statuses = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(statuses.stream().filter(status -> status==200).count()).isEqualTo(1);
            assertThat(statuses).allMatch(status -> Set.of(200, 404, 409).contains(status));
        } finally { start.countDown(); pool.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_operation WHERE tenant_id='demo' AND report_id=?", Integer.class, report.id().toString())).isEqualTo(2);
        var review = reviews.find("demo", report.id(), 1).orElseThrow();
        assertThat(review.status()).isEqualTo(ExpenseBudgetReview.Status.AUTHORIZED);
        assertThat(occupations.find("demo", report.id()).orElseThrow().pendingOperationId()).isEqualTo(review.authorizedOperationId());
    }

    @Test void validProxyAuthorizationRecordsTheActualActorAndCannotBeReplacedByAdministratorPrivilege() throws Exception {
        GATEWAY.exceptionPolicy("policy-flex-1"); GATEWAY.budgetStatus(BudgetObservation.Status.REJECTED);
        var definition = definition(true, false); var report = draft(definition); submit(report); approve(report, "manager");
        String taskId = task(report).getId();
        var input = Map.of("action", "APPROVE", "expectedVersion", application(report).version(), "comment", "合成预算代理");
        assertThat(raw("/api/v1/tasks/"+taskId+"/actions", "admin", input).getStatus()).isIn(403, 404);
        var proxy = proxies.create(ADMIN, definition.id(), manager, person("bob", true), Instant.now().minusSeconds(1), Instant.now().plusSeconds(600), "合成临时代理");
        var approved = send("/api/v1/tasks/"+taskId+"/actions", "bob",
                Map.of("action", "APPROVE", "expectedVersion", application(report).version(), "comment", "合成预算代理", "proxyId", proxy.id()), 200);
        var review = reviews.find("demo", report.id(), 1).orElseThrow();
        assertThat(review.approval().actorId()).isEqualTo("bob");
        assertThat(review.approval().auditEventId().toString()).isEqualTo(approved.path("auditEventId").asText());
        var audit = json.read(jdbc.queryForObject("SELECT payload_json FROM audit_event WHERE tenant_id='demo' AND event_id=?", String.class,
                review.approval().auditEventId().toString()), JsonNode.class);
        assertThat(audit.path("proxyUse").path("proxyId").asText()).isEqualTo(proxy.id().toString());
        proxies.revoke(ADMIN, proxy.id(), proxy.revision(), "合成代理结束");
        assertThat(reviews.find("demo", report.id(), 1).orElseThrow().approval()).isEqualTo(review.approval());
    }

    @Test void delegatedBudgetTaskWaitsForOwnerBeforeAutomaticPass() throws Exception {
        GATEWAY.budgetStatus(BudgetObservation.Status.PENDING); var report = draft(definition(true, false)); submit(report); approve(report, "manager");
        person("bob", true); String taskId = task(report).getId();
        send("/api/v1/tasks/"+taskId+"/actions", "manager", Map.of("action", "DELEGATE", "targetUser", "bob",
                "expectedVersion", application(report).version(), "comment", "合成委派"), 200);
        GATEWAY.budgetStatus(BudgetObservation.Status.APPLIED); budgets(report); reviewWorker.poll();
        assertThat(task(report).getId()).isEqualTo(taskId);
        send("/api/v1/tasks/"+taskId+"/actions", "bob", Map.of("action", "RESOLVE", "expectedVersion", application(report).version(), "comment", "资料核对完成"), 200);
        jdbc.update("UPDATE expense_budget_review SET next_check_at=? WHERE tenant_id='demo' AND report_id=?",
                java.sql.Timestamp.from(Instant.now()), report.id().toString());
        reviewWorker.poll(); assertThat(task(report).getTaskDefinitionKey()).isEqualTo("receipt");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='APPROVE'", Integer.class, report.applicationId().toString())).isEqualTo(1);
    }

    @Test void originalRoundQuerySeparatesHumanAuthorizationFromActualBudgetConfirmation() throws Exception {
        GATEWAY.exceptionPolicy("policy-flex-1"); GATEWAY.budgetStatus(BudgetObservation.Status.REJECTED);
        var report = draft(definition(true, false)); var checked = check(report);
        var precheck = request(path(report)+"/prechecks/"+checked.input().id(), "alice", 200);
        assertThat(precheck.path("budgetExceptionPolicy").path("reference").asText()).isEqualTo("policy-flex-1");
        send(path(report)+"/submit", "alice", submitInput(report, checked.input().id()), 200); budgets(report); approve(report, "manager");
        var required = request(path(report)+"/budget-review?roundNo=1", "manager", 200);
        assertThat(required.path("status").asText()).isEqualTo("RECORDED");
        assertThat(required.path("details").path("status").asText()).isEqualTo("REVIEW_REQUIRED");
        assertThat(required.path("details").path("decision").isNull()).isTrue();
        approve(report, "manager");
        var authorized = request(path(report)+"/budget-review?roundNo=1", "alice", 200);
        assertThat(authorized.path("details").path("status").asText()).isEqualTo("AUTHORIZED");
        assertThat(authorized.path("details").path("authorizedOperationStatus").asText()).isEqualTo("QUEUED");
        assertThat(authorized.path("details").path("decision").path("actorId").asText()).isEqualTo("manager");
        assertThat(authorized.toString()).doesNotContain("targetDigest", "offerReference", "commandDigest", "accountNumber");
        GATEWAY.budgetStatus(BudgetObservation.Status.APPLIED); budgets(report);
        send(path(report)+"/withdraw", "alice", lifecycle(report), 200); submit(report);
        var old = request(path(report)+"/budget-review?roundNo=1", "alice", 200);
        assertThat(old.path("details").path("closure").asText()).isEqualTo("WITHDRAWN");
        assertThat(old.path("details").path("originalOperationId")).isEqualTo(authorized.path("details").path("originalOperationId"));
        var next = request(path(report)+"/budget-review?roundNo=2", "alice", 200);
        assertThat(next.path("details").path("originalOperationId")).isNotEqualTo(old.path("details").path("originalOperationId"));
        for (var suffix : List.of("", "?roundNo=0", "?roundNo=01", "?roundNo=1&roundNo=1", "?roundNo=1&user=alice")) {
            request(path(report)+"/budget-review"+suffix, "alice", 400);
        }
        request(path(report)+"/budget-review?roundNo=99", "alice", 404);
    }

    @Test void actualTaskOptionsOmitApprovalUntilTheBudgetCheckpointNeedsHumanDecision() throws Exception {
        GATEWAY.exceptionPolicy("policy-flex-1"); GATEWAY.budgetStatus(BudgetObservation.Status.PENDING);
        var definition = definition(true, false); var report = draft(definition); submit(report); approve(report, "manager");
        String id = task(report).getId();
        var currentTask = request("/api/v1/tasks/"+id, "manager", 200);
        assertThat(currentTask.path("allowedActions").toString()).doesNotContain("APPROVE").contains("RETURN");
        var workflow = request(path(report)+"/workflow?taskId="+id, "manager", 200);
        assertThat(workflow.path("task").path("canApprove").isBoolean()).isTrue();
        assertThat(workflow.path("task").path("canApprove").asBoolean()).isFalse();
        assertThat(workflow.path("task").path("approvalUnavailable").asText()).isEqualTo("EXPENSE_BUDGET_RESULT_PENDING");
        proxies.create(ADMIN, definition.id(), manager, person("bob", true), Instant.now().minusSeconds(1), Instant.now().plusSeconds(600), "合成待预算代理");
        assertThat(request("/api/v1/tasks/"+id, "bob", 200).path("allowedActions").toString()).doesNotContain("APPROVE").contains("RETURN");
        GATEWAY.budgetStatus(BudgetObservation.Status.REJECTED); budgets(report);
        assertThat(request("/api/v1/tasks/"+id, "manager", 200).path("allowedActions").toString()).contains("APPROVE");
        assertThat(request(path(report)+"/workflow?taskId="+id, "manager", 200).path("task").path("canApprove").asBoolean()).isTrue();
    }

    @ParameterizedTest @ValueSource(strings = {"HIDDEN", "MASKED"})
    void budgetHistoryUsesSensitiveOriginalNodePermissionsIncludingForAdministrators(String access) throws Exception {
        var report = draft(definition(true, false, FieldVisibility.valueOf(access))); submit(report);
        request(path(report)+"/budget-review?roundNo=1", "manager", 403);
        request(path(report)+"/budget-review?roundNo=1", "admin", 403);
        request(path(report)+"/budget-review?roundNo=1", "bob", 404);
        assertThat(request(path(report)+"/budget-review?roundNo=1", "alice", 200).path("details").path("status").asText()).isEqualTo("CONFIRMED");
    }

    private DefinitionDraft definition(boolean budget, boolean financeOwnsBudget) {
        return definition(budget, financeOwnsBudget, FieldVisibility.READ_ONLY);
    }
    private DefinitionDraft definition(boolean budget, boolean financeOwnsBudget, FieldVisibility businessVisibility) {
        var start = Map.of(ExpenseSelfApprovalPolicy.PROPERTY, ExpenseSelfApprovalPolicy.ESCALATE_SUPERVISOR,
                ExpenseDuplicateApprovalPolicy.PROPERTY, ExpenseDuplicateApprovalPolicy.AUTO_PASS_ADJACENT);
        var nodes = new ArrayList<>(List.of(new Node("start", "开始", NodeType.START, start),
                new Node("business", "业务审批", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_"+manager)),
                new Node("receipt", "纸件签收", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_"+finance, "expenseStage", "RECEIPT")),
                new Node("finance", "财务复核", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_"+finance, "expenseStage", "FINANCE_REVIEW")),
                new Node("end", "结束", NodeType.END, Map.of())));
        var edges = new ArrayList<>(List.of(new Edge("a", "start", "business", ""), new Edge("b", "business", budget ? "budget" : "receipt", ""),
                new Edge("c", "receipt", "finance", ""), new Edge("d", "finance", "end", "")));
        var visibility = new HashMap<>(Map.of("business", businessVisibility, "receipt", FieldVisibility.READ_ONLY, "finance", FieldVisibility.READ_ONLY));
        if (budget) {
            nodes.add(new Node("budget", "预算负责人", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_"+(financeOwnsBudget ? finance : manager), "expenseStage", "BUDGET_REVIEW")));
            edges.add(new Edge("budget_done", "budget", "receipt", "")); visibility.put("budget", FieldVisibility.READ_ONLY);
        }
        var fields = List.of(new FormSchema.Field("expenseDetails", "费用明细", FormSchema.FieldType.TEXT, true,
                null, null, null, null, null, null, null, true, visibility), field("amount", FormSchema.FieldType.NUMBER),
                field("currency", FormSchema.FieldType.TEXT), field("overPolicy", FormSchema.FieldType.BOOLEAN));
        var draft = definitions.create("demo", "budget-review-"+UUID.randomUUID(), "合成预算流程", new Graph(nodes, edges), new FormSchema(2, fields), null);
        return definitions.publish(ADMIN, draft.id(), draft.revision(), "合成验收");
    }
    private ExpenseReport draft(DefinitionDraft definition) throws Exception {
        var line = new ExpenseLine(1, "OFFICE", LocalDate.now(), null, "SH", BigDecimal.ONE, ExpenseLine.Unit.ITEM, money("100"), money("6"),
                List.of(), null, List.of(new CostAllocation("IT", null, money("100"))), "合成费用", null);
        var created = send("/api/v1/expense-reports", "alice", Map.of("businessNo", "BUDGET-"+UUID.randomUUID(), "processKey", definition.key(),
                "definitionVersion", definition.version(), "content", new ExpenseContent(entity, ExpenseContent.Type.DAILY, "合成报销", List.of(line), List.of())), 201);
        return reports.find("demo", UUID.fromString(created.path("id").asText())).orElseThrow();
    }
    private ExpensePrecheckJob check(ExpenseReport report) throws Exception {
        var checked = send(path(report) + "/precheck", "alice", Map.of("applicationVersion", application(report).version(), "financialVersion", current(report).version(),
                "initiatorAppointmentId", appointment, "accountingDate", LocalDate.now(), "targetDigest", financeConfiguration.destination("demo").orElseThrow().digest("demo")), 202);
        var id = UUID.fromString(checked.path("id").asText()); precheckWorker.poll();
        request(path(report) + "/prechecks/" + id, "alice", 200); return prechecks.find("demo", id).orElseThrow();
    }
    private void submit(ExpenseReport report) throws Exception {
        var checked = check(report); assertThat(checked.status()).as(json.write(checked.result())).isEqualTo(ExpensePrecheckJob.Status.READY);
        send(path(report) + "/submit", "alice", submitInput(report, checked.input().id()), 200); budgets(report);
    }
    private void budgets(ExpenseReport report) throws InterruptedException {
        UUID id = occupations.find("demo", report.id()).orElseThrow().pendingOperationId(); if (id == null) return;
        var operation = operations.find("demo", id).orElseThrow();
        long wait = java.time.Duration.between(Instant.now(), operation.nextAttemptAt()).toMillis();
        if (wait>=0) Thread.sleep(wait+1);
        var at = Instant.now();
        var claimed = budgetExecution.claim("demo", id, at); var input = claimed.input();
        var result = claimed.status() == BudgetOperation.Status.QUERYING ? budgetPort.query(input.targetDigest(), input.command()) : budgetPort.execute(input.targetDigest(), input.command());
        budgetExecution.finish(claimed, result, Instant.now());
    }
    private void approve(ExpenseReport report, String user) throws Exception { send("/api/v1/tasks/" + task(report).getId() + "/actions", user,
            Map.of("action", "APPROVE", "expectedVersion", application(report).version(), "comment", "合成审批"), 200); }
    private Map<String, Object> lifecycle(ExpenseReport report) { return Map.of("applicationVersion", application(report).version(), "financialVersion", current(report).version(), "comment", "合成操作"); }
    private JsonNode request(String endpoint, String user, int expected) throws Exception {
        var response = mvc.perform(get(endpoint).header("Authorization", "Bearer " + tokens.computeIfAbsent(user, value -> auth.login("demo", value, "demo").token())))
                .andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected);
        if (expected == 200 && endpoint.startsWith("/api/v1/expense-reports/")) assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        capture("GET", endpoint, null, response);
        return json.read(response.getContentAsString(StandardCharsets.UTF_8), JsonNode.class);
    }
    private Map<String, Object> submitInput(ExpenseReport report, UUID checked) { return Map.of("applicationVersion", application(report).version(), "financialVersion", current(report).version(), "precheckId", checked); }
    private Application application(ExpenseReport report) { return applications.findById("demo", report.applicationId()).orElseThrow(); }
    private ExpenseReport current(ExpenseReport report) { return reports.find("demo", report.id()).orElseThrow(); }
    private Task task(ExpenseReport report) { return tasks.createTaskQuery().processVariableValueEquals("applicationId", report.applicationId().toString()).singleResult(); }
    private UUID person(String user, boolean approver) { var found = jdbc.queryForList("SELECT id FROM organization_person WHERE tenant_id='demo' AND subject=?", String.class, user);
        return found.isEmpty() ? organization.createPerson(ADMIN, user, "合成" + user, true, approver).id() : UUID.fromString(found.get(0)); }
    private FormSchema.Field field(String key, FormSchema.FieldType type) { return new FormSchema.Field(key, key, type, true, null, null, null, null, null); }
    private MockHttpServletResponse raw(String path, String user, Object body) throws Exception {
        return raw(path, user, body, UUID.randomUUID().toString());
    }
    private MockHttpServletResponse raw(String path, String user, Object body, String key) throws Exception {
        String token = "Bearer " + tokens.computeIfAbsent(user, value -> auth.login("demo", value, "demo").token());
        var response = mvc.perform(post(path).header("Authorization", token).header("Idempotency-Key", key).contentType("application/json").content(json.write(body))).andReturn().getResponse();
        capture("POST", path, body, response); return response;
    }
    private void capture(String method, String path, Object request, MockHttpServletResponse response) throws Exception {
        var record = new HashMap<String, Object>(); record.put("method", method); record.put("path", path); record.put("status", response.getStatus());
        if (request != null) record.put("request", request);
        record.put("response", json.read(response.getContentAsString(StandardCharsets.UTF_8), JsonNode.class));
        java.nio.file.Files.createDirectories(RESPONSES);
        java.nio.file.Files.writeString(RESPONSES.resolve(UUID.randomUUID() + ".json"), json.write(record));
    }
    private JsonNode send(String path, String user, Object body, int status) throws Exception { var response = raw(path, user, body);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status); return json.read(response.getContentAsString(StandardCharsets.UTF_8), JsonNode.class); }
    private static String path(ExpenseReport report) { return "/api/v1/expense-reports/" + report.id(); }
    private static Money money(String amount) { return new Money(new BigDecimal(amount), "CNY"); }
}
