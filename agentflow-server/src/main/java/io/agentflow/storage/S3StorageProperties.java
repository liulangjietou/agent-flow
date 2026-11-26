package io.agentflow.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文档存储后端配置：默认保持 local 兼容存量部署，切换为 s3 后支持多副本共享对象存储（AWS S3 / MinIO / 兼容协议）。
 * @author owlzhangfq@gmail.com
 */
@Component
@ConfigurationProperties(prefix = "agentflow.storage")
public class S3StorageProperties {
    private String type = "local";
    private S3 s3 = new S3();

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public S3 getS3() { return s3; }
    public void setS3(S3 s3) { this.s3 = s3; }

    /** 是否显式启用 S3/MinIO 对象存储后端。 */
    public boolean s3Enabled() {
        return "s3".equalsIgnoreCase(type == null ? "" : type.trim());
    }

    /**
     * S3 兼容对象存储连接配置；凭据禁止出现在日志或字符串表示中。
     * @author owlzhangfq@gmail.com
     */
    public static class S3 {
        private String endpoint = "";
        private String region = "us-east-1";
        private String bucket = "";
        private String accessKey = "";
        private String secretKey = "";
        private String prefix = "documents";
        private boolean pathStyleAccess = true;
        private int connectTimeoutSeconds = 5;
        private int requestTimeoutSeconds = 30;

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
        public String getBucket() { return bucket; }
        public void setBucket(String bucket) { this.bucket = bucket; }
        public String getAccessKey() { return accessKey; }
        public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
        public String getPrefix() { return prefix; }
        public void setPrefix(String prefix) { this.prefix = prefix; }
        public boolean isPathStyleAccess() { return pathStyleAccess; }
        public void setPathStyleAccess(boolean pathStyleAccess) { this.pathStyleAccess = pathStyleAccess; }
        public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) { this.connectTimeoutSeconds = connectTimeoutSeconds; }
        public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
        public void setRequestTimeoutSeconds(int requestTimeoutSeconds) { this.requestTimeoutSeconds = requestTimeoutSeconds; }

        @Override
        public String toString() {
            return "S3StorageConfig[region=" + region + ", bucket=" + bucket + ", pathStyle=" + pathStyleAccess + ", redacted]";
        }
    }
}
