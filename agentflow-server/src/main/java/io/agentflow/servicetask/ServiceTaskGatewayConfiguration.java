package io.agentflow.servicetask;

import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 操作与地址由可信部署配置声明，设计器不能新增目的地、凭据或可执行代码。
 * @author owlzhangfq@gmail.com
 */
@Component
@ConfigurationProperties(prefix = "agentflow.service-tasks.gateway")
public class ServiceTaskGatewayConfiguration {
    private static final int MAX_OPERATIONS_PER_TENANT = 100;
    private boolean enabled;
    private Map<String, List<Operation>> tenants = new LinkedHashMap<>();

    /** 启用后一次验证所有声明，歧义键或无效配置使启动失败。 */
    @PostConstruct
    public void validate() { declarations(); }

    /** 返回部署者明确声明的版本，包含停用版本用于保留原契约和恢复查询。 */
    public List<Declaration> declarations() {
        if (!enabled) return List.of();
        if (tenants == null) throw invalid();
        var result = new ArrayList<Declaration>();
        tenants.forEach((tenant, operations) -> {
            if (StringUtils.isBlank(tenant) || tenant.length() > 64 || !tenant.equals(tenant.strip())
                    || tenant.codePoints().anyMatch(Character::isISOControl) || !ServiceTaskContract.unicode(tenant)
                    || operations == null || operations.size() > MAX_OPERATIONS_PER_TENANT) throw invalid();
            var identities = new HashSet<String>();
            for (var operation : operations) {
                if (operation == null) throw invalid();
                var value = operation.resolve(tenant);
                if (!identities.add(value.contract().key() + ":" + value.contract().version())) throw invalid();
                result.add(value);
            }
        });
        return List.copyOf(result);
    }

    /** 精确匹配租户和版本，不使用最新版本或默认租户回退。 */
    public Optional<Declaration> find(String tenant, String key, long version) {
        return declarations().stream().filter(value -> value.tenantId().equals(tenant) && value.contract().key().equals(key) && value.contract().version() == version).findFirst();
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public Map<String, List<Operation>> getTenants() { return tenants; }
    public void setTenants(Map<String, List<Operation>> value) { tenants = value; }

    /** @author owlzhangfq@gmail.com */
    public static class Operation {
        private String key;
        private long version;
        private String name;
        private List<ServiceTaskContract.Parameter> parameters = List.of();
        private boolean enabled = true;
        private String endpoint = "";
        private String token = "";
        private int timeoutSeconds = 15;
        private boolean allowUnauthenticatedLoopback;

        private Declaration resolve(String tenant) {
            var contract = new ServiceTaskContract(key, version, name, parameters);
            if (StringUtils.isBlank(endpoint) || timeoutSeconds < 1 || timeoutSeconds > 60 || token == null
                    || token.length() > 4096 || !token.matches("[!-~]*")) throw invalid();
            try {
                URI uri = URI.create(endpoint);
                boolean loopback = "http".equals(uri.getScheme()) && Set.of("127.0.0.1", "[::1]").contains(uri.getHost() == null ? "" : uri.getHost());
                if ((!"https".equals(uri.getScheme()) && !loopback) || uri.getHost() == null || uri.getUserInfo() != null
                        || uri.getQuery() != null || uri.getFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535
                        || !uri.getRawPath().matches("[/A-Za-z0-9_-]*") || token.isEmpty() && !(loopback && allowUnauthenticatedLoopback)) throw invalid();
                return new Declaration(tenant, contract, enabled, new Destination(URI.create(endpoint.endsWith("/") ? endpoint : endpoint + "/"), token, Duration.ofSeconds(timeoutSeconds)));
            } catch (IllegalArgumentException failure) { throw invalid(); }
        }
        public String getKey() { return key; }
        public void setKey(String value) { key = value; }
        public long getVersion() { return version; }
        public void setVersion(long value) { version = value; }
        public String getName() { return name; }
        public void setName(String value) { name = value; }
        public List<ServiceTaskContract.Parameter> getParameters() { return parameters; }
        public void setParameters(List<ServiceTaskContract.Parameter> value) { parameters = value; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { enabled = value; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String value) { endpoint = value; }
        public String getToken() { return token; }
        public void setToken(String value) { token = value; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int value) { timeoutSeconds = value; }
        public boolean isAllowUnauthenticatedLoopback() { return allowUnauthenticatedLoopback; }
        public void setAllowUnauthenticatedLoopback(boolean value) { allowUnauthenticatedLoopback = value; }
    }

    /** @author owlzhangfq@gmail.com */
    public record Declaration(String tenantId, ServiceTaskContract contract, boolean enabled, Destination destination) {
        /** 目标身份绑定契约和协议；轮换凭据不改写原授权目标。 */
        public String targetDigest() {
            var digest = ServiceTaskContract.sha256();
            ServiceTaskContract.add(digest, "agentflow-service-http-1", tenantId, contract.digest(), destination.baseUri().toASCIIString());
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    /** @author owlzhangfq@gmail.com */
    public record Destination(URI baseUri, String token, Duration timeout) {
        /** 诊断对象不输出地址及凭据。 */
        @Override public String toString() { return "ServiceTaskDestination[redacted]"; }
    }

    private static IllegalStateException invalid() { return new IllegalStateException("Service task gateway configuration is invalid"); }
}
