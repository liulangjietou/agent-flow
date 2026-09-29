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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.*;

/**
 * 回环 HTTP 验证固定原成功放款、银行退回和 ERP 借款贷方依据。
 * @author owlzhangfq@gmail.com
 */
class GatewayAdvanceDisbursementReturnTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule()).registerModule(new FinanceJsonConfiguration().financeMoneyModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final JsonUtil json = new JsonUtil(mapper);
    private final AtomicReference<Function<JsonNode, String>> responder = new AtomicReference<>();
    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>(), idempotency = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final Instant paidAt = Instant.now().minusSeconds(40);
    private final PaymentCommand command = new PaymentCommand(UUID.randomUUID(), "tenant-a", PaymentCommand.Purpose.EMPLOYEE_ADVANCE,
            new PaymentCommand.Binding(UUID.randomUUID(), UUID.randomUUID(), 1, 3, 2), new Money(new BigDecimal("100"), "CNY"), "company-bank",
            new EmployeeAccountSnapshot(UUID.randomUUID(), "alice", "account", "****1234", "a".repeat(64), "v1"), "original-accrual",
            new PaymentCommand.Authorization("finance", "cashier", paidAt.minusSeconds(10), paidAt.plusSeconds(600)));
    private final PaymentObservation original = observation(PaymentObservation.Status.SUCCEEDED, 1, paidAt.plusSeconds(5));
    private final AdvanceDisbursementReturnPort.Request request = new AdvanceDisbursementReturnPort.Request(command, original);
    private HttpServer server;
    private FinanceGatewayConfiguration configuration;
    private FinanceGatewayClient client;
    private GatewayAdvanceDisbursementReturn gateway;
    private String target;
    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/finance/", exchange -> {
            calls.incrementAndGet(); path.set(exchange.getRequestURI().getPath()); idempotency.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            var body = json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonNode.class); received.set(body);
            byte[] answer = responder.get().apply(body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, answer.length);
            try { exchange.getResponseBody().write(answer); } finally { exchange.close(); }
        });
        server.start(); configuration = FinanceGatewayConfigurationTest.configured("http://127.0.0.1:" + server.getAddress().getPort() + "/finance", "synthetic-token");
        client = new FinanceGatewayClient(configuration, mapper); gateway = new GatewayAdvanceDisbursementReturn(client);
        target = configuration.destination("tenant-a").orElseThrow().digest("tenant-a"); answer(receipt(AdvanceDisbursementReturnPort.Status.CONFIRMED));
    }
    @AfterEach void stop() { TransactionSynchronizationManager.setActualTransactionActive(false); server.stop(0); }

    @Test
    void confirmedReceiptRetainsBothFactsWithReadOnlyTransportAndDistinctCorrelation() {
        var receipt = receipt(AdvanceDisbursementReturnPort.Status.CONFIRMED); answer(receipt);
        assertThat(gateway.query("tenant-a", target, request).requireValue()).isEqualTo(receipt);
        assertThat(path.get()).isEqualTo("/finance/advance-disbursement-return"); assertThat(idempotency.get()).isNull();
        assertThat(json.read(received.get().path("data").toString(), AdvanceDisbursementReturnPort.Request.class)).isEqualTo(request);
        String correlation = received.get().path("requestId").asText(); gateway.query("tenant-a", target, request);
        assertThat(received.get().path("requestId").asText()).isNotEqualTo(correlation); assertThat(calls.get()).isEqualTo(2);
    }
    @Test
    void unresolvedConfirmedAndReturnedRemainExplicitSeparateOutcomes() {
        for (var status : AdvanceDisbursementReturnPort.Status.values()) {
            var receipt = receipt(status); answer(receipt);
            assertThat(gateway.query("tenant-a", target, request).requireValue().status()).isEqualTo(status);
        }
    }
    @Test
    void foreignSourceIncompletePostingAmountMismatchFutureAndExpiredEvidenceAreRejected() {
        List<Consumer<ObjectNode>> changes = List.of(
                v -> ((ObjectNode) v.path("request").path("command")).put("id", UUID.randomUUID().toString()),
                v -> ((ObjectNode) v.path("request").path("command").path("payee")).put("employeeId", "bob"),
                v -> v.putNull("current"), v -> v.putNull("returns"),
                v -> ((ObjectNode) v.path("returns").get(0)).putNull("posting"),
                v -> ((ObjectNode) v.path("returns").get(0).path("posting").path("amount")).put("value", "101.00"),
                v -> ((ObjectNode) v.path("returns").get(0).path("funding")).put("channel", "CASH"),
                v -> ((com.fasterxml.jackson.databind.node.ArrayNode) v.path("returns")).add(v.path("returns").get(0).deepCopy()),
                v -> ((ObjectNode) v.path("current")).put("accountDigest", "b".repeat(64)),
                v -> v.put("observedAt", Instant.now().plusSeconds(1).toString()),
                v -> v.put("validUntil", Instant.now().minusSeconds(1).toString()),
                v -> v.put("validUntil", Instant.now().plusSeconds(600).toString()),
                v -> v.put("revision", 0), v -> v.put("hiddenOverride", true));
        for (var change : changes) {
            responder.set(input -> { var value = success(input, receipt(AdvanceDisbursementReturnPort.Status.RETURNED)); change.accept((ObjectNode) value.path("data")); return json.write(value); });
            assertThat(gateway.query("tenant-a", target, request)).isEqualTo(new FinanceResult.Unavailable<>(FinanceResult.Failure.INVALID_RESPONSE));
        }
    }
    @Test
    void unconfiguredChangedDestinationAndOpenTransactionsNeverSend() {
        assertThat(gateway.query("tenant-b", target, request)).isEqualTo(new FinanceResult.Unavailable<>(FinanceResult.Failure.NOT_CONFIGURED));
        assertThat(gateway.query("tenant-a", "b".repeat(64), request)).isEqualTo(new FinanceResult.Unavailable<>(FinanceResult.Failure.TARGET_CHANGED));
        assertThatThrownBy(() -> client.read("tenant-a", FinanceGatewayClient.Operation.ADVANCE_DISBURSEMENT_RETURN, request, AdvanceDisbursementReturnPort.Receipt.class, v -> true)).isInstanceOf(IllegalArgumentException.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> gateway.query("tenant-a", target, request)).isInstanceOf(IllegalStateException.class); assertThat(calls.get()).isZero();
    }
    @Test
    void onlyAllowedBusinessRejectionsAreAcceptedAndNoRemoteBodyIsExposed() {
        for (String reason : List.of("LEGAL_ENTITY_UNAVAILABLE", "EMPLOYEE_UNAVAILABLE", "INVOICE_INVALID", "REMOTE_PRIVATE_ERROR")) {
            responder.set(input -> json.write(Map.of("contractVersion", 1, "tenantId", "tenant-a", "requestId", input.path("requestId").asText(), "outcome", "REJECTED", "reason", reason)));
            var result = gateway.query("tenant-a", target, request);
            if (reason.endsWith("UNAVAILABLE")) assertThat(result).isEqualTo(new FinanceResult.Rejected<>(FinanceResult.Reason.valueOf(reason)));
            else assertThat(result).isEqualTo(new FinanceResult.Unavailable<>(FinanceResult.Failure.INVALID_RESPONSE));
        }
    }
    private PaymentObservation observation(PaymentObservation.Status status, long revision, Instant at) {
        return new PaymentObservation(command.id(), command.digest(), status, revision, at, "original-payment", command.amount(), command.payee().accountDigest(), paidAt, "original-bank-receipt", null);
    }
    private AdvanceDisbursementReturnPort.Receipt receipt(AdvanceDisbursementReturnPort.Status status) {
        var at = Instant.now().minusMillis(10); boolean returned = status == AdvanceDisbursementReturnPort.Status.RETURNED || status == AdvanceDisbursementReturnPort.Status.PARTIALLY_RETURNED;
        var amount = status == AdvanceDisbursementReturnPort.Status.PARTIALLY_RETURNED ? new Money(new BigDecimal("1.00"), "CNY") : command.amount();
        var current = observation(status == AdvanceDisbursementReturnPort.Status.RETURNED ? PaymentObservation.Status.REVERSED : PaymentObservation.Status.SUCCEEDED, 2, at);
        var proofs = returned ? List.of(new AdvanceDisbursementReturnPort.ReturnItem(
                new AdvanceRepaymentPort.Funding(AdvanceRepaymentPort.Channel.BANK_TRANSFER, "return-row", amount, at.minusSeconds(10)),
                new AdvanceRepaymentPort.Posting("return-voucher", "credit", amount, LocalDate.now(), at.minusSeconds(5)))) : List.<AdvanceDisbursementReturnPort.ReturnItem>of();
        return new AdvanceDisbursementReturnPort.Receipt(request, status, 3, at, at.plusSeconds(120), current, proofs);
    }
    private void answer(Object value) { responder.set(input -> json.write(success(input, value))); }
    private ObjectNode success(JsonNode input, Object value) { return json.read(json.write(Map.of("contractVersion", 1, "tenantId", "tenant-a", "requestId", input.path("requestId").asText(), "outcome", "SUCCESS", "data", value)), ObjectNode.class); }
}
