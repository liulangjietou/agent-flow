package io.agentflow.expense;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.auth.AuthService;
import io.agentflow.common.JsonUtil;
import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 本人关闭已批准额度的公开事务；领域已有关闭规则，接口、身份与成功回执仍须独立验证。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${AGENTFLOW_REQUEST_CLOSE_TEST_URL:jdbc:h2:mem:expense-request-close;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${AGENTFLOW_REQUEST_CLOSE_TEST_USER:sa}",
        "spring.datasource.password=${AGENTFLOW_REQUEST_CLOSE_TEST_PASSWORD:}",
        "spring.datasource.driver-class-name=${AGENTFLOW_REQUEST_CLOSE_TEST_DRIVER:org.h2.Driver}",
        "agentflow.auth.demo-enabled=true", "agentflow.expense-plans.precheck-worker-enabled=false",
        "agentflow.expenses.precheck-worker-enabled=false", "agentflow.budgets.worker-enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ExpenseRequestClosureIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;
    @Autowired ApplicationRepository applications;
    @Autowired ExpenseRequestRepository requests;
    @Autowired JdbcTemplate jdbc;
    @Autowired ExpenseReportRepository reports;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean ExpenseRequestClosureAudit audit;
    @MockitoSpyBean JdbcExpenseRequestRepository requestStore;

    @Test
    void applicantClosesApprovedCreditAndReplaysOneAuditedReceiptWithoutChangingApproval() throws Exception {
        var application = Application.restore(UUID.randomUUID(), "demo", "CLOSE-" + UUID.randomUUID(),
                "close-fixture", 1, "alice", "已批准的事前费用", Map.of(), ApplicationStatus.APPROVED, 1, 1);
        applications.save(application);
        var request = new ExpenseRequest(UUID.randomUUID(), "demo", application.id(), UUID.randomUUID(), "alice",
                List.of(new ExpenseRequest.ApprovedLine(1, new Money(new BigDecimal("100"), "CNY"), BigDecimal.ZERO, "fixture-policy-v1")));
        requests.create(request, "fixture");
        String path = "/api/v1/expense-requests/" + request.id() + "/close", key = UUID.randomUUID().toString();
        String token = "Bearer " + auth.login("demo", "alice", "demo").token();
        String body = json.write(Map.of("expectedVersion", 1, "comment", "本次出差结束，不再新增报销"));
        var first = mvc.perform(post(path).header("Authorization", token).header("Idempotency-Key", key)
                .contentType("application/json").content(body)).andReturn().getResponse();
        assertThat(first.getStatus()).as(first.getContentAsString()).isEqualTo(200);
        var receipt = json.read(first.getContentAsString(StandardCharsets.UTF_8), JsonNode.class);
        assertThat(receipt.path("requestId").asText()).isEqualTo(request.id().toString());
        assertThat(receipt.path("version").asLong()).isEqualTo(2);
        var closed = requests.find("demo", request.id()).orElseThrow();
        assertThat(closed.closed()).isTrue(); assertThat(closed.balances()).isEqualTo(request.balances());
        assertThat(applications.findById("demo", application.id()).orElseThrow().version()).isEqualTo(1);
        var replay = mvc.perform(post(path).header("Authorization", token).header("Idempotency-Key", key)
                .contentType("application/json").content(body)).andReturn().getResponse();
        assertThat(replay.getStatus()).isEqualTo(200);
        assertThat(replay.getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE tenant_id='demo' AND aggregate_id=? AND action='EXPENSE_REQUEST_CLOSED'",
                Integer.class, request.id().toString())).isEqualTo(1);
        var auditBody = json.map(jdbc.queryForObject("SELECT payload_json FROM audit_event WHERE tenant_id='demo' AND aggregate_id=? AND action='EXPENSE_REQUEST_CLOSED'",
                String.class, request.id().toString()));
        assertThat(auditBody).containsEntry("comment", "本次出差结束，不再新增报销").containsEntry("previousVersion", 1);
        assertThat(receipt.fieldNames()).toIterable().containsExactlyInAnyOrder("requestId", "applicationId", "version", "closed", "eventId");
        assertThat(receipt.path("closed").asBoolean()).isTrue();
    }

    @Test
    void otherEmployeesFinanceAndAdministratorsCannotCloseAnotherApplicantsCredit() throws Exception {
        var request = credit("demo");
        for (String user : List.of("bob", "finance", "admin", "manager")) {
            code(send(request, user, UUID.randomUUID().toString(), input(1)), 404, "NOT_FOUND");
        }
        code(send(credit("other-tenant"), "alice", UUID.randomUUID().toString(), input(1)), 404, "NOT_FOUND");
        assertThat(requests.find("demo", request.id()).orElseThrow().state()).isEqualTo(request.state());
        assertThat(auditCount(request)).isZero();
    }

    @Test
    void anonymousRequestAndUnknownCreditCannotCreateAClosure() throws Exception {
        var request = credit("demo");
        assertThat(mvc.perform(post(path(request)).contentType("application/json").content(input(1))).andReturn().getResponse().getStatus()).isEqualTo(401);
        var missing = new ExpenseRequest(UUID.randomUUID(), "demo", request.applicationId(), request.legalEntityId(), "alice", request.approvedLines());
        code(send(missing, "alice", UUID.randomUUID().toString(), input(1)), 404, "NOT_FOUND");
        assertThat(requests.find("demo", request.id()).orElseThrow().closed()).isFalse();
    }

    @Test
    void invalidNumbersReasonsAndAdditionalPropertiesDoNotCloseTheCredit() throws Exception {
        var request = credit("demo");
        for (String version : List.of("0", "-1", "1.5", "\"1\"", "true", "null", "9223372036854775808")) {
            var response = send(request, "alice", UUID.randomUUID().toString(), "{\"expectedVersion\":" + version + ",\"comment\":\"结束\"}");
            assertThat(response.getStatus()).as(response.getContentAsString()).isIn(400, 422);
        }
        for (String body : List.of("{\"comment\":\"结束\"}", "{\"expectedVersion\":1}",
                "{\"expectedVersion\":1,\"comment\":\"   \"}", "{\"expectedVersion\":1,\"comment\":123}",
                "{\"expectedVersion\":1,\"comment\":\"结束\",\"tenantId\":\"demo\"}",
                json.write(Map.of("expectedVersion", 1, "comment", "x".repeat(2001))))) {
            var response = send(request, "alice", UUID.randomUUID().toString(), body);
            assertThat(response.getStatus()).as(body).isIn(400, 422);
        }
        assertThat(requests.find("demo", request.id()).orElseThrow().state()).isEqualTo(request.state());
        assertThat(auditCount(request)).isZero();
    }

    @Test
    void staleVersionAndChangedOriginalRequestCannotCreateASecondClosure() throws Exception {
        var request = credit("demo"); String key = UUID.randomUUID().toString();
        code(send(request, "alice", UUID.randomUUID().toString(), input(2)), 409, "CONCURRENCY_CONFLICT");
        assertThat(send(request, "alice", key, input(1)).getStatus()).isEqualTo(200);
        code(send(request, "alice", key, input(2)), 409, "IDEMPOTENCY_KEY_REUSED");
        code(send(request, "alice", UUID.randomUUID().toString(), input(2)), 422, "EXPENSE_REQUEST_CLOSED");
        assertThat(auditCount(request)).isEqualTo(1);
        assertThat(requests.find("demo", request.id()).orElseThrow().version()).isEqualTo(2);
    }

    @Test
    void closedCreditPreservesExistingReservationAndAllowsItsReductionAndSettlement() throws Exception {
        var request = credit("demo"); var use = use(request);
        request.reserve(1, 1, use, money("40")); requests.update(request, 1, "fixture", "RESERVE");
        String key = UUID.randomUUID().toString();
        var first = send(request, "alice", key, input(2)); assertThat(first.getStatus()).isEqualTo(200);
        var closed = requests.find("demo", request.id()).orElseThrow();
        assertThat(closed.balances()).isEqualTo(request.balances());
        assertThatThrownBy(() -> closed.reserve(3, 1, new ExpenseUse(use.reportId(), 2, 1), money("1")))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo("EXPENSE_REQUEST_CLOSED"));
        assertThatThrownBy(() -> closed.reserve(3, 1, use, money("41")))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo("EXPENSE_REQUEST_CLOSED"));
        closed.reserve(3, 1, use, money("30")); requests.update(closed, 3, "fixture", "RESERVE");
        closed.consume(4, 1, use); requests.update(closed, 4, "fixture", "CONSUME");
        var latest = requests.find("demo", request.id()).orElseThrow();
        assertThat(latest.closed()).isTrue(); assertThat(latest.balance(1).consumed()).isEqualTo(money("30"));
        assertThat(latest.balance(1).reserved()).isEqualTo(money("0"));
        assertThat(send(request, "alice", key, input(2)).getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(auditCount(request)).isEqualTo(1);
    }

    @Test
    void auditFailureRollsBackTheResourceVersionAndSuccessReceiptBeforeOriginalKeyRetry() throws Exception {
        var request = credit("demo"); String key = UUID.randomUUID().toString();
        doThrow(new DomainException("DEPENDENCY_UNAVAILABLE", "Synthetic audit failure")).doCallRealMethod()
                .when(AopTestUtils.<ExpenseRequestClosureAudit>getUltimateTargetObject(audit)).record(any(), anyString(), anyLong(), anyString(), any());
        code(send(request, "alice", key, input(1)), 503, "DEPENDENCY_UNAVAILABLE");
        assertThat(requests.find("demo", request.id()).orElseThrow().state()).isEqualTo(request.state());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finance_resource_revision WHERE tenant_id='demo' AND resource_id=?", Integer.class, request.id().toString())).isEqualTo(1);
        assertThat(auditCount(request)).isZero();
        assertThat(send(request, "alice", key, input(1)).getStatus()).isEqualTo(200);
        assertThat(auditCount(request)).isEqualTo(1);
    }

    @Test
    void concurrentCloseRequestsProduceOneStateChangeAndOneAudit() throws Exception {
        var request = credit("demo"); var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Integer> close = () -> { start.await(); return send(request, "alice", UUID.randomUUID().toString(), input(1)).getStatus(); };
            var one = pool.submit(close); var two = pool.submit(close); start.countDown();
            assertThat(List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
            assertThat(auditCount(request)).isEqualTo(1);
            assertThat(requests.find("demo", request.id()).orElseThrow().version()).isEqualTo(2);
        } finally { start.countDown(); pool.shutdownNow(); pool.awaitTermination(10, TimeUnit.SECONDS); }
    }

    @Test
    void closeWaitsForReservationAndRejectsTheNowStaleVersionWithoutLosingItsBalance() throws Exception {
        var request = credit("demo"); var use = use(request); var reachedLock = new CountDownLatch(1);
        doAnswer(invocation -> { reachedLock.countDown(); return invocation.callRealMethod(); })
                .when(AopTestUtils.<JdbcExpenseRequestRepository>getUltimateTargetObject(requestStore)).lock("demo", request.id());
        var pool = Executors.newSingleThreadExecutor();
        try {
            var future = new TransactionTemplate(transactions).execute(status -> {
                jdbc.queryForList("SELECT id FROM finance_resource WHERE tenant_id='demo' AND resource_type='PRIOR_REQUEST' AND id=? FOR UPDATE", request.id().toString());
                var pending = pool.submit(() -> send(request, "alice", UUID.randomUUID().toString(), input(1)));
                try { assertThat(reachedLock.await(5, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                request.reserve(1, 1, use, money("40")); requests.update(request, 1, "fixture", "RESERVE");
                return pending;
            });
            code(future.get(10, TimeUnit.SECONDS), 409, "CONCURRENCY_CONFLICT");
            assertThat(requests.find("demo", request.id()).orElseThrow().state()).isEqualTo(request.state());
            assertThat(auditCount(request)).isZero();
            assertThat(send(request, "alice", UUID.randomUUID().toString(), input(2)).getStatus()).isEqualTo(200);
        } finally { pool.shutdownNow(); pool.awaitTermination(10, TimeUnit.SECONDS); }
    }

    @Test
    void administrativeAuditFilterFindsClosureWithoutDisclosingItsReasonOrAmounts() throws Exception {
        var request = credit("demo");
        assertThat(send(request, "alice", UUID.randomUUID().toString(), input(1)).getStatus()).isEqualTo(200);
        var result = mvc.perform(get("/api/v1/operations/audit")
                .header("Authorization", "Bearer " + auth.login("demo", "admin", "demo").token())
                .param("source", "ExpenseRequest").param("action", "EXPENSE_REQUEST_CLOSED")
                .param("applicationId", request.applicationId().toString())).andReturn().getResponse();
        assertThat(result.getStatus()).as(result.getContentAsString()).isEqualTo(200);
        var items = json.read(result.getContentAsString(StandardCharsets.UTF_8), JsonNode.class).path("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).path("aggregateId").asText()).isEqualTo(request.id().toString());
        assertThat(items.get(0).path("aggregateVersion").asLong()).isEqualTo(2);
        assertThat(result.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("已核对剩余额度", "comment", "payload", "balances");
    }

    private ExpenseRequest credit(String tenant) {
        var application = Application.restore(UUID.randomUUID(), tenant, "CLOSE-" + UUID.randomUUID(), "closure-fixture", 1,
                "alice", "事前额度", Map.of(), ApplicationStatus.APPROVED, 1, 1); applications.save(application);
        var credit = new ExpenseRequest(UUID.randomUUID(), tenant, application.id(), UUID.randomUUID(), "alice",
                List.of(new ExpenseRequest.ApprovedLine(1, money("100"), BigDecimal.ZERO, "fixture-policy-v1")));
        requests.create(credit, "fixture"); return credit;
    }
    private ExpenseUse use(ExpenseRequest request) {
        UUID id = UUID.randomUUID();
        var app = Application.draftBusiness(UUID.randomUUID(), request.tenantId(), "CLOSE-EXP-" + UUID.randomUUID(), "closure-fixture", 1,
                "alice", "费用", Map.of(), null, null, null, new BusinessReference(BusinessReference.Type.EXPENSE, id));
        applications.save(app);
        reports.create(ExpenseReport.draft(id, request.tenantId(), app.id(), "alice", new ExpenseContent(request.legalEntityId(), ExpenseContent.Type.DAILY, "费用", List.of(), List.of())), "fixture");
        return new ExpenseUse(id, 1, 1);
    }
    private MockHttpServletResponse send(ExpenseRequest request, String user, String key, String body) throws Exception {
        return mvc.perform(post(path(request)).header("Authorization", "Bearer " + auth.login("demo", user, "demo").token())
                .header("Idempotency-Key", key).contentType("application/json").content(body)).andReturn().getResponse();
    }
    private void code(MockHttpServletResponse response, int status, String code) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        assertThat(json.read(response.getContentAsString(StandardCharsets.UTF_8), JsonNode.class).path("code").asText()).isEqualTo(code);
    }
    private int auditCount(ExpenseRequest request) { return jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE tenant_id=? AND aggregate_id=? AND action='EXPENSE_REQUEST_CLOSED'", Integer.class, request.tenantId(), request.id().toString()); }
    private String input(long version) { return json.write(Map.of("expectedVersion", version, "comment", "已核对剩余额度")); }
    private static String path(ExpenseRequest request) { return "/api/v1/expense-requests/" + request.id() + "/close"; }
    private static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
}
