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
 * 回环 HTTP 验证只读复核、固定原还款及资金退回和 ERP 借方依据。
 * @author owlzhangfq@gmail.com
 */
class GatewayAdvanceRepaymentAdjustmentTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule()).registerModule(new FinanceJsonConfiguration().financeMoneyModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final JsonUtil json = new JsonUtil(mapper);
    private final AtomicReference<Function<JsonNode, String>> responder = new AtomicReference<>();
    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>(), idempotency = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final AdvanceRepaymentPort.Request originalRequest = new AdvanceRepaymentPort.Request(UUID.randomUUID(), UUID.randomUUID(), "alice", "paid-original", "CNY", "receipt-original");
    private final AdvanceRepaymentPort.Receipt original = original();
    private final AdvanceRepaymentAdjustmentPort.Request request = new AdvanceRepaymentAdjustmentPort.Request(UUID.randomUUID(), original);
    private HttpServer server;
    private FinanceGatewayConfiguration configuration;
    private FinanceGatewayClient client;
    private GatewayAdvanceRepaymentAdjustment gateway;
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
        client = new FinanceGatewayClient(configuration, mapper); gateway = new GatewayAdvanceRepaymentAdjustment(client);
        target = configuration.destination("tenant-a").orElseThrow().digest("tenant-a"); answer(receipt(AdvanceRepaymentAdjustmentPort.Status.CONFIRMED));
    }
    @AfterEach void stop() { TransactionSynchronizationManager.setActualTransactionActive(false); server.stop(0); }

    @Test
    void confirmedReceiptRetainsBothFactsWithReadOnlyTransportAndDistinctCorrelation() {
        var receipt = receipt(AdvanceRepaymentAdjustmentPort.Status.CONFIRMED); answer(receipt);
        assertThat(gateway.query("tenant-a", target, request).requireValue()).isEqualTo(receipt);
        assertThat(path.get()).isEqualTo("/finance/advance-repayment-adjustment"); assertThat(idempotency.get()).isNull();
        assertThat(json.read(received.get().path("data").toString(), AdvanceRepaymentAdjustmentPort.Request.class)).isEqualTo(request);
        String correlation = received.get().path("requestId").asText(); gateway.query("tenant-a", target, request);
        assertThat(received.get().path("requestId").asText()).isNotEqualTo(correlation); assertThat(calls.get()).isEqualTo(2);
    }
    @Test
    void unresolvedConfirmedAndReturnedRemainExplicitSeparateOutcomes() {
        for (var status : AdvanceRepaymentAdjustmentPort.Status.values()) {
            var receipt = receipt(status); answer(receipt);
            assertThat(gateway.query("tenant-a", target, request).requireValue().status()).isEqualTo(status);
        }
    }
    @Test
    void foreignSourceIncompletePostingAmountMismatchFutureAndExpiredEvidenceAreRejected() {
        List<Consumer<ObjectNode>> changes = List.of(
                v -> ((ObjectNode) v.path("request")).put("repaymentId", UUID.randomUUID().toString()),
                v -> ((ObjectNode) v.path("request").path("original").path("request")).put("employeeId", "bob"),
                v -> v.putNull("current"), v -> v.putNull("posting"), v -> v.putNull("fundsReturn"),
                v -> ((ObjectNode) v.path("posting").path("amount")).put("value", "101.00"),
                v -> v.put("observedAt", Instant.now().plusSeconds(1).toString()),
                v -> v.put("validUntil", Instant.now().minusSeconds(1).toString()),
                v -> v.put("validUntil", Instant.now().plusSeconds(600).toString()),
                v -> v.put("revision", 0), v -> v.put("hiddenOverride", true));
        for (var change : changes) {
            responder.set(input -> { var value = success(input, receipt(AdvanceRepaymentAdjustmentPort.Status.RETURNED)); change.accept((ObjectNode) value.path("data")); return json.write(value); });
            assertThat(gateway.query("tenant-a", target, request)).isEqualTo(new FinanceResult.Unavailable<>(FinanceResult.Failure.INVALID_RESPONSE));
        }
    }
    @Test
    void unconfiguredChangedDestinationAndOpenTransactionsNeverSend() {
        assertThat(gateway.query("tenant-b", target, request)).isEqualTo(new FinanceResult.Unavailable<>(FinanceResult.Failure.NOT_CONFIGURED));
        assertThat(gateway.query("tenant-a", "b".repeat(64), request)).isEqualTo(new FinanceResult.Unavailable<>(FinanceResult.Failure.TARGET_CHANGED));
        assertThatThrownBy(() -> client.read("tenant-a", FinanceGatewayClient.Operation.ADVANCE_REPAYMENT_ADJUSTMENT, request, AdvanceRepaymentAdjustmentPort.Receipt.class, v -> true)).isInstanceOf(IllegalArgumentException.class);
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
    private AdvanceRepaymentPort.Receipt original() {
        var at = Instant.now().minusSeconds(30); var amount = new Money(new BigDecimal("100"), "CNY");
        return new AdvanceRepaymentPort.Receipt(originalRequest, AdvanceRepaymentPort.Status.CONFIRMED, 1, at, at.plusSeconds(120),
                new AdvanceRepaymentPort.Funding(AdvanceRepaymentPort.Channel.PAYROLL, "funds-row", amount, at.minusSeconds(20)),
                new AdvanceRepaymentPort.Posting("voucher", "entry", amount, LocalDate.now(), at.minusSeconds(10)));
    }
    private AdvanceRepaymentAdjustmentPort.Receipt receipt(AdvanceRepaymentAdjustmentPort.Status status) {
        var at = Instant.now().minusSeconds(1); boolean returned = status == AdvanceRepaymentAdjustmentPort.Status.RETURNED;
        var current = new AdvanceRepaymentPort.Receipt(originalRequest, returned ? AdvanceRepaymentPort.Status.REVERSED : AdvanceRepaymentPort.Status.CONFIRMED, 2, at, at.plusSeconds(120), original.funding(), original.posting());
        return new AdvanceRepaymentAdjustmentPort.Receipt(request, status, 3, at, at.plusSeconds(120), current,
                returned ? new AdvanceRepaymentAdjustmentPort.FundsReturn(AdvanceRepaymentPort.Channel.PAYROLL, "return-row", original.funding().amount(), at.minusSeconds(10)) : null,
                returned ? new AdvanceRepaymentAdjustmentPort.ReturnPosting("return-voucher", "debit", original.funding().amount(), LocalDate.now(), at.minusSeconds(5)) : null);
    }
    private void answer(Object value) { responder.set(input -> json.write(success(input, value))); }
    private ObjectNode success(JsonNode input, Object value) { return json.read(json.write(Map.of("contractVersion", 1, "tenantId", "tenant-a", "requestId", input.path("requestId").asText(), "outcome", "SUCCESS", "data", value)), ObjectNode.class); }
}
