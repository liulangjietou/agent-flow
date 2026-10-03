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
 * 回环验证挂账差额真实写命令、原编号查询、严格结果契约及外部副作用隔离。
 * @author owlzhangfq@gmail.com
 */
class GatewayExpenseAccrualReductionTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule()).registerModule(new FinanceJsonConfiguration().financeMoneyModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final JsonUtil json = new JsonUtil(mapper);
    private final AtomicReference<Function<JsonNode, String>> responder = new AtomicReference<>();
    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>(), idempotency = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final Instant createdAt = Instant.now().minusSeconds(40);
    private final VoucherCommand command = command();
    private HttpServer server;
    private ExecutorService executor;
    private FinanceGatewayConfiguration configuration;
    private FinanceGatewayClient client;
    private GatewayExpenseAccrualReduction gateway;
    private String target;
    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/finance/", exchange -> {
            calls.incrementAndGet(); path.set(exchange.getRequestURI().getPath()); idempotency.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            var body = json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonNode.class); received.set(body);
            byte[] answer = responder.get().apply(body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, answer.length);
            try { exchange.getResponseBody().write(answer); } finally { exchange.close(); }
        });
        server.start(); configuration = FinanceGatewayConfigurationTest.configured("http://127.0.0.1:" + server.getAddress().getPort() + "/finance", "synthetic-token");
        client = new FinanceGatewayClient(configuration, mapper); gateway = new GatewayExpenseAccrualReduction(client);
        target = configuration.destination("tenant-a").orElseThrow().digest("tenant-a"); answer(posted(reduction(false)));
    }
    @AfterEach void stop() { TransactionSynchronizationManager.setActualTransactionActive(false); server.stop(0); executor.shutdownNow(); }


    @Test void postPreservesBusinessIdentityDigestOriginalAndDerivedReverseLines() {
        var value = reduction(false); var response = posted(value); answer(response);
        assertThat(gateway.post(target, value).requireValue()).isEqualTo(response);
        assertThat(path.get()).isEqualTo("/finance/expense-accrual-reduction-command"); assertThat(idempotency.get()).isEqualTo(value.id().toString());
        assertThat(received.get().path("requestId").asText()).isEqualTo(value.id().toString());
        assertThat(received.get().path("data").path("commandDigest").asText()).isEqualTo(value.digest());
        assertThat(json.read(received.get().path("data").path("command").toString(), ExpenseAccrualReductionCommand.class)).isEqualTo(value);
        assertThat(received.get().path("data").path("lines").size()).isEqualTo(3);
        assertThat(received.get().path("data").path("lines").get(0).path("side").asText()).isEqualTo("CREDIT");
        gateway.post(target, value); assertThat(idempotency.get()).isEqualTo(value.id().toString()); assertThat(calls.get()).isEqualTo(2);
    }

    @Test void supportedLargeAccrualPreservesEveryActualEntryThroughPostAndQuery() {
        var value = largeReduction();
        var response = posted(value);
        assertThat(json.write(response).getBytes(StandardCharsets.UTF_8).length).isGreaterThan(FinanceGatewayClient.MAX_RESPONSE_BYTES);
        answer(response);
        assertThat(gateway.post(target, value).requireValue()).isEqualTo(response);
        assertThat(gateway.query(target, value).requireValue()).isEqualTo(response);
        assertThat(response.posting().voucher().lines()).hasSize(4001);
        assertThat(value.reducedAmount()).isEqualTo(new Money(new BigDecimal("800"), "CNY"));
    }
    @Test void expiredAuthorizationBlocksPostButStillQueriesTheOriginalIdentityWithoutWriteHeaders() {
        var value = reduction(true); var response = posted(value); answer(response);
        assertThatThrownBy(() -> gateway.post(target, value)).isInstanceOf(io.agentflow.common.DomainException.class); assertThat(calls.get()).isZero();
        assertThat(gateway.query(target, value).requireValue()).isEqualTo(response);
        assertThat(path.get()).isEqualTo("/finance/expense-accrual-reduction-query"); assertThat(idempotency.get()).isNull();
        assertThat(received.get().path("data").path("operationId").asText()).isEqualTo(value.id().toString());
        assertThat(received.get().path("data").path("commandDigest").asText()).isEqualTo(value.digest());
        var correlation = received.get().path("requestId").asText(); gateway.query(target, value);
        assertThat(received.get().path("requestId").asText()).isNotEqualTo(correlation);
    }
    @Test void pendingFailureAndQueryOnlyNotFoundRemainExplicit() {
        var value = reduction(false);
        for (var status : ExpenseAccrualReductionObservation.Status.values()) {
            if (status == ExpenseAccrualReductionObservation.Status.POSTED) continue;
            var response = new ExpenseAccrualReductionObservation(value.id(), value.adjustmentId(), value.digest(), status, status == ExpenseAccrualReductionObservation.Status.NOT_FOUND ? 0 : 1,
                    Instant.now(), status == ExpenseAccrualReductionObservation.Status.NOT_FOUND ? null : "acceptance", null,
                    status == ExpenseAccrualReductionObservation.Status.FAILED ? ExpenseAccrualReductionObservation.Rejection.ACCOUNTING_PERIOD_CLOSED : null);
            answer(response); assertThat(gateway.query(target, value).requireValue()).isEqualTo(response);
            if (status == ExpenseAccrualReductionObservation.Status.NOT_FOUND) assertInvalid(gateway.post(target, value));
            else assertThat(gateway.post(target, value).requireValue()).isEqualTo(response);
        }
    }
    @Test void foreignIdentityWrongDigestChangedPeriodAndIncompletePostingAreRejected() {
        var value = reduction(false);
        List<Consumer<ObjectNode>> changes = List.of(
                v -> v.put("operationId", UUID.randomUUID().toString()), v -> v.put("adjustmentId", UUID.randomUUID().toString()), v -> v.put("commandDigest", "b".repeat(64)),
                v -> v.put("revision", "2"), v -> v.putNull("posting"), v -> v.put("observedAt", Instant.now().plusSeconds(100).toString()),
                v -> ((ObjectNode) v.path("posting").path("voucher")).put("periodReference", "other-period"),
                v -> ((ObjectNode) v.path("posting").path("voucher")).put("accountingDate", LocalDate.now().plusDays(10).toString()),
                v -> ((ObjectNode) v.path("posting").path("voucher").path("lines").get(0)).put("accountCode", "other-account"),
                v -> ((ObjectNode) v.path("posting")).put("beforeDigest", "b".repeat(64)),
                v -> ((ObjectNode) v.path("posting")).put("afterDigest", "b".repeat(64)),
                v -> ((ObjectNode) v.at("/posting/original")).put("voucherReference", "another-original"),
                v -> ((ObjectNode) v.path("posting")).put("adjustmentRevision", 2),
                v -> ((ObjectNode) v.at("/posting/voucher/lines/0")).put("side", "DEBIT"),
                v -> v.put("hiddenOverride", true));
        for (var change : changes) {
            responder.set(input -> { var output = success(input, posted(value)); change.accept((ObjectNode) output.path("data")); return json.write(output); });
            assertInvalid(gateway.post(target, value));
        }
    }
    @Test void configurationBindingAndOpenTransactionsPreventNetworkCalls() {
        var value = reduction(false);
        assertThat(gateway.post("b".repeat(64), value)).isEqualTo(new FinanceResult.Unavailable<>(FinanceResult.Failure.TARGET_CHANGED));
        assertThat(gateway.query("b".repeat(64), value)).isEqualTo(new FinanceResult.Unavailable<>(FinanceResult.Failure.TARGET_CHANGED));
        for (var operation : List.of(FinanceGatewayClient.Operation.EXPENSE_ACCRUAL_REDUCTION_COMMAND, FinanceGatewayClient.Operation.EXPENSE_ACCRUAL_REDUCTION_QUERY)) {
            assertThatThrownBy(() -> client.read("tenant-a", operation, value, ExpenseAccrualReductionObservation.class, v -> true)).isInstanceOf(IllegalArgumentException.class);
        }
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> gateway.post(target, value)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> gateway.query(target, value)).isInstanceOf(IllegalStateException.class); assertThat(calls.get()).isZero();
    }
    @Test void genericRejectionCannotPretendThatAnUnknownWriteWasNotExecuted() {
        var value = reduction(false);
        responder.set(input -> json.write(Map.of("contractVersion", 1, "tenantId", "tenant-a", "requestId", input.path("requestId").asText(), "outcome", "REJECTED", "reason", "ACCOUNTING_PERIOD_CLOSED")));
        assertInvalid(gateway.post(target, value));
    }

    @Test void timedOutPostingUsesOnlyOriginalQueryAndNeverAutomaticallyPostsAgain() throws Exception {
        var value = reduction(false);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        responder.set(input -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return json.write(success(input, posted(value)));
        });
        configuration.getTenants().get("tenant-a").setTimeoutSeconds(1);
        try {
            assertThat(gateway.post(target, value)).isEqualTo(new FinanceResult.Unavailable<>(FinanceResult.Failure.TIMEOUT));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(calls.get()).isEqualTo(1);
            answer(posted(value));
            assertThat(gateway.query(target, value).requireValue().status()).isEqualTo(ExpenseAccrualReductionObservation.Status.POSTED);
            assertThat(calls.get()).isEqualTo(2);
            assertThat(path.get()).isEqualTo("/finance/expense-accrual-reduction-query");
            assertThat(idempotency.get()).isNull();
        } finally { release.countDown(); }
    }
    private void assertInvalid(FinanceResult<ExpenseAccrualReductionObservation> value) { assertThat(value).isEqualTo(new FinanceResult.Unavailable<>(FinanceResult.Failure.INVALID_RESPONSE)); }
    private ExpenseAccrualReductionCommand reduction(boolean expired) {
        var at = expired ? createdAt.plusSeconds(2) : Instant.now().minusMillis(10);
        var end = expired ? at.plusSeconds(1) : at.plusSeconds(120);
        var current = observation(VoucherObservation.Status.POSTED, 1, at);
        var date = command.accountingDate().plusDays(1);
        var period = new AccountingPeriodPort.OpenPeriod(new AccountingPeriodPort.Request(command.legalEntityId(), "CNY", date), "reverse-period", "v2", date, date.plusDays(1), at, end);
        var before = positions("94", "6", "100");
        var after = positions("75.2", "4.8", "80");
        return new ExpenseAccrualReductionCommand(UUID.randomUUID(), UUID.randomUUID(), new VoucherReversalPort.Request(command, current), null,
                before, after, period, "finance", "proof", "确认挂账差额", at, end);
    }
    private ExpenseAccrualReductionObservation posted(ExpenseAccrualReductionCommand value) {
        var at = Instant.now();
        var voucher = new VoucherReversalPort.Posting("reduction-posting", "reduction-voucher", value.period().periodReference(), value.period().request().accountingDate(), value.createdAt(),
                value.lines().stream().map(line -> new VoucherReversalPort.Line("entry-" + line.originalLineNo(), line.originalLineNo(), line.accountCode(), line.side(), line.amount(), line.sourceLineNo(), line.costCenter(), line.projectCode(), line.advanceId())).toList());
        var original = value.source().original();
        var current = new VoucherObservation(original.operationId(), original.commandDigest(), original.status(), original.revision(), at, original.postingReference(),
                original.voucherReference(), original.periodReference(), original.accountingDate(), original.debitTotal(), original.creditTotal(), original.postedAt(), null);
        var proof = new ExpenseAccrualReductionObservation.Posting(current, 1, value.beforeDigest(), value.afterDigest(), voucher);
        return new ExpenseAccrualReductionObservation(value.id(), value.adjustmentId(), value.digest(), ExpenseAccrualReductionObservation.Status.POSTED, 2, at, "acceptance", proof, null);
    }
    private ExpenseAccrualReductionCommand largeReduction() {
        var expense = new AccountMappingPort.Key(AccountMappingPort.Role.EXPENSE, "OFFICE");
        var payable = new AccountMappingPort.Key(AccountMappingPort.Role.EMPLOYEE_PAYABLE, "");
        var lines = new java.util.ArrayList<VoucherCommand.Line>();
        for (int sourceLine = 1; sourceLine <= 200; sourceLine++) {
            for (int allocation = 1; allocation <= 20; allocation++) {
                lines.add(new VoucherCommand.Line(lines.size() + 1, expense, VoucherCommand.Side.DEBIT, new Money(BigDecimal.ONE, "CNY"), sourceLine,
                        "成本中心".repeat(20) + allocation, "辅助项目".repeat(20), null));
            }
        }
        var amount = new Money(new BigDecimal("4000"), "CNY");
        lines.add(new VoucherCommand.Line(lines.size() + 1, payable, VoucherCommand.Side.CREDIT, amount, 0, null, null, null));
        var mapping = new AccountMappingPort.Mapping(new AccountMappingPort.Request(command.legalEntityId(), "CNY", List.of(expense, payable)), "v1", createdAt.minusSeconds(1), createdAt.plusSeconds(300),
                List.of(new AccountMappingPort.Entry(expense, "6601"), new AccountMappingPort.Entry(payable, "2241")));
        var source = new VoucherCommand(UUID.randomUUID(), command.tenantId(), VoucherCommand.Kind.EXPENSE_ACCRUAL, command.binding(), command.legalEntityId(), command.employeeId(), command.accountingDate(),
                new VoucherCommand.Totals(amount, Money.zero("CNY"), Money.zero("CNY")), command.period(), mapping, lines, null, createdAt, createdAt.plusSeconds(60));
        var original = new VoucherObservation(source.id(), source.digest(), VoucherObservation.Status.POSTED, 1L, createdAt.plusSeconds(1), "large-original-posting", "large-original-voucher",
                source.period().periodReference(), source.accountingDate(), amount, amount, createdAt, null);
        var before = lines.stream().map(line -> new VoucherReversalCommand.Line(line.lineNo(), mapping.account(line.account()), line.side(), line.amount(),
                line.sourceLineNo(), line.costCenter(), line.projectCode(), line.advanceId())).toList();
        var after = before.stream().map(line -> new VoucherReversalCommand.Line(line.originalLineNo(), line.accountCode(), line.side(),
                new Money(new BigDecimal(line.side() == VoucherCommand.Side.DEBIT ? "0.80" : "3200"), "CNY"),
                line.sourceLineNo(), line.costCenter(), line.projectCode(), line.advanceId())).toList();
        var at = Instant.now().minusMillis(10);
        var date = source.accountingDate().plusDays(1);
        var period = new AccountingPeriodPort.OpenPeriod(new AccountingPeriodPort.Request(source.legalEntityId(), "CNY", date), "large-period", "v1", date, date, at, at.plusSeconds(180));
        return new ExpenseAccrualReductionCommand(UUID.randomUUID(), UUID.randomUUID(), new VoucherReversalPort.Request(source, original), null,
                before, after, period, "finance", "large-proof", "减少大单挂账", at, at.plusSeconds(120));
    }
    private List<VoucherReversalCommand.Line> positions(String expense, String tax, String payable) {
        var amounts = List.of(expense, tax, payable);
        return command.lines().stream().map(line -> new VoucherReversalCommand.Line(line.lineNo(), command.mapping().account(line.account()), line.side(),
                new Money(new BigDecimal(amounts.get(line.lineNo() - 1)), "CNY"), line.sourceLineNo(), line.costCenter(), line.projectCode(), line.advanceId())).toList();
    }
    private VoucherCommand command() {
        var entity = UUID.randomUUID(); var business = UUID.randomUUID(); var date = LocalDate.now(); var amount = new Money(new BigDecimal("100"), "CNY");
        var expense = new AccountMappingPort.Key(AccountMappingPort.Role.EXPENSE, "OFFICE");
        var tax = new AccountMappingPort.Key(AccountMappingPort.Role.DEDUCTIBLE_TAX, "");
        var payable = new AccountMappingPort.Key(AccountMappingPort.Role.EMPLOYEE_PAYABLE, "");
        var period = new AccountingPeriodPort.OpenPeriod(new AccountingPeriodPort.Request(entity, "CNY", date), "period", "v1", date.minusDays(1), date.plusDays(1), createdAt.minusSeconds(1), createdAt.plusSeconds(300));
        var mapping = new AccountMappingPort.Mapping(new AccountMappingPort.Request(entity, "CNY", List.of(expense, tax, payable)), "v1", createdAt.minusSeconds(1), createdAt.plusSeconds(300),
                List.of(new AccountMappingPort.Entry(expense, "6601"), new AccountMappingPort.Entry(tax, "2221"), new AccountMappingPort.Entry(payable, "2241")));
        return new VoucherCommand(UUID.randomUUID(), "tenant-a", VoucherCommand.Kind.EXPENSE_ACCRUAL, new VoucherCommand.Binding(business, UUID.randomUUID(), 1, 3, 2), entity, "alice", date,
                new VoucherCommand.Totals(amount, new Money(new BigDecimal("6"), "CNY"), Money.zero("CNY")), period, mapping,
                List.of(new VoucherCommand.Line(1, expense, VoucherCommand.Side.DEBIT, new Money(new BigDecimal("94"), "CNY"), 1, "IT", null, null),
                        new VoucherCommand.Line(2, tax, VoucherCommand.Side.DEBIT, new Money(new BigDecimal("6"), "CNY"), 1, "IT", null, null),
                        new VoucherCommand.Line(3, payable, VoucherCommand.Side.CREDIT, amount, 0, null, null, null)), null, createdAt, createdAt.plusSeconds(60));
    }
    private VoucherObservation observation(VoucherObservation.Status status, long revision, Instant at) {
        return new VoucherObservation(command.id(), command.digest(), status, revision, at, "original-posting", "original-voucher", command.period().periodReference(), command.accountingDate(), command.totals().gross(), command.totals().gross(), createdAt, null);
    }
    private void answer(Object value) { responder.set(input -> json.write(success(input, value))); }
    private ObjectNode success(JsonNode input, Object value) { return json.read(json.write(Map.of("contractVersion", 1, "tenantId", "tenant-a", "requestId", input.path("requestId").asText(), "outcome", "SUCCESS", "data", value)), ObjectNode.class); }
}
