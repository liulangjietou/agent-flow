package io.agentflow.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HexFormat;
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
 * 真实回环 HTTP 验证租户绑定、协议严格性、整段超时和限流；所有财务数据均为合成夹具。
 * @author owlzhangfq@gmail.com
 */
class FinanceGatewayClientTest {
    private static final UUID ENTITY = UUID.randomUUID();
    private static final LocalDate DATE = LocalDate.of(2026, 9, 28);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .registerModule(new FinanceJsonConfiguration().financeMoneyModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final JsonUtil json = new JsonUtil(mapper);
    private final AtomicReference<Function<JsonNode, String>> responder = new AtomicReference<>();
    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger status = new AtomicInteger(200);
    private HttpServer server;
    private ExecutorService executor;
    private FinanceGatewayConfiguration config;
    private FinanceGatewayClient client;
    private GatewayFinanceMasterData master;
    private GatewayExchangeRates rates;
    private GatewayExpensePolicies policies;
    private GatewayInvoiceVerification invoices;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/finance/", this::handle); server.start();
        config = FinanceGatewayConfigurationTest.configured("http://127.0.0.1:" + server.getAddress().getPort() + "/finance", "tenant-a-test-token");
        config.validate(); client = new FinanceGatewayClient(config, mapper);
        master = new GatewayFinanceMasterData(client); rates = new GatewayExchangeRates(client);
        policies = new GatewayExpensePolicies(client); invoices = new GatewayInvoiceVerification(client);
        answer(catalog("alice", Instant.now().plusSeconds(60)));
    }

    @AfterEach
    void stop() { TransactionSynchronizationManager.setActualTransactionActive(false); server.stop(0); executor.shutdownNow(); }

    @Test
    void actualHttpCarriesFixedPathTenantEmployeeAndOnlyItsConfiguredCredential() {
        var result = master.catalog("tenant-a", "alice").requireValue();
        assertThat(result.employeeId()).isEqualTo("alice");
        assertThat(result.legalEntities().get(0).baseCurrency()).isEqualTo("CNY");
        assertThat(path.get()).isEqualTo("/finance/catalog");
        assertThat(authorization.get()).isEqualTo("Bearer tenant-a-test-token");
        assertThat(received.get().path("tenantId").asText()).isEqualTo("tenant-a");
        assertThat(received.get().at("/data/employeeId").asText()).isEqualTo("alice");
        String firstId = received.get().path("requestId").asText();
        master.catalog("tenant-a", "alice").requireValue();
        assertThat(received.get().path("requestId").asText()).isNotEqualTo(firstId);
        assertThat(master.catalog("tenant-b", "alice")).isEqualTo(unavailable(FinanceResult.Failure.NOT_CONFIGURED));
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    void noExternalCallsRunInsideDatabaseTransactionsOrWhenDisabled() {
        config.setEnabled(false);
        assertThat(master.catalog("tenant-a", "alice")).isEqualTo(unavailable(FinanceResult.Failure.NOT_CONFIGURED));
        config.setEnabled(true); TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> master.catalog("tenant-a", "alice")).hasMessage("Finance gateway must run outside a transaction");
        assertThat(requests.get()).isZero();
    }

    @Test
    void envelopesRejectCrossTenantNonceProtocolAndUnknownOutcomeFields() {
        List<Consumer<ObjectNode>> corruptions = List.of(
                body -> body.put("tenantId", "tenant-b"), body -> body.put("requestId", UUID.randomUUID().toString()),
                body -> body.put("contractVersion", 2), body -> body.put("contractVersion", "1"),
                body -> body.put("outcome", "APPROVED"), body -> body.put("additional", "discard"));
        for (var corrupt : corruptions) {
            responder.set(request -> { var body = success(request, catalog("alice", Instant.now().plusSeconds(60))); corrupt.accept(body); return json.write(body); });
            assertThat(master.catalog("tenant-a", "alice")).isEqualTo(invalid());
        }
        responder.set(request -> json.write(success(request, catalog("alice", Instant.now().plusSeconds(60)))) + " {}");
        assertThat(master.catalog("tenant-a", "alice")).isEqualTo(invalid());
        responder.set(request -> json.write(success(request, catalog("alice", Instant.now().plusSeconds(60))))
                .replace("\"contractVersion\":1", "\"contractVersion\":2,\"contractVersion\":1"));
        assertThat(master.catalog("tenant-a", "alice")).isEqualTo(invalid());
    }

