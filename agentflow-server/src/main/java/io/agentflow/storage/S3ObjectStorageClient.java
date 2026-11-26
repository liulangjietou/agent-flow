package io.agentflow.storage;

import io.agentflow.common.DomainException;
import io.agentflow.common.ErrorCode;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * 纯 JDK 17 实现的 S3/MinIO 兼容对象存储客户端（AWS Signature Version 4）。
 * 禁用 HTTP 重定向以防止签名凭据泄露；写入与读取均严格复核 SHA-256 指纹与字节长度。
 * @author owlzhangfq@gmail.com
 */
public class S3ObjectStorageClient {
    private static final Logger LOG = LoggerFactory.getLogger(S3ObjectStorageClient.class);
    private static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String SERVICE = "s3";
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final int COPY_BUFFER_BYTES = 64 * 1024;

    private final URI endpoint;
    private final String region;
    private final String bucket;
    private final String accessKey;
    private final String secretKey;
    private final String prefix;
    private final boolean pathStyleAccess;
    private final Duration requestTimeout;
    private final HttpClient client;
    private final Clock clock;

    /** 依据已配置属性构造对象存储客户端并校验必填参数。 */
    public S3ObjectStorageClient(S3StorageProperties.S3 config) {
        this(config, Clock.systemUTC());
    }

