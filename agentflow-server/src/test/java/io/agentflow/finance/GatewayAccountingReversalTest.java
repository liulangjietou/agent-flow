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
 * 回环验证独立冲销真实写命令、原编号查询、严格结果契约及外部副作用隔离。
 * @author owlzhangfq@gmail.com
 */
class GatewayAccountingReversalTest {
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
    private GatewayAccountingReversal gateway;
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
        client = new FinanceGatewayClient(configuration, mapper); gateway = new GatewayAccountingReversal(client);
        target = configuration.destination("tenant-a").orElseThrow().digest("tenant-a"); answer(posted(reversal(false)));
    }
    @AfterEach void stop() { TransactionSynchronizationManager.setActualTransactionActive(false); server.stop(0); }


    @Test void postPreservesBusinessIdentityDigestOriginalAndDerivedReverseLines() {
        var value = reversal(false); var response = posted(value); answer(response);
        assertThat(gateway.post(target, value).requireValue()).isEqualTo(response);
        assertThat(path.get()).isEqualTo("/finance/voucher-reversal-command"); assertThat(idempotency.get()).isEqualTo(value.id().toString());
        assertThat(received.get().path("requestId").asText()).isEqualTo(value.id().toString());
        assertThat(received.get().path("data").path("commandDigest").asText()).isEqualTo(value.digest());
        assertThat(json.read(received.get().path("data").path("command").toString(), VoucherReversalCommand.class)).isEqualTo(value);
        assertThat(received.get().path("data").path("lines").size()).isEqualTo(2);
        assertThat(received.get().path("data").path("lines").get(0).path("side").asText()).isEqualTo("CREDIT");
        gateway.post(target, value); assertThat(idempotency.get()).isEqualTo(value.id().toString()); assertThat(calls.get()).isEqualTo(2);
    }
    @Test void expiredAuthorizationBlocksPostButStillQueriesTheOriginalIdentityWithoutWriteHeaders() {
        var value = reversal(true); var response = posted(value); answer(response);
        assertThatThrownBy(() -> gateway.post(target, value)).isInstanceOf(io.agentflow.common.DomainException.class); assertThat(calls.get()).isZero();
        assertThat(gateway.query(target, value).requireValue()).isEqualTo(response);
        assertThat(path.get()).isEqualTo("/finance/voucher-reversal-query"); assertThat(idempotency.get()).isNull();
        assertThat(received.get().path("data").path("operationId").asText()).isEqualTo(value.id().toString());
        assertThat(received.get().path("data").path("commandDigest").asText()).isEqualTo(value.digest());
        var correlation = received.get().path("requestId").asText(); gateway.query(target, value);
        assertThat(received.get().path("requestId").asText()).isNotEqualTo(correlation);
    }
    @Test void pendingFailureAndQueryOnlyNotFoundRemainExplicit() {
        var value = reversal(false);
        for (var status : VoucherReversalObservation.Status.values()) {
            if (status == VoucherReversalObservation.Status.POSTED) continue;
            var response = new VoucherReversalObservation(value.id(), value.digest(), status, status == VoucherReversalObservation.Status.NOT_FOUND ? 0 : 1,
                    Instant.now(), status == VoucherReversalObservation.Status.NOT_FOUND ? null : "acceptance", null,
                    status == VoucherReversalObservation.Status.FAILED ? VoucherReversalObservation.Rejection.ACCOUNTING_PERIOD_CLOSED : null);
            answer(response); assertThat(gateway.query(target, value).requireValue()).isEqualTo(response);
            if (status == VoucherReversalObservation.Status.NOT_FOUND) assertInvalid(gateway.post(target, value));
            else assertThat(gateway.post(target, value).requireValue()).isEqualTo(response);
        }
    }
    @Test void foreignIdentityWrongDigestChangedPeriodAndIncompletePostingAreRejected() {
        var value = reversal(false);
        List<Consumer<ObjectNode>> changes = List.of(
                v -> v.put("operationId", UUID.randomUUID().toString()), v -> v.put("commandDigest", "b".repeat(64)),
                v -> v.put("revision", "2"), v -> v.putNull("posting"), v -> v.put("observedAt", Instant.now().plusSeconds(100).toString()),
                v -> ((ObjectNode) v.path("posting").path("reversal")).put("periodReference", "other-period"),
                v -> ((ObjectNode) v.path("posting").path("reversal")).put("accountingDate", LocalDate.now().plusDays(10).toString()),
                v -> ((ObjectNode) v.path("posting").path("reversal").path("lines").get(0)).put("accountCode", "other-account"),
                v -> v.put("hiddenOverride", true));
        for (var change : changes) {
            responder.set(input -> { var output = success(input, posted(value)); change.accept((ObjectNode) output.path("data")); return json.write(output); });
            assertInvalid(gateway.post(target, value));
        }
    }
    @Test void configurationBindingAndOpenTransactionsPreventNetworkCalls() {
        var value = reversal(false);
        assertThat(gateway.post("b".repeat(64), value)).isEqualTo(new FinanceResult.Unavailable<>(FinanceResult.Failure.TARGET_CHANGED));
        assertThat(gateway.query("b".repeat(64), value)).isEqualTo(new FinanceResult.Unavailable<>(FinanceResult.Failure.TARGET_CHANGED));
        for (var operation : List.of(FinanceGatewayClient.Operation.VOUCHER_REVERSAL_COMMAND, FinanceGatewayClient.Operation.VOUCHER_REVERSAL_QUERY)) {
            assertThatThrownBy(() -> client.read("tenant-a", operation, value, VoucherReversalObservation.class, v -> true)).isInstanceOf(IllegalArgumentException.class);
        }
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> gateway.post(target, value)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> gateway.query(target, value)).isInstanceOf(IllegalStateException.class); assertThat(calls.get()).isZero();
    }
    @Test void genericRejectionCannotPretendThatAnUnknownWriteWasNotExecuted() {
        var value = reversal(false);
        responder.set(input -> json.write(Map.of("contractVersion", 1, "tenantId", "tenant-a", "requestId", input.path("requestId").asText(), "outcome", "REJECTED", "reason", "ACCOUNTING_PERIOD_CLOSED")));
        assertInvalid(gateway.post(target, value));
    }
    private void assertInvalid(FinanceResult<VoucherReversalObservation> value) { assertThat(value).isEqualTo(new FinanceResult.Unavailable<>(FinanceResult.Failure.INVALID_RESPONSE)); }
    private VoucherReversalCommand reversal(boolean expired) {
        var at = expired ? createdAt.plusSeconds(2) : Instant.now().minusMillis(10);
        var end = expired ? at.plusSeconds(1) : at.plusSeconds(120);
        var current = observation(VoucherObservation.Status.POSTED, 1, at);
        var date = command.accountingDate().plusDays(1);
        var period = new AccountingPeriodPort.OpenPeriod(new AccountingPeriodPort.Request(command.legalEntityId(), "CNY", date), "reverse-period", "v2", date, date.plusDays(1), at, end);
        return new VoucherReversalCommand(UUID.randomUUID(), request, current, period, "finance", "proof", "确认独立冲销", at, end);
    }
    private VoucherReversalObservation posted(VoucherReversalCommand value) {
        var at = Instant.now(); var postedAt = value.createdAt();
        var reversal = new VoucherReversalPort.Posting("reverse-posting", "reverse-voucher", value.period().periodReference(), value.period().request().accountingDate(), postedAt,
                value.lines().stream().map(line -> new VoucherReversalPort.Line("entry-" + line.originalLineNo(), line.originalLineNo(), line.accountCode(), line.side(), line.amount(), line.sourceLineNo(), line.costCenter(), line.projectCode(), line.advanceId())).toList());
        var proof = new VoucherReversalPort.Receipt(request, VoucherReversalPort.Status.VERIFIED, 2, at, at.plusSeconds(300), observation(VoucherObservation.Status.REVERSED, 2, at), reversal);
        return new VoucherReversalObservation(value.id(), value.digest(), VoucherReversalObservation.Status.POSTED, 2, at, "acceptance", proof, null);
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
    private void answer(Object value) { responder.set(input -> json.write(success(input, value))); }
    private ObjectNode success(JsonNode input, Object value) { return json.read(json.write(Map.of("contractVersion", 1, "tenantId", "tenant-a", "requestId", input.path("requestId").asText(), "outcome", "SUCCESS", "data", value)), ObjectNode.class); }
}
