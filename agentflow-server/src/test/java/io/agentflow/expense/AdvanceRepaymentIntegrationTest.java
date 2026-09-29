package io.agentflow.expense;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import io.agentflow.finance.*;
import io.agentflow.form.FieldVisibility;
import io.agentflow.form.FormSchema;
import io.agentflow.organization.OrganizationService;
import io.agentflow.organization.OrganizationUnit;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * 实际发起、审批、放款后通过认证接口确认还款；回环资金和 ERP 均为明确合成服务。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "agentflow.finance-gateway.enabled=true",
        "agentflow.vouchers.preparation-worker-enabled=false", "agentflow.vouchers.worker-enabled=false", "agentflow.budgets.worker-enabled=false",
        "agentflow.payments.worker-enabled=false", "agentflow.payments.request-worker-enabled=false", "agentflow.payments.payee-review-worker-enabled=false",
        "agentflow.invoices.verification-worker-enabled=false", "agentflow.expenses.precheck-worker-enabled=false", "agentflow.expenses.settlement-worker-enabled=false",
        "agentflow.expenses.archive-worker-enabled=false", "agentflow.advance-requests.precheck-worker-enabled=false", "agentflow.advances.repayment-worker-enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class AdvanceRepaymentIntegrationTest {
    private static final AtomicReference<AdvanceRepaymentIntegrationTest> ACTIVE = new AtomicReference<>();
    private static final HttpServer SERVER = server();
    private static final String ENDPOINT = "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/finance";
    private final Actor admin = new Actor("demo", "admin", Set.of("ADMIN", "PROCESS_ADMIN"));
    private final Map<UUID, VoucherCommand> voucherCommands = new ConcurrentHashMap<>();
    private final Map<UUID, PaymentCommand> paymentCommands = new ConcurrentHashMap<>();
    private final Map<String, AdvanceRepaymentPort.Receipt> receipts = new ConcurrentHashMap<>();
    private final List<UUID> ownedAdvances = new ArrayList<>();
    private UUID entity, appointment, financePerson, financeAppointment;
    private int paymentWrites, voucherWrites, repaymentReads;
    private String amount = "25";
    private AdvanceRepaymentPort.Status receiptStatus = AdvanceRepaymentPort.Status.CONFIRMED;
    private long receiptRevision = 1;
    private boolean unavailable;
    private AdvanceRepaymentPort.Channel channel = AdvanceRepaymentPort.Channel.BANK_TRANSFER;
    private String transactionReference, postingReference;
    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @Autowired AuthService auth;
    @Autowired CurrentActor actors;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired OrganizationService organization;
    @Autowired DefinitionApplicationService definitions;
    @Autowired ApplicationRepository applications;
    @Autowired AdvanceRequestRepository requests;
    @Autowired EmployeeAdvanceRepository balances;
    @Autowired ExpenseReportRepository reports;
    @Autowired TaskService tasks;
    @Autowired AdvanceRequestCheckWorker precheckWorker;
    @Autowired VoucherPreparationWorker preparationWorker;
    @Autowired VoucherOperationWorker voucherWorker;
    @Autowired JdbcVoucherOperationRepository vouchers;
    @Autowired PaymentExecutionRequestWorker executionWorker;
    @Autowired PaymentOperationWorker paymentWorker;
    @Autowired JdbcPaymentOperationRepository payments;
    @Autowired FinanceGatewayConfiguration configuration;
    @Autowired AdvanceRepaymentWorker worker;
    @Autowired AdvanceRepaymentService service;
    @Autowired JdbcAdvanceRepaymentCheckRepository checks;
    @Autowired JdbcAdvanceRepaymentRepository repayments;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry values) {
        values.add("agentflow.finance-gateway.tenants.demo.endpoint", () -> ENDPOINT);
        values.add("agentflow.finance-gateway.tenants.demo.allow-unauthenticated-loopback", () -> "true");
        values.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_REPAYMENT_TEST_URL", "jdbc:h2:mem:advance-repayments;DB_CLOSE_DELAY=-1"));
        values.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_REPAYMENT_TEST_USER", "sa"));
        values.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_REPAYMENT_TEST_PASSWORD", ""));
        values.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_REPAYMENT_TEST_DRIVER", "org.h2.Driver"));
    }
    @BeforeEach void setup() {
        ACTIVE.set(this); configuration.setEnabled(true); configuration.getTenants().get("demo").setEndpoint(ENDPOINT);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM organization_directory WHERE tenant_id='demo'", Integer.class) == 0) organization.initialize(admin);
        entity = organization.createUnit(admin, OrganizationUnit.Kind.LEGAL_ENTITY, "还款验收法人", null, null, true).id();
        var department = organization.createUnit(admin, OrganizationUnit.Kind.DEPARTMENT, "合成部门", entity, null, true);
        var position = organization.createUnit(admin, OrganizationUnit.Kind.POSITION, "合成岗位", entity, null, true);
        appointment = organization.createAppointment(admin, person("alice", false), department.id(), position.id(), true).id();
        financePerson = person("finance", true); financeAppointment = organization.createAppointment(admin, financePerson, department.id(), position.id(), true).id();
        organization.createAppointment(admin, person("cashier", false), department.id(), position.id(), true);
    }
    @AfterEach void clear() {
        actors.clear();
        jdbc.update("UPDATE organization_appointment SET active=TRUE WHERE tenant_id='demo' AND id=?", financeAppointment.toString());
        for (UUID loan : ownedAdvances) for (var id : jdbc.queryForList("SELECT id FROM advance_repayment_check WHERE tenant_id='demo' AND advance_id=? AND status IN ('QUEUED','RUNNING')", String.class, loan.toString())) {
            var job = checks.find("demo", UUID.fromString(id)).orElseThrow();
            if (job.status() == AdvanceRepaymentCheck.Status.QUEUED) job = service.claim("demo", job.input().id(), Instant.now());
            if (job != null) service.fail(job, Instant.now());
        }
    }
    @AfterAll static void stopServer() { SERVER.stop(0); }

    @Test void explicitRepaymentsCloseRealLoanWithoutAnotherPaymentOrVoucherAndReplayOnlyOnce() throws Exception {
        UUID loan = paidLoan(); var before = balance(loan).state(); int outgoing = paymentWrites, postings = voucherWrites;
        var queued = query(loan, "first"); assertThat(repaymentReads).isZero(); assertThat(balance(loan).state()).isEqualTo(before);
        worker.poll(); assertThat(balance(loan).state()).isEqualTo(before);
        var view = view(loan, "finance"); assertThat(view.at("/latestCheck/canRecord").asBoolean()).isTrue();
        assertThat(view.toString()).doesNotContain("accountDigest", "targetDigest", "private-account", "reason");
        String key = UUID.randomUUID().toString(); var input = recordInput(loan, queued);
        var recorded = send(path(loan) + "/repayments", "finance", key, input); ok(recorded, 202);
        assertThat(send(path(loan) + "/repayments", "finance", key, input).getContentAsString()).isEqualTo(recorded.getContentAsString());
        assertThat(recorded.getHeader("Cache-Control")).isEqualTo("no-store"); assertThat(balance(loan).repaid()).isEqualTo(money("25"));
        assertThat(balance(loan).outstanding()).isEqualTo(money("75")); assertThat(balance(loan).status()).isEqualTo(EmployeeAdvance.Status.PARTIALLY_SETTLED);
        amount = "75"; var remaining = query(loan, "remaining"); worker.poll(); ok(send(path(loan) + "/repayments", "finance", recordInput(loan, remaining)), 202);
        assertThat(balance(loan).status()).isEqualTo(EmployeeAdvance.Status.SETTLED); assertThat(balance(loan).balance().limit()).isEqualTo(money("100"));
        assertThat(balance(loan).balance().consumed()).isEqualTo(money("0")); assertThat(balance(loan).available()).isEqualTo(money("0"));
        var owner = view(loan, "alice"); assertThat(owner.path("canQuery").asBoolean()).isFalse(); assertThat(owner.path("latestCheck").isNull()).isTrue();
        assertThat(owner.path("records")).hasSize(2); assertThat(owner.at("/balance/repaid/value").asText()).isEqualTo("100.00");
        assertThat(paymentWrites).isEqualTo(outgoing); assertThat(voucherWrites).isEqualTo(postings);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE tenant_id='demo' AND application_id=? AND action='ADVANCE_REPAYMENT_RECORD'", Integer.class, app(loan).id().toString())).isEqualTo(2);
    }

    @ParameterizedTest @EnumSource(value = AdvanceRepaymentPort.Channel.class, names = {"CASH", "PAYROLL"})
    void externallyPostedCashAndPayrollUseOriginalFacts(AdvanceRepaymentPort.Channel selected) throws Exception {
        UUID loan = paidLoan(); channel = selected; var check = query(loan, "channel"); worker.poll();
        ok(send(path(loan) + "/repayments", "finance", recordInput(loan, check)), 202);
        assertThat(view(loan, "alice").at("/records/0/channel").asText()).isEqualTo(selected.name());
        assertThat(balance(loan).repaid()).isEqualTo(money("25"));
    }

    @Test void pendingMissingMalformedAndOverpaidReceiptsCannotChangeBalance() throws Exception {
        UUID loan = paidLoan(); var original = balance(loan).state();
        for (var state : List.of(AdvanceRepaymentPort.Status.NOT_FOUND, AdvanceRepaymentPort.Status.PENDING, AdvanceRepaymentPort.Status.REVERSED)) {
            receiptStatus = state; var check = query(loan, "unready-" + state); worker.poll();
            assertThat(view(loan, "finance").at("/latestCheck/canRecord").asBoolean()).isFalse();
            code(send(path(loan) + "/repayments", "finance", recordInput(loan, check)), "ADVANCE_REPAYMENT_EVIDENCE_UNAVAILABLE");
        }
        unavailable = true; var invalid = query(loan, "malformed"); worker.poll();
        assertThat(checks.find("demo", invalid).orElseThrow().status()).isEqualTo(AdvanceRepaymentCheck.Status.UNAVAILABLE);
        unavailable = false; receiptStatus = AdvanceRepaymentPort.Status.CONFIRMED; amount = "101";
        var excessive = query(loan, "too-large"); worker.poll();
        code(send(path(loan) + "/repayments", "finance", recordInput(loan, excessive)), "INSUFFICIENT_FINANCIAL_BALANCE");
        assertThat(balance(loan).state()).isEqualTo(original);
    }

    @Test void identityPermissionsAndCurrentAppointmentAreCheckedBeforeReplay() throws Exception {
        UUID loan = paidLoan(); var input = Map.of("advanceVersion", balance(loan).version(), "receiptReference", "private", "comment", "合成核对");
        for (String user : List.of("alice", "admin", "cashier", "bob")) {
            assertThat(send(path(loan) + "/repayment-checks", user, input).getStatus()).isIn(403, 404);
            if (!user.equals("alice")) assertThat(read(path(loan) + "/repayments", user).getStatus()).isIn(403, 404);
        }
        var forged = new LinkedHashMap<String, Object>(input); forged.put("amount", money("25"));
        okError(send(path(loan) + "/repayment-checks", "finance", forged), 400);
        code(read(path(loan) + "/repayments?employeeId=alice", "finance"), "INVALID_ADVANCE_REPAYMENT_QUERY");
        code(read(path(loan) + "/repayments?beforeId=" + UUID.randomUUID(), "finance"), "INVALID_ADVANCE_REPAYMENT_QUERY");
        var check = query(loan, "record"); worker.poll(); var record = recordInput(loan, check); String key = UUID.randomUUID().toString();
        ok(send(path(loan) + "/repayments", "finance", key, record), 202);
        var before = balance(loan).state(); jdbc.update("UPDATE organization_appointment SET active=FALSE WHERE id=?", financeAppointment.toString());
        code(send(path(loan) + "/repayments", "finance", key, record), "PAYMENT_ACTOR_UNAVAILABLE");
        assertThat(view(loan, "finance").path("canQuery").asBoolean()).isFalse();
        assertThat(view(loan, "finance").path("latestCheck").isNull()).isTrue(); assertThat(balance(loan).state()).isEqualTo(before);
    }

    @Test void duplicateFundsAndAccountingEntryRollBackEveryLocalWrite() throws Exception {
        UUID loan = paidLoan(); var first = query(loan, "original"); worker.poll();
        ok(send(path(loan) + "/repayments", "finance", recordInput(loan, first)), 202); var saved = balance(loan).state();
        for (String duplicate : List.of("funds", "entry")) {
            transactionReference = duplicate.equals("funds") ? "receive-original" : null;
            postingReference = duplicate.equals("entry") ? "repay-voucher-original" : null;
            var second = query(loan, duplicate); worker.poll(); var checked = checks.find("demo", second).orElseThrow();
            code(send(path(loan) + "/repayments", "finance", recordInput(loan, second)), "ADVANCE_REPAYMENT_ALREADY_RECORDED");
            assertThat(checks.find("demo", second).orElseThrow()).isEqualTo(checked); assertThat(balance(loan).state()).isEqualTo(saved);
        }
        assertThat(view(loan, "alice").path("records")).hasSize(1);
    }

    @Test void auditFailureRollsBackReceiptConsumptionAndBalance() throws Exception {
        UUID loan = paidLoan(); var check = query(loan, "audit-failure"); worker.poll(); var before = balance(loan).state(); var pending = checks.find("demo", check).orElseThrow();
        jdbc.execute("ALTER TABLE audit_event ADD CONSTRAINT reject_repayment_audit_fixture CHECK (application_id <> '" + app(loan).id() + "' OR action <> 'ADVANCE_REPAYMENT_RECORD')");
        try { assertThatThrownBy(() -> send(path(loan) + "/repayments", "finance", recordInput(loan, check))).hasCauseInstanceOf(org.springframework.dao.DataIntegrityViolationException.class); }
        finally { jdbc.execute("ALTER TABLE audit_event DROP CONSTRAINT reject_repayment_audit_fixture"); }
        assertThat(balance(loan).state()).isEqualTo(before); assertThat(checks.find("demo", check).orElseThrow()).isEqualTo(pending);
        assertThat(view(loan, "alice").path("records")).isEmpty();
        ok(send(path(loan) + "/repayments", "finance", recordInput(loan, check)), 202);
    }

    @Test void existingExpenseReservationSurvivesRepaymentAndFinalOffsetClosesLoan() throws Exception {
        UUID loan = paidLoan(); var use = reserve(loan, "80"); var check = query(loan, "over-reserved"); worker.poll();
        code(send(path(loan) + "/repayments", "finance", recordInput(loan, check)), "INSUFFICIENT_FINANCIAL_BALANCE");
        amount = "20"; var fitting = query(loan, "remainder"); worker.poll(); ok(send(path(loan) + "/repayments", "finance", recordInput(loan, fitting)), 202);
        assertThat(balance(loan).available()).isEqualTo(money("0")); assertThat(balance(loan).outstanding()).isEqualTo(money("80"));
        new TransactionTemplate(transactions).executeWithoutResult(tx -> { var advance = balance(loan); long version = advance.version(); advance.settle(version, use); balances.update(advance, version, "fixture", "SETTLE"); });
        assertThat(balance(loan).status()).isEqualTo(EmployeeAdvance.Status.SETTLED); assertThat(balance(loan).balance().consumed()).isEqualTo(money("80"));
        assertThat(balance(loan).repaid()).isEqualTo(money("20"));
    }

    @Test void reversedRecordedReceiptFreezesNewUseAndNeverErasesHistory() throws Exception {
        UUID loan = paidLoan(); var first = query(loan, "reversed"); worker.poll(); ok(send(path(loan) + "/repayments", "finance", recordInput(loan, first)), 202);
        var entries = balance(loan).repayments(); receiptStatus = AdvanceRepaymentPort.Status.REVERSED; receiptRevision = 2;
        query(loan, "reversed"); worker.poll(); assertThat(balance(loan).repaymentReviewRequired()).isTrue();
        assertThat(balance(loan).available()).isEqualTo(money("0")); assertThat(balance(loan).repayments()).isEqualTo(entries);
        receiptStatus = AdvanceRepaymentPort.Status.CONFIRMED; receiptRevision = 3; query(loan, "reversed"); worker.poll();
        assertThat(balance(loan).status()).isEqualTo(EmployeeAdvance.Status.REPAYMENT_REVIEW);
        var newReceipt = query(loan, "other"); worker.poll(); code(send(path(loan) + "/repayments", "finance", recordInput(loan, newReceipt)), "ADVANCE_REPAYMENT_REVIEW_REQUIRED");
        assertThat(view(loan, "alice").path("records")).hasSize(1);
    }

    @Test void staleVersionsExpiredLeaseAndRevokedActorNeverConsumeEvidence() throws Exception {
        UUID loan = paidLoan(); var queued = query(loan, "timed-out"); var claimed = service.claim("demo", queued, Instant.now());
        assertThat(service.claim("demo", queued, claimed.leaseUntil())).isNull();
        var at = claimed.updatedAt(); var receipt = new AdvanceRepaymentPort.Receipt(claimed.input().request(), AdvanceRepaymentPort.Status.CONFIRMED, 1, at, at.plusSeconds(300),
                new AdvanceRepaymentPort.Funding(channel, "late", money("25"), at), new AdvanceRepaymentPort.Posting("late", "1", money("25"), LocalDate.now(), at));
        service.finish(claimed, new FinanceResult.Success<>(receipt), claimed.leaseUntil());
        assertThat(checks.find("demo", queued).orElseThrow().issue()).isEqualTo(AdvanceRepaymentCheck.Issue.TIMEOUT);
        var check = query(loan, "stale"); worker.poll(); var stale = new LinkedHashMap<>(recordInput(loan, check));
        stale.put("checkVersion", 1L); code(send(path(loan) + "/repayments", "finance", stale), "CONCURRENCY_CONFLICT");
        var newer = query(loan, "newer"); worker.poll(); code(send(path(loan) + "/repayments", "finance", recordInput(loan, check)), "CONCURRENCY_CONFLICT");
        var revoked = query(loan, "revoked"); jdbc.update("UPDATE organization_appointment SET active=FALSE WHERE id=?", financeAppointment.toString()); worker.poll();
        assertThat(checks.find("demo", revoked).orElseThrow().status()).isEqualTo(AdvanceRepaymentCheck.Status.VOIDED);
        assertThat(balance(loan).repaid()).isEqualTo(money("0"));
    }

    @Test void concurrentFinanceConfirmationsHaveExactlyOneWinner() throws Exception {
        UUID loan = paidLoan(); var check = query(loan, "concurrent"); worker.poll(); var input = recordInput(loan, check);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2); var gate = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Integer> action = () -> { gate.await(); return send(path(loan) + "/repayments", "finance", input).getStatus(); };
            var first = pool.submit(action); var second = pool.submit(action); gate.countDown();
            assertThat(List.of(first.get(15, java.util.concurrent.TimeUnit.SECONDS), second.get(15, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(202, 409);
        } finally { pool.shutdownNow(); }
        assertThat(balance(loan).repaid()).isEqualTo(money("25")); assertThat(view(loan, "alice").path("records")).hasSize(1);
    }

    @Test void newerQueryCannotRewritePreviouslyObservedFundsOrRollBackExternalRevision() throws Exception {
        UUID loan = paidLoan(); receiptRevision = 3; query(loan, "stable"); worker.poll();
        receiptRevision = 2; var stale = query(loan, "stable"); worker.poll();
        code(send(path(loan) + "/repayments", "finance", recordInput(loan, stale)), "ADVANCE_REPAYMENT_EVIDENCE_CHANGED");
        receiptRevision = 4; receipts.remove("stable"); amount = "30"; var changed = query(loan, "stable"); worker.poll();
        code(send(path(loan) + "/repayments", "finance", recordInput(loan, changed)), "ADVANCE_REPAYMENT_EVIDENCE_CHANGED");
        assertThat(balance(loan).repaid()).isEqualTo(money("0"));
    }

    @Test void historyCursorIsBoundedAndCannotSelectAnotherLoan() throws Exception {
        UUID loan = paidLoan(); amount = "1";
        for (int i = 0; i < 26; i++) { var check = query(loan, "history-" + i); worker.poll(); ok(send(path(loan) + "/repayments", "finance", recordInput(loan, check)), 202); }
        var first = view(loan, "alice"); assertThat(first.path("records")).hasSize(25); String cursor = first.path("nextBeforeId").asText();
        var second = ok(read(path(loan) + "/repayments?beforeId=" + cursor, "alice"), 200); assertThat(second.path("records")).hasSize(1); assertThat(second.path("nextBeforeId").isNull()).isTrue();
        assertThat(first.path("records").toString()).doesNotContain(second.at("/records/0/id").asText());
        UUID other = paidLoan(); code(read(path(other) + "/repayments?beforeId=" + cursor, "alice"), "INVALID_ADVANCE_REPAYMENT_QUERY");
    }

    private ExpenseUse reserve(UUID loan, String reserved) {
        return new TransactionTemplate(transactions).execute(tx -> {
            UUID id = UUID.randomUUID(); var application = Application.draftBusiness(UUID.randomUUID(), "demo", "EXP-" + id, "fixture", 1, "alice", "合成预留", Map.of(), null, null, null, new BusinessReference(BusinessReference.Type.EXPENSE, id));
            applications.save(application); reports.create(ExpenseReport.draft(id, "demo", application.id(), "alice", new ExpenseContent(entity, ExpenseContent.Type.DAILY, "费用", List.of(), List.of())), "alice");
            var use = new ExpenseUse(id, 1, 0); var advance = balance(loan); long version = advance.version(); advance.reserve(version, use, money(reserved)); balances.update(advance, version, "fixture", "RESERVE"); return use;
        });
    }

    private UUID paidLoan() throws Exception {
        var graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()), new Node("finance", "财务审批", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_" + financePerson)), new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("a", "start", "finance", "", false), new Edge("b", "finance", "end", "", false)));
        var schema = new FormSchema(2, List.of(new FormSchema.Field(AdvanceRequestFormContract.DETAILS, "借款明细", FormSchema.FieldType.TEXT, true, null, null, null, null, null, null, null, true, Map.of("finance", FieldVisibility.READ_ONLY)),
                new FormSchema.Field("amount", "金额", FormSchema.FieldType.NUMBER, true, null, null, null, null, null), new FormSchema.Field("currency", "币种", FormSchema.FieldType.TEXT, true, null, null, null, null, null)));
        var draft = definitions.create("demo", "repayment-" + UUID.randomUUID(), "还款验收", graph, schema, null); var definition = definitions.publish(admin, draft.id(), draft.revision(), "合成流程");
        var content = new AdvanceRequestContent(entity, "合成还款借款", "真实状态机验收", money("100"), LocalDate.now().plusDays(10));
        UUID loan = UUID.fromString(ok(send("/api/v1/advance-requests", "alice", Map.of("businessNo", "REP-" + UUID.randomUUID(), "processKey", definition.key(), "definitionVersion", definition.version(), "content", content)), 201).path("id").asText()); ownedAdvances.add(loan);
        UUID checked = UUID.fromString(ok(send(path(loan) + "/prechecks", "alice", Map.of("applicationVersion", app(loan).version(), "requestVersion", request(loan).version(), "initiatorAppointmentId", appointment, "targetDigest", configuration.destination("demo").orElseThrow().digest("demo"))), 202).path("id").asText());
        precheckWorker.poll(); ok(send(path(loan) + "/submit", "alice", Map.of("applicationVersion", app(loan).version(), "requestVersion", request(loan).version(), "precheckId", checked)), 200);
        var task = tasks.createTaskQuery().processVariableValueEquals("applicationId", app(loan).id().toString()).singleResult();
        ok(send("/api/v1/tasks/" + task.getId() + "/actions", "finance", Map.of("action", "APPROVE", "expectedVersion", app(loan).version(), "comment", "合成借款批准")), 200);
        preparationWorker.poll(); voucherWorker.poll(); var voucher = vouchers.forRound("demo", app(loan).id(), 1, VoucherCommand.Kind.EMPLOYEE_ADVANCE).orElseThrow();
        UUID payment = UUID.fromString(ok(send("/api/v1/applications/" + app(loan).id() + "/payments/authorizations", "finance", Map.of("roundNo", 1, "applicationVersion", app(loan).version(), "businessVersion", request(loan).version(), "voucherOperationId", voucher.input().command().id(), "voucherVersion", voucher.version(), "validitySeconds", 900, "comment", "合成授权")), 202).path("authorizationId").asText());
        ok(send("/api/v1/cashier/payments/" + payment + "/actions", "cashier", Map.of("action", "EXECUTE", "authorizationVersion", 1, "debitAccountReference", "debit-1", "debitAccountVersion", "v1", "comment", "合成执行")), 202);
        executionWorker.poll(); paymentWorker.poll(); assertThat(payments.find("demo", payment).orElseThrow().status()).isEqualTo(PaymentOperation.Status.SUCCEEDED);
        preparationWorker.poll(); voucherWorker.poll(); assertThat(balance(loan).available()).isEqualTo(money("100")); return loan;
    }
    private UUID query(UUID loan, String reference) throws Exception {
        var receipt = ok(send(path(loan) + "/repayment-checks", "finance", Map.of("advanceVersion", balance(loan).version(), "receiptReference", reference, "comment", "读取原收款")), 202);
        assertThat(receipt.has("repaymentId")).as("Queued receipt explicitly distinguishes no recorded repayment").isTrue();
        assertThat(receipt.path("repaymentId").isNull()).isTrue(); return UUID.fromString(receipt.path("checkId").asText());
    }
    private Map<String, Object> recordInput(UUID loan, UUID checkId) { return Map.of("advanceVersion", balance(loan).version(), "checkId", checkId, "checkVersion", checks.find("demo", checkId).orElseThrow().version(), "comment", "核对收款与原借款贷方分录"); }
    private JsonNode view(UUID loan, String user) throws Exception { return ok(read(path(loan) + "/repayments", user), 200); }
    private EmployeeAdvance balance(UUID id) { return balances.find("demo", id).orElseThrow(); }
    private AdvanceRequest request(UUID id) { return requests.find("demo", id).orElseThrow(); }
    private Application app(UUID id) { return applications.findById("demo", request(id).applicationId()).orElseThrow(); }
    private String path(UUID id) { return "/api/v1/advance-requests/" + id; }
    private MockHttpServletResponse send(String path, String user, Object value) throws Exception { return send(path, user, UUID.randomUUID().toString(), value); }
    private MockHttpServletResponse send(String path, String user, String key, Object value) throws Exception { return mvc.perform(post(path).header("Authorization", "Bearer " + auth.login("demo", user, "demo").token()).header("Idempotency-Key", key).contentType("application/json").content(json.write(value))).andReturn().getResponse(); }
    private MockHttpServletResponse read(String path, String user) throws Exception { return mvc.perform(get(path).header("Authorization", "Bearer " + auth.login("demo", user, "demo").token())).andReturn().getResponse(); }
    private JsonNode ok(MockHttpServletResponse response, int expected) throws Exception { assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected); return json.read(response.getContentAsString(StandardCharsets.UTF_8), JsonNode.class); }
    private void code(MockHttpServletResponse response, String expected) throws Exception { assertThat(response.getStatus()).isBetween(400, 499); assertThat(json.read(response.getContentAsString(), JsonNode.class).path("code").asText()).isEqualTo(expected); }
    private void okError(MockHttpServletResponse response, int expected) { assertThat(response.getStatus()).isEqualTo(expected); }
    private UUID person(String subject, boolean approver) { var found = jdbc.queryForList("SELECT id FROM organization_person WHERE tenant_id='demo' AND subject=?", String.class, subject); return found.isEmpty() ? organization.createPerson(admin, subject, subject, true, approver).id() : UUID.fromString(found.get(0)); }
    private static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
    private Object response(String operation, JsonNode data) {
        var at = Instant.now();
        return switch (operation) {
            case "catalog" -> new FinanceCatalog("alice", "v1", at.plusSeconds(600), List.of(new FinanceCatalog.LegalEntity(entity, "合成法人", "CNY", false, "v1", "UTC")), List.of(new FinanceCatalog.Category("OFFICE", "办公", List.of(ExpenseLine.Unit.ITEM))), List.of(new FinanceCatalog.CostCenter(entity, "IT", "研发")), List.of(), List.of(new FinanceCatalog.City("SH", "上海")));
            case "employee-account" -> new EmployeeAccountPort.Account(new EmployeeAccountSnapshot(entity, "alice", "private-account", "****1234", "a".repeat(64), "v1"), at.plusSeconds(600));
            case "debit-accounts" -> new PaymentAccountsPort.Directory(json.read(data.toString(), PaymentAccountsPort.Request.class), "v1", at, at.plusSeconds(300), List.of(new PaymentAccountsPort.DebitAccount("debit-1", "合成账户", "****4567", "CNY", "v1")));
            case "accounting-period" -> { var r = json.read(data.toString(), AccountingPeriodPort.Request.class); yield new AccountingPeriodPort.OpenPeriod(r, "synthetic-period", "v1", r.accountingDate().minusDays(30), r.accountingDate().plusDays(30), at, at.plusSeconds(300)); }
            case "account-mapping" -> { var r = json.read(data.toString(), AccountMappingPort.Request.class); yield new AccountMappingPort.Mapping(r, "v1", at, at.plusSeconds(300), r.keys().stream().map(key -> new AccountMappingPort.Entry(key, "synthetic-" + key.role())).toList()); }
            case "voucher-command", "voucher-query" -> {
                var command = operation.endsWith("command") ? json.read(data.path("command").toString(), VoucherCommand.class) : voucherCommands.get(UUID.fromString(data.path("operationId").asText()));
                if (operation.endsWith("command")) voucherWrites++; voucherCommands.put(command.id(), command);
                yield new VoucherObservation(command.id(), command.digest(), VoucherObservation.Status.POSTED, 1L, at, "posting-" + command.id(), "voucher-" + command.id(), command.period().periodReference(), command.accountingDate(), command.totals().gross(), command.totals().gross(), command.createdAt(), null);
            }
            case "payment-command", "payment-query" -> {
                var command = operation.endsWith("command") ? json.read(data.path("command").toString(), PaymentCommand.class) : paymentCommands.get(UUID.fromString(data.path("authorizationId").asText()));
                if (operation.endsWith("command")) paymentWrites++; paymentCommands.put(command.id(), command);
                yield new PaymentObservation(command.id(), command.digest(), PaymentObservation.Status.SUCCEEDED, 1L, at, "funding-" + command.id(), command.amount(), command.payee().accountDigest(), command.authorization().authorizedAt(), "bank-" + command.id(), null);
            }
            case "advance-repayment" -> {
                repaymentReads++; if (unavailable) yield Map.of("malformed", true);
                var request = json.read(data.toString(), AdvanceRepaymentPort.Request.class); var key = request.receiptReference();
                var original = receipts.computeIfAbsent(key, ignored -> new AdvanceRepaymentPort.Receipt(request, AdvanceRepaymentPort.Status.CONFIRMED, 1, at, at.plusSeconds(300),
                        new AdvanceRepaymentPort.Funding(channel, transactionReference == null ? "receive-" + key : transactionReference, money(amount), at),
                        new AdvanceRepaymentPort.Posting(postingReference == null ? "repay-voucher-" + key : postingReference, "row-1", money(amount), LocalDate.now(), at)));
                boolean terminal = receiptStatus == AdvanceRepaymentPort.Status.CONFIRMED || receiptStatus == AdvanceRepaymentPort.Status.REVERSED;
                yield new AdvanceRepaymentPort.Receipt(request, receiptStatus, receiptStatus == AdvanceRepaymentPort.Status.NOT_FOUND ? 0 : receiptRevision, at, at.plusSeconds(300), terminal ? original.funding() : null, terminal ? original.posting() : null);
            }
            default -> throw new IllegalStateException("Unexpected synthetic finance operation");
        };
    }
    private static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/finance/", exchange -> {
                var active = ACTIVE.get(); var request = active.json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonNode.class);
                Object value = active.response(exchange.getRequestURI().getPath().substring("/finance/".length()), request.path("data"));
                byte[] response = active.json.write(Map.of("contractVersion", 1, "tenantId", request.path("tenantId").asText(), "requestId", request.path("requestId").asText(), "outcome", "SUCCESS", "data", value)).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, response.length);
                try { exchange.getResponseBody().write(response); } finally { exchange.close(); }
            }); server.start(); return server;
        } catch (Exception problem) { throw new IllegalStateException(problem); }
    }
}
