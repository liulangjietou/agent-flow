package io.agentflow.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.ExpenseExchangeRate;
import io.agentflow.expense.ExpenseLine;
import io.agentflow.expense.ExpensePolicyPort;
import io.agentflow.expense.ExpensePolicySnapshot;
import io.agentflow.expense.Invoice;
import io.agentflow.expense.InvoiceKey;
import io.agentflow.finance.BudgetCommand;
import io.agentflow.finance.BudgetObservation;
import io.agentflow.finance.BudgetPrecheckPort;
import io.agentflow.finance.EmployeeAccountPort;
import io.agentflow.finance.EmployeeAccountSnapshot;
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.finance.Money;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 报销子流程测试使用的合成财务系统；预算按原命令保存事实，不提供银行支付或凭证入账能力。
 * @author owlzhangfq@gmail.com
 */
final class ExpenseSubprocessGatewayFixture implements AutoCloseable {
    private final HttpServer server;
    private final Map<UUID, BudgetCommand> commands = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> writes = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> queries = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> appliedAt = new ConcurrentHashMap<>();
    private volatile Context context;
    private volatile RuntimeException failure;
    private volatile BudgetObservation.Status budgetStatus = BudgetObservation.Status.APPLIED;

    ExpenseSubprocessGatewayFixture() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/finance/", exchange -> {
                var current = context;
                try {
                    String operation = exchange.getRequestURI().getPath().substring("/finance/".length());
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
            throw new IllegalStateException("Cannot start synthetic expense gateway", failure);
        }
    }

    void use(JsonUtil json, UUID entity, String invoiceNumber) {
        context = new Context(json, entity, invoiceNumber);
        commands.clear(); writes.clear(); queries.clear(); appliedAt.clear(); failure = null;
        budgetStatus = BudgetObservation.Status.APPLIED;
    }
    String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/finance"; }
    RuntimeException failure() { return failure; }
    void budgetStatus(BudgetObservation.Status status) { budgetStatus = status; }
    int writes() { return writes.values().stream().mapToInt(AtomicInteger::get).sum(); }
    int queries() { return queries.values().stream().mapToInt(AtomicInteger::get).sum(); }
    int writes(UUID id) { return writes.getOrDefault(id, new AtomicInteger()).get(); }
    int queries(UUID id) { return queries.getOrDefault(id, new AtomicInteger()).get(); }
    List<BudgetCommand> commands() { return List.copyOf(commands.values()); }

    private Object data(Context current, String operation, JsonNode data) {
        return switch (operation) {
            case "catalog" -> new FinanceCatalog("alice", "synthetic-subprocess-v1", Instant.now().plusSeconds(600),
                    List.of(new FinanceCatalog.LegalEntity(current.entity(), "合成报销法人", "CNY", true, "entity-v1", "UTC")),
                    List.of(new FinanceCatalog.Category("OFFICE", "办公", List.of(ExpenseLine.Unit.ITEM))),
                    List.of(new FinanceCatalog.CostCenter(current.entity(), "IT", "研发")), List.of(), List.of(new FinanceCatalog.City("SH", "上海")));
            case "employee-account" -> new EmployeeAccountPort.Account(new EmployeeAccountSnapshot(current.entity(), "alice",
                    "synthetic-employee-account", "****1234", "a".repeat(64), "account-v1"), Instant.now().plusSeconds(600));
            case "exchange-rate" -> new ExpenseExchangeRate("CNY", "CNY", BigDecimal.ONE, "synthetic-rate", LocalDate.parse(data.path("rateDate").asText()));
            case "invoice-verification" -> new Invoice.VerifiedFacts(new InvoiceKey(InvoiceKey.Type.DIGITAL, null, current.invoiceNumber()), current.entity(),
                    money("100"), money("6"), LocalDate.now(), data.path("originalDigest").asText(), "synthetic-invoice", Instant.now().minusSeconds(1), Instant.now().plusSeconds(600));
            case "expense-policy" -> new ExpensePolicyPort.Assessment(new ExpensePolicySnapshot(UUID.randomUUID(), 1, money("100"), money("100"),
                    ExpensePolicySnapshot.Decision.WITHIN_LIMIT, "synthetic-tax", "synthetic-policy"), money("6"), false, Instant.now().plusSeconds(600));
            case "budget-precheck" -> new BudgetPrecheckPort.Assessment(current.json().read(data.toString(), BudgetPrecheckPort.Request.class),
                    "synthetic-precheck", Instant.now().minusSeconds(1), Instant.now().plusSeconds(600));
            case "budget-command", "budget-query" -> budget(current.json(), operation, data);
            default -> throw new IllegalArgumentException("Unexpected financial operation during expense subprocess approval: " + operation);
        };
    }

    private BudgetObservation budget(JsonUtil json, String operation, JsonNode data) {
        BudgetCommand command;
        if (operation.equals("budget-command")) {
            command = json.read(data.path("command").toString(), BudgetCommand.class);
            var previous = commands.putIfAbsent(command.id(), command);
            if (previous != null && !previous.equals(command)) throw new IllegalStateException("Synthetic budget command identity changed");
            writes.computeIfAbsent(command.id(), ignored -> new AtomicInteger()).incrementAndGet();
        } else {
            UUID id = UUID.fromString(data.path("operationId").asText());
            command = commands.get(id);
            if (command == null) throw new IllegalStateException("Synthetic query has no original command");
            queries.computeIfAbsent(id, ignored -> new AtomicInteger()).incrementAndGet();
        }
        var status = budgetStatus;
        return new BudgetObservation(command.id(), command.digest(), status,
                status == BudgetObservation.Status.APPLIED ? command.expected() == null ? 1L : command.expected().revision() + 1 : null,
                status == BudgetObservation.Status.APPLIED ? "synthetic-budget-" + command.id() : null,
                status == BudgetObservation.Status.APPLIED ? appliedAt.computeIfAbsent(command.id(), ignored -> Instant.now()) : null,
                status == BudgetObservation.Status.REJECTED ? BudgetObservation.Rejection.BUDGET_INSUFFICIENT : null);
    }

    private static Money money(String amount) { return new Money(new BigDecimal(amount), "CNY"); }
    /** 只关闭本用例的合成端口，已有验收服务保持运行。 */
    @Override public void close() { server.stop(0); }
    private record Context(JsonUtil json, UUID entity, String invoiceNumber) { }
}
