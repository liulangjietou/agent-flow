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
import io.agentflow.procurement.SupplierPayableAdjustmentCommand;
import io.agentflow.procurement.SupplierPayableAdjustmentEvidence;
import io.agentflow.procurement.SupplierPayableAdjustmentObservation;
import io.agentflow.procurement.SupplierPayableAdjustmentOperation;
import io.agentflow.procurement.SupplierPayableAdjustmentSource;
import io.agentflow.procurement.SupplierPayableSettlementCommand;
import io.agentflow.procurement.SupplierPayableSettlementObservation;
import io.agentflow.procurement.SupplierPaymentEvidence;
import io.agentflow.procurement.SupplierPaymentOperation;
import io.agentflow.procurement.SupplierPaymentReturnPort;
import io.agentflow.procurement.SupplierPaymentReturns;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
 * 真实回环 HTTP 验证独立回款调整契约、固定命令和未知恢复，不代表企业 ERP 联调完成。
 * @author owlzhangfq@gmail.com
 */
class GatewaySupplierPayableAdjustmentTest {
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
    private GatewaySupplierPayableAdjustment gateway;
    private SupplierPayableAdjustmentCommand command;
    private String target;

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); executor = Executors.newCachedThreadPool();
        server.setExecutor(executor); server.createContext("/finance/", this::handle); server.start();
        configuration = FinanceGatewayConfigurationTest.configured("http://127.0.0.1:" + server.getAddress().getPort() + "/finance", "synthetic-token");
        client = new FinanceGatewayClient(configuration, mapper); gateway = new GatewaySupplierPayableAdjustment(client);
        target = configuration.destination("tenant-a").orElseThrow().digest("tenant-a"); command = command(Instant.now().minusSeconds(120)); answer(adjusted(command));
    }
    @AfterEach void stop() { TransactionSynchronizationManager.setActualTransactionActive(false); server.stop(0); executor.shutdownNow(); }

    @Test void fixedCommandRetainsExactQuantityAndSeparateIdempotencyWhileCredentialsRotate() {
        assertThat(gateway.adjust(command, evidence(command, Instant.now())).requireValue().status()).isEqualTo(SupplierPayableAdjustmentObservation.Status.ADJUSTED);
        String first = received.get(); var request = json.read(first, JsonNode.class);
        assertThat(path.get()).isEqualTo("/finance/supplier-payable-adjustment-command"); assertThat(key.get()).isEqualTo(command.id().toString());
        assertThat(request.path("requestId").asText()).isEqualTo(command.id().toString()); assertThat(request.at("/data/commandDigest").asText()).isEqualTo(command.digest());
        assertThat(first).contains("999999999999999.123456"); assertThat(json.read(request.at("/data/command").toString(), SupplierPayableAdjustmentCommand.class)).isEqualTo(command);
        assertThat(request.at("/data/command/source/returns/request/command/holdCommand/authorization/source/reservation/source/round/content/amount/value").asText()).isEqualTo("70.00");
        configuration.getTenants().get("tenant-a").setToken("rotated-token"); gateway.adjust(command, evidence(command, Instant.now())).requireValue();
        assertThat(received.get()).isEqualTo(first); assertThat(credential.get()).isEqualTo("Bearer rotated-token");
    }

    @Test void queryAfterEvidenceExpiresSendsOnlyOriginalIdentityWithoutWriteHeader() {
        var old = command(Instant.now().minusSeconds(172800)); answer(adjusted(old));
        assertThatThrownBy(() -> gateway.adjust(old, evidence(old, old.registeredAt()))).isInstanceOf(DomainException.class); assertThat(calls.get()).isZero();
        assertThat(gateway.query(old).requireValue().status()).isEqualTo(SupplierPayableAdjustmentObservation.Status.ADJUSTED);
        var first = json.read(received.get(), JsonNode.class); assertThat(first.path("data").size()).isEqualTo(2);
        assertThat(first.at("/data/operationId").asText()).isEqualTo(old.id().toString()); assertThat(first.at("/data/commandDigest").asText()).isEqualTo(old.digest());
        assertThat(key.get()).isNull(); assertThat(path.get()).isEqualTo("/finance/supplier-payable-adjustment-query");
        gateway.query(old).requireValue(); assertThat(json.read(received.get(), JsonNode.class).path("requestId").asText()).isNotEqualTo(first.path("requestId").asText());
        var absent = new SupplierPayableAdjustmentObservation(command.id(), command.digest(), SupplierPayableAdjustmentObservation.Status.NOT_FOUND, 0, Instant.now(), null, null);
        answer(absent); assertThat(gateway.adjust(command, evidence(command, Instant.now()))).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE)); assertThat(gateway.query(command).requireValue()).isEqualTo(absent);
        responder.set(request -> { var body = success(request, absent); ((ObjectNode) body.path("data")).remove("revision"); return json.write(body); });
        assertThat(gateway.query(command)).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
    }

    @Test void disabledChangedTargetTransactionsAndGenericReadCannotBypassAdjustmentContract() {
        var proof = evidence(command, Instant.now()); configuration.setEnabled(false);
        assertThat(gateway.adjust(command, proof)).isEqualTo(unavailable(FinanceResult.Failure.NOT_CONFIGURED));
        configuration.setEnabled(true); configuration.getTenants().get("tenant-a").setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/other");
        assertThat(gateway.adjust(command, proof)).isEqualTo(unavailable(FinanceResult.Failure.TARGET_CHANGED)); assertThat(gateway.query(command)).isEqualTo(unavailable(FinanceResult.Failure.TARGET_CHANGED));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> gateway.adjust(command, proof)).hasMessage("Finance gateway must run outside a transaction");
        assertThatThrownBy(() -> gateway.query(command)).hasMessage("Finance gateway must run outside a transaction"); TransactionSynchronizationManager.setActualTransactionActive(false);
        for (var operation : List.of(FinanceGatewayClient.Operation.SUPPLIER_PAYABLE_ADJUSTMENT_COMMAND, FinanceGatewayClient.Operation.SUPPLIER_PAYABLE_ADJUSTMENT_QUERY)) {
            assertThatThrownBy(() -> client.read("tenant-a", operation, command, SupplierPayableAdjustmentObservation.class, value -> true)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(calls.get()).isZero();
    }

    @Test void coercedOrUnrelatedBankAndAccountingFactsCannotRestorePayable() {
        List<Consumer<ObjectNode>> corruptions = List.of(
                data -> data.put("operationId", UUID.randomUUID().toString()), data -> data.put("commandDigest", "b".repeat(64)),
                data -> data.put("revision", "1"), data -> data.remove("revision"), data -> data.put("revision", 0), data -> data.put("unknown", true),
                data -> data.put("observedAt", Instant.now().plusSeconds(30).toString()), data -> data.put("status", "PENDING"),
                data -> ((ObjectNode) data.path("posting")).remove("recognitionVoucherReference"), data -> ((ObjectNode) data.path("posting")).put("holdReference", "other-hold"),
                data -> ((ObjectNode) data.at("/posting/entries/0")).put("transactionReference", "other-bank"),
                data -> ((ObjectNode) data.path("posting")).put("recognitionVoucherReference", "other-original-voucher"),
                data -> ((ObjectNode) data.path("posting")).put("periodReference", "other-period"), data -> ((ObjectNode) data.path("posting")).put("adjustedAt", command.registeredAt().minusNanos(1).toString()),
                data -> ((ObjectNode) data.at("/posting/payableSettledAfter")).put("value", "79.99"), data -> ((ObjectNode) data.at("/posting/returnedAmount")).put("value", 20),
                data -> ((ObjectNode) data.path("posting")).put("accountingDate", command.period().request().accountingDate().plusDays(1).toString()));
        for (var corrupt : corruptions) {
            responder.set(request -> { var body = success(request, adjusted(command)); corrupt.accept((ObjectNode) body.path("data")); return json.write(body); });
            assertThat(gateway.adjust(command, evidence(command, Instant.now()))).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
        }
        var rejected = new SupplierPayableAdjustmentObservation(command.id(), command.digest(), SupplierPayableAdjustmentObservation.Status.REJECTED, 1, Instant.now(), null, SupplierPayableAdjustmentObservation.Rejection.ACCOUNTING_PERIOD_CLOSED);
        answer(rejected); assertThat(gateway.adjust(command, evidence(command, Instant.now())).requireValue()).isEqualTo(rejected);
    }

    @Test void timedOutAdjustmentUsesOneWriteAndRecoversOriginalIdentity() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        responder.set(request -> { entered.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); } return json.write(success(request, adjusted(command))); });
        configuration.getTenants().get("tenant-a").setTimeoutSeconds(1);
        try {
            assertThat(gateway.adjust(command, evidence(command, Instant.now()))).isEqualTo(unavailable(FinanceResult.Failure.TIMEOUT));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue(); assertThat(calls.get()).isEqualTo(1);
            answer(adjusted(command)); assertThat(gateway.query(command).requireValue().status()).isEqualTo(SupplierPayableAdjustmentObservation.Status.ADJUSTED);
            assertThat(calls.get()).isEqualTo(2); assertThat(key.get()).isNull(); assertThat(path.get()).isEqualTo("/finance/supplier-payable-adjustment-query");
        } finally { release.countDown(); }
    }

    @Test void originalAndSubsequentSourcesAndEveryRecoveryStateRoundTripUsingActualJson() {
        var now = Instant.now().minusSeconds(1); var queue = SupplierPayableAdjustmentOperation.queue(command, command.registeredAt());
        var checking = queue.claim(now, Duration.ofSeconds(30)); var proof = evidence(command, now); var sending = checking.readyToSend(proof, now);
        var unknown = sending.unavailable(SupplierPayableAdjustmentOperation.Failure.CONNECTION, now);
        var done = sending.complete(new FinanceResult.Success<>(adjusted(command)), Instant.now());
        for (var value : List.of(queue, checking, sending, unknown, done)) assertThat(json.read(json.write(value), SupplierPayableAdjustmentOperation.class)).isEqualTo(value);
        assertThat(json.read(json.write(proof), SupplierPayableAdjustmentEvidence.class)).isEqualTo(proof);
        var unaccounted = new SupplierPayableAdjustmentCommand(UUID.randomUUID(), new SupplierPayableAdjustmentSource(command.source().returns(), null, null), command.period(), "finance", command.registeredAt());
        assertThat(json.read(json.write(unaccounted), SupplierPayableAdjustmentCommand.class)).isEqualTo(unaccounted);
        var ledger = command.source().returns(); var payment = ledger.request().command();
        var previous = new SupplierPayableAdjustmentSource.Previous(payment.id(), payment.digest(), command.source().settlement().command().id(), done.version(), ledger.entries(), done.observation());
        var entries = new ArrayList<>(ledger.entries()); entries.add(new SupplierPaymentReturns.Entry(UUID.randomUUID(), new SupplierPaymentReturnPort.BankReceipt("return-2", payment.debitAccount().reference(), money("10"), Instant.now())));
        var at = Instant.now(); var updated = new SupplierPaymentReturns(ledger.request(), 4, entries, true, ledger.createdAt(), at, new SupplierPaymentReturns.Accounting(command.id(), done.version(), ledger.entries().size(), done.updatedAt()));
        var next = new SupplierPayableAdjustmentCommand(UUID.randomUUID(), new SupplierPayableAdjustmentSource(updated, command.source().settlement(), previous), period(payment, at, null), "finance-2", at);
        assertThat(json.read(json.write(next), SupplierPayableAdjustmentCommand.class)).isEqualTo(next);
        assertThat(json.read(json.write(next), SupplierPayableAdjustmentCommand.class).digest()).isEqualTo(next.digest());
    }

    private SupplierPayableAdjustmentCommand command(Instant start) {
        var payment = SupplierFinanceProtocolFixture.payment(target, start); var paidAt = start.plusSeconds(7);
        var proof = SupplierPaymentEvidence.checked(payment, directory(payment.holdCommand(), paidAt), payment.holdCommand().authorization().payable(), held(payment.holdCommand(), paidAt), paidAt);
        var bank = SupplierPaymentOperation.queue(payment, payment.registeredAt()).claim(paidAt, Duration.ofSeconds(30)).readyToSend(proof, paidAt).complete(new FinanceResult.Success<>(paid(payment, paidAt)), paidAt);
        var original = SupplierPayableSettlementCommand.register(UUID.randomUUID(), bank, paid(payment, start.plusSeconds(8)), period(payment, start.plusSeconds(8), null), "finance", start.plusSeconds(8));
        var posting = new SupplierPayableSettlementObservation.Posting("settlement-1", payment.held().holdReference(), "ledger-2", payment.amount(), money("30"), money("100"), "bank-1", "receipt-1", "voucher-1", original.period().periodReference(), original.period().request().accountingDate(), original.registeredAt());
        var settled = new SupplierPayableSettlementObservation(original.id(), original.digest(), SupplierPayableSettlementObservation.Status.SETTLED, 1L, start.plusSeconds(9), posting, null);
        var request = new SupplierPaymentReturnPort.Request(payment, bank.observation());
        var entry = new SupplierPaymentReturns.Entry(UUID.randomUUID(), new SupplierPaymentReturnPort.BankReceipt("return-1", payment.debitAccount().reference(), money("20"), start.plusSeconds(11)));
        var ledger = new SupplierPaymentReturns(request, 3, List.of(entry), true, start.plusSeconds(10), start.plusSeconds(12));
        var at = start.plusSeconds(13);
        return new SupplierPayableAdjustmentCommand(UUID.randomUUID(), new SupplierPayableAdjustmentSource(ledger, new SupplierPayableAdjustmentSource.OriginalSettlement(4, original, settled), null), period(payment, at, null), "finance", at);
    }
    private static SupplierPayableAdjustmentEvidence evidence(SupplierPayableAdjustmentCommand command, Instant now) {
        var ledger = command.source().returns(); var payment = ledger.request().command();
        var bank = new SupplierPaymentReturnPort.Receipt(ledger.request(), SupplierPaymentReturnPort.Status.PARTIALLY_RETURNED, 1, now, now.plusSeconds(300), paid(payment, now), ledger.entries().stream().map(SupplierPaymentReturns.Entry::proof).toList());
        var original = command.source().settlement().observation();
        var settlement = new SupplierPayableSettlementObservation(original.operationId(), original.commandDigest(), original.status(), original.revision(), now, original.posting(), null);
        return SupplierPayableAdjustmentEvidence.checked(command, bank, null, settlement, null, period(payment, now, command.period().request().accountingDate()), now);
    }
    private static SupplierPayableAdjustmentObservation adjusted(SupplierPayableAdjustmentCommand command) {
        var source = command.source(); var posting = new SupplierPayableAdjustmentObservation.Posting("adjustment-1", source.returns().request().command().held().holdReference(), "ledger-3", "voucher-1", money("20"), money("20"), money("50"), money("100"), money("80"),
                List.of(new SupplierPayableAdjustmentObservation.ReturnEntry("return-1", money("20"), "return-voucher-1", "entry-1")), command.period().periodReference(), command.period().request().accountingDate(), command.registeredAt());
        return new SupplierPayableAdjustmentObservation(command.id(), command.digest(), SupplierPayableAdjustmentObservation.Status.ADJUSTED, 1, Instant.now(), posting, null);
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
