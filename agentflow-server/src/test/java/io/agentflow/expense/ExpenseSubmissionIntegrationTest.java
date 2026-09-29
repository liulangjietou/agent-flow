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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 发布定义、认证 HTTP、实际引擎、资源台账与合成财务 HTTP 联合验证正式提交及审批控制。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "agentflow.finance-gateway.enabled=true",
        "agentflow.vouchers.preparation-worker-enabled=false", "agentflow.vouchers.worker-enabled=false",
        "agentflow.payments.worker-enabled=false", "agentflow.payments.request-worker-enabled=false",
        "agentflow.invoices.verification-worker-enabled=false", "agentflow.expenses.precheck-worker-enabled=false",
        "agentflow.budgets.worker-enabled=false", "agentflow.expenses.settlement-worker-enabled=false", "agentflow.expenses.archive-worker-enabled=false"})
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
    private String legalTimeZone = "UTC";
    private boolean financeStage = true;
    private boolean afterFinanceTask;
    private boolean reductionRoute;
    private boolean hideBusinessDetails;
    private BudgetObservation.Status budgetStatus = BudgetObservation.Status.APPLIED;
    private BudgetObservation.Rejection budgetRejection = BudgetObservation.Rejection.BUDGET_INSUFFICIENT;
    private int writes;
    private int queries;
    private String voucherMode = "POSTED";
    private final Map<UUID, VoucherCommand> voucherCommands = new ConcurrentHashMap<>();
    private final Map<UUID, PaymentCommand> paymentCommands = new ConcurrentHashMap<>();
    private String paymentMode = "SUCCEEDED";
    private boolean verificationUnavailable;
    private String advanceOffset = "50";
    private int paymentWrites;
    private int accountReads;
    private UUID cashierAppointment;

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
    @Autowired io.agentflow.organization.OrganizationRepository organizationRepository;
    @Autowired DefinitionApplicationService definitions;
    @Autowired ApplicationRepository applications;
    @Autowired ExpenseReportRepository reports;
    @Autowired JdbcExpenseSettlementRepository settlements;
    @Autowired JdbcExpenseArchiveRepository archives;
    @Autowired ExpenseArchiveWorker archiveWorker;
    @Autowired ExpenseArchiveService archiveService;
    @Autowired ExpenseArchiveFiles archiveFiles;
    @Autowired ExpenseSettlementWorker settlementWorker;
    @Autowired ExpenseSettlementService settlementService;
    @Autowired ExpenseSettlementRegistration settlementRegistration;
    @Autowired PaymentOperationService paymentExecution;
    @Autowired ApprovedVoucherSources voucherSources;
    @Autowired JdbcVoucherPreparationRepository voucherPreparations;
    @Autowired VoucherPreparationWorker voucherPreparationWorker;
    @Autowired JdbcVoucherOperationRepository voucherOperations;
    @Autowired VoucherOperationWorker voucherWorker;
    @Autowired VoucherPreparationService voucherPreparationService;
    @Autowired JdbcPaymentAuthorizationRepository paymentAuthorizations;
    @Autowired JdbcPaymentExecutionRequestRepository paymentRequests;
    @Autowired JdbcPaymentOperationRepository paymentOperations;
    @Autowired PaymentExecutionRequestWorker paymentRequestWorker;
    @Autowired PaymentOperationWorker paymentWorker;
    @Autowired CashierPaymentWorkspace cashierWorkspace;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
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
        for (UUID report : created) {
            jdbc.update("DELETE FROM expense_archive_original WHERE tenant_id='demo' AND report_id=?", report.toString());
            jdbc.update("DELETE FROM expense_archive WHERE tenant_id='demo' AND report_id=?", report.toString());
            jdbc.update("DELETE FROM expense_settlement_revision WHERE tenant_id='demo' AND report_id=?", report.toString());
            jdbc.update("DELETE FROM expense_settlement WHERE tenant_id='demo' AND report_id=?", report.toString());
            jdbc.update("DELETE FROM voucher_preparation_revision WHERE tenant_id='demo' AND preparation_id IN (SELECT id FROM voucher_preparation WHERE tenant_id='demo' AND business_id=?)", report.toString());
            jdbc.update("DELETE FROM voucher_preparation WHERE tenant_id='demo' AND business_id=?", report.toString());
            String authorizations = "SELECT id FROM payment_authorization WHERE tenant_id='demo' AND business_id=?";
            jdbc.update("DELETE FROM payment_retirement WHERE tenant_id='demo' AND authorization_id IN (" + authorizations + ")", report.toString());
            jdbc.update("DELETE FROM payment_execution_request_revision WHERE tenant_id='demo' AND request_id IN (SELECT id FROM payment_execution_request WHERE tenant_id='demo' AND authorization_id IN (" + authorizations + "))", report.toString());
            jdbc.update("DELETE FROM payment_execution_request WHERE tenant_id='demo' AND authorization_id IN (" + authorizations + ")", report.toString());
            jdbc.update("DELETE FROM payment_operation_revision WHERE tenant_id='demo' AND operation_id IN (" + authorizations + ")", report.toString());
            jdbc.update("DELETE FROM payment_operation WHERE tenant_id='demo' AND id IN (" + authorizations + ")", report.toString());
            jdbc.update("DELETE FROM payment_authorization_revision WHERE tenant_id='demo' AND authorization_id IN (" + authorizations + ")", report.toString());
            jdbc.update("DELETE FROM payment_authorization WHERE tenant_id='demo' AND business_id=?", report.toString());
            jdbc.update("DELETE FROM voucher_operation_revision WHERE tenant_id='demo' AND operation_id IN (SELECT id FROM voucher_operation WHERE tenant_id='demo' AND business_id=?)", report.toString());
            jdbc.update("DELETE FROM voucher_operation WHERE tenant_id='demo' AND business_id=?", report.toString());
        }
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
        var preparation = voucherPreparations.latest(voucherSources.reference(app(report))).orElseThrow();
        assertThat(preparation.input().source().businessVersion()).isEqualTo(current(report).version());
        assertThat(preparation.input().source().applicationVersion()).isEqualTo(app(report).version());
        voucherPreparationWorker.poll(); assertThat(voucherPreparations.find("demo", preparation.input().id()).orElseThrow().status()).isEqualTo(VoucherPreparation.Status.READY);
        var voucher = voucherOperations.find("demo", preparation.input().id()).orElseThrow();
        assertThat(voucher.input().command().totals()).isEqualTo(new VoucherCommand.Totals(money("100"), money("6"), money("50")));
        assertThat(voucher.input().command().binding().applicationId()).isEqualTo(report.applicationId());
        voucherWorker.poll(); assertThat(voucherOperations.find("demo", preparation.input().id()).orElseThrow().status()).isEqualTo(VoucherOperation.Status.POSTED);
        assertThat(occupations.find("demo", report.id()).orElseThrow().status()).isEqualTo(BudgetOccupation.Status.FROZEN);
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
        var preparation = voucherPreparations.latest(voucherSources.reference(app(report))).orElseThrow();
        assertThat(preparation.input().source().businessVersion()).isEqualTo(reduced.version());
        var source = voucherSources.derive(preparation.input().source());
        assertThat(source.totals()).isEqualTo(new VoucherCommand.Totals(money("25"), money("1"), money("25")));
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
        var preparation = voucherPreparations.latest(voucherSources.reference(app(report))).orElseThrow(); voucherPreparationWorker.poll();
        assertThat(voucherPreparations.find("demo", preparation.input().id()).orElseThrow().status()).isEqualTo(VoucherPreparation.Status.NOT_REQUIRED);
        assertThat(voucherOperations.find("demo", preparation.input().id())).isEmpty();
        assertThat(occupations.find("demo", report.id()).orElseThrow().status()).isEqualTo(BudgetOccupation.Status.FROZEN);
        var versions = resourceVersions(report); settlementWorker.poll(); budgetWorker.poll();
        var settled = settlements.find("demo", report.id()).orElseThrow(); assertThat(settled.status()).isEqualTo(ExpenseSettlement.Status.SETTLED);
        assertThat(settled.input().voucherOperationId()).isNull(); assertThat(settled.input().payment()).isNull();
        assertThat(resourceVersions(report)).isEqualTo(versions); assertThat(paymentWrites).isZero();
        pollArchive(); var archive = archives.find("demo", report.id(), 1).orElseThrow().archive();
        assertThat(archive).isNotNull(); assertThat(archive.manifest().vouchers()).isEmpty();
        assertThat(archive.manifest().expense().adjustments()).hasSize(1); assertThat(archive.manifest().originals()).hasSize(1);
    }

    @Test
    void approvedExpenseCannotPrepareVoucherWhileBudgetFinalizationIsUnconfirmed() throws Exception {
        var report = fixture(false).report(); enterFinance(report); ok(act(report, "finance", "APPROVE"), 200);
        var preparation = voucherPreparations.latest(voucherSources.reference(app(report))).orElseThrow();
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(transaction ->
                budgetExecution.finalizeOccupation("demo", report.id(), BudgetCommand.Action.CONSUME, Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)));
        assertThat(voucherPreparationService.claim("demo", preparation.input().id(), Instant.now())).isNull();
        var blocked = voucherPreparations.find("demo", preparation.input().id()).orElseThrow();
        assertThat(blocked.status()).isEqualTo(VoucherPreparation.Status.BLOCKED); assertThat(blocked.result().code()).isEqualTo("VOUCHER_BUDGET_NOT_FROZEN");
        assertThat(voucherOperations.find("demo", preparation.input().id())).isEmpty();
    }

    @Test
    void voucherApiKeepsFieldPermissionsAndFinanceSeparationAcrossIdempotentPreparationAndQueries() throws Exception {
        var report = fixture(false).report(); enterFinance(report); ok(act(report, "finance", "APPROVE"), 200); String path = voucherPath(report);
        var owned = ok(read(path, "alice"), 200);
        assertThat(owned.path("applicationId").asText()).isEqualTo(report.applicationId().toString());
        assertThat(owned.has("operation")).isTrue(); assertThat(owned.get("operation").isNull()).isTrue();
        assertThat(owned.path("preparation").has("completedAt")).isTrue(); assertThat(owned.path("preparation").has("issue")).isTrue();
        assertThat(owned.at("/preparation/status").asText()).isEqualTo("QUEUED"); assertThat(owned.at("/actions/prepare").asBoolean()).isFalse();
        assertThat(read(path, "bob").getStatus()).isEqualTo(404); assertThat(read(path, "admin").getStatus()).isEqualTo(403);
        assertThat(read(path + "?roundNo=0", "finance").getStatus()).isEqualTo(400);
        assertThat(read(path + "?employeeId=alice", "finance").getStatus()).isEqualTo(400);
        assertThat(read(path + "?roundNo=2", "finance").getStatus()).isEqualTo(404);
        configuration.setEnabled(false); voucherPreparationWorker.poll(); configuration.setEnabled(true);
        var blocked = ok(read(path, "finance"), 200); assertThat(blocked.at("/preparation/issue").asText()).isEqualTo("NOT_CONFIGURED");
        assertThat(blocked.at("/actions/prepare").asBoolean()).isTrue();
        var input = voucherInput(report, "PREPARE", null); String key = UUID.randomUUID().toString();
        for (String user : List.of("alice", "manager", "admin")) assertThat(send(path + "/actions", user, input).getStatus()).isEqualTo(403);
        var forged = new HashMap<>(input); forged.put("amount", "999.99"); assertThat(send(path + "/actions", "finance", forged).getStatus()).isEqualTo(400);
        var stale = new HashMap<>(input); stale.put("businessVersion", current(report).version() - 1);
        assertCode(send(path + "/actions", "finance", stale), "CONCURRENCY_CONFLICT");
        var prepared = send(path + "/actions", "finance", key, input); ok(prepared, 202);
        var preparationReceipt = ok(prepared, 202);
        assertThat(preparationReceipt.has("operationId")).isTrue(); assertThat(preparationReceipt.get("operationId").isNull()).isTrue();
        assertThat(preparationReceipt.has("operationVersion")).isTrue(); assertThat(preparationReceipt.get("operationVersion").isNull()).isTrue();
        assertThat(prepared.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(send(path + "/actions", "finance", key, input).getContentAsString()).isEqualTo(prepared.getContentAsString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='VOUCHER_PREPARE'", Integer.class, report.applicationId().toString())).isEqualTo(1);
        voucherPreparationWorker.poll(); var queued = ok(read(path, "finance"), 200);
        for (String field : List.of("observedStatus", "voucherReference", "postedAt", "issue")) assertThat(queued.path("operation").has(field)).isTrue();
        assertThat(queued.at("/operation/status").asText()).isEqualTo("QUEUED"); assertThat(queued.at("/actions/query").asBoolean()).isFalse();
        voucherWorker.poll(); var posted = ok(read(path, "finance"), 200); assertThat(posted.at("/operation/status").asText()).isEqualTo("POSTED");
        assertThat(posted.at("/operation/voucherReference").asText()).isEqualTo("synthetic-voucher"); assertThat(posted.at("/actions/query").asBoolean()).isTrue();
        assertThat(posted.toString()).doesNotContain("targetDigest", "accountDigest", "accountReference", "mapping", "commandDigest", "synthetic-private-account");
        assertThat(ok(read(path, "alice"), 200).at("/actions/query").asBoolean()).isFalse();
        var queryInput = voucherInput(report, "QUERY", posted.path("operation")); String queryKey = UUID.randomUUID().toString();
        var query = send(path + "/actions", "finance", queryKey, queryInput); ok(query, 202);
        assertThat(ok(query, 202).has("preparationId")).isTrue();
        var person = organizationRepository.person("demo", finance).orElseThrow();
        var inactive = organization.updatePerson(admin, finance, person.displayName(), false, person.approvalEligible(), person.revision());
        try { assertThat(send(path + "/actions", "finance", queryKey, queryInput).getStatus()).isIn(403, 404); }
        finally { organization.updatePerson(admin, finance, person.displayName(), person.active(), person.approvalEligible(), inactive.revision()); }
        assertThat(send(path + "/actions", "finance", queryKey, queryInput).getContentAsString()).isEqualTo(query.getContentAsString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='VOUCHER_QUERY'", Integer.class, report.applicationId().toString())).isEqualTo(1);
        assertCode(send(path + "/actions", "finance", queryInput), "CONCURRENCY_CONFLICT");
    }

    @Test
    void financeCanResendOnlyTheSameAuthoritativelyMissingCommandWithFreshVersions() throws Exception {
        var report = fixture(false).report(); enterFinance(report); ok(act(report, "finance", "APPROVE"), 200); String path = voucherPath(report);
        voucherPreparationWorker.poll(); voucherMode = "INVALID"; voucherWorker.poll();
        var unknown = ok(read(path, "finance"), 200); assertThat(unknown.at("/operation/status").asText()).isEqualTo("UNKNOWN");
        assertThat(unknown.at("/actions/resendOriginal").asBoolean()).isFalse();
        assertThat(send(path + "/actions", "finance", voucherInput(report, "RESEND_ORIGINAL", unknown.path("operation"))).getStatus()).isBetween(400, 499);
        voucherMode = "NOT_FOUND"; ok(send(path + "/actions", "finance", voucherInput(report, "QUERY", unknown.path("operation"))), 202); voucherWorker.poll();
        var missing = ok(read(path, "finance"), 200); assertThat(missing.at("/operation/status").asText()).isEqualTo("NOT_FOUND");
        assertThat(missing.at("/actions/resendOriginal").asBoolean()).isTrue(); UUID operationId = UUID.fromString(missing.at("/operation/id").asText());
        var original = voucherOperations.find("demo", operationId).orElseThrow().input();
        String key = UUID.randomUUID().toString(); var input = voucherInput(report, "RESEND_ORIGINAL", missing.path("operation"));
        var receipt = send(path + "/actions", "finance", key, input); ok(receipt, 202);
        assertThat(send(path + "/actions", "finance", key, input).getContentAsString()).isEqualTo(receipt.getContentAsString());
        assertThat(voucherOperations.find("demo", operationId).orElseThrow().input()).isEqualTo(original);
        voucherMode = "POSTED"; voucherWorker.poll(); assertThat(ok(read(path, "finance"), 200).at("/operation/status").asText()).isEqualTo("POSTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM voucher_operation WHERE tenant_id='demo' AND business_id=?", Integer.class, report.id().toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='VOUCHER_RESEND_ORIGINAL'", Integer.class, report.applicationId().toString())).isEqualTo(1);
    }

    @Test
    void paymentApiSeparatesFinanceCashierAndApplicantThroughRealApprovalAndBankQueue() throws Exception {
        var report = paymentReport(); String path = paymentPath(report); var input = authorizationInput(report);
        var initial = ok(read(path, "finance"), 200); assertThat(initial.at("/actions/authorize").asBoolean()).isTrue();
        assertThat(initial.at("/payable/value").asText()).isEqualTo("100.00");
        assertThat(ok(read(path, "alice"), 200).at("/actions/authorize").asBoolean()).isFalse();
        assertThat(read(path, "admin").getStatus()).isEqualTo(403); assertThat(read(path, "cashier").getStatus()).isEqualTo(404);
        for (String user : List.of("alice", "manager", "admin", "cashier")) assertThat(send(path + "/authorizations", user, input).getStatus()).isIn(403, 404);
        var forged = new HashMap<>(input); forged.put("amount", "999.99"); assertThat(send(path + "/authorizations", "finance", forged).getStatus()).isEqualTo(400);
        var stale = new HashMap<>(input); stale.put("voucherVersion", 1); assertCode(send(path + "/authorizations", "finance", stale), "CONCURRENCY_CONFLICT");
        String key = UUID.randomUUID().toString(); var first = send(path + "/authorizations", "finance", key, input); var receipt = ok(first, 202);
        UUID id = UUID.fromString(receipt.path("authorizationId").asText()); String cashierPath = "/api/v1/cashier/payments/" + id;
        assertThat(first.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(send(path + "/authorizations", "finance", key, input).getContentAsString()).isEqualTo(first.getContentAsString());
        assertCode(send(path + "/authorizations", "finance", input), "PAYMENT_AUTHORIZATION_EXISTS");
        for (String user : List.of("finance", "admin", "alice")) assertThat(read(cashierPath, user).getStatus()).isEqualTo(403);
        var detail = ok(read(cashierPath, "cashier"), 200); assertThat(detail.at("/actions/execute").asBoolean()).isTrue();
        assertThat(detail.at("/payment/amount/value").asText()).isEqualTo("100.00");
        assertThat(detail.toString()).doesNotContain("targetDigest", "accountDigest", "commandDigest", "accountReference", "synthetic-private-account");
        assertThat(ok(read("/api/v1/cashier/payments", "cashier"), 200).at("/items/0/payment/id").asText()).isEqualTo(id.toString());
        var options = ok(read(cashierPath + "/accounts", "cashier"), 200); assertThat(options.at("/items/0/maskedAccount").asText()).isEqualTo("****4567");
        actors.set(new Actor("demo", "cashier", Set.of("CASHIER")));
        try { assertThatThrownBy(() -> new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> cashierWorkspace.accountOptions(id)))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class); }
        finally { actors.clear(); }
        int readsBefore = accountReads; String executeKey = UUID.randomUUID().toString(); var executeInput = cashierInput("EXECUTE", 1, null);
        var executed = send(cashierPath + "/actions", "cashier", executeKey, executeInput); ok(executed, 202);
        assertThat(accountReads).isEqualTo(readsBefore); assertThat(paymentWrites).isZero();
        assertThat(send(cashierPath + "/actions", "cashier", executeKey, executeInput).getContentAsString()).isEqualTo(executed.getContentAsString());
        assertCode(send(cashierPath + "/actions", "cashier", executeInput), "PAYMENT_EXECUTION_ALREADY_REQUESTED");
        assertThat(paymentRequests.forAuthorization("demo", id).orElseThrow().status()).isEqualTo(PaymentExecutionRequest.Status.QUEUED);
        assertThat(paymentOperations.find("demo", id)).isEmpty();
        paymentRequestWorker.poll(); assertThat(paymentOperations.find("demo", id).orElseThrow().status()).isEqualTo(PaymentOperation.Status.QUEUED);
        assertThat(paymentWrites).isZero(); paymentWorker.poll();
        var paid = ok(read(cashierPath, "cashier"), 200); assertThat(paid.at("/payment/operation/status").asText()).isEqualTo("SUCCEEDED");
        assertThat(paid.at("/payment/operation/receiptReference").asText()).isEqualTo("synthetic-bank-receipt"); assertThat(paymentWrites).isEqualTo(1);
        assertThat(ok(read(path, "alice"), 200).at("/payment/operation/status").asText()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action IN ('PAYMENT_AUTHORIZE','PAYMENT_EXECUTE')", Integer.class, report.applicationId().toString())).isEqualTo(2);
        var queryInput = Map.of("action", "QUERY", "authorizationVersion", 2, "operationVersion", paid.at("/payment/operation/version").asLong(), "comment", "财务核对原银行回单");
        ok(send("/api/v1/payments/" + id + "/finance-actions", "finance", queryInput), 202); paymentWorker.poll();
        assertThat(paymentOperations.find("demo", id).orElseThrow().status()).isEqualTo(PaymentOperation.Status.SUCCEEDED); assertThat(paymentWrites).isEqualTo(1);
    }

    @Test
    void paymentReplayRechecksCurrentPersonnelAndCashierDirectoryScopeBeforeReturningReceipt() throws Exception {
        var report = paymentReport(); String path = paymentPath(report); String key = UUID.randomUUID().toString(); var input = authorizationInput(report);
        var first = send(path + "/authorizations", "finance", key, input); UUID id = UUID.fromString(ok(first, 202).path("authorizationId").asText());
        var person = organizationRepository.person("demo", finance).orElseThrow();
        var inactive = organization.updatePerson(admin, finance, person.displayName(), false, person.approvalEligible(), person.revision());
        try { assertThat(send(path + "/authorizations", "finance", key, input).getStatus()).isIn(403, 404); }
        finally { organization.updatePerson(admin, finance, person.displayName(), person.active(), person.approvalEligible(), inactive.revision()); }
        String cashierPath = "/api/v1/cashier/payments/" + id; String executeKey = UUID.randomUUID().toString(); var execution = cashierInput("EXECUTE", 1, null);
        var registered = send(cashierPath + "/actions", "cashier", executeKey, execution); ok(registered, 202);
        jdbc.update("UPDATE organization_appointment SET active=false WHERE tenant_id='demo' AND id=?", cashierAppointment.toString());
        assertThat(read(cashierPath, "cashier").getStatus()).isEqualTo(404);
        assertThat(send(cashierPath + "/actions", "cashier", executeKey, execution).getStatus()).isEqualTo(404);
        assertThat(ok(read("/api/v1/cashier/payments", "cashier"), 200).path("items")).isEmpty();
        assertThat(read("/api/v1/cashier/payments?beforeId=" + id, "cashier").getStatus()).isEqualTo(404);
        for (String query : List.of("limit=101", "tenantId=foreign", "beforeId=1-1-1-1-1")) assertThat(read("/api/v1/cashier/payments?" + query, "cashier").getStatus()).isEqualTo(400);
        paymentRequestWorker.poll(); assertThat(paymentRequests.forAuthorization("demo", id).orElseThrow().status()).isEqualTo(PaymentExecutionRequest.Status.VOIDED);
        assertThat(paymentWrites).isZero();
    }

    @Test
    void cashierUnknownPaymentQueriesOriginalNumberAndResendsOnlyAfterAuthoritativeNotFound() throws Exception {
        var report = paymentReport(); UUID id = authorizePayment(report); String path = "/api/v1/cashier/payments/" + id;
        ok(send(path + "/actions", "cashier", cashierInput("EXECUTE", 1, null)), 202); paymentRequestWorker.poll();
        paymentMode = "INVALID"; paymentWorker.poll(); var unknown = paymentOperations.find("demo", id).orElseThrow();
        assertThat(unknown.status()).isEqualTo(PaymentOperation.Status.UNKNOWN); var original = unknown.input();
        assertThat(ok(read(path, "cashier"), 200).at("/actions/resendOriginal").asBoolean()).isFalse();
        assertThat(send(path + "/actions", "cashier", cashierInput("RESEND_ORIGINAL", 2, unknown.version())).getStatus()).isBetween(400, 499);
        assertThat(send("/api/v1/payments/" + id + "/finance-actions", "cashier", Map.of("action", "QUERY", "authorizationVersion", 2, "operationVersion", unknown.version(), "comment", "测试")).getStatus()).isIn(403, 404);
        paymentMode = "NOT_FOUND"; ok(send(path + "/actions", "cashier", cashierInput("QUERY", 2, unknown.version())), 202); paymentWorker.poll();
        var missing = paymentOperations.find("demo", id).orElseThrow(); assertThat(missing.status()).isEqualTo(PaymentOperation.Status.NOT_FOUND);
        assertThat(ok(read(path, "cashier"), 200).at("/actions/resendOriginal").asBoolean()).isTrue();
        var forged = new HashMap<>(cashierInput("RESEND_ORIGINAL", 2, missing.version())); forged.put("debitAccountReference", "other");
        assertThat(send(path + "/actions", "cashier", forged).getStatus()).isEqualTo(400);
        String key = UUID.randomUUID().toString(); var input = cashierInput("RESEND_ORIGINAL", 2, missing.version()); var replay = send(path + "/actions", "cashier", key, input); ok(replay, 202);
        assertThat(send(path + "/actions", "cashier", key, input).getContentAsString()).isEqualTo(replay.getContentAsString());
        paymentMode = "SUCCEEDED"; paymentWorker.poll(); assertThat(paymentOperations.find("demo", id).orElseThrow().input()).isEqualTo(original);
        assertThat(paymentWrites).isEqualTo(2); assertThat(paymentCommands).hasSize(1);
        assertThat(ok(read(paymentPath(report), "finance"), 200).at("/actions/authorize").asBoolean()).isFalse();
    }

    @Test
    void financeMayVoidQueuedSelectionButCannotCancelRegisteredExecutionOrPayChangedSource() throws Exception {
        var report = paymentReport(); UUID id = authorizePayment(report); String path = "/api/v1/cashier/payments/" + id;
        ok(send(path + "/actions", "cashier", cashierInput("EXECUTE", 1, null)), 202);
        var input = Map.of("action", "VOID", "authorizationVersion", 1, "comment", "财务停止尚未执行的选择");
        ok(send("/api/v1/payments/" + id + "/finance-actions", "finance", input), 202); paymentRequestWorker.poll();
        assertThat(paymentAuthorizations.find("demo", id).orElseThrow().status()).isEqualTo(PaymentAuthorization.Status.VOIDED);
        assertThat(paymentRequests.forAuthorization("demo", id).orElseThrow().status()).isEqualTo(PaymentExecutionRequest.Status.VOIDED);
        assertThat(paymentOperations.find("demo", id)).isEmpty(); UUID replacement = authorizePayment(report);
        ok(send("/api/v1/cashier/payments/" + replacement + "/actions", "cashier", cashierInput("EXECUTE", 1, null)), 202); paymentRequestWorker.poll();
        assertThat(send("/api/v1/payments/" + replacement + "/finance-actions", "finance", Map.of("action", "VOID", "authorizationVersion", 2, "comment", "不能取消可能执行的资金操作")).getStatus()).isEqualTo(409);
        jdbc.update("UPDATE approval_application SET status='REVOKED' WHERE tenant_id='demo' AND id=?", report.applicationId().toString());
        paymentWorker.poll(); assertThat(paymentOperations.find("demo", replacement).orElseThrow().status()).isEqualTo(PaymentOperation.Status.VOIDED);
        assertThat(paymentWrites).isZero();
    }

    @Test
    void financeRetiresNeverDispatchedExecutionBeforeASeparateNewAuthorization() throws Exception {
        var report = paymentReport(); UUID id = authorizePayment(report);
        ok(send("/api/v1/cashier/payments/" + id + "/actions", "cashier", cashierInput("EXECUTE", 1, null)), 202);
        paymentRequestWorker.poll(); var original = paymentOperations.find("demo", id).orElseThrow();
        var input = Map.of("action", "RETIRE", "authorizationVersion", 2, "operationVersion", original.version(), "comment", "确认原命令从未发送，结束后重新授权");
        var receipt = ok(send("/api/v1/payments/" + id + "/finance-actions", "finance", input), 202);
        assertThat(receipt.path("authorizationVersion").asLong()).isEqualTo(3);
        assertThat(paymentOperations.find("demo", id).orElseThrow().status()).isEqualTo(PaymentOperation.Status.VOIDED);
        paymentWorker.poll(); assertThat(paymentWrites).isZero();
        UUID replacement = authorizePayment(report); assertThat(replacement).isNotEqualTo(id);
        assertThat(paymentOperations.find("demo", replacement)).isEmpty();
        assertThat(paymentAuthorizations.find("demo", id).orElseThrow().status().name()).isEqualTo("RETIRED");
    }

    @Test void confirmedFailureCanRetireOnceAndReplacementNeedsIndependentCashierExecution() throws Exception {
        var report = paymentReport(); UUID id = authorizePayment(report);
        ok(send("/api/v1/cashier/payments/" + id + "/actions", "cashier", cashierInput("EXECUTE", 1, null)), 202); paymentRequestWorker.poll();
        paymentMode = "FAILED"; paymentWorker.poll(); var failed = paymentOperations.find("demo", id).orElseThrow();
        assertThat(failed.status()).isEqualTo(PaymentOperation.Status.FAILED);
        var input = Map.of("action", "RETIRE", "authorizationVersion", 2, "operationVersion", failed.version(), "comment", "确认资金系统原交易为终态失败");
        String key = UUID.randomUUID().toString(), path = "/api/v1/payments/" + id + "/finance-actions";
        for (String user : List.of("alice", "manager", "admin", "cashier")) assertThat(send(path, user, key, input).getStatus()).isIn(403, 404);
        var response = send(path, "finance", key, input); var receipt = ok(response, 202);
        assertThat(receipt.path("operationVersion").asLong()).isEqualTo(failed.version());
        assertThat(send(path, "finance", key, input).getContentAsString()).isEqualTo(response.getContentAsString());
        assertCode(send(path, "finance", input), "CONCURRENCY_CONFLICT");
        assertThat(paymentOperations.find("demo", id)).contains(failed);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='PAYMENT_RETIRE'", Integer.class, report.applicationId().toString())).isEqualTo(1);
        var financeView = ok(read(paymentPath(report), "finance"), 200);
        assertThat(financeView.at("/payment/retirement/basis").asText()).isEqualTo("CONFIRMED_FAILED");
        assertThat(financeView.at("/actions/authorize").asBoolean()).isTrue();
        var cashier = ok(read("/api/v1/cashier/payments/" + id, "cashier"), 200);
        assertThat(cashier.at("/actions/query").asBoolean()).isFalse(); assertThat(cashier.toString()).doesNotContain("确认资金系统原交易", "commandDigest");
        assertCode(send(path, "finance", Map.of("action", "QUERY", "authorizationVersion", 3, "operationVersion", failed.version(), "comment", "旧授权不能重新启用")), "CONCURRENCY_CONFLICT");
        assertCode(send("/api/v1/cashier/payments/" + id + "/actions", "cashier", cashierInput("QUERY", 3, failed.version())), "CONCURRENCY_CONFLICT");
        UUID replacement = authorizePayment(report); assertThat(paymentWrites).isEqualTo(1); assertThat(paymentOperations.find("demo", replacement)).isEmpty();
        ok(send("/api/v1/cashier/payments/" + replacement + "/actions", "cashier", cashierInput("EXECUTE", 1, null)), 202);
        paymentMode = "SUCCEEDED"; paymentRequestWorker.poll(); paymentWorker.poll();
        assertThat(paymentOperations.find("demo", replacement).orElseThrow().status()).isEqualTo(PaymentOperation.Status.SUCCEEDED);
        assertThat(paymentWrites).isEqualTo(2); assertThat(paymentCommands).hasSize(2);
        settlementWorker.poll(); budgetWorker.poll(); voucherPreparationWorker.poll(); voucherWorker.poll(); pollArchive();
        assertThat(archives.find("demo", report.id(), 1).orElseThrow().archive()).isNotNull();
        assertThat(paymentOperations.find("demo", id)).contains(failed);
        jdbc.update("UPDATE organization_appointment SET active=false WHERE tenant_id='demo' AND person_id=?", finance.toString());
        assertThat(send(path, "finance", key, input).getStatus()).isIn(403, 404);
    }

    @Test void unknownMissingReturnedAndPaidOperationsCannotReleaseBusinessOccupation() throws Exception {
        for (String mode : List.of("INVALID", "NOT_FOUND", "SUCCEEDED", "REVERSED")) {
            var report = paymentReport(); UUID id = authorizePayment(report);
            ok(send("/api/v1/cashier/payments/" + id + "/actions", "cashier", cashierInput("EXECUTE", 1, null)), 202); paymentRequestWorker.poll();
            paymentMode = mode.equals("NOT_FOUND") ? "INVALID" : mode; paymentWorker.poll(); var operation = paymentOperations.find("demo", id).orElseThrow();
            if (mode.equals("NOT_FOUND")) {
                paymentMode = "NOT_FOUND"; ok(send("/api/v1/cashier/payments/" + id + "/actions", "cashier", cashierInput("QUERY", 2, operation.version())), 202);
                paymentWorker.poll(); operation = paymentOperations.find("demo", id).orElseThrow();
            }
            var input = Map.of("action", "RETIRE", "authorizationVersion", 2, "operationVersion", operation.version(), "comment", "不安全的结束应拒绝");
            assertThat(ok(read(paymentPath(report), "finance"), 200).at("/actions/retire").asBoolean()).isFalse();
            assertCode(send("/api/v1/payments/" + id + "/finance-actions", "finance", input), "PAYMENT_RETIREMENT_UNSAFE");
            assertCode(send(paymentPath(report) + "/authorizations", "finance", authorizationInput(report)), "PAYMENT_AUTHORIZATION_EXISTS");
            assertThat(paymentOperations.find("demo", id)).contains(operation);
        }
    }

    @Test void staleRetirementAndEvidenceWriteFailureLeaveOriginalQueueAndOccupation() throws Exception {
        var report = paymentReport(); UUID id = authorizePayment(report);
        ok(send("/api/v1/cashier/payments/" + id + "/actions", "cashier", cashierInput("EXECUTE", 1, null)), 202); paymentRequestWorker.poll();
        var original = paymentOperations.find("demo", id).orElseThrow(); String path = "/api/v1/payments/" + id + "/finance-actions";
        assertCode(send(path, "finance", Map.of("action", "RETIRE", "authorizationVersion", 2, "operationVersion", original.version() + 1, "comment", "过时页面")), "CONCURRENCY_CONFLICT");
        jdbc.execute("ALTER TABLE payment_retirement ADD CONSTRAINT reject_retirement_fixture CHECK (authorization_id <> '" + id + "')");
        try {
            assertThatThrownBy(() -> send(path, "finance", Map.of("action", "RETIRE", "authorizationVersion", 2, "operationVersion", original.version(), "comment", "证据写入失败不能结束")))
                    .hasCauseInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(paymentAuthorizations.find("demo", id).orElseThrow().status()).isEqualTo(PaymentAuthorization.Status.EXECUTION_REGISTERED);
            assertThat(paymentOperations.find("demo", id)).contains(original);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='PAYMENT_RETIRE'", Integer.class, report.applicationId().toString())).isZero();
        } finally { jdbc.execute("ALTER TABLE payment_retirement DROP CONSTRAINT reject_retirement_fixture"); }
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

    @Test
    void expenseWorkspacePaginatesOnlyOwnedReportsAndDoesNotExposeFinancialSourceSecrets() throws Exception {
        fixture(true); var first = fixture(true); var second = fixture(false); var third = fixture(false);
        var page = ok(read("/api/v1/expense-reports?limit=1&status=DRAFT", "alice"), 200);
        assertThat(page.path("items")).hasSize(1); assertThat(page.path("items").get(0).path("id").asText()).isEqualTo(third.report().id().toString());
        var next = ok(read("/api/v1/expense-reports?limit=1&status=DRAFT&beforeId=" + page.path("nextBeforeId").asText(), "alice"), 200);
        assertThat(next.path("items").get(0).path("id").asText()).isEqualTo(second.report().id().toString());
        assertThat(ok(read("/api/v1/expense-reports", "admin"), 200).path("items")).isEmpty();
        assertThat(ok(read("/api/v1/expense-reports", "bob"), 200).path("items")).isEmpty();
        for (String path : List.of("/api/v1/expense-reports", "/api/v1/expense-requests", "/api/v1/employee-advances")) {
            assertThat(read(path + "?employeeId=alice", "bob").getStatus()).isEqualTo(400);
            assertThat(read(path + "?limit=0", "alice").getStatus()).isEqualTo(400);
            assertThat(read(path + "?limit=101", "alice").getStatus()).isEqualTo(400);
        }
        for (String path : List.of("/api/v1/expense-requests", "/api/v1/employee-advances")) {
            var firstPage = ok(read(path + "?limit=1", "alice"), 200);
            var secondPage = ok(read(path + "?limit=1&beforeId=" + firstPage.path("nextBeforeId").asText(), "alice"), 200);
            assertThat(secondPage.path("items")).hasSize(1);
            assertThat(secondPage.path("items").get(0).path("id").asText()).isNotEqualTo(firstPage.path("items").get(0).path("id").asText());
        }
        var prior = ok(read("/api/v1/expense-requests?limit=100", "alice"), 200).path("items");
        var found = java.util.stream.StreamSupport.stream(prior.spliterator(), false).filter(item -> item.path("id").asText().equals(first.prior().toString())).findFirst().orElseThrow();
        assertThat(found.path("lines").get(0).path("available").path("value").asText()).isEqualTo("200.00");
        var loansResponse = read("/api/v1/employee-advances?limit=100", "alice");
        assertThat(loansResponse.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(loansResponse.getContentAsString()).doesNotContain("synthetic-payment", "paymentReference", "account", "reservations");
        var held = advances.find("demo", first.advance()).orElseThrow(); long oldVersion = held.version();
        held.requirePaymentReview(oldVersion); advances.update(held, oldVersion, "payment-settlement", "PAYMENT_REVIEW");
        var heldView = java.util.stream.StreamSupport.stream(ok(read("/api/v1/employee-advances?limit=100", "alice"), 200).path("items").spliterator(), false)
                .filter(item -> item.path("id").asText().equals(first.advance().toString())).findFirst().orElseThrow();
        assertThat(heldView.path("status").asText()).isEqualTo("PAYMENT_REVIEW");
        assertThat(heldView.path("available").path("value").asText()).isEqualTo("0.00");
        assertThat(heldView.path("paid").path("value").asText()).isEqualTo("200.00");
        assertThat(ok(read("/api/v1/employee-advances", "bob"), 200).path("items")).isEmpty();
        assertThat(read("/api/v1/expense-reports?beforeId=" + first.report().id(), "bob").getStatus()).isEqualTo(400);
        assertThat(read("/api/v1/employee-advances?beforeId=" + first.advance(), "bob").getStatus()).isEqualTo(400);
        assertThat(read("/api/v1/expense-requests?beforeId=" + first.advance(), "alice").getStatus()).isEqualTo(400);
    }

    @Test
    void expenseWorkflowShowsRealControlsAcrossSubmissionReceiptReductionAndBudgetConfirmation() throws Exception {
        var report = fixture(false).report(); String path = path(report) + "/workflow";
        var draft = ok(read(path, "alice"), 200); assertThat(draft.path("canCancel").asBoolean()).isTrue(); assertThat(draft.path("canWithdraw").asBoolean()).isFalse();
        assertThat(draft.path("budget").path("confirmedCurrent").asBoolean()).isFalse();
        submit(report); var business = ok(read(path + "?taskId=" + task(report).getId(), "manager"), 200);
        assertThat(business.path("task").path("stage").asText()).isEqualTo("BUSINESS");
        assertThat(business.path("budget").path("operationStatus").asText()).isEqualTo("QUEUED");
        assertThat(ok(read(path, "alice"), 200).path("canWithdraw").asBoolean()).isTrue();
        ok(act(report, "manager", "APPROVE"), 200);
        var receipt = ok(read(path + "?taskId=" + task(report).getId(), "finance"), 200);
        assertThat(receipt.path("task").path("canReceive").asBoolean()).isTrue();
        ok(send(path(report) + "/tasks/" + task(report).getId() + "/receive", "finance", receiveInput(report)), 200);
        ok(act(report, "finance", "APPROVE"), 200);
        assertThat(ok(read(path, "alice"), 200).path("canWithdraw").asBoolean()).isFalse();
        var financial = ok(read(path + "?taskId=" + task(report).getId(), "finance"), 200);
        assertThat(financial.path("paper").path("received").asBoolean()).isTrue();
        assertThat(financial.path("task").path("canReduce").asBoolean()).isFalse();
        budgetWorker.poll();
        assertThat(ok(read(path + "?taskId=" + task(report).getId(), "finance"), 200).path("task").path("canReduce").asBoolean()).isTrue();
        ok(send(reductionPath(report), "finance", reductionInput(report, "50", "3")), 200);
        financial = ok(read(path + "?taskId=" + task(report).getId(), "finance"), 200);
        assertThat(financial.path("budget").path("ledgerStatus").asText()).isEqualTo("FROZEN");
        assertThat(financial.path("budget").path("confirmedCurrent").asBoolean()).isFalse();
        assertThat(financial.path("task").path("canReduce").asBoolean()).isFalse();
        assertThat(financial.toString()).doesNotContain("targetDigest", "synthetic-budget", "allocations", "account");
        budgetWorker.poll();
        assertThat(ok(read(path + "?taskId=" + task(report).getId(), "finance"), 200).path("budget").path("confirmedCurrent").asBoolean()).isTrue();
    }

    @Test
    void expenseWorkflowRetainsSensitiveFieldAndCurrentTaskBoundaries() throws Exception {
        hideBusinessDetails = true; var report = fixture(false).report(); submit(report); budgetWorker.poll();
        String path = path(report) + "/workflow";
        assertThat(read(path, "admin").getStatus()).isEqualTo(403);
        assertThat(read(path, "bob").getStatus()).isEqualTo(404);
        assertThat(read(path + "?taskId=" + task(report).getId(), "manager").getStatus()).isEqualTo(403);
        assertThat(read(path + "?tenantId=other", "alice").getStatus()).isEqualTo(400);
        ok(act(report, "manager", "APPROVE"), 200); String receiptTask = task(report).getId();
        ok(send("/api/v1/tasks/" + receiptTask + "/actions", "finance", Map.of("action", "DELEGATE", "targetUser", "manager", "expectedVersion", app(report).version())), 200);
        var delegated = ok(read(path + "?taskId=" + receiptTask, "manager"), 200);
        assertThat(delegated.path("task").path("canReceive").asBoolean()).isFalse();
        assertThat(delegated.path("task").path("canReduce").asBoolean()).isFalse();
        assertThat(read(path + "?taskId=" + receiptTask, "finance").getStatus()).isEqualTo(403);
    }

    @Test void archiveWaitsForBothSettlementAndPaymentVoucher() throws Exception {
        var report = paidExpense();
        var view = ok(read(path(report) + "/archive", "finance"), 200);
        assertThat(view.path("status").asText()).isEqualTo("WAITING");
        assertThat(view.path("issue").asText()).isEqualTo("ARCHIVE_SETTLEMENT_REQUIRED");
        settlementWorker.poll(); budgetWorker.poll();
        view = ok(read(path(report) + "/archive", "finance"), 200);
        assertThat(view.path("status").asText()).isEqualTo("WAITING");
        assertThat(view.path("canDownload").asBoolean()).isFalse();
        pollArchive(); view = ok(read(path(report) + "/archive", "finance"), 200);
        assertThat(view.path("status").asText()).isEqualTo("BLOCKED");
        assertThat(view.path("issue").asText()).isEqualTo("ARCHIVE_PAYMENT_VOUCHER_REQUIRED");
        assertCode(read(path(report) + "/archive/content", "finance"), "ARCHIVE_NOT_READY");
    }

    @Test void sealedArchiveContainsOriginalBytesAndStableEvidenceWithoutRepeatingFinancialEffects() throws Exception {
        var report = archiveReadyExpense(); var versions = resourceVersions(report);
        pollArchive(); var entry = archives.find("demo", report.id(), 1).orElseThrow();
        assertThat(entry.archive()).isNotNull(); var manifest = entry.archive().manifest();
        assertThat(manifest.vouchers()).extracting(ExpenseArchive.Voucher::kind).containsExactly(VoucherCommand.Kind.EXPENSE_ACCRUAL, VoucherCommand.Kind.PAYMENT);
        assertThat(manifest.history()).anyMatch(event -> "APPROVE".equals(event.action()));
        assertThat(manifest.control().paperReady()).isTrue();
        assertThat(entry.encoded()).doesNotContain("targetDigest", "debitAccountReference", "bearerToken", "gateway");
        byte[] zip = archiveDownload(report, "finance"); var contents = unzipArchive(zip);
        assertThat(contents.get("manifest.json")).isEqualTo(entry.encoded().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(new String(contents.get("manifest.sha256"), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(entry.sha256() + "  manifest.json\n");
        var original = manifest.originals().get(0);
        assertThat(contents.get(original.entryName())).isEqualTo(java.nio.file.Files.readAllBytes(DIRECTORY.resolve(original.file().id() + ".bin")));
        pollArchive(); pollArchive();
        assertThat(archiveDownload(report, "alice")).isEqualTo(zip);
        assertThat(archives.find("demo", report.id(), 1).orElseThrow()).isEqualTo(entry);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='EXPENSE_ARCHIVED'", Integer.class, report.applicationId().toString())).isEqualTo(1);
        assertThat(resourceVersions(report)).isEqualTo(versions); assertThat(paymentWrites).isEqualTo(1);
        var response = read(path(report) + "/archive", "finance"); var view = ok(response, 200);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store"); assertThat(view.path("issue").isNull()).isTrue();
        assertThat(view.path("originalCount").asInt()).isEqualTo(1); assertThat(view.path("voucherCount").asInt()).isEqualTo(2);
    }

    @Test void missingOrDamagedOriginalBlocksArchiveAndDownloadWithoutReplacingFrozenReceipt() throws Exception {
        var report = archiveReadyExpense(); var manifest = archiveService.prepare("demo", report.id());
        var original = manifest.originals().get(0); var file = DIRECTORY.resolve(original.file().id() + ".bin"); var bytes = java.nio.file.Files.readAllBytes(file);
        try {
            java.nio.file.Files.write(file, new byte[] { 0, 1 }); pollArchive();
            var entry = archives.find("demo", report.id(), 1).orElseThrow(); assertThat(entry.archive()).isNull(); assertThat(entry.issue()).isEqualTo("FILE_INTEGRITY_FAILED");
            java.nio.file.Files.write(file, bytes); pollArchive(); pollArchive();
            entry = archives.find("demo", report.id(), 1).orElseThrow(); assertThat(entry.archive()).isNotNull();
            assertThat(entry.archive().manifest().originals().get(0)).isEqualTo(original);
            java.nio.file.Files.write(file, new byte[] { 1 });
            assertThat(ok(read(path(report) + "/archive/content", "finance"), 503).path("code").asText()).isEqualTo("FILE_INTEGRITY_FAILED");
            assertThat(archives.find("demo", report.id(), 1).orElseThrow()).isEqualTo(entry);
        } finally { java.nio.file.Files.write(file, bytes); }
    }

    @Test void bankReturnDuringFileCheckCannotSeal() throws Exception {
        var report = archiveReadyExpense(); var candidate = archiveService.prepare("demo", report.id()); archiveFiles.verify(candidate);
        var payment = paymentOperations.find("demo", candidate.settlement().input().payment().operationId()).orElseThrow();
        tx().executeWithoutResult(status -> paymentExecution.query("demo", payment.input().command().id(), payment.version(), Instant.now()));
        paymentMode = "REVERSED"; paymentWorker.poll();
        assertThatThrownBy(() -> archiveService.complete(candidate)).isInstanceOf(io.agentflow.common.DomainException.class).hasMessageContaining("not ready");
        assertThat(archives.find("demo", report.id(), 1)).isEmpty();
    }

    @Test void paymentVoucherReversalKeepsOriginalArchiveAndShowsCurrentDispute() throws Exception {
        var report = archiveReadyExpense(); pollArchive(); var entry = archives.find("demo", report.id(), 1).orElseThrow();
        byte[] before = archiveDownload(report, "finance"); var path = voucherPath(report) + "/payment";
        var view = ok(read(path, "finance"), 200); voucherMode = "REVERSED";
        ok(send(path + "/actions", "finance", voucherInput(report, "QUERY", view.path("operation"))), 202); voucherWorker.poll();
        var after = ok(read(path(report) + "/archive", "finance"), 200);
        assertThat(after.path("status").asText()).isEqualTo("ARCHIVED"); assertThat(after.path("issue").asText()).isEqualTo("ARCHIVE_PAYMENT_VOUCHER_REQUIRED");
        assertThat(after.path("canDownload").asBoolean()).isTrue(); assertThat(archiveDownload(report, "finance")).isEqualTo(before);
        assertThat(archives.find("demo", report.id(), 1).orElseThrow()).isEqualTo(entry); assertThat(paymentWrites).isEqualTo(1);
    }

    @Test void archiveAccessAlwaysUsesOriginalRoundFieldsAndCurrentIdentity() throws Exception {
        hideBusinessDetails = true; var report = archiveReadyExpense(); pollArchive(); String path = path(report) + "/archive";
        ok(read(path, "finance"), 200); ok(read(path, "alice"), 200);
        for (String user : List.of("bob", "cashier", "admin", "manager")) {
            assertThat(read(path, user).getStatus()).isIn(403, 404); assertThat(read(path + "/content", user).getStatus()).isIn(403, 404);
        }
        assertThat(read(path + "?tenantId=other", "finance").getStatus()).isEqualTo(400);
        assertThat(read(path + "?roundNo=2", "finance").getStatus()).isEqualTo(404);
        var person = organizationRepository.person("demo", finance).orElseThrow();
        var inactive = organization.updatePerson(admin, finance, person.displayName(), false, person.approvalEligible(), person.revision());
        try { assertThat(read(path + "/content", "finance").getStatus()).isIn(403, 404); }
        finally { organization.updatePerson(admin, finance, person.displayName(), person.active(), person.approvalEligible(), inactive.revision()); }
        assertThat(archiveDownload(report, "alice")).isNotEmpty();
    }

    @Test void originalReferenceFailureRollsBackSealAndAuditTogether() throws Exception {
        var report = archiveReadyExpense(); var candidate = archiveService.prepare("demo", report.id());
        archiveService.block("demo", report.id(), "ARCHIVE_CHECK_PENDING"); var original = candidate.originals().get(0);
        jdbc.update("INSERT INTO expense_archive_original(tenant_id,report_id,round_no,original_id) VALUES('demo',?,1,?)", report.id().toString(), original.file().id().toString());
        assertThatThrownBy(() -> archiveService.complete(candidate)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(archives.find("demo", report.id(), 1).orElseThrow().archive()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='EXPENSE_ARCHIVED'", Integer.class, report.applicationId().toString())).isZero();
        jdbc.update("DELETE FROM expense_archive_original WHERE tenant_id='demo' AND report_id=?", report.id().toString());
        pollArchive(); assertThat(archives.find("demo", report.id(), 1).orElseThrow().archive()).isNotNull();
    }

    private void pollArchive() { archiveWorker.poll(); archiveWorker.poll(); }
    private ExpenseReport archiveReadyExpense() throws Exception {
        var report = paidExpense(); settlementWorker.poll(); budgetWorker.poll(); voucherPreparationWorker.poll(); voucherWorker.poll(); return report;
    }
    private byte[] archiveDownload(ExpenseReport report, String user) throws Exception {
        var pending = mvc.perform(get(path(report) + "/archive/content?roundNo=1").header("Authorization", "Bearer " + auth.login("demo", user, "demo").token())).andReturn();
        assertThat(pending.getRequest().isAsyncStarted()).isTrue(); pending.getAsyncResult(5000);
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(pending)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200); assertThat(response.getContentType()).isEqualTo("application/zip");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store"); return response.getContentAsByteArray();
    }
    private Map<String, byte[]> unzipArchive(byte[] value) throws Exception {
        var entries = new LinkedHashMap<String, byte[]>();
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(value))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) entries.put(entry.getName(), zip.readAllBytes());
        }
        return entries;
    }

    @Test void confirmedExpensePaymentRegistersSettlementWithoutConsumingResourcesInsideTheBankCallback() throws Exception {
        var report = paidExpense(); var original = settlements.find("demo", report.id()).orElseThrow();
        assertThat(original.status()).isEqualTo(ExpenseSettlement.Status.QUEUED); assertThat(original.resourcesConsumed()).isFalse();
        var invoiceId = current(report).currentRound().originalLines().get(0).original().invoiceIds().get(0);
        assertThat(invoices.find("demo", invoiceId).orElseThrow().occupation()).isEqualTo(Invoice.Occupation.OCCUPIED);
        settlementWorker.poll(); var pending = settlements.find("demo", report.id()).orElseThrow();
        assertThat(pending.status()).isEqualTo(ExpenseSettlement.Status.BUDGET_PENDING); assertThat(pending.resourcesConsumed()).isTrue();
        assertThat(pending.input().payment().amount()).isEqualTo(money("50"));
        assertThat(invoices.find("demo", invoiceId).orElseThrow().occupation()).isEqualTo(Invoice.Occupation.CONSUMED);
        var round = current(report).currentRound(); var prior = round.originalLines().get(0).original().priorRequest();
        assertThat(requests.find("demo", prior.requestId()).orElseThrow().balance(1).consumed()).isEqualTo(money("100"));
        assertThat(advances.find("demo", round.advanceOffsets().get(0).advanceId()).orElseThrow().balance().consumed()).isEqualTo(money("50"));
        var versions = resourceVersions(report); settlementWorker.poll(); assertThat(resourceVersions(report)).isEqualTo(versions);
        budgetWorker.poll(); assertThat(settlements.find("demo", report.id()).orElseThrow().status()).isEqualTo(ExpenseSettlement.Status.SETTLED);
        assertThat(occupations.find("demo", report.id()).orElseThrow().status()).isEqualTo(BudgetOccupation.Status.CONSUMED);
        var payment = paymentOperations.find("demo", original.input().payment().operationId()).orElseThrow();
        tx().executeWithoutResult(status -> paymentExecution.query("demo", payment.input().command().id(), payment.version(), Instant.now())); paymentWorker.poll();
        assertThat(settlements.find("demo", report.id()).orElseThrow().input()).isEqualTo(original.input());
        assertThat(resourceVersions(report)).isEqualTo(versions); assertThat(paymentWrites).isEqualTo(1);
    }

    @Test void budgetConsumeRejectionRetriesOnlyBudgetAndKeepsConsumedResources() throws Exception {
        var report = paidExpense(); settlementWorker.poll(); var versions = resourceVersions(report);
        var first = settlements.find("demo", report.id()).orElseThrow().budgetOperationId();
        budgetStatus = BudgetObservation.Status.REJECTED; budgetRejection = BudgetObservation.Rejection.ACCOUNTING_PERIOD_CLOSED; budgetWorker.poll();
        var rejected = settlements.find("demo", report.id()).orElseThrow();
        assertThat(rejected.status()).isEqualTo(ExpenseSettlement.Status.BUDGET_REJECTED); assertThat(rejected.issue()).isEqualTo("BUDGET_ACCOUNTING_PERIOD_CLOSED");
        budgetStatus = BudgetObservation.Status.APPLIED;
        String path = path(report) + "/settlement"; var readResponse = read(path, "finance"); var view = ok(readResponse, 200);
        assertThat(readResponse.getHeader("Cache-Control")).isEqualTo("no-store"); assertThat(view.path("canRetry").asBoolean()).isTrue();
        assertThat(view.toString()).doesNotContain("account", "commandDigest", "targetDigest", "receiptReference", "synthetic-payment");
        assertThat(ok(read(path, "alice"), 200).path("canRetry").asBoolean()).isFalse();
        for (String user : List.of("bob", "cashier", "admin")) assertThat(read(path, user).getStatus()).isIn(403, 404);
        assertThat(read(path + "?tenantId=foreign", "finance").getStatus()).isEqualTo(400);
        assertThat(read(path + "?roundNo=0", "finance").getStatus()).isEqualTo(400);
        var input = Map.<String, Object>of("roundNo", app(report).roundNo(), "applicationVersion", app(report).version(), "financialVersion", current(report).version(), "settlementVersion", rejected.version(), "comment", "会计期间已重新核对");
        for (String user : List.of("alice", "manager", "admin", "bob", "cashier")) assertThat(send(path + "/retry", user, input).getStatus()).isIn(403, 404);
        var forged = new HashMap<>(input); forged.put("resourcesConsumed", false); assertThat(send(path + "/retry", "finance", forged).getStatus()).isEqualTo(400);
        var stale = new HashMap<>(input); stale.put("settlementVersion", rejected.version() - 1); assertCode(send(path + "/retry", "finance", stale), "CONCURRENCY_CONFLICT");
        String key = UUID.randomUUID().toString(); var response = send(path + "/retry", "finance", key, input); ok(response, 202);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(send(path + "/retry", "finance", key, input).getContentAsString()).isEqualTo(response.getContentAsString());
        var person = organizationRepository.person("demo", finance).orElseThrow();
        var inactive = organization.updatePerson(admin, finance, person.displayName(), false, person.approvalEligible(), person.revision());
        try { assertThat(send(path + "/retry", "finance", key, input).getStatus()).isIn(403, 404); }
        finally { organization.updatePerson(admin, finance, person.displayName(), person.active(), person.approvalEligible(), inactive.revision()); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='EXPENSE_SETTLEMENT_RETRY'", Integer.class, report.applicationId().toString())).isEqualTo(1);
        settlementWorker.poll();
        assertThat(resourceVersions(report)).isEqualTo(versions);
        assertThat(settlements.find("demo", report.id()).orElseThrow().budgetOperationId()).isNotEqualTo(first);
        budgetWorker.poll(); assertThat(settlements.find("demo", report.id()).orElseThrow().status()).isEqualTo(ExpenseSettlement.Status.SETTLED);
        assertThat(paymentWrites).isEqualTo(1);
    }

    @Test void localRevisionFailureRollsBackAllConsumptionAndBudgetQueueButNotBankSuccess() throws Exception {
        var report = paidExpense(); var queued = settlements.find("demo", report.id()).orElseThrow(); var versions = resourceVersions(report);
        jdbc.update("INSERT INTO expense_settlement_revision(tenant_id,report_id,version,state_json) VALUES('demo',?,2,?)", report.id().toString(), json.write(queued));
        settlementWorker.poll(); assertThat(settlements.find("demo", report.id()).orElseThrow()).isEqualTo(queued);
        assertThat(resourceVersions(report)).isEqualTo(versions); assertThat(occupations.find("demo", report.id()).orElseThrow().pendingOperationId()).isNull();
        assertThat(paymentOperations.find("demo", queued.input().payment().operationId()).orElseThrow().settleable()).isTrue();
        jdbc.update("DELETE FROM expense_settlement_revision WHERE tenant_id='demo' AND report_id=? AND version=2", report.id().toString());
        settlementWorker.poll(); assertThat(settlements.find("demo", report.id()).orElseThrow().status()).isEqualTo(ExpenseSettlement.Status.BUDGET_PENDING);
    }

    @Test void disputedAdvanceBlocksWholeConsumptionWithoutErasingBankReceipt() throws Exception {
        var report = paidExpense(); var id = current(report).currentRound().advanceOffsets().get(0).advanceId();
        var advance = advances.find("demo", id).orElseThrow(); long version = advance.version(); advance.requirePaymentReview(version); advances.update(advance, version, "fixture", "PAYMENT_REVIEW");
        var versions = resourceVersions(report); settlementWorker.poll(); var blocked = settlements.find("demo", report.id()).orElseThrow();
        assertThat(blocked.status()).isEqualTo(ExpenseSettlement.Status.BLOCKED); assertThat(blocked.issue()).isEqualTo("ADVANCE_PAYMENT_REVIEW_REQUIRED");
        assertThat(blocked.resourcesConsumed()).isFalse(); assertThat(resourceVersions(report)).isEqualTo(versions);
        assertThat(paymentOperations.find("demo", blocked.input().payment().operationId()).orElseThrow().settleable()).isTrue();
    }

    @Test void failedReverificationAfterReservationBlocksUntilActualNewSuccess() throws Exception {
        var report = paidExpense(); var invoice = current(report).currentRound().originalLines().get(0).original().invoiceIds().get(0);
        verificationUnavailable = true; verify(invoice); var versions = resourceVersions(report); settlementWorker.poll();
        var blocked = settlements.find("demo", report.id()).orElseThrow(); assertThat(blocked.issue()).isEqualTo("INVOICE_VERIFICATION_REQUIRED");
        assertThat(resourceVersions(report)).isEqualTo(versions); verificationUnavailable = false; verify(invoice);
        tx().executeWithoutResult(status -> settlementService.retry("demo", report.id(), blocked.version())); settlementWorker.poll();
        assertThat(settlements.find("demo", report.id()).orElseThrow().status()).isEqualTo(ExpenseSettlement.Status.BUDGET_PENDING);
    }

    @Test void fullAdvanceOffsetSettlesAfterAccrualWithoutAnyPaymentAuthorizationOrBankCall() throws Exception {
        advanceOffset = "100"; var fixture = fixture(true); var report = fixture.report(); enterFinance(report); ok(act(report, "finance", "APPROVE"), 200);
        voucherPreparationWorker.poll(); assertThat(settlements.find("demo", report.id())).isEmpty(); voucherWorker.poll();
        var queued = settlements.find("demo", report.id()).orElseThrow(); assertThat(queued.input().payment()).isNull(); assertThat(queued.input().payable()).isEqualTo(money("0"));
        settlementWorker.poll(); budgetWorker.poll(); assertThat(settlements.find("demo", report.id()).orElseThrow().status()).isEqualTo(ExpenseSettlement.Status.SETTLED);
        assertThat(advances.find("demo", fixture.advance()).orElseThrow().balance().consumed()).isEqualTo(money("100"));
        assertThat(paymentAuthorizations.latest("demo", report.applicationId(), 1)).isEmpty(); assertThat(paymentWrites).isZero();
        pollArchive(); var archive = archives.find("demo", report.id(), 1).orElseThrow().archive();
        assertThat(archive).isNotNull(); assertThat(archive.manifest().vouchers()).extracting(ExpenseArchive.Voucher::kind).containsExactly(VoucherCommand.Kind.EXPENSE_ACCRUAL);
        assertThat(archive.manifest().settlement().input().payment()).isNull();
    }

    @Test void lateSuccessAfterApprovalRevocationIsRecordedButCannotConsume() throws Exception {
        var report = paymentReport(true); UUID id = authorizePayment(report);
        ok(send("/api/v1/cashier/payments/" + id + "/actions", "cashier", cashierInput("EXECUTE", 1, null)), 202); paymentRequestWorker.poll();
        paymentMode = "INVALID"; paymentWorker.poll(); var unknown = paymentOperations.find("demo", id).orElseThrow();
        assertThat(unknown.status()).isEqualTo(PaymentOperation.Status.UNKNOWN);
        jdbc.update("UPDATE approval_application SET status='REVOKED',version=version+1 WHERE tenant_id='demo' AND id=?", report.applicationId().toString());
        tx().executeWithoutResult(status -> paymentExecution.query("demo", id, unknown.version(), Instant.now())); paymentMode = "SUCCEEDED"; paymentWorker.poll();
        assertThat(paymentOperations.find("demo", id).orElseThrow().settleable()).isTrue(); var versions = resourceVersions(report); settlementWorker.poll();
        assertThat(settlements.find("demo", report.id()).orElseThrow().status()).isEqualTo(ExpenseSettlement.Status.BLOCKED); assertThat(resourceVersions(report)).isEqualTo(versions);
        voucherPreparationWorker.poll(); voucherWorker.poll();
        assertThat(voucherOperations.forRound("demo", report.applicationId(), 1, VoucherCommand.Kind.PAYMENT).orElseThrow().usablePosted()).isTrue();
        assertThat(resourceVersions(report)).isEqualTo(versions); assertThat(paymentWrites).isEqualTo(1);
    }

    @Test void paymentVoucherUsesFrozenExpenseTimeZoneAfterResourcesAndBudgetWereConsumed() throws Exception {
        legalTimeZone = "America/New_York"; var report = paidExpense(); settlementWorker.poll(); budgetWorker.poll();
        assertThat(settlements.find("demo", report.id()).orElseThrow().status()).isEqualTo(ExpenseSettlement.Status.SETTLED);
        var versions = resourceVersions(report); var source = voucherPreparations.latest("demo", report.applicationId(), 1, VoucherCommand.Kind.PAYMENT).orElseThrow().input().source();
        var original = paymentOperations.revision("demo", source.paymentOperationId(), source.paymentVersion()).orElseThrow();
        legalTimeZone = "Asia/Shanghai"; voucherPreparationWorker.poll(); voucherWorker.poll();
        var voucher = voucherOperations.forRound("demo", report.applicationId(), 1, VoucherCommand.Kind.PAYMENT).orElseThrow();
        assertThat(voucher.usablePosted()).isTrue();
        assertThat(voucher.input().command().accountingDate()).isEqualTo(LocalDate.ofInstant(original.observation().completedAt(), java.time.ZoneId.of("America/New_York")));
        assertThat(voucher.input().command().totals().gross()).isEqualTo(current(report).currentRound().payable());
        assertThat(voucher.input().command().payment().receipt()).isEqualTo(original.observation());
        assertThat(resourceVersions(report)).isEqualTo(versions); assertThat(paymentWrites).isEqualTo(1);
    }

    @Test void paymentVoucherHttpRequiresCurrentFinanceScopeAndNeverAcceptsAccrualOperationIds() throws Exception {
        var report = paidExpense(); String path = voucherPath(report) + "/payment";
        configuration.setEnabled(false); voucherPreparationWorker.poll(); configuration.setEnabled(true);
        var response = read(path, "finance"); var view = ok(response, 200);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(view.path("kind").asText()).isEqualTo("PAYMENT"); assertThat(view.path("preparation").path("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(view.path("actions").path("prepare").asBoolean()).isTrue();
        assertThat(view.toString()).doesNotContain("accountDigest", "debitAccountReference", "paymentVersion", "targetDigest", "commandDigest", "receiptReference");
        assertThat(ok(read(path, "alice"), 200).path("actions").path("prepare").asBoolean()).isFalse();
        for (String user : List.of("bob", "cashier", "admin")) assertThat(read(path, user).getStatus()).isIn(403, 404);
        assertThat(read(path + "?kind=EXPENSE_ACCRUAL", "finance").getStatus()).isEqualTo(400);
        var input = voucherInput(report, "PREPARE", null);
        for (String user : List.of("alice", "manager", "cashier", "admin", "bob")) assertThat(send(path + "/actions", user, input).getStatus()).isIn(403, 404);
        var forged = new HashMap<>(input); forged.put("paymentVersion", 99); assertThat(send(path + "/actions", "finance", forged).getStatus()).isEqualTo(400);
        var stale = new HashMap<>(input); stale.put("applicationVersion", app(report).version() - 1); assertCode(send(path + "/actions", "finance", stale), "CONCURRENCY_CONFLICT");
        String key = UUID.randomUUID().toString(); var accepted = send(path + "/actions", "finance", key, input); var receipt = ok(accepted, 202);
        assertThat(receipt.path("kind").asText()).isEqualTo("PAYMENT"); assertThat(accepted.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(send(path + "/actions", "finance", key, input).getContentAsString()).isEqualTo(accepted.getContentAsString());
        var person = organizationRepository.person("demo", finance).orElseThrow();
        var inactive = organization.updatePerson(admin, finance, person.displayName(), false, person.approvalEligible(), person.revision());
        try { assertThat(send(path + "/actions", "finance", key, input).getStatus()).isIn(403, 404); }
        finally { organization.updatePerson(admin, finance, person.displayName(), person.active(), person.approvalEligible(), inactive.revision()); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='PAYMENT_VOUCHER_PREPARE'", Integer.class, report.applicationId().toString())).isEqualTo(1);
        voucherPreparationWorker.poll(); voucherWorker.poll();
        var posted = ok(read(path, "finance"), 200); assertThat(posted.path("operation").path("status").asText()).isEqualTo("POSTED");
        var accrual = ok(read(voucherPath(report), "finance"), 200);
        assertThat(send(path + "/actions", "finance", voucherInput(report, "QUERY", accrual.path("operation"))).getStatus()).isEqualTo(404);
        assertThat(send(voucherPath(report) + "/actions", "finance", voucherInput(report, "QUERY", posted.path("operation"))).getStatus()).isEqualTo(404);
        ok(send(path + "/actions", "finance", voucherInput(report, "QUERY", posted.path("operation"))), 202); voucherWorker.poll();
        assertThat(paymentWrites).isEqualTo(1);
    }

    @Test void upgradeRecoveryUsesOriginalPaidFactWithoutQueryingOrSendingAgain() throws Exception {
        var report = paidExpense(); var queued = settlements.find("demo", report.id()).orElseThrow();
        jdbc.update("DELETE FROM expense_settlement_revision WHERE tenant_id='demo' AND report_id=?", report.id().toString());
        jdbc.update("DELETE FROM expense_settlement WHERE tenant_id='demo' AND report_id=?", report.id().toString());
        var candidate = settlements.recoveryCandidates(null).stream().filter(value -> value.kind() == JdbcExpenseSettlementRepository.FundingKind.PAYMENT && value.reportId().equals(report.id())).findFirst().orElseThrow();
        settlementRegistration.recover(candidate); settlementRegistration.recover(candidate);
        assertThat(settlements.find("demo", report.id()).orElseThrow().input()).isEqualTo(queued.input()); assertThat(paymentWrites).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_settlement_revision WHERE tenant_id='demo' AND report_id=?", Integer.class, report.id().toString())).isEqualTo(1);
    }

    @Test void bankReturnFreezesExistingConsumptionAndLateBudgetSuccessCannotClearReview() throws Exception {
        var report = paidExpense(); settlementWorker.poll(); var original = settlements.find("demo", report.id()).orElseThrow(); var versions = resourceVersions(report);
        var payment = paymentOperations.find("demo", original.input().payment().operationId()).orElseThrow();
        tx().executeWithoutResult(status -> paymentExecution.query("demo", payment.input().command().id(), payment.version(), Instant.now())); paymentMode = "REVERSED"; paymentWorker.poll();
        var review = settlements.find("demo", report.id()).orElseThrow(); assertThat(review.status()).isEqualTo(ExpenseSettlement.Status.REVIEW_REQUIRED);
        assertThat(review.input()).isEqualTo(original.input()); assertThat(review.resourcesConsumed()).isTrue();
        budgetWorker.poll(); settlementWorker.poll(); assertThat(settlements.find("demo", report.id()).orElseThrow()).isEqualTo(review);
        assertThat(occupations.find("demo", report.id()).orElseThrow().status()).isEqualTo(BudgetOccupation.Status.CONSUMED);
        assertThat(resourceVersions(report)).isEqualTo(versions); assertThat(paymentWrites).isEqualTo(1);
    }

    @Test void reversedAccrualFreezesSettledReportWithoutReopeningInvoicesOrOffsets() throws Exception {
        var report = paidExpense(); settlementWorker.poll(); budgetWorker.poll(); var versions = resourceVersions(report);
        var source = ok(read(voucherPath(report), "finance"), 200); voucherMode = "REVERSED";
        ok(send(voucherPath(report) + "/actions", "finance", voucherInput(report, "QUERY", source.path("operation"))), 202); voucherWorker.poll();
        assertThat(settlements.find("demo", report.id()).orElseThrow().status()).isEqualTo(ExpenseSettlement.Status.REVIEW_REQUIRED);
        assertThat(settlements.find("demo", report.id()).orElseThrow().issue()).isEqualTo("EXPENSE_VOUCHER_REVIEW"); assertThat(resourceVersions(report)).isEqualTo(versions);
    }

    private ExpenseReport paidExpense() throws Exception {
        var report = paymentReport(true); UUID id = authorizePayment(report);
        ok(send("/api/v1/cashier/payments/" + id + "/actions", "cashier", cashierInput("EXECUTE", 1, null)), 202);
        paymentRequestWorker.poll(); paymentWorker.poll(); assertThat(paymentOperations.find("demo", id).orElseThrow().settleable()).isTrue(); return report;
    }
    private List<ExpensePrecheckEvidence.ResourceVersion> resourceVersions(ExpenseReport report) {
        var round = current(report).currentRound(); var original = round.originalLines().get(0).original();
        return List.of(new ExpensePrecheckEvidence.ResourceVersion(ExpensePrecheckEvidence.ResourceKind.INVOICE, original.invoiceIds().get(0), invoices.find("demo", original.invoiceIds().get(0)).orElseThrow().version()),
                new ExpensePrecheckEvidence.ResourceVersion(ExpensePrecheckEvidence.ResourceKind.PRIOR_REQUEST, original.priorRequest().requestId(), requests.find("demo", original.priorRequest().requestId()).orElseThrow().version()),
                new ExpensePrecheckEvidence.ResourceVersion(ExpensePrecheckEvidence.ResourceKind.ADVANCE, round.advanceOffsets().get(0).advanceId(), advances.find("demo", round.advanceOffsets().get(0).advanceId()).orElseThrow().version()));
    }
    private org.springframework.transaction.support.TransactionTemplate tx() { return new org.springframework.transaction.support.TransactionTemplate(transactionManager); }

    private MockHttpServletResponse read(String path, String user) throws Exception {
        return mvc.perform(get(path).header("Authorization", "Bearer " + auth.login("demo", user, "demo").token())).andReturn().getResponse();
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
                List.of(line(invoice, priorId)), advanceId == null ? List.of() : List.of(new AdvanceOffset(advanceId, money(advanceOffset))));
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
                null, null, null, null, null, null, true, Map.of("business", hideBusinessDetails ? FieldVisibility.HIDDEN : FieldVisibility.READ_ONLY, "receipt", FieldVisibility.READ_ONLY, "finance", FieldVisibility.READ_ONLY)),
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
    private String voucherPath(ExpenseReport report) { return "/api/v1/applications/" + report.applicationId() + "/vouchers"; }
    private String paymentPath(ExpenseReport report) { return "/api/v1/applications/" + report.applicationId() + "/payments"; }
    private ExpenseReport paymentReport() throws Exception { return paymentReport(false); }
    private ExpenseReport paymentReport(boolean resources) throws Exception {
        var department = organization.createUnit(admin, OrganizationUnit.Kind.DEPARTMENT, "合成付款部门", entity, null, true);
        var position = organization.createUnit(admin, OrganizationUnit.Kind.POSITION, "合成付款岗位", entity, null, true);
        organization.createAppointment(admin, finance, department.id(), position.id(), true);
        cashierAppointment = organization.createAppointment(admin, person("cashier", false), department.id(), position.id(), true).id();
        var report = fixture(resources).report(); enterFinance(report); ok(act(report, "finance", "APPROVE"), 200);
        voucherPreparationWorker.poll(); voucherWorker.poll();
        assertThat(voucherOperations.forRound("demo", report.applicationId(), 1, VoucherCommand.Kind.EXPENSE_ACCRUAL).orElseThrow().usablePosted()).isTrue();
        return report;
    }
    private Map<String, Object> authorizationInput(ExpenseReport report) {
        var voucher = voucherOperations.forRound("demo", report.applicationId(), app(report).roundNo(), VoucherCommand.Kind.EXPENSE_ACCRUAL).orElseThrow();
        return Map.of("roundNo", app(report).roundNo(), "applicationVersion", app(report).version(), "businessVersion", current(report).version(),
                "voucherOperationId", voucher.input().command().id(), "voucherVersion", voucher.version(), "validitySeconds", 900, "comment", "合成财务付款授权");
    }
    private UUID authorizePayment(ExpenseReport report) throws Exception {
        return UUID.fromString(ok(send(paymentPath(report) + "/authorizations", "finance", authorizationInput(report)), 202).path("authorizationId").asText());
    }
    private Map<String, Object> cashierInput(String action, long authorizationVersion, Long operationVersion) {
        var input = new HashMap<String, Object>(); input.put("action", action); input.put("authorizationVersion", authorizationVersion); input.put("comment", "合成出纳付款办理");
        if (action.equals("EXECUTE")) { input.put("debitAccountReference", "synthetic-debit"); input.put("debitAccountVersion", "v1"); }
        else input.put("operationVersion", operationVersion); return input;
    }
    private Map<String, Object> voucherInput(ExpenseReport report, String action, JsonNode operation) {
        var input = new HashMap<String, Object>(); input.put("action", action); input.put("roundNo", app(report).roundNo());
        input.put("applicationVersion", app(report).version()); input.put("businessVersion", current(report).version()); input.put("comment", "合成财务对账操作");
        if (operation != null) { input.put("operationId", operation.path("id").asText()); input.put("operationVersion", operation.path("version").asLong()); }
        return input;
    }
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
            case "catalog" -> new FinanceCatalog("alice", "synthetic-v1", Instant.now().plusSeconds(600), List.of(new FinanceCatalog.LegalEntity(entity, "合成法人", "CNY", paperRequired, "v1", legalTimeZone)),
                    List.of(new FinanceCatalog.Category("OFFICE", "办公", List.of(ExpenseLine.Unit.ITEM))), List.of(new FinanceCatalog.CostCenter(entity, "IT", "研发")), List.of(), List.of(new FinanceCatalog.City("SH", "上海")));
            case "employee-account" -> new EmployeeAccountPort.Account(new EmployeeAccountSnapshot(entity, "alice", "synthetic-private-account", "****1234", "a".repeat(64), "v1"), Instant.now().plusSeconds(600));
            case "debit-accounts" -> {
                accountReads++; var request = json.read(data.toString(), PaymentAccountsPort.Request.class); var now = Instant.now();
                yield new PaymentAccountsPort.Directory(request, "directory-v1", now, now.plusSeconds(300),
                        List.of(new PaymentAccountsPort.DebitAccount("synthetic-debit", "合成基本户", "****4567", "CNY", "v1")));
            }
            case "payment-command", "payment-query" -> {
                PaymentCommand command;
                if (operation.equals("payment-command")) { paymentWrites++; command = json.read(data.path("command").toString(), PaymentCommand.class); paymentCommands.put(command.id(), command); }
                else command = paymentCommands.get(UUID.fromString(data.path("authorizationId").asText()));
                if (paymentMode.equals("INVALID")) yield Map.of("invalidFixture", true);
                if (paymentMode.equals("NOT_FOUND")) yield new PaymentObservation(command.id(), command.digest(), PaymentObservation.Status.NOT_FOUND, 0L, Instant.now(), null, null, null, null, null, null);
                if (paymentMode.equals("FAILED")) yield new PaymentObservation(command.id(), command.digest(), PaymentObservation.Status.FAILED, 1L, Instant.now(), "synthetic-failed-payment", null, null, null, null, PaymentObservation.Failure.PAYMENT_REJECTED);
                yield new PaymentObservation(command.id(), command.digest(), paymentMode.equals("REVERSED") ? PaymentObservation.Status.REVERSED : PaymentObservation.Status.SUCCEEDED,
                        paymentMode.equals("REVERSED") ? 2L : 1L, Instant.now(), "synthetic-payment", command.amount(), command.payee().accountDigest(), command.authorization().authorizedAt(), paymentMode.equals("REVERSED") ? "synthetic-return-receipt" : "synthetic-bank-receipt", null);
            }
            case "exchange-rate" -> new ExpenseExchangeRate("CNY", "CNY", BigDecimal.ONE, "synthetic", LocalDate.parse(data.path("rateDate").asText()));
            case "invoice-verification" -> verificationUnavailable ? Map.of("unavailableFixture", true) : new Invoice.VerifiedFacts(new InvoiceKey(InvoiceKey.Type.DIGITAL, null, invoiceNumber), entity, money("100"), money("6"), LocalDate.now(), data.path("originalDigest").asText(), "synthetic-verification", Instant.now().minusSeconds(1), Instant.now().plusSeconds(600));
            case "expense-policy" -> new ExpensePolicyPort.Assessment(new ExpensePolicySnapshot(UUID.randomUUID(), 1, money("100"), money("100"), ExpensePolicySnapshot.Decision.WITHIN_LIMIT, "synthetic-tax", "synthetic-policy"), money("6"), false, Instant.now().plusSeconds(600));
            case "budget-precheck" -> new BudgetPrecheckPort.Assessment(json.read(data.toString(), BudgetPrecheckPort.Request.class), "synthetic-precheck", Instant.now().minusSeconds(1), Instant.now().plusSeconds(600));
            case "accounting-period" -> {
                var request = json.read(data.toString(), AccountingPeriodPort.Request.class); var date = request.accountingDate(); var at = Instant.now();
                yield new AccountingPeriodPort.OpenPeriod(request, "synthetic-period", "v1", date.minusDays(30), date.plusDays(30), at, at.plusSeconds(300));
            }
            case "account-mapping" -> {
                var request = json.read(data.toString(), AccountMappingPort.Request.class); var at = Instant.now();
                yield new AccountMappingPort.Mapping(request, "v1", at, at.plusSeconds(300), request.keys().stream().map(key -> new AccountMappingPort.Entry(key, "synthetic-" + key.role())).toList());
            }
            case "voucher-command", "voucher-query" -> {
                VoucherCommand command;
                if (operation.equals("voucher-command")) { command = json.read(data.path("command").toString(), VoucherCommand.class); voucherCommands.put(command.id(), command); }
                else command = voucherCommands.get(UUID.fromString(data.path("operationId").asText()));
                if (voucherMode.equals("INVALID")) yield Map.of("invalidFixture", true);
                if (voucherMode.equals("NOT_FOUND")) yield new VoucherObservation(command.id(), command.digest(), VoucherObservation.Status.NOT_FOUND, 0L, Instant.now(), null, null, null, null, null, null, null, null);
                yield new VoucherObservation(command.id(), command.digest(), voucherMode.equals("REVERSED") ? VoucherObservation.Status.REVERSED : VoucherObservation.Status.POSTED, voucherMode.equals("REVERSED") ? 2L : 1L, Instant.now(), "synthetic-posting", "synthetic-voucher", command.period().periodReference(), command.accountingDate(), command.totals().gross(), command.totals().gross(), command.createdAt(), null);
            }
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
