package io.agentflow.procurement;

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
    @Autowired JdbcSupplierSettlementPreparationRepository settlementPreparations;
    @Autowired JdbcSupplierPayableSettlementRepository settlements;
    @Autowired SupplierSettlementPreparationService settlementPreparation;
    @Autowired SupplierSettlementService settlementExecution;
    @Autowired SupplierSettlementEvidenceReader settlementReader;
    @Autowired SupplierPayableSettlementPort settlementPort;
    @Autowired PaymentCallbackService callbacks;
    @Autowired JdbcPaymentCallbackRepository callbackRecords;

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
            case "accounting-period" -> {
                var period = json.read(request.path("data").toString(), AccountingPeriodPort.Request.class); var date = period.accountingDate(); var now = Instant.now();
                yield new AccountingPeriodPort.OpenPeriod(period, "PERIOD-" + date.getMonthValue(), "v1", date.withDayOfMonth(1), date.withDayOfMonth(date.lengthOfMonth()), now, now.plusSeconds(600));
            }
            case "supplier-payable-settlement-command" -> settlementObservation(json.read(request.at("/data/command").toString(), SupplierPayableSettlementCommand.class));
            case "supplier-payable-settlement-query" -> settlementObservation(settlements.find("demo", UUID.fromString(request.at("/data/operationId").asText())).orElseThrow().command());
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
