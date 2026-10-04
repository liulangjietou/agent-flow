package io.agentflow.signature;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.storage.LocalDocumentStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.agentflow.signature.SignatureOperation.Failure;

/**
 * 固定 submit/query/artifact 协议，原件先核验后一次外发，结果按已签名指纹排他保存。
 * @author owlzhangfq@gmail.com
 */
@Component
public class HttpSignatureGateway implements SignatureGateway {
    public static final String PROTOCOL = "agentflow-signature-http-1";
    public static final String RECEIPT_SIGNATURE_HEADER = "X-Agentflow-Receipt-Signature";
    private static final int MAX_METADATA_BYTES = 64 * 1024;
    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";
    private final SignatureGatewayConfiguration configuration;
    private final JsonUtil json;
    private final SignatureReceiptVerifier verifier;
    private final LocalDocumentStore documents;
    private final Clock clock;
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    /** 复用配置、验真和字节存储，各自不拥有申请授权或数据库状态变更。 */
    @Autowired
    public HttpSignatureGateway(SignatureGatewayConfiguration configuration, JsonUtil json, SignatureReceiptVerifier verifier, LocalDocumentStore documents) {
        this(configuration, json, verifier, documents, Clock.systemUTC());
    }
    HttpSignatureGateway(SignatureGatewayConfiguration configuration, JsonUtil json, SignatureReceiptVerifier verifier, LocalDocumentStore documents, Clock clock) {
        this.configuration = configuration; this.json = json; this.verifier = verifier; this.documents = documents; this.clock = clock;
    }

    /** 原件总量由固定请求限制，multipart 不复制成额外的 Base64 正文；底层不能重新订阅发送正文。 */
    @Override public ReceiptResult submit(SignatureOperation claim) {
        outsideTransaction(); requireStatus(claim, SignatureOperation.Status.SENDING);
        try {
            requireLease(claim); requireAuthorization(claim);
            var selected = configured(claim.input());
            if (!selected.enabled()) throw failed(Failure.OPERATION_DISABLED);
            var request = claim.input().request();
            byte[] metadata = metadata(new Submit(PROTOCOL, request.digest(), claim.input().targetDigest(), request));
            String boundary = "agentflow-" + UUID.randomUUID();
            var parts = new ArrayList<HttpRequest.BodyPublisher>();
            parts.add(HttpRequest.BodyPublishers.ofString("--" + boundary + "\r\nContent-Disposition: form-data; name=\"request\"\r\nContent-Type: " + JSON_CONTENT_TYPE + "\r\n\r\n"));
            parts.add(HttpRequest.BodyPublishers.ofByteArray(metadata));
            for (var document : request.documents()) {
                byte[] bytes;
                try { bytes = documents.read(new LocalDocumentStore.Content(document.contentId(), document.size(), document.sha256())); }
                catch (DomainException unavailable) { throw failed(Failure.SOURCE_UNAVAILABLE); }
                String id = document.attachmentId().toString();
                parts.add(HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file-" + id + "\"; filename=\"" + id + ".bin\"\r\nContent-Type: application/octet-stream\r\n\r\n"));
                parts.add(HttpRequest.BodyPublishers.ofByteArray(bytes));
            }
            parts.add(HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n"));
            requireLease(claim); requireAuthorization(claim);
            var timeout = timeout(claim, selected, true);
            var body = new OnceBodyPublisher(HttpRequest.BodyPublishers.concat(parts.toArray(HttpRequest.BodyPublisher[]::new)));
            var http = request(selected, "submit", "multipart/form-data; boundary=" + boundary, timeout)
                    .header("Idempotency-Key", request.id().toString()).POST(body).build();
            return observed(claim, selected, exchange(http, SignatureReceiptVerifier.MAX_RECEIPT_BYTES, timeout));
        } catch (GatewayFailure failure) { return new Unavailable(failure.reason); }
    }

    /** 发送授权过期或资料被停用后仍查询原号，只有已签名 NOT_FOUND 正文才构成查询观察。 */
    @Override public ReceiptResult query(SignatureOperation claim) {
        outsideTransaction(); requireStatus(claim, SignatureOperation.Status.QUERYING);
        try {
            requireLease(claim); var selected = configured(claim.input()); var timeout = timeout(claim, selected, false);
            var http = request(selected, "query", JSON_CONTENT_TYPE, timeout).POST(HttpRequest.BodyPublishers.ofByteArray(metadata(identity(claim.input())))).build();
            return observed(claim, selected, exchange(http, SignatureReceiptVerifier.MAX_RECEIPT_BYTES, timeout));
        } catch (GatewayFailure failure) { return new Unavailable(failure.reason); }
    }

