package io.agentflow.organization;

import io.agentflow.common.DomainException;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 每个租户显式配置一个可信组织来源，业务请求不能提供地址、凭据或超时覆盖。
 * @author owlzhangfq@gmail.com
 */
@Component
@ConfigurationProperties(prefix = "agentflow.organization-sync")
public class OrganizationSyncConfiguration {
    private boolean enabled;
    private boolean workerEnabled = true;
    private Map<String, Target> tenants = new LinkedHashMap<>();

    /** 已启用配置在启动阶段校验，未配置租户没有默认来源。 */
    @PostConstruct
    public void validate() {
        if (!enabled) return;
        if (tenants == null) throw invalid();
        tenants.forEach((tenant, target) -> {
            if (StringUtils.isBlank(tenant) || tenant.length() > 64 || tenant.chars().anyMatch(Character::isISOControl) || target == null) throw invalid();
            target.resolve();
        });
    }

    /** 只返回当前租户的配置，不发起网络探测或注册来源。 */
    public Optional<Destination> destination(String tenant) {
        if (!enabled || tenants == null || !tenants.containsKey(tenant)) return Optional.empty();
        return Optional.of(tenants.get(tenant).resolve());
    }

    /** 新批次需要已配置来源；历史读取和取消不使用此限制。 */
    public Destination require(String tenant) {
        return destination(tenant).orElseThrow(() -> new DomainException("ORGANIZATION_SYNC_UNAVAILABLE", "Organization synchronization source is not configured"));
    }

    /** 排队、发送、结果接收与人工应用复核同一逻辑来源及部署目标。 */
    public boolean matches(OrganizationSyncBatch.Context context) {
        return destination(context.tenantId()).filter(value -> value.sourceKey().equals(context.sourceKey())
                && value.digest(context.tenantId()).equals(context.targetDigest())).isPresent();
    }

    /** 原目标失效时要求重新核对，不能把旧事实视为来自新的部署地址。 */
    public void requireCurrent(OrganizationSyncBatch.Context context) {
        if (!matches(context)) throw new DomainException("ORGANIZATION_SYNC_SOURCE_CHANGED", "Organization synchronization source or deployment target changed");
    }

    private static IllegalStateException invalid() { return new IllegalStateException("Organization synchronization configuration is invalid"); }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public boolean isWorkerEnabled() { return workerEnabled; }
    public void setWorkerEnabled(boolean value) { workerEnabled = value; }
    public Map<String, Target> getTenants() { return tenants; }
    public void setTenants(Map<String, Target> value) { tenants = value; }

    /**
     * HTTPS 是企业来源入口；明文本机夹具只能使用固定回环地址并显式允许无凭据模式。
     * @author owlzhangfq@gmail.com
     */
    public static class Target {
        private String sourceKey = "";
        private String endpoint = "";
        private String token = "";
        private int timeoutSeconds = 15;
        private boolean allowUnauthenticatedLoopback;

        private Destination resolve() {
            if (sourceKey == null || !sourceKey.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}") || StringUtils.isBlank(endpoint)
                    || timeoutSeconds < 1 || timeoutSeconds > 60 || token == null || token.length() > 4096 || !token.matches("[!-~]*")) throw invalid();
            try {
                URI uri = URI.create(endpoint);
                boolean loopback = "http".equals(uri.getScheme()) && Set.of("127.0.0.1", "[::1]").contains(uri.getHost() == null ? "" : uri.getHost());
                if (!("https".equals(uri.getScheme()) || loopback) || uri.getHost() == null || uri.getUserInfo() != null
                        || uri.getQuery() != null || uri.getFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535
                        || !uri.getRawPath().matches("[/A-Za-z0-9_-]*") || token.isEmpty() && !(loopback && allowUnauthenticatedLoopback)) throw invalid();
                return new Destination(sourceKey, URI.create(endpoint.endsWith("/") ? endpoint : endpoint + "/"), token, Duration.ofSeconds(timeoutSeconds));
            } catch (IllegalArgumentException failure) { throw invalid(); }
        }
        public String getSourceKey() { return sourceKey; }
        public void setSourceKey(String value) { sourceKey = value; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String value) { endpoint = value; }
        public String getToken() { return token; }
        public void setToken(String value) { token = value; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int value) { timeoutSeconds = value; }
        public boolean isAllowUnauthenticatedLoopback() { return allowUnauthenticatedLoopback; }
        public void setAllowUnauthenticatedLoopback(boolean value) { allowUnauthenticatedLoopback = value; }
    }

    /**
     * 单次只读请求使用的可信配置，诊断文本不输出凭据。
     * @author owlzhangfq@gmail.com
     */
    public record Destination(String sourceKey, URI baseUri, String token, Duration timeout) {
        /** 摘要绑定租户、协议、逻辑来源和地址；凭据轮换不改变来源身份。 */
        public String digest(String tenant) {
            String value = tenant.length() + ":" + tenant + "|organization-contract-" + OrganizationSyncCodec.CONTRACT_VERSION
                    + "|" + sourceKey.length() + ":" + sourceKey + "|" + baseUri.toASCIIString();
            try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
            catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
        }
        /** 防止配置对象意外进入常规日志。 */
        @Override public String toString() { return "OrganizationSyncDestination[redacted]"; }
    }
}
