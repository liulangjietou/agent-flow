package io.agentflow.expense;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.BusinessReference;
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
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * 实际发起、审批、放款后通过认证接口确认还款；回环资金和 ERP 均为明确合成服务。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "agentflow.finance-gateway.enabled=true",
        "agentflow.vouchers.preparation-worker-enabled=false", "agentflow.vouchers.worker-enabled=false", "agentflow.vouchers.reversal-execution-worker-enabled=false", "agentflow.budgets.worker-enabled=false",
        "agentflow.payments.worker-enabled=false", "agentflow.payments.request-worker-enabled=false", "agentflow.payments.payee-review-worker-enabled=false",
        "agentflow.invoices.verification-worker-enabled=false", "agentflow.expenses.precheck-worker-enabled=false", "agentflow.expenses.settlement-worker-enabled=false",
        "agentflow.expenses.archive-worker-enabled=false", "agentflow.advance-requests.precheck-worker-enabled=false", "agentflow.advances.repayment-worker-enabled=false", "agentflow.advances.repayment-review-worker-enabled=false", "agentflow.advances.disbursement-return-worker-enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class AdvanceRepaymentIntegrationTest {
    private static final AtomicReference<AdvanceRepaymentIntegrationTest> ACTIVE = new AtomicReference<>();
    private static final HttpServer SERVER = server();
    private static final String ENDPOINT = "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/finance";
    private final Actor admin = new Actor("demo", "admin", Set.of("ADMIN", "PROCESS_ADMIN"));
    private final Map<UUID, VoucherCommand> voucherCommands = new ConcurrentHashMap<>();
    private final Map<UUID, PaymentCommand> paymentCommands = new ConcurrentHashMap<>();
    private final Map<String, AdvanceRepaymentPort.Receipt> receipts = new ConcurrentHashMap<>();
    private final List<UUID> ownedAdvances = new ArrayList<>();
    private UUID entity, appointment, financePerson, financeAppointment;
    private int paymentWrites, voucherWrites, repaymentReads;
    private String amount = "25";
    private AdvanceRepaymentPort.Status receiptStatus = AdvanceRepaymentPort.Status.CONFIRMED;
    private long receiptRevision = 1;
    private boolean unavailable;
    private AdvanceRepaymentPort.Channel channel = AdvanceRepaymentPort.Channel.BANK_TRANSFER;
    private String transactionReference, postingReference;
    private AdvanceRepaymentAdjustmentPort.Status reviewStatus = AdvanceRepaymentAdjustmentPort.Status.CONFIRMED;
    private long reviewRevision = 1;
    private boolean reviewUnavailable;
    private String returnReference;
    private List<AdvanceRepaymentAdjustmentPort.ReturnItem> partialReturns;
    private final Map<UUID, AdvanceRepaymentAdjustmentPort.Receipt> returnFacts = new HashMap<>();
    private VoucherObservation.Status voucherQueryStatus = VoucherObservation.Status.POSTED;
    private long voucherQueryRevision = 1;
    private boolean reverseVoucherBeforeReceipt, queryVoucherBeforeReceipt;
    private PaymentObservation.Status paymentStatus = PaymentObservation.Status.SUCCEEDED;
    private long paymentRevision = 1, disbursementRevision = 1;
    private AdvanceDisbursementReturnPort.Status disbursementStatus = AdvanceDisbursementReturnPort.Status.CONFIRMED;
    private List<AdvanceDisbursementReturnPort.ReturnItem> disbursementFacts = List.of();
    @Autowired io.agentflow.notification.DisbursementReturnNotificationAccess disbursementNoticeAccess;
    @Autowired io.agentflow.notification.NotificationPreferencesService notificationPreferences;
    @Autowired io.agentflow.notification.NotificationDeliveryService notificationDeliveries;
    @Autowired io.agentflow.notification.JdbcNotificationDeliveryStore notificationDeliveryStore;
    @Autowired org.springframework.context.ApplicationEventPublisher events;
    @Autowired AdvanceDisbursementReturnWorker disbursementWorker;
    @Autowired AdvanceDisbursementReturnService disbursementService;
    @Autowired JdbcDisbursementReturnCheckRepository disbursementChecks;
    @Autowired JdbcDisbursementResolutionRepository disbursementDecisions;
    @Autowired JdbcPaymentAuthorizationRepository paymentAuthorizations;
    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @Autowired AuthService auth;
    @Autowired CurrentActor actors;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired OrganizationService organization;
    @Autowired DefinitionApplicationService definitions;
    @Autowired ApplicationRepository applications;
    @Autowired AdvanceRequestRepository requests;
    @Autowired EmployeeAdvanceRepository balances;
    @Autowired ExpenseReportRepository reports;
    @Autowired TaskService tasks;
    @Autowired AdvanceRequestCheckWorker precheckWorker;
    @Autowired VoucherPreparationWorker preparationWorker;
    @Autowired VoucherOperationWorker voucherWorker;
    @Autowired VoucherOperationService voucherExecution;
    @Autowired JdbcVoucherOperationRepository vouchers;
    @Autowired VoucherReversalPreparationService reversalPreparing;
    @Autowired VoucherReversalService externalReversalChecks;
    @Autowired VoucherReversalRetirementService reversalRetirement;
    @Autowired JdbcVoucherReversalOperationRepository reversalOperations;
    @Autowired JdbcVoucherReversalPreparationRepository reversalPreparations;
    @Autowired PaymentExecutionRequestWorker executionWorker;
    @Autowired PaymentOperationWorker paymentWorker;
    @Autowired JdbcPaymentOperationRepository payments;
    @Autowired FinanceGatewayConfiguration configuration;
    @Autowired AdvanceRepaymentWorker worker;
    @Autowired AdvanceRepaymentService service;
    @Autowired JdbcAdvanceRepaymentCheckRepository checks;
    @Autowired JdbcAdvanceRepaymentRepository repayments;
    @Autowired AdvanceRepaymentReviewWorker reviewWorker;
    @Autowired AdvanceRepaymentReviewService reviewService;
    @Autowired JdbcRepaymentReviewCheckRepository reviewChecks;
    @Autowired JdbcRepaymentResolutionRepository resolutions;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry values) {
        values.add("agentflow.finance-gateway.tenants.demo.endpoint", () -> ENDPOINT);
        values.add("agentflow.finance-gateway.tenants.demo.allow-unauthenticated-loopback", () -> "true");
        values.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_REPAYMENT_TEST_URL", "jdbc:h2:mem:advance-repayments;DB_CLOSE_DELAY=-1"));
        values.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_REPAYMENT_TEST_USER", "sa"));
        values.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_REPAYMENT_TEST_PASSWORD", ""));
        values.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_REPAYMENT_TEST_DRIVER", "org.h2.Driver"));
    }
    @BeforeEach void setup() {
        ACTIVE.set(this); configuration.setEnabled(true); configuration.getTenants().get("demo").setEndpoint(ENDPOINT);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM organization_directory WHERE tenant_id='demo'", Integer.class) == 0) organization.initialize(admin);
        entity = organization.createUnit(admin, OrganizationUnit.Kind.LEGAL_ENTITY, "还款验收法人", null, null, true).id();
        var department = organization.createUnit(admin, OrganizationUnit.Kind.DEPARTMENT, "合成部门", entity, null, true);
        var position = organization.createUnit(admin, OrganizationUnit.Kind.POSITION, "合成岗位", entity, null, true);
        appointment = organization.createAppointment(admin, person("alice", false), department.id(), position.id(), true).id();
        financePerson = person("finance", true); financeAppointment = organization.createAppointment(admin, financePerson, department.id(), position.id(), true).id();
        organization.createAppointment(admin, person("cashier", false), department.id(), position.id(), true);
    }
    @AfterEach void clear() {
        actors.clear();
        jdbc.update("UPDATE organization_appointment SET active=TRUE WHERE tenant_id='demo' AND id=?", financeAppointment.toString());
        for (UUID loan : ownedAdvances) for (var id : jdbc.queryForList("SELECT id FROM advance_repayment_check WHERE tenant_id='demo' AND advance_id=? AND status IN ('QUEUED','RUNNING')", String.class, loan.toString())) {
            var job = checks.find("demo", UUID.fromString(id)).orElseThrow();
            if (job.status() == AdvanceRepaymentCheck.Status.QUEUED) job = service.claim("demo", job.input().id(), Instant.now());
            if (job != null) service.fail(job, Instant.now());
        }
    }
    @AfterAll static void stopServer() { SERVER.stop(0); }

    @ParameterizedTest @EnumSource(value = VoucherCommand.Kind.class, names = {"EMPLOYEE_ADVANCE", "PAYMENT"})
    void authorizedReversalImmediatelyFreezesExistingLoanWithoutChangingRepaymentsOrReservations(VoucherCommand.Kind kind) throws Exception {
        var loan = paidLoan(); repay(loan, "before-reversal-authorization"); reserve(loan, "20"); var before = balance(loan);
        var command = vouchers.forRound("demo", app(loan).id(), 1, kind).orElseThrow().input().command();
        authorizeLoanReversal(loan, kind);
        var held = balance(loan); assertThat(held.voucherReviews()).containsExactly(command.id()); assertThat(held.available()).isEqualTo(money("0"));
        assertThat(held.outstanding()).isEqualTo(before.outstanding()); assertThat(held.balance()).isEqualTo(before.balance()); assertThat(held.repayments()).isEqualTo(before.repayments());
        queryVoucher(loan, kind); assertThat(balance(loan).voucherReviews()).containsExactly(command.id()); assertThat(balance(loan).available()).isEqualTo(money("0"));
    }

    @Test void reversalRetirementReleasesOnlyItsLoanVoucherAndPreservesMoneyAndOtherHold() throws Exception {
        var loan = paidLoan(); repay(loan, "before-safe-retirement"); reserve(loan, "20"); var before = balance(loan);
        authorizeLoanReversal(loan, VoucherCommand.Kind.EMPLOYEE_ADVANCE); authorizeLoanReversal(loan, VoucherCommand.Kind.PAYMENT);
        var accrual = vouchers.forRound("demo", app(loan).id(), 1, VoucherCommand.Kind.EMPLOYEE_ADVANCE).orElseThrow().input().command().id();
        var payment = vouchers.forRound("demo", app(loan).id(), 1, VoucherCommand.Kind.PAYMENT).orElseThrow().input().command().id();
        assertThat(balance(loan).voucherReviews()).containsExactlyInAnyOrder(accrual, payment);
        retireLoanReversal(loan, VoucherCommand.Kind.EMPLOYEE_ADVANCE);
        assertThat(balance(loan).voucherReviews()).containsExactly(payment); assertThat(balance(loan).available()).isEqualTo(money("0"));
        assertThat(vouchers.requiresAdvanceReview("demo", accrual)).isFalse(); assertThat(vouchers.requiresAdvanceReview("demo", payment)).isTrue();
        retireLoanReversal(loan, VoucherCommand.Kind.PAYMENT);
        queryVoucher(loan, VoucherCommand.Kind.EMPLOYEE_ADVANCE); queryVoucher(loan, VoucherCommand.Kind.PAYMENT);
        var after = balance(loan); assertThat(after.voucherReviews()).isEmpty(); assertThat(after.available()).isEqualTo(before.available());
        assertThat(after.outstanding()).isEqualTo(before.outstanding()); assertThat(after.balance()).isEqualTo(before.balance()); assertThat(after.repayments()).isEqualTo(before.repayments());
        assertThat(vouchers.requiresAdvanceReview("demo", payment)).isFalse(); assertThat(reversalOperations.retirements("demo", payment)).hasSize(1);
    }
    @ParameterizedTest @EnumSource(value = VoucherCommand.Kind.class, names = {"EMPLOYEE_ADVANCE", "PAYMENT"})
    void originalExternalReversalCheckNotificationUsesTheLoanRoundAndPreservesItsHold(VoucherCommand.Kind kind) throws Exception {
        var loan = paidLoan(); voucherQueryStatus = VoucherObservation.Status.REVERSED; voucherQueryRevision = 2; queryVoucher(loan, kind);
        var original = vouchers.forRound("demo", app(loan).id(), 1, kind).orElseThrow(); var held = balance(loan); UUID id;
        actors.set(new Actor("demo", "finance", Set.of("EMPLOYEE", "APPROVER", "FINANCE")));
        try { id = externalReversalChecks.queue(app(loan).id(), original.input().command().id(), new VoucherReversalService.QueryInput(1, app(loan).version(), request(loan).version(), original.version(), "明确查询原借款反向凭证")).checkId(); }
        finally { actors.clear(); }
        var claimed = externalReversalChecks.claim("demo", id, Instant.now()); externalReversalChecks.fail(claimed, Instant.now());
        String key = "reversal-check:" + id + ":UNAVAILABLE";
        assertThat(jdbc.queryForList("SELECT recipient_id FROM notification_inbox WHERE tenant_id='demo' AND event_key=?", String.class, key)).containsExactlyInAnyOrder("alice", "finance");
        String message = jdbc.queryForObject("SELECT id FROM notification_inbox WHERE tenant_id='demo' AND event_key=? AND recipient_id='alice'", String.class, key);
        var detail = ok(read("/api/v1/notifications/" + message + "/reversal-check-target", "alice"), 200);
        assertThat(detail.path("kind").asText()).isEqualTo(kind.name()); assertThat(detail.path("businessId").asText()).isEqualTo(loan.toString());
        assertThat(detail.path("record").isNull()).isTrue(); assertThat(balance(loan).state()).isEqualTo(held.state());
    }

    private void authorizeLoanReversal(UUID loan, VoucherCommand.Kind kind) {
        var original = vouchers.forRound("demo", app(loan).id(), 1, kind).orElseThrow(); var command = original.input().command();
        UUID id;
        actors.set(new Actor("demo", "finance", Set.of("EMPLOYEE", "APPROVER", "FINANCE")));
        try { id = reversalPreparing.prepare(app(loan).id(), command.id(), new VoucherReversalPreparationService.PrepareInput(1, app(loan).version(), request(loan).version(), original.version(), command.accountingDate(), "LOAN-REVERSE-PROOF", "明确核对原借款凭证")).preparationId(); }
        finally { actors.clear(); }
        var claimed = reversalPreparing.claim("demo", id, Instant.now()); var now = Instant.now(); var fact = original.observation();
        var verified = new VoucherObservation(command.id(), command.digest(), VoucherObservation.Status.POSTED, fact.revision(), now, fact.postingReference(), fact.voucherReference(), fact.periodReference(), fact.accountingDate(), fact.debitTotal(), fact.creditTotal(), fact.postedAt(), null);
        var period = new AccountingPeriodPort.OpenPeriod(new AccountingPeriodPort.Request(command.legalEntityId(), "CNY", command.accountingDate()), "new-reverse-period", "v2", command.accountingDate(), command.accountingDate(), now, now.plusSeconds(300));
        reversalPreparing.finish(claimed, new FinanceResult.Success<>(verified), new FinanceResult.Success<>(period), Instant.now());
        var prepared = reversalPreparations.find("demo", id).orElseThrow(); assertThat(prepared.status()).isEqualTo(VoucherReversalPreparation.Status.READY);
        actors.set(new Actor("demo", "finance", Set.of("EMPLOYEE", "APPROVER", "FINANCE")));
        try { reversalPreparing.authorize(app(loan).id(), command.id(), new VoucherReversalPreparationService.AuthorizeInput(1, app(loan).version(), request(loan).version(), original.version(), id, prepared.version(), "确认完整冲销，原资金另行办理")); }
        finally { actors.clear(); }
    }
    private void retireLoanReversal(UUID loan, VoucherCommand.Kind kind) throws Exception {
        queryVoucher(loan, kind); var original = vouchers.forRound("demo", app(loan).id(), 1, kind).orElseThrow();
        var reversal = reversalOperations.forOriginal("demo", original.input().command().id()).orElseThrow();
        actors.set(new Actor("demo", "finance", Set.of("EMPLOYEE", "APPROVER", "FINANCE")));
        try { reversalRetirement.retire(app(loan).id(), original.input().command().id(), new VoucherReversalRetirementService.Input(1, app(loan).version(), request(loan).version(), original.version(),
                reversal.input().command().id(), reversal.version(), "LOAN-RETIRE-PROOF", "核对原件后只结束本次未发送冲销")); }
        finally { actors.clear(); }
        String key = "reversal:" + reversal.input().command().id() + ":RETIRED";
        assertThat(jdbc.queryForList("SELECT recipient_id FROM notification_inbox WHERE tenant_id='demo' AND event_key=?", String.class, key)).containsExactlyInAnyOrder("alice", "finance");
        String message = jdbc.queryForObject("SELECT id FROM notification_inbox WHERE tenant_id='demo' AND event_key=? AND recipient_id='alice'", String.class, key);
        var detail = ok(read("/api/v1/notifications/" + message + "/reversal-target", "alice"), 200);
        assertThat(detail.path("kind").asText()).isEqualTo(kind.name()); assertThat(detail.path("reversalId").asText()).isEqualTo(reversal.input().command().id().toString());
        assertThat(detail.at("/retirement/basis").asText()).isEqualTo("NEVER_DISPATCHED");
    }

    @ParameterizedTest @EnumSource(value = VoucherCommand.Kind.class, names = {"EMPLOYEE_ADVANCE", "PAYMENT"})
    void originalVoucherReversalFreezesLoanAndOnlyExplicitPostingResolutionRestoresIt(VoucherCommand.Kind kind) throws Exception {
        var loan = paidLoan(); repay(loan, "kept-employee-repayment"); reserve(loan, "20"); var before = balance(loan);
        voucherQueryStatus = VoucherObservation.Status.REVERSED; voucherQueryRevision = 2; queryVoucher(loan, kind);
        assertThat(balance(loan).available()).as("An advance cannot be reused while its original accounting voucher is reversed").isEqualTo(money("0"));
        assertThat(balance(loan).status().name()).isEqualTo("VOUCHER_REVIEW");
        assertThat(balance(loan).outstanding()).isEqualTo(before.outstanding()); assertThat(balance(loan).repaid()).isEqualTo(before.repaid());
        assertThat(balance(loan).balance()).isEqualTo(before.balance());
        var blocked = query(loan, "held-voucher-repayment"); worker.poll();
        assertThat(view(loan, "finance").at("/latestCheck/confirmationIssue").asText()).isEqualTo("ADVANCE_VOUCHER_REVIEW_REQUIRED");
        code(send(path(loan) + "/repayments", "finance", recordInput(loan, blocked)), "ADVANCE_VOUCHER_REVIEW_REQUIRED");
        voucherQueryStatus = VoucherObservation.Status.POSTED; voucherQueryRevision = 3; queryVoucher(loan, kind);
        assertThat(balance(loan).status().name()).isEqualTo("VOUCHER_REVIEW");
        var voucher = vouchers.forRound("demo", app(loan).id(), 1, kind).orElseThrow();
        assertThat(voucher.status()).isEqualTo(VoucherOperation.Status.RECONCILING);
        ok(send("/api/v1/applications/" + app(loan).id() + "/vouchers/" + voucher.input().command().id() + "/dispute-resolutions", "finance", Map.of(
                "roundNo", 1, "applicationVersion", app(loan).version(), "businessVersion", request(loan).version(), "operationVersion", voucher.version(),
                "outcome", "POSTED", "evidenceReference", "restored-original-posting", "comment", "独立核对原凭证有效过账")), 202);
        assertThat(balance(loan).available()).isEqualTo(before.available()); assertThat(balance(loan).status()).isEqualTo(before.status());
        assertThat(balance(loan).balance()).isEqualTo(before.balance()); assertThat(balance(loan).repayments()).isEqualTo(before.repayments());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void incomingSuccessfulPaymentInheritsEarlierVoucherHoldIncludingDuringRequery(boolean requery) throws Exception {
        reverseVoucherBeforeReceipt = true; queryVoucherBeforeReceipt = requery; var loan = paidLoan();
        var original = vouchers.forRound("demo", app(loan).id(), 1, VoucherCommand.Kind.EMPLOYEE_ADVANCE).orElseThrow();
        assertThat(balance(loan).voucherReviews()).containsExactly(original.input().command().id());
        assertThat(balance(loan).balance().limit()).isEqualTo(money("100")); assertThat(balance(loan).available()).isEqualTo(money("0"));
        var before = balance(loan).state(); queryOriginalPayment(loan); assertThat(balance(loan).state()).isEqualTo(before);
    }

    @Test void disbursementReturnNotificationFailureRetainsOriginalQueryAfterFreshConfirmation() throws Exception {
        var loan = paidLoan(); var check = disbursementReview(loan); var before = balance(loan).state();
        var claimed = disbursementService.claim("demo", check, Instant.now()); disbursementService.fail(claimed, Instant.now());
        assertThat(disbursementNoticeRecipients(check, "UNAVAILABLE")).containsExactlyInAnyOrder("alice", "finance");
        var original = disbursementNoticePath(check, "UNAVAILABLE", "alice");
        var next = disbursementReview(loan); disbursementWorker.poll();
        assertThat(disbursementNoticeRecipients(next, "RESOLVED")).isEmpty();
        var response = read(original, "alice"); assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        var detail = ok(response, 200); assertThat(detail.path("checkId").asText()).isEqualTo(check.toString());
        assertThat(detail.path("status").asText()).isEqualTo("UNAVAILABLE"); assertThat(detail.path("observation").isNull()).isTrue();
        assertThat(detail.path("resolution").isNull()).isTrue(); assertThat(balance(loan).state()).isEqualTo(before);
    }

    @Test void disbursementReturnNotificationCandidateCannotClaimDebtHasBeenAdjusted() throws Exception {
        var loan = paidLoan(); disbursementStatus = AdvanceDisbursementReturnPort.Status.PARTIALLY_RETURNED;
        disbursementFacts = List.of(bankReturn("notice-candidate", "20")); var check = disbursementReview(loan); disbursementWorker.poll();
        assertThat(disbursementNoticeRecipients(check, "RETURN_REVIEW")).containsExactlyInAnyOrder("alice", "finance");
        var detail = ok(read(disbursementNoticePath(check, "RETURN_REVIEW", "finance"), "finance"), 200);
        assertThat(detail.path("status").asText()).isEqualTo("CHECKED"); assertThat(detail.at("/observation/outcome").asText()).isEqualTo("PARTIALLY_RETURNED");
        assertThat(detail.path("resolution").isNull()).isTrue(); assertThat(balance(loan).returnedDisbursements()).isEqualTo(money("0"));
        assertThat(balance(loan).outstanding()).isEqualTo(money("100"));
        assertThat(detail.toString()).doesNotContain("accountDigest", "amount", "transactionReference", "evidenceReference", "canResolve");
    }

    @Test void disbursementReturnNotificationDecisionUsesOwnCheckAndDeduplicatesReplay() throws Exception {
        var loan = paidLoan(); disbursementStatus = AdvanceDisbursementReturnPort.Status.PARTIALLY_RETURNED;
        disbursementFacts = List.of(bankReturn("notice-decision", "20")); var check = disbursementReview(loan); disbursementWorker.poll();
        var input = disbursementInput(loan, check); var key = UUID.randomUUID().toString();
        var action = ok(send(path(loan) + "/disbursement-resolutions", "finance", key, input), 202);
        assertThat(ok(send(path(loan) + "/disbursement-resolutions", "finance", key, input), 202)).isEqualTo(action);
        assertThat(disbursementNoticeRecipients(check, "RESOLVED")).containsExactlyInAnyOrder("alice", "finance");
        for (String fact : List.of("RETURN_REVIEW", "RESOLVED")) {
            var detail = ok(read(disbursementNoticePath(check, fact, "alice"), "alice"), 200);
            assertThat(detail.path("checkId").asText()).isEqualTo(check.toString()); assertThat(detail.path("status").asText()).isEqualTo("RESOLVED");
            assertThat(detail.at("/resolution/id")).isEqualTo(action.path("resolutionId")); assertThat(detail.at("/resolution/advanceVersion")).isEqualTo(action.path("advanceVersion"));
        }
        assertThat(balance(loan).returnedDisbursements()).isEqualTo(money("20")); assertThat(balance(loan).repaid()).isEqualTo(money("0"));
        var originalPath = disbursementNoticePath(check, "RESOLVED", "alice"); var original = ok(read(originalPath, "alice"), 200);
        disbursementRevision++; disbursementFacts = List.of(disbursementFacts.get(0), bankReturn("notice-later", "10"));
        var next = disbursementReview(loan); disbursementWorker.poll(); ok(send(path(loan) + "/disbursement-resolutions", "finance", disbursementInput(loan, next)), 202);
        assertThat(ok(read(originalPath, "alice"), 200)).isEqualTo(original);
        var resolution = UUID.fromString(action.path("resolutionId").asText()); assertThat(disbursementDecisions.find("foreign", resolution)).isEmpty();
        for (String column : List.of("advance_version", "check_version")) {
            long version = action.path(column.equals("advance_version") ? "advanceVersion" : "checkVersion").asLong();
            jdbc.update("UPDATE advance_disbursement_resolution SET " + column + "=? WHERE tenant_id='demo' AND id=?", version - 1, resolution.toString());
            try { assertThatThrownBy(() -> disbursementDecisions.find("demo", resolution)).isInstanceOf(io.agentflow.common.DomainException.class); }
            finally { jdbc.update("UPDATE advance_disbursement_resolution SET " + column + "=? WHERE tenant_id='demo' AND id=?", version, resolution.toString()); }
        }

    }

    @Test void disbursementReturnNotificationUnknownNeedsExplicitConfirmationWithoutInventingFunds() throws Exception {
        var loan = paidLoan(); var principal = balance(loan).balance().limit(); var outgoing = paymentWrites; var postings = voucherWrites;
        disbursementStatus = AdvanceDisbursementReturnPort.Status.UNRESOLVED; var unknown = disbursementReview(loan); disbursementWorker.poll();
        assertThat(disbursementNoticeRecipients(unknown, "UNRESOLVED")).containsExactlyInAnyOrder("alice", "finance");
        assertThat(balance(loan).paymentReviewRequired()).isTrue();
        disbursementRevision++; disbursementStatus = AdvanceDisbursementReturnPort.Status.CONFIRMED;
        var confirmed = disbursementReview(loan); disbursementWorker.poll(); assertThat(disbursementNoticeRecipients(confirmed, "RESOLVED")).isEmpty();
        assertThat(balance(loan).paymentReviewRequired()).isTrue();
        ok(send(path(loan) + "/disbursement-resolutions", "finance", disbursementInput(loan, confirmed)), 202);
        assertThat(disbursementNoticeRecipients(confirmed, "RESOLVED")).containsExactlyInAnyOrder("alice", "finance");
        assertThat(ok(read(disbursementNoticePath(confirmed, "RESOLVED", "alice"), "alice"), 200).at("/resolution/outcome").asText()).isEqualTo("CONFIRMED");
        assertThat(ok(read(disbursementNoticePath(unknown, "UNRESOLVED", "alice"), "alice"), 200).path("resolution").isNull()).isTrue();
        assertThat(balance(loan).paymentReviewRequired()).isFalse(); assertThat(balance(loan).outstanding()).isEqualTo(principal);
        assertThat(balance(loan).disbursementReturns()).isEmpty(); assertThat(paymentWrites).isEqualTo(outgoing); assertThat(voucherWrites).isEqualTo(postings);
    }

    @Test void disbursementReturnNotificationCurrentFieldsRoleTenantAndExactFactGuardReads() throws Exception {
        var loan = paidLoan(); disbursementStatus = AdvanceDisbursementReturnPort.Status.UNRESOLVED;
        var check = disbursementReview(loan); disbursementWorker.poll(); var own = disbursementNoticePath(check, "UNRESOLVED", "finance");
        var messageId = UUID.fromString(own.split("/")[4]); var before = balance(loan).state();
        assertThat(ok(read(own, "finance"), 200).path("resolution").isNull()).isTrue();
        for (String actor : List.of("alice", "admin", "cashier", "bob")) assertThat(read(own, actor).getStatus()).isIn(403, 404);
        assertThat(read(own + "?advanceId=" + loan, "finance").getStatus()).isEqualTo(400);
        actors.set(new Actor("demo", "finance", Set.of("ADMIN")));
        try { assertThatThrownBy(() -> disbursementNoticeAccess.target(messageId)).isInstanceOf(io.agentflow.common.DomainException.class); }
        finally { actors.clear(); }
        actors.set(new Actor("foreign", "finance", Set.of("FINANCE", "APPROVER")));
        try { assertThatThrownBy(() -> disbursementNoticeAccess.target(messageId)).isInstanceOfSatisfying(io.agentflow.common.DomainException.class,
                failure -> assertThat(failure.code()).isEqualTo("NOT_FOUND")); } finally { actors.clear(); }
        String event = "disbursement-return:" + check + ":UNRESOLVED";
        jdbc.update("UPDATE notification_inbox SET event_key=? WHERE id=?", "disbursement-return:" + check + ":RETURN_REVIEW", messageId.toString());
        try { assertThat(read(own, "finance").getStatus()).isEqualTo(404); }
        finally { jdbc.update("UPDATE notification_inbox SET event_key=? WHERE id=?", event, messageId.toString()); }
        new TransactionTemplate(transactions).executeWithoutResult(status -> events.publishEvent(new AdvanceDisbursementReturnChanged(disbursementChecks.find("demo", check).orElseThrow())));
        assertThat(disbursementNoticeRecipients(check, "UNRESOLVED")).containsExactlyInAnyOrder("alice", "finance");
        assertThat(balance(loan).state()).isEqualTo(before); assertThat(jdbc.queryForObject("SELECT read_at FROM notification_inbox WHERE id=?", java.sql.Timestamp.class, messageId.toString())).isNull();
    }

    @Test void disbursementReturnNotificationExpiredLeaseAndRevokedAppointmentCannotUseOldEvidence() throws Exception {
        var loan = paidLoan(); var first = disbursementReview(loan); var before = balance(loan).state();
        var claimed = disbursementService.claim("demo", first, Instant.now()); assertThat(disbursementService.claim("demo", first, claimed.leaseUntil())).isNull();
        disbursementService.fail(claimed, claimed.leaseUntil().plusSeconds(1));
        assertThat(disbursementNoticeRecipients(first, "UNAVAILABLE")).containsExactlyInAnyOrder("alice", "finance");
        var queued = disbursementReview(loan); jdbc.update("UPDATE organization_appointment SET active=FALSE WHERE tenant_id='demo' AND id=?", financeAppointment.toString());
        try {
            disbursementWorker.poll(); assertThat(disbursementNoticeRecipients(queued, "SOURCE_CHANGED")).containsExactly("alice");
            assertThat(ok(read(disbursementNoticePath(queued, "SOURCE_CHANGED", "alice"), "alice"), 200).path("status").asText()).isEqualTo("VOIDED");
            assertThat(balance(loan).state()).isEqualTo(before);
        } finally { jdbc.update("UPDATE organization_appointment SET active=TRUE WHERE tenant_id='demo' AND id=?", financeAppointment.toString()); }
    }

    @Test void disbursementReturnNotificationFailureRollsBackDecisionBalanceCreditsAndDispatch() throws Exception {
        var loan = paidLoan(); disbursementStatus = AdvanceDisbursementReturnPort.Status.PARTIALLY_RETURNED;
        disbursementFacts = List.of(bankReturn("notice-atomic", "20")); var check = disbursementReview(loan); disbursementWorker.poll();
        var checked = disbursementChecks.find("demo", check).orElseThrow(); var before = balance(loan).state();
        var finance = new Actor("demo", "finance", Set.of("FINANCE", "APPROVER")); var applicant = new Actor("demo", "alice", Set.of("EMPLOYEE"));
        var financePreference = notificationPreferences.get(finance); var applicantPreference = notificationPreferences.get(applicant);
        notificationPreferences.revise(finance, financePreference.version(), true, false); notificationPreferences.revise(applicant, applicantPreference.version(), true, false);
        String event = "disbursement-return:" + check + ":RESOLVED";
        try {
            jdbc.execute("ALTER TABLE notification_inbox ADD CONSTRAINT disbursement_notice_failure CHECK (NOT (event_key='" + event + "' AND recipient_id='finance'))");
            actors.set(finance);
            try { assertThatThrownBy(() -> disbursementService.resolve(loan, new AdvanceDisbursementReturnService.ResolveInput(balance(loan).version(), check,
                    checked.version(), checked.receipt().status(), "notice-proof", "核对本次原放款退回"))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class); }
            finally { actors.clear(); jdbc.execute("ALTER TABLE notification_inbox DROP CONSTRAINT disbursement_notice_failure"); }
            assertThat(balance(loan).state()).isEqualTo(before); assertThat(disbursementChecks.find("demo", check)).contains(checked); assertThat(disbursementDecisions.latest("demo", loan)).isEmpty();
            assertThat(disbursementNoticeRecipients(check, "RESOLVED")).isEmpty();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM advance_receipt_credit WHERE tenant_id='demo' AND advance_id=?", Integer.class, loan.toString())).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finance_receipt_credit WHERE tenant_id='demo' AND business_id=?", Integer.class, loan.toString())).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='DISBURSEMENT_RETURN_RESOLVE'", Integer.class, app(loan).id().toString())).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_dispatch d JOIN notification_inbox i ON i.id=d.inbox_id WHERE i.event_key=?", Integer.class, event)).isZero();
            ok(send(path(loan) + "/disbursement-resolutions", "finance", disbursementInput(loan, check)), 202);
            var own = disbursementNoticePath(check, "RESOLVED", "finance");
            UUID deliveryId = UUID.fromString(jdbc.queryForObject("SELECT id FROM notification_dispatch WHERE inbox_id=? AND channel='EMAIL'", String.class, own.split("/")[4]));
            jdbc.update("UPDATE organization_appointment SET active=FALSE WHERE tenant_id='demo' AND id=?", financeAppointment.toString());
            try {
                assertThat(read(own, "finance").getStatus()).isEqualTo(404); assertThat(notificationDeliveries.claim(deliveryId, Instant.now())).isNull();
                assertThat(notificationDeliveryStore.find(deliveryId).orElseThrow().progress().errorCode().name()).isEqualTo("MESSAGE_UNAVAILABLE");
            } finally { jdbc.update("UPDATE organization_appointment SET active=TRUE WHERE tenant_id='demo' AND id=?", financeAppointment.toString()); }
        } finally {
            notificationPreferences.revise(finance, notificationPreferences.get(finance).version(), financePreference.emailEnabled(), financePreference.enterpriseImEnabled());
            notificationPreferences.revise(applicant, notificationPreferences.get(applicant).version(), applicantPreference.emailEnabled(), applicantPreference.enterpriseImEnabled());
        }
    }

    private List<String> disbursementNoticeRecipients(UUID check, String fact) {
        return jdbc.queryForList("SELECT recipient_id FROM notification_inbox WHERE tenant_id='demo' AND event_key=? ORDER BY recipient_id", String.class,
                "disbursement-return:" + check + ":" + fact);
    }
    private String disbursementNoticePath(UUID check, String fact, String recipient) {
        return "/api/v1/notifications/" + jdbc.queryForObject("SELECT id FROM notification_inbox WHERE tenant_id='demo' AND event_key=? AND recipient_id=?", String.class,
                "disbursement-return:" + check + ":" + fact, recipient) + "/disbursement-return-target";
    }

    @Test void bankReturnsAreCumulativeAndDistinctFromRepaymentsAndReservations() throws Exception {
        var loan = paidLoan(); amount = "20"; repay(loan, "employee-paid"); reserve(loan, "20");
        int outgoing = paymentWrites, posting = voucherWrites;
        var first = bankReturn("bank-first", "40"); disbursementStatus = AdvanceDisbursementReturnPort.Status.PARTIALLY_RETURNED; disbursementFacts = List.of(first);
        var check = disbursementReview(loan); disbursementWorker.poll();
        assertThat(balance(loan).returnedDisbursements()).isEqualTo(money("0")); assertThat(balance(loan).paymentReviewRequired()).isTrue();
        assertThat(disbursementView(loan, "finance").at("/latestCheck/canResolve").asBoolean()).isTrue();
        var input = disbursementInput(loan, check); var key = UUID.randomUUID().toString();
        var accepted = send(path(loan) + "/disbursement-resolutions", "finance", key, input); ok(accepted, 202);
        assertThat(send(path(loan) + "/disbursement-resolutions", "finance", key, input).getContentAsString()).isEqualTo(accepted.getContentAsString());
        var preserved = balance(loan).disbursementReturns().get(0);
        disbursementRevision = 2; disbursementFacts = List.of(bankReturn("bank-second", "20"), first);
        var second = disbursementReview(loan); disbursementWorker.poll(); ok(send(path(loan) + "/disbursement-resolutions", "finance", disbursementInput(loan, second)), 202);
        var value = balance(loan); assertThat(value.disbursementReturns()).contains(preserved).hasSize(2);
        assertThat(value.repaid()).isEqualTo(money("20")); assertThat(value.returnedDisbursements()).isEqualTo(money("60"));
        assertThat(value.outstanding()).isEqualTo(money("20")); assertThat(value.available()).isEqualTo(money("0")); assertThat(value.paymentReviewRequired()).isFalse();
        var owner = disbursementView(loan, "alice"); assertThat(owner.path("latestCheck").isNull()).isTrue(); assertThat(owner.path("canQuery").asBoolean()).isFalse();
        assertThat(owner.path("returns")).hasSize(2); assertThat(owner.toString()).doesNotContain("accountDigest", "private-account", "targetDigest", "debitAccount");
        disbursementReview(loan); disbursementWorker.poll(); queryOriginalPayment(loan);
        assertThat(balance(loan).paymentReviewRequired()).isFalse(); assertThat(balance(loan).returnedDisbursements()).isEqualTo(money("60"));
        assertThat(paymentWrites).isEqualTo(outgoing); assertThat(voucherWrites).isEqualTo(posting);
    }

    @Test void repaymentThenBankReturnCannotSpendOneIncomingCreditTwiceAndRollsBackEarlierNewEntry() throws Exception {
        var employeeLoan = paidLoan(); var bankLoan = paidLoan(); amount = "20";
        var repayment = repayments.find("demo", repay(employeeLoan, "shared-incoming")).orElseThrow();
        var fresh = bankReturn("otherwise-new", "5");
        disbursementFacts = List.of(fresh, new AdvanceDisbursementReturnPort.ReturnItem(repayment.receipt().funding(), repayment.receipt().posting()));
        disbursementStatus = AdvanceDisbursementReturnPort.Status.PARTIALLY_RETURNED;
        var check = disbursementReview(bankLoan); disbursementWorker.poll(); var before = balance(bankLoan).state(); var checked = disbursementChecks.find("demo", check).orElseThrow();
        code(send(path(bankLoan) + "/disbursement-resolutions", "finance", disbursementInput(bankLoan, check)), "DISBURSEMENT_RETURN_ALREADY_RECORDED");
        assertThat(balance(bankLoan).state()).isEqualTo(before); assertThat(disbursementChecks.find("demo", check).orElseThrow()).isEqualTo(checked);
        assertThat(disbursementDecisions.latest("demo", bankLoan)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM advance_receipt_credit WHERE tenant_id='demo' AND legal_entity_id=?", Integer.class, entity.toString())).isEqualTo(1);
        assertThat(balance(employeeLoan).repaid()).isEqualTo(money("20"));
    }

    @Test void bankReturnThenRepaymentCannotSpendOneIncomingCreditTwice() throws Exception {
        var bankLoan = paidLoan(); var employeeLoan = paidLoan(); var returned = bankReturn("bank-shared", "20");
        disbursementStatus = AdvanceDisbursementReturnPort.Status.PARTIALLY_RETURNED; disbursementFacts = List.of(returned);
        var check = disbursementReview(bankLoan); disbursementWorker.poll(); ok(send(path(bankLoan) + "/disbursement-resolutions", "finance", disbursementInput(bankLoan, check)), 202);
        amount = "20"; transactionReference = returned.funding().transactionReference(); postingReference = returned.posting().voucherReference();
        var employeeCheck = query(employeeLoan, "same-bank-credit"); worker.poll(); var before = balance(employeeLoan).state();
        code(send(path(employeeLoan) + "/repayments", "finance", recordInput(employeeLoan, employeeCheck)), "ADVANCE_REPAYMENT_ALREADY_RECORDED");
        assertThat(balance(employeeLoan).state()).isEqualTo(before); assertThat(view(employeeLoan, "alice").path("records")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM advance_receipt_credit WHERE tenant_id='demo' AND legal_entity_id=?", Integer.class, entity.toString())).isEqualTo(1);
    }

    @Test void fullBankReturnRequiresOriginalPaymentResolutionAndLaterBankQueryDoesNotFreezeAgain() throws Exception {
        var loan = paidLoan(); int outgoing = paymentWrites, posting = voucherWrites;
        paymentStatus = PaymentObservation.Status.REVERSED; paymentRevision = 1; queryOriginalPayment(loan);
        UUID paymentId = originalPayment(loan); assertThat(payments.find("demo", paymentId).orElseThrow().status()).isEqualTo(PaymentOperation.Status.RECONCILING);
        disbursementStatus = AdvanceDisbursementReturnPort.Status.RETURNED; disbursementFacts = List.of(bankReturn("full-bank-return", "100"));
        var unreviewed = disbursementReview(loan); disbursementWorker.poll();
        code(send(path(loan) + "/disbursement-resolutions", "finance", disbursementInput(loan, unreviewed)), "DISBURSEMENT_RETURN_PAYMENT_UNRESOLVED");
        paymentRevision = 2; queryOriginalPayment(loan);
        ok(send("/api/v1/payments/" + paymentId + "/dispute-resolutions", "finance", Map.of("authorizationVersion", paymentAuthorizations.find("demo", paymentId).orElseThrow().version(),
                "operationVersion", payments.find("demo", paymentId).orElseThrow().version(), "outcome", "REVERSED", "evidenceReference", "original-bank-proof", "comment", "独立核对原放款状态")), 202);
        var check = disbursementReview(loan); disbursementWorker.poll(); ok(send(path(loan) + "/disbursement-resolutions", "finance", disbursementInput(loan, check)), 202);
        assertThat(balance(loan).status()).isEqualTo(EmployeeAdvance.Status.RETURNED); assertThat(balance(loan).outstanding()).isEqualTo(money("0"));
        queryOriginalPayment(loan); assertThat(balance(loan).paymentReviewRequired()).isFalse(); assertThat(balance(loan).status()).isEqualTo(EmployeeAdvance.Status.RETURNED);
        assertThat(balance(loan).repaid()).isEqualTo(money("0")); assertThat(paymentWrites).isEqualTo(outgoing); assertThat(voucherWrites).isEqualTo(posting);
        assertThat(disbursementNoticeRecipients(check, "RESOLVED")).containsExactlyInAnyOrder("alice", "finance");
        assertThat(ok(read(disbursementNoticePath(check, "RESOLVED", "alice"), "alice"), 200).at("/resolution/outcome").asText()).isEqualTo("RETURNED");
    }

    @Test void bankReturnEvidenceCannotOmitEarlierFactsOrOverrunCurrentCapacity() throws Exception {
        var loan = paidLoan(); var first = bankReturn("first-seen", "40");
        disbursementStatus = AdvanceDisbursementReturnPort.Status.PARTIALLY_RETURNED; disbursementFacts = List.of(first);
        disbursementReview(loan); disbursementWorker.poll();
        disbursementRevision = 2; disbursementFacts = List.of(bankReturn("replacement", "40"));
        var changed = disbursementReview(loan); disbursementWorker.poll(); code(send(path(loan) + "/disbursement-resolutions", "finance", disbursementInput(loan, changed)), "DISBURSEMENT_RETURN_EVIDENCE_CHANGED");
        disbursementRevision = 3; disbursementFacts = List.of(first, disbursementFacts.get(0));
        var corrected = disbursementReview(loan); disbursementWorker.poll(); ok(send(path(loan) + "/disbursement-resolutions", "finance", disbursementInput(loan, corrected)), 202);
        assertThat(balance(loan).returnedDisbursements()).isEqualTo(money("80"));
        reserve(loan, "10"); disbursementRevision = 4; disbursementFacts = List.of(first, disbursementFacts.get(1), bankReturn("too-large", "15"));
        var excessive = disbursementReview(loan); disbursementWorker.poll();
        assertThat(disbursementView(loan, "finance").at("/latestCheck/canResolve").asBoolean()).isFalse();
        assertThat(send(path(loan) + "/disbursement-resolutions", "finance", disbursementInput(loan, excessive)).getStatus()).isBetween(400, 499);
        assertThat(balance(loan).returnedDisbursements()).isEqualTo(money("80"));
    }

    @Test void bankReturnUsesCurrentPermissionsAndRejectsInjectedFinancialFields() throws Exception {
        var loan = paidLoan(); var input = Map.of("advanceVersion", balance(loan).version(), "comment", "核对银行退回");
        for (String user : List.of("alice", "cashier", "admin")) assertThat(send(path(loan) + "/disbursement-review-checks", user, input).getStatus()).as(user).isIn(403, 404);
        var injected = new HashMap<String, Object>(input); injected.put("targetDigest", "forged"); okError(send(path(loan) + "/disbursement-review-checks", "finance", injected), 400);
        code(read(path(loan) + "/disbursement-review?advanceId=" + loan, "finance"), "INVALID_DISBURSEMENT_RETURN_QUERY");
        disbursementStatus = AdvanceDisbursementReturnPort.Status.PARTIALLY_RETURNED; disbursementFacts = List.of(bankReturn("permission-proof", "20"));
        var check = disbursementReview(loan); disbursementWorker.poll(); var action = disbursementInput(loan, check); var key = UUID.randomUUID().toString();
        var mismatched = new HashMap<String, Object>(action); mismatched.put("outcome", "RETURNED"); code(send(path(loan) + "/disbursement-resolutions", "finance", mismatched), "DISBURSEMENT_RETURN_OUTCOME_CHANGED");
        ok(send(path(loan) + "/disbursement-resolutions", "finance", key, action), 202);
        jdbc.update("UPDATE organization_appointment SET active=FALSE WHERE tenant_id='demo' AND id=?", financeAppointment.toString());
        okError(send(path(loan) + "/disbursement-resolutions", "finance", key, action), 403);
        assertThat(balance(loan).returnedDisbursements()).isEqualTo(money("20"));
    }

    @Test void trueReturnPreservesOriginalAndRestoresDebtOnceWithoutSendingFundsOrPosting() throws Exception {
        var loan = paidLoan(); amount = "100"; var repayment = repay(loan, "paid-in-full"); var original = repayments.find("demo", repayment).orElseThrow();
        assertThat(balance(loan).status()).isEqualTo(EmployeeAdvance.Status.SETTLED); int outgoing = paymentWrites, posting = voucherWrites;
        reviewStatus = AdvanceRepaymentAdjustmentPort.Status.RETURNED; receiptRevision = 2; var check = review(loan, repayment); reviewWorker.poll();
        assertThat(balance(loan).repaid()).isEqualTo(money("100")); assertThat(balance(loan).repaymentReviewRequired()).isTrue();
        var candidate = reviewView(loan, repayment, "finance"); assertThat(candidate.at("/latestCheck/canResolve").asBoolean()).isTrue();
        assertThat(candidate.at("/latestCheck/evidence/fundsReturn/amount/value").asText()).isEqualTo("100.00");
        var input = resolveInput(loan, check); var key = UUID.randomUUID().toString(); var result = send(reviewPath(loan, repayment) + "/resolutions", "finance", key, input); ok(result, 202);
        assertThat(send(reviewPath(loan, repayment) + "/resolutions", "finance", key, input).getContentAsString()).isEqualTo(result.getContentAsString());
        assertThat(repayments.find("demo", repayment).orElseThrow()).isEqualTo(original); assertThat(balance(loan).receivedRepayments()).isEqualTo(money("100"));
        assertThat(balance(loan).returnedRepayments()).isEqualTo(money("100")); assertThat(balance(loan).repaid()).isEqualTo(money("0"));
        assertThat(balance(loan).outstanding()).isEqualTo(money("100")); assertThat(balance(loan).repaymentReviewRequired()).isFalse();
        var owner = reviewView(loan, repayment, "alice"); assertThat(owner.path("latestCheck").isNull()).isTrue(); assertThat(owner.path("canQuery").asBoolean()).isFalse();
        assertThat(owner.at("/original/returned/fundsReturn/amount/value").asText()).isEqualTo("100.00"); assertThat(owner.at("/latestDecision/outcome").asText()).isEqualTo("RETURNED");
        assertThat(owner.toString()).doesNotContain("targetDigest", "accountDigest", "private-account");
        receiptStatus = AdvanceRepaymentPort.Status.REVERSED; query(loan, "paid-in-full"); worker.poll();
        assertThat(balance(loan).repaymentReviewRequired()).as("Accepted original reversal must not refreeze a recorded actual return").isFalse();
        assertThat(paymentWrites).isEqualTo(outgoing); assertThat(voucherWrites).isEqualTo(posting);
    }

    @Test void confirmationOnlyReleasesThatReceiptAndNeverAnotherReceiptOrOriginalPaymentHold() throws Exception {
        var loan = paidLoan(); var first = repay(loan, "first-original"); var second = repay(loan, "second-original");
        receiptStatus = AdvanceRepaymentPort.Status.REVERSED; receiptRevision = 2; query(loan, "first-original"); worker.poll(); query(loan, "second-original"); worker.poll();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> { requests.lock("demo", loan); var value = balance(loan); long version = value.version(); value.requirePaymentReview(version); balances.update(value, version, "test", "TEST_PAYMENT_REVIEW"); });
        var firstCheck = review(loan, first); reviewWorker.poll(); ok(send(reviewPath(loan, first) + "/resolutions", "finance", resolveInput(loan, firstCheck)), 202);
        assertThat(balance(loan).repaymentReviews()).containsExactly(second); assertThat(balance(loan).paymentReviewRequired()).isTrue(); assertThat(balance(loan).available()).isEqualTo(money("0"));
        var secondCheck = review(loan, second); reviewWorker.poll(); ok(send(reviewPath(loan, second) + "/resolutions", "finance", resolveInput(loan, secondCheck)), 202);
        assertThat(balance(loan).repaymentReviewRequired()).isFalse(); assertThat(balance(loan).paymentReviewRequired()).isTrue(); assertThat(balance(loan).repaid()).isEqualTo(money("50"));
    }

    @Test void reviewRequiresCurrentFullFieldFinanceAndRechecksBeforeIdempotentReplay() throws Exception {
        var loan = paidLoan(); var repayment = repay(loan, "access"); var queryInput = Map.of("advanceVersion", balance(loan).version(), "comment", "复核");
        for (var user : List.of("alice", "admin", "cashier", "bob")) {
            assertThat(send(reviewPath(loan, repayment) + "/review-checks", user, queryInput).getStatus()).isIn(403, 404);
            if (!user.equals("alice")) assertThat(read(reviewPath(loan, repayment) + "/review", user).getStatus()).isIn(403, 404);
        }
        var forged = new LinkedHashMap<String, Object>(queryInput); forged.put("amount", money("1")); okError(send(reviewPath(loan, repayment) + "/review-checks", "finance", forged), 400);
        code(read(reviewPath(loan, repayment) + "/review?employeeId=bob", "finance"), "INVALID_REPAYMENT_REVIEW_QUERY");
        String key = UUID.randomUUID().toString(); var response = send(reviewPath(loan, repayment) + "/review-checks", "finance", key, queryInput); ok(response, 202);
        assertThat(send(reviewPath(loan, repayment) + "/review-checks", "finance", key, queryInput).getContentAsString()).isEqualTo(response.getContentAsString());
        jdbc.update("UPDATE organization_appointment SET active=FALSE WHERE tenant_id='demo' AND id=?", financeAppointment.toString());
        assertThat(send(reviewPath(loan, repayment) + "/review-checks", "finance", key, queryInput).getStatus()).isIn(403, 409);
        reviewWorker.poll(); assertThat(reviewChecks.find("demo", UUID.fromString(ok(response, 202).path("checkId").asText())).orElseThrow().status()).isEqualTo(AdvanceRepaymentReviewCheck.Status.VOIDED);
        assertThat(reviewView(loan, repayment, "finance").path("latestCheck").isNull()).isTrue();
    }

    @Test void unavailableUnresolvedAndStaleOriginalEvidenceNeverAdjustDebt() throws Exception {
        var loan = paidLoan(); var repayment = repay(loan, "stale"); var initial = balance(loan).repaid();
        reviewUnavailable = true; var unavailable = review(loan, repayment); reviewWorker.poll(); assertThat(reviewChecks.find("demo", unavailable).orElseThrow().status()).isEqualTo(AdvanceRepaymentReviewCheck.Status.UNAVAILABLE);
        assertThat(balance(loan).repaymentReviewRequired()).isFalse(); reviewUnavailable = false; reviewStatus = AdvanceRepaymentAdjustmentPort.Status.UNRESOLVED;
        var unresolved = review(loan, repayment); reviewWorker.poll(); assertThat(balance(loan).repaymentReviewRequired()).isTrue();
        code(send(reviewPath(loan, repayment) + "/resolutions", "finance", resolveInput(loan, unresolved)), "REPAYMENT_REVIEW_EVIDENCE_UNAVAILABLE");
        reviewStatus = AdvanceRepaymentAdjustmentPort.Status.CONFIRMED; reviewRevision = 2; var checked = review(loan, repayment); reviewWorker.poll();
        receiptRevision = 3; query(loan, "stale"); worker.poll();
        code(send(reviewPath(loan, repayment) + "/resolutions", "finance", resolveInput(loan, checked)), "REPAYMENT_REVIEW_EVIDENCE_CHANGED");
        assertThat(balance(loan).repaid()).isEqualTo(initial); assertThat(resolutions.latest("demo", repayment)).isEmpty();
    }

    @Test void returnedFundsCannotBeErasedOrCountedAgainAfterReconfirmation() throws Exception {
        var loan = paidLoan(); var repayment = repay(loan, "return-original"); reviewStatus = AdvanceRepaymentAdjustmentPort.Status.RETURNED; receiptRevision = 2;
        var check = review(loan, repayment); reviewWorker.poll(); ok(send(reviewPath(loan, repayment) + "/resolutions", "finance", resolveInput(loan, check)), 202);
        var originalDecision = resolutions.returned("demo", repayment).orElseThrow();
        reviewStatus = AdvanceRepaymentAdjustmentPort.Status.CONFIRMED; reviewRevision = 2; receiptRevision = 3; var wrong = review(loan, repayment); reviewWorker.poll();
        assertThat(balance(loan).repaymentReviewRequired()).isTrue(); code(send(reviewPath(loan, repayment) + "/resolutions", "finance", resolveInput(loan, wrong)), "REPAYMENT_REVIEW_EVIDENCE_CHANGED");
        reviewStatus = AdvanceRepaymentAdjustmentPort.Status.RETURNED; reviewRevision = 3; receiptRevision = 4; var correct = review(loan, repayment); reviewWorker.poll();
        ok(send(reviewPath(loan, repayment) + "/resolutions", "finance", resolveInput(loan, correct)), 202);
        assertThat(balance(loan).returnedRepayments()).isEqualTo(money("25")); assertThat(balance(loan).outstanding()).isEqualTo(money("100"));
        assertThat(resolutions.returned("demo", repayment).orElseThrow()).isEqualTo(originalDecision); assertThat(resolutions.latest("demo", repayment).orElseThrow().id()).isNotEqualTo(originalDecision.id());
        assertThat(balance(loan).repaymentReturns()).hasSize(1); assertThat(balance(loan).repaymentReviewRequired()).isFalse();
    }

    @Test void regressedReviewOrOriginalQueryAfterAcceptedReturnFreezesThatReceiptAgain() throws Exception {
        var loan = paidLoan(); var repayment = repay(loan, "regression"); reviewStatus = AdvanceRepaymentAdjustmentPort.Status.RETURNED; receiptRevision = 4; reviewRevision = 4;
        var check = review(loan, repayment); reviewWorker.poll(); ok(send(reviewPath(loan, repayment) + "/resolutions", "finance", resolveInput(loan, check)), 202);
        reviewRevision = 3; var regression = review(loan, repayment); reviewWorker.poll();
        assertThat(balance(loan).repaymentReviewRequired()).as("A lower combined revision must not leave accepted return usable").isTrue();
        code(send(reviewPath(loan, repayment) + "/resolutions", "finance", resolveInput(loan, regression)), "REPAYMENT_REVIEW_EVIDENCE_CHANGED");
        reviewRevision = 5; receiptRevision = 5; var restored = review(loan, repayment); reviewWorker.poll(); ok(send(reviewPath(loan, repayment) + "/resolutions", "finance", resolveInput(loan, restored)), 202);
        receiptStatus = AdvanceRepaymentPort.Status.REVERSED; receiptRevision = 4; query(loan, "regression"); worker.poll();
        assertThat(balance(loan).repaymentReviewRequired()).as("Original source revision must include later accepted reconfirmation").isTrue();
        assertThat(balance(loan).returnedRepayments()).isEqualTo(money("25"));
    }

    @Test void duplicateReturnedFundsRollBackBalanceConsumedReviewAndDecisionTogether() throws Exception {
        var loan = paidLoan(); var first = repay(loan, "duplicate-first"); var second = repay(loan, "duplicate-second");
        returnReference = "same-real-return"; reviewStatus = AdvanceRepaymentAdjustmentPort.Status.RETURNED; receiptRevision = 2;
        var checked = review(loan, first); reviewWorker.poll(); ok(send(reviewPath(loan, first) + "/resolutions", "finance", resolveInput(loan, checked)), 202);
        var another = review(loan, second); reviewWorker.poll(); var balanceBefore = balance(loan).state(); var checkBefore = reviewChecks.find("demo", another).orElseThrow();
        code(send(reviewPath(loan, second) + "/resolutions", "finance", resolveInput(loan, another)), "REPAYMENT_RETURN_ALREADY_RECORDED");
        assertThat(balance(loan).state()).isEqualTo(balanceBefore); assertThat(reviewChecks.find("demo", another).orElseThrow()).isEqualTo(checkBefore); assertThat(resolutions.latest("demo", second)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE tenant_id='demo' AND application_id=? AND action='ADVANCE_REPAYMENT_REVIEW_RESOLVE'", Integer.class, app(loan).id().toString())).isEqualTo(1);
    }

    @Test void concurrentResolutionsHaveOneWinnerAndPreserveOriginalExpenseReservation() throws Exception {
        var loan = paidLoan(); var repayment = repay(loan, "concurrent-return"); var use = reserve(loan, "60"); reviewStatus = AdvanceRepaymentAdjustmentPort.Status.RETURNED; receiptRevision = 2;
        var check = review(loan, repayment); reviewWorker.poll(); var input = resolveInput(loan, check);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2); var gate = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Integer> action = () -> { gate.await(); return send(reviewPath(loan, repayment) + "/resolutions", "finance", input).getStatus(); };
            var first = pool.submit(action); var second = pool.submit(action); gate.countDown();
            assertThat(List.of(first.get(15, java.util.concurrent.TimeUnit.SECONDS), second.get(15, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(202, 409);
        } finally { pool.shutdownNow(); }
        assertThat(balance(loan).balance().reserved()).isEqualTo(money("60")); assertThat(balance(loan).available()).isEqualTo(money("40")); assertThat(balance(loan).repaymentReturns()).hasSize(1);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> { requests.lock("demo", loan); var value = balance(loan); long version = value.version(); value.settle(version, use); balances.update(value, version, "test", "TEST_SETTLE"); });
        assertThat(balance(loan).outstanding()).isEqualTo(money("40")); assertThat(balance(loan).returnedRepayments()).isEqualTo(money("25"));
    }

    @Test void successivePartialReturnsAppendNewRowsAndRetainOriginalReceiptAndReservations() throws Exception {
        var loan = paidLoan(); amount = "60"; var repayment = repay(loan, "partial-sequence"); reserve(loan, "40"); int originalVoucherWrites = voucherWrites;
        var first = partialItem("first-30", "30"); var second = partialItem("second-20", "20"); var third = partialItem("third-10", "10");
        reviewStatus = AdvanceRepaymentAdjustmentPort.Status.PARTIALLY_RETURNED; partialReturns = List.of(first); receiptRevision = 2;
        var check = review(loan, repayment); reviewWorker.poll();
        assertThat(balance(loan).repaid()).isEqualTo(money("60")); assertThat(balance(loan).repaymentReviewRequired()).isTrue();
        String key = UUID.randomUUID().toString(); var input = resolveInput(loan, check); var saved = send(reviewPath(loan, repayment) + "/resolutions", "finance", key, input); ok(saved, 202);
        assertThat(send(reviewPath(loan, repayment) + "/resolutions", "finance", key, input).getContentAsString()).isEqualTo(saved.getContentAsString());
        var retained = balance(loan).repaymentReturns().get(0); assertThat(balance(loan).repaid()).isEqualTo(money("30"));
        query(loan, "partial-sequence"); worker.poll(); assertThat(balance(loan).repaymentReviewRequired()).isFalse();
        reviewRevision = 2; receiptRevision = 3; partialReturns = List.of(first, second, third); reviewStatus = AdvanceRepaymentAdjustmentPort.Status.RETURNED;
        var all = review(loan, repayment); reviewWorker.poll(); ok(send(reviewPath(loan, repayment) + "/resolutions", "finance", resolveInput(loan, all)), 202);
        assertThat(balance(loan).repaymentReturns()).hasSize(3).contains(retained); assertThat(balance(loan).repaid()).isEqualTo(money("0"));
        assertThat(balance(loan).receivedRepayments()).isEqualTo(money("60")); assertThat(balance(loan).returnedRepayments()).isEqualTo(money("60"));
        assertThat(balance(loan).balance().reserved()).isEqualTo(money("40")); assertThat(balance(loan).available()).isEqualTo(money("60"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM advance_repayment_return WHERE tenant_id='demo' AND repayment_id=?", Integer.class, repayment.toString())).isEqualTo(3);
        var owner = reviewView(loan, repayment, "alice"); assertThat(owner.at("/original/additionalReturns")).hasSize(2);
        receiptStatus = AdvanceRepaymentPort.Status.REVERSED; query(loan, "partial-sequence"); worker.poll(); assertThat(balance(loan).repaymentReviewRequired()).isFalse();
        assertThat(paymentWrites).isEqualTo(1); assertThat(voucherWrites).isEqualTo(originalVoucherWrites);
    }

    @Test void newerPartialSnapshotCannotOmitEarlierObservedOrAcceptedReturn() throws Exception {
        var loan = paidLoan(); var repayment = repay(loan, "partial-history"); var first = partialItem("history-first", "5"); var second = partialItem("history-second", "5");
        reviewStatus = AdvanceRepaymentAdjustmentPort.Status.PARTIALLY_RETURNED; partialReturns = List.of(first); receiptRevision = 2;
        var firstCheck = review(loan, repayment); reviewWorker.poll(); ok(send(reviewPath(loan, repayment) + "/resolutions", "finance", resolveInput(loan, firstCheck)), 202);
        partialReturns = List.of(first, second); var sameRevision = review(loan, repayment); reviewWorker.poll();
        code(send(reviewPath(loan, repayment) + "/resolutions", "finance", resolveInput(loan, sameRevision)), "REPAYMENT_REVIEW_EVIDENCE_CHANGED");
        reviewRevision = 2; receiptRevision = 3; var complete = review(loan, repayment); reviewWorker.poll(); ok(send(reviewPath(loan, repayment) + "/resolutions", "finance", resolveInput(loan, complete)), 202);
        var retained = balance(loan).repaymentReturns(); partialReturns = List.of(first); reviewRevision = 3; receiptRevision = 4;
        var missing = review(loan, repayment); reviewWorker.poll(); code(send(reviewPath(loan, repayment) + "/resolutions", "finance", resolveInput(loan, missing)), "REPAYMENT_REVIEW_EVIDENCE_CHANGED");
        assertThat(balance(loan).repaymentReturns()).isEqualTo(retained); assertThat(balance(loan).returnedRepayments()).isEqualTo(money("10"));
        assertThat(balance(loan).repaymentReviewRequired()).isTrue();
    }

    @Test void duplicateExternalPartialReturnRollsBackEarlierNewRowsInTheSameDecision() throws Exception {
        var firstLoan = paidLoan(); var firstRepayment = repay(firstLoan, "partial-other-loan");
        var secondLoan = paidLoan(); var secondRepayment = repay(secondLoan, "partial-conflict-loan");
        var duplicate = partialItem("cross-loan-shared", "10"); var fresh = partialItem("cross-loan-new", "5");
        reviewStatus = AdvanceRepaymentAdjustmentPort.Status.PARTIALLY_RETURNED; partialReturns = List.of(duplicate); receiptRevision = 2;
        var accepted = review(firstLoan, firstRepayment); reviewWorker.poll(); ok(send(reviewPath(firstLoan, firstRepayment) + "/resolutions", "finance", resolveInput(firstLoan, accepted)), 202);
        partialReturns = List.of(fresh, duplicate); var candidate = review(secondLoan, secondRepayment); reviewWorker.poll();
        var before = balance(secondLoan).state(); var check = reviewChecks.find("demo", candidate).orElseThrow();
        code(send(reviewPath(secondLoan, secondRepayment) + "/resolutions", "finance", resolveInput(secondLoan, candidate)), "REPAYMENT_RETURN_ALREADY_RECORDED");
        assertThat(balance(secondLoan).state()).isEqualTo(before); assertThat(reviewChecks.find("demo", candidate).orElseThrow()).isEqualTo(check);
        assertThat(resolutions.latest("demo", secondRepayment)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM advance_repayment_return WHERE tenant_id='demo' AND transaction_reference='cross-loan-new'", Integer.class)).isZero();
    }

    private AdvanceRepaymentAdjustmentPort.ReturnItem partialItem(String reference, String value) {
        var at = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        return new AdvanceRepaymentAdjustmentPort.ReturnItem(new AdvanceRepaymentAdjustmentPort.FundsReturn(AdvanceRepaymentPort.Channel.BANK_TRANSFER, reference, money(value), at),
                new AdvanceRepaymentAdjustmentPort.ReturnPosting("partial-" + reference, "debit-1", money(value), LocalDate.now(), at));
    }

    @Test void reviewLeaseTimeoutAndDisplayedVersionPreventLateOrStaleResolution() throws Exception {
        var loan = paidLoan(); var repayment = repay(loan, "lease"); var queued = review(loan, repayment); var claimed = reviewService.claim("demo", queued, Instant.now());
        assertThat(reviewService.claim("demo", queued, claimed.leaseUntil())).isNull();
        var receipt = (AdvanceRepaymentAdjustmentPort.Receipt) response("advance-repayment-adjustment", json.read(json.write(claimed.input().request()), JsonNode.class));
        reviewService.finish(claimed, new FinanceResult.Success<>(receipt), claimed.leaseUntil()); assertThat(reviewChecks.find("demo", queued).orElseThrow().issue()).isEqualTo(AdvanceRepaymentReviewCheck.Issue.TIMEOUT);
        reviewStatus = AdvanceRepaymentAdjustmentPort.Status.RETURNED; receiptRevision = 2; var check = review(loan, repayment); reviewWorker.poll();
        var stale = new LinkedHashMap<>(resolveInput(loan, check)); stale.put("advanceVersion", 1L); code(send(reviewPath(loan, repayment) + "/resolutions", "finance", stale), "CONCURRENCY_CONFLICT");
        var newer = review(loan, repayment); reviewWorker.poll(); code(send(reviewPath(loan, repayment) + "/resolutions", "finance", resolveInput(loan, check)), "CONCURRENCY_CONFLICT");
        assertThat(resolutions.latest("demo", repayment)).isEmpty(); assertThat(balance(loan).repaid()).isEqualTo(money("25"));
    }

    @Test void explicitRepaymentsCloseRealLoanWithoutAnotherPaymentOrVoucherAndReplayOnlyOnce() throws Exception {
        UUID loan = paidLoan(); var before = balance(loan).state(); int outgoing = paymentWrites, postings = voucherWrites;
        var queued = query(loan, "first"); assertThat(repaymentReads).isZero(); assertThat(balance(loan).state()).isEqualTo(before);
        worker.poll(); assertThat(balance(loan).state()).isEqualTo(before);
        var view = view(loan, "finance"); assertThat(view.at("/latestCheck/canRecord").asBoolean()).isTrue();
        assertThat(view.toString()).doesNotContain("accountDigest", "targetDigest", "private-account", "reason");
        String key = UUID.randomUUID().toString(); var input = recordInput(loan, queued);
        var recorded = send(path(loan) + "/repayments", "finance", key, input); ok(recorded, 202);
        assertThat(send(path(loan) + "/repayments", "finance", key, input).getContentAsString()).isEqualTo(recorded.getContentAsString());
        assertThat(recorded.getHeader("Cache-Control")).isEqualTo("no-store"); assertThat(balance(loan).repaid()).isEqualTo(money("25"));
        assertThat(balance(loan).outstanding()).isEqualTo(money("75")); assertThat(balance(loan).status()).isEqualTo(EmployeeAdvance.Status.PARTIALLY_SETTLED);
        amount = "75"; var remaining = query(loan, "remaining"); worker.poll(); ok(send(path(loan) + "/repayments", "finance", recordInput(loan, remaining)), 202);
        assertThat(balance(loan).status()).isEqualTo(EmployeeAdvance.Status.SETTLED); assertThat(balance(loan).balance().limit()).isEqualTo(money("100"));
        assertThat(balance(loan).balance().consumed()).isEqualTo(money("0")); assertThat(balance(loan).available()).isEqualTo(money("0"));
        var owner = view(loan, "alice"); assertThat(owner.path("canQuery").asBoolean()).isFalse(); assertThat(owner.path("latestCheck").isNull()).isTrue();
        assertThat(owner.path("records")).hasSize(2); assertThat(owner.at("/balance/repaid/value").asText()).isEqualTo("100.00");
        assertThat(paymentWrites).isEqualTo(outgoing); assertThat(voucherWrites).isEqualTo(postings);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE tenant_id='demo' AND application_id=? AND action='ADVANCE_REPAYMENT_RECORD'", Integer.class, app(loan).id().toString())).isEqualTo(2);
    }

    @ParameterizedTest @EnumSource(value = AdvanceRepaymentPort.Channel.class, names = {"CASH", "PAYROLL"})
    void externallyPostedCashAndPayrollUseOriginalFacts(AdvanceRepaymentPort.Channel selected) throws Exception {
        UUID loan = paidLoan(); channel = selected; var check = query(loan, "channel"); worker.poll();
        ok(send(path(loan) + "/repayments", "finance", recordInput(loan, check)), 202);
        assertThat(view(loan, "alice").at("/records/0/channel").asText()).isEqualTo(selected.name());
        assertThat(balance(loan).repaid()).isEqualTo(money("25"));
    }

    @Test void pendingMissingMalformedAndOverpaidReceiptsCannotChangeBalance() throws Exception {
        UUID loan = paidLoan(); var original = balance(loan).state();
        for (var state : List.of(AdvanceRepaymentPort.Status.NOT_FOUND, AdvanceRepaymentPort.Status.PENDING, AdvanceRepaymentPort.Status.REVERSED)) {
            receiptStatus = state; var check = query(loan, "unready-" + state); worker.poll();
            assertThat(view(loan, "finance").at("/latestCheck/canRecord").asBoolean()).isFalse();
            code(send(path(loan) + "/repayments", "finance", recordInput(loan, check)), "ADVANCE_REPAYMENT_EVIDENCE_UNAVAILABLE");
        }
        unavailable = true; var invalid = query(loan, "malformed"); worker.poll();
        assertThat(checks.find("demo", invalid).orElseThrow().status()).isEqualTo(AdvanceRepaymentCheck.Status.UNAVAILABLE);
        unavailable = false; receiptStatus = AdvanceRepaymentPort.Status.CONFIRMED; amount = "101";
        var excessive = query(loan, "too-large"); worker.poll();
        code(send(path(loan) + "/repayments", "finance", recordInput(loan, excessive)), "INSUFFICIENT_FINANCIAL_BALANCE");
        assertThat(balance(loan).state()).isEqualTo(original);
    }

    @Test void identityPermissionsAndCurrentAppointmentAreCheckedBeforeReplay() throws Exception {
        UUID loan = paidLoan(); var input = Map.of("advanceVersion", balance(loan).version(), "receiptReference", "private", "comment", "合成核对");
        for (String user : List.of("alice", "admin", "cashier", "bob")) {
            assertThat(send(path(loan) + "/repayment-checks", user, input).getStatus()).isIn(403, 404);
            if (!user.equals("alice")) assertThat(read(path(loan) + "/repayments", user).getStatus()).isIn(403, 404);
        }
        var forged = new LinkedHashMap<String, Object>(input); forged.put("amount", money("25"));
        okError(send(path(loan) + "/repayment-checks", "finance", forged), 400);
        code(read(path(loan) + "/repayments?employeeId=alice", "finance"), "INVALID_ADVANCE_REPAYMENT_QUERY");
        code(read(path(loan) + "/repayments?beforeId=" + UUID.randomUUID(), "finance"), "INVALID_ADVANCE_REPAYMENT_QUERY");
        var check = query(loan, "record"); worker.poll(); var record = recordInput(loan, check); String key = UUID.randomUUID().toString();
        ok(send(path(loan) + "/repayments", "finance", key, record), 202);
        var before = balance(loan).state(); jdbc.update("UPDATE organization_appointment SET active=FALSE WHERE id=?", financeAppointment.toString());
        code(send(path(loan) + "/repayments", "finance", key, record), "PAYMENT_ACTOR_UNAVAILABLE");
        assertThat(view(loan, "finance").path("canQuery").asBoolean()).isFalse();
        assertThat(view(loan, "finance").path("latestCheck").isNull()).isTrue(); assertThat(balance(loan).state()).isEqualTo(before);
    }

    @Test void duplicateFundsAndAccountingEntryRollBackEveryLocalWrite() throws Exception {
        UUID loan = paidLoan(); var first = query(loan, "original"); worker.poll();
        ok(send(path(loan) + "/repayments", "finance", recordInput(loan, first)), 202); var saved = balance(loan).state();
        for (String duplicate : List.of("funds", "entry")) {
            transactionReference = duplicate.equals("funds") ? "receive-original" : null;
            postingReference = duplicate.equals("entry") ? "repay-voucher-original" : null;
            var second = query(loan, duplicate); worker.poll(); var checked = checks.find("demo", second).orElseThrow();
            code(send(path(loan) + "/repayments", "finance", recordInput(loan, second)), "ADVANCE_REPAYMENT_ALREADY_RECORDED");
            assertThat(checks.find("demo", second).orElseThrow()).isEqualTo(checked); assertThat(balance(loan).state()).isEqualTo(saved);
        }
        assertThat(view(loan, "alice").path("records")).hasSize(1);
    }

    @Test void auditFailureRollsBackReceiptConsumptionAndBalance() throws Exception {
        UUID loan = paidLoan(); var check = query(loan, "audit-failure"); worker.poll(); var before = balance(loan).state(); var pending = checks.find("demo", check).orElseThrow();
        jdbc.execute("ALTER TABLE audit_event ADD CONSTRAINT reject_repayment_audit_fixture CHECK (application_id <> '" + app(loan).id() + "' OR action <> 'ADVANCE_REPAYMENT_RECORD')");
        try { assertThatThrownBy(() -> send(path(loan) + "/repayments", "finance", recordInput(loan, check))).hasCauseInstanceOf(org.springframework.dao.DataIntegrityViolationException.class); }
        finally { jdbc.execute("ALTER TABLE audit_event DROP CONSTRAINT reject_repayment_audit_fixture"); }
        assertThat(balance(loan).state()).isEqualTo(before); assertThat(checks.find("demo", check).orElseThrow()).isEqualTo(pending);
        assertThat(view(loan, "alice").path("records")).isEmpty();
        ok(send(path(loan) + "/repayments", "finance", recordInput(loan, check)), 202);
    }

    @Test void existingExpenseReservationSurvivesRepaymentAndFinalOffsetClosesLoan() throws Exception {
        UUID loan = paidLoan(); var use = reserve(loan, "80"); var check = query(loan, "over-reserved"); worker.poll();
        code(send(path(loan) + "/repayments", "finance", recordInput(loan, check)), "INSUFFICIENT_FINANCIAL_BALANCE");
        amount = "20"; var fitting = query(loan, "remainder"); worker.poll(); ok(send(path(loan) + "/repayments", "finance", recordInput(loan, fitting)), 202);
        assertThat(balance(loan).available()).isEqualTo(money("0")); assertThat(balance(loan).outstanding()).isEqualTo(money("80"));
        new TransactionTemplate(transactions).executeWithoutResult(tx -> { var advance = balance(loan); long version = advance.version(); advance.settle(version, use); balances.update(advance, version, "fixture", "SETTLE"); });
        assertThat(balance(loan).status()).isEqualTo(EmployeeAdvance.Status.SETTLED); assertThat(balance(loan).balance().consumed()).isEqualTo(money("80"));
        assertThat(balance(loan).repaid()).isEqualTo(money("20"));
    }

    @Test void reversedRecordedReceiptFreezesNewUseAndNeverErasesHistory() throws Exception {
        UUID loan = paidLoan(); var first = query(loan, "reversed"); worker.poll(); ok(send(path(loan) + "/repayments", "finance", recordInput(loan, first)), 202);
        var entries = balance(loan).repayments(); receiptStatus = AdvanceRepaymentPort.Status.REVERSED; receiptRevision = 2;
        query(loan, "reversed"); worker.poll(); assertThat(balance(loan).repaymentReviewRequired()).isTrue();
        assertThat(balance(loan).available()).isEqualTo(money("0")); assertThat(balance(loan).repayments()).isEqualTo(entries);
        receiptStatus = AdvanceRepaymentPort.Status.CONFIRMED; receiptRevision = 3; query(loan, "reversed"); worker.poll();
        assertThat(balance(loan).status()).isEqualTo(EmployeeAdvance.Status.REPAYMENT_REVIEW);
        var newReceipt = query(loan, "other"); worker.poll(); code(send(path(loan) + "/repayments", "finance", recordInput(loan, newReceipt)), "ADVANCE_REPAYMENT_REVIEW_REQUIRED");
        assertThat(view(loan, "alice").path("records")).hasSize(1);
    }

    @Test void staleVersionsExpiredLeaseAndRevokedActorNeverConsumeEvidence() throws Exception {
        UUID loan = paidLoan(); var queued = query(loan, "timed-out"); var claimed = service.claim("demo", queued, Instant.now());
        assertThat(service.claim("demo", queued, claimed.leaseUntil())).isNull();
        var at = claimed.updatedAt(); var receipt = new AdvanceRepaymentPort.Receipt(claimed.input().request(), AdvanceRepaymentPort.Status.CONFIRMED, 1, at, at.plusSeconds(300),
                new AdvanceRepaymentPort.Funding(channel, "late", money("25"), at), new AdvanceRepaymentPort.Posting("late", "1", money("25"), LocalDate.now(), at));
        service.finish(claimed, new FinanceResult.Success<>(receipt), claimed.leaseUntil());
        assertThat(checks.find("demo", queued).orElseThrow().issue()).isEqualTo(AdvanceRepaymentCheck.Issue.TIMEOUT);
        var check = query(loan, "stale"); worker.poll(); var stale = new LinkedHashMap<>(recordInput(loan, check));
        stale.put("checkVersion", 1L); code(send(path(loan) + "/repayments", "finance", stale), "CONCURRENCY_CONFLICT");
        var newer = query(loan, "newer"); worker.poll(); code(send(path(loan) + "/repayments", "finance", recordInput(loan, check)), "CONCURRENCY_CONFLICT");
        var revoked = query(loan, "revoked"); jdbc.update("UPDATE organization_appointment SET active=FALSE WHERE id=?", financeAppointment.toString()); worker.poll();
        assertThat(checks.find("demo", revoked).orElseThrow().status()).isEqualTo(AdvanceRepaymentCheck.Status.VOIDED);
        assertThat(balance(loan).repaid()).isEqualTo(money("0"));
    }

    @Test void concurrentFinanceConfirmationsHaveExactlyOneWinner() throws Exception {
        UUID loan = paidLoan(); var check = query(loan, "concurrent"); worker.poll(); var input = recordInput(loan, check);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2); var gate = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Integer> action = () -> { gate.await(); return send(path(loan) + "/repayments", "finance", input).getStatus(); };
            var first = pool.submit(action); var second = pool.submit(action); gate.countDown();
            assertThat(List.of(first.get(15, java.util.concurrent.TimeUnit.SECONDS), second.get(15, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(202, 409);
        } finally { pool.shutdownNow(); }
        assertThat(balance(loan).repaid()).isEqualTo(money("25")); assertThat(view(loan, "alice").path("records")).hasSize(1);
    }

    @Test void newerQueryCannotRewritePreviouslyObservedFundsOrRollBackExternalRevision() throws Exception {
        UUID loan = paidLoan(); receiptRevision = 3; query(loan, "stable"); worker.poll();
        receiptRevision = 2; var stale = query(loan, "stable"); worker.poll();
        code(send(path(loan) + "/repayments", "finance", recordInput(loan, stale)), "ADVANCE_REPAYMENT_EVIDENCE_CHANGED");
        receiptRevision = 4; receipts.remove("stable"); amount = "30"; var changed = query(loan, "stable"); worker.poll();
        code(send(path(loan) + "/repayments", "finance", recordInput(loan, changed)), "ADVANCE_REPAYMENT_EVIDENCE_CHANGED");
        assertThat(balance(loan).repaid()).isEqualTo(money("0"));
    }

    @Test void historyCursorIsBoundedAndCannotSelectAnotherLoan() throws Exception {
        UUID loan = paidLoan(); amount = "1";
        for (int i = 0; i < 26; i++) { var check = query(loan, "history-" + i); worker.poll(); ok(send(path(loan) + "/repayments", "finance", recordInput(loan, check)), 202); }
        var first = view(loan, "alice"); assertThat(first.path("records")).hasSize(25); String cursor = first.path("nextBeforeId").asText();
        var second = ok(read(path(loan) + "/repayments?beforeId=" + cursor, "alice"), 200); assertThat(second.path("records")).hasSize(1); assertThat(second.path("nextBeforeId").isNull()).isTrue();
        assertThat(first.path("records").toString()).doesNotContain(second.at("/records/0/id").asText());
        UUID other = paidLoan(); code(read(path(other) + "/repayments?beforeId=" + cursor, "alice"), "INVALID_ADVANCE_REPAYMENT_QUERY");
    }

    private ExpenseUse reserve(UUID loan, String reserved) {
        return new TransactionTemplate(transactions).execute(tx -> {
            UUID id = UUID.randomUUID(); var application = Application.draftBusiness(UUID.randomUUID(), "demo", "EXP-" + id, "fixture", 1, "alice", "合成预留", Map.of(), null, null, null, new BusinessReference(BusinessReference.Type.EXPENSE, id));
            applications.save(application); reports.create(ExpenseReport.draft(id, "demo", application.id(), "alice", new ExpenseContent(entity, ExpenseContent.Type.DAILY, "费用", List.of(), List.of())), "alice");
            var use = new ExpenseUse(id, 1, 0); var advance = balance(loan); long version = advance.version(); advance.reserve(version, use, money(reserved)); balances.update(advance, version, "fixture", "RESERVE"); return use;
        });
    }

    private UUID paidLoan() throws Exception {
        var graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()), new Node("finance", "财务审批", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_" + financePerson)), new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("a", "start", "finance", "", false), new Edge("b", "finance", "end", "", false)));
        var schema = new FormSchema(2, List.of(new FormSchema.Field(AdvanceRequestFormContract.DETAILS, "借款明细", FormSchema.FieldType.TEXT, true, null, null, null, null, null, null, null, true, Map.of("finance", FieldVisibility.READ_ONLY)),
                new FormSchema.Field("amount", "金额", FormSchema.FieldType.NUMBER, true, null, null, null, null, null), new FormSchema.Field("currency", "币种", FormSchema.FieldType.TEXT, true, null, null, null, null, null)));
        var draft = definitions.create("demo", "repayment-" + UUID.randomUUID(), "还款验收", graph, schema, null); var definition = definitions.publish(admin, draft.id(), draft.revision(), "合成流程");
        var content = new AdvanceRequestContent(entity, "合成还款借款", "真实状态机验收", money("100"), LocalDate.now().plusDays(10));
        UUID loan = UUID.fromString(ok(send("/api/v1/advance-requests", "alice", Map.of("businessNo", "REP-" + UUID.randomUUID(), "processKey", definition.key(), "definitionVersion", definition.version(), "content", content)), 201).path("id").asText()); ownedAdvances.add(loan);
        UUID checked = UUID.fromString(ok(send(path(loan) + "/prechecks", "alice", Map.of("applicationVersion", app(loan).version(), "requestVersion", request(loan).version(), "initiatorAppointmentId", appointment, "targetDigest", configuration.destination("demo").orElseThrow().digest("demo"))), 202).path("id").asText());
        precheckWorker.poll(); ok(send(path(loan) + "/submit", "alice", Map.of("applicationVersion", app(loan).version(), "requestVersion", request(loan).version(), "precheckId", checked)), 200);
        var task = tasks.createTaskQuery().processVariableValueEquals("applicationId", app(loan).id().toString()).singleResult();
        ok(send("/api/v1/tasks/" + task.getId() + "/actions", "finance", Map.of("action", "APPROVE", "expectedVersion", app(loan).version(), "comment", "合成借款批准")), 200);
        preparationWorker.poll(); voucherWorker.poll(); var voucher = vouchers.forRound("demo", app(loan).id(), 1, VoucherCommand.Kind.EMPLOYEE_ADVANCE).orElseThrow();
        UUID payment = UUID.fromString(ok(send("/api/v1/applications/" + app(loan).id() + "/payments/authorizations", "finance", Map.of("roundNo", 1, "applicationVersion", app(loan).version(), "businessVersion", request(loan).version(), "voucherOperationId", voucher.input().command().id(), "voucherVersion", voucher.version(), "validitySeconds", 900, "comment", "合成授权")), 202).path("authorizationId").asText());
        ok(send("/api/v1/cashier/payments/" + payment + "/actions", "cashier", Map.of("action", "EXECUTE", "authorizationVersion", 1, "debitAccountReference", "debit-1", "debitAccountVersion", "v1", "comment", "合成执行")), 202);
        executionWorker.poll(); paymentWorker.poll(); assertThat(payments.find("demo", payment).orElseThrow().status()).isEqualTo(PaymentOperation.Status.SUCCEEDED);
        preparationWorker.poll(); voucherWorker.poll(); assertThat(balance(loan).available()).isEqualTo(money(reverseVoucherBeforeReceipt ? "0" : "100")); return loan;
    }
    private AdvanceDisbursementReturnPort.ReturnItem bankReturn(String reference, String value) {
        var now = Instant.now(); return new AdvanceDisbursementReturnPort.ReturnItem(
                new AdvanceRepaymentPort.Funding(AdvanceRepaymentPort.Channel.BANK_TRANSFER, reference, money(value), now),
                new AdvanceRepaymentPort.Posting("credit-" + reference, "row-1", money(value), LocalDate.now(), now));
    }
    private UUID disbursementReview(UUID loan) throws Exception {
        return UUID.fromString(ok(send(path(loan) + "/disbursement-review-checks", "finance", Map.of("advanceVersion", balance(loan).version(), "comment", "核对原放款与银行退回")), 202).path("checkId").asText());
    }
    private Map<String, Object> disbursementInput(UUID loan, UUID check) {
        var value = disbursementChecks.find("demo", check).orElseThrow();
        assertThat(value.receipt()).as("Independent original disbursement and return evidence is available").isNotNull();
        return Map.of("advanceVersion", balance(loan).version(), "checkId", check, "checkVersion", value.version(), "outcome", value.receipt().status(), "evidenceReference", "bank-return-proof", "comment", "核对原放款、回款和应收贷方分录");
    }
    private JsonNode disbursementView(UUID loan, String user) throws Exception { return ok(read(path(loan) + "/disbursement-review", user), 200); }
    private UUID originalPayment(UUID loan) { return paymentCommands.values().stream().filter(value -> value.binding().businessId().equals(loan)).findFirst().orElseThrow().id(); }
    private void queryVoucher(UUID loan, VoucherCommand.Kind kind) throws Exception {
        var voucher = vouchers.forRound("demo", app(loan).id(), 1, kind).orElseThrow();
        ok(send("/api/v1/applications/" + app(loan).id() + "/vouchers" + (kind == VoucherCommand.Kind.PAYMENT ? "/payment" : "") + "/actions", "finance", Map.of(
                "action", "QUERY", "roundNo", 1, "applicationVersion", app(loan).version(), "businessVersion", request(loan).version(),
                "operationId", voucher.input().command().id(), "operationVersion", voucher.version(), "comment", "读取原 ERP 凭证状态")), 202); voucherWorker.poll();
    }
    private void queryOriginalPayment(UUID loan) throws Exception {
        UUID payment = originalPayment(loan);
        ok(send("/api/v1/payments/" + payment + "/finance-actions", "finance", Map.of("action", "QUERY", "authorizationVersion", paymentAuthorizations.find("demo", payment).orElseThrow().version(),
                "operationVersion", payments.find("demo", payment).orElseThrow().version(), "comment", "读取原银行结果")), 202); paymentWorker.poll();
    }
    private UUID repay(UUID loan, String reference) throws Exception {
        var check = query(loan, reference); worker.poll(); return UUID.fromString(ok(send(path(loan) + "/repayments", "finance", recordInput(loan, check)), 202).path("repaymentId").asText());
    }
    private String reviewPath(UUID loan, UUID repayment) { return path(loan) + "/repayments/" + repayment; }
    private UUID review(UUID loan, UUID repayment) throws Exception {
        return UUID.fromString(ok(send(reviewPath(loan, repayment) + "/review-checks", "finance", Map.of("advanceVersion", balance(loan).version(), "comment", "核对原收款与真实退回")), 202).path("checkId").asText());
    }
    private Map<String, Object> resolveInput(UUID loan, UUID check) {
        var value = reviewChecks.find("demo", check).orElseThrow(); return Map.of("advanceVersion", balance(loan).version(), "checkId", check, "checkVersion", value.version(), "outcome", value.receipt().status(), "evidenceReference", "proof-1", "comment", "核对独立原件与实际退回分录");
    }
    private JsonNode reviewView(UUID loan, UUID repayment, String user) throws Exception { return ok(read(reviewPath(loan, repayment) + "/review", user), 200); }
    private UUID query(UUID loan, String reference) throws Exception {
        var receipt = ok(send(path(loan) + "/repayment-checks", "finance", Map.of("advanceVersion", balance(loan).version(), "receiptReference", reference, "comment", "读取原收款")), 202);
        assertThat(receipt.has("repaymentId")).as("Queued receipt explicitly distinguishes no recorded repayment").isTrue();
        assertThat(receipt.path("repaymentId").isNull()).isTrue(); return UUID.fromString(receipt.path("checkId").asText());
    }
    private Map<String, Object> recordInput(UUID loan, UUID checkId) { return Map.of("advanceVersion", balance(loan).version(), "checkId", checkId, "checkVersion", checks.find("demo", checkId).orElseThrow().version(), "comment", "核对收款与原借款贷方分录"); }
    private JsonNode view(UUID loan, String user) throws Exception { return ok(read(path(loan) + "/repayments", user), 200); }
    private EmployeeAdvance balance(UUID id) { return balances.find("demo", id).orElseThrow(); }
    private AdvanceRequest request(UUID id) { return requests.find("demo", id).orElseThrow(); }
    private Application app(UUID id) { return applications.findById("demo", request(id).applicationId()).orElseThrow(); }
    private String path(UUID id) { return "/api/v1/advance-requests/" + id; }
    private MockHttpServletResponse send(String path, String user, Object value) throws Exception { return send(path, user, UUID.randomUUID().toString(), value); }
    private MockHttpServletResponse send(String path, String user, String key, Object value) throws Exception { return mvc.perform(post(path).header("Authorization", "Bearer " + auth.login("demo", user, "demo").token()).header("Idempotency-Key", key).contentType("application/json").content(json.write(value))).andReturn().getResponse(); }
    private MockHttpServletResponse read(String path, String user) throws Exception { return mvc.perform(get(path).header("Authorization", "Bearer " + auth.login("demo", user, "demo").token())).andReturn().getResponse(); }
    private JsonNode ok(MockHttpServletResponse response, int expected) throws Exception { assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected); return json.read(response.getContentAsString(StandardCharsets.UTF_8), JsonNode.class); }
    private void code(MockHttpServletResponse response, String expected) throws Exception { assertThat(response.getStatus()).isBetween(400, 499); assertThat(json.read(response.getContentAsString(), JsonNode.class).path("code").asText()).isEqualTo(expected); }
    private void okError(MockHttpServletResponse response, int expected) { assertThat(response.getStatus()).isEqualTo(expected); }
    private UUID person(String subject, boolean approver) { var found = jdbc.queryForList("SELECT id FROM organization_person WHERE tenant_id='demo' AND subject=?", String.class, subject); return found.isEmpty() ? organization.createPerson(admin, subject, subject, true, approver).id() : UUID.fromString(found.get(0)); }
    private static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
    private Object response(String operation, JsonNode data) {
        var at = Instant.now();
        return switch (operation) {
            case "catalog" -> new FinanceCatalog("alice", "v1", at.plusSeconds(600), List.of(new FinanceCatalog.LegalEntity(entity, "合成法人", "CNY", false, "v1", "UTC")), List.of(new FinanceCatalog.Category("OFFICE", "办公", List.of(ExpenseLine.Unit.ITEM))), List.of(new FinanceCatalog.CostCenter(entity, "IT", "研发")), List.of(), List.of(new FinanceCatalog.City("SH", "上海")));
            case "employee-account" -> new EmployeeAccountPort.Account(new EmployeeAccountSnapshot(entity, "alice", "private-account", "****1234", "a".repeat(64), "v1"), at.plusSeconds(600));
            case "debit-accounts" -> new PaymentAccountsPort.Directory(json.read(data.toString(), PaymentAccountsPort.Request.class), "v1", at, at.plusSeconds(300), List.of(new PaymentAccountsPort.DebitAccount("debit-1", "合成账户", "****4567", "CNY", "v1")));
            case "accounting-period" -> { var r = json.read(data.toString(), AccountingPeriodPort.Request.class); yield new AccountingPeriodPort.OpenPeriod(r, "synthetic-period", "v1", r.accountingDate().minusDays(30), r.accountingDate().plusDays(30), at, at.plusSeconds(300)); }
            case "account-mapping" -> { var r = json.read(data.toString(), AccountMappingPort.Request.class); yield new AccountMappingPort.Mapping(r, "v1", at, at.plusSeconds(300), r.keys().stream().map(key -> new AccountMappingPort.Entry(key, "synthetic-" + key.role())).toList()); }
            case "voucher-command", "voucher-query" -> {
                var command = operation.endsWith("command") ? json.read(data.path("command").toString(), VoucherCommand.class) : voucherCommands.get(UUID.fromString(data.path("operationId").asText()));
                if (operation.endsWith("command")) voucherWrites++; voucherCommands.put(command.id(), command);
                yield new VoucherObservation(command.id(), command.digest(), operation.endsWith("query") ? voucherQueryStatus : VoucherObservation.Status.POSTED, operation.endsWith("query") ? voucherQueryRevision : 1L, at, "posting-" + command.id(), "voucher-" + command.id(), command.period().periodReference(), command.accountingDate(), command.totals().gross(), command.totals().gross(), command.createdAt(), null);
            }
            case "payment-command", "payment-query" -> {
                var command = operation.endsWith("command") ? json.read(data.path("command").toString(), PaymentCommand.class) : paymentCommands.get(UUID.fromString(data.path("authorizationId").asText()));
                if (operation.endsWith("command")) paymentWrites++; paymentCommands.put(command.id(), command);
                if (operation.endsWith("command") && reverseVoucherBeforeReceipt) reverseOriginalWhilePaymentInFlight(command);
                yield new PaymentObservation(command.id(), command.digest(), paymentStatus, paymentRevision, at, "funding-" + command.id(), command.amount(), command.payee().accountDigest(), command.authorization().authorizedAt(), "bank-" + command.id(), null);
            }
            case "advance-disbursement-return" -> {
                var request = json.read(data.toString(), AdvanceDisbursementReturnPort.Request.class); var original = request.original();
                var current = new PaymentObservation(original.authorizationId(), original.commandDigest(), paymentStatus, paymentRevision, at, original.paymentReference(),
                        original.paidAmount(), original.accountDigest(), original.completedAt(), original.receiptReference(), null);
                yield new AdvanceDisbursementReturnPort.Receipt(request, disbursementStatus, disbursementRevision, at, at.plusSeconds(300), current, disbursementFacts);
            }
            case "advance-repayment-adjustment" -> {
                if (reviewUnavailable) yield Map.of("malformed", true);
                var request = json.read(data.toString(), AdvanceRepaymentAdjustmentPort.Request.class); var original = request.original();
                var current = new AdvanceRepaymentPort.Receipt(original.request(), reviewStatus == AdvanceRepaymentAdjustmentPort.Status.CONFIRMED || reviewStatus == AdvanceRepaymentAdjustmentPort.Status.PARTIALLY_RETURNED ? AdvanceRepaymentPort.Status.CONFIRMED : AdvanceRepaymentPort.Status.REVERSED,
                        receiptRevision, at, at.plusSeconds(300), original.funding(), original.posting());
                if (partialReturns != null) yield new AdvanceRepaymentAdjustmentPort.Receipt(request, reviewStatus, reviewRevision, at, at.plusSeconds(300), current,
                        partialReturns.get(0).fundsReturn(), partialReturns.get(0).posting(), partialReturns.subList(1, partialReturns.size()));
                var facts = reviewStatus == AdvanceRepaymentAdjustmentPort.Status.RETURNED ? returnFacts.computeIfAbsent(request.repaymentId(), ignored -> new AdvanceRepaymentAdjustmentPort.Receipt(request, reviewStatus, reviewRevision, at, at.plusSeconds(300), current,
                        new AdvanceRepaymentAdjustmentPort.FundsReturn(channel, returnReference == null ? "return-" + request.repaymentId() : returnReference, original.funding().amount(), at),
                        new AdvanceRepaymentAdjustmentPort.ReturnPosting("return-voucher-" + request.repaymentId(), "debit-1", original.funding().amount(), LocalDate.now(), at))) : null;
                yield new AdvanceRepaymentAdjustmentPort.Receipt(request, reviewStatus, reviewRevision, at, at.plusSeconds(300), current, facts == null ? null : facts.fundsReturn(), facts == null ? null : facts.posting());
            }
            case "advance-repayment" -> {
                repaymentReads++; if (unavailable) yield Map.of("malformed", true);
                var request = json.read(data.toString(), AdvanceRepaymentPort.Request.class); var key = request.receiptReference();
                var original = receipts.computeIfAbsent(key, ignored -> new AdvanceRepaymentPort.Receipt(request, AdvanceRepaymentPort.Status.CONFIRMED, 1, at, at.plusSeconds(300),
                        new AdvanceRepaymentPort.Funding(channel, transactionReference == null ? "receive-" + key : transactionReference, money(amount), at),
                        new AdvanceRepaymentPort.Posting(postingReference == null ? "repay-voucher-" + key : postingReference, "row-1", money(amount), LocalDate.now(), at)));
                boolean terminal = receiptStatus == AdvanceRepaymentPort.Status.CONFIRMED || receiptStatus == AdvanceRepaymentPort.Status.REVERSED;
                yield new AdvanceRepaymentPort.Receipt(request, receiptStatus, receiptStatus == AdvanceRepaymentPort.Status.NOT_FOUND ? 0 : receiptRevision, at, at.plusSeconds(300), terminal ? original.funding() : null, terminal ? original.posting() : null);
            }
            default -> throw new IllegalStateException("Unexpected synthetic finance operation");
        };
    }
    private void reverseOriginalWhilePaymentInFlight(PaymentCommand payment) {
        var original = vouchers.forRound("demo", payment.binding().applicationId(), payment.binding().roundNo(), VoucherCommand.Kind.EMPLOYEE_ADVANCE).orElseThrow();
        var command = original.input().command(); var transaction = new TransactionTemplate(transactions);
        transaction.executeWithoutResult(tx -> voucherExecution.query("demo", command.id(), original.version(), Instant.now()));
        var claimed = voucherExecution.claim("demo", command.id(), Instant.now()); var now = Instant.now();
        var reversed = new VoucherObservation(command.id(), command.digest(), VoucherObservation.Status.REVERSED, 2L, now,
                "posting-" + command.id(), "voucher-" + command.id(), command.period().periodReference(), command.accountingDate(),
                command.totals().gross(), command.totals().gross(), command.createdAt(), null);
        voucherExecution.finish(claimed, new FinanceResult.Success<>(reversed), Instant.now());
        if (queryVoucherBeforeReceipt) transaction.executeWithoutResult(tx -> voucherExecution.query("demo", command.id(), vouchers.find("demo", command.id()).orElseThrow().version(), Instant.now()));
    }
    private static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/finance/", exchange -> {
                var active = ACTIVE.get(); var request = active.json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonNode.class);
                Object value = active.response(exchange.getRequestURI().getPath().substring("/finance/".length()), request.path("data"));
                byte[] response = active.json.write(Map.of("contractVersion", 1, "tenantId", request.path("tenantId").asText(), "requestId", request.path("requestId").asText(), "outcome", "SUCCESS", "data", value)).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, response.length);
                try { exchange.getResponseBody().write(response); } finally { exchange.close(); }
            }); server.start(); return server;
        } catch (Exception problem) { throw new IllegalStateException(problem); }
    }
}
