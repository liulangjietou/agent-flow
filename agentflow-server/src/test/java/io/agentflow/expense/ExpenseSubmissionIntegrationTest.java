package io.agentflow.expense;

import io.agentflow.approval.model.BusinessReference;

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
        "agentflow.vouchers.preparation-worker-enabled=false", "agentflow.vouchers.worker-enabled=false", "agentflow.vouchers.reversal-worker-enabled=false", "agentflow.vouchers.reversal-execution-worker-enabled=false",
        "agentflow.payments.worker-enabled=false", "agentflow.payments.request-worker-enabled=false", "agentflow.payments.payee-review-worker-enabled=false",
        "agentflow.invoices.verification-worker-enabled=false", "agentflow.expenses.precheck-worker-enabled=false", "agentflow.expenses.payment-return-worker-enabled=false",
        "agentflow.budgets.worker-enabled=false", "agentflow.expenses.settlement-worker-enabled=false", "agentflow.expenses.archive-worker-enabled=false", "agentflow.expenses.resource-adjustment-worker-enabled=false"})
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
    private int reversalWrites;
    private int reversalQueries;
    private boolean invalidReversalResponse;
    private final Map<UUID, VoucherReversalCommand> reversalCommands = new ConcurrentHashMap<>();
    private final Map<UUID, VoucherReversalPort.Posting> reversePostings = new ConcurrentHashMap<>();
    private final Set<UUID> reversedOriginals = ConcurrentHashMap.newKeySet();
    private int accountReads;
    private int payeeReads;
    private volatile EmployeeAccountSnapshot currentPayee;
    private volatile boolean invalidPayee;
    private UUID cashierAppointment;
    private ExpensePaymentReturnPort.Status expenseReturnStatus = ExpensePaymentReturnPort.Status.CONFIRMED;
    private long expenseReturnRevision = 1;
    private List<ExpensePaymentReturnPort.ReturnItem> expenseReturnRows = List.of();
    private int budgetReversalWrites;
    private int budgetReversalQueries;
    private boolean invalidBudgetReversalResponse;
    private BudgetConsumptionReversalObservation.Status budgetReversalStatus = BudgetConsumptionReversalObservation.Status.APPLIED;
    private final Map<UUID, BudgetConsumptionReversalCommand> budgetReversalCommands = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> budgetReversalAppliedAt = new ConcurrentHashMap<>();

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
    @Autowired VoucherOperationService voucherExecution;
    @Autowired VoucherReversalService voucherReversals;
    @Autowired VoucherReversalWorker voucherReversalWorker;
    @Autowired JdbcVoucherReversalCheckRepository voucherReversalChecks;
    @Autowired JdbcVoucherReversalRecordRepository voucherReversalRecords;
    @Autowired VoucherReversalPreparationService reversalPreparing;
    @Autowired JdbcVoucherReversalPreparationRepository reversalPreparations;
    @Autowired JdbcVoucherReversalOperationRepository reversalOperations;
    @Autowired VoucherReversalExecutionService reversalExecution;
    @Autowired VoucherReversalExecutionWorker reversalExecutionWorker;
    @Autowired VoucherReversalRetirementService reversalRetirement;
    @Autowired AccountingReversalPort reversalPort;
    @Autowired VoucherDisputeService voucherDisputes;
    @Autowired JdbcVoucherDisputeResolutionRepository voucherDecisions;
    @Autowired JdbcPaymentAuthorizationRepository paymentAuthorizations;
    @Autowired JdbcPaymentExecutionRequestRepository paymentRequests;
    @Autowired JdbcPaymentOperationRepository paymentOperations;
    @Autowired PaymentExecutionRequestWorker paymentRequestWorker;
    @Autowired PaymentPayeeReviewWorker payeeReviewWorker;
    @Autowired PaymentPayeeReviewService payeeReviewService;
    @Autowired JdbcPaymentPayeeReviewRepository payeeReviews;
    @Autowired PaymentDisputeService disputes;
    @Autowired JdbcPaymentDisputeResolutionRepository disputeDecisions;
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
    private InvoiceOriginal.Format invoiceOriginalFormat = InvoiceOriginal.Format.PDF;
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
    @Autowired ExpensePaymentReturnService expenseReturns;
    @Autowired ExpensePaymentReturnSources expenseReturnSources;
    @Autowired ExpensePaymentReturnWorker expenseReturnWorker;
    @Autowired JdbcExpensePaymentReturnCheckRepository expenseReturnChecks;
    @Autowired JdbcExpensePaymentReturnsRepository expenseReturnLedgers;
    @Autowired JdbcExpensePaymentReturnRepository expenseReturnRegistrations;
    @Autowired ExpenseSettlementSources settlementSources;
    @Autowired ExpenseResourceAdjustmentSources resourceAdjustmentSources;
    @Autowired ExpensePartialAdjustmentSources partialAdjustmentSources;
    @Autowired JdbcExpensePartialAdjustmentRepository partialAdjustments;
    @Autowired ExpensePartialAdjustmentExecution partialAdjustmentExecution;
    @Autowired JdbcExpenseResourceAdjustmentPreparationRepository resourcePreparations;
    @Autowired JdbcExpenseResourceAdjustmentRepository resourceAdjustments;
    @Autowired JdbcBudgetConsumptionReversalRepository budgetReversals;
    @Autowired AccountingPeriodPort accountingPeriods;
    @Autowired ExpenseResourceAdjustmentExecution resourceAdjustmentExecution;
    @Autowired ExpensePrecheckResources financialResources;
    @Autowired ExpenseResourceChanges resourceChanges;
    @Autowired ExpenseResourceAdjustmentPreparationService resourcePreparing;
    @Autowired ExpenseResourceAdjustmentActionService resourceActions;
    @Autowired ExpenseResourceAdjustmentBudgetExecution resourceBudgetExecution;
    @Autowired ExpenseResourceAdjustmentWorker resourceWorker;

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
            for (var partial : jdbc.queryForList("SELECT id FROM expense_partial_adjustment WHERE tenant_id='demo' AND report_id=? ORDER BY sequence_no DESC,created_at DESC,id DESC", String.class, report.toString())) {
                jdbc.update("DELETE FROM finance_consumption_reduction WHERE tenant_id='demo' AND adjustment_id=?", partial);
                jdbc.update("DELETE FROM expense_partial_adjustment_return WHERE tenant_id='demo' AND adjustment_id=?", partial);
                jdbc.update("DELETE FROM expense_partial_adjustment_completion WHERE tenant_id='demo' AND adjustment_id=?", partial);
                jdbc.update("DELETE FROM expense_partial_adjustment_operation WHERE tenant_id='demo' AND adjustment_id=?", partial);
                jdbc.update("DELETE FROM expense_partial_adjustment_revision WHERE tenant_id='demo' AND adjustment_id=?", partial);
                jdbc.update("DELETE FROM expense_partial_adjustment WHERE tenant_id='demo' AND id=?", partial);
            }
            String adjustments = "SELECT id FROM expense_resource_adjustment WHERE tenant_id='demo' AND report_id=?";
            jdbc.update("DELETE FROM finance_consumption_reversal WHERE tenant_id='demo' AND report_id=?", report.toString());
            jdbc.update("DELETE FROM expense_resource_adjustment_retirement WHERE tenant_id='demo' AND adjustment_id IN (" + adjustments + ")", report.toString());
            jdbc.update("UPDATE expense_resource_adjustment SET budget_reversal_version=NULL,resources_reversed=FALSE,status='WAITING_BUDGET',active_report_id=report_id,issue=NULL WHERE tenant_id='demo' AND report_id=? AND budget_reversal_version IS NOT NULL", report.toString());
            jdbc.update("DELETE FROM budget_consumption_reversal_operation_revision WHERE tenant_id='demo' AND operation_id IN (" + adjustments + ")", report.toString());
            jdbc.update("DELETE FROM budget_consumption_reversal_operation WHERE tenant_id='demo' AND id IN (" + adjustments + ")", report.toString());
            jdbc.update("DELETE FROM expense_resource_adjustment_revision WHERE tenant_id='demo' AND adjustment_id IN (" + adjustments + ")", report.toString());
            jdbc.update("DELETE FROM expense_resource_adjustment WHERE tenant_id='demo' AND report_id=?", report.toString());
            jdbc.update("DELETE FROM expense_resource_adjustment_preparation_revision WHERE tenant_id='demo' AND preparation_id IN (SELECT id FROM expense_resource_adjustment_preparation WHERE tenant_id='demo' AND report_id=?)", report.toString());
            jdbc.update("DELETE FROM expense_resource_adjustment_preparation WHERE tenant_id='demo' AND report_id=?", report.toString());
            jdbc.update("DELETE FROM finance_receipt_credit WHERE tenant_id='demo' AND business_id=?", report.toString());
            jdbc.update("DELETE FROM expense_payment_return_registration WHERE tenant_id='demo' AND report_id=?", report.toString());
            jdbc.update("DELETE FROM expense_payment_return_check_revision WHERE tenant_id='demo' AND check_id IN (SELECT id FROM expense_payment_return_check WHERE tenant_id='demo' AND report_id=?)", report.toString());
            jdbc.update("DELETE FROM expense_payment_return_check WHERE tenant_id='demo' AND report_id=?", report.toString());
            jdbc.update("DELETE FROM expense_payment_returns_revision WHERE tenant_id='demo' AND report_id=?", report.toString());
            jdbc.update("DELETE FROM expense_payment_returns WHERE tenant_id='demo' AND report_id=?", report.toString());
            jdbc.update("DELETE FROM expense_archive_original WHERE tenant_id='demo' AND report_id=?", report.toString());
            jdbc.update("DELETE FROM expense_archive WHERE tenant_id='demo' AND report_id=?", report.toString());
            jdbc.update("DELETE FROM expense_settlement_revision WHERE tenant_id='demo' AND report_id=?", report.toString());
            jdbc.update("DELETE FROM expense_settlement WHERE tenant_id='demo' AND report_id=?", report.toString());
            jdbc.update("DELETE FROM voucher_preparation_revision WHERE tenant_id='demo' AND preparation_id IN (SELECT id FROM voucher_preparation WHERE tenant_id='demo' AND business_id=?)", report.toString());
            jdbc.update("DELETE FROM voucher_preparation WHERE tenant_id='demo' AND business_id=?", report.toString());
            String authorizations = "SELECT id FROM payment_authorization WHERE tenant_id='demo' AND business_id=?";
            jdbc.update("DELETE FROM payment_payee_review_revision WHERE tenant_id='demo' AND review_id IN (SELECT id FROM payment_payee_review WHERE tenant_id='demo' AND original_authorization_id IN (" + authorizations + "))", report.toString());
            jdbc.update("DELETE FROM payment_payee_review WHERE tenant_id='demo' AND original_authorization_id IN (" + authorizations + ")", report.toString());
            jdbc.update("DELETE FROM payment_dispute_resolution WHERE tenant_id='demo' AND payment_id IN (" + authorizations + ")", report.toString());
            jdbc.update("DELETE FROM payment_retirement WHERE tenant_id='demo' AND authorization_id IN (" + authorizations + ")", report.toString());
            jdbc.update("DELETE FROM payment_execution_request_revision WHERE tenant_id='demo' AND request_id IN (SELECT id FROM payment_execution_request WHERE tenant_id='demo' AND authorization_id IN (" + authorizations + "))", report.toString());
            jdbc.update("DELETE FROM payment_execution_request WHERE tenant_id='demo' AND authorization_id IN (" + authorizations + ")", report.toString());
            jdbc.update("DELETE FROM payment_operation_revision WHERE tenant_id='demo' AND operation_id IN (" + authorizations + ")", report.toString());
            jdbc.update("DELETE FROM payment_operation WHERE tenant_id='demo' AND id IN (" + authorizations + ")", report.toString());
            jdbc.update("DELETE FROM payment_authorization_revision WHERE tenant_id='demo' AND authorization_id IN (" + authorizations + ")", report.toString());
            jdbc.update("DELETE FROM payment_authorization WHERE tenant_id='demo' AND business_id=?", report.toString());
            String voucherIds = "SELECT id FROM voucher_operation WHERE tenant_id='demo' AND business_id=?";
            jdbc.update("UPDATE voucher_operation SET reversal_id=NULL WHERE tenant_id='demo' AND business_id=?", report.toString());
            jdbc.update("DELETE FROM voucher_reversal_retirement WHERE tenant_id='demo' AND operation_id IN (" + voucherIds + ")", report.toString());
            jdbc.update("DELETE FROM voucher_reversal_operation_revision WHERE tenant_id='demo' AND reversal_id IN (SELECT id FROM voucher_reversal_operation WHERE tenant_id='demo' AND operation_id IN (" + voucherIds + "))", report.toString());
            jdbc.update("DELETE FROM voucher_reversal_operation WHERE tenant_id='demo' AND operation_id IN (" + voucherIds + ")", report.toString());
            jdbc.update("DELETE FROM voucher_reversal_preparation_revision WHERE tenant_id='demo' AND preparation_id IN (SELECT id FROM voucher_reversal_preparation WHERE tenant_id='demo' AND operation_id IN (" + voucherIds + "))", report.toString());
            jdbc.update("DELETE FROM voucher_reversal_preparation WHERE tenant_id='demo' AND operation_id IN (" + voucherIds + ")", report.toString());
            jdbc.update("DELETE FROM voucher_reversal_record WHERE tenant_id='demo' AND operation_id IN (" + voucherIds + ")", report.toString());
            jdbc.update("DELETE FROM voucher_reversal_check_revision WHERE tenant_id='demo' AND check_id IN (SELECT id FROM voucher_reversal_check WHERE tenant_id='demo' AND operation_id IN (" + voucherIds + "))", report.toString());
            jdbc.update("DELETE FROM voucher_reversal_check WHERE tenant_id='demo' AND operation_id IN (" + voucherIds + ")", report.toString());
            jdbc.update("DELETE FROM voucher_dispute_resolution WHERE tenant_id='demo' AND operation_id IN (SELECT id FROM voucher_operation WHERE tenant_id='demo' AND business_id=?)", report.toString());
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

    @Test void financeRegistersPayeeReviewAfterOldAuthorizationEndsWithoutReadingAccountsInRequest() throws Exception {
        var report = paymentReport(); UUID id = authorizePayment(report);
        ok(send("/api/v1/payments/" + id + "/finance-actions", "finance", Map.of("action", "VOID", "authorizationVersion", 1, "comment", "本人已维护账户，先结束旧授权")), 202);
        int before = payeeReads;
        var receipt = ok(send("/api/v1/payments/" + id + "/payee-reviews", "finance", Map.of("authorizationVersion", 2,
                "voucherVersion", authorizationInput(report).get("voucherVersion"), "comment", "重新核对当前本人账户")), 202);
        assertThat(receipt.path("authorizationId").asText()).isEqualTo(id.toString());
        assertThat(receipt.path("reviewId").asText()).isNotBlank();
        assertThat(payeeReads).isEqualTo(before); assertThat(paymentWrites).isZero();
    }

    @Test void reviewedAccountNeedsExplicitNewFinanceAuthorizationAndIndependentCashierBeforeSettlementAndArchive() throws Exception {
        var report = paymentReport(); UUID originalId = authorizePayment(report);
        var original = paymentAuthorizations.find("demo", originalId).orElseThrow(); var approvedRound = current(report).currentRound();
        ok(send("/api/v1/payments/" + originalId + "/finance-actions", "finance", Map.of("action", "VOID", "authorizationVersion", 1, "comment", "账户更新，先作废原授权")), 202);
        currentPayee = new EmployeeAccountSnapshot(entity, "alice", "synthetic-updated-private-account", "****9876", "b".repeat(64), "v2");
        var reviewId = registerPayeeReview(report, originalId); var queued = payeeReviews.find("demo", reviewId).orElseThrow();
        assertCode(send(paymentPath(report) + "/authorizations", "finance", reviewedAuthorizationInput(report, queued)), "PAYMENT_PAYEE_REVIEW_UNAVAILABLE");
        int before = payeeReads; payeeReviewWorker.poll(); var ready = payeeReviews.find("demo", reviewId).orElseThrow();
        assertThat(ready.status()).isEqualTo(PaymentPayeeReview.Status.READY); assertThat(payeeReads).isEqualTo(before + 1);
        assertThat(paymentAuthorizations.active("demo", BusinessReference.Type.EXPENSE, report.id())).isEmpty(); assertThat(paymentWrites).isZero();
        var view = ok(read(paymentPath(report), "finance"), 200);
        assertThat(view.at("/payeeReview/maskedAccount").asText()).isEqualTo("****9876"); assertThat(view.at("/actions/authorizeReviewed").asBoolean()).isTrue();
        assertThat(view.toString()).doesNotContain("synthetic-updated-private-account", "accountDigest", "targetDigest", "sourceVersion");
        assertThat(ok(read(paymentPath(report), "alice"), 200).path("payeeReview").isNull()).isTrue();
        var input = reviewedAuthorizationInput(report, ready); String key = UUID.randomUUID().toString();
        var response = send(paymentPath(report) + "/authorizations", "finance", key, input);
        UUID replacement = UUID.fromString(ok(response, 202).path("authorizationId").asText());
        assertThat(send(paymentPath(report) + "/authorizations", "finance", key, input).getContentAsString()).isEqualTo(response.getContentAsString());
        assertThat(payeeReviews.find("demo", reviewId).orElseThrow().consumedAuthorizationId()).isEqualTo(replacement);
        assertThat(paymentAuthorizations.find("demo", originalId).orElseThrow().terms()).isEqualTo(original.terms());
        assertThat(current(report).currentRound()).isEqualTo(approvedRound);
        assertThat(paymentAuthorizations.find("demo", replacement).orElseThrow().terms().payee()).isEqualTo(currentPayee);
        assertThat(paymentOperations.find("demo", replacement)).isEmpty(); assertThat(paymentWrites).isZero();
        ok(send("/api/v1/cashier/payments/" + replacement + "/actions", "cashier", cashierInput("EXECUTE", 1, null)), 202);
        paymentRequestWorker.poll(); paymentWorker.poll();
        assertThat(paymentOperations.find("demo", replacement).orElseThrow().status()).isEqualTo(PaymentOperation.Status.SUCCEEDED);
        assertThat(paymentWrites).isEqualTo(1); assertThat(paymentCommands.get(replacement).payee()).isEqualTo(currentPayee);
        settlementWorker.poll(); budgetWorker.poll(); voucherPreparationWorker.poll(); voucherWorker.poll(); pollArchive();
        assertThat(archives.find("demo", report.id(), 1).orElseThrow().archive()).isNotNull();
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

    @Test void payeeReviewRequiresEndedSourceStrictInputAndCurrentFinanceEvenForReplay() throws Exception {
        var report = paymentReport(); UUID id = authorizePayment(report); String path = "/api/v1/payments/" + id + "/payee-reviews";
        var input = new HashMap<String, Object>(Map.of("authorizationVersion", 1, "voucherVersion", authorizationInput(report).get("voucherVersion"), "comment", "核对本人账户"));
        assertCode(send(path, "finance", input), "PAYMENT_SOURCE_CHANGED");
        ok(send("/api/v1/payments/" + id + "/finance-actions", "finance", Map.of("action", "VOID", "authorizationVersion", 1, "comment", "停止原授权")), 202); input.put("authorizationVersion", 2);
        for (String user : List.of("alice", "manager", "admin", "cashier")) assertThat(send(path, user, input).getStatus()).isIn(403, 404);
        for (String field : List.of("accountReference", "accountDigest", "amount", "actor", "tenantId")) {
            var forged = new HashMap<>(input); forged.put(field, "untrusted"); assertThat(send(path, "finance", forged).getStatus()).isEqualTo(400);
        }
        String key = UUID.randomUUID().toString(); var response = send(path, "finance", key, input); ok(response, 202);
        assertThat(send(path, "finance", key, input).getContentAsString()).isEqualTo(response.getContentAsString());
        assertCode(send(path, "finance", input), "PAYMENT_PAYEE_REVIEW_PENDING");
        assertThatThrownBy(() -> new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> { payeeReviewWorker.poll(); return null; }))
                .isInstanceOf(IllegalStateException.class);
        jdbc.update("UPDATE organization_appointment SET active=false WHERE tenant_id='demo' AND person_id=?", finance.toString());
        assertThat(send(path, "finance", key, input).getStatus()).isIn(403, 404); int before = payeeReads; payeeReviewWorker.poll();
        var review = payeeReviews.latest("demo", id, "finance").orElseThrow(); assertThat(review.status()).isEqualTo(PaymentPayeeReview.Status.VOIDED);
        assertThat(payeeReads).isEqualTo(before); assertThat(paymentWrites).isZero();
    }

    @Test void onlyLatestReadyPayeeReviewCanBeConsumedAndConsumptionFailureRollsBackNewAuthorization() throws Exception {
        var report = paymentReport(); UUID original = voidedPayment(report); UUID first = registerPayeeReview(report, original); payeeReviewWorker.poll();
        var previous = payeeReviews.find("demo", first).orElseThrow(); UUID second = registerPayeeReview(report, original);
        assertCode(send(paymentPath(report) + "/authorizations", "finance", reviewedAuthorizationInput(report, previous)), "PAYMENT_PAYEE_REVIEW_UNAVAILABLE");
        payeeReviewWorker.poll(); var ready = payeeReviews.find("demo", second).orElseThrow(); var input = reviewedAuthorizationInput(report, ready);
        var missingVersion = new HashMap<>(input); missingVersion.remove("payeeReviewVersion"); assertThat(send(paymentPath(report) + "/authorizations", "finance", missingVersion).getStatus()).isEqualTo(400);
        jdbc.execute("ALTER TABLE payment_payee_review ADD CONSTRAINT reject_consumed_review_fixture CHECK (id <> '" + second + "' OR status <> 'CONSUMED')");
        try {
            assertThatThrownBy(() -> send(paymentPath(report) + "/authorizations", "finance", input)).hasCauseInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(payeeReviews.find("demo", second)).contains(ready);
            assertThat(paymentAuthorizations.active("demo", BusinessReference.Type.EXPENSE, report.id())).isEmpty();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_authorization WHERE tenant_id='demo' AND business_id=?", Integer.class, report.id().toString())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='PAYMENT_AUTHORIZE'", Integer.class, report.applicationId().toString())).isEqualTo(1);
        } finally { jdbc.execute("ALTER TABLE payment_payee_review DROP CONSTRAINT reject_consumed_review_fixture"); }
        UUID replacement = UUID.fromString(ok(send(paymentPath(report) + "/authorizations", "finance", input), 202).path("authorizationId").asText());
        ok(send("/api/v1/payments/" + replacement + "/finance-actions", "finance", Map.of("action", "VOID", "authorizationVersion", 1, "comment", "结束新授权再核对旧证据")), 202);
        assertCode(send(paymentPath(report) + "/authorizations", "finance", input), "PAYMENT_PAYEE_REVIEW_UNAVAILABLE");
        assertCode(send("/api/v1/payments/" + original + "/payee-reviews", "finance", Map.of("authorizationVersion", 2,
                "voucherVersion", authorizationInput(report).get("voucherVersion"), "comment", "更早授权不能再次复核")), "PAYMENT_SOURCE_CHANGED");
    }

    @Test void payeeReadFailureAndChangedSourceDoNotCreateAuthorizationAndLateLeaseCannotOverwriteNewRead() throws Exception {
        var report = paymentReport(); UUID original = voidedPayment(report); invalidPayee = true;
        UUID failedId = registerPayeeReview(report, original); payeeReviewWorker.poll();
        var failed = payeeReviews.find("demo", failedId).orElseThrow(); assertThat(failed.status()).isEqualTo(PaymentPayeeReview.Status.UNAVAILABLE);
        assertThat(failed.account()).isNull(); invalidPayee = false;
        UUID id = registerPayeeReview(report, original); var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var claimed = payeeReviewService.claim("demo", id, now); assertThat(claimed).isNotNull();
        var result = new FinanceResult.Success<>(new EmployeeAccountPort.Account(current(report).currentRound().account(), now.plusSeconds(600)));
        payeeReviewService.finish(claimed, result, claimed.leaseUntil()); assertThat(payeeReviews.find("demo", id).orElseThrow().status()).isEqualTo(PaymentPayeeReview.Status.QUEUED);
        var next = payeeReviewService.claim("demo", id, claimed.leaseUntil()); assertThat(next.attempts()).isEqualTo(2);
        payeeReviewService.finish(claimed, result, next.updatedAt()); assertThat(payeeReviews.find("demo", id)).contains(next);
        jdbc.update("UPDATE approval_application SET status='REVOKED' WHERE tenant_id='demo' AND id=?", report.applicationId().toString());
        payeeReviewService.finish(next, result, next.updatedAt().plusMillis(1));
        assertThat(payeeReviews.find("demo", id).orElseThrow().status()).isEqualTo(PaymentPayeeReview.Status.VOIDED);
        assertThat(paymentAuthorizations.active("demo", BusinessReference.Type.EXPENSE, report.id())).isEmpty(); assertThat(paymentWrites).isZero();
    }

    @Test void repeatedAccountChangesRetainAuthorizationChainAndLaterMasterChangeStopsCashierExecution() throws Exception {
        var report = paymentReport(); UUID original = voidedPayment(report);
        for (int index = 2; index <= 3; index++) {
            currentPayee = new EmployeeAccountSnapshot(entity, "alice", "synthetic-private-account-v" + index, "****9876", "b".repeat(64), "v" + index);
            UUID reviewId = registerPayeeReview(report, original); payeeReviewWorker.poll();
            UUID next = UUID.fromString(ok(send(paymentPath(report) + "/authorizations", "finance", reviewedAuthorizationInput(report, payeeReviews.find("demo", reviewId).orElseThrow())), 202).path("authorizationId").asText());
            if (index == 2) ok(send("/api/v1/payments/" + next + "/finance-actions", "finance", Map.of("action", "VOID", "authorizationVersion", 1, "comment", "再次更新本人账户")), 202);
            original = next;
        }
        var source = paymentAuthorizations.find("demo", original).orElseThrow();
        ok(send("/api/v1/cashier/payments/" + original + "/actions", "cashier", cashierInput("EXECUTE", 1, null)), 202);
        currentPayee = new EmployeeAccountSnapshot(entity, "alice", "synthetic-private-account-v4", "****5432", "c".repeat(64), "v4");
        paymentRequestWorker.poll();
        assertThat(paymentRequests.forAuthorization("demo", original).orElseThrow().status()).isEqualTo(PaymentExecutionRequest.Status.BLOCKED);
        assertThat(paymentAuthorizations.find("demo", original)).contains(source); assertThat(paymentOperations.find("demo", original)).isEmpty(); assertThat(paymentWrites).isZero();
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

    @Test void xmlInvoiceIsConsumedAndArchivedWithoutReencodingItsOriginal() throws Exception {
        invoiceOriginalFormat = InvoiceOriginal.Format.XML;
        var report = archiveReadyExpense(); pollArchive();
        var entry = archives.find("demo", report.id(), 1).orElseThrow();
        assertThat(entry.archive()).isNotNull(); var original = entry.archive().manifest().originals().get(0);
        byte[] expected = syntheticXmlOriginal();
        var contents = unzipArchive(archiveDownload(report, "alice"));
        assertThat(contents.get(original.entryName())).isEqualTo(expected);
        assertThat(original.file().sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected)));
        assertThat(original.file().filename()).endsWith(".xml");
        UUID invoice = current(report).currentRound().originalLines().get(0).original().invoiceIds().get(0);
        assertThat(invoices.find("demo", invoice).orElseThrow().occupation()).isEqualTo(Invoice.Occupation.CONSUMED);
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
        var report = paidExpense(); settlementWorker.poll(); budgetWorker.poll(); voucherPreparationWorker.poll(); voucherWorker.poll();
        assertThat(voucherOperations.forRound("demo", report.applicationId(), 1, VoucherCommand.Kind.PAYMENT))
                .as(() -> "Payment voucher preparation: " + voucherPreparations.latest("demo", report.applicationId(), 1, VoucherCommand.Kind.PAYMENT)
                        .map(value -> value.status() + ":" + value.result()).orElse("missing"))
                .hasValueSatisfying(value -> assertThat(value.usablePosted()).isTrue());
        return report;
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

    @Test void expensePaymentReturnCumulativelyRegistersBankFundsAndPreservesConsumedResourcesAndArchive() throws Exception {
        var report = archiveReadyExpense(); pollArchive(); var archive = archives.find("demo", report.id(), 1).orElseThrow();
        var bytes = archiveDownload(report, "finance"); var resources = resourceVersions(report); var original = settlements.find("demo", report.id()).orElseThrow();
        var payment = paymentOperations.find("demo", original.input().payment().operationId()).orElseThrow(); var command = payment.input();
        var first = expenseReturnItem(report, "partial-one", "20"); expenseReturnRows = List.of(first); expenseReturnStatus = ExpensePaymentReturnPort.Status.PARTIALLY_RETURNED;
        var checked = queryExpenseReturn(report);
        assertThat(checked.status()).isEqualTo(ExpensePaymentReturnCheck.Status.CHECKED);
        assertThat(expenseReturnLedgers.find("demo", report.id()).orElseThrow().entries()).isEmpty();
        assertThat(settlements.find("demo", report.id()).orElseThrow().status()).isEqualTo(ExpenseSettlement.Status.REVIEW_REQUIRED);
        var firstAction = registerExpenseReturn(report, checked);
        var recorded = expenseReturnLedgers.find("demo", report.id()).orElseThrow(); assertThat(recorded.totalReturned()).isEqualTo(money("20"));
        assertThat(recorded.entries().get(0).registrationId()).isEqualTo(firstAction.registrationId());
        expenseReturnRevision++; expenseReturnRows = List.of(expenseReturnItem(report, "partial-two", "10"), first);
        registerExpenseReturn(report, queryExpenseReturn(report));
        assertThat(expenseReturnLedgers.find("demo", report.id()).orElseThrow().totalReturned()).isEqualTo(money("30"));
        paymentMode = "REVERSED"; tx().executeWithoutResult(status -> paymentExecution.query("demo", payment.input().command().id(), payment.version(), Instant.now())); paymentWorker.poll();
        assertThat(paymentOperations.find("demo", payment.input().command().id()).orElseThrow().status()).isEqualTo(PaymentOperation.Status.REVERSED);
        expenseReturnRevision++; expenseReturnStatus = ExpensePaymentReturnPort.Status.RETURNED;
        expenseReturnRows = List.of(first, expenseReturnRows.get(0), expenseReturnItem(report, "full-final", "20"));
        registerExpenseReturn(report, queryExpenseReturn(report));
        recorded = expenseReturnLedgers.find("demo", report.id()).orElseThrow(); assertThat(recorded.totalReturned()).isEqualTo(money("50")); assertThat(recorded.entries()).hasSize(3);
        assertThat(recorded.reviewRequired()).isTrue(); assertThat(expenseReturnRegistrations.history("demo", report.id())).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finance_receipt_credit WHERE tenant_id='demo' AND business_id=?", Integer.class, report.id().toString())).isEqualTo(3);
        assertThat(settlements.find("demo", report.id()).orElseThrow().input()).isEqualTo(original.input());
        assertThat(settlements.find("demo", report.id()).orElseThrow().resourcesConsumed()).isTrue(); assertThat(resourceVersions(report)).isEqualTo(resources);
        assertThat(paymentOperations.find("demo", payment.input().command().id()).orElseThrow().input()).isEqualTo(command);
        assertThat(archives.find("demo", report.id(), 1)).contains(archive); assertThat(archiveDownload(report, "finance")).isEqualTo(bytes); assertThat(paymentWrites).isEqualTo(1);
    }

    @Test void expensePaymentReturnUnknownEvidenceNeedsExplicitFreshConfirmationBeforeSettlementResumes() throws Exception {
        var report = archiveReadyExpense(); var original = settlements.find("demo", report.id()).orElseThrow(); var resources = resourceVersions(report);
        expenseReturnStatus = ExpensePaymentReturnPort.Status.UNRESOLVED; var unresolved = queryExpenseReturn(report);
        assertThat(asFinance(() -> expenseReturns.registrationIssue(expenseReturnSources.find("demo", report.id()), expenseReturnLedgers.find("demo", report.id()).orElseThrow(), unresolved, Instant.now())))
                .isEqualTo("EXPENSE_PAYMENT_RETURN_EVIDENCE_UNAVAILABLE");
        assertThatThrownBy(() -> settlementSources.requireCurrent(settlements.find("demo", report.id()).orElseThrow(), current(report)))
                .isInstanceOfSatisfying(io.agentflow.common.DomainException.class, failure -> assertThat(failure.code()).isEqualTo("EXPENSE_PAYMENT_RETURN_REVIEW_REQUIRED"));
        expenseReturnRevision++; expenseReturnStatus = ExpensePaymentReturnPort.Status.CONFIRMED; var confirmed = queryExpenseReturn(report);
        assertThat(settlements.find("demo", report.id()).orElseThrow().status()).isEqualTo(ExpenseSettlement.Status.REVIEW_REQUIRED);
        registerExpenseReturn(report, confirmed);
        var recovered = settlements.find("demo", report.id()).orElseThrow(); assertThat(recovered.status()).isEqualTo(ExpenseSettlement.Status.SETTLED);
        assertThat(recovered.input()).isEqualTo(original.input()); assertThat(recovered.budgetOperationId()).isEqualTo(original.budgetOperationId()); assertThat(resourceVersions(report)).isEqualTo(resources);
        assertThat(expenseReturnLedgers.find("demo", report.id()).orElseThrow().reviewRequired()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finance_receipt_credit WHERE tenant_id='demo' AND business_id=?", Integer.class, report.id().toString())).isZero();
    }

    @Test void expensePaymentReturnHoldSurvivesAnOtherwiseValidOriginalVoucherResolution() throws Exception {
        var report = archiveReadyExpense(); var resources = resourceVersions(report);
        expenseReturnStatus = ExpensePaymentReturnPort.Status.PARTIALLY_RETURNED; expenseReturnRows = List.of(expenseReturnItem(report, "retained", "20"));
        registerExpenseReturn(report, queryExpenseReturn(report));
        var disputed = correctedVoucher(report, VoucherCommand.Kind.EXPENSE_ACCRUAL);
        var input = json.read(json.write(voucherDecisionInput(report, disputed)), VoucherDisputeService.Input.class);
        asFinance(() -> voucherDisputes.resolve(report.applicationId(), disputed.input().command().id(), input));
        assertThat(voucherOperations.find("demo", disputed.input().command().id()).orElseThrow().usablePosted()).isTrue();
        assertThat(settlements.find("demo", report.id()).orElseThrow().status()).isEqualTo(ExpenseSettlement.Status.REVIEW_REQUIRED);
        assertThat(expenseReturnLedgers.find("demo", report.id()).orElseThrow().totalReturned()).isEqualTo(money("20")); assertThat(resourceVersions(report)).isEqualTo(resources);
    }

    @Test void expensePaymentReturnCannotOmitAnyObservedBankReceiptEvenBeforeRegistration() throws Exception {
        var report = archiveReadyExpense(); expenseReturnStatus = ExpensePaymentReturnPort.Status.PARTIALLY_RETURNED;
        expenseReturnRows = List.of(expenseReturnItem(report, "known-one", "20")); queryExpenseReturn(report);
        expenseReturnRevision++; expenseReturnRows = List.of(expenseReturnItem(report, "replacement", "20")); var changed = queryExpenseReturn(report);
        assertThatThrownBy(() -> registerExpenseReturn(report, changed)).isInstanceOfSatisfying(io.agentflow.common.DomainException.class,
                failure -> assertThat(failure.code()).isEqualTo("EXPENSE_PAYMENT_RETURN_EVIDENCE_CHANGED"));
        assertThat(expenseReturnRegistrations.history("demo", report.id())).isEmpty(); assertThat(expenseReturnLedgers.find("demo", report.id()).orElseThrow().entries()).isEmpty();
    }

    @Test void expensePaymentReturnRegistrationRollsBackEveryRowAndConsumesItsCheckOnlyOnce() throws Exception {
        var report = archiveReadyExpense(); expenseReturnStatus = ExpensePaymentReturnPort.Status.PARTIALLY_RETURNED;
        expenseReturnRows = List.of(expenseReturnItem(report, "atomic-one", "10"), expenseReturnItem(report, "atomic-two", "10")); var checked = queryExpenseReturn(report);
        var before = expenseReturnLedgers.find("demo", report.id()).orElseThrow(); var settlement = settlements.find("demo", report.id()).orElseThrow();
        var input = expenseReturnInput(report, checked);
        assertThatThrownBy(() -> asFinance(() -> tx().execute(status -> { expenseReturns.register(report.id(), input); throw new IllegalStateException("Synthetic registration rollback"); }))).isInstanceOf(IllegalStateException.class);
        assertThat(expenseReturnLedgers.find("demo", report.id())).contains(before); assertThat(expenseReturnChecks.find("demo", checked.input().id())).contains(checked);
        assertThat(settlements.find("demo", report.id())).contains(settlement); assertThat(expenseReturnRegistrations.history("demo", report.id())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finance_receipt_credit WHERE tenant_id='demo' AND business_id=?", Integer.class, report.id().toString())).isZero();
        registerExpenseReturn(report, checked);
        assertThatThrownBy(() -> asFinance(() -> expenseReturns.register(report.id(), input))).isInstanceOfSatisfying(io.agentflow.common.DomainException.class, failure -> assertThat(failure.code()).isEqualTo("CONCURRENCY_CONFLICT"));
        assertThat(expenseReturnRegistrations.history("demo", report.id())).hasSize(1);
    }

    @Test void expensePaymentReturnExpiredLeaseAndRevokedFinanceCannotConsumeOrOverwriteEvidence() throws Exception {
        var report = archiveReadyExpense(); var queued = queueExpenseReturn(report); var claimed = expenseReturns.claim("demo", queued.input().id(), Instant.now());
        assertThat(claimed.status()).isEqualTo(ExpensePaymentReturnCheck.Status.RUNNING);
        assertThat(expenseReturns.claim("demo", claimed.input().id(), claimed.leaseUntil())).isNull();
        assertThat(expenseReturnChecks.find("demo", claimed.input().id()).orElseThrow().issue()).isEqualTo(ExpensePaymentReturnCheck.Issue.TIMEOUT);
        expenseReturns.finish(claimed, new FinanceResult.Success<>(expenseReturnReceipt(claimed.input().request())), claimed.leaseUntil().plusSeconds(1));
        assertThat(expenseReturnChecks.find("demo", claimed.input().id()).orElseThrow().issue()).isEqualTo(ExpensePaymentReturnCheck.Issue.TIMEOUT);
        var person = organizationRepository.person("demo", finance).orElseThrow(); var inactive = organization.updatePerson(admin, finance, person.displayName(), false, person.approvalEligible(), person.revision());
        try { assertThatThrownBy(() -> queueExpenseReturn(report)).isInstanceOfSatisfying(io.agentflow.common.DomainException.class, failure -> assertThat(failure.code()).isEqualTo("FORBIDDEN")); }
        finally { organization.updatePerson(admin, finance, person.displayName(), true, person.approvalEligible(), inactive.revision()); }
    }

    @Test void expensePaymentReturnApiRechecksFieldsAndPersonnelBeforeIdempotentReplay() throws Exception {
        hideBusinessDetails = true; var report = archiveReadyExpense(); String path = path(report) + "/payment-return";
        var initial = read(path, "finance"); assertThat(initial.getHeader("Cache-Control")).isEqualTo("no-store");
        var view = ok(initial, 200); assertThat(view.path("returnVersion").asLong()).isZero(); assertThat(view.path("canQuery").asBoolean()).isTrue();
        assertThat(ok(read(path, "alice"), 200).path("canQuery").asBoolean()).isFalse();
        for (String user : List.of("bob", "cashier", "admin", "manager")) assertThat(read(path, user).getStatus()).isIn(403, 404);
        assertThat(read(path + "?tenantId=foreign", "finance").getStatus()).isEqualTo(400);
        assertThat(read(path + "?roundNo=2", "finance").getStatus()).isEqualTo(404);
        var query = new ExpensePaymentReturnService.QueryInput(view.path("settlementVersion").asLong(), 0, "核对银行实收与员工应付");
        var injected = json.read(json.write(query), com.fasterxml.jackson.databind.node.ObjectNode.class).put("amount", "999");
        assertThat(send(path + "/checks", "finance", injected).getStatus()).isEqualTo(400);
        for (String user : List.of("alice", "cashier", "admin")) assertThat(send(path + "/checks", user, query).getStatus()).isIn(403, 404);
        expenseReturnStatus = ExpensePaymentReturnPort.Status.PARTIALLY_RETURNED; expenseReturnRows = List.of(expenseReturnItem(report, "api-bank", "20"));
        String queryKey = UUID.randomUUID().toString(); var queued = ok(send(path + "/checks", "finance", queryKey, query), 202);
        var replay = send(path + "/checks", "finance", queryKey, query); assertThat(replay.getHeader("Idempotency-Replayed")).isEqualTo("true"); assertThat(ok(replay, 202)).isEqualTo(queued);
        expenseReturnWorker.poll(); view = ok(read(path, "finance"), 200); assertThat(view.path("latestCheck").path("canRegister").asBoolean()).isTrue();
        assertThat(ok(read(path, "alice"), 200).path("latestCheck").isNull()).isTrue();
        var check = expenseReturnChecks.find("demo", UUID.fromString(queued.path("checkId").asText())).orElseThrow(); var input = expenseReturnInput(report, check);
        String registerKey = UUID.randomUUID().toString(); var saved = ok(send(path + "/registrations", "finance", registerKey, input), 202);
        assertThat(ok(send(path + "/registrations", "finance", registerKey, input), 202)).isEqualTo(saved);
        var applicant = read(path, "alice"); var accepted = ok(applicant, 200); assertThat(accepted.path("totalReturned").path("value").asText()).isEqualTo("20.00");
        assertThat(accepted.path("netPaid").path("value").asText()).isEqualTo("30.00"); assertThat(accepted.path("registrations")).hasSize(1);
        assertThat(applicant.getContentAsString()).doesNotContain("commandDigest", "targetDigest", "accountDigest", "debitAccountReference", "synthetic-private-account");
        var person = organizationRepository.person("demo", finance).orElseThrow(); var inactive = organization.updatePerson(admin, finance, person.displayName(), false, person.approvalEligible(), person.revision());
        try { assertThat(send(path + "/registrations", "finance", registerKey, input).getStatus()).isIn(403, 404); }
        finally { organization.updatePerson(admin, finance, person.displayName(), true, person.approvalEligible(), inactive.revision()); }
    }

    @Test void expensePaymentReturnBankUniquenessRollsBackAllItemsAcrossDifferentExpenses() throws Exception {
        var first = paidExpense(); expenseReturnStatus = ExpensePaymentReturnPort.Status.PARTIALLY_RETURNED; expenseReturnRows = List.of(expenseReturnItem(first, "colliding", "10"));
        registerExpenseReturn(first, queryExpenseReturn(first));
        var second = paymentReport(false); UUID id = authorizePayment(second);
        ok(send("/api/v1/cashier/payments/" + id + "/actions", "cashier", cashierInput("EXECUTE", 1, null)), 202); paymentRequestWorker.poll(); paymentWorker.poll();
        expenseReturnRevision++; expenseReturnRows = List.of(expenseReturnItem(second, "would-be-new", "10"), expenseReturnItem(second, "colliding", "10"));
        var check = queryExpenseReturn(second); var before = expenseReturnLedgers.find("demo", second.id()).orElseThrow();
        assertThatThrownBy(() -> registerExpenseReturn(second, check)).isInstanceOfSatisfying(io.agentflow.common.DomainException.class,
                failure -> assertThat(failure.code()).isEqualTo("EXPENSE_PAYMENT_RETURN_ALREADY_RECORDED"));
        assertThat(expenseReturnLedgers.find("demo", second.id())).contains(before); assertThat(expenseReturnChecks.find("demo", check.input().id())).contains(check);
        assertThat(expenseReturnRegistrations.history("demo", second.id())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finance_receipt_credit WHERE tenant_id='demo' AND business_id=?", Integer.class, second.id().toString())).isZero();
        assertThat(expenseReturnLedgers.find("demo", first.id()).orElseThrow().totalReturned()).isEqualTo(money("10"));
    }

    @Test void expensePaymentReturnConcurrentFinanceRegistrationsHaveExactlyOneWinner() throws Exception {
        var report = paidExpense(); expenseReturnStatus = ExpensePaymentReturnPort.Status.PARTIALLY_RETURNED; expenseReturnRows = List.of(expenseReturnItem(report, "concurrent", "20"));
        var checked = queryExpenseReturn(report); var input = expenseReturnInput(report, checked); var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var jobs = java.util.stream.IntStream.range(0, 2).mapToObj(index -> pool.submit(() -> {
                try { asFinance(() -> expenseReturns.register(report.id(), input)); return "SAVED"; }
                catch (io.agentflow.common.DomainException failure) { return failure.code(); }
            })).toList();
            assertThat(List.of(jobs.get(0).get(20, java.util.concurrent.TimeUnit.SECONDS), jobs.get(1).get(20, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder("SAVED", "CONCURRENCY_CONFLICT");
        } finally { pool.shutdownNow(); }
        assertThat(expenseReturnRegistrations.history("demo", report.id())).hasSize(1); assertThat(expenseReturnLedgers.find("demo", report.id()).orElseThrow().entries()).hasSize(1);
    }

    @Test void resourceAdjustmentAuthorizationAndBudgetCommandCommitTogetherFromActualOriginalSources() throws Exception {
        var report = resourceAdjustmentReport(); var sources = resourceAdjustmentSources.find("demo", report.id()); var versions = resourceVersions(report);
        var prepared = readyResourceAdjustment(report); var id = prepared.input().id();
        assertThat(resourcePreparations.find("demo", id)).contains(prepared); assertThat(resourcePreparations.find("other", id)).isEmpty();
        assertThat(resourceAdjustments.active("demo", report.id())).isEmpty(); assertThat(budgetReversals.find("demo", id)).isEmpty();
        assertThatThrownBy(() -> tx().execute(status -> { authorizeResourceAdjustment(prepared); throw new IllegalStateException("Synthetic adjustment authorization rollback"); }))
                .hasMessageContaining("Synthetic adjustment authorization rollback");
        assertThat(resourcePreparations.find("demo", id)).contains(prepared); assertThat(resourceAdjustments.find("demo", id)).isEmpty(); assertThat(budgetReversals.find("demo", id)).isEmpty();
        var adjustment = authorizeResourceAdjustment(prepared);
        assertThat(resourceAdjustments.find("demo", id)).contains(adjustment); assertThat(budgetReversals.find("demo", id).orElseThrow().input()).isEqualTo(adjustment.input().budget());
        assertThat(resourcePreparations.revision("demo", id, prepared.version() + 1).orElseThrow().status()).isEqualTo(ExpenseResourceAdjustmentPreparation.Status.AUTHORIZED);
        assertThatThrownBy(() -> authorizeResourceAdjustment(prepared)).isInstanceOf(io.agentflow.common.DomainException.class);
        var another = readyResourceAdjustment(report);
        assertThatThrownBy(() -> authorizeResourceAdjustment(another)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(resourcePreparations.find("demo", another.input().id())).contains(another); assertThat(budgetReversals.find("demo", another.input().id())).isEmpty();
        assertThat(resourceAdjustmentSources.find("demo", report.id())).isEqualTo(sources); assertThat(resourceVersions(report)).isEqualTo(versions);
    }

    @Test void resourceAdjustmentCannotCompleteWithOnlyABudgetReceiptOrAnUnpersistedBudgetRevision() throws Exception {
        var report = resourceAdjustmentReport(); var versions = resourceVersions(report); var adjustment = authorizeResourceAdjustment(readyResourceAdjustment(report));
        var queued = budgetReversals.find("demo", adjustment.id()).orElseThrow(); var at = adjustmentTime();
        var claimed = queued.claim(at, java.time.Duration.ofSeconds(30)); var command = claimed.input().command();
        var receipt = new BudgetConsumptionReversalObservation(command.id(), command.digest(), BudgetConsumptionReversalObservation.Status.APPLIED, at,
                command.consumed().ledgerRevision() + 1, "resource-budget-return", command.period().periodReference(), command.period().request().accountingDate(), at, null);
        var completed = claimed.complete(new FinanceResult.Success<>(receipt), at); var ready = adjustment.budgetApplied(completed, at);
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> resourceAdjustments.update(ready))).isInstanceOf(io.agentflow.common.DomainException.class);
        tx().executeWithoutResult(status -> { budgetReversals.update(claimed); budgetReversals.update(completed); resourceAdjustments.update(ready); });
        assertThat(resourceAdjustments.find("demo", adjustment.id())).contains(ready);
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> resourceAdjustments.update(ready.applied(adjustmentTime())))).isInstanceOf(io.agentflow.common.DomainException.class);
        assertThat(resourceAdjustments.find("demo", adjustment.id())).contains(ready); assertThat(resourceVersions(report)).isEqualTo(versions);
        assertThat(resourceAdjustments.revision("demo", adjustment.id(), 1)).contains(adjustment);
    }

    @Test void resourceAdjustmentRetirementRollsBackAtomicallyAndPreservesOldAuthorizationForANewPreparation() throws Exception {
        var report = resourceAdjustmentReport(); var adjustment = authorizeResourceAdjustment(readyResourceAdjustment(report)); var versions = resourceVersions(report);
        var queued = budgetReversals.find("demo", adjustment.id()).orElseThrow(); var at = adjustmentTime(); var stopped = queued.voidBeforeSend(at);
        var decision = new ExpenseResourceAdjustmentRetirement(adjustment, stopped, "finance", "retirement-proof", "结束未发送调整后重新准备", at);
        assertThatThrownBy(() -> tx().execute(status -> { budgetReversals.update(stopped); resourceAdjustments.retire(decision); throw new IllegalStateException("Synthetic adjustment retirement rollback"); }))
                .hasMessageContaining("Synthetic adjustment retirement rollback");
        assertThat(budgetReversals.find("demo", adjustment.id())).contains(queued); assertThat(resourceAdjustments.active("demo", report.id())).contains(adjustment);
        assertThat(resourceAdjustments.retirement("demo", adjustment.id())).isEmpty();
        tx().executeWithoutResult(status -> { budgetReversals.update(stopped); resourceAdjustments.retire(decision); });
        assertThat(resourceAdjustments.active("demo", report.id())).isEmpty(); assertThat(resourceAdjustments.retirement("demo", adjustment.id())).contains(decision);
        assertThat(resourceAdjustments.find("demo", adjustment.id())).contains(decision.after());
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> budgetReversals.update(stopped.requestQuery(adjustmentTime())))).isInstanceOf(io.agentflow.common.DomainException.class);
        var replacement = authorizeResourceAdjustment(readyResourceAdjustment(report));
        assertThat(resourceAdjustments.history("demo", report.id())).containsExactly(decision.after(), replacement); assertThat(resourceVersions(report)).isEqualTo(versions);
    }

    @Test void resourceAdjustmentUnknownBudgetResultCannotReleaseItsActiveReportClaim() throws Exception {
        var report = resourceAdjustmentReport(); var adjustment = authorizeResourceAdjustment(readyResourceAdjustment(report));
        var queued = budgetReversals.find("demo", adjustment.id()).orElseThrow(); var at = adjustmentTime(); var claimed = queued.claim(at, java.time.Duration.ofSeconds(30));
        var unknown = claimed.unavailable(BudgetConsumptionReversalOperation.Failure.TIMEOUT, at);
        tx().executeWithoutResult(status -> { budgetReversals.update(claimed); budgetReversals.update(unknown); });
        assertThatThrownBy(() -> new ExpenseResourceAdjustmentRetirement(adjustment, unknown, "finance", "timeout-proof", "未知不证明未执行", adjustmentTime())).isInstanceOf(io.agentflow.common.DomainException.class);
        assertThat(resourceAdjustments.active("demo", report.id())).contains(adjustment); assertThat(budgetReversals.due(unknown.nextAttemptAt())).anyMatch(value -> value.id().equals(adjustment.id()));
    }

    @Test void resourceAdjustmentResourcesAndCompletionRollBackTogetherWhileOriginalArchiveAndConsumptionsRemain() throws Exception {
        var report = archiveReadyExpense(); pollArchive(); var archive = archives.find("demo", report.id(), 1).orElseThrow(); var bytes = archiveDownload(report, "finance");
        prepareResourceAdjustmentSources(report); var original = resourceAdjustmentSources.find("demo", report.id()); var versions = resourceVersions(report);
        var ready = readyResourceExecution(report); var candidate = resourceCandidate(ready);
        assertThatThrownBy(() -> tx().execute(status -> { resourceAdjustmentExecution.apply(candidate); throw new IllegalStateException("Synthetic resource completion rollback"); }))
                .hasMessageContaining("Synthetic resource completion rollback");
        assertThat(resourceAdjustments.find("demo", ready.id())).contains(ready); assertThat(resourceVersions(report)).isEqualTo(versions);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finance_consumption_reversal WHERE tenant_id='demo' AND report_id=?", Integer.class, report.id().toString())).isZero();
        resourceAdjustmentExecution.apply(candidate); var complete = resourceAdjustments.find("demo", ready.id()).orElseThrow();
        assertThat(complete.status()).isEqualTo(ExpenseResourceAdjustment.Status.APPLIED); assertThat(complete.resourcesReversed()).isTrue();
        assertThat(resourceAdjustmentSources.find("demo", report.id())).isEqualTo(original);
        var loaded = financialResources.loadReserved(current(report));
        assertThat(loaded.invoices()).hasSize(1); var invoice = Invoice.restore(loaded.invoices().values().iterator().next());
        assertThat(invoice.occupation()).isEqualTo(Invoice.Occupation.AVAILABLE); assertThat(invoice.verification()).isEqualTo(Invoice.Verification.PENDING);
        assertThat(invoice.reversals()).hasSize(1); assertThat(invoice.reversals().get(0).adjustmentId()).isEqualTo(complete.id());
        var prior = ExpenseRequest.restore(loaded.requests().values().iterator().next()); var advance = EmployeeAdvance.restore(loaded.advances().values().iterator().next());
        assertThat(prior.balance(1).consumed()).isEqualTo(money("0")); assertThat(prior.balance(1).grossConsumed()).isEqualTo(money("100"));
        assertThat(advance.balance().consumed()).isEqualTo(money("0")); assertThat(advance.balance().grossConsumed()).isEqualTo(money("50"));
        assertThat(advance.outstanding()).isEqualTo(money("200"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invoice_active_claim WHERE tenant_id='demo' AND invoice_id=?", Integer.class, invoice.id().toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finance_consumption_reversal WHERE tenant_id='demo' AND report_id=?", Integer.class, report.id().toString())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finance_amount_use WHERE tenant_id='demo' AND report_id=? AND status='CONSUMED'", Integer.class, report.id().toString())).isEqualTo(2);
        var after = resourceVersions(report); resourceAdjustmentExecution.apply(candidate); assertThat(resourceVersions(report)).isEqualTo(after);
        assertThat(archives.find("demo", report.id(), 1)).contains(archive); assertThat(archiveDownload(report, "finance")).isEqualTo(bytes); assertThat(paymentWrites).isEqualTo(1);
        var erased = new Invoice.State(invoice.id(), invoice.tenantId(), invoice.ownerId(), invoice.originalFileId(), invoice.originalDigest(), invoice.version() + 1,
                invoice.verification(), invoice.occupation(), invoice.facts(), invoice.failureCode(), invoice.checkedAt(), invoice.use(), List.of());
        assertThatThrownBy(() -> invoices.update(Invoice.restore(erased), invoice.version(), "finance", "VERIFY")).isInstanceOf(io.agentflow.common.DomainException.class);
        assertThat(resourceVersions(report)).isEqualTo(after);
    }

    @Test void resourceAdjustmentCannotReclassifyAnOldFullReversalAsAPartialReduction() throws Exception {
        var report = resourceAdjustmentReport(); var ready = readyResourceExecution(report);
        resourceAdjustmentExecution.apply(resourceCandidate(ready));
        var id = current(report).currentRound().advanceOffsets().get(0).advanceId();
        var advance = advances.find("demo", id).orElseThrow(); var original = advance.state(); var reversal = advance.balance().reversals().get(0);
        var changed = json.read(json.write(original), com.fasterxml.jackson.databind.node.ObjectNode.class);
        changed.put("version", advance.version() + 1); var balance = (com.fasterxml.jackson.databind.node.ObjectNode) changed.get("balance");
        balance.putArray("reversals"); balance.set("reductions", json.read(json.write(List.of(new ReservedAmount.ConsumptionReduction(
                reversal.adjustmentId(), reversal.use(), reversal.amount(), reversal.reversedAt()))), JsonNode.class));
        var replacement = EmployeeAdvance.restore(json.read(json.write(changed), EmployeeAdvance.State.class));
        assertThatThrownBy(() -> advances.update(replacement, advance.version(), "finance", "REDUCE"))
                .isInstanceOfSatisfying(io.agentflow.common.DomainException.class, failure -> assertThat(failure.code()).isEqualTo("EXPENSE_CONSUMPTION_REVERSAL_UNAUTHORIZED"));
        assertThat(advances.find("demo", id).orElseThrow().state()).isEqualTo(original);
    }

    @Test void resourceAdjustmentRejectsUnauthorizedReversalsAndUnrelatedAggregateChanges() throws Exception {
        var report = resourceAdjustmentReport(); var versions = resourceVersions(report); var at = adjustmentTime();
        var unauthorized = new ExpenseResourceReversal().plan(report, financialResources.loadReserved(report), UUID.randomUUID(), at);
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> resourceChanges.persist(unauthorized, "finance"))).isInstanceOf(io.agentflow.common.DomainException.class);
        assertThat(resourceVersions(report)).isEqualTo(versions);
        var ready = readyResourceExecution(report); var plan = new ExpenseResourceReversal().plan(report, financialResources.loadReserved(report), ready.id(), adjustmentTime());
        var prior = plan.requests().get(0).after();
        var changed = new ExpenseRequest.State(prior.id(), prior.tenantId(), prior.applicationId(), prior.legalEntityId(), prior.employeeId(), prior.approvedLines(), prior.balances(), !prior.closed(), prior.version());
        assertThatThrownBy(() -> requests.update(ExpenseRequest.restore(changed), changed.version() - 1, "finance", "REVERSE_CONSUMPTION")).isInstanceOf(io.agentflow.common.DomainException.class);
        assertThat(resourceVersions(report)).isEqualTo(versions); assertThat(resourceAdjustments.find("demo", ready.id())).contains(ready);
    }

    @Test void resourceAdjustmentLateResourceFailureRollsBackEarlierResourcesAndAllowsExplicitRecovery() throws Exception {
        var report = resourceAdjustmentReport(); var ready = readyResourceExecution(report); var candidate = resourceCandidate(ready); var versions = resourceVersions(report);
        jdbc.update("UPDATE finance_amount_use SET amount=49 WHERE tenant_id='demo' AND report_id=? AND resource_type='ADVANCE' AND status='CONSUMED'", report.id().toString());
        assertThatThrownBy(() -> resourceAdjustmentExecution.apply(candidate)).isInstanceOf(io.agentflow.common.DomainException.class);
        assertThat(resourceVersions(report)).isEqualTo(versions);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finance_consumption_reversal WHERE tenant_id='demo' AND report_id=?", Integer.class, report.id().toString())).isZero();
        resourceAdjustmentExecution.block(candidate, "EXPENSE_CONSUMPTION_CHANGED"); var blocked = resourceAdjustments.find("demo", ready.id()).orElseThrow();
        assertThat(blocked.status()).isEqualTo(ExpenseResourceAdjustment.Status.REVIEW_REQUIRED); assertThat(blocked.budgetReversal()).isEqualTo(ready.budgetReversal());
        jdbc.update("UPDATE finance_amount_use SET amount=50 WHERE tenant_id='demo' AND report_id=? AND resource_type='ADVANCE' AND status='CONSUMED'", report.id().toString());
        var voucher = voucherOperations.forRound("demo", report.applicationId(), 1, VoucherCommand.Kind.PAYMENT).orElseThrow();
        observeVoucher(voucher, voucherFact(voucher, VoucherObservation.Status.POSTED, voucher.highestRevision() + 1));
        assertThatCode(() -> resourceAdjustmentSources.requireSupported(ready.input().basis())).doesNotThrowAnyException();
        assertThatThrownBy(() -> resourceAdjustmentSources.requireCurrent(ready.input().basis())).isInstanceOf(io.agentflow.common.DomainException.class);
        var retried = blocked.retryResources(budgetReversals.find("demo", ready.id()).orElseThrow(), adjustmentTime()); tx().executeWithoutResult(status -> resourceAdjustments.update(retried));
        resourceAdjustmentExecution.apply(resourceCandidate(retried)); assertThat(resourceAdjustments.find("demo", ready.id()).orElseThrow().resourcesReversed()).isTrue();
        assertThat(budgetReversals.find("demo", ready.id()).orElseThrow().attempts()).isEqualTo(1);
    }

    @Test void resourceWorkflowPreparesWithoutWritingAndExplicitAuthorizationCompletesExactlyOnce() throws Exception {
        var report = resourceAdjustmentReport(); var versions = resourceVersions(report); var prepared = prepareResourceWorkflow(report);
        assertThat(prepared.status()).isEqualTo(ExpenseResourceAdjustmentPreparation.Status.READY); assertThat(budgetReversalWrites).isZero();
        assertThat(budgetReversals.find("demo", prepared.input().id())).isEmpty(); assertThat(resourceVersions(report)).isEqualTo(versions);
        var input = resourceAuthorizationInput(report, prepared); var accepted = asFinance(() -> resourcePreparing.authorize(report.id(), input));
        assertThat(accepted.adjustmentVersion()).isEqualTo(1); assertThat(accepted.budgetVersion()).isEqualTo(1); assertThat(budgetReversalWrites).isZero();
        assertThatThrownBy(() -> asFinance(() -> resourcePreparing.authorize(report.id(), input))).isInstanceOf(io.agentflow.common.DomainException.class);
        resourceWorker.poll(); var complete = resourceAdjustments.find("demo", accepted.adjustmentId()).orElseThrow();
        assertThat(complete.status()).isEqualTo(ExpenseResourceAdjustment.Status.APPLIED); assertThat(complete.resourcesReversed()).isTrue(); assertThat(budgetReversalWrites).isEqualTo(1);
        var after = resourceVersions(report); resourceWorker.poll(); assertThat(resourceVersions(report)).isEqualTo(after); assertThat(budgetReversalWrites).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE tenant_id='demo' AND aggregate_id=? AND action IN ('EXPENSE_ADJUSTMENT_PREPARE','EXPENSE_ADJUSTMENT_AUTHORIZE')", Integer.class, complete.id().toString())).isEqualTo(2);
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> resourceWorker.poll())).isInstanceOf(IllegalStateException.class);
        asFinance(() -> resourceActions.act(report.id(), resourceActionInput(report, ExpenseResourceAdjustmentActionService.Action.QUERY)));
        assertThat(resourceAdjustments.find("demo", complete.id()).orElseThrow().resourcesReversed()).isTrue(); resourceWorker.poll();
        assertThat(resourceAdjustments.find("demo", complete.id()).orElseThrow().status()).isEqualTo(ExpenseResourceAdjustment.Status.REVIEW_REQUIRED);
        asFinance(() -> resourceActions.act(report.id(), resourceActionInput(report, ExpenseResourceAdjustmentActionService.Action.CONFIRM_COMPLETED)));
        assertThat(resourceAdjustments.find("demo", complete.id()).orElseThrow().status()).isEqualTo(ExpenseResourceAdjustment.Status.APPLIED);
        assertThat(resourceVersions(report)).isEqualTo(after); assertThat(budgetReversalWrites).isEqualTo(1); assertThat(budgetReversalQueries).isEqualTo(1);
    }

    @Test void resourceWorkflowAuthorizationRollbackAndConcurrencyPreserveSingleSourceConsumption() throws Exception {
        var report = resourceAdjustmentReport(); var prepared = prepareResourceWorkflow(report); var input = resourceAuthorizationInput(report, prepared);
        assertThatThrownBy(() -> asFinance(() -> tx().execute(status -> { resourcePreparing.authorize(report.id(), input); throw new IllegalStateException("Synthetic service authorization rollback"); })))
                .hasMessageContaining("Synthetic service authorization rollback");
        assertThat(resourcePreparations.find("demo", prepared.input().id())).contains(prepared); assertThat(resourceAdjustments.active("demo", report.id())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE tenant_id='demo' AND aggregate_id=? AND action='EXPENSE_ADJUSTMENT_AUTHORIZE'", Integer.class, prepared.input().id().toString())).isZero();
        var gate = new java.util.concurrent.CountDownLatch(1); var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.Callable<String> action = () -> { gate.await(); try { asFinance(() -> resourcePreparing.authorize(report.id(), input)); return "AUTHORIZED"; }
            catch (io.agentflow.common.DomainException conflict) { return conflict.code(); } };
        try {
            var first = pool.submit(action); var second = pool.submit(action); gate.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder("AUTHORIZED", "CONCURRENCY_CONFLICT");
        } finally { pool.shutdownNow(); }
        resourceWorker.poll(); assertThat(budgetReversalWrites).isEqualTo(1); assertThat(resourceAdjustments.active("demo", report.id()).orElseThrow().resourcesReversed()).isTrue();
    }

    @Test void resourceWorkflowUnavailableFinanceStopsBeforeSendingAndCanBeSafelyEndedAfterEligibilityReturns() throws Exception {
        var report = resourceAdjustmentReport(); var prepared = prepareResourceWorkflow(report); asFinance(() -> resourcePreparing.authorize(report.id(), resourceAuthorizationInput(report, prepared)));
        var person = organizationRepository.person("demo", finance).orElseThrow(); var inactive = organization.updatePerson(admin, finance, person.displayName(), false, person.approvalEligible(), person.revision());
        try { resourceWorker.poll(); }
        finally { organization.updatePerson(admin, finance, person.displayName(), person.active(), person.approvalEligible(), inactive.revision()); }
        var adjustment = resourceAdjustments.active("demo", report.id()).orElseThrow(); var stopped = budgetReversals.find("demo", adjustment.id()).orElseThrow();
        assertThat(stopped.status()).isEqualTo(BudgetConsumptionReversalOperation.Status.VOIDED); assertThat(stopped.attempts()).isZero(); assertThat(budgetReversalWrites).isZero();
        var input = resourceRetirementInput(report);
        assertThatThrownBy(() -> asFinance(() -> tx().execute(status -> { resourceActions.retire(report.id(), input); throw new IllegalStateException("Synthetic service retirement rollback"); })))
                .hasMessageContaining("Synthetic service retirement rollback");
        assertThat(resourceAdjustments.active("demo", report.id())).contains(adjustment); assertThat(resourceAdjustments.retirement("demo", adjustment.id())).isEmpty();
        asFinance(() -> resourceActions.retire(report.id(), input)); assertThat(resourceAdjustments.active("demo", report.id())).isEmpty();
        assertThat(resourceAdjustments.retirement("demo", adjustment.id())).isPresent(); assertThat(prepareResourceWorkflow(report).status()).isEqualTo(ExpenseResourceAdjustmentPreparation.Status.READY);
    }

    @Test void resourceWorkflowQueriesUnknownBudgetDespiteSourceAndPersonnelChangesThenRequiresExplicitLocalRecovery() throws Exception {
        var report = resourceAdjustmentReport(); var prepared = prepareResourceWorkflow(report); asFinance(() -> resourcePreparing.authorize(report.id(), resourceAuthorizationInput(report, prepared)));
        invalidBudgetReversalResponse = true; resourceWorker.poll(); var adjustment = resourceAdjustments.active("demo", report.id()).orElseThrow();
        assertThat(budgetReversals.find("demo", adjustment.id()).orElseThrow().status()).isEqualTo(BudgetConsumptionReversalOperation.Status.UNKNOWN);
        assertThat(budgetReversalWrites).isEqualTo(1); assertThat(adjustment.resourcesReversed()).isFalse();
        var accrual = voucherOperations.find("demo", adjustment.input().basis().accrualReversal().operationId()).orElseThrow();
        tx().executeWithoutResult(status -> voucherExecution.query("demo", accrual.input().command().id(), accrual.version(), Instant.now()));
        asFinance(() -> resourceActions.act(report.id(), resourceActionInput(report, ExpenseResourceAdjustmentActionService.Action.QUERY)));
        var person = organizationRepository.person("demo", finance).orElseThrow(); var inactive = organization.updatePerson(admin, finance, person.displayName(), false, person.approvalEligible(), person.revision());
        invalidBudgetReversalResponse = false;
        try { resourceWorker.poll(); }
        finally { organization.updatePerson(admin, finance, person.displayName(), person.active(), person.approvalEligible(), inactive.revision()); }
        var budget = budgetReversals.find("demo", adjustment.id()).orElseThrow(); assertThat(budget.status()).isEqualTo(BudgetConsumptionReversalOperation.Status.APPLIED);
        assertThat(resourceAdjustments.active("demo", report.id()).orElseThrow().status()).isEqualTo(ExpenseResourceAdjustment.Status.REVIEW_REQUIRED);
        assertThat(resourceAdjustments.active("demo", report.id()).orElseThrow().resourcesReversed()).isFalse(); assertThat(budgetReversalWrites).isEqualTo(1); assertThat(budgetReversalQueries).isEqualTo(1);
        var claimed = voucherExecution.claim("demo", accrual.input().command().id(), Instant.now());
        voucherExecution.finish(claimed, new FinanceResult.Success<>(voucherFact(accrual, VoucherObservation.Status.REVERSED, accrual.highestRevision() + 1)), Instant.now().plusMillis(1));
        asFinance(() -> resourceActions.act(report.id(), resourceActionInput(report, ExpenseResourceAdjustmentActionService.Action.RETRY_RESOURCES))); resourceWorker.poll();
        assertThat(resourceAdjustments.active("demo", report.id()).orElseThrow().resourcesReversed()).isTrue(); assertThat(budgetReversalWrites).isEqualTo(1);
    }

    @Test void resourceWorkflowNotFoundNeedsExplicitOriginalAuthorizerResendAndKeepsOneCommand() throws Exception {
        var report = resourceAdjustmentReport(); var prepared = prepareResourceWorkflow(report); asFinance(() -> resourcePreparing.authorize(report.id(), resourceAuthorizationInput(report, prepared)));
        invalidBudgetReversalResponse = true; resourceWorker.poll(); invalidBudgetReversalResponse = false; budgetReversalStatus = BudgetConsumptionReversalObservation.Status.NOT_FOUND;
        asFinance(() -> resourceActions.act(report.id(), resourceActionInput(report, ExpenseResourceAdjustmentActionService.Action.QUERY))); resourceWorker.poll();
        var before = resourceAdjustments.active("demo", report.id()).orElseThrow(); var command = budgetReversals.find("demo", before.id()).orElseThrow().input();
        assertThat(budgetReversals.find("demo", before.id()).orElseThrow().status()).isEqualTo(BudgetConsumptionReversalOperation.Status.NOT_FOUND);
        resourceWorker.poll(); assertThat(budgetReversalWrites).isEqualTo(1);
        assertThatThrownBy(() -> asFinance(() -> resourceActions.retire(report.id(), resourceRetirementInput(report)))).isInstanceOf(io.agentflow.common.DomainException.class);
        actors.set(new Actor("demo", "manager", Set.of("EMPLOYEE", "APPROVER", "FINANCE")));
        try { assertThatThrownBy(() -> resourceActions.act(report.id(), resourceActionInput(report, ExpenseResourceAdjustmentActionService.Action.RESEND_ORIGINAL))).isInstanceOf(io.agentflow.common.DomainException.class); }
        finally { actors.clear(); }
        asFinance(() -> resourceActions.act(report.id(), resourceActionInput(report, ExpenseResourceAdjustmentActionService.Action.RESEND_ORIGINAL)));
        budgetReversalStatus = BudgetConsumptionReversalObservation.Status.APPLIED; resourceWorker.poll();
        assertThat(resourceAdjustments.active("demo", report.id()).orElseThrow().resourcesReversed()).isTrue(); assertThat(budgetReversalWrites).isEqualTo(2);
        assertThat(budgetReversalCommands).hasSize(1); assertThat(budgetReversals.find("demo", before.id()).orElseThrow().input()).isEqualTo(command);
    }

    @Test void resourceWorkflowBlocksCashierAndHiddenFinancialFieldsAndRecoversLocalResourcesWithoutGateway() throws Exception {
        var report = resourceAdjustmentReport(); var input = resourcePreparationInput(report);
        for (String user : List.of("alice", "cashier", "admin")) {
            actors.set(new Actor("demo", user, Set.of("EMPLOYEE", "APPROVER", "ADMIN", "FINANCE")));
            try { assertThatThrownBy(() -> resourcePreparing.prepare(report.id(), input)).isInstanceOf(io.agentflow.common.DomainException.class); }
            finally { actors.clear(); }
        }
        var ready = readyResourceExecution(report); configuration.setEnabled(false);
        try { resourceWorker.poll(); } finally { configuration.setEnabled(true); }
        assertThat(resourceAdjustments.find("demo", ready.id()).orElseThrow().resourcesReversed()).isTrue(); assertThat(budgetReversalWrites).isZero();
        paymentMode = "SUCCEEDED"; hideBusinessDetails = true; var hidden = archiveReadyExpense();
        assertThatThrownBy(() -> asFinance(() -> resourcePreparing.prepare(hidden.id(), resourcePreparationInput(hidden)))).isInstanceOf(io.agentflow.common.DomainException.class);
        assertThat(resourcePreparations.latest("demo", hidden.id(), "finance")).isEmpty();
    }

    @Test void resourceWorkflowZeroPayableReversesResourcesWithoutInventingAnyBankReturn() throws Exception {
        advanceOffset = "100"; var report = paymentReport(true); settlementWorker.poll(); budgetWorker.poll();
        assertThat(settlements.find("demo", report.id()).orElseThrow().status()).isEqualTo(ExpenseSettlement.Status.SETTLED);
        var accrual = reversedVoucher(report, VoucherCommand.Kind.EXPENSE_ACCRUAL); var checked = checkedReversal(report, accrual);
        ok(send(reversalPath(accrual) + "/records", "finance", reversalRecordInput(report, accrual, checked)), 202);
        var prepared = prepareResourceWorkflow(report); assertThat(prepared.input().basis().paymentReturns()).isNull();
        assertThat(prepared.input().basis().paymentVoucher()).isNull(); asFinance(() -> resourcePreparing.authorize(report.id(), resourceAuthorizationInput(report, prepared)));
        resourceWorker.poll(); var complete = resourceAdjustments.active("demo", report.id()).orElseThrow();
        assertThat(complete.resourcesReversed()).isTrue(); assertThat(paymentWrites).isZero(); assertThat(budgetReversalWrites).isEqualTo(1);
        assertThat(expenseReturnLedgers.find("demo", report.id())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finance_consumption_reversal WHERE tenant_id='demo' AND report_id=?", Integer.class, report.id().toString())).isEqualTo(3);
    }

    @Test void resourceWorkflowSourceChangeDuringPreparationVoidsTheCandidateAndDisabledDestinationNeverSends() throws Exception {
        var report = resourceAdjustmentReport(); var queued = asFinance(() -> resourcePreparing.prepare(report.id(), resourcePreparationInput(report)));
        var claimed = resourcePreparing.claim("demo", queued.preparationId(), Instant.now());
        var accrual = voucherOperations.find("demo", claimed.input().basis().accrualReversal().operationId()).orElseThrow();
        observeVoucher(accrual, voucherFact(accrual, VoucherObservation.Status.REVERSED, accrual.highestRevision() + 1));
        // 挂账登记仍对应同一原件，改用付款凭证的新修订使未授权准备明确失效。
        var voucher = claimed.input().basis().paymentVoucher(); observeVoucher(voucher, voucherFact(voucher, VoucherObservation.Status.POSTED, voucher.highestRevision() + 1));
        var period = accountingPeriods.period("demo", target(), claimed.input().periodRequest()); resourcePreparing.finish(claimed, period, Instant.now());
        assertThat(resourcePreparations.find("demo", queued.preparationId()).orElseThrow().status()).isEqualTo(ExpenseResourceAdjustmentPreparation.Status.VOIDED);
        assertThat(resourceAdjustments.active("demo", report.id())).isEmpty(); assertThat(budgetReversalWrites).isZero();
        var prepared = prepareResourceWorkflow(report); asFinance(() -> resourcePreparing.authorize(report.id(), resourceAuthorizationInput(report, prepared)));
        configuration.setEnabled(false); try { resourceWorker.poll(); } finally { configuration.setEnabled(true); }
        var stopped = budgetReversals.find("demo", prepared.input().id()).orElseThrow(); assertThat(stopped.status()).isEqualTo(BudgetConsumptionReversalOperation.Status.VOIDED);
        assertThat(stopped.attempts()).isZero(); assertThat(budgetReversalWrites).isZero();
    }

    @Test void resourceWorkflowExplicitBudgetRejectionCanBeRetiredAndRepreparedForAnotherOpenDate() throws Exception {
        var report = resourceAdjustmentReport(); var prepared = prepareResourceWorkflow(report); asFinance(() -> resourcePreparing.authorize(report.id(), resourceAuthorizationInput(report, prepared)));
        budgetReversalStatus = BudgetConsumptionReversalObservation.Status.REJECTED; resourceWorker.poll(); var rejected = budgetReversals.find("demo", prepared.input().id()).orElseThrow();
        assertThat(rejected.status()).isEqualTo(BudgetConsumptionReversalOperation.Status.REJECTED); assertThat(resourceAdjustments.active("demo", report.id()).orElseThrow().resourcesReversed()).isFalse();
        asFinance(() -> resourceActions.retire(report.id(), resourceRetirementInput(report))); var originalInput = resourcePreparationInput(report);
        var newInput = new ExpenseResourceAdjustmentPreparationService.PrepareInput(originalInput.roundNo(), originalInput.applicationVersion(), originalInput.businessVersion(), originalInput.settlementVersion(),
                originalInput.accountingDate().plusDays(1), "new-open-period-evidence", "原期间拒绝后选择新的开放记账日");
        var next = asFinance(() -> resourcePreparing.prepare(report.id(), newInput)); resourceWorker.poll();
        var ready = resourcePreparations.find("demo", next.preparationId()).orElseThrow(); asFinance(() -> resourcePreparing.authorize(report.id(), resourceAuthorizationInput(report, ready)));
        budgetReversalStatus = BudgetConsumptionReversalObservation.Status.APPLIED; resourceWorker.poll();
        assertThat(resourceAdjustments.active("demo", report.id()).orElseThrow().resourcesReversed()).isTrue();
        assertThat(budgetReversals.find("demo", prepared.input().id())).contains(rejected);
        assertThat(budgetReversals.find("demo", next.preparationId()).orElseThrow().input().command().period().request().accountingDate()).isEqualTo(newInput.accountingDate());
        assertThat(budgetReversalWrites).isEqualTo(2); assertThat(resourceAdjustments.history("demo", report.id())).hasSize(2);
    }

    @Test void resourceWorkflowRetirementAndFirstDispatchClaimCannotBothWin() throws Exception {
        var report = resourceAdjustmentReport(); var prepared = prepareResourceWorkflow(report); asFinance(() -> resourcePreparing.authorize(report.id(), resourceAuthorizationInput(report, prepared)));
        var input = resourceRetirementInput(report); var gate = new java.util.concurrent.CountDownLatch(1); var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var retirement = pool.submit(() -> { gate.await(); try { asFinance(() -> resourceActions.retire(report.id(), input)); return "RETIRED"; }
                catch (io.agentflow.common.DomainException conflict) { return "BLOCKED"; } });
            var dispatch = pool.submit(() -> { gate.await(); try { return resourceBudgetExecution.claim("demo", prepared.input().id(), Instant.now()) == null ? "NONE" : "CLAIMED"; }
                catch (io.agentflow.common.DomainException conflict) { return "BLOCKED"; } });
            gate.countDown(); var ended = retirement.get(); var claimed = dispatch.get(); var operation = budgetReversals.find("demo", prepared.input().id()).orElseThrow();
            if (ended.equals("RETIRED")) {
                assertThat(claimed).isEqualTo("BLOCKED"); assertThat(operation.attempts()).isZero(); assertThat(resourceAdjustments.active("demo", report.id())).isEmpty();
            } else {
                assertThat(claimed).isEqualTo("CLAIMED"); assertThat(operation.status()).isEqualTo(BudgetConsumptionReversalOperation.Status.EXECUTING);
                assertThat(resourceAdjustments.retirement("demo", prepared.input().id())).isEmpty(); assertThat(resourceAdjustments.active("demo", report.id())).isPresent();
            }
        } finally { pool.shutdownNow(); }
        assertThat(budgetReversalWrites).isZero();
    }

    @Test void resourceApiScopesOriginalFieldsRejectsInjectedDataAndNeverAuthorizesFromARead() throws Exception {
        hideBusinessDetails = true; var report = resourceAdjustmentReport(); var endpoint = path(report) + "/resource-adjustment";
        var response = read(endpoint, "finance"); var view = ok(response, 200);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store"); assertThat(view.path("canPrepare").asBoolean()).isTrue();
        assertThat(view.path("adjustments")).isEmpty(); assertThat(budgetReversalWrites).isZero();
        var applicant = ok(read(endpoint, "alice"), 200); assertThat(applicant.path("finance").asBoolean()).isFalse(); assertThat(applicant.path("latestPreparation").isNull()).isTrue();
        for (var user : List.of("bob", "cashier", "admin", "manager")) assertThat(read(endpoint, user).getStatus()).isIn(403, 404);
        for (var query : List.of("?tenantId=foreign", "?roundNo=0", "?roundNo=1e2")) assertThat(read(endpoint + query, "finance").getStatus()).isEqualTo(400);
        assertThat(read(endpoint + "?roundNo=2", "finance").getStatus()).isEqualTo(404);
        var input = resourcePreparationInput(report);
        for (var user : List.of("alice", "cashier", "admin")) assertThat(send(endpoint + "/preparations", user, input).getStatus()).isIn(403, 404);
        var injected = json.read(json.write(input), com.fasterxml.jackson.databind.node.ObjectNode.class).put("amount", "0.01");
        assertThat(send(endpoint + "/preparations", "finance", injected).getStatus()).isEqualTo(400);
        String key = UUID.randomUUID().toString(); var queued = ok(send(endpoint + "/preparations", "finance", key, input), 202);
        var replay = send(endpoint + "/preparations", "finance", key, input); assertThat(replay.getHeader("Idempotency-Replayed")).isEqualTo("true"); assertThat(ok(replay, 202)).isEqualTo(queued);
        resourceWorker.poll(); var ready = ok(read(endpoint, "finance"), 200); assertThat(ready.path("latestPreparation").path("canAuthorize").asBoolean()).isTrue();
        assertThat(ok(read(endpoint, "alice"), 200).path("latestPreparation").isNull()).isTrue();
        assertThat(resourceAdjustments.active("demo", report.id())).isEmpty(); assertThat(budgetReversalWrites).isZero();
        var person = organizationRepository.person("demo", finance).orElseThrow(); var inactive = organization.updatePerson(admin, finance, person.displayName(), false, person.approvalEligible(), person.revision());
        try { assertThat(send(endpoint + "/preparations", "finance", key, input).getStatus()).isIn(403, 404); }
        finally { organization.updatePerson(admin, finance, person.displayName(), true, person.approvalEligible(), inactive.revision()); }
    }

    @Test void resourceApiExplicitAuthorizationKeepsBudgetAndResourceResultsSeparateAndReplayCannotDuplicateEffects() throws Exception {
        var report = resourceAdjustmentReport(); var endpoint = path(report) + "/resource-adjustment"; var prepared = prepareResourceWorkflow(report);
        var input = resourceAuthorizationInput(report, prepared); String key = UUID.randomUUID().toString();
        var accepted = ok(send(endpoint + "/authorizations", "finance", key, input), 202);
        var queued = ok(read(endpoint, "finance"), 200).path("adjustments").get(0);
        assertThat(queued.path("status").asText()).isEqualTo("WAITING_BUDGET"); assertThat(queued.path("budget").path("status").asText()).isEqualTo("QUEUED");
        assertThat(queued.path("resourcesReversed").asBoolean()).isFalse(); assertThat(queued.path("canRetire").asBoolean()).isTrue();
        assertThat(queued.path("availableActions")).isEmpty(); assertThat(budgetReversalWrites).isZero();
        resourceWorker.poll(); var versions = resourceVersions(report);
        assertThat(ok(send(endpoint + "/authorizations", "finance", key, input), 202)).isEqualTo(accepted);
        assertThat(send(endpoint + "/authorizations", "finance", input).getStatus()).isIn(409, 422);
        var response = read(endpoint, "alice"); var complete = ok(response, 200).path("adjustments").get(0);
        assertThat(complete.path("status").asText()).isEqualTo("APPLIED"); assertThat(complete.path("resourcesReversed").asBoolean()).isTrue();
        assertThat(complete.path("budget").path("acceptedReference").isTextual()).isTrue(); assertThat(complete.path("availableActions")).isEmpty();
        assertThat(response.getContentAsString()).doesNotContain("commandDigest", "targetDigest", "accountDigest", "debitAccountReference", "synthetic-private-account", "authorizedInput");
        var query = resourceActionInput(report, ExpenseResourceAdjustmentActionService.Action.QUERY); String queryKey = UUID.randomUUID().toString();
        var requested = ok(send(endpoint + "/actions", "finance", queryKey, query), 202); resourceWorker.poll();
        var checking = ok(read(endpoint, "finance"), 200).path("adjustments").get(0);
        assertThat(checking.path("status").asText()).isEqualTo("REVIEW_REQUIRED"); assertThat(checking.path("availableActions").toString()).contains("CONFIRM_COMPLETED").doesNotContain("RETRY_RESOURCES");
        assertThat(ok(send(endpoint + "/actions", "finance", queryKey, query), 202)).isEqualTo(requested);
        ok(send(endpoint + "/actions", "finance", resourceActionInput(report, ExpenseResourceAdjustmentActionService.Action.CONFIRM_COMPLETED)), 202);
        assertThat(resourceVersions(report)).isEqualTo(versions); assertThat(budgetReversalWrites).isEqualTo(1); assertThat(budgetReversalQueries).isEqualTo(1);
    }

    @Test void resourceApiOriginalQueryRemainsAvailableWhenAccrualSourceChanges() throws Exception {
        var report = resourceAdjustmentReport(); var endpoint = path(report) + "/resource-adjustment"; var prepared = prepareResourceWorkflow(report);
        ok(send(endpoint + "/authorizations", "finance", resourceAuthorizationInput(report, prepared)), 202);
        invalidBudgetReversalResponse = true; resourceWorker.poll(); invalidBudgetReversalResponse = false;
        var adjustment = resourceAdjustments.active("demo", report.id()).orElseThrow();
        var accrual = voucherOperations.find("demo", adjustment.input().basis().accrualReversal().operationId()).orElseThrow();
        tx().executeWithoutResult(status -> voucherExecution.query("demo", accrual.input().command().id(), accrual.version(), Instant.now()));
        var before = ok(read(endpoint, "finance"), 200); assertThat(before.path("canPrepare").asBoolean()).isFalse();
        assertThat(before.path("adjustments").get(0).path("availableActions").toString()).contains("QUERY");
        ok(send(endpoint + "/actions", "finance", resourceActionInput(report, ExpenseResourceAdjustmentActionService.Action.QUERY)), 202); resourceWorker.poll();
        var after = ok(read(endpoint, "finance"), 200).path("adjustments").get(0);
        assertThat(after.path("budget").path("status").asText()).isEqualTo("APPLIED"); assertThat(after.path("status").asText()).isEqualTo("REVIEW_REQUIRED");
        assertThat(after.path("resourcesReversed").asBoolean()).isFalse(); assertThat(after.path("availableActions").toString()).doesNotContain("RETRY_RESOURCES");
        assertThat(budgetReversalWrites).isEqualTo(1); assertThat(budgetReversalQueries).isEqualTo(1);
    }

    @Test void resourceApiSafeRetirementRetainsHistoryAndPreventsCrossReportActions() throws Exception {
        var report = resourceAdjustmentReport(); var endpoint = path(report) + "/resource-adjustment"; var prepared = prepareResourceWorkflow(report);
        ok(send(endpoint + "/authorizations", "finance", resourceAuthorizationInput(report, prepared)), 202);
        var input = resourceRetirementInput(report); String key = UUID.randomUUID().toString();
        for (var user : List.of("alice", "cashier", "admin")) assertThat(send(endpoint + "/retirements", user, input).getStatus()).isIn(403, 404);
        var injected = json.read(json.write(input), com.fasterxml.jackson.databind.node.ObjectNode.class).put("resourcesReversed", true);
        assertThat(send(endpoint + "/retirements", "finance", injected).getStatus()).isEqualTo(400);
        var accepted = ok(send(endpoint + "/retirements", "finance", key, input), 202); resourceWorker.poll();
        assertThat(ok(send(endpoint + "/retirements", "finance", key, input), 202)).isEqualTo(accepted);
        var view = ok(read(endpoint, "finance"), 200); assertThat(view.path("canPrepare").asBoolean()).isTrue();
        var retired = view.path("adjustments").get(0); assertThat(retired.path("status").asText()).isEqualTo("RETIRED");
        assertThat(retired.path("retirement").path("evidenceReference").asText()).isEqualTo(input.evidenceReference()); assertThat(retired.path("availableActions")).isEmpty();
        ok(send(endpoint + "/preparations", "finance", resourcePreparationInput(report)), 202); resourceWorker.poll();
        assertThat(ok(read(endpoint, "finance"), 200).path("latestPreparation").path("canAuthorize").asBoolean()).isTrue();
        assertThat(budgetReversalWrites).isZero();
        paymentMode = "SUCCEEDED"; var other = paymentReport(false); var wrong = new ExpenseResourceAdjustmentActionService.OperationInput(1, app(other).version(), current(other).version(),
                input.adjustmentId(), input.adjustmentVersion(), input.budgetVersion(), ExpenseResourceAdjustmentActionService.Action.QUERY, "跨单请求不得修改原调整");
        assertThat(send(path(other) + "/resource-adjustment/actions", "finance", wrong).getStatus()).isEqualTo(409);
    }

    private ExpenseResourceAdjustmentPreparationService.PrepareInput resourcePreparationInput(ExpenseReport report) {
        return new ExpenseResourceAdjustmentPreparationService.PrepareInput(1, app(report).version(), current(report).version(), settlements.find("demo", report.id()).orElseThrow().version(), LocalDate.now(), "full-cancellation-evidence", "独立核对后取消整笔报销");
    }
    private ExpenseResourceAdjustmentPreparation prepareResourceWorkflow(ExpenseReport report) {
        var action = asFinance(() -> resourcePreparing.prepare(report.id(), resourcePreparationInput(report))); resourceWorker.poll();
        return resourcePreparations.find("demo", action.preparationId()).orElseThrow();
    }
    private ExpenseResourceAdjustmentPreparationService.AuthorizeInput resourceAuthorizationInput(ExpenseReport report, ExpenseResourceAdjustmentPreparation value) {
        return new ExpenseResourceAdjustmentPreparationService.AuthorizeInput(1, app(report).version(), current(report).version(), settlements.find("demo", report.id()).orElseThrow().version(), value.input().id(), value.version(), "确认原完整准备和预算冲正授权");
    }
    private ExpenseResourceAdjustmentActionService.OperationInput resourceActionInput(ExpenseReport report, ExpenseResourceAdjustmentActionService.Action action) {
        var current = resourceAdjustments.active("demo", report.id()).orElseThrow(); var budget = budgetReversals.find("demo", current.id()).orElseThrow();
        return new ExpenseResourceAdjustmentActionService.OperationInput(1, app(report).version(), current(report).version(), current.id(), current.version(), budget.version(), action, "核对原操作后明确继续办理");
    }
    private ExpenseResourceAdjustmentActionService.RetireInput resourceRetirementInput(ExpenseReport report) {
        var current = resourceAdjustments.active("demo", report.id()).orElseThrow(); var budget = budgetReversals.find("demo", current.id()).orElseThrow();
        return new ExpenseResourceAdjustmentActionService.RetireInput(1, app(report).version(), current(report).version(), current.id(), current.version(), budget.version(), "safe-retirement-evidence", "确认原命令未产生副作用后结束");
    }

    private ExpenseResourceAdjustment readyResourceExecution(ExpenseReport report) {
        var adjustment = authorizeResourceAdjustment(readyResourceAdjustment(report)); var queued = budgetReversals.find("demo", adjustment.id()).orElseThrow(); var at = adjustmentTime();
        var claimed = queued.claim(at, java.time.Duration.ofSeconds(30)); var command = claimed.input().command();
        var receipt = new BudgetConsumptionReversalObservation(command.id(), command.digest(), BudgetConsumptionReversalObservation.Status.APPLIED, at,
                command.consumed().ledgerRevision() + 1, "resource-return-" + command.id(), command.period().periodReference(), command.period().request().accountingDate(), at, null);
        var completed = claimed.complete(new FinanceResult.Success<>(receipt), at); var ready = adjustment.budgetApplied(completed, at);
        tx().executeWithoutResult(status -> { budgetReversals.update(claimed); budgetReversals.update(completed); resourceAdjustments.update(ready); }); return ready;
    }
    private JdbcExpenseResourceAdjustmentRepository.Candidate resourceCandidate(ExpenseResourceAdjustment value) {
        return new JdbcExpenseResourceAdjustmentRepository.Candidate(value.input().basis().tenantId(), value.id(), value.input().basis().reportId(), value.version());
    }
    @Test void partialAdjustmentPersistsActualSourcesClaimsAndEveryRevision() throws Exception {
        var value = partialAdjustment(); var report = reports.find("demo", value.input().basis().reportId()).orElseThrow();
        var versions = resourceVersions(report); var original = settlements.find("demo", report.id()).orElseThrow();
        tx().executeWithoutResult(status -> partialAdjustments.create(value));
        assertThat(partialAdjustments.find("demo", value.id())).contains(value);
        assertThat(partialAdjustments.find("foreign", value.id())).isEmpty();
        assertThat(partialAdjustments.revision("demo", value.id(), 1)).contains(value);
        assertThat(partialAdjustments.active("demo", report.id())).contains(value);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_partial_adjustment_return WHERE tenant_id='demo' AND adjustment_id=? AND active_funds_identity IS NOT NULL", Integer.class, value.id().toString())).isEqualTo(1);
        var at = adjustmentTime(); var queued = value.authorizeBudget(partialBudget(value, at), at);
        tx().executeWithoutResult(status -> partialAdjustments.update(queued));
        assertThat(partialAdjustments.find("demo", value.id())).contains(queued);
        assertThat(partialAdjustments.dueBudget(adjustmentTime())).extracting(JdbcExpensePartialAdjustmentRepository.Candidate::id).contains(value.id());
        assertThat(partialAdjustments.dueAccrual(adjustmentTime())).extracting(JdbcExpensePartialAdjustmentRepository.Candidate::id).doesNotContain(value.id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_partial_adjustment_operation WHERE tenant_id='demo' AND adjustment_id=?", Integer.class, value.id().toString())).isEqualTo(1);
        assertThat(settlements.find("demo", report.id())).contains(original); assertThat(resourceVersions(report)).isEqualTo(versions);
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> partialAdjustments.update(queued))).isInstanceOf(io.agentflow.common.DomainException.class);
    }

    @Test void partialAdjustmentConcurrentCreationHasOneOwnerAndRollbackReleasesEveryClaim() throws Exception {
        var first = partialAdjustment(); var input = first.input();
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> { partialAdjustments.create(first); throw new IllegalStateException("Synthetic partial adjustment rollback"); }))
                .hasMessageContaining("Synthetic partial adjustment rollback");
        assertThat(partialAdjustments.find("demo", first.id())).isEmpty();
        var second = ExpensePartialAdjustment.begin(new ExpensePartialAdjustment.Input(UUID.randomUUID(), input.basis(), input.requestedBy(), input.evidenceReference(), input.reason(), input.createdAt()));
        var start = new java.util.concurrent.CountDownLatch(1); var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var jobs = List.of(first, second).stream().map(value -> pool.submit(() -> {
                start.await();
                try { tx().executeWithoutResult(status -> partialAdjustments.create(value)); return "SAVED"; }
                catch (io.agentflow.common.DomainException conflict) { return conflict.code(); }
            })).toList();
            start.countDown();
            assertThat(List.of(jobs.get(0).get(20, java.util.concurrent.TimeUnit.SECONDS), jobs.get(1).get(20, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("SAVED", "EXPENSE_PARTIAL_ADJUSTMENT_PENDING");
        } finally { pool.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_partial_adjustment_return WHERE tenant_id='demo' AND report_id=? AND active_funds_identity IS NOT NULL", Integer.class, input.basis().reportId().toString())).isEqualTo(1);
    }

    @Test void partialAdjustmentBlocksWholeAccrualReversalUntilExplicitSafeRetirement() throws Exception {
        var value = partialAdjustment(); tx().executeWithoutResult(status -> partialAdjustments.create(value));
        var report = reports.find("demo", value.input().basis().reportId()).orElseThrow(); var accrual = value.input().basis().funding().financial().accrual();
        var response = send(executionPath(report, accrual) + "/preparations", "finance", executionPreparation(report, accrual));
        assertThat(response.getStatus()).isEqualTo(409);
        var stopped = value.retire("finance", "no-effects", "确认未发送后结束", adjustmentTime());
        tx().executeWithoutResult(status -> partialAdjustments.update(stopped));
        assertThat(partialAdjustments.active("demo", report.id())).isEmpty();
        assertThat(partialAdjustments.find("demo", value.id())).contains(stopped);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_partial_adjustment_return WHERE tenant_id='demo' AND adjustment_id=? AND active_funds_identity IS NOT NULL", Integer.class, value.id().toString())).isZero();
        var replacement = ExpensePartialAdjustment.begin(new ExpensePartialAdjustment.Input(UUID.randomUUID(), value.input().basis(), "finance", "new-evidence", "重新建立独立调整", adjustmentTime()));
        tx().executeWithoutResult(status -> partialAdjustments.create(replacement));
        assertThat(partialAdjustments.active("demo", report.id())).contains(replacement);
    }

    @Test void partialAdjustmentOperationRevisionFailureRollsBackAuthorizationAndClaims() throws Exception {
        var value = partialAdjustment(); tx().executeWithoutResult(status -> partialAdjustments.create(value));
        var at = adjustmentTime(); var queued = value.authorizeBudget(partialBudget(value, at), at);
        jdbc.update("INSERT INTO expense_partial_adjustment_revision(tenant_id,adjustment_id,version,state_json) VALUES('demo',?,2,'{}')", value.id().toString());
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> partialAdjustments.update(queued))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(partialAdjustments.find("demo", value.id())).contains(value);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_partial_adjustment_operation WHERE tenant_id='demo' AND adjustment_id=?", Integer.class, value.id().toString())).isZero();
    }

    @Test void partialAdjustmentPersistsBothExternalSuccessesButCannotForgeResourceCompletion() throws Exception {
        var value = partialAdjustment(); var report = reports.find("demo", value.input().basis().reportId()).orElseThrow(); var versions = resourceVersions(report);
        var ready = readyPartialAdjustment(value); var at = ready.updatedAt();
        assertThat(partialAdjustments.find("demo", value.id())).contains(ready); assertThat(ready.status()).isEqualTo(ExpensePartialAdjustment.Status.READY);
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> partialAdjustments.update(ready.completeResources(at)))).isInstanceOf(io.agentflow.common.DomainException.class);
        assertThat(partialAdjustments.latestCompleted("demo", report.id())).isEmpty(); assertThat(resourceVersions(report)).isEqualTo(versions);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_partial_adjustment_return WHERE tenant_id='demo' AND adjustment_id=? AND completed_at IS NOT NULL", Integer.class, value.id().toString())).isZero();
    }

    @Test void partialAdjustmentChangedBankBlocksNewSendingButNotOriginalResultQueries() throws Exception {
        var value = partialAdjustment(); tx().executeWithoutResult(status -> partialAdjustments.create(value));
        var at = adjustmentTime(); var budgetQueued = value.authorizeBudget(partialBudget(value, at), at);
        tx().executeWithoutResult(status -> partialAdjustments.update(budgetQueued));
        var financial = value.input().basis().funding().financial();
        var command = ExpenseAccrualReductionCommand.forExpense(UUID.randomUUID(), value.id(), financial, null, budgetQueued.budget().input().command().period(),
                "finance", "accrual-proof", "独立挂账差额", at, at.plusSeconds(120));
        var accrual = ExpenseAccrualReductionOperation.queue(new ExpenseAccrualReductionOperation.Input(financial.accrual().version(), command, financial.accrual().input().targetDigest()), at);
        var queued = budgetQueued.authorizeAccrual(accrual, at);
        tx().executeWithoutResult(status -> partialAdjustments.update(queued));
        var bankId = value.input().basis().funding().payment().input().command().id();
        var bank = paymentOperations.find("demo", bankId).orElseThrow();
        tx().executeWithoutResult(status -> paymentExecution.query("demo", bankId, bank.version(), Instant.now()));
        var blockedAt = adjustmentTime(); var candidate = queued.withBudget(queued.budget().claim(blockedAt, java.time.Duration.ofSeconds(30)), blockedAt);
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> partialAdjustments.update(candidate))).isInstanceOf(io.agentflow.common.DomainException.class);
        var blockedAccrual = queued.withAccrual(queued.accrual().claim(blockedAt, java.time.Duration.ofSeconds(30)), blockedAt);
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> partialAdjustments.update(blockedAccrual))).isInstanceOf(io.agentflow.common.DomainException.class);
        assertThat(partialAdjustments.find("demo", value.id())).contains(queued);
        paymentWorker.poll();
        var sendAt = adjustmentTime(); var running = queued.withBudget(queued.budget().claim(sendAt, java.time.Duration.ofSeconds(30)), sendAt);
        tx().executeWithoutResult(status -> partialAdjustments.update(running));
        var unknown = running.withBudget(running.budget().unavailable(BudgetConsumptionReductionOperation.Failure.TIMEOUT, sendAt), sendAt);
        tx().executeWithoutResult(status -> partialAdjustments.update(unknown));
        var accrualRunning = unknown.withAccrual(unknown.accrual().claim(sendAt, java.time.Duration.ofSeconds(30)), sendAt);
        tx().executeWithoutResult(status -> partialAdjustments.update(accrualRunning));
        var bothUnknown = accrualRunning.withAccrual(accrualRunning.accrual().unavailable(ExpenseAccrualReductionOperation.Failure.TIMEOUT, sendAt), sendAt);
        tx().executeWithoutResult(status -> partialAdjustments.update(bothUnknown));
        var refreshed = paymentOperations.find("demo", bankId).orElseThrow();
        tx().executeWithoutResult(status -> paymentExecution.query("demo", bankId, refreshed.version(), Instant.now()));
        var queryAt = bothUnknown.budget().nextAttemptAt(); var querying = bothUnknown.withBudget(bothUnknown.budget().claim(queryAt, java.time.Duration.ofSeconds(30)), queryAt);
        tx().executeWithoutResult(status -> partialAdjustments.update(querying));
        var queryingBoth = querying.withAccrual(querying.accrual().claim(queryAt, java.time.Duration.ofSeconds(30)), queryAt);
        tx().executeWithoutResult(status -> partialAdjustments.update(queryingBoth));
        assertThat(partialAdjustments.find("demo", value.id())).contains(queryingBoth);
        assertThat(queryingBoth.budget().status()).isEqualTo(BudgetConsumptionReductionOperation.Status.QUERYING);
        assertThat(queryingBoth.accrual().status()).isEqualTo(ExpenseAccrualReductionOperation.Status.QUERYING);
        assertThat(queryingBoth.budget().input()).isEqualTo(queued.budget().input());
        assertThat(queryingBoth.accrual().input()).isEqualTo(queued.accrual().input());
    }

    @Test void partialCompletionAtomicallyReducesOnlyThisDifferenceAndPreservesOriginalFinance() throws Exception {
        var ready = readyPartialAdjustment(partialAdjustment()); var report = reports.find("demo", ready.input().basis().reportId()).orElseThrow();
        var original = partialAdjustmentSources.current(ready.input().basis().funding()); var candidate = partialCandidate(ready);
        var start = new java.util.concurrent.CountDownLatch(1); var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var jobs = java.util.stream.IntStream.range(0, 2).mapToObj(index -> pool.submit(() -> { start.await(); partialAdjustmentExecution.apply(candidate); return true; })).toList();
            start.countDown(); for (var job : jobs) assertThat(job.get(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally { pool.shutdownNow(); }
        var completed = partialAdjustments.find("demo", ready.id()).orElseThrow();
        assertThat(completed.status()).isEqualTo(ExpensePartialAdjustment.Status.APPLIED);
        assertThat(completed.completion().budget()).isEqualTo(ready.budget().observation());
        assertThat(completed.completion().accrual()).isEqualTo(ready.accrual().observation());
        assertThat(partialAdjustments.active("demo", report.id())).isEmpty();
        assertThat(partialAdjustments.latestCompleted("demo", report.id())).contains(completed);
        var loaded = financialResources.loadReserved(report); var prior = ExpenseRequest.restore(loaded.requests().values().iterator().next());
        var advance = EmployeeAdvance.restore(loaded.advances().values().iterator().next()); var invoice = Invoice.restore(loaded.invoices().values().iterator().next());
        assertThat(prior.balance(1).grossConsumed()).isEqualTo(money("100")); assertThat(prior.balance(1).consumed()).isEqualTo(money("80"));
        assertThat(prior.balance(1).reductions()).hasSize(1); assertThat(advance.balance().consumed()).isEqualTo(money("50"));
        assertThat(invoice.occupation()).isEqualTo(Invoice.Occupation.CONSUMED); assertThat(invoice.reversals()).isEmpty();
        assertThat(partialAdjustmentSources.current(ready.input().basis().funding())).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finance_consumption_reduction WHERE tenant_id='demo' AND adjustment_id=?", Integer.class, ready.id().toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_partial_adjustment_return WHERE tenant_id='demo' AND adjustment_id=? AND completed_at IS NOT NULL", Integer.class, ready.id().toString())).isEqualTo(1);
        var versions = resourceVersions(report); partialAdjustmentExecution.apply(candidate);
        assertThat(resourceVersions(report)).isEqualTo(versions); assertThat(partialAdjustments.find("demo", ready.id())).contains(completed);
    }

    @Test void partialCompletionFinalResourceFailureRollsBackInvoiceQuotaAndBankCompletion() throws Exception {
        var ready = readyPartialAdjustment(partialAdjustment("0", "50")); var report = reports.find("demo", ready.input().basis().reportId()).orElseThrow();
        var versions = resourceVersions(report); var candidate = partialCandidate(ready);
        jdbc.update("UPDATE finance_amount_use SET amount=49 WHERE tenant_id='demo' AND report_id=? AND resource_type='ADVANCE' AND status='CONSUMED'", report.id().toString());
        assertThatThrownBy(() -> partialAdjustmentExecution.apply(candidate)).isInstanceOf(io.agentflow.common.DomainException.class);
        assertThat(resourceVersions(report)).isEqualTo(versions); assertThat(partialAdjustments.find("demo", ready.id())).contains(ready);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finance_consumption_reduction WHERE tenant_id='demo' AND adjustment_id=?", Integer.class, ready.id().toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_partial_adjustment_return WHERE tenant_id='demo' AND adjustment_id=? AND completed_at IS NOT NULL", Integer.class, ready.id().toString())).isZero();
        jdbc.update("UPDATE finance_amount_use SET amount=50 WHERE tenant_id='demo' AND report_id=? AND resource_type='ADVANCE' AND status='CONSUMED'", report.id().toString());
        partialAdjustmentExecution.apply(candidate);
        var loaded = financialResources.loadReserved(report); var invoice = Invoice.restore(loaded.invoices().values().iterator().next());
        assertThat(invoice.occupation()).isEqualTo(Invoice.Occupation.AVAILABLE); assertThat(invoice.verification()).isEqualTo(Invoice.Verification.PENDING);
        assertThat(ExpenseRequest.restore(loaded.requests().values().iterator().next()).balance(1).consumed()).isEqualTo(money("0"));
        assertThat(EmployeeAdvance.restore(loaded.advances().values().iterator().next()).outstanding()).isEqualTo(money("200"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finance_consumption_reduction WHERE tenant_id='demo' AND adjustment_id=?", Integer.class, ready.id().toString())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invoice_active_claim WHERE tenant_id='demo' AND invoice_id=?", Integer.class, invoice.id().toString())).isZero();
    }

    @Test void partialCompletionRevisionFailureRollsBackEveryEffectAndCannotBeForged() throws Exception {
        var ready = readyPartialAdjustment(partialAdjustment()); var report = reports.find("demo", ready.input().basis().reportId()).orElseThrow(); var versions = resourceVersions(report);
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> partialAdjustments.completeResources(ready.completeResources(adjustmentTime()))))
                .isInstanceOf(io.agentflow.common.DomainException.class);
        jdbc.update("INSERT INTO expense_partial_adjustment_revision(tenant_id,adjustment_id,version,state_json) VALUES('demo',?,?,'{}')", ready.id().toString(), ready.version()+1);
        assertThatThrownBy(() -> partialAdjustmentExecution.apply(partialCandidate(ready))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(resourceVersions(report)).isEqualTo(versions); assertThat(partialAdjustments.find("demo", ready.id())).contains(ready);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_partial_adjustment_completion WHERE tenant_id='demo' AND adjustment_id=?", Integer.class, ready.id().toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finance_consumption_reduction WHERE tenant_id='demo' AND adjustment_id=?", Integer.class, ready.id().toString())).isZero();
        jdbc.update("DELETE FROM expense_partial_adjustment_revision WHERE tenant_id='demo' AND adjustment_id=? AND version=?", ready.id().toString(), ready.version()+1);
        partialAdjustmentExecution.apply(partialCandidate(ready)); assertThat(partialAdjustments.find("demo", ready.id()).orElseThrow().status()).isEqualTo(ExpensePartialAdjustment.Status.APPLIED);
    }

    @Test void partialCompletionConsecutiveAdjustmentsUseOnlyNewReturnsAndExactCompletedRemainders() throws Exception {
        var firstReady = readyPartialAdjustment(partialAdjustment()); partialAdjustmentExecution.apply(partialCandidate(firstReady));
        var first = partialAdjustments.find("demo", firstReady.id()).orElseThrow();
        var secondReady = readyPartialAdjustment(nextPartialAdjustment(first, "60", "20")); partialAdjustmentExecution.apply(partialCandidate(secondReady));
        var second = partialAdjustments.find("demo", secondReady.id()).orElseThrow();
        assertThat(second.input().basis().previous().id()).isEqualTo(first.id());
        assertThat(second.input().basis().funding().previousReturns()).containsExactlyElementsOf(first.input().basis().usedReturns());
        var finalIntent = nextPartialAdjustment(second, "0", "10");
        var heldFirst = first.requireReview("OLDER_COMPLETION_RECHECK", adjustmentTime()); tx().executeWithoutResult(status -> partialAdjustments.update(heldFirst));
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> partialAdjustments.create(finalIntent))).isInstanceOf(io.agentflow.common.DomainException.class);
        tx().executeWithoutResult(status -> partialAdjustments.update(heldFirst.confirmCurrent(adjustmentTime())));
        var finalReady = readyPartialAdjustment(finalIntent); partialAdjustmentExecution.apply(partialCandidate(finalReady));
        var completed = partialAdjustments.find("demo", finalReady.id()).orElseThrow();
        assertThat(completed.status()).isEqualTo(ExpensePartialAdjustment.Status.APPLIED);
        assertThat(completed.input().basis().funding().financial().change().before().gross()).isEqualTo(money("60"));
        assertThat(completed.input().basis().usedReturns()).hasSize(3);
        var report = reports.find("demo", completed.input().basis().reportId()).orElseThrow(); var loaded = financialResources.loadReserved(report);
        var prior = ExpenseRequest.restore(loaded.requests().values().iterator().next());
        assertThat(prior.balance(1).consumed()).isEqualTo(money("0")); assertThat(prior.balance(1).grossConsumed()).isEqualTo(money("100"));
        assertThat(prior.balance(1).reductions()).extracting(ReservedAmount.ConsumptionReduction::amount).containsExactly(money("20"), money("20"), money("60"));
        assertThat(EmployeeAdvance.restore(loaded.advances().values().iterator().next()).outstanding()).isEqualTo(money("200"));
        assertThat(Invoice.restore(loaded.invoices().values().iterator().next()).reversals()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_partial_adjustment_return WHERE tenant_id='demo' AND report_id=? AND completed_at IS NOT NULL", Integer.class, report.id().toString())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_partial_adjustment_completion c JOIN expense_partial_adjustment a ON a.tenant_id=c.tenant_id AND a.id=c.adjustment_id WHERE a.tenant_id='demo' AND a.report_id=?", Integer.class, report.id().toString())).isEqualTo(3);
        assertThat(partialAdjustments.revision("demo", first.id(), first.version())).contains(first);
    }

    @Test void partialCompletionPredecessorReviewBlocksNewEffectsUntilOriginalCompletionIsConfirmed() throws Exception {
        var firstReady = readyPartialAdjustment(partialAdjustment()); partialAdjustmentExecution.apply(partialCandidate(firstReady));
        var first = partialAdjustments.find("demo", firstReady.id()).orElseThrow(); var second = nextPartialAdjustment(first, "60", "20");
        tx().executeWithoutResult(status -> partialAdjustments.create(second));
        var held = first.requireReview("ORIGINAL_RECHECK_REQUIRED", adjustmentTime()); tx().executeWithoutResult(status -> partialAdjustments.update(held));
        var at = adjustmentTime(); var queued = second.authorizeBudget(partialBudget(second, at), at);
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> partialAdjustments.update(queued))).isInstanceOf(io.agentflow.common.DomainException.class);
        assertThat(partialAdjustments.find("demo", second.id())).contains(second);
        var confirmed = held.confirmCurrent(adjustmentTime()); tx().executeWithoutResult(status -> partialAdjustments.update(confirmed));
        assertThat(confirmed.completion()).isEqualTo(first.completion());
        tx().executeWithoutResult(status -> partialAdjustments.update(queued));
        assertThat(partialAdjustments.find("demo", second.id())).contains(queued);
    }

    @Test void partialCompletionZeroPayableRestoresOffsetsWithoutCreatingBankEvidence() throws Exception {
        advanceOffset = "100"; var report = paymentReport(true); settlementWorker.poll(); budgetWorker.poll(); report = current(report);
        var before = ExpenseAdjustmentAmounts.from(report); var first = before.lines().get(0);
        var change = before.reduce(List.of(new ExpenseReport.Reduction(first.lineNo(), money("80"), first.tax())));
        var funding = partialAdjustmentSources.find("demo", change, List.of(), List.of());
        var initial = ExpensePartialAdjustment.begin(new ExpensePartialAdjustment.Input(UUID.randomUUID(), ExpensePartialAdjustmentBasis.from(funding, null), "finance", "zero-payable", "仅恢复未使用借款抵扣", adjustmentTime()));
        var ready = readyPartialAdjustment(initial); partialAdjustmentExecution.apply(partialCandidate(ready));
        var loaded = financialResources.loadReserved(report); var advance = EmployeeAdvance.restore(loaded.advances().values().iterator().next());
        assertThat(advance.balance().grossConsumed()).isEqualTo(money("100")); assertThat(advance.balance().consumed()).isEqualTo(money("80"));
        assertThat(advance.outstanding()).isEqualTo(money("120")); assertThat(paymentWrites).isZero();
        assertThat(expenseReturnLedgers.find("demo", report.id())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_partial_adjustment_return WHERE tenant_id='demo' AND adjustment_id=?", Integer.class, ready.id().toString())).isZero();
        assertThat(partialAdjustments.find("demo", ready.id()).orElseThrow().status()).isEqualTo(ExpensePartialAdjustment.Status.APPLIED);
    }

    @Test void partialCompletionRejectsWrongDifferenceAndUnrelatedResourceChanges() throws Exception {
        var ready = readyPartialAdjustment(partialAdjustment()); var report = reports.find("demo", ready.input().basis().reportId()).orElseThrow(); var versions = resourceVersions(report);
        var resource = ExpenseRequest.restore(financialResources.loadReserved(report).requests().values().iterator().next());
        resource.reduceConsumption(resource.version(), 1, new ExpenseUse(report.id(), 1, 1), money("30"), ready.id(), adjustmentTime());
        assertThatThrownBy(() -> requests.update(resource, resource.version()-1, "finance", "REDUCE_CONSUMPTION")).isInstanceOf(io.agentflow.common.DomainException.class);
        var plan = new ExpenseResourceReduction().plan(ready.input().basis().funding().financial().change(), financialResources.loadReserved(report), ready.id(), adjustmentTime());
        var prior = plan.requests().get(0).after();
        var changed = new ExpenseRequest.State(prior.id(), prior.tenantId(), prior.applicationId(), prior.legalEntityId(), prior.employeeId(), prior.approvedLines(), prior.balances(), !prior.closed(), prior.version());
        assertThatThrownBy(() -> requests.update(ExpenseRequest.restore(changed), changed.version()-1, "finance", "REDUCE_CONSUMPTION")).isInstanceOf(io.agentflow.common.DomainException.class);
        assertThat(resourceVersions(report)).isEqualTo(versions); assertThat(partialAdjustments.find("demo", ready.id())).contains(ready);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finance_consumption_reduction WHERE tenant_id='demo' AND adjustment_id=?", Integer.class, ready.id().toString())).isZero();
    }

    private JdbcExpensePartialAdjustmentRepository.Candidate partialCandidate(ExpensePartialAdjustment value) {
        return new JdbcExpensePartialAdjustmentRepository.Candidate(value.input().basis().tenantId(), value.id(), value.input().basis().reportId(), value.version());
    }

    private ExpensePartialAdjustment nextPartialAdjustment(ExpensePartialAdjustment previous, String remaining, String returned) throws Exception {
        var report = reports.find("demo", previous.input().basis().reportId()).orElseThrow();
        var bankId = previous.input().basis().funding().payment().input().command().id();
        if (remaining.equals("0")) {
            paymentMode = "REVERSED"; var paid = paymentOperations.find("demo", bankId).orElseThrow();
            tx().executeWithoutResult(status -> paymentExecution.query("demo", bankId, paid.version(), Instant.now())); paymentWorker.poll();
            expenseReturnStatus = ExpensePaymentReturnPort.Status.RETURNED;
        }
        expenseReturnRevision++; var items = new java.util.ArrayList<>(expenseReturnRows);
        items.add(expenseReturnItem(report, UUID.randomUUID().toString(), returned)); expenseReturnRows = List.copyOf(items);
        registerExpenseReturn(report, queryExpenseReturn(report));
        var paid = paymentOperations.find("demo", bankId).orElseThrow();
        tx().executeWithoutResult(status -> paymentExecution.query("demo", bankId, paid.version(), Instant.now())); paymentWorker.poll();
        var original = voucherOperations.find("demo", previous.input().basis().funding().financial().accrual().input().command().id()).orElseThrow();
        tx().executeWithoutResult(status -> voucherExecution.query("demo", original.input().command().id(), original.version(), Instant.now())); voucherWorker.poll();
        var before = previous.input().basis().funding().financial().change().after(); var first = before.lines().get(0);
        var change = before.reduce(List.of(new ExpenseReport.Reduction(first.lineNo(), money(remaining), remaining.equals("0") ? money("0") : first.tax())));
        var prior = previous.input().basis().usedReturns();
        var selected = expenseReturnLedgers.find("demo", report.id()).orElseThrow().entries().stream().filter(value -> !prior.contains(value)).toList();
        var funding = partialAdjustmentSources.find("demo", change, prior, selected);
        return ExpensePartialAdjustment.begin(new ExpensePartialAdjustment.Input(UUID.randomUUID(), ExpensePartialAdjustmentBasis.from(funding, previous), "finance", "next-partial", "继续按实际剩余额取消", adjustmentTime()));
    }

    private ExpensePartialAdjustment readyPartialAdjustment(ExpensePartialAdjustment value) {
        tx().executeWithoutResult(status -> partialAdjustments.create(value));
        var at = adjustmentTime(); var budgetQueued = value.authorizeBudget(partialBudget(value, at), at);
        tx().executeWithoutResult(status -> partialAdjustments.update(budgetQueued));
        var financial = value.input().basis().funding().financial(); var period = budgetQueued.budget().input().command().period();
        var command = ExpenseAccrualReductionCommand.forExpense(UUID.randomUUID(), value.id(), financial, value.input().basis().previous() == null ? null : value.input().basis().previous().accrual(), period, "finance", "accrual-proof", "本次独立挂账差额", at, at.plusSeconds(120));
        var operation = ExpenseAccrualReductionOperation.queue(new ExpenseAccrualReductionOperation.Input(financial.accrual().version(), command, financial.accrual().input().targetDigest()), at);
        var both = budgetQueued.authorizeAccrual(operation, at); tx().executeWithoutResult(status -> partialAdjustments.update(both));
        var budgetRunning = both.withBudget(both.budget().claim(at, java.time.Duration.ofSeconds(30)), at);
        tx().executeWithoutResult(status -> partialAdjustments.update(budgetRunning));
        var budgetCommand = both.budget().input().command();
        var budgetProof = new BudgetConsumptionReductionObservation.Posting(budgetCommand.source().id(), budgetCommand.source().digest(), budgetCommand.consumed().reference(), budgetCommand.expected().revision()+1,
                "partial-budget-" + value.id(), budgetCommand.beforeDigest(), budgetCommand.afterDigest(), budgetCommand.reducedAmount(), period.periodReference(), period.request().accountingDate(), at);
        var budgetResult = new BudgetConsumptionReductionObservation(budgetCommand.id(), value.id(), budgetCommand.digest(), BudgetConsumptionReductionObservation.Status.APPLIED, at, budgetProof, null);
        var budgetApplied = budgetRunning.withBudget(budgetRunning.budget().complete(new FinanceResult.Success<>(budgetResult), at), at);
        tx().executeWithoutResult(status -> partialAdjustments.update(budgetApplied));
        var accrualRunning = budgetApplied.withAccrual(budgetApplied.accrual().claim(at, java.time.Duration.ofSeconds(30)), at);
        tx().executeWithoutResult(status -> partialAdjustments.update(accrualRunning));
        var original = command.source().original();
        var currentOriginal = new VoucherObservation(original.operationId(), original.commandDigest(), original.status(), original.revision(), at,
                original.postingReference(), original.voucherReference(), original.periodReference(), original.accountingDate(), original.debitTotal(), original.creditTotal(), original.postedAt(), null);
        var lines = command.lines().stream().map(line -> new VoucherReversalPort.Line("partial-entry-"+line.originalLineNo(), line.originalLineNo(), line.accountCode(), line.side(), line.amount(), line.sourceLineNo(), line.costCenter(), line.projectCode(), line.advanceId())).toList();
        var voucher = new VoucherReversalPort.Posting("partial-posting-"+value.id(), "partial-voucher-"+value.id(), period.periodReference(), period.request().accountingDate(), at, lines);
        var proof = new ExpenseAccrualReductionObservation.Posting(currentOriginal, command.expectedAdjustmentRevision() + 1, command.beforeDigest(), command.afterDigest(), voucher);
        var result = new ExpenseAccrualReductionObservation(command.id(), value.id(), command.digest(), ExpenseAccrualReductionObservation.Status.POSTED, 1, at, "partial-acceptance", proof, null);
        var ready = accrualRunning.withAccrual(accrualRunning.accrual().complete(new FinanceResult.Success<>(result), at), at);
        tx().executeWithoutResult(status -> partialAdjustments.update(ready));
        return ready;
    }

    private ExpensePartialAdjustment partialAdjustment() throws Exception { return partialAdjustment("80", "20"); }
    private ExpensePartialAdjustment partialAdjustment(String remaining, String returned) throws Exception {
        var report = archiveReadyExpense();
        if (returned.equals("50")) {
            paymentMode = "REVERSED";
            var paid = paymentOperations.find("demo", settlements.find("demo", report.id()).orElseThrow().input().payment().operationId()).orElseThrow();
            tx().executeWithoutResult(status -> paymentExecution.query("demo", paid.input().command().id(), paid.version(), Instant.now())); paymentWorker.poll();
        }
        expenseReturnStatus = returned.equals("50") ? ExpensePaymentReturnPort.Status.RETURNED : ExpensePaymentReturnPort.Status.PARTIALLY_RETURNED;
        expenseReturnRows = List.of(expenseReturnItem(report, UUID.randomUUID().toString(), returned));
        registerExpenseReturn(report, queryExpenseReturn(report)); report = current(report);
        var paymentId = settlements.find("demo", report.id()).orElseThrow().input().payment().operationId();
        var payment = paymentOperations.find("demo", paymentId).orElseThrow();
        tx().executeWithoutResult(status -> paymentExecution.query("demo", paymentId, payment.version(), Instant.now())); paymentWorker.poll();
        var before = ExpenseAdjustmentAmounts.from(report); var first = before.lines().get(0);
        var change = before.reduce(List.of(new ExpenseReport.Reduction(first.lineNo(), money(remaining), remaining.equals("0") ? money("0") : first.tax())));
        var selected = expenseReturnLedgers.find("demo", report.id()).orElseThrow().entries();
        var funding = partialAdjustmentSources.find("demo", change, List.of(), selected);
        return ExpensePartialAdjustment.begin(new ExpensePartialAdjustment.Input(UUID.randomUUID(), ExpensePartialAdjustmentBasis.from(funding, null), "finance", "partial-return-proof", "独立部分取消原报销", adjustmentTime()));
    }
    private BudgetConsumptionReductionOperation partialBudget(ExpensePartialAdjustment value, Instant at) {
        var financial = value.input().basis().funding().financial(); var original = financial.consumption(); var date = LocalDate.now();
        var period = new AccountingPeriodPort.OpenPeriod(new AccountingPeriodPort.Request(financial.accrual().input().command().legalEntityId(), "CNY", date), "partial-period", "v1", date, date, at, at.plusSeconds(300));
        var command = BudgetConsumptionReductionCommand.forExpense(UUID.randomUUID(), value.id(), financial, value.input().basis().previous() == null ? null : value.input().basis().previous().budget(), period,
                "finance", "budget-proof", "独立释放本次差额", at, at.plusSeconds(120));
        return BudgetConsumptionReductionOperation.queue(new BudgetConsumptionReductionOperation.Input(original.version(), command, original.input().targetDigest()), at);
    }

    private ExpenseReport resourceAdjustmentReport() throws Exception { var report = archiveReadyExpense(); prepareResourceAdjustmentSources(report); return current(report); }
    private void prepareResourceAdjustmentSources(ExpenseReport report) throws Exception {
        var payment = paymentOperations.find("demo", settlements.find("demo", report.id()).orElseThrow().input().payment().operationId()).orElseThrow();
        paymentMode = "REVERSED"; tx().executeWithoutResult(status -> paymentExecution.query("demo", payment.input().command().id(), payment.version(), Instant.now())); paymentWorker.poll();
        expenseReturnStatus = ExpensePaymentReturnPort.Status.RETURNED; expenseReturnRows = List.of(expenseReturnItem(report, UUID.randomUUID().toString(), "50"));
        registerExpenseReturn(report, queryExpenseReturn(report));
        var accrual = reversedVoucher(report, VoucherCommand.Kind.EXPENSE_ACCRUAL); var checked = checkedReversal(report, accrual);
        ok(send(reversalPath(accrual) + "/records", "finance", reversalRecordInput(report, accrual, checked)), 202);
    }
    private ExpenseResourceAdjustmentPreparation readyResourceAdjustment(ExpenseReport report) {
        var basis = resourceAdjustmentSources.find("demo", report.id());
        var prepared = ExpenseResourceAdjustmentPreparation.queue(new ExpenseResourceAdjustmentPreparation.Input(UUID.randomUUID(), basis, LocalDate.now(), "finance", "full-cancellation", "完整取消已核销报销", adjustmentTime()));
        tx().executeWithoutResult(status -> resourcePreparations.create(prepared)); var claimed = prepared.claim(adjustmentTime(), java.time.Duration.ofSeconds(30));
        tx().executeWithoutResult(status -> resourcePreparations.update(claimed));
        var period = accountingPeriods.period("demo", basis.consumption().input().targetDigest(), prepared.input().periodRequest());
        assertThat(period).isInstanceOf(FinanceResult.Success.class); var ready = claimed.ready(((FinanceResult.Success<AccountingPeriodPort.OpenPeriod>) period).value(), adjustmentTime());
        tx().executeWithoutResult(status -> resourcePreparations.update(ready)); return ready;
    }
    private ExpenseResourceAdjustment authorizeResourceAdjustment(ExpenseResourceAdjustmentPreparation prepared) {
        return tx().execute(status -> {
            var authorized = prepared.authorize(adjustmentTime()); resourcePreparations.update(authorized);
            var adjustment = ExpenseResourceAdjustment.begin(authorized.authorizedInput()); resourceAdjustments.create(adjustment, authorized.version());
            budgetReversals.create(BudgetConsumptionReversalOperation.queue(adjustment.input().budget(), authorized.updatedAt())); return adjustment;
        });
    }
    private static Instant adjustmentTime() { return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS); }

    private ExpensePaymentReturnCheck queueExpenseReturn(ExpenseReport report) {
        var ledger = expenseReturnLedgers.find("demo", report.id()).orElse(null); var settlement = settlements.find("demo", report.id()).orElseThrow();
        var action = asFinance(() -> expenseReturns.queue(report.id(), new ExpensePaymentReturnService.QueryInput(settlement.version(), ledger == null ? 0 : ledger.version(), "核对原报销银行退回")));
        return expenseReturnChecks.find("demo", action.checkId()).orElseThrow();
    }
    private ExpensePaymentReturnCheck queryExpenseReturn(ExpenseReport report) {
        var queued = queueExpenseReturn(report); expenseReturnWorker.poll(); return expenseReturnChecks.find("demo", queued.input().id()).orElseThrow();
    }
    private ExpensePaymentReturnService.RegisterInput expenseReturnInput(ExpenseReport report, ExpensePaymentReturnCheck check) {
        return new ExpensePaymentReturnService.RegisterInput(settlements.find("demo", report.id()).orElseThrow().version(), expenseReturnLedgers.find("demo", report.id()).orElseThrow().version(),
                check.input().id(), check.version(), check.receipt().status(), "EXPENSE-BANK-RETURN", "核对独立入款和员工应付贷方");
    }
    private ExpensePaymentReturnService.ActionReceipt registerExpenseReturn(ExpenseReport report, ExpensePaymentReturnCheck check) {
        var input = expenseReturnInput(report, check); return asFinance(() -> expenseReturns.register(report.id(), input));
    }
    private ExpensePaymentReturnPort.ReturnItem expenseReturnItem(ExpenseReport report, String id, String amount) {
        var request = expenseReturnSources.find("demo", report.id()).request(); var at = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        return new ExpensePaymentReturnPort.ReturnItem(new ExpensePaymentReturnPort.BankReceipt("expense-bank-" + id, money(amount), at),
                new ExpensePaymentReturnPort.PayableCredit("expense-return-voucher-" + id, "credit", request.payableAccountCode(), money(amount), LocalDate.now(), at));
    }
    private ExpensePaymentReturnPort.Receipt expenseReturnReceipt(ExpensePaymentReturnPort.Request request) {
        var current = paymentOperations.find("demo", request.command().id()).orElseThrow().observation(); var now = Instant.now();
        var observed = new PaymentObservation(current.authorizationId(), current.commandDigest(), current.status(), current.revision(), now,
                current.paymentReference(), current.paidAmount(), current.accountDigest(), current.completedAt(), current.receiptReference(), current.failure());
        return new ExpensePaymentReturnPort.Receipt(request, expenseReturnStatus, expenseReturnRevision, now, now.plusSeconds(300), observed, expenseReturnRows);
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

    @Test void explicitPaymentDisputeResolutionRestoresOriginalSettlementWithoutConsumingResourcesAgain() throws Exception {
        var report = paidExpense(); settlementWorker.poll(); var original = settlements.find("demo", report.id()).orElseThrow();
        var versions = resourceVersions(report); var id = original.input().payment().operationId();
        var paid = paymentOperations.find("demo", id).orElseThrow(); var receipt = paid.observation();
        tx().executeWithoutResult(status -> paymentExecution.query("demo", id, paid.version(), Instant.now())); paymentMode = "REVERSED"; paymentWorker.poll();
        budgetWorker.poll(); assertThat(settlements.find("demo", report.id()).orElseThrow().status()).isEqualTo(ExpenseSettlement.Status.REVIEW_REQUIRED);
        var reversed = paymentOperations.find("demo", id).orElseThrow();
        tx().executeWithoutResult(status -> paymentExecution.query("demo", id, reversed.version(), Instant.now()));
        var checking = paymentExecution.claim("demo", id, Instant.now()); var observedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        paymentExecution.finish(checking, new FinanceResult.Success<>(new PaymentObservation(id, receipt.commandDigest(), PaymentObservation.Status.SUCCEEDED,
                3L, observedAt, receipt.paymentReference(), receipt.paidAmount(), receipt.accountDigest(), receipt.completedAt(), receipt.receiptReference(), null)), observedAt);
        var disputed = paymentOperations.find("demo", id).orElseThrow(); assertThat(disputed.status()).isEqualTo(PaymentOperation.Status.RECONCILING);
        var input = Map.of("authorizationVersion", 2, "operationVersion", disputed.version(), "outcome", "SUCCEEDED", "evidenceReference", "BANK-RECONCILIATION-001", "comment", "核对银行原交易，退回通知已更正，原到账回执仍有效");
        ok(send("/api/v1/payments/" + id + "/dispute-resolutions", "finance", input), 202);
        var restored = settlements.find("demo", report.id()).orElseThrow(); assertThat(restored.status()).isEqualTo(ExpenseSettlement.Status.SETTLED);
        assertThat(restored.input()).isEqualTo(original.input()); assertThat(restored.budgetOperationId()).isEqualTo(original.budgetOperationId());
        assertThat(resourceVersions(report)).isEqualTo(versions); settlementWorker.poll(); budgetWorker.poll();
        assertThat(resourceVersions(report)).isEqualTo(versions); assertThat(paymentWrites).isEqualTo(1);
    }

    @Test void disputeResolutionRequiresStrictInputAndCurrentFinanceIncludingIdempotentReplay() throws Exception {
        var report = paidExpense(); var disputed = correctedExpensePayment(report); var id = disputed.input().command().id();
        var input = disputeInput(disputed); String path = "/api/v1/payments/" + id + "/dispute-resolutions", key = UUID.randomUUID().toString();
        assertThat(ok(read(paymentPath(report), "finance"), 200).at("/dispute/canResolve").asBoolean()).isTrue();
        assertThat(ok(read(paymentPath(report), "alice"), 200).path("dispute").isNull()).isTrue();
        for (String user : List.of("alice", "admin", "cashier", "manager")) assertThat(send(path, user, input).getStatus()).isIn(403, 404);
        var forged = new HashMap<>(input); forged.put("paidAmount", "999.00"); assertThat(send(path, "finance", forged).getStatus()).isEqualTo(400);
        var invalid = new HashMap<>(input); invalid.put("outcome", "NOT_FOUND"); assertThat(send(path, "finance", invalid).getStatus()).isEqualTo(400);
        invalid.put("outcome", "SUCCEEDED"); invalid.put("evidenceReference", "   "); assertThat(send(path, "finance", invalid).getStatus()).isEqualTo(400);
        invalid.put("evidenceReference", "BANK\nFAKE"); assertThat(send(path, "finance", invalid).getStatus()).isEqualTo(400);
        var stale = new HashMap<>(input); stale.put("operationVersion", disputed.version() - 1); assertCode(send(path, "finance", stale), "CONCURRENCY_CONFLICT");
        var first = send(path, "finance", key, input); ok(first, 202); assertThat(first.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(send(path, "finance", key, input).getContentAsString()).isEqualTo(first.getContentAsString());
        assertCode(send(path, "finance", input), "CONCURRENCY_CONFLICT");
        assertThat(paymentOperations.revision("demo", id, disputed.version())).contains(disputed);
        assertThat(disputeDecisions.latest("demo", id).orElseThrow().observation().observedAt()).isEqualTo(disputed.conflictingObservation().observedAt());
        assertThat(ok(read(paymentPath(report), "finance"), 200).at("/dispute/latest/outcome").asText()).isEqualTo("SUCCEEDED");
        assertThat(disputeDecisions.latest("foreign", id)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_dispute_resolution WHERE tenant_id='demo' AND payment_id=?", Integer.class, id.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='PAYMENT_DISPUTE_RESOLVE'", Integer.class, report.applicationId().toString())).isEqualTo(1);
        var person = organizationRepository.person("demo", finance).orElseThrow();
        var inactive = organization.updatePerson(admin, finance, person.displayName(), false, person.approvalEligible(), person.revision());
        try { assertThat(send(path, "finance", key, input).getStatus()).isIn(403, 404); }
        finally { organization.updatePerson(admin, finance, person.displayName(), person.active(), person.approvalEligible(), inactive.revision()); }
        assertThat(settlements.find("demo", report.id()).orElseThrow().status()).isEqualTo(ExpenseSettlement.Status.QUEUED);
        settlementWorker.poll(); budgetWorker.poll(); assertThat(settlements.find("demo", report.id()).orElseThrow().status()).isEqualTo(ExpenseSettlement.Status.SETTLED);
        assertThat(paymentWrites).isEqualTo(1);
    }

    @Test void resolutionRestoresPendingBudgetWithoutAnotherConsumptionAndPreservesAnIndependentVoucherHold() throws Exception {
        var report = paidExpense(); settlementWorker.poll(); var original = settlements.find("demo", report.id()).orElseThrow(); var versions = resourceVersions(report);
        var disputed = correctedExpensePayment(report);
        ok(send("/api/v1/payments/" + disputed.input().command().id() + "/dispute-resolutions", "finance", disputeInput(disputed)), 202);
        var restored = settlements.find("demo", report.id()).orElseThrow(); assertThat(restored.status()).isEqualTo(ExpenseSettlement.Status.BUDGET_PENDING);
        assertThat(restored.budgetOperationId()).isEqualTo(original.budgetOperationId()); budgetWorker.poll(); assertThat(resourceVersions(report)).isEqualTo(versions);
        var again = correctedExpensePayment(report); var voucher = ok(read(voucherPath(report), "finance"), 200); voucherMode = "REVERSED";
        ok(send(voucherPath(report) + "/actions", "finance", voucherInput(report, "QUERY", voucher.path("operation"))), 202); voucherWorker.poll();
        ok(send("/api/v1/payments/" + again.input().command().id() + "/dispute-resolutions", "finance", disputeInput(again)), 202);
        assertThat(settlements.find("demo", report.id()).orElseThrow().status()).isEqualTo(ExpenseSettlement.Status.REVIEW_REQUIRED);
        assertThat(resourceVersions(report)).isEqualTo(versions); assertThat(paymentWrites).isEqualTo(1);
    }

    @Test void concurrentDisputeDecisionsHaveOneWinnerAndTransactionRollbackRetainsEveryOriginalFact() throws Exception {
        var report = paidExpense(); var disputed = correctedExpensePayment(report); var id = disputed.input().command().id(); var before = settlements.find("demo", report.id()).orElseThrow();
        var input = new PaymentDisputeService.Input(2, disputed.version(), PaymentObservation.Status.SUCCEEDED, "BANK-ROLLBACK", "核对原回单");
        actors.set(new Actor("demo", "finance", Set.of("EMPLOYEE", "APPROVER", "FINANCE")));
        try { assertThatThrownBy(() -> tx().execute(status -> { disputes.resolve(id, input); throw new IllegalStateException("Synthetic failure after decision"); })).isInstanceOf(IllegalStateException.class); }
        finally { actors.clear(); }
        assertThat(paymentOperations.find("demo", id)).contains(disputed); assertThat(settlements.find("demo", report.id())).contains(before); assertThat(disputeDecisions.latest("demo", id)).isEmpty();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2); var gate = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<PaymentDisputeService.Receipt> decide = () -> { actors.set(new Actor("demo", "finance", Set.of("EMPLOYEE", "APPROVER", "FINANCE"))); try { gate.await(); return disputes.resolve(id, input); } finally { actors.clear(); } };
        int succeeded = 0, rejected = 0;
        try {
            var first = pool.submit(decide); var second = pool.submit(decide); gate.countDown();
            for (var future : List.of(first, second)) {
                try { future.get(10, java.util.concurrent.TimeUnit.SECONDS); succeeded++; }
                catch (java.util.concurrent.ExecutionException failure) { assertThat(failure.getCause()).isInstanceOf(io.agentflow.common.DomainException.class); rejected++; }
            }
        } finally { pool.shutdownNow(); }
        assertThat(succeeded).isEqualTo(1); assertThat(rejected).isEqualTo(1);
        assertThat(paymentOperations.find("demo", id).orElseThrow().version()).isEqualTo(disputed.version() + 1);
        assertThat(settlements.find("demo", report.id()).orElseThrow().version()).isEqualTo(before.version() + 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_dispute_resolution WHERE tenant_id='demo' AND payment_id=?", Integer.class, id.toString())).isEqualTo(1);
    }

    @Test void overwrittenConflictingFundingStillPreventsFailedDecisionAndNewPaymentOccupation() throws Exception {
        var report = paymentReport(); var id = authorizePayment(report);
        ok(send("/api/v1/cashier/payments/" + id + "/actions", "cashier", cashierInput("EXECUTE", 1, null)), 202);
        paymentRequestWorker.poll(); paymentMode = "FAILED"; paymentWorker.poll(); var failed = paymentOperations.find("demo", id).orElseThrow();
        var command = failed.input().command(); var at = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        observePayment(failed, new PaymentObservation(id, command.digest(), PaymentObservation.Status.SUCCEEDED, 2L, at, failed.observation().paymentReference(), command.amount(), command.payee().accountDigest(), at, "conflicting-success", null));
        var conflicted = paymentOperations.find("demo", id).orElseThrow();
        observePayment(conflicted, new PaymentObservation(id, command.digest(), PaymentObservation.Status.FAILED, 3L, Instant.now(), failed.observation().paymentReference(), null, null, null, null, PaymentObservation.Failure.PAYMENT_REJECTED));
        var latest = paymentOperations.find("demo", id).orElseThrow(); var input = new HashMap<>(disputeInput(latest)); input.put("outcome", "FAILED");
        assertCode(send("/api/v1/payments/" + id + "/dispute-resolutions", "finance", input), "PAYMENT_DISPUTE_UNRESOLVABLE");
        assertThat(ok(read(paymentPath(report), "finance"), 200).at("/dispute/issue").asText()).isEqualTo("FUNDING_ALREADY_OBSERVED");
        assertCode(send(paymentPath(report) + "/authorizations", "finance", authorizationInput(report)), "PAYMENT_AUTHORIZATION_EXISTS");
        assertThat(paymentOperations.disputeEvidence("demo", id).fundingObserved()).isTrue(); assertThat(disputeDecisions.latest("demo", id)).isEmpty();
    }

    @Test void failedDecisionWithoutFundingStillRequiresSeparateRetirementBeforeAnotherAuthorization() throws Exception {
        var report = paymentReport(); var id = authorizePayment(report);
        ok(send("/api/v1/cashier/payments/" + id + "/actions", "cashier", cashierInput("EXECUTE", 1, null)), 202);
        paymentRequestWorker.poll(); paymentMode = "FAILED"; paymentWorker.poll(); var failed = paymentOperations.find("demo", id).orElseThrow(); var fact = failed.observation();
        observePayment(failed, new PaymentObservation(id, fact.commandDigest(), PaymentObservation.Status.PENDING, 2L, Instant.now(), fact.paymentReference(), null, null, null, null, null));
        observePayment(paymentOperations.find("demo", id).orElseThrow(), new PaymentObservation(id, fact.commandDigest(), PaymentObservation.Status.FAILED, 3L, Instant.now(), fact.paymentReference(), null, null, null, null, fact.failure()));
        var disputed = paymentOperations.find("demo", id).orElseThrow(); ok(send("/api/v1/payments/" + id + "/dispute-resolutions", "finance", disputeInput(disputed)), 202);
        assertCode(send(paymentPath(report) + "/authorizations", "finance", authorizationInput(report)), "PAYMENT_AUTHORIZATION_EXISTS");
        var resolved = paymentOperations.find("demo", id).orElseThrow(); assertThat(resolved.status()).isEqualTo(PaymentOperation.Status.FAILED);
        ok(send("/api/v1/payments/" + id + "/finance-actions", "finance", Map.of("action", "RETIRE", "authorizationVersion", 2, "operationVersion", resolved.version(), "comment", "明确失败后结束原占用")), 202);
        UUID next = authorizePayment(report); assertThat(next).isNotEqualTo(id); assertThat(paymentWrites).isEqualTo(1); assertThat(settlements.find("demo", report.id())).isEmpty();
    }

    @Test void reversedDecisionRetainsResourceConsumptionAndDoesNotReleaseOriginalPaymentOccupation() throws Exception {
        var report = paidExpense(); settlementWorker.poll(); budgetWorker.poll(); var versions = resourceVersions(report); var disputed = correctedExpensePayment(report); var fact = disputed.conflictingObservation();
        observePayment(disputed, new PaymentObservation(fact.authorizationId(), fact.commandDigest(), PaymentObservation.Status.REVERSED, fact.revision() + 1, Instant.now(), fact.paymentReference(), fact.paidAmount(), fact.accountDigest(), fact.completedAt(), "confirmed-return", null));
        var latest = paymentOperations.find("demo", fact.authorizationId()).orElseThrow();
        ok(send("/api/v1/payments/" + fact.authorizationId() + "/dispute-resolutions", "finance", disputeInput(latest)), 202);
        assertThat(paymentOperations.find("demo", fact.authorizationId()).orElseThrow().status()).isEqualTo(PaymentOperation.Status.REVERSED);
        assertThat(settlements.find("demo", report.id()).orElseThrow().status()).isEqualTo(ExpenseSettlement.Status.REVIEW_REQUIRED);
        assertThat(resourceVersions(report)).isEqualTo(versions); assertCode(send(paymentPath(report) + "/authorizations", "finance", authorizationInput(report)), "VOUCHER_BUDGET_NOT_FROZEN");
        assertThat(paymentAuthorizations.active("demo", BusinessReference.Type.EXPENSE, report.id())).isPresent();
    }

    @Test void successfulDecisionRestoresCurrentArchiveEvidenceWithoutRewritingSealedManifestOrOriginals() throws Exception {
        var report = archiveReadyExpense(); pollArchive(); var entry = archives.find("demo", report.id(), 1).orElseThrow(); var before = archiveDownload(report, "finance"); var versions = resourceVersions(report);
        var disputed = correctedExpensePayment(report);
        assertThat(ok(read(path(report) + "/archive", "finance"), 200).path("issue").asText()).isEqualTo("ARCHIVE_SETTLEMENT_REQUIRED");
        ok(send("/api/v1/payments/" + disputed.input().command().id() + "/dispute-resolutions", "finance", disputeInput(disputed)), 202); pollArchive();
        assertThat(ok(read(path(report) + "/archive", "finance"), 200).path("issue").isNull()).isTrue();
        assertThat(archives.find("demo", report.id(), 1).orElseThrow()).isEqualTo(entry); assertThat(archiveDownload(report, "finance")).isEqualTo(before);
        assertThat(resourceVersions(report)).isEqualTo(versions); assertThat(paymentWrites).isEqualTo(1);
    }

    @Test void voucherDecisionRestoresSettledResourcesAndSealedArchiveWithoutReposting() throws Exception {
        var report = archiveReadyExpense(); pollArchive(); var archive = archives.find("demo", report.id(), 1).orElseThrow();
        var bytes = archiveDownload(report, "finance"); var versions = resourceVersions(report); var settled = settlements.find("demo", report.id()).orElseThrow();
        var disputed = correctedVoucher(report, VoucherCommand.Kind.EXPENSE_ACCRUAL);
        assertThat(settlements.find("demo", report.id()).orElseThrow().status()).isEqualTo(ExpenseSettlement.Status.REVIEW_REQUIRED);
        assertThat(ok(read(voucherPath(report), "finance"), 200).at("/dispute/canResolve").asBoolean()).isTrue();
        ok(send(voucherDecisionPath(disputed), "finance", voucherDecisionInput(report, disputed)), 202);
        var restored = settlements.find("demo", report.id()).orElseThrow(); assertThat(restored.status()).isEqualTo(ExpenseSettlement.Status.SETTLED);
        assertThat(restored.input()).isEqualTo(settled.input()); assertThat(restored.budgetOperationId()).isEqualTo(settled.budgetOperationId());
        assertThat(resourceVersions(report)).isEqualTo(versions); pollArchive();
        assertThat(archives.find("demo", report.id(), 1)).contains(archive); assertThat(archiveDownload(report, "finance")).isEqualTo(bytes);
        assertThat(voucherOperations.find("demo", disputed.input().command().id()).orElseThrow().input()).isEqualTo(disputed.input());
        assertThat(voucherOperations.revision("demo", disputed.input().command().id(), disputed.version())).contains(disputed);
        assertThat(paymentWrites).isEqualTo(1);
    }

    @Test void voucherDecisionKeepsIndependentPaymentHoldUntilBothOriginalFactsAreResolved() throws Exception {
        var report = paidExpense(); settlementWorker.poll(); var budget = settlements.find("demo", report.id()).orElseThrow().budgetOperationId(); var versions = resourceVersions(report);
        var voucher = correctedVoucher(report, VoucherCommand.Kind.EXPENSE_ACCRUAL); var payment = correctedExpensePayment(report);
        ok(send(voucherDecisionPath(voucher), "finance", voucherDecisionInput(report, voucher)), 202);
        assertThat(settlements.find("demo", report.id()).orElseThrow().status()).isEqualTo(ExpenseSettlement.Status.REVIEW_REQUIRED);
        ok(send("/api/v1/payments/" + payment.input().command().id() + "/dispute-resolutions", "finance", disputeInput(payment)), 202);
        var restored = settlements.find("demo", report.id()).orElseThrow(); assertThat(restored.status()).isEqualTo(ExpenseSettlement.Status.BUDGET_PENDING);
        assertThat(restored.budgetOperationId()).isEqualTo(budget); budgetWorker.poll(); assertThat(resourceVersions(report)).isEqualTo(versions);
    }

    @Test void voucherDecisionRequiresStrictVersionsCurrentFinanceAndAuthorityEvenForReplay() throws Exception {
        var report = paidExpense(); var disputed = correctedVoucher(report, VoucherCommand.Kind.EXPENSE_ACCRUAL); var input = voucherDecisionInput(report, disputed);
        String route = voucherDecisionPath(disputed), key = UUID.randomUUID().toString();
        for (String user : List.of("alice", "admin", "cashier", "manager")) assertThat(send(route, user, input).getStatus()).isIn(403,404);
        assertThat(ok(read(voucherPath(report), "alice"), 200).path("dispute").isNull()).isTrue();
        var forged = new HashMap<>(input); forged.put("voucherReference", "CLIENT-VOUCHER"); assertThat(send(route, "finance", forged).getStatus()).isEqualTo(400);
        for (String outcome : List.of("PENDING", "NOT_FOUND")) { var invalid = new HashMap<>(input); invalid.put("outcome", outcome); assertThat(send(route, "finance", invalid).getStatus()).isEqualTo(400); }
        for (String version : List.of("applicationVersion", "businessVersion", "operationVersion")) {
            var stale = new HashMap<>(input); stale.put(version, ((Number) input.get(version)).longValue() + 1); assertCode(send(route, "finance", stale), "CONCURRENCY_CONFLICT");
        }
        var first = send(route, "finance", key, input); ok(first, 202); assertThat(first.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(send(route, "finance", key, input).getContentAsString()).isEqualTo(first.getContentAsString());
        assertCode(send(route, "finance", input), "CONCURRENCY_CONFLICT");
        var person = organizationRepository.person("demo", finance).orElseThrow(); var inactive = organization.updatePerson(admin, finance, person.displayName(), false, person.approvalEligible(), person.revision());
        try { assertThat(send(route, "finance", key, input).getStatus()).isIn(403,404); }
        finally { organization.updatePerson(admin, finance, person.displayName(), person.active(), person.approvalEligible(), inactive.revision()); }
        assertThat(voucherDecisions.latest("foreign", disputed.input().command().id())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='VOUCHER_DISPUTE_RESOLVE'", Integer.class, report.applicationId().toString())).isEqualTo(1);
        assertThat(settlements.find("demo", report.id()).orElseThrow().status()).isEqualTo(ExpenseSettlement.Status.QUEUED);
    }

    @Test void concurrentVoucherDecisionsAndRollbackPreserveAtomicSettlementAndAudit() throws Exception {
        var report = paidExpense(); var disputed = correctedVoucher(report, VoucherCommand.Kind.EXPENSE_ACCRUAL); var id = disputed.input().command().id();
        var input = json.read(json.write(voucherDecisionInput(report, disputed)), VoucherDisputeService.Input.class); var held = settlements.find("demo", report.id()).orElseThrow();
        actors.set(new Actor("demo", "finance", Set.of("EMPLOYEE", "APPROVER", "FINANCE")));
        try { assertThatThrownBy(() -> tx().execute(status -> { voucherDisputes.resolve(report.applicationId(), id, input); throw new IllegalStateException("Synthetic decision rollback"); })).isInstanceOf(IllegalStateException.class); }
        finally { actors.clear(); }
        assertThat(voucherOperations.find("demo", id)).contains(disputed); assertThat(voucherDecisions.latest("demo", id)).isEmpty(); assertThat(settlements.find("demo", report.id())).contains(held);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2); var gate = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<VoucherDisputeService.Receipt> decide = () -> { actors.set(new Actor("demo", "finance", Set.of("EMPLOYEE", "APPROVER", "FINANCE"))); try { gate.await(); return voucherDisputes.resolve(report.applicationId(), id, input); } finally { actors.clear(); } };
        int succeeded = 0, rejected = 0;
        try { var first = pool.submit(decide); var second = pool.submit(decide); gate.countDown();
            for (var future : List.of(first, second)) { try { future.get(10, java.util.concurrent.TimeUnit.SECONDS); succeeded++; }
                catch (java.util.concurrent.ExecutionException failure) { assertThat(failure.getCause()).isInstanceOf(io.agentflow.common.DomainException.class); rejected++; } }
        } finally { pool.shutdownNow(); }
        assertThat(succeeded).isEqualTo(1); assertThat(rejected).isEqualTo(1);
        assertThat(voucherOperations.find("demo", id).orElseThrow().version()).isEqualTo(disputed.version() + 1);
        assertThat(settlements.find("demo", report.id()).orElseThrow().version()).isEqualTo(held.version() + 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='VOUCHER_DISPUTE_RESOLVE'", Integer.class, report.applicationId().toString())).isEqualTo(1);
    }

    @Test void voucherFailureCannotEraseEarlierPostingAndAcceptedReversalKeepsConsumptionFrozen() throws Exception {
        var report = paidExpense(); settlementWorker.poll(); budgetWorker.poll(); var versions = resourceVersions(report);
        var current = correctedVoucher(report, VoucherCommand.Kind.EXPENSE_ACCRUAL); var original = current.observation();
        var failed = observeVoucher(current, new VoucherObservation(original.operationId(), original.commandDigest(), VoucherObservation.Status.FAILED, current.highestRevision() + 1,
                Instant.now(), original.postingReference(), null, null, null, null, null, null, VoucherObservation.Failure.VOUCHER_REJECTED));
        assertCode(send(voucherDecisionPath(failed), "finance", voucherDecisionInput(report, failed)), "VOUCHER_DISPUTE_UNRESOLVABLE");
        assertThat(ok(read(voucherPath(report), "finance"), 200).at("/dispute/issue").asText()).isEqualTo("POSTING_ALREADY_OBSERVED");
        var reversed = observeVoucher(failed, voucherFact(failed, VoucherObservation.Status.REVERSED, failed.highestRevision() + 1));
        ok(send(voucherDecisionPath(reversed), "finance", voucherDecisionInput(report, reversed)), 202);
        assertThat(voucherOperations.find("demo", original.operationId()).orElseThrow().status()).isEqualTo(VoucherOperation.Status.REVERSED);
        assertThat(settlements.find("demo", report.id()).orElseThrow().status()).isEqualTo(ExpenseSettlement.Status.REVIEW_REQUIRED);
        assertThat(resourceVersions(report)).isEqualTo(versions); assertThat(paymentWrites).isEqualTo(1);
    }

    @Test void paymentVoucherDecisionDoesNotAllowOriginalCashierOrRecreateFundingAndArchive() throws Exception {
        var report = archiveReadyExpense(); pollArchive(); var archive = archives.find("demo", report.id(), 1).orElseThrow(); var settled = settlements.find("demo", report.id()).orElseThrow();
        var disputed = correctedVoucher(report, VoucherCommand.Kind.PAYMENT); var input = json.read(json.write(voucherDecisionInput(report, disputed)), VoucherDisputeService.Input.class);
        actors.set(new Actor("demo", "cashier", Set.of("EMPLOYEE", "APPROVER", "CASHIER", "FINANCE")));
        try { assertThatThrownBy(() -> voucherDisputes.resolve(report.applicationId(), disputed.input().command().id(), input)).isInstanceOfSatisfying(io.agentflow.common.DomainException.class, e -> assertThat(e.code()).isIn("FORBIDDEN", "NOT_FOUND")); }
        finally { actors.clear(); }
        assertThat(VoucherDisputeResolution.independent(disputed.input().command(), "cashier")).isFalse();
        ok(send(voucherDecisionPath(disputed), "finance", voucherDecisionInput(report, disputed)), 202); pollArchive();
        assertThat(settlements.find("demo", report.id())).contains(settled); assertThat(archives.find("demo", report.id(), 1)).contains(archive);
        assertThat(ok(read(path(report) + "/archive", "finance"), 200).path("issue").isNull()).isTrue(); assertThat(paymentWrites).isEqualTo(1);
    }

    @Test void independentReversalCannotBeErasedByLaterPostedDecision() throws Exception {
        var report = paidExpense(); settlementWorker.poll(); budgetWorker.poll();
        var operation = reversedVoucher(report, VoucherCommand.Kind.EXPENSE_ACCRUAL); var check = checkedReversal(report, operation);
        ok(send(reversalPath(operation) + "/records", "finance", reversalRecordInput(report, operation, check)), 202);
        assertThat(voucherReversalRecords.forOperation("demo", operation.input().command().id())).isPresent();
        var disputed = correctedVoucher(report, VoucherCommand.Kind.EXPENSE_ACCRUAL);
        assertCode(send(voucherDecisionPath(disputed), "finance", voucherDecisionInput(report, disputed)), "VOUCHER_REVERSAL_ALREADY_RECORDED");
        assertThat(ok(read(voucherPath(report), "finance"), 200).at("/dispute/canResolve").asBoolean()).isFalse();
        assertThat(settlements.find("demo", report.id()).orElseThrow().status()).isEqualTo(ExpenseSettlement.Status.REVIEW_REQUIRED);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = VoucherCommand.Kind.class, names = {"EXPENSE_ACCRUAL", "PAYMENT"})
    void voucherReversalRecordsIndependentPostingWithoutChangingFinancialResourcesOrSealedArchive(VoucherCommand.Kind kind) throws Exception {
        var report = archiveReadyExpense(); pollArchive(); var archive = archives.find("demo", report.id(), 1).orElseThrow();
        var bytes = archiveDownload(report, "finance"); var versions = resourceVersions(report); var operation = reversedVoucher(report, kind);
        var check = checkedReversal(report, operation); var originalInput = operation.input(); var route = reversalPath(operation);
        assertThat(voucherReversalRecords.forOperation("demo", originalInput.command().id())).isEmpty();
        var view = ok(read(route, "finance"), 200); assertThat(view.at("/latestCheck/canRecord").asBoolean()).isTrue();
        assertThat(view.at("/latestCheck/evidence/reversal/lines").size()).isEqualTo(originalInput.command().lines().size());
        assertThat(view.toString()).doesNotContain("targetDigest", "commandDigest", "accountReference", "accountDigest", "payee");
        assertThat(ok(read(route, "alice"), 200).path("latestCheck").isNull()).isTrue();
        String key = UUID.randomUUID().toString(); var input = reversalRecordInput(report, operation, check); var first = send(route + "/records", "finance", key, input);
        ok(first, 202); assertThat(first.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(send(route + "/records", "finance", key, input).getContentAsString()).isEqualTo(first.getContentAsString());
        assertCode(send(route + "/records", "finance", input), "VOUCHER_REVERSAL_ALREADY_RECORDED");
        assertCode(send(route + "/checks", "finance", reversalQueryInput(report, operation)), "VOUCHER_REVERSAL_ALREADY_RECORDED");
        assertThat(ok(read(route, "alice"), 200).at("/record/reversal/voucherReference").asText()).startsWith("reverse-voucher-");
        assertThat(voucherOperations.find("demo", originalInput.command().id())).contains(operation); assertThat(resourceVersions(report)).isEqualTo(versions);
        assertThat(archives.find("demo", report.id(), 1)).contains(archive); var preserved = new java.io.ByteArrayOutputStream(); archiveFiles.write(archive, preserved); assertThat(preserved.toByteArray()).isEqualTo(bytes);
        assertThat(voucherReversalRecords.forOperation("foreign", originalInput.command().id())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='VOUCHER_REVERSAL_RECORD'", Integer.class, report.applicationId().toString())).isEqualTo(1);
        assertThat(paymentWrites).isEqualTo(1);
    }

    @Test void voucherReversalRequiresCurrentVersionsAuthorityAndOriginalBindingEvenOnReplay() throws Exception {
        var report = paidExpense(); var operation = reversedVoucher(report, VoucherCommand.Kind.EXPENSE_ACCRUAL); var route = reversalPath(operation); var query = reversalQueryInput(report, operation);
        for (String user : List.of("alice", "admin", "cashier", "manager")) assertThat(send(route + "/checks", user, query).getStatus()).isIn(403, 404);
        assertThat(read(route + "?amount=1", "finance").getStatus()).isEqualTo(400);
        var forged = new HashMap<>(query); forged.put("targetDigest", "a".repeat(64)); assertThat(send(route + "/checks", "finance", forged).getStatus()).isEqualTo(400);
        for (String name : List.of("applicationVersion", "businessVersion", "operationVersion")) {
            var stale = new HashMap<>(query); stale.put(name, ((Number) query.get(name)).longValue() + 1); assertCode(send(route + "/checks", "finance", stale), "CONCURRENCY_CONFLICT");
        }
        var check = checkedReversal(report, operation); var input = reversalRecordInput(report, operation, check); String key = UUID.randomUUID().toString();
        ok(send(route + "/records", "finance", key, input), 202);
        var person = organizationRepository.person("demo", finance).orElseThrow(); var inactive = organization.updatePerson(admin, finance, person.displayName(), false, person.approvalEligible(), person.revision());
        try {
            assertThat(send(route + "/records", "finance", key, input).getStatus()).isIn(403, 404);
            assertThat(read(route, "finance").getStatus()).isEqualTo(403);
        } finally { organization.updatePerson(admin, finance, person.displayName(), person.active(), person.approvalEligible(), inactive.revision()); }
        assertThat(read(reversalPath(operation).replace(report.applicationId().toString(), UUID.randomUUID().toString()), "finance").getStatus()).isEqualTo(404);
    }

    @Test void voucherReversalRollbackConcurrencyAndSharedErpIdentityAreAtomic() throws Exception {
        var report = archiveReadyExpense(); var operation = reversedVoucher(report, VoucherCommand.Kind.EXPENSE_ACCRUAL); var check = checkedReversal(report, operation);
        var input = json.read(json.write(reversalRecordInput(report, operation, check)), VoucherReversalService.RecordInput.class); var id = operation.input().command().id();
        actors.set(new Actor("demo", "finance", Set.of("EMPLOYEE", "APPROVER", "FINANCE")));
        try { assertThatThrownBy(() -> tx().execute(status -> { voucherReversals.record(report.applicationId(), id, input); throw new IllegalStateException("Synthetic reversal rollback"); })).hasMessageContaining("Synthetic reversal rollback"); }
        finally { actors.clear(); }
        assertThat(voucherReversalChecks.find("demo", check.input().id())).contains(check); assertThat(voucherReversalRecords.forOperation("demo", id)).isEmpty();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2); var gate = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<VoucherReversalService.ActionReceipt> decide = () -> { actors.set(new Actor("demo", "finance", Set.of("EMPLOYEE", "APPROVER", "FINANCE"))); try { gate.await(); return voucherReversals.record(report.applicationId(), id, input); } finally { actors.clear(); } };
        int succeeded = 0, rejected = 0;
        try { var first = pool.submit(decide); var second = pool.submit(decide); gate.countDown();
            for (var future : List.of(first, second)) { try { future.get(10, java.util.concurrent.TimeUnit.SECONDS); succeeded++; }
                catch (java.util.concurrent.ExecutionException failure) { assertThat(failure.getCause()).isInstanceOf(io.agentflow.common.DomainException.class); rejected++; } }
        } finally { pool.shutdownNow(); }
        assertThat(succeeded).isEqualTo(1); assertThat(rejected).isEqualTo(1);
        var paymentVoucher = reversedVoucher(report, VoucherCommand.Kind.PAYMENT);
        var queued = ok(send(reversalPath(paymentVoucher) + "/checks", "finance", reversalQueryInput(report, paymentVoucher)), 202);
        var claimed = voucherReversals.claim("demo", UUID.fromString(queued.path("checkId").asText()), Instant.now());
        voucherReversals.finish(claimed, new FinanceResult.Success<>(reversalReceipt(claimed, 1, id.toString())), Instant.now()); var checked = voucherReversalChecks.find("demo", claimed.input().id()).orElseThrow();
        assertCode(send(reversalPath(paymentVoucher) + "/records", "finance", reversalRecordInput(report, paymentVoucher, checked)), "VOUCHER_REVERSAL_ALREADY_RECORDED");
        assertThat(voucherReversalChecks.find("demo", claimed.input().id())).contains(checked); assertThat(voucherReversalRecords.forOperation("demo", paymentVoucher.input().command().id())).isEmpty();
    }

    @Test void voucherReversalRejectsOlderLatestQueryAndChangedOriginalFacts() throws Exception {
        var report = paidExpense(); var operation = reversedVoucher(report, VoucherCommand.Kind.EXPENSE_ACCRUAL); var first = checkedReversal(report, operation);
        var oldInput = reversalRecordInput(report, operation, first);
        var queued = ok(send(reversalPath(operation) + "/checks", "finance", reversalQueryInput(report, operation)), 202);
        assertCode(send(reversalPath(operation) + "/records", "finance", oldInput), "CONCURRENCY_CONFLICT");
        var claimed = voucherReversals.claim("demo", UUID.fromString(queued.path("checkId").asText()), Instant.now()); var proof = reversalReceipt(claimed, 2, "different-erp-voucher");
        voucherReversals.finish(claimed, new FinanceResult.Success<>(proof), Instant.now()); var latest = voucherReversalChecks.find("demo", claimed.input().id()).orElseThrow();
        assertCode(send(reversalPath(operation) + "/records", "finance", reversalRecordInput(report, operation, latest)), "VOUCHER_REVERSAL_EVIDENCE_CHANGED");
        var disputed = correctedVoucher(report, VoucherCommand.Kind.EXPENSE_ACCRUAL);
        assertCode(send(reversalPath(operation) + "/checks", "finance", reversalQueryInput(report, disputed)), "VOUCHER_REVERSAL_ORIGINAL_UNRESOLVED");
        assertThat(ok(read(reversalPath(operation), "finance"), 200).at("/latestCheck/canRecord").asBoolean()).isFalse();
    }

    @Test void voucherReversalWorkerUsesFixedReadAndExpiresLostLeasesWithoutAcceptingLateResult() throws Exception {
        var report = paidExpense(); var operation = reversedVoucher(report, VoucherCommand.Kind.EXPENSE_ACCRUAL);
        var queued = ok(send(reversalPath(operation) + "/checks", "finance", reversalQueryInput(report, operation)), 202); var id = UUID.fromString(queued.path("checkId").asText());
        assertCode(send(reversalPath(operation) + "/checks", "finance", reversalQueryInput(report, operation)), "VOUCHER_REVERSAL_PENDING");
        voucherReversalWorker.poll(); var checked = voucherReversalChecks.find("demo", id).orElseThrow(); assertThat(checked.status()).isEqualTo(VoucherReversalCheck.Status.CHECKED);
        assertThat(checked.input().originalVersion()).isEqualTo(voucherOperations.firstAcceptedPosting("demo", operation.input().command().id()).orElseThrow().version());
        queued = ok(send(reversalPath(operation) + "/checks", "finance", reversalQueryInput(report, operation)), 202); id = UUID.fromString(queued.path("checkId").asText());
        var claimed = voucherReversals.claim("demo", id, Instant.now()); var result = new FinanceResult.Success<>(reversalReceipt(claimed, 2, operation.input().command().id().toString()));
        assertThat(voucherReversals.claim("demo", id, claimed.leaseUntil())).isNull(); voucherReversals.finish(claimed, result, claimed.leaseUntil());
        assertThat(voucherReversalChecks.find("demo", id).orElseThrow().issue()).isEqualTo(VoucherReversalCheck.Issue.TIMEOUT);
        assertThat(voucherReversalRecords.forOperation("demo", operation.input().command().id())).isEmpty();
    }

    @Test void voucherReversalCannotConsumeExpiredEvidenceOrIgnoreAnotherFinanceObservation() throws Exception {
        var report = paidExpense(); var operation = reversedVoucher(report, VoucherCommand.Kind.EXPENSE_ACCRUAL); var first = checkedReversal(report, operation);
        var proof = first.receipt(); var at = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var another = VoucherReversalCheck.queue(new VoucherReversalCheck.Input(UUID.randomUUID(), "demo", first.input().targetDigest(), first.input().originalVersion(), first.input().request(), "other-finance", at));
        var running = another.claim(at, java.time.Duration.ofSeconds(90));
        var newer = new VoucherReversalPort.Receipt(proof.request(), proof.status(), proof.revision() + 1, at, at.plusSeconds(60), proof.current(), proof.reversal());
        var checked = running.complete(new FinanceResult.Success<>(newer), at);
        tx().executeWithoutResult(status -> { voucherReversalChecks.create(another); voucherReversalChecks.update(running); voucherReversalChecks.update(checked); });
        assertCode(send(reversalPath(operation) + "/records", "finance", reversalRecordInput(report, operation, first)), "VOUCHER_REVERSAL_EVIDENCE_CHANGED");
        var queued = ok(send(reversalPath(operation) + "/checks", "finance", reversalQueryInput(report, operation)), 202);
        var claimed = voucherReversals.claim("demo", UUID.fromString(queued.path("checkId").asText()), Instant.now()); var latest = reversalReceipt(claimed, 3, operation.input().command().id().toString());
        var shortLived = new VoucherReversalPort.Receipt(latest.request(), latest.status(), latest.revision(), latest.observedAt(), latest.observedAt().plusMillis(1), latest.current(), latest.reversal());
        voucherReversals.finish(claimed, new FinanceResult.Success<>(shortLived), shortLived.observedAt()); Thread.sleep(5);
        var expired = voucherReversalChecks.find("demo", claimed.input().id()).orElseThrow();
        assertCode(send(reversalPath(operation) + "/records", "finance", reversalRecordInput(report, operation, expired)), "VOUCHER_REVERSAL_EVIDENCE_UNAVAILABLE");
        assertThat(voucherReversalRecords.forOperation("demo", operation.input().command().id())).isEmpty();
    }

    @Test void voucherReversalVoidsInFlightEvidenceWhenOriginalOrFinanceAppointmentChanges() throws Exception {
        var report = paidExpense(); var operation = reversedVoucher(report, VoucherCommand.Kind.EXPENSE_ACCRUAL);
        var queued = ok(send(reversalPath(operation) + "/checks", "finance", reversalQueryInput(report, operation)), 202);
        var claimed = voucherReversals.claim("demo", UUID.fromString(queued.path("checkId").asText()), Instant.now()); var proof = reversalReceipt(claimed, 1, operation.input().command().id().toString());
        var person = organizationRepository.person("demo", finance).orElseThrow(); var inactive = organization.updatePerson(admin, finance, person.displayName(), false, person.approvalEligible(), person.revision());
        try { voucherReversals.finish(claimed, new FinanceResult.Success<>(proof), Instant.now()); }
        finally { organization.updatePerson(admin, finance, person.displayName(), person.active(), person.approvalEligible(), inactive.revision()); }
        assertThat(voucherReversalChecks.find("demo", claimed.input().id()).orElseThrow().status()).isEqualTo(VoucherReversalCheck.Status.VOIDED);
        queued = ok(send(reversalPath(operation) + "/checks", "finance", reversalQueryInput(report, operation)), 202);
        var second = voucherReversals.claim("demo", UUID.fromString(queued.path("checkId").asText()), Instant.now()); var secondProof = reversalReceipt(second, 2, operation.input().command().id().toString());
        correctedVoucher(report, VoucherCommand.Kind.EXPENSE_ACCRUAL); voucherReversals.finish(second, new FinanceResult.Success<>(secondProof), Instant.now());
        assertThat(voucherReversalChecks.find("demo", second.input().id()).orElseThrow().status()).isEqualTo(VoucherReversalCheck.Status.VOIDED);
        assertThat(voucherReversalRecords.forOperation("demo", operation.input().command().id())).isEmpty();
    }

    private VoucherOperation reversedVoucher(ExpenseReport report, VoucherCommand.Kind kind) {
        var current = voucherOperations.forRound("demo", report.applicationId(), 1, kind).orElseThrow();
        return observeVoucher(current, voucherFact(current, VoucherObservation.Status.REVERSED, current.highestRevision() + 1));
    }
    private VoucherReversalCheck checkedReversal(ExpenseReport report, VoucherOperation operation) throws Exception {
        var queued = ok(send(reversalPath(operation) + "/checks", "finance", reversalQueryInput(report, operation)), 202);
        var claimed = voucherReversals.claim("demo", UUID.fromString(queued.path("checkId").asText()), Instant.now());
        voucherReversals.finish(claimed, new FinanceResult.Success<>(reversalReceipt(claimed, 1, operation.input().command().id().toString())), Instant.now());
        return voucherReversalChecks.find("demo", claimed.input().id()).orElseThrow();
    }
    private VoucherReversalPort.Receipt reversalReceipt(VoucherReversalCheck check, long revision, String postingId) {
        var request = check.input().request(); var command = request.command(); var current = voucherOperations.find("demo", command.id()).orElseThrow().observation();
        var at = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var fresh = new VoucherObservation(command.id(), command.digest(), current.status(), current.revision(), at, current.postingReference(), current.voucherReference(),
                current.periodReference(), current.accountingDate(), current.debitTotal(), current.creditTotal(), current.postedAt(), null);
        var posting = new VoucherReversalPort.Posting("reverse-posting-" + postingId, "reverse-voucher-" + postingId, command.period().periodReference(), command.accountingDate(), current.postedAt().plusMillis(1),
                command.lines().stream().map(line -> new VoucherReversalPort.Line("reverse-" + line.lineNo(), line.lineNo(), command.mapping().account(line.account()),
                        line.side() == VoucherCommand.Side.DEBIT ? VoucherCommand.Side.CREDIT : VoucherCommand.Side.DEBIT, line.amount(), line.sourceLineNo(), line.costCenter(), line.projectCode(), line.advanceId())).toList());
        return new VoucherReversalPort.Receipt(request, VoucherReversalPort.Status.VERIFIED, revision, at, at.plusSeconds(120), fresh, posting);
    }
    private Map<String, Object> reversalQueryInput(ExpenseReport report, VoucherOperation operation) {
        return Map.of("roundNo", 1, "applicationVersion", app(report).version(), "businessVersion", current(report).version(), "operationVersion", operation.version(), "comment", "核验原凭证的独立反向分录");
    }
    private Map<String, Object> reversalRecordInput(ExpenseReport report, VoucherOperation operation, VoucherReversalCheck check) {
        var input = new HashMap<>(reversalQueryInput(report, operation)); input.put("checkId", check.input().id()); input.put("checkVersion", check.version()); input.put("evidenceReference", "ERP-REVERSAL-001"); return input;
    }
    private String reversalPath(VoucherOperation operation) { return "/api/v1/applications/" + operation.input().command().binding().applicationId() + "/vouchers/" + operation.input().command().id() + "/reversal"; }

    private VoucherOperation correctedVoucher(ExpenseReport report, VoucherCommand.Kind kind) {
        var original = voucherOperations.forRound("demo", report.applicationId(), 1, kind).orElseThrow(); var fact = original.observation();
        var disputed = observeVoucher(original, new VoucherObservation(fact.operationId(), fact.commandDigest(), VoucherObservation.Status.PENDING, original.highestRevision() + 1,
                Instant.now(), fact.postingReference(), null, null, null, null, null, null, null));
        return observeVoucher(disputed, voucherFact(disputed, VoucherObservation.Status.POSTED, disputed.highestRevision() + 1));
    }
    private VoucherObservation voucherFact(VoucherOperation value, VoucherObservation.Status status, long revision) {
        var fact = value.observation(); return new VoucherObservation(fact.operationId(), fact.commandDigest(), status, revision, Instant.now().plusNanos(123), fact.postingReference(), fact.voucherReference(),
                fact.periodReference(), fact.accountingDate(), fact.debitTotal(), fact.creditTotal(), fact.postedAt(), null);
    }
    private VoucherOperation observeVoucher(VoucherOperation current, VoucherObservation fact) {
        var id = current.input().command().id(); tx().executeWithoutResult(status -> voucherExecution.query("demo", id, current.version(), Instant.now()));
        var claimed = voucherExecution.claim("demo", id, Instant.now()); voucherExecution.finish(claimed, new FinanceResult.Success<>(fact), Instant.now());
        return voucherOperations.find("demo", id).orElseThrow();
    }
    private Map<String, Object> voucherDecisionInput(ExpenseReport report, VoucherOperation value) {
        return Map.of("roundNo", 1, "applicationVersion", app(report).version(), "businessVersion", current(report).version(), "operationVersion", value.version(),
                "outcome", value.conflictingObservation().status().name(), "evidenceReference", "ERP-REVIEW-001", "comment", "核对原凭证和最高对账版本");
    }
    private String voucherDecisionPath(VoucherOperation value) { return "/api/v1/applications/" + value.input().command().binding().applicationId() + "/vouchers/" + value.input().command().id() + "/dispute-resolutions"; }

    private PaymentOperation correctedExpensePayment(ExpenseReport report) {
        var id = settlements.find("demo", report.id()).orElseThrow().input().payment().operationId(); var original = paymentOperations.find("demo", id).orElseThrow();
        var fact = original.observation(); var revision = original.highestRevision();
        observePayment(original, new PaymentObservation(id, fact.commandDigest(), PaymentObservation.Status.FAILED, revision + 1, Instant.now(), fact.paymentReference(), null, null, null, null, PaymentObservation.Failure.PAYMENT_REJECTED));
        var disputed = paymentOperations.find("demo", id).orElseThrow();
        observePayment(disputed, new PaymentObservation(id, fact.commandDigest(), PaymentObservation.Status.SUCCEEDED, revision + 2, Instant.now().plusNanos(123), fact.paymentReference(), fact.paidAmount(), fact.accountDigest(), fact.completedAt(), fact.receiptReference(), null));
        return paymentOperations.find("demo", id).orElseThrow();
    }
    private void observePayment(PaymentOperation current, PaymentObservation observation) {
        var id = current.input().command().id(); tx().executeWithoutResult(status -> paymentExecution.query("demo", id, current.version(), Instant.now()));
        var claimed = paymentExecution.claim("demo", id, Instant.now()); paymentExecution.finish(claimed, new FinanceResult.Success<>(observation), Instant.now());
    }
    private Map<String, Object> disputeInput(PaymentOperation value) {
        return Map.of("authorizationVersion", 2, "operationVersion", value.version(), "outcome", value.conflictingObservation().status().name(), "evidenceReference", "BANK-DISPUTE-001", "comment", "核对原付款对账证据");
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

    @Test void reversalExecutionRequiresExplicitAuthorizationFreezesOriginalAndPreservesConsumedResources() throws Exception {
        var report = archiveReadyExpense(); var versions = resourceVersions(report); var paymentCount = paymentWrites;
        var prepared = prepareExecution(report); var source = prepared.input().source().command();
        assertThat(reversalWrites).isZero(); assertThat(voucherOperations.find("demo", source.id()).orElseThrow().usablePosted()).isTrue();
        var receipt = authorizeExecution(report, prepared); var held = voucherOperations.find("demo", source.id()).orElseThrow();
        assertThat(held.reversalId()).isEqualTo(prepared.input().id()); assertThat(held.usablePosted()).isFalse();
        assertThat(settlements.find("demo", report.id()).orElseThrow().status()).isEqualTo(ExpenseSettlement.Status.REVIEW_REQUIRED);
        assertThat(reversalWrites).isZero(); reversalExecutionWorker.poll();
        var operation = reversalOperations.find("demo", receipt.reversalId()).orElseThrow();
        assertThat(operation.status()).isEqualTo(VoucherReversalOperation.Status.POSTED); assertThat(reversalWrites).isEqualTo(1);
        voucherWorker.poll(); assertThat(voucherOperations.find("demo", source.id()).orElseThrow().status()).isEqualTo(VoucherOperation.Status.REVERSED);
        assertThat(voucherOperations.find("demo", source.id()).orElseThrow().reversalId()).isEqualTo(prepared.input().id());
        assertThat(resourceVersions(report)).isEqualTo(versions); assertThat(paymentWrites).isEqualTo(paymentCount);
        assertThat(voucherReversalRecords.forOperation("demo", source.id())).isEmpty();
        assertThat(reversalOperations.find("foreign", receipt.reversalId())).isEmpty();
    }

    @Test void reversalExecutionAuthorizationRollbackPreservesOriginalAndReadyEvidence() throws Exception {
        var report = paidExpense(); var prepared = prepareExecution(report); var source = prepared.input().source().command();
        assertThatThrownBy(() -> asFinance(() -> tx().execute(status -> { reversalPreparing.authorize(report.applicationId(), source.id(), executionAuthorization(report, prepared)); throw new IllegalStateException("Synthetic authorization rollback"); }))).hasMessageContaining("Synthetic authorization rollback");
        assertThat(reversalPreparations.find("demo", prepared.input().id())).contains(prepared); assertThat(reversalOperations.forOriginal("demo", source.id())).isEmpty();
        assertThat(voucherOperations.find("demo", source.id()).orElseThrow().usablePosted()).isTrue(); assertThat(reversalWrites).isZero();
    }

    @Test void reversalExecutionQueriesTheOriginalAfterLostWriteResponseWithoutResending() throws Exception {
        var report = paidExpense(); var prepared = prepareExecution(report); var receipt = authorizeExecution(report, prepared);
        invalidReversalResponse = true; reversalExecutionWorker.poll(); var unknown = reversalOperations.find("demo", receipt.reversalId()).orElseThrow();
        assertThat(unknown.status()).isEqualTo(VoucherReversalOperation.Status.UNKNOWN); assertThat(reversalWrites).isEqualTo(1);
        invalidReversalResponse = false; var claimed = reversalExecution.claim("demo", receipt.reversalId(), unknown.nextAttemptAt());
        assertThat(claimed.status()).isEqualTo(VoucherReversalOperation.Status.QUERYING);
        var result = reversalPort.query(claimed.input().targetDigest(), claimed.input().command()); reversalExecution.finish(claimed, result, claimed.updatedAt().plusMillis(1));
        assertThat(reversalOperations.find("demo", receipt.reversalId()).orElseThrow().status()).isEqualTo(VoucherReversalOperation.Status.POSTED);
        assertThat(reversalWrites).isEqualTo(1); assertThat(reversalQueries).isEqualTo(1);
    }

    @Test void reversalExecutionCurrentPersonnelAndOriginalVersionsInvalidateNewSendingOnly() throws Exception {
        var report = paidExpense(); var prepared = prepareExecution(report); authorizeExecution(report, prepared);
        var person = organizationRepository.person("demo", finance).orElseThrow();
        var inactive = organization.updatePerson(admin, finance, person.displayName(), false, person.approvalEligible(), person.revision());
        try { reversalExecutionWorker.poll(); assertThat(reversalOperations.find("demo", prepared.input().id()).orElseThrow().status()).isEqualTo(VoucherReversalOperation.Status.VOIDED); assertThat(reversalWrites).isZero(); }
        finally { organization.updatePerson(admin, finance, person.displayName(), person.active(), person.approvalEligible(), inactive.revision()); }
        assertThat(voucherOperations.find("demo", prepared.input().source().command().id()).orElseThrow().usablePosted()).isFalse();
    }

    @Test void reversalExecutionOnlyOneConcurrentAuthorizationAndClaimCanSucceed() throws Exception {
        var report = paidExpense(); var prepared = prepareExecution(report); var source = prepared.input().source().command(); var input = executionAuthorization(report, prepared);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2); var gate = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<VoucherReversalPreparationService.ActionReceipt> authorize = () -> { gate.await(); return asFinance(() -> reversalPreparing.authorize(report.applicationId(), source.id(), input)); };
        int success = 0, rejected = 0;
        try { var first = pool.submit(authorize); var second = pool.submit(authorize); gate.countDown();
            for (var future : List.of(first, second)) { try { future.get(10, java.util.concurrent.TimeUnit.SECONDS); success++; }
                catch (java.util.concurrent.ExecutionException failure) { assertThat(failure.getCause()).isInstanceOf(io.agentflow.common.DomainException.class); rejected++; } }
            assertThat(success).isEqualTo(1); assertThat(rejected).isEqualTo(1);
            var one = pool.submit(() -> reversalExecution.claim("demo", prepared.input().id(), Instant.now())); var two = pool.submit(() -> reversalExecution.claim("demo", prepared.input().id(), Instant.now()));
            var claimed = java.util.stream.Stream.of(one.get(10, java.util.concurrent.TimeUnit.SECONDS), two.get(10, java.util.concurrent.TimeUnit.SECONDS)).filter(java.util.Objects::nonNull).toList();
            assertThat(claimed).hasSize(1); assertThat(claimed.get(0).status()).isEqualTo(VoucherReversalOperation.Status.POSTING);
        } finally { pool.shutdownNow(); }
    }

    @Test void reversalExecutionStalePreparationAndLateReadCannotAuthorize() throws Exception {
        var report = paidExpense(); var prepared = prepareExecution(report); var source = prepared.input().source().command(); var original = voucherOperations.find("demo", source.id()).orElseThrow();
        observeVoucher(original, new VoucherObservation(source.id(), source.digest(), VoucherObservation.Status.POSTED, 1L, Instant.now(), original.observation().postingReference(), original.observation().voucherReference(), source.period().periodReference(), source.accountingDate(), source.totals().gross(), source.totals().gross(), original.observation().postedAt(), null));
        assertThatThrownBy(() -> authorizeExecution(report, prepared)).isInstanceOf(io.agentflow.common.DomainException.class); assertThat(reversalOperations.forOriginal("demo", source.id())).isEmpty();
        var queued = asFinance(() -> reversalPreparing.prepare(report.applicationId(), source.id(), executionPreparation(report, voucherOperations.find("demo", source.id()).orElseThrow())));
        var claimed = reversalPreparing.claim("demo", queued.preparationId(), Instant.now());
        reversalPreparing.finish(claimed, new FinanceResult.Success<>(prepared.command().verifiedOriginal()), new FinanceResult.Success<>(prepared.command().period()), claimed.leaseUntil());
        assertThat(reversalPreparations.find("demo", queued.preparationId()).orElseThrow().issue()).isEqualTo("TIMEOUT"); assertThat(reversalWrites).isZero();
    }

    @Test void reversalExecutionHttpSeparatesPreparationAuthorizationAndPostingWithIdempotentReceipts() throws Exception {
        var report = paidExpense(); var original = voucherOperations.forRound("demo", report.applicationId(), 1, VoucherCommand.Kind.EXPENSE_ACCRUAL).orElseThrow();
        var route = executionPath(report, original); var initial = ok(read(route, "finance"), 200);
        assertThat(initial.path("canPrepare").asBoolean()).isTrue(); assertThat(initial.path("originalHeld").asBoolean()).isFalse();
        var input = executionPreparation(report, original); var key = UUID.randomUUID().toString();
        var preparedResponse = send(route + "/preparations", "finance", key, input); var queued = ok(preparedResponse, 202);
        assertThat(preparedResponse.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(ok(send(route + "/preparations", "finance", key, input), 202)).isEqualTo(queued);
        assertThat(queued.path("preparationVersion").asLong()).isEqualTo(1); assertThat(queued.path("reversalId").isNull()).isTrue();
        assertThat(reversalOperations.forOriginal("demo", original.input().command().id())).isEmpty(); assertThat(reversalWrites).isZero();
        reversalExecutionWorker.poll(); var ready = reversalPreparations.find("demo", UUID.fromString(queued.path("preparationId").asText())).orElseThrow();
        var candidate = ok(read(route, "finance"), 200);
        assertThat(candidate.at("/latestPreparation/canAuthorize").asBoolean()).isTrue();
        assertThat(candidate.at("/latestPreparation/candidate/lines").size()).isEqualTo(original.input().command().lines().size());
        assertThat(candidate.toString()).doesNotContain("targetDigest", "commandDigest", "accountReference", "accountDigest", "payee");
        assertThat(ok(read(route, "alice"), 200).path("latestPreparation").isNull()).isTrue();
        var authorization = executionAuthorization(report, ready); var authorizationKey = UUID.randomUUID().toString();
        var receipt = ok(send(route + "/authorizations", "finance", authorizationKey, authorization), 202);
        assertThat(ok(send(route + "/authorizations", "finance", authorizationKey, authorization), 202)).isEqualTo(receipt);
        assertThat(receipt.path("operationVersion").asLong()).isEqualTo(original.version() + 1);
        assertThat(receipt.path("reversalId").asText()).isEqualTo(ready.input().id().toString());
        var held = ok(read(route, "finance"), 200); assertThat(held.path("originalHeld").asBoolean()).isTrue();
        assertThat(held.path("canPrepare").asBoolean()).isFalse(); assertThat(held.at("/operation/status").asText()).isEqualTo("QUEUED");
        assertThat(held.at("/latestPreparation/canAuthorize").asBoolean()).isFalse(); assertThat(held.at("/operation/canQuery").asBoolean()).isFalse();
        assertThat(ok(read(route, "alice"), 200).at("/operation/canQuery").asBoolean()).isFalse(); assertThat(reversalWrites).isZero();
        assertCode(send(route + "/authorizations", "finance", authorization), "CONCURRENCY_CONFLICT");
        reversalExecutionWorker.poll(); voucherWorker.poll(); var posted = ok(read(route, "finance"), 200);
        assertThat(posted.path("originalStatus").asText()).isEqualTo("REVERSED"); assertThat(posted.path("originalHeld").asBoolean()).isTrue();
        assertThat(posted.at("/operation/status").asText()).isEqualTo("POSTED"); assertThat(posted.at("/operation/observation/posting/lines").size()).isEqualTo(original.input().command().lines().size());
        assertThat(reversalWrites).isEqualTo(1);
    }

    @Test void reversalExecutionHttpRejectsStaleForgedCrossSourceAndRevokedReplay() throws Exception {
        var report = paidExpense(); var original = voucherOperations.forRound("demo", report.applicationId(), 1, VoucherCommand.Kind.EXPENSE_ACCRUAL).orElseThrow();
        var route = executionPath(report, original); var input = executionPreparation(report, original);
        for (String user : List.of("alice", "admin", "cashier", "manager")) assertThat(send(route + "/preparations", user, input).getStatus()).isIn(403, 404);
        assertThat(read(route + "?amount=1", "finance").getStatus()).isEqualTo(400);
        assertThat(read(route.replace(report.applicationId().toString(), UUID.randomUUID().toString()), "finance").getStatus()).isEqualTo(404);
        var forged = (com.fasterxml.jackson.databind.node.ObjectNode) json.read(json.write(input), JsonNode.class); forged.put("commandDigest", "a".repeat(64));
        assertThat(send(route + "/preparations", "finance", forged).getStatus()).isEqualTo(400);
        for (String field : List.of("applicationVersion", "businessVersion", "operationVersion")) {
            var stale = (com.fasterxml.jackson.databind.node.ObjectNode) json.read(json.write(input), JsonNode.class); stale.put(field, stale.path(field).asLong() + 1);
            assertCode(send(route + "/preparations", "finance", stale), "CONCURRENCY_CONFLICT");
        }
        var key = UUID.randomUUID().toString(); ok(send(route + "/preparations", "finance", key, input), 202);
        var person = organizationRepository.person("demo", finance).orElseThrow();
        var inactive = organization.updatePerson(admin, finance, person.displayName(), false, person.approvalEligible(), person.revision());
        try {
            assertThat(send(route + "/preparations", "finance", key, input).getStatus()).isIn(403, 404);
            assertThat(read(route, "finance").getStatus()).isEqualTo(403);
        } finally { organization.updatePerson(admin, finance, person.displayName(), person.active(), person.approvalEligible(), inactive.revision()); }
        assertThat(reversalOperations.forOriginal("demo", original.input().command().id())).isEmpty(); assertThat(reversalWrites).isZero();
    }

    @Test void reversalExecutionHttpRetainsOriginalRoundFieldPermissionsAndAdminCannotBypass() throws Exception {
        hideBusinessDetails = true; var report = paidExpense(); var original = voucherOperations.forRound("demo", report.applicationId(), 1, VoucherCommand.Kind.EXPENSE_ACCRUAL).orElseThrow();
        var route = executionPath(report, original);
        ok(read(route, "finance"), 200);
        for (String user : List.of("manager", "admin")) {
            assertThat(read(route, user).getStatus()).isEqualTo(403);
            assertThat(send(route + "/preparations", user, executionPreparation(report, original)).getStatus()).isEqualTo(403);
        }
        assertThat(reversalWrites).isZero();
    }

    @Test void reversalExecutionHttpRequiresExplicitOriginalQueryAndResendAfterAuthoritativeNotFound() throws Exception {
        var report = paidExpense(); var prepared = prepareExecution(report); var receipt = authorizeExecution(report, prepared);
        var original = voucherOperations.find("demo", prepared.input().source().command().id()).orElseThrow(); var route = executionPath(report, original);
        var sending = reversalExecution.claim("demo", receipt.reversalId(), Instant.now());
        reversalExecution.fail(sending, VoucherReversalOperation.Failure.CONNECTION, Instant.now());
        var unknown = reversalOperations.find("demo", receipt.reversalId()).orElseThrow();
        var query = new VoucherReversalPreparationService.OperationInput(1, app(report).version(), current(report).version(), original.version(), receipt.reversalId(), unknown.version(), VoucherReversalPreparationService.Action.QUERY, "读取原冲销编号结果");
        var key = UUID.randomUUID().toString(); var queued = ok(send(route + "/actions", "finance", key, query), 202);
        assertThat(ok(send(route + "/actions", "finance", key, query), 202)).isEqualTo(queued);
        assertCode(send(route + "/actions", "finance", query), "CONCURRENCY_CONFLICT");
        var reading = reversalExecution.claim("demo", receipt.reversalId(), Instant.now()); var at = Instant.now();
        reversalExecution.finish(reading, new FinanceResult.Success<>(new VoucherReversalObservation(receipt.reversalId(), prepared.command().digest(), VoucherReversalObservation.Status.NOT_FOUND, 0, at, null, null, null)), at);
        var notFound = ok(read(route, "finance"), 200); assertThat(notFound.at("/operation/canResendOriginal").asBoolean()).isTrue();
        var resend = new VoucherReversalPreparationService.OperationInput(1, app(report).version(), current(report).version(), original.version(), receipt.reversalId(), notFound.at("/operation/version").asLong(), VoucherReversalPreparationService.Action.RESEND_ORIGINAL, "查无受理后明确按原编号重发");
        assertThat(send(route + "/actions", "cashier", resend).getStatus()).isIn(403, 404);
        var resent = ok(send(route + "/actions", "finance", resend), 202); assertThat(resent.path("reversalId").asText()).isEqualTo(receipt.reversalId().toString());
        reversalExecutionWorker.poll(); assertThat(reversalWrites).isEqualTo(1);
        assertThat(reversalOperations.find("demo", receipt.reversalId()).orElseThrow().status()).isEqualTo(VoucherReversalOperation.Status.POSTED);
    }

    private String executionPath(ExpenseReport report, VoucherOperation original) { return "/api/v1/applications/" + report.applicationId() + "/vouchers/" + original.input().command().id() + "/reversal-execution"; }
    @Test void reversalRetirementStopsNeverSentCommandRestoresOnlyOriginalAndAllowsNewExplicitPreparation() throws Exception {
        var report = paidExpense(); settlementWorker.poll(); budgetWorker.poll(); var resources = resourceVersions(report);
        var prepared = prepareExecution(report); var authorized = authorizeExecution(report, prepared); var id = authorized.operationId();
        var before = reversalOperations.find("demo", authorized.reversalId()).orElseThrow(); var original = refreshRetirementOriginal(id);
        var receipt = asFinance(() -> reversalRetirement.retire(report.applicationId(), id, retirementInput(report, original, before)));
        var restored = voucherOperations.find("demo", id).orElseThrow(); var stopped = reversalOperations.find("demo", authorized.reversalId()).orElseThrow();
        assertThat(restored.usablePosted()).isTrue(); assertThat(restored.input()).isEqualTo(original.input()); assertThat(restored.observation()).isEqualTo(original.observation());
        assertThat(stopped.status()).isEqualTo(VoucherReversalOperation.Status.VOIDED); assertThat(stopped.failure()).isEqualTo(VoucherReversalOperation.Failure.FINANCE_RETIRED);
        assertThat(stopped.input()).isEqualTo(before.input()); assertThat(reversalOperations.revision("demo", authorized.reversalId(), before.version())).contains(before);
        assertThat(reversalOperations.retirement("demo", authorized.reversalId()).orElseThrow().id()).isEqualTo(receipt.retirementId());
        assertThat(reversalOperations.forOriginal("demo", id)).isEmpty(); assertThat(reversalExecution.claim("demo", authorized.reversalId(), Instant.now())).isNull();
        assertThatThrownBy(() -> tx().execute(status -> reversalExecution.query("demo", authorized.reversalId(), stopped.version(), Instant.now()))).isInstanceOf(io.agentflow.common.DomainException.class);
        assertThat(settlements.find("demo", report.id()).orElseThrow().status()).isEqualTo(ExpenseSettlement.Status.SETTLED);
        assertThat(resourceVersions(report)).isEqualTo(resources); assertThat(reversalWrites).isZero(); assertThat(paymentWrites).isEqualTo(1);
        var next = prepareExecution(report); assertThat(next.input().id()).isNotEqualTo(authorized.reversalId());
        authorizeExecution(report, next); assertThat(reversalOperations.forOriginal("demo", id).orElseThrow().input().command().id()).isEqualTo(next.input().id());
        assertThat(reversalOperations.retirements("demo", id)).hasSize(1); assertThat(reversalWrites).isZero();
    }

    @Test void reversalRetirementPreservesConfirmedFailureAndIndependentPaymentDispute() throws Exception {
        var report = paidExpense(); settlementWorker.poll(); budgetWorker.poll(); var resources = resourceVersions(report);
        var prepared = prepareExecution(report); var authorized = authorizeExecution(report, prepared);
        var claimed = reversalExecution.claim("demo", authorized.reversalId(), Instant.now()); var at = Instant.now();
        var rejected = new VoucherReversalObservation(authorized.reversalId(), prepared.command().digest(), VoucherReversalObservation.Status.FAILED, 1, at,
                "ERP-REJECTED-BEFORE-POSTING", null, VoucherReversalObservation.Rejection.ACCOUNTING_PERIOD_CLOSED);
        reversalExecution.finish(claimed, new FinanceResult.Success<>(rejected), at);
        var failed = reversalOperations.find("demo", authorized.reversalId()).orElseThrow();
        var oldOriginal = voucherOperations.find("demo", authorized.operationId()).orElseThrow();
        assertThatThrownBy(() -> asFinance(() -> reversalRetirement.retire(report.applicationId(), authorized.operationId(), retirementInput(report, oldOriginal, failed))))
                .isInstanceOf(io.agentflow.common.DomainException.class).hasMessageContaining("rechecked");
        var original = refreshRetirementOriginal(authorized.operationId()); correctedExpensePayment(report);
        var receipt = asFinance(() -> reversalRetirement.retire(report.applicationId(), authorized.operationId(), retirementInput(report, original, failed)));
        assertThat(receipt.basis()).isEqualTo(VoucherReversalOperation.RetirementBasis.CONFIRMED_FAILED);
        assertThat(reversalOperations.find("demo", authorized.reversalId())).contains(failed);
        assertThat(settlements.find("demo", report.id()).orElseThrow().status()).isEqualTo(ExpenseSettlement.Status.REVIEW_REQUIRED);
        assertThat(resourceVersions(report)).isEqualTo(resources); assertThat(reversalWrites).isZero();
    }

    @Test void reversalRetirementRollsBackAllVersionsAndFreezesWhenAuditTransactionFails() throws Exception {
        var report = paidExpense(); settlementWorker.poll(); var prepared = prepareExecution(report); var authorized = authorizeExecution(report, prepared);
        var original = refreshRetirementOriginal(authorized.operationId()); var reversal = reversalOperations.find("demo", authorized.reversalId()).orElseThrow();
        var settlement = settlements.find("demo", report.id()).orElseThrow(); var input = retirementInput(report, original, reversal);
        assertThatThrownBy(() -> asFinance(() -> tx().execute(status -> { reversalRetirement.retire(report.applicationId(), authorized.operationId(), input); throw new IllegalStateException("Synthetic retirement rollback"); })))
                .hasMessageContaining("Synthetic retirement rollback");
        assertThat(voucherOperations.find("demo", authorized.operationId())).contains(original); assertThat(reversalOperations.find("demo", authorized.reversalId())).contains(reversal);
        assertThat(reversalOperations.retirements("demo", authorized.operationId())).isEmpty(); assertThat(settlements.find("demo", report.id())).contains(settlement);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='VOUCHER_REVERSAL_RETIRED'", Integer.class, report.applicationId().toString())).isZero();
    }

    @Test void reversalRetirementCannotRaceSendingOrReleaseUnknownCommand() throws Exception {
        var report = paidExpense(); var prepared = prepareExecution(report); var authorized = authorizeExecution(report, prepared);
        var original = refreshRetirementOriginal(authorized.operationId()); var reverse = reversalOperations.find("demo", authorized.reversalId()).orElseThrow();
        var input = retirementInput(report, original, reverse); var pool = java.util.concurrent.Executors.newFixedThreadPool(2); var gate = new java.util.concurrent.CountDownLatch(1);
        try {
            var retire = pool.submit(() -> { gate.await(); try { return asFinance(() -> reversalRetirement.retire(report.applicationId(), authorized.operationId(), input)); }
                catch (io.agentflow.common.DomainException conflict) { return null; } });
            var send = pool.submit(() -> { gate.await(); return reversalExecution.claim("demo", authorized.reversalId(), Instant.now()); }); gate.countDown();
            var retired = retire.get(10, java.util.concurrent.TimeUnit.SECONDS); var claimed = send.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat((retired != null) ^ (claimed != null)).isTrue();
            if (claimed != null) {
                reversalExecution.fail(claimed, VoucherReversalOperation.Failure.CONNECTION, Instant.now()); var fresh = refreshRetirementOriginal(authorized.operationId());
                var unknown = reversalOperations.find("demo", authorized.reversalId()).orElseThrow();
                assertThatThrownBy(() -> asFinance(() -> reversalRetirement.retire(report.applicationId(), authorized.operationId(), retirementInput(report, fresh, unknown))))
                        .isInstanceOf(io.agentflow.common.DomainException.class).hasMessageContaining("cannot be retired");
                assertThat(voucherOperations.find("demo", authorized.operationId()).orElseThrow().reversalId()).isEqualTo(authorized.reversalId());
            }
        } finally { pool.shutdownNow(); }
        assertThat(reversalWrites).isZero();
    }

    @Test void reversalRetirementHttpRequiresFreshOriginalAndPreservesHistoryAndIdempotentDecision() throws Exception {
        var report = paidExpense(); var prepared = prepareExecution(report); var authorized = authorizeExecution(report, prepared);
        var original = voucherOperations.find("demo", authorized.operationId()).orElseThrow(); var route = executionPath(report, original);
        var reversal = reversalOperations.find("demo", authorized.reversalId()).orElseThrow();
        var held = ok(read(route, "finance"), 200); assertThat(held.at("/operation/canRetire").asBoolean()).isFalse();
        assertThat(held.at("/operation/retirementIssue").asText()).isEqualTo("VOUCHER_REVERSAL_ORIGINAL_RECHECK_REQUIRED");
        assertThat(send(route + "/retirements", "finance", retirementInput(report, original, reversal)).getStatus()).isEqualTo(409);
        original = refreshRetirementOriginal(authorized.operationId()); var input = retirementInput(report, original, reversal);
        var ready = ok(read(route, "finance"), 200); assertThat(ready.at("/operation/canRetire").asBoolean()).isTrue();
        assertThat(ready.at("/operation/retirementCheck/basis").asText()).isEqualTo("NEVER_DISPATCHED");
        assertThat(ok(read(route, "alice"), 200).at("/operation/canRetire").asBoolean()).isFalse();
        var key = UUID.randomUUID().toString(); var response = send(route + "/retirements", "finance", key, input); var result = ok(response, 202);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store"); assertThat(result.path("operationVersion").asLong()).isEqualTo(original.version() + 1);
        assertThat(ok(send(route + "/retirements", "finance", key, input), 202)).isEqualTo(result);
        var after = ok(read(route, "finance"), 200); assertThat(after.path("operation").isNull()).isTrue(); assertThat(after.path("latestPreparation").isNull()).isTrue();
        assertThat(after.path("canPrepare").asBoolean()).isTrue(); assertThat(after.path("originalHeld").asBoolean()).isFalse();
        assertThat(after.path("retirements")).hasSize(1); assertThat(after.at("/retirements/0/reversalId").asText()).isEqualTo(authorized.reversalId().toString());
        assertThat(after.toString()).doesNotContain("commandDigest", "targetDigest", "accountReference", "accountDigest");
        var person = organizationRepository.person("demo", finance).orElseThrow(); var inactive = organization.updatePerson(admin, finance, person.displayName(), false, person.approvalEligible(), person.revision());
        try { assertThat(send(route + "/retirements", "finance", key, input).getStatus()).isIn(403, 404); }
        finally { organization.updatePerson(admin, finance, person.displayName(), person.active(), person.approvalEligible(), inactive.revision()); }
        assertThat(reversalWrites).isZero();
    }

    @Test void reversalRetirementHttpRejectsStaleForgedForeignAndUnauthorizedDecisions() throws Exception {
        var report = paidExpense(); var prepared = prepareExecution(report); var authorized = authorizeExecution(report, prepared);
        var original = refreshRetirementOriginal(authorized.operationId()); var reversal = reversalOperations.find("demo", authorized.reversalId()).orElseThrow();
        var route = executionPath(report, original) + "/retirements"; var input = retirementInput(report, original, reversal);
        for (String user : List.of("alice", "admin", "cashier", "manager")) assertThat(send(route, user, input).getStatus()).isIn(403, 404);
        for (String field : List.of("applicationVersion", "businessVersion", "operationVersion", "reversalVersion")) {
            var stale = (com.fasterxml.jackson.databind.node.ObjectNode) json.read(json.write(input), JsonNode.class); stale.put(field, stale.path(field).asLong() + 1);
            assertCode(send(route, "finance", stale), "CONCURRENCY_CONFLICT");
        }
        var forged = (com.fasterxml.jackson.databind.node.ObjectNode) json.read(json.write(input), JsonNode.class); forged.put("basis", "NEVER_DISPATCHED");
        assertThat(send(route, "finance", forged).getStatus()).isEqualTo(400);
        assertThat(send(route.replace(report.applicationId().toString(), UUID.randomUUID().toString()), "finance", input).getStatus()).isEqualTo(404);
        var incorrect = (com.fasterxml.jackson.databind.node.ObjectNode) json.read(json.write(input), JsonNode.class); incorrect.put("reversalId", UUID.randomUUID().toString());
        assertCode(send(route, "finance", incorrect), "CONCURRENCY_CONFLICT");
        assertThat(reversalOperations.retirements("demo", authorized.operationId())).isEmpty(); assertThat(voucherOperations.find("demo", authorized.operationId())).contains(original);
    }

    private VoucherOperation refreshRetirementOriginal(UUID id) {
        var original = voucherOperations.find("demo", id).orElseThrow();
        return observeVoucher(original, voucherFact(original, VoucherObservation.Status.POSTED, original.highestRevision()));
    }
    private VoucherReversalRetirementService.Input retirementInput(ExpenseReport report, VoucherOperation original, VoucherReversalOperation reverse) {
        return new VoucherReversalRetirementService.Input(1, app(report).version(), current(report).version(), original.version(), reverse.input().command().id(), reverse.version(), "SAFE-REVERSE-END-001", "核对原过账及未执行依据后结束本次冲销");
    }

    private VoucherReversalPreparation prepareExecution(ExpenseReport report) {
        var original = voucherOperations.forRound("demo", report.applicationId(), 1, VoucherCommand.Kind.EXPENSE_ACCRUAL).orElseThrow();
        var queued = asFinance(() -> reversalPreparing.prepare(report.applicationId(), original.input().command().id(), executionPreparation(report, original)));
        reversalExecutionWorker.poll(); var ready = reversalPreparations.find("demo", queued.preparationId()).orElseThrow();
        assertThat(ready.status()).isEqualTo(VoucherReversalPreparation.Status.READY); return ready;
    }
    private VoucherReversalPreparationService.PrepareInput executionPreparation(ExpenseReport report, VoucherOperation original) {
        return new VoucherReversalPreparationService.PrepareInput(1, app(report).version(), current(report).version(), original.version(), original.input().command().accountingDate(), "REVERSE-EXECUTION-PROOF", "核对原凭证后准备独立冲销");
    }
    private VoucherReversalPreparationService.AuthorizeInput executionAuthorization(ExpenseReport report, VoucherReversalPreparation prepared) {
        return new VoucherReversalPreparationService.AuthorizeInput(1, app(report).version(), current(report).version(), prepared.input().operationVersion(), prepared.input().id(), prepared.version(), "明确授权原凭证的完整反向命令");
    }
    private VoucherReversalPreparationService.ActionReceipt authorizeExecution(ExpenseReport report, VoucherReversalPreparation prepared) {
        return asFinance(() -> reversalPreparing.authorize(report.applicationId(), prepared.input().source().command().id(), executionAuthorization(report, prepared)));
    }
    private <T> T asFinance(java.util.function.Supplier<T> action) {
        actors.set(new Actor("demo", "finance", Set.of("EMPLOYEE", "APPROVER", "FINANCE"))); try { return action.get(); } finally { actors.clear(); }
    }
    private VoucherReversalObservation executionReceipt(VoucherReversalCommand command) {
        var at = Instant.now(); var original = command.verifiedOriginal(); var reversed = new VoucherObservation(original.operationId(), original.commandDigest(), VoucherObservation.Status.REVERSED,
                original.revision() + 1, at, original.postingReference(), original.voucherReference(), original.periodReference(), original.accountingDate(), original.debitTotal(), original.creditTotal(), original.postedAt(), null);
        var posting = reversePostings.computeIfAbsent(command.id(), id -> new VoucherReversalPort.Posting("executed-reverse-posting-" + id, "executed-reverse-voucher-" + id, command.period().periodReference(), command.period().request().accountingDate(), at,
                command.lines().stream().map(line -> new VoucherReversalPort.Line("executed-entry-" + line.originalLineNo(), line.originalLineNo(), line.accountCode(), line.side(), line.amount(), line.sourceLineNo(), line.costCenter(), line.projectCode(), line.advanceId())).toList()));
        return new VoucherReversalObservation(command.id(), command.digest(), VoucherReversalObservation.Status.POSTED, 2, at, "executed-acceptance-" + command.id(),
                new VoucherReversalPort.Receipt(command.source(), VoucherReversalPort.Status.VERIFIED, 2, at, at.plusSeconds(300), reversed, posting), null);
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
        byte[] bytes = invoiceOriginalFormat == InvoiceOriginal.Format.XML ? syntheticXmlOriginal()
                : ("%PDF-1.7\nsynthetic-submission-" + UUID.randomUUID() + "\n%%EOF").getBytes(StandardCharsets.UTF_8);
        actors.set(new Actor("demo", "alice", Set.of("EMPLOYEE")));
        try {
            UUID id = wallet.reserve(new InvoiceWalletService.UploadInput("合成发票." + invoiceOriginalFormat.name().toLowerCase(java.util.Locale.ROOT), (long) bytes.length, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), invoiceOriginalFormat)).id();
            wallet.upload(id, new ByteArrayInputStream(bytes)); return id;
        } finally { actors.clear(); }
    }
    private static byte[] syntheticXmlOriginal() {
        return "\ufeff<Invoice xmlns='urn:synthetic'><Name>合成归档原件</Name><Memo><![CDATA[原始字节不变]]></Memo></Invoice>".getBytes(StandardCharsets.UTF_8);
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
    private UUID registerPayeeReview(ExpenseReport report, UUID authorizationId) throws Exception {
        var authorization = paymentAuthorizations.find("demo", authorizationId).orElseThrow();
        return UUID.fromString(ok(send("/api/v1/payments/" + authorizationId + "/payee-reviews", "finance", Map.of("authorizationVersion", authorization.version(),
                "voucherVersion", authorizationInput(report).get("voucherVersion"), "comment", "复核当前本人账户")), 202).path("reviewId").asText());
    }
    private UUID voidedPayment(ExpenseReport report) throws Exception {
        var id = authorizePayment(report);
        ok(send("/api/v1/payments/" + id + "/finance-actions", "finance", Map.of("action", "VOID", "authorizationVersion", 1, "comment", "账户更新前停止原授权")), 202); return id;
    }
    private Map<String, Object> reviewedAuthorizationInput(ExpenseReport report, PaymentPayeeReview review) {
        var input = new HashMap<>(authorizationInput(report)); input.put("payeeReviewId", review.input().id()); input.put("payeeReviewVersion", review.version()); return input;
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
            case "budget-consumption-reversal-command", "budget-consumption-reversal-query" -> {
                BudgetConsumptionReversalCommand command;
                if (operation.equals("budget-consumption-reversal-command")) {
                    budgetReversalWrites++; command = json.read(data.path("command").toString(), BudgetConsumptionReversalCommand.class); budgetReversalCommands.put(command.id(), command);
                } else { budgetReversalQueries++; command = budgetReversalCommands.get(UUID.fromString(data.path("operationId").asText())); }
                if (invalidBudgetReversalResponse) yield Map.of("invalidFixture", true);
                var at = Instant.now(); boolean applied = budgetReversalStatus == BudgetConsumptionReversalObservation.Status.APPLIED;
                yield new BudgetConsumptionReversalObservation(command.id(), command.digest(), budgetReversalStatus, at,
                        applied ? command.consumed().ledgerRevision() + 1 : null, applied ? "synthetic-budget-reversal-" + command.id() : null,
                        applied ? command.period().periodReference() : null, applied ? command.period().request().accountingDate() : null,
                        applied ? budgetReversalAppliedAt.computeIfAbsent(command.id(), id -> at) : null,
                        budgetReversalStatus == BudgetConsumptionReversalObservation.Status.REJECTED ? BudgetConsumptionReversalObservation.Rejection.ACCOUNTING_PERIOD_CLOSED : null);
            }
            case "expense-payment-return" -> expenseReturnReceipt(json.read(data.toString(), ExpensePaymentReturnPort.Request.class));
            case "catalog" -> new FinanceCatalog("alice", "synthetic-v1", Instant.now().plusSeconds(600), List.of(new FinanceCatalog.LegalEntity(entity, "合成法人", "CNY", paperRequired, "v1", legalTimeZone)),
                    List.of(new FinanceCatalog.Category("OFFICE", "办公", List.of(ExpenseLine.Unit.ITEM))), List.of(new FinanceCatalog.CostCenter(entity, "IT", "研发")), List.of(), List.of(new FinanceCatalog.City("SH", "上海")));
            case "employee-account" -> {
                payeeReads++;
                yield invalidPayee ? Map.of("invalidFixture", true) : new EmployeeAccountPort.Account(currentPayee == null
                        ? new EmployeeAccountSnapshot(entity, "alice", "synthetic-private-account", "****1234", "a".repeat(64), "v1") : currentPayee, Instant.now().plusSeconds(600));
            }
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
            case "voucher-reversal" -> {
                var request = json.read(data.toString(), VoucherReversalPort.Request.class);
                var check = voucherReversalChecks.latest("demo", request.command().id(), "finance").orElseThrow();
                if (!request.equals(check.input().request())) throw new IllegalStateException("Synthetic reversal source changed");
                yield reversalReceipt(check, 1, request.command().id().toString());
            }
            case "voucher-reversal-command", "voucher-reversal-query" -> {
                VoucherReversalCommand command;
                if (operation.equals("voucher-reversal-command")) { reversalWrites++; command = json.read(data.path("command").toString(), VoucherReversalCommand.class); reversalCommands.put(command.id(), command); reversedOriginals.add(command.source().command().id()); }
                else { reversalQueries++; command = reversalCommands.get(UUID.fromString(data.path("operationId").asText())); }
                if (invalidReversalResponse) yield Map.of("invalidFixture", true);
                yield executionReceipt(command);
            }
            case "voucher-command", "voucher-query" -> {
                VoucherCommand command;
                if (operation.equals("voucher-command")) { command = json.read(data.path("command").toString(), VoucherCommand.class); voucherCommands.put(command.id(), command); }
                else command = voucherCommands.get(UUID.fromString(data.path("operationId").asText()));
                if (voucherMode.equals("INVALID")) yield Map.of("invalidFixture", true);
                if (voucherMode.equals("NOT_FOUND")) yield new VoucherObservation(command.id(), command.digest(), VoucherObservation.Status.NOT_FOUND, 0L, Instant.now(), null, null, null, null, null, null, null, null);
                boolean reversed = voucherMode.equals("REVERSED") || reversedOriginals.contains(command.id());
                yield new VoucherObservation(command.id(), command.digest(), reversed ? VoucherObservation.Status.REVERSED : VoucherObservation.Status.POSTED, reversed ? 2L : 1L, Instant.now(), "synthetic-posting", "synthetic-voucher", command.period().periodReference(), command.accountingDate(), command.totals().gross(), command.totals().gross(), command.createdAt(), null);
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
