package io.agentflow.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import io.agentflow.common.JsonUtil;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模型链路的韧性契约：429 在超时预算内按 Retry-After 退避；协议不合规有界修复且用量合计；按用途覆盖模型与输出上限。
 * 使用真实回环 HTTP，不连接任何真实模型。
 * @author owlzhangfq@gmail.com
 */
class AgentModelResilienceTest {
    private final JsonUtil json = new JsonUtil(new ObjectMapper().registerModule(new JavaTimeModule()));
    private final AssistConfiguration configuration = new AssistConfiguration();
    /** 每个元素为一次响应：状态码、Retry-After（可空）和正文。 */
    private final ConcurrentLinkedQueue<Object[]> replies = new ConcurrentLinkedQueue<>();
    private final List<JsonNode> received = new CopyOnWriteArrayList<>();
    private HttpServer server;

    @BeforeEach void startSyntheticEndpoint() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/model", exchange -> {
            received.add(json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonNode.class));
            Object[] next = replies.poll();
            byte[] body = ((String) next[2]).getBytes(StandardCharsets.UTF_8);
            if (next[1] != null) exchange.getResponseHeaders().set("Retry-After", (String) next[1]);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders((Integer) next[0], body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        configuration.setEnabled(true); configuration.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/model");
        configuration.setProviderId("synthetic-provider"); configuration.setModel("synthetic-default-model"); configuration.setTimeoutSeconds(3);
    }
    @AfterEach void stopSyntheticEndpoint() { server.stop(0); }

    @Test void rateLimitedRequestIsRetriedWithinTheTimeoutBudget() {
        replies.add(new Object[]{429, "0", "{}"});
        replies.add(new Object[]{200, null, envelope(finish(), 10, 2)});
        var decision = new ExpenseAgentModel(configuration, json).decide(context(), Map.of("goal", "核对"));
        assertThat(decision.action()).isEqualTo(ExpenseAgentRun.Action.FINISH);
        assertThat(received).hasSize(2);
    }

    @Test void retryAfterBeyondTheRemainingBudgetFailsWithoutWaiting() {
        configuration.setTimeoutSeconds(1);
        replies.add(new Object[]{429, "5", "{}"});
        long started = System.nanoTime();
        assertThatThrownBy(() -> new ExpenseAgentModel(configuration, json).decide(context(), Map.of("goal", "核对")))
                .isInstanceOfSatisfying(AssistModelPort.ModelFailure.class,
                        failure -> assertThat(failure.failure()).isEqualTo(AssistRun.Failure.MODEL_UNAVAILABLE));
        assertThat(received).hasSize(1);
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(1));
    }

    @Test void retryAfterHeaderIsBoundedAndToleratesHttpDates() throws Exception {
        assertThat(OpenAiTextClient.retryAfterNanos(response("2"))).isEqualTo(2_000_000_000L);
        assertThat(OpenAiTextClient.retryAfterNanos(response("3600"))).isEqualTo(10_000_000_000L);
        assertThat(OpenAiTextClient.retryAfterNanos(response("Wed, 21 Oct 2026 07:28:00 GMT"))).isEqualTo(1_000_000_000L);
        assertThat(OpenAiTextClient.retryAfterNanos(response(null))).isEqualTo(1_000_000_000L);
    }

    @Test void invalidDecisionIsRepairedOnceAndUsageIsAccumulated() {
        replies.add(new Object[]{200, null, envelope("{\"action\":\"DELETE\",\"referenceId\":null,\"lineNo\":null,\"message\":\"x\"}", 100, 5)});
        replies.add(new Object[]{200, null, envelope(finish(), 120, 6)});
        var usage = new java.util.concurrent.atomic.AtomicReference<OpenAiTextClient.Usage>();
        var decision = observe(usage, () -> new ExpenseAgentModel(configuration, json).decide(context(), Map.of("goal", "核对")));
        assertThat(decision.action()).isEqualTo(ExpenseAgentRun.Action.FINISH);
        assertThat(received).hasSize(2);
        assertThat(received.get(1).path("messages").get(0).path("content").asText()).contains("上一次回复不符合");
        assertThat(usage.get()).isEqualTo(new OpenAiTextClient.Usage(OpenAiTextClient.UsageStatus.REPORTED, 220L, 11L, 231L));
    }

    @Test void repairIsBoundedAndCanBeDisabled() {
        configuration.setInvalidOutputRepairAttempts(0);
        replies.add(new Object[]{200, null, envelope("not-a-decision", 1, 1)});
        assertThatThrownBy(() -> new ExpenseAgentModel(configuration, json).decide(context(), Map.of("goal", "核对")))
                .isInstanceOfSatisfying(AssistModelPort.ModelFailure.class,
                        failure -> assertThat(failure.failure()).isEqualTo(AssistRun.Failure.INVALID_MODEL_OUTPUT));
        assertThat(received).hasSize(1);
    }

    @Test void transportFailureIsNotRepairedBecauseItsOutcomeIsUnknown() {
        replies.add(new Object[]{500, null, "{}"});
        assertThatThrownBy(() -> new ExpenseAgentModel(configuration, json).decide(context(), Map.of("goal", "核对")))
                .isInstanceOfSatisfying(AssistModelPort.ModelFailure.class,
                        failure -> assertThat(failure.failure()).isEqualTo(AssistRun.Failure.MODEL_UNAVAILABLE));
        assertThat(received).hasSize(1);
    }

    @Test void purposeOverrideSelectsModelAndTokenLimitAndEntersTheTargetDigest() {
        String before = configuration.targetDigest(ExpenseAgentRun.PROMPT_VERSION);
        var purpose = new AssistConfiguration.Purpose(); purpose.setModel("synthetic-agent-model"); purpose.setMaxCompletionTokens(1024);
        configuration.setPurposes(Map.of("expense-handling-agent", purpose));
        configuration.requireAvailable();
        assertThat(configuration.targetDigest(ExpenseAgentRun.PROMPT_VERSION)).isNotEqualTo(before);
        assertThat(configuration.modelFor(AssistConfiguration.PROMPT_VERSION)).isEqualTo("synthetic-default-model");
        replies.add(new Object[]{200, null, envelope(finish(), 1, 1)});
        new ExpenseAgentModel(configuration, json).decide(context(), Map.of("goal", "核对"));
        assertThat(received.get(0).path("model").asText()).isEqualTo("synthetic-agent-model");
        assertThat(received.get(0).path("max_completion_tokens").asInt()).isEqualTo(1024);
    }

    @Test void invalidPurposeOverrideMakesTheModelUnavailable() {
        var purpose = new AssistConfiguration.Purpose(); purpose.setMaxCompletionTokens(0);
        configuration.setPurposes(Map.of("expense-handling-agent", purpose));
        assertThatThrownBy(configuration::requireAvailable).isInstanceOf(io.agentflow.common.DomainException.class);
    }

    @Test void usageCombinationNeverTurnsMissingIntoZero() {
        var reported = new OpenAiTextClient.Usage(OpenAiTextClient.UsageStatus.REPORTED, 1L, 1L, 2L);
        var missing = new OpenAiTextClient.Usage(OpenAiTextClient.UsageStatus.NOT_REPORTED, null, null, null);
        var invalid = new OpenAiTextClient.Usage(OpenAiTextClient.UsageStatus.INVALID, null, null, null);
        assertThat(AgentExecutionTelemetry.combine(reported, missing).status()).isEqualTo(OpenAiTextClient.UsageStatus.NOT_REPORTED);
        assertThat(AgentExecutionTelemetry.combine(missing, invalid).status()).isEqualTo(OpenAiTextClient.UsageStatus.INVALID);
        assertThat(AgentExecutionTelemetry.combine(reported, new OpenAiTextClient.Usage(OpenAiTextClient.UsageStatus.REPORTED, Long.MAX_VALUE, 0L, Long.MAX_VALUE)).status())
                .isEqualTo(OpenAiTextClient.UsageStatus.INVALID);
    }

    /** 借用遥测的线程作用域收集用量，不访问数据库。 */
    private static <T> T observe(java.util.concurrent.atomic.AtomicReference<OpenAiTextClient.Usage> sink, java.util.function.Supplier<T> operation) {
        var repository = org.mockito.Mockito.mock(AgentUsageRepository.class);
        org.mockito.Mockito.when(repository.begin(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> new AgentExecutionUsage(invocation.getArgument(3),
                        invocation.getArgument(2), UUID.randomUUID(), invocation.getArgument(4), invocation.getArgument(4), null, 0L, null,
                        null, null, null, null, null, null, null, null));
        org.mockito.Mockito.doAnswer(invocation -> {
            AgentExecutionUsage value = invocation.getArgument(1);
            sink.set(new OpenAiTextClient.Usage(OpenAiTextClient.UsageStatus.valueOf(value.usageStatus()), value.inputTokens(), value.outputTokens(), value.totalTokens()));
            return null;
        }).when(repository).finish(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        return new AgentExecutionTelemetry(repository).execute("tenant", "user", AgentExecutionUsage.Kind.HANDLING, UUID.randomUUID(), Instant.now(), operation);
    }

    private ExpenseAgentRun.Context context() {
        return new ExpenseAgentRun.Context(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "tenant", "owner", "核对报销",
                1, 1, new ExpenseAgentRun.Scope(List.of(), List.of(), List.of(), 4), configuration.targetDigest(ExpenseAgentRun.PROMPT_VERSION),
                Instant.now(), Instant.now().plusSeconds(600));
    }

    private static String finish() { return "{\"action\":\"FINISH\",\"referenceId\":null,\"lineNo\":null,\"message\":\"已完成\"}"; }

    private String envelope(String content, long input, long output) {
        return json.write(Map.of("model", "reported-model", "choices", List.of(Map.of("finish_reason", "stop",
                        "message", Map.of("role", "assistant", "content", content))),
                "usage", Map.of("prompt_tokens", input, "completion_tokens", output, "total_tokens", input + output)));
    }

    private static java.net.http.HttpResponse<Object> response(String retryAfter) {
        @SuppressWarnings("unchecked")
        java.net.http.HttpResponse<Object> response = org.mockito.Mockito.mock(java.net.http.HttpResponse.class);
        var headers = java.net.http.HttpHeaders.of(retryAfter == null ? Map.of() : Map.of("Retry-After", List.of(retryAfter)), (name, value) -> true);
        org.mockito.Mockito.when(response.headers()).thenReturn(headers);
        return response;
    }
}
