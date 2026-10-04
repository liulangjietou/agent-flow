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
 * 真实回环 HTTP 验证预算变更与只读查询的边界，合成服务不代表已接通企业预算系统。
 * @author owlzhangfq@gmail.com
 */
class GatewayBudgetSystemTest {
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
    private GatewayBudgetSystem budgets;
    private BudgetCommand command;
    private String target;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(); server.setExecutor(executor); server.createContext("/finance/", this::handle); server.start();
        config = FinanceGatewayConfigurationTest.configured("http://127.0.0.1:" + server.getAddress().getPort() + "/finance", "synthetic-token");
        client = new FinanceGatewayClient(config, mapper); budgets = new GatewayBudgetSystem(client);
        target = config.destination("tenant-a").orElseThrow().digest("tenant-a");
        var position = new BudgetPrecheckPort.Request(UUID.randomUUID(), 1, 2, "alice", UUID.randomUUID(), "CNY", LocalDate.of(2026, 9, 28),
                List.of(new BudgetPrecheckPort.Allocation(1, 1, "TRAVEL", new CostAllocation("IT", null, new Money(new BigDecimal("100.00"), "CNY")))));
        command = new BudgetCommand(UUID.randomUUID(), "tenant-a", BudgetCommand.Action.FREEZE, position, null);
        answer(applied(command, 1));
    }

    @AfterEach
    void stop() { TransactionSynchronizationManager.setActualTransactionActive(false); server.stop(0); executor.shutdownNow(); }

    @Test
    void repeatsCarryExactlySameBytesIdempotencyKeyAndAmountSnapshot() {
        assertThat(budgets.execute(target, command).requireValue().status()).isEqualTo(BudgetObservation.Status.APPLIED);
        String first = received.get();
        assertThat(path.get()).isEqualTo("/finance/budget-command"); assertThat(key.get()).isEqualTo(command.id().toString());
        assertThat(authorization.get()).isEqualTo("Bearer synthetic-token");
        var request = json.read(first, JsonNode.class);
        assertThat(request.path("requestId").asText()).isEqualTo(command.id().toString());
        assertThat(request.at("/data/commandDigest").asText()).isEqualTo(command.digest());
        assertThat(json.read(request.at("/data/command").toString(), BudgetCommand.class)).isEqualTo(command);
        budgets.execute(target, command).requireValue();
        assertThat(received.get()).isEqualTo(first); assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    void queriesAreReadOnlyAndUseOriginalIdentityWithFreshCorrelationIds() {
        var missing = new BudgetObservation(command.id(), command.digest(), BudgetObservation.Status.NOT_FOUND, null, null, null, null);
        answer(missing); assertThat(budgets.execute(target, command)).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
        assertThat(budgets.query(target, command).requireValue()).isEqualTo(missing);
        assertThat(path.get()).isEqualTo("/finance/budget-query"); assertThat(key.get()).isNull();
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
        assertThatThrownBy(() -> client.read("tenant-a", FinanceGatewayClient.Operation.BUDGET_COMMAND, command, BudgetObservation.class, value -> true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.read("tenant-a", FinanceGatewayClient.Operation.BUDGET_QUERY, command, BudgetObservation.class, value -> true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(requests.get()).isZero();
    }

    @Test
    void validTransportCannotHideChangedAmountIdentityVersionOrMalformedOutcome() {
        List<Consumer<ObjectNode>> corruptions = List.of(
                body -> body.put("operationId", UUID.randomUUID().toString()), body -> body.put("commandDigest", "b".repeat(64)),
                body -> body.put("ledgerRevision", 2), body -> body.put("ledgerRevision", "1"), body -> body.remove("ledgerRevision"),
                body -> body.put("appliedAt", Instant.now().plusSeconds(30).toString()), body -> body.put("status", "PENDING"),
                body -> body.put("rejection", "BUDGET_INSUFFICIENT"), body -> body.put("unknown", true));
        for (var corrupt : corruptions) {
            responder.set(request -> { var body = success(request, applied(command, 1)); corrupt.accept((ObjectNode) body.path("data")); return json.write(body); });
            assertThat(budgets.execute(target, command)).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
        }
        responder.set(request -> json.write(Map.of("contractVersion", 1, "tenantId", "tenant-a", "requestId", request.path("requestId").asText(),
                "outcome", "REJECTED", "reason", "BUDGET_INSUFFICIENT")));
        assertThat(budgets.execute(target, command)).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
    }

    @Test
    void rejectionAndPendingRemainDistinctFromAppliedForEveryBudgetAction() {
        var rejected = new BudgetObservation(command.id(), command.digest(), BudgetObservation.Status.REJECTED, null, null, null, BudgetObservation.Rejection.BUDGET_INSUFFICIENT);
        answer(rejected); assertThat(budgets.execute(target, command).requireValue()).isEqualTo(rejected);
        var pending = new BudgetObservation(command.id(), command.digest(), BudgetObservation.Status.PENDING, null, null, null, null);
        answer(pending); assertThat(budgets.execute(target, command).requireValue()).isEqualTo(pending);
        for (var action : List.of(BudgetCommand.Action.FREEZE, BudgetCommand.Action.ADJUST, BudgetCommand.Action.RELEASE, BudgetCommand.Action.CONSUME)) {
            var next = new BudgetCommand(UUID.randomUUID(), "tenant-a", action, command.position(), new BudgetCommand.Expected(4, "ledger-v4"));
            answer(applied(next, 5)); assertThat(budgets.execute(target, next).requireValue().ledgerRevision()).isEqualTo(5);
            answer(applied(next, 4)); assertThat(budgets.execute(target, next)).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
        }
    }

    @Test
    void explicitFlexibleRejectionIsAcceptedOnlyWithOriginalCommandAndCompleteOffer() {
        responder.set(request -> {
            var body = success(request, new BudgetObservation(command.id(), command.digest(), BudgetObservation.Status.REJECTED,
                    null, null, null, BudgetObservation.Rejection.BUDGET_INSUFFICIENT));
            var value = (ObjectNode) body.path("data"); value.put("rejection", "BUDGET_EXCEPTION_REQUIRED");
            value.putObject("exceptionOffer").put("policyReference", "policy-flex-1").put("reference", "offer-1");
            return json.write(body);
        });
        var result = budgets.execute(target, command);
        assertThat(result).isInstanceOf(FinanceResult.Success.class);
        assertThat(result.requireValue().status()).isEqualTo(BudgetObservation.Status.REJECTED);
        assertThat(json.read(json.write(result.requireValue()), JsonNode.class).at("/exceptionOffer/reference").asText()).isEqualTo("offer-1");
        assertThat(requests.get()).isEqualTo(1);
        var valid = responder.get();
        List<Consumer<ObjectNode>> corruptions = List.of(value -> value.remove("exceptionOffer"), value -> value.put("rejection", "BUDGET_INSUFFICIENT"),
                value -> value.put("operationId", UUID.randomUUID().toString()), value -> value.put("commandDigest", "f".repeat(64)),
                value -> ((ObjectNode) value.path("exceptionOffer")).remove("policyReference"),
                value -> ((ObjectNode) value.path("exceptionOffer")).put("reference", " "),
                value -> ((ObjectNode) value.path("exceptionOffer")).put("unknown", true));
        for (var corrupt : corruptions) {
            responder.set(request -> { var body = json.read(valid.apply(request), ObjectNode.class); corrupt.accept((ObjectNode) body.path("data")); return json.write(body); });
            assertThat(budgets.execute(target, command)).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
        }
    }

    @Test
    void timedOutWriteIsSentOnceAndRecoveredThroughOriginalOperationQuery() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        responder.set(request -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return json.write(success(request, applied(command, 1)));
        });
        config.getTenants().get("tenant-a").setTimeoutSeconds(1);
        try {
            assertThat(budgets.execute(target, command)).isEqualTo(unavailable(FinanceResult.Failure.TIMEOUT));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue(); assertThat(requests.get()).isEqualTo(1);
            answer(applied(command, 1));
            assertThat(budgets.query(target, command).requireValue().status()).isEqualTo(BudgetObservation.Status.APPLIED);
            assertThat(requests.get()).isEqualTo(2); assertThat(path.get()).isEqualTo("/finance/budget-query"); assertThat(key.get()).isNull();
        } finally { release.countDown(); }
    }

    private BudgetObservation applied(BudgetCommand value, long revision) {
        return new BudgetObservation(value.id(), value.digest(), BudgetObservation.Status.APPLIED, revision, "synthetic-ledger-" + revision, Instant.now().minusSeconds(1), null);
    }
    private void answer(BudgetObservation value) { responder.set(request -> json.write(success(request, value))); }
    private ObjectNode success(JsonNode request, BudgetObservation value) {
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
