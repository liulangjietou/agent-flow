package io.agentflow.approval;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import io.agentflow.expense.CostAllocation;
import io.agentflow.expense.ExpenseContent;
import io.agentflow.expense.ExpenseLine;
import io.agentflow.expense.ExpensePrecheckJob;
import io.agentflow.expense.ExpensePrecheckWorker;
import io.agentflow.expense.ExpenseReport;
import io.agentflow.expense.ExpenseReportRepository;
import io.agentflow.expense.ExpenseSplitRoutingSnapshot;
import io.agentflow.expense.JdbcExpensePrecheckRepository;
import io.agentflow.expense.JdbcExpenseSplitRoutingRepository;
import io.agentflow.finance.BudgetOperation;
import io.agentflow.finance.BudgetOperationService;
import io.agentflow.finance.BudgetSystemPort;
import io.agentflow.finance.FinanceGatewayConfiguration;
import io.agentflow.finance.JdbcBudgetOccupationRepository;
import io.agentflow.finance.JdbcBudgetOperationRepository;
import io.agentflow.finance.Money;
import io.agentflow.form.FieldVisibility;
import io.agentflow.form.FormSchema;
import io.agentflow.organization.OrganizationService;
import io.agentflow.organization.OrganizationUnit;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 真实报销提交事务和 Flowable 路由验证；财务接口由本机合成服务提供明确金额。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:expense-split-runtime;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "agentflow.auth.demo-enabled=true",
        "agentflow.finance-gateway.enabled=true", "agentflow.timers.enabled=false", "agentflow.sla.reminders-enabled=false",
        "agentflow.vouchers.preparation-worker-enabled=false", "agentflow.vouchers.worker-enabled=false", "agentflow.payments.worker-enabled=false",
        "agentflow.payments.request-worker-enabled=false", "agentflow.invoices.verification-worker-enabled=false",
        "agentflow.expenses.precheck-worker-enabled=false", "agentflow.budgets.worker-enabled=false",
        "agentflow.expenses.settlement-worker-enabled=false", "agentflow.expenses.archive-worker-enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExpenseSplitRoutingIntegrationTest {
    private static final Actor ADMIN = new Actor("demo", "admin", Set.of("ADMIN", "PROCESS_ADMIN"));
    private static final String ROUTING_VARIABLE = "agentflowExpenseSplitRouting";
    private static final ExpenseSubprocessGatewayFixture GATEWAY = new ExpenseSubprocessGatewayFixture();
    private final Map<String, String> tokens = new ConcurrentHashMap<>();
    private UUID entity, appointment, manager, finance;
    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @Autowired AuthService auth;
    @Autowired CurrentActor actors;
    @Autowired OrganizationService organization;
    @Autowired DefinitionApplicationService definitions;
    @Autowired ApplicationRepository applications;
    @Autowired ExpenseReportRepository reports;
    @Autowired ExpensePrecheckWorker precheckWorker;
    @Autowired JdbcExpensePrecheckRepository prechecks;
    @Autowired FinanceGatewayConfiguration financeConfiguration;
    @Autowired JdbcBudgetOccupationRepository occupations;
    @Autowired JdbcBudgetOperationRepository operations;
    @Autowired BudgetOperationService budgetExecution;
    @Autowired BudgetSystemPort budgetPort;
    @Autowired JdbcExpenseSplitRoutingRepository routing;
    @Autowired TaskService tasks;
    @Autowired RuntimeService runtime;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("agentflow.finance-gateway.tenants.demo.endpoint", GATEWAY::endpoint);
        registry.add("agentflow.finance-gateway.tenants.demo.allow-unauthenticated-loopback", () -> true);
        registry.add("agentflow.attachments.directory", () -> "/fyoung/tmp/agentflow-split-http-fixtures");
    }
    @BeforeEach void organization() {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM organization_directory WHERE tenant_id='demo'", Integer.class) == 0) organization.initialize(ADMIN);
        entity = organization.createUnit(ADMIN, OrganizationUnit.Kind.LEGAL_ENTITY, "合成拆单法人", null, null, true).id();
        var department = organization.createUnit(ADMIN, OrganizationUnit.Kind.DEPARTMENT, "合成部门", entity, null, true);
        var position = organization.createUnit(ADMIN, OrganizationUnit.Kind.POSITION, "合成岗位", entity, null, true);
        appointment = organization.createAppointment(ADMIN, person("alice", false), department.id(), position.id(), true).id();
        manager = person("manager", true); finance = person("finance", true);
        GATEWAY.use(json, entity, "76543210987654321000", money("4000"));
    }
    @AfterEach void clearActorAndCheckGateway() { actors.clear(); assertThat(GATEWAY.failure()).isNull(); }
    @AfterAll static void stopOwnedServer() { GATEWAY.close(); }

    @Test void concurrentSubmissionsFreezeDifferentTotalsAndFinanceStillUsesReducedOwnAmount() throws Exception {
        var definition = definition("ENABLED"); var first = draft(definition); var second = draft(definition);
        var firstInput = submitInput(first, precheck(first)); var secondInput = submitInput(second, precheck(second));
        var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> { start.await(); return send(path(first) + "/submit", "alice", firstInput, 200); });
            var two = pool.submit(() -> { start.await(); return send(path(second) + "/submit", "alice", secondInput, 200); });
            start.countDown(); one.get(30, TimeUnit.SECONDS); two.get(30, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        budgets(first); budgets(second); approve(first, "manager"); approve(second, "manager");
        assertThat(List.of(task(first).getTaskDefinitionKey(), task(second).getTaskDefinitionKey())).containsExactlyInAnyOrder("receipt", "higher");
        var escalated = task(first).getTaskDefinitionKey().equals("higher") ? first : second;
        var ordinary = escalated.id().equals(first.id()) ? second : first;
        assertThat(snapshot(ordinary, 1).assessment().routingAmount()).isEqualTo(money("4000"));
        var frozen = snapshot(escalated, 1); assertThat(frozen.assessment().routingAmount()).isEqualTo(money("8000"));
        assertThat(frozen.assessment().sources()).hasSize(2);
        approve(escalated, "manager");
        send(path(escalated) + "/tasks/" + task(escalated).getId() + "/receive", "finance", lifecycle(escalated), 200);
        approve(escalated, "finance"); assertThat(task(escalated).getTaskDefinitionKey()).isEqualTo("finance");
        send(path(escalated) + "/tasks/" + task(escalated).getId() + "/reduce", "finance",
                Map.of("applicationVersion", application(escalated).version(), "financialVersion", current(escalated).version(),
                        "lines", List.of(Map.of("lineNo", 1, "approvedGross", "3000.00", "approvedTax", "3.00")),
                        "reasonCode", "INELIGIBLE_COST", "comment", "合成核减"), 200);
        budgets(escalated); approve(escalated, "finance");
        assertThat(application(escalated).status()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(current(escalated).currentRound().approvedGross()).isEqualTo(money("3000"));
        assertThat(snapshot(escalated, 1)).isEqualTo(frozen);
    }

    @ParameterizedTest @ValueSource(strings = {"UNCONFIGURED", "DISABLED"})
    void legacyAndExplicitlyDisabledDefinitionsKeepOwnAmountAndTheirOriginalMode(String mode) throws Exception {
        var definition = definition(mode); var first = draft(definition); var second = draft(definition);
        submit(first); submit(second); approve(first, "manager"); approve(second, "manager");
        assertThat(task(first).getTaskDefinitionKey()).isEqualTo("receipt"); assertThat(task(second).getTaskDefinitionKey()).isEqualTo("receipt");
        assertThat(snapshot(second, 1).configuration().mode().name()).isEqualTo(mode);
        assertThat(snapshot(second, 1).assessment()).isNull();
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "malformed", "application", "round", "definition", "ruleVersion", "gateway", "currency"})
    void markedGatewayCannotFallBackToOwnAmountWhenFrozenRuntimeContextIsMissingOrMalformed(String corruption) throws Exception {
        var report = draft(definition("ENABLED")); submit(report); long version = application(report).version();
        String taskId = task(report).getId(), process = task(report).getProcessInstanceId();
        if (corruption.equals("missing")) runtime.removeVariable(process, ROUTING_VARIABLE);
        else if (corruption.equals("malformed")) runtime.setVariable(process, ROUTING_VARIABLE, "{}");
        else {
            var value = new HashMap<Object, Object>((Map<?, ?>) runtime.getVariable(process, ROUTING_VARIABLE));
            switch (corruption) {
                case "application" -> value.put("applicationId", UUID.randomUUID().toString());
                case "round" -> value.put("roundNo", 99);
                case "definition" -> value.put("runtimeDefinitionId", "foreign-runtime");
                case "ruleVersion" -> value.put("ruleVersion", 99);
                case "gateway" -> value.put("gatewayIds", Set.of("financialGate"));
                case "currency" -> value.put("currency", "USD");
                default -> throw new AssertionError(corruption);
            }
            runtime.setVariable(process, ROUTING_VARIABLE, value);
        }
        var response = raw("/api/v1/tasks/" + taskId + "/actions", "manager", Map.of("action", "APPROVE", "expectedVersion", version, "comment", "缺失依据应阻断"));
        assertThat(response.getStatus()).as(response.getContentAsString()).isNotEqualTo(200);
        assertThat(application(report).version()).isEqualTo(version); assertThat(task(report).getId()).isEqualTo(taskId);
    }

    @Test void laterBudgetFailureRollsBackPreparedEvidenceFinancialRoundAndFlowableInstance() throws Exception {
        var first = draft(definition("ENABLED")); submit(first);
        var second = draft(definition("ENABLED")); var input = submitInput(second, precheck(second));
        jdbc.execute("ALTER TABLE budget_occupation ADD CONSTRAINT split_fixture_budget_failure CHECK(report_id<>'" + second.id() + "')");
        try {
            assertThatThrownBy(() -> raw(path(second) + "/submit", "alice", input)).isInstanceOf(jakarta.servlet.ServletException.class)
                    .hasCauseInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(current(second).version()).isEqualTo(1); assertThat(current(second).rounds()).isEmpty();
            assertThat(application(second).status()).isEqualTo(ApplicationStatus.DRAFT);
            assertThat(routing.find("demo", second.id(), 1)).isEmpty();
            assertThat(runtime.createProcessInstanceQuery().variableValueEquals("applicationId", second.applicationId().toString()).count()).isZero();
        } finally { jdbc.execute("ALTER TABLE budget_occupation DROP CONSTRAINT split_fixture_budget_failure"); }
        send(path(second) + "/submit", "alice", input, 200); budgets(second); approve(second, "manager");
        assertThat(task(second).getTaskDefinitionKey()).isEqualTo("higher"); assertThat(snapshot(second, 1).assessment().sources()).hasSize(2);
    }

    @Test void withdrawnSourceIsExcludedAndResubmissionDoesNotCountItsOwnOldRound() throws Exception {
        var definition = definition("ENABLED"); var first = draft(definition); submit(first);
        var original = snapshot(first, 1);
        send(path(first) + "/withdraw", "alice", lifecycle(first), 200); budgets(first);
        var second = draft(definition); submit(second); approve(second, "manager");
        assertThat(task(second).getTaskDefinitionKey()).isEqualTo("receipt");
        submit(first); approve(first, "manager");
        assertThat(task(first).getTaskDefinitionKey()).isEqualTo("higher");
        assertThat(snapshot(first, 1)).isEqualTo(original);
        assertThat(snapshot(first, 2).assessment().sources()).hasSize(2);
        assertThat(snapshot(first, 2).assessment().routingAmount()).isEqualTo(money("8000"));
    }

    @Test void newerPublishedRuleDoesNotRewriteAlreadySubmittedRounds() throws Exception {
        var originalDefinition = definition("ENABLED"); var first = draft(originalDefinition); var second = draft(originalDefinition);
        submit(first); submit(second); var frozen = snapshot(second, 1);
        var newer = definition("ENABLED", originalDefinition.key(), "15000");
        assertThat(newer.version()).isEqualTo(2); var third = draft(newer); submit(third);
        approve(second, "manager"); approve(third, "manager");
        assertThat(task(second).getTaskDefinitionKey()).isEqualTo("higher");
        assertThat(task(third).getTaskDefinitionKey()).isEqualTo("receipt");
        assertThat(snapshot(second, 1)).isEqualTo(frozen);
        assertThat(snapshot(third, 1).definitionVersion()).isEqualTo(2);
        assertThat(snapshot(third, 1).configuration().rule().threshold()).isEqualTo(money("15000"));
        assertThat(snapshot(third, 1).assessment().routingAmount()).isEqualTo(money("4000"));
    }

    private DefinitionDraft definition(String mode) {
        return definition(mode, "split-runtime-" + UUID.randomUUID(), "5000");
    }
    private DefinitionDraft definition(String mode, String key, String threshold) {
        var settings = mode.equals("UNCONFIGURED") ? Map.<String, String>of() : Map.of("expenseSplitRisk", mode,
                "expenseSplitWindowDays", "7", "expenseSplitThreshold", threshold, "expenseSplitCurrency", "CNY");
        var graph = new Graph(List.of(new Node("start", "开始", NodeType.START, settings),
                new Node("business", "业务审批", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_" + manager)),
                new Node("gate", "业务金额", NodeType.EXCLUSIVE_GATEWAY, mode.equals("UNCONFIGURED") ? Map.of() : Map.of("expenseSplitRouting", "AGGREGATE_AMOUNT")),
                new Node("higher", "更高层业务审批", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_" + manager)),
                new Node("receipt", "原件签收", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_" + finance, "expenseStage", "RECEIPT")),
                new Node("finance", "财务审核", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_" + finance, "expenseStage", "FINANCE_REVIEW")),
                new Node("financialGate", "本单核定金额", NodeType.EXCLUSIVE_GATEWAY, Map.of()),
                new Node("recheck", "财务复核", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_" + manager, "expenseStage", "FINANCE_RECHECK")),
                new Node("end", "结束", NodeType.END, Map.of())), List.of(
                new Edge("a", "start", "business", ""), new Edge("b", "business", "gate", ""),
                new Edge("high", "gate", "higher", "amount > " + threshold), new Edge("low", "gate", "receipt", "", true),
                new Edge("c", "higher", "receipt", ""), new Edge("d", "receipt", "finance", ""), new Edge("e", "finance", "financialGate", ""),
                new Edge("review", "financialGate", "recheck", "amount > 3500"), new Edge("skip", "financialGate", "end", "", true), new Edge("done", "recheck", "end", "")));
        var schema = new FormSchema(2, List.of(new FormSchema.Field("expenseDetails", "费用明细", FormSchema.FieldType.TEXT, true,
                null, null, null, null, null, null, null, true, Map.of("business", FieldVisibility.READ_ONLY, "higher", FieldVisibility.READ_ONLY,
                "receipt", FieldVisibility.READ_ONLY, "finance", FieldVisibility.READ_ONLY, "recheck", FieldVisibility.READ_ONLY)),
                field("amount", FormSchema.FieldType.NUMBER), field("currency", FormSchema.FieldType.TEXT), field("overPolicy", FormSchema.FieldType.BOOLEAN)));
        var draft = definitions.create("demo", key, "合成跨单审批", graph, schema, null);
        return definitions.publish(ADMIN, draft.id(), draft.revision(), "合成验收");
    }
    private ExpenseReport draft(DefinitionDraft definition) throws Exception {
        var gross = money("4000");
        var line = new ExpenseLine(1, "OFFICE", LocalDate.now(), null, "SH", BigDecimal.ONE, ExpenseLine.Unit.ITEM, gross, money("6"),
                List.of(), null, List.of(new CostAllocation("IT", null, gross)), "合成费用", null);
        var created = send("/api/v1/expense-reports", "alice", Map.of("businessNo", "SPLIT-" + UUID.randomUUID(), "processKey", definition.key(),
                "definitionVersion", definition.version(), "content", new ExpenseContent(entity, ExpenseContent.Type.DAILY, "合成报销", List.of(line), List.of())), 201);
        return reports.find("demo", UUID.fromString(created.path("id").asText())).orElseThrow();
    }
    private UUID precheck(ExpenseReport report) throws Exception {
        var checked = send(path(report) + "/precheck", "alice", Map.of("applicationVersion", application(report).version(), "financialVersion", current(report).version(),
                "initiatorAppointmentId", appointment, "accountingDate", LocalDate.now(), "targetDigest", financeConfiguration.destination("demo").orElseThrow().digest("demo")), 202);
        var id = UUID.fromString(checked.path("id").asText()); precheckWorker.poll();
        var job = prechecks.find("demo", id).orElseThrow(); assertThat(job.status()).as(json.write(job.result())).isEqualTo(ExpensePrecheckJob.Status.READY);
        return id;
    }
    private void submit(ExpenseReport report) throws Exception { send(path(report) + "/submit", "alice", submitInput(report, precheck(report)), 200); budgets(report); }
    private Map<String, Object> submitInput(ExpenseReport report, UUID checked) { return Map.of("applicationVersion", application(report).version(), "financialVersion", current(report).version(), "precheckId", checked); }
    private void budgets(ExpenseReport report) {
        UUID id = occupations.find("demo", report.id()).orElseThrow().pendingOperationId(); if (id == null) return;
        var operation = operations.find("demo", id).orElseThrow(); var now = Instant.now(); var at = operation.nextAttemptAt().isAfter(now) ? operation.nextAttemptAt() : now;
        var claimed = budgetExecution.claim("demo", id, at); var input = claimed.input();
        var result = claimed.status() == BudgetOperation.Status.QUERYING ? budgetPort.query(input.targetDigest(), input.command()) : budgetPort.execute(input.targetDigest(), input.command());
        var finished = Instant.now(); budgetExecution.finish(claimed, result, (finished.isAfter(at) ? finished : at).plusMillis(1));
    }
    private void approve(ExpenseReport report, String user) throws Exception { send("/api/v1/tasks/" + task(report).getId() + "/actions", user,
            Map.of("action", "APPROVE", "expectedVersion", application(report).version(), "comment", "合成审批"), 200); }
    private Map<String, Object> lifecycle(ExpenseReport report) { return Map.of("applicationVersion", application(report).version(), "financialVersion", current(report).version(), "comment", "合成操作"); }
    private ExpenseSplitRoutingSnapshot snapshot(ExpenseReport report, int round) {
        var result = routing.find("demo", report.id(), round); assertThat(result).as("Submitted round must retain explicit split routing evidence").isPresent();
        return result.orElseThrow();
    }
    private Application application(ExpenseReport report) { return applications.findById("demo", report.applicationId()).orElseThrow(); }
    private ExpenseReport current(ExpenseReport report) { return reports.find("demo", report.id()).orElseThrow(); }
    private Task task(ExpenseReport report) { return tasks.createTaskQuery().processVariableValueEquals("applicationId", report.applicationId().toString()).singleResult(); }
    private UUID person(String user, boolean approver) { var found = jdbc.queryForList("SELECT id FROM organization_person WHERE tenant_id='demo' AND subject=?", String.class, user);
        return found.isEmpty() ? organization.createPerson(ADMIN, user, "合成" + user, true, approver).id() : UUID.fromString(found.get(0)); }
    private FormSchema.Field field(String key, FormSchema.FieldType type) { return new FormSchema.Field(key, key, type, true, null, null, null, null, null); }
    private MockHttpServletResponse raw(String path, String user, Object body) throws Exception {
        String token = "Bearer " + tokens.computeIfAbsent(user, value -> auth.login("demo", value, "demo").token());
        return mvc.perform(post(path).header("Authorization", token).header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json").content(json.write(body))).andReturn().getResponse();
    }
    private JsonNode send(String path, String user, Object body, int status) throws Exception { var response = raw(path, user, body);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status); return json.read(response.getContentAsString(StandardCharsets.UTF_8), JsonNode.class); }
    private static String path(ExpenseReport report) { return "/api/v1/expense-reports/" + report.id(); }
    private static Money money(String amount) { return new Money(new BigDecimal(amount), "CNY"); }
}
