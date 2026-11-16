package io.agentflow.agent;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.common.JsonUtil;
import io.agentflow.observability.DiagnosticContext;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 摘要、草稿和票据共用的有界 JSON 输出传输，不解释业务输出，也不执行工具或重定向。
 * @author owlzhangfq@gmail.com
 */
final class OpenAiTextClient {
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final Logger LOG = LoggerFactory.getLogger(OpenAiTextClient.class);
    private static final int RATE_LIMITED = 429;
    private static final int MAX_RATE_LIMIT_ATTEMPTS = 3;
    private static final long DEFAULT_RETRY_AFTER_SECONDS = 1;
    private static final long MAX_RETRY_AFTER_SECONDS = 10;
    /** 全部适配器共享一个客户端：连接池与选择器线程按进程复用，不随每个适配器实例重复创建。 */
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final AssistConfiguration configuration;
    private final JsonUtil json;

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
        var endpoint = configuration.uri(); String provider = configuration.getProviderId(), model = configuration.modelFor(promptVersion);
        String apiKey = configuration.getApiKey(); int timeout = configuration.getTimeoutSeconds();
        if (!configuration.targetDigest(promptVersion).equals(expectedTarget)) {
            throw new AssistModelPort.ModelFailure(AssistRun.Failure.MODEL_UNAVAILABLE);
        }
        String body = json.write(Map.of("model", model, "stream", false, "store", false,
                "max_completion_tokens", configuration.maxCompletionTokensFor(promptVersion), "response_format", Map.of("type", "json_object"),
                "messages", List.of(Map.of("role", "system", "content", instruction), Map.of("role", "user", "content", content))));
        long started = System.nanoTime();
        long deadline = started + TimeUnit.SECONDS.toNanos(timeout);
        try {
            HttpResponse<byte[]> response = null;
            for (int attempt = 1; attempt <= MAX_RATE_LIMIT_ATTEMPTS; attempt++) {
                response = send(endpoint, apiKey, body, deadline);
                if (response.statusCode() != RATE_LIMITED) break;
                // 429 表示请求未被处理，重试不会造成重复执行；等待时长受本次总超时约束，等不起就直接失败。
                long wait = retryAfterNanos(response);
                if (attempt == MAX_RATE_LIMIT_ATTEMPTS || System.nanoTime() + wait >= deadline) break;
                LOG.info("Agent model rate limited, errorCode={}, promptVersion={}, attempt={}, retryAfterMs={}",
                        AssistRun.Failure.MODEL_UNAVAILABLE, promptVersion, attempt, TimeUnit.NANOSECONDS.toMillis(wait));
                TimeUnit.NANOSECONDS.sleep(wait);
            }
            if (response.statusCode() != 200) throw new AssistModelPort.ModelFailure(AssistRun.Failure.MODEL_UNAVAILABLE);
            var reply = parse(provider, promptVersion, new String(response.body(), StandardCharsets.UTF_8));
            var usage = reply.usage();
            LOG.info("Agent model response received, errorCode={}, promptVersion={}, elapsedMs={}, usageStatus={}, inputTokens={}, outputTokens={}, totalTokens={}",
                    "NONE", promptVersion, elapsed(started), usage.status(), usage.inputTokens(), usage.outputTokens(), usage.totalTokens());
            return reply;
        } catch (AssistModelPort.ModelFailure failure) {
            logFailure(promptVersion, started, failure.failure()); throw failure;
        } catch (InterruptedException interrupted) {
            logFailure(promptVersion, started, AssistRun.Failure.MODEL_UNAVAILABLE);
            Thread.currentThread().interrupt(); throw new AssistModelPort.ModelFailure(AssistRun.Failure.MODEL_UNAVAILABLE);
        } catch (TimeoutException timeoutFailure) {
            logFailure(promptVersion, started, AssistRun.Failure.MODEL_TIMEOUT);
            throw new AssistModelPort.ModelFailure(AssistRun.Failure.MODEL_TIMEOUT);
        }
        catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            var failure = cause instanceof AssistModelPort.ModelFailure modelFailure ? modelFailure.failure()
                    : cause instanceof java.net.http.HttpTimeoutException ? AssistRun.Failure.MODEL_TIMEOUT : AssistRun.Failure.MODEL_UNAVAILABLE;
            logFailure(promptVersion, started, failure); throw new AssistModelPort.ModelFailure(failure);
        }
    }

    /** 单次发送只使用剩余预算；超时后取消读取，避免后台线程继续占用连接。 */
    private HttpResponse<byte[]> send(java.net.URI endpoint, String apiKey, String body, long deadline)
            throws InterruptedException, ExecutionException, TimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new TimeoutException("Model request budget exhausted");
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofNanos(remaining))
                .header("Content-Type", "application/json; charset=utf-8").POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        request.header(DiagnosticContext.HEADER, DiagnosticContext.capture().traceId());
        if (!apiKey.isBlank()) request.header("Authorization", "Bearer " + apiKey);
        var future = CLIENT.sendAsync(request.build(), response -> new BoundedBody());
        try { return future.get(remaining, TimeUnit.NANOSECONDS); }
        finally { if (!future.isDone()) future.cancel(true); }
    }

    /** 只接受整数秒的 Retry-After；缺失、HTTP 日期或非法值都按默认退避处理，并封顶避免被提供者拖住。 */
    static long retryAfterNanos(HttpResponse<?> response) {
        long seconds = response.headers().firstValue("Retry-After").map(value -> {
            try { return Long.parseLong(value.trim()); } catch (NumberFormatException notSeconds) { return DEFAULT_RETRY_AFTER_SECONDS; }
        }).orElse(DEFAULT_RETRY_AFTER_SECONDS);
        return TimeUnit.SECONDS.toNanos(Math.max(0, Math.min(seconds, MAX_RETRY_AFTER_SECONDS)));
    }

    private Reply parse(String provider, String promptVersion, String body) {
        try {
            JsonNode response = json.readStrict(body, JsonNode.class);
            var reportedUsage = usage(response.path("usage"));
            String modelVersion = response.path("model").isTextual() && !response.path("model").asText().isBlank()
                    ? response.path("model").asText() : null;
            AgentExecutionTelemetry.received(promptVersion, provider, modelVersion, reportedUsage);
            var choices = response.path("choices");
            if (!choices.isArray() || choices.size() != 1 || !response.path("model").isTextual()) throw invalid();
            var choice = choices.get(0); var message = choice.path("message");
            if (!"stop".equals(choice.path("finish_reason").asText()) || !"assistant".equals(message.path("role").asText())
                    || !message.path("content").isTextual() || message.hasNonNull("refusal") || message.hasNonNull("function_call")
                    || message.has("tool_calls") && !message.path("tool_calls").isEmpty()) throw invalid();
            return new Reply(provider, response.path("model").asText(), json.readStrict(message.path("content").asText(), JsonNode.class), reportedUsage);
        } catch (RuntimeException invalid) { throw invalid(); }
    }
    private static AssistModelPort.ModelFailure invalid() { return new AssistModelPort.ModelFailure(AssistRun.Failure.INVALID_MODEL_OUTPUT); }
    private static long elapsed(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }
    /** 只记录失败分类与耗时，不输出异常对象：传输异常可能携带端点地址或提供者回显内容。 */
    private static void logFailure(String promptVersion, long started, AssistRun.Failure failure) {
        LOG.error("Agent model request failed, errorCode={}, promptVersion={}, elapsedMs={}", failure, promptVersion, elapsed(started));
    }

    /** 不把未报告或异常用量补成零；用量异常不改变经过业务校验的模型内容。 */
    static Usage usage(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return new Usage(UsageStatus.NOT_REPORTED, null, null, null);
        var input = node.path("prompt_tokens"); var output = node.path("completion_tokens"); var total = node.path("total_tokens");
        if (!tokenCount(input) || !tokenCount(output) || !tokenCount(total)
                || input.longValue() > Long.MAX_VALUE - output.longValue() || input.longValue() + output.longValue() != total.longValue()) {
            return new Usage(UsageStatus.INVALID, null, null, null);
        }
        return new Usage(UsageStatus.REPORTED, input.longValue(), output.longValue(), total.longValue());
    }
    private static boolean tokenCount(JsonNode node) { return node.isIntegralNumber() && node.canConvertToLong() && node.longValue() >= 0; }

    /**
     * 模型标识来自传输回执，业务字段由各自适配器继续严格校验。
     * @author owlzhangfq@gmail.com
     */
    record Reply(String providerId, String modelVersion, JsonNode output, Usage usage) { }

    /**
     * 提供者报告的用量只用于观测，不能据此推算费用或认定业务执行成功。
     * @author owlzhangfq@gmail.com
     */
    record Usage(UsageStatus status, Long inputTokens, Long outputTokens, Long totalTokens) { }

    /**
     * 区分真实零用量、缺失用量和格式错误。
     * @author owlzhangfq@gmail.com
     */
    enum UsageStatus { REPORTED, NOT_REPORTED, INVALID }

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
