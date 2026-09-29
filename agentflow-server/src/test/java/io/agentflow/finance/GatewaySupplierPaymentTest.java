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
import io.agentflow.expense.InvoiceKey;
import io.agentflow.organization.InitiatorContext;
import io.agentflow.procurement.ApprovedProcurementPayment;
import io.agentflow.procurement.ProcurementPayablePort;
import io.agentflow.procurement.ProcurementPayableReservation;
import io.agentflow.procurement.ProcurementPaymentContent;
import io.agentflow.procurement.ProcurementPaymentRequest;
import io.agentflow.procurement.SupplierAccountSnapshot;
import io.agentflow.procurement.SupplierPayableHoldCommand;
import io.agentflow.procurement.SupplierPayableHoldObservation;
import io.agentflow.procurement.SupplierPayableHoldOperation;
import io.agentflow.procurement.SupplierPaymentAuthorization;
import io.agentflow.procurement.SupplierPaymentCommand;
import io.agentflow.procurement.SupplierPaymentEvidence;
import io.agentflow.procurement.SupplierPaymentOperation;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import static org.assertj.core.api.Assertions.*;

/**
 * 真实回环 HTTP 检查供应商银行命令与原交易查询，此测试不表示真实银行已接入。
 * @author owlzhangfq@gmail.com
 */
