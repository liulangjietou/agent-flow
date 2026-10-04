package io.agentflow.approval;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.auth.AuthService;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 原生审批、认证与字段投影共同验证报表范围；财务来源仅使用明确的本地合成端口。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:expense-reporting;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "agentflow.auth.demo-enabled=true",
        "agentflow.finance-gateway.enabled=true", "agentflow.timers.enabled=false", "agentflow.sla.reminders-enabled=false",
        "agentflow.vouchers.preparation-worker-enabled=false", "agentflow.vouchers.worker-enabled=false", "agentflow.payments.worker-enabled=false",
        "agentflow.payments.request-worker-enabled=false", "agentflow.invoices.verification-worker-enabled=false",
        "agentflow.expenses.precheck-worker-enabled=false", "agentflow.budgets.worker-enabled=false",
        "agentflow.expenses.budget-review-worker-enabled=false", "agentflow.expenses.settlement-worker-enabled=false", "agentflow.expenses.archive-worker-enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExpenseFinancialReportingIntegrationTest {
    private static final Actor ADMIN = new Actor("demo", "admin", Set.of("ADMIN", "PROCESS_ADMIN"));
    private static final String ENDPOINT = "/api/v1/reports/expense-finance";
    private static final ExpenseSubprocessGatewayFixture GATEWAY = new ExpenseSubprocessGatewayFixture();
    private UUID entity, department, appointment, manager, finance;
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
    @Autowired io.agentflow.expense.reporting.ExpenseFinancialReportQuery reporting;

    @DynamicPropertySource static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("agentflow.finance-gateway.tenants.demo.endpoint", GATEWAY::endpoint);
        registry.add("agentflow.finance-gateway.tenants.demo.allow-unauthenticated-loopback", () -> true);
        registry.add("agentflow.attachments.directory", () -> "/fyoung/tmp/agentflow-financial-reporting-http-fixtures");
    }
    @BeforeEach void source() {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM organization_directory WHERE tenant_id='demo'", Integer.class) == 0) organization.initialize(ADMIN);
        entity = organization.createUnit(ADMIN, OrganizationUnit.Kind.LEGAL_ENTITY, "报表原法人", null, null, true).id();
        department = organization.createUnit(ADMIN, OrganizationUnit.Kind.DEPARTMENT, "报表原部门", entity, null, true).id();
        var position = organization.createUnit(ADMIN, OrganizationUnit.Kind.POSITION, "报表岗位", entity, null, true);
        appointment = organization.createAppointment(ADMIN, person("alice"), department, position.id(), true).id();
        manager = person("manager"); finance = person("finance");
        GATEWAY.use(json, entity, "76543210987654321000");
    }
    @AfterEach void clear() { actors.clear(); assertThat(GATEWAY.failure()).isNull(); }
    @AfterAll static void stopOwnedGateway() { GATEWAY.close(); }

    @Test void emptyReportRequiresFinanceAndRejectsInvalidOrRepeatedFilters() throws Exception {
        var report = report("");
        assertThat(report.at("/totals/submitted").asLong()).isZero();
        assertThat(report.at("/totals/approval/p50Seconds").isNull()).isTrue();
        assertThat(report.path("scope").asText()).isEqualTo("CURRENT_ACTOR_READABLE");
        assertThat(report.at("/activity/duplicateSubmissions/containsUnrecordedHistory").asBoolean()).isTrue();
        for (String user : List.of("alice", "manager")) request(ENDPOINT, user, 403);
        assertThat(request(ENDPOINT, "admin", 200).at("/totals/submitted").asLong()).isZero();
        for (String query : List.of("?from=invalid", "?from=2020-01-01&to=2026-10-01", "?tenantId=other", "?legalEntityId=1-1-1-1-1", "?categoryCode=", "?from=2026-01-01&from=2026-01-02"))
            request(ENDPOINT+query, "finance", 400);
    }

    @Test void reportRequiresOriginalRoundAccessAndKeepsOriginalOrganization() throws Exception {
        var expense = draft(FieldVisibility.READ_ONLY); submit(expense);
        assertThat(report("").at("/totals/submitted").asLong()).isZero();
        action(expense, "manager", "APPROVE");
        int writes = GATEWAY.writes(), queries = GATEWAY.queries();
        var before = report("");
        assertThat(before.at("/totals/submitted").asLong()).isOne();
        assertThat(before.at("/totals/inApproval").asLong()).isOne();
        assertThat(before.at("/totals/amounts/0/claimed").asText()).isEqualTo("100.00");
        assertThat(before.path("groups").toString()).contains("报表原法人", "报表原部门", "OFFICE");
        jdbc.update("UPDATE organization_unit SET name='目录现名称' WHERE tenant_id='demo' AND id IN (?,?)", entity.toString(), department.toString());
        var after = report("&departmentId="+department+"&categoryCode=OFFICE");
        assertThat(after.path("totals")).isEqualTo(before.path("totals"));
        assertThat(after.path("groups")).isEqualTo(before.path("groups"));
        assertThat(report("&departmentId="+UUID.randomUUID()).at("/totals/submitted").asLong()).isZero();
        assertThat(report("&categoryCode=TRAVEL").at("/totals/submitted").asLong()).isZero();
        assertThat(GATEWAY.writes()).isEqualTo(writes); assertThat(GATEWAY.queries()).isEqualTo(queries);
    }

    @ParameterizedTest @EnumSource(value = FieldVisibility.class, names = {"HIDDEN", "MASKED"})
    void hiddenAndMaskedFinancialDetailsNeverContributeAmountsOrExistence(FieldVisibility visibility) throws Exception {
        var expense = draft(visibility); submit(expense);
        request("/api/v1/expense-reports/"+expense.id()+"?roundNo=1", "finance", 403);
        var report = report("");
        assertThat(report.at("/totals/submitted").asLong()).isZero(); assertThat(report.at("/totals/amounts")).isEmpty();
        assertThat(report.path("groups")).isEmpty(); assertThat(report.toString()).doesNotContain(expense.id().toString());
    }

    @Test void returnedAndResubmittedRoundsUseTheirOwnPermissionsAndDenominator() throws Exception {
        var expense = draft(FieldVisibility.READ_ONLY); submit(expense); action(expense, "manager", "APPROVE"); action(expense, "finance", "RETURN");
        var returned = report("");
        assertThat(returned.at("/totals/returned").asLong()).isOne();
        assertThat(returned.at("/totals/returnRate").decimalValue()).isEqualByComparingTo("1");
        assertThat(returned.at("/totals/returnReasons/0/reason").asText()).isEqualTo("报表合成原因");
        submit(expense);
        assertThat(report("").at("/totals/submitted").asLong()).isOne();
        action(expense, "manager", "APPROVE"); var both = report("");
        assertThat(both.at("/totals/submitted").asLong()).isEqualTo(2);
        assertThat(both.at("/totals/returned").asLong()).isOne(); assertThat(both.at("/totals/inApproval").asLong()).isOne();
        assertThat(both.at("/totals/returnRate").decimalValue()).isEqualByComparingTo("1");
    }

    @Test void financeRoleDoesNotBypassAdministratorSensitiveRulesOrTenantIsolation() throws Exception {
        var expense = draft(FieldVisibility.READ_ONLY); submit(expense); action(expense, "manager", "APPROVE");
        assertThat(report("").at("/totals/submitted").asLong()).isOne();
        var day = Instant.now().atOffset(java.time.ZoneOffset.UTC).toLocalDate();
        var query = new io.agentflow.expense.reporting.ExpenseReportQueryParameters.Query(day, day, entity, null, null);
        for (var actor : List.of(new Actor("demo", "admin", Set.of("ADMIN", "FINANCE", "APPROVER")),
                new Actor("another-tenant", "finance", Set.of("FINANCE", "APPROVER")))) {
            actors.set(actor);
            try {
                var result = reporting.read(query, Instant.now());
                assertThat(result.totals().submitted()).isZero(); assertThat(result.totals().amounts()).isEmpty();
                assertThat(result.groups()).isEmpty(); assertThat(result.activity().duplicateSubmissions().recorded()).isZero();
            } finally { actors.clear(); }
        }
    }

    @Test void unrecordedOriginalOrganizationStaysUnknownInsteadOfUsingCurrentDirectory() throws Exception {
        var expense = draft(FieldVisibility.READ_ONLY); submit(expense); action(expense, "manager", "APPROVE");
        jdbc.update("UPDATE approval_submission_round SET initiator_context_json=NULL,initiator_legal_entity_name=NULL,initiator_department_name=NULL,initiator_position_name=NULL "
                + "WHERE tenant_id='demo' AND application_id=? AND round_no=1", expense.applicationId().toString());
        var all = request(ENDPOINT, "finance", 200);
        var unknown = java.util.stream.StreamSupport.stream(all.path("groups").spliterator(), false)
                .filter(group -> "LEGAL_ENTITY".equals(group.path("dimension").asText()) && group.path("code").isNull()).findFirst().orElseThrow();
        assertThat(unknown.path("name").isNull()).isTrue(); assertThat(unknown.at("/metrics/submitted").asLong()).isOne();
        assertThat(report("").at("/totals/submitted").asLong()).isZero();
    }

    private ExpenseReport draft(FieldVisibility visibility) throws Exception {
        var graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("business", "业务审批", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_"+(visibility == FieldVisibility.READ_ONLY ? manager : finance))),
                new Node("receipt", "签收", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_"+finance, "expenseStage", "RECEIPT")),
                new Node("finance", "财务", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_"+finance, "expenseStage", "FINANCE_REVIEW")),
                new Node("end", "结束", NodeType.END, Map.of())), List.of(new Edge("a", "start", "business", ""),
                new Edge("b", "business", "receipt", ""), new Edge("c", "receipt", "finance", ""), new Edge("d", "finance", "end", "")));
        var schema = new FormSchema(2, List.of(new FormSchema.Field("expenseDetails", "费用明细", FormSchema.FieldType.TEXT, true,
                null, null, null, null, null, null, null, true, Map.of("business", visibility, "receipt", FieldVisibility.READ_ONLY, "finance", FieldVisibility.READ_ONLY)),
                field("amount", FormSchema.FieldType.NUMBER), field("currency", FormSchema.FieldType.TEXT), field("overPolicy", FormSchema.FieldType.BOOLEAN)));
        var draft = definitions.create("demo", "financial-report-"+UUID.randomUUID(), "报表合成流程", graph, schema, null);
        var definition = definitions.publish(ADMIN, draft.id(), draft.revision(), "报表合成验收");
        var line = new ExpenseLine(1, "OFFICE", LocalDate.now(), null, "SH", BigDecimal.ONE, ExpenseLine.Unit.ITEM, money("100"), money("6"),
                List.of(), null, List.of(new CostAllocation("IT", null, money("100"))), "报表合成明细", null);
        var created = send("/api/v1/expense-reports", "alice", Map.of("businessNo", "REPORT-"+UUID.randomUUID(), "processKey", definition.key(),
                "definitionVersion", definition.version(), "content", new ExpenseContent(entity, ExpenseContent.Type.DAILY, "报表合成报销", List.of(line), List.of())), 201);
        return reports.find("demo", UUID.fromString(created.path("id").asText())).orElseThrow();
    }
    private void submit(ExpenseReport expense) throws Exception {
        String path = "/api/v1/expense-reports/"+expense.id();
        long version = reports.find("demo", expense.id()).orElseThrow().version();
        var response = send(path+"/precheck", "alice", Map.of("applicationVersion", application(expense).version(), "financialVersion", version,
                "initiatorAppointmentId", appointment, "accountingDate", LocalDate.now(), "targetDigest", financeConfiguration.destination("demo").orElseThrow().digest("demo")), 202);
        precheckWorker.poll(); var checked = prechecks.find("demo", UUID.fromString(response.path("id").asText())).orElseThrow();
        assertThat(checked.status()).as(json.write(checked.result())).isEqualTo(ExpensePrecheckJob.Status.READY);
        send(path+"/submit", "alice", Map.of("applicationVersion", application(expense).version(), "financialVersion", version, "precheckId", checked.input().id()), 200);
        UUID id = occupations.find("demo", expense.id()).orElseThrow().pendingOperationId();
        var operation = operations.find("demo", id).orElseThrow();
        Instant now = operation.nextAttemptAt().isAfter(Instant.now()) ? operation.nextAttemptAt() : Instant.now();
        var claimed = budgetExecution.claim("demo", id, now); var input = claimed.input();
        var result = budgetPort.execute(input.targetDigest(), input.command());
        budgetExecution.finish(claimed, result, Instant.now().isAfter(now) ? Instant.now() : now.plusMillis(1));
    }
    private void action(ExpenseReport expense, String user, String action) throws Exception {
        var task = tasks.createTaskQuery().processVariableValueEquals("applicationId", expense.applicationId().toString()).singleResult();
        send("/api/v1/tasks/"+task.getId()+"/actions", user, Map.of("action", action, "comment", "报表合成原因", "expectedVersion", application(expense).version()), 200);
    }
    private JsonNode report(String filters) throws Exception { return request(ENDPOINT+"?legalEntityId="+entity+filters, "finance", 200); }
    private Application application(ExpenseReport expense) { return applications.findById("demo", expense.applicationId()).orElseThrow(); }
    private UUID person(String user) {
        var found = jdbc.queryForList("SELECT id FROM organization_person WHERE tenant_id='demo' AND subject=?", String.class, user);
        return found.isEmpty() ? organization.createPerson(ADMIN, user, "合成"+user, true, true).id() : UUID.fromString(found.get(0));
    }
    private JsonNode send(String path, String user, Object body, int expected) throws Exception {
        var response = mvc.perform(post(path).header("Authorization", token(user)).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json").content(json.write(body))).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected);
        return json.read(response.getContentAsString(StandardCharsets.UTF_8), JsonNode.class);
    }
    private JsonNode request(String path, String user, int expected) throws Exception {
        var response = mvc.perform(get(path).header("Authorization", token(user))).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected);
        if (expected == 200) assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        var body = json.read(response.getContentAsString(StandardCharsets.UTF_8), JsonNode.class);
        // 契约验收可显式保存真实控制器响应，常规测试不生成额外文件。
        String archive = System.getProperty("agentflow.test.financial-report-responses");
        if (archive != null) {
            var directory = java.nio.file.Path.of(archive).toAbsolutePath().normalize();
            assertThat(directory.startsWith(java.nio.file.Path.of("/fyoung/tmp"))).isTrue();
            java.nio.file.Files.createDirectories(directory);
            java.nio.file.Files.writeString(directory.resolve(UUID.randomUUID()+".json"),
                    json.write(List.of(Map.of("method", "GET", "path", path, "actor", user, "status", expected, "response", body))));
        }
        return body;
    }
    private String token(String user) { return "Bearer "+auth.login("demo", user, "demo").token(); }
    private static Money money(String amount) { return new Money(new BigDecimal(amount), "CNY"); }
    private static FormSchema.Field field(String key, FormSchema.FieldType type) { return new FormSchema.Field(key, key, type, true, null, null, null, null, null); }
}
