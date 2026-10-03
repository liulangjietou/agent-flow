package io.agentflow.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.FinanceJsonConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.*;

/**
 * 真实回环资金目录协议验证操作者、目标与账户边界，不代表企业资金系统验收。
 * @author owlzhangfq@gmail.com
 */
class GatewayPaymentAccountsTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .registerModule(new FinanceJsonConfiguration().financeMoneyModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final JsonUtil json = new JsonUtil(mapper);
    private final AtomicReference<Function<JsonNode, String>> responder = new AtomicReference<>();
    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>(), idempotency = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final PaymentAccountsPort.Request request = new PaymentAccountsPort.Request(UUID.randomUUID(), "CNY", "cashier");
    private HttpServer server;
    private FinanceGatewayConfiguration configuration;
    private FinanceGatewayClient client;
    private GatewayPaymentAccounts accounts;
    private String target;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/finance/", exchange -> {
            calls.incrementAndGet(); path.set(exchange.getRequestURI().getPath()); idempotency.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            var body = json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonNode.class); received.set(body);
            byte[] response = responder.get().apply(body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, response.length);
            try { exchange.getResponseBody().write(response); } finally { exchange.close(); }
        });
        server.start(); configuration = FinanceGatewayConfigurationTest.configured("http://127.0.0.1:" + server.getAddress().getPort() + "/finance", "synthetic-token");
        client = new FinanceGatewayClient(configuration, mapper); accounts = new GatewayPaymentAccounts(client);
        target = configuration.destination("tenant-a").orElseThrow().digest("tenant-a"); answer(directory());
    }
    @AfterEach
    void stop() { TransactionSynchronizationManager.setActualTransactionActive(false); server.stop(0); }

    @Test
    void directoryPreservesOriginalScopeAndReadOnlyTransportWithoutSelectingDefault() {
        var directory = directory(); answer(directory);
        assertThat(accounts.debitAccounts("tenant-a", target, request).requireValue()).isEqualTo(directory);
        assertThat(path.get()).isEqualTo("/finance/debit-accounts"); assertThat(idempotency.get()).isNull();
        assertThat(json.read(received.get().path("data").toString(), PaymentAccountsPort.Request.class)).isEqualTo(request);
        String correlation = received.get().path("requestId").asText();
        answer(new PaymentAccountsPort.Directory(request, "v1", Instant.now().minusSeconds(1), Instant.now().plusSeconds(60), List.of()));
        assertThat(accounts.debitAccounts("tenant-a", target, request).requireValue().accounts()).isEmpty();
        assertThat(received.get().path("requestId").asText()).isNotEqualTo(correlation); assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void wrongCashierLegalEntityCurrencyFutureExpiredDuplicateAndUnmaskedResultsAreRejected() {
        List<Consumer<ObjectNode>> corruptions = List.of(
                body -> ((ObjectNode) body.path("request")).put("cashierId", "another"),
                body -> ((ObjectNode) body.path("request")).put("legalEntityId", UUID.randomUUID().toString()),
                body -> ((ObjectNode) body.path("request")).put("currency", "USD"),
                body -> body.put("observedAt", Instant.now().plusSeconds(10).toString()), body -> body.put("validUntil", Instant.now().minusSeconds(1).toString()),
                body -> body.putNull("accounts"), body -> ((com.fasterxml.jackson.databind.node.ArrayNode) body.path("accounts")).add(body.path("accounts").get(0).deepCopy()),
                body -> ((ObjectNode) body.path("accounts").get(0)).put("maskedAccount", "6212345678901234"), body -> body.put("defaultAccount", "hidden"));
        for (var corruption : corruptions) {
            responder.set(input -> { var body = success(input, directory()); corruption.accept((ObjectNode) body.path("data")); return json.write(body); });
            assertThat(accounts.debitAccounts("tenant-a", target, request)).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
        }
    }

    @Test
    void payeeRecheckUsesOriginalDestinationAndExactEmployeeLegalEntityWithLiveValidity() {
        var payeeRequest = new PaymentAccountsPort.PayeeRequest(request.legalEntityId(), "alice"); var payee = payee(); answer(payee);
        assertThat(accounts.currentPayee("tenant-a", target, payeeRequest).requireValue()).isEqualTo(payee);
        assertThat(path.get()).isEqualTo("/finance/employee-account"); assertThat(idempotency.get()).isNull();
        assertThat(received.get().path("data").size()).isEqualTo(2);
        assertThat(json.read(received.get().path("data").toString(), PaymentAccountsPort.PayeeRequest.class)).isEqualTo(payeeRequest);
        for (Consumer<ObjectNode> corruption : List.<Consumer<ObjectNode>>of(
                body -> ((ObjectNode) body.path("snapshot")).put("employeeId", "bob"),
                body -> ((ObjectNode) body.path("snapshot")).put("legalEntityId", UUID.randomUUID().toString()),
                body -> body.put("validUntil", Instant.now().minusSeconds(1).toString()))) {
            responder.set(input -> { var body = success(input, payee()); corruption.accept((ObjectNode) body.path("data")); return json.write(body); });
            assertThat(accounts.currentPayee("tenant-a", target, payeeRequest)).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
        }
    }

    @Test
    void missingConfigChangedTargetTransactionsAndGenericOperationsCannotBypassPaymentEvidenceBoundary() {
        var payeeRequest = new PaymentAccountsPort.PayeeRequest(request.legalEntityId(), "alice");
        configuration.setEnabled(false); assertThat(accounts.debitAccounts("tenant-a", target, request)).isEqualTo(unavailable(FinanceResult.Failure.NOT_CONFIGURED));
        assertThat(accounts.currentPayee("tenant-a", target, payeeRequest)).isEqualTo(unavailable(FinanceResult.Failure.NOT_CONFIGURED)); configuration.setEnabled(true);
        configuration.getTenants().get("tenant-a").setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/other");
        assertThat(accounts.debitAccounts("tenant-a", target, request)).isEqualTo(unavailable(FinanceResult.Failure.TARGET_CHANGED));
        assertThat(accounts.currentPayee("tenant-a", target, payeeRequest)).isEqualTo(unavailable(FinanceResult.Failure.TARGET_CHANGED));
        assertThatThrownBy(() -> accounts.debitAccounts("tenant-a", null, request)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.read("tenant-a", FinanceGatewayClient.Operation.DEBIT_ACCOUNTS, request, PaymentAccountsPort.Directory.class, value -> true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.readPaymentAccounts("tenant-a", target, FinanceGatewayClient.Operation.PAYMENT_COMMAND, request, PaymentAccountsPort.Directory.class, value -> true)).isInstanceOf(IllegalArgumentException.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> accounts.debitAccounts("tenant-a", target, request)).hasMessage("Finance gateway must run outside a transaction");
        assertThatThrownBy(() -> accounts.currentPayee("tenant-a", target, payeeRequest)).hasMessage("Finance gateway must run outside a transaction");
        assertThat(calls.get()).isZero();
    }

    @Test
    void businessRejectionsRemainExplicitAndDoNotBecomeAnEmptySuccessfulDirectory() {
        for (var reason : List.of(FinanceResult.Reason.CASHIER_UNAVAILABLE, FinanceResult.Reason.DEBIT_ACCOUNT_UNAVAILABLE, FinanceResult.Reason.LEGAL_ENTITY_UNAVAILABLE)) {
            responder.set(input -> json.write(Map.of("contractVersion", 1, "tenantId", "tenant-a", "requestId", input.path("requestId").asText(), "outcome", "REJECTED", "reason", reason)));
            assertThat(accounts.debitAccounts("tenant-a", target, request)).isEqualTo(new FinanceResult.Rejected<>(reason));
        }
        responder.set(input -> json.write(Map.of("contractVersion", 1, "tenantId", "tenant-a", "requestId", input.path("requestId").asText(), "outcome", "REJECTED", "reason", "BUDGET_INSUFFICIENT")));
        assertThat(accounts.debitAccounts("tenant-a", target, request)).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
    }

    private PaymentAccountsPort.Directory directory() {
        return new PaymentAccountsPort.Directory(request, "v1", Instant.now().minusSeconds(1), Instant.now().plusSeconds(60),
                List.of(new PaymentAccountsPort.DebitAccount("synthetic-debit", "业务账户", "****1234", "CNY", "v1")));
    }
    private EmployeeAccountPort.Account payee() {
        return new EmployeeAccountPort.Account(new EmployeeAccountSnapshot(request.legalEntityId(), "alice", "synthetic-payee", "****5678", "a".repeat(64), "v1"), Instant.now().plusSeconds(60));
    }
    private void answer(Object value) { responder.set(input -> json.write(success(input, value))); }
    private ObjectNode success(JsonNode input, Object value) {
        return json.read(json.write(Map.of("contractVersion", 1, "tenantId", "tenant-a", "requestId", input.path("requestId").asText(), "outcome", "SUCCESS", "data", value)), ObjectNode.class);
    }
    private static FinanceResult.Unavailable<?> unavailable(FinanceResult.Failure failure) { return new FinanceResult.Unavailable<>(failure); }
}