class GatewaySupplierPaymentTest {
    private static final PaymentAccountsPort.DebitAccount DEBIT = new PaymentAccountsPort.DebitAccount("debit-1", "法人基本户", "****5678", "CNY", "v1");
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .registerModule(new FinanceJsonConfiguration().financeMoneyModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private final JsonUtil json = new JsonUtil(mapper);
    private final AtomicReference<Function<JsonNode, String>> responder = new AtomicReference<>();
    private final AtomicReference<String> received = new AtomicReference<>(), key = new AtomicReference<>(), path = new AtomicReference<>(), credential = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private HttpServer server;
    private ExecutorService executor;
    private FinanceGatewayConfiguration configuration;
    private FinanceGatewayClient client;
    private GatewaySupplierPayment gateway;
    private SupplierPaymentCommand command;
    private String target;

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); executor = Executors.newCachedThreadPool();
        server.setExecutor(executor); server.createContext("/finance/", this::handle); server.start();
        configuration = FinanceGatewayConfigurationTest.configured("http://127.0.0.1:" + server.getAddress().getPort() + "/finance", "synthetic-token");
        client = new FinanceGatewayClient(configuration, mapper); gateway = new GatewaySupplierPayment(client);
        target = configuration.destination("tenant-a").orElseThrow().digest("tenant-a");
        command = command(Instant.now().minusSeconds(120)); answer(paid(command));
    }

    @AfterEach void stop() { TransactionSynchronizationManager.setActualTransactionActive(false); server.stop(0); executor.shutdownNow(); }

    @Test void repeatedWriteKeepsOriginalIdentityExactBytesAndPrecisionEvenWhenCredentialsRotate() {
        assertThat(gateway.execute(command).requireValue().status()).isEqualTo(PaymentObservation.Status.SUCCEEDED);
        String original = received.get(); var request = json.read(original, JsonNode.class);
        assertThat(path.get()).isEqualTo("/finance/supplier-payment-command"); assertThat(key.get()).isEqualTo(command.id().toString());
        assertThat(request.path("requestId").asText()).isEqualTo(command.id().toString());
        assertThat(request.at("/data/commandDigest").asText()).isEqualTo(command.digest());
        assertThat(request.at("/data/command/holdCommand/authorization/source/reservation/source/round/content/amount/value").asText()).isEqualTo("70.00");
        assertThat(original).contains("999999999999999.123456");
        assertThat(json.read(request.at("/data/command").toString(), SupplierPaymentCommand.class)).isEqualTo(command);
        assertThat(credential.get()).isEqualTo("Bearer synthetic-token");
        configuration.getTenants().get("tenant-a").setToken("rotated-token"); gateway.execute(command).requireValue();
        assertThat(received.get()).isEqualTo(original); assertThat(credential.get()).isEqualTo("Bearer rotated-token"); assertThat(calls.get()).isEqualTo(2);
    }

    @Test void queryAfterAuthorizationExpiryCarriesOnlyOriginalIdentityAndFreshTransportCorrelation() {
        var expired = command(Instant.now().minusSeconds(172800)); answer(paid(expired));
        assertThatThrownBy(() -> gateway.execute(expired)).isInstanceOf(DomainException.class); assertThat(calls.get()).isZero();
        assertThat(gateway.query(expired).requireValue().status()).isEqualTo(PaymentObservation.Status.SUCCEEDED);
        assertThat(path.get()).isEqualTo("/finance/supplier-payment-query"); assertThat(key.get()).isNull();
        var first = json.read(received.get(), JsonNode.class);
        assertThat(first.path("data").size()).isEqualTo(2); assertThat(first.at("/data/authorizationId").asText()).isEqualTo(expired.id().toString());
        assertThat(first.at("/data/commandDigest").asText()).isEqualTo(expired.digest());
        gateway.query(expired).requireValue();
        assertThat(json.read(received.get(), JsonNode.class).path("requestId").asText()).isNotEqualTo(first.path("requestId").asText());
        var absent = new PaymentObservation(command.id(), command.digest(), PaymentObservation.Status.NOT_FOUND, 0L, Instant.now(), null, null, null, null, null, null);
        answer(absent); assertThat(gateway.execute(command)).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
        assertThat(gateway.query(command).requireValue()).isEqualTo(absent);
    }

    @Test void unavailableConfigurationChangedTargetTransactionAndGenericReadCannotBypassIdentity() {
        configuration.setEnabled(false); assertThat(gateway.execute(command)).isEqualTo(unavailable(FinanceResult.Failure.NOT_CONFIGURED));
        configuration.setEnabled(true); configuration.getTenants().get("tenant-a").setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/other");
        assertThat(gateway.execute(command)).isEqualTo(unavailable(FinanceResult.Failure.TARGET_CHANGED));
        assertThat(gateway.query(command)).isEqualTo(unavailable(FinanceResult.Failure.TARGET_CHANGED));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> gateway.execute(command)).hasMessage("Finance gateway must run outside a transaction");
        assertThatThrownBy(() -> gateway.query(command)).hasMessage("Finance gateway must run outside a transaction");
        TransactionSynchronizationManager.setActualTransactionActive(false);
        for (var operation : List.of(FinanceGatewayClient.Operation.SUPPLIER_PAYMENT_COMMAND, FinanceGatewayClient.Operation.SUPPLIER_PAYMENT_QUERY)) {
            assertThatThrownBy(() -> client.read("tenant-a", operation, command, PaymentObservation.class, value -> true)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(calls.get()).isZero();
    }

    @Test void malformedAndMismatchedReceiptsCannotDeclarePaymentAndGenericRejectionIsNotBankFailure() {
        List<Consumer<ObjectNode>> corruptions = List.of(
                data -> data.put("authorizationId", UUID.randomUUID().toString()), data -> data.put("commandDigest", "c".repeat(64)),
                data -> data.put("accountDigest", "b".repeat(64)), data -> ((ObjectNode) data.path("paidAmount")).put("value", "69.99"),
                data -> ((ObjectNode) data.path("paidAmount")).put("currency", "USD"), data -> ((ObjectNode) data.path("paidAmount")).put("value", 70),
                data -> data.put("revision", "1"), data -> data.remove("revision"), data -> data.put("revision", 0),
                data -> data.put("observedAt", Instant.now().plusSeconds(60).toString()), data -> data.put("completedAt", command.registeredAt().minusNanos(1).toString()),
                data -> data.remove("receiptReference"), data -> data.remove("paymentReference"), data -> data.put("status", "PENDING"),
                data -> data.put("failure", "PAYMENT_REJECTED"), data -> data.put("unknown", true));
        for (var corrupt : corruptions) {
            responder.set(request -> { var body = success(request, paid(command)); corrupt.accept((ObjectNode) body.path("data")); return json.write(body); });
            assertThat(gateway.execute(command)).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
        }
        responder.set(request -> json.write(Map.of("contractVersion", 1, "tenantId", "tenant-a", "requestId", request.path("requestId").asText(), "outcome", "REJECTED", "reason", "ACCOUNT_UNAVAILABLE")));
        assertThat(gateway.execute(command)).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
        var rejected = new PaymentObservation(command.id(), command.digest(), PaymentObservation.Status.FAILED, 1L, Instant.now(), "bank-failed-1", null, null, null, null, PaymentObservation.Failure.INSUFFICIENT_FUNDS);
        answer(rejected); assertThat(gateway.execute(command).requireValue()).isEqualTo(rejected);
    }

    @Test void timeoutMakesOneWriteThenRecoversUsingOnlyTheOriginalQuery() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        responder.set(request -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return json.write(success(request, paid(command)));
        });
        configuration.getTenants().get("tenant-a").setTimeoutSeconds(1);
        try {
            assertThat(gateway.execute(command)).isEqualTo(unavailable(FinanceResult.Failure.TIMEOUT));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue(); assertThat(calls.get()).isEqualTo(1);
            answer(paid(command)); assertThat(gateway.query(command).requireValue().status()).isEqualTo(PaymentObservation.Status.SUCCEEDED);
            assertThat(calls.get()).isEqualTo(2); assertThat(key.get()).isNull(); assertThat(path.get()).isEqualTo("/finance/supplier-payment-query");
        } finally { release.countDown(); }
    }

    @Test void persistedQueueEvidenceAndUnknownStateRoundTripWithApplicationJsonShape() {
        var now = Instant.now().minusSeconds(2); var queued = SupplierPaymentOperation.queue(command, command.registeredAt());
        var checking = queued.claim(now.minusSeconds(1), Duration.ofSeconds(30));
        var evidence = SupplierPaymentEvidence.checked(command, directory(command.holdCommand(), now), command.holdCommand().authorization().payable(), held(command.holdCommand(), now), now);
        var sending = checking.readyToSend(evidence, now); var unknown = sending.unavailable(SupplierPaymentOperation.Failure.TIMEOUT, now);
        for (var value : List.of(queued, checking, sending, unknown)) {
            assertThat(json.read(json.write(value), SupplierPaymentOperation.class)).isEqualTo(value);
        }
        assertThat(json.read(json.write(evidence), SupplierPaymentEvidence.class)).isEqualTo(evidence);
    }

    private SupplierPaymentCommand command(Instant now) {
        var entity = UUID.randomUUID(); var content = new ProcurementPaymentContent(entity, "采购付款", "已验收货物付款", "supplier-1", "payable-1", money("70"));
        var quantity = new BigDecimal("999999999999999.123456");
        var line = new ProcurementPayablePort.MatchedLine(1, 1, "receipt-1", new InvoiceKey(InvoiceKey.Type.DIGITAL, null, "00000000000000000001"), 1,
                "c".repeat(64), "verification-1", "件", quantity, quantity, quantity, money("100"), money("100"), money("100"), money("6"));
        var payable = new ProcurementPayablePort.Payable(content.payableRequest("alice"), "v1", now, now.plusSeconds(600), "供应商",
                new SupplierAccountSnapshot(entity, "supplier-1", "private-supplier-account", "****1234", "a".repeat(64), "v1"),
                "contract-1", "order-1", "match-1", "accrual-1", "budget-1", LocalDate.parse("2026-10-01"), money("100"), money("30"), List.of(line));
        var request = ProcurementPaymentRequest.draft(UUID.randomUUID(), "tenant-a", UUID.randomUUID(), "alice", content);
        var catalog = new FinanceCatalog("alice", "v1", now.plusSeconds(600), List.of(new FinanceCatalog.LegalEntity(entity, "法人", "CNY", false, "v1", "Asia/Shanghai")), List.of(), List.of(), List.of(), List.of());
        request.freeze(1, 1, catalog, target, payable, new InitiatorContext(UUID.randomUUID(), UUID.randomUUID(), "alice", 1, entity, "法人", UUID.randomUUID(), "部门", UUID.randomUUID(), "岗位"), now);
        var local = ProcurementPayableReservation.hold(UUID.randomUUID(), request, now); request.approve(2, 1, 8, "manager", now.plusSeconds(1));
        var holdCommand = new SupplierPayableHoldCommand(new SupplierPaymentAuthorization(UUID.randomUUID(), ApprovedProcurementPayment.from(request, local), payable, "finance", now.plusSeconds(2), now.plusSeconds(86400)));
        var original = SupplierPayableHoldOperation.queue(holdCommand, now.plusSeconds(2)).claim(now.plusSeconds(2), Duration.ofSeconds(30))
                .complete(new FinanceResult.Success<>(held(holdCommand, now.plusSeconds(4))), now.plusSeconds(4));
        return SupplierPaymentCommand.register(original, held(holdCommand, now.plusSeconds(5)), directory(holdCommand, now.plusSeconds(5)), DEBIT.reference(), "cashier", now.plusSeconds(5));
    }
    private static PaymentAccountsPort.Directory directory(SupplierPayableHoldCommand command, Instant now) {
        return new PaymentAccountsPort.Directory(new PaymentAccountsPort.Request(command.authorization().payable().request().legalEntityId(), "CNY", "cashier"), "directory-v1", now, now.plusSeconds(600), List.of(DEBIT));
    }
    private static SupplierPayableHoldObservation held(SupplierPayableHoldCommand command, Instant now) {
        return new SupplierPayableHoldObservation(command.id(), command.digest(), SupplierPayableHoldObservation.Status.HELD, 1L, now,
                "hold-1", "ledger-1", command.authorization().source().amount(), command.authorization().payable().account().accountDigest(), command.authorization().authorizedAt().plusSeconds(1), null);
    }
    private static PaymentObservation paid(SupplierPaymentCommand command) {
        return new PaymentObservation(command.id(), command.digest(), PaymentObservation.Status.SUCCEEDED, 1L, Instant.now(), "bank-1", command.amount(), command.payee().accountDigest(), command.registeredAt().plusSeconds(1), "receipt-1", null);
    }
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
