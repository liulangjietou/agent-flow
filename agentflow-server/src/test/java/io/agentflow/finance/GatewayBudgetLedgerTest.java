package io.agentflow.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import io.agentflow.budget.BudgetAdjustmentContent;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;

/**
 * 回环 HTTP 验证预算原台账协议，合成响应不代表真实企业预算联调。
 * @author owlzhangfq@gmail.com
 */
class GatewayBudgetLedgerTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .registerModule(new FinanceJsonConfiguration().financeMoneyModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final JsonUtil json = new JsonUtil(mapper);
    private final AtomicReference<Function<JsonNode, String>> responder = new AtomicReference<>();
    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>(), idempotency = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final BudgetLedgerPort.Request request = new BudgetLedgerPort.Request(UUID.randomUUID(), "alice", LocalDate.parse("2026-09-29"), List.of("budget-source", "budget-target"));
    private HttpServer server;
    private FinanceGatewayConfiguration configuration;
    private FinanceGatewayClient client;
    private GatewayBudgetLedger ledger;
    private String target;

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/finance/", exchange -> {
            calls.incrementAndGet();
            path.set(exchange.getRequestURI().getPath());
            idempotency.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            var body = json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonNode.class);
            received.set(body);
            byte[] response = responder.get().apply(body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try { exchange.getResponseBody().write(response); } finally { exchange.close(); }
        });
        server.start();
        configuration = FinanceGatewayConfigurationTest.configured("http://127.0.0.1:" + server.getAddress().getPort() + "/finance", "synthetic-token");
        client = new FinanceGatewayClient(configuration, mapper);
        ledger = new GatewayBudgetLedger(client);
        target = configuration.destination("tenant-a").orElseThrow().digest("tenant-a");
        responder.set(input -> json.write(success(input)));
    }

    @AfterEach void stop() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        server.stop(0);
    }

    @Test void originalPositionsAreReadExactlyWithoutWriteIdentityOrBalanceMutation() {
        var observed = ledger.read("tenant-a", target, request).requireValue();
        assertThat(path.get()).isEqualTo("/finance/budget-ledger");
        assertThat(idempotency.get()).isNull();
        assertThat(observed.request()).isEqualTo(request);
        assertThat(observed.position("budget-source").limit()).isEqualTo(money("999999999999998.99"));
        assertThat(observed.position("budget-source").available()).isEqualTo(money("999999999999248.99"));
        assertThat(json.read(received.get().path("data").toString(), BudgetLedgerPort.Request.class)).isEqualTo(request);
        String firstCorrelation = received.get().path("requestId").asText();
        assertThat(ledger.read("tenant-a", target, request).requireValue().request()).isEqualTo(request);
        assertThat(received.get().path("requestId").asText()).isNotEqualTo(firstCorrelation);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test void foreignStaleIncompleteAndInexactFactsCannotBecomeBudgetEvidence() {
        List<Consumer<ObjectNode>> corruptions = List.of(
                data -> ((ObjectNode) data.path("request")).put("employeeId", "bob"),
                data -> ((ObjectNode) data.path("request")).put("accountingDate", "2026-09-30"),
                data -> ((ObjectNode) data.path("request")).put("legalEntityId", UUID.randomUUID().toString()),
                data -> data.put("observedAt", Instant.now().plusSeconds(10).toString()),
                data -> data.put("observedAt", Instant.now().minusSeconds(301).toString()),
                data -> data.put("validUntil", Instant.now().minusSeconds(1).toString()),
                data -> ((ObjectNode) data.path("positions").get(0)).put("legalEntityId", UUID.randomUUID().toString()),
                data -> ((ObjectNode) data.path("positions").get(0)).put("reference", "budget-target"),
                data -> ((ObjectNode) data.path("positions").get(0)).put("version", ""),
                data -> ((ObjectNode) data.path("positions").get(0)).remove("periodStatus"),
                data -> ((ObjectNode) data.path("positions").get(0)).put("periodStart", "2026-10-01"),
                data -> ((ObjectNode) data.at("/positions/0/limit")).put("value", 1000),
                data -> ((ObjectNode) data.at("/positions/0/limit")).put("value", "10.001"),
                data -> ((ObjectNode) data.at("/positions/0/committed")).put("currency", "USD"),
                data -> data.putNull("positions"),
                data -> data.put("alreadyAdjusted", true));
        for (var corruption : corruptions) {
            responder.set(input -> {
                var body = success(input);
                corruption.accept((ObjectNode) body.path("data"));
                return json.write(body);
            });
            assertThat(ledger.read("tenant-a", target, request)).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
        }
    }

    @Test void missingConfigurationChangedDestinationAndDatabaseTransactionCannotBeBypassed() {
        configuration.setEnabled(false);
        assertThat(ledger.read("tenant-a", target, request)).isEqualTo(unavailable(FinanceResult.Failure.NOT_CONFIGURED));
        configuration.setEnabled(true);
        assertThat(ledger.read("tenant-b", target, request)).isEqualTo(unavailable(FinanceResult.Failure.NOT_CONFIGURED));
        configuration.getTenants().get("tenant-a").setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/other");
        assertThat(ledger.read("tenant-a", target, request)).isEqualTo(unavailable(FinanceResult.Failure.TARGET_CHANGED));
        assertThatThrownBy(() -> ledger.read("tenant-a", null, request)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.read("tenant-a", FinanceGatewayClient.Operation.BUDGET_LEDGER, request, BudgetLedgerPort.Snapshot.class, value -> true))
                .isInstanceOf(IllegalArgumentException.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> ledger.read("tenant-a", target, request)).hasMessage("Finance gateway must run outside a transaction");
        assertThat(calls.get()).isZero();
    }

    @Test void onlyExplicitBudgetReadRejectionsAreAcceptedWithoutInventingAnEmptyLedger() {
        for (var reason : List.of(FinanceResult.Reason.LEGAL_ENTITY_UNAVAILABLE, FinanceResult.Reason.EMPLOYEE_UNAVAILABLE,
                FinanceResult.Reason.BUDGET_POSITION_UNAVAILABLE, FinanceResult.Reason.BUDGET_POLICY_UNAVAILABLE, FinanceResult.Reason.ACCOUNTING_PERIOD_CLOSED)) {
            responder.set(input -> json.write(Map.of("contractVersion", 1, "tenantId", "tenant-a", "requestId", input.path("requestId").asText(), "outcome", "REJECTED", "reason", reason)));
            assertThat(ledger.read("tenant-a", target, request)).isEqualTo(new FinanceResult.Rejected<>(reason));
        }
        responder.set(input -> json.write(Map.of("contractVersion", 1, "tenantId", "tenant-a", "requestId", input.path("requestId").asText(), "outcome", "REJECTED", "reason", "ACCOUNT_UNAVAILABLE")));
        assertThat(ledger.read("tenant-a", target, request)).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
    }

    @Test void draftJsonRejectsSelfReportedBalancesVersionsAndSuccessfulExecution() {
        var content = new BudgetAdjustmentContent(request.legalEntityId(), "预算调拨", "合成测试", BudgetAdjustmentContent.Type.TRANSFER,
                request.accountingDate(), "budget-source", "budget-target", money("10"));
        assertThat(json.read(json.write(content), BudgetAdjustmentContent.class)).isEqualTo(content);
        for (String field : List.of("limit", "committed", "consumed", "expectedVersion", "approved", "executed")) {
            var data = json.read(json.write(content), ObjectNode.class);
            data.put(field, "injected");
            assertThatThrownBy(() -> json.read(json.write(data), BudgetAdjustmentContent.class)).isInstanceOf(RuntimeException.class);
        }
    }

    private BudgetLedgerPort.Snapshot source() {
        var positions = request.budgetReferences().stream().map(reference -> new BudgetLedgerPort.Position(request.legalEntityId(), reference, "年度预算", "v1", "2026",
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), BudgetLedgerPort.PeriodStatus.OPEN,
                money("999999999999998.99"), money("300"), money("450"))).toList();
        return new BudgetLedgerPort.Snapshot(request, "ledger-v1", Instant.now().minusSeconds(1), Instant.now().plusSeconds(300), positions);
    }

    private ObjectNode success(JsonNode input) {
        return json.read(json.write(Map.of("contractVersion", 1, "tenantId", "tenant-a", "requestId", input.path("requestId").asText(), "outcome", "SUCCESS", "data", source())), ObjectNode.class);
    }

    private static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
    private static FinanceResult.Unavailable<?> unavailable(FinanceResult.Failure failure) { return new FinanceResult.Unavailable<>(failure); }
}