    @Test
    void businessRejectionIsDistinctFromUnavailableAndUnknownRemoteReasons() {
        reject("EMPLOYEE_UNAVAILABLE");
        assertThat(master.catalog("tenant-a", "alice")).isEqualTo(new FinanceResult.Rejected<>(FinanceResult.Reason.EMPLOYEE_UNAVAILABLE));
        for (String unknown : List.of("INVOICE_INVALID", "REMOTE_ERROR_WITH_PRIVATE_DETAILS", "")) {
            reject(unknown); assertThat(master.catalog("tenant-a", "alice")).isEqualTo(invalid());
        }
        status.set(401); assertThat(master.catalog("tenant-a", "alice")).isEqualTo(unavailable(FinanceResult.Failure.AUTHENTICATION));
        status.set(403); assertThat(master.catalog("tenant-a", "alice")).isEqualTo(unavailable(FinanceResult.Failure.AUTHENTICATION));
        status.set(500); assertThat(master.catalog("tenant-a", "alice")).isEqualTo(unavailable(FinanceResult.Failure.REMOTE_FAILURE));
    }

    @Test
    void redirectIsNotFollowedWithCredentialsOrRequestBody() throws IOException {
        var redirected = new AtomicInteger();
        server.createContext("/redirect-target", exchange -> { redirected.incrementAndGet(); exchange.sendResponseHeaders(204, -1); exchange.close(); });
        server.removeContext("/finance/");
        server.createContext("/finance/", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect-target");
            exchange.sendResponseHeaders(307, -1); exchange.close();
        });
        assertThat(master.catalog("tenant-a", "alice")).isEqualTo(unavailable(FinanceResult.Failure.REMOTE_FAILURE));
        assertThat(redirected.get()).isZero();
    }

    @Test
    void responseLimitAppliesDuringChunkedBodyAndTimeoutCoversIncompleteBody() throws IOException {
        responder.set(request -> "x".repeat(FinanceGatewayClient.MAX_RESPONSE_BYTES + 1));
        assertThat(master.catalog("tenant-a", "alice")).isEqualTo(unavailable(FinanceResult.Failure.RESPONSE_TOO_LARGE));
        server.removeContext("/finance/");
        var release = new CountDownLatch(1);
        server.createContext("/finance/", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        config.getTenants().get("tenant-a").setTimeoutSeconds(1);
        long start = System.nanoTime();
        try { assertThat(master.catalog("tenant-a", "alice")).isEqualTo(unavailable(FinanceResult.Failure.TIMEOUT)); }
        finally { release.countDown(); }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(3000);
    }

    @Test
    void catalogRejectsStaleWrongEmployeeUnknownFieldsAndScalarCoercion() {
        answer(catalog("bob", Instant.now().plusSeconds(60)));
        assertThat(master.catalog("tenant-a", "alice")).isEqualTo(invalid());
        answer(catalog("alice", Instant.now().minusSeconds(1)));
        assertThat(master.catalog("tenant-a", "alice")).isEqualTo(invalid());
        List<Consumer<ObjectNode>> corruptions = List.of(
                body -> ((ObjectNode) body.path("data")).put("fullBankNumber", "6222021234567890123"),
                body -> ((ObjectNode) body.at("/data/legalEntities/0")).put("paperReceiptRequired", "true"),
                body -> ((ObjectNode) body.at("/data/legalEntities/0")).remove("paperReceiptRequired"),
                body -> ((ObjectNode) body.path("data")).put("employeeId", 123));
        for (var corrupt : corruptions) {
            responder.set(request -> { var body = success(request, catalog("alice", Instant.now().plusSeconds(60))); corrupt.accept(body); return json.write(body); });
            assertThat(master.catalog("tenant-a", "alice")).isEqualTo(invalid());
        }
    }

    @Test
    void accountRequiresSameOwnerEntityValidityAndActualMask() {
        var valid = new EmployeeAccountPort.Account(new EmployeeAccountSnapshot(ENTITY, "alice", "account-ref", "****1234", "a".repeat(64), "v1"), Instant.now().plusSeconds(60));
        answer(valid); assertThat(master.primaryAccount("tenant-a", "alice", ENTITY).requireValue()).isEqualTo(valid);
        assertThat(master.primaryAccount("tenant-a", "bob", ENTITY)).isEqualTo(invalid());
        assertThat(master.primaryAccount("tenant-a", "alice", UUID.randomUUID())).isEqualTo(invalid());
        answer(new EmployeeAccountPort.Account(valid.snapshot(), Instant.now().minusSeconds(1)));
        assertThat(master.primaryAccount("tenant-a", "alice", ENTITY)).isEqualTo(invalid());
        responder.set(request -> { var body = success(request, valid); ((ObjectNode) body.at("/data/snapshot")).put("maskedAccount", "6222021234567890123"); return json.write(body); });
        assertThat(master.primaryAccount("tenant-a", "alice", ENTITY)).isEqualTo(invalid());
    }

    @Test
    void exchangeRateMustMatchExactCurrencyPairDateAndExplicitSource() {
        var value = new ExpenseExchangeRate("USD", "CNY", new BigDecimal("7.123456789123"), "synthetic-rate-v1", DATE);
        answer(value); assertThat(rates.rate("tenant-a", ENTITY, "USD", "CNY", DATE).requireValue()).isEqualTo(value);
        assertThat(rates.rate("tenant-a", ENTITY, "CNY", "USD", DATE)).isEqualTo(invalid());
        assertThat(rates.rate("tenant-a", ENTITY, "USD", "CNY", DATE.plusDays(1))).isEqualTo(invalid());
        assertThat(received.get().at("/data/legalEntityId").asText()).isEqualTo(ENTITY.toString());
    }

    @Test
    void expensePolicyBindsConvertedAmountAndNeverIncreasesClaimedTax() {
        var request = policyRequest();
        var snapshot = new ExpensePolicySnapshot(UUID.randomUUID(), 3, cny("100"), cny("90"), ExpensePolicySnapshot.Decision.REQUIRES_EXCEPTION, "tax-v2", "policy-evidence-1");
        var good = new ExpensePolicyPort.Assessment(snapshot, cny("6"), true, Instant.now().plusSeconds(60));
        answer(good); assertThat(policies.assess("tenant-a", request).requireValue()).isEqualTo(good);
        answer(new ExpensePolicyPort.Assessment(snapshot, cny("6.01"), true, good.validUntil()));
        assertThat(policies.assess("tenant-a", request)).isEqualTo(invalid());
        var wrongAmount = new ExpensePolicySnapshot(snapshot.policyId(), 3, cny("99"), cny("90"), snapshot.decision(), "tax-v2", "policy-evidence-1");
        answer(new ExpensePolicyPort.Assessment(wrongAmount, cny("6"), true, good.validUntil()));
        assertThat(policies.assess("tenant-a", request)).isEqualTo(invalid());
        answer(new ExpensePolicyPort.Assessment(snapshot, cny("6"), true, Instant.now().minusSeconds(1)));
        assertThat(policies.assess("tenant-a", request)).isEqualTo(invalid());
        responder.set(in -> { var body = success(in, good); ((ObjectNode) body.at("/data/policy/assessedGross")).put("value", 100); return json.write(body); });
        assertThat(policies.assess("tenant-a", request)).isEqualTo(invalid());
    }

    @Test
    void foreignCurrencyPolicyResultFeedsFreezeWithoutSecondTaxConversion() {
        var line = new ExpenseLine(1, "TRAINING", DATE, null, "SH", BigDecimal.ONE, ExpenseLine.Unit.ITEM,
                new Money(new BigDecimal("100"), "USD"), new Money(new BigDecimal("6"), "USD"), List.of(), null,
                List.of(new CostAllocation("IT", null, new Money(new BigDecimal("100"), "USD"))), "合成境外费用", "行程调整导致超标");
        var rate = new ExpenseExchangeRate("USD", "CNY", new BigDecimal("7.1"), "synthetic-fx-v1", DATE);
        var policy = new ExpensePolicySnapshot(UUID.randomUUID(), 4, cny("710"), cny("700"),
                ExpensePolicySnapshot.Decision.REQUIRES_EXCEPTION, "synthetic-tax", "synthetic-foreign-policy");
        answer(new ExpensePolicyPort.Assessment(policy, cny("42.60"), false, Instant.now().plusSeconds(60)));
        var assessed = policies.assess("tenant-a", new ExpensePolicyPort.Request("alice", ENTITY, ExpenseContent.Type.TRAINING, line, rate, List.of())).requireValue();
        var report = ExpenseReport.draft(UUID.randomUUID(), "tenant-a", UUID.randomUUID(), "alice",
                new ExpenseContent(ENTITY, ExpenseContent.Type.TRAINING, "合成境外培训", List.of(line), List.of()));
        var account = new EmployeeAccountSnapshot(ENTITY, "alice", "synthetic-account", "****1234", "a".repeat(64), "v1");
        report.freeze(1, 1, "CNY", account, Map.of(1, new ExpenseAssessment(rate, assessed.policy(), assessed.deductibleTax())), "alice", Instant.now());
        assertThat(report.currentRound().approvedGross()).isEqualTo(cny("710"));
        assertThat(report.currentRound().approvedTax()).isEqualTo(cny("42.60"));
        assertThat(report.currentRound().originalLines().get(0).deductibleTaxBase()).isEqualTo(cny("42.60"));
        assertThat(report.currentRound().originalLines().get(0).original().claimedTax().currency()).isEqualTo("USD");
        assertThat(report.currentRound().approvedLines().get(0).allocations().get(0).amount()).isEqualTo(cny("710"));
    }

    @Test
    void invoiceVerificationTransmitsExactOriginalAndRequiresCurrentMatchingFacts() throws Exception {
        byte[] bytes = "%PDF-original-fixture".getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var input = new InvoiceVerificationPort.Request("alice", ENTITY, UUID.randomUUID(), digest, "application/pdf", bytes);
        var facts = new Invoice.VerifiedFacts(new InvoiceKey(InvoiceKey.Type.DIGITAL, null, "12345678901234567890"), ENTITY,
                cny("100"), cny("6"), DATE, digest, "synthetic-verification-1", Instant.now().minusSeconds(1), Instant.now().plusSeconds(60));
        answer(facts); assertThat(invoices.verify("tenant-a", input).requireValue()).isEqualTo(facts);
        assertThat(Base64.getDecoder().decode(received.get().at("/data/original").asText())).isEqualTo(bytes);
        assertThat(received.get().at("/data/originalDigest").asText()).isEqualTo(digest);
        List<Consumer<ObjectNode>> corruptions = List.of(
                body -> ((ObjectNode) body.path("data")).put("originalDigest", "b".repeat(64)),
                body -> ((ObjectNode) body.path("data")).put("legalEntityId", UUID.randomUUID().toString()),
                body -> ((ObjectNode) body.path("data")).put("validUntil", Instant.now().minusSeconds(1).toString()),
                body -> ((ObjectNode) body.path("data")).put("verifiedAt", Instant.now().plusSeconds(30).toString()));
        for (var corrupt : corruptions) {
            responder.set(request -> { var body = success(request, facts); corrupt.accept(body); return json.write(body); });
            assertThat(invoices.verify("tenant-a", input)).isEqualTo(invalid());
        }
        reject("INVOICE_CANCELLED");
        assertThat(invoices.verify("tenant-a", input)).isEqualTo(new FinanceResult.Rejected<>(FinanceResult.Reason.INVOICE_CANCELLED));
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet(); path.set(exchange.getRequestURI().getPath()); authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        var request = json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonNode.class); received.set(request);
        byte[] body = responder.get().apply(request).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8"); exchange.sendResponseHeaders(status.get(), 0);
        try { exchange.getResponseBody().write(body); } finally { exchange.close(); }
    }

    private void answer(Object value) { responder.set(request -> json.write(success(request, value))); }
    private void reject(String reason) {
        responder.set(request -> json.write(Map.of("contractVersion", 1, "tenantId", request.path("tenantId").asText(),
                "requestId", request.path("requestId").asText(), "outcome", "REJECTED", "reason", reason)));
    }
    private ObjectNode success(JsonNode request, Object value) {
        return json.read(json.write(Map.of("contractVersion", 1, "tenantId", request.path("tenantId").asText(),
                "requestId", request.path("requestId").asText(), "outcome", "SUCCESS", "data", value)), ObjectNode.class);
    }
    private FinanceCatalog catalog(String employee, Instant validity) {
        return new FinanceCatalog(employee, "synthetic-catalog-v1", validity,
                List.of(new FinanceCatalog.LegalEntity(ENTITY, "合成测试法人", "CNY", false, "v1")),
                List.of(new FinanceCatalog.Category("TRAINING", "培训", List.of(ExpenseLine.Unit.ITEM))),
                List.of(new FinanceCatalog.CostCenter(ENTITY, "IT", "研发中心")), List.of(), List.of(new FinanceCatalog.City("SH", "上海")));
    }
    private ExpensePolicyPort.Request policyRequest() {
        var line = new ExpenseLine(1, "TRAINING", DATE, null, "SH", BigDecimal.ONE, ExpenseLine.Unit.ITEM,
                cny("100"), cny("6"), List.of(), null, List.of(new CostAllocation("IT", null, cny("100"))), "合成培训支出", "合成例外说明");
        return new ExpensePolicyPort.Request("alice", ENTITY, ExpenseContent.Type.TRAINING, line,
                new ExpenseExchangeRate("CNY", "CNY", BigDecimal.ONE, "synthetic-identity-v1", DATE), List.of());
    }
    private static Money cny(String value) { return new Money(new BigDecimal(value), "CNY"); }
    private static FinanceResult.Unavailable<?> invalid() { return unavailable(FinanceResult.Failure.INVALID_RESPONSE); }
    private static FinanceResult.Unavailable<?> unavailable(FinanceResult.Failure failure) { return new FinanceResult.Unavailable<>(failure); }
}
