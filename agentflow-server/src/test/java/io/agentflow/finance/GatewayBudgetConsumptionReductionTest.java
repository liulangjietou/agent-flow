package io.agentflow.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.CostAllocation;
import io.agentflow.expense.FinanceJsonConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.*;

/**
 * 真实回环 HTTP 验证逐项预算差额与只读查询的边界，合成服务不代表已接通企业预算系统。
 * @author owlzhangfq@gmail.com
 */
class GatewayBudgetConsumptionReductionTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .registerModule(new FinanceJsonConfiguration().financeMoneyModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final JsonUtil json = new JsonUtil(mapper);
    private final AtomicReference<Function<JsonNode, String>> responder = new AtomicReference<>();
    private final AtomicReference<String> received = new AtomicReference<>();
    private final AtomicReference<String> key = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;
    private ExecutorService executor;
    private FinanceGatewayConfiguration config;
    private FinanceGatewayClient client;
    private GatewayBudgetConsumptionReduction budgets;
    private BudgetConsumptionReductionCommand command;
    private String target;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(); server.setExecutor(executor); server.createContext("/finance/", this::handle); server.start();
        config = FinanceGatewayConfigurationTest.configured("http://127.0.0.1:" + server.getAddress().getPort() + "/finance", "synthetic-token");
        client = new FinanceGatewayClient(config, mapper); budgets = new GatewayBudgetConsumptionReduction(client);
        target = config.destination("tenant-a").orElseThrow().digest("tenant-a");
        var position = new BudgetPrecheckPort.Request(UUID.randomUUID(), 1, 2, "alice", UUID.randomUUID(), "CNY", LocalDate.of(2026, 9, 28),
                List.of(new BudgetPrecheckPort.Allocation(1, 1, "TRAVEL", new CostAllocation("IT", null, new Money(new BigDecimal("60.00"), "CNY"))),
                        new BudgetPrecheckPort.Allocation(1, 2, "TRAVEL", new CostAllocation("SALES", "PROJECT", new Money(new BigDecimal("40.00"), "CNY")))));
        var original = new BudgetCommand(UUID.randomUUID(), "tenant-a", BudgetCommand.Action.CONSUME, position, new BudgetCommand.Expected(1, "frozen"));
        var now = Instant.now();
        var consumed = new BudgetObservation(original.id(), original.digest(), BudgetObservation.Status.APPLIED, 2L, "consumed", now.minusSeconds(10), null);
        var period = new AccountingPeriodPort.OpenPeriod(new AccountingPeriodPort.Request(position.legalEntityId(), "CNY", LocalDate.of(2026, 9, 29)),
                "period", "v1", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), now.minusSeconds(5), now.plusSeconds(180));
        command = new BudgetConsumptionReductionCommand(UUID.randomUUID(), UUID.randomUUID(), original, consumed, null, position.allocations(),
                List.of(new BudgetPrecheckPort.Allocation(1, 1, "TRAVEL", new CostAllocation("IT", null, new Money(new BigDecimal("48.00"), "CNY"))),
                        new BudgetPrecheckPort.Allocation(1, 2, "TRAVEL", new CostAllocation("SALES", "PROJECT", new Money(new BigDecimal("32.00"), "CNY")))),
                period, "finance", "material", "reason", now.minusSeconds(4), now.plusSeconds(120));
        answer(applied(command, 3));
    }

    @AfterEach
    void stop() { TransactionSynchronizationManager.setActualTransactionActive(false); server.stop(0); executor.shutdownNow(); }

    @Test
    void repeatsCarryExactlySameBytesIdempotencyKeyAndAmountSnapshot() {
        assertThat(budgets.execute(target, command).requireValue().status()).isEqualTo(BudgetConsumptionReductionObservation.Status.APPLIED);
        String first = received.get();
        assertThat(path.get()).isEqualTo("/finance/budget-consumption-reduction-command"); assertThat(key.get()).isEqualTo(command.id().toString());
        assertThat(authorization.get()).isEqualTo("Bearer synthetic-token");
        var request = json.read(first, JsonNode.class);
        assertThat(request.path("requestId").asText()).isEqualTo(command.id().toString());
        assertThat(request.at("/data/commandDigest").asText()).isEqualTo(command.digest());
        assertThat(json.read(request.at("/data/command").toString(), BudgetConsumptionReductionCommand.class)).isEqualTo(command);
        budgets.execute(target, command).requireValue();
        assertThat(received.get()).isEqualTo(first); assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    void queriesAreReadOnlyAndUseOriginalIdentityWithFreshCorrelationIds() {
        var missing = new BudgetConsumptionReductionObservation(command.id(), command.adjustmentId(), command.digest(), BudgetConsumptionReductionObservation.Status.NOT_FOUND, Instant.now(), null, null);
        answer(missing); assertThat(budgets.execute(target, command)).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
        assertThat(budgets.query(target, command).requireValue()).isEqualTo(missing);
        assertThat(path.get()).isEqualTo("/finance/budget-consumption-reduction-query"); assertThat(key.get()).isNull();
        var first = json.read(received.get(), JsonNode.class);
        assertThat(first.path("data").size()).isEqualTo(2);
        assertThat(first.at("/data/operationId").asText()).isEqualTo(command.id().toString());
        assertThat(first.at("/data/commandDigest").asText()).isEqualTo(command.digest());
        budgets.query(target, command).requireValue();
        assertThat(json.read(received.get(), JsonNode.class).path("requestId").asText()).isNotEqualTo(first.path("requestId").asText());
    }

    @Test
    void unavailableOrChangedDestinationAndTransactionsNeverSendCommandsOrQueries() {
        config.getTenants().get("tenant-a").setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/other");
        assertThat(budgets.execute(target, command)).isEqualTo(unavailable(FinanceResult.Failure.TARGET_CHANGED));
        assertThat(budgets.query(target, command)).isEqualTo(unavailable(FinanceResult.Failure.TARGET_CHANGED));
        config.setEnabled(false);
        assertThat(budgets.execute(target, command)).isEqualTo(unavailable(FinanceResult.Failure.NOT_CONFIGURED));
        config.setEnabled(true); TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> budgets.execute(target, command)).hasMessage("Finance gateway must run outside a transaction");
        assertThatThrownBy(() -> budgets.query(target, command)).hasMessage("Finance gateway must run outside a transaction");
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThatThrownBy(() -> client.read("tenant-a", FinanceGatewayClient.Operation.BUDGET_REDUCTION_COMMAND, command, BudgetConsumptionReductionObservation.class, value -> true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.read("tenant-a", FinanceGatewayClient.Operation.BUDGET_REDUCTION_QUERY, command, BudgetConsumptionReductionObservation.class, value -> true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(requests.get()).isZero();
    }

    @Test
    void validTransportCannotHideChangedAmountIdentityVersionOrMalformedOutcome() {
        List<Consumer<ObjectNode>> corruptions = List.of(
                body -> body.put("operationId", UUID.randomUUID().toString()), body -> body.put("adjustmentId", UUID.randomUUID().toString()),
                body -> body.put("commandDigest", "b".repeat(64)), body -> body.put("unknown", true), body -> body.put("status", "PENDING"),
                body -> body.put("rejection", "BUDGET_INSUFFICIENT"), body -> body.put("observedAt", command.createdAt().minusSeconds(1).toString()),
                body -> posting(body).put("ledgerRevision", 2), body -> posting(body).put("ledgerRevision", "3"), body -> posting(body).remove("ledgerRevision"),
                body -> posting(body).put("appliedAt", Instant.now().plusSeconds(30).toString()),
                body -> posting(body).put("reference", command.consumed().reference()), body -> posting(body).put("periodReference", "other"),
                body -> posting(body).put("accountingDate", "2026-09-28"), body -> posting(body).put("consumptionId", UUID.randomUUID().toString()),
                body -> posting(body).put("consumptionReference", "foreign-consumption"),
                body -> ((ObjectNode) body.at("/posting/after/0/cost")).put("costCenter", "other"),
                body -> {
                    ((ObjectNode) body.at("/posting/after/0/cost/amount")).put("value", "47.00");
                    ((ObjectNode) body.at("/posting/after/1/cost/amount")).put("value", "33.00");
                },
                body -> ((ObjectNode) body.at("/posting/before/0/cost/amount")).put("value", "61.00"));
        for (var corrupt : corruptions) {
            responder.set(request -> { var body = success(request, applied(command, 3)); corrupt.accept((ObjectNode) body.path("data")); return json.write(body); });
            assertThat(budgets.execute(target, command)).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
        }
        responder.set(request -> json.write(Map.of("contractVersion", 1, "tenantId", "tenant-a", "requestId", request.path("requestId").asText(),
                "outcome", "REJECTED", "reason", "BUDGET_INSUFFICIENT")));
        assertThat(budgets.execute(target, command)).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
    }

    @Test
    void rejectionPendingAndExpiredAuthorizationStayDistinctFromApplied() {
        var rejected = new BudgetConsumptionReductionObservation(command.id(), command.adjustmentId(), command.digest(), BudgetConsumptionReductionObservation.Status.REJECTED, Instant.now(),
                null, BudgetConsumptionReductionObservation.Rejection.LEDGER_VERSION_CONFLICT);
        answer(rejected); assertThat(budgets.execute(target, command).requireValue()).isEqualTo(rejected);
        var pending = new BudgetConsumptionReductionObservation(command.id(), command.adjustmentId(), command.digest(), BudgetConsumptionReductionObservation.Status.PENDING, Instant.now(), null, null);
        answer(pending); assertThat(budgets.execute(target, command).requireValue()).isEqualTo(pending);
        var expired = new BudgetConsumptionReductionCommand(command.id(), command.adjustmentId(), command.source(), command.consumed(), command.previous(), command.before(), command.after(), command.period(),
                command.authorizedBy(), command.evidenceReference(), command.reason(), command.createdAt(), Instant.now().minusSeconds(1));
        int before = requests.get(); assertThatThrownBy(() -> budgets.execute(target, expired)).hasMessageContaining("expired"); assertThat(requests.get()).isEqualTo(before);
        var missing = new BudgetConsumptionReductionObservation(expired.id(), expired.adjustmentId(), expired.digest(), BudgetConsumptionReductionObservation.Status.NOT_FOUND, Instant.now(), null, null);
        answer(missing); assertThat(budgets.query(target, expired).requireValue()).isEqualTo(missing); assertThat(key.get()).isNull();
    }

    @Test
    void timedOutWriteIsSentOnceAndRecoveredThroughOriginalOperationQuery() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        responder.set(request -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return json.write(success(request, applied(command, 3)));
        });
        config.getTenants().get("tenant-a").setTimeoutSeconds(1);
        try {
            assertThat(budgets.execute(target, command)).isEqualTo(unavailable(FinanceResult.Failure.TIMEOUT));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue(); assertThat(requests.get()).isEqualTo(1);
            answer(applied(command, 3));
            assertThat(budgets.query(target, command).requireValue().status()).isEqualTo(BudgetConsumptionReductionObservation.Status.APPLIED);
            assertThat(requests.get()).isEqualTo(2); assertThat(path.get()).isEqualTo("/finance/budget-consumption-reduction-query"); assertThat(key.get()).isNull();
        } finally { release.countDown(); }
    }

    private BudgetConsumptionReductionObservation applied(BudgetConsumptionReductionCommand value, long revision) {
        var now = Instant.now();
        var posting = new BudgetConsumptionReductionObservation.Posting(value.source().id(), value.source().digest(), value.consumed().reference(), revision,
                "synthetic-ledger-" + revision, value.before(), value.after(), value.period().periodReference(), value.period().request().accountingDate(), now.minusSeconds(1));
        return new BudgetConsumptionReductionObservation(value.id(), value.adjustmentId(), value.digest(), BudgetConsumptionReductionObservation.Status.APPLIED, now, posting, null);
    }
    private static ObjectNode posting(ObjectNode body) { return (ObjectNode) body.path("posting"); }
    private void answer(BudgetConsumptionReductionObservation value) { responder.set(request -> json.write(success(request, value))); }
    private ObjectNode success(JsonNode request, BudgetConsumptionReductionObservation value) {
        return json.read(json.write(Map.of("contractVersion", 1, "tenantId", request.path("tenantId").asText(), "requestId", request.path("requestId").asText(),
                "outcome", "SUCCESS", "data", value)), ObjectNode.class);
    }
    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet(); path.set(exchange.getRequestURI().getPath()); key.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8); received.set(request);
        byte[] body = responder.get().apply(json.read(request, JsonNode.class)).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, 0);
        try { exchange.getResponseBody().write(body); } finally { exchange.close(); }
    }
    private static FinanceResult.Unavailable<?> unavailable(FinanceResult.Failure failure) { return new FinanceResult.Unavailable<>(failure); }
}
