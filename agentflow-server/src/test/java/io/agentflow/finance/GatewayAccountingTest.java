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
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * 真实回环 ERP 协议验证期间、映射、幂等过账与原交易查询，不替代企业 ERP 验收。
 * @author owlzhangfq@gmail.com
 */
class GatewayAccountingTest {
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
    private GatewayAccounting accounting;
    private VoucherCommand command;
    private String target;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); executor = Executors.newCachedThreadPool();
        server.setExecutor(executor); server.createContext("/finance/", this::handle); server.start();
        config = FinanceGatewayConfigurationTest.configured("http://127.0.0.1:" + server.getAddress().getPort() + "/finance", "synthetic-token");
        client = new FinanceGatewayClient(config, mapper); accounting = new GatewayAccounting(client);
        target = config.destination("tenant-a").orElseThrow().digest("tenant-a"); command = command(Instant.now().minusSeconds(120), Instant.now().plusSeconds(60));
        answer(posted(command, VoucherObservation.Status.POSTED));
    }
    @AfterEach
    void stop() { TransactionSynchronizationManager.setActualTransactionActive(false); server.stop(0); executor.shutdownNow(); }

    @Test
    void periodAndMappingReadsPreserveOriginalRequestAndNeverPretendClosedPeriodIsOpen() {
        answer(command.period()); assertThat(accounting.period("tenant-a", target, command.period().request()).requireValue()).isEqualTo(command.period());
        assertThat(path.get()).isEqualTo("/finance/accounting-period"); assertThat(key.get()).isNull();
        assertThat(json.read(json.read(received.get(), JsonNode.class).path("data").toString(), AccountingPeriodPort.Request.class)).isEqualTo(command.period().request());
        answer(command.mapping()); assertThat(accounting.mapping("tenant-a", target, command.mapping().request()).requireValue()).isEqualTo(command.mapping());
        assertThat(path.get()).isEqualTo("/finance/account-mapping"); assertThat(key.get()).isNull();
        reject(FinanceResult.Reason.ACCOUNTING_PERIOD_CLOSED);
        assertThat(accounting.period("tenant-a", target, command.period().request())).isEqualTo(new FinanceResult.Rejected<>(FinanceResult.Reason.ACCOUNTING_PERIOD_CLOSED));
        reject(FinanceResult.Reason.ACCOUNT_MAPPING_UNAVAILABLE);
        assertThat(accounting.mapping("tenant-a", target, command.mapping().request())).isEqualTo(new FinanceResult.Rejected<>(FinanceResult.Reason.ACCOUNT_MAPPING_UNAVAILABLE));
        assertThat(accounting.period("tenant-a", target, command.period().request())).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
    }

    @Test
    void missingMappingWrongDateStaleOrFutureFactsAreNotAcceptedAsPreparation() {
        List<Consumer<ObjectNode>> periodCorruptions = List.of(
                body -> ((ObjectNode) body.path("request")).put("legalEntityId", UUID.randomUUID().toString()),
                body -> ((ObjectNode) body.path("request")).put("currency", "USD"),
                body -> ((ObjectNode) body.path("request")).put("accountingDate", command.accountingDate().minusDays(1).toString()),
                body -> body.put("validUntil", Instant.now().minusSeconds(1).toString()),
                body -> body.put("observedAt", Instant.now().plusSeconds(10).toString()), body -> body.put("closed", true));
        for (var corrupt : periodCorruptions) {
            corrupt(command.period(), corrupt); assertThat(accounting.period("tenant-a", target, command.period().request())).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
        }
        List<Consumer<ObjectNode>> mappingCorruptions = List.of(
                body -> ((ObjectNode) body.path("request")).put("legalEntityId", UUID.randomUUID().toString()),
                body -> ((ObjectNode) body.path("request")).put("currency", "USD"),
                body -> ((com.fasterxml.jackson.databind.node.ArrayNode) body.path("entries")).remove(0),
                body -> body.put("validUntil", Instant.now().minusSeconds(1).toString()),
                body -> body.put("observedAt", Instant.now().plusSeconds(10).toString()), body -> body.putNull("sourceVersion"));
        for (var corrupt : mappingCorruptions) {
            corrupt(command.mapping(), corrupt); assertThat(accounting.mapping("tenant-a", target, command.mapping().request())).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
        }
    }

    @Test
    void repeatedVoucherPostUsesIdenticalBytesOriginalKeyAndExactAmounts() {
        assertThat(accounting.post(target, command).requireValue().status()).isEqualTo(VoucherObservation.Status.POSTED);
        String first = received.get(); var body = json.read(first, JsonNode.class);
        assertThat(path.get()).isEqualTo("/finance/voucher-command"); assertThat(key.get()).isEqualTo(command.id().toString());
        assertThat(body.path("requestId").asText()).isEqualTo(command.id().toString());
        assertThat(body.at("/data/commandDigest").asText()).isEqualTo(command.digest());
        assertThat(body.at("/data/command/totals/gross/value").isTextual()).isTrue();
        assertThat(json.read(body.at("/data/command").toString(), VoucherCommand.class)).isEqualTo(command);
        config.getTenants().get("tenant-a").setToken("rotated-token"); accounting.post(target, command).requireValue();
        assertThat(received.get()).isEqualTo(first); assertThat(authorization.get()).isEqualTo("Bearer rotated-token");
    }

    @Test
    void notFoundIsOnlyAnOriginalOperationQueryAndMissingRevisionIsInvalid() {
        var missing = new VoucherObservation(command.id(), command.digest(), VoucherObservation.Status.NOT_FOUND, 0L, Instant.now().minusSeconds(1), null, null, null, null, null, null, null, null);
        answer(missing); assertThat(accounting.post(target, command)).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
        assertThat(accounting.query(target, command).requireValue()).isEqualTo(missing);
        assertThat(path.get()).isEqualTo("/finance/voucher-query"); assertThat(key.get()).isNull();
        var first = json.read(received.get(), JsonNode.class);
        assertThat(first.path("data").size()).isEqualTo(2); assertThat(first.at("/data/operationId").asText()).isEqualTo(command.id().toString());
        assertThat(first.at("/data/commandDigest").asText()).isEqualTo(command.digest()); accounting.query(target, command).requireValue();
        assertThat(json.read(received.get(), JsonNode.class).path("requestId").asText()).isNotEqualTo(first.path("requestId").asText());
        corrupt(missing, body -> body.remove("revision")); assertThat(accounting.query(target, command)).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
    }

    @Test
    void destinationsTransactionsAndGenericReadCannotBypassPersistedAccountingTarget() {
        config.getTenants().get("tenant-a").setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/other");
        assertThat(accounting.post(target, command)).isEqualTo(unavailable(FinanceResult.Failure.TARGET_CHANGED));
        assertThat(accounting.query(target, command)).isEqualTo(unavailable(FinanceResult.Failure.TARGET_CHANGED));
        assertThat(accounting.period("tenant-a", target, command.period().request())).isEqualTo(unavailable(FinanceResult.Failure.TARGET_CHANGED));
        assertThat(accounting.mapping("tenant-a", target, command.mapping().request())).isEqualTo(unavailable(FinanceResult.Failure.TARGET_CHANGED));
        config.setEnabled(false); assertThat(accounting.post(target, command)).isEqualTo(unavailable(FinanceResult.Failure.NOT_CONFIGURED)); config.setEnabled(true);
        for (var operation : List.of(FinanceGatewayClient.Operation.VOUCHER_COMMAND, FinanceGatewayClient.Operation.VOUCHER_QUERY,
                FinanceGatewayClient.Operation.ACCOUNTING_PERIOD, FinanceGatewayClient.Operation.ACCOUNT_MAPPING)) {
            assertThatThrownBy(() -> client.read("tenant-a", operation, command, VoucherObservation.class, value -> true)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> client.readAccounting("tenant-a", target, FinanceGatewayClient.Operation.VOUCHER_COMMAND, command, VoucherObservation.class, value -> true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> accounting.post(null, command)).isInstanceOf(IllegalArgumentException.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> accounting.post(target, command)).hasMessage("Finance gateway must run outside a transaction");
        assertThatThrownBy(() -> accounting.query(target, command)).hasMessage("Finance gateway must run outside a transaction");
        assertThatThrownBy(() -> accounting.period("tenant-a", target, command.period().request())).hasMessage("Finance gateway must run outside a transaction");
        assertThatThrownBy(() -> accounting.mapping("tenant-a", target, command.mapping().request())).hasMessage("Finance gateway must run outside a transaction");
        assertThat(requests.get()).isZero();
    }

    @Test
    void expiredPostingFactsBlockSendingButDoNotBlockRecoveryOfAcceptedVoucher() {
        var expired = command(Instant.now().minusSeconds(120), Instant.now().minusSeconds(60));
        assertThatThrownBy(() -> accounting.post(target, expired)).isInstanceOf(DomainException.class).hasMessage("Voucher evidence is outside its sending window");
        assertThat(requests.get()).isZero(); answer(posted(expired, VoucherObservation.Status.POSTED));
        assertThat(accounting.query(target, expired).requireValue().status()).isEqualTo(VoucherObservation.Status.POSTED);
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void mismatchedAmountDatePeriodIdentityAndMalformedResponsesNeverConfirmPosting() {
        List<Consumer<ObjectNode>> corruptions = List.of(
                body -> body.put("operationId", UUID.randomUUID().toString()), body -> body.put("commandDigest", "b".repeat(64)),
                body -> body.put("periodReference", "other-period"), body -> body.put("accountingDate", command.accountingDate().plusDays(1).toString()),
                body -> { ((ObjectNode) body.path("debitTotal")).put("value", "99.99"); ((ObjectNode) body.path("creditTotal")).put("value", "99.99"); },
                body -> ((ObjectNode) body.path("debitTotal")).put("value", 100), body -> body.put("revision", "2"), body -> body.put("revision", 0),
                body -> body.put("observedAt", Instant.now().plusSeconds(60).toString()), body -> body.put("postedAt", command.createdAt().minusSeconds(1).toString()),
                body -> body.put("status", "PENDING"), body -> body.remove("voucherReference"), body -> body.put("unknown", true));
        for (var corrupt : corruptions) {
            corrupt(posted(command, VoucherObservation.Status.POSTED), corrupt); assertThat(accounting.post(target, command)).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
        }
        reject(FinanceResult.Reason.ACCOUNTING_PERIOD_CLOSED);
        assertThat(accounting.post(target, command)).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
    }

    @Test
    void pendingFailureAndReversalRetainTheirDistinctExternalFacts() {
        for (var value : List.of(
                new VoucherObservation(command.id(), command.digest(), VoucherObservation.Status.PENDING, 1L, Instant.now().minusSeconds(1), "posting-1", null, null, null, null, null, null, null),
                new VoucherObservation(command.id(), command.digest(), VoucherObservation.Status.FAILED, 2L, Instant.now().minusSeconds(1), "posting-1", null, null, null, null, null, null, VoucherObservation.Failure.ACCOUNTING_PERIOD_CLOSED),
                posted(command, VoucherObservation.Status.REVERSED))) {
            answer(value); assertThat(accounting.query(target, command).requireValue()).isEqualTo(value); assertThat(value.status()).isNotEqualTo(VoucherObservation.Status.POSTED);
        }
    }

    @Test
    void timeoutSendsOnlyOnceAndRecoveryQueriesWithoutAnotherPosting() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        responder.set(request -> {
            entered.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return json.write(success(request, posted(command, VoucherObservation.Status.POSTED)));
        });
        config.getTenants().get("tenant-a").setTimeoutSeconds(1);
        try {
            assertThat(accounting.post(target, command)).isEqualTo(unavailable(FinanceResult.Failure.TIMEOUT));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue(); assertThat(requests.get()).isEqualTo(1);
            answer(posted(command, VoucherObservation.Status.POSTED)); accounting.query(target, command).requireValue();
            assertThat(requests.get()).isEqualTo(2); assertThat(path.get()).isEqualTo("/finance/voucher-query"); assertThat(key.get()).isNull();
        } finally { release.countDown(); }
    }

    private static VoucherCommand command(Instant created, Instant expires) {
        UUID business = UUID.randomUUID(), entity = UUID.randomUUID(); LocalDate date = LocalDate.ofInstant(created, ZoneOffset.UTC);
        var receivable = new AccountMappingPort.Key(AccountMappingPort.Role.EMPLOYEE_RECEIVABLE, ""); var payable = new AccountMappingPort.Key(AccountMappingPort.Role.EMPLOYEE_PAYABLE, "");
        var period = new AccountingPeriodPort.OpenPeriod(new AccountingPeriodPort.Request(entity, "CNY", date), "synthetic-period", "v1", date.minusDays(31), date.plusDays(31), created.minusSeconds(1), expires.plusSeconds(1));
        var mapping = new AccountMappingPort.Mapping(new AccountMappingPort.Request(entity, "CNY", List.of(receivable, payable)), "v1", created.minusSeconds(1), expires.plusSeconds(1),
                List.of(new AccountMappingPort.Entry(receivable, "synthetic-1122"), new AccountMappingPort.Entry(payable, "synthetic-2241")));
        var amount = new Money(new BigDecimal("100.00"), "CNY");
        return new VoucherCommand(UUID.randomUUID(), "tenant-a", VoucherCommand.Kind.EMPLOYEE_ADVANCE, new VoucherCommand.Binding(business, UUID.randomUUID(), 2, 7, 4), entity, "alice", date,
                new VoucherCommand.Totals(amount, Money.zero("CNY"), Money.zero("CNY")), period, mapping,
                List.of(new VoucherCommand.Line(1, receivable, VoucherCommand.Side.DEBIT, amount, 0, null, null, business), new VoucherCommand.Line(2, payable, VoucherCommand.Side.CREDIT, amount, 0, null, null, null)), null, created, expires);
    }
    private VoucherObservation posted(VoucherCommand command, VoucherObservation.Status status) {
        return new VoucherObservation(command.id(), command.digest(), status, 2L, Instant.now().minusSeconds(1), "posting-1", "voucher-1", command.period().periodReference(), command.accountingDate(),
                command.totals().gross(), command.totals().gross(), Instant.now().minusSeconds(2), null);
    }
    private void answer(Object value) { responder.set(request -> json.write(success(request, value))); }
    private void corrupt(Object value, Consumer<ObjectNode> corrupt) { responder.set(request -> { var body = success(request, value); corrupt.accept((ObjectNode) body.path("data")); return json.write(body); }); }
    private void reject(FinanceResult.Reason reason) { responder.set(request -> json.write(Map.of("contractVersion", 1, "tenantId", request.path("tenantId").asText(), "requestId", request.path("requestId").asText(), "outcome", "REJECTED", "reason", reason))); }
    private ObjectNode success(JsonNode request, Object value) { return json.read(json.write(Map.of("contractVersion", 1, "tenantId", request.path("tenantId").asText(), "requestId", request.path("requestId").asText(), "outcome", "SUCCESS", "data", value)), ObjectNode.class); }
    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet(); path.set(exchange.getRequestURI().getPath()); key.set(exchange.getRequestHeaders().getFirst("Idempotency-Key")); authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8); received.set(request);
        byte[] body = responder.get().apply(json.read(request, JsonNode.class)).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, 0);
        try { exchange.getResponseBody().write(body); } finally { exchange.close(); }
    }
    private static FinanceResult.Unavailable<?> unavailable(FinanceResult.Failure failure) { return new FinanceResult.Unavailable<>(failure); }
}
