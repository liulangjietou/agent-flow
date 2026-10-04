package io.agentflow.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.agentflow.agent.*;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.auth.AuthService;
import io.agentflow.calendar.BusinessCalendarService;
import io.agentflow.calendar.CalendarRules;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import io.agentflow.expense.*;
import io.agentflow.finance.*;
import io.agentflow.form.FieldVisibility;
import io.agentflow.form.FormSchema;
import io.agentflow.organization.OrganizationService;
import io.agentflow.organization.OrganizationUnit;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * 实际报销创建、预检、提交、Flowable 任务、认证与风险 API 的集成验收；财务与模型仅使用本机合成 HTTP 服务。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:expense-risk-http;DB_CLOSE_DELAY=-1", "agentflow.auth.demo-enabled=true",
        "agentflow.assist.enabled=true", "agentflow.assist.worker-enabled=false", "agentflow.assist.model=synthetic-risk",
        "agentflow.finance-gateway.enabled=true", "agentflow.timers.enabled=false", "agentflow.sla.reminders-enabled=false",
        "agentflow.vouchers.preparation-worker-enabled=false", "agentflow.vouchers.worker-enabled=false",
        "agentflow.payments.worker-enabled=false", "agentflow.payments.request-worker-enabled=false",
        "agentflow.invoices.verification-worker-enabled=false", "agentflow.expenses.precheck-worker-enabled=false",
        "agentflow.budgets.worker-enabled=false", "agentflow.expenses.settlement-worker-enabled=false", "agentflow.expenses.archive-worker-enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExpenseRiskIntegrationTest {
    private static final Actor ADMIN = new Actor("demo", "admin", Set.of("ADMIN", "PROCESS_ADMIN"));
    private static final ExpenseSubprocessGatewayFixture GATEWAY = new ExpenseSubprocessGatewayFixture();
    private static final AtomicInteger MODEL_CALLS = new AtomicInteger();
    private static final AtomicReference<String> LAST_MODEL = new AtomicReference<>();
    private static final java.util.concurrent.ExecutorService HTTP_THREADS = Executors.newFixedThreadPool(2);
    private static final HttpServer MODEL = modelServer();
    private static JsonUtil wire;
    private final Map<String, String> tokens = new HashMap<>();
    private final List<UUID> queued = new ArrayList<>();
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
    @Autowired TaskService tasks;
    @Autowired JdbcTemplate jdbc;
    @Autowired BusinessCalendarService calendars;
    @Autowired ExpenseRiskService risks;
    @Autowired ExpenseRiskWorker worker;
    @Autowired JdbcExpenseRiskRepository runs;

    @DynamicPropertySource static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("agentflow.assist.endpoint", () -> "http://127.0.0.1:" + MODEL.getAddress().getPort() + "/v1/chat/completions");
        registry.add("agentflow.finance-gateway.tenants.demo.endpoint", GATEWAY::endpoint);
        registry.add("agentflow.finance-gateway.tenants.demo.allow-unauthenticated-loopback", () -> true);
        registry.add("agentflow.attachments.directory", () -> "/fyoung/tmp/agentflow-risk-http-fixtures");
    }
    @BeforeEach void organization() {
        wire = json;
        if (jdbc.queryForObject("SELECT COUNT(*) FROM organization_directory WHERE tenant_id='demo'", Integer.class) == 0) organization.initialize(ADMIN);
        entity = organization.createUnit(ADMIN, OrganizationUnit.Kind.LEGAL_ENTITY, "合成风险法人", null, null, true).id();
        var department = organization.createUnit(ADMIN, OrganizationUnit.Kind.DEPARTMENT, "合成部门", entity, null, true);
        var position = organization.createUnit(ADMIN, OrganizationUnit.Kind.POSITION, "合成岗位", entity, null, true);
        appointment = organization.createAppointment(ADMIN, person("alice", false), department.id(), position.id(), true).id();
        manager = person("manager", true); finance = person("finance", true); GATEWAY.use(json, entity, "76543210987654321000");
    }
    @AfterEach void settleOwnedRuns() {
        actors.clear();
        for (UUID id : queued) { risks.claim("demo", id, Instant.now()); risks.finish("demo", id, null, AssistRun.Failure.MODEL_UNAVAILABLE, Instant.now()); }
        assertThat(GATEWAY.failure()).isNull();
    }
    @AfterAll static void stopOwnedServers() { MODEL.stop(0); HTTP_THREADS.shutdownNow(); GATEWAY.close(); }

    @Test void actualExpenseTasksPreviewQueueExplainAndReviewWithoutChangingApprovalOrMoney() throws Exception {
        var primary = submitted(false); var comparison = submitted(false); var scope = scope(primary, comparison); String task = task(primary).getId();
        var before = businessState(primary, comparison); int calls = MODEL_CALLS.get();
        var options = preview(primary, task, scope, "manager", 200);
        assertThat(options.path("enabled").asBoolean()).isTrue();
        assertThat(options.path("sources").toString()).doesNotContain(primary.id().toString(), comparison.id().toString(), "alice", "不可发送", "account");
        var generation = generation(task, scope, options); String key = UUID.randomUUID().toString();
        var receipt = send(path(primary), "manager", generation, key, 202); UUID id = remember(receipt);
        assertThat(send(path(primary), "manager", generation, key, 202)).isEqualTo(receipt); assertThat(MODEL_CALLS.get()).isEqualTo(calls);
        worker.poll(); var detail = read(path(primary) + "/" + id, "manager", 200);
        assertThat(detail.path("status").asText()).isEqualTo("COMPLETED"); assertThat(detail.path("reviewable").asBoolean()).isTrue(); assertThat(detail.path("adoptable").asBoolean()).isTrue();
        assertThat(MODEL_CALLS.get()).isEqualTo(calls + 1); assertThat(LAST_MODEL.get()).doesNotContain(primary.id().toString(), comparison.id().toString(), primary.applicationId().toString(), "alice", "不可发送", "loginReference", "targetDigest");
        var review = Map.of("expectedRunVersion", 3, "action", "ADOPT", "selectedConcernIds", concernIds(detail), "comment", "已核对原依据");
        String reviewKey = UUID.randomUUID().toString(); var adopted = send(path(primary) + "/" + id + "/review", "manager", review, reviewKey, 200);
        assertThat(send(path(primary) + "/" + id + "/review", "manager", review, reviewKey, 200)).isEqualTo(adopted);
        assertThat(adopted.path("status").asText()).isEqualTo("ADOPTED"); assertThat(businessState(primary, comparison)).isEqualTo(before);
        assertThat(runs.find("demo", id).orElseThrow().run().state().suggestion()).isNotNull();
        assertThat(read(path(primary) + "?roundNo=1&page=0&pageSize=1", "manager", 200).path("total").asInt()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"alice", "admin", "finance"})
    void applicantAdministratorAndFutureReviewerCannotReplaceCurrentDecisionAuthority(String user) throws Exception {
        var primary = submitted(false); int calls = MODEL_CALLS.get();
        var response = postBody(path(primary) + "/input", user, Map.of("taskId", task(primary).getId(), "scope", scope(primary)));
        assertThat(response.getStatus()).isIn(403, 404); assertThat(MODEL_CALLS.get()).isEqualTo(calls);
    }

    @Test void eachComparisonMustExposeItsOriginalSensitiveDetailsToThisActor() throws Exception {
        var primary = submitted(false); var hidden = submitted(true); int calls = MODEL_CALLS.get();
        preview(primary, task(primary).getId(), scope(primary, hidden), "manager", 403);
        assertThat(MODEL_CALLS.get()).isEqualTo(calls);
    }

    @Test void endedRealTaskPreventsQueuedModelRequestAndCannotBeReplacedByTheNextTask() throws Exception {
        var primary = submitted(false); var comparison = submitted(false); UUID id = queue(primary, scope(primary, comparison)); int calls = MODEL_CALLS.get();
        approve(primary); worker.poll(); assertThat(MODEL_CALLS.get()).isEqualTo(calls);
        assertThat(runs.find("demo", id).orElseThrow().run().state().failure()).isEqualTo(AssistRun.Failure.INPUT_UNAVAILABLE);
        assertThat(task(primary).getTaskDefinitionKey()).isEqualTo("receipt");
    }

    @Test void originalLogoutPreventsDeliveryEvenAfterANewLoginForTheSameUser() throws Exception {
        var primary = submitted(false); var comparison = submitted(false); UUID id = queue(primary, scope(primary, comparison)); int calls = MODEL_CALLS.get();
        auth.logout(tokens.get("manager")); tokens.put("manager", auth.login("demo", "manager", "demo").token());
        worker.poll(); assertThat(MODEL_CALLS.get()).isEqualTo(calls); assertThat(runs.find("demo", id).orElseThrow().run().state().failure()).isEqualTo(AssistRun.Failure.INPUT_UNAVAILABLE);
    }

    @Test void calendarDirectoryIsBoundedTenantScopedAndRequiresTheCurrentDecision() throws Exception {
        var primary = submitted(false); String taskId = task(primary).getId();
        var rules = new CalendarRules("UTC", Map.of(DayOfWeek.MONDAY, List.of(new CalendarRules.Period("09:00", "18:00"))), List.of());
        String prefix = "zzRisk" + UUID.randomUUID().toString().replace("-", "");
        for (int i = 1; i <= 31; i++) calendars.create(ADMIN, prefix + String.format("%02d", i), "风险目录合成日历", rules);
        calendars.create(new Actor("other-risk-tenant", "admin", Set.of("ADMIN")), prefix + "00", "异租户日历", rules);
        String endpoint = path(primary) + "/calendars?roundNo=1&taskId=" + taskId;
        var first = read(endpoint + "&afterKey=" + prefix, "manager", 200);
        assertThat(first.path("items").size()).isEqualTo(30);
        assertThat(first.path("nextAfterKey").asText()).isEqualTo(prefix + "30");
        assertThat(first.toString()).doesNotContain("异租户日历", "updatedBy", "weeklyHours");
        var last = read(endpoint + "&afterKey=" + prefix + "30", "manager", 200);
        assertThat(last.path("items").size()).isEqualTo(1); assertThat(last.path("nextAfterKey").isNull()).isTrue();
        read(endpoint, "alice", 403); read(endpoint, "admin", 403);
        approve(primary); read(endpoint, "manager", 403);
    }

    @Test void calendarDirectoryRejectsHiddenExpenseFieldsAndMalformedOrDuplicateQuery() throws Exception {
        var hidden = submitted(true); String taskId = task(hidden).getId(); String endpoint = path(hidden) + "/calendars";
        read(endpoint + "?roundNo=1&taskId=" + taskId, "manager", 403);
        for (String query : List.of("?roundNo=1", "?taskId=" + taskId, "?roundNo=1&taskId=" + taskId + "&afterKey=bad!",
                "?roundNo=1&taskId=" + taskId + "&roundNo=2", "?roundNo=1&taskId=" + taskId + "&tenantId=other")) read(endpoint + query, "manager", 400);
    }

    @Test void changedCalendarRequiresNewConsentAndLeavesOldOutputOnlyDismissible() throws Exception {
        var primary = submitted(false); var comparison = submitted(false); var scope = scope(primary, comparison);
        var rules = new CalendarRules("UTC", Map.of(DayOfWeek.MONDAY, List.of(new CalendarRules.Period("09:00", "18:00"))), List.of());
        var calendar = calendars.create(ADMIN, "RISK_" + UUID.randomUUID().toString().replace("-", ""), "合成日历", rules);
        scope.put("calendarId", calendar.id()); var options = preview(primary, task(primary).getId(), scope, "manager", 200);
        calendars.update(ADMIN, calendar.id(), "修订名称", rules, 1);
        send(path(primary), "manager", generation(task(primary).getId(), scope, options), UUID.randomUUID().toString(), 409);
        UUID id = queue(primary, scope); worker.poll(); calendars.update(ADMIN, calendar.id(), "再次修订", rules, 2);
        var detail = read(path(primary) + "/" + id, "manager", 200); assertThat(detail.path("reviewable").asBoolean()).isTrue(); assertThat(detail.path("adoptable").asBoolean()).isFalse();
        send(path(primary) + "/" + id + "/review", "manager", Map.of("expectedRunVersion", 3, "action", "ADOPT", "selectedConcernIds", concernIds(detail)), UUID.randomUUID().toString(), 409);
        assertThat(send(path(primary) + "/" + id + "/review", "manager", Map.of("expectedRunVersion", 3, "action", "DISMISS"), UUID.randomUUID().toString(), 200).path("status").asText()).isEqualTo("DISMISSED");
    }

    @Test void successfulReviewReplayRemainsReadableAfterOriginalTaskEndsWithoutReapplying() throws Exception {
        var primary = submitted(false); var comparison = submitted(false); UUID id = queue(primary, scope(primary, comparison)); worker.poll();
        var review = Map.of("expectedRunVersion", 3, "action", "DISMISS"); String key = UUID.randomUUID().toString();
        var result = send(path(primary) + "/" + id + "/review", "manager", review, key, 200); approve(primary);
        assertThat(send(path(primary) + "/" + id + "/review", "manager", review, key, 200)).isEqualTo(result);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_expense_risk_transition WHERE run_id=?", Integer.class, id.toString())).isEqualTo(4);
        assertThat(read(path(primary) + "/" + id, "manager", 200).path("reviewable").asBoolean()).isFalse();
    }

    @Test void strictNestedInputsAndQueriesRejectExtraAuthorityOrAmbiguousScope() throws Exception {
        var primary = submitted(false); String task = task(primary).getId(); var scope = scope(primary); int calls = MODEL_CALLS.get();
        for (var body : List.of(Map.of("taskId", task, "scope", scope, "tenantId", "other"),
                Map.of("taskId", task, "scope", Map.of("documents", scope.get("documents"), "employeeId", "other")),
                Map.of("taskId", task, "scope", Map.of("documents", List.of(Map.of("reportId", primary.id(), "roundNo", 1, "lineNos", List.of(1), "amount", 999)))))) {
            assertThat(postBody(path(primary) + "/input", "manager", body).getStatus()).isEqualTo(400);
        }
        // 已解析的来源位置违反领域规则时沿用平台 422 契约，和非法 JSON 形状分别断言。
        for (var lineNos : List.of(List.of(1, 1), List.of(201))) {
            var response = postBody(path(primary) + "/input", "manager", Map.of("taskId", task, "scope",
                    Map.of("documents", List.of(Map.of("reportId", primary.id(), "roundNo", 1, "lineNos", lineNos)))));
            assertThat(response.getStatus()).isEqualTo(422);
            assertThat(json.read(response.getContentAsString(), JsonNode.class).path("code").asText()).isEqualTo("INVALID_AGENT_INPUT");
        }
        for (String query : List.of("", "?roundNo=0", "?roundNo=1&roundNo=2", "?roundNo=1&tenantId=other", "?roundNo=1&pageSize=51", "?roundNo=2147483648")) read(path(primary) + query, "manager", 400);
        assertThat(postBody(path(primary) + "/input?extra=1", "manager", Map.of("taskId", task, "scope", scope)).getStatus()).isEqualTo(400);
        assertThat(MODEL_CALLS.get()).isEqualTo(calls);
    }

    private ExpenseReport submitted(boolean hidden) throws Exception {
        var graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("business", "业务审批", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_" + manager)),
                new Node("receipt", "原件签收", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_" + finance, "expenseStage", "RECEIPT")),
                new Node("finance", "财务审核", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_" + finance, "expenseStage", "FINANCE_REVIEW")),
                new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("a", "start", "business", "", false), new Edge("b", "business", "receipt", "", false), new Edge("c", "receipt", "finance", "", false), new Edge("d", "finance", "end", "", false)));
        var schema = new FormSchema(2, List.of(new FormSchema.Field(ExpenseFormContract.DETAILS, "费用明细", FormSchema.FieldType.TEXT, true,
                null, null, null, null, null, null, null, true, Map.of("business", hidden ? FieldVisibility.HIDDEN : FieldVisibility.READ_ONLY, "receipt", FieldVisibility.READ_ONLY, "finance", FieldVisibility.READ_ONLY)),
                field("amount", FormSchema.FieldType.NUMBER), field("currency", FormSchema.FieldType.TEXT), field("overPolicy", FormSchema.FieldType.BOOLEAN)));
        var draft = definitions.create("demo", "risk-http-" + UUID.randomUUID(), "合成风险审批", graph, schema, null); var published = definitions.publish(ADMIN, draft.id(), draft.revision(), "合成验收");
        var amount = new Money(new BigDecimal("100"), "CNY");
        var line = new ExpenseLine(1, "OFFICE", LocalDate.now(), null, "SH", BigDecimal.ONE, ExpenseLine.Unit.ITEM, amount, new Money(new BigDecimal("6"), "CNY"), List.of(), null,
                List.of(new CostAllocation("IT", null, amount)), "不可发送私人说明", null);
        var content = new ExpenseContent(entity, ExpenseContent.Type.DAILY, "不可发送费用标题", List.of(line), List.of());
        var created = send("/api/v1/expense-reports", "alice", Map.of("businessNo", "RISK-" + UUID.randomUUID(), "processKey", published.key(), "definitionVersion", published.version(), "content", content), UUID.randomUUID().toString(), 201);
        var report = reports.find("demo", UUID.fromString(created.path("id").asText())).orElseThrow();
        var checked = send(expensePath(report) + "/precheck", "alice", Map.of("applicationVersion", application(report).version(), "financialVersion", report.version(), "initiatorAppointmentId", appointment,
                "accountingDate", LocalDate.now(), "targetDigest", financeConfiguration.destination("demo").orElseThrow().digest("demo")), UUID.randomUUID().toString(), 202);
        UUID checkId = UUID.fromString(checked.path("id").asText()); precheckWorker.poll();
        var job = prechecks.find("demo", checkId).orElseThrow(); assertThat(job.status()).as(json.write(job.result())).isEqualTo(ExpensePrecheckJob.Status.READY);
        send(expensePath(report) + "/submit", "alice", Map.of("applicationVersion", application(report).version(), "financialVersion", reports.find("demo", report.id()).orElseThrow().version(), "precheckId", checkId), UUID.randomUUID().toString(), 200);
        UUID operationId = occupations.find("demo", report.id()).orElseThrow().pendingOperationId();
        if (operationId != null) {
            var operation = operations.find("demo", operationId).orElseThrow(); Instant at = operation.nextAttemptAt().isAfter(Instant.now()) ? operation.nextAttemptAt() : Instant.now();
            var claimed = budgetExecution.claim("demo", operationId, at); var input = claimed.input();
            var result = claimed.status() == BudgetOperation.Status.QUERYING ? budgetPort.query(input.targetDigest(), input.command()) : budgetPort.execute(input.targetDigest(), input.command());
            Instant finished = Instant.now(); budgetExecution.finish(claimed, result, (finished.isAfter(at) ? finished : at).plusMillis(1));
        }
        assertThat(task(report).getTaskDefinitionKey()).isEqualTo("business"); return reports.find("demo", report.id()).orElseThrow();
    }
    private FormSchema.Field field(String key, FormSchema.FieldType type) { return new FormSchema.Field(key, key, type, true, null, null, null, null, null); }
    private UUID person(String user, boolean approver) { var found = jdbc.queryForList("SELECT id FROM organization_person WHERE tenant_id='demo' AND subject=?", String.class, user); return found.isEmpty() ? organization.createPerson(ADMIN, user, "合成" + user, true, approver).id() : UUID.fromString(found.get(0)); }
    private Application application(ExpenseReport report) { return applications.findById("demo", report.applicationId()).orElseThrow(); }
    private Task task(ExpenseReport report) { return tasks.createTaskQuery().processVariableValueEquals("applicationId", report.applicationId().toString()).singleResult(); }
    private void approve(ExpenseReport report) throws Exception { send("/api/v1/tasks/" + task(report).getId() + "/actions", "manager", Map.of("action", "APPROVE", "expectedVersion", application(report).version(), "comment", "正常审批"), UUID.randomUUID().toString(), 200); }
    private Map<String, Object> scope(ExpenseReport... reports) { var scope = new HashMap<String, Object>(); scope.put("documents", java.util.Arrays.stream(reports).map(r -> Map.of("reportId", r.id(), "roundNo", 1, "lineNos", List.of(1))).toList()); return scope; }
    private Map<String, Object> generation(String task, Map<String, Object> scope, JsonNode options) { return Map.of("taskId", task, "scope", scope, "inputDigest", options.path("inputDigest").asText(), "targetDigest", options.path("targetDigest").asText(), "sourceIds", java.util.stream.StreamSupport.stream(options.path("sources").spliterator(), false).map(s -> s.path("reference").path("sourceId").asText()).toList()); }
    private JsonNode preview(ExpenseReport report, String task, Map<String, Object> scope, String user, int expected) throws Exception { return response(postBody(path(report) + "/input", user, Map.of("taskId", task, "scope", scope)), expected); }
    private UUID queue(ExpenseReport report, Map<String, Object> scope) throws Exception { String task = task(report).getId(); return remember(send(path(report), "manager", generation(task, scope, preview(report, task, scope, "manager", 200)), UUID.randomUUID().toString(), 202)); }
    private UUID remember(JsonNode result) { UUID id = UUID.fromString(result.path("id").asText()); queued.add(id); return id; }
    private List<String> concernIds(JsonNode detail) { return java.util.stream.StreamSupport.stream(detail.path("concerns").spliterator(), false).map(c -> c.path("sourceId").asText()).toList(); }
    private String token(String user) { return "Bearer " + tokens.computeIfAbsent(user, u -> auth.login("demo", u, "demo").token()); }
    private org.springframework.mock.web.MockHttpServletResponse postBody(String path, String user, Object body) throws Exception { return mvc.perform(post(path).header("Authorization", token(user)).contentType("application/json").content(json.write(body))).andReturn().getResponse(); }
    private JsonNode send(String path, String user, Object body, String key, int expected) throws Exception { return response(mvc.perform(post(path).header("Authorization", token(user)).header("Idempotency-Key", key).contentType("application/json").content(json.write(body))).andReturn().getResponse(), expected); }
    private JsonNode read(String path, String user, int expected) throws Exception { return response(mvc.perform(get(path).header("Authorization", token(user))).andReturn().getResponse(), expected); }
    private JsonNode response(org.springframework.mock.web.MockHttpServletResponse response, int expected) throws Exception { assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected); if (expected < 300) assertThat(response.getHeader("Cache-Control")).contains("no-store"); return json.read(response.getContentAsString(StandardCharsets.UTF_8), JsonNode.class); }
    private List<Object> businessState(ExpenseReport... selected) { var facts = new ArrayList<Object>(); for (var report : selected) { facts.add(reports.find("demo", report.id()).orElseThrow().state()); var app = application(report); facts.add(List.of(app.version(), app.status(), task(report).getId())); facts.add(occupations.find("demo", report.id()).orElseThrow()); } return facts; }
    private static String expensePath(ExpenseReport report) { return "/api/v1/expense-reports/" + report.id(); }
    private static String path(ExpenseReport report) { return expensePath(report) + "/risk-explanations"; }

    private static HttpServer modelServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(HTTP_THREADS);
            server.createContext("/v1/chat/completions", exchange -> {
                try {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8); LAST_MODEL.set(body); MODEL_CALLS.incrementAndGet();
                    var request = wire.read(body, JsonNode.class); var input = wire.read(request.at("/messages/1/content").asText(), JsonNode.class);
                    var evidence = java.util.stream.StreamSupport.stream(input.path("sources").spliterator(), false).map(s -> s.path("reference")).toList();
                    var items = java.util.stream.StreamSupport.stream(input.path("concerns").spliterator(), false).map(c -> Map.of("concernSourceId", c.path("sourceId").asText(), "kind", c.path("kind").asText(),
                            "explanation", "合成解释：所选事实值得人工核对", "limitations", "不证明重复报销或规避审批", "checks", List.of("核对用途与实际行程"), "evidence", evidence)).toList();
                    byte[] result = wire.write(Map.of("model", "synthetic-risk-v1", "choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("role", "assistant", "content", wire.write(Map.of("items", items))))))).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, result.length); exchange.getResponseBody().write(result);
                } finally { exchange.close(); }
            }); server.start(); return server;
        } catch (java.io.IOException failure) { throw new IllegalStateException("Cannot start synthetic risk model", failure); }
    }
}
