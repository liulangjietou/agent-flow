package io.agentflow.expense;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import io.agentflow.finance.*;
import io.agentflow.form.FieldVisibility;
import io.agentflow.form.FormSchema;
import io.agentflow.organization.OrganizationService;
import io.agentflow.organization.OrganizationUnit;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 发布定义、认证 HTTP、实际引擎、资源台账与合成财务 HTTP 联合验证正式提交及审批控制。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "agentflow.finance-gateway.enabled=true",
        "agentflow.invoices.verification-worker-enabled=false", "agentflow.expenses.precheck-worker-enabled=false",
        "agentflow.budgets.worker-enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ExpenseSubmissionIntegrationTest {
    private static final HttpServer SERVER = server();
    private static final String ENDPOINT = "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/finance";
    private static final AtomicReference<ExpenseSubmissionIntegrationTest> ACTIVE = new AtomicReference<>();
    private static final java.util.concurrent.atomic.AtomicInteger SERIAL = new java.util.concurrent.atomic.AtomicInteger();
    private static final Path DIRECTORY = Path.of("/fyoung/tmp/agentflow-expense-submission-" + UUID.randomUUID());
    private final Actor admin = new Actor("demo", "admin", Set.of("ADMIN", "PROCESS_ADMIN"));
    private final Map<UUID, BudgetCommand> commands = new ConcurrentHashMap<>();
    private final List<UUID> created = new ArrayList<>();
    private UUID entity;
    private UUID appointment;
    private UUID manager;
    private UUID finance;
    private final String invoiceNumber = String.format("1234567890%010d", SERIAL.incrementAndGet());
    private boolean paperRequired = true;
    private boolean financeStage = true;
    private boolean afterFinanceTask;
    private boolean reductionRoute;
    private BudgetObservation.Status budgetStatus = BudgetObservation.Status.APPLIED;
    private BudgetObservation.Rejection budgetRejection = BudgetObservation.Rejection.BUDGET_INSUFFICIENT;
    private int writes;
    private int queries;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("agentflow.attachments.directory", DIRECTORY::toString);
        registry.add("agentflow.finance-gateway.tenants.demo.endpoint", () -> ENDPOINT);
        registry.add("agentflow.finance-gateway.tenants.demo.allow-unauthenticated-loopback", () -> "true");
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_SUBMISSION_TEST_URL", "jdbc:h2:mem:expense-submission;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_SUBMISSION_TEST_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_SUBMISSION_TEST_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_SUBMISSION_TEST_PASSWORD", ""));
    }
    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @Autowired AuthService auth;
    @Autowired CurrentActor actors;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrganizationService organization;
    @Autowired DefinitionApplicationService definitions;
    @Autowired ApplicationRepository applications;
    @Autowired ExpenseReportRepository reports;
    @Autowired InvoiceRepository invoices;
    @Autowired ExpenseRequestRepository requests;
    @Autowired EmployeeAdvanceRepository advances;
    @Autowired TaskService tasks;
    @Autowired org.flowable.engine.RuntimeService runtime;
    @Autowired io.agentflow.approval.repository.SubmissionRoundRepository rounds;
    @Autowired InvoiceWalletService wallet;
    @Autowired InvoiceVerificationService verification;
    @Autowired InvoiceVerificationWorker invoiceWorker;
    @Autowired ExpensePrecheckWorker precheckWorker;
    @Autowired JdbcExpensePrecheckRepository prechecks;
    @Autowired JdbcExpenseSubmissionControlRepository controls;
    @Autowired FinanceGatewayConfiguration configuration;
    @Autowired BudgetOperationWorker budgetWorker;
    @Autowired BudgetOperationService budgetExecution;
    @Autowired JdbcBudgetOperationRepository operations;
    @Autowired JdbcBudgetOccupationRepository occupations;
    @Autowired BudgetSystemPort budgetPort;

    @BeforeEach void setup() {
        ACTIVE.set(this); configuration.setEnabled(true);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM organization_directory WHERE tenant_id='demo'", Integer.class) == 0) organization.initialize(admin);
        entity = organization.createUnit(admin, OrganizationUnit.Kind.LEGAL_ENTITY, "合成财务提交法人", null, null, true).id();
        var department = organization.createUnit(admin, OrganizationUnit.Kind.DEPARTMENT, "合成部门", entity, null, true);
        var position = organization.createUnit(admin, OrganizationUnit.Kind.POSITION, "合成岗位", entity, null, true);
        appointment = organization.createAppointment(admin, person("alice", false), department.id(), position.id(), true).id();
        manager = person("manager", true); finance = person("finance", true);
    }
    @AfterEach void clear() {
        actors.clear(); budgetStatus = BudgetObservation.Status.APPLIED;
        for (UUID report : created) for (int index = 0; index < 4; index++) {
            var occupation = occupations.find("demo", report).orElse(null);
            if (occupation == null || occupation.pendingOperationId() == null) break;
            drive(occupation.pendingOperationId());
        }
    }
    @AfterAll static void closeServer() { SERVER.stop(0); }

    @Test
    void formalSubmissionReplaysOnceAndRequiresExplicitReceiptThenActualBudgetFreeze() throws Exception {
        var fixture = fixture(true); var report = fixture.report(); UUID checked = precheck(report);
        var preview = prechecks.find("demo", checked).orElseThrow().result().evidence().preview();
        String key = UUID.randomUUID().toString(); var input = submitInput(report, checked);
        var first = send(path(report) + "/submit", "alice", key, input); JsonNode receipt = ok(first, 200);
        assertThat(send(path(report) + "/submit", "alice", key, input).getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(writes).isZero(); assertThat(receipt.path("applicationVersion").asLong()).isEqualTo(3);
        assertThat(receipt.path("financialVersion").asLong()).isEqualTo(2);
        var frozen = current(report); assertThat(frozen.currentRound().submittedAt()).isAfterOrEqualTo(preview.submittedAt());
        assertThat(app(report).payload().get("amount")).isEqualTo("100.00");
        assertThat(invoices.find("demo", fixture.invoice()).orElseThrow().occupation()).isEqualTo(Invoice.Occupation.OCCUPIED);
        assertThat(requests.find("demo", fixture.prior()).orElseThrow().balance(1).available()).isEqualTo(money("100"));
        assertThat(advances.find("demo", fixture.advance()).orElseThrow().balance().available()).isEqualTo(money("150"));
        ok(act(report, "manager", "APPROVE"), 200);
        assertCode(act(report, "finance", "APPROVE"), "EXPENSE_PAPER_RECEIPT_REQUIRED");
        String receiptTask = task(report).getId();
        assertThat(send(path(report) + "/tasks/" + receiptTask + "/receive", "manager", receiveInput(report)).getStatus()).isEqualTo(403);
        ok(send(path(report) + "/tasks/" + receiptTask + "/receive", "finance", receiveInput(report)), 200);
        assertCode(send(path(report) + "/tasks/" + receiptTask + "/receive", "finance", receiveInput(report)), "EXPENSE_RECEIPT_NOT_ALLOWED");
        ok(act(report, "finance", "APPROVE"), 200);
        assertCode(act(report, "finance", "APPROVE"), "EXPENSE_BUDGET_NOT_CONFIRMED");
        budgetWorker.poll(); assertThat(writes).isEqualTo(1);
        ok(act(report, "finance", "APPROVE"), 200);
        assertThat(app(report).status()).isEqualTo(ApplicationStatus.APPROVED); assertThat(tasks(report)).isEmpty();
        assertThat(occupations.find("demo", report.id()).orElseThrow().status()).isEqualTo(BudgetOccupation.Status.FROZEN);
        assertThat(frozen.rounds()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_operation WHERE report_id=?", Integer.class, report.id().toString())).isEqualTo(1);
    }

    @Test
    void stalePrechecksForeignActorsUnknownFieldsAndMissingFinancePathCannotStartApproval() throws Exception {
        var report = fixture(false).report(); UUID checked = precheck(report); var input = submitInput(report, checked);
        assertThat(send(path(report) + "/submit", "admin", input).getStatus()).isEqualTo(404);
        assertThat(send(path(report) + "/submit", "bob", input).getStatus()).isEqualTo(404);
        var forged = new HashMap<String, Object>(input); forged.put("budgetApproved", true);
        assertThat(send(path(report) + "/submit", "alice", forged).getStatus()).isEqualTo(400);
        forged = new HashMap<>(input); forged.put("financialVersion", 99);
        assertCode(send(path(report) + "/submit", "alice", forged), "CONCURRENCY_CONFLICT");
        precheck(report);
        assertCode(send(path(report) + "/submit", "alice", input), "PRECHECK_SUPERSEDED");
        assertThat(current(report).version()).isEqualTo(1); assertThat(tasks(report)).isEmpty();
        financeStage = false; var invalid = fixture(false).report(); UUID invalidChecked = precheck(invalid);
        assertCode(send(path(invalid) + "/submit", "alice", submitInput(invalid, invalidChecked)), "EXPENSE_FINANCE_PATH_REQUIRED");
        assertThat(app(invalid).status()).isEqualTo(ApplicationStatus.DRAFT);
        assertThat(occupations.find("demo", invalid.id())).isEmpty(); assertThat(tasks(invalid)).isEmpty();
    }

    @Test
    void differentConcurrentRequestKeysStillCreateOnlyOneRoundAndOneBudgetCommand() throws Exception {
        var fixture = fixture(true); var report = fixture.report(); UUID checked = precheck(report); var input = submitInput(report, checked);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2); var start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Integer> submit = () -> { start.await(); return send(path(report) + "/submit", "alice", input).getStatus(); };
            var first = pool.submit(submit); var second = pool.submit(submit); start.countDown();
            assertThat(List.of(first.get(15, java.util.concurrent.TimeUnit.SECONDS), second.get(15, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        } finally { start.countDown(); pool.shutdownNow(); }
        assertThat(current(report).rounds()).hasSize(1); assertThat(tasks(report)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_operation WHERE report_id=?", Integer.class, report.id().toString())).isEqualTo(1);
        assertThat(invoices.find("demo", fixture.invoice()).orElseThrow().version()).isEqualTo(3);
    }

    @Test
    void optionalPaperSkipsReceiptButOtherBudgetRejectionsCannotPassFinanceOrPretendToBeInsufficient() throws Exception {
        paperRequired = false; var report = fixture(false).report(); submit(report);
        ok(act(report, "manager", "APPROVE"), 200);
        assertCode(send(path(report) + "/tasks/" + task(report).getId() + "/receive", "finance", receiveInput(report)), "EXPENSE_RECEIPT_NOT_ALLOWED");
        ok(act(report, "finance", "APPROVE"), 200);
        budgetStatus = BudgetObservation.Status.REJECTED; budgetRejection = BudgetObservation.Rejection.ACCOUNTING_PERIOD_CLOSED;
        budgetWorker.poll();
        assertThat(app(report).status()).isEqualTo(ApplicationStatus.IN_APPROVAL);
        assertCode(act(report, "finance", "APPROVE"), "EXPENSE_BUDGET_NOT_CONFIRMED");
        ok(act(report, "finance", "RETURN"), 200); assertThat(app(report).status()).isEqualTo(ApplicationStatus.RETURNED);
    }

    @Test
    void applicantCannotWithdrawOnceTheRoundHasEnteredFinancialReview() throws Exception {
        paperRequired = false; afterFinanceTask = true; var report = fixture(false).report(); submit(report);
        ok(act(report, "manager", "APPROVE"), 200); ok(act(report, "finance", "APPROVE"), 200);
        long version = app(report).version(); String financeTask = task(report).getId();
        assertCode(send(path(report) + "/withdraw", "alice", lifecycleInput(report)), "EXPENSE_WITHDRAWAL_NOT_ALLOWED");
        assertThat(app(report).version()).isEqualTo(version); assertThat(app(report).status()).isEqualTo(ApplicationStatus.IN_APPROVAL);
        assertThat(task(report).getId()).isEqualTo(financeTask);
        budgetWorker.poll(); ok(act(report, "finance", "APPROVE"), 200);
        assertThat(task(report).getTaskDefinitionKey()).isEqualTo("afterFinance");
        assertCode(send(path(report) + "/withdraw", "alice", lifecycleInput(report)), "EXPENSE_WITHDRAWAL_NOT_ALLOWED");
        assertThat(app(report).status()).isEqualTo(ApplicationStatus.IN_APPROVAL);
    }

    @Test
    void pendingBudgetRollsBackLateResubmissionIncludingEngineRoundAndResourceVersions() throws Exception {
        var report = fixture(false).report(); submit(report);
        ok(send(path(report) + "/withdraw", "alice", lifecycleInput(report)), 200);
        long appVersion = app(report).version(); UUID checked = precheck(current(report));
        assertCode(send(path(report) + "/submit", "alice", submitInput(current(report), checked)), "BUDGET_OPERATION_PENDING");
        assertThat(app(report).status()).isEqualTo(ApplicationStatus.WITHDRAWN);
        assertThat(app(report).version()).isEqualTo(appVersion); assertThat(current(report).version()).isEqualTo(2);
        assertThat(current(report).rounds()).hasSize(1); assertThat(controls.find("demo", report.id(), 2)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_submission_round WHERE application_id=?", Integer.class, report.applicationId().toString())).isEqualTo(1);
        assertThat(tasks(report)).isEmpty(); assertThat(writes).isZero();
    }

    @Test
    void resubmissionMovesReservationsAndRequiresANewReceiptAndAnAdjustedBudget() throws Exception {
        var fixture = fixture(true); var report = fixture.report(); submit(report); budgetWorker.poll();
        ok(act(report, "manager", "APPROVE"), 200);
        ok(send(path(report) + "/tasks/" + task(report).getId() + "/receive", "finance", receiveInput(report)), 200);
        var old = controls.find("demo", report.id(), 1).orElseThrow();
        ok(send(path(report) + "/withdraw", "alice", lifecycleInput(report)), 200);
        verify(fixture.invoice()); submit(current(report));
        assertThat(app(report).roundNo()).isEqualTo(2);
        assertThat(controls.find("demo", report.id(), 1).orElseThrow()).isEqualTo(old);
        assertThat(controls.find("demo", report.id(), 2).orElseThrow().receipt()).isNull();
        assertThat(invoices.find("demo", fixture.invoice()).orElseThrow().use().roundNo()).isEqualTo(2);
        assertThat(requests.find("demo", fixture.prior()).orElseThrow().balance(1).reservedFor(new ExpenseUse(report.id(), 1, 1))).isEqualTo(money("0"));
        assertThat(advances.find("demo", fixture.advance()).orElseThrow().balance().reservedFor(new ExpenseUse(report.id(), 2, 0))).isEqualTo(money("50"));
        var pending = occupations.find("demo", report.id()).orElseThrow();
        assertThat(operations.find("demo", pending.pendingOperationId()).orElseThrow().input().command().action()).isEqualTo(BudgetCommand.Action.ADJUST);
        budgetWorker.poll(); assertThat(writes).isEqualTo(2);
        ok(act(report, "manager", "APPROVE"), 200);
        assertCode(act(report, "finance", "APPROVE"), "EXPENSE_PAPER_RECEIPT_REQUIRED");
    }

    @Test
    void budgetInsufficiencyReturnsActualRoundAndKeepsReservationsForCorrection() throws Exception {
        var fixture = fixture(true); var report = fixture.report(); submit(report);
        budgetStatus = BudgetObservation.Status.REJECTED; budgetWorker.poll();
        assertThat(app(report).status()).isEqualTo(ApplicationStatus.RETURNED); assertThat(tasks(report)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT completed_by FROM approval_submission_round WHERE application_id=? AND round_no=1", String.class, report.applicationId().toString())).isEqualTo("system:budget");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='RETURN' AND actor_id='system:budget'", Integer.class, report.applicationId().toString())).isEqualTo(1);
        assertThat(invoices.find("demo", fixture.invoice()).orElseThrow().occupation()).isEqualTo(Invoice.Occupation.OCCUPIED);
        assertThat(occupations.find("demo", report.id()).orElseThrow().status()).isEqualTo(BudgetOccupation.Status.UNFUNDED);
        assertThat(current(report).version()).isEqualTo(2);
    }

    @Test
    void rejectionDuringUnknownFreezeReleasesLocalResourcesThenQueriesBeforeExternalRelease() throws Exception {
        var fixture = fixture(true); var report = fixture.report(); submit(report);
        budgetStatus = BudgetObservation.Status.PENDING; budgetWorker.poll();
        UUID pending = occupations.find("demo", report.id()).orElseThrow().pendingOperationId();
        assertThat(operations.find("demo", pending).orElseThrow().status()).isEqualTo(BudgetOperation.Status.UNKNOWN);
        ok(act(report, "manager", "REJECT"), 200);
        assertThat(invoices.find("demo", fixture.invoice()).orElseThrow().occupation()).isEqualTo(Invoice.Occupation.AVAILABLE);
        assertThat(requests.find("demo", fixture.prior()).orElseThrow().balance(1).available()).isEqualTo(money("200"));
        assertThat(advances.find("demo", fixture.advance()).orElseThrow().balance().available()).isEqualTo(money("200"));
        assertThat(writes).isEqualTo(1); assertThat(queries).isZero();
        budgetStatus = BudgetObservation.Status.APPLIED; drive(pending);
        var release = occupations.find("demo", report.id()).orElseThrow().pendingOperationId();
        assertThat(release).isNotNull().isNotEqualTo(pending); assertThat(queries).isEqualTo(1); assertThat(writes).isEqualTo(1);
        assertThat(operations.find("demo", release).orElseThrow().input().command().action()).isEqualTo(BudgetCommand.Action.RELEASE);
        drive(release);
        assertThat(occupations.find("demo", report.id()).orElseThrow().status()).isEqualTo(BudgetOccupation.Status.RELEASED);
        assertThat(app(report).status()).isEqualTo(ApplicationStatus.REJECTED); assertThat(writes).isEqualTo(2);
    }

    @Test
    void delegatedReceiptCannotSignAndCancellationIgnoresUnsubmittedResourceReferences() throws Exception {
        var fixture = fixture(true); var report = fixture.report(); submit(report); budgetWorker.poll();
        ok(act(report, "manager", "APPROVE"), 200);
        String receiptTask = task(report).getId();
        ok(send("/api/v1/tasks/" + receiptTask + "/actions", "finance", Map.of("action", "DELEGATE", "targetUser", "manager", "expectedVersion", app(report).version())), 200);
        assertCode(send(path(report) + "/tasks/" + receiptTask + "/receive", "manager", receiveInput(report)), "TASK_DELEGATION_PENDING");
        ok(send(path(report) + "/withdraw", "alice", lifecycleInput(report)), 200);
        var draft = new ExpenseContent(entity, ExpenseContent.Type.DAILY, "补正草稿", List.of(line(UUID.randomUUID(), UUID.randomUUID())), List.of());
        ok(send(path(report) + "/revise", "alice", Map.of("applicationVersion", app(report).version(), "financialVersion", current(report).version(), "content", draft)), 200);
        ok(send(path(report) + "/cancel", "alice", lifecycleInput(report)), 200);
        assertThat(app(report).status()).isEqualTo(ApplicationStatus.CANCELLED);
        assertThat(invoices.find("demo", fixture.invoice()).orElseThrow().occupation()).isEqualTo(Invoice.Occupation.AVAILABLE);
        assertThat(requests.find("demo", fixture.prior()).orElseThrow().balance(1).available()).isEqualTo(money("200"));
        budgetWorker.poll(); assertThat(occupations.find("demo", report.id()).orElseThrow().status()).isEqualTo(BudgetOccupation.Status.RELEASED);
    }

    @Test
    void financialReductionAdjustsReservationsRoutesAndBudgetWithoutReplacingTheSubmission() throws Exception {
        reductionRoute = true; var fixture = fixture(true); var report = fixture.report(); enterFinance(report);
        var before = current(report); var original = rounds.findByRound("demo", report.applicationId(), 1).orElseThrow();
        long applicationVersion = app(report).version(); String taskId = task(report).getId();
        String key = UUID.randomUUID().toString(); var input = reductionInput(report, "25", "1");
        var receipt = ok(send(reductionPath(report), "finance", key, input), 200);
        assertThat(ok(send(reductionPath(report), "finance", key, input), 200)).isEqualTo(receipt);
        var reduced = current(report);
        assertThat(reduced.version()).isEqualTo(before.version() + 1); assertThat(app(report).version()).isEqualTo(applicationVersion + 1);
        assertThat(reduced.rounds()).hasSize(1); assertThat(reduced.currentRound().originalLines()).isEqualTo(before.currentRound().originalLines());
        assertThat(rounds.findByRound("demo", report.applicationId(), 1).orElseThrow()).isEqualTo(original);
        assertThat(reduced.currentRound().adjustments()).hasSize(1);
        assertThat(reduced.currentRound().approvedGross()).isEqualTo(money("25")); assertThat(reduced.currentRound().offsetTotal()).isEqualTo(money("25"));
        assertThat(reduced.currentRound().payable()).isEqualTo(money("0"));
        assertThat(invoices.find("demo", fixture.invoice()).orElseThrow().occupation()).isEqualTo(Invoice.Occupation.OCCUPIED);
        assertThat(requests.find("demo", fixture.prior()).orElseThrow().balance(1).reservedFor(new ExpenseUse(report.id(), 1, 1))).isEqualTo(money("25"));
        assertThat(advances.find("demo", fixture.advance()).orElseThrow().balance().reservedFor(new ExpenseUse(report.id(), 1, 0))).isEqualTo(money("25"));
        assertThat(task(report).getId()).isEqualTo(taskId); assertThat(app(report).payload().get("amount")).isEqualTo("25.00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND aggregate_type='Task' AND aggregate_id=? AND action='EXPENSE_REDUCE' AND actor_id='finance' AND aggregate_version=?", Integer.class,
                report.applicationId().toString(), taskId, app(report).version())).isEqualTo(1);
        assertThat(((Map<?, ?>) runtime.getVariable(task(report).getProcessInstanceId(), "formData")).get("amount")).isEqualTo("25.00");
        var operation = operations.find("demo", UUID.fromString(receipt.path("budgetOperationId").asText())).orElseThrow();
        assertThat(operation.input().command().action()).isEqualTo(BudgetCommand.Action.ADJUST);
        assertThat(operation.input().command().position().total()).isEqualTo(money("25"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE application_id=? AND kind='EXPENSE_ADJUSTED'", Integer.class, report.applicationId().toString())).isEqualTo(1);
        assertCode(act(report, "finance", "APPROVE"), "EXPENSE_BUDGET_NOT_CONFIRMED");
        budgetWorker.poll(); ok(act(report, "finance", "APPROVE"), 200);
        // 原金额 100 会进入额外审批；实际执行直接结束，证明引擎条件读取了 25。
        assertThat(app(report).status()).isEqualTo(ApplicationStatus.APPROVED); assertThat(tasks(report)).isEmpty();
    }

    @Test
    void zeroReductionReleasesLocalUsesButStillRequiresConfirmedBudgetAdjustment() throws Exception {
        var fixture = fixture(true); var report = fixture.report(); enterFinance(report);
        var input = reductionInput(report, "0", "0"); ok(send(reductionPath(report), "finance", input), 200);
        assertThat(invoices.find("demo", fixture.invoice()).orElseThrow().occupation()).isEqualTo(Invoice.Occupation.AVAILABLE);
        assertThat(requests.find("demo", fixture.prior()).orElseThrow().balance(1).available()).isEqualTo(money("200"));
        assertThat(advances.find("demo", fixture.advance()).orElseThrow().balance().available()).isEqualTo(money("200"));
        assertThat(current(report).currentRound().approvedGross()).isEqualTo(money("0"));
        assertCode(act(report, "finance", "APPROVE"), "EXPENSE_BUDGET_NOT_CONFIRMED");
        budgetWorker.poll(); ok(act(report, "finance", "APPROVE"), 200);
    }

    @Test
    void reductionRequiresActualFinancialTaskAuthorityStrictFieldsAndCurrentDoubleVersions() throws Exception {
        var report = fixture(false).report(); submit(report); budgetWorker.poll();
        assertCode(send(reductionPath(report), "manager", reductionInput(report, "50", "3")), "EXPENSE_FINANCE_TASK_REQUIRED");
        ok(act(report, "manager", "APPROVE"), 200); ok(send(path(report) + "/tasks/" + task(report).getId() + "/receive", "finance", receiveInput(report)), 200);
        ok(act(report, "finance", "APPROVE"), 200); var original = current(report).state(); long version = app(report).version();
        for (String user : List.of("alice", "admin", "manager")) assertThat(send(reductionPath(report), user, reductionInput(report, "50", "3")).getStatus()).isBetween(400, 499);
        var foreign = fixture(false).report();
        assertThat(send(path(foreign) + "/tasks/" + task(report).getId() + "/reduce", "finance", reductionInput(report, "50", "3")).getStatus()).isEqualTo(404);
        var forged = new HashMap<String, Object>(reductionInput(report, "50", "3")); forged.put("actor", "alice");
        assertThat(send(reductionPath(report), "finance", forged).getStatus()).isEqualTo(400);
        forged = new HashMap<>(reductionInput(report, "50", "3")); forged.put("lines", List.of(Map.of("lineNo", 1, "approvedGross", "50", "approvedTax", "3", "currency", "USD")));
        assertThat(send(reductionPath(report), "finance", forged).getStatus()).isEqualTo(400);
        for (String field : List.of("applicationVersion", "financialVersion")) {
            forged = new HashMap<>(reductionInput(report, "50", "3")); forged.put(field, 999);
            assertCode(send(reductionPath(report), "finance", forged), "CONCURRENCY_CONFLICT");
        }
        assertCode(send(reductionPath(report), "finance", reductionInput(report, "101", "6")), "INVALID_EXPENSE_REDUCTION");
        assertCode(send(reductionPath(report), "finance", reductionInput(report, "100", "6")), "INVALID_EXPENSE_REDUCTION");
        assertThat(current(report).state()).isEqualTo(original); assertThat(app(report).version()).isEqualTo(version);
        String taskId = task(report).getId();
        ok(send("/api/v1/tasks/" + taskId + "/actions", "finance", Map.of("action", "DELEGATE", "targetUser", "manager", "expectedVersion", version)), 200);
        assertCode(send(reductionPath(report), "manager", reductionInput(report, "50", "3")), "TASK_DELEGATION_PENDING");
    }

    @Test
    void concurrentReductionsOnlyAppendOneAdjustmentAndPendingBudgetBlocksAnother() throws Exception {
        var report = fixture(false).report(); enterFinance(report); var input = reductionInput(report, "40", "2"); String path = reductionPath(report);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var results = executor.invokeAll(List.<java.util.concurrent.Callable<MockHttpServletResponse>>of(
                    () -> send(path, "finance", input), () -> send(path, "finance", input)));
            assertThat(results.stream().map(value -> { try { return value.get().getStatus(); } catch (Exception failure) { throw new IllegalStateException(failure); } }).toList())
                    .containsExactlyInAnyOrder(200, 409);
        } finally { executor.shutdownNow(); }
        assertThat(current(report).currentRound().adjustments()).hasSize(1);
        assertCode(send(path, "finance", reductionInput(report, "20", "1")), "EXPENSE_BUDGET_NOT_CONFIRMED");
        budgetWorker.poll(); ok(send(path, "finance", reductionInput(report, "20", "1")), 200);
        assertThat(current(report).currentRound().adjustments()).hasSize(2);
    }

    @Test
    void lateInstanceBindingFailureRollsBackReductionResourcesAuditAndNotification() throws Exception {
        var fixture = fixture(true); var report = fixture.report(); enterFinance(report);
        var before = current(report).state(); var application = app(report); var task = task(report);
        var invoice = invoices.find("demo", fixture.invoice()).orElseThrow().state();
        var request = requests.find("demo", fixture.prior()).orElseThrow().state();
        var advance = advances.find("demo", fixture.advance()).orElseThrow().state();
        var duplicate = runtime.createProcessInstanceBuilder().processDefinitionId(task.getProcessDefinitionId()).tenantId("demo")
                .businessKey("synthetic-duplicate").variables(Map.of("tenantId", "demo", "applicationId", application.id().toString(), "roundNo", 1, "formData", application.payload())).start();
        try {
            assertCode(send(path(report) + "/tasks/" + task.getId() + "/reduce", "finance", reductionInput(report, "0", "0")), "CONCURRENCY_CONFLICT");
            assertThat(current(report).state()).isEqualTo(before); assertThat(app(report).version()).isEqualTo(application.version());
            assertThat(invoices.find("demo", fixture.invoice()).orElseThrow().state()).isEqualTo(invoice);
            assertThat(requests.find("demo", fixture.prior()).orElseThrow().state()).isEqualTo(request);
            assertThat(advances.find("demo", fixture.advance()).orElseThrow().state()).isEqualTo(advance);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_report_revision WHERE report_id=? AND operation='REDUCE'", Integer.class, report.id().toString())).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE application_id=? AND kind='EXPENSE_ADJUSTED'", Integer.class, application.id().toString())).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_operation WHERE report_id=?", Integer.class, report.id().toString())).isEqualTo(1);
            assertThat(((Map<?, ?>) runtime.getVariable(task.getProcessInstanceId(), "formData")).get("amount")).isEqualTo("100.00");
        } finally { runtime.deleteProcessInstance(duplicate.getId(), "synthetic cleanup"); }
    }

    @Test
    void reductionRejectsJsonNumbersAndExponentStringsBeforeChangingFinancialFacts() throws Exception {
        var report = fixture(false).report(); enterFinance(report); var before = current(report).state();
        for (Object gross : List.of(50, 49.99, "5e1", "50.001")) {
            var input = new HashMap<>(reductionInput(report, "50", "3"));
            input.put("lines", List.of(Map.of("lineNo", 1, "approvedGross", gross, "approvedTax", "3")));
            assertThat(send(reductionPath(report), "finance", input).getStatus()).isBetween(400, 499);
            assertThat(current(report).state()).isEqualTo(before);
        }
    }

    private void enterFinance(ExpenseReport report) throws Exception {
        submit(report); budgetWorker.poll(); ok(act(report, "manager", "APPROVE"), 200);
        ok(send(path(report) + "/tasks/" + task(report).getId() + "/receive", "finance", receiveInput(report)), 200);
        ok(act(report, "finance", "APPROVE"), 200);
    }
    private String reductionPath(ExpenseReport report) { return path(report) + "/tasks/" + task(report).getId() + "/reduce"; }
    private Map<String, Object> reductionInput(ExpenseReport report, String gross, String tax) {
        return Map.of("applicationVersion", app(report).version(), "financialVersion", current(report).version(),
                "lines", List.of(Map.of("lineNo", 1, "approvedGross", gross, "approvedTax", tax)), "reasonCode", "INELIGIBLE_COST", "comment", "合成核减原因");
    }

    private Fixture fixture(boolean withResources) throws Exception {
        UUID invoice = withResources ? original() : null; UUID priorId = null; UUID advanceId = null;
        if (withResources) {
            verify(invoice);
            var source = Application.restore(UUID.randomUUID(), "demo", "SYNTHETIC-" + UUID.randomUUID(), "prior", 1, "alice", "合成批准事实", Map.of(), ApplicationStatus.APPROVED, 1, 1);
            applications.save(source);
            var prior = new ExpenseRequest(UUID.randomUUID(), "demo", source.id(), entity, "alice", List.of(new ExpenseRequest.ApprovedLine(1, money("200"), BigDecimal.ZERO, "synthetic")));
            requests.create(prior, "fixture"); priorId = prior.id();
            var advance = new EmployeeAdvance(UUID.randomUUID(), "demo", entity, "alice", money("200"), "synthetic-payment-" + UUID.randomUUID(), LocalDate.now(), LocalDate.now().plusDays(30));
            advances.create(advance, "fixture"); advanceId = advance.id();
        }
        var definition = definition(); var content = new ExpenseContent(entity, ExpenseContent.Type.DAILY, "合成正式报销",
                List.of(line(invoice, priorId)), advanceId == null ? List.of() : List.of(new AdvanceOffset(advanceId, money("50"))));
        var response = ok(send("/api/v1/expense-reports", "alice", Map.of("businessNo", "SYNTHETIC-" + UUID.randomUUID(), "processKey", definition.key(), "definitionVersion", definition.version(), "content", content)), 201);
        UUID id = UUID.fromString(response.path("id").asText()); created.add(id);
        return new Fixture(reports.find("demo", id).orElseThrow(), invoice, priorId, advanceId);
    }
    private DefinitionDraft definition() {
        var nodes = new ArrayList<>(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("business", "业务审批", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_" + manager)),
                new Node("receipt", "原件签收", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_" + finance, "expenseStage", "RECEIPT")),
                new Node("finance", "财务审核", NodeType.USER_TASK, financeStage ? Map.of("assigneeRule", "role:ORG_PERSON_" + finance, "expenseStage", "FINANCE_REVIEW") : Map.of("assigneeRule", "role:ORG_PERSON_" + finance)),
                new Node("end", "结束", NodeType.END, Map.of())));
        var edges = new ArrayList<>(List.of(new Edge("a", "start", "business", "", false), new Edge("b", "business", "receipt", "", false),
                new Edge("c", "receipt", "finance", "", false), new Edge("d", "finance", reductionRoute ? "amountGate" : afterFinanceTask ? "afterFinance" : "end", "", false)));
        if (afterFinanceTask || reductionRoute) {
            nodes.add(new Node("afterFinance", "财务后续业务", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_" + manager)));
            edges.add(new Edge("e", "afterFinance", "end", "", false));
        }
        if (reductionRoute) {
            nodes.add(new Node("amountGate", "核定金额判断", NodeType.EXCLUSIVE_GATEWAY, Map.of()));
            edges.add(new Edge("low", "amountGate", "end", "amount <= 50", false));
            edges.add(new Edge("high", "amountGate", "afterFinance", "amount > 50", false));
        }
        var graph = new Graph(nodes, edges);
        var schema = new FormSchema(2, List.of(new FormSchema.Field("expenseDetails", "费用明细", FormSchema.FieldType.TEXT, true, null,
                null, null, null, null, null, null, true, Map.of("business", FieldVisibility.READ_ONLY, "receipt", FieldVisibility.READ_ONLY, "finance", FieldVisibility.READ_ONLY)),
                new FormSchema.Field("amount", "本币金额", FormSchema.FieldType.NUMBER, true, null, null, null, null, null),
                new FormSchema.Field("currency", "本位币", FormSchema.FieldType.TEXT, true, null, null, null, null, null),
                new FormSchema.Field("overPolicy", "超标", FormSchema.FieldType.BOOLEAN, true, null, null, null, null, null)));
        var draft = definitions.create("demo", "expense-submit-" + UUID.randomUUID(), "合成提交审批", graph, schema, null);
        return definitions.publish(admin, draft.id(), draft.revision(), "合成验收");
    }
    private UUID person(String user, boolean approver) {
        var found = jdbc.queryForList("SELECT id FROM organization_person WHERE tenant_id='demo' AND subject=?", String.class, user);
        return found.isEmpty() ? organization.createPerson(admin, user, "测试" + user, true, approver).id() : UUID.fromString(found.get(0));
    }
    private ExpenseLine line(UUID invoice, UUID prior) {
        return new ExpenseLine(1, "OFFICE", LocalDate.now(), null, "SH", BigDecimal.ONE, ExpenseLine.Unit.ITEM, money("100"), money("6"),
                invoice == null ? List.of() : List.of(invoice), prior == null ? null : new ExpenseLine.PriorRequestLine(prior, 1),
                List.of(new CostAllocation("IT", null, money("100"))), "合成办公费", null);
    }
    private UUID original() throws Exception {
        byte[] bytes = ("%PDF-1.7\nsynthetic-submission-" + UUID.randomUUID() + "\n%%EOF").getBytes(StandardCharsets.UTF_8);
        actors.set(new Actor("demo", "alice", Set.of("EMPLOYEE")));
        try {
            UUID id = wallet.reserve(new InvoiceWalletService.UploadInput("合成发票.pdf", (long) bytes.length, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), InvoiceOriginal.Format.PDF)).id();
            wallet.upload(id, new ByteArrayInputStream(bytes)); return id;
        } finally { actors.clear(); }
    }
    private void verify(UUID invoice) {
        actors.set(new Actor("demo", "alice", Set.of("EMPLOYEE")));
        try { verification.queue(invoice, new InvoiceVerificationService.QueueInput(invoices.find("demo", invoice).orElseThrow().version(), entity, target())); }
        finally { actors.clear(); }
        invoiceWorker.poll();
    }
    private UUID precheck(ExpenseReport report) throws Exception {
        UUID id = UUID.fromString(ok(send(path(report) + "/precheck", "alice", Map.of("applicationVersion", app(report).version(), "financialVersion", current(report).version(),
                "initiatorAppointmentId", appointment, "accountingDate", LocalDate.now(), "targetDigest", target())), 202).path("id").asText());
        precheckWorker.poll(); var checked = prechecks.find("demo", id).orElseThrow();
        assertThat(checked.status()).as(json.write(checked.result())).isEqualTo(ExpensePrecheckJob.Status.READY); return id;
    }
    private void submit(ExpenseReport report) throws Exception { UUID id = precheck(report); ok(send(path(report) + "/submit", "alice", submitInput(current(report), id)), 200); }
    private Map<String, Object> submitInput(ExpenseReport report, UUID checked) { return Map.of("applicationVersion", app(report).version(), "financialVersion", report.version(), "precheckId", checked); }
    private Map<String, Object> receiveInput(ExpenseReport report) { return lifecycleInput(report); }
    private Map<String, Object> lifecycleInput(ExpenseReport report) { return Map.of("applicationVersion", app(report).version(), "financialVersion", current(report).version(), "comment", "合成验收操作"); }
    private MockHttpServletResponse act(ExpenseReport report, String user, String action) throws Exception { return send("/api/v1/tasks/" + task(report).getId() + "/actions", user, Map.of("action", action, "comment", "合成决策", "expectedVersion", app(report).version())); }
    private Task task(ExpenseReport report) { return tasks(report).get(0); }
    private List<Task> tasks(ExpenseReport report) { return tasks.createTaskQuery().processVariableValueEquals("applicationId", report.applicationId().toString()).list(); }
    private Application app(ExpenseReport report) { return applications.findById("demo", report.applicationId()).orElseThrow(); }
    private ExpenseReport current(ExpenseReport report) { return reports.find("demo", report.id()).orElseThrow(); }
    private String path(ExpenseReport report) { return "/api/v1/expense-reports/" + report.id(); }
    private String target() { return configuration.destination("demo").orElseThrow().digest("demo"); }
    private MockHttpServletResponse send(String path, String user, Object input) throws Exception { return send(path, user, UUID.randomUUID().toString(), input); }
    private MockHttpServletResponse send(String path, String user, String key, Object input) throws Exception {
        return mvc.perform(post(path).header("Authorization", "Bearer " + auth.login("demo", user, "demo").token()).header("Idempotency-Key", key)
                .contentType("application/json").content(json.write(input))).andReturn().getResponse();
    }
    private JsonNode ok(MockHttpServletResponse response, int status) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status); return json.read(response.getContentAsString(), JsonNode.class);
    }
    private void assertCode(MockHttpServletResponse response, String code) throws Exception {
        assertThat(response.getStatus()).isBetween(400, 499); assertThat(json.read(response.getContentAsString(), JsonNode.class).path("code").asText()).isEqualTo(code);
    }
    private static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
    private void drive(UUID id) {
        var operation = operations.find("demo", id).orElseThrow();
        Instant now = operation.nextAttemptAt().isAfter(Instant.now()) ? operation.nextAttemptAt() : Instant.now();
        var claimed = budgetExecution.claim("demo", id, now); assertThat(claimed).isNotNull();
        var input = claimed.input();
        var result = claimed.status() == BudgetOperation.Status.QUERYING ? budgetPort.query(input.targetDigest(), input.command()) : budgetPort.execute(input.targetDigest(), input.command());
        Instant completed = Instant.now(); budgetExecution.finish(claimed, result, (completed.isAfter(now) ? completed : now).plusMillis(1));
    }
    private Object data(String operation, JsonNode data) {
        return switch (operation) {
            case "catalog" -> new FinanceCatalog("alice", "synthetic-v1", Instant.now().plusSeconds(600), List.of(new FinanceCatalog.LegalEntity(entity, "合成法人", "CNY", paperRequired, "v1", "UTC")),
                    List.of(new FinanceCatalog.Category("OFFICE", "办公", List.of(ExpenseLine.Unit.ITEM))), List.of(new FinanceCatalog.CostCenter(entity, "IT", "研发")), List.of(), List.of(new FinanceCatalog.City("SH", "上海")));
            case "employee-account" -> new EmployeeAccountPort.Account(new EmployeeAccountSnapshot(entity, "alice", "synthetic-private-account", "****1234", "a".repeat(64), "v1"), Instant.now().plusSeconds(600));
            case "exchange-rate" -> new ExpenseExchangeRate("CNY", "CNY", BigDecimal.ONE, "synthetic", LocalDate.parse(data.path("rateDate").asText()));
            case "invoice-verification" -> new Invoice.VerifiedFacts(new InvoiceKey(InvoiceKey.Type.DIGITAL, null, invoiceNumber), entity, money("100"), money("6"), LocalDate.now(), data.path("originalDigest").asText(), "synthetic-verification", Instant.now().minusSeconds(1), Instant.now().plusSeconds(600));
            case "expense-policy" -> new ExpensePolicyPort.Assessment(new ExpensePolicySnapshot(UUID.randomUUID(), 1, money("100"), money("100"), ExpensePolicySnapshot.Decision.WITHIN_LIMIT, "synthetic-tax", "synthetic-policy"), money("6"), false, Instant.now().plusSeconds(600));
            case "budget-precheck" -> new BudgetPrecheckPort.Assessment(json.read(data.toString(), BudgetPrecheckPort.Request.class), "synthetic-precheck", Instant.now().minusSeconds(1), Instant.now().plusSeconds(600));
            case "budget-command", "budget-query" -> {
                BudgetCommand command;
                if (operation.equals("budget-command")) { writes++; command = json.read(data.path("command").toString(), BudgetCommand.class); commands.put(command.id(), command); }
                else { queries++; command = commands.get(UUID.fromString(data.path("operationId").asText())); }
                yield new BudgetObservation(command.id(), command.digest(), budgetStatus,
                        budgetStatus == BudgetObservation.Status.APPLIED ? command.expected() == null ? 1L : command.expected().revision() + 1 : null,
                        budgetStatus == BudgetObservation.Status.APPLIED ? "synthetic-budget-" + command.id() : null,
                        budgetStatus == BudgetObservation.Status.APPLIED ? Instant.now() : null,
                        budgetStatus == BudgetObservation.Status.REJECTED ? budgetRejection : null);
            }
            default -> throw new IllegalArgumentException("Unknown synthetic finance operation");
        };
    }
    private static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/finance/", exchange -> {
                var test = ACTIVE.get(); var request = test.json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonNode.class);
                Object result = test.data(exchange.getRequestURI().getPath().substring("/finance/".length()), request.path("data"));
                byte[] body = test.json.write(Map.of("contractVersion", 1, "tenantId", request.path("tenantId").asText(), "requestId", request.path("requestId").asText(), "outcome", "SUCCESS", "data", result)).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.length);
                try { exchange.getResponseBody().write(body); } finally { exchange.close(); }
            }); server.start(); return server;
        } catch (java.io.IOException failed) { throw new IllegalStateException(failed); }
    }
    /**
     * 发票和资金来源为合成事实，提交与审批均通过真实接口执行。
     * @author owlzhangfq@gmail.com
     */
    private record Fixture(ExpenseReport report, UUID invoice, UUID prior, UUID advance) { }
}
