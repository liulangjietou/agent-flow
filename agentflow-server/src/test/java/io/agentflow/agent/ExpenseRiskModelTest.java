package io.agentflow.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.ExpenseRiskEvidence;
import io.agentflow.finance.Money;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;

/**
 * 真实回环 HTTP 验证专用风险模型请求与严格输出，不使用企业模型或真实财务数据。
 * @author owlzhangfq@gmail.com
 */
class ExpenseRiskModelTest {
    private final JsonUtil json = new JsonUtil(new ObjectMapper().registerModule(new JavaTimeModule()));
    private final AssistConfiguration configuration = new AssistConfiguration();
    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private final AtomicReference<String> reply = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;
    private ExpenseRiskRun.Context context;

    @BeforeEach void prepareSyntheticEndpoint() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/model", exchange -> {
            requests.incrementAndGet(); received.set(json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonNode.class));
            byte[] body = reply.get().getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length); try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start(); configuration.setEnabled(true); configuration.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/model");
        configuration.setProviderId("synthetic-risk-provider"); configuration.setModel("synthetic-risk-model"); configuration.setTimeoutSeconds(2);
        var sources = new ExpenseRiskSources(json);
        var lines = List.of(line(1), line(2));
        var document = new ExpenseRiskInput.Document(1, UUID.randomUUID(), UUID.randomUUID(), 7, 2, 8, "a".repeat(64), List.of(1, 2));
        var catalog = sources.available(List.of(document), null, new ExpenseRiskEvidence.Input(lines, List.of(), null));
        var input = sources.select(catalog, catalog.sources().stream().map(source -> source.reference().sourceId()).toList());
        context = new ExpenseRiskRun.Context(UUID.randomUUID(), "private-tenant-marker", "private-person-marker", "private-task-marker", Instant.now(), input,
                configuration.targetDigest(ExpenseRiskRun.PROMPT_VERSION));
        reply.set(envelope(output()));
    }
    @AfterEach void stopSyntheticEndpoint() { if (server != null) server.stop(0); TransactionSynchronizationManager.clear(); }

    @Test void sendsOnlySelectedPublicSourcesAndParsesTheActualHttpReply() {
        var result = model().generate(context); result.requireMatches(context.input());
        assertThat(result.providerId()).isEqualTo("synthetic-risk-provider"); assertThat(result.modelVersion()).isEqualTo("reported-model-version");
        assertThat(result.promptVersion()).isEqualTo(ExpenseRiskRun.PROMPT_VERSION); assertThat(result.items()).hasSize(1);
        JsonNode request = received.get(); String wire = request.toString();
        assertThat(request.path("store").asBoolean()).isFalse(); assertThat(request.has("tools")).isFalse(); assertThat(request.path("stream").asBoolean()).isFalse();
        assertThat(wire).doesNotContain(context.tenantId(), context.requestedBy(), context.taskId(), context.id().toString(),
                context.input().documents().get(0).reportId().toString(), context.input().documents().get(0).applicationId().toString(), "snapshotDigest");
        var user = json.read(request.path("messages").get(1).path("content").asText(), JsonNode.class);
        assertThat(user.size()).isEqualTo(2); assertThat(user.path("sources").size()).isEqualTo(3); assertThat(user.path("concerns").size()).isEqualTo(1);
        assertThat(requests.get()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"command", "provider", "coercion", "unknown-kind", "changed-digest", "unknown-source", "missing-coverage", "missing-document", "missing-item", "extra-item", "blank-limitations", "no-checks", "duplicate-json", "tool-call", "truncated"})
    void rejectsUntrustedOutputWithoutTreatingItAsAnExecutableAction(String change) {
        ObjectNode output = output(); ObjectNode item = (ObjectNode) output.path("items").get(0);
        switch (change) {
            case "command" -> item.put("approve", true);
            case "provider" -> output.put("providerId", "forged-provider");
            case "coercion" -> item.put("explanation", 99);
            case "unknown-kind" -> item.put("kind", "APPROVE");
            case "changed-digest" -> ((ObjectNode) item.path("evidence").get(0)).put("contentDigest", "b".repeat(64));
            case "unknown-source" -> ((ObjectNode) item.path("evidence").get(0)).put("sourceId", "expense:document[9]");
            case "missing-coverage" -> ((ArrayNode) item.path("evidence")).remove(1);
            case "missing-document" -> ((ArrayNode) item.path("evidence")).remove(0);
            case "missing-item" -> ((ArrayNode) output.path("items")).removeAll();
            case "extra-item" -> { var extra = item.deepCopy(); extra.put("concernSourceId", "expense:risk[2]"); ((ArrayNode) output.path("items")).add(extra); }
            case "blank-limitations" -> item.put("limitations", " ");
            case "no-checks" -> ((ArrayNode) item.path("checks")).removeAll();
            default -> { }
        }
        reply.set(envelope(output));
        if (change.equals("duplicate-json")) reply.set(envelopeContent("{\"items\":[],\"items\":" + output.path("items") + "}"));
        if (change.equals("tool-call")) {
            var outer = (ObjectNode) json.read(reply.get(), JsonNode.class);
            ((ObjectNode) outer.path("choices").get(0).path("message")).putArray("tool_calls").addObject().put("id", "forged"); reply.set(json.write(outer));
        }
        if (change.equals("truncated")) reply.set(reply.get().replace("\"finish_reason\":\"stop\"", "\"finish_reason\":\"length\""));
        assertThatThrownBy(() -> model().generate(context)).isInstanceOfSatisfying(AssistModelPort.ModelFailure.class,
                failure -> assertThat(failure.failure()).isEqualTo(AssistRun.Failure.INVALID_MODEL_OUTPUT));
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test void changedDestinationOrDisabledModelDoesNotSendOriginalConsentToAnotherTarget() {
        configuration.setModel("different-model");
        assertThatThrownBy(() -> model().generate(context)).isInstanceOfSatisfying(AssistModelPort.ModelFailure.class,
                failure -> assertThat(failure.failure()).isEqualTo(AssistRun.Failure.MODEL_UNAVAILABLE));
        configuration.setModel("synthetic-risk-model"); configuration.setEnabled(false);
        assertThatThrownBy(() -> model().generate(context)).isInstanceOf(AssistModelPort.ModelFailure.class);
        assertThat(requests.get()).isZero();
    }

    @Test void activeDatabaseTransactionCannotHoldLocksAcrossModelHttp() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> model().generate(context)).isInstanceOf(IllegalStateException.class);
        assertThat(requests.get()).isZero();
    }

    private ObjectNode output() {
        var item = new ExpenseRiskSuggestion.Item(context.input().concerns().get(0).sourceId(), ExpenseRiskInput.Kind.SAME_DAY,
                "已选两行发生在同一天", "没有行程事实，不能认定重复费用", List.of("核对两次出行用途"),
                context.input().sources().stream().map(AssistModelPort.Source::reference).toList());
        return (ObjectNode) json.read(json.write(Map.of("items", List.of(item))), JsonNode.class);
    }
    private String envelope(ObjectNode output) { return envelopeContent(json.write(output)); }
    private String envelopeContent(String content) { return json.write(Map.of("model", "reported-model-version", "choices",
            List.of(Map.of("finish_reason", "stop", "message", Map.of("role", "assistant", "content", content))))); }
    private OpenAiCompatibleExpenseRiskModel model() { return new OpenAiCompatibleExpenseRiskModel(configuration, json); }
    private static ExpenseRiskEvidence.Line line(int number) { return new ExpenseRiskEvidence.Line(new ExpenseRiskEvidence.LineId(1, number), "TAXI",
            LocalDate.of(2026, 10, 4), new Money(new BigDecimal("10.00"), "CNY"), 0); }
}
