package io.agentflow.finance;

import com.fasterxml.jackson.annotation.JsonInclude;
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
 * 真实回环 HTTP 验证原应付预留的幂等、查询和严格回执；此测试不代表企业 ERP 已接入。
 * @author owlzhangfq@gmail.com
 */
class GatewaySupplierPayableHoldTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .registerModule(new FinanceJsonConfiguration().financeMoneyModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private final JsonUtil json = new JsonUtil(mapper);
    private final AtomicReference<Function<JsonNode, String>> responder = new AtomicReference<>();
    private final AtomicReference<String> received = new AtomicReference<>(), key = new AtomicReference<>(), path = new AtomicReference<>(), credential = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private HttpServer server;
    private ExecutorService executor;
    private FinanceGatewayConfiguration config;
    private FinanceGatewayClient client;
    private GatewaySupplierPayableHold gateway;
    private SupplierPayableHoldCommand command;
    private String target;

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); executor = Executors.newCachedThreadPool();
        server.setExecutor(executor); server.createContext("/finance/", this::handle); server.start();
        config = FinanceGatewayConfigurationTest.configured("http://127.0.0.1:" + server.getAddress().getPort() + "/finance", "synthetic-token");
        client = new FinanceGatewayClient(config, mapper); gateway = new GatewaySupplierPayableHold(client);
        target = config.destination("tenant-a").orElseThrow().digest("tenant-a");
        command = command(Instant.now().minusSeconds(120)); answer(held(command));
    }

    @AfterEach void stop() { TransactionSynchronizationManager.setActualTransactionActive(false); server.stop(0); executor.shutdownNow(); }

    @Test void repeatedReservePreservesExactBytesAndDestinationWhileCredentialsCanRotate() {
        assertThat(gateway.reserve(command).requireValue().status()).isEqualTo(SupplierPayableHoldObservation.Status.HELD);
        String first = received.get(); var body = json.read(first, JsonNode.class);
        assertThat(path.get()).isEqualTo("/finance/supplier-payable-hold-command"); assertThat(key.get()).isEqualTo(command.id().toString());
        assertThat(body.path("requestId").asText()).isEqualTo(command.id().toString());
        assertThat(body.at("/data/commandDigest").asText()).isEqualTo(command.digest());
        assertThat(body.at("/data/command/authorization/source/reservation/source/round/content/amount/value").isTextual()).isTrue();
        assertThat(json.read(body.at("/data/command").toString(), SupplierPayableHoldCommand.class)).isEqualTo(command);
        assertThat(credential.get()).isEqualTo("Bearer synthetic-token");
        config.getTenants().get("tenant-a").setToken("rotated-token"); gateway.reserve(command).requireValue();
        assertThat(received.get()).isEqualTo(first); assertThat(credential.get()).isEqualTo("Bearer rotated-token"); assertThat(calls.get()).isEqualTo(2);
        var queued = SupplierPayableHoldOperation.queue(command, command.authorization().authorizedAt());
        var running = queued.claim(queued.createdAt(), Duration.ofSeconds(30));
        assertThat(json.read(json.write(queued), SupplierPayableHoldOperation.class)).isEqualTo(queued);
        assertThat(json.read(json.write(running), SupplierPayableHoldOperation.class)).isEqualTo(running);
    }

    @Test void queryIsReadOnlyHasFreshCorrelationAndCanRecoverAfterTheSendingWindow() {
        var expired = command(Instant.now().minusSeconds(1000)); answer(held(expired));
        assertThatThrownBy(() -> gateway.reserve(expired)).isInstanceOf(DomainException.class); assertThat(calls.get()).isZero();
        assertThat(gateway.query(expired).requireValue().status()).isEqualTo(SupplierPayableHoldObservation.Status.HELD);
        assertThat(path.get()).isEqualTo("/finance/supplier-payable-hold-query"); assertThat(key.get()).isNull();
        var first = json.read(received.get(), JsonNode.class);
        assertThat(first.path("data").size()).isEqualTo(2); assertThat(first.at("/data/authorizationId").asText()).isEqualTo(expired.id().toString());
        assertThat(first.at("/data/commandDigest").asText()).isEqualTo(expired.digest());
        gateway.query(expired).requireValue();
        assertThat(json.read(received.get(), JsonNode.class).path("requestId").asText()).isNotEqualTo(first.path("requestId").asText());
        var absent = new SupplierPayableHoldObservation(command.id(), command.digest(), SupplierPayableHoldObservation.Status.NOT_FOUND, 0L, Instant.now(), null, null, null, null, null, null);
        answer(absent); assertThat(gateway.reserve(command)).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
        assertThat(gateway.query(command).requireValue()).isEqualTo(absent);
    }

    @Test void missingConfigurationTargetChangesAndTransactionBoundariesNeverSend() {
        config.setEnabled(false); assertThat(gateway.reserve(command)).isEqualTo(unavailable(FinanceResult.Failure.NOT_CONFIGURED));
        config.setEnabled(true); config.getTenants().get("tenant-a").setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/other");
        assertThat(gateway.reserve(command)).isEqualTo(unavailable(FinanceResult.Failure.TARGET_CHANGED));
        assertThat(gateway.query(command)).isEqualTo(unavailable(FinanceResult.Failure.TARGET_CHANGED));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> gateway.reserve(command)).hasMessage("Finance gateway must run outside a transaction");
        assertThatThrownBy(() -> gateway.query(command)).hasMessage("Finance gateway must run outside a transaction");
        TransactionSynchronizationManager.setActualTransactionActive(false);
        for (var operation : List.of(FinanceGatewayClient.Operation.SUPPLIER_PAYABLE_HOLD_COMMAND, FinanceGatewayClient.Operation.SUPPLIER_PAYABLE_HOLD_QUERY)) {
            assertThatThrownBy(() -> client.read("tenant-a", operation, command, SupplierPayableHoldObservation.class, value -> true)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(calls.get()).isZero();
    }

    @Test void wrongBindingMoneyAccountTimingAndMalformedRepliesCannotConfirmReservation() {
        List<Consumer<ObjectNode>> corruptions = List.of(
                data -> data.put("authorizationId", UUID.randomUUID().toString()), data -> data.put("commandDigest", "a".repeat(64)), data -> data.put("accountDigest", "b".repeat(64)),
                data -> ((ObjectNode) data.path("heldAmount")).put("value", "69.99"), data -> ((ObjectNode) data.path("heldAmount")).put("currency", "USD"),
                data -> ((ObjectNode) data.path("heldAmount")).put("value", 70), data -> data.put("revision", "1"), data -> data.remove("revision"),
                data -> data.put("revision", 0), data -> data.put("observedAt", Instant.now().plusSeconds(60).toString()),
                data -> data.put("heldAt", command.sendDeadline().toString()), data -> data.put("heldAt", command.authorization().authorizedAt().minusSeconds(1).toString()),
                data -> data.remove("holdReference"), data -> data.remove("ledgerVersion"), data -> data.put("status", "PENDING"),
                data -> data.put("rejection", "PAYABLE_INSUFFICIENT"), data -> data.put("unknown", true));
        for (var corrupt : corruptions) {
            responder.set(request -> { var body = success(request, held(command)); corrupt.accept((ObjectNode) body.path("data")); return json.write(body); });
            assertThat(gateway.reserve(command)).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
        }
        responder.set(request -> json.write(Map.of("contractVersion", 1, "tenantId", "tenant-a", "requestId", request.path("requestId").asText(), "outcome", "REJECTED", "reason", "PROCUREMENT_PAYABLE_UNAVAILABLE")));
        assertThat(gateway.reserve(command)).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
        var rejection = new SupplierPayableHoldObservation(command.id(), command.digest(), SupplierPayableHoldObservation.Status.REJECTED, 1L, Instant.now(), null, null, null, null, null, SupplierPayableHoldObservation.Rejection.PAYABLE_VERSION_CONFLICT);
        answer(rejection); assertThat(gateway.reserve(command).requireValue()).isEqualTo(rejection);
    }

    @Test void timeoutPerformsOneWriteThenQueriesTheOriginalHoldWithoutResending() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        responder.set(request -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return json.write(success(request, held(command)));
        });
        config.getTenants().get("tenant-a").setTimeoutSeconds(1);
        try {
            assertThat(gateway.reserve(command)).isEqualTo(unavailable(FinanceResult.Failure.TIMEOUT));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue(); assertThat(calls.get()).isEqualTo(1);
            answer(held(command)); assertThat(gateway.query(command).requireValue().status()).isEqualTo(SupplierPayableHoldObservation.Status.HELD);
            assertThat(calls.get()).isEqualTo(2); assertThat(key.get()).isNull(); assertThat(path.get()).isEqualTo("/finance/supplier-payable-hold-query");
        } finally { release.countDown(); }
    }

    private SupplierPayableHoldCommand command(Instant now) {
        var entity = UUID.randomUUID(); var content = new ProcurementPaymentContent(entity, "采购付款", "已验收货物付款", "supplier-1", "payable-1", money("70"));
        var line = new ProcurementPayablePort.MatchedLine(1, 1, "receipt-1", new InvoiceKey(InvoiceKey.Type.DIGITAL, null, "00000000000000000001"), 1,
                "c".repeat(64), "verification-1", "件", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, money("100"), money("100"), money("100"), money("6"));
        var payable = new ProcurementPayablePort.Payable(content.payableRequest("alice"), "v1", now, now.plusSeconds(600), "供应商",
                new SupplierAccountSnapshot(entity, "supplier-1", "private-supplier-account", "****1234", "a".repeat(64), "v1"),
                "contract-1", "order-1", "match-1", "accrual-1", "budget-1", LocalDate.parse("2026-10-01"), money("100"), money("30"), List.of(line));
        var request = ProcurementPaymentRequest.draft(UUID.randomUUID(), "tenant-a", UUID.randomUUID(), "alice", content);
        var catalog = new FinanceCatalog("alice", "v1", now.plusSeconds(600), List.of(new FinanceCatalog.LegalEntity(entity, "法人", "CNY", false, "v1", "Asia/Shanghai")), List.of(), List.of(), List.of(), List.of());
        request.freeze(1, 1, catalog, target, payable, new InitiatorContext(UUID.randomUUID(), UUID.randomUUID(), "alice", 1, entity, "法人", UUID.randomUUID(), "部门", UUID.randomUUID(), "岗位"), now);
        var local = ProcurementPayableReservation.hold(UUID.randomUUID(), request, now); request.approve(2, 1, 8, "manager", now.plusSeconds(1));
        return new SupplierPayableHoldCommand(new SupplierPaymentAuthorization(UUID.randomUUID(), ApprovedProcurementPayment.from(request, local), payable, "finance", now.plusSeconds(2), now.plusSeconds(86400)));
    }

    private SupplierPayableHoldObservation held(SupplierPayableHoldCommand value) {
        return new SupplierPayableHoldObservation(value.id(), value.digest(), SupplierPayableHoldObservation.Status.HELD, 1L, Instant.now().minusSeconds(1),
                "erp-hold-1", "ledger-2", value.authorization().source().amount(), value.authorization().payable().account().accountDigest(), value.authorization().authorizedAt().plusSeconds(1), null);
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
    private static Money money(String amount) { return new Money(new BigDecimal(amount), "CNY"); }
    private static FinanceResult.Unavailable<?> unavailable(FinanceResult.Failure failure) { return new FinanceResult.Unavailable<>(failure); }
}
