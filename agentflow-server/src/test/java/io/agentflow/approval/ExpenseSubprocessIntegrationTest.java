package io.agentflow.approval;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.approval.repository.SubprocessCallRepository;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import io.agentflow.definition.DefinitionDeploymentPort;
import io.agentflow.definition.DefinitionDraftRepository;
import io.agentflow.definition.SubprocessPolicy;
import io.agentflow.expense.AdvanceOffset;
import io.agentflow.expense.CostAllocation;
import io.agentflow.expense.EmployeeAdvance;
import io.agentflow.expense.EmployeeAdvanceRepository;
import io.agentflow.expense.ExpenseContent;
import io.agentflow.expense.ExpenseFormContract;
import io.agentflow.expense.ExpenseLine;
import io.agentflow.expense.ExpensePrecheckJob;
import io.agentflow.expense.ExpensePrecheckWorker;
import io.agentflow.expense.ExpenseReport;
import io.agentflow.expense.ExpenseReportRepository;
import io.agentflow.expense.ExpenseRequest;
import io.agentflow.expense.ExpenseRequestRepository;
import io.agentflow.expense.ExpenseUse;
import io.agentflow.expense.Invoice;
import io.agentflow.expense.InvoiceOriginal;
import io.agentflow.expense.InvoiceRepository;
import io.agentflow.expense.InvoiceVerificationService;
import io.agentflow.expense.InvoiceVerificationWorker;
import io.agentflow.expense.InvoiceWalletService;
import io.agentflow.expense.JdbcExpensePrecheckRepository;
import io.agentflow.expense.JdbcExpenseSubmissionControlRepository;
import io.agentflow.finance.BudgetCommand;
import io.agentflow.finance.BudgetObservation;
import io.agentflow.finance.BudgetOccupation;
import io.agentflow.finance.BudgetOperation;
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
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 报销父流程通过实际专用提交、原生子审批、签收和核减验证财务边界；企业事实及原件为合成夹具。
 * 仅父定义使用内部发布夹具，正式部署依赖、实际引擎和应用事务均参与，不代表公开子流程入口已开放。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "agentflow.finance-gateway.enabled=true",
        "agentflow.timers.enabled=false", "agentflow.sla.reminders-enabled=false",
        "agentflow.vouchers.preparation-worker-enabled=false", "agentflow.vouchers.worker-enabled=false",
        "agentflow.payments.worker-enabled=false", "agentflow.payments.request-worker-enabled=false",
        "agentflow.invoices.verification-worker-enabled=false", "agentflow.expenses.precheck-worker-enabled=false",
        "agentflow.budgets.worker-enabled=false", "agentflow.expenses.settlement-worker-enabled=false", "agentflow.expenses.archive-worker-enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExpenseSubprocessIntegrationTest {
    private static final Actor ADMIN = new Actor("demo", "admin", Set.of("ADMIN", "PROCESS_ADMIN"));
    private static final Actor APPLICANT = new Actor("demo", "alice", Set.of("EMPLOYEE"));
    private static final ExpenseSubprocessGatewayFixture GATEWAY = new ExpenseSubprocessGatewayFixture();
    private static final Path FILES = Path.of("/fyoung/tmp/agentflow-expense-subprocess-" + UUID.randomUUID());
    private static final AtomicInteger SERIAL = new AtomicInteger();
    private UUID entity;
    private UUID appointment;
    private UUID manager;
    private UUID finance;

    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @Autowired AuthService auth;
    @Autowired CurrentActor actors;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrganizationService organization;
    @Autowired DefinitionApplicationService definitions;
    @Autowired DefinitionDraftRepository drafts;
    @Autowired DefinitionDeploymentPort deployment;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ApplicationRepository applications;
    @Autowired SubmissionRoundRepository rounds;
    @Autowired SubprocessCallRepository calls;
    @Autowired TaskService tasks;
    @Autowired HistoryService history;
    @Autowired ExpenseReportRepository reports;
    @Autowired ExpenseRequestRepository requests;
    @Autowired EmployeeAdvanceRepository advances;
    @Autowired InvoiceRepository invoices;
    @Autowired InvoiceWalletService wallet;
    @Autowired InvoiceVerificationService verification;
    @Autowired InvoiceVerificationWorker invoiceChecks;
    @Autowired ExpensePrecheckWorker precheckWorker;
    @Autowired JdbcExpensePrecheckRepository prechecks;
    @Autowired JdbcExpenseSubmissionControlRepository controls;
    @Autowired FinanceGatewayConfiguration configuration;
    @Autowired JdbcBudgetOccupationRepository occupations;
    @Autowired JdbcBudgetOperationRepository operations;
    @Autowired BudgetOperationService budgetExecution;
    @Autowired BudgetSystemPort budgetPort;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("agentflow.attachments.directory", FILES::toString);
        registry.add("agentflow.finance-gateway.tenants.demo.endpoint", GATEWAY::endpoint);
        registry.add("agentflow.finance-gateway.tenants.demo.allow-unauthenticated-loopback", () -> true);
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_EXPENSE_SUBPROCESS_URL", "jdbc:h2:mem:expense-subprocess;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_EXPENSE_SUBPROCESS_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_EXPENSE_SUBPROCESS_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_EXPENSE_SUBPROCESS_PASSWORD", ""));
    }

    @BeforeEach void setup() {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM organization_directory WHERE tenant_id='demo'", Integer.class) == 0) organization.initialize(ADMIN);
        entity = organization.createUnit(ADMIN, OrganizationUnit.Kind.LEGAL_ENTITY, "合成报销子流程法人", null, null, true).id();
        var department = organization.createUnit(ADMIN, OrganizationUnit.Kind.DEPARTMENT, "合成部门", entity, null, true);
        var position = organization.createUnit(ADMIN, OrganizationUnit.Kind.POSITION, "合成岗位", entity, null, true);
        appointment = organization.createAppointment(ADMIN, person("alice", false), department.id(), position.id(), true).id();
        manager = person("manager", true);
        finance = person("finance", true);
        GATEWAY.use(json, entity, String.format("6543210987%010d", SERIAL.incrementAndGet()));
    }
    @AfterEach void clearActor() {
        actors.clear();
        assertThat(GATEWAY.failure()).as("合成端口不隐藏意外支付或无效事实").isNull();
    }
    @AfterAll static void closeGateway() { GATEWAY.close(); }

    @Test void reductionReachesOnlyTheNewChildWhilePaperBudgetAndOriginalResourcesRemainRequired() throws Exception {
        var fixture = create(Layout.COMPLETE);
        submit(fixture);
        var first = child(fixture, "before");
        assertChild(fixture, first, "100.00");
        assertCode(send(taskPath(fixture, task(first.id()).getId(), "receive"), "manager", lifecycle(fixture)), "NOT_FOUND");
        assertCode(send(taskPath(fixture, task(first.id()).getId(), "reduce"), "manager", reduction(fixture)), "NOT_FOUND");
        assertHeld(fixture, 1, "100");
        ok(act(first.id(), "manager", "APPROVE"), 200);
        var firstRound = rounds.findByRound("demo", first.id(), 1).orElseThrow();
        assertThat(task(fixture.applicationId()).getTaskDefinitionKey()).isEqualTo("receipt");
        assertCode(act(fixture.applicationId(), "finance", "APPROVE"), "EXPENSE_PAPER_RECEIPT_REQUIRED");
        receive(fixture);
        ok(act(fixture.applicationId(), "finance", "APPROVE"), 200);
        assertCode(act(fixture.applicationId(), "finance", "APPROVE"), "EXPENSE_BUDGET_NOT_CONFIRMED");
        drivePending(fixture);
        ok(send(taskPath(fixture, task(fixture.applicationId()).getId(), "reduce"), "finance", reduction(fixture)), 200);
        assertCode(act(fixture.applicationId(), "finance", "APPROVE"), "EXPENSE_BUDGET_NOT_CONFIRMED");
        assertHeld(fixture, 1, "80");
        drivePending(fixture);
        ok(act(fixture.applicationId(), "finance", "APPROVE"), 200);
        var last = child(fixture, "after");
        assertChild(fixture, last, "80.00");
        assertThat(rounds.findByRound("demo", first.id(), 1)).contains(firstRound);
        assertThat(application(first.id()).payload().get("total")).isEqualTo("100.00");
        assertThat(rounds.findByRound("demo", fixture.applicationId(), 1).orElseThrow().payload().get("amount")).isEqualTo("100.00");
        assertThat(report(fixture).currentRound().originalLines().get(0).claimedBase()).isEqualTo(money("100"));
        assertThat(report(fixture).currentRound().approvedGross()).isEqualTo(money("80"));
        assertThat(app(fixture).status()).isEqualTo(ApplicationStatus.IN_APPROVAL);
        assertNoSettlement(fixture, 0);

        String path = actionPath(last.id());
        String key = UUID.randomUUID().toString();
        var decision = decision(last.id(), "APPROVE");
        var response = send(path, "manager", key, decision);
        ok(response, 200);
        assertThat(send(path, "manager", key, decision).getContentAsString()).isEqualTo(response.getContentAsString());
        assertThat(app(fixture).status()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(application(last.id()).status()).isEqualTo(ApplicationStatus.APPROVED);
        assertRound(fixture.applicationId(), 1, SubmissionRound.Status.APPROVED);
        assertHeld(fixture, 1, "80");
        assertThat(occupation(fixture).confirmed().position().total()).isEqualTo(money("80"));
        assertNoSettlement(fixture, 1);
        assertThat(GATEWAY.commands()).extracting(BudgetCommand::action).containsExactlyInAnyOrder(BudgetCommand.Action.FREEZE, BudgetCommand.Action.ADJUST);
        assertThat(GATEWAY.writes()).isEqualTo(2);
        assertThat(GATEWAY.queries()).isZero();
    }

    @Test void legacyExecutionIdentityRemainsUnchangedWhenNextCallStarts() throws Exception {
        var fixture = create(Layout.COMPLETE);
        submit(fixture);
        var first = child(fixture, "before");
        var call = calls.findByChild("demo", first.id()).orElseThrow();
        var activity = history.createHistoricActivityInstanceQuery().processInstanceId(call.parentProcessInstanceId())
                .activityId(call.nodeId()).singleResult();
        assertThat(call.activationId()).isEqualTo(activity.getId()).isNotEqualTo(activity.getExecutionId());
        // 合成旧版本的持久化格式；不冒充真实旧二进制升级验收。
        jdbc.update("UPDATE approval_subprocess_call SET activation_id=? WHERE id=?", activity.getExecutionId(), call.id().toString());
        var legacy = calls.findByChild("demo", first.id()).orElseThrow();
        drivePending(fixture);
        ok(act(first.id(), "manager", "APPROVE"), 200);
        receive(fixture);
        ok(act(fixture.applicationId(), "finance", "APPROVE"), 200);
        ok(act(fixture.applicationId(), "finance", "APPROVE"), 200);
        var last = child(fixture, "after");
        assertChild(fixture, last, "100.00");
        assertThat(calls.findByParentRound("demo", fixture.applicationId(), 1)).hasSize(2);
        ok(act(last.id(), "manager", "APPROVE"), 200);
        assertThat(calls.findByChild("demo", first.id())).contains(legacy);
        assertThat(app(fixture).status()).isEqualTo(ApplicationStatus.APPROVED);
        assertNoSettlement(fixture, 1);
    }

    @Test void returnedLastChildKeepsOldFinancialRoundAndResubmissionRequiresNewReceipt() throws Exception {
        var fixture = create(Layout.COMPLETE);
        enterLastChild(fixture);
        var oldFirst = child(fixture, "before");
        var oldLast = child(fixture, "after");
        var oldFinancial = report(fixture).currentRound();
        var oldControl = controls.find("demo", fixture.id(), 1).orElseThrow();
        var oldCalls = calls.findByParentRound("demo", fixture.applicationId(), 1);
        ok(act(oldLast.id(), "manager", "RETURN"), 200);
        assertThat(app(fixture).status()).isEqualTo(ApplicationStatus.RETURNED);
        assertHeld(fixture, 1, "100");
        assertThat(occupation(fixture).pendingOperationId()).isNull();
        verify(fixture.invoice());
        submit(fixture);
        assertThat(app(fixture).roundNo()).isEqualTo(2);
        assertThat(report(fixture).rounds().get(0)).isEqualTo(oldFinancial);
        assertThat(controls.find("demo", fixture.id(), 1)).contains(oldControl);
        assertThat(controls.find("demo", fixture.id(), 2).orElseThrow().receipt()).isNull();
        assertThat(calls.findByParentRound("demo", fixture.applicationId(), 1)).isEqualTo(oldCalls);
        assertThat(application(oldFirst.id()).status()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(application(oldLast.id()).status()).isEqualTo(ApplicationStatus.RETURNED);
        var nextFirst = child(fixture, "before");
        assertThat(nextFirst.id()).isNotEqualTo(oldFirst.id());
        assertHeld(fixture, 2, "100");
        assertThat(requests.find("demo", fixture.prior()).orElseThrow().balance(1).reservedFor(new ExpenseUse(fixture.id(), 1, 1))).isEqualTo(money("0"));
        assertThat(operations.find("demo", occupation(fixture).pendingOperationId()).orElseThrow().input().command().action()).isEqualTo(BudgetCommand.Action.ADJUST);
        drivePending(fixture);
        ok(act(nextFirst.id(), "manager", "APPROVE"), 200);
        assertCode(act(fixture.applicationId(), "finance", "APPROVE"), "EXPENSE_PAPER_RECEIPT_REQUIRED");
        receive(fixture);
        ok(act(fixture.applicationId(), "finance", "APPROVE"), 200);
        ok(act(fixture.applicationId(), "finance", "APPROVE"), 200);
        ok(act(child(fixture, "after").id(), "manager", "APPROVE"), 200);
        assertRound(fixture.applicationId(), 1, SubmissionRound.Status.RETURNED);
        assertRound(fixture.applicationId(), 2, SubmissionRound.Status.APPROVED);
        assertNoSettlement(fixture, 1);
    }

    @Test void childRejectionReleasesLocalResourcesAndQueuesOnlyTheOriginalBudgetRelease() throws Exception {
        var fixture = create(Layout.COMPLETE);
        submit(fixture);
        drivePending(fixture);
        var original = report(fixture).currentRound();
        var child = child(fixture, "before");
        ok(act(child.id(), "manager", "REJECT"), 200);
        assertThat(app(fixture).status()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(application(child.id()).status()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(report(fixture).currentRound()).isEqualTo(original);
        assertReleased(fixture);
        assertThat(occupation(fixture).status()).isEqualTo(BudgetOccupation.Status.FROZEN);
        assertThat(operations.find("demo", occupation(fixture).pendingOperationId()).orElseThrow().input().command().action()).isEqualTo(BudgetCommand.Action.RELEASE);
        assertThat(GATEWAY.writes()).isEqualTo(1);
        drivePending(fixture);
        assertThat(occupation(fixture).status()).isEqualTo(BudgetOccupation.Status.RELEASED);
        assertNoSettlement(fixture, 0);
        assertThat(GATEWAY.writes()).isEqualTo(2);
    }

    @Test void rejectedChildWithUnknownFreezeQueriesTheOriginalCommandBeforeReleasingBudget() throws Exception {
        var fixture = create(Layout.COMPLETE);
        submit(fixture);
        UUID freeze = occupation(fixture).pendingOperationId();
        GATEWAY.budgetStatus(BudgetObservation.Status.PENDING);
        drive(freeze);
        assertThat(operations.find("demo", freeze).orElseThrow().status()).isEqualTo(BudgetOperation.Status.UNKNOWN);
        ok(act(child(fixture, "before").id(), "manager", "REJECT"), 200);
        assertReleased(fixture);
        assertThat(occupation(fixture).pendingOperationId()).isEqualTo(freeze);
        assertThat(GATEWAY.writes()).isEqualTo(1);
        GATEWAY.budgetStatus(BudgetObservation.Status.APPLIED);
        drive(freeze);
        UUID release = occupation(fixture).pendingOperationId();
        assertThat(release).isNotNull().isNotEqualTo(freeze);
        assertThat(GATEWAY.writes(freeze)).isEqualTo(1);
        assertThat(GATEWAY.queries(freeze)).isEqualTo(1);
        drive(release);
        assertThat(occupation(fixture).status()).isEqualTo(BudgetOccupation.Status.RELEASED);
        assertThat(GATEWAY.writes()).isEqualTo(2);
        assertThat(app(fixture).status()).isEqualTo(ApplicationStatus.REJECTED);
        assertNoSettlement(fixture, 0);
    }

    @Test void applicantWithdrawalCancelsActiveChildButPreservesReservationsForNextRound() throws Exception {
        var fixture = create(Layout.COMPLETE);
        submit(fixture);
        drivePending(fixture);
        var original = report(fixture).currentRound();
        var oldChild = child(fixture, "before");
        var budget = occupation(fixture);
        ok(send(path(fixture) + "/withdraw", "alice", lifecycle(fixture)), 200);
        assertThat(app(fixture).status()).isEqualTo(ApplicationStatus.WITHDRAWN);
        assertThat(application(oldChild.id()).status()).isEqualTo(ApplicationStatus.CANCELLED);
        assertHeld(fixture, 1, "100");
        assertThat(occupation(fixture)).isEqualTo(budget);
        verify(fixture.invoice());
        submit(fixture);
        assertThat(report(fixture).rounds().get(0)).isEqualTo(original);
        assertThat(child(fixture, "before").id()).isNotEqualTo(oldChild.id());
        assertHeld(fixture, 2, "100");
        assertRound(fixture.applicationId(), 1, SubmissionRound.Status.WITHDRAWN);
        assertRound(oldChild.id(), 1, SubmissionRound.Status.CANCELLED);
        assertNoSettlement(fixture, 0);
    }

    @Test void terminatingPausedExpenseCancelsChildAndReleasesOriginalFinancialReservations() throws Exception {
        var fixture = create(Layout.COMPLETE);
        submit(fixture);
        drivePending(fixture);
        var child = child(fixture, "before");
        String runtime = "/api/v1/applications/" + fixture.applicationId() + "/rounds/1/runtime";
        ok(send(runtime + "/pause", "admin", Map.of("expectedVersion", app(fixture).version(), "reason", "核对报销原件")), 200);
        assertHeld(fixture, 1, "100");
        ok(send(runtime + "/terminate", "admin", Map.of("expectedVersion", app(fixture).version(), "reason", "重复报销申请终止")), 200);
        assertThat(app(fixture).status()).isEqualTo(ApplicationStatus.CANCELLED);
        assertThat(application(child.id()).status()).isEqualTo(ApplicationStatus.CANCELLED);
        assertReleased(fixture);
        assertRound(fixture.applicationId(), 1, SubmissionRound.Status.CANCELLED);
        assertRound(child.id(), 1, SubmissionRound.Status.CANCELLED);
        drivePending(fixture);
        assertThat(occupation(fixture).status()).isEqualTo(BudgetOccupation.Status.RELEASED);
        assertNoSettlement(fixture, 0);
    }

    @Test void insufficientBudgetReturnsParentAndCancelsActiveChildWithoutReleasingCorrectableResources() throws Exception {
        var fixture = create(Layout.COMPLETE);
        submit(fixture);
        var child = child(fixture, "before");
        GATEWAY.budgetStatus(BudgetObservation.Status.REJECTED);
        drivePending(fixture);
        assertThat(app(fixture).status()).isEqualTo(ApplicationStatus.RETURNED);
        assertThat(application(child.id()).status()).isEqualTo(ApplicationStatus.CANCELLED);
        assertHeld(fixture, 1, "100");
        assertThat(occupation(fixture).status()).isEqualTo(BudgetOccupation.Status.UNFUNDED);
        assertThat(occupation(fixture).pendingOperationId()).isNull();
        assertThat(tasks.createTaskQuery().processInstanceId(instance(child.id())).count()).isZero();
        assertThat(rounds.findByRound("demo", fixture.applicationId(), 1).orElseThrow().completedBy()).isEqualTo("system:budget");
        assertNoSettlement(fixture, 0);
    }

    @Test void failedVoucherPreparationRollsBackFinalChildApprovalAndRetriesTheSameRequest() throws Exception {
        var fixture = create(Layout.COMPLETE);
        enterLastChild(fixture);
        var parent = app(fixture);
        var child = child(fixture, "after");
        var financial = report(fixture).state();
        var budget = occupation(fixture);
        long audit = auditCount(child.id());
        String path = actionPath(child.id());
        String key = UUID.randomUUID().toString();
        var input = decision(child.id(), "APPROVE");
        jdbc.execute("ALTER TABLE voucher_preparation ADD CONSTRAINT ck_expense_subprocess_voucher CHECK (application_id <> '" + parent.id() + "')");
        try {
            assertThatThrownBy(() -> send(path, "manager", key, input)).hasRootCauseInstanceOf(SQLException.class);
            assertThat(app(fixture).version()).isEqualTo(parent.version());
            assertThat(application(child.id()).version()).isEqualTo(child.version());
            assertThat(actionPath(child.id())).isEqualTo(path);
            assertThat(report(fixture).state()).isEqualTo(financial);
            assertThat(occupation(fixture)).isEqualTo(budget);
            assertThat(auditCount(child.id())).isEqualTo(audit);
            assertRound(parent.id(), 1, SubmissionRound.Status.IN_APPROVAL);
            assertRound(child.id(), 1, SubmissionRound.Status.IN_APPROVAL);
            assertThat(history.createHistoricTaskInstanceQuery().processInstanceId(instance(child.id())).finished().count()).isZero();
            assertHeld(fixture, 1, "100");
            assertNoSettlement(fixture, 0);
        } finally {
            jdbc.execute("ALTER TABLE voucher_preparation DROP CONSTRAINT ck_expense_subprocess_voucher");
        }
        ok(send(path, "manager", key, input), 200);
        assertThat(app(fixture).status()).isEqualTo(ApplicationStatus.APPROVED);
        assertNoSettlement(fixture, 1);
    }

    @Test void failedBudgetReleaseRegistrationRestoresLocalResourcesAndBothApprovals() throws Exception {
        var fixture = create(Layout.COMPLETE);
        submit(fixture);
        drivePending(fixture);
        var parent = app(fixture);
        var child = child(fixture, "before");
        var budget = occupation(fixture);
        String path = actionPath(child.id());
        String key = UUID.randomUUID().toString();
        var input = decision(child.id(), "REJECT");
        jdbc.execute("ALTER TABLE budget_occupation ADD CONSTRAINT ck_expense_subprocess_release CHECK (report_id <> '" + fixture.id() + "' OR pending_operation_id IS NULL)");
        try {
            assertThatThrownBy(() -> send(path, "manager", key, input)).hasRootCauseInstanceOf(SQLException.class);
            assertThat(app(fixture).version()).isEqualTo(parent.version());
            assertThat(application(child.id()).version()).isEqualTo(child.version());
            assertThat(actionPath(child.id())).isEqualTo(path);
            assertThat(occupation(fixture)).isEqualTo(budget);
            assertRound(parent.id(), 1, SubmissionRound.Status.IN_APPROVAL);
            assertRound(child.id(), 1, SubmissionRound.Status.IN_APPROVAL);
            assertHeld(fixture, 1, "100");
            assertThat(GATEWAY.writes()).isEqualTo(1);
        } finally {
            jdbc.execute("ALTER TABLE budget_occupation DROP CONSTRAINT ck_expense_subprocess_release");
        }
        ok(send(path, "manager", key, input), 200);
        assertReleased(fixture);
        assertThat(app(fixture).status()).isEqualTo(ApplicationStatus.REJECTED);
        drivePending(fixture);
        assertThat(occupation(fixture).status()).isEqualTo(BudgetOccupation.Status.RELEASED);
        assertNoSettlement(fixture, 0);
    }

    @ParameterizedTest @EnumSource(value = Layout.class, names = {"MISSING_FINANCE", "MISSING_RECEIPT"})
    void ordinaryChildDoesNotReplaceMandatoryExpenseStages(Layout layout) throws Exception {
        var fixture = create(layout);
        UUID checked = ready(fixture);
        var response = send(path(fixture) + "/submit", "alice", submission(fixture, checked));
        assertCode(response, layout == Layout.MISSING_FINANCE ? "EXPENSE_FINANCE_PATH_REQUIRED" : "EXPENSE_RECEIPT_PATH_REQUIRED");
        assertThat(app(fixture).status()).isEqualTo(ApplicationStatus.DRAFT);
        assertThat(report(fixture).rounds()).isEmpty();
        assertThat(rounds.findAll("demo", fixture.applicationId())).isEmpty();
        assertThat(calls.findByParentRound("demo", fixture.applicationId(), 1)).isEmpty();
        assertThat(occupations.find("demo", fixture.id())).isEmpty();
        assertReleased(fixture);
        assertNoSettlement(fixture, 0);
    }

    private Fixture create(Layout layout) throws Exception {
        UUID invoice = original();
        verify(invoice);
        // 原事前额度和已到账借款是明确的合成历史来源；本轮报销及后续审批全部走实际入口。
        var source = Application.restore(UUID.randomUUID(), "demo", "SOURCE-" + UUID.randomUUID(), "prior", 1,
                "alice", "合成原批准依据", Map.of(), ApplicationStatus.APPROVED, 1, 1);
        applications.save(source);
        var prior = new ExpenseRequest(UUID.randomUUID(), "demo", source.id(), entity, "alice",
                List.of(new ExpenseRequest.ApprovedLine(1, money("200"), BigDecimal.ZERO, "synthetic-prior")));
        requests.create(prior, "fixture");
        var advance = new EmployeeAdvance(UUID.randomUUID(), "demo", entity, "alice", money("200"), "synthetic-paid-" + UUID.randomUUID(),
                LocalDate.now(), LocalDate.now().plusDays(30));
        advances.create(advance, "fixture");
        var definition = definition(layout);
        var line = new ExpenseLine(1, "OFFICE", LocalDate.now(), null, "SH", BigDecimal.ONE, ExpenseLine.Unit.ITEM, money("100"), money("6"),
                List.of(invoice), new ExpenseLine.PriorRequestLine(prior.id(), 1), List.of(new CostAllocation("IT", null, money("100"))), "合成办公费用", null);
        var content = new ExpenseContent(entity, ExpenseContent.Type.DAILY, "合成父子报销", List.of(line), List.of(new AdvanceOffset(advance.id(), money("30"))));
        var response = ok(send("/api/v1/expense-reports", "alice", Map.of("businessNo", "EXPSUB-" + UUID.randomUUID(), "processKey", definition.key(),
                "definitionVersion", definition.version(), "content", content)), 201);
        UUID id = UUID.fromString(response.path("id").asText());
        return new Fixture(id, reports.find("demo", id).orElseThrow().applicationId(), invoice, prior.id(), advance.id());
    }

    private DefinitionDraft definition(Layout layout) {
        var childDraft = definitions.create("demo", "expense-child-" + UUID.randomUUID(), "独立报销资料确认",
                linear(List.of(new Node("review", "资料确认", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_" + manager)))),
                new FormSchema(2, List.of(field("total", FormSchema.FieldType.NUMBER), field("currency", FormSchema.FieldType.TEXT))));
        var child = definitions.publish(ADMIN, childDraft.id(), childDraft.revision(), "合成固定子版本");
        var policy = new SubprocessPolicy(child.key(), child.version(), Map.of("total", "amount", "currency", "currency"));
        var nodes = new ArrayList<Node>();
        var access = new HashMap<String, FieldVisibility>();
        nodes.add(new Node("before", "核减前资料", NodeType.SUB_PROCESS, policy.properties()));
        if (layout != Layout.MISSING_RECEIPT) {
            nodes.add(new Node("receipt", "纸质签收", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_" + finance, "expenseStage", "RECEIPT")));
            access.put("receipt", FieldVisibility.READ_ONLY);
        }
        if (layout != Layout.MISSING_FINANCE) {
            nodes.add(new Node("finance", "财务核定", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_" + finance, "expenseStage", "FINANCE_REVIEW")));
            access.put("finance", FieldVisibility.READ_ONLY);
        }
        nodes.add(new Node("after", "核定后资料", NodeType.SUB_PROCESS, policy.properties()));
        var schema = new FormSchema(2, List.of(new FormSchema.Field(ExpenseFormContract.DETAILS, "费用明细", FormSchema.FieldType.TEXT, true,
                null, null, null, null, null, null, null, true, access), field("amount", FormSchema.FieldType.NUMBER),
                field("currency", FormSchema.FieldType.TEXT), field("overPolicy", FormSchema.FieldType.BOOLEAN)));
        return new TransactionTemplate(transactions).execute(status -> {
            var draft = DefinitionDraft.create(UUID.randomUUID(), "demo", "expense-parent-" + UUID.randomUUID(), "合成报销父流程", linear(nodes), schema);
            drafts.save(draft);
            draft.publish(0, drafts.nextVersion("demo", draft.key()));
            drafts.save(draft);
            deployment.deploy(draft);
            return draft;
        });
    }

    private UUID original() throws Exception {
        byte[] bytes = ("%PDF-1.7\nsynthetic-expense-subprocess-" + UUID.randomUUID() + "\n%%EOF").getBytes(StandardCharsets.UTF_8);
        actors.set(APPLICANT);
        try {
            UUID id = wallet.reserve(new InvoiceWalletService.UploadInput("合成报销原件.pdf", (long) bytes.length,
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), InvoiceOriginal.Format.PDF)).id();
            wallet.upload(id, new ByteArrayInputStream(bytes));
            return id;
        } finally { actors.clear(); }
    }
    private void verify(UUID invoice) {
        actors.set(APPLICANT);
        try { verification.queue(invoice, new InvoiceVerificationService.QueueInput(invoices.find("demo", invoice).orElseThrow().version(), entity, target())); }
        finally { actors.clear(); }
        invoiceChecks.poll();
        assertThat(invoices.find("demo", invoice).orElseThrow().verification()).isEqualTo(Invoice.Verification.VERIFIED);
    }
    private UUID ready(Fixture fixture) throws Exception {
        var response = ok(send(path(fixture) + "/precheck", "alice", Map.of("applicationVersion", app(fixture).version(), "financialVersion", report(fixture).version(),
                "initiatorAppointmentId", appointment, "accountingDate", LocalDate.now(), "targetDigest", target())), 202);
        UUID id = UUID.fromString(response.path("id").asText());
        precheckWorker.poll();
        var checked = prechecks.find("demo", id).orElseThrow();
        assertThat(checked.status()).as(json.write(checked.result())).isEqualTo(ExpensePrecheckJob.Status.READY);
        return id;
    }
    private void submit(Fixture fixture) throws Exception { UUID checked = ready(fixture); ok(send(path(fixture) + "/submit", "alice", submission(fixture, checked)), 200); }
    private Map<String, Object> submission(Fixture fixture, UUID checked) {
        return Map.of("applicationVersion", app(fixture).version(), "financialVersion", report(fixture).version(), "precheckId", checked);
    }
    private Map<String, Object> lifecycle(Fixture fixture) {
        return Map.of("applicationVersion", app(fixture).version(), "financialVersion", report(fixture).version(), "comment", "已核对本轮原件");
    }
    private Map<String, Object> reduction(Fixture fixture) {
        return Map.of("applicationVersion", app(fixture).version(), "financialVersion", report(fixture).version(),
                "lines", List.of(Map.of("lineNo", 1, "approvedGross", "80.00", "approvedTax", "4.00")), "reasonCode", "INELIGIBLE_COST", "comment", "核减不合规费用");
    }
    private void receive(Fixture fixture) throws Exception { ok(send(taskPath(fixture, task(fixture.applicationId()).getId(), "receive"), "finance", lifecycle(fixture)), 200); }
    private void enterLastChild(Fixture fixture) throws Exception {
        submit(fixture);
        drivePending(fixture);
        ok(act(child(fixture, "before").id(), "manager", "APPROVE"), 200);
        receive(fixture);
        ok(act(fixture.applicationId(), "finance", "APPROVE"), 200);
        ok(act(fixture.applicationId(), "finance", "APPROVE"), 200);
        assertThat(child(fixture, "after").status()).isEqualTo(ApplicationStatus.IN_APPROVAL);
    }

    /** 只领取本测试指定的原号，恢复时间取实际 nextAttemptAt；实际 HTTP 不进入领取或完成事务。 */
    private void drive(UUID id) {
        var operation = operations.find("demo", id).orElseThrow();
        Instant now = operation.nextAttemptAt().isAfter(Instant.now()) ? operation.nextAttemptAt() : Instant.now();
        var claimed = budgetExecution.claim("demo", id, now);
        assertThat(claimed).isNotNull();
        var input = claimed.input();
        var result = claimed.status() == BudgetOperation.Status.QUERYING ? budgetPort.query(input.targetDigest(), input.command()) : budgetPort.execute(input.targetDigest(), input.command());
        Instant completed = Instant.now();
        budgetExecution.finish(claimed, result, (completed.isAfter(now) ? completed : now).plusMillis(1));
    }
    private void drivePending(Fixture fixture) { UUID id = occupation(fixture).pendingOperationId(); assertThat(id).isNotNull(); drive(id); }
    private BudgetOccupation occupation(Fixture fixture) { return occupations.find("demo", fixture.id()).orElseThrow(); }
    private void assertHeld(Fixture fixture, int round, String amount) {
        var invoice = invoices.find("demo", fixture.invoice()).orElseThrow();
        assertThat(invoice.occupation()).isEqualTo(Invoice.Occupation.OCCUPIED);
        assertThat(invoice.use()).isEqualTo(new ExpenseUse(fixture.id(), round, 1));
        var prior = requests.find("demo", fixture.prior()).orElseThrow().balance(1);
        assertThat(prior.reservedFor(new ExpenseUse(fixture.id(), round, 1))).isEqualTo(money(amount));
        assertThat(prior.available()).isEqualTo(money("200").minus(money(amount)));
        var advance = advances.find("demo", fixture.advance()).orElseThrow().balance();
        assertThat(advance.reservedFor(new ExpenseUse(fixture.id(), round, 0))).isEqualTo(money("30"));
        assertThat(advance.available()).isEqualTo(money("170"));
    }
    private void assertReleased(Fixture fixture) {
        assertThat(invoices.find("demo", fixture.invoice()).orElseThrow().occupation()).isEqualTo(Invoice.Occupation.AVAILABLE);
        assertThat(requests.find("demo", fixture.prior()).orElseThrow().balance(1).available()).isEqualTo(money("200"));
        assertThat(advances.find("demo", fixture.advance()).orElseThrow().balance().available()).isEqualTo(money("200"));
    }
    private void assertNoSettlement(Fixture fixture, int preparations) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM voucher_preparation WHERE tenant_id='demo' AND application_id=?", Integer.class, fixture.applicationId().toString())).isEqualTo(preparations);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM voucher_operation WHERE tenant_id='demo' AND application_id=?", Integer.class, fixture.applicationId().toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_authorization WHERE tenant_id='demo' AND application_id=?", Integer.class, fixture.applicationId().toString())).isZero();
    }
    private void assertChild(Fixture fixture, Application child, String amount) {
        assertThat(child.businessReference()).isNull();
        assertThat(child.payload()).containsOnlyKeys("total", "currency").containsEntry("total", amount).containsEntry("currency", "CNY");
        assertThat(rounds.findByRound("demo", child.id(), 1).orElseThrow().initiatorContext())
                .isEqualTo(rounds.findByRound("demo", fixture.applicationId(), app(fixture).roundNo()).orElseThrow().initiatorContext());
        var call = calls.findByChild("demo", child.id()).orElseThrow();
        var activity = history.createHistoricActivityInstanceQuery().processInstanceId(call.parentProcessInstanceId())
                .activityId(call.nodeId()).singleResult();
        assertThat(activity).isNotNull();
        assertThat(call.activationId()).isEqualTo(activity.getId()).isNotEqualTo(activity.getExecutionId());
        assertThat(activity.getCalledProcessInstanceId()).isEqualTo(call.childProcessInstanceId());
    }
    private void assertRound(UUID id, int round, SubmissionRound.Status status) { assertThat(rounds.findByRound("demo", id, round).orElseThrow().status()).isEqualTo(status); }
    private Application child(Fixture fixture, String node) {
        var matches = calls.findByParentRound("demo", fixture.applicationId(), app(fixture).roundNo()).stream().filter(call -> call.nodeId().equals(node)).toList();
        assertThat(matches).hasSize(1);
        return application(matches.get(0).childApplicationId());
    }
    private ExpenseReport report(Fixture fixture) { return reports.find("demo", fixture.id()).orElseThrow(); }
    private Application app(Fixture fixture) { return application(fixture.applicationId()); }
    private Application application(UUID id) { return applications.findById("demo", id).orElseThrow(); }
    private String instance(UUID id) { return rounds.findByRound("demo", id, application(id).roundNo()).orElseThrow().processInstanceId(); }
    private Task task(UUID id) { return tasks.createTaskQuery().processVariableValueEquals("applicationId", id.toString()).singleResult(); }
    private String path(Fixture fixture) { return "/api/v1/expense-reports/" + fixture.id(); }
    private String taskPath(Fixture fixture, String task, String action) { return path(fixture) + "/tasks/" + task + "/" + action; }
    private long auditCount(UUID id) { return jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE tenant_id='demo' AND application_id=?", Long.class, id.toString()); }
    private String actionPath(UUID id) { return "/api/v1/tasks/" + task(id).getId() + "/actions"; }
    private Map<String, Object> decision(UUID id, String action) { return Map.of("action", action, "expectedVersion", application(id).version(), "comment", "核对本轮资料后办理"); }
    private MockHttpServletResponse act(UUID id, String user, String action) throws Exception { return send(actionPath(id), user, decision(id, action)); }
    private MockHttpServletResponse send(String path, String user, Object input) throws Exception { return send(path, user, UUID.randomUUID().toString(), input); }
    private MockHttpServletResponse send(String path, String user, String key, Object input) throws Exception {
        return mvc.perform(post(path).header("Authorization", "Bearer " + auth.login("demo", user, "demo").token()).header("Idempotency-Key", key)
                .contentType("application/json").content(json.write(input))).andReturn().getResponse();
    }
    private JsonNode ok(MockHttpServletResponse response, int status) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(status);
        return json.read(response.getContentAsString(StandardCharsets.UTF_8), JsonNode.class);
    }
    private void assertCode(MockHttpServletResponse response, String code) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString(StandardCharsets.UTF_8)).isBetween(400, 499);
        assertThat(json.read(response.getContentAsString(StandardCharsets.UTF_8), JsonNode.class).path("code").asText()).isEqualTo(code);
    }
    private String target() { return configuration.destination("demo").orElseThrow().digest("demo"); }
    private UUID person(String subject, boolean approver) {
        var found = jdbc.queryForList("SELECT id FROM organization_person WHERE tenant_id='demo' AND subject=?", String.class, subject);
        return found.isEmpty() ? organization.createPerson(ADMIN, subject, subject, true, approver).id() : UUID.fromString(found.get(0));
    }
    private static Money money(String amount) { return new Money(new BigDecimal(amount), "CNY"); }
    private static FormSchema.Field field(String key, FormSchema.FieldType type) { return new FormSchema.Field(key, key, type, true, null, null, null, null, null); }
    private static Graph linear(List<Node> steps) {
        var nodes = new ArrayList<Node>();
        nodes.add(new Node("start", "开始", NodeType.START, Map.of()));
        nodes.addAll(steps);
        nodes.add(new Node("end", "结束", NodeType.END, Map.of()));
        var edges = new ArrayList<Edge>();
        for (int index = 1; index < nodes.size(); index++) edges.add(new Edge("edge" + index, nodes.get(index - 1).id(), nodes.get(index).id(), ""));
        return new Graph(nodes, edges);
    }
    private enum Layout { COMPLETE, MISSING_FINANCE, MISSING_RECEIPT }
    private record Fixture(UUID id, UUID applicationId, UUID invoice, UUID prior, UUID advance) { }
}
