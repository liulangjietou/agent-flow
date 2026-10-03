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
 * 回环 HTTP 验证固定原凭证、独立反向分录及严格只读传输边界。
 * @author owlzhangfq@gmail.com
 */
class GatewayVoucherReversalTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule()).registerModule(new FinanceJsonConfiguration().financeMoneyModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final JsonUtil json = new JsonUtil(mapper);
    private final AtomicReference<Function<JsonNode, String>> responder = new AtomicReference<>();
    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>(), idempotency = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final Instant createdAt = Instant.now().minusSeconds(40);
    private final VoucherCommand command = command();
    private final VoucherObservation original = observation(VoucherObservation.Status.POSTED, 1, createdAt.plusSeconds(1));
    private final VoucherReversalPort.Request request = new VoucherReversalPort.Request(command, original);
    private HttpServer server;
    private FinanceGatewayConfiguration configuration;
    private FinanceGatewayClient client;
    private GatewayVoucherReversal gateway;
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
        client = new FinanceGatewayClient(configuration, mapper); gateway = new GatewayVoucherReversal(client);
        target = configuration.destination("tenant-a").orElseThrow().digest("tenant-a"); answer(receipt(VoucherReversalPort.Status.VERIFIED));
    }
    @AfterEach void stop() { TransactionSynchronizationManager.setActualTransactionActive(false); server.stop(0); }

    @Test
    void confirmedReceiptRetainsBothFactsWithReadOnlyTransportAndDistinctCorrelation() {
        var receipt = receipt(VoucherReversalPort.Status.VERIFIED); answer(receipt);
        assertThat(gateway.query("tenant-a", target, request).requireValue()).isEqualTo(receipt);
        assertThat(path.get()).isEqualTo("/finance/voucher-reversal"); assertThat(idempotency.get()).isNull();
        assertThat(json.read(received.get().path("data").toString(), VoucherReversalPort.Request.class)).isEqualTo(request);
        String correlation = received.get().path("requestId").asText(); gateway.query("tenant-a", target, request);
        assertThat(received.get().path("requestId").asText()).isNotEqualTo(correlation); assertThat(calls.get()).isEqualTo(2);
    }
    @Test
    void unresolvedAndVerifiedRemainExplicitSeparateOutcomes() {
        for (var status : VoucherReversalPort.Status.values()) {
            var receipt = receipt(status); answer(receipt);
            assertThat(gateway.query("tenant-a", target, request).requireValue().status()).isEqualTo(status);
        }
    }
    @Test
    void foreignSourceIncompletePostingAmountMismatchFutureAndExpiredEvidenceAreRejected() {
        List<Consumer<ObjectNode>> changes = List.of(
                v -> ((ObjectNode) v.path("request").path("command")).put("id", UUID.randomUUID().toString()),
                v -> v.putNull("current"), v -> v.putNull("reversal"),
                v -> ((ObjectNode) v.path("reversal")).put("voucherReference", original.voucherReference()),
                v -> ((ObjectNode) v.path("reversal").path("lines").get(0)).put("accountCode", "other-account"),
                v -> ((ObjectNode) v.path("reversal").path("lines").get(0)).put("side", "DEBIT"),
                v -> ((ObjectNode) v.path("reversal").path("lines").get(0).path("amount")).put("value", "101.00"),
                v -> ((ObjectNode) v.path("reversal").path("lines").get(0)).put("advanceId", UUID.randomUUID().toString()),
                v -> ((com.fasterxml.jackson.databind.node.ArrayNode) v.path("reversal").path("lines")).add(v.path("reversal").path("lines").get(0).deepCopy()),
                v -> v.put("observedAt", Instant.now().plusSeconds(1).toString()),
                v -> v.put("validUntil", Instant.now().minusSeconds(1).toString()),
                v -> v.put("validUntil", Instant.now().plusSeconds(600).toString()),
                v -> v.put("revision", 0), v -> v.put("revision", "1"), v -> v.put("hiddenOverride", true));
        for (var change : changes) {
            responder.set(input -> { var value = success(input, receipt(VoucherReversalPort.Status.VERIFIED)); change.accept((ObjectNode) value.path("data")); return json.write(value); });
            assertThat(gateway.query("tenant-a", target, request)).isEqualTo(new FinanceResult.Unavailable<>(FinanceResult.Failure.INVALID_RESPONSE));
        }
    }
    @Test
    void unconfiguredChangedDestinationAndOpenTransactionsNeverSend() {
        assertThat(gateway.query("tenant-b", target, request)).isEqualTo(new FinanceResult.Unavailable<>(FinanceResult.Failure.NOT_CONFIGURED));
        assertThat(gateway.query("tenant-a", "b".repeat(64), request)).isEqualTo(new FinanceResult.Unavailable<>(FinanceResult.Failure.TARGET_CHANGED));
        assertThatThrownBy(() -> client.read("tenant-a", FinanceGatewayClient.Operation.VOUCHER_REVERSAL, request, VoucherReversalPort.Receipt.class, v -> true)).isInstanceOf(IllegalArgumentException.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> gateway.query("tenant-a", target, request)).isInstanceOf(IllegalStateException.class); assertThat(calls.get()).isZero();
    }
    @Test
    void onlyAllowedBusinessRejectionsAreAcceptedAndNoRemoteBodyIsExposed() {
        for (String reason : List.of("LEGAL_ENTITY_UNAVAILABLE", "EMPLOYEE_UNAVAILABLE", "INVOICE_INVALID", "REMOTE_PRIVATE_ERROR")) {
            responder.set(input -> json.write(Map.of("contractVersion", 1, "tenantId", "tenant-a", "requestId", input.path("requestId").asText(), "outcome", "REJECTED", "reason", reason)));
            var result = gateway.query("tenant-a", target, request);
            if (reason.equals("LEGAL_ENTITY_UNAVAILABLE")) assertThat(result).isEqualTo(new FinanceResult.Rejected<>(FinanceResult.Reason.valueOf(reason)));
            else assertThat(result).isEqualTo(new FinanceResult.Unavailable<>(FinanceResult.Failure.INVALID_RESPONSE));
        }
    }
    private VoucherCommand command() {
        var entity = UUID.randomUUID(); var business = UUID.randomUUID(); var date = LocalDate.now(); var amount = new Money(new BigDecimal("100"), "CNY");
        var receivable = new AccountMappingPort.Key(AccountMappingPort.Role.EMPLOYEE_RECEIVABLE, ""); var payable = new AccountMappingPort.Key(AccountMappingPort.Role.EMPLOYEE_PAYABLE, "");
        var period = new AccountingPeriodPort.OpenPeriod(new AccountingPeriodPort.Request(entity, "CNY", date), "period", "v1", date.minusDays(1), date.plusDays(1), createdAt.minusSeconds(1), createdAt.plusSeconds(300));
        var mapping = new AccountMappingPort.Mapping(new AccountMappingPort.Request(entity, "CNY", List.of(receivable, payable)), "v1", createdAt.minusSeconds(1), createdAt.plusSeconds(300), List.of(new AccountMappingPort.Entry(receivable, "1122"), new AccountMappingPort.Entry(payable, "2241")));
        return new VoucherCommand(UUID.randomUUID(), "tenant-a", VoucherCommand.Kind.EMPLOYEE_ADVANCE, new VoucherCommand.Binding(business, UUID.randomUUID(), 1, 3, 2), entity, "alice", date,
                new VoucherCommand.Totals(amount, Money.zero("CNY"), Money.zero("CNY")), period, mapping,
                List.of(new VoucherCommand.Line(1, receivable, VoucherCommand.Side.DEBIT, amount, 0, null, null, business), new VoucherCommand.Line(2, payable, VoucherCommand.Side.CREDIT, amount, 0, null, null, null)), null, createdAt, createdAt.plusSeconds(60));
    }
    private VoucherObservation observation(VoucherObservation.Status status, long revision, Instant at) {
        return new VoucherObservation(command.id(), command.digest(), status, revision, at, "original-posting", "original-voucher", command.period().periodReference(), command.accountingDate(), command.totals().gross(), command.totals().gross(), createdAt, null);
    }
    private VoucherReversalPort.Receipt receipt(VoucherReversalPort.Status status) {
        var at = Instant.now().minusMillis(10); var current = observation(VoucherObservation.Status.REVERSED, 2, at);
        var posting = status == VoucherReversalPort.Status.UNRESOLVED ? null : new VoucherReversalPort.Posting("reverse-posting", "reverse-voucher", "reverse-period", command.accountingDate(), createdAt.plusSeconds(2),
                command.lines().stream().map(line -> new VoucherReversalPort.Line("entry-" + line.lineNo(), line.lineNo(), command.mapping().account(line.account()),
                        line.side() == VoucherCommand.Side.DEBIT ? VoucherCommand.Side.CREDIT : VoucherCommand.Side.DEBIT, line.amount(), line.sourceLineNo(), line.costCenter(), line.projectCode(), line.advanceId())).toList());
        return new VoucherReversalPort.Receipt(request, status, 3, at, at.plusSeconds(120), current, posting);
    }
    private void answer(Object value) { responder.set(input -> json.write(success(input, value))); }
    private ObjectNode success(JsonNode input, Object value) { return json.read(json.write(Map.of("contractVersion", 1, "tenantId", "tenant-a", "requestId", input.path("requestId").asText(), "outcome", "SUCCESS", "data", value)), ObjectNode.class); }
}
