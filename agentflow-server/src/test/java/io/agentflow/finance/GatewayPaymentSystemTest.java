package io.agentflow.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
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
 * 真实回环 HTTP 验证支付幂等、精确到账、超时查询及外发边界，不代表银行或资金系统联调。
 * @author owlzhangfq@gmail.com
 */
class GatewayPaymentSystemTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .registerModule(new FinanceJsonConfiguration().financeMoneyModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final JsonUtil json = new JsonUtil(mapper);
    private final AtomicReference<Function<JsonNode, String>> responder = new AtomicReference<>();
    private final AtomicReference<String> received = new AtomicReference<>(), key = new AtomicReference<>(), path = new AtomicReference<>(), authorization = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;
    private ExecutorService executor;
    private FinanceGatewayConfiguration config;
    private FinanceGatewayClient client;
    private GatewayPaymentSystem payments;
    private PaymentCommand command;
    private String target;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(); server.setExecutor(executor); server.createContext("/finance/", this::handle); server.start();
        config = FinanceGatewayConfigurationTest.configured("http://127.0.0.1:" + server.getAddress().getPort() + "/finance", "synthetic-token");
        client = new FinanceGatewayClient(config, mapper); payments = new GatewayPaymentSystem(client);
        target = config.destination("tenant-a").orElseThrow().digest("tenant-a");
        command = new PaymentCommand(UUID.randomUUID(), "tenant-a", PaymentCommand.Purpose.EMPLOYEE_ADVANCE,
                new PaymentCommand.Binding(UUID.randomUUID(), UUID.randomUUID(), 1, 7, 4), new Money(new BigDecimal("100.00"), "CNY"),
                "synthetic-debit-account", new EmployeeAccountSnapshot(UUID.randomUUID(), "alice", "synthetic-employee-account", "****1234", "a".repeat(64), "v1"),
                "synthetic-voucher", new PaymentCommand.Authorization("finance", "cashier", Instant.now().minusSeconds(120), Instant.now().plusSeconds(60)));
        answer(paid(command));
    }

    @AfterEach
    void stop() { TransactionSynchronizationManager.setActualTransactionActive(false); server.stop(0); executor.shutdownNow(); }

    @Test
    void repeatsCarryIdenticalCommandBytesIdentityAndBoundAmountWhileCredentialsCanRotate() {
        assertThat(payments.execute(target, command).requireValue().status()).isEqualTo(PaymentObservation.Status.SUCCEEDED);
        String first = received.get();
        assertThat(path.get()).isEqualTo("/finance/payment-command"); assertThat(key.get()).isEqualTo(command.id().toString());
        assertThat(authorization.get()).isEqualTo("Bearer synthetic-token");
        var request = json.read(first, JsonNode.class);
        assertThat(request.path("requestId").asText()).isEqualTo(command.id().toString());
        assertThat(request.at("/data/commandDigest").asText()).isEqualTo(command.digest());
        assertThat(request.at("/data/command/amount/value").isTextual()).isTrue();
        assertThat(json.read(request.at("/data/command").toString(), PaymentCommand.class)).isEqualTo(command);
        config.getTenants().get("tenant-a").setToken("rotated-token"); payments.execute(target, command).requireValue();
        assertThat(received.get()).isEqualTo(first); assertThat(authorization.get()).isEqualTo("Bearer rotated-token"); assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    void missingCanOnlyComeFromReadOnlyOriginalAuthorizationQueryWithFreshCorrelationId() {
        var missing = new PaymentObservation(command.id(), command.digest(), PaymentObservation.Status.NOT_FOUND, 0L, Instant.now().minusSeconds(1), null, null, null, null, null, null);
        answer(missing); assertThat(payments.execute(target, command)).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
        assertThat(payments.query(target, command).requireValue()).isEqualTo(missing);
        assertThat(path.get()).isEqualTo("/finance/payment-query"); assertThat(key.get()).isNull();
        var first = json.read(received.get(), JsonNode.class);
        assertThat(first.path("data").size()).isEqualTo(2); assertThat(first.at("/data/authorizationId").asText()).isEqualTo(command.id().toString());
        assertThat(first.at("/data/commandDigest").asText()).isEqualTo(command.digest());
        payments.query(target, command).requireValue();
        assertThat(json.read(received.get(), JsonNode.class).path("requestId").asText()).isNotEqualTo(first.path("requestId").asText());
        responder.set(request -> { var body = success(request, missing); ((ObjectNode) body.path("data")).remove("revision"); return json.write(body); });
        assertThat(payments.query(target, command)).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
    }

    @Test
    void destinationsTransactionsAndGenericReadCannotBypassPersistentPaymentIdentity() {
        config.getTenants().get("tenant-a").setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/other");
        assertThat(payments.execute(target, command)).isEqualTo(unavailable(FinanceResult.Failure.TARGET_CHANGED));
        assertThat(payments.query(target, command)).isEqualTo(unavailable(FinanceResult.Failure.TARGET_CHANGED));
        config.setEnabled(false); assertThat(payments.execute(target, command)).isEqualTo(unavailable(FinanceResult.Failure.NOT_CONFIGURED));
        config.setEnabled(true); TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> payments.execute(target, command)).hasMessage("Finance gateway must run outside a transaction");
        assertThatThrownBy(() -> payments.query(target, command)).hasMessage("Finance gateway must run outside a transaction");
        TransactionSynchronizationManager.setActualTransactionActive(false);
        for (var operation : List.of(FinanceGatewayClient.Operation.PAYMENT_COMMAND, FinanceGatewayClient.Operation.PAYMENT_QUERY,
                FinanceGatewayClient.Operation.BUDGET_COMMAND, FinanceGatewayClient.Operation.BUDGET_QUERY)) {
            assertThatThrownBy(() -> client.read("tenant-a", operation, command, PaymentObservation.class, value -> true)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(requests.get()).isZero();
    }

    @Test
    void expiredAuthorizationCannotSendButCanStillRecoverExistingPaymentByQuery() {
        var expired = new PaymentCommand(command.id(), command.tenantId(), command.purpose(), command.binding(), command.amount(), command.debitAccountReference(),
                command.payee(), command.voucherReference(), new PaymentCommand.Authorization("finance", "cashier", Instant.now().minusSeconds(120), Instant.now().minusSeconds(60)));
        assertThatThrownBy(() -> payments.execute(target, expired)).isInstanceOf(DomainException.class).hasMessage("Payment authorization is outside its sending window");
        assertThat(requests.get()).isZero(); answer(paid(expired));
        assertThat(payments.query(target, expired).requireValue().status()).isEqualTo(PaymentObservation.Status.SUCCEEDED);
        assertThat(requests.get()).isEqualTo(1); assertThat(path.get()).isEqualTo("/finance/payment-query");
    }

    @Test
    void wrongIdentityAccountPartialAmountFutureTimeAndMalformedEvidenceNeverConfirmPayment() {
        List<Consumer<ObjectNode>> corruptions = List.of(
                body -> body.put("authorizationId", UUID.randomUUID().toString()), body -> body.put("commandDigest", "b".repeat(64)),
                body -> body.put("accountDigest", "b".repeat(64)), body -> ((ObjectNode) body.path("paidAmount")).put("value", "99.99"),
                body -> ((ObjectNode) body.path("paidAmount")).put("currency", "USD"), body -> ((ObjectNode) body.path("paidAmount")).put("value", 100),
                body -> body.put("revision", 0), body -> body.put("revision", "2"), body -> body.remove("revision"),
                body -> body.put("observedAt", Instant.now().plusSeconds(60).toString()), body -> body.put("status", "PENDING"),
                body -> body.remove("receiptReference"), body -> body.put("failure", "PAYMENT_REJECTED"), body -> body.put("unknown", true));
        for (var corrupt : corruptions) {
            responder.set(request -> { var body = success(request, paid(command)); corrupt.accept((ObjectNode) body.path("data")); return json.write(body); });
            assertThat(payments.execute(target, command)).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
        }
        responder.set(request -> json.write(Map.of("contractVersion", 1, "tenantId", "tenant-a", "requestId", request.path("requestId").asText(), "outcome", "REJECTED", "reason", "ACCOUNT_UNAVAILABLE")));
        assertThat(payments.execute(target, command)).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
    }

    @Test
    void pendingAndReversedNeverBecomeSucceededAndExplicitFailureRemainsBoundToCommand() {
        for (var value : List.of(
                new PaymentObservation(command.id(), command.digest(), PaymentObservation.Status.PENDING, 1L, Instant.now().minusSeconds(1), "bank-1", null, null, null, null, null),
                new PaymentObservation(command.id(), command.digest(), PaymentObservation.Status.FAILED, 2L, Instant.now().minusSeconds(1), "bank-1", null, null, null, null, PaymentObservation.Failure.ACCOUNT_UNAVAILABLE),
                new PaymentObservation(command.id(), command.digest(), PaymentObservation.Status.REVERSED, 3L, Instant.now().minusSeconds(1), "bank-1", command.amount(), command.payee().accountDigest(), Instant.now().minusSeconds(2), "reversal-1", null))) {
            answer(value); assertThat(payments.query(target, command).requireValue()).isEqualTo(value);
            assertThat(value.status()).isNotEqualTo(PaymentObservation.Status.SUCCEEDED);
        }
    }

    @Test
    void timeoutSendsOnlyOnceThenQueriesOriginalAuthorizationWithoutResendingCommand() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        responder.set(request -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return json.write(success(request, paid(command)));
        });
        config.getTenants().get("tenant-a").setTimeoutSeconds(1);
        try {
            assertThat(payments.execute(target, command)).isEqualTo(unavailable(FinanceResult.Failure.TIMEOUT));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue(); assertThat(requests.get()).isEqualTo(1);
            answer(paid(command)); assertThat(payments.query(target, command).requireValue().status()).isEqualTo(PaymentObservation.Status.SUCCEEDED);
            assertThat(requests.get()).isEqualTo(2); assertThat(path.get()).isEqualTo("/finance/payment-query"); assertThat(key.get()).isNull();
        } finally { release.countDown(); }
    }

    private PaymentObservation paid(PaymentCommand value) {
        return new PaymentObservation(value.id(), value.digest(), PaymentObservation.Status.SUCCEEDED, 2L, Instant.now().minusSeconds(1), "bank-1",
                value.amount(), value.payee().accountDigest(), Instant.now().minusSeconds(2), "receipt-1", null);
    }
    private void answer(PaymentObservation value) { responder.set(request -> json.write(success(request, value))); }
    private ObjectNode success(JsonNode request, PaymentObservation value) {
        return json.read(json.write(Map.of("contractVersion", 1, "tenantId", request.path("tenantId").asText(), "requestId", request.path("requestId").asText(), "outcome", "SUCCESS", "data", value)), ObjectNode.class);
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
