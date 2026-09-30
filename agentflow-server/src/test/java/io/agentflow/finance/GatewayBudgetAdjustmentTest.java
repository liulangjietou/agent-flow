package io.agentflow.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentflow.budget.ApprovedBudgetAdjustment;
import io.agentflow.budget.BudgetAdjustmentCommand;
import io.agentflow.budget.BudgetAdjustmentContent;
import io.agentflow.budget.BudgetAdjustmentObservation;
import io.agentflow.budget.BudgetAdjustmentRequest;
import io.agentflow.budget.BudgetAdjustmentRound;
import io.agentflow.budget.BudgetLedgerPort;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.FinanceJsonConfiguration;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实回环 HTTP 核对原子预算写入和原号查询；合成台账不代表企业预算联调。
 * @author owlzhangfq@gmail.com
 */
class GatewayBudgetAdjustmentTest {
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
    private GatewayBudgetAdjustment budgets;
    private BudgetAdjustmentCommand command;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(); server.setExecutor(executor); server.createContext("/finance/", this::handle); server.start();
        config = FinanceGatewayConfigurationTest.configured("http://127.0.0.1:" + server.getAddress().getPort() + "/finance", "synthetic-token");
        client = new FinanceGatewayClient(config, mapper); budgets = new GatewayBudgetAdjustment(client);
        var now = Instant.now(); var entityId = UUID.randomUUID(); var date = LocalDate.of(2026, 9, 29);
        var content = new BudgetAdjustmentContent(entityId, "季度调拨", "复核预算", BudgetAdjustmentContent.Type.TRANSFER, date,
                "source-budget", "target-budget", money("0.01"));
        var positions = List.of(position(entityId, "source-budget", "1000.01", "300.01", "100.00"),
                position(entityId, "target-budget", "999.99", "80.00", "100.01"));
        var ledger = new BudgetLedgerPort.Snapshot(content.ledgerRequest("alice"), "ledger-v1", now.minusSeconds(15), now.plusSeconds(285), positions);
        var round = new BudgetAdjustmentRound(1, 1, "alice", now.minusSeconds(14), content,
                new FinanceCatalog.LegalEntity(entityId, "法人", "CNY", false, "v1", "Asia/Shanghai"), "catalog-v1",
                config.destination("tenant-a").orElseThrow().digest("tenant-a"), ledger);
        var source = new ApprovedBudgetAdjustment("tenant-a", UUID.randomUUID(), UUID.randomUUID(), "alice", 3, round,
                new BudgetAdjustmentRequest.Approval(1, 4, "manager", now.minusSeconds(10)));
        var latest = new BudgetLedgerPort.Snapshot(ledger.request(), "ledger-v2", now.minusSeconds(5), now.plusSeconds(295), positions);
        command = BudgetAdjustmentCommand.authorize(UUID.randomUUID(), source, latest, "finance", "确认原批准与最新额度", now.minusSeconds(4));
        answer(applied(command));
    }

    @AfterEach
    void stop() { TransactionSynchronizationManager.setActualTransactionActive(false); server.stop(0); executor.shutdownNow(); }

    @Test
    void repeatCarriesSameBytesAndSingleIdempotencyKeyWithBothExactChanges() {
        assertThat(budgets.execute(command).requireValue().status()).isEqualTo(BudgetAdjustmentObservation.Status.APPLIED);
        String first = received.get();
        assertThat(path.get()).isEqualTo("/finance/budget-adjustment-command"); assertThat(key.get()).isEqualTo(command.id().toString());
        assertThat(authorization.get()).isEqualTo("Bearer synthetic-token");
        var request = json.read(first, JsonNode.class);
        assertThat(request.path("requestId").asText()).isEqualTo(command.id().toString());
        assertThat(request.at("/data/commandDigest").asText()).isEqualTo(command.digest());
        assertThat(json.read(request.at("/data/command").toString(), BudgetAdjustmentCommand.class)).isEqualTo(command);
        assertThat(request.at("/data/command/changes/0/afterLimit/value").textValue()).isEqualTo("1000.00");
        assertThat(request.at("/data/command/changes/1/afterLimit/value").textValue()).isEqualTo("1000.00");
        budgets.execute(command).requireValue();
        assertThat(received.get()).isEqualTo(first); assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    void queriesUseOriginalIdentityFreshCorrelationAndNoWriteKey() {
        var missing = observed(command, BudgetAdjustmentObservation.Status.NOT_FOUND, null);
        answer(missing); assertThat(budgets.execute(command)).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
        assertThat(budgets.query(command).requireValue()).isEqualTo(missing);
        assertThat(path.get()).isEqualTo("/finance/budget-adjustment-query"); assertThat(key.get()).isNull();
        var first = json.read(received.get(), JsonNode.class);
        assertThat(first.path("data").size()).isEqualTo(2);
        assertThat(first.at("/data/operationId").asText()).isEqualTo(command.id().toString());
        assertThat(first.at("/data/commandDigest").asText()).isEqualTo(command.digest());
        budgets.query(command).requireValue();
        assertThat(json.read(received.get(), JsonNode.class).path("requestId").asText()).isNotEqualTo(first.path("requestId").asText());
    }

    @Test
    void changedTargetDisabledGatewayAndTransactionNeverSend() {
        config.getTenants().get("tenant-a").setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/other");
        assertThat(budgets.execute(command)).isEqualTo(unavailable(FinanceResult.Failure.TARGET_CHANGED));
        assertThat(budgets.query(command)).isEqualTo(unavailable(FinanceResult.Failure.TARGET_CHANGED));
        config.setEnabled(false);
        assertThat(budgets.execute(command)).isEqualTo(unavailable(FinanceResult.Failure.NOT_CONFIGURED));
        assertThat(budgets.query(command)).isEqualTo(unavailable(FinanceResult.Failure.NOT_CONFIGURED));
        config.setEnabled(true); TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> budgets.execute(command)).hasMessage("Finance gateway must run outside a transaction");
        assertThatThrownBy(() -> budgets.query(command)).hasMessage("Finance gateway must run outside a transaction");
        TransactionSynchronizationManager.setActualTransactionActive(false);
        for (var operation : List.of(FinanceGatewayClient.Operation.BUDGET_ADJUSTMENT_COMMAND, FinanceGatewayClient.Operation.BUDGET_ADJUSTMENT_QUERY)) {
            assertThatThrownBy(() -> client.read("tenant-a", operation, command, BudgetAdjustmentObservation.class, value -> true)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(requests.get()).isZero();
    }

    @Test
    void incompleteChangedOrMalformedFactsCannotBecomeApplied() {
        List<Consumer<ObjectNode>> corruptions = List.of(
                body -> body.put("operationId", UUID.randomUUID().toString()), body -> body.put("commandDigest", "b".repeat(64)),
                body -> body.put("revision", "1"), body -> body.remove("revision"), body -> body.put("revision", 0),
                body -> body.put("appliedAt", Instant.now().plusSeconds(30).toString()), body -> body.put("status", "PENDING"),
                body -> body.put("rejection", "BUDGET_INSUFFICIENT"), body -> body.put("unknown", true),
                body -> body.put("observedAt", command.authorizedAt().minusSeconds(1).toString()),
                body -> ((ArrayNode) body.path("changes")).remove(1),
                body -> ((ArrayNode) body.path("changes")).set(1, body.at("/changes/0")),
                body -> firstChange(body).put("beforeVersion", "different"),
                body -> firstChange(body).put("afterVersion", "v1"),
                body -> firstChange(body).put("periodReference", "other"),
                body -> firstChange(body).put("accountingDate", "2026-09-28"),
                body -> ((ObjectNode) body.at("/changes/0/afterLimit")).put("value", "1000.01"),
                body -> ((ObjectNode) body.at("/changes/0/afterLimit")).put("value", 1000),
                body -> ((ObjectNode) body.at("/changes/0/committed")).put("value", "300.00"),
                body -> ((ObjectNode) body.at("/changes/0/consumed")).put("value", "99.99"));
        for (var corrupt : corruptions) {
            responder.set(request -> { var body = success(request, applied(command)); corrupt.accept((ObjectNode) body.path("data")); return json.write(body); });
            assertThat(budgets.execute(command)).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
        }
        responder.set(request -> json.write(success(request, applied(command))).replace("\"status\":\"APPLIED\"", "\"status\":\"APPLIED\",\"status\":\"APPLIED\""));
        assertThat(budgets.query(command)).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
        responder.set(request -> json.write(Map.of("contractVersion", 1, "tenantId", "tenant-a", "requestId", request.path("requestId").asText(),
                "outcome", "REJECTED", "reason", "BUDGET_INSUFFICIENT")));
        assertThat(budgets.execute(command)).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
    }

    @Test
    void rejectionPendingAndExpiredAuthorizationAreNotApplied() {
        var rejected = observed(command, BudgetAdjustmentObservation.Status.REJECTED, BudgetAdjustmentObservation.Rejection.LEDGER_VERSION_CONFLICT);
        answer(rejected); assertThat(budgets.execute(command).requireValue()).isEqualTo(rejected);
        var pending = observed(command, BudgetAdjustmentObservation.Status.PENDING, null);
        answer(pending); assertThat(budgets.execute(command).requireValue()).isEqualTo(pending);
        var expired = new BudgetAdjustmentCommand(command.id(), command.source(), command.ledger(), command.changes(), command.authorizedBy(), command.reason(),
                command.authorizedAt(), command.authorizedAt().plusSeconds(1));
        int before = requests.get(); assertThatThrownBy(() -> budgets.execute(expired)).hasMessageContaining("expired"); assertThat(requests.get()).isEqualTo(before);
        var missing = observed(expired, BudgetAdjustmentObservation.Status.NOT_FOUND, null);
        answer(missing); assertThat(budgets.query(expired).requireValue()).isEqualTo(missing); assertThat(key.get()).isNull();
    }

    @Test
    void timedOutWriteIsSentOnceAndRecoveredThroughOriginalQuery() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        responder.set(request -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return json.write(success(request, applied(command)));
        });
        config.getTenants().get("tenant-a").setTimeoutSeconds(1);
        try {
            assertThat(budgets.execute(command)).isEqualTo(unavailable(FinanceResult.Failure.TIMEOUT));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue(); assertThat(requests.get()).isEqualTo(1);
            answer(applied(command));
            assertThat(budgets.query(command).requireValue().status()).isEqualTo(BudgetAdjustmentObservation.Status.APPLIED);
            assertThat(requests.get()).isEqualTo(2); assertThat(path.get()).isEqualTo("/finance/budget-adjustment-query"); assertThat(key.get()).isNull();
        } finally { release.countDown(); }
    }

    private BudgetAdjustmentObservation applied(BudgetAdjustmentCommand value) {
        var changes = value.changes().stream().map(change -> {
            var position = value.ledger().position(change.budgetReference());
            return new BudgetAdjustmentObservation.AppliedChange(change.budgetReference(), change.expectedVersion(), "v2", position.periodReference(),
                    value.source().round().content().accountingDate(), change.beforeLimit(), change.afterLimit(), position.committed(), position.consumed());
        }).toList();
        return new BudgetAdjustmentObservation(value.id(), value.digest(), BudgetAdjustmentObservation.Status.APPLIED, 1, Instant.now(),
                "synthetic-adjustment", value.authorizedAt().plusSeconds(1), changes, null);
    }
    private BudgetAdjustmentObservation observed(BudgetAdjustmentCommand value, BudgetAdjustmentObservation.Status status, BudgetAdjustmentObservation.Rejection rejection) {
        return new BudgetAdjustmentObservation(value.id(), value.digest(), status, status == BudgetAdjustmentObservation.Status.NOT_FOUND ? 0 : 1,
                Instant.now(), null, null, List.of(), rejection);
    }
    private static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
    private static BudgetLedgerPort.Position position(UUID entity, String reference, String limit, String committed, String consumed) {
        return new BudgetLedgerPort.Position(entity, reference, reference, "v1", "2026", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                BudgetLedgerPort.PeriodStatus.OPEN, money(limit), money(committed), money(consumed));
    }
    private ObjectNode firstChange(ObjectNode body) { return (ObjectNode) body.at("/changes/0"); }
    private void answer(BudgetAdjustmentObservation value) { responder.set(request -> json.write(success(request, value))); }
    private ObjectNode success(JsonNode request, BudgetAdjustmentObservation value) {
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
