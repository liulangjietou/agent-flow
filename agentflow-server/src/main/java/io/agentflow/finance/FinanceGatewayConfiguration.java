package io.agentflow.finance;

import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
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

/**
 * 财务服务按租户显式配置；请求不能覆盖目的地、凭据或超时时间。
 * @author owlzhangfq@gmail.com
 */
@Component
@ConfigurationProperties(prefix = "agentflow.finance-gateway")
public class FinanceGatewayConfiguration {
    private boolean enabled;
    private Map<String, Target> tenants = new LinkedHashMap<>();

    /** 启用时在启动阶段拒绝无效目标；未配置的租户继续返回不可用。 */
    @PostConstruct
    public void validate() {
        if (!enabled) return;
        if (tenants == null) throw invalid();
        tenants.forEach((tenant, target) -> {
            if (StringUtils.isBlank(tenant) || tenant.length() > 64 || target == null) throw invalid();
            target.resolve();
        });
    }

    /** 精确匹配租户，不回退到其他租户或默认目标。 */
    public Optional<Destination> destination(String tenantId) {
        if (!enabled || tenants == null || !tenants.containsKey(tenantId)) return Optional.empty();
        return Optional.of(tenants.get(tenantId).resolve());
    }

    private static IllegalStateException invalid() { return new IllegalStateException("Finance gateway configuration is invalid"); }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public Map<String, Target> getTenants() { return tenants; }
    public void setTenants(Map<String, Target> value) { tenants = value; }

    /**
     * 凭据仅用于对应租户的固定目标，明文 HTTP 仅允许显式本机夹具。
     * @author owlzhangfq@gmail.com
     */
    public static class Target {
        private String endpoint = "";
        private String token = "";
        private int timeoutSeconds = 15;
        private boolean allowUnauthenticatedLoopback;

        private Destination resolve() {
            if (StringUtils.isBlank(endpoint) || timeoutSeconds < 1 || timeoutSeconds > 60 || token == null
                    || token.length() > 4096 || !token.matches("[!-~]*")) throw invalid();
            try {
                URI uri = URI.create(endpoint);
                boolean loopback = "http".equals(uri.getScheme()) && Set.of("127.0.0.1", "[::1]").contains(uri.getHost() == null ? "" : uri.getHost());
                boolean secure = "https".equals(uri.getScheme());
                if ((!secure && !loopback) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                        || uri.getPort() == 0 || uri.getPort() > 65535 || !uri.getRawPath().matches("[/A-Za-z0-9_-]*")
                        || token.isEmpty() && !(loopback && allowUnauthenticatedLoopback)) throw invalid();
                return new Destination(URI.create(endpoint.endsWith("/") ? endpoint : endpoint + "/"), token, Duration.ofSeconds(timeoutSeconds));
            } catch (IllegalArgumentException failure) { throw invalid(); }
        }
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
     * 已校验的单次调用配置，令牌不参与对象文本表示。
     * @author owlzhangfq@gmail.com
     */
    public record Destination(URI baseUri, String token, Duration timeout) {
        /** 排队绑定租户、协议和目的地；凭据轮换不改变已授权目标。 */
        public String digest(String tenant) {
            try {
                String identity = tenant.length() + ":" + tenant + "|finance-contract-1|" + baseUri.toASCIIString();
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
        }
        /** 避免常规日志和诊断意外输出目标凭据。 */
        @Override public String toString() { return "FinanceGatewayDestination[redacted]"; }
    }
}
