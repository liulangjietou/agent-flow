package io.agentflow.budget;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.auth.AuthService;
import io.agentflow.budget.BudgetAdjustmentFormContract;
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
 * 使用真实认证、数据库、Flowable 和回环财务 HTTP 验证预算提交、占用与审批原子性。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "agentflow.finance-gateway.enabled=true",
        "agentflow.vouchers.preparation-worker-enabled=false", "agentflow.vouchers.worker-enabled=false",
        "agentflow.invoices.verification-worker-enabled=false", "agentflow.expenses.precheck-worker-enabled=false",
        "agentflow.advance-requests.precheck-worker-enabled=false", "agentflow.expense-plans.precheck-worker-enabled=false",
        "agentflow.budget-adjustments.precheck-worker-enabled=false", "agentflow.procurement-payments.precheck-worker-enabled=false", "agentflow.budgets.worker-enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class BudgetAdjustmentWorkflowTest {
    private static final HttpServer SERVER = server();
    private static final String ENDPOINT = "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/finance";
    private static final AtomicReference<BudgetAdjustmentWorkflowTest> ACTIVE = new AtomicReference<>();
    private static final Path DIRECTORY = Path.of("/fyoung/tmp/agentflow-budget-adjustment-workflow-" + UUID.randomUUID());
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
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_BUDGET_ADJUSTMENT_WORKFLOW_URL", "jdbc:h2:mem:budget-adjustment-workflow;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_BUDGET_ADJUSTMENT_WORKFLOW_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_BUDGET_ADJUSTMENT_WORKFLOW_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_BUDGET_ADJUSTMENT_WORKFLOW_PASSWORD", ""));
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

    @BeforeEach void setup() {
        ACTIVE.set(this); configuration.setEnabled(true); configuration.getTenants().get("demo").setEndpoint(ENDPOINT);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM organization_directory WHERE tenant_id='demo'", Integer.class) == 0) organization.initialize(admin);
        entity = organization.createUnit(admin, OrganizationUnit.Kind.LEGAL_ENTITY, "合成预算法人", null, null, true).id();
        var department = organization.createUnit(admin, OrganizationUnit.Kind.DEPARTMENT, "预算部门", entity, null, true);
        var position = organization.createUnit(admin, OrganizationUnit.Kind.POSITION, "预算岗位", entity, null, true);
        appointment = organization.createAppointment(admin, person("alice", false), department.id(), position.id(), true).id();
        manager = person("manager", true); responder = this::normal;
    }
    @AfterEach void settle() {
        actors.clear(); responder = this::normal;
        for (String id : jdbc.queryForList("SELECT id FROM budget_adjustment_check_job WHERE status IN ('QUEUED','RUNNING')", String.class)) {
            var job = check(UUID.fromString(id));
            if (job.status() == Status.QUEUED) job = execution.claim("demo", job.input().id(), Instant.now());
            if (job != null) execution.finish(job, Result.unavailable("INTERNAL_ERROR"), Instant.now());
        }
    }
    @AfterAll static void closeServer() { SERVER.stop(0); }

    @Test void creationReplaysOneBindingAndRejectsClientFinancialFacts() throws Exception {
        var body = createBody(published(false, false), content("70")); String key = UUID.randomUUID().toString();
        var first = send("/api/v1/budget-adjustments", "alice", key, body); UUID id = id(ok(first, 201));
        assertThat(send("/api/v1/budget-adjustments", "alice", key, body).getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(app(id).businessReference().id()).isEqualTo(id); assertThat(app(id).businessReference().type().name()).isEqualTo("BUDGET_ADJUSTMENT");
        assertThat(current(id).content().amount()).isEqualTo(money("70")); assertThat(current(id).rounds()).isEmpty();
         assertThat(calls).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_adjustment_revision WHERE request_id=?", Integer.class, id.toString())).isEqualTo(1);
        var forged = new java.util.HashMap<>(body); forged.put("employeeId", "bob");
        assertThat(send("/api/v1/budget-adjustments", "alice", forged).getStatus()).isBetween(400, 499);
        var facts = json.read(json.write(content("70")), JsonNode.class).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) facts).put("accountReference", "client-forged-account"); forged = new java.util.HashMap<>(body); forged.put("content", facts);
        assertThat(send("/api/v1/budget-adjustments", "alice", forged).getStatus()).isBetween(400, 499);
    }

    @Test void ownershipFieldRulesAndGenericWritesCannotBypassTheBudgetBoundary() throws Exception {
        UUID id = create(), checked = ready(id);
        for (String user : List.of("bob", "manager", "admin")) {
            for (String suffix : List.of("", "/prechecks/options", "/prechecks", "/prechecks/" + checked)) assertThat(read(path(id) + suffix, user).getStatus()).isEqualTo(404);
            assertThat(send(path(id) + "/revise", user, revision(id, content("60"))).getStatus()).isEqualTo(404);
            assertThat(send(path(id) + "/submit", user, submission(id, checked)).getStatus()).isEqualTo(404);
            for (String suffix : List.of("/withdraw", "/cancel")) assertThat(send(path(id) + suffix, user, lifecycle(id)).getStatus()).isEqualTo(404);
            assertThat(ok(read("/api/v1/budget-adjustments", user), 200).path("items").isEmpty()).isTrue();
        }
        for (String query : List.of("employeeId=alice", "limit=101", "beforeId=" + UUID.randomUUID())) assertThat(read("/api/v1/budget-adjustments?" + query, "alice").getStatus()).isEqualTo(400);
        for (String action : List.of("submit", "withdraw", "cancel")) code(send("/api/v1/applications/" + app(id).id() + "/" + action, "alice", Map.of("expectedVersion", 1)), "USE_BUSINESS_ENDPOINT");
        assertThat(requests.find("foreign", id)).isEmpty(); assertThat(checks.find("foreign", checked)).isEmpty();
        UUID another = create(); assertThat(read(path(another) + "/prechecks/" + checked, "alice").getStatus()).isEqualTo(404);
        assertThat(read(path(id) + "/prechecks?employeeId=alice", "alice").getStatus()).isEqualTo(400);
        var definition = published(false, false);
        code(send("/api/v1/applications", "alice", Map.of("businessNo", "FORGE-" + UUID.randomUUID(), "processKey", definition.key(),
                "definitionVersion", definition.version(), "title", "预算入口保护", "payload", BudgetAdjustmentFormContract.draftPayload())), "USE_BUSINESS_ENDPOINT");
    }

    @Test void noHumanPathOrMaskedApproverCannotStartBudgetApproval() throws Exception {
        for (var definition : List.of(published(true, false), published(false, true))) {
            UUID id = id(ok(send("/api/v1/budget-adjustments", "alice", createBody(definition, content("70"))), 201));
            var response = send(path(id) + "/submit", "alice", submission(id, ready(id)));
            assertThat(response.getStatus()).isEqualTo(422); assertThat(current(id).rounds()).isEmpty();
             assertThat(app(id).status()).isEqualTo(ApplicationStatus.DRAFT);
        }
    }

    @Test void doubleVersionsAndNewestAttemptAreRequiredAndQueueReplayDoesNotDuplicate() throws Exception {
        UUID id = create(); var request = queueInput(id); String key = UUID.randomUUID().toString();
        var first = send(path(id) + "/prechecks", "alice", key, request); UUID old = id(ok(first, 202));
        assertThat(send(path(id) + "/prechecks", "alice", key, request).getContentAsString()).isEqualTo(first.getContentAsString());
        code(send(path(id) + "/prechecks", "alice", request), "BUDGET_ADJUSTMENT_CHECK_ACTIVE"); worker.poll();
        UUID latest = enqueue(id); code(send(path(id) + "/submit", "alice", submission(id, old)), "PRECHECK_SUPERSEDED"); worker.poll();
        var wrong = new java.util.HashMap<>(submission(id, latest)); wrong.put("requestVersion", 9);
        code(send(path(id) + "/submit", "alice", wrong), "CONCURRENCY_CONFLICT"); wrong = new java.util.HashMap<>(submission(id, latest)); wrong.put("applicationVersion", 9);
        code(send(path(id) + "/submit", "alice", wrong), "CONCURRENCY_CONFLICT");
        ok(send(path(id) + "/submit", "alice", submission(id, latest)), 200); assertThat(current(id).rounds()).hasSize(1);
    }

    @Test void failedSubmissionRoundWriteRollsBackTheBusinessRevisionAndEngine() throws Exception {
        UUID id = create(), checked = ready(id); UUID application = app(id).id();
        jdbc.execute("ALTER TABLE approval_submission_round ADD CONSTRAINT ck_budget_adjustment_round_fixture CHECK (application_id <> '" + application + "')");
        try {
            assertThatThrownBy(() -> send(path(id) + "/submit", "alice", submission(id, checked))).isInstanceOf(jakarta.servlet.ServletException.class).hasRootCauseInstanceOf(java.sql.SQLException.class);
            assertThat(current(id).version()).isEqualTo(1); assertThat(current(id).rounds()).isEmpty();
            assertThat(app(id).version()).isEqualTo(1); assertThat(app(id).status()).isEqualTo(ApplicationStatus.DRAFT);
            assertThat(tasks.createTaskQuery().processVariableValueEquals("applicationId", application.toString()).count()).isZero();
        } finally { jdbc.execute("ALTER TABLE approval_submission_round DROP CONSTRAINT ck_budget_adjustment_round_fixture"); }
        ok(send(path(id) + "/submit", "alice", submission(id, checked)), 200);
    }

    @Test void failedApprovalRevisionRollsBackFinalTaskRoundAndBusinessApproval() throws Exception {
        UUID id = create(); submit(id); ok(act(id, "APPROVE"), 200); long before = app(id).version(); String task = actionPath(id);
        jdbc.execute("ALTER TABLE budget_adjustment_revision ADD CONSTRAINT ck_budget_adjustment_approval_fixture CHECK (request_id <> '" + id + "' OR operation <> 'APPROVE')");
        try {
            assertThatThrownBy(() -> act(id, "APPROVE")).isInstanceOf(jakarta.servlet.ServletException.class).hasRootCauseInstanceOf(java.sql.SQLException.class);
            assertThat(app(id).version()).isEqualTo(before); assertThat(app(id).status()).isEqualTo(ApplicationStatus.IN_APPROVAL);
            assertThat(current(id).version()).isEqualTo(2); assertThat(current(id).approval()).isNull(); assertThat(actionPath(id)).isEqualTo(task);
            assertThat(jdbc.queryForObject("SELECT status FROM approval_submission_round WHERE application_id=? AND round_no=1", String.class, app(id).id().toString())).isEqualTo("IN_APPROVAL");

        } finally { jdbc.execute("ALTER TABLE budget_adjustment_revision DROP CONSTRAINT ck_budget_adjustment_approval_fixture"); }
        ok(act(id, "APPROVE"), 200); assertThat(current(id).approval()).isNotNull();
    }

    @Test void concurrentWorkersClaimOnlyOneReadAndTimeoutNeverReplaysAClaimedJob() throws Exception {
        UUID id = create(), checked = enqueue(id); var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> { await(start); worker.poll(); }); var b = pool.submit(() -> { await(start); worker.poll(); });
            start.countDown(); a.get(15, TimeUnit.SECONDS); b.get(15, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertThat(check(checked).status()).isEqualTo(Status.READY); assertThat(calls.get("catalog").get()).isEqualTo(1); assertThat(calls.get("budget-ledger").get()).isEqualTo(1);
        UUID crashed = enqueue(id); var running = execution.claim("demo", crashed, Instant.now());
        assertThat(execution.claim("demo", crashed, running.leaseUntil().minusMillis(1))).isNull();
        assertThat(execution.claim("demo", crashed, running.leaseUntil())).isNull(); worker.poll();
        execution.finish(running, check(checked).result(), Instant.now());
        assertThat(check(crashed).result().code()).isEqualTo("TIMEOUT"); assertThat(calls.get("catalog").get()).isEqualTo(1);
    }

    @Test void editingDuringExternalWaitRemainsAvailableAndInvalidatesTheOldFacts() throws Exception {
        UUID id = create(), checked = enqueue(id); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        responder = (operation, request) -> { if (operation.equals("catalog")) { entered.countDown(); await(release); } return normal(operation, request); };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var running = pool.submit(worker::poll); assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            ok(pool.submit(() -> send(path(id) + "/revise", "alice", revision(id, content("40")))).get(3, TimeUnit.SECONDS), 200);
            release.countDown(); running.get(10, TimeUnit.SECONDS);
            assertThat(check(checked).status()).isEqualTo(Status.UNAVAILABLE); assertThat(check(checked).result().code()).isEqualTo("CONTEXT_CHANGED");
            assertThat(current(id).rounds()).isEmpty();
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void changedTargetAppointmentOrExpiredEvidenceCannotBeUsedForSubmission() throws Exception {
        UUID id = create(), checked = ready(id);
        assertThat(execution.readyFailure(check(checked), current(id), check(checked).result().evidence().validUntil())).isEqualTo("FACTS_EXPIRED");
        configuration.getTenants().get("demo").setEndpoint(ENDPOINT + "-changed");
        code(send(path(id) + "/submit", "alice", submission(id, checked)), "TARGET_CHANGED"); configuration.getTenants().get("demo").setEndpoint(ENDPOINT);
        organization.updateAppointment(admin, appointment, false, 1);
        code(send(path(id) + "/submit", "alice", submission(id, checked)), "INITIATOR_CHANGED"); organization.updateAppointment(admin, appointment, true, 2);
        responder = (operation, request) -> "{}"; UUID malformed = enqueue(id); worker.poll();
        assertThat(check(malformed).status()).isEqualTo(Status.UNAVAILABLE); assertThat(check(malformed).result().code()).isEqualTo("INVALID_RESPONSE");
         assertThat(current(id).rounds()).isEmpty();
    }

    @Test void unavailableAndBusinessRejectedRemainDistinctAndDoNotCallPaymentPorts() throws Exception {
        UUID id = create(); responder = (operation, request) -> operation.equals("catalog") ? normal(operation, request)
                : json.write(Map.of("contractVersion", 1, "tenantId", "demo", "requestId", request.path("requestId").asText(), "outcome", "REJECTED", "reason", "BUDGET_POSITION_UNAVAILABLE"));
        UUID checked = enqueue(id); worker.poll(); assertThat(check(checked).status()).isEqualTo(Status.BLOCKED);
        assertThat(check(checked).result().code()).isEqualTo("BUDGET_POSITION_UNAVAILABLE");
        configuration.setEnabled(false); assertThat(ok(read(path(id) + "/prechecks/options", "alice"), 200).path("enabled").asBoolean()).isFalse();
        assertThat(send(path(id) + "/prechecks", "alice", Map.of("applicationVersion", app(id).version(), "requestVersion", current(id).version(), "initiatorAppointmentId", appointment, "targetDigest", "a".repeat(64))).getStatus()).isEqualTo(503);
        assertNoFinancialWrites(id);
    }

    @Test void onlyFinalHumanApprovalSealsTheFrozenLedgerWithoutExecutingBudgetWrites() throws Exception {
        UUID id = create(), checked = ready(id);
        var preview = check(checked).result().evidence().preview();
        var displayed = ok(read(path(id) + "/prechecks/" + checked, "alice"), 200);
        assertThat(displayed.at("/preview/positions/0/beforeLimit/value").asText()).isEqualTo("1000.00");
        assertThat(displayed.at("/preview/positions/0/proposedLimit/value").asText()).isEqualTo("930.00");
        assertThat(displayed.at("/preview/positions/1/proposedLimit/value").asText()).isEqualTo("1070.00");
        assertPrivateFactsAbsent(displayed);
        String key = UUID.randomUUID().toString();
        var submission = submission(id, checked);
        var submitted = send(path(id) + "/submit", "alice", key, submission);
        ok(submitted, 200);
        assertThat(send(path(id) + "/submit", "alice", key, submission).getContentAsString()).isEqualTo(submitted.getContentAsString());
        assertThat(current(id).currentRound().ledger()).isEqualTo(preview.ledger());
        assertThat(app(id).payload().get("amount")).isEqualTo("70.00");
        ok(act(id, "APPROVE"), 200);
        assertThat(current(id).approval()).isNull();
        assertThat(app(id).status()).isEqualTo(ApplicationStatus.IN_APPROVAL);
        String finalPath = actionPath(id), finalKey = UUID.randomUUID().toString();
        var decision = decision(id, "APPROVE");
        var approved = send(finalPath, "manager", finalKey, decision);
        ok(approved, 200);
        assertThat(send(finalPath, "manager", finalKey, decision).getContentAsString()).isEqualTo(approved.getContentAsString());
        assertThat(current(id).approval().applicationVersion()).isEqualTo(app(id).version());
        assertThat(current(id).version()).isEqualTo(3);
        assertThat(app(id).status()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(current(id).currentRound().ledger().position("budget-source").limit()).isEqualTo(money("1000"));
        for (String user : List.of("alice", "manager")) assertPrivateFactsAbsent(ok(read(path(id), user), 200));
        assertThat(read(path(id), "admin").getStatus()).isEqualTo(403);
        assertThat(send(path(id) + "/cancel", "alice", lifecycle(id)).getStatus()).isBetween(400, 499);
        assertNoFinancialWrites(id);
    }

    @Test void returnedCorrectionKeepsOldLedgerAndPermissionsWhileFreezingANewRound() throws Exception {
        UUID id = create();
        submit(id);
        var original = current(id).currentRound();
        ok(act(id, "RETURN"), 200);
        ok(send(path(id) + "/revise", "alice", revision(id, content("40"))), 200);
        assertThat(read(path(id), "manager").getStatus()).isEqualTo(404);
        assertThat(ok(read(path(id) + "?roundNo=1", "manager"), 200).at("/content/amount/value").asText()).isEqualTo("70.00");
        assertThat(read(path(id) + "?roundNo=1", "admin").getStatus()).isEqualTo(403);
        submit(id);
        ok(act(id, "APPROVE"), 200);
        ok(act(id, "APPROVE"), 200);
        assertThat(current(id).rounds().get(0)).isEqualTo(original);
        assertThat(current(id).currentRound().changes().get(0).afterLimit()).isEqualTo(money("960"));
        assertThat(current(id).approval().roundNo()).isEqualTo(2);
        assertThat(ok(read(path(id) + "?roundNo=1", "manager"), 200).has("approval")).isFalse();
        assertNoFinancialWrites(id);
    }

    @Test void withdrawalCancellationAndRejectionKeepOriginalEvidenceAndNeverAdjustTheLedger() throws Exception {
        UUID id = create();
        submit(id);
        var original = current(id).currentRound();
        ok(send(path(id) + "/withdraw", "alice", lifecycle(id)), 200);
        assertThat(app(id).status()).isEqualTo(ApplicationStatus.WITHDRAWN);
        assertThat(current(id).currentRound()).isEqualTo(original);
        assertThat(tasks.createTaskQuery().processVariableValueEquals("applicationId", app(id).id().toString()).count()).isZero();
        submit(id);
        ok(act(id, "REJECT"), 200);
        assertThat(app(id).status()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(current(id).rounds().get(0)).isEqualTo(original);
        UUID cancelled = create();
        ok(send(path(cancelled) + "/cancel", "alice", lifecycle(cancelled)), 200);
        assertThat(app(cancelled).status()).isEqualTo(ApplicationStatus.CANCELLED);
        assertThat(current(cancelled).approval()).isNull();
        assertNoFinancialWrites(id);
        assertNoFinancialWrites(cancelled);
    }

    @Test void insufficientReductionClosedPeriodAndCrossCurrencyNeverStartAnApproval() throws Exception {
        for (var content : List.of(content("250.01"), new BudgetAdjustmentContent(entity, "外币调整", "合成预算", BudgetAdjustmentContent.Type.INCREASE,
                LocalDate.parse("2026-09-29"), null, "budget-target", new Money(new BigDecimal("1"), "USD")))) {
            UUID id = id(ok(send("/api/v1/budget-adjustments", "alice", createBody(published(false, false), content)), 201));
            UUID checked = enqueue(id);
            worker.poll();
            assertThat(check(checked).status()).isEqualTo(Status.BLOCKED);
            code(send(path(id) + "/submit", "alice", submission(id, checked)), "PRECHECK_NOT_READY");
            assertThat(current(id).rounds()).isEmpty();
            assertThat(app(id).status()).isEqualTo(ApplicationStatus.DRAFT);
        }
        responder = (operation, request) -> {
            var response = json.read(normal(operation, request), JsonNode.class);
            if (operation.equals("budget-ledger")) ((com.fasterxml.jackson.databind.node.ObjectNode) response.at("/data/positions/0")).put("periodStatus", "CLOSED");
            return json.write(response);
        };
        UUID closed = create(), checked = enqueue(closed);
        worker.poll();
        assertThat(check(checked).result().code()).isEqualTo("BUDGET_PERIOD_CLOSED");
        assertThat(current(closed).rounds()).isEmpty();
        assertNoFinancialWrites(closed);
    }

    @Test void increaseAndReductionUseSinglePositionWithDerivedRoutingAndStillWaitForExternalExecution() throws Exception {
        for (var type : List.of(BudgetAdjustmentContent.Type.INCREASE, BudgetAdjustmentContent.Type.DECREASE)) {
            var content = new BudgetAdjustmentContent(entity, "单项预算调整", "追加或调减", type, LocalDate.parse("2026-09-29"),
                    type == BudgetAdjustmentContent.Type.INCREASE ? null : "budget-source", type == BudgetAdjustmentContent.Type.DECREASE ? null : "budget-target", money("10.01"));
            UUID id = id(ok(send("/api/v1/budget-adjustments", "alice", createBody(published(false, false), content)), 201));
            submit(id);
            assertThat(app(id).payload().get("amount")).isEqualTo("10.01");
            assertThat(current(id).currentRound().changes()).hasSize(1);
            assertThat(current(id).currentRound().changes().get(0).afterLimit()).isEqualTo(money(type == BudgetAdjustmentContent.Type.INCREASE ? "1010.01" : "989.99"));
            ok(act(id, "APPROVE"), 200);
            ok(act(id, "APPROVE"), 200);
            assertThat(current(id).approval()).isNotNull();
            assertThat(current(id).currentRound().ledger().positions().get(0).limit()).isEqualTo(money("1000"));
            assertNoFinancialWrites(id);
        }
    }

    @Test void copiedTemplateUsesTheChosenAppointmentsSupervisorThenFinancialReview() throws Exception {
        var department = organization.createUnit(admin, OrganizationUnit.Kind.DEPARTMENT, "主管与财务部门", entity, null, true);
        var position = organization.createUnit(admin, OrganizationUnit.Kind.POSITION, "合成审批岗位", entity, null, true);
        var supervisor = organization.createAppointment(admin, manager, department.id(), position.id(), true);
        organization.createAppointment(admin, person("finance", true), department.id(), position.id(), true);
        organization.setSupervisor(admin, appointment, supervisor.id(), 1);
        var copied = ok(send("/api/v1/process-templates/budget-adjustment/copy", "admin",
                Map.of("key", "budget-copy-" + UUID.randomUUID(), "name", "复制的已验收预算调整", "templateVersion", 1)), 200);
        var draft = definitions.get("demo", id(copied));
        assertThat(definitions.inspect("demo", draft.graph(), draft.formSchema()).errors()).contains("ASSIGNEE_NOT_AVAILABLE:finance");
        var finance = person("finance", true);
        var assigned = new Graph(draft.graph().nodes().stream().map(node -> node.id().equals("finance")
                ? new Node(node.id(), node.name(), node.type(), Map.of("assigneeRule", "role:ORG_PERSON_" + finance)) : node).toList(), draft.graph().edges());
        draft = definitions.update("demo", draft.id(), draft.name(), assigned, draft.formSchema(), draft.revision());
        var definition = definitions.publish(admin, draft.id(), draft.revision(), "预算模板联动验收");
        UUID id = id(ok(send("/api/v1/budget-adjustments", "alice", createBody(definition, content("70"))), 201)); submit(id);
        assertThat(tasks.createTaskQuery().processVariableValueEquals("applicationId", app(id).id().toString()).singleResult().getTaskDefinitionKey()).isEqualTo("supervisor");
        ok(act(id, "APPROVE"), 200);
        assertThat(tasks.createTaskQuery().processVariableValueEquals("applicationId", app(id).id().toString()).singleResult().getTaskDefinitionKey()).isEqualTo("finance");
        assertPrivateFactsAbsent(ok(read(path(id), "finance"), 200));
        ok(send(actionPath(id), "finance", decision(id, "APPROVE")), 200);
        assertThat(current(id).approval().approvedBy()).isEqualTo("finance"); assertThat(app(id).status()).isEqualTo(ApplicationStatus.APPROVED);
        assertNoFinancialWrites(id);
    }

    private UUID create() throws Exception { return id(ok(send("/api/v1/budget-adjustments", "alice", createBody(published(false, false), content("70"))), 201)); }
    private Map<String, Object> createBody(DefinitionDraft definition, BudgetAdjustmentContent content) { return Map.of("businessNo", "PROCUREMENT-" + UUID.randomUUID(), "processKey", definition.key(), "definitionVersion", definition.version(), "content", content); }
    private BudgetAdjustmentContent content(String amount) { return new BudgetAdjustmentContent(entity, "合成预算调拨", "按业务需要调整额度", BudgetAdjustmentContent.Type.TRANSFER, LocalDate.parse("2026-09-29"), "budget-source", "budget-target", money(amount)); }
    private Map<String, Object> revision(UUID id, BudgetAdjustmentContent content) { return Map.of("applicationVersion", app(id).version(), "requestVersion", current(id).version(), "content", content); }
    private Map<String, Object> lifecycle(UUID id) { return Map.of("applicationVersion", app(id).version(), "requestVersion", current(id).version(), "comment", "合成生命周期原因"); }
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
    private MockHttpServletResponse act(UUID id, String action) throws Exception { return send(actionPath(id), "manager", decision(id, action)); }
    private MockHttpServletResponse send(String path, String user, Object body) throws Exception { return send(path, user, UUID.randomUUID().toString(), body); }
    private MockHttpServletResponse send(String path, String user, String key, Object body) throws Exception { return mvc.perform(post(path).header("Authorization", token(user)).header("Idempotency-Key", key).contentType("application/json").content(json.write(body))).andReturn().getResponse(); }
    private MockHttpServletResponse read(String path, String user) throws Exception { return mvc.perform(get(path).header("Authorization", token(user))).andReturn().getResponse(); }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private JsonNode ok(MockHttpServletResponse response, int status) throws Exception { assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status); return json.read(response.getContentAsString(StandardCharsets.UTF_8), JsonNode.class); }
    private void code(MockHttpServletResponse response, String code) throws Exception { assertThat(response.getStatus()).isBetween(400, 499); assertThat(json.read(response.getContentAsString(StandardCharsets.UTF_8), JsonNode.class).path("code").asText()).isEqualTo(code); }
    private UUID id(JsonNode node) { return UUID.fromString(node.path("id").asText()); }
    private static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
    private void assertPrivateFactsAbsent(JsonNode node) { assertThat(node.toString()).doesNotContain("targetDigest", "legalEntities", "costCenters", "categories", "projects"); }
    private void assertNoFinancialWrites(UUID id) {
        assertThat(calls.keySet()).allMatch(value -> value.equals("catalog") || value.equals("budget-ledger"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM voucher_preparation WHERE tenant_id='demo' AND business_id=?", Integer.class, id.toString())).isZero();
    }
    private UUID person(String subject, boolean approver) {
        var found = jdbc.queryForList("SELECT id FROM organization_person WHERE tenant_id='demo' AND subject=?", String.class, subject);
        return found.isEmpty() ? organization.createPerson(admin, subject, subject, true, approver).id() : UUID.fromString(found.get(0));
    }
    private DefinitionDraft published(boolean noHuman, boolean masked) {
        var graph = noHuman ? new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()), new Node("end", "结束", NodeType.END, Map.of())), List.of(new Edge("a", "start", "end", "", false)))
                : new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()), new Node("review", "预算主管", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_" + manager)),
                new Node("finalReview", "财务复核", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_" + manager)), new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("a", "start", "review", "", false), new Edge("b", "review", "finalReview", "", false), new Edge("c", "finalReview", "end", "", false)));
        var access = noHuman ? Map.<String, FieldVisibility>of() : Map.of("review", masked ? FieldVisibility.MASKED : FieldVisibility.READ_ONLY, "finalReview", FieldVisibility.READ_ONLY);
        var schema = new FormSchema(2, List.of(new FormSchema.Field(BudgetAdjustmentFormContract.DETAILS, "预算应付明细", FormSchema.FieldType.TEXT, true, null,
                null, null, null, null, null, null, true, access), new FormSchema.Field("amount", "本次付款额", FormSchema.FieldType.NUMBER, true, null, null, null, null, null),
                new FormSchema.Field("currency", "本位币", FormSchema.FieldType.TEXT, true, null, null, null, null, null)));
        var draft = definitions.create("demo", "budget-adjustment-test-" + UUID.randomUUID(), "预算调整合成流程", graph, schema, null);
        return definitions.publish(admin, draft.id(), draft.revision(), "预算调整验收");
    }
    private String normal(String operation, JsonNode request) {
        Object result = switch (operation) {
            case "catalog" -> new FinanceCatalog("alice", "synthetic-budget-v1", Instant.now().plusSeconds(600),
                    List.of(new FinanceCatalog.LegalEntity(entity, "合成法人", "CNY", false, "entity-v1", "Asia/Shanghai")), List.of(), List.of(), List.of(), List.of());
            case "budget-ledger" -> ledger(json.read(request.path("data").toString(), BudgetLedgerPort.Request.class));
            default -> throw new IllegalArgumentException("Unexpected external write from budget approval");
        };
        return json.write(Map.of("contractVersion", 1, "tenantId", request.path("tenantId").asText(), "requestId", request.path("requestId").asText(), "outcome", "SUCCESS", "data", result));
    }
    private BudgetLedgerPort.Snapshot ledger(BudgetLedgerPort.Request request) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        var positions = request.budgetReferences().stream().map(reference -> new BudgetLedgerPort.Position(request.legalEntityId(), reference, "合成预算", "v1", "2026",
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), BudgetLedgerPort.PeriodStatus.OPEN, money("1000"), money("300"), money("450"))).toList();
        return new BudgetLedgerPort.Snapshot(request, "ledger-v1", now, now.plusSeconds(300), positions);
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
