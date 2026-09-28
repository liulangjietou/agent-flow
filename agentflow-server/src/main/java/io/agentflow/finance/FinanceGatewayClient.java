package io.agentflow.finance;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.JsonUtil;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * 财务只读端口的固定协议传输；在事务外调用，不重定向、不自动重试、不透传远端错误正文。
 * @author owlzhangfq@gmail.com
 */
@Component
public class FinanceGatewayClient {
    static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int CONTRACT_VERSION = 1;
    private final FinanceGatewayConfiguration configuration;
    private final JsonUtil json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    /** 隔离外部协议的严格反序列化策略，不改变平台已有 JSON 兼容行为。 */
    @SuppressWarnings("deprecation")
    public FinanceGatewayClient(FinanceGatewayConfiguration configuration, ObjectMapper mapper) {
        this.configuration = configuration;
        this.json = new JsonUtil(mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS));
    }

    /** 业务适配器核对结果与请求一致性，基础传输只认固定操作及封闭结果类型。 */
    public <T> FinanceResult<T> read(String tenantId, Operation operation, Object data, Class<T> resultType, Predicate<T> matchesRequest) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Finance gateway must run outside a transaction");
        var selected = configuration.destination(tenantId);
        if (selected.isEmpty()) return unavailable(FinanceResult.Failure.NOT_CONFIGURED);
        var destination = selected.get();
        UUID requestId = UUID.randomUUID();
        var request = HttpRequest.newBuilder(destination.baseUri().resolve(operation.path)).timeout(destination.timeout())
                .header("Content-Type", "application/json; charset=utf-8").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.write(new Request(CONTRACT_VERSION, tenantId, requestId, data)), StandardCharsets.UTF_8));
        if (!destination.token().isEmpty()) request.header("Authorization", "Bearer " + destination.token());
        var future = client.sendAsync(request.build(), response -> new BoundedBody());
        try {
            var response = future.get(destination.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() == 401 || response.statusCode() == 403) return unavailable(FinanceResult.Failure.AUTHENTICATION);
            if (response.statusCode() != 200) return unavailable(FinanceResult.Failure.REMOTE_FAILURE);
            String contentType = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim();
            if (!"application/json".equalsIgnoreCase(contentType)) return unavailable(FinanceResult.Failure.INVALID_RESPONSE);
            return parse(new String(response.body(), StandardCharsets.UTF_8), tenantId, requestId, operation, resultType, matchesRequest);
        } catch (TimeoutException timeout) { return unavailable(FinanceResult.Failure.TIMEOUT); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return unavailable(FinanceResult.Failure.CONNECTION); }
        catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof ResponseTooLarge) return unavailable(FinanceResult.Failure.RESPONSE_TOO_LARGE);
            return unavailable(cause instanceof java.net.http.HttpTimeoutException ? FinanceResult.Failure.TIMEOUT : FinanceResult.Failure.CONNECTION);
        } finally { if (!future.isDone()) future.cancel(true); }
    }

    private <T> FinanceResult<T> parse(String body, String tenantId, UUID requestId, Operation operation, Class<T> resultType, Predicate<T> matchesRequest) {
        try {
            JsonNode envelope = json.read(body, JsonNode.class);
            if (!envelope.isObject() || envelope.size() != 5 || !envelope.path("contractVersion").isInt()
                    || envelope.path("contractVersion").intValue() != CONTRACT_VERSION || !envelope.path("tenantId").isTextual()
                    || !tenantId.equals(envelope.path("tenantId").textValue()) || !envelope.path("requestId").isTextual()
                    || !requestId.toString().equals(envelope.path("requestId").textValue()) || !envelope.path("outcome").isTextual()) return invalid();
            if ("REJECTED".equals(envelope.path("outcome").textValue()) && envelope.path("reason").isTextual()) {
                var reason = FinanceResult.Reason.valueOf(envelope.path("reason").textValue());
                return operation.reasons.contains(reason) ? new FinanceResult.Rejected<>(reason) : invalid();
            }
            if (!"SUCCESS".equals(envelope.path("outcome").textValue()) || !envelope.path("data").isObject()) return invalid();
            T result = json.read(envelope.path("data").toString(), resultType);
            return matchesRequest.test(result) ? new FinanceResult.Success<>(result) : invalid();
        } catch (RuntimeException malformed) { return invalid(); }
    }

    private static <T> FinanceResult<T> unavailable(FinanceResult.Failure failure) { return new FinanceResult.Unavailable<>(failure); }
    private static <T> FinanceResult<T> invalid() { return unavailable(FinanceResult.Failure.INVALID_RESPONSE); }

    /**
     * 已实现只读接口的固定路径与业务拒绝集合，禁止任意 URL 或未知远端指令。
     * @author owlzhangfq@gmail.com
     */
    public enum Operation {
        CATALOG("catalog", Set.of(FinanceResult.Reason.EMPLOYEE_UNAVAILABLE)),
        EMPLOYEE_ACCOUNT("employee-account", Set.of(FinanceResult.Reason.EMPLOYEE_UNAVAILABLE, FinanceResult.Reason.ACCOUNT_UNAVAILABLE, FinanceResult.Reason.LEGAL_ENTITY_UNAVAILABLE)),
        EXCHANGE_RATE("exchange-rate", Set.of(FinanceResult.Reason.RATE_UNAVAILABLE, FinanceResult.Reason.LEGAL_ENTITY_UNAVAILABLE)),
        EXPENSE_POLICY("expense-policy", Set.of(FinanceResult.Reason.POLICY_NOT_FOUND, FinanceResult.Reason.EXPENSE_PROHIBITED,
                FinanceResult.Reason.PRIOR_REQUEST_REQUIRED, FinanceResult.Reason.COST_OBJECT_UNAVAILABLE, FinanceResult.Reason.LEGAL_ENTITY_UNAVAILABLE, FinanceResult.Reason.EMPLOYEE_UNAVAILABLE)),
        INVOICE_VERIFICATION("invoice-verification", Set.of(FinanceResult.Reason.INVOICE_INVALID, FinanceResult.Reason.INVOICE_CANCELLED,
                FinanceResult.Reason.INVOICE_BUYER_MISMATCH, FinanceResult.Reason.LEGAL_ENTITY_UNAVAILABLE));
        private final String path;
        private final Set<FinanceResult.Reason> reasons;
        Operation(String path, Set<FinanceResult.Reason> reasons) { this.path = path; this.reasons = reasons; }
    }

    /**
     * 每次读取使用独立编号，成功和业务拒绝都必须回传原租户与编号。
     * @author owlzhangfq@gmail.com
     */
    private record Request(int contractVersion, String tenantId, UUID requestId, Object data) { }

    /**
     * 接收期间超限立即取消，限制整个响应体，而非只限制 Content-Length。
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
                if (value.remaining() > MAX_RESPONSE_BYTES - body.size()) {
                    subscription.cancel(); result.completeExceptionally(new ResponseTooLarge()); return;
                }
                byte[] bytes = new byte[value.remaining()]; value.get(bytes); body.writeBytes(bytes);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(body.toByteArray()); }
    }

    /**
     * 只携带稳定分类，不把远端响应或凭据加入异常。
     * @author owlzhangfq@gmail.com
     */
    private static final class ResponseTooLarge extends RuntimeException { }
}
