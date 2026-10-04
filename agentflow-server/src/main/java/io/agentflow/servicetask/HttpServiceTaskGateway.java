package io.agentflow.servicetask;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 固定 execute/query 协议，不跟随重定向、不自动重试，接收正文和整个请求时长均有界。
 * @author owlzhangfq@gmail.com
 */
@Component
public class HttpServiceTaskGateway implements ServiceTaskGateway {
    private static final int PROTOCOL_VERSION = 1;
    private static final int MAX_REQUEST_BYTES = 256 * 1024;
    private static final int MAX_RESPONSE_BYTES = 16 * 1024;
    private final ServiceTaskGatewayConfiguration configuration;
    private final JsonUtil json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();

    /** 严格协议使用独立 JSON 配置，不改变平台历史正文的兼容策略。 */
    @SuppressWarnings("deprecation")
    public HttpServiceTaskGateway(ServiceTaskGatewayConfiguration configuration, ObjectMapper mapper) {
        this.configuration = configuration;
        this.json = new JsonUtil(mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS));
    }

    /** 原操作号同时是幂等头，远端必须将完整摘要与终态一起持久化。 */
    @Override public Result execute(ServiceTaskOperation.Input input) { return exchange(input, false); }
    /** 查询只携带原身份和摘要，不再次外发完整表单输入。 */
    @Override public Result query(ServiceTaskOperation.Input input) { return exchange(input, true); }

    private Result exchange(ServiceTaskOperation.Input input, boolean query) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Service task gateway must run outside a transaction");
        var command = input.command();
        var found = configuration.find(command.tenantId(), command.contract().key(), command.contract().version());
        if (found.isEmpty()) return unavailable(ServiceTaskOperation.Failure.NOT_CONFIGURED);
        var selected = found.get();
        if (!selected.targetDigest().equals(input.targetDigest()) || !selected.contract().equals(command.contract())) return unavailable(ServiceTaskOperation.Failure.TARGET_CHANGED);
        if (!query && !selected.enabled()) return unavailable(ServiceTaskOperation.Failure.OPERATION_DISABLED);
        var destination = selected.destination();
        Object payload = query ? new Query(PROTOCOL_VERSION, command.tenantId(), command.id(), command.digest(), command.contract().key(), command.contract().version(), command.contract().digest())
                : new Execute(PROTOCOL_VERSION, command.digest(), command);
        byte[] bytes = json.write(payload).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_REQUEST_BYTES) return unavailable(ServiceTaskOperation.Failure.INVALID_RESPONSE);
        var request = HttpRequest.newBuilder(destination.baseUri().resolve(query ? "query" : "execute")).timeout(destination.timeout())
                .header("Content-Type", "application/json; charset=utf-8").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes));
        if (!query) request.header("Idempotency-Key", command.id().toString());
        if (!destination.token().isEmpty()) request.header("Authorization", "Bearer " + destination.token());
        var future = client.sendAsync(request.build(), response -> new BoundedBody());
        try {
            var response = future.get(destination.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() == 401 || response.statusCode() == 403) return unavailable(ServiceTaskOperation.Failure.AUTHENTICATION);
            if (response.statusCode() != 200) return unavailable(ServiceTaskOperation.Failure.REMOTE_FAILURE);
            if (!"application/json".equalsIgnoreCase(response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim())) return unavailable(ServiceTaskOperation.Failure.INVALID_RESPONSE);
            try {
                String body = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(response.body())).toString();
                var value = json.readStrict(body, Response.class);
                if (value.protocolVersion() != PROTOCOL_VERSION || !command.tenantId().equals(value.tenantId())
                        || !command.contract().key().equals(value.operationKey()) || command.contract().version() != value.operationVersion()
                        || !command.contract().digest().equals(value.contractDigest()) || value.observation() == null
                        || !value.observation().matches(command, query, Instant.now())) return unavailable(ServiceTaskOperation.Failure.INVALID_RESPONSE);
                return new Observed(value.observation());
            } catch (RuntimeException | CharacterCodingException invalid) { return unavailable(ServiceTaskOperation.Failure.INVALID_RESPONSE); }
        } catch (TimeoutException timeout) { return unavailable(ServiceTaskOperation.Failure.TIMEOUT); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return unavailable(ServiceTaskOperation.Failure.CONNECTION); }
        catch (ExecutionException failure) {
            var cause = failure.getCause();
            return unavailable(cause instanceof TooLarge ? ServiceTaskOperation.Failure.RESPONSE_TOO_LARGE
                    : cause instanceof java.net.http.HttpTimeoutException ? ServiceTaskOperation.Failure.TIMEOUT : ServiceTaskOperation.Failure.CONNECTION);
        } finally { if (!future.isDone()) future.cancel(true); }
    }

    private static Unavailable unavailable(ServiceTaskOperation.Failure failure) { return new Unavailable(failure); }
    /** @author owlzhangfq@gmail.com */
    private record Execute(int protocolVersion, String commandDigest, ServiceTaskCommand command) { }
    /** @author owlzhangfq@gmail.com */
    private record Query(int protocolVersion, String tenantId, UUID operationId, String commandDigest, String operationKey, long operationVersion, String contractDigest) { }
    /** @author owlzhangfq@gmail.com */
    private record Response(int protocolVersion, String tenantId, String operationKey, long operationVersion, String contractDigest, ServiceTaskObservation observation) { }
    /** @author owlzhangfq@gmail.com */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> chunks) {
            for (var chunk : chunks) {
                if (chunk.remaining() > MAX_RESPONSE_BYTES - bytes.size()) { subscription.cancel(); result.completeExceptionally(new TooLarge()); return; }
                byte[] value = new byte[chunk.remaining()]; chunk.get(value); bytes.writeBytes(value);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
    /** @author owlzhangfq@gmail.com */
    private static final class TooLarge extends RuntimeException { }
}
