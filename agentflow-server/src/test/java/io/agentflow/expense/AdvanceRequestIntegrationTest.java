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
import io.agentflow.finance.EmployeeAccountPort;
import io.agentflow.finance.EmployeeAccountSnapshot;
import io.agentflow.finance.FinanceGatewayConfiguration;
import io.agentflow.finance.Money;
import io.agentflow.finance.ApprovedVoucherSources;
import io.agentflow.finance.JdbcVoucherPreparationRepository;
import io.agentflow.finance.VoucherPreparation;
import io.agentflow.form.FieldVisibility;
import io.agentflow.form.FormSchema;
import io.agentflow.organization.OrganizationService;
import io.agentflow.organization.OrganizationUnit;
import io.agentflow.procurement.ProcurementPaymentFormContract;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import static io.agentflow.definition.DefinitionModels.*;
import static io.agentflow.expense.AdvanceRequestCheck.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 实际 HTTP 财务端口、认证、数据库和 Flowable 联合验收借款申请；企业数据均为合成夹具。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "agentflow.finance-gateway.enabled=true", "agentflow.timers.enabled=false",
        "agentflow.vouchers.preparation-worker-enabled=false", "agentflow.vouchers.worker-enabled=false",
        "agentflow.invoices.verification-worker-enabled=false", "agentflow.expenses.precheck-worker-enabled=false",
        "agentflow.advance-requests.precheck-worker-enabled=false", "agentflow.expense-plans.precheck-worker-enabled=false", "agentflow.budgets.worker-enabled=false",
        "agentflow.advances.overdue.reminders-enabled=false", "agentflow.advances.overdue.tenants.demo.block-new-requests=true"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class AdvanceRequestIntegrationTest {
    private static final HttpServer SERVER = server();
    private static final String ENDPOINT = "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/finance";
    private static final AtomicReference<AdvanceRequestIntegrationTest> ACTIVE = new AtomicReference<>();
    private static final Path DIRECTORY = Path.of("/fyoung/tmp/agentflow-advance-request-" + UUID.randomUUID());
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
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_ADVANCE_TEST_URL", "jdbc:h2:mem:advance-request;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_ADVANCE_TEST_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_ADVANCE_TEST_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_ADVANCE_TEST_PASSWORD", ""));
    }
    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @Autowired AuthService auth;
    @Autowired CurrentActor actors;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrganizationService organization;
    @Autowired DefinitionApplicationService definitions;
    @Autowired ApplicationRepository applications;
    @Autowired AdvanceRequestRepository advances;
    @Autowired EmployeeAdvanceRepository paidAdvances;
    @Autowired TaskService tasks;
    @Autowired io.agentflow.approval.process.TimerWaitService timerWaits;
    @Autowired org.flowable.engine.ManagementService timerJobs;
    @Autowired org.flowable.engine.ProcessEngine engine;
    @Autowired AdvanceRequestCheckService execution;
    @Autowired AdvanceRequestCheckWorker worker;
    @Autowired JdbcAdvanceRequestCheckRepository checks;
    @Autowired FinanceGatewayConfiguration configuration;
    @Autowired ApprovedVoucherSources voucherSources;
    @Autowired JdbcVoucherPreparationRepository voucherPreparations;
    @Autowired AdvanceOverdueConfiguration overdueConfiguration;
    @Autowired AdvanceOverdueChecks overdueChecks;
    @Autowired JdbcAdvanceOverdueRepository overdueRepository;
    @Autowired AdvanceOverdueReminders overdueReminders;

    @BeforeEach void setup() {
        ACTIVE.set(this); configuration.setEnabled(true); configuration.getTenants().get("demo").setEndpoint(ENDPOINT);
        var overduePolicy = new AdvanceOverdueConfiguration.Tenant(); overduePolicy.setBlockNewRequests(true);
        overdueConfiguration.setTenants(new java.util.LinkedHashMap<>(Map.of("demo", overduePolicy)));
        if (jdbc.queryForObject("SELECT COUNT(*) FROM organization_directory WHERE tenant_id='demo'", Integer.class) == 0) organization.initialize(admin);
        entity = organization.createUnit(admin, OrganizationUnit.Kind.LEGAL_ENTITY, "合成事前法人", null, null, true).id();
        var department = organization.createUnit(admin, OrganizationUnit.Kind.DEPARTMENT, "合成部门", entity, null, true);
        var position = organization.createUnit(admin, OrganizationUnit.Kind.POSITION, "合成岗位", entity, null, true);
        appointment = organization.createAppointment(admin, person("alice", false), department.id(), position.id(), true).id();
        manager = person("manager", true); responder = this::normal;
    }
    @AfterEach void settle() {
        actors.clear(); responder = this::normal;
        for (String id : jdbc.queryForList("SELECT id FROM advance_request_check_job WHERE status IN ('QUEUED','RUNNING')", String.class)) {
            var job = check(UUID.fromString(id));
            if (job.status() == Status.QUEUED) job = execution.claim("demo", job.input().id(), Instant.now());
            if (job != null) execution.finish(job, Result.unavailable("INTERNAL_ERROR"), Instant.now());
        }
    }
    @AfterAll static void closeServer() { SERVER.stop(0); }

    @Test void overdueBalanceAppearingAfterReadyCannotBypassTheSubmissionGate() throws Exception {
        UUID id = create(); UUID checked = ready(id);
        var today = LocalDate.now(java.time.ZoneId.of("Pacific/Kiritimati"));
        var paid = new EmployeeAdvance(UUID.randomUUID(), "demo", entity, "alice", money("100", "CNY"),
                "overdue-" + UUID.randomUUID(), today.minusDays(10), today.minusDays(1));
        paidAdvances.create(paid, "test");
        var before = app(id); long requestVersion = current(id).version();
        code(send(path(id) + "/submit", "alice", submission(id, checked)), "ADVANCE_OVERDUE");
        assertThat(app(id).status()).isEqualTo(ApplicationStatus.DRAFT);
        assertThat(app(id).version()).isEqualTo(before.version());
        assertThat(current(id).version()).isEqualTo(requestVersion);
        assertThat(current(id).rounds()).isEmpty();
    }

    @Test void overduePrechecksRemainBlockedUntilTheActualBalanceIsFullyRepaid() throws Exception {
        UUID id = create(); var today = LocalDate.now(java.time.ZoneId.of("Pacific/Kiritimati"));
        var paid = overdueBalance("demo", "alice", entity, today.minusDays(1), "CNY");
        assertThat(ok(read(path(id) + "/prechecks/options", "alice"), 200).path("overduePolicy").asText()).isEqualTo("BLOCK");
        UUID first = enqueue(id); worker.poll();
        assertThat(check(first).status()).isEqualTo(Status.BLOCKED); assertThat(check(first).result().code()).isEqualTo("ADVANCE_OVERDUE");
        repay(paid, "20"); UUID second = enqueue(id); worker.poll();
        assertThat(check(second).result().code()).isEqualTo("ADVANCE_OVERDUE");
        repay(paid, "80"); UUID third = ready(id);
        ok(send(path(id) + "/submit", "alice", submission(id, third)), 200);
        assertThat(app(id).status()).isEqualTo(ApplicationStatus.IN_APPROVAL);
        assertThat(paidAdvances.find("demo", paid.id()).orElseThrow().outstanding()).isEqualTo(money("0", "CNY"));
    }

    @Test void explicitAllowAndUnconfiguredPoliciesDoNotPretendToEnforceBlocking() throws Exception {
        var today = LocalDate.now(java.time.ZoneId.of("Pacific/Kiritimati"));
        overdueBalance("demo", "alice", entity, today.minusDays(1), "CNY");
        for (boolean configured : List.of(true, false)) {
            if (configured) overdueConfiguration.getTenants().get("demo").setBlockNewRequests(false);
            else overdueConfiguration.setTenants(new java.util.LinkedHashMap<>());
            UUID id = create();
            assertThat(ok(read(path(id) + "/prechecks/options", "alice"), 200).path("overduePolicy").asText()).isEqualTo(configured ? "ALLOW" : "UNCONFIGURED");
            submit(id); assertThat(app(id).status()).isEqualTo(ApplicationStatus.IN_APPROVAL);
        }
    }

    @Test void overdueScopeUsesLegalEntityDateAndAllItsCurrenciesWithoutLeakingOtherOwners() {
        var due = LocalDate.parse("2026-10-10"); var at = Instant.parse("2026-10-10T10:00:00Z");
        var east = new FinanceCatalog.LegalEntity(entity, "合成法人", "CNY", false, "v1", "Pacific/Kiritimati");
        var west = new FinanceCatalog.LegalEntity(entity, "合成法人", "CNY", false, "v1", "America/Los_Angeles");
        overdueBalance("other-tenant", "alice", entity, due.minusDays(1), "CNY");
        overdueBalance("demo", "bob", entity, due.minusDays(1), "CNY");
        overdueBalance("demo", "alice", UUID.randomUUID(), due.minusDays(1), "CNY");
        assertThat(overdueChecks.failure("demo", "alice", east, at)).isNull();
        var paid = overdueBalance("demo", "alice", entity, due, "USD");
        paid.requirePaymentReview(paid.version()); paidAdvances.update(paid, paid.version() - 1, "test", "PAYMENT_REVIEW");
        assertThat(paid.available()).isEqualTo(money("0", "USD"));
        assertThat(overdueChecks.failure("demo", "alice", west, at)).isNull();
        assertThat(overdueChecks.failure("demo", "alice", east, at.minusSeconds(1))).isNull();
        assertThat(overdueChecks.failure("demo", "alice", east, at)).isEqualTo("ADVANCE_OVERDUE");
    }

    @Test void overdueGateReadsPastAFullPageOfSettledLoans() {
        var due = LocalDate.parse("2026-10-10"); var at = Instant.parse("2026-10-10T10:00:00Z");
        var legal = new FinanceCatalog.LegalEntity(entity, "合成法人", "CNY", false, "v1", "Pacific/Kiritimati");
        for (int index = 0; index < JdbcAdvanceOverdueRepository.BATCH_SIZE; index++) repay(overdueBalance("demo", "alice", entity, due.minusDays(1), "CNY"), "100");
        assertThat(overdueChecks.failure("demo", "alice", legal, at)).isNull();
        overdueBalance("demo", "alice", entity, due, "CNY");
        assertThat(overdueChecks.failure("demo", "alice", legal, at)).isEqualTo("ADVANCE_OVERDUE");
    }

    @Test void overdueReminderUsesFrozenZoneAndLatestBalanceAndRemainsOnceAfterRepayment() throws Exception {
        var paid = approvedBalance(); var candidate = overdueCandidate(paid);
        var at = paid.dueOn().plusDays(1).atStartOfDay(java.time.ZoneId.of("Pacific/Kiritimati")).toInstant();
        assertThat(overdueReminders.remind(candidate, at.minusSeconds(1))).isFalse();
        repay(paid, "20");
        assertThat(overdueReminders.remind(candidate, at)).isTrue();
        assertThat(overdueReminders.remind(candidate, at.plusSeconds(5))).isFalse();
        var evidence = jdbc.queryForMap("SELECT * FROM advance_overdue_reminder WHERE tenant_id='demo' AND advance_id=?", paid.id().toString());
        assertThat(evidence.get("outstanding")).isEqualTo(new BigDecimal("80.00"));
        assertThat(evidence.get("time_zone")).isEqualTo("Pacific/Kiritimati");
        assertThat(((java.sql.Date) evidence.get("observed_on")).toLocalDate()).isEqualTo(paid.dueOn().plusDays(1));
        String message = evidence.get("inbox_id").toString();
        assertThat(ok(read("/api/v1/notifications?limit=100", "alice"), 200).path("items").toString()).contains(message, "ADVANCE_OVERDUE");
        assertThat(ok(read("/api/v1/notifications?limit=100", "bob"), 200).path("items").toString()).doesNotContain(message);
        assertThat(send("/api/v1/notifications/" + message + "/read", "bob", Map.of()).getStatus()).isEqualTo(404);
        ok(send("/api/v1/notifications/" + message + "/read", "alice", Map.of()), 200);
        repay(paid, "80");
        assertThat(overdueReminders.remind(candidate, at.plusSeconds(86400))).isFalse();
        assertThat(jdbc.queryForMap("SELECT * FROM advance_overdue_reminder WHERE tenant_id='demo' AND advance_id=?", paid.id().toString())).isEqualTo(evidence);
        assertThat(overdueRepository.candidates(at, null)).doesNotContain(candidate);
        var settledBeforeScan = approvedBalance(); repay(settledBeforeScan, "100");
        assertThat(overdueReminders.remind(overdueCandidate(settledBeforeScan), at)).isFalse();
        assertThat(overdueRepository.recorded(overdueCandidate(settledBeforeScan))).isFalse();
    }

    @Test void overdueReminderRollsBackWithItsEvidenceAndConcurrentScansCommitOnlyOne() throws Exception {
        var paid = approvedBalance(); var candidate = overdueCandidate(paid);
        var at = paid.dueOn().plusDays(1).atStartOfDay(java.time.ZoneId.of("Pacific/Kiritimati")).toInstant();
        jdbc.execute("ALTER TABLE advance_overdue_reminder ADD CONSTRAINT ck_overdue_fixture CHECK (advance_id <> '" + paid.id() + "')");
        try {
            assertThatThrownBy(() -> overdueReminders.remind(candidate, at)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(overdueRepository.recorded(candidate)).isFalse();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE tenant_id='demo' AND event_key=?", Integer.class, "advance-overdue:" + paid.id())).isZero();
        } finally { jdbc.execute("ALTER TABLE advance_overdue_reminder DROP CONSTRAINT ck_overdue_fixture"); }
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> { await(start); return overdueReminders.remind(candidate, at); });
            var second = pool.submit(() -> { await(start); return overdueReminders.remind(candidate, at); });
            start.countDown(); assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        } finally { pool.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE tenant_id='demo' AND event_key=?", Integer.class, "advance-overdue:" + paid.id())).isEqualTo(1);
        assertThat(overdueRepository.recorded(candidate)).isTrue();
    }

    @Autowired io.agentflow.expense.reporting.ExpenseFinancialReportQuery financialReporting;

    @Test void financialReportAgingUsesOriginalLegalTimeZoneAndTreatsCategoryAsInapplicable() throws Exception {
        var paid = approvedBalance();
        Instant at = paid.dueOn().plusDays(1).atStartOfDay(java.time.ZoneId.of("Pacific/Kiritimati")).toInstant();
        var day = at.atOffset(java.time.ZoneOffset.UTC).toLocalDate();
        var query = new io.agentflow.expense.reporting.ExpenseReportQueryParameters.Query(day, day, entity, null, null);
        actors.set(new Actor("demo", "manager", Set.of("FINANCE", "APPROVER")));
        try {
            var report = financialReporting.read(query, at);
            assertThat(report.resources().advances()).hasSize(1);
            var result = report.resources().advances().get(0);
            assertThat(result.outstanding()).isEqualTo("100.00");
            assertThat(result.ages().stream().filter(age -> age.band() == io.agentflow.expense.reporting.ExpenseResourceMetrics.AgeBand.DAYS_1_30)
                    .findFirst().orElseThrow().accounts()).isOne();
            var categorized = financialReporting.read(new io.agentflow.expense.reporting.ExpenseReportQueryParameters.Query(day, day, entity, null, "TRAVEL"), at);
            assertThat(categorized.resources().advanceCategoryApplicable()).isFalse(); assertThat(categorized.resources().advances()).isEmpty();
        } finally { actors.clear(); }
    }

    private EmployeeAdvance approvedBalance() throws Exception {
        UUID id = create(); submit(id); ok(act(id, "APPROVE"), 200); ok(act(id, "APPROVE"), 200);
        var paid = new EmployeeAdvance(id, "demo", entity, "alice", money("100", "CNY"), "synthetic-paid-" + id,
                LocalDate.now(java.time.ZoneId.of("Pacific/Kiritimati")), current(id).content().dueOn());
        paidAdvances.create(paid, "test"); return paid;
    }
    private EmployeeAdvance overdueBalance(String tenant, String employee, UUID legal, LocalDate due, String currency) {
        var paid = new EmployeeAdvance(UUID.randomUUID(), tenant, legal, employee, money("100", currency), "synthetic-overdue-" + UUID.randomUUID(), due.minusDays(10), due);
        paidAdvances.create(paid, "test"); return paid;
    }
    private void repay(EmployeeAdvance paid, String amount) {
        var now = Instant.now(); String reference = UUID.randomUUID().toString(); var value = money(amount, paid.balance().limit().currency());
        var request = new io.agentflow.finance.AdvanceRepaymentPort.Request(paid.id(), paid.legalEntityId(), paid.employeeId(), paid.paymentReference(), value.currency(), reference);
        var receipt = new io.agentflow.finance.AdvanceRepaymentPort.Receipt(request, io.agentflow.finance.AdvanceRepaymentPort.Status.CONFIRMED, 1, now.minusSeconds(1), now.plusSeconds(60),
                new io.agentflow.finance.AdvanceRepaymentPort.Funding(io.agentflow.finance.AdvanceRepaymentPort.Channel.CASH, "funding-" + reference, value, now.minusSeconds(10)),
                new io.agentflow.finance.AdvanceRepaymentPort.Posting("voucher-" + reference, "entry-" + reference, value, LocalDate.now(), now.minusSeconds(2)));
        long version = paid.version(); paid.repay(version, new AdvanceRepayment(UUID.randomUUID(), paid.tenantId(), UUID.randomUUID(), receipt, "finance", now, "合成实际还款凭据"));
        paidAdvances.update(paid, version, "finance", "REPAY");
    }
    private static JdbcAdvanceOverdueRepository.Candidate overdueCandidate(EmployeeAdvance paid) { return new JdbcAdvanceOverdueRepository.Candidate(paid.tenantId(), paid.id(), paid.dueOn()); }

    @Test void createReplaysOneBindingAndPreservesMoneyWithoutPaidBalance() throws Exception {
        var definition = published(false, false); var body = createBody(definition, content("100")); String key = UUID.randomUUID().toString();
        var first = send("/api/v1/advance-requests", "alice", key, body); var created = ok(first, 201); UUID id = id(created);
        assertThat(send("/api/v1/advance-requests", "alice", key, body).getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(created.has("content")).isFalse();
        assertThat(ok(read(path(id), "alice"), 200).at("/content/amount/value").asText()).isEqualTo("100.00");
        assertThat(app(id).businessReference().type().name()).isEqualTo("ADVANCE_REQUEST");
        assertThat(app(id).payload()).isEqualTo(AdvanceRequestFormContract.draftPayload());
        assertThat(paidAdvances.find("demo", id)).isEmpty(); assertThat(current(id).rounds()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM advance_request_revision WHERE request_id=?", Integer.class, id.toString())).isEqualTo(1);
        var forged = new java.util.HashMap<String, Object>(body); forged.put("employeeId", "bob");
        assertThat(send("/api/v1/advance-requests", "alice", forged).getStatus()).isBetween(400, 499);
    }

    @Test void onlyFinalApprovalSealsTermsAndNeverCreatesAPaidAdvance() throws Exception {
        UUID id = create(); UUID checked = ready(id); var preview = check(checked).result().evidence().preview();
        assertThat(preview.content().amount()).isEqualTo(money("100", "CNY")); assertThat(current(id).version()).isEqualTo(1);
        assertThat(app(id).status()).isEqualTo(ApplicationStatus.DRAFT); assertThat(paidAdvances.find("demo", id)).isEmpty();
        assertThat(calls.keySet()).containsExactlyInAnyOrder("catalog", "employee-account");
        var display = ok(read(path(id) + "/prechecks/" + checked, "alice"), 200);
        assertThat(display.at("/preview/maskedAccount").asText()).isEqualTo("****1234");
        assertThat(display.toString()).doesNotContain("private-account-reference", "accountDigest", "accountReference", "a".repeat(64));
        String key = UUID.randomUUID().toString(); var input = submission(id, checked); var first = send(path(id) + "/submit", "alice", key, input);
        ok(first, 200); assertThat(send(path(id) + "/submit", "alice", key, input).getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(current(id).currentRound().account()).isEqualTo(preview.account());
        assertThat(app(id).payload().get("amount")).isEqualTo("100.00"); assertThat(current(id).approval()).isNull();
        var firstApproval = ok(act(id, "APPROVE"), 200); assertThat(firstApproval.path("applicationStatus").asText()).isEqualTo("IN_APPROVAL");
        assertThat(current(id).approval()).isNull(); assertThat(paidAdvances.find("demo", id)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM voucher_preparation WHERE tenant_id='demo' AND business_id=?", Integer.class, id.toString())).isZero();
        String lastPath = actionPath(id); String approvalKey = UUID.randomUUID().toString(); var decision = decision(id, "APPROVE");
        var approved = send(lastPath, "manager", approvalKey, decision); ok(approved, 200);
        assertThat(send(lastPath, "manager", approvalKey, decision).getContentAsString()).isEqualTo(approved.getContentAsString());
        assertThat(current(id).approval().applicationVersion()).isEqualTo(app(id).version());
        assertThat(current(id).approval().roundNo()).isEqualTo(1); assertThat(current(id).approval().approvedBy()).isEqualTo("manager");
        assertThat(current(id).version()).isEqualTo(3); assertThat(paidAdvances.find("demo", id)).isEmpty();
        assertThat(app(id).status()).isEqualTo(ApplicationStatus.APPROVED);
        var preparation = voucherPreparations.latest(voucherSources.reference(app(id))).orElseThrow();
        assertThat(preparation.status()).isEqualTo(VoucherPreparation.Status.QUEUED);
        assertThat(preparation.input().source().businessVersion()).isEqualTo(current(id).version());
        assertThat(preparation.input().source().applicationVersion()).isEqualTo(app(id).version());
        assertThat(preparation.input().attempt()).isEqualTo(1); assertThat(preparation.input().requestedBy()).isEqualTo("manager");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM voucher_preparation WHERE tenant_id='demo' AND business_id=?", Integer.class, id.toString())).isEqualTo(1);
        assertThat(calls.keySet()).containsExactlyInAnyOrder("catalog", "employee-account");
        for (String user : List.of("alice", "manager")) {
            var view = ok(read(path(id), user), 200);
            assertThat(view.at("/financialRound/maskedAccount").asText()).isEqualTo("****1234");
            assertThat(view.toString()).doesNotContain("private-account-reference", "accountDigest", "accountReference");
        }
        assertThat(send(path(id) + "/cancel", "alice", lifecycle(id)).getStatus()).isBetween(400, 499);
        assertThat(send(lastPath, "manager", decision).getStatus()).isBetween(400, 499);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM advance_request_revision WHERE request_id=? AND operation='APPROVE'", Integer.class, id.toString())).isEqualTo(1);
    }

    @Test void returnedRevisionPreservesOldReviewerSnapshotAndApprovesOnlyTheNewRound() throws Exception {
        UUID id = create(); submit(id); var original = current(id).currentRound(); ok(act(id, "RETURN"), 200);
        assertThat(paidAdvances.find("demo", id)).isEmpty(); ok(send(path(id) + "/revise", "alice", revision(id, "50")), 200);
        assertThat(read(path(id), "manager").getStatus()).isEqualTo(404);
        var old = ok(read(path(id) + "?roundNo=1", "manager"), 200);
        assertThat(old.at("/content/amount/value").asText()).isEqualTo("100.00");
        assertThat(read(path(id) + "?roundNo=1", "admin").getStatus()).isEqualTo(403);
        submit(id); ok(act(id, "APPROVE"), 200); ok(act(id, "APPROVE"), 200);
        assertThat(current(id).rounds().get(0)).isEqualTo(original);
        assertThat(current(id).approval().roundNo()).isEqualTo(2);
        assertThat(current(id).currentRound().content().amount()).isEqualTo(money("50", "CNY"));
        assertThat(paidAdvances.find("demo", id)).isEmpty();
        assertThat(ok(read(path(id) + "?roundNo=1", "manager"), 200).has("approval")).isFalse();
        assertThat(app(id).roundNo()).isEqualTo(2);
        var preparation = voucherPreparations.latest(voucherSources.reference(app(id))).orElseThrow();
        assertThat(preparation.input().source().roundNo()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM voucher_preparation WHERE tenant_id='demo' AND business_id=? AND round_no=1", Integer.class, id.toString())).isZero();
    }

    @Test void ownListAndEveryWriteOrPrecheckRejectAnotherIdentityAndUnknownFilters() throws Exception {
        UUID id = create(); UUID checked = ready(id);
        for (String user : List.of("bob", "admin", "manager")) {
            for (String suffix : List.of("", "/prechecks/options", "/prechecks", "/prechecks/" + checked)) assertThat(read(path(id) + suffix, user).getStatus()).isEqualTo(404);
            assertThat(send(path(id) + "/revise", user, revision(id, "99")).getStatus()).isEqualTo(404);
            assertThat(send(path(id) + "/submit", user, submission(id, checked)).getStatus()).isEqualTo(404);
            assertThat(ok(read("/api/v1/advance-requests", user), 200).path("items").isEmpty()).isTrue();
        }
        assertThat(read("/api/v1/advance-requests?employeeId=alice", "admin").getStatus()).isEqualTo(400);
        assertThat(read("/api/v1/advance-requests?limit=101", "alice").getStatus()).isEqualTo(400);
        assertThat(read("/api/v1/advance-requests?beforeId=" + UUID.randomUUID(), "alice").getStatus()).isEqualTo(400);
        assertThat(advances.find("foreign", id)).isEmpty(); assertThat(checks.find("foreign", checked)).isEmpty();
        UUID another = create(); assertThat(read(path(another) + "/prechecks/" + checked, "alice").getStatus()).isEqualTo(404);
    }

    @Test void genericApplicationWritesCannotForgeAPlanOrBypassPreparation() throws Exception {
        var definition = published(false, false); var body = createBody(definition, content("100"));
        code(send("/api/v1/applications", "alice", Map.of("businessNo", "forged-" + UUID.randomUUID(), "processKey", definition.key(),
                "definitionVersion", definition.version(), "title", "伪造借款", "payload", AdvanceRequestFormContract.draftPayload())), "USE_BUSINESS_ENDPOINT");
        UUID id = id(ok(send("/api/v1/advance-requests", "alice", body), 201));
        for (String action : List.of("submit", "withdraw", "cancel")) code(send("/api/v1/applications/" + app(id).id() + "/" + action, "alice", Map.of("expectedVersion", 1)), "USE_BUSINESS_ENDPOINT");
        code(send(path(id) + "/submit", "alice", submission(id, UUID.randomUUID())), "NOT_FOUND");
        assertThat(app(id).status()).isEqualTo(ApplicationStatus.DRAFT); assertThat(paidAdvances.find("demo", id)).isEmpty();
    }

    @Test void genericApplicationCannotBypassTheReservedProcurementForm() throws Exception {
        var original = published(false, false);
        var access = Map.of("review", FieldVisibility.READ_ONLY, "finalReview", FieldVisibility.READ_ONLY);
        var schema = new FormSchema(2, List.of(new FormSchema.Field(ProcurementPaymentFormContract.DETAILS, "采购应付明细", FormSchema.FieldType.TEXT,
                true, null, null, null, null, null, null, null, true, access),
                new FormSchema.Field("amount", "本次付款额", FormSchema.FieldType.NUMBER, true, null, null, null, null, null),
                new FormSchema.Field("currency", "币种", FormSchema.FieldType.TEXT, true, null, null, null, null, null)));
        var draft = definitions.create("demo", "procurement-guard-" + UUID.randomUUID(), "采购付款入口隔离", original.graph(), schema, null);
        var definition = definitions.publish(admin, draft.id(), draft.revision(), "采购付款入口验收");
        String businessNo = "forged-procurement-" + UUID.randomUUID();
        code(send("/api/v1/applications", "alice", Map.of("businessNo", businessNo, "processKey", definition.key(),
                "definitionVersion", definition.version(), "title", "伪造采购付款", "payload", ProcurementPaymentFormContract.draftPayload())), "USE_BUSINESS_ENDPOINT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_application WHERE tenant_id='demo' AND business_no=?", Integer.class, businessNo)).isZero();
    }

    @Test void noHumanPathOrMaskedReviewCannotSubmitAndNoRoundSurvivesFailure() throws Exception {
        for (var definition : List.of(published(true, false), published(false, true))) {
            UUID id = id(ok(send("/api/v1/advance-requests", "alice", createBody(definition, content("100"))), 201));
            UUID checked = ready(id); var response = send(path(id) + "/submit", "alice", submission(id, checked));
            assertThat(tree(response).path("code").asText()).isIn("ADVANCE_REQUEST_REVIEW_REQUIRED", "ADVANCE_REQUEST_REVIEW_FIELDS_REQUIRED");
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
        code(send(path(id) + "/revise", "alice", Map.of("applicationVersion", app(id).version(), "requestVersion", 1, "content", content("30"))), "CONCURRENCY_CONFLICT");
        assertThat(current(id).content().amount()).isEqualTo(money("50", "CNY"));
    }

    @Test void withdrawResubmitAndRejectNeverCreateAnAllowance() throws Exception {
        UUID id = create(); submit(id); var original = current(id).currentRound();
        ok(send(path(id) + "/withdraw", "alice", lifecycle(id)), 200);
        assertThat(app(id).status()).isEqualTo(ApplicationStatus.WITHDRAWN); submit(id);
        assertThat(current(id).rounds().get(0)).isEqualTo(original); assertThat(app(id).roundNo()).isEqualTo(2);
        ok(act(id, "REJECT"), 200); assertThat(paidAdvances.find("demo", id)).isEmpty();
        UUID cancelled = create(); ok(send(path(cancelled) + "/cancel", "alice", lifecycle(cancelled)), 200);
        assertThat(ok(read(path(cancelled), "alice"), 200).path("editable").asBoolean()).isFalse();
        assertThat(paidAdvances.find("demo", cancelled)).isEmpty();
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
        assertThat(own.at("/content/amount/value").asText()).isEqualTo("40.00");
        assertThat(own.path("editable").asBoolean()).isFalse();
        assertThat(own.path("financialRound").isMissingNode()).isTrue();
        assertThat(read(path(id), "manager").getStatus()).isEqualTo(404);
        assertThat(ok(read(path(id) + "?roundNo=1", "manager"), 200).at("/content/amount/value").asText()).isEqualTo("100.00");
        assertThat(paidAdvances.find("demo", id)).isEmpty();
    }

    @Test void concurrentWorkersIssueOnlyOneActualCatalogAndAccountRequest() throws Exception {
        UUID id = create(); UUID checked = enqueue(id); var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { await(start); worker.poll(); }); var second = pool.submit(() -> { await(start); worker.poll(); });
            start.countDown(); first.get(15, TimeUnit.SECONDS); second.get(15, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertThat(check(checked).status()).isEqualTo(Status.READY);
        assertThat(calls.get("catalog").get()).isEqualTo(1); assertThat(calls.get("employee-account").get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM advance_request_check_revision WHERE job_id=?", Integer.class, checked.toString())).isEqualTo(3);
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
        assertThat(paidAdvances.find("demo", id)).isEmpty(); assertThat(current(id).rounds()).isEmpty();
    }

    @Test void unavailableAndBusinessRejectedRemainDistinctAndNeverCallUnneededPorts() throws Exception {
        UUID id = create();
        responder = (operation, request) -> json.write(Map.of("contractVersion", 1, "tenantId", "demo", "requestId", request.path("requestId").asText(), "outcome", "REJECTED", "reason", "EMPLOYEE_UNAVAILABLE"));
        UUID rejected = enqueue(id); worker.poll(); assertThat(check(rejected).status()).isEqualTo(Status.BLOCKED);
        assertThat(check(rejected).result().code()).isEqualTo("EMPLOYEE_UNAVAILABLE"); assertThat(calls.keySet()).containsExactly("catalog");
        code(send(path(id) + "/submit", "alice", submission(id, rejected)), "PRECHECK_NOT_READY");
    }

    @Test void currencyAndOverdueTermsBlockBeforeAnyRoundOrApprovalIsCreated() throws Exception {
        for (var content : List.of(new AdvanceRequestContent(entity, "币种边界", "客户现场", money("100", "USD"), LocalDate.now().plusDays(10)),
                new AdvanceRequestContent(entity, "日期边界", "客户现场", money("100", "CNY"), LocalDate.now().minusDays(2)))) {
            UUID id = id(ok(send("/api/v1/advance-requests", "alice", createBody(published(false, false), content)), 201));
            UUID checked = enqueue(id); worker.poll();
            assertThat(check(checked).status()).isEqualTo(Status.BLOCKED);
            assertThat(check(checked).result().code()).isIn("ADVANCE_BASE_CURRENCY_REQUIRED", "ADVANCE_REPAYMENT_DATE_PASSED");
            assertThat(current(id).rounds()).isEmpty(); assertThat(current(id).approval()).isNull();
            assertThat(paidAdvances.find("demo", id)).isEmpty();
        }
    }

    @Test void wrongEmployeeOrExpiredExternalAccountCannotProduceUsableFacts() throws Exception {
        UUID id = create();
        for (boolean expired : List.of(false, true)) {
            responder = (operation, request) -> {
                if (!operation.equals("employee-account")) return normal(operation, request);
                var account = new EmployeeAccountPort.Account(new EmployeeAccountSnapshot(entity, expired ? "alice" : "bob",
                        "private-invalid-account", "****9876", "b".repeat(64), "v2"), expired ? Instant.now().minusSeconds(1) : Instant.now().plusSeconds(300));
                return json.write(Map.of("contractVersion", 1, "tenantId", "demo", "requestId", request.path("requestId").asText(), "outcome", "SUCCESS", "data", account));
            };
            UUID checked = enqueue(id); worker.poll();
            assertThat(check(checked).status()).isEqualTo(Status.UNAVAILABLE);
            assertThat(check(checked).result().code()).isEqualTo("INVALID_RESPONSE");
            var view = ok(read(path(id) + "/prechecks/" + checked, "alice"), 200);
            assertThat(view.path("usable").asBoolean()).isFalse(); assertThat(view.has("preview")).isFalse();
            assertThat(view.toString()).doesNotContain("private-invalid-account", "9876");
        }
        var forged = json.read(json.write(content("100")), new com.fasterxml.jackson.core.type.TypeReference<java.util.HashMap<String, Object>>() { });
        forged.put("accountReference", "client-account");
        var definition = published(false, false);
        assertThat(send("/api/v1/advance-requests", "alice", Map.of("businessNo", "FORGED-" + UUID.randomUUID(), "processKey", definition.key(),
                "definitionVersion", definition.version(), "content", forged)).getStatus()).isBetween(400, 499);
        assertThat(current(id).rounds()).isEmpty(); assertThat(paidAdvances.find("demo", id)).isEmpty();
    }

    @Test void failedApprovalWriteRollsBackApplicationEngineAndCompletedRound() throws Exception {
        UUID id = create(); submit(id); ok(act(id, "APPROVE"), 200); long before = app(id).version(); String task = actionPath(id);
        jdbc.execute("ALTER TABLE advance_request_revision ADD CONSTRAINT ck_advance_approval_fixture CHECK (request_id <> '" + id + "' OR operation <> 'APPROVE')");
        try {
            assertThatThrownBy(() -> act(id, "APPROVE")).isInstanceOf(jakarta.servlet.ServletException.class)
                    .hasRootCauseInstanceOf(java.sql.SQLException.class);
            assertThat(app(id).status()).isEqualTo(ApplicationStatus.IN_APPROVAL); assertThat(app(id).version()).isEqualTo(before);
            assertThat(actionPath(id)).isEqualTo(task); assertThat(paidAdvances.find("demo", id)).isEmpty();
            assertThat(current(id).version()).isEqualTo(2); assertThat(current(id).approval()).isNull();
            assertThat(jdbc.queryForObject("SELECT status FROM approval_submission_round WHERE tenant_id='demo' AND application_id=? AND round_no=1", String.class, app(id).id().toString())).isEqualTo("IN_APPROVAL");
        } finally { jdbc.execute("ALTER TABLE advance_request_revision DROP CONSTRAINT ck_advance_approval_fixture"); }
        ok(act(id, "APPROVE"), 200); assertThat(current(id).approval()).isNotNull();
        assertThat(paidAdvances.find("demo", id)).isEmpty();
    }

    @Test void failedVoucherQueueWriteRollsBackFinalTaskRoundAndAdvanceApprovalTogether() throws Exception {
        UUID id = create(); submit(id); ok(act(id, "APPROVE"), 200); long before = app(id).version(); String task = actionPath(id);
        jdbc.execute("ALTER TABLE voucher_preparation ADD CONSTRAINT ck_voucher_approval_fixture CHECK (business_id <> '" + id + "')");
        try {
            assertThatThrownBy(() -> act(id, "APPROVE")).isInstanceOf(jakarta.servlet.ServletException.class).hasRootCauseInstanceOf(java.sql.SQLException.class);
            assertThat(app(id).status()).isEqualTo(ApplicationStatus.IN_APPROVAL); assertThat(app(id).version()).isEqualTo(before);
            assertThat(actionPath(id)).isEqualTo(task); assertThat(current(id).version()).isEqualTo(2); assertThat(current(id).approval()).isNull();
            assertThat(jdbc.queryForObject("SELECT status FROM approval_submission_round WHERE tenant_id='demo' AND application_id=? AND round_no=1", String.class, app(id).id().toString())).isEqualTo("IN_APPROVAL");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM voucher_preparation WHERE tenant_id='demo' AND business_id=?", Integer.class, id.toString())).isZero();
        } finally { jdbc.execute("ALTER TABLE voucher_preparation DROP CONSTRAINT ck_voucher_approval_fixture"); }
        ok(act(id, "APPROVE"), 200); assertThat(voucherPreparations.latest(voucherSources.reference(app(id)))).isPresent();
    }

    @Test void finalTimerAndFinancialPreparationCommitTogetherAfterTheRealHumanDecisions() throws Exception {
        UUID id = id(ok(send("/api/v1/advance-requests", "alice", createBody(published(false, false, true), content("100"))), 201));
        submit(id); ok(act(id, "APPROVE"), 200);
        // 只调整该测试的引擎时钟，管理员重试仍核对真实时间，不等待或修改持久到期值。
        engine.getProcessEngineConfiguration().getClock().setCurrentTime(java.util.Date.from(Instant.now().minusSeconds(120)));
        try { ok(act(id, "APPROVE"), 200); }
        finally { engine.getProcessEngineConfiguration().getClock().reset(); }
        var before = app(id); long previous = before.version();
        String instanceId = engine.getRuntimeService().createProcessInstanceQuery().variableValueEquals("applicationId", before.id().toString()).singleResult().getId();
        var job = timerJobs.createTimerJobQuery().processInstanceId(instanceId).singleResult();
        assertThat(before.status()).isEqualTo(ApplicationStatus.IN_APPROVAL);
        assertThat(current(id).approval()).isNull(); assertThat(paidAdvances.find("demo", id)).isEmpty();
        jdbc.execute("ALTER TABLE voucher_preparation ADD CONSTRAINT ck_timer_voucher_fixture CHECK (business_id <> '" + id + "')");
        try {
            assertThatThrownBy(() -> timerWaits.advance(job.getId(), Instant.now())).isInstanceOf(RuntimeException.class);
            assertThat(timerJobs.createTimerJobQuery().jobId(job.getId()).singleResult().getDuedate()).isEqualTo(job.getDuedate());
            assertThat(app(id).version()).isEqualTo(previous); assertThat(current(id).approval()).isNull();
            assertThat(jdbc.queryForObject("SELECT status FROM approval_submission_round WHERE application_id=? AND round_no=1", String.class, before.id().toString())).isEqualTo("IN_APPROVAL");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='TIMER_ELAPSED'", Integer.class, before.id().toString())).isZero();
        } finally { jdbc.execute("ALTER TABLE voucher_preparation DROP CONSTRAINT ck_timer_voucher_fixture"); }
        assertThat(timerWaits.advance(job.getId(), Instant.now())).isTrue();
        assertThat(app(id).status()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(current(id).approval().applicationVersion()).isEqualTo(app(id).version());
        assertThat(current(id).approval().approvedBy()).isEqualTo("system:timer");
        assertThat(voucherPreparations.latest(voucherSources.reference(app(id))).orElseThrow().status()).isEqualTo(VoucherPreparation.Status.QUEUED);
        assertThat(paidAdvances.find("demo", id)).isEmpty();
        assertThat(timerWaits.advance(job.getId(), Instant.now())).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM advance_request_revision WHERE request_id=? AND operation='APPROVE'", Integer.class, id.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM voucher_preparation WHERE business_id=?", Integer.class, id.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='APPROVE'", Integer.class, before.id().toString())).isEqualTo(2);
    }

    private UUID create() throws Exception { return id(ok(send("/api/v1/advance-requests", "alice", createBody(published(false, false), content("100"))), 201)); }
    private Map<String, Object> createBody(DefinitionDraft definition, AdvanceRequestContent content) { return Map.of("businessNo", "PLAN-" + UUID.randomUUID(), "processKey", definition.key(), "definitionVersion", definition.version(), "content", content); }
    private AdvanceRequestContent content(String amount) { return new AdvanceRequestContent(entity, "合成出差借款", "客户现场交流", money(amount, "CNY"), LocalDate.now().plusDays(10)); }
    private Map<String, Object> revision(UUID id, String amount) { return Map.of("applicationVersion", app(id).version(), "requestVersion", current(id).version(), "content", content(amount)); }
    private Map<String, Object> lifecycle(UUID id) { return Map.of("applicationVersion", app(id).version(), "requestVersion", current(id).version(), "comment", "测试生命周期原因"); }
    private Map<String, Object> submission(UUID id, UUID checked) { return Map.of("applicationVersion", app(id).version(), "requestVersion", current(id).version(), "precheckId", checked); }
    private UUID enqueue(UUID id) throws Exception { return id(ok(send(path(id) + "/prechecks", "alice", Map.of("applicationVersion", app(id).version(), "requestVersion", current(id).version(), "initiatorAppointmentId", appointment, "targetDigest", configuration.destination("demo").orElseThrow().digest("demo"))), 202)); }
    private UUID ready(UUID id) throws Exception { UUID checked = enqueue(id); worker.poll(); assertThat(check(checked).status()).as(String.valueOf(check(checked).result())).isEqualTo(Status.READY); return checked; }
    private void submit(UUID id) throws Exception { ok(send(path(id) + "/submit", "alice", submission(id, ready(id))), 200); }
    private AdvanceRequest current(UUID id) { return advances.find("demo", id).orElseThrow(); }
    private Application app(UUID id) { return applications.findById("demo", current(id).applicationId()).orElseThrow(); }
    private AdvanceRequestCheck check(UUID id) { return checks.find("demo", id).orElseThrow(); }
    private String path(UUID id) { return "/api/v1/advance-requests/" + id; }
    private String actionPath(UUID id) { return "/api/v1/tasks/" + tasks.createTaskQuery().processVariableValueEquals("applicationId", app(id).id().toString()).singleResult().getId() + "/actions"; }
    private Map<String, Object> decision(UUID id, String action) { return Map.of("action", action, "expectedVersion", app(id).version(), "comment", "已核对本轮借款"); }
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
        return published(noHuman, masked, false);
    }
    private DefinitionDraft published(boolean noHuman, boolean masked, boolean waitAfterApproval) {
        var graph = noHuman ? new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()), new Node("end", "结束", NodeType.END, Map.of())), List.of(new Edge("a", "start", "end", "", false)))
                : new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()), new Node("review", "主管", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_" + manager)),
                    new Node("finalReview", "复核", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_" + manager)), new Node("end", "结束", NodeType.END, Map.of())),
                    List.of(new Edge("a", "start", "review", "", false), new Edge("b", "review", "finalReview", "", false), new Edge("c", "finalReview", "end", "", false)));
        if (waitAfterApproval) {
            var nodes = new java.util.ArrayList<>(graph.nodes());
            nodes.add(new Node("wait", "批准后等待", NodeType.TIMER_WAIT, Map.of("timerDelaySeconds", "1")));
            var edges = new java.util.ArrayList<>(graph.edges()); edges.removeIf(edge -> edge.target().equals("end"));
            edges.add(new Edge("timerIn", "finalReview", "wait", "", false)); edges.add(new Edge("timerOut", "wait", "end", "", false));
            graph = new Graph(nodes, edges);
        }
        var access = noHuman ? Map.<String, FieldVisibility>of() : Map.of("review", masked ? FieldVisibility.MASKED : FieldVisibility.READ_ONLY, "finalReview", FieldVisibility.READ_ONLY);
        var schema = new FormSchema(2, List.of(new FormSchema.Field(AdvanceRequestFormContract.DETAILS, "借款明细", FormSchema.FieldType.TEXT, true, null,
                null, null, null, null, null, null, true, access), new FormSchema.Field("amount", "借款本币额", FormSchema.FieldType.NUMBER, true, null, null, null, null, null),
                new FormSchema.Field("currency", "本位币", FormSchema.FieldType.TEXT, true, null, null, null, null, null)));
        var draft = definitions.create("demo", "plan-test-" + UUID.randomUUID(), "借款申请合成流程", graph, schema, null);
        return definitions.publish(admin, draft.id(), draft.revision(), "借款申请验收");
    }
    private String normal(String operation, JsonNode request) {
        JsonNode data = request.path("data"); Object result = switch (operation) {
            case "catalog" -> new FinanceCatalog("alice", "synthetic-plan-v1", Instant.now().plusSeconds(600), List.of(new FinanceCatalog.LegalEntity(entity, "合成法人", "CNY", false, "entity-v1", "Pacific/Kiritimati")), List.of(new FinanceCatalog.Category("TRAVEL", "差旅", List.of(ExpenseLine.Unit.ITEM))), List.of(new FinanceCatalog.CostCenter(entity, "IT", "研发")), List.of(), List.of(new FinanceCatalog.City("SH", "上海")));
            case "employee-account" -> new EmployeeAccountPort.Account(new EmployeeAccountSnapshot(entity, "alice", "private-account-reference", "****1234", "a".repeat(64), "account-v1"), Instant.now().plusSeconds(600));
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
