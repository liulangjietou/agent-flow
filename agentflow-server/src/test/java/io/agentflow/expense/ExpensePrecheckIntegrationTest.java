package io.agentflow.expense;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.observability.DiagnosticContext;
import org.slf4j.MDC;
import io.agentflow.finance.BudgetPrecheckPort;
import io.agentflow.finance.EmployeeAccountPort;
import io.agentflow.finance.EmployeeAccountSnapshot;
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.finance.FinanceGatewayConfiguration;
import io.agentflow.finance.Money;
import io.agentflow.organization.OrganizationAppointment;
import io.agentflow.organization.OrganizationService;
import io.agentflow.organization.OrganizationUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
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
import static io.agentflow.expense.ExpensePrecheckJob.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 认证、持久任务、真实 HTTP 财务适配器和原件文件的消费者回归；外部业务数据均为合成夹具。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "agentflow.finance-gateway.enabled=true",
        "agentflow.invoices.verification-worker-enabled=false", "agentflow.expenses.precheck-worker-enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ExpensePrecheckIntegrationTest {
    private static final Path DIRECTORY = Path.of("/fyoung/tmp/agentflow-expense-precheck-" + UUID.randomUUID());
    private static final HttpServer SERVER = server();
    private static final String ENDPOINT = "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/finance";
    private static final AtomicReference<BiFunction<String, JsonNode, String>> RESPONDER = new AtomicReference<>();
    private static final Map<String, AtomicInteger> CALLS = new ConcurrentHashMap<>();
    private static final AtomicReference<JsonNode> LAST_BUDGET = new AtomicReference<>();
    private static final AtomicInteger SERIAL = new AtomicInteger();
    private static final String ZONE = "Pacific/Kiritimati";
    private static final List<String> TRACES = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static JsonUtil wire;
    private final Actor admin = new Actor("demo", "admin", Set.of("ADMIN"));
    private UUID entity;
    private OrganizationAppointment appointment;
    private String invoiceNumber;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("agentflow.attachments.directory", DIRECTORY::toString);
        registry.add("agentflow.finance-gateway.tenants.demo.endpoint", () -> ENDPOINT);
        registry.add("agentflow.finance-gateway.tenants.demo.allow-unauthenticated-loopback", () -> "true");
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_PRECHECK_TEST_URL", "jdbc:h2:mem:expense-precheck;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_PRECHECK_TEST_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_PRECHECK_TEST_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_PRECHECK_TEST_PASSWORD", ""));
    }
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;
    @Autowired CurrentActor actors;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired OrganizationService organization;
    @Autowired ApplicationRepository applications;
    @Autowired ExpenseReportRepository reports;
    @Autowired InvoiceRepository invoices;
    @Autowired ExpenseRequestRepository requests;
    @Autowired EmployeeAdvanceRepository advances;
    @Autowired InvoiceWalletService wallet;
    @Autowired InvoiceVerificationService verification;
    @Autowired InvoiceVerificationWorker invoiceWorker;
    @Autowired ExpensePrecheckService execution;
    @Autowired ExpensePrecheckWorker worker;
    @Autowired ExpensePrecheckEvaluator evaluator;
    @Autowired JdbcExpensePrecheckRepository jobs;
    @Autowired JdbcInvoiceVerificationRepository verificationJobs;
    @Autowired ExpensePrecheckResources resourceSnapshots;
    @Autowired FinanceGatewayConfiguration configuration;
    @Autowired JdbcExpenseSubmissionControlRepository controls;
    @Autowired ExpenseConfigurationService expenseConfiguration;

    @BeforeEach void setup() {
        wire = json; CALLS.clear(); LAST_BUDGET.set(null);
        configuration.setEnabled(true); configuration.getTenants().get("demo").setEndpoint(ENDPOINT);
        invoiceNumber = String.format("1234567890%010d", SERIAL.incrementAndGet());
        if (jdbc.queryForObject("SELECT COUNT(*) FROM organization_directory WHERE tenant_id='demo'", Integer.class) == 0) organization.initialize(admin);
        entity = organization.createUnit(admin, OrganizationUnit.Kind.LEGAL_ENTITY, "合成预检法人", null, null, true).id();
        var department = organization.createUnit(admin, OrganizationUnit.Kind.DEPARTMENT, "合成部门", entity, null, true);
        var position = organization.createUnit(admin, OrganizationUnit.Kind.POSITION, "合成岗位", entity, null, true);
        var people = jdbc.queryForList("SELECT id FROM organization_person WHERE tenant_id='demo' AND subject='alice'", String.class);
        UUID person = people.isEmpty() ? organization.createPerson(admin, "alice", "测试员工", true, false).id() : UUID.fromString(people.get(0));
        appointment = organization.createAppointment(admin, person, department.id(), position.id(), true);
        RESPONDER.set(this::normal);
    }
    @AfterEach void settleJobs() {
        for (String id : jdbc.queryForList("SELECT id FROM expense_precheck_job WHERE status IN ('QUEUED','RUNNING')", String.class)) {
            var job = job(UUID.fromString(id));
            if (job.status() == Status.QUEUED) job = execution.claim("demo", job.input().id(), Instant.now());
            if (job != null) execution.finish(job, Result.unavailable(Stage.SYSTEM, "INTERNAL_ERROR"), Instant.now());
        }
        actors.clear();
    }
    @AfterAll static void closeServer() { SERVER.stop(0); }

    @Test
    void tracePersistsThroughQueueAndReachesFinanceGateway() throws Exception {
        var report = fixture(false).report();
        var queued = queue(report, "alice", UUID.randomUUID().toString(), input(report), 202);
        UUID id = id(queued); String expectedTrace = queued.getHeader("X-Trace-Id");
        assertThat(DiagnosticContext.validTrace(expectedTrace)).isTrue();
        assertThat(MDC.get(DiagnosticContext.TRACE_ID)).isNull();
        assertThat(jdbc.queryForMap("SELECT * FROM expense_precheck_job WHERE tenant_id='demo' AND id=?", id.toString())
                .get("TRACE_ID")).isEqualTo(expectedTrace);
        TRACES.clear();
        worker.poll();
        assertThat(job(id).status()).isEqualTo(Status.READY);
        assertThat(TRACES).isNotEmpty().containsOnly(expectedTrace);
        assertThat(jdbc.queryForMap("SELECT * FROM expense_precheck_job WHERE tenant_id='demo' AND id=?", id.toString())
                .get("TRACE_ID")).isEqualTo(expectedTrace);
        assertThat(MDC.get(DiagnosticContext.TRACE_ID)).isNull();
        assertThat(MDC.get(DiagnosticContext.TENANT_ID)).isNull();
    }

    @Test
    void blockedResultRetainsExplanationFreshnessWithoutAReadyPreview() throws Exception {
        var report = fixture(false).report();
        RESPONDER.set((operation, request) -> operation.equals("budget-precheck") ? rejected(request, "BUDGET_INSUFFICIENT") : normal(operation, request));
        UUID id = enqueue(report); worker.poll();
        var checked = job(id);
        assertThat(checked.status()).isEqualTo(Status.BLOCKED);
        assertThat(checked.result().evidence()).isNull();
        var observation = json.read(json.write(checked), JsonNode.class).at("/result/observation");
        assertThat(observation.isObject()).as("Blocked findings need their own explanation freshness evidence").isTrue();
        assertThat(observation.path("dependencyDigest").asText()).matches("[a-f0-9]{64}");
        assertThat(Instant.parse(observation.path("observedAt").asText())).isBeforeOrEqualTo(checked.completedAt());
        assertThat(Instant.parse(observation.path("validUntil").asText())).isAfter(checked.completedAt());
        assertThat(execution.explanationFailure(checked, report, Instant.now())).isNull();
        assertThat(execution.readyFailure(checked, report, Instant.now())).isEqualTo("PRECHECK_NOT_READY");
        assertThat(new JdbcExpensePrecheckRepository(jdbc, json).find("demo", id)).contains(checked);
        assertThat(tree(read(report, "/prechecks/" + id, "alice")).toString()).doesNotContain("observation", "dependencyDigest");
        assertThat(reports.find("demo", report.id()).orElseThrow().state()).isEqualTo(report.state());
        assertThat(execution.explanationFailure(checked, report, checked.result().observation().validUntil())).isEqualTo("FACTS_EXPIRED");
    }

    @Test
    void blockedExplanationBindsThePolicyObservedBeforeExternalEvaluation() throws Exception {
        try {
            publishManagedPolicy();
            RESPONDER.set((operation, request) -> operation.equals("expense-policy") ? managedAssessment(request)
                    : operation.equals("budget-precheck") ? rejected(request, "BUDGET_INSUFFICIENT") : normal(operation, request));
            var report = fixture(false).report(); UUID id = enqueue(report);
            var claimed = execution.claim("demo", id, Instant.now()); var evaluated = evaluator.evaluate(claimed);
            assertThat(evaluated.status()).isEqualTo(Status.BLOCKED);
            var observed = evaluated.observation(); assertThat(observed.policySelection().policyVersion()).isEqualTo(1);
            var previous = expenseConfiguration.draft("demo", "managed");
            expenseConfiguration.saveDraft(admin, "managed", 1, new ExpensePolicyDefinition("新制度", previous.definition().rules()), "更新合成制度");
            expenseConfiguration.publish(admin, "managed", 2, 1, 1, "发布合成制度");
            execution.finish(claimed, evaluated, Instant.now());
            var checked = job(id);
            assertThat(checked.result().observation()).isEqualTo(observed);
            assertThat(checked.status()).isEqualTo(Status.BLOCKED);
            assertThat(execution.explanationFailure(checked, report, Instant.now())).isEqualTo("POLICY_CONFIGURATION_CHANGED");
        } finally { clearExpenseConfiguration(); }
    }

    @Test
    void newPrecheckOrDraftRevisionInvalidatesBlockedExplanation() throws Exception {
        var report = fixture(false).report();
        RESPONDER.set((operation, request) -> operation.equals("budget-precheck") ? rejected(request, "BUDGET_INSUFFICIENT") : normal(operation, request));
        UUID first = enqueue(report); worker.poll(); var checked = job(first);
        UUID second = enqueue(report);
        assertThat(execution.explanationFailure(checked, report, Instant.now())).isEqualTo("PRECHECK_SUPERSEDED");
        assertThat(execution.explanationFailure(job(second), report, Instant.now())).isEqualTo("PRECHECK_NOT_FINISHED");
        worker.poll(); var latest = job(second);
        assertThat(execution.explanationFailure(latest, report, Instant.now())).isNull();
        report.revise(1, report.content()); reports.update(report, 1, "alice", "FIXTURE_REVISE");
        assertThat(execution.explanationFailure(latest, report, Instant.now())).isEqualTo("CONTEXT_CHANGED");
    }

    @Test
    void invoiceRetryInvalidatesBlockedExplanationEvenIfItFailsWithoutChangingInvoiceVersion() throws Exception {
        var fixture = fixture(true); var report = fixture.report();
        RESPONDER.set((operation, request) -> operation.equals("budget-precheck") ? rejected(request, "BUDGET_INSUFFICIENT") : normal(operation, request));
        UUID id = enqueue(report); worker.poll(); var checked = job(id);
        assertThat(checked.status()).isEqualTo(Status.BLOCKED);
        long version = invoices.find("demo", fixture.invoice()).orElseThrow().version();
        actors.set(new Actor("demo", "alice", Set.of("EMPLOYEE")));
        UUID retry;
        try { retry = verification.queue(fixture.invoice(), new InvoiceVerificationService.QueueInput(version, entity, target())).id(); }
        finally { actors.clear(); }
        assertThat(execution.explanationFailure(checked, report, Instant.now())).isEqualTo("RESOURCES_CHANGED");
        var claimed = verification.claim("demo", retry, Instant.now()); verification.fail(claimed, InvoiceVerificationJob.Failure.TIMEOUT, Instant.now());
        assertThat(invoices.find("demo", fixture.invoice()).orElseThrow().version()).isEqualTo(version);
        assertThat(execution.explanationFailure(checked, report, Instant.now())).isEqualTo("RESOURCES_CHANGED");
        UUID next = enqueue(report); worker.poll(); var nextCheck = job(next);
        assertThat(nextCheck.result().findings().get(0).code()).isEqualTo("INVOICE_VERIFICATION_REQUIRED");
        assertThat(execution.explanationFailure(nextCheck, report, Instant.now())).isNull();
    }

    @Test
    void canonicalOccupationChangesInvalidateExplanationWithoutTouchingTheSelectedInvoice() throws Exception {
        var first = fixture(true); UUID id = enqueue(first.report());
        RESPONDER.set((operation, request) -> operation.equals("budget-precheck") ? rejected(request, "BUDGET_INSUFFICIENT") : normal(operation, request));
        worker.poll(); var checked = job(id); var source = invoices.find("demo", first.invoice()).orElseThrow().state();
        assertThat(execution.explanationFailure(checked, first.report(), Instant.now())).isNull();
        var duplicate = fixture(true); var invoice = invoices.find("demo", duplicate.invoice()).orElseThrow();
        invoice.occupy(invoice.version(), new ExpenseUse(duplicate.report().id(), 1, 1), "alice", entity, Instant.now());
        invoices.update(invoice, 2, "alice", "FIXTURE_OCCUPY");
        assertThat(invoices.find("demo", first.invoice()).orElseThrow().state()).isEqualTo(source);
        assertThat(execution.explanationFailure(checked, first.report(), Instant.now())).isEqualTo("RESOURCES_CHANGED");
    }

    @Test
    void blockedExplanationExpiresWithTheEarliestReadAuthorityFact() throws Exception {
        var report = fixture(false).report(); var deadline = new AtomicReference<Instant>();
        RESPONDER.set((operation, request) -> {
            if (operation.equals("budget-precheck")) return rejected(request, "BUDGET_INSUFFICIENT");
            var response = json.read(normal(operation, request), JsonNode.class);
            if (operation.equals("expense-policy")) {
                deadline.set(Instant.now().plusSeconds(40));
                ((com.fasterxml.jackson.databind.node.ObjectNode) response.path("data")).put("validUntil", deadline.get().toString());
            }
            return response.toString();
        });
        UUID id = enqueue(report); worker.poll(); var checked = job(id);
        assertThat(checked.status()).isEqualTo(Status.BLOCKED);
        assertThat(checked.result().observation().validUntil()).isEqualTo(deadline.get());
        assertThat(execution.explanationFailure(checked, report, deadline.get().minusNanos(1))).isNull();
        assertThat(execution.explanationFailure(checked, report, deadline.get())).isEqualTo("FACTS_EXPIRED");
    }

    @Test
    void historicalResultsStayReadableWithoutInventingExplanationFreshness() throws Exception {
        var report = fixture(false).report();
        RESPONDER.set((operation, request) -> operation.equals("budget-precheck") ? rejected(request, "BUDGET_INSUFFICIENT") : normal(operation, request));
        UUID id = enqueue(report); worker.poll();
        var old = json.read(json.write(job(id)), JsonNode.class);
        ((com.fasterxml.jackson.databind.node.ObjectNode) old.path("result")).remove("observation");
        String oldJson = old.toString();
        jdbc.update("UPDATE expense_precheck_job SET state_json=? WHERE tenant_id='demo' AND id=?", oldJson, id.toString());
        jdbc.update("UPDATE expense_precheck_revision SET state_json=? WHERE tenant_id='demo' AND job_id=? AND version=3", oldJson, id.toString());
        var retained = job(id); assertThat(retained.result().observation()).isNull();
        assertThat(execution.explanationFailure(retained, report, Instant.now())).isEqualTo("PRECHECK_EXPLANATION_REFRESH_REQUIRED");
        assertThat(read(report, "/prechecks/" + id, "alice").getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT state_json FROM expense_precheck_job WHERE tenant_id='demo' AND id=?", String.class, id.toString())).isEqualTo(oldJson);
        assertThat(count("budget-precheck")).isEqualTo(1);
    }

    @Test
    void publishingManagedPolicyInvalidatesAnUnexpiredLegacyPrecheck() throws Exception {
        var report = fixture(false).report(); UUID checked = enqueue(report); worker.poll();
        assertThat(job(checked).status()).isEqualTo(Status.READY);
        assertThat(tree(read(report, "/prechecks/" + checked, "alice")).path("usable").asBoolean()).isTrue();
        try {
            expenseConfiguration.saveCategories(admin, 0, List.of(new ExpenseCategoryCatalog.Category("OFFICE", "办公", List.of(ExpenseLine.Unit.ITEM), true)), "配置合成类别");
            var definition = new ExpensePolicyDefinition("合成制度", List.of(new ExpensePolicyDefinition.Rule("office", "办公规则",
                    new ExpensePolicyDefinition.Match(List.of(entity), List.of("OFFICE"), List.of(), List.of(), null, null, "USD"),
                    new ExpensePolicyDefinition.Constraints(ExpensePolicyDefinition.Effect.ALLOW, null, null, null, null, List.of(), false))));
            expenseConfiguration.saveDraft(admin, "managed", 0, definition, "保存合成草稿");
            expenseConfiguration.publish(admin, "managed", 1, 1, 0, "启用合成制度");
            var view = tree(read(report, "/prechecks/" + checked, "alice"));
            assertThat(view.path("usable").asBoolean()).isFalse();
            assertThat(view.path("unavailableCode").asText()).isEqualTo("POLICY_CONFIGURATION_CHANGED");
            var submit = mvc.perform(post(path(report) + "/submit").header("Authorization", token("alice")).header("Idempotency-Key", UUID.randomUUID())
                    .contentType("application/json").content(json.write(new ExpenseSubmissionService.Input(1L, 1L, checked)))).andReturn().getResponse();
            assertThat(submit.getStatus()).isEqualTo(409);
            assertThat(tree(submit).path("code").asText()).isEqualTo("POLICY_CONFIGURATION_CHANGED");
            assertThat(reports.find("demo", report.id()).orElseThrow().version()).isEqualTo(1);
        } finally {
            jdbc.update("UPDATE expense_configuration SET active_revision=0,active_policy_id=NULL,active_policy_version=NULL WHERE tenant_id='demo'");
            for (var table : List.of("expense_policy_activation", "expense_policy_version", "expense_policy_draft_revision", "expense_policy_draft", "expense_category_revision", "expense_configuration")) jdbc.update("DELETE FROM " + table + " WHERE tenant_id='demo'");
        }
    }

    @Test
    void managedPrecheckFreezesExactReceiptAndRejectsSourcesThatIgnoreTheSelectedVersion() throws Exception {
        try {
            publishManagedPolicy();
            var report = fixture(false).report();
            UUID unsupported = enqueue(report); worker.poll();
            assertThat(job(unsupported).status()).isEqualTo(Status.UNAVAILABLE);
            assertThat(job(unsupported).result().findings()).anySatisfy(finding -> assertThat(finding.code()).isEqualTo("INVALID_RESPONSE"));
            RESPONDER.set((operation, request) -> operation.equals("expense-policy") ? managedAssessment(request) : normal(operation, request));
            UUID checked = enqueue(report); worker.poll();
            assertThat(job(checked).status()).isEqualTo(Status.READY);
            var selection = job(checked).result().evidence().policySelection();
            assertThat(selection).isNotNull(); assertThat(selection.policyVersion()).isEqualTo(1); assertThat(selection.categoryRevision()).isEqualTo(1);
            var frozen = job(checked).result().evidence().preview().originalLines().get(0).assessment().policy();
            assertThat(frozen.managedPolicy().selection()).isEqualTo(selection);
            assertThat(frozen.managedPolicy().ruleKey()).isEqualTo("office");
            assertThat(tree(read(report, "/prechecks/" + checked, "alice")).path("usable").asBoolean()).isTrue();
            var current = expenseConfiguration.draft("demo", "managed");
            expenseConfiguration.saveDraft(admin, "managed", current.revision(), new ExpensePolicyDefinition("修改未发布", current.definition().rules()), "仅修改草稿");
            assertThat(tree(read(report, "/prechecks/" + checked, "alice")).path("usable").asBoolean()).isTrue();
            expenseConfiguration.saveCategories(admin, 1, List.of(new ExpenseCategoryCatalog.Category("OFFICE", "办公新名称", List.of(ExpenseLine.Unit.ITEM), true)), "修改目录版本");
            assertThat(tree(read(report, "/prechecks/" + checked, "alice")).path("unavailableCode").asText()).isEqualTo("POLICY_CONFIGURATION_CHANGED");
            assertThat(job(checked).result().evidence().preview().originalLines().get(0).assessment().policy()).isEqualTo(frozen);
        } finally { clearExpenseConfiguration(); }
    }

    @Test
    void policyPublishedWhileEvaluationRunsCannotFinishReady() throws Exception {
        try {
            publishManagedPolicy();
            RESPONDER.set((operation, request) -> operation.equals("expense-policy") ? managedAssessment(request) : normal(operation, request));
            var report = fixture(false).report(); UUID id = enqueue(report);
            var claimed = execution.claim("demo", id, Instant.now()); var evaluated = evaluator.evaluate(claimed);
            assertThat(evaluated.evidence()).isNotNull();
            var draft = expenseConfiguration.draft("demo", "managed");
            expenseConfiguration.saveDraft(admin, "managed", 1, new ExpensePolicyDefinition("新发布版本", draft.definition().rules()), "变更草稿");
            expenseConfiguration.publish(admin, "managed", 2, 1, 1, "发布新版本");
            execution.finish(claimed, evaluated, Instant.now());
            assertThat(job(id).status()).isEqualTo(Status.UNAVAILABLE);
            assertThat(job(id).result().findings()).anySatisfy(finding -> assertThat(finding.code()).isEqualTo("POLICY_CONFIGURATION_CHANGED"));
        } finally { clearExpenseConfiguration(); }
    }

    @Test
    void managedCatalogCannotAddAnUnauthorizedCategoryOrUnitAndDisableTakesEffect() throws Exception {
        try {
            publishManagedPolicy();
            expenseConfiguration.saveCategories(admin, 1, List.of(
                    new ExpenseCategoryCatalog.Category("OFFICE", "受控制度办公", List.of(ExpenseLine.Unit.ITEM, ExpenseLine.Unit.NIGHT), true),
                    new ExpenseCategoryCatalog.Category("PRIVATE", "未授权类别", List.of(ExpenseLine.Unit.ITEM), true)), "更新合成类别");
            var response = mvc.perform(get("/api/v1/finance/catalog").header("Authorization", token("alice"))).andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(200);
            var catalog = tree(response); assertThat(catalog.path("categories")).hasSize(1);
            assertThat(catalog.path("categories").get(0).path("name").asText()).isEqualTo("受控制度办公");
            assertThat(catalog.path("categories").get(0).path("units")).hasSize(1);
            var previous = expenseConfiguration.categories("demo");
            expenseConfiguration.saveCategories(admin, 2, previous.categories().stream().map(category -> new ExpenseCategoryCatalog.Category(category.code(), category.name(), category.units(), false)).toList(), "停用类别");
            var report = fixture(false).report(); UUID id = enqueue(report); worker.poll();
            assertThat(job(id).status()).isEqualTo(Status.BLOCKED);
            assertThat(count("expense-policy")).isZero();
        } finally { clearExpenseConfiguration(); }
    }

    private void publishManagedPolicy() {
        expenseConfiguration.saveCategories(admin, 0, List.of(new ExpenseCategoryCatalog.Category("OFFICE", "办公", List.of(ExpenseLine.Unit.ITEM), true)), "配置合成类别");
        var definition = new ExpensePolicyDefinition("合成制度", List.of(new ExpensePolicyDefinition.Rule("office", "办公规则",
                new ExpensePolicyDefinition.Match(List.of(entity), List.of("OFFICE"), List.of(), List.of(), null, null, "USD"),
                new ExpensePolicyDefinition.Constraints(ExpensePolicyDefinition.Effect.ALLOW, null, null, null, null, List.of(), false))));
        expenseConfiguration.saveDraft(admin, "managed", 0, definition, "保存合成草稿");
        expenseConfiguration.publish(admin, "managed", 1, 1, 0, "启用合成制度");
    }

    private String managedAssessment(JsonNode request) {
        var input = json.read(request.path("data").toString(), ExpensePolicyPort.Request.class); var selected = input.managedPolicy().selection();
        var amount = input.exchangeRate().convert(input.line().claimedGross());
        var policy = new ExpensePolicySnapshot(selected.policyId(), selected.policyVersion(), amount, amount, ExpensePolicySnapshot.Decision.WITHIN_LIMIT,
                "synthetic-tax", "synthetic-policy", List.of(), new ExpensePolicyReceipt(selected, "office", "synthetic-grade-city-v1"));
        return json.write(Map.of("contractVersion", 1, "tenantId", request.path("tenantId").asText(), "requestId", request.path("requestId").asText(), "outcome", "SUCCESS",
                "data", new ExpensePolicyPort.Assessment(policy, input.exchangeRate().convert(input.line().claimedTax()), false, Instant.now().plusSeconds(600))));
    }

    private void clearExpenseConfiguration() {
        jdbc.update("UPDATE expense_configuration SET active_revision=0,active_policy_id=NULL,active_policy_version=NULL WHERE tenant_id='demo'");
        for (var table : List.of("expense_policy_activation", "expense_policy_version", "expense_policy_draft_revision", "expense_policy_draft", "expense_category_revision", "expense_configuration")) jdbc.update("DELETE FROM " + table + " WHERE tenant_id='demo'");
    }

    @Test
    void optionsLocateLatestAttemptInsteadOfFirstUuidHistoryItem() throws Exception {
        var report = fixture(false).report();
        assertThat(tree(read(report, "/precheck-options", "alice")).path("latestPrecheckId").isMissingNode()).isTrue();
        UUID original = enqueue(report); worker.poll(); var source = job(original).input();
        UUID older = new UUID(-1, -1), latest = new UUID(0, 1);
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            var oldInput = new Input(older, source.tenantId(), source.reportId(), source.applicationId(), source.employeeId(),
                    source.applicationVersion(), source.financialVersion(), source.roundNo(), 2, source.initiator(), source.accountingDate(), source.targetDigest());
            var old = ExpensePrecheckJob.queue(oldInput, now); jobs.create(old);
            old = old.start(now, now.plusSeconds(60)); jobs.update(old);
            jobs.update(old.finish(Result.unavailable(Stage.SYSTEM, "TIMEOUT"), now));
            var newestInput = new Input(latest, source.tenantId(), source.reportId(), source.applicationId(), source.employeeId(),
                    source.applicationVersion(), source.financialVersion(), source.roundNo(), 3, source.initiator(), source.accountingDate(), source.targetDigest());
            jobs.create(ExpensePrecheckJob.queue(newestInput, now));
        });
        assertThat(tree(read(report, "/prechecks?limit=1", "alice")).path("items").get(0).path("id").asText()).isEqualTo(older.toString());
        assertThat(tree(read(report, "/precheck-options", "alice")).path("latestPrecheckId").asText()).isEqualTo(latest.toString());
        assertThat(read(report, "/precheck-options", "bob").getStatus()).isEqualTo(404);
    }

    @Test
    void submissionControlPersistsActualPrecheckEvidenceAndCannotReuseReceiptAcrossRounds() throws Exception {
        var report = fixture(false).report(); UUID checked = enqueue(report); worker.poll(); var evidence = job(checked).result().evidence();
        var preview = evidence.preview(); Instant submittedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var input = new ExpenseSubmissionControl.Input("demo", report.id(), report.applicationId(), "alice", 1, 2, checked,
                job(checked).input().accountingDate(), true, Map.of("receipt", ExpenseProcessPolicy.Stage.RECEIPT, "finance", ExpenseProcessPolicy.Stage.FINANCE_REVIEW));
        var value = ExpenseSubmissionControl.submitted(input, submittedAt);
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            report.freeze(1, 1, preview.baseCurrency(), preview.account(), Map.of(1, preview.originalLines().get(0).assessment()), "alice", submittedAt);
            reports.update(report, 1, "alice", "SYNTHETIC_FREEZE");
            // 此测试验证存储边界，审批轮次为明确的合成夹具；实际 Flowable 提交由专门消费者测试验收。
            jdbc.update("""
                    INSERT INTO approval_submission_round(tenant_id,application_id,round_no,process_instance_id,definition_version,
                    title,payload_json,submitted_by,submitted_at,status)
                    VALUES('demo',?,1,?,1,'合成财务控制','{}','alice',?,'IN_APPROVAL')
                    """, report.applicationId().toString(), "synthetic-control-" + UUID.randomUUID(), java.sql.Timestamp.from(submittedAt));
            controls.create(value);
        });
        assertThat(controls.find("demo", report.id(), 1)).contains(value);
        assertThat(controls.find("foreign", report.id(), 1)).isEmpty(); assertThat(controls.find("demo", report.id(), 2)).isEmpty();
        var signed = value.receive("synthetic-task", "receipt", "manager", "合成纸件已核对", submittedAt.plusSeconds(1));
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> controls.update(signed));
        assertThat(new JdbcExpenseSubmissionControlRepository(jdbc, json).find("demo", report.id(), 1)).contains(signed);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_submission_control_revision WHERE tenant_id='demo' AND report_id=?", Integer.class, report.id().toString())).isEqualTo(2);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(transaction -> controls.update(signed))).isInstanceOf(DomainException.class);
        assertThat(reports.find("demo", report.id()).orElseThrow().version()).isEqualTo(2);
    }

    @Test
    void completeReadonlyPrecheckUsesActualPortsAndKeepsEveryFinancialResourceUnchanged() throws Exception {
        var fixture = fixture(true); var report = fixture.report();
        var source = report.state(); var invoice = invoices.find("demo", fixture.invoice()).orElseThrow().state();
        var prior = requests.find("demo", fixture.prior().id()).orElseThrow().state();
        var advance = advances.find("demo", fixture.advance().id()).orElseThrow().state();
        String key = UUID.randomUUID().toString(); var first = queue(report, "alice", key, input(report), 202); UUID id = id(first);
        assertThat(count("catalog")).isZero();
        new ExpensePrecheckWorker(new JdbcExpensePrecheckRepository(jdbc, json), execution, evaluator).poll();
        var job = job(id); assertThat(job.status()).isEqualTo(Status.READY);
        var evidence = job.result().evidence();
        assertThat(evidence.preview().approvedGross()).isEqualTo(money("710", "CNY"));
        assertThat(evidence.preview().approvedTax()).isEqualTo(money("42.60", "CNY"));
        assertThat(evidence.preview().payable()).isEqualTo(money("610", "CNY"));
        assertThat(evidence.budget().request().total()).isEqualTo(money("710", "CNY"));
        assertThat(evidence.rateDate()).isEqualTo(LocalDate.ofInstant(evidence.preview().submittedAt(), ZoneId.of(ZONE)));
        assertThat(evidence.preview().originalLines().get(0).assessment().exchangeRate().rateDate()).isEqualTo(evidence.rateDate());
        assertThat(evidence.validUntil()).isBeforeOrEqualTo(evidence.rateDate().plusDays(1).atStartOfDay(ZoneId.of(ZONE)).toInstant());
        assertThat(evidence.resources()).hasSize(3); assertThat(evidence.invoices()).hasSize(1);
        assertThat(reports.find("demo", report.id()).orElseThrow().state()).isEqualTo(source);
        assertThat(invoices.find("demo", fixture.invoice()).orElseThrow().state()).isEqualTo(invoice);
        assertThat(requests.find("demo", fixture.prior().id()).orElseThrow().state()).isEqualTo(prior);
        assertThat(advances.find("demo", fixture.advance().id()).orElseThrow().state()).isEqualTo(advance);
        assertThat(applications.findById("demo", report.applicationId()).orElseThrow().status()).isEqualTo(ApplicationStatus.DRAFT);
        assertThat(journal(id)).isEqualTo(3);
        var view = tree(read(report, "/prechecks/" + id, "alice"));
        assertThat(view.path("usable").asBoolean()).isTrue();
        assertThat(view.toString()).doesNotContain("private-account", "accountDigest", "targetDigest", "originalDigest");
        assertThat(view.at("/preview/maskedAccount").asText()).isEqualTo("****1234");
        var replay = queue(report, "alice", key, input(report), 202);
        assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(replay.getHeader("Idempotency-Replayed")).isEqualTo("true");
        worker.poll(); assertThat(count("budget-precheck")).isEqualTo(1);
    }

    @Test
    void everyRouteIsOwnerOnlyAndRejectsForgedConclusionsAndStaleInputs() throws Exception {
        var report = fixture(false).report(); var input = input(report);
        for (String user : List.of("admin", "manager", "bob")) {
            queue(report, user, UUID.randomUUID().toString(), input, 404);
            assertThat(read(report, "/precheck-options", user).getStatus()).isEqualTo(404);
            assertThat(read(report, "/prechecks", user).getStatus()).isEqualTo(404);
        }
        queue(report, "alice", UUID.randomUUID().toString(), new ExpensePrecheckService.QueueInput(2L, 1L, appointment.id(), LocalDate.now(), target()), 409);
        queue(report, "alice", UUID.randomUUID().toString(), new ExpensePrecheckService.QueueInput(1L, 1L, appointment.id(), LocalDate.now(), "0".repeat(64)), 409);
        queue(report, "alice", UUID.randomUUID().toString(), new ExpensePrecheckService.QueueInput(1L, 1L, UUID.randomUUID(), LocalDate.now(), target()), 422);
        var forged = json.map(json.write(input)); forged.put("status", "READY");
        queue(report, "alice", UUID.randomUUID().toString(), forged, 400);
        assertThat(jobs.list("demo", report.id(), null, 10)).isEmpty();
        UUID id = id(queue(report, "alice", UUID.randomUUID().toString(), input, 202));
        assertThat(read(report, "/prechecks/" + id, "admin").getStatus()).isEqualTo(404);
        assertThat(read(fixture(false).report(), "/prechecks/" + id, "alice").getStatus()).isEqualTo(404);
        for (String query : List.of("?employeeId=alice", "?limit=101", "?beforeId=1-1-1-1-1")) assertThat(read(report, "/prechecks" + query, "alice").getStatus()).isEqualTo(400);
        assertThat(read(report, "/prechecks/" + id, "alice").getHeader("Cache-Control")).isEqualTo("no-store");
        actors.set(new Actor("foreign", "alice", Set.of("ADMIN")));
        try { assertThatThrownBy(() -> execution.get(report.id(), id)).isInstanceOf(DomainException.class); } finally { actors.clear(); }
    }

    @Test
    void newerAttemptSupersedesOldReadyEvenWhenBudgetLaterRejects() throws Exception {
        var report = fixture(false).report(); UUID first = enqueue(report); worker.poll();
        assertThat(job(first).status()).isEqualTo(Status.READY);
        RESPONDER.set((operation, request) -> operation.equals("budget-precheck") ? rejected(request, "BUDGET_INSUFFICIENT") : normal(operation, request));
        UUID next = enqueue(report);
        assertThat(tree(read(report, "/prechecks/" + first, "alice")).path("unavailableCode").asText()).isEqualTo("PRECHECK_SUPERSEDED");
        worker.poll(); assertThat(job(next).status()).isEqualTo(Status.BLOCKED);
        assertThat(job(next).input().attempt()).isEqualTo(2);
        assertThat(job(next).result().findings()).containsExactly(new Finding(Stage.BUDGET, null, Nature.REJECTED, "BUDGET_INSUFFICIENT"));
        assertThat(tree(read(report, "/prechecks/" + first, "alice")).path("usable").asBoolean()).isFalse();
        var page = tree(read(report, "/prechecks?limit=1", "alice"));
        assertThat(page.path("items")).hasSize(1); assertThat(page.hasNonNull("nextBeforeId")).isTrue();
        assertThat(page.toString()).doesNotContain("preview", "private-account", "findings");
    }

    @Test
    void anotherVerificationAttemptMakesOldSuccessUnusableWithoutChangingTheInvoiceVersion() throws Exception {
        var fixture = fixture(true); var report = fixture.report(); UUID ready = enqueue(report); worker.poll();
        long version = invoices.find("demo", fixture.invoice()).orElseThrow().version();
        actors.set(new Actor("demo", "alice", Set.of("EMPLOYEE")));
        UUID retry;
        try { retry = verification.queue(fixture.invoice(), new InvoiceVerificationService.QueueInput(version, entity, target())).id(); }
        finally { actors.clear(); }
        assertThat(tree(read(report, "/prechecks/" + ready, "alice")).path("unavailableCode").asText()).isEqualTo("RESOURCES_CHANGED");
        var claimed = verification.claim("demo", retry, Instant.now()); verification.fail(claimed, InvoiceVerificationJob.Failure.TIMEOUT, Instant.now());
        assertThat(invoices.find("demo", fixture.invoice()).orElseThrow().version()).isEqualTo(version);
        UUID blocked = enqueue(report); worker.poll();
        assertThat(job(blocked).result().findings()).containsExactly(new Finding(Stage.INVOICE, 1, Nature.REJECTED, "INVOICE_VERIFICATION_REQUIRED"));
        verify(fixture.invoice()); UUID fresh = enqueue(report); worker.poll(); assertThat(job(fresh).status()).isEqualTo(Status.READY);
    }

    @Test
    void externalWaitDoesNotHoldApplicationLocksAndDraftChangeDiscardsTheResult() throws Exception {
        var report = fixture(false).report(); UUID id = enqueue(report);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        RESPONDER.set((operation, request) -> { if (operation.equals("budget-precheck")) { entered.countDown(); await(release); } return normal(operation, request); });
        var pool = Executors.newFixedThreadPool(2);
        try {
            var run = pool.submit(worker::poll); assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            pool.submit(() -> { report.revise(1, report.content()); reports.update(report, 1, "alice", "FIXTURE_REVISE"); }).get(3, TimeUnit.SECONDS);
            release.countDown(); run.get(10, TimeUnit.SECONDS);
            assertThat(job(id).status()).isEqualTo(Status.UNAVAILABLE);
            assertThat(job(id).result().findings()).containsExactly(new Finding(Stage.CONTEXT, null, Nature.UNAVAILABLE, "CONTEXT_CHANGED"));
            assertThat(reports.find("demo", report.id()).orElseThrow().version()).isEqualTo(2);
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test
    void organizationTargetAndResourceChangesInvalidatePendingOrReadyEvidence() throws Exception {
        var report = fixture(false).report(); UUID pending = enqueue(report);
        organization.updateAppointment(admin, appointment.id(), false, appointment.revision());
        worker.poll(); assertThat(job(pending).result().findings().get(0).code()).isEqualTo("INITIATOR_CHANGED");
        organization.updateAppointment(admin, appointment.id(), true, appointment.revision() + 1);
        UUID targetChange = enqueue(report); configuration.getTenants().get("demo").setEndpoint(ENDPOINT + "-changed");
        worker.poll(); assertThat(job(targetChange).result().findings().get(0).code()).isEqualTo("TARGET_CHANGED");
        configuration.getTenants().get("demo").setEndpoint(ENDPOINT);
        var fixture = fixture(true); UUID ready = enqueue(fixture.report()); worker.poll();
        var another = fixture(false).report(); var advance = advances.find("demo", fixture.advance().id()).orElseThrow();
        advance.reserve(advance.version(), new ExpenseUse(another.id(), 1, 0), money("1", "CNY")); advances.update(advance, 1, "alice", "FIXTURE_RESERVE");
        assertThat(tree(read(fixture.report(), "/prechecks/" + ready, "alice")).path("unavailableCode").asText()).isEqualTo("RESOURCES_CHANGED");
    }

    @Test
    void claimedCrashTimesOutWithoutResendAndRequiresExplicitNewAttempt() throws Exception {
        var report = fixture(false).report(); UUID id = enqueue(report);
        var running = execution.claim("demo", id, Instant.now()); assertThat(running.status()).isEqualTo(Status.RUNNING);
        assertThat(execution.claim("demo", id, running.leaseUntil().minusMillis(1))).isNull();
        assertThat(execution.claim("demo", id, running.leaseUntil())).isNull();
        assertThat(job(id).result().findings()).containsExactly(new Finding(Stage.SYSTEM, null, Nature.UNAVAILABLE, "TIMEOUT"));
        worker.poll(); assertThat(count("catalog")).isZero();
        UUID retry = enqueue(report); worker.poll(); assertThat(job(retry).status()).isEqualTo(Status.READY);
        execution.finish(running, job(retry).result(), Instant.now()); assertThat(job(id).status()).isEqualTo(Status.UNAVAILABLE);
    }

    @Test
    void twoConcurrentWorkersCannotExecuteTheSamePrecheckTwice() throws Exception {
        var report = fixture(false).report(); UUID id = enqueue(report); var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> { await(start); worker.poll(); }); var two = pool.submit(() -> { await(start); worker.poll(); });
            start.countDown(); one.get(15, TimeUnit.SECONDS); two.get(15, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertThat(job(id).status()).isEqualTo(Status.READY); assertThat(count("catalog")).isEqualTo(1); assertThat(count("budget-precheck")).isEqualTo(1); assertThat(journal(id)).isEqualTo(3);
    }

    @Test
    void linePolicyAndCatalogFailuresNeverReachBudgetAndUnavailableIsNotARejection() throws Exception {
        var report = report(new ExpenseContent(entity, ExpenseContent.Type.DAILY, "逐行检查", List.of(line(1, "FORBIDDEN", List.of(), null), line(2, "OFFICE", List.of(), null)), List.of()));
        RESPONDER.set((operation, request) -> operation.equals("expense-policy") ? rejected(request, "PRIOR_REQUEST_REQUIRED") : normal(operation, request));
        UUID id = enqueue(report); worker.poll();
        assertThat(job(id).result().findings()).containsExactly(new Finding(Stage.CATALOG, 1, Nature.REJECTED, "EXPENSE_CATEGORY_UNAVAILABLE"), new Finding(Stage.POLICY, 2, Nature.REJECTED, "PRIOR_REQUEST_REQUIRED"));
        assertThat(count("budget-precheck")).isZero();
        RESPONDER.set((operation, request) -> operation.equals("catalog") ? "{}" : normal(operation, request));
        UUID unavailable = enqueue(report); worker.poll(); assertThat(job(unavailable).status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(job(unavailable).result().findings()).containsExactly(new Finding(Stage.CATALOG, null, Nature.UNAVAILABLE, "INVALID_RESPONSE"));
    }

    @Test
    void canonicalOccupationCorruptOriginalAndMissingReceiptCannotPass() throws Exception {
        var first = fixture(true); var invoice = invoices.find("demo", first.invoice()).orElseThrow();
        invoice.occupy(invoice.version(), new ExpenseUse(first.report().id(), 1, 1), "alice", entity, Instant.now()); invoices.update(invoice, 2, "alice", "FIXTURE_OCCUPY");
        var duplicate = fixture(true); UUID occupied = enqueue(duplicate.report()); worker.poll();
        assertThat(job(occupied).result().findings()).containsExactly(new Finding(Stage.RESOURCES, null, Nature.REJECTED, "INVOICE_OCCUPIED"));
        UUID invoiceId = duplicate.invoice(); Files.write(DIRECTORY.resolve(invoices.find("demo", invoiceId).orElseThrow().originalFileId() + ".bin"), new byte[]{1, 2, 3});
        UUID corrupt = enqueue(duplicate.report()); worker.poll();
        assertThat(job(corrupt).result().findings()).containsExactly(new Finding(Stage.INVOICE, 1, Nature.UNAVAILABLE, "INVOICE_ORIGINAL_UNAVAILABLE"));
        var missing = Invoice.uploaded(UUID.randomUUID(), "demo", "alice", UUID.randomUUID(), "a".repeat(64)); invoices.create(missing, "fixture");
        missing.verified(1, facts("a".repeat(64))); invoices.update(missing, 1, "fixture", "FIXTURE_VERIFY");
        var forged = report(new ExpenseContent(entity, ExpenseContent.Type.DAILY, "没有查验任务", List.of(line(1, "OFFICE", List.of(missing.id()), null)), List.of()));
        UUID noReceipt = enqueue(forged); worker.poll();
        assertThat(job(noReceipt).result().findings()).containsExactly(new Finding(Stage.INVOICE, 1, Nature.REJECTED, "INVOICE_VERIFICATION_REQUIRED"));
    }

    @Test
    void externalEvaluationRefusesAnEnclosingDatabaseTransaction() throws Exception {
        var report = fixture(false).report(); UUID id = enqueue(report); var running = execution.claim("demo", id, Instant.now());
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> evaluator.evaluate(running))).isInstanceOf(IllegalStateException.class);
        assertThat(count("catalog")).isZero();
    }

    @Test
    void returnedApplicationPrechecksSecondRoundWithoutMovingExistingReservations() throws Exception {
        var fixture = fixture(true); var report = fixture.report(); UUID first = enqueue(report); worker.poll();
        var preview = job(first).result().evidence().preview();
        var assessments = preview.originalLines().stream().collect(java.util.stream.Collectors.toMap(value -> value.original().lineNo(), ExpenseRound.FrozenLine::assessment));
        var loaded = resourceSnapshots.load(report);
        report.freeze(1, 1, preview.baseCurrency(), preview.account(), assessments, "alice", Instant.now());
        var plan = new ExpenseSubmissionResources().plan(report, loaded, Instant.now());
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            for (var change : plan.invoices()) invoices.update(Invoice.restore(change.after()), change.after().version() - 1, "fixture", "FIXTURE_RESERVE");
            for (var change : plan.requests()) requests.update(ExpenseRequest.restore(change.after()), change.after().version() - 1, "fixture", "FIXTURE_RESERVE");
            for (var change : plan.advances()) advances.update(EmployeeAdvance.restore(change.after()), change.after().version() - 1, "fixture", "FIXTURE_RESERVE");
            reports.update(report, 1, "fixture", "FIXTURE_FREEZE");
            var application = applications.findById("demo", report.applicationId()).orElseThrow(); application.submit(1); applications.update(application, 1);
            application.returnToApplicant(2); applications.update(application, 2);
        });
        report.revise(2, report.content()); reports.update(report, 2, "alice", "FIXTURE_REVISE");
        verify(fixture.invoice()); var before = resourceSnapshots.load(report); var reportBefore = report.state();
        UUID second = enqueue(report); worker.poll();
        assertThat(job(second).status()).isEqualTo(Status.READY); assertThat(job(second).input().roundNo()).isEqualTo(2);
        assertThat(job(second).result().evidence().preview().roundNo()).isEqualTo(2);
        assertThat(resourceSnapshots.load(report)).isEqualTo(before); assertThat(reports.find("demo", report.id()).orElseThrow().state()).isEqualTo(reportBefore);
        assertThat(invoices.find("demo", fixture.invoice()).orElseThrow().use().roundNo()).isEqualTo(1);
        assertThat(requests.find("demo", fixture.prior().id()).orElseThrow().balance(1).reservations().get(0).use().roundNo()).isEqualTo(1);
    }

    @Test
    void changingResourceDuringBudgetCallDiscardsOtherwiseSuccessfulEvidence() throws Exception {
        var fixture = fixture(true); UUID id = enqueue(fixture.report()); var another = fixture(false).report();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        RESPONDER.set((operation, request) -> { if (operation.equals("budget-precheck")) { entered.countDown(); await(release); } return normal(operation, request); });
        var pool = Executors.newSingleThreadExecutor();
        try {
            var run = pool.submit(worker::poll); assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var advance = advances.find("demo", fixture.advance().id()).orElseThrow();
            advance.reserve(1, new ExpenseUse(another.id(), 1, 0), money("1", "CNY")); advances.update(advance, 1, "alice", "FIXTURE_RESERVE");
            release.countDown(); run.get(10, TimeUnit.SECONDS);
            assertThat(job(id).result().findings()).containsExactly(new Finding(Stage.RESOURCES, null, Nature.UNAVAILABLE, "RESOURCES_CHANGED"));
            assertThat(invoices.find("demo", fixture.invoice()).orElseThrow().occupation()).isEqualTo(Invoice.Occupation.AVAILABLE);
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test
    void anotherOriginalCanInvalidateReadyByCanonicalClaimWithoutChangingAnyReferencedVersion() throws Exception {
        var fixture = fixture(true); UUID ready = enqueue(fixture.report()); worker.poll();
        var before = resourceSnapshots.load(fixture.report());
        var competitor = fixture(true); var invoice = invoices.find("demo", competitor.invoice()).orElseThrow();
        invoice.occupy(invoice.version(), new ExpenseUse(competitor.report().id(), 1, 1), "alice", entity, Instant.now());
        invoices.update(invoice, 2, "alice", "FIXTURE_OCCUPY");
        assertThat(resourceSnapshots.load(fixture.report())).isEqualTo(before);
        var view = tree(read(fixture.report(), "/prechecks/" + ready, "alice"));
        assertThat(view.path("usable").asBoolean()).isFalse();
        assertThat(view.path("unavailableCode").asText()).isEqualTo("RESOURCES_CHANGED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DENIED", "REQUIRES_EXCEPTION"})
    void localPolicyGuardKeepsTheOriginalLineNumberWithoutReservingOrCallingBudget(String decision) throws Exception {
        var report = report(new ExpenseContent(entity, ExpenseContent.Type.DAILY, "逐行制度反馈",
                List.of(line(3, "OFFICE", List.of(), null), line(7, "OFFICE", List.of(), null)), List.of()));
        var before = report.state();
        RESPONDER.set((operation, request) -> {
            if (!operation.equals("expense-policy") || request.at("/data/line/lineNo").asInt() != 7) return normal(operation, request);
            var input = json.read(request.path("data").toString(), ExpensePolicyPort.Request.class);
            var amount = input.exchangeRate().convert(input.line().claimedGross());
            var assessment = new ExpensePolicyPort.Assessment(new ExpensePolicySnapshot(UUID.randomUUID(), 1, amount,
                    money("0", "CNY"), ExpensePolicySnapshot.Decision.valueOf(decision), "synthetic-tax", "synthetic-policy"),
                    input.exchangeRate().convert(input.line().claimedTax()), false, Instant.now().plusSeconds(600));
            return json.write(Map.of("contractVersion", 1, "tenantId", request.path("tenantId").asText(),
                    "requestId", request.path("requestId").asText(), "outcome", "SUCCESS", "data", assessment));
        });
        UUID id = enqueue(report); worker.poll();
        String code = decision.equals("DENIED") ? "EXPENSE_POLICY_DENIED" : "EXPENSE_EXCEPTION_REASON_REQUIRED";
        assertThat(job(id).status()).isEqualTo(Status.BLOCKED);
        assertThat(job(id).result().findings()).containsExactly(new Finding(Stage.INPUT, 7, Nature.REJECTED, code));
        var response = tree(read(report, "/prechecks/" + id, "alice"));
        assertThat(response.path("findings").get(0).path("lineNo").asInt()).isEqualTo(7);
        assertThat(count("budget-precheck")).isZero();
        assertThat(reports.find("demo", report.id()).orElseThrow().state()).isEqualTo(before);
        assertThat(applications.findById("demo", report.applicationId()).orElseThrow().status()).isEqualTo(ApplicationStatus.DRAFT);
    }

    private Fixture fixture(boolean withResources) throws Exception {
        if (!withResources) return new Fixture(report(new ExpenseContent(entity, ExpenseContent.Type.DAILY, "合成预检", List.of(line(1, "OFFICE", List.of(), null)), List.of())), null, null, null);
        UUID invoice = original(); verify(invoice);
        var priorApplication = Application.restore(UUID.randomUUID(), "demo", "SYNTHETIC-" + UUID.randomUUID(), "prior", 1, "alice", "合成已批准事前申请", Map.of(), ApplicationStatus.APPROVED, 1, 1);
        applications.save(priorApplication);
        var prior = new ExpenseRequest(UUID.randomUUID(), "demo", priorApplication.id(), entity, "alice", List.of(new ExpenseRequest.ApprovedLine(1, money("1000", "CNY"), BigDecimal.ZERO, "synthetic"))); requests.create(prior, "fixture");
        var advance = new EmployeeAdvance(UUID.randomUUID(), "demo", entity, "alice", money("500", "CNY"), "synthetic-payment-" + UUID.randomUUID(), LocalDate.now(), LocalDate.now().plusDays(30)); advances.create(advance, "fixture");
        var content = new ExpenseContent(entity, ExpenseContent.Type.DAILY, "合成完整预检", List.of(line(1, "OFFICE", List.of(invoice), prior.id())), List.of(new AdvanceOffset(advance.id(), money("100", "CNY"))));
        return new Fixture(report(content), invoice, prior, advance);
    }
    private ExpenseReport report(ExpenseContent content) {
        UUID id = UUID.randomUUID(); var application = Application.draftBusiness(UUID.randomUUID(), "demo", "SYNTHETIC-" + UUID.randomUUID(), "fixture", 1, "alice", content.title(), Map.of(), null, null, null, new BusinessReference(BusinessReference.Type.EXPENSE, id));
        applications.save(application); var report = ExpenseReport.draft(id, "demo", application.id(), "alice", content); reports.create(report, "alice"); return report;
    }
    private ExpenseLine line(int number, String category, List<UUID> invoiceIds, UUID prior) {
        return new ExpenseLine(number, category, LocalDate.parse("2026-01-02"), null, "SH", BigDecimal.ONE, ExpenseLine.Unit.ITEM, money("100", "USD"), money("6", "USD"), invoiceIds,
                prior == null ? null : new ExpenseLine.PriorRequestLine(prior, 1), List.of(new CostAllocation("IT", null, money("100", "USD"))), "合成费用", null);
    }
    private UUID original() throws Exception {
        byte[] bytes = ("%PDF-1.7\nsynthetic-precheck-" + UUID.randomUUID() + "\n%%EOF").getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); actors.set(new Actor("demo", "alice", Set.of("EMPLOYEE")));
        try { UUID id = wallet.reserve(new InvoiceWalletService.UploadInput("合成预检.pdf", (long) bytes.length, digest, InvoiceOriginal.Format.PDF)).id(); wallet.upload(id, new ByteArrayInputStream(bytes)); return id; }
        finally { actors.clear(); }
    }
    private void verify(UUID invoice) {
        actors.set(new Actor("demo", "alice", Set.of("EMPLOYEE"))); UUID id;
        try { id = verification.queue(invoice, new InvoiceVerificationService.QueueInput(invoices.find("demo", invoice).orElseThrow().version(), entity, target())).id(); }
        finally { actors.clear(); }
        invoiceWorker.poll(); assertThat(verificationJobs.find("demo", id).orElseThrow().status()).isEqualTo(InvoiceVerificationJob.Status.SUCCEEDED);
    }
    private ExpensePrecheckService.QueueInput input(ExpenseReport report) { return new ExpensePrecheckService.QueueInput(applications.findById("demo", report.applicationId()).orElseThrow().version(), report.version(), appointment.id(), LocalDate.now(), target()); }
    private UUID enqueue(ExpenseReport report) throws Exception { return id(queue(report, "alice", UUID.randomUUID().toString(), input(report), 202)); }
    private MockHttpServletResponse queue(ExpenseReport report, String user, String key, Object input, int status) throws Exception {
        var response = mvc.perform(post(path(report) + "/precheck").header("Authorization", token(user)).header("Idempotency-Key", key).contentType("application/json").content(json.write(input))).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status); return response;
    }
    private MockHttpServletResponse read(ExpenseReport report, String suffix, String user) throws Exception { return mvc.perform(get(path(report) + suffix).header("Authorization", token(user))).andReturn().getResponse(); }
    private String path(ExpenseReport report) { return "/api/v1/expense-reports/" + report.id(); }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private String target() { return configuration.destination("demo").orElseThrow().digest("demo"); }
    private JsonNode tree(MockHttpServletResponse response) throws Exception { return json.read(response.getContentAsString(StandardCharsets.UTF_8), JsonNode.class); }
    private UUID id(MockHttpServletResponse response) throws Exception { return UUID.fromString(tree(response).path("id").asText()); }
    private ExpensePrecheckJob job(UUID id) { return jobs.find("demo", id).orElseThrow(); }
    private int journal(UUID id) { return jdbc.queryForObject("SELECT COUNT(*) FROM expense_precheck_revision WHERE tenant_id='demo' AND job_id=?", Integer.class, id.toString()); }
    private static Money money(String value, String currency) { return new Money(new BigDecimal(value), currency); }
    private static int count(String operation) { return CALLS.getOrDefault(operation, new AtomicInteger()).get(); }
    private Invoice.VerifiedFacts facts(String digest) { return new Invoice.VerifiedFacts(new InvoiceKey(InvoiceKey.Type.DIGITAL, null, invoiceNumber), entity, money("100", "USD"), money("6", "USD"), LocalDate.now(), digest, "synthetic-invoice", Instant.now().minusSeconds(1), Instant.now().plusSeconds(600)); }
    private String normal(String operation, JsonNode request) {
        JsonNode data = request.path("data"); Object value = switch (operation) {
            case "catalog" -> new FinanceCatalog("alice", "synthetic-v1", Instant.now().plusSeconds(600), List.of(new FinanceCatalog.LegalEntity(entity, "合成法人", "CNY", true, "v1", ZONE)), List.of(new FinanceCatalog.Category("OFFICE", "办公", List.of(ExpenseLine.Unit.ITEM))), List.of(new FinanceCatalog.CostCenter(entity, "IT", "研发")), List.of(), List.of(new FinanceCatalog.City("SH", "上海")));
            case "employee-account" -> new EmployeeAccountPort.Account(new EmployeeAccountSnapshot(entity, "alice", "private-account", "****1234", "a".repeat(64), "v1"), Instant.now().plusSeconds(600));
            case "exchange-rate" -> new ExpenseExchangeRate(data.path("fromCurrency").asText(), data.path("toCurrency").asText(), new BigDecimal("7.1"), "synthetic-rate", LocalDate.parse(data.path("rateDate").asText()));
            case "invoice-verification" -> facts(data.path("originalDigest").asText());
            case "expense-policy" -> {
                var input = json.read(data.toString(), ExpensePolicyPort.Request.class); var amount = input.exchangeRate().convert(input.line().claimedGross());
                yield new ExpensePolicyPort.Assessment(new ExpensePolicySnapshot(UUID.randomUUID(), 1, amount, amount, ExpensePolicySnapshot.Decision.WITHIN_LIMIT, "synthetic-tax", "synthetic-policy"), input.exchangeRate().convert(input.line().claimedTax()), false, Instant.now().plusSeconds(600));
            }
            case "budget-precheck" -> { LAST_BUDGET.set(data); yield new BudgetPrecheckPort.Assessment(json.read(data.toString(), BudgetPrecheckPort.Request.class), "synthetic-budget", Instant.now().minusSeconds(1), Instant.now().plusSeconds(600)); }
            default -> throw new IllegalArgumentException("Unknown synthetic finance operation");
        };
        return json.write(Map.of("contractVersion", 1, "tenantId", request.path("tenantId").asText(), "requestId", request.path("requestId").asText(), "outcome", "SUCCESS", "data", value));
    }
    private String rejected(JsonNode request, String reason) { return json.write(Map.of("contractVersion", 1, "tenantId", request.path("tenantId").asText(), "requestId", request.path("requestId").asText(), "outcome", "REJECTED", "reason", reason)); }
    private static void await(CountDownLatch latch) { try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic precheck wait timed out"); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); } }
    private static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/finance/", exchange -> {
                TRACES.add(exchange.getRequestHeaders().getFirst("X-Trace-Id"));
                String operation = exchange.getRequestURI().getPath().substring("/finance/".length()); CALLS.computeIfAbsent(operation, key -> new AtomicInteger()).incrementAndGet();
                var request = wire.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonNode.class);
                byte[] bytes = RESPONDER.get().apply(operation, request).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length);
                try { exchange.getResponseBody().write(bytes); } finally { exchange.close(); }
            }); server.start(); return server;
        } catch (java.io.IOException failed) { throw new IllegalStateException(failed); }
    }
    /**
     * 合成外部事实的本地关联，不代表正式提交或真实放款联调。
     * @author owlzhangfq@gmail.com
     */
    private record Fixture(ExpenseReport report, UUID invoice, ExpenseRequest prior, EmployeeAdvance advance) { }
}
