package io.agentflow.organization;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static io.agentflow.organization.OrganizationSyncBatch.Failure;

/**
 * 固定 changes 只读协议，完整响应时长和字节数均有界，不跟随重定向或转发上游错误正文。
 * @author owlzhangfq@gmail.com
 */
@Component
public final class HttpOrganizationSyncSource {
    private final OrganizationSyncConfiguration configuration;
    private final OrganizationSyncCodec codec;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();

    /** 来源正文使用已经验证的组织事实协议，不接收角色授权或登录凭据。 */
    public HttpOrganizationSyncSource(OrganizationSyncConfiguration configuration, OrganizationSyncCodec codec) { this.configuration = configuration; this.codec = codec; }

    /** 只发送原租户、来源及已应用游标，网络请求不能运行在数据库事务中。 */
    public Result read(OrganizationSyncBatch.Context context, Instant leaseUntil) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Organization source read must run outside a transaction");
        var found = configuration.destination(context.tenantId());
        if (found.isEmpty() || !found.get().sourceKey().equals(context.sourceKey()) || !found.get().digest(context.tenantId()).equals(context.targetDigest())) return failed(Failure.SOURCE_CHANGED);
        var destination = found.get(); long remaining = Duration.between(Instant.now(), leaseUntil).toMillis();
        long timeout = Math.min(destination.timeout().toMillis(), remaining); if (timeout <= 0) return failed(Failure.SOURCE_TIMEOUT);
        String query = "changes?tenantId=" + encoded(context.tenantId()) + "&sourceKey=" + encoded(context.sourceKey()) + "&afterRevision=" + context.afterRevision();
        var request = HttpRequest.newBuilder(destination.baseUri().resolve(query)).timeout(Duration.ofMillis(timeout))
                .header("Accept", "application/json").GET();
        if (!destination.token().isEmpty()) request.header("Authorization", "Bearer " + destination.token());
        var future = client.sendAsync(request.build(), response -> new BoundedBody());
        try {
            var response = future.get(timeout, TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) return failed(Failure.SOURCE_UNAVAILABLE);
            if (!"application/json".equalsIgnoreCase(response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim())) return failed(Failure.INVALID_SOURCE_DATA);
            try {
                String body = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(response.body())).toString();
                return new Result(codec.read(body, context.tenantId(), context.sourceKey(), context.afterRevision()), null);
            } catch (RuntimeException | CharacterCodingException invalid) { return failed(Failure.INVALID_SOURCE_DATA); }
        } catch (TimeoutException timeoutFailure) { return failed(Failure.SOURCE_TIMEOUT); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return failed(Failure.SOURCE_UNAVAILABLE); }
        catch (ExecutionException failure) {
            return failed(failure.getCause() instanceof TooLarge ? Failure.INVALID_SOURCE_DATA
                    : failure.getCause() instanceof java.net.http.HttpTimeoutException ? Failure.SOURCE_TIMEOUT : Failure.SOURCE_UNAVAILABLE);
        } finally { if (!future.isDone()) future.cancel(true); }
    }

    private static String encoded(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static Result failed(Failure failure) { return new Result(null, failure); }

    /**
     * 结果只有可信事实或受控失败二者之一，不包含远端异常文本。
     * @author owlzhangfq@gmail.com
     */
    public record Result(OrganizationSyncDelta delta, Failure failure) {
        /** 避免工作器误把空响应或同时成功失败当成来源事实。 */
        public Result { if ((delta == null) == (failure == null)) throw new IllegalArgumentException("Organization source result must have exactly one outcome"); }
    }
    /**
     * 在聚合完整正文前拒绝超量，避免无 Content-Length 的流绕过上限。
     * @author owlzhangfq@gmail.com
     */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> chunks) {
            for (var chunk : chunks) {
                if (chunk.remaining() > OrganizationSyncCodec.MAX_BYTES - bytes.size()) { subscription.cancel(); result.completeExceptionally(new TooLarge()); return; }
                byte[] value = new byte[chunk.remaining()]; chunk.get(value); bytes.writeBytes(value);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
    /**
     * 超量错误只供内部映射为协议失败。
     * @author owlzhangfq@gmail.com
     */
    private static final class TooLarge extends RuntimeException { }
}
