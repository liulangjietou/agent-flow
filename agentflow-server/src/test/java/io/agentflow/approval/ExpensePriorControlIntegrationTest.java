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
import io.agentflow.expense.*;
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
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
 * 真实预检、事务和引擎验证事前累计控制；合成批准额度不代表企业审批数据。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:expense-prior-control-runtime;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "agentflow.auth.demo-enabled=true",
        "agentflow.finance-gateway.enabled=true", "agentflow.timers.enabled=false", "agentflow.sla.reminders-enabled=false",
        "agentflow.vouchers.preparation-worker-enabled=false", "agentflow.vouchers.worker-enabled=false", "agentflow.payments.worker-enabled=false",
        "agentflow.payments.request-worker-enabled=false", "agentflow.invoices.verification-worker-enabled=false",
        "agentflow.expenses.precheck-worker-enabled=false", "agentflow.budgets.worker-enabled=false",
        "agentflow.expenses.settlement-worker-enabled=false", "agentflow.expenses.archive-worker-enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExpensePriorControlIntegrationTest {
    private static final Actor ADMIN = new Actor("demo", "admin", Set.of("ADMIN", "PROCESS_ADMIN"));
    private static final ExpenseSubprocessGatewayFixture GATEWAY = new ExpenseSubprocessGatewayFixture();
    private static final java.nio.file.Path RESPONSES = java.nio.file.Path.of("/fyoung/tmp/agentflow-remaining-20260928/f05-prior-responses-" + UUID.randomUUID());
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
    @Autowired ExpenseRequestRepository requests;
    @Autowired ExpensePrecheckWorker precheckWorker;
    @Autowired JdbcExpensePrecheckRepository prechecks;
    @Autowired FinanceGatewayConfiguration financeConfiguration;
    @Autowired JdbcBudgetOccupationRepository occupations;
    @Autowired JdbcBudgetOperationRepository operations;
    @Autowired BudgetOperationService budgetExecution;
    @Autowired BudgetSystemPort budgetPort;
    @Autowired TaskService tasks;
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcExpensePriorControlRepository controls;

    @DynamicPropertySource static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("agentflow.finance-gateway.tenants.demo.endpoint", GATEWAY::endpoint);
        registry.add("agentflow.finance-gateway.tenants.demo.allow-unauthenticated-loopback", () -> true);
        registry.add("agentflow.attachments.directory", () -> "/fyoung/tmp/agentflow-prior-control-http-fixtures");
    }
    @BeforeEach void organization() {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM organization_directory WHERE tenant_id='demo'", Integer.class) == 0) organization.initialize(ADMIN);
        entity = organization.createUnit(ADMIN, OrganizationUnit.Kind.LEGAL_ENTITY, "合成事前法人", null, null, true).id();
        var department = organization.createUnit(ADMIN, OrganizationUnit.Kind.DEPARTMENT, "合成部门", entity, null, true);
        var position = organization.createUnit(ADMIN, OrganizationUnit.Kind.POSITION, "合成岗位", entity, null, true);
        appointment = organization.createAppointment(ADMIN, person("alice", false), department.id(), position.id(), true).id();
        manager = person("manager", true); finance = person("finance", true);
        GATEWAY.use(json, entity, "76543210987654321000");
    }
    @AfterEach void clearActorAndCheckGateway() { actors.clear(); assertThat(GATEWAY.failure()).isNull(); }
    @AfterAll static void stopOwnedServer() { GATEWAY.close(); }

    @Test void cumulativeExcessWithoutExplanationIsBlockedBeforeReservation() throws Exception {
        var credit = credit(ExpensePriorControl.Mode.TOLERANCE, "80");
        var report = draft(definition(), credit.id(), null);
        var job = check(report);
        assertThat(job.status()).as(json.write(job.result())).isEqualTo(ExpensePrecheckJob.Status.BLOCKED);
        assertThat(job.result().findings()).extracting(ExpensePrecheckJob.Finding::code).contains("PRIOR_REQUEST_EXCEPTION_REASON_REQUIRED");
        assertThat(requests.find("demo", credit.id()).orElseThrow().balance(1).reservations()).isEmpty();
        assertThat(current(report).rounds()).isEmpty();
    }

    @Test void explanationCannotReplaceAnIndependentApprovalNode() throws Exception {
        var credit = credit(ExpensePriorControl.Mode.TOLERANCE, "80");
        var report = draft(definition(), credit.id(), "合成超额说明");
        var job = check(report); assertThat(job.status()).as(json.write(job.result())).isEqualTo(ExpensePrecheckJob.Status.READY);
        var response = raw(path(report) + "/submit", "alice", submitInput(report, job.input().id()));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(422);
        assertThat(json.read(response.getContentAsString(), JsonNode.class).path("code").asText()).isEqualTo("EXPENSE_PRIOR_APPROVAL_REQUIRED");
        assertThat(current(report).rounds()).isEmpty(); assertThat(task(report)).isNull();
        assertThat(requests.find("demo", credit.id()).orElseThrow().balance(1).reservations()).isEmpty();
        assertThat(occupations.find("demo", report.id())).isEmpty();
    }

    @Test void independentReviewRunsBeforePaperAndReductionKeepsOriginalDecisionAndEvidence() throws Exception {
        var credit = credit(ExpensePriorControl.Mode.TOLERANCE, "80");
        var report = draft(definition(true), credit.id(), "合成额度例外"); submit(report);
        var original = controls.find("demo", report.id(), 1).orElseThrow();
        assertThat(original.requiresApproval()).isTrue();
        assertThat(original.assessments().get(0).totalExposure()).isEqualTo(money("100"));
        assertThat(original.assessments().get(0).exceeded()).isEqualTo(money("12"));
        assertThat(application(report).payload()).containsEntry(ExpenseFormContract.PRIOR_OVER_TOLERANCE, true).containsEntry("overPolicy", false);
        approve(report, "manager");
        assertThat(task(report).getTaskDefinitionKey()).as("Same manager must still perform the separate prior review").isEqualTo("prior");
        approve(report, "manager"); assertThat(task(report).getTaskDefinitionKey()).isEqualTo("receipt");
        send(path(report) + "/tasks/" + task(report).getId() + "/receive", "finance", lifecycle(report), 200);
        approve(report, "finance");
        send(path(report) + "/tasks/" + task(report).getId() + "/reduce", "finance", Map.of(
                "applicationVersion", application(report).version(), "financialVersion", current(report).version(),
                "lines", List.of(Map.of("lineNo", 1, "approvedGross", "70.00", "approvedTax", "3.00")),
                "reasonCode", "INELIGIBLE_COST", "comment", "合成核减"), 200);
        budgets(report); approve(report, "finance");
        assertThat(application(report).status()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(application(report).payload()).containsEntry(ExpenseFormContract.PRIOR_OVER_TOLERANCE, true).containsEntry("amount", "70.00");
        assertThat(controls.find("demo", report.id(), 1)).contains(original);
        assertThat(requests.find("demo", credit.id()).orElseThrow().balance(1).reserved()).isEqualTo(money("70"));
        assertThat(read(report, 1, "alice", 200).path("details")).isEqualTo(json.read(json.write(original), JsonNode.class));
    }

    @ParameterizedTest @ValueSource(strings = {"STRICT", "TOLERANCE", "NONE"})
    void withinToleranceAndUncappedUsageKeepTheNormalRoute(String value) throws Exception {
        var mode = ExpensePriorControl.Mode.valueOf(value);
        var credit = credit(mode, mode == ExpensePriorControl.Mode.NONE ? "50" : "100");
        var report = draft(definition(true), credit.id(), null); submit(report); approve(report, "manager");
        assertThat(task(report).getTaskDefinitionKey()).isEqualTo("receipt");
        assertThat(application(report).payload()).containsEntry(ExpenseFormContract.PRIOR_OVER_TOLERANCE, false);
        var frozen = controls.find("demo", report.id(), 1).orElseThrow();
        assertThat(frozen.requiresApproval()).isFalse();
        assertThat(frozen.assessments().get(0).exceeded()).isEqualTo(money(mode == ExpensePriorControl.Mode.NONE ? "50" : "0"));
    }

    @Test void strictControlBlocksEvenWithAnExplanationAndIndependentNode() throws Exception {
        var credit = credit(ExpensePriorControl.Mode.STRICT, "80");
        var report = draft(definition(true), credit.id(), "说明不能解除硬上限");
        var checked = check(report); assertThat(checked.status()).isEqualTo(ExpensePrecheckJob.Status.BLOCKED);
        assertThat(checked.result().findings()).extracting(ExpensePrecheckJob.Finding::code).contains("INSUFFICIENT_FINANCIAL_BALANCE");
        assertThat(controls.find("demo", report.id(), 1)).isEmpty();
    }

    @Test void concurrentPrechecksCannotSilentlyReuseTheSameRemainingTolerance() throws Exception {
        var credit = credit(ExpensePriorControl.Mode.TOLERANCE, "150"); var definition = definition(true);
        var first = draft(definition, credit.id(), "合成共享额度"); var second = draft(definition, credit.id(), "合成共享额度");
        var inputOne = submitInput(first, check(first).input().id()); var inputTwo = submitInput(second, check(second).input().id());
        var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        MockHttpServletResponse one, two;
        try {
            var a = pool.submit(() -> { start.await(); return raw(path(first) + "/submit", "alice", inputOne); });
            var b = pool.submit(() -> { start.await(); return raw(path(second) + "/submit", "alice", inputTwo); });
            start.countDown(); one = a.get(30, TimeUnit.SECONDS); two = b.get(30, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertThat(List.of(one.getStatus(), two.getStatus())).containsExactlyInAnyOrder(200, 422);
        var stale = one.getStatus() == 200 ? two : one;
        assertThat(json.read(stale.getContentAsString(), JsonNode.class).path("code").asText()).isEqualTo("RESOURCES_CHANGED");
        var submitted = one.getStatus() == 200 ? first : second; var retry = one.getStatus() == 200 ? second : first;
        assertThat(controls.find("demo", submitted.id(), 1).orElseThrow().requiresApproval()).isFalse();
        assertThat(controls.find("demo", retry.id(), 1)).isEmpty();
        submit(retry); budgets(submitted); approve(retry, "manager");
        assertThat(task(retry).getTaskDefinitionKey()).isEqualTo("prior");
        var assessment = controls.find("demo", retry.id(), 1).orElseThrow().assessments().get(0);
        assertThat(assessment.otherReserved()).isEqualTo(money("100"));
        assertThat(assessment.totalExposure()).isEqualTo(money("200"));
        assertThat(assessment.exceeded()).isEqualTo(money("35"));
        assertThat(requests.find("demo", credit.id()).orElseThrow().balance(1).reserved()).isEqualTo(money("200"));
    }

    @Test void withdrawnAndResubmittedRoundsRetainSeparateEvidenceWithoutCountingOwnOldUseTwice() throws Exception {
        var credit = credit(ExpensePriorControl.Mode.TOLERANCE, "150"); var definition = definition(true);
        var first = draft(definition, credit.id(), "合成共享额度"); submit(first);
        var original = controls.find("demo", first.id(), 1).orElseThrow();
        send(path(first) + "/withdraw", "alice", lifecycle(first), 200); budgets(first);
        var second = draft(definition, credit.id(), "合成共享额度"); submit(second); submit(first);
        assertThat(controls.find("demo", first.id(), 1)).contains(original);
        var revised = controls.find("demo", first.id(), 2).orElseThrow();
        assertThat(revised.assessments().get(0).roundReserved()).isEqualTo(money("100"));
        assertThat(revised.assessments().get(0).totalExposure()).isEqualTo(money("200"));
        assertThat(read(first, 1, "alice", 200).path("requiresApproval").asBoolean()).isFalse();
        assertThat(read(first, 2, "alice", 200).path("requiresApproval").asBoolean()).isTrue();
    }

    @Test void failureAfterStartingTheEngineRollsBackEvidenceAndResourceAndReplaysOriginalKey() throws Exception {
        var credit = credit(ExpensePriorControl.Mode.TOLERANCE, "80"); var report = draft(definition(true), credit.id(), "合成额度例外");
        var input = submitInput(report, check(report).input().id()); String key = UUID.randomUUID().toString();
        jdbc.execute("ALTER TABLE budget_occupation ADD CONSTRAINT prior_fixture_budget_failure CHECK(report_id<>'" + report.id() + "')");
        try {
            assertThatThrownBy(() -> raw(path(report) + "/submit", "alice", input, key)).isInstanceOf(jakarta.servlet.ServletException.class)
                    .hasCauseInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(controls.find("demo", report.id(), 1)).isEmpty(); assertThat(current(report).rounds()).isEmpty(); assertThat(task(report)).isNull();
            assertThat(requests.find("demo", credit.id()).orElseThrow().state()).isEqualTo(credit.state());
        } finally { jdbc.execute("ALTER TABLE budget_occupation DROP CONSTRAINT prior_fixture_budget_failure"); }
        var successful = raw(path(report) + "/submit", "alice", input, key); assertThat(successful.getStatus()).as(successful.getContentAsString()).isEqualTo(200);
        var replay = raw(path(report) + "/submit", "alice", input, key);
        assertThat(replay.getStatus()).isEqualTo(200); assertThat(replay.getContentAsString()).isEqualTo(successful.getContentAsString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_prior_control WHERE report_id=?", Integer.class, report.id().toString())).isEqualTo(1);
        assertThat(requests.find("demo", credit.id()).orElseThrow().balance(1).reserved()).isEqualTo(money("100"));
    }

    @ParameterizedTest @ValueSource(strings = {"HIDDEN", "MASKED"})
    void sensitiveEvidenceUsesTheOriginalNodePermissionsIncludingForAdministrators(String visibility) throws Exception {
        var credit = credit(ExpensePriorControl.Mode.NONE, "50");
        var report = draft(definition(false, FieldVisibility.valueOf(visibility)), credit.id(), null); submit(report);
        read(report, 1, "manager", 403); read(report, 1, "admin", 403); read(report, 1, "bob", 404);
        var response = read(report, 1, "alice", 200); assertThat(response.path("status").asText()).isEqualTo("RECORDED");
        assertThat(response.toString()).doesNotContain("reservations", "accountNumber");
        read(report, 99, "alice", 404);
        for (var suffix : List.of("", "?roundNo=0", "?roundNo=01", "?roundNo=1&roundNo=1", "?roundNo=1&user=alice")) {
            request(path(report) + "/prior-control" + suffix, "alice", 400);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"projection", "source", "original"})
    void corruptedStoredFactsFailClosedInsteadOfChangingTheOriginalDecision(String corruption) throws Exception {
        var credit = credit(ExpensePriorControl.Mode.TOLERANCE, "80"); var report = draft(definition(true), credit.id(), "合成额度例外"); submit(report);
        if (corruption.equals("projection")) {
            jdbc.update("UPDATE expense_prior_control SET requires_approval=false WHERE report_id=?", report.id().toString());
        } else if (corruption.equals("source")) {
            jdbc.update("DELETE FROM expense_prior_control_source WHERE report_id=?", report.id().toString());
        } else {
            String raw = jdbc.queryForObject("SELECT snapshot_json FROM expense_prior_control WHERE report_id=?", String.class, report.id().toString());
            var changed = (com.fasterxml.jackson.databind.node.ObjectNode) json.read(raw, JsonNode.class);
            ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("assessments").get(0)).put("requestVersion", 2);
            jdbc.update("UPDATE expense_prior_control SET snapshot_json=? WHERE report_id=?", changed.toString(), report.id().toString());
        }
        assertThatThrownBy(() -> controls.find("demo", report.id(), 1)).isInstanceOf(IllegalStateException.class);
    }

    @Test void selectorDistinguishesReferenceCeilingAndFrozenCategorySource() throws Exception {
        var credit = credit(ExpensePriorControl.Mode.NONE, "50");
        var response = request("/api/v1/expense-requests", "alice", 200);
        var items = response.path("items");
        var selected = java.util.stream.StreamSupport.stream(items.spliterator(), false).filter(item -> item.path("id").asText().equals(credit.id().toString())).findFirst().orElseThrow();
        var line = selected.path("lines").get(0);
        assertThat(line.path("hardLimit").isBoolean()).isTrue(); assertThat(line.path("hardLimit").asBoolean()).isFalse();
        assertThat(line.at("/control/control/mode").asText()).isEqualTo("NONE");
        assertThat(line.at("/control/categoryCode").asText()).isEqualTo("OFFICE");
        assertThat(line.at("/exceeded/value").asText()).isEqualTo("0.00");
    }

    private ExpenseRequest credit(ExpensePriorControl.Mode mode, String amount) {
        var source = Application.restore(UUID.randomUUID(), "demo", "PRIOR-" + UUID.randomUUID(), "prior", 1, "alice", "合成批准事实", Map.of(), ApplicationStatus.APPROVED, 1, 1);
        applications.save(source);
        var control = new ExpensePriorControl(mode, mode == ExpensePriorControl.Mode.TOLERANCE ? new BigDecimal("0.1") : null);
        var value = new ExpenseRequest(UUID.randomUUID(), "demo", source.id(), entity, "alice", List.of(
                new ExpenseRequest.ApprovedLine(1, money(amount), control.referenceFraction(), "synthetic-control",
                        new ExpensePriorControl.Snapshot("OFFICE", 1, control))));
        requests.create(value, "fixture"); return value;
    }
    private DefinitionDraft definition() { return definition(false); }
    private DefinitionDraft definition(boolean controlled) { return definition(controlled, FieldVisibility.READ_ONLY); }
    private DefinitionDraft definition(boolean controlled, FieldVisibility businessVisibility) {
        var nodes = new ArrayList<>(List.of(new Node("start", "开始", NodeType.START, controlled ? Map.of("expenseSplitRisk", "DISABLED") : Map.of()),
                new Node("business", "业务审批", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_" + manager)),
                new Node("receipt", "纸件签收", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_" + finance, "expenseStage", "RECEIPT")),
                new Node("finance", "财务复核", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_" + finance, "expenseStage", "FINANCE_REVIEW")),
                new Node("end", "结束", NodeType.END, Map.of())));
        var edges = new ArrayList<>(List.of(new Edge("a", "start", "business", ""),
                new Edge("b", "business", controlled ? "priorGate" : "receipt", ""), new Edge("c", "receipt", "finance", ""), new Edge("d", "finance", "end", "")));
        var visibility = new HashMap<>(Map.of("business", businessVisibility, "receipt", FieldVisibility.READ_ONLY, "finance", FieldVisibility.READ_ONLY));
        if (controlled) {
            nodes.add(new Node("priorGate", "事前额度", NodeType.EXCLUSIVE_GATEWAY, Map.of()));
            nodes.add(new Node("prior", "事前额度例外审批", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_" + manager, "expenseStage", "PRIOR_REQUEST_REVIEW")));
            edges.add(new Edge("over", "priorGate", "prior", "priorRequestOverTolerance == true"));
            edges.add(new Edge("within", "priorGate", "receipt", "", true)); edges.add(new Edge("reviewed", "prior", "receipt", ""));
            visibility.put("prior", FieldVisibility.READ_ONLY);
        }
        var fields = new ArrayList<>(List.of(new FormSchema.Field("expenseDetails", "费用明细", FormSchema.FieldType.TEXT, true,
                null, null, null, null, null, null, null, true, visibility), field("amount", FormSchema.FieldType.NUMBER),
                field("currency", FormSchema.FieldType.TEXT), field("overPolicy", FormSchema.FieldType.BOOLEAN)));
        if (controlled) fields.add(field(ExpenseFormContract.PRIOR_OVER_TOLERANCE, FormSchema.FieldType.BOOLEAN));
        var draft = definitions.create("demo", "prior-control-" + UUID.randomUUID(), "合成事前控制流程", new Graph(nodes, edges), new FormSchema(2, fields), null);
        return definitions.publish(ADMIN, draft.id(), draft.revision(), "合成验收");
    }
    private ExpenseReport draft(DefinitionDraft definition, UUID credit, String reason) throws Exception {
        var gross = money("100");
        var line = new ExpenseLine(1, "OFFICE", LocalDate.now(), null, "SH", BigDecimal.ONE, ExpenseLine.Unit.ITEM, gross, money("6"),
                List.of(), new ExpenseLine.PriorRequestLine(credit, 1), List.of(new CostAllocation("IT", null, gross)), "合成费用", reason);
        var created = send("/api/v1/expense-reports", "alice", Map.of("businessNo", "PRIOR-" + UUID.randomUUID(), "processKey", definition.key(),
                "definitionVersion", definition.version(), "content", new ExpenseContent(entity, ExpenseContent.Type.DAILY, "合成报销", List.of(line), List.of())), 201);
        return reports.find("demo", UUID.fromString(created.path("id").asText())).orElseThrow();
    }
    private ExpensePrecheckJob check(ExpenseReport report) throws Exception {
        var checked = send(path(report) + "/precheck", "alice", Map.of("applicationVersion", application(report).version(), "financialVersion", current(report).version(),
                "initiatorAppointmentId", appointment, "accountingDate", LocalDate.now(), "targetDigest", financeConfiguration.destination("demo").orElseThrow().digest("demo")), 202);
        var id = UUID.fromString(checked.path("id").asText()); precheckWorker.poll();
        request(path(report) + "/prechecks/" + id, "alice", 200); return prechecks.find("demo", id).orElseThrow();
    }
    private void submit(ExpenseReport report) throws Exception {
        var checked = check(report); assertThat(checked.status()).as(json.write(checked.result())).isEqualTo(ExpensePrecheckJob.Status.READY);
        send(path(report) + "/submit", "alice", submitInput(report, checked.input().id()), 200); budgets(report);
    }
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
    private JsonNode read(ExpenseReport report, int roundNo, String user, int expected) throws Exception {
        return request(path(report) + "/prior-control?roundNo=" + roundNo, user, expected);
    }
    private JsonNode request(String endpoint, String user, int expected) throws Exception {
        var response = mvc.perform(get(endpoint).header("Authorization", "Bearer " + tokens.computeIfAbsent(user, value -> auth.login("demo", value, "demo").token())))
                .andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected);
        if (expected == 200) assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        capture("GET", endpoint, null, response);
        return json.read(response.getContentAsString(StandardCharsets.UTF_8), JsonNode.class);
    }
    private Map<String, Object> submitInput(ExpenseReport report, UUID checked) { return Map.of("applicationVersion", application(report).version(), "financialVersion", current(report).version(), "precheckId", checked); }
    private Application application(ExpenseReport report) { return applications.findById("demo", report.applicationId()).orElseThrow(); }
    private ExpenseReport current(ExpenseReport report) { return reports.find("demo", report.id()).orElseThrow(); }
    private Task task(ExpenseReport report) { return tasks.createTaskQuery().processVariableValueEquals("applicationId", report.applicationId().toString()).singleResult(); }
    private UUID person(String user, boolean approver) { var found = jdbc.queryForList("SELECT id FROM organization_person WHERE tenant_id='demo' AND subject=?", String.class, user);
        return found.isEmpty() ? organization.createPerson(ADMIN, user, "合成" + user, true, approver).id() : UUID.fromString(found.get(0)); }
    private FormSchema.Field field(String key, FormSchema.FieldType type) { return new FormSchema.Field(key, key, type, true, null, null, null, null, null); }
    private MockHttpServletResponse raw(String path, String user, Object body) throws Exception {
        return raw(path, user, body, UUID.randomUUID().toString());
    }
    private MockHttpServletResponse raw(String path, String user, Object body, String key) throws Exception {
        String token = "Bearer " + tokens.computeIfAbsent(user, value -> auth.login("demo", value, "demo").token());
        var response = mvc.perform(post(path).header("Authorization", token).header("Idempotency-Key", key).contentType("application/json").content(json.write(body))).andReturn().getResponse();
        capture("POST", path, body, response); return response;
    }
    private void capture(String method, String path, Object request, MockHttpServletResponse response) throws Exception {
        var record = new HashMap<String, Object>(); record.put("method", method); record.put("path", path); record.put("status", response.getStatus());
        if (request != null) record.put("request", request);
        record.put("response", json.read(response.getContentAsString(StandardCharsets.UTF_8), JsonNode.class));
        java.nio.file.Files.createDirectories(RESPONSES);
        java.nio.file.Files.writeString(RESPONSES.resolve(UUID.randomUUID() + ".json"), json.write(record));
    }
    private JsonNode send(String path, String user, Object body, int status) throws Exception { var response = raw(path, user, body);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status); return json.read(response.getContentAsString(StandardCharsets.UTF_8), JsonNode.class); }
    private static String path(ExpenseReport report) { return "/api/v1/expense-reports/" + report.id(); }
    private static Money money(String amount) { return new Money(new BigDecimal(amount), "CNY"); }
}