    /** 已发布但尚未登记 READY 的正确文件可直接复用；损坏文件与原件都没有覆盖入口。 */
    @Override public FileResult collect(SignatureOperation claim, SignatureReceiptVerifier.Evidence evidence, SignatureOperation.StoredArtifact file) {
        outsideTransaction(); requireStatus(claim, SignatureOperation.Status.FETCHING_FILES);
        Path staged = null;
        try {
            requireLease(claim);
            SignatureReceipt receipt;
            try { receipt = verifier.reverify(claim.input(), evidence).receipt(); }
            catch (DomainException invalid) { throw failed(Failure.INVALID_RESPONSE); }
            if (claim.receipt() == null || !receipt.digest().equals(claim.receipt().digest()) || !claim.artifacts().contains(file)) throw failed(Failure.ARTIFACT_MISMATCH);
            var content = new LocalDocumentStore.Content(file.contentId(), file.size(), file.sha256());
            if (!documents.available()) throw failed(Failure.STORAGE_UNAVAILABLE);
            try {
                if (documents.readIfPresent(content).isPresent()) { requireLease(claim); return new Stored(file); }
            } catch (DomainException unavailable) {
                throw failed("FILE_INTEGRITY_FAILED".equals(unavailable.code()) ? Failure.ARTIFACT_MISMATCH : Failure.STORAGE_UNAVAILABLE);
            }
            if (file.size() > documents.maxFileBytes()) throw failed(Failure.STORAGE_UNAVAILABLE);
            var selected = configured(claim.input()); var timeout = timeout(claim, selected, false);
            var artifact = receipt.artifacts().stream().filter(value -> value.documentId().equals(file.documentId())).findFirst().orElseThrow(() -> failed(Failure.ARTIFACT_MISMATCH));
            var command = new ArtifactQuery(identity(claim.input()), receipt.revision(), receipt.digest(), file.documentId(), file.size(), file.sha256());
            var http = request(selected, "artifact", JSON_CONTENT_TYPE, timeout).setHeader("Accept", artifact.mediaType())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(metadata(command))).build();
            var response = exchange(http, Math.toIntExact(file.size()), timeout);
            requireResponse(response);
            if (!artifact.mediaType().equalsIgnoreCase(singleHeader(response, "Content-Type").strip())) throw failed(Failure.INVALID_RESPONSE);
            requireLease(claim);
            try {
                staged = documents.stage(content, new ByteArrayInputStream(response.body()));
                requireLease(claim); documents.publish(content, staged);
            } catch (DomainException failure) {
                throw failed("FILE_CONTENT_MISMATCH".equals(failure.code()) || "FILE_INTEGRITY_FAILED".equals(failure.code()) ? Failure.ARTIFACT_MISMATCH : Failure.STORAGE_UNAVAILABLE);
            }
            return new Stored(file);
        } catch (GatewayFailure failure) { return new Unavailable(failure.reason); }
        finally { documents.discard(staged); }
    }

    private Observed observed(SignatureOperation claim, SignatureGatewayConfiguration.Declaration selected, HttpResponse<byte[]> response) {
        requireResponse(response); requireLease(claim);
        if (!singleHeader(response, "Content-Type").strip().matches("(?i)application/json(?:\\s*;\\s*charset\\s*=\\s*(?:utf-8|\"utf-8\"))?")) throw failed(Failure.INVALID_RESPONSE);
        try { return new Observed(verifier.verify(claim.input(), selected.profile(), response.body(), singleHeader(response, RECEIPT_SIGNATURE_HEADER), clock.instant())); }
        catch (DomainException invalid) { throw failed(Failure.INVALID_RESPONSE); }
    }

    private SignatureGatewayConfiguration.Declaration configured(SignatureOperation.Input input) {
        var auth = input.request().authorization();
        var selected = configuration.find(input.request().tenantId(), auth.profileKey(), auth.profileVersion()).orElseThrow(() -> failed(Failure.NOT_CONFIGURED));
        if (!selected.targetDigest().equals(input.targetDigest()) || !selected.profile().matches(input.request())) throw failed(Failure.TARGET_CHANGED);
        return selected;
    }
    private HttpRequest.Builder request(SignatureGatewayConfiguration.Declaration selected, String path, String contentType, Duration timeout) {
        var destination = selected.destination();
        var request = HttpRequest.newBuilder(destination.endpoint().resolve(path)).timeout(timeout).header("Content-Type", contentType).header("Accept", "application/json");
        if (!destination.token().isEmpty()) request.header("Authorization", "Bearer " + destination.token());
        return request;
    }
    private HttpResponse<byte[]> exchange(HttpRequest request, int limit, Duration timeout) {
        var future = client.sendAsync(request, ignored -> new BoundedBody(limit));
        try { return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS); }
        catch (TimeoutException failure) { throw failed(Failure.TIMEOUT); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw failed(Failure.CONNECTION); }
        catch (ExecutionException failure) {
            var cause = failure.getCause();
            throw failed(cause instanceof TooLarge ? Failure.RESPONSE_TOO_LARGE : cause instanceof java.net.http.HttpTimeoutException ? Failure.TIMEOUT : Failure.CONNECTION);
        } finally { if (!future.isDone()) future.cancel(true); }
    }
    private byte[] metadata(Object value) {
        byte[] bytes = json.write(value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_METADATA_BYTES) throw failed(Failure.INVALID_RESPONSE);
        return bytes;
    }
    private Query identity(SignatureOperation.Input input) {
        var request = input.request(); var auth = request.authorization();
        return new Query(PROTOCOL, request.tenantId(), request.id(), request.digest(), input.targetDigest(), auth.profileKey(), auth.profileVersion(), auth.profileDigest());
    }
    private Duration timeout(SignatureOperation claim, SignatureGatewayConfiguration.Declaration selected, boolean firstSend) {
        Instant now = clock.instant(), end = claim.leaseUntil();
        if (firstSend && claim.input().request().authorization().validUntil().isBefore(end)) end = claim.input().request().authorization().validUntil();
        if (!end.isAfter(now)) throw failed(firstSend && !claim.input().request().authorization().validUntil().isAfter(now) ? Failure.AUTHORIZATION_EXPIRED : Failure.LEASE_EXPIRED);
        return selected.destination().timeout().compareTo(Duration.between(now, end)) < 0 ? selected.destination().timeout() : Duration.between(now, end);
    }
    private void requireLease(SignatureOperation claim) {
        if (clock.instant().isBefore(claim.updatedAt()) || claim.expired(clock.instant())) throw failed(Failure.LEASE_EXPIRED);
    }
    private void requireAuthorization(SignatureOperation claim) {
        if (!claim.input().request().authorization().validUntil().isAfter(clock.instant())) throw failed(Failure.AUTHORIZATION_EXPIRED);
    }
    private static void requireStatus(SignatureOperation claim, SignatureOperation.Status expected) {
        if (claim.status() != expected) throw new IllegalStateException("Signature gateway requires the matching persisted claim");
    }
    private static void outsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Signature gateway must run outside a transaction");
    }
    private static void requireResponse(HttpResponse<?> response) {
        if (response.statusCode() == 401 || response.statusCode() == 403) throw failed(Failure.AUTHENTICATION);
        if (response.statusCode() != 200) throw failed(Failure.REMOTE_FAILURE);
        if (!response.headers().allValues("Content-Encoding").isEmpty()) throw failed(Failure.INVALID_RESPONSE);
    }
    private static String singleHeader(HttpResponse<?> response, String name) {
        var values = response.headers().allValues(name);
        if (values.size() != 1) throw failed(Failure.INVALID_RESPONSE);
        return values.get(0);
    }
    private static GatewayFailure failed(Failure reason) { return new GatewayFailure(reason); }

    /**
     * 唯一包含完整原件元数据和实际签署身份的提交正文。
     * @author owlzhangfq@gmail.com
     */
    record Submit(String protocol, String requestDigest, String targetDigest, SignatureRequest request) { }
    /**
     * 查询沿用原号和摘要，不包含原件字节、授权目的或可替换服务地址。
     * @author owlzhangfq@gmail.com
     */
    record Query(String protocol, String tenantId, UUID operationId, String requestDigest, String targetDigest, String profileKey, long profileVersion, String profileDigest) { }
    /**
     * 固定端点按已验真修订及原文件标识返回结果，不接受服务方提供的任意下载 URL。
     * @author owlzhangfq@gmail.com
     */
    record ArtifactQuery(Query operation, long revision, String receiptDigest, UUID documentId, long size, String sha256) { }
    /**
     * 阻止底层 HTTP 客户端重新订阅完整提交正文，业务恢复只能转为原号查询。
     * @author owlzhangfq@gmail.com
     */
    static final class OnceBodyPublisher implements HttpRequest.BodyPublisher {
        private final HttpRequest.BodyPublisher delegate;
        private final AtomicBoolean used = new AtomicBoolean();
        OnceBodyPublisher(HttpRequest.BodyPublisher delegate) { this.delegate = delegate; }
        @Override public long contentLength() { return delegate.contentLength(); }
        @Override public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
            if (used.compareAndSet(false, true)) { delegate.subscribe(subscriber); return; }
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long count) { }
                @Override public void cancel() { }
            });
            subscriber.onError(new IllegalStateException("Signature submission body cannot be sent again"));
        }
    }
    /**
     * 回执和结果文件共用有界接收，整个接收过程受同一外层超时约束。
     * @author owlzhangfq@gmail.com
     */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        BoundedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> chunks) {
            for (var chunk : chunks) {
                if (chunk.remaining() > limit - bytes.size()) { subscription.cancel(); result.completeExceptionally(new TooLarge()); return; }
                byte[] value = new byte[chunk.remaining()]; chunk.get(value); bytes.writeBytes(value);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
    /**
     * 接收上限异常只映射为失败类别，不包含远端正文。
     * @author owlzhangfq@gmail.com
     */
    private static final class TooLarge extends RuntimeException { }
    /**
     * 端口内部的确定失败，最终转换为有界结果值供状态机处理。
     * @author owlzhangfq@gmail.com
     */
    private static final class GatewayFailure extends RuntimeException {
        private final Failure reason;
        GatewayFailure(Failure reason) { this.reason = reason; }
    }
}
