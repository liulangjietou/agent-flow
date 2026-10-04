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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 原轮次读取复用真实申请参与事实和敏感字段规则，不以角色或测试桩代替逐单授权。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:expense-split-read;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "agentflow.auth.demo-enabled=true",
        "agentflow.finance-gateway.enabled=true", "agentflow.timers.enabled=false", "agentflow.sla.reminders-enabled=false",
        "agentflow.vouchers.preparation-worker-enabled=false", "agentflow.vouchers.worker-enabled=false", "agentflow.payments.worker-enabled=false",
        "agentflow.payments.request-worker-enabled=false", "agentflow.invoices.verification-worker-enabled=false",
        "agentflow.expenses.precheck-worker-enabled=false", "agentflow.budgets.worker-enabled=false",
        "agentflow.expenses.settlement-worker-enabled=false", "agentflow.expenses.archive-worker-enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExpenseSplitRoutingReadIntegrationTest {
    private static final Actor ADMIN = new Actor("demo", "admin", Set.of("ADMIN", "PROCESS_ADMIN"));
    private static final java.nio.file.Path RESPONSES = java.nio.file.Path.of("/fyoung/tmp/agentflow-remaining-20260928/f04-read-responses-" + UUID.randomUUID());
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
    @Autowired JdbcTemplate jdbc;
    @Autowired io.agentflow.expense.ExpenseSplitRoutingQueries queries;

    @DynamicPropertySource static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("agentflow.finance-gateway.tenants.demo.endpoint", GATEWAY::endpoint);
        registry.add("agentflow.finance-gateway.tenants.demo.allow-unauthenticated-loopback", () -> true);
        registry.add("agentflow.attachments.directory", () -> "/fyoung/tmp/agentflow-split-read-http-fixtures");
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
    @AfterAll static void stopOwnedServer() { GATEWAY.close(); System.out.println("Split routing MVC responses: " + RESPONSES); }

    @Test void fullyReadableOriginalEvidenceRetainsExactFinancialVersionsWithoutWritingBusinessTables() throws Exception {
        var first = draft(definition("ENABLED")); submit(first);
        var second = draft(definition("ENABLED")); submit(second);
        var before = businessState();
        var clear = read(first, 1, "manager", 200);
        assertThat(clear.path("status").asText()).isEqualTo("CLEAR");
        var response = read(second, 1, "manager", 200);
        assertThat(response.path("reportId").asText()).isEqualTo(second.id().toString());
        assertThat(response.path("applicationId").asText()).isEqualTo(second.applicationId().toString());
        assertThat(response.path("roundNo").asInt()).isEqualTo(1);
        assertThat(response.path("status").asText()).isEqualTo("SPLIT_SUSPECTED");
        assertThat(response.path("sourcesReadable").asBoolean()).isTrue();
        assertThat(response.path("details")).isEqualTo(json.read(json.write(snapshot(second, 1)), JsonNode.class));
        assertThat(response.at("/details/assessment/routingAmount/value").asText()).isEqualTo("8000.00");
        assertThat(response.at("/details/assessment/sources")).hasSize(2);
        assertThat(read(second, 1, "alice", 200)).isEqualTo(response);
        assertThat(businessState()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"UNCONFIGURED", "DISABLED"})
    void missingConfigurationAndExplicitDisableAreNotReportedAsClear(String mode) throws Exception {
        var report = draft(definition(mode)); submit(report);
        var response = read(report, 1, "manager", 200);
        assertThat(response.path("status").asText()).isEqualTo(mode);
        assertThat(response.path("sourcesReadable").asBoolean()).isTrue();
        assertThat(response.path("details")).isEqualTo(json.read(json.write(snapshot(report, 1)), JsonNode.class));
    }

    @Test void historicalRoundWithoutPreparedRecordIsNotMisrepresentedAsChecked() throws Exception {
        var report = draft(definition("UNCONFIGURED")); submit(report);
        // 模拟 V115 的已提交原轮次：只移除新功能记录，保留实际审批与财务事实。
        assertThat(jdbc.update("DELETE FROM expense_split_routing WHERE tenant_id='demo' AND report_id=?", report.id().toString())).isEqualTo(1);
        var before = businessState(); var response = read(report, 1, "manager", 200);
        assertThat(response.path("status").asText()).isEqualTo("NOT_RECORDED");
        assertThat(response.path("sourcesReadable").asBoolean()).isFalse();
        assertThat(response.has("details")).isTrue(); assertThat(response.path("details").isNull()).isTrue();
        assertThat(businessState()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"HIDDEN", "MASKED"})
    void anyRestrictedComparisonHidesAllAmountsCountsSourceIdsAndRiskOutcome(String visibility) throws Exception {
        var source = draft(definition("ENABLED", FieldVisibility.valueOf(visibility), manager)); submit(source);
        var primary = draft(definition("ENABLED")); submit(primary);
        restricted(read(primary, 1, "manager", 200), primary);
        var applicant = read(primary, 1, "alice", 200);
        assertThat(applicant.path("status").asText()).isEqualTo("SPLIT_SUSPECTED");
        assertThat(applicant.at("/details/assessment/sources")).hasSize(2);
    }

    @Test void invisibleComparisonReturnsOnlyRestrictedStateWithoutExposingItsExistence() throws Exception {
        var source = draft(definition("ENABLED", FieldVisibility.READ_ONLY, finance)); submit(source);
        var primary = draft(definition("ENABLED")); submit(primary);
        read(source, 1, "manager", 404);
        restricted(read(primary, 1, "manager", 200), primary);
    }

    @Test void administratorWhoCanReadPrimaryCannotBypassComparisonSensitiveFields() throws Exception {
        var adminPerson = person("admin", true);
        var source = draft(definition("ENABLED", FieldVisibility.HIDDEN, adminPerson)); submit(source);
        var primary = draft(definition("ENABLED", FieldVisibility.READ_ONLY, adminPerson)); submit(primary);
        read(source, 1, "admin", 403);
        restricted(read(primary, 1, "admin", 200), primary);
    }

    @Test void primaryAuthorizationPrecedesSnapshotLookupAndAdministrativeRolesDoNotOverrideIt() throws Exception {
        var hidden = draft(definition("ENABLED", FieldVisibility.HIDDEN, manager)); submit(hidden);
        read(hidden, 1, "manager", 403); read(hidden, 1, "admin", 403); read(hidden, 1, "bob", 404);
        read(hidden, 99, "alice", 404);
        read(draft(definition("ENABLED")), 1, "alice", 404);
        request("/api/v1/expense-reports/" + UUID.randomUUID() + "/split-routing?roundNo=1", "admin", 404);
        read(hidden, 1, "alice", 200);
    }

    @Test void aLaterReadableSourceRoundCannotAuthorizeTheHiddenOriginalRound() throws Exception {
        var source = draft(definition("ENABLED", FieldVisibility.HIDDEN, manager)); submit(source);
        var primary = draft(definition("ENABLED", FieldVisibility.READ_ONLY, finance)); submit(primary);
        var original = snapshot(primary, 1);
        send(path(source) + "/withdraw", "alice", lifecycle(source), 200); budgets(source);
        submit(source); approve(source, "manager");
        assertThat(task(source).getTaskDefinitionKey()).isEqualTo("higher"); approve(source, "manager");
        assertThat(task(source).getTaskDefinitionKey()).isEqualTo("receipt");
        request(path(source) + "?roundNo=2", "finance", 200);
        restricted(read(primary, 1, "finance", 200), primary);
        assertThat(snapshot(primary, 1)).isEqualTo(original);
        assertThat(read(primary, 1, "alice", 200).at("/details/assessment/sources/1/roundNo").asInt()).isEqualTo(1);
    }

    @Test void accessIsRecheckedAfterSourceWithdrawalAndOldSnapshotIsStillAvailableToApplicant() throws Exception {
        var source = draft(definition("ENABLED")); submit(source);
        var primary = draft(definition("ENABLED")); submit(primary);
        assertThat(read(primary, 1, "manager", 200).path("status").asText()).isEqualTo("SPLIT_SUSPECTED");
        send(path(source) + "/withdraw", "alice", lifecycle(source), 200); budgets(source);
        restricted(read(primary, 1, "manager", 200), primary);
        assertThat(read(primary, 1, "alice", 200).at("/details/assessment/routingAmount/value").asText()).isEqualTo("8000.00");
    }

    @Test void oldAndResubmittedPrimaryRoundsRetainSeparateReadBindings() throws Exception {
        var report = draft(definition("ENABLED")); submit(report);
        var first = read(report, 1, "alice", 200);
        send(path(report) + "/withdraw", "alice", lifecycle(report), 200); budgets(report);
        var other = draft(definition("ENABLED")); submit(other); submit(report);
        assertThat(read(report, 1, "alice", 200)).isEqualTo(first);
        var second = read(report, 2, "alice", 200);
        assertThat(second.path("roundNo").asInt()).isEqualTo(2);
        assertThat(second.path("status").asText()).isEqualTo("SPLIT_SUSPECTED");
        assertThat(second.at("/details/primary/roundNo").asInt()).isEqualTo(2);
    }

    @Test void queryRejectsDuplicateMalformedAndAuthorityParametersAndRequiresAuthentication() throws Exception {
        var report = draft(definition("ENABLED")); submit(report);
        String endpoint = path(report) + "/split-routing";
        for (String query : List.of("", "?roundNo=", "?roundNo=0", "?roundNo=-1", "?roundNo=01", "?roundNo=1.0",
                "?roundNo=2147483648", "?roundNo=1&roundNo=1", "?roundNo=1&tenantId=foreign", "?roundNo=1&userId=alice", "?roundNo=1&amount=0")) {
            var response = request(endpoint + query, "alice", 400);
            assertThat(response.path("code").asText()).isEqualTo("INVALID_EXPENSE_QUERY");
        }
        assertThat(mvc.perform(get(endpoint + "?roundNo=1")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test void aForeignTenantCannotReadAnExistingReportEvenWithTheSameApplicantAndAdminRole() throws Exception {
        var report = draft(definition("ENABLED")); submit(report);
        actors.set(new Actor("foreign", "alice", Set.of("ADMIN", "APPROVER")));
        assertThatThrownBy(() -> queries.read(report.id(), 1)).isInstanceOf(io.agentflow.common.DomainException.class)
                .satisfies(error -> assertThat(((io.agentflow.common.DomainException) error).code()).isEqualTo("NOT_FOUND"));
        actors.clear();
    }

    private void restricted(JsonNode response, ExpenseReport primary) {
        var expected = new HashMap<String, Object>();
        expected.put("reportId", primary.id().toString()); expected.put("applicationId", primary.applicationId().toString()); expected.put("roundNo", 1);
        expected.put("status", "RESTRICTED"); expected.put("sourcesReadable", false); expected.put("details", null);
        assertThat(json.map(response.toString())).containsExactlyInAnyOrderEntriesOf(expected);
    }
    private JsonNode read(ExpenseReport report, int roundNo, String user, int expected) throws Exception {
        return request(path(report) + "/split-routing?roundNo=" + roundNo, user, expected);
    }
    private JsonNode request(String endpoint, String user, int expected) throws Exception {
        var response = mvc.perform(get(endpoint).header("Authorization", "Bearer " + tokens.computeIfAbsent(user, value -> auth.login("demo", value, "demo").token())))
                .andReturn().getResponse();
        assertThat(response.getStatus()).as(endpoint + " " + response.getContentAsString()).isEqualTo(expected);
        if (expected == 200 && endpoint.contains("/split-routing")) assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        var body = json.read(response.getContentAsString(StandardCharsets.UTF_8), JsonNode.class);
        if (endpoint.contains("/split-routing")) {
            java.nio.file.Files.createDirectories(RESPONSES);
            java.nio.file.Files.writeString(RESPONSES.resolve(UUID.randomUUID() + ".json"), json.write(Map.of(
                    "path", endpoint, "status", response.getStatus(), "response", body)));
        }
        return body;
    }
    private String businessState() {
        var snapshot = new java.util.TreeMap<String, List<String>>();
        for (String table : List.of("expense_report", "expense_report_revision", "approval_application", "approval_submission_round",
                "expense_split_routing", "expense_split_routing_source", "budget_occupation", "budget_operation")) {
            var rows = jdbc.query("SELECT * FROM " + table, (row, index) -> {
                var values = new java.util.TreeMap<String, String>(); var metadata = row.getMetaData();
                for (int column = 1; column <= metadata.getColumnCount(); column++) values.put(metadata.getColumnName(column), row.getString(column));
                return json.write(values);
            });
            snapshot.put(table, rows.stream().sorted().toList());
        }
        return json.write(snapshot);
    }

    private DefinitionDraft definition(String mode) {
        return definition(mode, FieldVisibility.READ_ONLY, manager);
    }
    private DefinitionDraft definition(String mode, FieldVisibility visibility, UUID reviewer) {
        String key = "split-read-" + UUID.randomUUID(), threshold = "5000";
        var settings = mode.equals("UNCONFIGURED") ? Map.<String, String>of() : Map.of("expenseSplitRisk", mode,
                "expenseSplitWindowDays", "7", "expenseSplitThreshold", threshold, "expenseSplitCurrency", "CNY");
        var graph = new Graph(List.of(new Node("start", "开始", NodeType.START, settings),
                new Node("business", "业务审批", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_" + reviewer)),
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
                null, null, null, null, null, null, null, true, Map.of("business", visibility, "higher", FieldVisibility.READ_ONLY,
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
