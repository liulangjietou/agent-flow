package io.agentflow.agent;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.common.JsonUtil;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 摘要、草稿和票据共用的有界 JSON 输出传输，不解释业务输出，也不执行工具或重定向。
 * @author owlzhangfq@gmail.com
 */
final class OpenAiTextClient {
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private final AssistConfiguration configuration;
    private final JsonUtil json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();

    OpenAiTextClient(AssistConfiguration configuration, JsonUtil json) { this.configuration = configuration; this.json = json; }

    /** 发送前核对本次实际目的地，凭据只写入请求头；响应体在接收期间限额。 */
    Reply complete(String promptVersion, String expectedTarget, String instruction, Object input) {
        return completeMessage(promptVersion, expectedTarget, instruction, json.write(input));
    }

    /** 票据适配器提供内联图片和 XML 文本块，不接收用户定义的请求内容块。 */
    Reply completeContent(String promptVersion, String expectedTarget, String instruction, List<Map<String, Object>> parts) {
        return completeMessage(promptVersion, expectedTarget, instruction, parts);
    }

    private Reply completeMessage(String promptVersion, String expectedTarget, String instruction, Object content) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Assist model must run outside a transaction");
        try { configuration.requireAvailable(); }
        catch (io.agentflow.common.DomainException unavailable) {
            throw new AssistModelPort.ModelFailure(AssistRun.Failure.MODEL_UNAVAILABLE);
        }
        var endpoint = configuration.uri(); String provider = configuration.getProviderId(), model = configuration.getModel();
        String apiKey = configuration.getApiKey(); int timeout = configuration.getTimeoutSeconds();
        if (!AssistConfiguration.digest(endpoint + "\n" + provider + "\n" + model + "\n" + promptVersion).equals(expectedTarget)) {
            throw new AssistModelPort.ModelFailure(AssistRun.Failure.MODEL_UNAVAILABLE);
        }
        String body = json.write(Map.of("model", model, "stream", false, "store", false,
                "max_completion_tokens", 4096, "response_format", Map.of("type", "json_object"),
                "messages", List.of(Map.of("role", "system", "content", instruction), Map.of("role", "user", "content", content))));
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(timeout))
                .header("Content-Type", "application/json; charset=utf-8").POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (!apiKey.isBlank()) request.header("Authorization", "Bearer " + apiKey);
        var future = client.sendAsync(request.build(), response -> new BoundedBody());
        try {
            var response = future.get(timeout, TimeUnit.SECONDS);
            if (response.statusCode() != 200) throw new AssistModelPort.ModelFailure(AssistRun.Failure.MODEL_UNAVAILABLE);
            return parse(provider, new String(response.body(), StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new AssistModelPort.ModelFailure(AssistRun.Failure.MODEL_UNAVAILABLE);
        } catch (TimeoutException timeoutFailure) { throw new AssistModelPort.ModelFailure(AssistRun.Failure.MODEL_TIMEOUT); }
        catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof AssistModelPort.ModelFailure modelFailure) throw modelFailure;
            throw new AssistModelPort.ModelFailure(cause instanceof java.net.http.HttpTimeoutException ? AssistRun.Failure.MODEL_TIMEOUT : AssistRun.Failure.MODEL_UNAVAILABLE);
        } finally { if (!future.isDone()) future.cancel(true); }
    }

    private Reply parse(String provider, String body) {
        try {
            JsonNode response = json.readStrict(body, JsonNode.class);
            var choices = response.path("choices");
            if (!choices.isArray() || choices.size() != 1 || !response.path("model").isTextual()) throw invalid();
            var choice = choices.get(0); var message = choice.path("message");
            if (!"stop".equals(choice.path("finish_reason").asText()) || !"assistant".equals(message.path("role").asText())
                    || !message.path("content").isTextual() || message.hasNonNull("refusal") || message.hasNonNull("function_call")
                    || message.has("tool_calls") && !message.path("tool_calls").isEmpty()) throw invalid();
            return new Reply(provider, response.path("model").asText(), json.readStrict(message.path("content").asText(), JsonNode.class));
        } catch (RuntimeException invalid) { throw invalid(); }
    }
    private static AssistModelPort.ModelFailure invalid() { return new AssistModelPort.ModelFailure(AssistRun.Failure.INVALID_MODEL_OUTPUT); }

    /**
     * 模型标识来自传输回执，业务字段由各自适配器继续严格校验。
     * @author owlzhangfq@gmail.com
     */
    record Reply(String providerId, String modelVersion, JsonNode output) { }

    /**
     * 超限立即取消流读取，不能等无界响应完整到达后才检查大小。
     * @author owlzhangfq@gmail.com
     */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; subscription.request(1); }
        @Override public void onNext(List<ByteBuffer> values) {
            for (ByteBuffer value : values) {
                if (value.remaining() > MAX_RESPONSE_BYTES - body.size()) { subscription.cancel(); result.completeExceptionally(invalid()); return; }
                byte[] bytes = new byte[value.remaining()]; value.get(bytes); body.writeBytes(bytes);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(body.toByteArray()); }
    }
}
