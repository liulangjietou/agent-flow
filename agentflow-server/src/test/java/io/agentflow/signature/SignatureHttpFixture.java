package io.agentflow.signature;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentflow.storage.LocalDocumentStore;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static io.agentflow.signature.SignatureVerificationFixtures.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 回环服务真实接收 multipart 原件和查询，再返回有真实回执签名的合成文件；不模拟真实 PDF 证书签署。
 * @author owlzhangfq@gmail.com
 */
final class SignatureHttpFixture implements AutoCloseable {
    final java.security.KeyPair key = keyPair();
    final SignatureProfile profile = profile(key);
    final SignatureGatewayConfiguration configuration = new SignatureGatewayConfiguration();
    final SignatureGatewayConfiguration.Profile declaration = new SignatureGatewayConfiguration.Profile();
    final SignatureReceiptVerifier verifier = new SignatureReceiptVerifier(MAPPER);
    final MutableClock clock = new MutableClock(NOW.plusSeconds(2));
    final LocalDocumentStore documents;
    final SignatureOperation.Input input;
    final Map<UUID, byte[]> originals = new LinkedHashMap<>();
    final Map<UUID, byte[]> results = new LinkedHashMap<>();
    final List<Captured> requests = new CopyOnWriteArrayList<>();
    final HttpServer provider;
    final CountDownLatch releaseBody = new CountDownLatch(1);
    volatile Mode mode = Mode.NORMAL;
    volatile boolean accepted;
    private final java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(3);
    private final AtomicReference<Throwable> providerFailure = new AtomicReference<>();

    SignatureHttpFixture(Path directory) throws IOException {
        documents = new LocalDocumentStore(directory.toAbsolutePath().toString(), SignatureReceipt.MAX_ARTIFACT_BYTES);
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        declaration.setKey(profile.key()); declaration.setVersion(profile.version()); declaration.setName(profile.name()); declaration.setActors(profile.actors());
        declaration.setSigners(profile.signers()); declaration.setReceiptPublicKey(profile.receiptPublicKey()); declaration.setToken("fixture-provider-token"); declaration.setTimeoutSeconds(2);
        declaration.setEndpoint("http://127.0.0.1:" + provider.getAddress().getPort() + "/signature");
        configuration.setEnabled(true); configuration.setTenants(Map.of("tenant-a", List.of(declaration))); configuration.validate();
        var sourceFiles = new ArrayList<SignatureRequest.Document>();
        for (int index = 0; index < 2; index++) {
            UUID id = UUID.randomUUID(); byte[] bytes = ("合成原件-" + index + "\u0000\r\n").getBytes(StandardCharsets.UTF_8);
            originals.put(id, bytes); results.put(id, ("合成签署结果-" + index + "\u0000\r\n").getBytes(StandardCharsets.UTF_8));
            sourceFiles.add(new SignatureRequest.Document(id, id, index == 0 ? "contract" : "items.attachment", "合同" + index + ".pdf", bytes.length, sha256(bytes)));
            put(documents, new LocalDocumentStore.Content(id, bytes.length, sha256(bytes)), bytes);
        }
        var request = new SignatureRequest(UUID.randomUUID(), "tenant-a", new SignatureRequest.Source(UUID.randomUUID(), 2, 7, "contract", 3),
                new SignatureRequest.Authorization("alice", profile.key(), profile.version(), profile.digest(), "明确授权签署本次合同", NOW, NOW.plusSeconds(3600)), sourceFiles, profile.signers());
        input = new SignatureOperation.Input(request, configuration.declarations().get(0).targetDigest());
        provider.createContext("/", this::handle); provider.setExecutor(executor); provider.start();
    }
    HttpSignatureGateway gateway() { return new HttpSignatureGateway(configuration, JSON, verifier, documents, clock); }
    SignatureOperation sending() { return SignatureOperation.queue(input, NOW).claim(NOW, Duration.ofSeconds(15)); }
    SignatureOperation querying() {
        var unknown = sending().unavailable(SignatureOperation.Failure.CONNECTION, NOW.plusSeconds(2)); clock.now = unknown.nextAttemptAt();
        return unknown.claim(clock.now, Duration.ofSeconds(15));
    }
    SignatureOperation fetching() {
        accepted = true; clock.now = NOW.plusSeconds(2);
        var collecting = sending().complete(receipt(SignatureReceipt.Status.SIGNED), clock.now);
        return collecting.claim(clock.now, Duration.ofSeconds(15));
    }
    SignatureReceipt receipt(SignatureReceipt.Status status) {
        var artifacts = status == SignatureReceipt.Status.SIGNED ? input.request().documents().stream().map(document -> {
            byte[] bytes = results.get(document.attachmentId());
            return new SignatureReceipt.Artifact(document.attachmentId(), bytes.length, sha256(bytes), "application/pdf",
                    List.of(new SignatureReceipt.Proof("company", "f".repeat(64), NOW.plusSeconds(1), null)));
        }).toList() : List.<SignatureReceipt.Artifact>of();
        boolean terminal = status == SignatureReceipt.Status.SIGNED;
        return new SignatureReceipt(input.request().id(), input.request().digest(), status == SignatureReceipt.Status.NOT_FOUND ? 0 : status == SignatureReceipt.Status.SIGNED ? 2 : 1, status, NOW.plusSeconds(1),
                status == SignatureReceipt.Status.NOT_FOUND ? null : "provider-signature-1", terminal ? NOW.plusSeconds(1) : null, artifacts);
    }
    SignatureReceiptVerifier.Verified proof() {
        byte[] body = body(profile, input, receipt(SignatureReceipt.Status.SIGNED));
        return verifier.verify(input, profile, body, sign(key, body), NOW.plusSeconds(2));
    }
    long calls(String suffix) { return requests.stream().filter(value -> value.path().equals("/signature/" + suffix)).count(); }

