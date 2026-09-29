package io.agentflow.procurement;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import io.agentflow.expense.ExpenseLine;
import io.agentflow.expense.InvoiceKey;
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.finance.FinanceGatewayConfiguration;
import io.agentflow.finance.Money;
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
        "agentflow.supplier-payments.hold-worker-enabled=false", "agentflow.supplier-payments.review-worker-enabled=false",
        "agentflow.procurement-payments.precheck-worker-enabled=false", "agentflow.budgets.worker-enabled=false"})
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
    private BiFunction<String, JsonNode, String> responder;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("agentflow.attachments.directory", DIRECTORY::toString);
        registry.add("agentflow.finance-gateway.tenants.demo.endpoint", () -> ENDPOINT);
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

    @BeforeEach void setup() {
        ACTIVE.set(this); configuration.setEnabled(true); configuration.getTenants().get("demo").setEndpoint(ENDPOINT);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM organization_directory WHERE tenant_id='demo'", Integer.class) == 0) organization.initialize(admin);
        entity = organization.createUnit(admin, OrganizationUnit.Kind.LEGAL_ENTITY, "合成采购法人", null, null, true).id();
        var department = organization.createUnit(admin, OrganizationUnit.Kind.DEPARTMENT, "采购部门", entity, null, true);
        var position = organization.createUnit(admin, OrganizationUnit.Kind.POSITION, "采购岗位", entity, null, true);
        appointment = organization.createAppointment(admin, person("alice", false), department.id(), position.id(), true).id();
        manager = person("manager", true); finance = person("finance", true);
        financeAppointment = organization.createAppointment(admin, finance, department.id(), position.id(), true).id();
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
