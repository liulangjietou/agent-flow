package io.agentflow.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.ExpenseContent;
import io.agentflow.expense.ExpenseLine;
import io.agentflow.expense.ExpenseReport;
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.notification.NotificationTexts;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;

/**
 * 用真实回环模型验证最小发送清单、主数据权限及报销建议的严格输出协议。
 * @author owlzhangfq@gmail.com
 */
class ExpenseDraftModelTest {
    private static final Instant AT = Instant.parse("2026-10-04T00:00:00Z");
    private static final ExpenseDraftAssistInput.Leg LEG = new ExpenseDraftAssistInput.Leg(1,
            LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-03"), "SH", "项目现场调研");
    private final JsonUtil json = new JsonUtil(new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    private final ExpenseDraftAssistInputs inputs = new ExpenseDraftAssistInputs(json);
    private final AssistConfiguration configuration = new AssistConfiguration();
    private final AtomicReference<String> reply = new AtomicReference<>();
    private final AtomicReference<JsonNode> request = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final UUID entity = UUID.randomUUID();
    private final UUID otherEntity = UUID.randomUUID();
    private final UUID reportId = UUID.randomUUID();
    private final UUID applicationId = UUID.randomUUID();
    private HttpServer server;
    private ExecutorService executor;
    private ExpenseDraftAssistRun.Context context;
    private OpenAiCompatibleExpenseDraftModel model;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); executor = Executors.newFixedThreadPool(2); server.setExecutor(executor);
        server.createContext("/model", exchange -> {
            calls.incrementAndGet(); request.set(json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonNode.class));
            byte[] body = json.write(Map.of("model", "synthetic-expense-v1", "choices", List.of(Map.of("finish_reason", "stop",
                    "message", Map.of("role", "assistant", "content", reply.get()))))).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        }); server.start();
        configuration.setEnabled(true); configuration.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/model");
        configuration.setModel("synthetic-expense"); configuration.setProviderId("synthetic"); configuration.setApiKey("synthetic-draft-key");
        var input = input(selection(), catalog("alice"), List.of(LEG));
        context = new ExpenseDraftAssistRun.Context(UUID.randomUUID(), "demo", "alice", AT, input,
                configuration.targetDigest(ExpenseDraftAssistRun.PROMPT_VERSION));
        model = new OpenAiCompatibleExpenseDraftModel(configuration, json); reply.set(json.write(valid()));
    }
    @AfterEach void stop() { if (server != null) server.stop(0); if (executor != null) executor.shutdownNow(); }

    @Test void sendsOnlyExplicitItineraryAndCatalogAndKeepsPrivateReportContextLocal() {
        var suggestion = model.generate(context); suggestion.requireMatches(context.input());
        assertThat(suggestion.providerId()).isEqualTo("synthetic"); assertThat(suggestion.modelVersion()).isEqualTo("synthetic-expense-v1");
        JsonNode sent = json.read(request.get().path("messages").get(1).path("content").asText(), JsonNode.class);
        assertThat(sent.size()).isEqualTo(1); assertThat(sent.path("sources")).hasSize(3);
        assertThat(sent.toString()).doesNotContain(applicationId.toString(), reportId.toString(), entity.toString(), otherEntity.toString(),
                "alice", "只留本地的费用标题", "account", "FOREIGN", "OFFICE", "北京", "synthetic-draft-key");
        assertThat(sent.toString()).contains("现场调研", "HOTEL", "P01");
        assertThat(request.get().path("store").asBoolean()).isFalse(); assertThat(request.get().path("stream").asBoolean()).isFalse();
        assertThat(request.get().has("tools")).isFalse();
        assertThat(context.input().applicationVersion()).isEqualTo(1); assertThat(context.input().financialVersion()).isEqualTo(1);
    }

    @Test void emptySuggestionIsAValidAbstentionAndContainsNoPlaceholderExpense() {
        reply.set("{\"lines\":[]}"); assertThat(model.generate(context).lines()).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"amount", "allowance", "dates", "extra-top", "numeric-percent", "exponent", "unknown-leg", "category", "unit", "center", "project", "digest", "missing-evidence", "duplicate-key"})
    void rejectsUnsupportedFieldsAndUnboundModelOutput(String variant) {
        ObjectNode out = valid(); ObjectNode line = (ObjectNode) out.path("lines").get(0);
        ObjectNode share = (ObjectNode) line.path("allocations").get(0);
        switch (variant) {
            case "amount" -> line.put("claimedGross", "100");
            case "allowance" -> line.put("allowanceRate", "200");
            case "dates" -> line.put("incurredOn", "2026-10-01");
            case "extra-top" -> out.put("approved", true);
            case "numeric-percent" -> share.put("percent", 100);
            case "exponent" -> share.put("percent", "1e2");
            case "unknown-leg" -> line.put("itineraryId", 2);
            case "category" -> line.put("categoryCode", "OFFICE");
            case "unit" -> line.put("unit", "KILOMETER");
            case "center" -> share.put("costCenter", "FOREIGN");
            case "project" -> share.put("projectCode", "SECRET");
            case "digest" -> ((ObjectNode) line.path("evidence").get(0)).put("contentDigest", "f".repeat(64));
            case "missing-evidence" -> line.putArray("evidence").add(json.read(json.write(context.input().reference(ExpenseDraftAssistInput.CATALOG)), JsonNode.class));
            case "duplicate-key" -> { }
            default -> throw new AssertionError(variant);
        }
        String text = json.write(out); if (variant.equals("duplicate-key")) text = text.replace("\"id\":\"line1\"", "\"id\":\"line1\",\"id\":\"other\"");
        reply.set(text);
        assertThatThrownBy(() -> model.generate(context)).isInstanceOfSatisfying(AssistModelPort.ModelFailure.class,
                failure -> assertThat(failure.failure()).isEqualTo(AssistRun.Failure.INVALID_MODEL_OUTPUT));
    }

    @Test void changedModelTargetAndActiveTransactionDoNotSendTheOriginalContent() {
        configuration.setModel("another-model");
        assertThatThrownBy(() -> model.generate(context)).isInstanceOfSatisfying(AssistModelPort.ModelFailure.class,
                failure -> assertThat(failure.failure()).isEqualTo(AssistRun.Failure.MODEL_UNAVAILABLE));
        configuration.setModel("synthetic-expense"); TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThatThrownBy(() -> model.generate(context)).isInstanceOf(IllegalStateException.class); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        assertThat(calls.get()).isZero();
    }

    @Test void otherEmployeeAndForeignLegalEntityCatalogCannotBeSelected() {
        assertCode(() -> input(selection(), catalog("bob"), List.of(LEG)), "FORBIDDEN");
        var foreign = new ExpenseDraftAssistInputs.CatalogSelection(List.of("HOTEL"), List.of("FOREIGN"), List.of());
        assertCode(() -> input(foreign, catalog("alice"), List.of(LEG)), "FORBIDDEN");
        var unknown = new ExpenseDraftAssistInput.Leg(1, LEG.startsOn(), LEG.endsOn(), "UNLISTED", "现场调研");
        assertCode(() -> input(selection(), catalog("alice"), List.of(unknown)), "FORBIDDEN");
    }

    @Test void selectedCatalogCodesCannotRepeatAndUtf8PayloadIsBoundedBeforeTransmission() {
        assertCode(() -> new ExpenseDraftAssistInputs.CatalogSelection(List.of("HOTEL", "HOTEL"), List.of("IT"), List.of()), "INVALID_AGENT_INPUT");
        var huge = IntStream.rangeClosed(1, 20).mapToObj(id -> new ExpenseDraftAssistInput.Leg(id, LEG.startsOn(), LEG.endsOn(), "SH", "行".repeat(2000))).toList();
        assertCode(() -> input(selection(), catalog("alice"), huge), "INVALID_AGENT_INPUT"); assertThat(calls.get()).isZero();
    }

    private ExpenseDraftAssistInput input(ExpenseDraftAssistInputs.CatalogSelection selection, FinanceCatalog catalog, List<ExpenseDraftAssistInput.Leg> itinerary) {
        var application = Application.draftBusiness(applicationId, "demo", "synthetic-trip", "expense", 1, "alice", "只留本地的费用标题",
                Map.of("account", "local-only"), null, "expense:1:synthetic", new NotificationTexts(null, null, null),
                new BusinessReference(BusinessReference.Type.EXPENSE, reportId));
        var report = ExpenseReport.draft(reportId, "demo", applicationId, "alice", new ExpenseContent(entity, ExpenseContent.Type.TRAVEL,
                "只留本地的费用标题", List.of(), List.of()));
        return inputs.select(application, report, catalog, "a".repeat(64), "按行程整理住宿，全部归研发调研项目", itinerary, selection);
    }
    private static ExpenseDraftAssistInputs.CatalogSelection selection() { return new ExpenseDraftAssistInputs.CatalogSelection(List.of("HOTEL"), List.of("IT"), List.of("P01")); }
    private FinanceCatalog catalog(String employee) {
        return new FinanceCatalog(employee, "catalog-v1", AT.plusSeconds(300),
                List.of(new FinanceCatalog.LegalEntity(entity, "合成法人", "CNY", true, "v1", "UTC"),
                        new FinanceCatalog.LegalEntity(otherEntity, "其他法人", "CNY", true, "v1", "UTC")),
                List.of(new FinanceCatalog.Category("HOTEL", "住宿", List.of(ExpenseLine.Unit.NIGHT)), new FinanceCatalog.Category("OFFICE", "办公", List.of(ExpenseLine.Unit.ITEM))),
                List.of(new FinanceCatalog.CostCenter(entity, "IT", "研发"), new FinanceCatalog.CostCenter(otherEntity, "FOREIGN", "其他法人成本中心")),
                List.of(new FinanceCatalog.Project(entity, "P01", "调研项目")), List.of(new FinanceCatalog.City("SH", "上海"), new FinanceCatalog.City("BJ", "北京")));
    }
    private ObjectNode valid() {
        return json.read(json.write(Map.of("lines", List.of(Map.of("id", "line1", "itineraryId", 1, "categoryCode", "HOTEL", "unit", "NIGHT", "description", "现场调研住宿",
                "allocations", List.of(Map.of("costCenter", "IT", "projectCode", "P01", "percent", "100")),
                "evidence", List.of(context.input().reference(LEG.sourceId()), context.input().reference(ExpenseDraftAssistInput.CATALOG)))))), ObjectNode.class);
    }
    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, failure -> assertThat(failure.code()).isEqualTo(code));
    }
}
