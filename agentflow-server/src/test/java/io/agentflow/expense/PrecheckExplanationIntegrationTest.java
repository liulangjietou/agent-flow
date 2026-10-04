package io.agentflow.expense;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.agentflow.agent.*;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.JsonUtil;
import io.agentflow.finance.FinanceGatewayConfiguration;
import io.agentflow.finance.Money;
import io.agentflow.organization.OrganizationAppointment;
import io.agentflow.organization.OrganizationService;
import io.agentflow.organization.OrganizationUnit;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import static io.agentflow.expense.ExpensePrecheckJob.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * 合成已持久预检到真实回环模型 HTTP 的消费者测试，不代表企业模型质量或财务联调。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${AGENTFLOW_EXPLANATION_TEST_URL:jdbc:h2:mem:precheck-explanation;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${AGENTFLOW_EXPLANATION_TEST_USER:sa}", "spring.datasource.password=${AGENTFLOW_EXPLANATION_TEST_PASSWORD:}",
        "spring.datasource.driver-class-name=${AGENTFLOW_EXPLANATION_TEST_DRIVER:org.h2.Driver}",
        "agentflow.auth.demo-enabled=true", "agentflow.assist.enabled=true", "agentflow.assist.worker-enabled=false",
        "agentflow.assist.model=synthetic-explanation", "agentflow.assist.timeout-seconds=2",
        "agentflow.finance-gateway.enabled=true", "agentflow.expenses.precheck-worker-enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class PrecheckExplanationIntegrationTest {
    private static final String ISSUE = "precheck:finding[0]";
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final AtomicReference<String> MODE = new AtomicReference<>("success");
    private static final AtomicReference<String> LAST_BODY = new AtomicReference<>();
    private static final java.util.concurrent.ExecutorService HTTP_THREADS = Executors.newFixedThreadPool(2);
    private static final HttpServer MODEL = server();
    private static final String ENDPOINT = "http://127.0.0.1:" + MODEL.getAddress().getPort() + "/v1/chat/completions";
    private static JsonUtil wire;
    private final List<UUID> queued = new ArrayList<>();
    private final Actor alice = new Actor("demo", "alice", Set.of("EMPLOYEE"));
    private UUID entity;
    private OrganizationAppointment appointment;
    @Autowired MockMvc mvc;
    @MockitoSpyBean AuthService auth;
    @Autowired JsonUtil json;
    @Autowired CurrentActor actors;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationRepository applications;
    @Autowired ExpenseReportRepository reports;
    @Autowired OrganizationService organization;
    @Autowired ExpensePrecheckService prechecks;
    @Autowired JdbcExpensePrecheckRepository jobs;
    @Autowired ExpensePrecheckObservations observations;
    @Autowired FinanceGatewayConfiguration finance;
    @Autowired PrecheckExplanationService service;
    @Autowired PrecheckExplanationWorker worker;
    @Autowired JdbcPrecheckExplanationRepository runs;
    @Autowired AssistConfiguration configuration;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("agentflow.assist.endpoint", () -> ENDPOINT);
        properties.add("agentflow.finance-gateway.tenants.demo.endpoint", () -> "http://127.0.0.1:" + MODEL.getAddress().getPort() + "/finance");
        properties.add("agentflow.finance-gateway.tenants.demo.allow-unauthenticated-loopback", () -> true);
        properties.add("agentflow.attachments.directory", () -> "/fyoung/tmp/agentflow-precheck-explanation-fixtures");
    }
    @BeforeEach void setup() {
        wire = json; MODE.set("success"); configuration.setEnabled(true); configuration.setEndpoint(ENDPOINT);
        configuration.setModel("synthetic-explanation"); configuration.setTimeoutSeconds(2);
        var admin = new Actor("demo", "admin", Set.of("ADMIN"));
        if (jdbc.queryForObject("SELECT COUNT(*) FROM organization_directory WHERE tenant_id='demo'", Integer.class) == 0) organization.initialize(admin);
        entity = organization.createUnit(admin, OrganizationUnit.Kind.LEGAL_ENTITY, "解释合成法人", null, null, true).id();
        var department = organization.createUnit(admin, OrganizationUnit.Kind.DEPARTMENT, "解释合成部门", entity, null, true);
        var position = organization.createUnit(admin, OrganizationUnit.Kind.POSITION, "解释合成岗位", entity, null, true);
        var found = jdbc.queryForList("SELECT id FROM organization_person WHERE tenant_id='demo' AND subject='alice'", String.class);
        UUID person = found.isEmpty() ? organization.createPerson(admin, "alice", "合成员工", true, false).id() : UUID.fromString(found.get(0));
        appointment = organization.createAppointment(admin, person, department.id(), position.id(), true);
    }
    @AfterEach void settle() {
        actors.clear(); configuration.setEnabled(true); configuration.setEndpoint(ENDPOINT); configuration.setModel("synthetic-explanation"); configuration.setTimeoutSeconds(2);
        for (UUID id : queued) {
            service.claim("demo", id, Instant.now()); service.finish("demo", id, null, AssistRun.Failure.MODEL_UNAVAILABLE, Instant.now());
        }
    }
    @AfterAll static void stop() { MODEL.stop(0); HTTP_THREADS.shutdownNow(); }

    @Test void explicitInputAndIdempotentReviewNeverChangeFinancialFactsOrRuleResult() throws Exception {
        var report = report(); var job = checked(report); var original = reports.find("demo", report.id()).orElseThrow().state();
        var application = applications.findById("demo", report.applicationId()).orElseThrow(); int before = REQUESTS.get();
        var options = read(report, "/input?precheckId=" + job.input().id(), "alice", 200);
        assertThat(options.path("enabled").asBoolean()).isTrue();
        assertThat(options.path("sources").toString()).contains(ISSUE, "expense:line[1]", "BUDGET_INSUFFICIENT")
                .doesNotContain("不可发送说明", "不可发送例外", "不可发送归属", "不可发送标题", "employeeId", "dependencyDigest", "invoiceIds");
        var body = generation(job, List.of("precheck:result", ISSUE)); String key = UUID.randomUUID().toString();
        var receipt = send(report, "", "alice", body, key, 202); UUID id = remember(receipt);
        assertThat(send(report, "", "alice", body, key, 202)).isEqualTo(receipt);
        send(report, "", "alice", body, UUID.randomUUID().toString(), 409);
        assertThat(receipt.toString()).doesNotContain("sources", "suggestion", "content");
        assertThat(REQUESTS.get()).isEqualTo(before); worker.poll();
        var detail = read(report, "/" + id, "alice", 200);
        assertThat(detail.path("status").asText()).isEqualTo("COMPLETED"); assertThat(detail.path("canAdopt").asBoolean()).isTrue();
        assertThat(detail.path("result").asText()).isEqualTo("BLOCKED");
        assertThat(LAST_BODY.get()).contains(ISSUE, "BUDGET_INSUFFICIENT").doesNotContain("expense:line[1]", report.id().toString(), report.applicationId().toString(),
                job.input().id().toString(), "alice", "tenantId", "不可发送", "dependencyDigest", "targetDigest");
        var review = Map.of("expectedRunVersion", 3, "action", "ADOPT", "selectedIssueIds", List.of(ISSUE), "comment", "已核对原检查");
        String reviewKey = UUID.randomUUID().toString(); var adopted = send(report, "/" + id + "/review", "alice", review, reviewKey, 200);
        assertThat(send(report, "/" + id + "/review", "alice", review, reviewKey, 200)).isEqualTo(adopted);
        assertThat(adopted.path("status").asText()).isEqualTo("ADOPTED");
        assertThat(reports.find("demo", report.id()).orElseThrow().state()).isEqualTo(original);
        assertThat(applications.findById("demo", report.applicationId()).orElseThrow().version()).isEqualTo(application.version());
        assertThat(jobs.find("demo", job.input().id())).contains(job);
        assertThat(transitions(id)).isEqualTo(4);
        assertThat(read(report, "/" + id, "alice", 200).path("suggestion")).isEqualTo(detail.path("suggestion"));
    }

    @Test void readsWritesAndReplaysRemainOwnerOnlyAndQueriesAreStrict() throws Exception {
        var report = report(); var job = checked(report); var body = generation(job, List.of("precheck:result", ISSUE));
        String key = UUID.randomUUID().toString(); UUID id = remember(send(report, "", "alice", body, key, 202));
        for (String user : List.of("admin", "bob", "manager")) {
            read(report, "", user, 404); read(report, "/input?precheckId=" + job.input().id(), user, 404); read(report, "/" + id, user, 404);
            send(report, "", user, body, key, 404); send(report, "/" + id + "/review", user, Map.of("expectedRunVersion", 3, "action", "DISMISS"), key, 404);
        }
        read(report(), "/" + id, "alice", 404);
        String foreign = UUID.randomUUID().toString();
        org.mockito.Mockito.doReturn(new Actor("foreign", "alice", Set.of("EMPLOYEE"))).when(auth).authenticate(foreign);
        assertThat(mvc.perform(get(path(report)).header("Authorization", "Bearer " + foreign)).andReturn().getResponse().getStatus()).isEqualTo(404);
        for (String query : List.of("?page=-1", "?page=01", "?page=0&page=1", "?pageSize=51", "?tenantId=demo")) read(report, query, "alice", 400);
        for (String query : List.of("", "?precheckId=1-1-1-1-1", "?precheckId=" + job.input().id() + "&precheckId=" + job.input().id(), "?precheckId=" + job.input().id() + "&userId=alice")) {
            read(report, "/input" + query, "alice", 400);
        }
        read(report, "/" + id + "?expand=true", "alice", 400);
        var page = read(report, "?page=0&pageSize=1", "alice", 200);
        assertThat(page.path("total").asLong()).isEqualTo(1); assertThat(page.path("items").toString()).doesNotContain("sources", "suggestion", "content");
    }

    @Test void queueRejectsUnselectedPrivateSourcesUnknownFieldsAndWrongPrecheckVersion() throws Exception {
        var report = report(); var job = checked(report);
        for (var selection : List.of(List.of("precheck:result"), List.of(ISSUE), List.of("precheck:result", ISSUE, ISSUE))) {
            send(report, "", "alice", generation(job, selection), UUID.randomUUID().toString(), 422);
        }
        send(report, "", "alice", generation(job, List.of("precheck:result", ISSUE, "application:title")), UUID.randomUUID().toString(), 403);
        var body = (ObjectNode) json.read(json.write(generation(job, List.of("precheck:result", ISSUE))), JsonNode.class);
        body.put("applicationVersion", 2); send(report, "", "alice", body, UUID.randomUUID().toString(), 409);
        body.put("applicationVersion", 1).put("targetDigest", "0".repeat(64)); send(report, "", "alice", body, UUID.randomUUID().toString(), 409);
        body.put("targetDigest", configuration.targetDigest(PrecheckExplanationRun.PROMPT_VERSION)).put("approved", true);
        send(report, "", "alice", body, UUID.randomUUID().toString(), 400);
        var other = checked(report()); body.remove("approved"); body.put("precheckId", other.input().id().toString());
        send(report, "", "alice", body, UUID.randomUUID().toString(), 404);
    }

    @Test void supersededCheckPreventsAdoptionButAllowsDismissalAndNewGeneration() throws Exception {
        var report = report(); var job = checked(report); UUID id = queue(report, job); worker.poll(); var old = read(report, "/" + id, "alice", 200);
        var newer = checked(report);
        assertThat(read(report, "/" + id, "alice", 200).path("unavailableCode").asText()).isEqualTo("PRECHECK_SUPERSEDED");
        send(report, "/" + id + "/review", "alice", Map.of("expectedRunVersion", 3, "action", "ADOPT", "selectedIssueIds", List.of(ISSUE)), UUID.randomUUID().toString(), 409);
        assertThat(transitions(id)).isEqualTo(3);
        send(report, "/" + id + "/review", "alice", Map.of("expectedRunVersion", 3, "action", "DISMISS"), UUID.randomUUID().toString(), 200);
        assertThat(read(report, "/" + id, "alice", 200).path("suggestion")).isEqualTo(old.path("suggestion"));
        UUID next = queue(report, newer); worker.poll(); assertThat(runs.find("demo", next).orElseThrow().state().status()).isEqualTo(PrecheckExplanationRun.Status.COMPLETED);
    }

    @Test void queueAndPresendRecheckFreshnessWithoutIssuingModelHttp() throws Exception {
        var report = report(); UUID id = queue(report, checked(report)); int before = REQUESTS.get(); checked(report); worker.poll();
        assertThat(runs.find("demo", id).orElseThrow().state().failure()).isEqualTo(AssistRun.Failure.INPUT_UNAVAILABLE);
        var current = checked(report); UUID next = queue(report, current); var context = service.claim("demo", next, Instant.now());
        assertThat(context).isNotNull(); checked(report);
        assertThat(service.sendable(context, Instant.now())).isFalse();
        assertThat(runs.find("demo", next).orElseThrow().state().failure()).isEqualTo(AssistRun.Failure.INPUT_UNAVAILABLE);
        assertThat(REQUESTS.get()).isEqualTo(before);
    }

    @Test void changedTargetDisabledConfigurationAndExpiredFactsFailWithoutSending() throws Exception {
        var report = report(); var job = checked(report); UUID id = queue(report, job); int before = REQUESTS.get();
        configuration.setModel("changed-target"); worker.poll(); assertThat(runs.find("demo", id).orElseThrow().state().failure()).isEqualTo(AssistRun.Failure.MODEL_UNAVAILABLE);
        configuration.setModel("synthetic-explanation"); UUID next = queue(report, job); var context = service.claim("demo", next, Instant.now());
        configuration.setEnabled(false); assertThat(service.sendable(context, Instant.now())).isFalse();
        assertThat(read(report, "/input?precheckId=" + job.input().id(), "alice", 200).path("enabled").asBoolean()).isFalse();
        configuration.setEnabled(true); UUID expired = queue(report, job);
        assertThat(service.claim("demo", expired, job.result().observation().validUntil())).isNull();
        assertThat(runs.find("demo", expired).orElseThrow().state().failure()).isEqualTo(AssistRun.Failure.INPUT_UNAVAILABLE);
        assertThat(REQUESTS.get()).isEqualTo(before);
    }

    @Test void concurrentClaimHasOneWinnerAndExpiredLeaseNeverReissuesOrAcceptsLateResult() throws Exception {
        var report = report(); UUID id = queue(report, checked(report)); var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> { start.await(); return service.claim("demo", id, Instant.now()); });
            var two = pool.submit(() -> { start.await(); return service.claim("demo", id, Instant.now()); }); start.countDown();
            assertThat(java.util.stream.Stream.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS)).filter(java.util.Objects::nonNull).count()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
        var running = runs.find("demo", id).orElseThrow(); int before = REQUESTS.get();
        assertThat(service.claim("demo", id, running.state().leaseUntil())).isNull();
        service.finish("demo", id, null, AssistRun.Failure.MODEL_UNAVAILABLE, running.state().leaseUntil().plusSeconds(1)); worker.poll();
        assertThat(runs.find("demo", id).orElseThrow().state().failure()).isEqualTo(AssistRun.Failure.MODEL_TIMEOUT);
        assertThat(transitions(id)).isEqualTo(3); assertThat(REQUESTS.get()).isEqualTo(before);
    }

    @Test void reviewAndTransitionAppendRollBackTogether() throws Exception {
        var report = report(); UUID id = queue(report, checked(report)); worker.poll();
        jdbc.execute("ALTER TABLE agent_precheck_explanation_transition ADD CONSTRAINT fixture_no_adoption CHECK(status<>'ADOPTED')");
        actors.set(alice);
        try {
            assertThatThrownBy(() -> service.review(report.id(), id, 3, AssistExecutionService.ReviewAction.ADOPT, List.of(ISSUE), "失败后一起回滚"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(runs.find("demo", id).orElseThrow().state().status()).isEqualTo(PrecheckExplanationRun.Status.COMPLETED);
            assertThat(transitions(id)).isEqualTo(3);
        } finally { actors.clear(); jdbc.execute("ALTER TABLE agent_precheck_explanation_transition DROP CONSTRAINT fixture_no_adoption"); }
    }

    @ParameterizedTest @ValueSource(strings = {"extra", "number", "forged", "omitted", "duplicate", "no-correction", "extra-reference", "tool", "truncated"})
    void malformedModelOutputCannotBecomeReviewable(String mode) throws Exception {
        var report = report(); UUID id = queue(report, checked(report)); MODE.set(mode); worker.poll();
        var value = runs.find("demo", id).orElseThrow(); assertThat(value.state().status()).isEqualTo(PrecheckExplanationRun.Status.FAILED);
        assertThat(value.state().failure()).isEqualTo(AssistRun.Failure.INVALID_MODEL_OUTPUT); assertThat(value.state().suggestion()).isNull();
        assertThat(reports.find("demo", report.id()).orElseThrow().version()).isEqualTo(1);
    }

    @Test void transportErrorsBecomeStableFailuresAndDoNotAutomaticallyRetry() throws Exception {
        var report = report(); UUID id = queue(report, checked(report)); MODE.set("http-error"); int before = REQUESTS.get(); worker.poll(); worker.poll();
        assertThat(runs.find("demo", id).orElseThrow().state().failure()).isEqualTo(AssistRun.Failure.MODEL_UNAVAILABLE);
        assertThat(REQUESTS.get()).isEqualTo(before + 1);
    }

    @Test void optedInExpenseLineContainsOnlyItsWhitelistedClaimedFacts() throws Exception {
        var report = report(); var job = checked(report);
        UUID id = remember(send(report, "", "alice", generation(job, List.of("precheck:result", ISSUE, "expense:line[1]")), UUID.randomUUID().toString(), 202));
        worker.poll(); var sent = json.read(LAST_BODY.get(), JsonNode.class);
        var input = json.read(sent.at("/messages/1/content").asText(), JsonNode.class);
        var source = java.util.stream.StreamSupport.stream(input.path("sources").spliterator(), false)
                .filter(value -> value.at("/reference/sourceId").asText().equals("expense:line[1]")).findFirst().orElseThrow();
        var line = json.read(source.path("content").asText(), JsonNode.class);
        assertThat(line.path("categoryCode").asText()).isEqualTo("OFFICE");
        assertThat(new BigDecimal(line.at("/claimedGross/value").asText())).isEqualByComparingTo("100.00");
        assertThat(line.path("invoiceCount").asInt()).isZero();
        assertThat(source.toString()).doesNotContain("不可发送", "allocations", "invoiceIds", "legalEntityId", "employeeId");
        assertThat(runs.find("demo", id).orElseThrow().state().status()).isEqualTo(PrecheckExplanationRun.Status.COMPLETED);
    }

    @Test void financialRevisionInvalidatesReviewAndReviewCannotAcceptExtraActions() throws Exception {
        var report = report(); var job = checked(report); UUID id = queue(report, job); worker.poll();
        send(report, "/" + id + "/review", "alice", Map.of("expectedRunVersion", 3, "action", "ADOPT", "selectedIssueIds", List.of("precheck:finding[1]")), UUID.randomUUID().toString(), 422);
        send(report, "/" + id + "/review", "alice", Map.of("expectedRunVersion", 3, "action", "ADOPT", "selectedIssueIds", List.of(ISSUE), "amount", "0"), UUID.randomUUID().toString(), 400);
        report.revise(1, report.content()); reports.update(report, 1, "alice", "FIXTURE_REVISE");
        assertThat(read(report, "/" + id, "alice", 200).path("canAdopt").asBoolean()).isFalse();
        send(report, "/" + id + "/review", "alice", Map.of("expectedRunVersion", 3, "action", "ADOPT", "selectedIssueIds", List.of(ISSUE)), UUID.randomUUID().toString(), 409);
        assertThat(transitions(id)).isEqualTo(3); assertThat(reports.find("demo", report.id()).orElseThrow().version()).isEqualTo(2);
    }

    @Test void legacyCheckWithoutObservationIsReadableButCannotBeAuthorizedForExplanation() throws Exception {
        var report = report(); var job = checked(report); var state = (ObjectNode) json.read(json.write(job), JsonNode.class);
        ((ObjectNode) state.path("result")).remove("observation"); String legacy = json.write(state);
        jdbc.update("UPDATE expense_precheck_job SET state_json=? WHERE tenant_id='demo' AND id=?", legacy, job.input().id().toString());
        var input = read(report, "/input?precheckId=" + job.input().id(), "alice", 200);
        assertThat(input.path("unavailableCode").asText()).isEqualTo("PRECHECK_EXPLANATION_REFRESH_REQUIRED");
        assertThat(input.path("sources")).isEmpty();
        send(report, "", "alice", generation(job, List.of("precheck:result", ISSUE)), UUID.randomUUID().toString(), 409);
        assertThat(jdbc.queryForObject("SELECT state_json FROM expense_precheck_job WHERE tenant_id='demo' AND id=?", String.class, job.input().id().toString())).isEqualTo(legacy);
    }

    @Test void disabledOriginalAppointmentStopsSendingAfterClaim() throws Exception {
        var report = report(); UUID id = queue(report, checked(report)); var context = service.claim("demo", id, Instant.now()); int before = REQUESTS.get();
        jdbc.update("UPDATE organization_appointment SET active=FALSE WHERE tenant_id='demo' AND id=?", appointment.id().toString());
        assertThat(service.sendable(context, Instant.now())).isFalse(); worker.poll();
        assertThat(runs.find("demo", id).orElseThrow().state().failure()).isEqualTo(AssistRun.Failure.INPUT_UNAVAILABLE);
        assertThat(REQUESTS.get()).isEqualTo(before);
    }

    private ExpenseReport report() {
        var amount = new Money(new BigDecimal("100"), "CNY");
        var line = new ExpenseLine(1, "OFFICE", LocalDate.now(), null, "SH", BigDecimal.ONE, ExpenseLine.Unit.ITEM, amount, Money.zero("CNY"), List.of(), null,
                List.of(new CostAllocation("不可发送归属", null, amount)), "不可发送说明", "不可发送例外");
        var content = new ExpenseContent(entity, ExpenseContent.Type.DAILY, "不可发送标题", List.of(line), List.of()); UUID id = UUID.randomUUID();
        var app = Application.draftBusiness(UUID.randomUUID(), "demo", "SYNTHETIC-" + UUID.randomUUID(), "fixture", 1, "alice", content.title(), Map.of(), null, null, null,
                new BusinessReference(BusinessReference.Type.EXPENSE, id));
        applications.save(app); var report = ExpenseReport.draft(id, "demo", app.id(), "alice", content); reports.create(report, "alice"); return report;
    }
    private ExpensePrecheckJob checked(ExpenseReport report) {
        actors.set(alice); UUID id;
        try { id = prechecks.queue(report.id(), new ExpensePrecheckService.QueueInput(1L, 1L, appointment.id(), LocalDate.now(), finance.destination("demo").orElseThrow().digest("demo"))).id(); }
        finally { actors.clear(); }
        var running = prechecks.claim("demo", id, Instant.now());
        var observation = observations.capture(report, Instant.now().truncatedTo(ChronoUnit.MILLIS)).observation();
        prechecks.finish(running, new Result(null, List.of(new Finding(Stage.BUDGET, 1, Nature.REJECTED, "BUDGET_INSUFFICIENT")), observation), Instant.now());
        var job = jobs.find("demo", id).orElseThrow(); assertThat(job.status()).isEqualTo(Status.BLOCKED); return job;
    }
    private Map<String, Object> generation(ExpensePrecheckJob job, List<String> sources) {
        return Map.of("precheckId", job.input().id(), "applicationVersion", 1, "financialVersion", 1,
                "targetDigest", configuration.targetDigest(PrecheckExplanationRun.PROMPT_VERSION), "sourceIds", sources);
    }
    private UUID queue(ExpenseReport report, ExpensePrecheckJob job) throws Exception {
        return remember(send(report, "", "alice", generation(job, List.of("precheck:result", ISSUE)), UUID.randomUUID().toString(), 202));
    }
    private UUID remember(JsonNode receipt) { UUID id = UUID.fromString(receipt.path("id").asText()); queued.add(id); return id; }
    private JsonNode read(ExpenseReport report, String suffix, String user, int expected) throws Exception {
        var response = mvc.perform(get(path(report) + suffix).header("Authorization", token(user))).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected);
        if (expected == 200) assertThat(response.getHeader("Cache-Control")).contains("no-store");
        return json.read(response.getContentAsString(StandardCharsets.UTF_8), JsonNode.class);
    }
    private JsonNode send(ExpenseReport report, String suffix, String user, Object body, String key, int expected) throws Exception {
        var response = mvc.perform(post(path(report) + suffix).header("Authorization", token(user)).header("Idempotency-Key", key)
                .contentType("application/json").content(json.write(body))).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected);
        return json.read(response.getContentAsString(StandardCharsets.UTF_8), JsonNode.class);
    }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private static String path(ExpenseReport report) { return "/api/v1/expense-reports/" + report.id() + "/precheck-explanations"; }
    private long transitions(UUID id) { return jdbc.queryForObject("SELECT COUNT(*) FROM agent_precheck_explanation_transition WHERE tenant_id='demo' AND run_id=?", Long.class, id.toString()); }
    private static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(HTTP_THREADS);
            server.createContext("/v1/chat/completions", exchange -> {
                REQUESTS.incrementAndGet(); String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8); LAST_BODY.set(body);
                var request = wire.read(body, JsonNode.class); var input = wire.read(request.at("/messages/1/content").asText(), JsonNode.class);
                var source = java.util.stream.StreamSupport.stream(input.path("sources").spliterator(), false)
                        .filter(value -> value.at("/reference/sourceId").asText().equals(ISSUE)).findFirst().orElseThrow();
                var item = wire.read(wire.write(Map.of("issueSourceId", ISSUE, "explanation", "原检查显示预算不足，请核对费用归属后重新预检。",
                        "corrections", List.of("核对预算口径与可用额度"), "evidence", List.of(source.path("reference")))), ObjectNode.class);
                var output = wire.read(wire.write(Map.of("items", List.of(item))), ObjectNode.class); String mode = MODE.get();
                switch (mode) {
                    case "extra" -> item.put("approved", true);
                    case "number" -> item.put("explanation", 1);
                    case "forged" -> ((ObjectNode) item.at("/evidence/0")).put("contentDigest", "0".repeat(64));
                    case "omitted" -> output.putArray("items");
                    case "duplicate" -> output.putArray("items").add(item).add(item);
                    case "no-correction" -> item.putArray("corrections");
                    case "extra-reference" -> ((ObjectNode) item.at("/evidence/0")).put("trusted", true);
                    default -> { }
                }
                if (!Set.of("omitted", "duplicate").contains(mode)) output.putArray("items").add(item);
                var message = wire.read(wire.write(Map.of("role", "assistant", "content", wire.write(output))), ObjectNode.class);
                if (mode.equals("tool")) message.putArray("tool_calls").addObject().put("id", "forbidden");
                byte[] response = wire.write(Map.of("model", "synthetic-explanation-v1", "choices", List.of(Map.of("finish_reason", mode.equals("truncated") ? "length" : "stop", "message", message)))).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(mode.equals("http-error") ? 503 : 200, response.length);
                try { exchange.getResponseBody().write(response); } finally { exchange.close(); }
            }); server.start(); return server;
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
}
