package io.agentflow.finance;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
import io.agentflow.procurement.SupplierPayableSettlementCommand;
import io.agentflow.procurement.SupplierPayableSettlementEvidence;
import io.agentflow.procurement.SupplierPayableSettlementObservation;
import io.agentflow.procurement.SupplierPayableSettlementOperation;
import io.agentflow.procurement.SupplierPaymentCommand;
import io.agentflow.procurement.SupplierPaymentEvidence;
import io.agentflow.procurement.SupplierPaymentOperation;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static io.agentflow.finance.SupplierFinanceProtocolFixture.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 真实回环 HTTP 验证原应付核销契约、固定命令和未知恢复，不代表企业 ERP 联调完成。
 * @author owlzhangfq@gmail.com
 */
class GatewaySupplierPayableSettlementTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .registerModule(new FinanceJsonConfiguration().financeMoneyModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private final JsonUtil json = new JsonUtil(mapper);
    private final AtomicReference<Function<JsonNode, String>> responder = new AtomicReference<>();
    private final AtomicReference<String> received = new AtomicReference<>(), key = new AtomicReference<>(), path = new AtomicReference<>(), credential = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private HttpServer server;
    private ExecutorService executor;
    private FinanceGatewayConfiguration configuration;
    private FinanceGatewayClient client;
    private GatewaySupplierPayableSettlement gateway;
    private SupplierPayableSettlementCommand command;
    private String target;

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); executor = Executors.newCachedThreadPool();
        server.setExecutor(executor); server.createContext("/finance/", this::handle); server.start();
        configuration = FinanceGatewayConfigurationTest.configured("http://127.0.0.1:" + server.getAddress().getPort() + "/finance", "synthetic-token");
        client = new FinanceGatewayClient(configuration, mapper); gateway = new GatewaySupplierPayableSettlement(client);
        target = configuration.destination("tenant-a").orElseThrow().digest("tenant-a"); command = command(Instant.now().minusSeconds(120)); answer(settled(command));
    }
    @AfterEach void stop() { TransactionSynchronizationManager.setActualTransactionActive(false); server.stop(0); executor.shutdownNow(); }

    @Test void repeatsPreserveOriginalBytesAndExactQuantityWhileReadEvidenceAndCredentialRefresh() {
        assertThat(gateway.settle(command, evidence(command, Instant.now())).requireValue().status()).isEqualTo(SupplierPayableSettlementObservation.Status.SETTLED);
        String first = received.get(); var request = json.read(first, JsonNode.class);
        assertThat(path.get()).isEqualTo("/finance/supplier-payable-settlement-command"); assertThat(key.get()).isEqualTo(command.id().toString());
        assertThat(request.path("requestId").asText()).isEqualTo(command.id().toString()); assertThat(request.at("/data/commandDigest").asText()).isEqualTo(command.digest());
        assertThat(first).contains("999999999999999.123456"); assertThat(json.read(request.at("/data/command").toString(), SupplierPayableSettlementCommand.class)).isEqualTo(command);
        assertThat(request.at("/data/command/payment/holdCommand/authorization/source/reservation/source/round/content/amount/value").asText()).isEqualTo("70.00");
        assertThat(credential.get()).isEqualTo("Bearer synthetic-token"); configuration.getTenants().get("tenant-a").setToken("rotated-token");
        gateway.settle(command, evidence(command, Instant.now())).requireValue(); assertThat(received.get()).isEqualTo(first); assertThat(credential.get()).isEqualTo("Bearer rotated-token");
    }

    @Test void queryAfterOldAuthorizationAndPeriodEvidenceExpireHasOnlyOriginalIdentity() {
        var old = command(Instant.now().minusSeconds(172800)); answer(settled(old));
        assertThatThrownBy(() -> gateway.settle(old, evidence(old, old.registeredAt()))).isInstanceOf(DomainException.class); assertThat(calls.get()).isZero();
        assertThat(gateway.query(old).requireValue().status()).isEqualTo(SupplierPayableSettlementObservation.Status.SETTLED);
        var first = json.read(received.get(), JsonNode.class); assertThat(first.path("data").size()).isEqualTo(2);
        assertThat(first.at("/data/operationId").asText()).isEqualTo(old.id().toString()); assertThat(first.at("/data/commandDigest").asText()).isEqualTo(old.digest());
        assertThat(key.get()).isNull(); assertThat(path.get()).isEqualTo("/finance/supplier-payable-settlement-query");
        gateway.query(old).requireValue(); assertThat(json.read(received.get(), JsonNode.class).path("requestId").asText()).isNotEqualTo(first.path("requestId").asText());
        var absent = new SupplierPayableSettlementObservation(command.id(), command.digest(), SupplierPayableSettlementObservation.Status.NOT_FOUND, 0L, Instant.now(), null, null);
        answer(absent); assertThat(gateway.settle(command, evidence(command, Instant.now()))).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE)); assertThat(gateway.query(command).requireValue()).isEqualTo(absent);
    }

    @Test void disabledChangedTargetTransactionsAndGenericReadCannotBypassFixedContract() {
        var proof = evidence(command, Instant.now()); configuration.setEnabled(false);
        assertThat(gateway.settle(command, proof)).isEqualTo(unavailable(FinanceResult.Failure.NOT_CONFIGURED));
        configuration.setEnabled(true); configuration.getTenants().get("tenant-a").setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/other");
        assertThat(gateway.settle(command, proof)).isEqualTo(unavailable(FinanceResult.Failure.TARGET_CHANGED)); assertThat(gateway.query(command)).isEqualTo(unavailable(FinanceResult.Failure.TARGET_CHANGED));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> gateway.settle(command, proof)).hasMessage("Finance gateway must run outside a transaction");
        assertThatThrownBy(() -> gateway.query(command)).hasMessage("Finance gateway must run outside a transaction"); TransactionSynchronizationManager.setActualTransactionActive(false);
        for (var operation : List.of(FinanceGatewayClient.Operation.SUPPLIER_PAYABLE_SETTLEMENT_COMMAND, FinanceGatewayClient.Operation.SUPPLIER_PAYABLE_SETTLEMENT_QUERY)) {
            assertThatThrownBy(() -> client.read("tenant-a", operation, command, SupplierPayableSettlementObservation.class, value -> true)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(calls.get()).isZero();
    }

    @Test void inconsistentIncompleteOrCoercedSettlementCannotConsumeOriginalPayable() {
        List<Consumer<ObjectNode>> corruptions = List.of(
                data -> data.put("operationId", UUID.randomUUID().toString()), data -> data.put("commandDigest", "b".repeat(64)),
                data -> data.put("revision", "1"), data -> data.remove("revision"), data -> data.put("revision", 0), data -> data.put("unknown", true),
                data -> data.put("observedAt", Instant.now().plusSeconds(30).toString()), data -> data.put("status", "PENDING"),
                data -> ((ObjectNode) data.path("posting")).remove("voucherReference"), data -> ((ObjectNode) data.path("posting")).put("holdReference", "other-hold"),
                data -> ((ObjectNode) data.path("posting")).put("bankReceiptReference", "other-receipt"), data -> ((ObjectNode) data.path("posting")).put("bankPaymentReference", "other-bank"),
                data -> ((ObjectNode) data.path("posting")).put("periodReference", "other-period"), data -> ((ObjectNode) data.path("posting")).put("settledAt", command.registeredAt().minusNanos(1).toString()),
                data -> ((ObjectNode) data.at("/posting/settledAfter")).put("value", "99.99"), data -> ((ObjectNode) data.at("/posting/settledAmount")).put("value", 70),
                data -> ((ObjectNode) data.path("posting")).put("accountingDate", command.period().request().accountingDate().plusDays(1).toString()));
        for (var corrupt : corruptions) {
            responder.set(request -> { var body = success(request, settled(command)); corrupt.accept((ObjectNode) body.path("data")); return json.write(body); });
            assertThat(gateway.settle(command, evidence(command, Instant.now()))).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
        }
        responder.set(request -> json.write(Map.of("contractVersion", 1, "tenantId", "tenant-a", "requestId", request.path("requestId").asText(), "outcome", "REJECTED", "reason", "ACCOUNTING_PERIOD_CLOSED")));
        assertThat(gateway.settle(command, evidence(command, Instant.now()))).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
        var rejection = new SupplierPayableSettlementObservation(command.id(), command.digest(), SupplierPayableSettlementObservation.Status.REJECTED, 1L, Instant.now(), null, SupplierPayableSettlementObservation.Rejection.ACCOUNTING_PERIOD_CLOSED);
        answer(rejection); assertThat(gateway.settle(command, evidence(command, Instant.now())).requireValue()).isEqualTo(rejection);
    }

    @Test void timedOutSettlementMakesOneWriteAndRecoversWithOriginalQuery() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        responder.set(request -> { entered.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); } return json.write(success(request, settled(command))); });
        configuration.getTenants().get("tenant-a").setTimeoutSeconds(1);
        try {
            assertThat(gateway.settle(command, evidence(command, Instant.now()))).isEqualTo(unavailable(FinanceResult.Failure.TIMEOUT));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue(); assertThat(calls.get()).isEqualTo(1);
            answer(settled(command)); assertThat(gateway.query(command).requireValue().status()).isEqualTo(SupplierPayableSettlementObservation.Status.SETTLED);
            assertThat(calls.get()).isEqualTo(2); assertThat(key.get()).isNull(); assertThat(path.get()).isEqualTo("/finance/supplier-payable-settlement-query");
        } finally { release.countDown(); }
    }

    @Test void immutableCommandAndRecoveryStatesRoundTripUsingActualNonNullJsonShape() {
        var now = Instant.now().minusSeconds(1); var queue = SupplierPayableSettlementOperation.queue(command, command.registeredAt());
        var checking = queue.claim(now, Duration.ofSeconds(30)); var proof = evidence(command, now); var sending = checking.readyToSend(proof, now);
        var unknown = sending.unavailable(SupplierPayableSettlementOperation.Failure.CONNECTION, now);
        var done = sending.complete(new FinanceResult.Success<>(settled(command)), Instant.now());
        for (var value : List.of(queue, checking, sending, unknown, done)) assertThat(json.read(json.write(value), SupplierPayableSettlementOperation.class)).isEqualTo(value);
        assertThat(json.read(json.write(proof), SupplierPayableSettlementEvidence.class)).isEqualTo(proof);
        assertThat(json.write(command)).doesNotContain("\"id\":null");
    }

    private SupplierPayableSettlementCommand command(Instant start) {
        var payment = payment(start); var now = start.plusSeconds(7);
        var proof = SupplierPaymentEvidence.checked(payment, directory(payment.holdCommand(), now), payment.holdCommand().authorization().payable(), held(payment.holdCommand(), now), now);
        var bank = SupplierPaymentOperation.queue(payment, payment.registeredAt()).claim(now, Duration.ofSeconds(30)).readyToSend(proof, now)
                .complete(new FinanceResult.Success<>(paid(payment, now)), now);
        now = now.plusSeconds(1); return SupplierPayableSettlementCommand.register(UUID.randomUUID(), bank, paid(payment, now), period(payment, now, null), "finance", now);
    }
    private static SupplierPayableSettlementEvidence evidence(SupplierPayableSettlementCommand command, Instant now) {
        return SupplierPayableSettlementEvidence.checked(command, held(command.payment().holdCommand(), now), paid(command.payment(), now), period(command.payment(), now, command.period().request().accountingDate()), now);
    }
    private static SupplierPayableSettlementObservation settled(SupplierPayableSettlementCommand command) {
        var posting = new SupplierPayableSettlementObservation.Posting("settlement-1", command.payment().held().holdReference(), "ledger-2", command.payment().amount(), money("30"), money("100"), "bank-1", "receipt-1", "voucher-1", command.period().periodReference(), command.period().request().accountingDate(), command.registeredAt());
        return new SupplierPayableSettlementObservation(command.id(), command.digest(), SupplierPayableSettlementObservation.Status.SETTLED, 1L, Instant.now(), posting, null);
    }
    private SupplierPaymentCommand payment(Instant now) { return SupplierFinanceProtocolFixture.payment(target, now); }
    private void answer(Object value) { responder.set(request -> json.write(success(request, value))); }
    private ObjectNode success(JsonNode request, Object value) {
        return json.read(json.write(Map.of("contractVersion", 1, "tenantId", "tenant-a", "requestId", request.path("requestId").asText(), "outcome", "SUCCESS", "data", value)), ObjectNode.class);
    }
    private void handle(HttpExchange exchange) throws IOException {
        calls.incrementAndGet(); path.set(exchange.getRequestURI().getPath()); key.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
        credential.set(exchange.getRequestHeaders().getFirst("Authorization"));
        String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8); received.set(request);
        byte[] body = responder.get().apply(json.read(request, JsonNode.class)).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.length);
        try { exchange.getResponseBody().write(body); } finally { exchange.close(); }
    }
    private static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
    private static FinanceResult.Unavailable<?> unavailable(FinanceResult.Failure failure) { return new FinanceResult.Unavailable<>(failure); }
}
