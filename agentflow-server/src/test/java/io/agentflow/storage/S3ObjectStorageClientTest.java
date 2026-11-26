package io.agentflow.storage;

import com.sun.net.httpserver.HttpServer;
import io.agentflow.common.DomainException;
import io.agentflow.common.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 S3/MinIO 兼容对象存储的 AWS SigV4 签名、不可变发布、完整性校验与 LocalDocumentStore 透明委托。
 * @author owlzhangfq@gmail.com
 */
class S3ObjectStorageClientTest {
    private HttpServer server;
    private Map<String, byte[]> bucketObjects;
    private AtomicReference<String> lastAuthHeader;
    private S3ObjectStorageClient s3Client;

    @BeforeEach
    void setup() throws IOException {
        bucketObjects = new ConcurrentHashMap<>();
        lastAuthHeader = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/agentflow-bucket", exchange -> {
            lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("HEAD".equals(method) && "/agentflow-bucket".equals(path)) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            if ("PUT".equals(method)) {
                byte[] body = exchange.getRequestBody().readAllBytes();
                String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
                if ("*".equals(ifNoneMatch) && bucketObjects.containsKey(path)) {
                    exchange.sendResponseHeaders(412, -1);
                    exchange.close();
                    return;
                }
                bucketObjects.put(path, body);
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            if ("GET".equals(method)) {
                byte[] data = bucketObjects.get(path);
                if (data == null) {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                    return;
                }
                exchange.sendResponseHeaders(200, data.length);
                exchange.getResponseBody().write(data);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
        });
        server.start();

        var props = new S3StorageProperties.S3();
        props.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
        props.setRegion("cn-north-1");
        props.setBucket("agentflow-bucket");
        props.setAccessKey("AKIA_TEST_KEY");
        props.setSecretKey("SECRET_TEST_KEY_VALUE");
        props.setPrefix("prod-docs");
        props.setPathStyleAccess(true);
        s3Client = new S3ObjectStorageClient(props);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void publishesAndReadsObjectsViaSigV4AndVerifiesIntegrity(@TempDir Path tempDir) throws Exception {
        LocalDocumentStore store = new LocalDocumentStore(tempDir.toAbsolutePath().toString(), 1024 * 1024, s3Client);
        assertThat(store.enabled()).isTrue();
        assertThat(store.backendType()).isEqualTo("s3");
        assertThat(store.available()).isTrue();

        byte[] payload = "agentflow-s3-cloud-native-document".getBytes(StandardCharsets.UTF_8);
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        UUID id = UUID.randomUUID();
        var content = new LocalDocumentStore.Content(id, payload.length, sha256);

        assertThat(store.readIfPresent(content)).isEmpty();

        Path staged = store.stage(content, new ByteArrayInputStream(payload));
        try {
            store.publish(content, staged);
            // 重复发布触发 412，客户端回读核验相同指纹保持幂等成功
            store.publish(content, staged);
        } finally {
            store.discard(staged);
        }

        assertThat(lastAuthHeader.get()).startsWith("AWS4-HMAC-SHA256 Credential=AKIA_TEST_KEY/");
        assertThat(lastAuthHeader.get()).contains("/cn-north-1/s3/aws4_request");
        assertThat(store.read(content)).isEqualTo(payload);
        assertThat(store.readIfPresent(content)).contains(payload);

        // 篡改远端对象内容后读取必须触发 FILE_INTEGRITY_FAILED
        bucketObjects.put("/agentflow-bucket/" + s3Client.keyFor(id), "corrupted-bytes-with-different-sha!!".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> store.read(content))
                .isInstanceOf(DomainException.class)
                .extracting(ex -> ((DomainException) ex).code())
                .isEqualTo(ErrorCode.FILE_INTEGRITY_FAILED.name());
    }
}
