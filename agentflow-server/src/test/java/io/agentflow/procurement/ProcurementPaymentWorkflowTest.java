package io.agentflow.procurement;

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
import static io.agentflow.procurement.ProcurementPaymentCheck.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 使用真实认证、数据库、Flowable 和回环财务 HTTP 验证采购提交、占用与审批原子性。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "agentflow.finance-gateway.enabled=true",
        "agentflow.vouchers.preparation-worker-enabled=false", "agentflow.vouchers.worker-enabled=false",
        "agentflow.invoices.verification-worker-enabled=false", "agentflow.expenses.precheck-worker-enabled=false",
        "agentflow.advance-requests.precheck-worker-enabled=false", "agentflow.expense-plans.precheck-worker-enabled=false",
        "agentflow.procurement-payments.precheck-worker-enabled=false", "agentflow.budgets.worker-enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ProcurementPaymentWorkflowTest {
    private static final HttpServer SERVER = server();
    private static final String ENDPOINT = "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/finance";
    private static final AtomicReference<ProcurementPaymentWorkflowTest> ACTIVE = new AtomicReference<>();
    private static final Path DIRECTORY = Path.of("/fyoung/tmp/agentflow-procurement-workflow-" + UUID.randomUUID());
    private final Actor admin = new Actor("demo", "admin", Set.of("ADMIN", "PROCESS_ADMIN"));
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private UUID entity;
    private UUID appointment;
    private UUID manager;
    private final String payableReference = "AP-" + UUID.randomUUID();
    private BiFunction<String, JsonNode, String> responder;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("agentflow.attachments.directory", DIRECTORY::toString);
        registry.add("agentflow.finance-gateway.tenants.demo.endpoint", () -> ENDPOINT);
        registry.add("agentflow.finance-gateway.tenants.demo.allow-unauthenticated-loopback", () -> "true");
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_PROCUREMENT_WORKFLOW_URL", "jdbc:h2:mem:procurement-workflow;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_PROCUREMENT_WORKFLOW_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_PROCUREMENT_WORKFLOW_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_PROCUREMENT_WORKFLOW_PASSWORD", ""));
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
    @Autowired io.agentflow.expense.InvoiceRepository invoices;
    @Autowired io.agentflow.expense.ExpenseReportRepository expenses;

    @BeforeEach void setup() {
        ACTIVE.set(this); configuration.setEnabled(true); configuration.getTenants().get("demo").setEndpoint(ENDPOINT);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM organization_directory WHERE tenant_id='demo'", Integer.class) == 0) organization.initialize(admin);
        entity = organization.createUnit(admin, OrganizationUnit.Kind.LEGAL_ENTITY, "合成采购法人", null, null, true).id();
        var department = organization.createUnit(admin, OrganizationUnit.Kind.DEPARTMENT, "采购部门", entity, null, true);
        var position = organization.createUnit(admin, OrganizationUnit.Kind.POSITION, "采购岗位", entity, null, true);
        appointment = organization.createAppointment(admin, person("alice", false), department.id(), position.id(), true).id();
        manager = person("manager", true); responder = this::normal;
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

    @Test void creationReplaysOneBindingAndRejectsClientFinancialFacts() throws Exception {
        var body = createBody(published(false, false), content("70")); String key = UUID.randomUUID().toString();
        var first = send("/api/v1/procurement-payments", "alice", key, body); UUID id = id(ok(first, 201));
        assertThat(send("/api/v1/procurement-payments", "alice", key, body).getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(app(id).businessReference().id()).isEqualTo(id); assertThat(app(id).businessReference().type().name()).isEqualTo("PROCUREMENT_PAYMENT");
        assertThat(current(id).content().amount()).isEqualTo(money("70")); assertThat(current(id).rounds()).isEmpty();
        assertThat(reservations.active("demo", id)).isEmpty(); assertThat(calls).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM procurement_payment_revision WHERE request_id=?", Integer.class, id.toString())).isEqualTo(1);
        var forged = new java.util.HashMap<>(body); forged.put("employeeId", "bob");
        assertThat(send("/api/v1/procurement-payments", "alice", forged).getStatus()).isBetween(400, 499);
        var facts = json.read(json.write(content("70")), JsonNode.class).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) facts).put("accountReference", "client-forged-account"); forged = new java.util.HashMap<>(body); forged.put("content", facts);
        assertThat(send("/api/v1/procurement-payments", "alice", forged).getStatus()).isBetween(400, 499);
    }

    @Test void precheckIsReadOnlyAndOnlyFinalApprovalSealsTheHeldSupplierTerms() throws Exception {
        UUID id = create(); UUID checked = ready(id); var preview = check(checked).result().evidence().preview();
        assertThat(current(id).version()).isEqualTo(1); assertThat(reservations.active("demo", id)).isEmpty();
        assertThat(calls.keySet()).containsExactlyInAnyOrder("catalog", "procurement-payable");
        var displayed = ok(read(path(id) + "/prechecks/" + checked, "alice"), 200);
        assertThat(displayed.at("/preview/payable/maskedAccount").asText()).isEqualTo("****3456");
        assertPrivateFactsAbsent(displayed);
        String key = UUID.randomUUID().toString(); var input = submission(id, checked); var first = send(path(id) + "/submit", "alice", key, input);
        ok(first, 200); assertThat(send(path(id) + "/submit", "alice", key, input).getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(current(id).currentRound().payable()).isEqualTo(preview.payable()); assertThat(app(id).payload().get("amount")).isEqualTo("70.00");
        assertThat(reservations.active("demo", id).orElseThrow().source().round()).isEqualTo(current(id).currentRound());
        ok(act(id, "APPROVE"), 200); assertThat(current(id).approval()).isNull(); assertThat(app(id).status()).isEqualTo(ApplicationStatus.IN_APPROVAL);
        String lastPath = actionPath(id), approvalKey = UUID.randomUUID().toString(); var decision = decision(id, "APPROVE");
        var approved = send(lastPath, "manager", approvalKey, decision); ok(approved, 200);
        assertThat(send(lastPath, "manager", approvalKey, decision).getContentAsString()).isEqualTo(approved.getContentAsString());
        assertThat(current(id).version()).isEqualTo(3); assertThat(current(id).approval().applicationVersion()).isEqualTo(app(id).version());
        assertThat(current(id).approval().approvedBy()).isEqualTo("manager"); assertThat(reservations.active("demo", id)).isPresent();
        assertThat(app(id).status()).isEqualTo(ApplicationStatus.APPROVED); assertNoFinancialWrites(id);
        for (String user : List.of("alice", "manager")) assertPrivateFactsAbsent(ok(read(path(id), user), 200));
        assertThat(read(path(id), "admin").getStatus()).isEqualTo(403);
        assertThat(send(path(id) + "/cancel", "alice", lifecycle(id)).getStatus()).isBetween(400, 499);
    }

    @Test void anExpenseClaimArrivingAfterPrecheckDisablesReadyAndBlocksBothSubmissionAndFreshPrecheck() throws Exception {
        UUID id = create(), checked = ready(id); var ready = check(checked); var before = current(id).state();
        var invoice = io.agentflow.expense.Invoice.uploaded(UUID.randomUUID(), "demo", "alice", UUID.randomUUID(), "b".repeat(64)); invoices.create(invoice, "alice");
        var now = Instant.now();
        invoice.verified(1, new io.agentflow.expense.Invoice.VerifiedFacts(ready.result().evidence().preview().payable().lines().get(0).invoice(), entity,
                money("100"), money("10"), LocalDate.now(), "b".repeat(64), "synthetic-verification", now, now.plusSeconds(300)));
        invoices.update(invoice, 1, "fixture", "VERIFY");
        var reportId = UUID.randomUUID();
        var app = Application.draftBusiness(UUID.randomUUID(), "demo", "EXP-" + reportId, "fixture", 1, "alice", "测试报销", Map.of(), null, null, null,
                new io.agentflow.approval.model.BusinessReference(io.agentflow.approval.model.BusinessReference.Type.EXPENSE, reportId)); applications.save(app);
        var report = io.agentflow.expense.ExpenseReport.draft(reportId, "demo", app.id(), "alice",
                new io.agentflow.expense.ExpenseContent(entity, io.agentflow.expense.ExpenseContent.Type.DAILY, "测试报销", List.of(), List.of())); expenses.create(report, "alice");
        invoice.occupy(2, new io.agentflow.expense.ExpenseUse(reportId, 1, 1), "alice", entity, now); invoices.update(invoice, 2, "alice", "OCCUPY");
        var view = ok(read(path(id) + "/prechecks/" + checked, "alice"), 200);
        assertThat(view.path("usable").asBoolean()).isFalse(); assertThat(view.path("unavailableCode").asText()).isEqualTo("INVOICE_OCCUPIED");
        assertThat(ok(send(path(id) + "/submit", "alice", submission(id, checked)), 409).path("code").asText()).isEqualTo("INVOICE_OCCUPIED");
        assertThat(current(id).state()).isEqualTo(before); assertThat(reservations.active("demo", id)).isEmpty();
        UUID fresh = enqueue(id); worker.poll(); assertThat(check(fresh).status()).isEqualTo(Status.BLOCKED); assertThat(check(fresh).result().code()).isEqualTo("INVOICE_OCCUPIED");
        assertThat(invoices.find("demo", invoice.id()).orElseThrow().state()).isEqualTo(invoice.state()); assertNoFinancialWrites(id);
    }

    @Test void copiedTemplateUsesTheChosenAppointmentsSupervisorThenFinancialReview() throws Exception {
        var department = organization.createUnit(admin, OrganizationUnit.Kind.DEPARTMENT, "主管与财务部门", entity, null, true);
        var position = organization.createUnit(admin, OrganizationUnit.Kind.POSITION, "合成审批岗位", entity, null, true);
        var supervisor = organization.createAppointment(admin, manager, department.id(), position.id(), true);
        organization.createAppointment(admin, person("finance", true), department.id(), position.id(), true);
        organization.setSupervisor(admin, appointment, supervisor.id(), 1);
        var copied = ok(send("/api/v1/process-templates/procurement-payment/copy", "admin",
                Map.of("key", "procurement-copy-" + UUID.randomUUID(), "name", "复制的已验收采购付款", "templateVersion", 1)), 200);
        var draft = definitions.get("demo", id(copied));
        assertThat(definitions.inspect("demo", draft.graph(), draft.formSchema()).errors()).contains("ASSIGNEE_NOT_AVAILABLE:finance");
        var finance = person("finance", true);
        var assigned = new Graph(draft.graph().nodes().stream().map(node -> node.id().equals("finance")
                ? new Node(node.id(), node.name(), node.type(), Map.of("assigneeRule", "role:ORG_PERSON_" + finance)) : node).toList(), draft.graph().edges());
        draft = definitions.update("demo", draft.id(), draft.name(), assigned, draft.formSchema(), draft.revision());
        var definition = definitions.publish(admin, draft.id(), draft.revision(), "采购模板联动验收");
        UUID id = id(ok(send("/api/v1/procurement-payments", "alice", createBody(definition, content("70"))), 201)); submit(id);
        assertThat(tasks.createTaskQuery().processVariableValueEquals("applicationId", app(id).id().toString()).singleResult().getTaskDefinitionKey()).isEqualTo("supervisor");
        ok(act(id, "APPROVE"), 200);
        assertThat(tasks.createTaskQuery().processVariableValueEquals("applicationId", app(id).id().toString()).singleResult().getTaskDefinitionKey()).isEqualTo("finance");
        assertPrivateFactsAbsent(ok(read(path(id), "finance"), 200));
        ok(send(actionPath(id), "finance", decision(id, "APPROVE")), 200);
        assertThat(current(id).approval().approvedBy()).isEqualTo("finance"); assertThat(app(id).status()).isEqualTo(ApplicationStatus.APPROVED);
        assertNoFinancialWrites(id);
    }

    @Test void returnedRevisionKeepsOriginalEvidenceAndReplacesTheHoldOnlyAtResubmission() throws Exception {
        UUID id = create(); submit(id); var original = current(id).currentRound(); var originalHold = reservations.active("demo", id).orElseThrow();
        ok(act(id, "RETURN"), 200); ok(send(path(id) + "/revise", "alice", revision(id, content("40"))), 200);
        assertThat(reservations.active("demo", id).orElseThrow()).isEqualTo(originalHold);
        assertThat(read(path(id), "manager").getStatus()).isEqualTo(404);
        assertThat(ok(read(path(id) + "?roundNo=1", "manager"), 200).at("/content/amount/value").asText()).isEqualTo("70.00");
        assertThat(read(path(id) + "?roundNo=1", "admin").getStatus()).isEqualTo(403);
        submit(id); ok(act(id, "APPROVE"), 200); ok(act(id, "APPROVE"), 200);
        assertThat(current(id).rounds().get(0)).isEqualTo(original); assertThat(current(id).approval().roundNo()).isEqualTo(2);
        var history = reservations.history("demo", id); assertThat(history).hasSize(2);
        assertThat(history.get(0).release().reason()).isEqualTo(ProcurementPayableReservation.ReleaseReason.RESUBMITTED);
        assertThat(history.get(0).source()).isEqualTo(originalHold.source()); assertThat(history.get(1).held()).isTrue();
        assertThat(ok(read(path(id) + "?roundNo=1", "manager"), 200).has("approval")).isFalse(); assertNoFinancialWrites(id);
    }

    @Test void ownershipFieldRulesAndGenericWritesCannotBypassTheProcurementBoundary() throws Exception {
        UUID id = create(), checked = ready(id);
        for (String user : List.of("bob", "manager", "admin")) {
            for (String suffix : List.of("", "/prechecks/options", "/prechecks", "/prechecks/" + checked)) assertThat(read(path(id) + suffix, user).getStatus()).isEqualTo(404);
            assertThat(send(path(id) + "/revise", user, revision(id, content("60"))).getStatus()).isEqualTo(404);
            assertThat(send(path(id) + "/submit", user, submission(id, checked)).getStatus()).isEqualTo(404);
            for (String suffix : List.of("/withdraw", "/cancel")) assertThat(send(path(id) + suffix, user, lifecycle(id)).getStatus()).isEqualTo(404);
            assertThat(ok(read("/api/v1/procurement-payments", user), 200).path("items").isEmpty()).isTrue();
        }
        for (String query : List.of("employeeId=alice", "limit=101", "beforeId=" + UUID.randomUUID())) assertThat(read("/api/v1/procurement-payments?" + query, "alice").getStatus()).isEqualTo(400);
        for (String action : List.of("submit", "withdraw", "cancel")) code(send("/api/v1/applications/" + app(id).id() + "/" + action, "alice", Map.of("expectedVersion", 1)), "USE_BUSINESS_ENDPOINT");
        assertThat(requests.find("foreign", id)).isEmpty(); assertThat(checks.find("foreign", checked)).isEmpty();
        UUID another = create(); assertThat(read(path(another) + "/prechecks/" + checked, "alice").getStatus()).isEqualTo(404);
        assertThat(read(path(id) + "/prechecks?employeeId=alice", "alice").getStatus()).isEqualTo(400);
        var definition = published(false, false);
        code(send("/api/v1/applications", "alice", Map.of("businessNo", "FORGE-" + UUID.randomUUID(), "processKey", definition.key(),
                "definitionVersion", definition.version(), "title", "采购入口保护", "payload", ProcurementPaymentFormContract.draftPayload())), "USE_BUSINESS_ENDPOINT");
    }

    @Test void noHumanPathOrMaskedApproverCannotAcquireAReservation() throws Exception {
        for (var definition : List.of(published(true, false), published(false, true))) {
            UUID id = id(ok(send("/api/v1/procurement-payments", "alice", createBody(definition, content("70"))), 201));
            var response = send(path(id) + "/submit", "alice", submission(id, ready(id)));
            assertThat(response.getStatus()).isEqualTo(422); assertThat(current(id).rounds()).isEmpty();
            assertThat(reservations.active("demo", id)).isEmpty(); assertThat(app(id).status()).isEqualTo(ApplicationStatus.DRAFT);
        }
    }

    @Test void doubleVersionsAndNewestAttemptAreRequiredAndQueueReplayDoesNotDuplicate() throws Exception {
        UUID id = create(); var request = queueInput(id); String key = UUID.randomUUID().toString();
        var first = send(path(id) + "/prechecks", "alice", key, request); UUID old = id(ok(first, 202));
        assertThat(send(path(id) + "/prechecks", "alice", key, request).getContentAsString()).isEqualTo(first.getContentAsString());
        code(send(path(id) + "/prechecks", "alice", request), "PROCUREMENT_CHECK_ACTIVE"); worker.poll();
        UUID latest = enqueue(id); code(send(path(id) + "/submit", "alice", submission(id, old)), "PRECHECK_SUPERSEDED"); worker.poll();
        var wrong = new java.util.HashMap<>(submission(id, latest)); wrong.put("requestVersion", 9);
        code(send(path(id) + "/submit", "alice", wrong), "CONCURRENCY_CONFLICT"); wrong = new java.util.HashMap<>(submission(id, latest)); wrong.put("applicationVersion", 9);
        code(send(path(id) + "/submit", "alice", wrong), "CONCURRENCY_CONFLICT");
        ok(send(path(id) + "/submit", "alice", submission(id, latest)), 200); assertThat(current(id).rounds()).hasSize(1);
    }

    @Test void withdrawalRetainsTheHoldAndOnlyActualRejectionOrCancellationReleasesIt() throws Exception {
        UUID id = create(); submit(id); var held = reservations.active("demo", id).orElseThrow();
        assertThat(send(path(id) + "/cancel", "alice", lifecycle(id)).getStatus()).isBetween(400, 499);
        ok(send(path(id) + "/withdraw", "alice", lifecycle(id)), 200); assertThat(reservations.active("demo", id).orElseThrow()).isEqualTo(held);
        submit(id); ok(act(id, "REJECT"), 200); assertThat(reservations.active("demo", id)).isEmpty();
        assertThat(reservations.history("demo", id).get(1).release().reason()).isEqualTo(ProcurementPayableReservation.ReleaseReason.REJECTED);
        UUID cancelled = create(); submit(cancelled); ok(act(cancelled, "RETURN"), 200);
        ok(send(path(cancelled) + "/revise", "alice", revision(cancelled, content("30"))), 200);
        ok(send(path(cancelled) + "/cancel", "alice", lifecycle(cancelled)), 200);
        assertThat(reservations.active("demo", cancelled)).isEmpty();
        assertThat(ok(read(path(cancelled), "alice"), 200).at("/content/amount/value").asText()).isEqualTo("30.00");
        assertThat(ok(read(path(cancelled) + "?roundNo=1", "manager"), 200).at("/content/amount/value").asText()).isEqualTo("70.00");
        UUID draft = create(); ok(send(path(draft) + "/cancel", "alice", lifecycle(draft)), 200); assertThat(reservations.history("demo", draft)).isEmpty();
    }

    @Test void concurrentSubmissionsForOnePayableStartOnlyOneApproval() throws Exception {
        UUID first = create(), second = create(); var firstInput = submission(first, ready(first)); var secondInput = submission(second, ready(second));
        var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> { await(start); return send(path(first) + "/submit", "alice", firstInput); });
            var b = pool.submit(() -> { await(start); return send(path(second) + "/submit", "alice", secondInput); });
            start.countDown(); var ra = a.get(15, TimeUnit.SECONDS); var rb = b.get(15, TimeUnit.SECONDS);
            assertThat(List.of(ra.getStatus(), rb.getStatus())).containsExactlyInAnyOrder(200, 409);
            UUID loser = ra.getStatus() == 200 ? second : first;
            assertThat(current(loser).version()).isEqualTo(1); assertThat(current(loser).rounds()).isEmpty();
            assertThat(app(loser).version()).isEqualTo(1); assertThat(app(loser).status()).isEqualTo(ApplicationStatus.DRAFT);
            assertThat(tasks.createTaskQuery().processVariableValueEquals("applicationId", app(loser).id().toString()).count()).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM procurement_payable_reservation WHERE tenant_id='demo' AND legal_entity_id=? AND active_payable_reference=?", Integer.class, entity.toString(), payableReference)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test void failedResubmissionKeepsBothThePreviousHoldAndCorrectedDraft() throws Exception {
        UUID first = create(); submit(first); var original = reservations.active("demo", first).orElseThrow(); ok(act(first, "RETURN"), 200);
        var otherContent = new ProcurementPaymentContent(entity, "另一原应付", "合成验收采购款", "supplier-1", "AP-OTHER-" + UUID.randomUUID(), money("70"));
        UUID other = id(ok(send("/api/v1/procurement-payments", "alice", createBody(published(false, false), otherContent)), 201)); submit(other);
        ok(send(path(first) + "/revise", "alice", revision(first, otherContent)), 200);
        long applicationVersion = app(first).version(), requestVersion = current(first).version();
        code(send(path(first) + "/submit", "alice", submission(first, ready(first))), "PROCUREMENT_PAYABLE_OCCUPIED");
        assertThat(reservations.active("demo", first).orElseThrow()).isEqualTo(original); assertThat(reservations.history("demo", first)).hasSize(1);
        assertThat(current(first).version()).isEqualTo(requestVersion); assertThat(current(first).content()).isEqualTo(otherContent);
        assertThat(app(first).version()).isEqualTo(applicationVersion); assertThat(app(first).status()).isEqualTo(ApplicationStatus.RETURNED);
    }

    @Test void failedSubmissionRoundWriteRollsBackTheBusinessRevisionHoldAndEngine() throws Exception {
        UUID id = create(), checked = ready(id); UUID application = app(id).id();
        jdbc.execute("ALTER TABLE approval_submission_round ADD CONSTRAINT ck_procurement_round_fixture CHECK (application_id <> '" + application + "')");
        try {
            assertThatThrownBy(() -> send(path(id) + "/submit", "alice", submission(id, checked))).isInstanceOf(jakarta.servlet.ServletException.class).hasRootCauseInstanceOf(java.sql.SQLException.class);
            assertThat(current(id).version()).isEqualTo(1); assertThat(current(id).rounds()).isEmpty(); assertThat(reservations.history("demo", id)).isEmpty();
            assertThat(app(id).version()).isEqualTo(1); assertThat(app(id).status()).isEqualTo(ApplicationStatus.DRAFT);
            assertThat(tasks.createTaskQuery().processVariableValueEquals("applicationId", application.toString()).count()).isZero();
        } finally { jdbc.execute("ALTER TABLE approval_submission_round DROP CONSTRAINT ck_procurement_round_fixture"); }
        ok(send(path(id) + "/submit", "alice", submission(id, checked)), 200);
    }

    @Test void failedApprovalRevisionRollsBackFinalTaskRoundAndBusinessApproval() throws Exception {
        UUID id = create(); submit(id); ok(act(id, "APPROVE"), 200); long before = app(id).version(); String task = actionPath(id);
        jdbc.execute("ALTER TABLE procurement_payment_revision ADD CONSTRAINT ck_procurement_approval_fixture CHECK (request_id <> '" + id + "' OR operation <> 'APPROVE')");
        try {
            assertThatThrownBy(() -> act(id, "APPROVE")).isInstanceOf(jakarta.servlet.ServletException.class).hasRootCauseInstanceOf(java.sql.SQLException.class);
            assertThat(app(id).version()).isEqualTo(before); assertThat(app(id).status()).isEqualTo(ApplicationStatus.IN_APPROVAL);
            assertThat(current(id).version()).isEqualTo(2); assertThat(current(id).approval()).isNull(); assertThat(actionPath(id)).isEqualTo(task);
            assertThat(jdbc.queryForObject("SELECT status FROM approval_submission_round WHERE application_id=? AND round_no=1", String.class, app(id).id().toString())).isEqualTo("IN_APPROVAL");
            assertThat(reservations.active("demo", id)).isPresent();
        } finally { jdbc.execute("ALTER TABLE procurement_payment_revision DROP CONSTRAINT ck_procurement_approval_fixture"); }
        ok(act(id, "APPROVE"), 200); assertThat(current(id).approval()).isNotNull();
    }

    @Test void failedReleaseRevisionRollsBackRejectionAndRetainsTheTaskAndHold() throws Exception {
        UUID id = create(); submit(id); var held = reservations.active("demo", id).orElseThrow(); String task = actionPath(id); long before = app(id).version();
        jdbc.execute("ALTER TABLE procurement_payable_reservation_revision ADD CONSTRAINT ck_procurement_release_fixture CHECK (reservation_id <> '" + held.id() + "' OR version <> 2)");
        try {
            assertThatThrownBy(() -> act(id, "REJECT")).isInstanceOf(jakarta.servlet.ServletException.class).hasRootCauseInstanceOf(java.sql.SQLException.class);
            assertThat(app(id).version()).isEqualTo(before); assertThat(app(id).status()).isEqualTo(ApplicationStatus.IN_APPROVAL);
            assertThat(actionPath(id)).isEqualTo(task); assertThat(reservations.active("demo", id).orElseThrow()).isEqualTo(held);
        } finally { jdbc.execute("ALTER TABLE procurement_payable_reservation_revision DROP CONSTRAINT ck_procurement_release_fixture"); }
        ok(act(id, "REJECT"), 200); assertThat(reservations.active("demo", id)).isEmpty();
    }

    @Test void concurrentWorkersClaimOnlyOneReadAndTimeoutNeverReplaysAClaimedJob() throws Exception {
        UUID id = create(), checked = enqueue(id); var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> { await(start); worker.poll(); }); var b = pool.submit(() -> { await(start); worker.poll(); });
            start.countDown(); a.get(15, TimeUnit.SECONDS); b.get(15, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertThat(check(checked).status()).isEqualTo(Status.READY); assertThat(calls.get("catalog").get()).isEqualTo(1); assertThat(calls.get("procurement-payable").get()).isEqualTo(1);
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
            assertThat(current(id).rounds()).isEmpty(); assertThat(reservations.history("demo", id)).isEmpty();
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
        assertThat(reservations.history("demo", id)).isEmpty(); assertThat(current(id).rounds()).isEmpty();
    }

    @Test void amountAndCurrencyUseTheActualOutstandingMatchedPayable() throws Exception {
        for (var content : List.of(content("70.01"), new ProcurementPaymentContent(entity, "外币付款", "合成采购", "supplier-1", payableReference, new Money(new BigDecimal("70"), "USD")))) {
            UUID id = id(ok(send("/api/v1/procurement-payments", "alice", createBody(published(false, false), content)), 201));
            UUID checked = enqueue(id); worker.poll(); assertThat(check(checked).status()).isEqualTo(Status.BLOCKED);
            code(send(path(id) + "/submit", "alice", submission(id, checked)), "PRECHECK_NOT_READY");
            assertThat(reservations.history("demo", id)).isEmpty(); assertThat(current(id).rounds()).isEmpty();
        }
    }

    @Test void publicQuantityProjectionPreservesSixDecimalPlacesBeyondBrowserNumberPrecision() throws Exception {
        String quantity = "999999999999999.123456";
        responder = (operation, request) -> {
            var response = json.read(normal(operation, request), JsonNode.class);
            if (operation.equals("procurement-payable")) {
                var line = (com.fasterxml.jackson.databind.node.ObjectNode) response.at("/data/lines/0");
                for (String field : List.of("orderedQuantity", "acceptedQuantity", "invoicedQuantity")) line.put(field, new BigDecimal(quantity));
            }
            return json.write(response);
        };
        UUID id = create(), checked = ready(id); var response = ok(read(path(id) + "/prechecks/" + checked, "alice"), 200);
        for (String field : List.of("orderedQuantity", "acceptedQuantity", "invoicedQuantity")) {
            var value = response.at("/preview/payable/lines/0/" + field);
            assertThat(value.isTextual()).isTrue(); assertThat(value.asText()).isEqualTo(quantity);
        }
    }

    @Test void unavailableAndBusinessRejectedRemainDistinctAndDoNotCallPaymentPorts() throws Exception {
        UUID id = create(); responder = (operation, request) -> operation.equals("catalog") ? normal(operation, request)
                : json.write(Map.of("contractVersion", 1, "tenantId", "demo", "requestId", request.path("requestId").asText(), "outcome", "REJECTED", "reason", "PROCUREMENT_MATCH_REQUIRED"));
        UUID checked = enqueue(id); worker.poll(); assertThat(check(checked).status()).isEqualTo(Status.BLOCKED);
        assertThat(check(checked).result().code()).isEqualTo("PROCUREMENT_MATCH_REQUIRED");
        configuration.setEnabled(false); assertThat(ok(read(path(id) + "/prechecks/options", "alice"), 200).path("enabled").asBoolean()).isFalse();
        assertThat(send(path(id) + "/prechecks", "alice", Map.of("applicationVersion", app(id).version(), "requestVersion", current(id).version(), "initiatorAppointmentId", appointment, "targetDigest", "a".repeat(64))).getStatus()).isEqualTo(503);
        assertNoFinancialWrites(id);
    }

    private UUID create() throws Exception { return id(ok(send("/api/v1/procurement-payments", "alice", createBody(published(false, false), content("70"))), 201)); }
    private Map<String, Object> createBody(DefinitionDraft definition, ProcurementPaymentContent content) { return Map.of("businessNo", "PROCUREMENT-" + UUID.randomUUID(), "processKey", definition.key(), "definitionVersion", definition.version(), "content", content); }
    private ProcurementPaymentContent content(String amount) { return new ProcurementPaymentContent(entity, "合成设备采购款", "按原合同支付验收应付", "supplier-1", payableReference, money(amount)); }
    private Map<String, Object> revision(UUID id, ProcurementPaymentContent content) { return Map.of("applicationVersion", app(id).version(), "requestVersion", current(id).version(), "content", content); }
    private Map<String, Object> lifecycle(UUID id) { return Map.of("applicationVersion", app(id).version(), "requestVersion", current(id).version(), "comment", "合成生命周期原因"); }
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
    private MockHttpServletResponse act(UUID id, String action) throws Exception { return send(actionPath(id), "manager", decision(id, action)); }
    private MockHttpServletResponse send(String path, String user, Object body) throws Exception { return send(path, user, UUID.randomUUID().toString(), body); }
    private MockHttpServletResponse send(String path, String user, String key, Object body) throws Exception { return mvc.perform(post(path).header("Authorization", token(user)).header("Idempotency-Key", key).contentType("application/json").content(json.write(body))).andReturn().getResponse(); }
    private MockHttpServletResponse read(String path, String user) throws Exception { return mvc.perform(get(path).header("Authorization", token(user))).andReturn().getResponse(); }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private JsonNode ok(MockHttpServletResponse response, int status) throws Exception { assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status); return json.read(response.getContentAsString(StandardCharsets.UTF_8), JsonNode.class); }
    private void code(MockHttpServletResponse response, String code) throws Exception { assertThat(response.getStatus()).isBetween(400, 499); assertThat(json.read(response.getContentAsString(StandardCharsets.UTF_8), JsonNode.class).path("code").asText()).isEqualTo(code); }
    private UUID id(JsonNode node) { return UUID.fromString(node.path("id").asText()); }
    private static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
    private void assertPrivateFactsAbsent(JsonNode node) { assertThat(node.toString()).doesNotContain("private-supplier-account", "accountReference", "accountDigest", "verificationReference", "invoiceDigest", "a".repeat(64), "b".repeat(64)); }
    private void assertNoFinancialWrites(UUID id) {
        assertThat(calls.keySet()).allMatch(value -> value.equals("catalog") || value.equals("procurement-payable"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM voucher_preparation WHERE tenant_id='demo' AND business_id=?", Integer.class, id.toString())).isZero();
    }
    private UUID person(String subject, boolean approver) {
        var found = jdbc.queryForList("SELECT id FROM organization_person WHERE tenant_id='demo' AND subject=?", String.class, subject);
        return found.isEmpty() ? organization.createPerson(admin, subject, subject, true, approver).id() : UUID.fromString(found.get(0));
    }
    private DefinitionDraft published(boolean noHuman, boolean masked) {
        var graph = noHuman ? new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()), new Node("end", "结束", NodeType.END, Map.of())), List.of(new Edge("a", "start", "end", "", false)))
                : new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()), new Node("review", "采购主管", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_" + manager)),
                new Node("finalReview", "财务复核", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_" + manager)), new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("a", "start", "review", "", false), new Edge("b", "review", "finalReview", "", false), new Edge("c", "finalReview", "end", "", false)));
        var access = noHuman ? Map.<String, FieldVisibility>of() : Map.of("review", masked ? FieldVisibility.MASKED : FieldVisibility.READ_ONLY, "finalReview", FieldVisibility.READ_ONLY);
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
            default -> throw new IllegalArgumentException("Unexpected finance operation from procurement approval");
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