    private void handle(HttpExchange exchange) {
        try {
            byte[] body = exchange.getRequestBody().readNBytes(Math.toIntExact(SignatureRequest.MAX_TOTAL_BYTES) + 128 * 1024);
            String path = exchange.getRequestURI().getPath();
            requests.add(new Captured(path, exchange.getRequestHeaders().getFirst("Content-Type"), exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Idempotency-Key"), body));
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer " + declaration.getToken());
            if (path.equals("/signature/submit")) {
                verifyMultipart(exchange, body); accepted = true;
            } else if (path.equals("/signature/query")) {
                verifyQuery(JSON.readStrict(new String(body, StandardCharsets.UTF_8), HttpSignatureGateway.Query.class));
                assertThat(exchange.getRequestHeaders().getFirst("Idempotency-Key")).isNull();
            }
            switch (mode) {
                case DROP -> { exchange.close(); return; }
                case UNSIGNED_NOT_FOUND -> { reply(exchange, 404, "text/plain", new byte[0]); return; }
                case AUTHENTICATION -> { reply(exchange, 401, "text/plain", new byte[0]); return; }
                case REDIRECT -> { exchange.getResponseHeaders().set("Location", "/leak"); reply(exchange, 302, "text/plain", new byte[0]); return; }
                default -> { }
            }
            byte[] response;
            if (path.equals("/signature/artifact")) {
                var command = JSON.readStrict(new String(body, StandardCharsets.UTF_8), HttpSignatureGateway.ArtifactQuery.class); verifyQuery(command.operation());
                assertThat(command.receiptDigest()).isEqualTo(receipt(SignatureReceipt.Status.SIGNED).digest()); assertThat(command.revision()).isEqualTo(2);
                response = results.get(command.documentId()); assertThat(command.size()).isEqualTo(response.length); assertThat(command.sha256()).isEqualTo(sha256(response));
                if (mode == Mode.SHORT_FILE) response = Arrays.copyOf(response, response.length - 1);
                if (mode == Mode.CORRUPT_FILE) { response = response.clone(); response[0] ^= 1; }
                if (mode == Mode.OVERSIZED) response = Arrays.copyOf(response, response.length + 1);
            } else {
                var receipt = receipt(path.equals("/signature/submit") ? SignatureReceipt.Status.PENDING : accepted ? SignatureReceipt.Status.SIGNED : SignatureReceipt.Status.NOT_FOUND);
                response = body(profile, input, receipt);
                if (mode == Mode.FOREIGN_REQUEST) response = new String(response, StandardCharsets.UTF_8).replace(input.request().digest(), "0".repeat(64)).getBytes(StandardCharsets.UTF_8);
                if (mode == Mode.OVERSIZED) response = new byte[SignatureReceiptVerifier.MAX_RECEIPT_BYTES + 1];
                exchange.getResponseHeaders().set(HttpSignatureGateway.RECEIPT_SIGNATURE_HEADER, sign(mode == Mode.INVALID_SIGNATURE ? keyPair() : key, response));
                if (mode == Mode.DUPLICATE_SIGNATURE) exchange.getResponseHeaders().add(HttpSignatureGateway.RECEIPT_SIGNATURE_HEADER, sign(key, response));
            }
            if (mode == Mode.ENCODED) exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            if (mode == Mode.EXPIRE_ON_RESPONSE) clock.now = NOW.plusSeconds(30);
            String contentType = mode == Mode.WRONG_CONTENT_TYPE ? "text/plain" : path.equals("/signature/artifact") ? "application/pdf" : "application/json; charset=utf-8";
            if (mode == Mode.DUPLICATE_CONTENT_TYPE) exchange.getResponseHeaders().add("Content-Type", "text/plain");
            reply(exchange, 200, contentType, response);
        } catch (IOException ignored) {
            // 有界接收和超时用例会主动关闭连接，服务方不能把它当作应用成功。
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Throwable failure) { providerFailure.compareAndSet(null, failure); exchange.close(); }
    }
    private void reply(HttpExchange exchange, int status, String type, byte[] bytes) throws IOException, InterruptedException {
        exchange.getResponseHeaders().add("Content-Type", type); exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            if (mode == Mode.SLOW_BODY && bytes.length > 0) {
                output.write(bytes, 0, 1); output.flush(); releaseBody.await(5, TimeUnit.SECONDS); output.write(bytes, 1, bytes.length - 1);
            } else output.write(bytes);
        } finally { exchange.close(); }
    }
    private void verifyMultipart(HttpExchange exchange, byte[] body) {
        assertThat(exchange.getRequestHeaders().getFirst("Idempotency-Key")).isEqualTo(input.request().id().toString());
        String type = exchange.getRequestHeaders().getFirst("Content-Type"); assertThat(type).startsWith("multipart/form-data; boundary=");
        String boundary = type.substring(type.indexOf("boundary=") + "boundary=".length());
        String wire = new String(body, StandardCharsets.ISO_8859_1); var files = new LinkedHashMap<String, byte[]>();
        for (String part : wire.split(Pattern.quote("--" + boundary))) {
            if (part.isEmpty() || part.equals("--\r\n")) continue;
            int separator = part.indexOf("\r\n\r\n"); assertThat(separator).isPositive(); assertThat(part).endsWith("\r\n");
            var name = Pattern.compile("name=\"([^\"]+)\"").matcher(part.substring(0, separator)); assertThat(name.find()).isTrue();
            assertThat(files.put(name.group(1), part.substring(separator + 4, part.length() - 2).getBytes(StandardCharsets.ISO_8859_1))).isNull();
        }
        var metadata = JSON.readStrict(new String(files.remove("request"), StandardCharsets.UTF_8), HttpSignatureGateway.Submit.class);
        assertThat(metadata.protocol()).isEqualTo(HttpSignatureGateway.PROTOCOL); assertThat(metadata.request()).isEqualTo(input.request());
        assertThat(metadata.requestDigest()).isEqualTo(input.request().digest()); assertThat(metadata.targetDigest()).isEqualTo(input.targetDigest());
        assertThat(files).hasSize(originals.size()); originals.forEach((id, bytes) -> assertThat(files.get("file-" + id)).containsExactly(bytes));
    }
    private void verifyQuery(HttpSignatureGateway.Query query) {
        assertThat(query.protocol()).isEqualTo(HttpSignatureGateway.PROTOCOL); assertThat(query.tenantId()).isEqualTo("tenant-a");
        assertThat(query.operationId()).isEqualTo(input.request().id()); assertThat(query.requestDigest()).isEqualTo(input.request().digest()); assertThat(query.targetDigest()).isEqualTo(input.targetDigest());
        assertThat(query.profileKey()).isEqualTo(profile.key()); assertThat(query.profileVersion()).isEqualTo(profile.version()); assertThat(query.profileDigest()).isEqualTo(profile.digest());
    }
    static String sha256(byte[] bytes) { return HexFormat.of().formatHex(SignatureRequest.sha256().digest(bytes)); }
    static void put(LocalDocumentStore store, LocalDocumentStore.Content content, byte[] bytes) {
        var staged = store.stage(content, new ByteArrayInputStream(bytes)); try { store.publish(content, staged); } finally { store.discard(staged); }
    }
    /** 关闭本测试创建的网络和线程，并把服务方断言传回测试线程。 */
    @Override public void close() throws Exception {
        releaseBody.countDown(); provider.stop(0); executor.shutdownNow(); assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(providerFailure.get()).as("provider fixture assertions").isNull();
    }

    /**
     * 合成服务的可重复失败分支，均在回环端点上执行真实 HTTP 传输。
     * @author owlzhangfq@gmail.com
     */
    enum Mode { NORMAL, DROP, UNSIGNED_NOT_FOUND, AUTHENTICATION, REDIRECT, INVALID_SIGNATURE, FOREIGN_REQUEST, DUPLICATE_SIGNATURE,
        WRONG_CONTENT_TYPE, ENCODED, OVERSIZED, SHORT_FILE, CORRUPT_FILE, SLOW_BODY, DUPLICATE_CONTENT_TYPE, EXPIRE_ON_RESPONSE }
    /**
     * 测试中记录实际收到的端点和字节，不从客户端调用次数推断网络行为。
     * @author owlzhangfq@gmail.com
     */
    record Captured(String path, String contentType, String authorization, String idempotencyKey, byte[] body) { }
    /**
     * 显式控制授权和领取时间，真实网络超时仍使用单调等待而非测试时钟。
     * @author owlzhangfq@gmail.com
     */
    static final class MutableClock extends Clock {
        volatile Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
