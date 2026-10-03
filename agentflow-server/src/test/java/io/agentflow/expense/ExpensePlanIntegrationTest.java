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
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.finance.FinanceGatewayConfiguration;
import io.agentflow.finance.Money;
import io.agentflow.form.FieldVisibility;
import io.agentflow.form.FormSchema;
import io.agentflow.organization.OrganizationService;
import io.agentflow.organization.OrganizationUnit;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import static io.agentflow.definition.DefinitionModels.*;
import static io.agentflow.expense.ExpensePlanCheck.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 实际 HTTP 财务端口、认证、数据库和 Flowable 联合验收事前申请；企业数据均为合成夹具。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "agentflow.finance-gateway.enabled=true",
        "agentflow.invoices.verification-worker-enabled=false", "agentflow.expenses.precheck-worker-enabled=false",
        "agentflow.expense-plans.precheck-worker-enabled=false", "agentflow.budgets.worker-enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ExpensePlanIntegrationTest {
    private static final HttpServer SERVER = server();
    private static final String ENDPOINT = "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/finance";
    private static final AtomicReference<ExpensePlanIntegrationTest> ACTIVE = new AtomicReference<>();
    private static final Path DIRECTORY = Path.of("/fyoung/tmp/agentflow-expense-plan-" + UUID.randomUUID());
    private final Actor admin = new Actor("demo", "admin", Set.of("ADMIN", "PROCESS_ADMIN"));
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private UUID entity;
    private UUID appointment;
    private UUID manager;
    private BiFunction<String, JsonNode, String> responder;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("agentflow.attachments.directory", DIRECTORY::toString);
        registry.add("agentflow.finance-gateway.tenants.demo.endpoint", () -> ENDPOINT);
        registry.add("agentflow.finance-gateway.tenants.demo.allow-unauthenticated-loopback", () -> "true");
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_PLAN_TEST_URL", "jdbc:h2:mem:expense-plan;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_PLAN_TEST_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_PLAN_TEST_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_PLAN_TEST_PASSWORD", ""));
    }
    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @Autowired AuthService auth;
    @Autowired CurrentActor actors;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired OrganizationService organization;
    @Autowired DefinitionApplicationService definitions;
    @Autowired ApplicationRepository applications;
    @Autowired ExpensePlanRepository plans;
    @Autowired ExpenseRequestRepository requests;
    @Autowired TaskService tasks;
    @Autowired ExpensePlanCheckService execution;
    @Autowired ExpensePlanCheckWorker worker;
    @Autowired JdbcExpensePlanCheckRepository checks;
    @Autowired FinanceGatewayConfiguration configuration;
    @Autowired ExpenseConfigurationService expenseConfiguration;

    @BeforeEach void setup() {
        ACTIVE.set(this); configuration.setEnabled(true); configuration.getTenants().get("demo").setEndpoint(ENDPOINT);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM organization_directory WHERE tenant_id='demo'", Integer.class) == 0) organization.initialize(admin);
        entity = organization.createUnit(admin, OrganizationUnit.Kind.LEGAL_ENTITY, "合成事前法人", null, null, true).id();
        var department = organization.createUnit(admin, OrganizationUnit.Kind.DEPARTMENT, "合成部门", entity, null, true);
        var position = organization.createUnit(admin, OrganizationUnit.Kind.POSITION, "合成岗位", entity, null, true);
        appointment = organization.createAppointment(admin, person("alice", false), department.id(), position.id(), true).id();
        manager = person("manager", true); responder = this::normal;
    }
    @AfterEach void settle() {
        actors.clear(); responder = this::normal;
        for (String id : jdbc.queryForList("SELECT id FROM expense_plan_check_job WHERE status IN ('QUEUED','RUNNING')", String.class)) {
            var job = check(UUID.fromString(id));
            if (job.status() == Status.QUEUED) job = execution.claim("demo", job.input().id(), Instant.now());
            if (job != null) execution.finish(job, Result.unavailable("INTERNAL_ERROR"), Instant.now());
        }
        clearExpenseConfiguration();
    }
    @AfterAll static void closeServer() { SERVER.stop(0); }

    @Test void createReplaysOneBindingAndPreservesExactOriginalMoneyWithoutCredit() throws Exception {
        var definition = published(false, false); var body = createBody(definition, content("100")); String key = UUID.randomUUID().toString();
        var first = send("/api/v1/expense-plans", "alice", key, body); var created = ok(first, 201); UUID id = id(created);
        assertThat(send("/api/v1/expense-plans", "alice", key, body).getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(created.at("/content/lines/0/amount/value").asText()).isEqualTo("100.00");
        assertThat(app(id).businessReference().type().name()).isEqualTo("EXPENSE_PLAN");
        assertThat(app(id).payload()).isEqualTo(ExpensePlanFormContract.draftPayload());
        assertThat(requests.find("demo", id)).isEmpty(); assertThat(current(id).rounds()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_plan_revision WHERE plan_id=?", Integer.class, id.toString())).isEqualTo(1);
        var forged = new java.util.HashMap<String, Object>(body); forged.put("employeeId", "bob");
        assertThat(send("/api/v1/expense-plans", "alice", forged).getStatus()).isBetween(400, 499);
    }

    @Test void onlyLastActualApprovalCreatesFrozenStrictCreditAndReplayCannotDoubleGrant() throws Exception {
        UUID id = create(); UUID checked = ready(id); var preview = check(checked).result().evidence().preview();
        assertThat(preview.total()).isEqualTo(money("710", "CNY")); assertThat(current(id).version()).isEqualTo(1);
        assertThat(app(id).status()).isEqualTo(ApplicationStatus.DRAFT); assertThat(requests.find("demo", id)).isEmpty();
        assertThat(calls.keySet()).containsExactlyInAnyOrder("catalog", "exchange-rate");
        String key = UUID.randomUUID().toString(); var input = submission(id, checked); var first = send(path(id) + "/submit", "alice", key, input);
        ok(first, 200); assertThat(send(path(id) + "/submit", "alice", key, input).getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(current(id).currentRound().lines()).isEqualTo(preview.lines());
        assertThat(app(id).payload().get("amount")).isEqualTo("710.00");
        assertThat(requests.find("demo", id)).isEmpty();
        var firstApproval = ok(act(id, "APPROVE"), 200); assertThat(firstApproval.path("applicationStatus").asText()).isEqualTo("IN_APPROVAL");
        assertThat(requests.find("demo", id)).isEmpty();
        String lastPath = actionPath(id); String approvalKey = UUID.randomUUID().toString(); var decision = decision(id, "APPROVE");
        var approved = send(lastPath, "manager", approvalKey, decision); ok(approved, 200);
        assertThat(send(lastPath, "manager", approvalKey, decision).getContentAsString()).isEqualTo(approved.getContentAsString());
        var credit = requests.find("demo", id).orElseThrow();
        assertThat(credit.balance(7).available()).isEqualTo(money("710", "CNY"));
        assertThat(credit.approvedLines().get(0).toleranceFraction()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(credit.approvedLines().get(0).policyReference()).isEqualTo("APPROVAL:" + app(id).id() + ":1");
        assertThat(credit.balance(7).reservations()).isEmpty();
        assertThat(app(id).status()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(send(path(id) + "/cancel", "alice", lifecycle(id)).getStatus()).isBetween(400, 499);
        assertThat(send(lastPath, "manager", decision).getStatus()).isBetween(400, 499);
    }

    @Test void returnedRevisionPreservesOldReviewerSnapshotAndOnlyNewRoundCanGrantCredit() throws Exception {
        UUID id = create(); submit(id); var original = current(id).currentRound(); ok(act(id, "RETURN"), 200);
        assertThat(requests.find("demo", id)).isEmpty(); ok(send(path(id) + "/revise", "alice", revision(id, "50")), 200);
        assertThat(read(path(id), "manager").getStatus()).isEqualTo(404);
        var old = ok(read(path(id) + "?roundNo=1", "manager"), 200);
        assertThat(old.at("/content/lines/0/amount/value").asText()).isEqualTo("100.00");
        assertThat(read(path(id) + "?roundNo=1", "admin").getStatus()).isEqualTo(403);
        submit(id); ok(act(id, "APPROVE"), 200); ok(act(id, "APPROVE"), 200);
        assertThat(current(id).rounds().get(0)).isEqualTo(original);
        assertThat(requests.find("demo", id).orElseThrow().balance(7).limit()).isEqualTo(money("355", "CNY"));
        assertThat(app(id).roundNo()).isEqualTo(2);
    }

    @Test void ownListAndEveryWriteOrPrecheckRejectAnotherIdentityAndUnknownFilters() throws Exception {
        UUID id = create(); UUID checked = ready(id);
        for (String user : List.of("bob", "admin", "manager")) {
            for (String suffix : List.of("", "/prechecks/options", "/prechecks", "/prechecks/" + checked)) assertThat(read(path(id) + suffix, user).getStatus()).isEqualTo(404);
            assertThat(send(path(id) + "/revise", user, revision(id, "99")).getStatus()).isEqualTo(404);
            assertThat(send(path(id) + "/submit", user, submission(id, checked)).getStatus()).isEqualTo(404);
            assertThat(ok(read("/api/v1/expense-plans", user), 200).path("items").isEmpty()).isTrue();
        }
        assertThat(read("/api/v1/expense-plans?employeeId=alice", "admin").getStatus()).isEqualTo(400);
        assertThat(read("/api/v1/expense-plans?limit=101", "alice").getStatus()).isEqualTo(400);
        assertThat(read("/api/v1/expense-plans?beforeId=" + UUID.randomUUID(), "alice").getStatus()).isEqualTo(400);
        assertThat(plans.find("foreign", id)).isEmpty(); assertThat(checks.find("foreign", checked)).isEmpty();
        UUID another = create(); assertThat(read(path(another) + "/prechecks/" + checked, "alice").getStatus()).isEqualTo(404);
    }

    @Test void genericApplicationWritesCannotForgeAPlanOrBypassPreparation() throws Exception {
        var definition = published(false, false); var body = createBody(definition, content("100"));
        code(send("/api/v1/applications", "alice", Map.of("businessNo", "forged-" + UUID.randomUUID(), "processKey", definition.key(),
                "definitionVersion", definition.version(), "title", "伪造计划", "payload", ExpensePlanFormContract.draftPayload())), "USE_BUSINESS_ENDPOINT");
        UUID id = id(ok(send("/api/v1/expense-plans", "alice", body), 201));
        for (String action : List.of("submit", "withdraw", "cancel")) code(send("/api/v1/applications/" + app(id).id() + "/" + action, "alice", Map.of("expectedVersion", 1)), "USE_BUSINESS_ENDPOINT");
        code(send(path(id) + "/submit", "alice", submission(id, UUID.randomUUID())), "NOT_FOUND");
        assertThat(app(id).status()).isEqualTo(ApplicationStatus.DRAFT); assertThat(requests.find("demo", id)).isEmpty();
    }

    @Test void noHumanPathOrMaskedReviewCannotSubmitAndNoRoundSurvivesFailure() throws Exception {
        for (var definition : List.of(published(true, false), published(false, true))) {
            UUID id = id(ok(send("/api/v1/expense-plans", "alice", createBody(definition, content("100"))), 201));
            UUID checked = ready(id); var response = send(path(id) + "/submit", "alice", submission(id, checked));
            assertThat(tree(response).path("code").asText()).isIn("EXPENSE_PLAN_REVIEW_REQUIRED", "EXPENSE_PLAN_REVIEW_FIELDS_REQUIRED");
            assertThat(current(id).version()).isEqualTo(1); assertThat(current(id).rounds()).isEmpty();
            assertThat(app(id).status()).isEqualTo(ApplicationStatus.DRAFT); assertThat(tasks.createTaskQuery().processVariableValueEquals("applicationId", app(id).id().toString()).count()).isZero();
        }
    }

    @Test void bothVersionsMatterAndLaterAttemptInvalidatesEarlierReady() throws Exception {
        UUID id = create(); UUID first = ready(id); UUID second = ready(id);
        assertThat(ok(read(path(id) + "/prechecks/options", "alice"), 200).path("latestPrecheckId").asText()).isEqualTo(second.toString());
        code(send(path(id) + "/submit", "alice", submission(id, first)), "PRECHECK_SUPERSEDED");
        var stale = submission(id, second); ok(send(path(id) + "/revise", "alice", revision(id, "50")), 200);
        code(send(path(id) + "/submit", "alice", stale), "CONCURRENCY_CONFLICT");
        assertThat(ok(read(path(id) + "/prechecks/" + second, "alice"), 200).path("usable").asBoolean()).isFalse();
        code(send(path(id) + "/revise", "alice", Map.of("applicationVersion", app(id).version(), "planVersion", 1, "content", content("30"))), "CONCURRENCY_CONFLICT");
        assertThat(current(id).content().lines().get(0).amount()).isEqualTo(money("50", "USD"));
    }

    @Test void withdrawResubmitAndRejectNeverCreateAnAllowance() throws Exception {
        UUID id = create(); submit(id); var original = current(id).currentRound();
        ok(send(path(id) + "/withdraw", "alice", lifecycle(id)), 200);
        assertThat(app(id).status()).isEqualTo(ApplicationStatus.WITHDRAWN); submit(id);
        assertThat(current(id).rounds().get(0)).isEqualTo(original); assertThat(app(id).roundNo()).isEqualTo(2);
        ok(act(id, "REJECT"), 200); assertThat(requests.find("demo", id)).isEmpty();
        UUID cancelled = create(); ok(send(path(cancelled) + "/cancel", "alice", lifecycle(cancelled)), 200);
        assertThat(ok(read(path(cancelled), "alice"), 200).path("editable").asBoolean()).isFalse();
        assertThat(requests.find("demo", cancelled)).isEmpty();
    }

    @Test void claimedCrashTimesOutWithoutResendingAndLateSuccessCannotOverwriteIt() throws Exception {
        UUID id = create(); UUID jobId = enqueue(id); var running = execution.claim("demo", jobId, Instant.now());
        assertThat(execution.claim("demo", jobId, running.leaseUntil().minusMillis(1))).isNull();
        assertThat(execution.claim("demo", jobId, running.leaseUntil())).isNull(); worker.poll();
        assertThat(calls).isEmpty(); assertThat(check(jobId).result().code()).isEqualTo("TIMEOUT");
        UUID retry = ready(id); execution.finish(running, check(retry).result(), Instant.now());
        assertThat(check(jobId).status()).isEqualTo(Status.UNAVAILABLE); assertThat(check(jobId).result().code()).isEqualTo("TIMEOUT");
    }

    @Test void cancellingACorrectedPlanKeepsTheOwnersLastDraftAndTheReviewersOriginalRoundSeparate() throws Exception {
        UUID id = create(); submit(id); ok(act(id, "RETURN"), 200);
        ok(send(path(id) + "/revise", "alice", revision(id, "40")), 200);
        ok(send(path(id) + "/cancel", "alice", lifecycle(id)), 200);
        var own = ok(read(path(id), "alice"), 200);
        assertThat(own.at("/content/lines/0/amount/value").asText()).isEqualTo("40.00");
        assertThat(own.path("editable").asBoolean()).isFalse();
        assertThat(own.path("financialRound").isMissingNode()).isTrue();
        assertThat(read(path(id), "manager").getStatus()).isEqualTo(404);
        assertThat(ok(read(path(id) + "?roundNo=1", "manager"), 200).at("/content/lines/0/amount/value").asText()).isEqualTo("100.00");
        assertThat(requests.find("demo", id)).isEmpty();
    }

    @Test void concurrentWorkersIssueOnlyOneActualCatalogAndRateRequest() throws Exception {
        UUID id = create(); UUID checked = enqueue(id); var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { await(start); worker.poll(); }); var second = pool.submit(() -> { await(start); worker.poll(); });
            start.countDown(); first.get(15, TimeUnit.SECONDS); second.get(15, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertThat(check(checked).status()).isEqualTo(Status.READY);
        assertThat(calls.get("catalog").get()).isEqualTo(1); assertThat(calls.get("exchange-rate").get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_plan_check_revision WHERE job_id=?", Integer.class, checked.toString())).isEqualTo(3);
    }

    @Test void editingDuringNetworkWaitDoesNotBlockAndInvalidatesTheReturnedFacts() throws Exception {
        UUID id = create(); UUID checked = enqueue(id); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        responder = (operation, request) -> { if (operation.equals("catalog")) { entered.countDown(); await(release); } return normal(operation, request); };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var running = pool.submit(worker::poll); assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var edited = pool.submit(() -> send(path(id) + "/revise", "alice", revision(id, "40"))).get(3, TimeUnit.SECONDS); ok(edited, 200);
            release.countDown(); running.get(10, TimeUnit.SECONDS);
            assertThat(check(checked).status()).isEqualTo(Status.UNAVAILABLE); assertThat(check(checked).result().code()).isEqualTo("CONTEXT_CHANGED");
            assertThat(current(id).version()).isEqualTo(2); assertThat(current(id).rounds()).isEmpty();
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void expiredChangedOrUnavailableFactsNeverTurnIntoApproval() throws Exception {
        UUID id = create(); UUID checked = ready(id);
        configuration.getTenants().get("demo").setEndpoint(ENDPOINT + "-changed");
        assertThat(ok(read(path(id) + "/prechecks/" + checked, "alice"), 200).path("unavailableCode").asText()).isEqualTo("TARGET_CHANGED");
        code(send(path(id) + "/submit", "alice", submission(id, checked)), "TARGET_CHANGED");
        configuration.getTenants().get("demo").setEndpoint(ENDPOINT);
        organization.updateAppointment(admin, appointment, false, 1);
        assertThat(ok(read(path(id) + "/prechecks/" + checked, "alice"), 200).path("unavailableCode").asText()).isEqualTo("INITIATOR_CHANGED");
        organization.updateAppointment(admin, appointment, true, 2);
        responder = (operation, request) -> "{}";
        UUID malformed = enqueue(id); worker.poll(); assertThat(check(malformed).status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(check(malformed).result().code()).isEqualTo("INVALID_RESPONSE");
        assertThat(requests.find("demo", id)).isEmpty(); assertThat(current(id).rounds()).isEmpty();
    }

    @Test void unavailableAndBusinessRejectedRemainDistinctAndNeverCallUnneededPorts() throws Exception {
        UUID id = create();
        responder = (operation, request) -> json.write(Map.of("contractVersion", 1, "tenantId", "demo", "requestId", request.path("requestId").asText(), "outcome", "REJECTED", "reason", "EMPLOYEE_UNAVAILABLE"));
        UUID rejected = enqueue(id); worker.poll(); assertThat(check(rejected).status()).isEqualTo(Status.BLOCKED);
        assertThat(check(rejected).result().code()).isEqualTo("EMPLOYEE_UNAVAILABLE"); assertThat(calls.keySet()).containsExactly("catalog");
        code(send(path(id) + "/submit", "alice", submission(id, rejected)), "PRECHECK_NOT_READY");
    }

    @Test void failedCreditWriteRollsBackApplicationEngineAndCompletedRound() throws Exception {
        UUID id = create(); submit(id); ok(act(id, "APPROVE"), 200); long before = app(id).version(); String task = actionPath(id);
        jdbc.execute("ALTER TABLE finance_resource ADD CONSTRAINT ck_plan_credit_fixture CHECK (id <> '" + id + "')");
        try {
            assertThatThrownBy(() -> act(id, "APPROVE")).isInstanceOf(jakarta.servlet.ServletException.class)
                    .hasRootCauseInstanceOf(java.sql.SQLException.class);
            assertThat(app(id).status()).isEqualTo(ApplicationStatus.IN_APPROVAL); assertThat(app(id).version()).isEqualTo(before);
            assertThat(actionPath(id)).isEqualTo(task); assertThat(requests.find("demo", id)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT status FROM approval_submission_round WHERE tenant_id='demo' AND application_id=? AND round_no=1", String.class, app(id).id().toString())).isEqualTo("IN_APPROVAL");
        } finally { jdbc.execute("ALTER TABLE finance_resource DROP CONSTRAINT ck_plan_credit_fixture"); }
        ok(act(id, "APPROVE"), 200); assertThat(requests.find("demo", id)).isPresent();
    }

    @Test void enablingManagedCategoriesInvalidatesAnExistingLegacyReadyPlan() throws Exception {
        UUID id = create(); UUID checked = ready(id); long before = app(id).version();
        configureCategories(false);
        var view = ok(read(path(id) + "/prechecks/" + checked, "alice"), 200);
        assertThat(view.path("usable").asBoolean()).isFalse();
        assertThat(view.path("unavailableCode").asText()).isEqualTo("EXPENSE_CATEGORY_CONFIGURATION_CHANGED");
        var response = send(path(id) + "/submit", "alice", submission(id, checked));
        assertThat(response.getStatus()).isEqualTo(409);
        code(response, "EXPENSE_CATEGORY_CONFIGURATION_CHANGED");
        assertThat(app(id).version()).isEqualTo(before); assertThat(current(id).rounds()).isEmpty();
        assertThat(requests.find("demo", id)).isEmpty();
    }

    @Test void changedManagedCategoryRevisionInvalidatesReadyButPolicyOnlyPublicationDoesNot() throws Exception {
        String policyKey = configureCategories(true); UUID id = create(); UUID checked = ready(id);
        var saved = expenseConfiguration.draft("demo", policyKey);
        expenseConfiguration.saveDraft(admin, policyKey, saved.revision(), policyDefinition("只编辑费用制度"), "草稿不改变类别");
        assertThat(ok(read(path(id) + "/prechecks/" + checked, "alice"), 200).path("usable").asBoolean()).isTrue();
        expenseConfiguration.publish(admin, policyKey, 2, 1, 1, "发布仅改变费用制度");
        assertThat(ok(read(path(id) + "/prechecks/" + checked, "alice"), 200).path("usable").asBoolean()).isTrue();
        expenseConfiguration.saveCategories(admin, 1, managedCategories(false), "停用差旅类别");
        var view = ok(read(path(id) + "/prechecks/" + checked, "alice"), 200);
        assertThat(view.path("usable").asBoolean()).isFalse();
        assertThat(view.path("unavailableCode").asText()).isEqualTo("EXPENSE_CATEGORY_CONFIGURATION_CHANGED");
        code(send(path(id) + "/submit", "alice", submission(id, checked)), "EXPENSE_CATEGORY_CONFIGURATION_CHANGED");
    }

    @Test void categoryChangeDuringRateWaitCannotPersistAReadyPlan() throws Exception {
        configureCategories(true); UUID id = create(); UUID checked = enqueue(id);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        responder = (operation, request) -> { if (operation.equals("exchange-rate")) { entered.countDown(); await(release); } return normal(operation, request); };
        var pool = Executors.newSingleThreadExecutor();
        try {
            var running = pool.submit(worker::poll); assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            expenseConfiguration.saveCategories(admin, 1, managedCategories(false), "外部等待期间停用差旅");
            release.countDown(); running.get(10, TimeUnit.SECONDS);
            assertThat(check(checked).status()).isEqualTo(Status.UNAVAILABLE);
            assertThat(check(checked).result().code()).isEqualTo("EXPENSE_CATEGORY_CONFIGURATION_CHANGED");
            assertThat(current(id).rounds()).isEmpty();
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void policyOnlyPublicationStillSubmitsAndApprovalKeepsTheOriginalCategoryRevision() throws Exception {
        String key = configureCategories(true); UUID id = create(); UUID checked = ready(id);
        assertThat(check(checked).result().evidence().preview().managedCategoryRevision()).isEqualTo(1L);
        expenseConfiguration.saveDraft(admin, key, 1, policyDefinition("新版费用金额制度"), "类别目录保持不变");
        expenseConfiguration.publish(admin, key, 2, 1, 1, "仅发布费用制度版本");
        ok(send(path(id) + "/submit", "alice", submission(id, checked)), 200);
        var frozen = current(id).currentRound(); assertThat(frozen.managedCategoryRevision()).isEqualTo(1L);
        expenseConfiguration.saveCategories(admin, 1, managedCategories(false), "新申请停用差旅");
        ok(act(id, "APPROVE"), 200); ok(act(id, "APPROVE"), 200);
        assertThat(current(id).currentRound()).isEqualTo(frozen);
        assertThat(ok(read(path(id), "alice"), 200).at("/financialRound/managedCategoryRevision").asLong()).isEqualTo(1L);
        assertThat(requests.find("demo", id).orElseThrow().balance(7).limit()).isEqualTo(money("710", "CNY"));
        assertThat(calls.keySet()).containsExactlyInAnyOrder("catalog", "exchange-rate");
    }

    @Test void submitWaitsForCategoryWriterAndRejectsItsSupersededPreview() throws Exception {
        configureCategories(true); UUID id = create(); UUID checked = ready(id); var input = submission(id, checked);
        var held = new CountDownLatch(1); var release = new CountDownLatch(1); var entered = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var writer = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                expenseConfiguration.saveCategories(admin, 1, managedCategories(false), "并发停用差旅");
                held.countDown(); await(release);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            var submission = pool.submit(() -> { entered.countDown(); return send(path(id) + "/submit", "alice", input); });
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> submission.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); writer.get(10, TimeUnit.SECONDS);
            var response = submission.get(10, TimeUnit.SECONDS);
            assertThat(response.getStatus()).isEqualTo(409); code(response, "EXPENSE_CATEGORY_CONFIGURATION_CHANGED");
            assertThat(current(id).rounds()).isEmpty(); assertThat(app(id).status()).isEqualTo(ApplicationStatus.DRAFT);
            assertThat(requests.find("demo", id)).isEmpty();
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void historicalPrecheckWithoutCategoryRevisionRemainsReadableAsUnmanaged() throws Exception {
        UUID id = create(); UUID checked = ready(id); var evidence = check(checked).result().evidence();
        var legacy = json.read(json.write(evidence), com.fasterxml.jackson.databind.node.ObjectNode.class);
        ((com.fasterxml.jackson.databind.node.ObjectNode) legacy.path("preview")).remove("managedCategoryRevision");
        var restored = json.read(json.write(legacy), Evidence.class);
        assertThat(restored.preview().managedCategoryRevision()).isNull();
        assertThat(restored).isEqualTo(evidence);
    }

    private String configureCategories(boolean travelActive) {
        expenseConfiguration.saveCategories(admin, 0, managedCategories(travelActive), "合成类别目录");
        String key = "plan-policy-" + UUID.randomUUID();
        expenseConfiguration.saveDraft(admin, key, 0, policyDefinition("合成事前类别制度"), "合成制度草稿");
        expenseConfiguration.publish(admin, key, 1, 1, 0, "明确启用平台类别"); return key;
    }
    private List<ExpenseCategoryCatalog.Category> managedCategories(boolean travelActive) {
        return List.of(new ExpenseCategoryCatalog.Category("TRAVEL", "差旅", List.of(ExpenseLine.Unit.ITEM), travelActive),
                new ExpenseCategoryCatalog.Category("OTHER", "其他", List.of(ExpenseLine.Unit.ITEM), true));
    }
    private ExpensePolicyDefinition policyDefinition(String name) {
        return new ExpensePolicyDefinition(name, List.of(new ExpensePolicyDefinition.Rule("allow", "合成允许规则",
                new ExpensePolicyDefinition.Match(List.of(), List.of(), List.of(), List.of(), null, null, null),
                new ExpensePolicyDefinition.Constraints(ExpensePolicyDefinition.Effect.ALLOW, null, null, null, null, List.of(), false))));
    }
    private void clearExpenseConfiguration() {
        // 仅清理本类独立测试库中的配置；历史计划快照不受当前指针影响。
        jdbc.update("UPDATE expense_configuration SET active_revision=0,active_policy_id=NULL,active_policy_version=NULL WHERE tenant_id='demo'");
        for (String table : List.of("expense_policy_activation", "expense_policy_version", "expense_policy_draft_revision",
                "expense_policy_draft", "expense_category_revision", "expense_configuration")) jdbc.update("DELETE FROM " + table + " WHERE tenant_id='demo'");
    }

    private UUID create() throws Exception { return id(ok(send("/api/v1/expense-plans", "alice", createBody(published(false, false), content("100"))), 201)); }
    private Map<String, Object> createBody(DefinitionDraft definition, ExpensePlanContent content) { return Map.of("businessNo", "PLAN-" + UUID.randomUUID(), "processKey", definition.key(), "definitionVersion", definition.version(), "content", content); }
    private ExpensePlanContent content(String amount) { return new ExpensePlanContent(entity, ExpenseContent.Type.TRAVEL, "合成差旅事前计划", List.of(new ExpensePlanContent.Line(7, "TRAVEL", LocalDate.now(), null, "SH", money(amount, "USD"), List.of(new CostAllocation("IT", null, money(amount, "USD"))), "客户现场交流"))); }
    private Map<String, Object> revision(UUID id, String amount) { return Map.of("applicationVersion", app(id).version(), "planVersion", current(id).version(), "content", content(amount)); }
    private Map<String, Object> lifecycle(UUID id) { return Map.of("applicationVersion", app(id).version(), "planVersion", current(id).version(), "comment", "测试生命周期原因"); }
    private Map<String, Object> submission(UUID id, UUID checked) { return Map.of("applicationVersion", app(id).version(), "planVersion", current(id).version(), "precheckId", checked); }
    private UUID enqueue(UUID id) throws Exception { return id(ok(send(path(id) + "/prechecks", "alice", Map.of("applicationVersion", app(id).version(), "planVersion", current(id).version(), "initiatorAppointmentId", appointment, "targetDigest", configuration.destination("demo").orElseThrow().digest("demo"))), 202)); }
    private UUID ready(UUID id) throws Exception { UUID checked = enqueue(id); worker.poll(); assertThat(check(checked).status()).as(String.valueOf(check(checked).result())).isEqualTo(Status.READY); return checked; }
    private void submit(UUID id) throws Exception { ok(send(path(id) + "/submit", "alice", submission(id, ready(id))), 200); }
    private ExpensePlan current(UUID id) { return plans.find("demo", id).orElseThrow(); }
    private Application app(UUID id) { return applications.findById("demo", current(id).applicationId()).orElseThrow(); }
    private ExpensePlanCheck check(UUID id) { return checks.find("demo", id).orElseThrow(); }
    private String path(UUID id) { return "/api/v1/expense-plans/" + id; }
    private String actionPath(UUID id) { return "/api/v1/tasks/" + tasks.createTaskQuery().processVariableValueEquals("applicationId", app(id).id().toString()).singleResult().getId() + "/actions"; }
    private Map<String, Object> decision(UUID id, String action) { return Map.of("action", action, "expectedVersion", app(id).version(), "comment", "已核对本轮计划"); }
    private MockHttpServletResponse act(UUID id, String action) throws Exception { return send(actionPath(id), "manager", decision(id, action)); }
    private MockHttpServletResponse send(String path, String user, Object body) throws Exception { return send(path, user, UUID.randomUUID().toString(), body); }
    private MockHttpServletResponse send(String path, String user, String key, Object body) throws Exception { return mvc.perform(post(path).header("Authorization", token(user)).header("Idempotency-Key", key).contentType("application/json").content(json.write(body))).andReturn().getResponse(); }
    private MockHttpServletResponse read(String path, String user) throws Exception { return mvc.perform(get(path).header("Authorization", token(user))).andReturn().getResponse(); }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private JsonNode tree(MockHttpServletResponse response) throws Exception { return json.read(response.getContentAsString(StandardCharsets.UTF_8), JsonNode.class); }
    private JsonNode ok(MockHttpServletResponse response, int status) throws Exception { assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status); return tree(response); }
    private void code(MockHttpServletResponse response, String code) throws Exception { assertThat(response.getStatus()).isBetween(400, 499); assertThat(tree(response).path("code").asText()).isEqualTo(code); }
    private UUID id(JsonNode node) { return UUID.fromString(node.path("id").asText()); }
    private static Money money(String value, String currency) { return new Money(new BigDecimal(value), currency); }
    private UUID person(String subject, boolean approver) { var found = jdbc.queryForList("SELECT id FROM organization_person WHERE tenant_id='demo' AND subject=?", String.class, subject); return found.isEmpty() ? organization.createPerson(admin, subject, subject, true, approver).id() : UUID.fromString(found.get(0)); }
    private DefinitionDraft published(boolean noHuman, boolean masked) {
        var graph = noHuman ? new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()), new Node("end", "结束", NodeType.END, Map.of())), List.of(new Edge("a", "start", "end", "", false)))
                : new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()), new Node("review", "主管", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_" + manager)),
                    new Node("finalReview", "复核", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_" + manager)), new Node("end", "结束", NodeType.END, Map.of())),
                    List.of(new Edge("a", "start", "review", "", false), new Edge("b", "review", "finalReview", "", false), new Edge("c", "finalReview", "end", "", false)));
        var access = noHuman ? Map.<String, FieldVisibility>of() : Map.of("review", masked ? FieldVisibility.MASKED : FieldVisibility.READ_ONLY, "finalReview", FieldVisibility.READ_ONLY);
        var schema = new FormSchema(2, List.of(new FormSchema.Field(ExpensePlanFormContract.DETAILS, "计划明细", FormSchema.FieldType.TEXT, true, null,
                null, null, null, null, null, null, true, access), new FormSchema.Field("amount", "计划本币额", FormSchema.FieldType.NUMBER, true, null, null, null, null, null),
                new FormSchema.Field("currency", "本位币", FormSchema.FieldType.TEXT, true, null, null, null, null, null)));
        var draft = definitions.create("demo", "plan-test-" + UUID.randomUUID(), "事前计划合成流程", graph, schema, null);
        return definitions.publish(admin, draft.id(), draft.revision(), "事前申请验收");
    }
    private String normal(String operation, JsonNode request) {
        JsonNode data = request.path("data"); Object result = switch (operation) {
            case "catalog" -> new FinanceCatalog("alice", "synthetic-plan-v1", Instant.now().plusSeconds(600), List.of(new FinanceCatalog.LegalEntity(entity, "合成法人", "CNY", false, "entity-v1", "Pacific/Kiritimati")), List.of(new FinanceCatalog.Category("TRAVEL", "差旅", List.of(ExpenseLine.Unit.ITEM))), List.of(new FinanceCatalog.CostCenter(entity, "IT", "研发")), List.of(), List.of(new FinanceCatalog.City("SH", "上海")));
            case "exchange-rate" -> new ExpenseExchangeRate(data.path("fromCurrency").asText(), data.path("toCurrency").asText(), new BigDecimal("7.1"), "synthetic-plan-rate", LocalDate.parse(data.path("rateDate").asText()));
            default -> throw new IllegalArgumentException("Unexpected finance call from an expense plan");
        };
        return json.write(Map.of("contractVersion", 1, "tenantId", request.path("tenantId").asText(), "requestId", request.path("requestId").asText(), "outcome", "SUCCESS", "data", result));
    }
    private static void await(CountDownLatch latch) { try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic wait timed out"); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); } }
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
