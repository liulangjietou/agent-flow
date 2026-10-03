package io.agentflow.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.FinanceJsonConfiguration;
import io.agentflow.expense.InvoiceKey;
import io.agentflow.procurement.ProcurementPayablePort;
import io.agentflow.procurement.ProcurementPaymentContent;
import io.agentflow.procurement.SupplierAccountSnapshot;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * 回环 HTTP 核对可信应付读取的身份、三单事实和失败边界，不代表已接通企业采购系统。
 * @author owlzhangfq@gmail.com
 */
class GatewayProcurementPayableTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .registerModule(new FinanceJsonConfiguration().financeMoneyModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final JsonUtil json = new JsonUtil(mapper);
    private final AtomicReference<Function<JsonNode, String>> responder = new AtomicReference<>();
    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>(), idempotency = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final ProcurementPayablePort.Request request = new ProcurementPayablePort.Request(UUID.randomUUID(), "alice", "supplier-1", "payable-1");
    private HttpServer server;
    private FinanceGatewayConfiguration configuration;
    private FinanceGatewayClient client;
    private GatewayProcurementPayable payable;
    private String target;

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/finance/", exchange -> {
            calls.incrementAndGet(); path.set(exchange.getRequestURI().getPath()); idempotency.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            var body = json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonNode.class); received.set(body);
            byte[] response = responder.get().apply(body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, response.length);
            try { exchange.getResponseBody().write(response); } finally { exchange.close(); }
        });
        server.start(); configuration = FinanceGatewayConfigurationTest.configured("http://127.0.0.1:" + server.getAddress().getPort() + "/finance", "synthetic-token");
        client = new FinanceGatewayClient(configuration, mapper); payable = new GatewayProcurementPayable(client);
        target = configuration.destination("tenant-a").orElseThrow().digest("tenant-a"); answer(source());
    }
    @AfterEach void stop() { TransactionSynchronizationManager.setActualTransactionActive(false); server.stop(0); }

    @Test void exactSourceAndOriginalTargetAreReadWithoutCreatingAWriteIdentity() {
        var source = source(); answer(source);
        assertThat(payable.payable("tenant-a", target, request).requireValue()).isEqualTo(source);
        assertThat(path.get()).isEqualTo("/finance/procurement-payable"); assertThat(idempotency.get()).isNull();
        assertThat(json.read(received.get().path("data").toString(), ProcurementPayablePort.Request.class)).isEqualTo(request);
        String correlation = received.get().path("requestId").asText();
        var settled = new ProcurementPayablePort.Payable(source.request(), source.sourceVersion(), source.observedAt(), source.validUntil(), source.supplierName(), source.account(),
                source.contractReference(), source.orderReference(), source.matchingReference(), source.accrualVoucherReference(), source.budgetRecognitionReference(), source.dueOn(),
                source.gross(), source.gross(), source.lines());
        answer(settled); assertThat(payable.payable("tenant-a", target, request).requireValue().outstanding()).isEqualTo(money("0"));
        assertThat(received.get().path("requestId").asText()).isNotEqualTo(correlation); assertThat(calls.get()).isEqualTo(2);
    }

    @Test void scopeAccountStalenessAndMalformedMatchingFactsCannotBecomePayableEvidence() {
        List<Consumer<ObjectNode>> corruptions = List.of(
                data -> ((ObjectNode) data.path("request")).put("employeeId", "bob"),
                data -> ((ObjectNode) data.path("request")).put("supplierReference", "another"),
                data -> ((ObjectNode) data.path("request")).put("payableReference", "other-payable"),
                data -> ((ObjectNode) data.path("request")).put("legalEntityId", UUID.randomUUID().toString()),
                data -> ((ObjectNode) data.path("account")).put("maskedAccount", "621234567890"),
                data -> ((ObjectNode) data.path("account")).put("supplierReference", "other"),
                data -> data.put("observedAt", Instant.now().plusSeconds(10).toString()),
                data -> data.put("observedAt", Instant.now().minusSeconds(301).toString()),
                data -> data.put("validUntil", Instant.now().minusSeconds(1).toString()),
                data -> ((ObjectNode) data.path("gross")).put("value", "99.99"),
                data -> ((ObjectNode) data.path("settled")).put("value", "100.01"),
                data -> ((ObjectNode) data.path("lines").get(0)).put("acceptedQuantity", new BigDecimal("0.5")),
                data -> ((ObjectNode) data.path("lines").get(0)).put("verificationReference", ""),
                data -> ((ObjectNode) data.path("lines").get(0).path("invoicedGross")).put("value", 100.00),
                data -> data.putNull("lines"), data -> data.put("paymentApproved", true));
        for (var corruption : corruptions) {
            responder.set(input -> { var body = success(input, source()); corruption.accept((ObjectNode) body.path("data")); return json.write(body); });
            assertThat(payable.payable("tenant-a", target, request)).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
        }
    }

    @Test void intermediateEnvelopeMustPreserveExactQuantitiesAndRejectExcessPrecision() {
        String exact = "999999999999999.123456";
        responder.set(input -> {
            var body = success(input, source()); var line = (ObjectNode) body.at("/data/lines/0");
            for (String field : List.of("orderedQuantity", "acceptedQuantity", "invoicedQuantity")) line.put(field, new BigDecimal(exact));
            return json.write(body);
        });
        var observed = payable.payable("tenant-a", target, request).requireValue().lines().get(0);
        assertThat(observed.orderedQuantity()).isEqualByComparingTo(exact);
        assertThat(observed.acceptedQuantity()).isEqualByComparingTo(exact);
        assertThat(observed.invoicedQuantity()).isEqualByComparingTo(exact);
        responder.set(input -> {
            var body = success(input, source()); var line = (ObjectNode) body.at("/data/lines/0");
            for (String field : List.of("orderedQuantity", "acceptedQuantity", "invoicedQuantity")) line.put(field, new BigDecimal(exact + "7"));
            return json.write(body);
        });
        assertThat(payable.payable("tenant-a", target, request)).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
    }

    @Test void readDoesNotBypassMissingConfigurationTargetChangeOrTransactionBoundary() {
        configuration.setEnabled(false); assertThat(payable.payable("tenant-a", target, request)).isEqualTo(unavailable(FinanceResult.Failure.NOT_CONFIGURED));
        configuration.setEnabled(true); assertThat(payable.payable("tenant-b", target, request)).isEqualTo(unavailable(FinanceResult.Failure.NOT_CONFIGURED));
        configuration.getTenants().get("tenant-a").setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/other");
        assertThat(payable.payable("tenant-a", target, request)).isEqualTo(unavailable(FinanceResult.Failure.TARGET_CHANGED));
        assertThatThrownBy(() -> payable.payable("tenant-a", null, request)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.read("tenant-a", FinanceGatewayClient.Operation.PROCUREMENT_PAYABLE, request, ProcurementPayablePort.Payable.class, value -> true)).isInstanceOf(IllegalArgumentException.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> payable.payable("tenant-a", target, request)).hasMessage("Finance gateway must run outside a transaction");
        assertThat(calls.get()).isZero();
    }

    @Test void onlyProcurementRejectionsAreAcceptedAndNeverConvertedToSuccessfulEmptySource() {
        for (var reason : List.of(FinanceResult.Reason.LEGAL_ENTITY_UNAVAILABLE, FinanceResult.Reason.EMPLOYEE_UNAVAILABLE,
                FinanceResult.Reason.SUPPLIER_UNAVAILABLE, FinanceResult.Reason.ACCOUNT_UNAVAILABLE,
                FinanceResult.Reason.PROCUREMENT_PAYABLE_UNAVAILABLE, FinanceResult.Reason.PROCUREMENT_MATCH_REQUIRED)) {
            responder.set(input -> json.write(Map.of("contractVersion", 1, "tenantId", "tenant-a", "requestId", input.path("requestId").asText(), "outcome", "REJECTED", "reason", reason)));
            assertThat(payable.payable("tenant-a", target, request)).isEqualTo(new FinanceResult.Rejected<>(reason));
        }
        responder.set(input -> json.write(Map.of("contractVersion", 1, "tenantId", "tenant-a", "requestId", input.path("requestId").asText(), "outcome", "REJECTED", "reason", "EXPENSE_PROHIBITED")));
        assertThat(payable.payable("tenant-a", target, request)).isEqualTo(unavailable(FinanceResult.Failure.INVALID_RESPONSE));
    }

    @Test void draftJsonDoesNotAcceptSelfReportedBankOrMatchingFacts() {
        var draft = new ProcurementPaymentContent(request.legalEntityId(), "采购付款", "已验收货物付款", request.supplierReference(), request.payableReference(), money("70"));
        assertThat(json.read(json.write(draft), ProcurementPaymentContent.class)).isEqualTo(draft);
        for (String field : List.of("account", "matched", "settled", "approved", "sourceVersion")) {
            var node = json.read(json.write(draft), ObjectNode.class); node.put(field, "injected");
            assertThatThrownBy(() -> json.read(json.write(node), ProcurementPaymentContent.class)).isInstanceOf(RuntimeException.class);
        }
    }

    private ProcurementPayablePort.Payable source() {
        var line = new ProcurementPayablePort.MatchedLine(1, 1, "receipt-1", new InvoiceKey(InvoiceKey.Type.DIGITAL, null, "00000000000000000001"), 1,
                "c".repeat(64), "verification-1", "件", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, money("100"), money("100"), money("100"), money("6"));
        return new ProcurementPayablePort.Payable(request, "v1", Instant.now().minusSeconds(1), Instant.now().plusSeconds(300), "供应商",
                new SupplierAccountSnapshot(request.legalEntityId(), request.supplierReference(), "private-supplier-account", "****1234", "a".repeat(64), "v1"),
                "contract-1", "order-1", "match-1", "accrual-1", "budget-1", LocalDate.parse("2026-10-01"), money("100"), money("30"), List.of(line));
    }
    private static Money money(String amount) { return new Money(new BigDecimal(amount), "CNY"); }
    private void answer(Object value) { responder.set(input -> json.write(success(input, value))); }
    private ObjectNode success(JsonNode input, Object value) {
        return json.read(json.write(Map.of("contractVersion", 1, "tenantId", "tenant-a", "requestId", input.path("requestId").asText(), "outcome", "SUCCESS", "data", value)), ObjectNode.class);
    }
    private static FinanceResult.Unavailable<?> unavailable(FinanceResult.Failure failure) { return new FinanceResult.Unavailable<>(failure); }
}
