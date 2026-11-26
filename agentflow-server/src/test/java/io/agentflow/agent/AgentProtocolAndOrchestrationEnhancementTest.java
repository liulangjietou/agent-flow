package io.agentflow.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentflow.common.JsonUtil;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 验证按用途独立端点/密钥、结构化输出 json_schema、可选 store 开关、推理模型用量及多票据连续抽取。
 * @author owlzhangfq@gmail.com
 */
class AgentProtocolAndOrchestrationEnhancementTest {
    private final JsonUtil json = new JsonUtil(new ObjectMapper().findAndRegisterModules());

    @Test
    void supportsPurposeSpecificEndpointStructuredOutputsOmittedStoreAndReasoningTokens() throws Exception {
        var capturedBody = new AtomicReference<String>();
        var capturedAuth = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] reply = """
                    {"model":"reasoning-v1","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"{\\"ok\\":true}"}}],
                     "usage":{"prompt_tokens":100,"completion_tokens":50,"total_tokens":230,"completion_tokens_details":{"reasoning_tokens":80}}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, reply.length);
            try (var out = exchange.getResponseBody()) { out.write(reply); }
        });
        server.start();
        try {
            var config = new AssistConfiguration();
            config.setEnabled(true);
            config.setEndpoint("https://default.example.invalid/v1/chat/completions");
            config.setModel("default-model");
            config.setApiKey("default-key");
            config.setIncludeStoreParam(false);
            config.setStructuredOutputsEnabled(true);
            var purpose = new AssistConfiguration.Purpose();
            purpose.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
            purpose.setProviderId("custom-reasoning");
            purpose.setModel("reasoning-v1");
            purpose.setApiKey("purpose-secret");
            purpose.setTimeoutSeconds(15);
            config.setPurposes(Map.of("approval-summary", purpose));

            var client = new OpenAiTextClient(config, json);
            var schema = Map.<String, Object>of("type", "object", "properties", Map.of("ok", Map.of("type", "boolean")), "required", List.of("ok"), "additionalProperties", false);
            var reply = client.complete("approval-summary-v1", config.targetDigest("approval-summary-v1"), "sys", Map.of("a", 1), schema);

            assertThat(reply.providerId()).isEqualTo("custom-reasoning");
            assertThat(reply.usage().status()).isEqualTo(OpenAiTextClient.UsageStatus.REPORTED);
            assertThat(reply.usage().totalTokens()).isEqualTo(230L);
            assertThat(capturedAuth.get()).isEqualTo("Bearer purpose-secret");
            var sent = json.readStrict(capturedBody.get(), com.fasterxml.jackson.databind.JsonNode.class);
            assertThat(sent.has("store")).isFalse();
            assertThat(sent.path("response_format").path("type").asText()).isEqualTo("json_schema");
            assertThat(sent.path("response_format").path("json_schema").path("strict").asBoolean()).isTrue();
        } finally { server.stop(0); }
    }

    @Test
    void multiInvoiceOrchestrationReturnsToReadyUntilAllAuthorizedInvoicesAreConfirmed() {
        Instant now = Instant.parse("2026-10-09T08:00:00Z");
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        var scope = new ExpenseAgentRun.Scope(List.of(), List.of(first, second), List.of(), 12);
        var context = new ExpenseAgentRun.Context(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "demo", "alice", "整理两张差旅发票", 1, 1, scope, "a".repeat(64), now, now.plusSeconds(1800));
        var run = new ExpenseAgentRun(context);
        run.plan(now, 60);
        run.decided(new ExpenseAgentRun.Decision(ExpenseAgentRun.Action.EXTRACT_INVOICE, first, null, "抽取第一张票"), now.plusSeconds(1));
        run.bind(ExpenseAgentRun.Action.EXTRACT_INVOICE, first, UUID.randomUUID(), now.plusSeconds(2));
        run.childConfirmed("{\"gross\":\"100.00\"}", now.plusSeconds(3));
        assertThat(run.state().status()).isEqualTo(ExpenseAgentRun.Status.READY);

        run.plan(now.plusSeconds(4), 60);
        run.decided(new ExpenseAgentRun.Decision(ExpenseAgentRun.Action.EXTRACT_INVOICE, second, null, "抽取第二张票"), now.plusSeconds(5));
        run.bind(ExpenseAgentRun.Action.EXTRACT_INVOICE, second, UUID.randomUUID(), now.plusSeconds(6));
        run.childConfirmed("{\"gross\":\"200.00\"}", now.plusSeconds(7));
        assertThat(run.state().status()).isEqualTo(ExpenseAgentRun.Status.NEEDS_CONFIRMATION);
        assertThat(run.last().decision().action()).isEqualTo(ExpenseAgentRun.Action.DRAFT);
    }
}
