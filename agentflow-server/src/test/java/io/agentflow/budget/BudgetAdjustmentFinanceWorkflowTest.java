package io.agentflow.budget;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import static io.agentflow.budget.BudgetAdjustmentCheck.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 使用真实认证、Flowable 和回环财务 HTTP 验证批准后的独立授权、原号恢复与权限边界。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "agentflow.finance-gateway.enabled=true",
        "agentflow.vouchers.preparation-worker-enabled=false", "agentflow.vouchers.worker-enabled=false",
        "agentflow.invoices.verification-worker-enabled=false", "agentflow.expenses.precheck-worker-enabled=false",
        "agentflow.advance-requests.precheck-worker-enabled=false", "agentflow.expense-plans.precheck-worker-enabled=false",
        "agentflow.budget-adjustments.precheck-worker-enabled=false", "agentflow.budget-adjustments.review-worker-enabled=false",
        "agentflow.budget-adjustments.execution-worker-enabled=false", "agentflow.procurement-payments.precheck-worker-enabled=false", "agentflow.budgets.worker-enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class BudgetAdjustmentFinanceWorkflowTest {
    private static final HttpServer SERVER = server();
    private static final String ENDPOINT = "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/finance";
    private static final AtomicReference<BudgetAdjustmentFinanceWorkflowTest> ACTIVE = new AtomicReference<>();
    private static final Path DIRECTORY = Path.of("/fyoung/tmp/agentflow-budget-adjustment-finance-workflow-" + UUID.randomUUID());
    private final Actor admin = new Actor("demo", "admin", Set.of("ADMIN", "PROCESS_ADMIN"));
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private UUID entity;
    private UUID appointment;
    private UUID manager;
    private UUID finance;
    private UUID financeAppointment;
    private String ledgerLimit = "1000";
    private final Map<UUID, String> commandBytes = new ConcurrentHashMap<>();
    private final Map<UUID, BudgetAdjustmentObservation> accepted = new ConcurrentHashMap<>();
    private BiFunction<String, JsonNode, String> responder;
    private final java.util.List<String> traceHeaders = new java.util.concurrent.CopyOnWriteArrayList<>();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("agentflow.attachments.directory", DIRECTORY::toString);
        registry.add("agentflow.finance-gateway.tenants.demo.endpoint", () -> ENDPOINT);
        registry.add("agentflow.finance-gateway.tenants.demo.allow-unauthenticated-loopback", () -> "true");
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_BUDGET_ADJUSTMENT_FINANCE_WORKFLOW_URL", "jdbc:h2:mem:budget-adjustment-finance-workflow;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_BUDGET_ADJUSTMENT_FINANCE_WORKFLOW_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_BUDGET_ADJUSTMENT_FINANCE_WORKFLOW_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_BUDGET_ADJUSTMENT_FINANCE_WORKFLOW_PASSWORD", ""));
    }

    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @Autowired AuthService auth;
    @Autowired CurrentActor actors;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrganizationService organization;
    @Autowired DefinitionApplicationService definitions;
    @Autowired ApplicationRepository applications;
    @Autowired BudgetAdjustmentRepository requests;
    @Autowired TaskService tasks;
    @Autowired BudgetAdjustmentCheckService execution;
    @Autowired BudgetAdjustmentCheckWorker worker;
    @Autowired JdbcBudgetAdjustmentCheckRepository checks;
    @Autowired FinanceGatewayConfiguration configuration;
    @Autowired BudgetAdjustmentReviewWorker reviewWorker;
    @Autowired BudgetAdjustmentExecutionWorker executionWorker;
    @Autowired JdbcBudgetAdjustmentReviewRepository reviews;
    @Autowired JdbcBudgetAdjustmentOperationRepository operations;
    @Autowired BudgetAdjustmentExecutionService financeExecution;
    @Autowired BudgetAdjustmentReviewService financeReviews;
    @Autowired org.springframework.context.ApplicationEventPublisher events;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired io.agentflow.notification.BudgetAdjustmentNotificationAccess noticeAccess;
    @Autowired io.agentflow.notification.NotificationPreferencesService notificationPreferences;
    @Autowired io.agentflow.notification.NotificationDeliveryService notificationDeliveries;
    @Autowired io.agentflow.notification.JdbcNotificationDeliveryStore notificationDeliveryStore;



    @BeforeEach void setup() {
        ACTIVE.set(this); configuration.setEnabled(true); configuration.getTenants().get("demo").setEndpoint(ENDPOINT);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM organization_directory WHERE tenant_id='demo'", Integer.class) == 0) organization.initialize(admin);
        entity = organization.createUnit(admin, OrganizationUnit.Kind.LEGAL_ENTITY, "合成预算执行法人", null, null, true).id();
        var department = organization.createUnit(admin, OrganizationUnit.Kind.DEPARTMENT, "预算执行部门", entity, null, true);
        var position = organization.createUnit(admin, OrganizationUnit.Kind.POSITION, "预算执行岗位", entity, null, true);
        appointment = organization.createAppointment(admin, person("alice", false), department.id(), position.id(), true).id();
        manager = person("manager", true); finance = person("finance", true);
        financeAppointment = organization.createAppointment(admin, finance, department.id(), position.id(), true).id();
        responder = this::normal;
    }
    @AfterEach void settle() { actors.clear(); responder = this::normal; configuration.setEnabled(true); configuration.getTenants().get("demo").setEndpoint(ENDPOINT); reviewWorker.poll(); executionWorker.poll(); }
    @AfterAll static void closeServer() { SERVER.stop(0); }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"PRECHECK", "REVIEW", "OPERATION"})
    void originalRequestTraceSurvivesEachQueueAndReplay(String kind) throws Exception {
        UUID id = kind.equals("PRECHECK") ? create() : approved();
        if (kind.equals("OPERATION")) queueAndRead(id);
        String endpoint = switch (kind) {
            case "PRECHECK" -> path(id) + "/prechecks";
            case "REVIEW" -> financePath(id) + "/reviews";
            default -> financePath(id) + "/authorizations";
        };
        var body = switch (kind) {
            case "PRECHECK" -> queueInput(id);
            case "REVIEW" -> reviewInput(id);
            default -> authorizeInput(id, view(id, "finance"));
        };
        String user = kind.equals("PRECHECK") ? "alice" : "finance", key = UUID.randomUUID().toString();
        var response = send(endpoint, user, key, body); var receipt = ok(response, 202);
        String origin = response.getHeader("X-Trace-Id");
        var replay = send(endpoint, user, key, body);
        assertThat(replay.getContentAsString()).isEqualTo(response.getContentAsString());
        assertThat(replay.getHeader("X-Trace-Id")).isNotEqualTo(origin);
        traceHeaders.clear();
        try (var outer = new io.agentflow.observability.DiagnosticContext(UUID.randomUUID().toString(), "caller").open()) {
            var context = org.slf4j.MDC.getCopyOfContextMap();
            switch (kind) {
                case "PRECHECK" -> worker.poll();
                case "REVIEW" -> reviewWorker.poll();
                default -> executionWorker.poll();
            }
            assertThat(org.slf4j.MDC.getCopyOfContextMap()).isEqualTo(context);
        }
        assertThat(traceHeaders).isNotEmpty().allSatisfy(trace -> assertThat(trace).isEqualTo(origin));
        String table = switch (kind) {
            case "PRECHECK" -> "budget_adjustment_check_job";
            case "REVIEW" -> "budget_adjustment_review";
            default -> "budget_adjustment_operation";
        };
        String identity = receipt.path(kind.equals("PRECHECK") ? "id" : kind.equals("REVIEW") ? "reviewId" : "operationId").asText();
        assertThat(jdbc.queryForObject("SELECT trace_id FROM " + table + " WHERE tenant_id='demo' AND id=?", String.class, identity)).isEqualTo(origin);
        assertThat(org.slf4j.MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test void actualApprovalReadAndExplicitAuthorizationApplyExactlyTheCurrentTwoSidedLedger() throws Exception {
        UUID id = approved(); assertThat(calls.get("budget-adjustment-command")).isNull();
        assertThat(view(id, "finance").at("/actions/review").asBoolean()).isTrue();
        ledgerLimit = "1200.01";
        String reviewKey = UUID.randomUUID().toString(); var reviewBody = reviewInput(id);
        var firstReview = send(financePath(id) + "/reviews", "finance", reviewKey, reviewBody); var queued = ok(firstReview, 202);
        assertThat(firstReview.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(send(financePath(id) + "/reviews", "finance", reviewKey, reviewBody).getContentAsString()).isEqualTo(firstReview.getContentAsString());
        assertThat(view(id, "finance").at("/review/status").asText()).isEqualTo("QUEUED");
        assertThat(view(id, "alice").path("review").isNull()).isTrue();
        reviewWorker.poll(); var ready = view(id, "finance");
        assertThat(ready.at("/review/status").asText()).isEqualTo("READY");
        assertThat(ready.at("/review/positions/0/beforeLimit/value").asText()).isEqualTo("1200.01");
        assertThat(ready.at("/review/positions/0/proposedLimit/value").asText()).isEqualTo("1130.01");
        assertThat(ready.at("/review/positions/1/proposedLimit/value").asText()).isEqualTo("1270.01");
        assertThat(current(id).currentRound().ledger().position("budget-source").limit()).isEqualTo(money("1000"));
        assertThat(calls.get("budget-adjustment-command")).isNull();
        String key = UUID.randomUUID().toString(); var input = authorizeInput(id, ready);
        var receipt = send(financePath(id) + "/authorizations", "finance", key, input); UUID operation = operationId(ok(receipt, 202));
        assertThat(send(financePath(id) + "/authorizations", "finance", key, input).getContentAsString()).isEqualTo(receipt.getContentAsString());
        assertThat(view(id, "finance").at("/review/status").asText()).isEqualTo("CONSUMED");
        assertThat(view(id, "alice").at("/operation/status").asText()).isEqualTo("QUEUED");
        executionWorker.poll(); executionWorker.poll();
        var done = view(id, "finance"); assertThat(done.at("/operation/status").asText()).isEqualTo("APPLIED");
        assertThat(done.at("/operation/observation/status").asText()).isEqualTo("APPLIED");
        assertThat(done.at("/actions/retire").asBoolean()).isFalse(); assertThat(done.at("/actions/review").asBoolean()).isFalse();
        assertThat(calls.get("budget-adjustment-command").get()).isEqualTo(1);
        assertThat(operations.find("demo", operation).orElseThrow().command().authorizedBy()).isEqualTo("finance");
        assertThat(reviews.find("demo", UUID.fromString(queued.path("reviewId").asText())).orElseThrow().consumedOperationId()).isEqualTo(operation);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE aggregate_type='BudgetAdjustmentExecution' AND application_id=?", Integer.class, app(id).id().toString())).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT payload_json FROM audit_event WHERE aggregate_type='BudgetAdjustmentExecution' AND application_id=?", String.class, app(id).id().toString())).allSatisfy(value -> assertThat(value).doesNotContain("targetDigest", "commandDigest", "ledger", "positions"));
        assertPrivate(done); assertPrivate(view(id, "alice"));
    }

    @Test void currentAppointmentAndOriginalSensitiveFieldsAreRequiredEvenBeforeIdempotentReplay() throws Exception {
        UUID id = approved(); String key = UUID.randomUUID().toString(); var body = reviewInput(id);
        var first = send(financePath(id) + "/reviews", "finance", key, body); ok(first, 202);
        organization.updateAppointment(admin, financeAppointment, false, 1);
        error(send(financePath(id) + "/reviews", "finance", key, body), 403, "FORBIDDEN");
        assertThat(view(id, "finance").path("review").isNull()).isTrue(); assertThat(view(id, "finance").at("/actions/review").asBoolean()).isFalse();
        organization.updateAppointment(admin, financeAppointment, true, 2);
        assertThat(send(financePath(id) + "/reviews", "finance", key, body).getContentAsString()).isEqualTo(first.getContentAsString());
        for (String subject : List.of("alice", "manager", "cashier", "admin", "bob")) assertThat(send(financePath(id) + "/reviews", subject, body).getStatus()).isBetween(400, 499);
        assertThat(read(financePath(id), "admin").getStatus()).isEqualTo(403);
        assertThat(read(financePath(id), "bob").getStatus()).isEqualTo(404);
        jdbc.update("UPDATE organization_person SET approval_eligible=FALSE WHERE tenant_id='demo' AND id=?", finance.toString());
        try {
            error(send(financePath(id) + "/reviews", "finance", key, body), 403, "FORBIDDEN");
            assertThat(read(financePath(id) + "/history", "finance").getStatus()).isEqualTo(403);
        } finally { jdbc.update("UPDATE organization_person SET approval_eligible=TRUE WHERE tenant_id='demo' AND id=?", finance.toString()); }
    }

    @Test void strictInputsRejectInventedFactsWrongScopeAndStaleDisplayedVersions() throws Exception {
        UUID draft = create(); assertThat(view(draft, "alice").path("roundNo").asInt()).isEqualTo(1);
        UUID id = approved();
        for (String query : List.of("roundNo=0", "roundNo=01", "roundNo=2147483648", "employeeId=alice", "operationId=bad")) error(read(financePath(id) + "?" + query, "finance"), 400, "INVALID_BUDGET_ADJUSTMENT_QUERY");
        for (String query : List.of("limit=0", "limit=51", "limit=01", "before=bad", "tenantId=other")) error(read(financePath(id) + "/history?" + query, "finance"), 400, "INVALID_BUDGET_ADJUSTMENT_QUERY");
        var forged = new java.util.HashMap<>(reviewInput(id)); forged.put("ledger", Map.of("limit", "999999"));
        assertThat(send(financePath(id) + "/reviews", "finance", forged).getStatus()).isEqualTo(400);
        forged = new java.util.HashMap<>(reviewInput(id)); forged.put("applicationVersion", app(id).version() - 1);
        error(send(financePath(id) + "/reviews", "finance", forged), 409, "CONCURRENCY_CONFLICT");
        queueAndRead(id); var ready = view(id, "finance");
        forged = new java.util.HashMap<>(authorizeInput(id, ready)); forged.put("changes", List.of());
        assertThat(send(financePath(id) + "/authorizations", "finance", forged).getStatus()).isEqualTo(400);
        forged = new java.util.HashMap<>(authorizeInput(id, ready)); forged.put("reviewVersion", 1);
        error(send(financePath(id) + "/authorizations", "finance", forged), 422, "BUDGET_ADJUSTMENT_REVIEW_UNAVAILABLE");
        UUID operation = authorize(id); forged = new java.util.HashMap<>(action(view(id, "finance"), "RETIRE")); forged.put("alreadyApplied", true);
        assertThat(send(actionUrl(operation), "finance", forged).getStatus()).isEqualTo(400);
        error(send(actionUrl(operation), "finance", Map.of("action", "RETIRE", "operationVersion", 99, "comment", "过期页面")), 409, "CONCURRENCY_CONFLICT");
        error(read(financePath(id) + "?operationId=" + UUID.randomUUID(), "finance"), 404, "NOT_FOUND");
        assertThat(operations.find("foreign", operation)).isEmpty();
    }

    @Test void onlyLatestOwnReviewMayBeConsumedAndForeignRequestReviewCannotBeSubstituted() throws Exception {
        UUID id = approved(); queueAndRead(id); var old = authorizeInput(id, view(id, "finance"));
        queueAndRead(id); error(send(financePath(id) + "/authorizations", "finance", old), 409, "CONCURRENCY_CONFLICT");
        UUID other = approved(); queueAndRead(other); var foreign = new java.util.HashMap<>(authorizeInput(id, view(id, "finance")));
        foreign.put("reviewId", view(other, "finance").at("/review/id").asText());
        error(send(financePath(id) + "/authorizations", "finance", foreign), 409, "CONCURRENCY_CONFLICT");
        UUID operation = authorize(id);
        error(read(financePath(other) + "?operationId=" + operation, "finance"), 404, "NOT_FOUND");
        error(send(financePath(id) + "/reviews", "finance", reviewInput(id)), 409, "BUDGET_ADJUSTMENT_ALREADY_AUTHORIZED");
        assertThat(operations.activeForRequest("demo", id)).isPresent();
    }

    @Test void lostResponseQueriesOriginalNumberAndOnlyExplicitNotFoundRetryResendsIdenticalCommand() throws Exception {
        UUID id = approved(); queueAndRead(id); UUID operation = authorize(id);
        responder = (kind, request) -> kind.equals("budget-adjustment-command") ? malformedCommand(request) : normal(kind, request);
        executionWorker.poll(); assertThat(view(id, "finance").at("/operation/status").asText()).isEqualTo("UNKNOWN");
        error(send(actionUrl(operation), "finance", action(view(id, "finance"), "RETIRE")), 409, "BUDGET_ADJUSTMENT_RETIREMENT_UNSAFE");
        ok(send(actionUrl(operation), "finance", action(view(id, "finance"), "QUERY")), 202);
        responder = (kind, request) -> kind.equals("budget-adjustment-query") ? envelope(request, observation(operation, BudgetAdjustmentObservation.Status.NOT_FOUND, null)) : normal(kind, request);
        executionWorker.poll(); var missing = view(id, "finance"); assertThat(missing.at("/operation/status").asText()).isEqualTo("NOT_FOUND");
        assertThat(missing.at("/actions/retry").asBoolean()).isTrue(); assertThat(missing.at("/actions/retire").asBoolean()).isFalse();
        executionWorker.poll(); assertThat(calls.get("budget-adjustment-command").get()).isEqualTo(1);
        var retry = action(missing, "RETRY"); String key = UUID.randomUUID().toString();
        var first = send(actionUrl(operation), "finance", key, retry); ok(first, 202);
        assertThat(send(actionUrl(operation), "finance", key, retry).getContentAsString()).isEqualTo(first.getContentAsString());
        responder = this::normal; executionWorker.poll();
        assertThat(view(id, "finance").at("/operation/status").asText()).isEqualTo("APPLIED");
        assertThat(calls.get("budget-adjustment-command").get()).isEqualTo(2); assertThat(commandBytes).hasSize(1);
        assertThat(calls.get("budget-adjustment-query").get()).isEqualTo(1);
    }

    @Test void contradictionNeverClearsAppliedEvidenceOrAllowsReplacement() throws Exception {
        UUID id = approved(); queueAndRead(id); UUID operation = authorize(id); executionWorker.poll();
        var original = view(id, "finance").at("/operation/observation");
        ok(send(actionUrl(operation), "finance", action(view(id, "finance"), "QUERY")), 202);
        responder = (kind, request) -> kind.equals("budget-adjustment-query") ? envelope(request, observation(operation, BudgetAdjustmentObservation.Status.REJECTED, BudgetAdjustmentObservation.Rejection.LEDGER_VERSION_CONFLICT)) : normal(kind, request);
        executionWorker.poll(); var disputed = view(id, "finance");
        assertThat(disputed.at("/operation/status").asText()).isEqualTo("RECONCILING");
        assertThat(disputed.at("/operation/observation")).isEqualTo(original);
        assertThat(disputed.at("/operation/conflictingObservation/status").asText()).isEqualTo("REJECTED");
        assertThat(disputed.at("/actions/retry").asBoolean()).isFalse(); assertThat(disputed.at("/actions/retire").asBoolean()).isFalse();
        error(send(actionUrl(operation), "finance", action(disputed, "RETIRE")), 409, "BUDGET_ADJUSTMENT_RETIREMENT_UNSAFE");
        ok(send(actionUrl(operation), "finance", action(disputed, "QUERY")), 202); responder = this::normal; executionWorker.poll();
        assertThat(view(id, "finance").at("/operation/status").asText()).isEqualTo("RECONCILING");
        assertThat(operations.activeForRequest("demo", id)).isPresent(); assertThat(calls.get("budget-adjustment-command").get()).isEqualTo(1);
    }

    @Test void safelyRetiredNeverSentOperationKeepsHistoryAndReplacementNeedsAnotherFreshReview() throws Exception {
        UUID id = approved(); queueAndRead(id); var oldReview = authorizeInput(id, view(id, "finance")); UUID first = authorize(id);
        var input = action(view(id, "finance"), "RETIRE"); String key = UUID.randomUUID().toString();
        var receipt = send(actionUrl(first), "finance", key, input); ok(receipt, 202);
        assertThat(send(actionUrl(first), "finance", key, input).getContentAsString()).isEqualTo(receipt.getContentAsString());
        var ended = view(id, "finance"); assertThat(ended.at("/operation/status").asText()).isEqualTo("VOIDED");
        assertThat(ended.at("/operation/retirement/basis").asText()).isEqualTo("NEVER_SENT");
        assertThat(ended.at("/actions/review").asBoolean()).isTrue(); assertThat(ended.at("/actions/authorize").asBoolean()).isFalse();
        executionWorker.poll(); assertThat(calls.get("budget-adjustment-command")).isNull();
        error(send(financePath(id) + "/authorizations", "finance", oldReview), 422, "BUDGET_ADJUSTMENT_REVIEW_UNAVAILABLE");
        queueAndRead(id); UUID second = authorize(id); assertThat(second).isNotEqualTo(first);
        assertThat(view(id, "finance").at("/operation/id").asText()).isEqualTo(second.toString());
        assertThat(ok(read(financePath(id) + "?operationId=" + first, "finance"), 200).at("/operation/retirement/basis").asText()).isEqualTo("NEVER_SENT");
        var page = ok(read(financePath(id) + "/history?limit=1", "finance"), 200); assertThat(page.path("items").size()).isEqualTo(1);
        var next = ok(read(financePath(id) + "/history?limit=1&before=" + page.path("nextBefore").asText(), "finance"), 200);
        assertThat(Set.of(page.at("/items/0/id").asText(), next.at("/items/0/id").asText())).isEqualTo(Set.of(first.toString(), second.toString()));
        assertThat(next.path("nextBefore").isNull()).isTrue(); assertPrivate(page); assertPrivate(next);
        executionWorker.poll(); assertThat(view(id, "alice").at("/operation/status").asText()).isEqualTo("APPLIED");
    }

    @Test void previousReturnedRoundCannotAuthorizeOrReadCurrentRoundExecutionByNumber() throws Exception {
        UUID id = create(); submit(id); approveTask(id, "finance", "APPROVE"); approveTask(id, "manager", "RETURN");
        ok(send(path(id) + "/revise", "alice", Map.of("applicationVersion", app(id).version(), "requestVersion", current(id).version(), "content", content("60"))), 200);
        submit(id); approveTask(id, "finance", "APPROVE"); approveTask(id, "manager", "APPROVE");
        assertThat(app(id).roundNo()).isEqualTo(2); queueAndRead(id); UUID operation = authorize(id);
        var old = ok(read(financePath(id) + "?roundNo=1", "finance"), 200);
        assertThat(old.path("operation").isNull()).isTrue(); assertThat(old.path("review").isNull()).isTrue(); assertThat(old.at("/actions/review").asBoolean()).isFalse();
        assertThat(ok(read(financePath(id) + "/history?roundNo=1", "finance"), 200).path("items").isEmpty()).isTrue();
        error(read(financePath(id) + "?roundNo=1&operationId=" + operation, "finance"), 404, "NOT_FOUND");
        var prior = new java.util.HashMap<>(reviewInput(id)); prior.put("roundNo", 1);
        error(send(financePath(id) + "/reviews", "finance", prior), 409, "CONCURRENCY_CONFLICT");
        executionWorker.poll(); assertThat(view(id, "finance").at("/operation/positions/0/proposedLimit/value").asText()).isEqualTo("940.00");
    }

    @Test void auditFailureRollsBackReviewAndAuthorizationIncludingIdempotentSuccess() throws Exception {
        UUID id = approved(); String key = UUID.randomUUID().toString(); var body = reviewInput(id);
        String constraint = "ck_budget_finance_review_audit";
        jdbc.execute("ALTER TABLE audit_event ADD CONSTRAINT " + constraint + " CHECK (application_id <> '" + app(id).id() + "' OR action <> 'BUDGET_ADJUSTMENT_REVIEW')");
        try {
            assertThatThrownBy(() -> send(financePath(id) + "/reviews", "finance", key, body)).isInstanceOf(jakarta.servlet.ServletException.class).hasRootCauseInstanceOf(java.sql.SQLException.class);
            assertThat(reviews.latest("demo", id, "finance")).isEmpty();
        } finally { jdbc.execute("ALTER TABLE audit_event DROP CONSTRAINT " + constraint); }
        ok(send(financePath(id) + "/reviews", "finance", key, body), 202); reviewWorker.poll();
        var input = authorizeInput(id, view(id, "finance")); String authKey = UUID.randomUUID().toString();
        constraint = "ck_budget_finance_authorize_audit";
        jdbc.execute("ALTER TABLE audit_event ADD CONSTRAINT " + constraint + " CHECK (application_id <> '" + app(id).id() + "' OR action <> 'BUDGET_ADJUSTMENT_AUTHORIZE')");
        try {
            assertThatThrownBy(() -> send(financePath(id) + "/authorizations", "finance", authKey, input)).isInstanceOf(jakarta.servlet.ServletException.class).hasRootCauseInstanceOf(java.sql.SQLException.class);
            assertThat(operations.activeForRequest("demo", id)).isEmpty(); assertThat(view(id, "finance").at("/review/status").asText()).isEqualTo("READY");
        } finally { jdbc.execute("ALTER TABLE audit_event DROP CONSTRAINT " + constraint); }
        ok(send(financePath(id) + "/authorizations", "finance", authKey, input), 202);
    }

    @Test void competingAuthorizationCreatesExactlyOneCommandAndOneConsumption() throws Exception {
        UUID id = approved(); queueAndRead(id); var input = authorizeInput(id, view(id, "finance")); var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> { await(start); return send(financePath(id) + "/authorizations", "finance", input); });
            var b = pool.submit(() -> { await(start); return send(financePath(id) + "/authorizations", "finance", input); });
            start.countDown();
            assertThat(List.of(a.get(20, TimeUnit.SECONDS).getStatus(), b.get(20, TimeUnit.SECONDS).getStatus())).containsExactlyInAnyOrder(202, 422);
        } finally { pool.shutdownNow(); }
        assertThat(operations.list("demo", id, null, 10)).hasSize(1);
        assertThat(reviews.latest("demo", id, "finance").orElseThrow().status()).isEqualTo(BudgetAdjustmentReview.Status.CONSUMED);
    }

    @Test void failedFreshReadAndChangedDestinationCannotTurnApprovalIntoAuthorization() throws Exception {
        UUID id = approved(); responder = (kind, request) -> kind.equals("budget-ledger") ? "{}" : normal(kind, request);
        ok(send(financePath(id) + "/reviews", "finance", reviewInput(id)), 202); reviewWorker.poll();
        assertThat(view(id, "finance").at("/review/status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(view(id, "finance").at("/actions/authorize").asBoolean()).isFalse();
        responder = this::normal; queueAndRead(id); var input = authorizeInput(id, view(id, "finance"));
        configuration.getTenants().get("demo").setEndpoint(ENDPOINT + "-changed");
        assertThat(view(id, "finance").path("destinationReady").asBoolean()).isFalse();
        error(send(financePath(id) + "/authorizations", "finance", input), 422, "BUDGET_ADJUSTMENT_DESTINATION_UNAVAILABLE");
        assertThat(operations.activeForRequest("demo", id)).isEmpty();
    }

    @Test void budgetAdjustmentNotificationFailedReviewKeepsOriginalAttemptAfterFreshAuthorization() throws Exception {
        var id = approved(); responder = (kind, request) -> kind.equals("budget-ledger") ? "{}" : normal(kind, request);
        var failed = UUID.fromString(ok(send(financePath(id) + "/reviews", "finance", reviewInput(id)), 202).path("reviewId").asText()); reviewWorker.poll();
        assertThat(noticeRecipients("REVIEW", failed, "REVIEW_UNAVAILABLE")).containsExactlyInAnyOrder("alice", "finance");
        var target = noticePath("REVIEW", failed, "REVIEW_UNAVAILABLE", "alice"); var before = ok(read(target, "alice"), 200);
        assertThat(before.at("/review/status").asText()).isEqualTo("UNAVAILABLE"); assertThat(before.path("operation").isNull()).isTrue();
        responder = this::normal; queueAndRead(id); authorize(id); executionWorker.poll();
        assertThat(ok(read(target, "alice"), 200)).isEqualTo(before);
    }

    @Test void budgetAdjustmentNotificationUnknownAndAppliedAreDistinctFactsOnOriginalOperation() throws Exception {
        var id = approved(); queueAndRead(id); var operation = authorize(id);
        responder = (kind, request) -> kind.equals("budget-adjustment-command") ? malformedCommand(request) : normal(kind, request); executionWorker.poll();
        assertThat(noticeRecipients("OPERATION", operation, "UNKNOWN")).containsExactlyInAnyOrder("alice", "finance");
        var unknown = noticePath("OPERATION", operation, "UNKNOWN", "alice"); var response = read(unknown, "alice"); assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(ok(response, 200).at("/operation/status").asText()).isEqualTo("UNKNOWN");
        ok(send(actionUrl(operation), "finance", action(view(id, "finance"), "QUERY")), 202); responder = this::normal; executionWorker.poll();
        assertThat(noticeRecipients("OPERATION", operation, "APPLIED")).containsExactlyInAnyOrder("alice", "finance");
        var updated = ok(read(unknown, "alice"), 200); assertThat(updated.path("fact").asText()).isEqualTo("UNKNOWN");
        assertThat(updated.at("/operation/id").asText()).isEqualTo(operation.toString()); assertThat(updated.at("/operation/status").asText()).isEqualTo("APPLIED");
        assertThat(updated.toString()).doesNotContain("ledger", "commandDigest", "targetDigest", "beforeLimit", "afterLimit", "changes", "actions");
        assertThat(calls.get("budget-adjustment-command").get()).isEqualTo(1);
    }

    @Test void budgetAdjustmentNotificationConflictKeepsBothOriginalObservations() throws Exception {
        var id = approved(); queueAndRead(id); var operation = authorize(id); executionWorker.poll();
        ok(send(actionUrl(operation), "finance", action(view(id, "finance"), "QUERY")), 202);
        responder = (kind, request) -> kind.equals("budget-adjustment-query") ? envelope(request, observation(operation, BudgetAdjustmentObservation.Status.REJECTED, BudgetAdjustmentObservation.Rejection.LEDGER_VERSION_CONFLICT)) : normal(kind, request); executionWorker.poll();
        assertThat(noticeRecipients("OPERATION", operation, "RECONCILING")).containsExactlyInAnyOrder("alice", "finance");
        var target = ok(read(noticePath("OPERATION", operation, "RECONCILING", "alice"), "alice"), 200);
        assertThat(target.at("/operation/observation/outcome").asText()).isEqualTo("APPLIED"); assertThat(target.at("/operation/conflictingObservation/outcome").asText()).isEqualTo("REJECTED");
        assertThat(target.path("retirement").isNull()).isTrue(); assertThat(operations.activeForRequest("demo", id)).isPresent();
    }

    @Test void budgetAdjustmentNotificationRetirementKeepsOriginalCommandAfterReplacementAndReplay() throws Exception {
        var id = approved(); queueAndRead(id); var operation = authorize(id); var input = action(view(id, "finance"), "RETIRE"); String key = UUID.randomUUID().toString();
        var response = send(actionUrl(operation), "finance", key, input); ok(response, 202);
        assertThat(send(actionUrl(operation), "finance", key, input).getContentAsString()).isEqualTo(response.getContentAsString());
        assertThat(noticeRecipients("OPERATION", operation, "RETIRED")).containsExactlyInAnyOrder("alice", "finance");
        var target = noticePath("OPERATION", operation, "RETIRED", "alice"); var original = ok(read(target, "alice"), 200);
        assertThat(original.at("/retirement/basis").asText()).isEqualTo("NEVER_SENT"); assertThat(original.at("/operation/status").asText()).isEqualTo("VOIDED");
        queueAndRead(id); var replacement = authorize(id); executionWorker.poll(); assertThat(replacement).isNotEqualTo(operation);
        assertThat(ok(read(target, "alice"), 200)).isEqualTo(original);
        assertThat(calls.get("budget-adjustment-command").get()).isEqualTo(1);
    }

    @Test void budgetAdjustmentNotificationScopeHistoryAndDeduplicationProtectOriginalMessage() throws Exception {
        var id = approved(); queueAndRead(id); var operation = authorize(id);
        responder = (kind, request) -> kind.equals("budget-adjustment-command") ? malformedCommand(request) : normal(kind, request); executionWorker.poll();
        var target = noticePath("OPERATION", operation, "UNKNOWN", "finance"); var message = UUID.fromString(target.split("/")[4]);
        var before = operations.find("demo", operation).orElseThrow(); int externalCalls = calls.values().stream().mapToInt(AtomicInteger::get).sum();
        ok(read(target, "finance"), 200); for (String user : List.of("alice", "admin", "cashier", "bob")) assertThat(read(target, user).getStatus()).isIn(403, 404);
        assertThat(read(target + "?requestId=" + id, "finance").getStatus()).isEqualTo(400);
        actors.set(new Actor("demo", "finance", Set.of("ADMIN")));
        try { assertThatThrownBy(() -> noticeAccess.target(message)).isInstanceOf(io.agentflow.common.DomainException.class); } finally { actors.clear(); }
        actors.set(new Actor("foreign", "finance", Set.of("FINANCE", "APPROVER")));
        try { assertThatThrownBy(() -> noticeAccess.target(message)).isInstanceOf(io.agentflow.common.DomainException.class); } finally { actors.clear(); }
        String event = "budget-adjustment:OPERATION:" + operation + ":UNKNOWN";
        jdbc.update("UPDATE notification_inbox SET event_key=? WHERE id=?", "budget-adjustment:OPERATION:" + operation + ":NOT_FOUND", message.toString());
        try { assertThat(read(target, "finance").getStatus()).isEqualTo(404); } finally { jdbc.update("UPDATE notification_inbox SET event_key=? WHERE id=?", event, message.toString()); }
        long version = before.command().source().approvedRequestVersion(); String raw = jdbc.queryForObject("SELECT state_json FROM budget_adjustment_revision WHERE tenant_id='demo' AND request_id=? AND request_version=?", String.class, id.toString(), version);
        var altered = (com.fasterxml.jackson.databind.node.ObjectNode) json.read(raw, JsonNode.class); altered.put("tenantId", "foreign");
        jdbc.update("UPDATE budget_adjustment_revision SET state_json=? WHERE tenant_id='demo' AND request_id=? AND request_version=?", altered.toString(), id.toString(), version);
        try { assertThat(read(target, "finance").getStatus()).isEqualTo(404); } finally { jdbc.update("UPDATE budget_adjustment_revision SET state_json=? WHERE tenant_id='demo' AND request_id=? AND request_version=?", raw, id.toString(), version); }
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx -> events.publishEvent(new BudgetAdjustmentOperationChanged(before, null)));
        assertThat(noticeRecipients("OPERATION", operation, "UNKNOWN")).containsExactlyInAnyOrder("alice", "finance");
        assertThat(operations.find("demo", operation)).contains(before); assertThat(calls.values().stream().mapToInt(AtomicInteger::get).sum()).isEqualTo(externalCalls);
        assertThat(jdbc.queryForObject("SELECT read_at FROM notification_inbox WHERE id=?", java.sql.Timestamp.class, message.toString())).isNull();
    }

    @Test void budgetAdjustmentNotificationBlockedAndRevokedReviewsRemainIndependentAttempts() throws Exception {
        var id = approved(); ledgerLimit = "760";
        var blocked = UUID.fromString(ok(send(financePath(id) + "/reviews", "finance", reviewInput(id)), 202).path("reviewId").asText()); reviewWorker.poll();
        assertThat(noticeRecipients("REVIEW", blocked, "REVIEW_BLOCKED")).containsExactlyInAnyOrder("alice", "finance");
        assertThat(ok(read(noticePath("REVIEW", blocked, "REVIEW_BLOCKED", "alice"), "alice"), 200).path("operation").isNull()).isTrue();
        ledgerLimit = "1000"; var stopped = UUID.fromString(ok(send(financePath(id) + "/reviews", "finance", reviewInput(id)), 202).path("reviewId").asText());
        jdbc.update("UPDATE organization_appointment SET active=FALSE WHERE tenant_id='demo' AND id=?", financeAppointment.toString());
        try {
            reviewWorker.poll(); assertThat(noticeRecipients("REVIEW", stopped, "REVIEW_SOURCE_CHANGED")).containsExactly("alice");
            assertThat(ok(read(noticePath("REVIEW", stopped, "REVIEW_SOURCE_CHANGED", "alice"), "alice"), 200).at("/review/status").asText()).isEqualTo("VOIDED");
            assertThat(operations.activeForRequest("demo", id)).isEmpty();
        } finally { jdbc.update("UPDATE organization_appointment SET active=TRUE WHERE tenant_id='demo' AND id=?", financeAppointment.toString()); }
    }

    @Test void budgetAdjustmentNotificationNotFoundRejectionAndExpiryNeverInventAdjustment() throws Exception {
        var id = approved(); queueAndRead(id); var operation = authorize(id);
        responder = (kind, request) -> kind.equals("budget-adjustment-command") ? malformedCommand(request) : normal(kind, request); executionWorker.poll();
        ok(send(actionUrl(operation), "finance", action(view(id, "finance"), "QUERY")), 202);
        responder = (kind, request) -> kind.equals("budget-adjustment-query") ? envelope(request, observation(operation, BudgetAdjustmentObservation.Status.NOT_FOUND, null)) : normal(kind, request); executionWorker.poll();
        assertThat(noticeRecipients("OPERATION", operation, "NOT_FOUND")).containsExactlyInAnyOrder("alice", "finance");
        ok(send(actionUrl(operation), "finance", action(view(id, "finance"), "RETRY")), 202);
        responder = (kind, request) -> kind.equals("budget-adjustment-command") ? envelope(request, observation(operation, BudgetAdjustmentObservation.Status.REJECTED, BudgetAdjustmentObservation.Rejection.LEDGER_VERSION_CONFLICT)) : normal(kind, request); executionWorker.poll();
        assertThat(noticeRecipients("OPERATION", operation, "REJECTED")).containsExactlyInAnyOrder("alice", "finance");
        assertThat(ok(read(noticePath("OPERATION", operation, "NOT_FOUND", "alice"), "alice"), 200).at("/operation/status").asText()).isEqualTo("REJECTED");
        responder = this::normal; var second = approved(); queueAndRead(second); var expiredId = authorize(second); var queued = operations.find("demo", expiredId).orElseThrow();
        assertThat(financeExecution.claim("demo", expiredId, queued.command().expiresAt())).isNull();
        assertThat(noticeRecipients("OPERATION", expiredId, "EXPIRED")).containsExactlyInAnyOrder("alice", "finance");
        assertThat(ok(read(noticePath("OPERATION", expiredId, "EXPIRED", "alice"), "alice"), 200).at("/operation/observation").isNull()).isTrue();
    }

    @Test void budgetAdjustmentNotificationFailureRollsBackResultAndDispatchThenRevocationStopsDelivery() throws Exception {
        var id = approved(); queueAndRead(id); var operation = authorize(id); var claimed = financeExecution.claim("demo", operation, Instant.now());
        var result = new io.agentflow.finance.FinanceResult.Success<>(applied(claimed.command())); String event = "budget-adjustment:OPERATION:" + operation + ":APPLIED";
        var actor = new Actor("demo", "finance", Set.of("FINANCE", "APPROVER")); var preference = notificationPreferences.get(actor); notificationPreferences.revise(actor, preference.version(), true, false);
        try {
            jdbc.execute("ALTER TABLE notification_inbox ADD CONSTRAINT budget_notice_failure CHECK (NOT (event_key='" + event + "' AND recipient_id='finance'))");
            try { assertThatThrownBy(() -> financeExecution.finish(claimed, result, Instant.now())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class); }
            finally { jdbc.execute("ALTER TABLE notification_inbox DROP CONSTRAINT budget_notice_failure"); }
            assertThat(operations.find("demo", operation)).contains(claimed); assertThat(noticeRecipients("OPERATION", operation, "APPLIED")).isEmpty();
            assertThat(operations.history("demo", operation)).hasSize(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_dispatch d JOIN notification_inbox i ON i.id=d.inbox_id WHERE i.event_key=?", Integer.class, event)).isZero();
            financeExecution.finish(claimed, result, Instant.now()); var target = noticePath("OPERATION", operation, "APPLIED", "finance");
            UUID delivery = UUID.fromString(jdbc.queryForObject("SELECT id FROM notification_dispatch WHERE inbox_id=? AND channel='EMAIL'", String.class, target.split("/")[4]));
            jdbc.update("UPDATE organization_appointment SET active=FALSE WHERE tenant_id='demo' AND id=?", financeAppointment.toString());
            assertThat(read(target, "finance").getStatus()).isEqualTo(404); assertThat(notificationDeliveries.claim(delivery, Instant.now())).isNull();
            assertThat(notificationDeliveryStore.find(delivery).orElseThrow().progress().errorCode().name()).isEqualTo("MESSAGE_UNAVAILABLE");
        } finally {
            jdbc.update("UPDATE organization_appointment SET active=TRUE WHERE tenant_id='demo' AND id=?", financeAppointment.toString());
            var latest = notificationPreferences.get(actor); notificationPreferences.revise(actor, latest.version(), preference.emailEnabled(), preference.enterpriseImEnabled());
        }
    }

    @Test void budgetAdjustmentNotificationFailureRollsBackSafeRetirementAndReviewFailure() throws Exception {
        var id = approved(); queueAndRead(id); var operation = authorize(id); var queued = operations.find("demo", operation).orElseThrow();
        String event = "budget-adjustment:OPERATION:" + operation + ":RETIRED";
        jdbc.execute("ALTER TABLE notification_inbox ADD CONSTRAINT budget_retire_notice_failure CHECK (NOT (event_key='" + event + "' AND recipient_id='finance'))");
        try { assertThatThrownBy(() -> new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx -> financeExecution.retire("demo", operation, queued.version(), "finance", Instant.now()))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class); }
        finally { jdbc.execute("ALTER TABLE notification_inbox DROP CONSTRAINT budget_retire_notice_failure"); }
        assertThat(operations.find("demo", operation)).contains(queued); assertThat(operations.retirement("demo", operation)).isEmpty();
        assertThat(noticeRecipients("OPERATION", operation, "RETIRED")).isEmpty(); assertThat(noticeRecipients("OPERATION", operation, "VOIDED")).isEmpty();
        ok(send(actionUrl(operation), "finance", action(view(id, "finance"), "RETIRE")), 202);
        var review = UUID.fromString(ok(send(financePath(id) + "/reviews", "finance", reviewInput(id)), 202).path("reviewId").asText()); var claimed = financeReviews.claim("demo", review, Instant.now());
        String failedEvent = "budget-adjustment:REVIEW:" + review + ":REVIEW_UNAVAILABLE";
        jdbc.execute("ALTER TABLE notification_inbox ADD CONSTRAINT budget_review_notice_failure CHECK (NOT (event_key='" + failedEvent + "' AND recipient_id='finance'))");
        try { assertThatThrownBy(() -> financeReviews.fail(claimed, Instant.now())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class); }
        finally { jdbc.execute("ALTER TABLE notification_inbox DROP CONSTRAINT budget_review_notice_failure"); }
        assertThat(reviews.find("demo", review)).contains(claimed); assertThat(noticeRecipients("REVIEW", review, "REVIEW_UNAVAILABLE")).isEmpty();
        financeReviews.fail(claimed, Instant.now()); assertThat(noticeRecipients("REVIEW", review, "REVIEW_UNAVAILABLE")).containsExactlyInAnyOrder("alice", "finance");
    }

    private List<String> noticeRecipients(String type, UUID id, String fact) {
        return jdbc.queryForList("SELECT recipient_id FROM notification_inbox WHERE tenant_id='demo' AND event_key=?", String.class, "budget-adjustment:" + type + ":" + id + ":" + fact);
    }
    private String noticePath(String type, UUID id, String fact, String recipient) {
        String message = jdbc.queryForObject("SELECT id FROM notification_inbox WHERE tenant_id='demo' AND recipient_id=? AND event_key=?", String.class, recipient, "budget-adjustment:" + type + ":" + id + ":" + fact);
        return "/api/v1/notifications/" + message + "/budget-adjustment-target";
    }

    private UUID approved() throws Exception { UUID id = create(); submit(id); approveTask(id, "finance", "APPROVE"); approveTask(id, "manager", "APPROVE"); assertThat(app(id).status()).isEqualTo(ApplicationStatus.APPROVED); return id; }
    private void approveTask(UUID id, String user, String action) throws Exception { ok(send(actionPath(id), user, decision(id, action)), 200); }
    private String financePath(UUID id) { return path(id) + "/execution"; }
    private String actionUrl(UUID id) { return "/api/v1/budget-adjustment-operations/" + id + "/actions"; }
    private JsonNode view(UUID id, String user) throws Exception { var response = read(financePath(id), user); assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store"); return ok(response, 200); }
    private Map<String, Object> reviewInput(UUID id) { return Map.of("roundNo", app(id).roundNo(), "applicationVersion", app(id).version(), "requestVersion", current(id).version(), "comment", "复核原批准及最新台账"); }
    private Map<String, Object> authorizeInput(UUID id, JsonNode view) { var input = new java.util.HashMap<>(reviewInput(id)); input.put("reviewId", view.at("/review/id").asText()); input.put("reviewVersion", view.at("/review/version").asLong()); return input; }
    private UUID queueAndRead(UUID id) throws Exception { var receipt = ok(send(financePath(id) + "/reviews", "finance", reviewInput(id)), 202); reviewWorker.poll(); assertThat(view(id, "finance").at("/review/status").asText()).isEqualTo("READY"); return UUID.fromString(receipt.path("reviewId").asText()); }
    private UUID authorize(UUID id) throws Exception { return operationId(ok(send(financePath(id) + "/authorizations", "finance", authorizeInput(id, view(id, "finance"))), 202)); }
    private UUID operationId(JsonNode node) { return UUID.fromString(node.path("operationId").asText()); }
    private Map<String, Object> action(JsonNode view, String action) { return Map.of("action", action, "operationVersion", view.at("/operation/version").asLong(), "comment", "人工核对原指令状态"); }
    private void error(MockHttpServletResponse response, int expected, String code) throws Exception { assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected); code(response, code); }
    private void assertPrivate(JsonNode value) { assertThat(value.toString()).doesNotContain("targetDigest", "commandDigest", "approvedRequestVersion", "consumedOperationId", "leaseUntil"); }
    private UUID create() throws Exception { return id(ok(send("/api/v1/budget-adjustments", "alice", createBody(published(), content("70"))), 201)); }
    private Map<String, Object> createBody(DefinitionDraft definition, BudgetAdjustmentContent content) { return Map.of("businessNo", "BUDGET-" + UUID.randomUUID(), "processKey", definition.key(), "definitionVersion", definition.version(), "content", content); }
    private BudgetAdjustmentContent content(String amount) { return new BudgetAdjustmentContent(entity, "合成预算调拨", "按业务需要调整额度", BudgetAdjustmentContent.Type.TRANSFER, LocalDate.parse("2026-09-29"), "budget-source", "budget-target", money(amount)); }
    private Map<String, Object> submission(UUID id, UUID checked) { return Map.of("applicationVersion", app(id).version(), "requestVersion", current(id).version(), "precheckId", checked); }
    private Map<String, Object> queueInput(UUID id) { return Map.of("applicationVersion", app(id).version(), "requestVersion", current(id).version(), "initiatorAppointmentId", appointment, "targetDigest", configuration.destination("demo").orElseThrow().digest("demo")); }
    private UUID enqueue(UUID id) throws Exception { return id(ok(send(path(id) + "/prechecks", "alice", queueInput(id)), 202)); }
    private UUID ready(UUID id) throws Exception { UUID checked = enqueue(id); worker.poll(); assertThat(check(checked).status()).as(String.valueOf(check(checked).result())).isEqualTo(Status.READY); return checked; }
    private void submit(UUID id) throws Exception { ok(send(path(id) + "/submit", "alice", submission(id, ready(id))), 200); }
    private BudgetAdjustmentRequest current(UUID id) { return requests.find("demo", id).orElseThrow(); }
    private Application app(UUID id) { return applications.findById("demo", current(id).applicationId()).orElseThrow(); }
    private BudgetAdjustmentCheck check(UUID id) { return checks.find("demo", id).orElseThrow(); }
    private String path(UUID id) { return "/api/v1/budget-adjustments/" + id; }
    private String actionPath(UUID id) { return "/api/v1/tasks/" + tasks.createTaskQuery().processVariableValueEquals("applicationId", app(id).id().toString()).singleResult().getId() + "/actions"; }
    private Map<String, Object> decision(UUID id, String action) { return Map.of("action", action, "expectedVersion", app(id).version(), "comment", "已核对本轮原预算应付"); }
    private MockHttpServletResponse send(String path, String user, Object body) throws Exception { return send(path, user, UUID.randomUUID().toString(), body); }
    private MockHttpServletResponse send(String path, String user, String key, Object body) throws Exception { return mvc.perform(post(path).header("Authorization", token(user)).header("Idempotency-Key", key).contentType("application/json").content(json.write(body))).andReturn().getResponse(); }
    private MockHttpServletResponse read(String path, String user) throws Exception { return mvc.perform(get(path).header("Authorization", token(user))).andReturn().getResponse(); }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private JsonNode ok(MockHttpServletResponse response, int status) throws Exception { assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status); return json.read(response.getContentAsString(StandardCharsets.UTF_8), JsonNode.class); }
    private void code(MockHttpServletResponse response, String code) throws Exception { assertThat(response.getStatus()).isBetween(400, 499); assertThat(json.read(response.getContentAsString(StandardCharsets.UTF_8), JsonNode.class).path("code").asText()).isEqualTo(code); }
    private UUID id(JsonNode node) { return UUID.fromString(node.path("id").asText()); }
    private static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
    private UUID person(String subject, boolean approver) {
        var found = jdbc.queryForList("SELECT id FROM organization_person WHERE tenant_id='demo' AND subject=?", String.class, subject);
        return found.isEmpty() ? organization.createPerson(admin, subject, subject, true, approver).id() : UUID.fromString(found.get(0));
    }
    private DefinitionDraft published() {
        var graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("review", "预算财务", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_" + finance)),
                new Node("finalReview", "预算主管", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_" + manager)), new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("a", "start", "review", "", false), new Edge("b", "review", "finalReview", "", false), new Edge("c", "finalReview", "end", "", false)));
        var schema = new FormSchema(2, List.of(new FormSchema.Field(BudgetAdjustmentFormContract.DETAILS, "预算调整明细", FormSchema.FieldType.TEXT, true, null,
                null, null, null, null, null, null, true, Map.of("review", FieldVisibility.READ_ONLY, "finalReview", FieldVisibility.READ_ONLY)),
                new FormSchema.Field("amount", "调整金额", FormSchema.FieldType.NUMBER, true, null, null, null, null, null),
                new FormSchema.Field("currency", "本位币", FormSchema.FieldType.TEXT, true, null, null, null, null, null)));
        var draft = definitions.create("demo", "budget-finance-test-" + UUID.randomUUID(), "预算财务合成流程", graph, schema, null);
        return definitions.publish(admin, draft.id(), draft.revision(), "预算财务接口验收");
    }
    private String normal(String operation, JsonNode request) {
        Object result = switch (operation) {
            case "catalog" -> new FinanceCatalog("alice", "synthetic-budget-v1", Instant.now().plusSeconds(600),
                    List.of(new FinanceCatalog.LegalEntity(entity, "合成法人", "CNY", false, "entity-v1", "Asia/Shanghai")), List.of(), List.of(), List.of(), List.of());
            case "budget-ledger" -> ledger(json.read(request.path("data").toString(), BudgetLedgerPort.Request.class));
            case "budget-adjustment-command" -> applied(capture(request));
            case "budget-adjustment-query" -> applied(operations.find("demo", UUID.fromString(request.at("/data/operationId").asText())).orElseThrow().command());
            default -> throw new IllegalArgumentException("Unexpected external operation from budget finance");
        };
        return envelope(request, result);
    }
    private String envelope(JsonNode request, Object value) { return json.write(Map.of("contractVersion", 1, "tenantId", request.path("tenantId").asText(), "requestId", request.path("requestId").asText(), "outcome", "SUCCESS", "data", value)); }
    private BudgetAdjustmentCommand capture(JsonNode request) {
        var command = json.read(request.at("/data/command").toString(), BudgetAdjustmentCommand.class);
        var old = commandBytes.putIfAbsent(command.id(), request.toString()); if (old != null) assertThat(request.toString()).isEqualTo(old);
        assertThat(request.at("/data/commandDigest").asText()).isEqualTo(command.digest()); return command;
    }
    private String malformedCommand(JsonNode request) { capture(request); return "{}"; }
    private BudgetAdjustmentObservation applied(BudgetAdjustmentCommand command) {
        return accepted.computeIfAbsent(command.id(), ignored -> {
            var changes = command.changes().stream().map(change -> {
                var position = command.ledger().position(change.budgetReference());
                return new BudgetAdjustmentObservation.AppliedChange(change.budgetReference(), change.expectedVersion(), "v2", position.periodReference(),
                        command.source().round().content().accountingDate(), change.beforeLimit(), change.afterLimit(), position.committed(), position.consumed());
            }).toList();
            return new BudgetAdjustmentObservation(command.id(), command.digest(), BudgetAdjustmentObservation.Status.APPLIED, 1, Instant.now(), "synthetic-atomic-budget", command.authorizedAt(), changes, null);
        });
    }
    private BudgetAdjustmentObservation observation(UUID id, BudgetAdjustmentObservation.Status status, BudgetAdjustmentObservation.Rejection rejected) {
        var command = operations.find("demo", id).orElseThrow().command();
        return new BudgetAdjustmentObservation(id, command.digest(), status, status == BudgetAdjustmentObservation.Status.NOT_FOUND ? 0 : 1, Instant.now(), null, null, List.of(), rejected);
    }
    private BudgetLedgerPort.Snapshot ledger(BudgetLedgerPort.Request request) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var positions = request.budgetReferences().stream().map(reference -> new BudgetLedgerPort.Position(request.legalEntityId(), reference, "合成预算", "v1", "2026",
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), BudgetLedgerPort.PeriodStatus.OPEN, money(ledgerLimit), money("300"), money("450"))).toList();
        return new BudgetLedgerPort.Snapshot(request, "ledger-v1", now, now.plusSeconds(300), positions);
    }
    private static void await(CountDownLatch latch) { try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic wait timed out"); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); } }
    private static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/finance/", exchange -> {
                var active = ACTIVE.get(); String operation = exchange.getRequestURI().getPath().substring("/finance/".length());
                active.calls.computeIfAbsent(operation, key -> new AtomicInteger()).incrementAndGet();
                active.traceHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Trace-Id")));
                var request = active.json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonNode.class);
                byte[] bytes = active.responder.apply(operation, request).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length);
                try { exchange.getResponseBody().write(bytes); } finally { exchange.close(); }
            }); server.start(); return server;
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
}
