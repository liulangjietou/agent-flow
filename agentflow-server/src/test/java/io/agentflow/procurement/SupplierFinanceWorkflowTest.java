package io.agentflow.procurement;

import io.agentflow.finance.FinanceResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.JsonUtil;
import io.agentflow.finance.callback.*;
import io.agentflow.definition.DefinitionApplicationService;
import io.agentflow.expense.ExpenseLine;
import io.agentflow.expense.InvoiceKey;
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.finance.AccountingPeriodPort;
import io.agentflow.finance.FinanceGatewayConfiguration;
import io.agentflow.finance.Money;
import io.agentflow.finance.PaymentAccountsPort;
import io.agentflow.finance.PaymentObservation;
import io.agentflow.form.FieldVisibility;
import io.agentflow.form.FormSchema;
import io.agentflow.organization.OrganizationService;
import io.agentflow.organization.OrganizationUnit;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.flowable.engine.TaskService;
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
import static io.agentflow.definition.DefinitionModels.*;
import static io.agentflow.procurement.ProcurementPaymentCheck.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 使用真实认证、数据库、Flowable 和回环财务 HTTP 验证供应商财务权限、幂等回放与人工决定原子性。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "agentflow.finance-gateway.enabled=true",
        "agentflow.vouchers.preparation-worker-enabled=false", "agentflow.vouchers.worker-enabled=false",
        "agentflow.invoices.verification-worker-enabled=false", "agentflow.expenses.precheck-worker-enabled=false",
        "agentflow.advance-requests.precheck-worker-enabled=false", "agentflow.expense-plans.precheck-worker-enabled=false",
        "agentflow.supplier-payments.execution-worker-enabled=false", "agentflow.supplier-payments.payment-worker-enabled=false",
        "agentflow.supplier-payments.settlement-preparation-worker-enabled=false", "agentflow.supplier-payments.settlement-worker-enabled=false",
        "agentflow.supplier-payments.hold-worker-enabled=false", "agentflow.supplier-payments.review-worker-enabled=false",
        "agentflow.supplier-payments.return-worker-enabled=false",
        "agentflow.supplier-payments.adjustment-preparation-worker-enabled=false", "agentflow.supplier-payments.adjustment-worker-enabled=false",
        "agentflow.procurement-payments.precheck-worker-enabled=false", "agentflow.budgets.worker-enabled=false",
        "agentflow.payment-callbacks.enabled=true", "agentflow.payment-callbacks.worker-enabled=false",
        "agentflow.budget-adjustments.precheck-worker-enabled=false", "agentflow.budget-adjustments.review-worker-enabled=false", "agentflow.budget-adjustments.execution-worker-enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class SupplierFinanceWorkflowTest {
    private static final HttpServer SERVER = server();
    private static final String ENDPOINT = "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/finance";
    private static final AtomicReference<SupplierFinanceWorkflowTest> ACTIVE = new AtomicReference<>();
    private static final Path DIRECTORY = Path.of("/fyoung/tmp/agentflow-supplier-finance-workflow-" + UUID.randomUUID());
    private final Actor admin = new Actor("demo", "admin", Set.of("ADMIN", "PROCESS_ADMIN"));
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private UUID entity;
    private UUID appointment;
    private UUID manager;
    private UUID finance;
    private UUID financeAppointment;
    private UUID cashierAppointment;
    private BiFunction<String, JsonNode, String> responder;
    private int returnRevision = 1;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("agentflow.attachments.directory", DIRECTORY::toString);
        registry.add("agentflow.finance-gateway.tenants.demo.endpoint", () -> ENDPOINT);
        registry.add("agentflow.payment-callbacks.tenants.demo.signing-secrets[0]", () -> PaymentCallbackTestRequests.SECRET);
        registry.add("agentflow.finance-gateway.tenants.demo.allow-unauthenticated-loopback", () -> "true");
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_SUPPLIER_FINANCE_URL", "jdbc:h2:mem:supplier-finance-workflow;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_SUPPLIER_FINANCE_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_SUPPLIER_FINANCE_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_SUPPLIER_FINANCE_PASSWORD", ""));
    }

    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @Autowired AuthService auth;
    @Autowired CurrentActor actors;
    @Autowired JdbcTemplate jdbc;
    @Autowired io.agentflow.notification.NotificationPreferencesService noticePreferences;
    @Autowired io.agentflow.notification.JdbcNotificationDeliveryStore noticeDeliveries;
    @Autowired io.agentflow.notification.NotificationDeliveryService noticeDeliveryService;
    @Autowired io.agentflow.notification.SupplierPaymentNotificationAccess noticeAccess;
    @Autowired OrganizationService organization;
    @Autowired DefinitionApplicationService definitions;
    @Autowired ApplicationRepository applications;
    @Autowired ProcurementPaymentRepository requests;
    @Autowired TaskService tasks;
    @Autowired ProcurementPaymentCheckService execution;
    @Autowired ProcurementPaymentCheckWorker worker;
    @Autowired JdbcProcurementPaymentCheckRepository checks;
    @Autowired JdbcProcurementPayableReservationRepository reservations;
    @Autowired FinanceGatewayConfiguration configuration;
    @Autowired JdbcSupplierPayableReviewRepository payableReviews;
    @Autowired SupplierPayableReviewService reviewService;
    @Autowired ProcurementPayablePort payablePort;
    @Autowired JdbcSupplierPayableHoldRepository holds;
    @Autowired SupplierPayableHoldService holdService;
    @Autowired SupplierPayableHoldPort holdPort;
    @Autowired JdbcSupplierPaymentAuthorizationRepository authorizations;
    @Autowired JdbcSupplierPaymentExecutionRepository cashierRequests;
    @Autowired JdbcSupplierPaymentOperationRepository bankPayments;
    @Autowired SupplierPaymentExecutionService cashierPreparation;
    @Autowired SupplierPaymentService bankService;
    @Autowired SupplierPaymentEvidenceReader bankReader;
    @Autowired SupplierPaymentPort bankPort;
    @Autowired SupplierCashierAccess cashierAccess;
    @Autowired SupplierSettlementAccess settlementAccess;
    @Autowired io.agentflow.notification.SupplierSettlementNotificationAccess supplierSettlementNoticeAccess;
    @Autowired JdbcSupplierSettlementPreparationRepository settlementPreparations;
    @Autowired JdbcSupplierPayableSettlementRepository settlements;
    @Autowired SupplierSettlementPreparationService settlementPreparation;
    @Autowired SupplierSettlementService settlementExecution;
    @Autowired SupplierSettlementEvidenceReader settlementReader;
    @Autowired SupplierPayableSettlementPort settlementPort;
    @Autowired PaymentCallbackService callbacks;
    @Autowired JdbcPaymentCallbackRepository callbackRecords;
    @Autowired JdbcSupplierPaymentReturnCheckRepository returnChecks;
    @Autowired JdbcSupplierPaymentReturnRepository returnRegistrations;
    @Autowired SupplierPaymentReturnService returnService;
    @Autowired io.agentflow.notification.SupplierReturnNotificationAccess supplierReturnNoticeAccess;
    @Autowired io.agentflow.notification.SupplierAdjustmentNotificationAccess supplierAdjustmentNoticeAccess;
    @Autowired SupplierPaymentReturnPort returnPort;
    @Autowired JdbcSupplierAdjustmentPreparationRepository adjustmentPreparations;
    @Autowired JdbcSupplierPayableAdjustmentRepository adjustments;
    @Autowired SupplierAdjustmentPreparationService adjustmentPreparation;
    @Autowired SupplierAdjustmentService adjustmentExecution;
    @Autowired SupplierAdjustmentEvidenceReader adjustmentReader;
    @Autowired SupplierPayableAdjustmentPort adjustmentPort;
    @Autowired SupplierAdjustmentCompletionService adjustmentCompletion;

    @BeforeEach void setup() {
        ACTIVE.set(this); configuration.setEnabled(true); configuration.getTenants().get("demo").setEndpoint(ENDPOINT);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM organization_directory WHERE tenant_id='demo'", Integer.class) == 0) organization.initialize(admin);
        entity = organization.createUnit(admin, OrganizationUnit.Kind.LEGAL_ENTITY, "合成采购法人", null, null, true).id();
        var department = organization.createUnit(admin, OrganizationUnit.Kind.DEPARTMENT, "采购部门", entity, null, true);
        var position = organization.createUnit(admin, OrganizationUnit.Kind.POSITION, "采购岗位", entity, null, true);
        appointment = organization.createAppointment(admin, person("alice", false), department.id(), position.id(), true).id();
        manager = person("manager", true); finance = person("finance", true);
        financeAppointment = organization.createAppointment(admin, finance, department.id(), position.id(), true).id();
        var cashier = person("cashier", false);
        jdbc.update("UPDATE organization_appointment SET active=FALSE WHERE tenant_id='demo' AND person_id=?", cashier.toString());
        cashierAppointment = organization.createAppointment(admin, cashier, department.id(), position.id(), true).id();
        responder = this::normal;
    }
    @AfterEach void settle() {
        actors.clear(); responder = this::normal;
        for (String id : jdbc.queryForList("SELECT id FROM procurement_payment_check_job WHERE status IN ('QUEUED','RUNNING')", String.class)) {
            var job = check(UUID.fromString(id));
            if (job.status() == Status.QUEUED) job = execution.claim("demo", job.input().id(), Instant.now());
            if (job != null) execution.finish(job, Result.unavailable("INTERNAL_ERROR"), Instant.now());
        }
    }
    @AfterAll static void closeServer() { SERVER.stop(0); }


    @Test void supplierPaymentResultNotifiesOriginalParticipantsWithoutCopyingFinancialFields() throws Exception {
        UUID id = paidBank(); var payment = bankPayments.find("demo", id).orElseThrow();
        var source = payment.command().holdCommand().authorization().source().reservation().source();
        var messages = jdbc.queryForList("SELECT recipient_id,title,content FROM notification_inbox WHERE tenant_id='demo' AND application_id=? AND kind='SUPPLIER_PAYMENT_RESULT'",
                source.applicationId().toString());
        assertThat(messages).extracting(row -> row.get("recipient_id")).containsExactlyInAnyOrder("alice", "finance", "cashier");
        assertThat(json.write(messages)).doesNotContain("70.00", "private-ledger", payment.command().digest(), payment.command().payee().accountDigest());
        ok(send(cashierPath(id) + "/actions", "cashier", bankAction(cashierView(id), "QUERY")), 202); pollBank(id);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE tenant_id='demo' AND application_id=? AND kind='SUPPLIER_PAYMENT_RESULT'",
                Long.class, source.applicationId().toString())).isEqualTo(3);
        assertThat(calls.get("supplier-payment-command").get()).isEqualTo(1);
    }

    @Test void supplierPaymentMessagesReadOnlyOriginalFactsAndRecheckCurrentPermissions() throws Exception {
        UUID id = paidBank(); var request = cashierRequests.registered("demo", id).orElseThrow();
        for (String user : List.of("alice", "finance", "cashier")) {
            String path = supplierNoticePath(id, request.input().id(), user, "SUCCEEDED"); var response = read(path, user); var target = ok(response, 200);
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(target.path("paymentId").asText()).isEqualTo(id.toString());
            assertThat(target.path("executionRequestId").asText()).isEqualTo(request.input().id().toString());
            assertThat(target.at("/payment/preparation/id").asText()).isEqualTo(request.input().id().toString());
            assertThat(target.at("/payment/operation/status").asText()).isEqualTo("SUCCEEDED");
            assertThat(target.at("/payment/actions/execute").asBoolean()).isFalse(); assertThat(target.at("/payment/actions/query").asBoolean()).isFalse();
            assertThat(target.at("/payment/actions/resendOriginal").asBoolean()).isFalse();
            assertThat(target.path("canOpenCashier").asBoolean()).isEqualTo(user.equals("cashier"));
            assertThat(target.toString()).doesNotContain("private-ledger", "debitReference", "commandDigest", "accountDigest", "contractReference");
            for (String stranger : List.of("admin", "bob", "manager")) okError(read(path, stranger), 404, "NOT_FOUND");
            okError(read(path + "?paymentId=" + id, user), 400, "INVALID_INBOX_QUERY");
        }
        String path = supplierNoticePath(id, request.input().id(), "cashier", "SUCCEEDED");
        jdbc.update("UPDATE organization_appointment SET active=FALSE WHERE tenant_id='demo' AND id=?", cashierAppointment.toString());
        okError(read(path, "cashier"), 404, "NOT_FOUND");
        String financePath = supplierNoticePath(id, request.input().id(), "finance", "SUCCEEDED");
        jdbc.update("UPDATE organization_appointment SET active=FALSE WHERE tenant_id='demo' AND id=?", financeAppointment.toString());
        assertThat(read(financePath, "finance").getStatus()).isIn(403, 404);
    }

    @Test void stoppedSupplierRequestMessageNeverChangesToLaterChoiceOrBankPayment() throws Exception {
        UUID id = authorizedHold(approved()); var choice = new java.util.HashMap<>(executeInput(cashierView(id))); choice.put("debitAccountVersion", "outdated");
        var first = ok(send(cashierPath(id) + "/actions", "cashier", choice), 202); UUID firstId = UUID.fromString(first.path("preparationId").asText());
        pollPreparation(firstId); assertThat(cashierRequests.find("demo", firstId).orElseThrow().status()).isEqualTo(SupplierPaymentExecutionRequest.Status.BLOCKED);
        assertThat(bankPayments.find("demo", id)).isEmpty();
        String path = supplierNoticePath(id, firstId, "cashier", "EVIDENCE_CHANGED"); var stopped = ok(read(path, "cashier"), 200);
        assertThat(stopped.path("canOpenCashier").asBoolean()).isFalse(); assertThat(stopped.at("/payment/operation").isNull()).isTrue();
        var second = ok(send(cashierPath(id) + "/actions", "cashier", executeInput(cashierView(id))), 202); UUID secondId = UUID.fromString(second.path("preparationId").asText());
        assertThat(secondId).isNotEqualTo(firstId); pollPreparation(secondId); pollBank(id);
        var original = ok(read(path, "cashier"), 200); assertThat(original.at("/payment/preparation/id").asText()).isEqualTo(firstId.toString());
        assertThat(original.at("/payment/preparation/status").asText()).isEqualTo("BLOCKED"); assertThat(original.at("/payment/operation").isNull()).isTrue();
        var paid = ok(read(supplierNoticePath(id, secondId, "cashier", "SUCCEEDED"), "cashier"), 200);
        assertThat(paid.at("/payment/operation/status").asText()).isEqualTo("SUCCEEDED"); assertThat(paid.path("executionRequestId").asText()).isEqualTo(secondId.toString());
        assertThat(calls.get("supplier-payment-command").get()).isEqualTo(1);
    }

    @Test void supplierRequestExpiryAndNotificationsCommitAtomically() throws Exception {
        UUID id = authorizedHold(approved()); var receipt = ok(send(cashierPath(id) + "/actions", "cashier", executeInput(cashierView(id))), 202);
        UUID requestId = UUID.fromString(receipt.path("preparationId").asText()); var original = cashierRequests.find("demo", requestId).orElseThrow();
        var authorization = authorizations.find("demo", id).orElseThrow(); var application = authorization.source().reservation().source().applicationId();
        jdbc.execute("ALTER TABLE notification_inbox ADD CONSTRAINT supplier_notice_fixture CHECK(application_id<>'" + application + "' OR kind<>'SUPPLIER_PAYMENT_ATTENTION' OR recipient_id<>'cashier')");
        try { assertThatThrownBy(() -> cashierPreparation.claim("demo", requestId, authorization.expiresAt())).isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("ALTER TABLE notification_inbox DROP CONSTRAINT supplier_notice_fixture"); }
        assertThat(cashierRequests.find("demo", requestId)).contains(original);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE application_id=? AND kind='SUPPLIER_PAYMENT_ATTENTION'", Long.class, application.toString())).isZero();
        assertThat(cashierPreparation.claim("demo", requestId, authorization.expiresAt())).isNull();
        assertThat(cashierRequests.find("demo", requestId).orElseThrow().status()).isEqualTo(SupplierPaymentExecutionRequest.Status.EXPIRED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE application_id=? AND kind='SUPPLIER_PAYMENT_ATTENTION'", Long.class, application.toString())).isEqualTo(3);
        assertThat(bankPayments.find("demo", id)).isEmpty(); assertThat(calls.getOrDefault("supplier-payment-command", new AtomicInteger()).get()).isZero();
    }

    @Test void supplierBankConflictAndResolutionKeepDistinctNoticesWithoutRepeatingSuccess() throws Exception {
        UUID id = paidBank(); UUID requestId = cashierRequests.registered("demo", id).orElseThrow().input().id();
        queryDispute(id, "WRONG-RECEIPT", 2);
        var conflict = ok(read(supplierNoticePath(id, requestId, "cashier", "RECONCILING"), "cashier"), 200);
        assertThat(conflict.at("/payment/operation/disputed").asBoolean()).isTrue();
        queryDispute(id, "RECEIPT-1", 3); ok(send(disputePath(id) + "/resolutions", "finance", disputeInput(disputeView(id))), 202);
        var resolved = ok(read(supplierNoticePath(id, requestId, "cashier", "RECONCILING"), "cashier"), 200);
        assertThat(resolved.at("/payment/operation/status").asText()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE tenant_id='demo' AND event_key=?", Long.class,
                "supplier-payment:" + id + ":" + requestId + ":SUCCEEDED")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE tenant_id='demo' AND event_key=?", Long.class,
                "supplier-payment:" + id + ":" + requestId + ":RECONCILING")).isEqualTo(3);
        assertThat(calls.get("supplier-payment-command").get()).isEqualTo(1);
    }

    @Test void supplierNoticeOutboundIntentIsSuppressedWhenOriginalCashierLosesEntityScope() throws Exception {
        var recipient = new Actor("demo", "cashier", Set.of("CASHIER")); var preference = noticePreferences.get(recipient);
        noticePreferences.revise(recipient, preference.version(), true, false);
        try {
            UUID id = paidBank(); UUID requestId = cashierRequests.registered("demo", id).orElseThrow().input().id();
            String key = "supplier-payment:" + id + ":" + requestId + ":SUCCEEDED";
            UUID deliveryId = UUID.fromString(jdbc.queryForObject("SELECT d.id FROM notification_dispatch d JOIN notification_inbox n ON n.id=d.inbox_id WHERE n.event_key=? AND n.recipient_id='cashier'", String.class, key));
            var intent = noticeDeliveries.find(deliveryId).orElseThrow(); assertThat(noticeAccess.deliveryAllowed(intent)).isTrue();
            jdbc.update("UPDATE organization_appointment SET active=FALSE WHERE tenant_id='demo' AND id=?", cashierAppointment.toString());
            assertThat(noticeAccess.deliveryAllowed(intent)).isFalse(); assertThat(noticeDeliveryService.claim(deliveryId, Instant.now())).isNull();
            var suppressed = noticeDeliveries.find(deliveryId).orElseThrow().progress();
            assertThat(suppressed.status()).isEqualTo(io.agentflow.notification.NotificationDeliveryProgress.Status.SUPPRESSED);
            assertThat(suppressed.errorCode()).isEqualTo(io.agentflow.notification.NotificationDeliveryProgress.FailureCode.MESSAGE_UNAVAILABLE);
            assertThat(suppressed.attempts()).isZero();
        } finally {
            var current = noticePreferences.get(recipient); noticePreferences.revise(recipient, current.version(), preference.emailEnabled(), preference.enterpriseImEnabled());
        }
    }

    @Test void pendingSupplierBankAndExplicitQueryDoNotNotifyUntilAnActualUnknownFailure() throws Exception {
        UUID id = authorizedHold(approved()); var receipt = ok(send(cashierPath(id) + "/actions", "cashier", executeInput(cashierView(id))), 202);
        UUID requestId = UUID.fromString(receipt.path("preparationId").asText()); pollPreparation(requestId);
        var command = bankPayments.find("demo", id).orElseThrow().command(); var app = command.holdCommand().authorization().source().reservation().source().applicationId();
        responder = (operation, request) -> operation.equals("supplier-payment-command")
                ? json.write(Map.of("contractVersion", 1, "tenantId", "demo", "requestId", request.path("requestId").asText(), "outcome", "SUCCESS", "data",
                    new PaymentObservation(id, command.digest(), PaymentObservation.Status.PENDING, 1L, Instant.now(), "BANK-1", null, null, null, null, null))) : normal(operation, request);
        pollBank(id); assertThat(bankPayments.find("demo", id).orElseThrow().status()).isEqualTo(SupplierPaymentOperation.Status.UNKNOWN);
        assertThat(bankPayments.find("demo", id).orElseThrow().observation().status()).isEqualTo(PaymentObservation.Status.PENDING);
        assertThat(bankPayments.find("demo", id).orElseThrow().failure()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE application_id=? AND kind IN ('SUPPLIER_PAYMENT_RESULT','SUPPLIER_PAYMENT_ATTENTION')", Long.class, app.toString())).isZero();
        ok(send(cashierPath(id) + "/actions", "cashier", bankAction(cashierView(id), "QUERY")), 202);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE application_id=? AND kind='SUPPLIER_PAYMENT_ATTENTION'", Long.class, app.toString())).isZero();
        var querying = bankService.claim("demo", id, Instant.now()); bankService.fail(querying, Instant.now());
        var target = ok(read(supplierNoticePath(id, requestId, "cashier", "UNKNOWN"), "cashier"), 200);
        assertThat(target.at("/payment/operation/status").asText()).isEqualTo("UNKNOWN");
        assertThat(target.at("/payment/operation/issue").asText()).isEqualTo("INTERNAL_ERROR");
        assertThat(calls.get("supplier-payment-command").get()).isEqualTo(1);
    }

    private String supplierNoticePath(UUID payment, UUID request, String user, String fact) {
        String id = jdbc.queryForObject("SELECT id FROM notification_inbox WHERE tenant_id='demo' AND recipient_id=? AND event_key=?", String.class,
                user, "supplier-payment:" + payment + ":" + request + ":" + fact);
        return "/api/v1/notifications/" + id + "/supplier-payment-target";
    }

    @Test void payableReviewFailureNotifiesOriginalParticipant() throws Exception {
        UUID id = approved();
        UUID review = UUID.fromString(ok(send(financePath(id) + "/reviews", "finance", reviewInput(id)), 202).path("reviewId").asText());
        var claimed = reviewService.claim("demo", review, Instant.now());
        reviewService.fail(claimed, Instant.now());
        assertThat(payableReviews.find("demo", review).orElseThrow().status()).isEqualTo(SupplierPayableReview.Status.UNAVAILABLE);
        assertThat(jdbc.queryForList("SELECT recipient_id FROM notification_inbox WHERE tenant_id='demo' AND application_id=? AND kind='SUPPLIER_PAYABLE_ATTENTION'", String.class,
                app(id).id().toString())).contains("finance");
    }

    @Test void payableHoldUnknownNotifiesOriginalParticipants() throws Exception {
        UUID id = approved(); queueAndRead(id);
        UUID authorization = UUID.fromString(ok(send(financePath(id) + "/authorizations", "finance", authorizeInput(id, view(id, "finance"))), 202).path("authorizationId").asText());
        var claimed = holdService.claim("demo", authorization, Instant.now());
        holdService.fail(claimed, SupplierPayableHoldOperation.Failure.TIMEOUT, Instant.now());
        assertThat(holds.find("demo", authorization).orElseThrow().status()).isEqualTo(SupplierPayableHoldOperation.Status.UNKNOWN);
        assertThat(jdbc.queryForList("SELECT recipient_id FROM notification_inbox WHERE tenant_id='demo' AND application_id=? AND kind='SUPPLIER_PAYABLE_ATTENTION'", String.class,
                app(id).id().toString())).containsExactlyInAnyOrder("alice", "finance");
    }

    @Test void payableAuthorizationRetirementNotifiesActualDecision() throws Exception {
        UUID id = approved(); queueAndRead(id);
        UUID authorization = UUID.fromString(ok(send(financePath(id) + "/authorizations", "finance", authorizeInput(id, view(id, "finance"))), 202).path("authorizationId").asText());
        ok(send(actionUrl(authorization), "finance", action(view(id, "finance"), "RETIRE")), 202);
        assertThat(authorizations.retirement("demo", authorization)).isPresent();
        assertThat(jdbc.queryForList("SELECT recipient_id FROM notification_inbox WHERE tenant_id='demo' AND application_id=? AND kind='SUPPLIER_PAYABLE_RESULT'", String.class,
                app(id).id().toString())).containsExactlyInAnyOrder("alice", "finance");
    }

    @Test void payableReviewNotificationKeepsFailedOriginalAndRechecksFinanceFields() throws Exception {
        UUID id = approved(); UUID review = UUID.fromString(ok(send(financePath(id) + "/reviews", "finance", reviewInput(id)), 202).path("reviewId").asText());
        var claimed = reviewService.claim("demo", review, Instant.now()); reviewService.fail(claimed, Instant.now());
        String path = payableNoticePath(review, "finance", "REVIEW_UNAVAILABLE"); var response = read(path, "finance"); var original = ok(response, 200);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store"); assertThat(original.at("/review/id").asText()).isEqualTo(review.toString());
        assertThat(original.path("operation").isNull()).isTrue(); assertThat(original.toString()).doesNotContain("amount", "account", "payableReference", "commandDigest", "requestedBy", "actions");
        UUID next = queueAndRead(id); assertThat(next).isNotEqualTo(review); assertThat(ok(read(path, "finance"), 200)).isEqualTo(original);
        for (String stranger : List.of("alice", "cashier", "admin", "bob", "manager")) okError(read(path, stranger), 404, "NOT_FOUND");
        okError(read(path + "?roundNo=1", "finance"), 400, "INVALID_INBOX_QUERY");
        jdbc.update("UPDATE organization_person SET approval_eligible=FALSE WHERE tenant_id='demo' AND id=?", finance.toString());
        try { assertThat(read(path, "finance").getStatus()).isIn(403, 404); }
        finally { jdbc.update("UPDATE organization_person SET approval_eligible=TRUE WHERE tenant_id='demo' AND id=?", finance.toString()); }
        jdbc.update("UPDATE organization_appointment SET active=FALSE WHERE tenant_id='demo' AND id=?", financeAppointment.toString());
        okError(read(path, "finance"), 404, "NOT_FOUND");
    }

    @Test void payableHoldNotificationQueriesSameOriginalAndPreservesHistoryWithoutDuplicates() throws Exception {
        UUID id = approved(); queueAndRead(id);
        UUID authorization = UUID.fromString(ok(send(financePath(id) + "/authorizations", "finance", authorizeInput(id, view(id, "finance"))), 202).path("authorizationId").asText());
        var claimed = holdService.claim("demo", authorization, Instant.now()); holdService.fail(claimed, SupplierPayableHoldOperation.Failure.TIMEOUT, Instant.now());
        String path = payableNoticePath(authorization, "alice", "UNKNOWN"); var first = ok(read(path, "alice"), 200);
        assertThat(first.at("/operation/status").asText()).isEqualTo("UNKNOWN");
        ok(send(actionUrl(authorization), "finance", action(view(id, "finance"), "QUERY")), 202); pollHold(authorization);
        var recovered = ok(read(path, "alice"), 200); assertThat(recovered.path("sourceId").asText()).isEqualTo(authorization.toString());
        assertThat(recovered.path("fact").asText()).isEqualTo("UNKNOWN"); assertThat(recovered.at("/operation/status").asText()).isEqualTo("HELD");
        assertThat(recovered.at("/operation/observation/outcome").asText()).isEqualTo("HELD");
        assertThat(recovered.toString()).doesNotContain("heldAmount", "holdReference", "ledgerVersion", "accountDigest", "commandDigest", "actions");
        ok(send(actionUrl(authorization), "finance", action(view(id, "finance"), "QUERY")), 202); pollHold(authorization);
        holdService.finish(claimed, new io.agentflow.finance.FinanceResult.Success<>(held(claimed.command())), Instant.now());
        assertThat(payableNoticeCount(authorization, "HELD")).isEqualTo(2); assertThat(payableNoticeCount(authorization, "UNKNOWN")).isEqualTo(2);
        assertThat(calls.getOrDefault("supplier-payable-hold-command", new AtomicInteger()).get()).isZero();
        assertThat(calls.get("supplier-payable-hold-query").get()).isEqualTo(2);
        for (String user : List.of("cashier", "admin", "bob")) okError(read(path, user), 404, "NOT_FOUND");
    }

    @Test void payableRetirementNotificationKeepsActualDecisionAfterReplacement() throws Exception {
        UUID id = approved(); queueAndRead(id);
        UUID original = UUID.fromString(ok(send(financePath(id) + "/authorizations", "finance", authorizeInput(id, view(id, "finance"))), 202).path("authorizationId").asText());
        String key = UUID.randomUUID().toString(); var input = action(view(id, "finance"), "RETIRE");
        ok(send(actionUrl(original), "finance", key, input), 202); ok(send(actionUrl(original), "finance", key, input), 202);
        String path = payableNoticePath(original, "alice", "RETIRED"); var target = ok(read(path, "alice"), 200);
        assertThat(target.at("/retirement/operationId").asText()).isEqualTo(original.toString());
        assertThat(target.at("/retirement/basis").asText()).isEqualTo("NEVER_DISPATCHED");
        assertThat(target.at("/operation/failure").asText()).isEqualTo("FINANCE_RETIRED");
        queueAndRead(id); var replacement = ok(send(financePath(id) + "/authorizations", "finance", authorizeInput(id, view(id, "finance"))), 202);
        assertThat(replacement.path("authorizationId").asText()).isNotEqualTo(original.toString());
        assertThat(ok(read(path, "alice"), 200)).isEqualTo(target); assertThat(payableNoticeCount(original, "RETIRED")).isEqualTo(2);
        assertThat(payableNoticeCount(original, "VOIDED")).isZero(); assertThat(calls.keySet()).doesNotContain("supplier-payable-hold-command");
    }

    @Test void payableNotificationRejectsUnrecordedFactAndTimestamp() throws Exception {
        UUID id = authorizedHold(approved()); String key = "supplier-payable:OPERATION:" + id + ":HELD";
        String path = payableNoticePath(id, "alice", "HELD"); var original = ok(read(path, "alice"), 200);
        jdbc.update("UPDATE notification_inbox SET event_key=? WHERE tenant_id='demo' AND event_key=? AND recipient_id='alice'", "supplier-payable:OPERATION:" + id + ":RETIRED", key);
        try { okError(read(path, "alice"), 404, "NOT_FOUND"); }
        finally { jdbc.update("UPDATE notification_inbox SET event_key=? WHERE tenant_id='demo' AND event_key=? AND recipient_id='alice'", key, "supplier-payable:OPERATION:" + id + ":RETIRED"); }
        var timestamp = jdbc.queryForObject("SELECT created_at FROM notification_inbox WHERE tenant_id='demo' AND event_key=? AND recipient_id='alice'", java.sql.Timestamp.class, key);
        jdbc.update("UPDATE notification_inbox SET created_at=? WHERE tenant_id='demo' AND event_key=? AND recipient_id='alice'", java.sql.Timestamp.from(timestamp.toInstant().plusSeconds(1)), key);
        try { okError(read(path, "alice"), 404, "NOT_FOUND"); }
        finally { jdbc.update("UPDATE notification_inbox SET created_at=? WHERE tenant_id='demo' AND event_key=? AND recipient_id='alice'", timestamp, key); }
        assertThat(ok(read(path, "alice"), 200)).isEqualTo(original);
    }

    @Test void payableNotificationFailureRollsBackOriginalStateAndDispatchThenRevokedScopeSuppresses() throws Exception {
        var recipient = new Actor("demo", "finance", Set.of("FINANCE")); var before = noticePreferences.get(recipient);
        noticePreferences.revise(recipient, before.version(), true, false);
        try {
            UUID id = approved(); queueAndRead(id);
            UUID authorization = UUID.fromString(ok(send(financePath(id) + "/authorizations", "finance", authorizeInput(id, view(id, "finance"))), 202).path("authorizationId").asText());
            var claimed = holdService.claim("demo", authorization, Instant.now()); var application = app(id).id();
            jdbc.execute("ALTER TABLE notification_inbox ADD CONSTRAINT payable_notice_fixture CHECK(application_id<>'" + application + "' OR kind<>'SUPPLIER_PAYABLE_ATTENTION' OR recipient_id<>'finance')");
            try { assertThatThrownBy(() -> holdService.fail(claimed, SupplierPayableHoldOperation.Failure.TIMEOUT, Instant.now())).isInstanceOf(RuntimeException.class); }
            finally { jdbc.execute("ALTER TABLE notification_inbox DROP CONSTRAINT payable_notice_fixture"); }
            assertThat(holds.find("demo", authorization)).contains(claimed); assertThat(payableNoticeCount(authorization, "UNKNOWN")).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_dispatch d JOIN notification_inbox n ON d.inbox_id=n.id WHERE n.application_id=? AND n.kind='SUPPLIER_PAYABLE_ATTENTION'", Long.class, application.toString())).isZero();
            holdService.fail(claimed, SupplierPayableHoldOperation.Failure.TIMEOUT, Instant.now());
            UUID deliveryId = UUID.fromString(jdbc.queryForObject("SELECT d.id FROM notification_dispatch d JOIN notification_inbox n ON n.id=d.inbox_id WHERE n.tenant_id='demo' AND n.event_key=? AND n.recipient_id='finance'", String.class, "supplier-payable:OPERATION:" + authorization + ":UNKNOWN"));
            jdbc.update("UPDATE organization_appointment SET active=FALSE WHERE tenant_id='demo' AND id=?", financeAppointment.toString());
            assertThat(noticeDeliveryService.claim(deliveryId, Instant.now())).isNull();
            assertThat(noticeDeliveries.find(deliveryId).orElseThrow().progress().errorCode()).isEqualTo(io.agentflow.notification.NotificationDeliveryProgress.FailureCode.MESSAGE_UNAVAILABLE);
        } finally { var current = noticePreferences.get(recipient); noticePreferences.revise(recipient, current.version(), before.emailEnabled(), before.enterpriseImEnabled()); }
    }

    @Test void payableReviewLeaseNoticeKeepsOriginalRequestAfterRecovery() throws Exception {
        UUID id = approved(); UUID review = UUID.fromString(ok(send(financePath(id) + "/reviews", "finance", reviewInput(id)), 202).path("reviewId").asText());
        var first = reviewService.claim("demo", review, Instant.now()); assertThat(reviewService.claim("demo", review, first.leaseUntil())).isNull();
        String path = payableNoticePath(review, "finance", "REVIEW_INTERRUPTED");
        assertThat(ok(read(path, "finance"), 200).at("/review/status").asText()).isEqualTo("QUEUED");
        var next = reviewService.claim("demo", review, first.leaseUntil()); reviewService.fail(next, first.leaseUntil());
        var target = ok(read(path, "finance"), 200); assertThat(target.path("fact").asText()).isEqualTo("REVIEW_INTERRUPTED");
        assertThat(target.at("/review/id").asText()).isEqualTo(review.toString()); assertThat(target.at("/review/status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(target.path("operation").isNull()).isTrue();
    }

    private String payableNoticePath(UUID source, String recipient, String fact) {
        String type = fact.startsWith("REVIEW_") ? "REVIEW" : "OPERATION";
        return "/api/v1/notifications/" + jdbc.queryForObject("SELECT id FROM notification_inbox WHERE tenant_id='demo' AND recipient_id=? AND event_key=?", String.class,
                recipient, "supplier-payable:" + type + ":" + source + ":" + fact) + "/supplier-payable-target";
    }
    private long payableNoticeCount(UUID source, String fact) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE tenant_id='demo' AND event_key=?", Long.class,
                "supplier-payable:OPERATION:" + source + ":" + fact);
    }

    @Test void explicitReviewAndAuthorizationUseOriginalFactsAndReplayOnlyOneDecision() throws Exception {
        UUID id = approved(); var initial = view(id, "finance"); assertThat(initial.at("/actions/review").asBoolean()).isTrue();
        var body = reviewInput(id); String reviewKey = UUID.randomUUID().toString();
        var queued = send(financePath(id) + "/reviews", "finance", reviewKey, body); var reviewReceipt = ok(queued, 202);
        assertThat(queued.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(send(financePath(id) + "/reviews", "finance", reviewKey, body).getContentAsString()).isEqualTo(queued.getContentAsString());
        UUID reviewId = UUID.fromString(reviewReceipt.path("reviewId").asText());
        assertThat(reviewReceipt.path("authorizationId").isNull()).isTrue(); assertThat(reviewReceipt.toString()).doesNotContain("account", "amount", "outstanding", "payableReference");
        assertThat(view(id, "alice").path("review").isNull()).isTrue();
        readPayable(reviewId); var ready = view(id, "finance"); assertThat(ready.at("/review/status").asText()).isEqualTo("READY");
        assertThat(ready.at("/review/outstanding/value").asText()).isEqualTo("70.00"); assertThat(ready.at("/actions/authorize").asBoolean()).isTrue();
        assertThat(count("supplier_payment_authorization", id)).isZero(); assertPrivateFactsAbsent(ready);
        var input = authorizeInput(id, ready); String key = UUID.randomUUID().toString(); var response = send(financePath(id) + "/authorizations", "finance", key, input);
        var receipt = ok(response, 202); UUID authorization = UUID.fromString(receipt.path("authorizationId").asText());
        assertThat(send(financePath(id) + "/authorizations", "finance", key, input).getContentAsString()).isEqualTo(response.getContentAsString());
        assertThat(count("supplier_payment_authorization", id)).isEqualTo(1); assertThat(view(id, "finance").at("/hold/status").asText()).isEqualTo("QUEUED");
        assertThat(holds.find("demo", authorization).orElseThrow().command().authorization().source().reservation().source().requestId()).isEqualTo(id);
        pollHold(authorization); var reserved = view(id, "finance"); assertThat(reserved.at("/hold/status").asText()).isEqualTo("HELD"); assertPrivateFactsAbsent(reserved);
        assertThat(reserved.at("/actions/retire").asBoolean()).isFalse();
        ok(send(actionUrl(authorization), "finance", action(reserved, "QUERY")), 202); pollHold(authorization);
        assertThat(calls.get("supplier-payable-hold-command").get()).isEqualTo(1); assertThat(calls.get("supplier-payable-hold-query").get()).isEqualTo(1);
        var applicant = view(id, "alice"); assertThat(applicant.path("review").isNull()).isTrue(); assertThat(applicant.at("/hold/status").asText()).isEqualTo("HELD");
        assertThat(applicant.path("actions").properties()).allMatch(entry -> !entry.getValue().asBoolean());
        var audit = jdbc.queryForList("SELECT actor_id,action,payload_json FROM audit_event WHERE application_id=? AND aggregate_type='SupplierPayment' ORDER BY occurred_at", app(id).id().toString());
        assertThat(audit).hasSize(3); assertThat(audit).allSatisfy(event -> assertThat(event.get("actor_id")).isEqualTo("finance"));
        assertThat(audit.toString()).doesNotContain("private-supplier-account", "accountDigest", "outstanding"); assertNoFinancialWrites(id);
    }

    @Test void strictInputDisplayedVersionsAndReadyEvidenceAreRequired() throws Exception {
        UUID id = approved(); var body = reviewInput(id);
        var forged = new java.util.HashMap<>(body); forged.put("accountReference", "forged-account");
        okError(send(financePath(id) + "/reviews", "finance", forged), 400, "INVALID_REQUEST");
        var stale = new java.util.HashMap<>(body); stale.put("requestVersion", current(id).version() - 1);
        okError(send(financePath(id) + "/reviews", "finance", stale), 409, "CONCURRENCY_CONFLICT");
        var queued = ok(send(financePath(id) + "/reviews", "finance", body), 202);
        okError(send(financePath(id) + "/reviews", "finance", body), 409, "SUPPLIER_PAYABLE_REVIEW_PENDING");
        var input = new java.util.HashMap<>(body); input.put("reviewId", queued.path("reviewId").asText()); input.put("reviewVersion", queued.path("reviewVersion").asLong());
        okError(send(financePath(id) + "/authorizations", "finance", input), 422, "SUPPLIER_PAYABLE_REVIEW_UNAVAILABLE");
        input.put("amount", Map.of("value", "999", "currency", "CNY")); okError(send(financePath(id) + "/authorizations", "finance", input), 400, "INVALID_REQUEST");
        okError(read(financePath(id) + "?account=forged", "finance"), 400, "INVALID_SUPPLIER_PAYMENT_QUERY");
        okError(read(financePath(id) + "?roundNo=0", "finance"), 400, "INVALID_SUPPLIER_PAYMENT_QUERY");
        assertThat(count("supplier_payment_authorization", id)).isZero();
    }

    @Test void replayRequiresCurrentAppointmentAndApplicantCashierOrAdministratorCannotBypassFields() throws Exception {
        UUID id = approved(); var body = reviewInput(id); String key = UUID.randomUUID().toString();
        var first = send(financePath(id) + "/reviews", "finance", key, body); ok(first, 202);
        jdbc.update("UPDATE organization_appointment SET active=FALSE WHERE tenant_id='demo' AND id=?", financeAppointment.toString());
        okError(send(financePath(id) + "/reviews", "finance", key, body), 403, "FORBIDDEN");
        assertThat(view(id, "finance").path("review").isNull()).isTrue();
        jdbc.update("UPDATE organization_appointment SET active=TRUE WHERE tenant_id='demo' AND id=?", financeAppointment.toString());
        assertThat(send(financePath(id) + "/reviews", "finance", key, body).getContentAsString()).isEqualTo(first.getContentAsString());
        for (String subject : List.of("alice", "cashier", "admin")) {
            assertThat(send(financePath(id) + "/reviews", subject, body).getStatus()).isBetween(400, 499);
        }
        assertThat(read(financePath(id), "admin").getStatus()).isBetween(400, 499);
        jdbc.update("UPDATE organization_person SET approval_eligible=FALSE WHERE tenant_id='demo' AND id=?", finance.toString());
        try {
            // 仍有财务角色和法人任职，但原节点字段权限已经失效，旧回执也不能绕过。
            okError(send(financePath(id) + "/reviews", "finance", key, body), 403, "FORBIDDEN");
            assertThat(read(financePath(id), "finance").getStatus()).isBetween(400, 499);
        } finally { jdbc.update("UPDATE organization_person SET approval_eligible=TRUE WHERE tenant_id='demo' AND id=?", finance.toString()); }
    }

    @Test void safeRetirementStopsOriginalQueueAndReplacementRequiresAnotherRead() throws Exception {
        UUID id = approved(); UUID review = queueAndRead(id); var ready = view(id, "finance"); var input = authorizeInput(id, ready);
        UUID original = UUID.fromString(ok(send(financePath(id) + "/authorizations", "finance", input), 202).path("authorizationId").asText());
        var queued = view(id, "finance"); String key = UUID.randomUUID().toString(); var retirement = action(queued, "RETIRE");
        var first = send(actionUrl(original), "finance", key, retirement); ok(first, 202);
        assertThat(send(actionUrl(original), "finance", key, retirement).getContentAsString()).isEqualTo(first.getContentAsString());
        var retired = view(id, "finance"); assertThat(retired.at("/hold/status").asText()).isEqualTo("VOIDED"); assertThat(retired.at("/authorization/retirementBasis").asText()).isEqualTo("NEVER_DISPATCHED");
        assertThat(retired.at("/actions/review").asBoolean()).isTrue(); assertThat(retired.at("/actions/authorize").asBoolean()).isFalse();
        okError(send(financePath(id) + "/authorizations", "finance", input), 422, "SUPPLIER_PAYABLE_REVIEW_UNAVAILABLE");
        assertThat(queueAndRead(id)).isNotEqualTo(review);
        UUID replacement = UUID.fromString(ok(send(financePath(id) + "/authorizations", "finance", authorizeInput(id, view(id, "finance"))), 202).path("authorizationId").asText());
        assertThat(replacement).isNotEqualTo(original); assertThat(authorizations.find("demo", original)).isPresent(); assertThat(holdService.claim("demo", original, Instant.now())).isNull();
        assertThat(reservations.active("demo", id)).isPresent(); assertThat(calls.keySet()).doesNotContain("supplier-payable-hold-command");
    }

    @Test void auditFailureRollsBackAuthorizationConsumptionQueueAndIdempotencyResponse() throws Exception {
        UUID id = approved(); UUID review = queueAndRead(id); var input = authorizeInput(id, view(id, "finance")); String key = UUID.randomUUID().toString();
        jdbc.execute("ALTER TABLE audit_event ADD CONSTRAINT fixture_supplier_audit CHECK(action<>'SUPPLIER_PAYMENT_AUTHORIZE' OR application_id<>'" + app(id).id() + "')");
        try { assertThatThrownBy(() -> send(financePath(id) + "/authorizations", "finance", key, input)).hasCauseInstanceOf(org.springframework.dao.DataIntegrityViolationException.class); }
        finally { jdbc.execute("ALTER TABLE audit_event DROP CONSTRAINT fixture_supplier_audit"); }
        assertThat(count("supplier_payment_authorization", id)).isZero(); assertThat(payableReviews.find("demo", review).orElseThrow().status()).isEqualTo(SupplierPayableReview.Status.READY);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_idempotency WHERE tenant_id='demo' AND idempotency_key=?", Integer.class, key)).isZero();
        ok(send(financePath(id) + "/authorizations", "finance", key, input), 202); assertThat(count("supplier_payment_authorization", id)).isEqualTo(1);
    }

    @Test void unknownHoldCanOnlyQueryOriginalAuthorizationAndCannotBeRetired() throws Exception {
        UUID id = approved(); queueAndRead(id);
        UUID authorization = UUID.fromString(ok(send(financePath(id) + "/authorizations", "finance", authorizeInput(id, view(id, "finance"))), 202).path("authorizationId").asText());
        var claimed = holdService.claim("demo", authorization, Instant.now()); holdService.fail(claimed, SupplierPayableHoldOperation.Failure.TIMEOUT, Instant.now());
        var unknown = view(id, "finance"); assertThat(unknown.at("/hold/status").asText()).isEqualTo("UNKNOWN");
        okError(send(actionUrl(authorization), "finance", action(unknown, "RETIRE")), 409, "SUPPLIER_AUTHORIZATION_RETIREMENT_UNSAFE");
        okError(send(actionUrl(authorization), "finance", action(unknown, "RETRY")), 409, "SUPPLIER_PAYABLE_HOLD_STATE_CONFLICT");
        ok(send(actionUrl(authorization), "finance", action(unknown, "QUERY")), 202); pollHold(authorization);
        assertThat(view(id, "finance").at("/hold/status").asText()).isEqualTo("HELD");
        assertThat(calls.keySet()).doesNotContain("supplier-payable-hold-command"); assertThat(calls.get("supplier-payable-hold-query").get()).isEqualTo(1);
    }

    @Test void anotherRequestsFreshReviewCannotAuthorizeThisApprovedPayable() throws Exception {
        UUID first = approved(); queueAndRead(first); var firstReady = view(first, "finance");
        UUID second = approved(); var crossed = authorizeInput(second, firstReady);
        okError(send(financePath(second) + "/authorizations", "finance", crossed), 409, "CONCURRENCY_CONFLICT");
        assertThat(count("supplier_payment_authorization", first)).isZero(); assertThat(count("supplier_payment_authorization", second)).isZero();
        assertThat(view(first, "finance").at("/review/status").asText()).isEqualTo("READY");
    }

    @Test void authorizationAndHoldActionReplaysAlsoLosePermissionAfterAppointmentEnds() throws Exception {
        UUID id = approved(); queueAndRead(id); var input = authorizeInput(id, view(id, "finance")); String key = UUID.randomUUID().toString();
        var first = send(financePath(id) + "/authorizations", "finance", key, input); UUID authorization = UUID.fromString(ok(first, 202).path("authorizationId").asText());
        var retireInput = action(view(id, "finance"), "RETIRE"); String retireKey = UUID.randomUUID().toString();
        ok(send(actionUrl(authorization), "finance", retireKey, retireInput), 202);
        jdbc.update("UPDATE organization_appointment SET active=FALSE WHERE tenant_id='demo' AND id=?", financeAppointment.toString());
        try {
            okError(send(financePath(id) + "/authorizations", "finance", key, input), 403, "FORBIDDEN");
            okError(send(actionUrl(authorization), "finance", retireKey, retireInput), 403, "FORBIDDEN");
        } finally { jdbc.update("UPDATE organization_appointment SET active=TRUE WHERE tenant_id='demo' AND id=?", financeAppointment.toString()); }
        assertThat(send(financePath(id) + "/authorizations", "finance", key, input).getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(count("supplier_payment_authorization", id)).isEqualTo(1);
    }

    @Test void cashierScopeAccountSelectionAndBankExecutionPreserveOneOriginalCommand() throws Exception {
        UUID request = approved(); UUID id = authorizedHold(request); var displayed = cashierView(id);
        assertThat(displayed.at("/actions/execute").asBoolean()).isTrue(); assertThat(displayed.at("/amount/value").asText()).isEqualTo("70.00");
        assertCashierPrivateFactsAbsent(displayed); assertThat(read(path(request), "cashier").getStatus()).isBetween(400, 499);
        var options = ok(read(cashierPath(id) + "/accounts", "cashier"), 200); assertThat(options.at("/items/0/reference").asText()).isEqualTo("debit-1");
        var input = executeInput(displayed); String key = UUID.randomUUID().toString(); var response = send(cashierPath(id) + "/actions", "cashier", key, input);
        var receipt = ok(response, 202); assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(send(cashierPath(id) + "/actions", "cashier", key, input).getContentAsString()).isEqualTo(response.getContentAsString());
        assertThat(receipt.toString()).doesNotContain("debit", "amount", "account", "supplierName");
        assertThat(cashierView(id).at("/preparation/status").asText()).isEqualTo("QUEUED");
        var preparation = UUID.fromString(receipt.path("preparationId").asText()); pollPreparation(preparation);
        var registered = cashierView(id); assertThat(registered.at("/preparation/status").asText()).isEqualTo("READY"); assertThat(registered.at("/operation/status").asText()).isEqualTo("QUEUED");
        pollBank(id); var paid = cashierView(id); assertThat(paid.at("/operation/status").asText()).isEqualTo("SUCCEEDED"); assertCashierPrivateFactsAbsent(paid);
        assertThat(paid.at("/hold/status").asText()).isEqualTo("HELD"); assertThat(calls.get("supplier-payment-command").get()).isEqualTo(1);
        assertThat(bankPayments.find("demo", id).orElseThrow().command().id()).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM supplier_payment_execution_request WHERE tenant_id='demo' AND authorization_id=?", Integer.class, id.toString())).isEqualTo(1);
        var audit = jdbc.queryForList("SELECT actor_id,payload_json FROM audit_event WHERE application_id=? AND action='SUPPLIER_PAYMENT_EXECUTE'", app(request).id().toString());
        assertThat(audit).hasSize(1); assertThat(audit.get(0).get("actor_id")).isEqualTo("cashier"); assertThat(audit.toString()).doesNotContain("debit-1", "accountDigest", "commandDigest"); assertNoFinancialWrites(request);
    }

    @Test void cashierPaginationIsBoundedAndLosingEntityScopeHidesDetailsAndCursor() throws Exception {
        UUID first = authorizedHold(approved()); UUID second = authorizedHold(approved());
        var page = ok(read("/api/v1/cashier/supplier-payments?limit=1", "cashier"), 200);
        assertThat(page.path("items").size()).isEqualTo(1); assertThat(page.at("/items/0/authorizationId").asText()).isEqualTo(second.toString());
        assertThat(page.path("nextBeforeId").asText()).isEqualTo(second.toString());
        var next = ok(read("/api/v1/cashier/supplier-payments?limit=1&beforeId=" + second, "cashier"), 200);
        assertThat(next.at("/items/0/authorizationId").asText()).isEqualTo(first.toString()); assertThat(next.path("nextBeforeId").isNull()).isTrue();
        for (String query : List.of("limit=101", "limit=0", "beforeId=bad", "tenantId=other", "legalEntityId=" + entity)) okError(read("/api/v1/cashier/supplier-payments?" + query, "cashier"), 400, "INVALID_PAYMENT_QUERY");
        jdbc.update("UPDATE organization_appointment SET active=FALSE WHERE tenant_id='demo' AND id=?", cashierAppointment.toString());
        okError(read(cashierPath(second), "cashier"), 404, "NOT_FOUND"); okError(read("/api/v1/cashier/supplier-payments?beforeId=" + second, "cashier"), 404, "NOT_FOUND");
        assertThat(ok(read("/api/v1/cashier/supplier-payments", "cashier"), 200).path("items").isEmpty()).isTrue();
    }

    @Test void cashierEntryRejectsOtherRolesSeparationViolationsUnknownFactsAndStaleHoldVersion() throws Exception {
        UUID id = authorizedHold(approved()); var displayed = cashierView(id); var input = executeInput(displayed);
        for (String user : List.of("alice", "finance", "admin", "manager", "bob")) {
            okError(read(cashierPath(id), user), 403, "FORBIDDEN"); okError(read(cashierPath(id) + "/accounts", user), 403, "FORBIDDEN");
            okError(send(cashierPath(id) + "/actions", user, input), 403, "FORBIDDEN");
        }
        for (String user : List.of("alice", "finance")) {
            actors.set(new Actor("demo", user, Set.of("CASHIER")));
            try { assertThatThrownBy(() -> cashierAccess.requireExecution(id)).isInstanceOfSatisfying(io.agentflow.common.DomainException.class, error -> assertThat(error.code()).isEqualTo("FORBIDDEN")); }
            finally { actors.clear(); }
        }
        var invalid = new java.util.HashMap<>(input); invalid.put("amount", Map.of("value", "999", "currency", "CNY"));
        okError(send(cashierPath(id) + "/actions", "cashier", invalid), 400, "INVALID_REQUEST");
        invalid.remove("amount"); invalid.put("holdVersion", displayed.at("/hold/version").asLong() - 1);
        okError(send(cashierPath(id) + "/actions", "cashier", invalid), 409, "CONCURRENCY_CONFLICT");
        invalid.put("holdVersion", displayed.at("/hold/version").asLong()); invalid.put("operationVersion", 1);
        okError(send(cashierPath(id) + "/actions", "cashier", invalid), 400, "INVALID_REQUEST"); assertThat(cashierRequests.owner("demo", id)).isEmpty(); assertThat(bankPayments.find("demo", id)).isEmpty();
    }

    @Test void allCashierWriteReplaysRequireCurrentScopeAndNotFoundRetryPreservesOriginalCommand() throws Exception {
        UUID id = authorizedHold(approved()); String executeKey = UUID.randomUUID().toString(); var execute = executeInput(cashierView(id));
        var first = send(cashierPath(id) + "/actions", "cashier", executeKey, execute); UUID preparation = UUID.fromString(ok(first, 202).path("preparationId").asText()); pollPreparation(preparation);
        var command = bankPayments.find("demo", id).orElseThrow().command();
        responder = (operation, request) -> operation.equals("supplier-payment-command")
                ? json.write(Map.of("contractVersion", 1, "tenantId", "demo", "requestId", request.path("requestId").asText(), "outcome", "UNAVAILABLE", "failure", "TIMEOUT")) : normal(operation, request);
        pollBank(id); var unknown = cashierView(id); assertThat(unknown.at("/operation/status").asText()).isEqualTo("UNKNOWN");
        String queryKey = UUID.randomUUID().toString(); var query = bankAction(unknown, "QUERY"); ok(send(cashierPath(id) + "/actions", "cashier", queryKey, query), 202);
        responder = (operation, request) -> operation.equals("supplier-payment-query") ? json.write(Map.of("contractVersion", 1, "tenantId", "demo", "requestId", request.path("requestId").asText(), "outcome", "SUCCESS", "data", bankObservation(command, PaymentObservation.Status.NOT_FOUND))) : normal(operation, request);
        pollBank(id); var absent = cashierView(id); assertThat(absent.at("/operation/status").asText()).isEqualTo("NOT_FOUND");
        String retryKey = UUID.randomUUID().toString(); var retry = bankAction(absent, "RESEND_ORIGINAL"); ok(send(cashierPath(id) + "/actions", "cashier", retryKey, retry), 202);
        jdbc.update("UPDATE organization_appointment SET active=FALSE WHERE tenant_id='demo' AND id=?", cashierAppointment.toString());
        try {
            okError(send(cashierPath(id) + "/actions", "cashier", executeKey, execute), 404, "NOT_FOUND");
            okError(send(cashierPath(id) + "/actions", "cashier", queryKey, query), 404, "NOT_FOUND");
            okError(send(cashierPath(id) + "/actions", "cashier", retryKey, retry), 404, "NOT_FOUND");
        } finally { jdbc.update("UPDATE organization_appointment SET active=TRUE WHERE tenant_id='demo' AND id=?", cashierAppointment.toString()); }
        assertThat(send(cashierPath(id) + "/actions", "cashier", executeKey, execute).getContentAsString()).isEqualTo(first.getContentAsString());
        responder = this::normal; pollBank(id); var paid = bankPayments.find("demo", id).orElseThrow(); assertThat(paid.settleable()).isTrue(); assertThat(paid.command()).isEqualTo(command); assertThat(paid.dispatches()).isEqualTo(2);
        assertThat(calls.get("supplier-payment-command").get()).isEqualTo(2); assertThat(calls.get("supplier-payment-query").get()).isEqualTo(1);
    }

    @Test void accountLookupRechecksAppointmentAfterExternalWaitAndRejectsStaleDirectory() throws Exception {
        UUID id = authorizedHold(approved());
        responder = (operation, request) -> {
            var response = normal(operation, request);
            if (operation.equals("debit-accounts")) jdbc.update("UPDATE organization_appointment SET active=FALSE WHERE tenant_id='demo' AND id=?", cashierAppointment.toString());
            return response;
        };
        okError(read(cashierPath(id) + "/accounts", "cashier"), 404, "NOT_FOUND");
        jdbc.update("UPDATE organization_appointment SET active=TRUE WHERE tenant_id='demo' AND id=?", cashierAppointment.toString());
        responder = (operation, request) -> operation.equals("debit-accounts")
                ? json.write(Map.of("contractVersion", 1, "tenantId", "demo", "requestId", request.path("requestId").asText(), "outcome", "SUCCESS", "data", directory(json.read(request.path("data").toString(), PaymentAccountsPort.Request.class), Instant.now().minusSeconds(301)))) : normal(operation, request);
        okError(read(cashierPath(id) + "/accounts", "cashier"), 422, "PAYMENT_ACCOUNT_EVIDENCE_EXPIRED");
        assertThat(cashierRequests.owner("demo", id)).isEmpty(); assertThat(bankPayments.find("demo", id)).isEmpty(); assertThat(calls.keySet()).doesNotContain("supplier-payment-command");
    }

    @Test void cashierAuditFailureRollsBackSelectionAndIdempotencyAndCanRetryOriginalKey() throws Exception {
        UUID request = approved(); UUID id = authorizedHold(request); var input = executeInput(cashierView(id)); String key = UUID.randomUUID().toString();
        jdbc.execute("ALTER TABLE audit_event ADD CONSTRAINT fixture_cashier_audit CHECK(action<>'SUPPLIER_PAYMENT_EXECUTE' OR application_id<>'" + app(request).id() + "')");
        try { assertThatThrownBy(() -> send(cashierPath(id) + "/actions", "cashier", key, input)).hasCauseInstanceOf(org.springframework.dao.DataIntegrityViolationException.class); }
        finally { jdbc.execute("ALTER TABLE audit_event DROP CONSTRAINT fixture_cashier_audit"); }
        assertThat(cashierRequests.owner("demo", id)).isEmpty(); assertThat(bankPayments.find("demo", id)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_idempotency WHERE tenant_id='demo' AND idempotency_key=?", Integer.class, key)).isZero();
        ok(send(cashierPath(id) + "/actions", "cashier", key, input), 202); assertThat(cashierRequests.owner("demo", id)).isPresent();
    }

    @Test void anotherEligibleCashierCanTrackButCannotResendTheOriginalCashiersCommand() throws Exception {
        UUID id = authorizedHold(approved()); var response = ok(send(cashierPath(id) + "/actions", "cashier", executeInput(cashierView(id))), 202);
        pollPreparation(UUID.fromString(response.path("preparationId").asText()));
        var originalAppointment = jdbc.queryForMap("SELECT department_id,position_id FROM organization_appointment WHERE tenant_id='demo' AND id=?", cashierAppointment.toString());
        organization.createAppointment(admin, person("bob", false), UUID.fromString(originalAppointment.get("department_id").toString()), UUID.fromString(originalAppointment.get("position_id").toString()), true);
        actors.set(new Actor("demo", "bob", Set.of("CASHIER")));
        try {
            assertThat(cashierAccess.requireCashier(id).id()).isEqualTo(id);
            assertThatThrownBy(() -> cashierAccess.requireResend(id)).isInstanceOfSatisfying(io.agentflow.common.DomainException.class, error -> assertThat(error.code()).isEqualTo("FORBIDDEN"));
        } finally { actors.clear(); }
        actors.set(new Actor("other", "cashier", Set.of("CASHIER")));
        try { assertThatThrownBy(() -> cashierAccess.requireCashier(id)).isInstanceOfSatisfying(io.agentflow.common.DomainException.class, error -> assertThat(error.code()).isEqualTo("NOT_FOUND")); }
        finally { actors.clear(); }
        assertThat(bankPayments.find("demo", id).orElseThrow().dispatches()).isZero();
    }

    @Test void financeExplicitlyConfirmsPaidBankAndDateThenReadsActualErpAndLocalCompletion() throws Exception {
        UUID payment = paidBank(); var initial = settlementView(payment); assertThat(initial.path("canPrepare").asBoolean()).isTrue();
        assertThat(initial.at("/bank/status").asText()).isEqualTo("SUCCEEDED"); assertThat(initial.path("completion").isNull()).isTrue();
        var input = settlementInput(initial); String key = UUID.randomUUID().toString(); var response = send(settlementPreparePath(payment), "finance", key, input);
        var receipt = ok(response, 202); assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(send(settlementPreparePath(payment), "finance", key, input).getContentAsString()).isEqualTo(response.getContentAsString());
        okError(send(settlementPreparePath(payment), "finance", input), 409, "SUPPLIER_SETTLEMENT_PENDING");
        assertThat(receipt.toString()).doesNotContain("amount", "account", "command", "periodReference");
        assertThat(calls.keySet()).doesNotContain("supplier-payable-settlement-command");
        UUID id = UUID.fromString(receipt.path("preparationId").asText()); pollSettlementPreparation(id);
        var queued = settlementView(payment); assertThat(queued.at("/preparation/status").asText()).isEqualTo("READY"); assertThat(queued.at("/items/0/status").asText()).isEqualTo("QUEUED");
        assertThat(queued.path("canPrepare").asBoolean()).isFalse(); pollSettlement(id);
        var done = settlementView(payment); assertThat(done.at("/items/0/status").asText()).isEqualTo("SETTLED"); assertThat(done.at("/items/0/posting/voucherReference").asText()).isEqualTo("SUPPLIER-VOUCHER-1");
        assertThat(done.at("/completion/settlementId").asText()).isEqualTo(id.toString()); assertThat(done.at("/items/0/actions/retire").asBoolean()).isFalse();
        var applicant = ok(read(settlementPath(payment), "alice"), 200); assertThat(applicant.path("canPrepare").asBoolean()).isFalse(); assertThat(applicant.at("/items/0/actions/query").asBoolean()).isFalse();
        assertCashierPrivateFactsAbsent(done); assertThat(calls.get("supplier-payment-command").get()).isEqualTo(1); assertThat(calls.get("supplier-payable-settlement-command").get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE aggregate_id=? AND action='SUPPLIER_SETTLEMENT_PREPARE'", Integer.class, id.toString())).isEqualTo(1);
    }

    @Test void supplierSettlementPreparationFailureNotifiesOriginalApplicantAndFinance() throws Exception {
        UUID payment = paidBank(); var receipt = ok(send(settlementPreparePath(payment), "finance", settlementInput(settlementView(payment))), 202);
        UUID id = UUID.fromString(receipt.path("preparationId").asText());
        var claimed = settlementPreparation.claim("demo", id, Instant.now());
        settlementPreparation.finish(claimed, new FinanceResult.Rejected<>(FinanceResult.Reason.ACCOUNTING_PERIOD_CLOSED), Instant.now());
        var rows = jdbc.queryForList("SELECT recipient_id,content FROM notification_inbox WHERE tenant_id='demo' AND event_key=?", "supplier-settlement:" + id + ":PREPARATION_BLOCKED");
        assertThat(rows).extracting(row -> row.get("recipient_id")).containsExactlyInAnyOrder("alice", "finance");
        assertThat(json.write(rows)).doesNotContain("70.00", "private-ledger", "accountDigest");
        var target = ok(read(supplierSettlementNoticePath(id, "finance", "PREPARATION_BLOCKED"), "finance"), 200);
        assertThat(target.at("/preparation/status").asText()).isEqualTo("BLOCKED");
        assertThat(target.path("operation").isNull()).isTrue(); assertThat(target.path("completion").isNull()).isTrue();
    }

    @Test void supplierSettlementCompletionNotifiesOnlyAfterActualLocalCompletion() throws Exception {
        UUID payment = paidBank(); UUID id = queueSettlement(payment); pollSettlement(id);
        var rows = jdbc.queryForList("SELECT recipient_id FROM notification_inbox WHERE tenant_id='demo' AND event_key=?", "supplier-settlement:" + id + ":COMPLETED");
        assertThat(rows).extracting(row -> row.get("recipient_id")).containsExactlyInAnyOrder("alice", "finance");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE event_key=?", Long.class, "supplier-settlement:" + id + ":ERP_SETTLED")).isZero();
        for (String user : List.of("alice", "finance")) {
            String path = supplierSettlementNoticePath(id, user, "COMPLETED"); var response = read(path, user); var target = ok(response, 200);
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(target.path("settlementId").asText()).isEqualTo(id.toString()); assertThat(target.path("paymentId").asText()).isEqualTo(payment.toString());
            assertThat(target.at("/completion/operationId").asText()).isEqualTo(id.toString());
            assertThat(target.at("/operation/status").asText()).isEqualTo("SETTLED");
            assertThat(target.toString()).doesNotContain("private-ledger", "accountDigest", "commandDigest", "actions", "amount");
            for (String stranger : List.of("admin", "cashier", "bob")) okError(read(path, stranger), 404, "NOT_FOUND");
            okError(read(path + "?paymentId=" + payment, user), 400, "INVALID_INBOX_QUERY");
        }
        settlementExecution.completeLocal("demo", id, Instant.now());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE event_key=?", Long.class, "supplier-settlement:" + id + ":COMPLETED")).isEqualTo(2);
    }

    @Test void supplierErpNoticeDoesNotInventLocalCompletionDuringBankDispute() throws Exception {
        UUID payment = paidBank(); UUID id = unresolvedErp(payment); queryErp(id, SupplierPayableSettlementObservation.Status.SETTLED, "SUPPLIER-VOUCHER-1", 2);
        queryDispute(payment, "WRONG-RECEIPT", 2);
        ok(send(erpDisputePath(id) + "/resolutions", "finance", erpDisputeInput(erpDisputeView(id))), 202);
        var keys = jdbc.queryForList("SELECT event_key FROM notification_inbox WHERE event_key=?", String.class, "supplier-settlement:" + id + ":ERP_SETTLED");
        assertThat(keys).hasSize(2);
        String path = supplierSettlementNoticePath(id, "finance", "ERP_SETTLED"); var pending = ok(read(path, "finance"), 200);
        assertThat(pending.at("/operation/status").asText()).isEqualTo("SETTLED"); assertThat(pending.path("completion").isNull()).isTrue();
        queryDispute(payment, "RECEIPT-1", 3); ok(send(disputePath(payment) + "/resolutions", "finance", disputeInput(disputeView(payment))), 202);
        settlementExecution.completeLocal("demo", id, Instant.now());
        assertThat(ok(read(path, "finance"), 200).at("/completion/operationId").asText()).isEqualTo(id.toString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE event_key=?", Long.class, "supplier-settlement:" + id + ":COMPLETED")).isEqualTo(2);
        assertThat(calls.get("supplier-payment-command").get()).isEqualTo(1); assertThat(calls.get("supplier-payable-settlement-command").get()).isEqualTo(1);
    }

    @Test void oldSupplierSettlementPreparationMessageNeverBorrowsLaterCompletion() throws Exception {
        UUID payment = paidBank(); var receipt = ok(send(settlementPreparePath(payment), "finance", settlementInput(settlementView(payment))), 202);
        UUID first = UUID.fromString(receipt.path("preparationId").asText()); var claimed = settlementPreparation.claim("demo", first, Instant.now());
        settlementPreparation.finish(claimed, new FinanceResult.Rejected<>(FinanceResult.Reason.ACCOUNTING_PERIOD_CLOSED), Instant.now());
        String path = supplierSettlementNoticePath(first, "alice", "PREPARATION_BLOCKED"); UUID second = queueSettlement(payment); pollSettlement(second);
        var old = ok(read(path, "alice"), 200); assertThat(old.path("settlementId").asText()).isEqualTo(first.toString());
        assertThat(old.at("/preparation/status").asText()).isEqualTo("BLOCKED"); assertThat(old.path("operation").isNull()).isTrue(); assertThat(old.path("completion").isNull()).isTrue();
        assertThat(ok(read(supplierSettlementNoticePath(second, "alice", "COMPLETED"), "alice"), 200).at("/completion/operationId").asText()).isEqualTo(second.toString());
    }

    @Test void supplierSettlementRetryAndExplicitQueryDoNotRepeatNotifications() throws Exception {
        UUID payment = paidBank(); var receipt = ok(send(settlementPreparePath(payment), "finance", settlementInput(settlementView(payment))), 202);
        UUID id = UUID.fromString(receipt.path("preparationId").asText()); var claimed = settlementPreparation.claim("demo", id, Instant.now());
        settlementPreparation.fail(claimed, Instant.now()); var retry = settlementPreparations.find("demo", id).orElseThrow();
        var next = settlementPreparation.claim("demo", id, retry.nextAttemptAt()); settlementPreparation.fail(next, retry.nextAttemptAt());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE event_key=?", Long.class, "supplier-settlement:" + id + ":PREPARATION_RETRY")).isEqualTo(2);
        UUID other = queueSettlement(paidBank()); var running = settlementExecution.claim("demo", other, Instant.now());
        settlementExecution.fail(running, Instant.now());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE event_key=?", Long.class, "supplier-settlement:" + other + ":EXECUTION_RETRY")).isEqualTo(2);
        UUID pending = unresolvedErp(paidBank());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE event_key=?", Long.class, "supplier-settlement:" + pending + ":UNKNOWN")).isZero();
    }

    @Test void supplierSettlementLeaseFailureAndMessageRollbackTogether() throws Exception {
        UUID payment = paidBank(); var receipt = ok(send(settlementPreparePath(payment), "finance", settlementInput(settlementView(payment))), 202);
        UUID id = UUID.fromString(receipt.path("preparationId").asText()); var claimed = settlementPreparation.claim("demo", id, Instant.now());
        String key = "supplier-settlement:" + id + ":PREPARATION_RETRY";
        jdbc.execute("ALTER TABLE notification_inbox ADD CONSTRAINT supplier_settlement_notice_fixture CHECK(event_key<>'" + key + "' OR recipient_id<>'finance')");
        try { assertThatThrownBy(() -> settlementPreparation.claim("demo", id, claimed.leaseUntil())).isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("ALTER TABLE notification_inbox DROP CONSTRAINT supplier_settlement_notice_fixture"); }
        assertThat(settlementPreparations.find("demo", id)).contains(claimed);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE event_key=?", Long.class, key)).isZero();
        assertThat(settlementPreparation.claim("demo", id, claimed.leaseUntil())).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE event_key=?", Long.class, key)).isEqualTo(2);
    }

    @Test void supplierRejectedResultAndActualRetirementAreSeparateNotices() throws Exception {
        UUID payment = paidBank(); UUID id = unresolvedErp(payment); queryErp(id, SupplierPayableSettlementObservation.Status.REJECTED, null, 2);
        ok(send(erpDisputePath(id) + "/resolutions", "finance", erpDisputeInput(erpDisputeView(id))), 202);
        String path = supplierSettlementNoticePath(id, "finance", "REJECTED"); assertThat(ok(read(path, "finance"), 200).path("retirement").isNull()).isTrue();
        var state = settlementView(payment).at("/items/0"); long version = state.path("version").asLong();
        ok(send(settlementActionPath(id), "finance", settlementAction(state, "RETIRE")), 202);
        assertThat(settlements.find("demo", id).orElseThrow().version()).isEqualTo(version);
        var ended = ok(read(supplierSettlementNoticePath(id, "alice", "RETIRED"), "alice"), 200);
        assertThat(ended.at("/retirement/basis").asText()).isEqualTo("CONFIRMED_REJECTED"); assertThat(ended.path("completion").isNull()).isTrue();
        UUID replacement = queueSettlement(payment); pollSettlement(replacement);
        var original = ok(read(path, "finance"), 200); assertThat(original.path("settlementId").asText()).isEqualTo(id.toString()); assertThat(original.path("completion").isNull()).isTrue();
    }

    @Test void supplierSettlementNoticeRechecksCurrentPersonEntityAndTenantBeforeReadAndSend() throws Exception {
        var recipient = new Actor("demo", "finance", Set.of("FINANCE")); var preference = noticePreferences.get(recipient);
        noticePreferences.revise(recipient, preference.version(), true, false);
        try {
            UUID id = queueSettlement(paidBank()); pollSettlement(id); String path = supplierSettlementNoticePath(id, "finance", "COMPLETED");
            String key = "supplier-settlement:" + id + ":COMPLETED";
            UUID deliveryId = UUID.fromString(jdbc.queryForObject("SELECT d.id FROM notification_dispatch d JOIN notification_inbox n ON n.id=d.inbox_id WHERE n.event_key=? AND n.recipient_id='finance'", String.class, key));
            var delivery = noticeDeliveries.find(deliveryId).orElseThrow(); assertThat(supplierSettlementNoticeAccess.deliveryAllowed(delivery)).isTrue();
            actors.set(new Actor("other", "finance", Set.of("FINANCE")));
            try { assertThatThrownBy(() -> supplierSettlementNoticeAccess.target(delivery.inboxId())).isInstanceOfSatisfying(io.agentflow.common.DomainException.class, e -> assertThat(e.code()).isEqualTo("NOT_FOUND")); }
            finally { actors.clear(); }
            jdbc.update("UPDATE organization_person SET approval_eligible=FALSE WHERE tenant_id='demo' AND id=?", finance.toString());
            try { assertThat(read(path, "finance").getStatus()).isIn(403, 404); }
            finally { jdbc.update("UPDATE organization_person SET approval_eligible=TRUE WHERE tenant_id='demo' AND id=?", finance.toString()); }
            jdbc.update("UPDATE organization_appointment SET active=FALSE WHERE tenant_id='demo' AND id=?", financeAppointment.toString());
            assertThat(read(path, "finance").getStatus()).isIn(403, 404); assertThat(supplierSettlementNoticeAccess.deliveryAllowed(delivery)).isFalse();
            assertThat(noticeDeliveryService.claim(deliveryId, Instant.now())).isNull();
            assertThat(noticeDeliveries.find(deliveryId).orElseThrow().progress().status()).isEqualTo(io.agentflow.notification.NotificationDeliveryProgress.Status.SUPPRESSED);
        } finally { var current = noticePreferences.get(recipient); noticePreferences.revise(recipient, current.version(), preference.emailEnabled(), preference.enterpriseImEnabled()); }
    }

    private String supplierSettlementNoticePath(UUID id, String user, String fact) {
        String message = jdbc.queryForObject("SELECT id FROM notification_inbox WHERE tenant_id='demo' AND recipient_id=? AND event_key=?", String.class,
                user, "supplier-settlement:" + id + ":" + fact);
        return "/api/v1/notifications/" + message + "/supplier-settlement-target";
    }

    @Test void settlementPreparationRejectsStaleBankUnknownFactsAndUnauthorizedUsers() throws Exception {
        UUID payment = paidBank(); var displayed = settlementView(payment); var input = settlementInput(displayed);
        for (String user : List.of("alice", "cashier", "admin", "manager", "bob")) assertThat(send(settlementPreparePath(payment), user, input).getStatus()).isBetween(400, 499);
        assertThat(read(settlementPath(payment), "admin").getStatus()).isBetween(400, 499);
        var wrong = new java.util.HashMap<>(input); wrong.put("paymentVersion", displayed.at("/bank/version").asLong() - 1);
        okError(send(settlementPreparePath(payment), "finance", wrong), 409, "CONCURRENCY_CONFLICT");
        wrong.put("paymentVersion", input.get("paymentVersion")); wrong.put("amount", Map.of("value", "1", "currency", "CNY"));
        okError(send(settlementPreparePath(payment), "finance", wrong), 400, "INVALID_REQUEST");
        for (String query : List.of("limit=0", "limit=101", "beforeId=bad", "tenantId=other")) okError(read(settlementPath(payment) + "?" + query, "finance"), 400, "INVALID_SUPPLIER_SETTLEMENT_QUERY");
        assertThat(settlementPreparations.active("demo", payment)).isEmpty();
        actors.set(new Actor("other", "finance", Set.of("FINANCE")));
        try { assertThatThrownBy(() -> settlementAccess.requireFinance(payment)).isInstanceOfSatisfying(io.agentflow.common.DomainException.class, e -> assertThat(e.code()).isEqualTo("NOT_FOUND")); }
        finally { actors.clear(); }
    }

    @Test void settlementWriteReplaysRecheckCurrentAppointmentAndSensitiveRoundPermissions() throws Exception {
        UUID payment = paidBank(); var input = settlementInput(settlementView(payment)); String key = UUID.randomUUID().toString();
        var first = send(settlementPreparePath(payment), "finance", key, input); ok(first, 202);
        jdbc.update("UPDATE organization_appointment SET active=FALSE WHERE tenant_id='demo' AND id=?", financeAppointment.toString());
        try { okError(send(settlementPreparePath(payment), "finance", key, input), 403, "FORBIDDEN"); }
        finally { jdbc.update("UPDATE organization_appointment SET active=TRUE WHERE tenant_id='demo' AND id=?", financeAppointment.toString()); }
        jdbc.update("UPDATE organization_person SET approval_eligible=FALSE WHERE tenant_id='demo' AND id=?", finance.toString());
        try { okError(send(settlementPreparePath(payment), "finance", key, input), 403, "FORBIDDEN"); assertThat(read(settlementPath(payment), "finance").getStatus()).isBetween(400, 499); }
        finally { jdbc.update("UPDATE organization_person SET approval_eligible=TRUE WHERE tenant_id='demo' AND id=?", finance.toString()); }
        assertThat(send(settlementPreparePath(payment), "finance", key, input).getContentAsString()).isEqualTo(first.getContentAsString());
    }

    @Test void settlementAuditFailureRollsBackIntentAndIdempotencyThenOriginalKeyCanRetry() throws Exception {
        UUID payment = paidBank(); var input = settlementInput(settlementView(payment)); String key = UUID.randomUUID().toString();
        var application = authorizations.find("demo", payment).orElseThrow().source().reservation().source().applicationId();
        jdbc.execute("ALTER TABLE audit_event ADD CONSTRAINT fixture_settlement_audit CHECK(action<>'SUPPLIER_SETTLEMENT_PREPARE' OR application_id<>'" + application + "')");
        try { assertThatThrownBy(() -> send(settlementPreparePath(payment), "finance", key, input)).hasCauseInstanceOf(org.springframework.dao.DataIntegrityViolationException.class); }
        finally { jdbc.execute("ALTER TABLE audit_event DROP CONSTRAINT fixture_settlement_audit"); }
        assertThat(settlementPreparations.latest("demo", payment)).isEmpty(); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_idempotency WHERE tenant_id='demo' AND idempotency_key=?", Integer.class, key)).isZero();
        ok(send(settlementPreparePath(payment), "finance", key, input), 202); assertThat(settlementPreparations.active("demo", payment)).isPresent();
    }

    @Test void retiredSettlementRemainsInBoundedHistoryAndCursorCannotCrossOriginalBank() throws Exception {
        UUID payment = paidBank(); UUID first = queueSettlement(payment); var old = settlementView(payment).at("/items/0");
        var input = settlementAction(old, "RETIRE"); String key = UUID.randomUUID().toString(); var response = send(settlementActionPath(first), "finance", key, input); ok(response, 202);
        assertThat(send(settlementActionPath(first), "finance", key, input).getContentAsString()).isEqualTo(response.getContentAsString());
        var retired = settlementView(payment); assertThat(retired.at("/items/0/retirement/basis").asText()).isEqualTo("NEVER_DISPATCHED"); assertThat(retired.path("canPrepare").asBoolean()).isTrue();
        UUID second = queueSettlement(payment); var page = ok(read(settlementPath(payment) + "?limit=1", "finance"), 200);
        assertThat(page.path("items").size()).isEqualTo(1); assertThat(page.at("/items/0/id").asText()).isEqualTo(second.toString()); assertThat(page.path("nextBeforeId").asText()).isEqualTo(second.toString());
        var next = ok(read(settlementPath(payment) + "?limit=1&beforeId=" + second, "finance"), 200); assertThat(next.at("/items/0/id").asText()).isEqualTo(first.toString()); assertThat(next.path("nextBeforeId").isNull()).isTrue();
        UUID another = paidBank(); okError(read(settlementPath(another) + "?beforeId=" + first, "finance"), 400, "INVALID_SUPPLIER_SETTLEMENT_QUERY");
        assertThat(bankPayments.find("demo", payment).orElseThrow().dispatches()).isEqualTo(1);
    }

    @Test void unknownSettlementOnlyAllowsOriginalQueryAndReplayCannotBypassCurrentScope() throws Exception {
        UUID payment = paidBank(); UUID id = queueSettlement(payment); var claimed = settlementExecution.claim("demo", id, Instant.now());
        var result = settlementReader.read(claimed.command().payment(), claimed.command().period().request().accountingDate());
        var sending = settlementExecution.ready(claimed, result, Instant.now()); settlementExecution.fail(sending, Instant.now());
        var unknown = settlementView(payment).at("/items/0"); assertThat(unknown.path("status").asText()).isEqualTo("UNKNOWN");
        assertThat(unknown.at("/actions/retire").asBoolean()).isFalse(); assertThat(unknown.at("/actions/retryOriginal").asBoolean()).isFalse();
        okError(send(settlementActionPath(id), "finance", settlementAction(unknown, "RETIRE")), 409, "SUPPLIER_SETTLEMENT_RETIREMENT_UNSAFE");
        var input = settlementAction(unknown, "QUERY"); String key = UUID.randomUUID().toString(); var receipt = send(settlementActionPath(id), "finance", key, input); ok(receipt, 202);
        jdbc.update("UPDATE organization_appointment SET active=FALSE WHERE tenant_id='demo' AND id=?", financeAppointment.toString());
        try { okError(send(settlementActionPath(id), "finance", key, input), 403, "FORBIDDEN"); }
        finally { jdbc.update("UPDATE organization_appointment SET active=TRUE WHERE tenant_id='demo' AND id=?", financeAppointment.toString()); }
        assertThat(send(settlementActionPath(id), "finance", key, input).getContentAsString()).isEqualTo(receipt.getContentAsString());
        pollSettlement(id); assertThat(settlementView(payment).at("/items/0/status").asText()).isEqualTo("SETTLED");
        assertThat(calls.keySet()).doesNotContain("supplier-payable-settlement-command"); assertThat(calls.get("supplier-payable-settlement-query").get()).isEqualTo(1);
    }

    @Test void signedSupplierCallbackQueriesOriginalBankAndKeepsCompletedErpAndLocalSettlement() throws Exception {
        UUID payment = paidBank(); UUID settlement = queueSettlement(payment); pollSettlement(settlement);
        var before = settlementView(payment); var original = bankPayments.find("demo", payment).orElseThrow();
        int previousQueries = calls.get("supplier-payment-query").get();
        String event = "evt_" + UUID.randomUUID(); String body = json.write(new PaymentCallbackVerifier.Signal(1, "payment.changed", "demo", PaymentCallbackVerifier.Kind.SUPPLIER,
                payment, original.command().digest(), 1));
        ok(mvc.perform(PaymentCallbackTestRequests.request(event, body)).andReturn().getResponse(), 202);
        var callback = callbackRecords.byEvent("demo", event).orElseThrow();
        ok(mvc.perform(PaymentCallbackTestRequests.request(event, body)).andReturn().getResponse(), 202);
        callbacks.process(new JdbcPaymentCallbackRepository.Candidate("demo", callback.id()), Instant.now()); pollBank(payment);
        assertThat(bankPayments.find("demo", payment).orElseThrow().settleable()).isTrue();
        assertThat(settlementView(payment).path("completion")).isEqualTo(before.path("completion"));
        assertThat(settlements.find("demo", settlement).orElseThrow().status()).isEqualTo(SupplierPayableSettlementOperation.Status.SETTLED);
        assertThat(callbackRecords.get("demo", callback.id()).reason()).isEqualTo(PaymentCallback.Reason.ALREADY_OBSERVED);
        assertThat(calls.get("supplier-payment-command").get()).isEqualTo(1); assertThat(calls.get("supplier-payment-query").get()).isEqualTo(previousQueries + 1);
        assertThat(calls.get("supplier-payable-settlement-command").get()).isEqualTo(1);
    }

    @Test void signedSupplierCallbackCannotSwapCommandKindDigestOrInventPaidFacts() throws Exception {
        UUID payment = paidBank(); var original = bankPayments.find("demo", payment).orElseThrow();
        for (var signal : List.of(
                new PaymentCallbackVerifier.Signal(1, "payment.changed", "demo", PaymentCallbackVerifier.Kind.SUPPLIER, payment, "a".repeat(64), 1),
                new PaymentCallbackVerifier.Signal(1, "payment.changed", "demo", PaymentCallbackVerifier.Kind.EMPLOYEE, payment, original.command().digest(), 1))) {
            var response = mvc.perform(PaymentCallbackTestRequests.request("evt_" + UUID.randomUUID(), json.write(signal))).andReturn().getResponse();
            assertThat(response.getStatus()).isIn(404, 409);
        }
        String event = "evt_" + UUID.randomUUID(); String body = json.write(new PaymentCallbackVerifier.Signal(1, "payment.changed", "demo", PaymentCallbackVerifier.Kind.SUPPLIER,
                payment, original.command().digest(), 2));
        var tampered = body.substring(0, body.length() - 1) + ",\"status\":\"SUCCEEDED\"}";
        okError(mvc.perform(PaymentCallbackTestRequests.request(event, tampered)).andReturn().getResponse(), 400, "PAYMENT_CALLBACK_INVALID");
        assertThat(callbackRecords.byEvent("demo", event)).isEmpty(); assertThat(bankPayments.find("demo", payment)).contains(original);
    }

    @Test void financialBankDisputeRequiresExplicitOriginalTerminalDecisionAndNeverResendsPayment() throws Exception {
        UUID payment = paidBank(); var original = bankPayments.find("demo", payment).orElseThrow(); var hold = holds.find("demo", payment).orElseThrow();
        queryDispute(payment, "WRONG-RECEIPT", 2); var invalid = disputeView(payment);
        assertThat(invalid.path("issue").asText()).isEqualTo("DIFFERENT_SETTLEMENT"); assertThat(invalid.path("canResolve").asBoolean()).isFalse();
        okError(send(disputePath(payment) + "/resolutions", "finance", disputeInput(invalid)), 422, "SUPPLIER_PAYMENT_DISPUTE_UNRESOLVABLE");
        queryDispute(payment, "RECEIPT-1", 3); var ready = disputeView(payment); assertThat(ready.path("canResolve").asBoolean()).isTrue();
        assertThat(ready.at("/observed/receiptReference").asText()).isEqualTo("RECEIPT-1"); assertCashierPrivateFactsAbsent(ready);
        String key = UUID.randomUUID().toString(); var input = disputeInput(ready); var first = send(disputePath(payment) + "/resolutions", "finance", key, input);
        var receipt = ok(first, 202); assertThat(first.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(receipt.path("status").asText()).isEqualTo("SUCCEEDED"); assertThat(receipt.path("operationVersion").asLong()).isEqualTo(ready.path("operationVersion").asLong() + 1);
        assertThat(send(disputePath(payment) + "/resolutions", "finance", key, input).getContentAsString()).isEqualTo(first.getContentAsString());
        var done = disputeView(payment); assertThat(done.path("candidate").isNull()).isTrue(); assertThat(done.path("canResolve").asBoolean()).isFalse();
        assertThat(done.at("/latest/id").asText()).isEqualTo(receipt.path("resolutionId").asText());
        assertThat(bankPayments.find("demo", payment).orElseThrow().command()).isEqualTo(original.command()); assertThat(holds.find("demo", payment)).contains(hold);
        assertThat(calls.get("supplier-payment-command").get()).isEqualTo(1); assertThat(calls).doesNotContainKey("supplier-payable-settlement-command");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM supplier_payment_dispute_resolution WHERE tenant_id='demo' AND payment_id=?", Integer.class, payment.toString())).isEqualTo(1);
    }

    @Test void disputeReplayRechecksFinancialAppointmentAndOriginalSensitiveFieldPermission() throws Exception {
        UUID payment = paidBank(); queryDispute(payment, "WRONG-RECEIPT", 2); queryDispute(payment, "RECEIPT-1", 3);
        var displayed = disputeView(payment); var input = disputeInput(displayed); String key = UUID.randomUUID().toString();
        var first = send(disputePath(payment) + "/resolutions", "finance", key, input); ok(first, 202);
        var applicant = ok(read(disputePath(payment), "alice"), 200); assertThat(applicant.path("canQuery").asBoolean()).isFalse(); assertThat(applicant.path("canResolve").asBoolean()).isFalse();
        okError(read(disputePath(payment), "admin"), 403, "FORBIDDEN"); okError(read(disputePath(payment), "bob"), 404, "NOT_FOUND");
        for (String user : List.of("alice", "cashier", "admin")) assertThat(send(disputePath(payment) + "/resolutions", user, input).getStatus()).isBetween(400, 499);
        organization.updateAppointment(admin, financeAppointment, false, 1);
        okError(send(disputePath(payment) + "/resolutions", "finance", key, input), 403, "FORBIDDEN");
        assertThat(disputeView(payment).path("canQuery").asBoolean()).isFalse();
        organization.updateAppointment(admin, financeAppointment, true, 2);
        assertThat(send(disputePath(payment) + "/resolutions", "finance", key, input).getContentAsString()).isEqualTo(first.getContentAsString());
        jdbc.update("UPDATE organization_person SET approval_eligible=FALSE WHERE tenant_id='demo' AND id=?", finance.toString());
        try { okError(send(disputePath(payment) + "/resolutions", "finance", key, input), 403, "FORBIDDEN"); okError(read(disputePath(payment), "finance"), 403, "FORBIDDEN"); }
        finally { jdbc.update("UPDATE organization_person SET approval_eligible=TRUE WHERE tenant_id='demo' AND id=?", finance.toString()); }
    }

    @Test void disputeInputsRejectInjectedBankFactsWrongOutcomeAndStaleDisplayedVersion() throws Exception {
        UUID payment = paidBank(); queryDispute(payment, "WRONG-RECEIPT", 2); queryDispute(payment, "RECEIPT-1", 3); var displayed = disputeView(payment);
        var forged = new java.util.HashMap<>(disputeInput(displayed)); forged.put("receiptReference", "forged");
        okError(send(disputePath(payment) + "/resolutions", "finance", forged), 400, "INVALID_REQUEST");
        forged = new java.util.HashMap<>(disputeInput(displayed)); forged.put("operationVersion", displayed.path("operationVersion").asLong() - 1);
        okError(send(disputePath(payment) + "/resolutions", "finance", forged), 409, "CONCURRENCY_CONFLICT");
        forged = new java.util.HashMap<>(disputeInput(displayed)); forged.put("outcome", "FAILED");
        okError(send(disputePath(payment) + "/resolutions", "finance", forged), 422, "SUPPLIER_PAYMENT_DISPUTE_UNRESOLVABLE");
        forged.put("outcome", "NOT_FOUND"); okError(send(disputePath(payment) + "/resolutions", "finance", forged), 400, "INVALID_REQUEST");
        forged = new java.util.HashMap<>(disputeInput(displayed)); forged.put("evidenceReference", "invalid\nreference");
        assertThat(send(disputePath(payment) + "/resolutions", "finance", forged).getStatus()).isEqualTo(400);
        okError(read(disputePath(payment) + "?tenantId=other", "finance"), 400, "INVALID_SUPPLIER_DISPUTE_QUERY");
        okError(read(disputePath(UUID.randomUUID()), "finance"), 404, "NOT_FOUND");
        assertThat(bankPayments.find("demo", payment).orElseThrow().status()).isEqualTo(SupplierPaymentOperation.Status.RECONCILING);
        assertThat(bankPayments.latestResolution("demo", payment)).isEmpty();
    }

    @Test void disputeAuditFailureRollsBackDecisionRevisionAndIdempotencyReceipt() throws Exception {
        UUID payment = paidBank(); queryDispute(payment, "WRONG-RECEIPT", 2); queryDispute(payment, "RECEIPT-1", 3);
        var before = bankPayments.find("demo", payment).orElseThrow(); var input = disputeInput(disputeView(payment)); String key = UUID.randomUUID().toString();
        jdbc.execute("ALTER TABLE audit_event ADD CONSTRAINT fixture_supplier_dispute_audit CHECK(aggregate_id<>'%s' OR action<>'SUPPLIER_PAYMENT_DISPUTE_RESOLVE')".formatted(payment));
        try { assertThatThrownBy(() -> send(disputePath(payment) + "/resolutions", "finance", key, input)).hasCauseInstanceOf(org.springframework.dao.DataIntegrityViolationException.class); }
        finally { jdbc.execute("ALTER TABLE audit_event DROP CONSTRAINT fixture_supplier_dispute_audit"); }
        assertThat(bankPayments.find("demo", payment)).contains(before); assertThat(bankPayments.latestResolution("demo", payment)).isEmpty();
        assertThat(bankPayments.revision("demo", payment, before.version() + 1)).isEmpty();
        ok(send(disputePath(payment) + "/resolutions", "finance", key, input), 202);
        assertThat(bankPayments.find("demo", payment).orElseThrow().settleable()).isTrue();
    }

    @Test void financialDisputeQueryReplaysNeedCurrentAccessAndAuditFailureDoesNotQueue() throws Exception {
        UUID payment = paidBank(); var original = bankPayments.find("demo", payment).orElseThrow(); String key = UUID.randomUUID().toString();
        var input = Map.of("operationVersion", original.version(), "comment", "核对原银行交易");
        jdbc.execute("ALTER TABLE audit_event ADD CONSTRAINT fixture_supplier_dispute_query CHECK(aggregate_id<>'%s' OR action<>'SUPPLIER_PAYMENT_DISPUTE_QUERY')".formatted(payment));
        try { assertThatThrownBy(() -> send(disputePath(payment) + "/queries", "finance", key, input)).hasCauseInstanceOf(org.springframework.dao.DataIntegrityViolationException.class); }
        finally { jdbc.execute("ALTER TABLE audit_event DROP CONSTRAINT fixture_supplier_dispute_query"); }
        assertThat(bankPayments.find("demo", payment)).contains(original);
        var first = send(disputePath(payment) + "/queries", "finance", key, input); assertThat(ok(first, 202).path("status").asText()).isEqualTo("UNKNOWN");
        pollBank(payment); organization.updateAppointment(admin, financeAppointment, false, 1);
        okError(send(disputePath(payment) + "/queries", "finance", key, input), 403, "FORBIDDEN");
        organization.updateAppointment(admin, financeAppointment, true, 2);
        assertThat(send(disputePath(payment) + "/queries", "finance", key, input).getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(calls.get("supplier-payment-command").get()).isEqualTo(1);
        assertThat(calls.get("supplier-payment-query").get()).isEqualTo(1);
    }

    @Test void disputeResolutionPreservesPreviouslyCompletedErpSettlementAndNeverReposts() throws Exception {
        UUID payment = paidBank(); UUID settlement = queueSettlement(payment); pollSettlement(settlement);
        var before = settlementView(payment); var operation = settlements.find("demo", settlement).orElseThrow();
        queryDispute(payment, "WRONG-RECEIPT", 2); queryDispute(payment, "RECEIPT-1", 3);
        ok(send(disputePath(payment) + "/resolutions", "finance", disputeInput(disputeView(payment))), 202);
        assertThat(settlementView(payment).path("completion")).isEqualTo(before.path("completion"));
        assertThat(settlements.find("demo", settlement)).contains(operation);
        assertThat(calls.get("supplier-payable-settlement-command").get()).isEqualTo(1); assertThat(calls.get("supplier-payment-command").get()).isEqualTo(1);
    }

    @Test void erpDisputeRequiresOriginalPostingAndKeepsPriorCompletionAndCommands() throws Exception {
        UUID payment = paidBank(); UUID id = queueSettlement(payment); pollSettlement(id);
        var original = settlements.find("demo", id).orElseThrow(); var completed = settlementView(payment).path("completion");
        queryErp(id, SupplierPayableSettlementObservation.Status.SETTLED, "DIFFERENT-VOUCHER", 2);
        var invalid = erpDisputeView(id); assertThat(invalid.path("issue").asText()).isEqualTo("DIFFERENT_POSTING");
        okError(send(erpDisputePath(id) + "/resolutions", "finance", erpDisputeInput(invalid)), 422, "SUPPLIER_SETTLEMENT_DISPUTE_UNRESOLVABLE");
        queryErp(id, SupplierPayableSettlementObservation.Status.SETTLED, "SUPPLIER-VOUCHER-1", 3);
        var ready = erpDisputeView(id); assertThat(ready.path("canResolve").asBoolean()).isTrue(); assertCashierPrivateFactsAbsent(ready);
        String key = UUID.randomUUID().toString(); var input = erpDisputeInput(ready); var first = send(erpDisputePath(id) + "/resolutions", "finance", key, input);
        var receipt = ok(first, 202); assertThat(first.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(receipt.path("status").asText()).isEqualTo("SETTLED"); assertThat(receipt.path("settlementVersion").asLong()).isEqualTo(ready.path("settlementVersion").asLong() + 1);
        assertThat(send(erpDisputePath(id) + "/resolutions", "finance", key, input).getContentAsString()).isEqualTo(first.getContentAsString());
        var done = erpDisputeView(id); assertThat(done.path("candidate").isNull()).isTrue(); assertThat(done.at("/latest/id").asText()).isEqualTo(receipt.path("resolutionId").asText());
        assertThat(settlementView(payment).path("completion")).isEqualTo(completed);
        assertThat(settlements.find("demo", id).orElseThrow().command()).isEqualTo(original.command());
        assertThat(calls.get("supplier-payment-command").get()).isEqualTo(1); assertThat(calls.get("supplier-payable-settlement-command").get()).isEqualTo(1);
    }

    @Test void erpDecisionAuditFailureRollsBackDecisionCompletionRevisionAndIdempotency() throws Exception {
        UUID payment = paidBank(); UUID id = unresolvedErp(payment); queryErp(id, SupplierPayableSettlementObservation.Status.SETTLED, "SUPPLIER-VOUCHER-1", 2);
        var before = settlements.find("demo", id).orElseThrow(); var input = erpDisputeInput(erpDisputeView(id)); String key = UUID.randomUUID().toString();
        assertThat(settlementView(payment).path("completion").isNull()).isTrue();
        jdbc.execute("ALTER TABLE audit_event ADD CONSTRAINT fixture_erp_dispute_audit CHECK(aggregate_id<>'%s' OR action<>'SUPPLIER_SETTLEMENT_DISPUTE_RESOLVE')".formatted(id));
        try { assertThatThrownBy(() -> send(erpDisputePath(id) + "/resolutions", "finance", key, input)).hasCauseInstanceOf(org.springframework.dao.DataIntegrityViolationException.class); }
        finally { jdbc.execute("ALTER TABLE audit_event DROP CONSTRAINT fixture_erp_dispute_audit"); }
        assertThat(settlements.find("demo", id)).contains(before); assertThat(settlements.latestResolution("demo", id)).isEmpty();
        assertThat(settlements.revision("demo", id, before.version() + 1)).isEmpty(); assertThat(settlementView(payment).path("completion").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_idempotency WHERE tenant_id='demo' AND idempotency_key=?", Integer.class, key)).isZero();
        ok(send(erpDisputePath(id) + "/resolutions", "finance", key, input), 202);
        assertThat(settlementView(payment).at("/completion/settlementId").asText()).isEqualTo(id.toString());
        assertThat(calls.get("supplier-payable-settlement-command").get()).isEqualTo(1);
    }

    @Test void erpDecisionReplayRechecksIndependentFinanceAppointmentAndSensitiveFields() throws Exception {
        UUID payment = paidBank(); UUID id = unresolvedErp(payment); queryErp(id, SupplierPayableSettlementObservation.Status.SETTLED, "SUPPLIER-VOUCHER-1", 2);
        var input = erpDisputeInput(erpDisputeView(id)); String key = UUID.randomUUID().toString();
        for (String user : List.of("alice", "cashier", "admin", "manager", "bob")) assertThat(send(erpDisputePath(id) + "/resolutions", user, input).getStatus()).isBetween(400, 499);
        assertThat(ok(read(erpDisputePath(id), "alice"), 200).path("canResolve").asBoolean()).isFalse();
        okError(read(erpDisputePath(id), "admin"), 403, "FORBIDDEN"); okError(read(erpDisputePath(id), "bob"), 404, "NOT_FOUND");
        var first = send(erpDisputePath(id) + "/resolutions", "finance", key, input); ok(first, 202);
        organization.updateAppointment(admin, financeAppointment, false, 1);
        okError(send(erpDisputePath(id) + "/resolutions", "finance", key, input), 403, "FORBIDDEN");
        organization.updateAppointment(admin, financeAppointment, true, 2);
        assertThat(send(erpDisputePath(id) + "/resolutions", "finance", key, input).getContentAsString()).isEqualTo(first.getContentAsString());
        jdbc.update("UPDATE organization_person SET approval_eligible=FALSE WHERE tenant_id='demo' AND id=?", finance.toString());
        try { okError(send(erpDisputePath(id) + "/resolutions", "finance", key, input), 403, "FORBIDDEN"); okError(read(erpDisputePath(id), "finance"), 403, "FORBIDDEN"); }
        finally { jdbc.update("UPDATE organization_person SET approval_eligible=TRUE WHERE tenant_id='demo' AND id=?", finance.toString()); }
    }

    @Test void erpDecisionRejectsForgedFactsWrongTerminalStaleRevisionAndForeignScope() throws Exception {
        UUID payment = paidBank(); UUID id = unresolvedErp(payment); queryErp(id, SupplierPayableSettlementObservation.Status.SETTLED, "SUPPLIER-VOUCHER-1", 2);
        var displayed = erpDisputeView(id); var forged = new java.util.HashMap<>(erpDisputeInput(displayed)); forged.put("voucherReference", "FORGED");
        okError(send(erpDisputePath(id) + "/resolutions", "finance", forged), 400, "INVALID_REQUEST");
        forged = new java.util.HashMap<>(erpDisputeInput(displayed)); forged.put("settlementVersion", displayed.path("settlementVersion").asLong() - 1);
        okError(send(erpDisputePath(id) + "/resolutions", "finance", forged), 409, "CONCURRENCY_CONFLICT");
        forged = new java.util.HashMap<>(erpDisputeInput(displayed)); forged.put("outcome", "REJECTED");
        okError(send(erpDisputePath(id) + "/resolutions", "finance", forged), 422, "SUPPLIER_SETTLEMENT_DISPUTE_UNRESOLVABLE");
        forged.put("outcome", "PENDING"); okError(send(erpDisputePath(id) + "/resolutions", "finance", forged), 400, "INVALID_REQUEST");
        forged = new java.util.HashMap<>(erpDisputeInput(displayed)); forged.put("evidenceReference", "INVALID\nREFERENCE");
        assertThat(send(erpDisputePath(id) + "/resolutions", "finance", forged).getStatus()).isEqualTo(400);
        okError(read(erpDisputePath(id) + "?paymentId=" + UUID.randomUUID(), "finance"), 400, "INVALID_SUPPLIER_SETTLEMENT_DISPUTE_QUERY");
        okError(read(erpDisputePath(UUID.randomUUID()), "finance"), 404, "NOT_FOUND");
        actors.set(new Actor("other", "finance", Set.of("FINANCE")));
        try { assertThatThrownBy(() -> settlementAccess.requireSettlement(id)).isInstanceOfSatisfying(io.agentflow.common.DomainException.class, e -> assertThat(e.code()).isEqualTo("NOT_FOUND")); }
        finally { actors.clear(); }
        assertThat(settlements.latestResolution("demo", id)).isEmpty();
    }

    @Test void erpResolutionDoesNotClearIndependentBankDisputeOrCompleteWhileBankIsUnsettled() throws Exception {
        UUID payment = paidBank(); UUID id = unresolvedErp(payment); queryErp(id, SupplierPayableSettlementObservation.Status.SETTLED, "SUPPLIER-VOUCHER-1", 2);
        queryDispute(payment, "WRONG-RECEIPT", 2);
        ok(send(erpDisputePath(id) + "/resolutions", "finance", erpDisputeInput(erpDisputeView(id))), 202);
        assertThat(settlements.find("demo", id).orElseThrow().settled()).isTrue();
        assertThat(bankPayments.find("demo", payment).orElseThrow().status()).isEqualTo(SupplierPaymentOperation.Status.RECONCILING);
        assertThat(settlementView(payment).path("completion").isNull()).isTrue();
        queryDispute(payment, "RECEIPT-1", 3); ok(send(disputePath(payment) + "/resolutions", "finance", disputeInput(disputeView(payment))), 202);
        settlementExecution.completeLocal("demo", id, Instant.now());
        assertThat(settlementView(payment).at("/completion/settlementId").asText()).isEqualTo(id.toString());
        assertThat(calls.get("supplier-payment-command").get()).isEqualTo(1); assertThat(calls.get("supplier-payable-settlement-command").get()).isEqualTo(1);
    }

    @Test void confirmedErpRejectionStillNeedsSeparateRetirementAndPreservesItsDecision() throws Exception {
        UUID payment = paidBank(); UUID id = unresolvedErp(payment); queryErp(id, SupplierPayableSettlementObservation.Status.REJECTED, null, 2);
        var view = erpDisputeView(id); assertThat(view.path("canResolve").asBoolean()).isTrue();
        ok(send(erpDisputePath(id) + "/resolutions", "finance", erpDisputeInput(view)), 202);
        assertThat(settlements.retirement("demo", id)).isEmpty(); assertThat(settlementView(payment).path("completion").isNull()).isTrue();
        var decision = erpDisputeView(id).path("latest"); var state = settlementView(payment).at("/items/0");
        ok(send(settlementActionPath(id), "finance", settlementAction(state, "RETIRE")), 202);
        assertThat(erpDisputeView(id).path("latest")).isEqualTo(decision); assertThat(erpDisputeView(id).path("canResolve").asBoolean()).isFalse();
        assertThat(bankPayments.find("demo", payment).orElseThrow().settleable()).isTrue();
        assertThat(calls.get("supplier-payable-settlement-command").get()).isEqualTo(1);
    }

    private String erpDisputePath(UUID id) { return "/api/v1/supplier-settlements/" + id + "/dispute"; }
    private JsonNode erpDisputeView(UUID id) throws Exception {
        var response = read(erpDisputePath(id), "finance"); assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store"); return ok(response, 200);
    }
    private Map<String, Object> erpDisputeInput(JsonNode view) {
        return Map.of("settlementVersion", view.path("settlementVersion").asLong(), "outcome", view.at("/candidate/outcome").asText(), "evidenceReference", "ERP-STATEMENT-1", "comment", "核对原 ERP 凭证与应付核销余额");
    }
    private UUID unresolvedErp(UUID payment) throws Exception {
        UUID id = queueSettlement(payment); var command = settlements.find("demo", id).orElseThrow().command();
        responder = (operation, request) -> operation.equals("supplier-payable-settlement-command")
                ? erpResponse(request, new SupplierPayableSettlementObservation(id, command.digest(), SupplierPayableSettlementObservation.Status.PENDING, 1L, Instant.now(), null, null)) : normal(operation, request);
        pollSettlement(id); queryErp(id, SupplierPayableSettlementObservation.Status.NOT_FOUND, null, 0); return id;
    }
    private void queryErp(UUID id, SupplierPayableSettlementObservation.Status outcome, String voucher, long revision) throws Exception {
        var current = settlements.find("demo", id).orElseThrow(); var command = current.command();
        responder = (operation, request) -> {
            if (!operation.equals("supplier-payable-settlement-query")) return normal(operation, request);
            var original = settlementObservation(command).posting();
            var posting = outcome != SupplierPayableSettlementObservation.Status.SETTLED ? null : new SupplierPayableSettlementObservation.Posting(original.settlementReference(), original.holdReference(), original.ledgerVersion(),
                    original.settledAmount(), original.settledBefore(), original.settledAfter(), original.bankPaymentReference(), original.bankReceiptReference(), voucher, original.periodReference(), original.accountingDate(), original.settledAt());
            return erpResponse(request, new SupplierPayableSettlementObservation(id, command.digest(), outcome, revision, Instant.now(), posting,
                    outcome == SupplierPayableSettlementObservation.Status.REJECTED ? SupplierPayableSettlementObservation.Rejection.ACCOUNTING_PERIOD_CLOSED : null));
        };
        ok(send(settlementActionPath(id), "finance", Map.of("action", "QUERY", "settlementVersion", current.version(), "comment", "按原核销号查询最新事实")), 202);
        pollSettlement(id); assertThat(settlements.find("demo", id).orElseThrow().status()).isEqualTo(SupplierPayableSettlementOperation.Status.RECONCILING);
    }
    private String erpResponse(JsonNode request, SupplierPayableSettlementObservation observation) {
        return json.write(Map.of("contractVersion", 1, "tenantId", "demo", "requestId", request.path("requestId").asText(), "outcome", "SUCCESS", "data", observation));
    }

    private String disputePath(UUID payment) { return "/api/v1/supplier-payments/" + payment + "/dispute"; }
    private JsonNode disputeView(UUID payment) throws Exception {
        var response = read(disputePath(payment), "finance"); assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store"); return ok(response, 200);
    }
    private Map<String, Object> disputeInput(JsonNode view) {
        return Map.of("operationVersion", view.path("operationVersion").asLong(), "outcome", view.at("/candidate/outcome").asText(), "evidenceReference", "BANK-STATEMENT-1", "comment", "核对原供应商银行交易及回单");
    }
    private void queryDispute(UUID payment, String receipt, long revision) throws Exception {
        var command = bankPayments.find("demo", payment).orElseThrow().command();
        responder = (operation, request) -> operation.equals("supplier-payment-query")
                ? json.write(Map.of("contractVersion", 1, "tenantId", "demo", "requestId", request.path("requestId").asText(), "outcome", "SUCCESS", "data",
                    new PaymentObservation(payment, command.digest(), PaymentObservation.Status.SUCCEEDED, revision, Instant.now(), "BANK-1", command.amount(), command.payee().accountDigest(), command.registeredAt(), receipt, null)))
                : normal(operation, request);
        var current = disputeView(payment);
        ok(send(disputePath(payment) + "/queries", "finance", Map.of("operationVersion", current.path("operationVersion").asLong(), "comment", "读取原银行最新事实")), 202);
        pollBank(payment); assertThat(bankPayments.find("demo", payment).orElseThrow().status()).isEqualTo(SupplierPaymentOperation.Status.RECONCILING);
    }

    @Test void supplierReturnReviewNotifiesWithoutInventingRegisteredFundsOrErpAdjustment() throws Exception {
        UUID payment = paidBank(); queueReturn(payment); var check = returnView(payment).path("latestCheck"); UUID checkId = UUID.fromString(check.path("id").asText());
        var rows = jdbc.queryForList("SELECT recipient_id,content FROM notification_inbox WHERE event_key=?", "supplier-return:" + checkId + ":RETURN_REVIEW");
        assertThat(rows).extracting(row -> row.get("recipient_id")).containsExactlyInAnyOrder("alice", "finance");
        assertThat(json.write(rows)).doesNotContain("20.00", "private-ledger", "accountDigest");
        var target = ok(read(supplierReturnNoticePath(checkId, "finance", "RETURN_REVIEW"), "finance"), 200);
        assertThat(target.path("status").asText()).isEqualTo("CHECKED"); assertThat(target.at("/observation/outcome").asText()).isEqualTo("PARTIALLY_RETURNED");
        assertThat(target.path("registration").isNull()).isTrue(); assertThat(returnRegistrations.history("demo", payment)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE event_key=?", Long.class, "supplier-return:" + checkId + ":RECORDED")).isZero();
    }

    @Test void supplierReturnRegistrationNoticeReadsOnlyItsOriginalDecisionAndRound() throws Exception {
        UUID payment = paidBank(); queueReturn(payment); var input = returnRegistration(returnView(payment)); UUID checkId = UUID.fromString(input.get("checkId").toString());
        String key = UUID.randomUUID().toString(); var response = send(returnPath(payment) + "/registrations", "finance", key, input); var registered = ok(response, 202);
        var rows = jdbc.queryForList("SELECT recipient_id FROM notification_inbox WHERE event_key=?", "supplier-return:" + checkId + ":RECORDED");
        assertThat(rows).extracting(row -> row.get("recipient_id")).containsExactlyInAnyOrder("alice", "finance");
        assertThat(send(returnPath(payment) + "/registrations", "finance", key, input).getContentAsString()).isEqualTo(response.getContentAsString());
        for (String user : List.of("alice", "finance")) {
            String path = supplierReturnNoticePath(checkId, user, "RECORDED"); var read = read(path, user); var target = ok(read, 200);
            assertThat(read.getHeader("Cache-Control")).isEqualTo("no-store"); assertThat(target.path("checkId").asText()).isEqualTo(checkId.toString());
            assertThat(target.at("/registration/id").asText()).isEqualTo(registered.path("registrationId").asText());
            assertThat(target.toString()).doesNotContain("amount", "20.00", "creditAccountReference", "private-ledger", "actions", "reason", "evidenceReference");
            for (String stranger : List.of("admin", "cashier", "bob")) okError(read(path, stranger), 404, "NOT_FOUND");
            okError(read(path + "?paymentId=" + payment, user), 400, "INVALID_INBOX_QUERY");
        }
        returnRevision = 2; registerFunds(payment);
        var old = ok(read(supplierReturnNoticePath(checkId, "alice", "RETURN_REVIEW"), "alice"), 200);
        assertThat(old.path("fact").asText()).isEqualTo("RETURN_REVIEW"); assertThat(old.path("status").asText()).isEqualTo("RESOLVED");
        assertThat(old.at("/registration/returnVersion")).isEqualTo(registered.path("returnVersion")); assertThat(old.at("/observation/revision").asLong()).isEqualTo(1); assertThat(old.at("/registration/id").asText()).isEqualTo(registered.path("registrationId").asText());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE event_key=?", Long.class, "supplier-return:" + checkId + ":RECORDED")).isEqualTo(2);
    }

    @Test void supplierReturnFailureMessageKeepsOriginalQueryAfterAnotherCheckSucceeds() throws Exception {
        UUID payment = paidBank(); var queued = ok(send(returnPath(payment) + "/checks", "finance", returnQuery(returnView(payment))), 202);
        UUID checkId = UUID.fromString(queued.path("checkId").asText()); var claim = returnService.claim("demo", checkId, Instant.now()); returnService.fail(claim, Instant.now());
        assertThat(jdbc.queryForList("SELECT recipient_id FROM notification_inbox WHERE event_key=?", String.class, "supplier-return:" + checkId + ":UNAVAILABLE")).containsExactlyInAnyOrder("alice", "finance");
        registerFunds(payment);
        var old = ok(read(supplierReturnNoticePath(checkId, "finance", "UNAVAILABLE"), "finance"), 200);
        assertThat(old.path("status").asText()).isEqualTo("UNAVAILABLE"); assertThat(old.path("issue").asText()).isEqualTo("INTERNAL_ERROR");
        assertThat(old.path("observation").isNull()).isTrue(); assertThat(old.path("registration").isNull()).isTrue();
    }

    @Test void supplierReturnRegistrationAndItsMessageRollbackFundsCheckAuditAndReplayTogether() throws Exception {
        UUID payment = paidBank(); queueReturn(payment); var displayed = returnView(payment); var input = returnRegistration(displayed);
        UUID checkId = UUID.fromString(input.get("checkId").toString()); var original = returnChecks.find("demo", checkId).orElseThrow(); String key = UUID.randomUUID().toString();
        String eventKey = "supplier-return:" + checkId + ":RECORDED";
        jdbc.execute("ALTER TABLE notification_inbox ADD CONSTRAINT supplier_return_notice_fixture CHECK(event_key<>'" + eventKey + "' OR recipient_id<>'finance')");
        try { assertThatThrownBy(() -> send(returnPath(payment) + "/registrations", "finance", key, input)).hasCauseInstanceOf(org.springframework.dao.DataIntegrityViolationException.class); }
        finally { jdbc.execute("ALTER TABLE notification_inbox DROP CONSTRAINT supplier_return_notice_fixture"); }
        assertThat(returnChecks.find("demo", checkId)).contains(original); assertThat(returnRegistrations.history("demo", payment)).isEmpty();
        assertThat(returnView(payment).path("returnVersion")).isEqualTo(displayed.path("returnVersion"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE event_key=?", Long.class, eventKey)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_idempotency WHERE tenant_id='demo' AND idempotency_key=?", Integer.class, key)).isZero();
        var result = ok(send(returnPath(payment) + "/registrations", "finance", key, input), 202);
        UUID recordId = UUID.fromString(result.path("registrationId").asText());
        assertThat(returnRegistrations.find("other", recordId)).isEmpty(); assertThat(returnRegistrations.find("demo", UUID.randomUUID())).isEmpty();
        assertThat(returnRegistrations.find("demo", recordId).orElseThrow().returnVersion()).isEqualTo(result.path("returnVersion").asLong());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finance_receipt_credit WHERE tenant_id='demo' AND supplier_registration_id=?", Integer.class, recordId.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE event_key=?", Long.class, eventKey)).isEqualTo(2);
    }

    @Test void supplierReturnExpiryAndSourceChangesNotifyOnceAndExcludeInactiveFinance() throws Exception {
        UUID payment = paidBank(); var queued = ok(send(returnPath(payment) + "/checks", "finance", returnQuery(returnView(payment))), 202);
        UUID id = UUID.fromString(queued.path("checkId").asText()); var claimed = returnService.claim("demo", id, Instant.now());
        assertThat(returnService.claim("demo", id, claimed.leaseUntil())).isNull();
        var failed = ok(read(supplierReturnNoticePath(id, "finance", "UNAVAILABLE"), "finance"), 200); assertThat(failed.path("issue").asText()).isEqualTo("TIMEOUT");
        returnService.claim("demo", id, claimed.leaseUntil());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE event_key=?", Long.class, "supplier-return:" + id + ":UNAVAILABLE")).isEqualTo(2);
        var next = ok(send(returnPath(payment) + "/checks", "finance", returnQuery(returnView(payment))), 202); UUID nextId = UUID.fromString(next.path("checkId").asText());
        jdbc.update("UPDATE organization_appointment SET active=FALSE WHERE tenant_id='demo' AND id=?", financeAppointment.toString());
        assertThat(returnService.claim("demo", nextId, Instant.now())).isNull();
        assertThat(jdbc.queryForList("SELECT recipient_id FROM notification_inbox WHERE event_key=?", String.class, "supplier-return:" + nextId + ":SOURCE_CHANGED")).containsExactly("alice");
        var stopped = ok(read(supplierReturnNoticePath(nextId, "alice", "SOURCE_CHANGED"), "alice"), 200); assertThat(stopped.path("status").asText()).isEqualTo("VOIDED");
    }

    @Test void supplierUnresolvedReturnAndConfirmedNoReturnKeepDifferentNotificationFacts() throws Exception {
        UUID payment = paidBank(); var first = ok(send(returnPath(payment) + "/checks", "finance", returnQuery(returnView(payment))), 202);
        UUID firstId = UUID.fromString(first.path("checkId").asText()); var claimed = returnService.claim("demo", firstId, Instant.now()); var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        returnService.finish(claimed, new FinanceResult.Success<>(new SupplierPaymentReturnPort.Receipt(claimed.input().request(), SupplierPaymentReturnPort.Status.UNRESOLVED, 1, now, now.plusSeconds(120), null, List.of())), now);
        var unresolved = ok(read(supplierReturnNoticePath(firstId, "finance", "UNRESOLVED"), "finance"), 200); assertThat(unresolved.path("registration").isNull()).isTrue();
        var next = ok(send(returnPath(payment) + "/checks", "finance", returnQuery(returnView(payment))), 202); UUID nextId = UUID.fromString(next.path("checkId").asText());
        var active = returnService.claim("demo", nextId, Instant.now()); var returned = returned(active.input().request());
        returnService.finish(active, new FinanceResult.Success<>(new SupplierPaymentReturnPort.Receipt(active.input().request(), SupplierPaymentReturnPort.Status.CONFIRMED, 2, returned.observedAt(), returned.validUntil(), returned.current(), List.of())), Instant.now());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE event_key LIKE ?", Long.class, "supplier-return:" + nextId + ":%" )).isZero();
        var registered = ok(send(returnPath(payment) + "/registrations", "finance", returnRegistration(returnView(payment))), 202);
        var target = ok(read(supplierReturnNoticePath(nextId, "alice", "RECORDED"), "alice"), 200);
        assertThat(target.at("/registration/outcome").asText()).isEqualTo("CONFIRMED"); assertThat(target.at("/observation/outcome").asText()).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finance_receipt_credit WHERE supplier_registration_id=?", Integer.class, registered.path("registrationId").asText())).isZero();
        assertThat(ok(read(supplierReturnNoticePath(firstId, "alice", "UNRESOLVED"), "alice"), 200).path("registration").isNull()).isTrue();
    }

    @Test void supplierReturnMessageRechecksCurrentFieldsEntityTenantAndDeliveryEligibility() throws Exception {
        var recipient = new Actor("demo", "finance", Set.of("FINANCE")); var preference = noticePreferences.get(recipient); noticePreferences.revise(recipient, preference.version(), true, false);
        try {
            UUID payment = paidBank(); registerFunds(payment); var checkId = UUID.fromString(returnView(payment).at("/latestCheck/id").asText());
            String path = supplierReturnNoticePath(checkId, "finance", "RECORDED"); String eventKey = "supplier-return:" + checkId + ":RECORDED";
            UUID deliveryId = UUID.fromString(jdbc.queryForObject("SELECT d.id FROM notification_dispatch d JOIN notification_inbox n ON n.id=d.inbox_id WHERE n.event_key=? AND n.recipient_id='finance'", String.class, eventKey));
            var delivery = noticeDeliveries.find(deliveryId).orElseThrow(); assertThat(supplierReturnNoticeAccess.deliveryAllowed(delivery)).isTrue();
            actors.set(new Actor("other", "finance", Set.of("FINANCE")));
            try { assertThatThrownBy(() -> supplierReturnNoticeAccess.target(delivery.inboxId())).isInstanceOfSatisfying(io.agentflow.common.DomainException.class, e -> assertThat(e.code()).isEqualTo("NOT_FOUND")); }
            finally { actors.clear(); }
            jdbc.update("UPDATE organization_person SET approval_eligible=FALSE WHERE tenant_id='demo' AND id=?", finance.toString());
            try { assertThat(read(path, "finance").getStatus()).isIn(403, 404); }
            finally { jdbc.update("UPDATE organization_person SET approval_eligible=TRUE WHERE tenant_id='demo' AND id=?", finance.toString()); }
            jdbc.update("UPDATE organization_appointment SET active=FALSE WHERE tenant_id='demo' AND id=?", financeAppointment.toString());
            assertThat(read(path, "finance").getStatus()).isIn(403, 404); assertThat(supplierReturnNoticeAccess.deliveryAllowed(delivery)).isFalse();
            assertThat(noticeDeliveryService.claim(deliveryId, Instant.now())).isNull();
            assertThat(noticeDeliveries.find(deliveryId).orElseThrow().progress().status()).isEqualTo(io.agentflow.notification.NotificationDeliveryProgress.Status.SUPPRESSED);
        } finally { var current = noticePreferences.get(recipient); noticePreferences.revise(recipient, current.version(), preference.emailEnabled(), preference.enterpriseImEnabled()); }
    }

    @Test void fullSupplierReturnNoticeKeepsFirstPaidSourceAndActualFullReturnDecision() throws Exception {
        UUID payment = paidBank(); var paid = bankPayments.find("demo", payment).orElseThrow();
        ok(send(cashierPath(payment) + "/actions", "cashier", bankAction(cashierView(payment), "QUERY")), 202);
        var bankClaim = bankService.claim("demo", payment, Instant.now()); var bankAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var reversed = new PaymentObservation(payment, paid.command().digest(), PaymentObservation.Status.REVERSED, 2L, bankAt,
                paid.observation().paymentReference(), paid.command().amount(), paid.command().payee().accountDigest(), bankAt, "FULL-RETURN-" + payment, null);
        bankService.finish(bankClaim, new FinanceResult.Success<>(reversed), bankAt);
        var queued = ok(send(returnPath(payment) + "/checks", "finance", returnQuery(returnView(payment))), 202); UUID checkId = UUID.fromString(queued.path("checkId").asText());
        var claim = returnService.claim("demo", checkId, Instant.now()); var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var funding = new SupplierPaymentReturnPort.BankReceipt("FULL-FUND-" + payment, paid.command().debitAccount().reference(), paid.command().amount(), bankAt);
        returnService.finish(claim, new FinanceResult.Success<>(new SupplierPaymentReturnPort.Receipt(claim.input().request(), SupplierPaymentReturnPort.Status.RETURNED, 1, now, now.plusSeconds(120), reversed, List.of(funding))), now);
        var review = ok(read(supplierReturnNoticePath(checkId, "alice", "RETURN_REVIEW"), "alice"), 200); assertThat(review.path("registration").isNull()).isTrue();
        ok(send(returnPath(payment) + "/registrations", "finance", returnRegistration(returnView(payment))), 202);
        var target = ok(read(supplierReturnNoticePath(checkId, "alice", "RECORDED"), "alice"), 200);
        assertThat(target.at("/registration/outcome").asText()).isEqualTo("RETURNED");
        assertThat(returnChecks.find("demo", checkId).orElseThrow().input().request().original()).isEqualTo(paid.observation());
        assertThat(bankPayments.find("demo", payment).orElseThrow().observation()).isEqualTo(reversed);
        assertThat(returnView(payment).at("/netPaid/value").asText()).isEqualTo("0.00");
    }

    private String supplierReturnNoticePath(UUID checkId, String user, String fact) {
        String message = jdbc.queryForObject("SELECT id FROM notification_inbox WHERE tenant_id='demo' AND recipient_id=? AND event_key=?", String.class, user, "supplier-return:" + checkId + ":" + fact);
        return "/api/v1/notifications/" + message + "/supplier-return-target";
    }

    @Test void returnRegistrationRequiresExplicitDecisionAndPreservesCompletedOriginalAccounts() throws Exception {
        UUID payment = paidBank(); UUID settlement = queueSettlement(payment); pollSettlement(settlement);
        var bank = bankPayments.find("demo", payment).orElseThrow(); var erp = settlements.find("demo", settlement).orElseThrow();
        var reservation = reservations.find("demo", bank.command().holdCommand().authorization().source().reservation().id()).orElseThrow();
        var initial = returnView(payment); assertThat(initial.path("original").isObject()).isTrue(); assertThat(initial.path("returnVersion").asLong()).isZero();
        var input = returnQuery(initial); String key = UUID.randomUUID().toString();
        var queued = send(returnPath(payment) + "/checks", "finance", key, input); var receipt = ok(queued, 202);
        assertThat(queued.getHeader("Cache-Control")).isEqualTo("no-store"); assertThat(send(returnPath(payment) + "/checks", "finance", key, input).getContentAsString()).isEqualTo(queued.getContentAsString());
        assertThat(returnView(payment).path("canQuery").asBoolean()).isFalse(); assertThat(calls).doesNotContainKey("supplier-payment-return");
        pollReturn(UUID.fromString(receipt.path("checkId").asText())); var checked = returnView(payment);
        assertThat(checked.at("/latestCheck/canRegister").asBoolean()).isTrue(); assertThat(checked.at("/totalReturned/value").asText()).isEqualTo("0.00");
        assertThat(checked.at("/latestCheck/evidence/newReturned/value").asText()).isEqualTo("20.00"); assertThat(returnRegistrations.history("demo", payment)).isEmpty();
        String registerKey = UUID.randomUUID().toString(); var registration = returnRegistration(checked);
        var registered = send(returnPath(payment) + "/registrations", "finance", registerKey, registration); ok(registered, 202);
        assertThat(registered.getHeader("Cache-Control")).isEqualTo("no-store"); assertThat(send(returnPath(payment) + "/registrations", "finance", registerKey, registration).getContentAsString()).isEqualTo(registered.getContentAsString());
        var result = returnView(payment); assertThat(result.at("/totalReturned/value").asText()).isEqualTo("20.00"); assertThat(result.at("/netPaid/value").asText()).isEqualTo("50.00");
        assertThat(result.path("reviewRequired").asBoolean()).isTrue(); assertThat(result.path("registrations").size()).isEqualTo(1); assertThat(result.at("/latestCheck/status").asText()).isEqualTo("RESOLVED");
        assertThat(bankPayments.find("demo", payment).orElseThrow()).isEqualTo(bank); assertThat(settlements.find("demo", settlement).orElseThrow()).isEqualTo(erp);
        assertThat(reservations.find("demo", reservation.id()).orElseThrow()).isEqualTo(reservation);
        assertThat(calls.get("supplier-payment-command").get()).isEqualTo(1); assertThat(calls.get("supplier-payable-settlement-command").get()).isEqualTo(1); assertThat(calls.get("supplier-payment-return").get()).isEqualTo(1);
        var applicant = ok(read(returnPath(payment), "alice"), 200); assertThat(applicant.path("latestCheck").isNull()).isTrue(); assertThat(applicant.path("canQuery").asBoolean()).isFalse();
        assertCashierPrivateFactsAbsent(result); assertThat(result.toString()).doesNotContain("creditAccountReference", "targetDigest", "requestedBy");
    }

    @Test void returnQueryAndRegistrationReplaysRecheckCurrentAppointmentAndFieldPermissions() throws Exception {
        UUID payment = paidBank(); var query = returnQuery(returnView(payment)); String queryKey = UUID.randomUUID().toString();
        var queued = send(returnPath(payment) + "/checks", "finance", queryKey, query); var check = ok(queued, 202);
        pollReturn(UUID.fromString(check.path("checkId").asText())); var input = returnRegistration(returnView(payment)); String key = UUID.randomUUID().toString();
        var registered = send(returnPath(payment) + "/registrations", "finance", key, input); ok(registered, 202);
        jdbc.update("UPDATE organization_appointment SET active=FALSE WHERE tenant_id='demo' AND id=?", financeAppointment.toString());
        try {
            okError(send(returnPath(payment) + "/checks", "finance", queryKey, query), 403, "FORBIDDEN");
            okError(send(returnPath(payment) + "/registrations", "finance", key, input), 403, "FORBIDDEN");
            assertThat(returnView(payment).path("latestCheck").isNull()).isTrue();
        } finally { jdbc.update("UPDATE organization_appointment SET active=TRUE WHERE tenant_id='demo' AND id=?", financeAppointment.toString()); }
        jdbc.update("UPDATE organization_person SET approval_eligible=FALSE WHERE tenant_id='demo' AND id=?", finance.toString());
        try {
            okError(send(returnPath(payment) + "/registrations", "finance", key, input), 403, "FORBIDDEN"); assertThat(read(returnPath(payment), "finance").getStatus()).isBetween(400, 499);
        } finally { jdbc.update("UPDATE organization_person SET approval_eligible=TRUE WHERE tenant_id='demo' AND id=?", finance.toString()); }
        assertThat(send(returnPath(payment) + "/registrations", "finance", key, input).getContentAsString()).isEqualTo(registered.getContentAsString());
        for (String user : List.of("alice", "cashier", "admin")) {
            assertThat(send(returnPath(payment) + "/checks", user, query).getStatus()).isBetween(400, 499);
            assertThat(send(returnPath(payment) + "/registrations", user, input).getStatus()).isBetween(400, 499);
        }
        assertThat(read(returnPath(payment), "admin").getStatus()).isBetween(400, 499);
    }

    @Test void returnHistoryIsBoundedAndCumulativeEvidenceKeepsFirstReceiptOwnership() throws Exception {
        UUID payment = paidBank(); queueReturn(payment); var first = ok(send(returnPath(payment) + "/registrations", "finance", returnRegistration(returnView(payment))), 202);
        returnRevision = 2; queueReturn(payment); var second = returnView(payment);
        assertThat(second.at("/latestCheck/evidence/newReturned/value").asText()).isEqualTo("10.00");
        ok(send(returnPath(payment) + "/registrations", "finance", returnRegistration(second)), 202);
        var page = ok(read(returnPath(payment) + "?limit=1", "finance"), 200); assertThat(page.path("registrations").size()).isEqualTo(1);
        assertThat(page.at("/totalReturned/value").asText()).isEqualTo("30.00"); assertThat(page.path("returns").size()).isEqualTo(2);
        assertThat(page.at("/returns/0/registrationId").asText()).isEqualTo(first.path("registrationId").asText());
        var next = ok(read(returnPath(payment) + "?limit=1&beforeVersion=" + page.path("nextBeforeVersion").asLong(), "finance"), 200);
        assertThat(next.at("/registrations/0/id").asText()).isEqualTo(first.path("registrationId").asText()); assertThat(next.path("nextBeforeVersion").isNull()).isTrue();
    }

    @Test void returnEndpointRejectsUnknownInputsStaleViewsAndAnotherPaymentsEvidence() throws Exception {
        UUID payment = paidBank(); var oldQuery = returnQuery(returnView(payment)); queueReturn(payment); var current = returnView(payment);
        okError(send(returnPath(payment) + "/checks", "finance", oldQuery), 409, "CONCURRENCY_CONFLICT");
        for (String parameters : List.of("limit=0", "limit=51", "limit=x", "beforeVersion=-1", "beforeVersion=9999999999999999999", "amount=10"))
            okError(read(returnPath(payment) + "?" + parameters, "finance"), 400, "INVALID_SUPPLIER_PAYMENT_RETURN_QUERY");
        var input = new java.util.HashMap<String, Object>(returnRegistration(current)); input.put("amount", "1");
        assertThat(send(returnPath(payment) + "/registrations", "finance", input).getStatus()).isEqualTo(400); input.remove("amount");
        input.put("outcome", "UNRESOLVED"); assertThat(send(returnPath(payment) + "/registrations", "finance", input).getStatus()).isEqualTo(400);
        input.put("outcome", "CONFIRMED"); okError(send(returnPath(payment) + "/registrations", "finance", input), 409, "SUPPLIER_PAYMENT_RETURN_OUTCOME_CHANGED");
        okError(send(returnPath(payment) + "/checks?limit=1", "finance", returnQuery(current)), 400, "INVALID_SUPPLIER_PAYMENT_RETURN_QUERY");
        UUID another = paidBank(); queueReturn(another); var wrong = new java.util.HashMap<String, Object>(returnRegistration(returnView(another)));
        wrong.put("checkId", current.at("/latestCheck/id").asText()); wrong.put("checkVersion", current.at("/latestCheck/version").asLong());
        okError(send(returnPath(another) + "/registrations", "finance", wrong), 409, "CONCURRENCY_CONFLICT");
        assertThat(returnRegistrations.history("demo", payment)).isEmpty(); assertThat(returnRegistrations.history("demo", another)).isEmpty();
    }

    @Test void returnWorkspaceBeforeFirstSuccessfulPaymentDoesNotInventBankOrMoney() throws Exception {
        UUID payment = authorizedHold(approved()); var view = returnView(payment);
        for (String field : List.of("original", "totalReturned", "netPaid", "latestCheck", "operationVersion", "bankStatus")) assertThat(view.path(field).isNull()).as(field).isTrue();
        assertThat(view.path("canQuery").asBoolean()).isFalse(); assertThat(view.path("returns").isEmpty()).isTrue();
        okError(send(returnPath(payment) + "/checks", "finance", Map.of("operationVersion", 1, "returnVersion", 0, "comment", "没有原成功付款")), 409, "SUPPLIER_PAYMENT_RETURN_SOURCE_CHANGED");
        assertThat(calls).doesNotContainKey("supplier-payment-return");
    }

    @Test void returnAuditFailureRollsBackFundsAndIdempotencyThenOriginalKeyRetries() throws Exception {
        UUID payment = paidBank(); queueReturn(payment); var input = returnRegistration(returnView(payment)); String key = UUID.randomUUID().toString();
        var application = authorizations.find("demo", payment).orElseThrow().source().reservation().source().applicationId();
        jdbc.execute("ALTER TABLE audit_event ADD CONSTRAINT fixture_return_audit CHECK(action<>'SUPPLIER_PAYMENT_RETURN_REGISTER' OR application_id<>'" + application + "')");
        try { assertThatThrownBy(() -> send(returnPath(payment) + "/registrations", "finance", key, input)).hasCauseInstanceOf(org.springframework.dao.DataIntegrityViolationException.class); }
        finally { jdbc.execute("ALTER TABLE audit_event DROP CONSTRAINT fixture_return_audit"); }
        assertThat(returnRegistrations.history("demo", payment)).isEmpty(); assertThat(returnView(payment).at("/latestCheck/canRegister").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_idempotency WHERE tenant_id='demo' AND idempotency_key=?", Integer.class, key)).isZero();
        var result = ok(send(returnPath(payment) + "/registrations", "finance", key, input), 202);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finance_receipt_credit WHERE tenant_id='demo' AND supplier_registration_id=?", Integer.class, result.path("registrationId").asText())).isEqualTo(1);
    }

    @Test void adjustmentWorkspaceRequiresRegisteredReturnsAndDoesNotStartNetworkReads() throws Exception {
        UUID payment = authorizedHold(approved()); var previousCalls = calls.values().stream().mapToInt(AtomicInteger::get).sum();
        var response = read("/api/v1/supplier-payments/" + payment + "/adjustments", "finance"); var view = ok(response, 200);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(view.path("paymentId").asText()).isEqualTo(payment.toString()); assertThat(view.path("returnVersion").asLong()).isZero();
        assertThat(view.path("canPrepare").asBoolean()).isFalse(); assertThat(view.path("items").isEmpty()).isTrue();
        assertThat(view.path("completion").isNull()).isTrue(); assertThat(view.path("bank").isNull()).isTrue(); assertPrivateFactsAbsent(view);
        assertThat(calls.values().stream().mapToInt(AtomicInteger::get).sum()).isEqualTo(previousCalls);
    }

    @Test void supplierAdjustmentErpNoticePrecedesSeparateLocalCompletion() throws Exception {
        UUID payment = paidBank(); registerFunds(payment); UUID id = queueAdjustment(payment);
        var claimed = adjustmentExecution.claim("demo", id, Instant.now());
        var sending = adjustmentExecution.ready(claimed, adjustmentReader.read(claimed.command().source(), claimed.command().period().request().accountingDate()), Instant.now());
        adjustmentExecution.finish(sending, new FinanceResult.Success<>(adjustmentObservation(sending.command())), Instant.now());
        assertThat(supplierAdjustmentNoticeRecipients(id, "ERP_ADJUSTED")).containsExactlyInAnyOrder("alice", "finance");
        assertThat(supplierAdjustmentNoticeRecipients(id, "COMPLETED")).isEmpty();
        String path = supplierAdjustmentNoticePath(id, "alice", "ERP_ADJUSTED");
        var before = ok(read(path, "alice"), 200); assertThat(before.at("/operation/status").asText()).isEqualTo("ADJUSTED");
        assertThat(before.path("completion").isNull()).isTrue(); pollAdjustment(id);
        var done = ok(read(path, "alice"), 200); assertThat(done.at("/completion/adjustmentVersion").asLong()).isEqualTo(before.at("/operation/version").asLong());
        assertThat(supplierAdjustmentNoticeRecipients(id, "COMPLETED")).containsExactlyInAnyOrder("alice", "finance");
        pollAdjustment(id); assertThat(supplierAdjustmentNoticeRecipients(id, "COMPLETED")).hasSize(2);
        assertThat(calls).doesNotContainKey("supplier-payable-adjustment-command");
    }

    @Test void supplierAdjustmentOldPreparationNoticeCannotBorrowLaterCompletion() throws Exception {
        UUID payment = paidBank(); registerFunds(payment);
        UUID first = UUID.fromString(ok(send(adjustmentPreparePath(payment), "finance", adjustmentInput(adjustmentView(payment))), 202).path("preparationId").asText());
        var claimed = adjustmentPreparation.claim("demo", first, Instant.now());
        adjustmentPreparation.finish(claimed, new FinanceResult.Rejected<>(FinanceResult.Reason.ACCOUNTING_PERIOD_CLOSED), Instant.now());
        assertThat(supplierAdjustmentNoticeRecipients(first, "PREPARATION_BLOCKED")).containsExactlyInAnyOrder("alice", "finance");
        String path = supplierAdjustmentNoticePath(first, "finance", "PREPARATION_BLOCKED"); UUID second = queueAdjustment(payment); pollAdjustment(second);
        var old = ok(read(path, "finance"), 200); assertThat(old.path("adjustmentId").asText()).isEqualTo(first.toString());
        assertThat(old.at("/preparation/status").asText()).isEqualTo("BLOCKED"); assertThat(old.path("operation").isNull()).isTrue(); assertThat(old.path("completion").isNull()).isTrue();
        assertThat(supplierAdjustmentNoticeRecipients(second, "COMPLETED")).containsExactlyInAnyOrder("alice", "finance");
    }

    @Test void supplierAdjustmentRejectedResultAndActualRetirementNotifySeparately() throws Exception {
        UUID payment = paidBank(); UUID id = unresolvedAdjustment(payment); queryAdjustmentDispute(id, SupplierPayableAdjustmentObservation.Status.REJECTED, 2);
        ok(send(adjustmentDisputePath(id) + "/resolutions", "finance", adjustmentDisputeInput(adjustmentDisputeView(id))), 202);
        assertThat(supplierAdjustmentNoticeRecipients(id, "REJECTED")).containsExactlyInAnyOrder("alice", "finance");
        String path = supplierAdjustmentNoticePath(id, "finance", "REJECTED"); assertThat(ok(read(path, "finance"), 200).path("retirement").isNull()).isTrue();
        var state = adjustmentView(payment).at("/items/0"); long version = state.path("version").asLong();
        ok(send(adjustmentActionPath(id), "finance", adjustmentAction(state, "RETIRE")), 202);
        assertThat(adjustments.find("demo", id).orElseThrow().version()).isEqualTo(version);
        var ended = ok(read(supplierAdjustmentNoticePath(id, "alice", "RETIRED"), "alice"), 200);
        assertThat(ended.at("/retirement/basis").asText()).isEqualTo("CONFIRMED_REJECTED"); assertThat(ended.path("completion").isNull()).isTrue();
        UUID replacement = queueAdjustment(payment); pollAdjustment(replacement);
        assertThat(ok(read(path, "finance"), 200).path("completion").isNull()).isTrue();
    }

    @Test void supplierAdjustmentCompletionNoticeFailureRollsBackOnlyLocalAccounting() throws Exception {
        UUID payment = paidBank(); registerFunds(payment); UUID id = queueAdjustment(payment);
        var claimed = adjustmentExecution.claim("demo", id, Instant.now());
        var sending = adjustmentExecution.ready(claimed, adjustmentReader.read(claimed.command().source(), claimed.command().period().request().accountingDate()), Instant.now());
        adjustmentExecution.finish(sending, new FinanceResult.Success<>(adjustmentObservation(sending.command())), Instant.now());
        var adjusted = adjustments.find("demo", id).orElseThrow(); var source = adjusted.command().source();
        var reservation = reservations.find("demo", source.returns().request().command().holdCommand().authorization().source().reservation().id()).orElseThrow();
        String originalLedger = jdbc.queryForObject("SELECT state_json FROM supplier_payment_returns WHERE tenant_id='demo' AND payment_id=?", String.class, payment.toString());
        var receipt = ((FinanceResult.Success<SupplierPaymentReturnPort.Receipt>) returnPort.query(source.returns().request())).value();
        String key = "supplier-adjustment:" + id + ":COMPLETED";
        jdbc.execute("ALTER TABLE notification_inbox ADD CONSTRAINT supplier_adjustment_notice_fixture CHECK(event_key<>'" + key + "' OR recipient_id<>'finance')");
        try { assertThatThrownBy(() -> adjustmentCompletion.complete(adjusted, receipt, Instant.now())).isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("ALTER TABLE notification_inbox DROP CONSTRAINT supplier_adjustment_notice_fixture"); }
        assertThat(adjustments.find("demo", id)).contains(adjusted); assertThat(adjustments.active("demo", payment)).contains(adjusted);
        assertThat(reservations.find("demo", reservation.id())).contains(reservation);
        assertThat(jdbc.queryForObject("SELECT state_json FROM supplier_payment_returns WHERE tenant_id='demo' AND payment_id=?", String.class, payment.toString())).isEqualTo(originalLedger);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM supplier_adjustment_completion WHERE tenant_id='demo' AND operation_id=?", Long.class, id.toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finance_receipt_credit WHERE tenant_id='demo' AND supplier_adjustment_id=?", Long.class, id.toString())).isZero();
        assertThat(supplierAdjustmentNoticeRecipients(id, "COMPLETED")).isEmpty(); assertThat(supplierAdjustmentNoticeRecipients(id, "ERP_ADJUSTED")).hasSize(2);
        var proof = adjustmentCompletion.complete(adjusted, receipt, Instant.now());
        assertThat(adjustmentCompletion.complete(adjusted, receipt, Instant.now())).isEqualTo(proof);
        assertThat(supplierAdjustmentNoticeRecipients(id, "COMPLETED")).containsExactlyInAnyOrder("alice", "finance");
    }

    @Test void supplierAdjustmentCompletionMessageKeepsOwnProofAfterMoreFundsAndLaterDispute() throws Exception {
        UUID payment = paidBank(); registerFunds(payment); UUID first = queueAdjustment(payment); pollAdjustment(first);
        String path = supplierAdjustmentNoticePath(first, "finance", "COMPLETED"); var response = read(path, "finance"); var before = ok(response, 200);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store"); var proof = before.path("completion");
        assertThat(proof.path("adjustmentId").asText()).isEqualTo(first.toString()); assertThat(before.toString()).doesNotContain("amount", "commandDigest", "private-ledger", "actions", "accountDigest");
        for (String other : List.of("admin", "bob", "cashier")) okError(read(path, other), 404, "NOT_FOUND");
        okError(read(path + "?adjustmentId=" + UUID.randomUUID(), "finance"), 400, "INVALID_INBOX_QUERY");
        returnRevision = 2; registerFunds(payment); UUID second = queueAdjustment(payment); pollAdjustment(second);
        assertThat(ok(read(path, "finance"), 200).path("completion")).isEqualTo(proof);
        var later = ok(read(supplierAdjustmentNoticePath(second, "finance", "COMPLETED"), "finance"), 200);
        assertThat(later.at("/completion/returnVersion").asLong()).isGreaterThan(proof.path("returnVersion").asLong());
        queryAdjustmentDispute(first, SupplierPayableAdjustmentObservation.Status.REJECTED, 2);
        var disputed = ok(read(path, "finance"), 200); assertThat(disputed.at("/operation/status").asText()).isEqualTo("RECONCILING");
        assertThat(disputed.path("completion")).isEqualTo(proof); assertThat(supplierAdjustmentNoticeRecipients(first, "RECONCILING")).hasSize(2);
        assertThat(supplierAdjustmentNoticeRecipients(first, "COMPLETED")).hasSize(2);
    }

    @Test void supplierAdjustmentPreparationRetryDeduplicatesAndNormalQueriesStayQuiet() throws Exception {
        UUID payment = paidBank(); registerFunds(payment);
        UUID id = UUID.fromString(ok(send(adjustmentPreparePath(payment), "finance", adjustmentInput(adjustmentView(payment))), 202).path("preparationId").asText());
        var claimed = adjustmentPreparation.claim("demo", id, Instant.now()); adjustmentPreparation.claim("demo", id, claimed.leaseUntil());
        var retry = adjustmentPreparations.find("demo", id).orElseThrow(); var second = adjustmentPreparation.claim("demo", id, retry.nextAttemptAt());
        adjustmentPreparation.fail(second, retry.nextAttemptAt()); assertThat(supplierAdjustmentNoticeRecipients(id, "PREPARATION_RETRY")).hasSize(2);
        UUID another = paidBank(); registerFunds(another); UUID operation = queueAdjustment(another);
        var running = adjustmentExecution.claim("demo", operation, Instant.now()); adjustmentExecution.fail(running, Instant.now());
        assertThat(supplierAdjustmentNoticeRecipients(operation, "EXECUTION_RETRY")).hasSize(2);
        UUID pending = unresolvedAdjustment(paidBank()); assertThat(supplierAdjustmentNoticeRecipients(pending, "UNKNOWN")).isEmpty();
    }

    @Test void supplierAdjustmentResolvedErpResultNotifiesBeforeItsBankRecheckCompletion() throws Exception {
        UUID payment = paidBank(); UUID id = unresolvedAdjustment(payment); queryAdjustmentDispute(id, SupplierPayableAdjustmentObservation.Status.ADJUSTED, 2);
        var before = adjustmentDisputeView(id); var input = adjustmentDisputeInput(before); String key = UUID.randomUUID().toString();
        var first = send(adjustmentDisputePath(id) + "/resolutions", "finance", key, input); ok(first, 202);
        assertThat(send(adjustmentDisputePath(id) + "/resolutions", "finance", key, input).getContentAsString()).isEqualTo(first.getContentAsString());
        String path = supplierAdjustmentNoticePath(id, "finance", "ERP_ADJUSTED"); assertThat(ok(read(path, "finance"), 200).path("completion").isNull()).isTrue();
        assertThat(supplierAdjustmentNoticeRecipients(id, "ERP_ADJUSTED")).hasSize(2); assertThat(supplierAdjustmentNoticeRecipients(id, "COMPLETED")).isEmpty();
        pollAdjustment(id); assertThat(ok(read(path, "finance"), 200).at("/completion/adjustmentId").asText()).isEqualTo(id.toString());
        assertThat(calls.get("supplier-payable-adjustment-command").get()).isEqualTo(1);
    }

    @Test void supplierAdjustmentMessageRechecksCurrentRecipientEntityTenantAndDeliveryEligibility() throws Exception {
        var recipient = new Actor("demo", "finance", Set.of("FINANCE")); var preference = noticePreferences.get(recipient); noticePreferences.revise(recipient, preference.version(), true, false);
        try {
            UUID payment = paidBank(); registerFunds(payment); UUID checkId = queueAdjustment(payment); pollAdjustment(checkId);
            String path = supplierAdjustmentNoticePath(checkId, "finance", "COMPLETED"); String eventKey = "supplier-adjustment:" + checkId + ":COMPLETED";
            UUID deliveryId = UUID.fromString(jdbc.queryForObject("SELECT d.id FROM notification_dispatch d JOIN notification_inbox n ON n.id=d.inbox_id WHERE n.event_key=? AND n.recipient_id='finance'", String.class, eventKey));
            var delivery = noticeDeliveries.find(deliveryId).orElseThrow(); assertThat(supplierAdjustmentNoticeAccess.deliveryAllowed(delivery)).isTrue();
            actors.set(new Actor("other", "finance", Set.of("FINANCE")));
            try { assertThatThrownBy(() -> supplierAdjustmentNoticeAccess.target(delivery.inboxId())).isInstanceOfSatisfying(io.agentflow.common.DomainException.class, e -> assertThat(e.code()).isEqualTo("NOT_FOUND")); }
            finally { actors.clear(); }
            jdbc.update("UPDATE organization_person SET approval_eligible=FALSE WHERE tenant_id='demo' AND id=?", finance.toString());
            try { assertThat(read(path, "finance").getStatus()).isIn(403, 404); }
            finally { jdbc.update("UPDATE organization_person SET approval_eligible=TRUE WHERE tenant_id='demo' AND id=?", finance.toString()); }
            jdbc.update("UPDATE organization_appointment SET active=FALSE WHERE tenant_id='demo' AND id=?", financeAppointment.toString());
            assertThat(read(path, "finance").getStatus()).isIn(403, 404); assertThat(supplierAdjustmentNoticeAccess.deliveryAllowed(delivery)).isFalse();
            assertThat(noticeDeliveryService.claim(deliveryId, Instant.now())).isNull();
            assertThat(noticeDeliveries.find(deliveryId).orElseThrow().progress().status()).isEqualTo(io.agentflow.notification.NotificationDeliveryProgress.Status.SUPPRESSED);
        } finally { var current = noticePreferences.get(recipient); noticePreferences.revise(recipient, current.version(), preference.emailEnabled(), preference.enterpriseImEnabled()); }
    }

    @Test void supplierAdjustmentAlreadyCompletedDoesNotAnnouncePendingLocalAccountingToReactivatedFinance() throws Exception {
        UUID payment = paidBank(); registerFunds(payment); UUID id = queueAdjustment(payment);
        var claimed = adjustmentExecution.claim("demo", id, Instant.now());
        var sending = adjustmentExecution.ready(claimed, adjustmentReader.read(claimed.command().source(), claimed.command().period().request().accountingDate()), Instant.now());
        jdbc.update("UPDATE organization_appointment SET active=FALSE WHERE tenant_id='demo' AND id=?", financeAppointment.toString());
        try {
            adjustmentExecution.finish(sending, new FinanceResult.Success<>(adjustmentObservation(sending.command())), Instant.now());
            var current = adjustments.find("demo", id).orElseThrow();
            var receipt = ((FinanceResult.Success<SupplierPaymentReturnPort.Receipt>) returnPort.query(current.command().source().returns().request())).value();
            adjustmentCompletion.complete(current, receipt, Instant.now());
        } finally { jdbc.update("UPDATE organization_appointment SET active=TRUE WHERE tenant_id='demo' AND id=?", financeAppointment.toString()); }
        assertThat(supplierAdjustmentNoticeRecipients(id, "ERP_ADJUSTED")).containsExactly("alice");
        assertThat(supplierAdjustmentNoticeRecipients(id, "COMPLETED")).containsExactly("alice");
        ok(send(adjustmentActionPath(id), "finance", adjustmentAction(adjustmentView(payment).at("/items/0"), "QUERY")), 202); pollAdjustment(id);
        assertThat(supplierAdjustmentNoticeRecipients(id, "ERP_ADJUSTED")).containsExactly("alice");
        assertThat(supplierAdjustmentNoticeRecipients(id, "COMPLETED")).containsExactly("alice");
    }

    private List<String> supplierAdjustmentNoticeRecipients(UUID id, String fact) {
        return jdbc.queryForList("SELECT recipient_id FROM notification_inbox WHERE tenant_id='demo' AND event_key=?", String.class, "supplier-adjustment:" + id + ":" + fact);
    }
    private String supplierAdjustmentNoticePath(UUID id, String recipient, String fact) {
        String message = jdbc.queryForObject("SELECT id FROM notification_inbox WHERE tenant_id='demo' AND event_key=? AND recipient_id=?", String.class, "supplier-adjustment:" + id + ":" + fact, recipient);
        return "/api/v1/notifications/" + message + "/supplier-adjustment-target";
    }

    @Test void adjustmentFinishesOnlyAfterErpAndLocalPostingThenAccountsOnlyLaterNewFunds() throws Exception {
        UUID payment = paidBank(); registerFunds(payment); var bank = bankPayments.find("demo", payment).orElseThrow();
        var before = adjustmentView(payment); assertThat(before.path("canPrepare").asBoolean()).isTrue(); assertThat(before.at("/pendingReturned/value").asText()).isEqualTo("20.00");
        String key = UUID.randomUUID().toString(); var input = adjustmentInput(before); var response = send(adjustmentPreparePath(payment), "finance", key, input); var receipt = ok(response, 202);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store"); assertThat(send(adjustmentPreparePath(payment), "finance", key, input).getContentAsString()).isEqualTo(response.getContentAsString());
        assertThat(receipt.fieldNames()).toIterable().containsExactlyInAnyOrder("paymentId", "requestId", "applicationId", "roundNo", "action", "preparationId", "preparationVersion", "adjustmentId", "adjustmentVersion", "auditEventId");
        assertThat(adjustmentView(payment).path("canPrepare").asBoolean()).isFalse(); assertThat(calls).doesNotContainKey("supplier-payable-adjustment-command");
        UUID first = UUID.fromString(receipt.path("preparationId").asText()); pollAdjustmentPreparation(first);
        var queued = adjustmentView(payment); assertThat(queued.at("/items/0/status").asText()).isEqualTo("QUEUED"); assertThat(queued.path("completion").isNull()).isTrue();
        pollAdjustment(first); var done = adjustmentView(payment);
        assertThat(done.at("/items/0/status").asText()).isEqualTo("ADJUSTED"); assertThat(done.at("/items/0/recognizesOriginalPayment").asBoolean()).isTrue();
        assertThat(done.at("/completion/adjustmentId").asText()).isEqualTo(first.toString()); assertThat(done.path("reviewRequired").asBoolean()).isFalse();
        assertThat(done.at("/accountedReturned/value").asText()).isEqualTo("20.00"); assertThat(done.at("/pendingReturned/value").asText()).isEqualTo("0.00"); assertThat(done.path("activeAdjustmentId").isNull()).isTrue();
        assertThat(done.path("canPrepare").asBoolean()).isFalse(); assertPrivateFactsAbsent(done); assertThat(done.toString()).doesNotContain("creditAccountReference", "targetDigest", "commandDigest", "private-ledger");
        returnRevision = 2; registerFunds(payment); var later = adjustmentView(payment);
        assertThat(later.at("/accountedReturned/value").asText()).isEqualTo("20.00"); assertThat(later.at("/pendingReturned/value").asText()).isEqualTo("10.00");
        assertThat(later.path("completion")).isEqualTo(done.path("completion")); assertThat(later.path("reviewRequired").asBoolean()).isTrue();
        UUID second = queueAdjustment(payment); pollAdjustment(second); var finalView = adjustmentView(payment);
        assertThat(finalView.at("/accountedReturned/value").asText()).isEqualTo("30.00"); assertThat(finalView.at("/pendingReturned/value").asText()).isEqualTo("0.00");
        assertThat(finalView.at("/items/0/returnedAmount/value").asText()).isEqualTo("10.00"); assertThat(finalView.at("/items/0/recognizesOriginalPayment").asBoolean()).isFalse();
        assertThat(finalView.at("/items/1/completion/adjustmentId").asText()).isEqualTo(first.toString());
        assertThat(bankPayments.find("demo", payment)).contains(bank); assertThat(calls.get("supplier-payment-command").get()).isEqualTo(1);
        assertThat(calls.get("supplier-payable-adjustment-command").get()).isEqualTo(2); assertThat(calls).doesNotContainKey("supplier-payable-settlement-command");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finance_receipt_credit c JOIN supplier_payment_return_registration r ON r.tenant_id=c.tenant_id AND r.id=c.supplier_registration_id WHERE c.tenant_id='demo' AND r.payment_id=? AND c.supplier_adjustment_id IS NOT NULL", Integer.class, payment.toString())).isEqualTo(2);
    }

    @Test void adjustmentAfterOriginalSettlementPreservesOriginalCompletionAndVoucher() throws Exception {
        UUID payment = paidBank(); UUID settlement = queueSettlement(payment); pollSettlement(settlement); var old = settlementView(payment);
        var original = settlements.find("demo", settlement).orElseThrow(); registerFunds(payment); UUID id = queueAdjustment(payment); pollAdjustment(id);
        var done = adjustmentView(payment); assertThat(done.at("/items/0/status").asText()).isEqualTo("ADJUSTED");
        assertThat(done.at("/items/0/recognizesOriginalPayment").asBoolean()).isFalse(); assertThat(done.at("/items/0/posting/recognitionVoucherReference").asText()).isEqualTo(original.observation().posting().voucherReference());
        assertThat(settlementView(payment).path("completion")).isEqualTo(old.path("completion")); assertThat(settlements.find("demo", settlement)).contains(original);
        assertThat(done.at("/completion/adjustmentId").asText()).isEqualTo(id.toString());
    }

    @Test void adjustmentPreparationAndReplayAlwaysRecheckIndependentFinanceAndSensitiveFields() throws Exception {
        UUID payment = paidBank(); registerFunds(payment); var input = adjustmentInput(adjustmentView(payment)); String key = UUID.randomUUID().toString();
        var initial = send(adjustmentPreparePath(payment), "finance", key, input); ok(initial, 202);
        assertThat(ok(read(adjustmentPath(payment), "alice"), 200).path("canPrepare").asBoolean()).isFalse();
        okError(read(adjustmentPath(payment), "admin"), 403, "FORBIDDEN"); okError(read(adjustmentPath(payment), "bob"), 404, "NOT_FOUND");
        for (String user : List.of("alice", "cashier", "admin")) assertThat(send(adjustmentPreparePath(payment), user, input).getStatus()).isBetween(400, 499);
        organization.updateAppointment(admin, financeAppointment, false, 1);
        okError(send(adjustmentPreparePath(payment), "finance", key, input), 403, "FORBIDDEN"); organization.updateAppointment(admin, financeAppointment, true, 2);
        assertThat(send(adjustmentPreparePath(payment), "finance", key, input).getContentAsString()).isEqualTo(initial.getContentAsString());
        jdbc.update("UPDATE organization_person SET approval_eligible=FALSE WHERE tenant_id='demo' AND id=?", finance.toString());
        try { okError(read(adjustmentPath(payment), "finance"), 403, "FORBIDDEN"); okError(send(adjustmentPreparePath(payment), "finance", key, input), 403, "FORBIDDEN"); }
        finally { jdbc.update("UPDATE organization_person SET approval_eligible=TRUE WHERE tenant_id='demo' AND id=?", finance.toString()); }
        assertThat(calls).doesNotContainKey("supplier-payable-adjustment-command");
    }

    @Test void adjustmentInputsRejectForgedFactsStaleVersionsAndUnboundedOrForeignHistory() throws Exception {
        UUID payment = paidBank(); registerFunds(payment); var displayed = adjustmentView(payment); var input = adjustmentInput(displayed);
        for (String field : List.of("amount", "financeActor", "source", "targetDigest", "posting")) {
            var forged = new java.util.HashMap<>(input); forged.put(field, "forged"); okError(send(adjustmentPreparePath(payment), "finance", forged), 400, "INVALID_REQUEST");
        }
        for (String field : List.of("paymentVersion", "returnVersion")) {
            var stale = new java.util.HashMap<>(input); stale.put(field, ((Number) input.get(field)).longValue() + 1); okError(send(adjustmentPreparePath(payment), "finance", stale), 409, "SUPPLIER_ADJUSTMENT_SOURCE_CHANGED");
        }
        var invalidDate = new java.util.HashMap<>(input); invalidDate.put("accountingDate", "2000-01-01");
        okError(send(adjustmentPreparePath(payment), "finance", invalidDate), 422, "INVALID_SUPPLIER_PAYABLE_ADJUSTMENT_COMMAND");
        for (String parameters : List.of("limit=0", "limit=101", "limit=01", "limit=-1", "beforeId=x", "tenantId=foreign", "sort=created_at"))
            okError(read(adjustmentPath(payment) + "?" + parameters, "finance"), 400, "INVALID_SUPPLIER_ADJUSTMENT_QUERY");
        okError(send(adjustmentPreparePath(payment) + "?tenantId=foreign", "finance", input), 400, "INVALID_SUPPLIER_ADJUSTMENT_QUERY");
        UUID id = queueAdjustment(payment); UUID another = paidBank();
        okError(read(adjustmentPath(another) + "?beforeId=" + id, "finance"), 400, "INVALID_SUPPLIER_ADJUSTMENT_QUERY");
        okError(send(adjustmentActionPath(id), "finance", Map.of("action", "RETIRE", "adjustmentVersion", 99, "comment", "旧页面")), 409, "CONCURRENCY_CONFLICT");
        okError(send(adjustmentActionPath(id) + "?date=other", "finance", adjustmentAction(adjustmentView(payment).at("/items/0"), "RETIRE")), 400, "INVALID_SUPPLIER_ADJUSTMENT_QUERY");
        assertThat(calls).doesNotContainKey("supplier-payable-adjustment-command");
    }

    @Test void adjustmentAuditFailureRollsBackPreparationAndSafeRetirementIncludingReplayReceipt() throws Exception {
        UUID payment = paidBank(); registerFunds(payment); var view = adjustmentView(payment); var input = adjustmentInput(view); String key = UUID.randomUUID().toString();
        jdbc.execute("ALTER TABLE audit_event ADD CONSTRAINT fixture_adjustment_prepare_audit CHECK(action<>'SUPPLIER_ADJUSTMENT_PREPARE' OR application_id<>'" + view.path("applicationId").asText() + "')");
        try { assertThatThrownBy(() -> send(adjustmentPreparePath(payment), "finance", key, input)).hasCauseInstanceOf(org.springframework.dao.DataIntegrityViolationException.class); }
        finally { jdbc.execute("ALTER TABLE audit_event DROP CONSTRAINT fixture_adjustment_prepare_audit"); }
        assertThat(adjustmentPreparations.latest("demo", payment)).isEmpty(); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_idempotency WHERE tenant_id='demo' AND idempotency_key=?", Integer.class, key)).isZero();
        UUID id = UUID.fromString(ok(send(adjustmentPreparePath(payment), "finance", key, input), 202).path("preparationId").asText()); pollAdjustmentPreparation(id);
        var before = adjustments.find("demo", id).orElseThrow(); var retire = adjustmentAction(adjustmentView(payment).at("/items/0"), "RETIRE"); String retireKey = UUID.randomUUID().toString();
        jdbc.execute("ALTER TABLE audit_event ADD CONSTRAINT fixture_adjustment_retire_audit CHECK(action<>'SUPPLIER_ADJUSTMENT_RETIRE' OR aggregate_id<>'" + id + "')");
        try { assertThatThrownBy(() -> send(adjustmentActionPath(id), "finance", retireKey, retire)).hasCauseInstanceOf(org.springframework.dao.DataIntegrityViolationException.class); }
        finally { jdbc.execute("ALTER TABLE audit_event DROP CONSTRAINT fixture_adjustment_retire_audit"); }
        assertThat(adjustments.find("demo", id)).contains(before); assertThat(adjustments.retirement("demo", id)).isEmpty();
        var retired = send(adjustmentActionPath(id), "finance", retireKey, retire); ok(retired, 202);
        assertThat(send(adjustmentActionPath(id), "finance", retireKey, retire).getContentAsString()).isEqualTo(retired.getContentAsString());
        assertThat(adjustmentView(payment).path("canPrepare").asBoolean()).isTrue();
    }

    @Test void unknownAdjustmentOnlyQueriesOriginalAndCompletedQueryDoesNotReoccupyPayment() throws Exception {
        UUID payment = paidBank(); registerFunds(payment); UUID id = queueAdjustment(payment);
        var claimed = adjustmentExecution.claim("demo", id, Instant.now()); var sending = adjustmentExecution.ready(claimed, adjustmentReader.read(claimed.command().source(), claimed.command().period().request().accountingDate()), Instant.now());
        adjustmentExecution.fail(sending, Instant.now()); var view = adjustmentView(payment); var unknown = view.at("/items/0");
        assertThat(unknown.path("status").asText()).isEqualTo("UNKNOWN"); assertThat(unknown.at("/actions/retire").asBoolean()).isFalse();
        okError(send(adjustmentActionPath(id), "finance", adjustmentAction(unknown, "RETIRE")), 409, "SUPPLIER_ADJUSTMENT_RETIREMENT_UNSAFE");
        okError(send(adjustmentPreparePath(payment), "finance", adjustmentInput(view)), 409, "SUPPLIER_ADJUSTMENT_PENDING");
        var input = adjustmentAction(unknown, "QUERY"); String key = UUID.randomUUID().toString(); var response = send(adjustmentActionPath(id), "finance", key, input); ok(response, 202);
        organization.updateAppointment(admin, financeAppointment, false, 1); okError(send(adjustmentActionPath(id), "finance", key, input), 403, "FORBIDDEN"); organization.updateAppointment(admin, financeAppointment, true, 2);
        assertThat(send(adjustmentActionPath(id), "finance", key, input).getContentAsString()).isEqualTo(response.getContentAsString());
        pollAdjustment(id); var done = adjustmentView(payment); assertThat(done.at("/items/0/status").asText()).isEqualTo("ADJUSTED");
        assertThat(done.at("/items/0/actions/query").asBoolean()).isTrue(); assertThat(done.path("activeAdjustmentId").isNull()).isTrue();
        ok(send(adjustmentActionPath(id), "finance", adjustmentAction(done.at("/items/0"), "QUERY")), 202);
        assertThat(adjustmentView(payment).path("completion")).isEqualTo(done.path("completion")); assertThat(adjustments.active("demo", payment)).isEmpty();
        pollAdjustment(id); assertThat(adjustmentView(payment).at("/items/0/status").asText()).isEqualTo("ADJUSTED");
        assertThat(calls).doesNotContainKey("supplier-payable-adjustment-command"); assertThat(calls.get("supplier-payable-adjustment-query").get()).isEqualTo(2);
    }

    @Test void retiredAdjustmentHistoryIsBoundedAndKeepsTheSameRegisteredFunds() throws Exception {
        UUID payment = paidBank(); registerFunds(payment); UUID first = queueAdjustment(payment); var firstView = adjustmentView(payment);
        ok(send(adjustmentActionPath(first), "finance", adjustmentAction(firstView.at("/items/0"), "RETIRE")), 202); UUID second = queueAdjustment(payment);
        var firstPage = ok(read(adjustmentPath(payment) + "?limit=1", "finance"), 200); assertThat(firstPage.at("/items/0/id").asText()).isEqualTo(second.toString());
        assertThat(firstPage.path("nextBeforeId").asText()).isEqualTo(second.toString()); var secondPage = ok(read(adjustmentPath(payment) + "?limit=1&beforeId=" + second, "finance"), 200);
        assertThat(secondPage.at("/items/0/id").asText()).isEqualTo(first.toString()); assertThat(secondPage.at("/items/0/retirement/basis").asText()).isEqualTo("NEVER_DISPATCHED");
        assertThat(secondPage.path("activeAdjustmentId").asText()).isEqualTo(second.toString()); assertThat(secondPage.path("nextBeforeId").isNull()).isTrue();
        assertThat(secondPage.path("returnVersion")).isEqualTo(firstView.path("returnVersion")); assertThat(calls).doesNotContainKey("supplier-payable-adjustment-command");
    }

    @Test void adjustmentDisputePreservesCompletedAccountingAndNeverSendsAnotherCommand() throws Exception {
        UUID payment = paidBank(); registerFunds(payment); UUID id = queueAdjustment(payment); pollAdjustment(id);
        var original = adjustments.find("demo", id).orElseThrow(); var completed = adjustmentView(payment).path("completion");
        var bank = bankPayments.find("demo", payment).orElseThrow();
        queryAdjustmentDispute(id, SupplierPayableAdjustmentObservation.Status.REJECTED, 2);
        var invalid = adjustmentDisputeView(id); assertThat(invalid.path("canResolve").asBoolean()).isFalse();
        assertThat(invalid.path("issue").asText()).isEqualTo("ADJUSTMENT_ALREADY_OBSERVED");
        okError(send(adjustmentDisputePath(id) + "/resolutions", "finance", adjustmentDisputeInput(invalid)), 422, "SUPPLIER_ADJUSTMENT_DISPUTE_UNRESOLVABLE");
        queryAdjustmentDispute(id, SupplierPayableAdjustmentObservation.Status.ADJUSTED, 3); var ready = adjustmentDisputeView(id);
        assertThat(ready.path("canResolve").asBoolean()).isTrue(); String key = UUID.randomUUID().toString(); var input = adjustmentDisputeInput(ready);
        var first = send(adjustmentDisputePath(id) + "/resolutions", "finance", key, input); var receipt = ok(first, 202);
        assertThat(first.getHeader("Cache-Control")).isEqualTo("no-store"); assertThat(receipt.path("adjustmentVersion").asLong()).isEqualTo(ready.path("adjustmentVersion").asLong() + 1);
        assertThat(send(adjustmentDisputePath(id) + "/resolutions", "finance", key, input).getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(adjustmentView(payment).path("completion")).isEqualTo(completed); assertThat(bankPayments.find("demo", payment)).contains(bank);
        assertThat(adjustments.find("demo", id).orElseThrow().command()).isEqualTo(original.command());
        assertThat(adjustmentDisputeView(id).at("/latest/id").asText()).isEqualTo(receipt.path("resolutionId").asText());
        assertThat(calls.get("supplier-payable-adjustment-command").get()).isEqualTo(1);
        assertThat(ready.toString()).doesNotContain("private-ledger", "creditAccountReference", "commandDigest", "holdCommand");
    }

    @Test void adjustmentDisputeCompletesLocallyOnlyAfterSeparateBankRecheck() throws Exception {
        UUID payment = paidBank(); UUID id = unresolvedAdjustment(payment); queryAdjustmentDispute(id, SupplierPayableAdjustmentObservation.Status.ADJUSTED, 2);
        var ready = adjustmentDisputeView(id); ok(send(adjustmentDisputePath(id) + "/resolutions", "finance", adjustmentDisputeInput(ready)), 202);
        assertThat(adjustments.find("demo", id).orElseThrow().adjusted()).isTrue(); assertThat(adjustmentView(payment).path("completion").isNull()).isTrue();
        pollAdjustment(id); assertThat(adjustmentView(payment).at("/completion/adjustmentId").asText()).isEqualTo(id.toString());
        assertThat(calls.get("supplier-payable-adjustment-command").get()).isEqualTo(1);
        assertThat(adjustmentDisputeView(id).path("candidate").isNull()).isTrue();
    }

    @Test void adjustmentDisputeReplayRechecksIndependentFinanceAndSensitiveFields() throws Exception {
        UUID payment = paidBank(); UUID id = unresolvedAdjustment(payment); queryAdjustmentDispute(id, SupplierPayableAdjustmentObservation.Status.ADJUSTED, 2);
        var input = adjustmentDisputeInput(adjustmentDisputeView(id)); String key = UUID.randomUUID().toString();
        for (String user : List.of("alice", "cashier", "admin", "manager", "bob")) assertThat(send(adjustmentDisputePath(id) + "/resolutions", user, input).getStatus()).isBetween(400, 499);
        assertThat(ok(read(adjustmentDisputePath(id), "alice"), 200).path("canResolve").asBoolean()).isFalse();
        okError(read(adjustmentDisputePath(id), "admin"), 403, "FORBIDDEN"); okError(read(adjustmentDisputePath(id), "bob"), 404, "NOT_FOUND");
        var first = send(adjustmentDisputePath(id) + "/resolutions", "finance", key, input); ok(first, 202);
        organization.updateAppointment(admin, financeAppointment, false, 1); okError(send(adjustmentDisputePath(id) + "/resolutions", "finance", key, input), 403, "FORBIDDEN");
        organization.updateAppointment(admin, financeAppointment, true, 2);
        assertThat(send(adjustmentDisputePath(id) + "/resolutions", "finance", key, input).getContentAsString()).isEqualTo(first.getContentAsString());
        jdbc.update("UPDATE organization_person SET approval_eligible=FALSE WHERE tenant_id='demo' AND id=?", finance.toString());
        try { okError(send(adjustmentDisputePath(id) + "/resolutions", "finance", key, input), 403, "FORBIDDEN"); okError(read(adjustmentDisputePath(id), "finance"), 403, "FORBIDDEN"); }
        finally { jdbc.update("UPDATE organization_person SET approval_eligible=TRUE WHERE tenant_id='demo' AND id=?", finance.toString()); }
    }

    @Test void adjustmentDisputeRejectsForgedFactsWrongTerminalVersionAndQueryParameters() throws Exception {
        UUID payment = paidBank(); UUID id = unresolvedAdjustment(payment); queryAdjustmentDispute(id, SupplierPayableAdjustmentObservation.Status.ADJUSTED, 2);
        var displayed = adjustmentDisputeView(id); var input = adjustmentDisputeInput(displayed); var forged = new java.util.HashMap<>(input); forged.put("voucherReference", "FORGED");
        okError(send(adjustmentDisputePath(id) + "/resolutions", "finance", forged), 400, "INVALID_REQUEST");
        forged = new java.util.HashMap<>(input); forged.put("adjustmentVersion", displayed.path("adjustmentVersion").asLong() - 1);
        okError(send(adjustmentDisputePath(id) + "/resolutions", "finance", forged), 409, "CONCURRENCY_CONFLICT");
        forged = new java.util.HashMap<>(input); forged.put("outcome", "REJECTED");
        okError(send(adjustmentDisputePath(id) + "/resolutions", "finance", forged), 422, "SUPPLIER_ADJUSTMENT_DISPUTE_UNRESOLVABLE");
        forged.put("outcome", "NOT_FOUND"); okError(send(adjustmentDisputePath(id) + "/resolutions", "finance", forged), 400, "INVALID_REQUEST");
        okError(read(adjustmentDisputePath(id) + "?paymentId=" + payment, "finance"), 400, "INVALID_SUPPLIER_ADJUSTMENT_DISPUTE_QUERY");
        okError(send(adjustmentDisputePath(id) + "/resolutions?paymentId=" + payment, "finance", input), 400, "INVALID_SUPPLIER_ADJUSTMENT_DISPUTE_QUERY");
        okError(read(adjustmentDisputePath(UUID.randomUUID()), "finance"), 404, "NOT_FOUND");
    }

    @Test void adjustmentDisputeAuditFailureRollsBackDecisionRevisionAndIdempotency() throws Exception {
        UUID payment = paidBank(); UUID id = unresolvedAdjustment(payment); queryAdjustmentDispute(id, SupplierPayableAdjustmentObservation.Status.ADJUSTED, 2);
        var before = adjustments.find("demo", id).orElseThrow(); var input = adjustmentDisputeInput(adjustmentDisputeView(id)); String key = UUID.randomUUID().toString();
        jdbc.execute("ALTER TABLE audit_event ADD CONSTRAINT fixture_adjustment_dispute_audit CHECK(aggregate_id<>'%s' OR action<>'SUPPLIER_ADJUSTMENT_DISPUTE_RESOLVE')".formatted(id));
        try { assertThatThrownBy(() -> send(adjustmentDisputePath(id) + "/resolutions", "finance", key, input)).hasCauseInstanceOf(org.springframework.dao.DataIntegrityViolationException.class); }
        finally { jdbc.execute("ALTER TABLE audit_event DROP CONSTRAINT fixture_adjustment_dispute_audit"); }
        assertThat(adjustments.find("demo", id)).contains(before); assertThat(adjustments.latestResolution("demo", id)).isEmpty();
        assertThat(adjustments.revision("demo", id, before.version() + 1)).isEmpty();
        ok(send(adjustmentDisputePath(id) + "/resolutions", "finance", key, input), 202);
        assertThat(adjustmentView(payment).path("completion").isNull()).isTrue(); pollAdjustment(id);
        assertThat(adjustmentView(payment).at("/completion/adjustmentId").asText()).isEqualTo(id.toString());
    }

    private String adjustmentDisputePath(UUID id) { return "/api/v1/supplier-adjustments/" + id + "/dispute"; }
    private JsonNode adjustmentDisputeView(UUID id) throws Exception { var response = read(adjustmentDisputePath(id), "finance"); var value = ok(response, 200); assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store"); return value; }
    private Map<String, Object> adjustmentDisputeInput(JsonNode view) { return Map.of("adjustmentVersion", view.path("adjustmentVersion").asLong(), "outcome", view.at("/candidate/outcome").asText(), "evidenceReference", "ERP-ADJUSTMENT-STATEMENT", "comment", "明确核对原回款调整与实际分录"); }
    private UUID unresolvedAdjustment(UUID payment) throws Exception {
        registerFunds(payment); UUID id = queueAdjustment(payment); var command = adjustments.find("demo", id).orElseThrow().command();
        responder = (operation, request) -> operation.equals("supplier-payable-adjustment-command") ? adjustmentResponse(request,
                new SupplierPayableAdjustmentObservation(id, command.digest(), SupplierPayableAdjustmentObservation.Status.PENDING, 1, Instant.now(), null, null)) : normal(operation, request);
        pollAdjustment(id); queryAdjustmentDispute(id, SupplierPayableAdjustmentObservation.Status.NOT_FOUND, 0); return id;
    }
    private void queryAdjustmentDispute(UUID id, SupplierPayableAdjustmentObservation.Status outcome, long revision) throws Exception {
        var current = adjustments.find("demo", id).orElseThrow(); var command = current.command();
        responder = (operation, request) -> operation.equals("supplier-payable-adjustment-query") ? adjustmentResponse(request,
                new SupplierPayableAdjustmentObservation(id, command.digest(), outcome, revision, Instant.now(), outcome == SupplierPayableAdjustmentObservation.Status.ADJUSTED ? adjustmentObservation(command).posting() : null,
                        outcome == SupplierPayableAdjustmentObservation.Status.REJECTED ? SupplierPayableAdjustmentObservation.Rejection.ACCOUNTING_PERIOD_CLOSED : null)) : normal(operation, request);
        ok(send(adjustmentActionPath(id), "finance", Map.of("action", "QUERY", "adjustmentVersion", current.version(), "comment", "按原调整编号读取近期终态")), 202); pollAdjustment(id);
        assertThat(adjustments.find("demo", id).orElseThrow().status()).isEqualTo(SupplierPayableAdjustmentOperation.Status.RECONCILING);
    }
    private String adjustmentResponse(JsonNode request, SupplierPayableAdjustmentObservation value) {
        return json.write(Map.of("contractVersion", 1, "tenantId", "demo", "requestId", request.path("requestId").asText(), "outcome", "SUCCESS", "data", value));
    }

    private String adjustmentPath(UUID payment) { return "/api/v1/supplier-payments/" + payment + "/adjustments"; }
    private String adjustmentPreparePath(UUID payment) { return "/api/v1/supplier-payments/" + payment + "/adjustment-preparations"; }
    private String adjustmentActionPath(UUID id) { return "/api/v1/supplier-adjustments/" + id + "/finance-actions"; }
    private JsonNode adjustmentView(UUID payment) throws Exception { var response = read(adjustmentPath(payment), "finance"); var view = ok(response, 200); assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store"); return view; }
    private Map<String, Object> adjustmentInput(JsonNode view) { return Map.of("paymentVersion", view.at("/bank/version").asLong(), "returnVersion", view.path("returnVersion").asLong(), "accountingDate", view.path("minimumAccountingDate").asText(), "comment", "确认已登记资金和本次记账日期"); }
    private Map<String, Object> adjustmentAction(JsonNode view, String action) { return Map.of("action", action, "adjustmentVersion", view.path("version").asLong(), "comment", "按原调整编号核对"); }
    private void registerFunds(UUID payment) throws Exception { queueReturn(payment); ok(send(returnPath(payment) + "/registrations", "finance", returnRegistration(returnView(payment))), 202); }
    private UUID queueAdjustment(UUID payment) throws Exception {
        UUID id = UUID.fromString(ok(send(adjustmentPreparePath(payment), "finance", adjustmentInput(adjustmentView(payment))), 202).path("preparationId").asText()); pollAdjustmentPreparation(id); return id;
    }
    private void pollAdjustmentPreparation(UUID id) {
        var candidates = org.mockito.Mockito.mock(JdbcSupplierAdjustmentPreparationRepository.class);
        org.mockito.Mockito.when(candidates.due(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(new JdbcSupplierAdjustmentPreparationRepository.Candidate("demo", id)));
        new SupplierAdjustmentPreparationWorker(candidates, adjustmentPreparation, adjustmentReader).poll();
        assertThat(adjustmentPreparations.find("demo", id).orElseThrow().status()).isEqualTo(SupplierAdjustmentPreparation.Status.READY);
    }
    private void pollAdjustment(UUID id) {
        var candidates = org.mockito.Mockito.mock(JdbcSupplierPayableAdjustmentRepository.class); var candidate = new JdbcSupplierPayableAdjustmentRepository.Candidate("demo", id);
        org.mockito.Mockito.when(candidates.due(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(candidate));
        org.mockito.Mockito.when(candidates.awaitingLocalCompletion()).thenAnswer(invocation -> adjustments.awaitingLocalCompletion().stream().filter(value -> value.id().equals(id)).toList());
        org.mockito.Mockito.when(candidates.find("demo", id)).thenAnswer(invocation -> adjustments.find("demo", id));
        new SupplierAdjustmentWorker(candidates, adjustmentExecution, adjustmentReader, adjustmentPort, returnPort, adjustmentCompletion).poll();
    }
    private SupplierPayableAdjustmentObservation adjustmentObservation(SupplierPayableAdjustmentCommand command) {
        var source = command.source(); var payment = source.returns().request().command();
        var before = source.previous() != null ? source.previous().observation().posting().payableSettledAfter()
                : source.settlement() != null ? source.settlement().observation().posting().settledAfter() : payment.holdCommand().authorization().payable().settled();
        var after = (source.recognizesOriginalPayment() ? before.plus(payment.amount()) : before).minus(source.newReturned());
        var recognition = source.previous() != null ? source.previous().observation().posting().recognitionVoucherReference()
                : source.settlement() != null ? source.settlement().observation().posting().voucherReference() : "recognized-" + payment.id();
        var posting = new SupplierPayableAdjustmentObservation.Posting("adjust-" + command.id(), payment.held().holdReference(), "private-ledger-adjusted", recognition,
                source.newReturned(), source.returns().totalReturned(), source.netPaid(), before, after, java.util.stream.IntStream.range(0, source.newReturns().size())
                .mapToObj(index -> { var entry = source.newReturns().get(index); return new SupplierPayableAdjustmentObservation.ReturnEntry(entry.proof().transactionReference(), entry.proof().amount(), "voucher-" + command.id(), "entry-" + index); }).toList(),
                command.period().periodReference(), command.period().request().accountingDate(), command.registeredAt());
        return new SupplierPayableAdjustmentObservation(command.id(), command.digest(), SupplierPayableAdjustmentObservation.Status.ADJUSTED, 1, Instant.now(), posting, null);
    }

    private String returnPath(UUID payment) { return "/api/v1/supplier-payments/" + payment + "/returns"; }
    private JsonNode returnView(UUID payment) throws Exception { var response = read(returnPath(payment), "finance"); assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store"); return ok(response, 200); }
    private Map<String, Object> returnQuery(JsonNode view) { return Map.of("operationVersion", view.path("operationVersion").asLong(), "returnVersion", view.path("returnVersion").asLong(), "comment", "读取原供应商付款实际回款"); }
    private Map<String, Object> returnRegistration(JsonNode view) { return Map.of("operationVersion", view.path("operationVersion").asLong(), "returnVersion", view.path("returnVersion").asLong(), "checkId", view.at("/latestCheck/id").asText(), "checkVersion", view.at("/latestCheck/version").asLong(), "outcome", view.at("/latestCheck/evidence/outcome").asText(), "evidenceReference", "SUPPLIER-RETURN-REVIEW", "comment", "已核对本次实际入款，账务调整单独办理"); }
    private void queueReturn(UUID payment) throws Exception { var response = ok(send(returnPath(payment) + "/checks", "finance", returnQuery(returnView(payment))), 202); pollReturn(UUID.fromString(response.path("checkId").asText())); }
    private void pollReturn(UUID id) {
        var candidates = org.mockito.Mockito.mock(JdbcSupplierPaymentReturnCheckRepository.class);
        org.mockito.Mockito.when(candidates.due(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(new JdbcSupplierPaymentReturnCheckRepository.Candidate("demo", id)));
        new SupplierPaymentReturnWorker(candidates, returnService, returnPort).poll();
        assertThat(returnChecks.find("demo", id).orElseThrow().status()).isEqualTo(SupplierPaymentReturnCheck.Status.CHECKED);
    }
    private SupplierPaymentReturnPort.Receipt returned(SupplierPaymentReturnPort.Request request) {
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS); var original = request.original();
        var current = new PaymentObservation(original.authorizationId(), original.commandDigest(), original.status(), original.revision(), now,
                original.paymentReference(), original.paidAmount(), original.accountDigest(), original.completedAt(), original.receiptReference(), original.failure());
        var first = new SupplierPaymentReturnPort.BankReceipt("RETURN-" + request.command().id() + "-1", request.command().debitAccount().reference(), money("20"), original.completedAt());
        var entries = returnRevision == 1 ? List.of(first) : List.of(first, new SupplierPaymentReturnPort.BankReceipt("RETURN-" + request.command().id() + "-2", request.command().debitAccount().reference(), money("10"), original.completedAt()));
        return new SupplierPaymentReturnPort.Receipt(request, SupplierPaymentReturnPort.Status.PARTIALLY_RETURNED, returnRevision, now, now.plusSeconds(300), current, entries);
    }

    private UUID paidBank() throws Exception {
        UUID id = authorizedHold(approved()); var receipt = ok(send(cashierPath(id) + "/actions", "cashier", executeInput(cashierView(id))), 202);
        pollPreparation(UUID.fromString(receipt.path("preparationId").asText())); pollBank(id); assertThat(bankPayments.find("demo", id).orElseThrow().settleable()).isTrue(); return id;
    }
    private String settlementPath(UUID payment) { return "/api/v1/supplier-payments/" + payment + "/settlements"; }
    private String settlementPreparePath(UUID payment) { return "/api/v1/supplier-payments/" + payment + "/settlement-preparations"; }
    private String settlementActionPath(UUID id) { return "/api/v1/supplier-settlements/" + id + "/finance-actions"; }
    private JsonNode settlementView(UUID payment) throws Exception { var response = read(settlementPath(payment), "finance"); assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store"); return ok(response, 200); }
    private Map<String, Object> settlementInput(JsonNode view) { return Map.of("paymentVersion", view.at("/bank/version").asLong(), "accountingDate", view.path("minimumAccountingDate").asText(), "comment", "已核对原银行到账、固定金额和本次记账日期"); }
    private Map<String, Object> settlementAction(JsonNode operation, String action) { return Map.of("action", action, "settlementVersion", operation.path("version").asLong(), "comment", "按原编号核对结算状态"); }
    private UUID queueSettlement(UUID payment) throws Exception {
        var receipt = ok(send(settlementPreparePath(payment), "finance", settlementInput(settlementView(payment))), 202); UUID id = UUID.fromString(receipt.path("preparationId").asText()); pollSettlementPreparation(id); return id;
    }
    private void pollSettlementPreparation(UUID id) {
        var candidates = org.mockito.Mockito.mock(JdbcSupplierSettlementPreparationRepository.class);
        org.mockito.Mockito.when(candidates.due(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(new JdbcSupplierSettlementPreparationRepository.Candidate("demo", id)));
        new SupplierSettlementPreparationWorker(candidates, settlementPreparation, settlementReader).poll();
    }
    private void pollSettlement(UUID id) {
        var candidates = org.mockito.Mockito.mock(JdbcSupplierPayableSettlementRepository.class);
        org.mockito.Mockito.when(candidates.due(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(new JdbcSupplierPayableSettlementRepository.Candidate("demo", id)));
        new SupplierSettlementWorker(candidates, settlementExecution, settlementReader, settlementPort).poll();
    }
    private SupplierPayableSettlementObservation settlementObservation(SupplierPayableSettlementCommand command) {
        var posting = new SupplierPayableSettlementObservation.Posting("SUPPLIER-SETTLEMENT-1", command.payment().held().holdReference(), "private-ledger-settled", command.payment().amount(), money("30"), money("100"),
                command.paid().paymentReference(), command.paid().receiptReference(), "SUPPLIER-VOUCHER-1", command.period().periodReference(), command.period().request().accountingDate(), command.registeredAt());
        return new SupplierPayableSettlementObservation(command.id(), command.digest(), SupplierPayableSettlementObservation.Status.SETTLED, 1L, Instant.now(), posting, null);
    }

    private UUID authorizedHold(UUID request) throws Exception {
        queueAndRead(request); var receipt = ok(send(financePath(request) + "/authorizations", "finance", authorizeInput(request, view(request, "finance"))), 202);
        UUID id = UUID.fromString(receipt.path("authorizationId").asText()); pollHold(id); return id;
    }
    private String cashierPath(UUID id) { return "/api/v1/cashier/supplier-payments/" + id; }
    private JsonNode cashierView(UUID id) throws Exception { var response = read(cashierPath(id), "cashier"); assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store"); return ok(response, 200); }
    private Map<String, Object> executeInput(JsonNode view) { return Map.of("action", "EXECUTE", "holdVersion", view.at("/hold/version").asLong(), "debitAccountReference", "debit-1", "debitAccountVersion", "debit-v1", "comment", "已核对供应商、原应付预留与本次出款账户"); }
    private Map<String, Object> bankAction(JsonNode view, String action) { return Map.of("action", action, "operationVersion", view.at("/operation/version").asLong(), "comment", "按原编号核对银行交易"); }
    private void assertCashierPrivateFactsAbsent(JsonNode value) { assertPrivateFactsAbsent(value); assertThat(value.toString()).doesNotContain("commandDigest", "private-erp-hold", "private-ledger", "CONTRACT-1", "ACCEPTANCE-1", "debit-1"); }
    private void pollPreparation(UUID id) {
        var candidates = org.mockito.Mockito.mock(JdbcSupplierPaymentExecutionRepository.class);
        org.mockito.Mockito.when(candidates.due(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(new JdbcSupplierPaymentExecutionRepository.Candidate("demo", id)));
        new SupplierPaymentExecutionWorker(candidates, cashierPreparation, bankReader).poll();
    }
    private void pollBank(UUID id) {
        var candidates = org.mockito.Mockito.mock(JdbcSupplierPaymentOperationRepository.class);
        org.mockito.Mockito.when(candidates.due(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(new JdbcSupplierPaymentOperationRepository.Candidate("demo", id)));
        new SupplierPaymentWorker(candidates, bankService, bankReader, bankPort).poll();
    }
    private PaymentAccountsPort.Directory directory(PaymentAccountsPort.Request request, Instant observedAt) {
        return new PaymentAccountsPort.Directory(request, "directory-v1", observedAt, observedAt.plusSeconds(600), List.of(new PaymentAccountsPort.DebitAccount("debit-1", "法人基本户", "****5678", "CNY", "debit-v1")));
    }
    private PaymentObservation bankObservation(SupplierPaymentCommand command, PaymentObservation.Status status) {
        return status == PaymentObservation.Status.NOT_FOUND
                ? new PaymentObservation(command.id(), command.digest(), status, 0L, Instant.now(), null, null, null, null, null, null)
                : new PaymentObservation(command.id(), command.digest(), status, 1L, Instant.now(), "BANK-1", command.amount(), command.payee().accountDigest(), command.registeredAt(), "RECEIPT-1", null);
    }

    private UUID approved() throws Exception {
        UUID id = id(ok(send("/api/v1/procurement-payments", "alice", createBody(published(), content("70"))), 201)); submit(id);
        ok(send(actionPath(id), "finance", decision(id, "APPROVE")), 200); ok(send(actionPath(id), "manager", decision(id, "APPROVE")), 200); return id;
    }
    private Map<String, Object> reviewInput(UUID id) { return Map.of("roundNo", 1, "applicationVersion", app(id).version(), "requestVersion", current(id).version(), "comment", "已核对原批准应付，申请读取当前余额"); }
    private Map<String, Object> authorizeInput(UUID id, JsonNode view) {
        var input = new java.util.HashMap<>(reviewInput(id)); input.put("reviewId", view.at("/review/id").asText()); input.put("reviewVersion", view.at("/review/version").asLong()); input.put("comment", "已核对新鲜余额，授权原应付预留"); return input;
    }
    private Map<String, Object> action(JsonNode view, String action) { return Map.of("action", action, "holdVersion", view.at("/hold/version").asLong(), "comment", "根据原预留状态处理"); }
    private String financePath(UUID id) { return path(id) + "/supplier-payment"; }
    private String actionUrl(UUID id) { return "/api/v1/supplier-payments/" + id + "/finance-actions"; }
    private JsonNode view(UUID id, String actor) throws Exception { var response = read(financePath(id), actor); assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store"); return ok(response, 200); }
    private int count(String table, UUID id) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id='demo' AND request_id=?", Integer.class, id.toString()); }
    private UUID queueAndRead(UUID id) throws Exception {
        UUID review = UUID.fromString(ok(send(financePath(id) + "/reviews", "finance", reviewInput(id)), 202).path("reviewId").asText()); readPayable(review); return review;
    }
    private void readPayable(UUID id) {
        var candidates = org.mockito.Mockito.mock(JdbcSupplierPayableReviewRepository.class);
        org.mockito.Mockito.when(candidates.due(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(new JdbcSupplierPayableReviewRepository.Candidate("demo", id)));
        new SupplierPayableReviewWorker(candidates, reviewService, payablePort).poll();
    }
    private void pollHold(UUID id) {
        var candidates = org.mockito.Mockito.mock(JdbcSupplierPayableHoldRepository.class);
        org.mockito.Mockito.when(candidates.due(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(new JdbcSupplierPayableHoldRepository.Candidate("demo", id)));
        new SupplierPayableHoldWorker(candidates, holdService, holdPort).poll();
    }
    private SupplierPayableHoldObservation held(SupplierPayableHoldCommand command) {
        return new SupplierPayableHoldObservation(command.id(), command.digest(), SupplierPayableHoldObservation.Status.HELD, 1L, Instant.now().truncatedTo(ChronoUnit.MICROS),
                "private-erp-hold", "private-ledger-v1", command.authorization().source().reservation().source().round().content().amount(), command.authorization().payable().account().accountDigest(), command.authorization().authorizedAt(), null);
    }
    private void okError(MockHttpServletResponse response, int status, String code) throws Exception { assertThat(ok(response, status).path("code").asText()).isEqualTo(code); }

    private Map<String, Object> createBody(DefinitionDraft definition, ProcurementPaymentContent content) { return Map.of("businessNo", "PROCUREMENT-" + UUID.randomUUID(), "processKey", definition.key(), "definitionVersion", definition.version(), "content", content); }
    private ProcurementPaymentContent content(String amount) { return new ProcurementPaymentContent(entity, "合成设备采购款", "按原合同支付验收应付", "supplier-1", "AP-" + UUID.randomUUID(), money(amount)); }
    private Map<String, Object> submission(UUID id, UUID checked) { return Map.of("applicationVersion", app(id).version(), "requestVersion", current(id).version(), "precheckId", checked); }
    private Map<String, Object> queueInput(UUID id) { return Map.of("applicationVersion", app(id).version(), "requestVersion", current(id).version(), "initiatorAppointmentId", appointment, "targetDigest", configuration.destination("demo").orElseThrow().digest("demo")); }
    private UUID enqueue(UUID id) throws Exception { return id(ok(send(path(id) + "/prechecks", "alice", queueInput(id)), 202)); }
    private UUID ready(UUID id) throws Exception { UUID checked = enqueue(id); worker.poll(); assertThat(check(checked).status()).as(String.valueOf(check(checked).result())).isEqualTo(Status.READY); return checked; }
    private void submit(UUID id) throws Exception { ok(send(path(id) + "/submit", "alice", submission(id, ready(id))), 200); }
    private ProcurementPaymentRequest current(UUID id) { return requests.find("demo", id).orElseThrow(); }
    private Application app(UUID id) { return applications.findById("demo", current(id).applicationId()).orElseThrow(); }
    private ProcurementPaymentCheck check(UUID id) { return checks.find("demo", id).orElseThrow(); }
    private String path(UUID id) { return "/api/v1/procurement-payments/" + id; }
    private String actionPath(UUID id) { return "/api/v1/tasks/" + tasks.createTaskQuery().processVariableValueEquals("applicationId", app(id).id().toString()).singleResult().getId() + "/actions"; }
    private Map<String, Object> decision(UUID id, String action) { return Map.of("action", action, "expectedVersion", app(id).version(), "comment", "已核对本轮原采购应付"); }
    private MockHttpServletResponse send(String path, String user, Object body) throws Exception { return send(path, user, UUID.randomUUID().toString(), body); }
    private MockHttpServletResponse send(String path, String user, String key, Object body) throws Exception { return mvc.perform(post(path).header("Authorization", token(user)).header("Idempotency-Key", key).contentType("application/json").content(json.write(body))).andReturn().getResponse(); }
    private MockHttpServletResponse read(String path, String user) throws Exception { return mvc.perform(get(path).header("Authorization", token(user))).andReturn().getResponse(); }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private JsonNode ok(MockHttpServletResponse response, int status) throws Exception { assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status); return json.read(response.getContentAsString(StandardCharsets.UTF_8), JsonNode.class); }
    private UUID id(JsonNode node) { return UUID.fromString(node.path("id").asText()); }
    private static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
    private void assertPrivateFactsAbsent(JsonNode node) { assertThat(node.toString()).doesNotContain("private-supplier-account", "accountReference", "accountDigest", "verificationReference", "invoiceDigest", "a".repeat(64), "b".repeat(64)); }
    private void assertNoFinancialWrites(UUID id) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM voucher_preparation WHERE tenant_id='demo' AND business_id=?", Integer.class, id.toString())).isZero();
    }
    private UUID person(String subject, boolean approver) {
        var found = jdbc.queryForList("SELECT id FROM organization_person WHERE tenant_id='demo' AND subject=?", String.class, subject);
        return found.isEmpty() ? organization.createPerson(admin, subject, subject, true, approver).id() : UUID.fromString(found.get(0));
    }
    private DefinitionDraft published() {
        var graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("review", "财务原应付复核", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_" + finance)),
                new Node("finalReview", "采购批准", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_" + manager)), new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("a", "start", "review", "", false), new Edge("b", "review", "finalReview", "", false), new Edge("c", "finalReview", "end", "", false)));
        var access = Map.of("review", FieldVisibility.READ_ONLY, "finalReview", FieldVisibility.READ_ONLY);
        var schema = new FormSchema(2, List.of(new FormSchema.Field(ProcurementPaymentFormContract.DETAILS, "采购应付明细", FormSchema.FieldType.TEXT, true, null,
                null, null, null, null, null, null, true, access), new FormSchema.Field("amount", "本次付款额", FormSchema.FieldType.NUMBER, true, null, null, null, null, null),
                new FormSchema.Field("currency", "本位币", FormSchema.FieldType.TEXT, true, null, null, null, null, null)));
        var draft = definitions.create("demo", "procurement-test-" + UUID.randomUUID(), "采购付款合成流程", graph, schema, null);
        return definitions.publish(admin, draft.id(), draft.revision(), "采购付款验收");
    }
    private String normal(String operation, JsonNode request) {
        Object result = switch (operation) {
            case "catalog" -> new FinanceCatalog("alice", "synthetic-procurement-v1", Instant.now().plusSeconds(600),
                    List.of(new FinanceCatalog.LegalEntity(entity, "合成法人", "CNY", false, "entity-v1", "Asia/Shanghai")),
                    List.of(new FinanceCatalog.Category("PROCUREMENT", "采购", List.of(ExpenseLine.Unit.ITEM))), List.of(new FinanceCatalog.CostCenter(entity, "IT", "研发")), List.of(), List.of());
            case "procurement-payable" -> payable(json.read(request.path("data").toString(), ProcurementPayablePort.Request.class));
            case "supplier-payable-hold-command" -> held(json.read(request.at("/data/command").toString(), SupplierPayableHoldCommand.class));
            case "supplier-payable-hold-query" -> held(holds.find("demo", UUID.fromString(request.at("/data/authorizationId").asText())).orElseThrow().command());
            case "debit-accounts" -> directory(json.read(request.path("data").toString(), PaymentAccountsPort.Request.class), Instant.now());
            case "supplier-payment-command" -> bankObservation(json.read(request.at("/data/command").toString(), SupplierPaymentCommand.class), PaymentObservation.Status.SUCCEEDED);
            case "supplier-payment-query" -> bankObservation(bankPayments.find("demo", UUID.fromString(request.at("/data/authorizationId").asText())).orElseThrow().command(), PaymentObservation.Status.SUCCEEDED);
            case "supplier-payment-return" -> returned(json.read(request.path("data").toString(), SupplierPaymentReturnPort.Request.class));
            case "accounting-period" -> {
                var period = json.read(request.path("data").toString(), AccountingPeriodPort.Request.class); var date = period.accountingDate(); var now = Instant.now();
                yield new AccountingPeriodPort.OpenPeriod(period, "PERIOD-" + date.getMonthValue(), "v1", date.withDayOfMonth(1), date.withDayOfMonth(date.lengthOfMonth()), now, now.plusSeconds(600));
            }
            case "supplier-payable-settlement-command" -> settlementObservation(json.read(request.at("/data/command").toString(), SupplierPayableSettlementCommand.class));
            case "supplier-payable-settlement-query" -> settlementObservation(settlements.find("demo", UUID.fromString(request.at("/data/operationId").asText())).orElseThrow().command());
            case "supplier-payable-adjustment-command" -> adjustmentObservation(json.read(request.at("/data/command").toString(), SupplierPayableAdjustmentCommand.class));
            case "supplier-payable-adjustment-query" -> adjustmentObservation(adjustments.find("demo", UUID.fromString(request.at("/data/operationId").asText())).orElseThrow().command());
            default -> throw new IllegalArgumentException("Unexpected finance operation from supplier finance");
        };
        return json.write(Map.of("contractVersion", 1, "tenantId", request.path("tenantId").asText(), "requestId", request.path("requestId").asText(), "outcome", "SUCCESS", "data", result));
    }
    private ProcurementPayablePort.Payable payable(ProcurementPayablePort.Request request) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return new ProcurementPayablePort.Payable(request, "synthetic-ap-v1", now, now.plusSeconds(300), "合成供应商",
                new SupplierAccountSnapshot(request.legalEntityId(), request.supplierReference(), "private-supplier-account", "****3456", "a".repeat(64), "account-v1"),
                "CONTRACT-1", "ORDER-1", "MATCH-1", "ACCRUAL-1", "BUDGET-RECOGNITION-1", LocalDate.now().plusDays(10), money("100"), money("30"),
                List.of(new ProcurementPayablePort.MatchedLine(1, 1, "ACCEPTANCE-1", new InvoiceKey(InvoiceKey.Type.DIGITAL, null, String.format("%020d", Integer.toUnsignedLong((request.legalEntityId() + request.payableReference()).hashCode()))), 1,
                        "b".repeat(64), "private-verification", "件", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, money("100"), money("100"), money("100"), money("10"))));
    }
    private static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/finance/", exchange -> {
                var active = ACTIVE.get(); String operation = exchange.getRequestURI().getPath().substring("/finance/".length());
                active.calls.computeIfAbsent(operation, key -> new AtomicInteger()).incrementAndGet();
                var request = active.json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonNode.class);
                byte[] bytes = active.responder.apply(operation, request).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length);
                try { exchange.getResponseBody().write(bytes); } finally { exchange.close(); }
            }); server.start(); return server;
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
}