    S3ObjectStorageClient(S3StorageProperties.S3 config, Clock clock) {
        if (config == null || StringUtils.isBlank(config.getEndpoint()) || StringUtils.isBlank(config.getBucket())
                || StringUtils.isBlank(config.getRegion()) || StringUtils.isBlank(config.getAccessKey())
                || StringUtils.isBlank(config.getSecretKey())) {
            throw new IllegalArgumentException("S3 storage requires endpoint, region, bucket, accessKey and secretKey");
        }
        URI parsed = URI.create(config.getEndpoint().trim());
        String scheme = parsed.getScheme();
        if ((!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme))
                || StringUtils.isBlank(parsed.getHost())
                || parsed.getRawUserInfo() != null
                || parsed.getRawQuery() != null
                || parsed.getRawFragment() != null) {
            throw new IllegalArgumentException("Invalid S3 endpoint URI");
        }
        String bucketName = config.getBucket().trim();
        if (!bucketName.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) {
            throw new IllegalArgumentException("Invalid S3 bucket name");
        }
        this.endpoint = parsed;
        this.region = config.getRegion().trim();
        this.bucket = bucketName;
        this.accessKey = config.getAccessKey().trim();
        this.secretKey = config.getSecretKey().trim();
        this.prefix = normalizePrefix(config.getPrefix());
        this.pathStyleAccess = config.isPathStyleAccess();
        int connectSeconds = Math.max(1, Math.min(30, config.getConnectTimeoutSeconds()));
        int requestSeconds = Math.max(2, Math.min(120, config.getRequestTimeoutSeconds()));
        this.requestTimeout = Duration.ofSeconds(requestSeconds);
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(connectSeconds))
                .build();
        this.clock = clock;
    }

    /** 将已核验指纹的本地暂存文件上传至对象存储；若对象已存在则回读核验一致性，不覆盖历史对象。 */
    public void putObject(LocalDocumentStore.Content file, Path staged) {
        String key = objectKey(file.id());
        try {
            HttpRequest request = signedRequest("PUT", key, file.sha256(),
                    HttpRequest.BodyPublishers.ofFile(staged), true);
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return;
            }
            if (status == 412 || status == 409) {
                // 目标键已存在：按不可变语义回读核验内容指纹，不覆写原对象。
                getObject(file);
                return;
            }
            LOG.error("S3 putObject failed, errorCode={}, status={}, fileId={}", "FILE_STORAGE_UNAVAILABLE", status, file.id());
            throw unavailable();
        } catch (DomainException domain) {
            throw domain;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException | RuntimeException failure) {
            LOG.error("S3 putObject IO failed, errorCode={}, fileId={}", "FILE_STORAGE_UNAVAILABLE", file.id(), failure);
            throw unavailable();
        }
    }

    /** 读取完整字节并严格核对长度与 SHA-256 指纹。 */
    public byte[] getObject(LocalDocumentStore.Content file) {
        return getObjectInternal(file, false)
                .orElseThrow(() -> new DomainException(ErrorCode.FILE_INTEGRITY_FAILED,
                        "Stored object is missing or differs from its fingerprint"));
    }

    /** 对象返回 404 时返回空；其他状态或完整性破坏均抛出异常。 */
    public Optional<byte[]> getObjectIfPresent(LocalDocumentStore.Content file) {
        return getObjectInternal(file, true);
    }

    /** 轻量级 HEAD 桶探针，用于系统健康检查。 */
    public boolean headBucket() {
        try {
            HttpRequest request = signedRequest("HEAD", null, EMPTY_SHA256, HttpRequest.BodyPublishers.noBody(), false);
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException failure) {
            LOG.error("S3 headBucket failed, errorCode={}, bucket={}", "FILE_STORAGE_UNAVAILABLE", bucket, failure);
            return false;
        }
    }

    private Optional<byte[]> getObjectInternal(LocalDocumentStore.Content file, boolean allowMissing) {
        if (file.size() < 0 || file.size() > LocalDocumentStore.MAX_SUPPORTED_BYTES) {
            throw unavailable();
        }
        String key = objectKey(file.id());
        try {
            HttpRequest request = signedRequest("GET", key, EMPTY_SHA256, HttpRequest.BodyPublishers.noBody(), false);
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status == 404) {
                try (InputStream ignored = response.body()) { /* 释放连接 */ }
                return allowMissing ? Optional.empty() : Optional.empty();
            }
            if (status < 200 || status >= 300) {
                try (InputStream ignored = response.body()) { /* 释放连接 */ }
                LOG.error("S3 getObject failed, errorCode={}, status={}, fileId={}", "FILE_STORAGE_UNAVAILABLE", status, file.id());
                throw unavailable();
            }
            byte[] bytes;
            try (InputStream body = response.body()) {
                bytes = readBounded(body, file.size());
            }
            file.verify(bytes.length, sha256Hex(bytes));
            return Optional.of(bytes);
        } catch (DomainException domain) {
            if (ErrorCode.FILE_CONTENT_MISMATCH.name().equals(domain.code())) {
                throw new DomainException(ErrorCode.FILE_INTEGRITY_FAILED,
                        "Stored attachment is missing or differs from its fingerprint");
            }
            throw domain;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException | RuntimeException failure) {
            LOG.error("S3 getObject IO failed, errorCode={}, fileId={}", "FILE_STORAGE_UNAVAILABLE", file.id(), failure);
            throw unavailable();
        }
    }

    private byte[] readBounded(InputStream input, long expectedSize) throws IOException {
        int maxBytes = (int) expectedSize;
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, COPY_BUFFER_BYTES));
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > expectedSize) {
                throw new DomainException(ErrorCode.FILE_CONTENT_MISMATCH, "Object size exceeds registered length");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private HttpRequest signedRequest(String method, String key, String payloadHash,
                                      HttpRequest.BodyPublisher body, boolean conditionalCreate) {
        Instant now = clock.instant();
        String amzDate = AMZ_DATE.format(now);
        String dateStamp = DATE_STAMP.format(now);

        Target target = resolveTarget(key);
        String canonicalHeaders = "host:" + target.hostHeader() + "\n"
                + "x-amz-content-sha256:" + payloadHash + "\n"
                + "x-amz-date:" + amzDate + "\n";
        String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
        String canonicalRequest = method + "\n"
                + target.canonicalUri() + "\n"
                + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + payloadHash;

        String credentialScope = dateStamp + "/" + region + "/" + SERVICE + "/aws4_request";
        String stringToSign = ALGORITHM + "\n"
                + amzDate + "\n"
                + credentialScope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        byte[] signingKey = deriveSigningKey(secretKey, dateStamp, region);
        String signature = HexFormat.of().formatHex(hmacSha256(signingKey, stringToSign));
        String authorization = ALGORITHM
                + " Credential=" + accessKey + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;

        HttpRequest.Builder builder = HttpRequest.newBuilder(target.uri())
                .timeout(requestTimeout)
                .header("x-amz-date", amzDate)
                .header("x-amz-content-sha256", payloadHash)
                .header("Authorization", authorization);
        if (conditionalCreate) {
            builder.header("Content-Type", "application/octet-stream");
            builder.header("If-None-Match", "*");
        }
        return builder.method(method, body).build();
    }

    private Target resolveTarget(String key) {
        String host = pathStyleAccess ? endpoint.getHost() : bucket + "." + endpoint.getHost();
        int port = endpoint.getPort();
        boolean defaultPort = (port == -1)
                || ("https".equalsIgnoreCase(endpoint.getScheme()) && port == 443)
                || ("http".equalsIgnoreCase(endpoint.getScheme()) && port == 80);
        String hostHeader = defaultPort ? host : host + ":" + port;

        String basePath = StringUtils.stripEnd(StringUtils.defaultString(endpoint.getRawPath()), "/");
        StringBuilder canonicalPath = new StringBuilder();
        if (!basePath.isEmpty()) {
            canonicalPath.append(basePath);
        }
        if (pathStyleAccess) {
            canonicalPath.append('/').append(uriEncodeSegment(bucket));
        }
        if (StringUtils.isNotBlank(key)) {
            for (String segment : key.split("/")) {
                if (!segment.isEmpty()) {
                    canonicalPath.append('/').append(uriEncodeSegment(segment));
                }
            }
        }
        if (canonicalPath.isEmpty()) {
            canonicalPath.append('/');
        }
        String uriText = endpoint.getScheme() + "://" + hostHeader + canonicalPath;
        return new Target(URI.create(uriText), hostHeader, canonicalPath.toString());
    }

    private String objectKey(UUID id) {
        return prefix.isEmpty() ? id + ".bin" : prefix + "/" + id + ".bin";
    }

    private static String normalizePrefix(String raw) {
        if (StringUtils.isBlank(raw)) return "";
        String cleaned = StringUtils.strip(raw.trim(), "/");
        if (cleaned.isEmpty()) return "";
        if (!cleaned.matches("[A-Za-z0-9._/-]{1,128}") || cleaned.contains("..")) {
            throw new IllegalArgumentException("Invalid S3 object key prefix");
        }
        return cleaned;
    }

    private static String uriEncodeSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    private static byte[] deriveSigningKey(String secret, String dateStamp, String region) {
        byte[] kSecret = ("AWS4" + secret).getBytes(StandardCharsets.UTF_8);
        byte[] kDate = hmacSha256(kSecret, dateStamp);
        byte[] kRegion = hmacSha256(kDate, region);
        byte[] kService = hmacSha256(kRegion, SERVICE);
        return hmacSha256(kService, "aws4_request");
    }

    private static byte[] hmacSha256(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception failure) {
            throw new IllegalStateException("HmacSHA256 unavailable", failure);
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static DomainException unavailable() {
        return new DomainException(ErrorCode.FILE_STORAGE_UNAVAILABLE, "Document storage is unavailable");
    }

    /**
     * 解析后的签名目标地址与规范路径。
     * @author owlzhangfq@gmail.com
     */
    private record Target(URI uri, String hostHeader, String canonicalUri) { }

    /** 返回当前配置桶名（不含凭据），供测试与诊断核对。 */
    String bucket() { return bucket; }

    /** 返回已规范化的对象键，供测试核对路径映射。 */
    String keyFor(UUID id) { return objectKey(id); }

    /** 测试用：检查是否使用路径风格。 */
    boolean pathStyleAccess() { return pathStyleAccess; }
}
