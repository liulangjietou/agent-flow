package io.agentflow.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.agentflow.budget.BudgetLedgerPort;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.ExpenseExchangeRate;
import io.agentflow.expense.ExpenseLine;
import io.agentflow.expense.InvoiceKey;
import io.agentflow.finance.EmployeeAccountPort;
import io.agentflow.finance.EmployeeAccountSnapshot;
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.finance.Money;
import io.agentflow.procurement.ProcurementPayablePort;
import io.agentflow.procurement.SupplierAccountSnapshot;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 父子财务联动测试的只读企业 HTTP 夹具；任何支付或账务写操作均不提供成功响应。
 * @author owlzhangfq@gmail.com
 */
final class SubprocessFinanceGatewayFixture implements AutoCloseable {
    private final HttpServer server;
    private final Map<String, AtomicInteger> reads = new ConcurrentHashMap<>();
    private volatile Context context;
    private volatile RuntimeException failure;

    SubprocessFinanceGatewayFixture() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/finance/", exchange -> {
                String operation = exchange.getRequestURI().getPath().substring("/finance/".length());
                reads.computeIfAbsent(operation, ignored -> new AtomicInteger()).incrementAndGet();
                var current = context;
                try {
                    var request = current.json().read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonNode.class);
                    var response = Map.of("contractVersion", 1, "tenantId", request.path("tenantId").asText(),
                            "requestId", request.path("requestId").asText(), "outcome", "SUCCESS",
                            "data", data(current, operation, request.path("data")));
                    byte[] bytes = current.json().write(response).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (RuntimeException rejected) {
                    failure = rejected;
                    exchange.sendResponseHeaders(500, -1);
                } finally {
                    exchange.close();
                }
            });
            server.start();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot start synthetic finance gateway", failure);
        }
    }

    void use(JsonUtil json, UUID entity) { context = new Context(json, entity); reads.clear(); failure = null; }
    RuntimeException failure() { return failure; }
    String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/finance"; }
    Map<String, Integer> calls() {
        return reads.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().get()));
    }

    private Object data(Context current, String operation, JsonNode data) {
        return switch (operation) {
            case "catalog" -> new FinanceCatalog("alice", "synthetic-subprocess-v1", Instant.now().plusSeconds(600),
                    List.of(new FinanceCatalog.LegalEntity(current.entity(), "合成法人", "CNY", false, "entity-v1", "Asia/Shanghai")),
                    List.of(new FinanceCatalog.Category("TRAVEL", "差旅", List.of(ExpenseLine.Unit.ITEM))),
                    List.of(new FinanceCatalog.CostCenter(current.entity(), "IT", "研发")), List.of(), List.of(new FinanceCatalog.City("SH", "上海")));
            case "exchange-rate" -> new ExpenseExchangeRate(data.path("fromCurrency").asText(), data.path("toCurrency").asText(),
                    new BigDecimal("7.1"), "synthetic-rate", LocalDate.parse(data.path("rateDate").asText()));
            case "employee-account" -> new EmployeeAccountPort.Account(new EmployeeAccountSnapshot(current.entity(), "alice",
                    "synthetic-private-account", "****1234", "a".repeat(64), "account-v1"), Instant.now().plusSeconds(600));
            case "procurement-payable" -> payable(current.json().read(data.toString(), ProcurementPayablePort.Request.class));
            case "budget-ledger" -> ledger(current.json().read(data.toString(), BudgetLedgerPort.Request.class));
            default -> throw new IllegalArgumentException("Unexpected financial write during subprocess approval: " + operation);
        };
    }

    private ProcurementPayablePort.Payable payable(ProcurementPayablePort.Request request) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String invoiceNumber = String.format("%020d", Integer.toUnsignedLong((request.legalEntityId() + request.payableReference()).hashCode()));
        return new ProcurementPayablePort.Payable(request, "synthetic-ap-v1", now, now.plusSeconds(300), "合成供应商",
                new SupplierAccountSnapshot(request.legalEntityId(), request.supplierReference(), "synthetic-supplier-account", "****3456", "a".repeat(64), "account-v1"),
                "CONTRACT-1", "ORDER-1", "MATCH-1", "ACCRUAL-1", "BUDGET-RECOGNITION-1", LocalDate.now().plusDays(10), money("100"), money("30"),
                List.of(new ProcurementPayablePort.MatchedLine(1, 1, "ACCEPTANCE-1", new InvoiceKey(InvoiceKey.Type.DIGITAL, null, invoiceNumber),
                        1, "b".repeat(64), "synthetic-verification", "件", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                        money("100"), money("100"), money("100"), money("10"))));
    }

    private BudgetLedgerPort.Snapshot ledger(BudgetLedgerPort.Request request) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        int year = request.accountingDate().getYear();
        var positions = request.budgetReferences().stream().map(reference -> new BudgetLedgerPort.Position(request.legalEntityId(), reference,
                "合成预算", "v1", Integer.toString(year), LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31),
                BudgetLedgerPort.PeriodStatus.OPEN, money("1000"), money("300"), money("450"))).toList();
        return new BudgetLedgerPort.Snapshot(request, "ledger-v1", now, now.plusSeconds(300), positions);
    }

    private static Money money(String amount) { return new Money(new BigDecimal(amount), "CNY"); }
    /** 用例组结束后关闭自己的回环服务，不影响既有验收服务。 */
    @Override public void close() { server.stop(0); }
    private record Context(JsonUtil json, UUID entity) { }
}
