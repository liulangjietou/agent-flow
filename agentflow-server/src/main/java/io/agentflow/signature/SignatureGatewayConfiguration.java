package io.agentflow.signature;

import jakarta.annotation.PostConstruct;
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
 * 可信部署配置固定签署授权、回执公钥和服务地址，业务请求不能提交任意目的地。
 * @author owlzhangfq@gmail.com
 */
@Component
@ConfigurationProperties(prefix = "agentflow.signatures.gateway")
public class SignatureGatewayConfiguration {
    private static final int MAX_PROFILES_PER_TENANT = 100;
    private boolean enabled;
    private Map<String, List<Profile>> tenants = new LinkedHashMap<>();

    /** 启用功能时校验全部声明，重复版本或非法目标使启动失败。 */
    @PostConstruct public void validate() { declarations(); }

    /** 保留已停用版本供原操作查询；删除旧配置会使该版本恢复失败，不回退到新版本。 */
    public List<Declaration> declarations() {
        if (!enabled) return List.of();
        if (tenants == null) throw invalid();
        var result = new ArrayList<Declaration>();
        tenants.forEach((tenant, profiles) -> {
            if (!SignatureRequest.literal(tenant, 64) || profiles == null || profiles.size() > MAX_PROFILES_PER_TENANT) throw invalid();
            var identities = new HashSet<String>();
            for (var profile : profiles) {
                if (profile == null) throw invalid();
                var value = profile.resolve(tenant);
                if (!identities.add(value.profile().key() + ":" + value.profile().version())) throw invalid();
                result.add(value);
            }
        });
        return List.copyOf(result);
    }

    /** 只接受精确租户、键和版本，不采用默认租户或最新版本。 */
    public Optional<Declaration> find(String tenant, String key, long version) {
        return declarations().stream().filter(value -> value.profile().tenantId().equals(tenant) && value.profile().key().equals(key) && value.profile().version() == version).findFirst();
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public Map<String, List<Profile>> getTenants() { return tenants; }
    public void setTenants(Map<String, List<Profile>> value) { tenants = value; }

    /**
     * 凭据仅留在部署配置中；对外查询只能投影不含凭据的签署资料。
     * @author owlzhangfq@gmail.com
     */
    public static class Profile {
        private String key;
        private long version;
        private String name;
        private List<String> actors = List.of();
        private List<SignatureRequest.Signer> signers = List.of();
        private String receiptPublicKey;
        private boolean enabled = true;
        private String endpoint = "";
        private String token = "";
        private int timeoutSeconds = 15;
        private boolean allowUnauthenticatedLoopback;

        private Declaration resolve(String tenant) {
            var profile = new SignatureProfile(tenant, key, version, name, actors, signers, receiptPublicKey);
            if (endpoint == null || timeoutSeconds < 1 || timeoutSeconds > 60 || token == null || token.length() > 4096 || !token.matches("[!-~]*")) throw invalid();
            try {
                var uri = URI.create(endpoint);
                boolean loopback = "http".equals(uri.getScheme()) && Set.of("127.0.0.1", "[::1]").contains(uri.getHost() == null ? "" : uri.getHost());
                if ((!"https".equals(uri.getScheme()) && !loopback) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                        || uri.getPort() == 0 || uri.getPort() > 65535 || !uri.getRawPath().matches("[/A-Za-z0-9_-]*")
                        || token.isEmpty() && !(loopback && allowUnauthenticatedLoopback)) throw invalid();
                return new Declaration(profile, enabled, new Destination(URI.create(endpoint.endsWith("/") ? endpoint : endpoint + "/"), token, Duration.ofSeconds(timeoutSeconds)));
            } catch (IllegalArgumentException failure) { throw invalid(); }
        }
        public String getKey() { return key; }
        public void setKey(String value) { key = value; }
        public long getVersion() { return version; }
        public void setVersion(long value) { version = value; }
        public String getName() { return name; }
        public void setName(String value) { name = value; }
        public List<String> getActors() { return actors; }
        public void setActors(List<String> value) { actors = value; }
        public List<SignatureRequest.Signer> getSigners() { return signers; }
        public void setSigners(List<SignatureRequest.Signer> value) { signers = value; }
        public String getReceiptPublicKey() { return receiptPublicKey; }
        public void setReceiptPublicKey(String value) { receiptPublicKey = value; }
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

    /**
     * 地址和公钥随原请求固定；令牌轮换不更换业务目标。
     * @author owlzhangfq@gmail.com
     */
    public record Declaration(SignatureProfile profile, boolean enabled, Destination destination) {
        /** 摘要不包含访问凭据和超时设置。 */
        public String targetDigest() {
            var digest = SignatureRequest.sha256();
            SignatureRequest.add(digest, "agentflow-signature-http-1", profile.digest(), destination.endpoint().toString());
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    /**
     * HTTP 端口只使用部署者声明的固定基础地址。
     * @author owlzhangfq@gmail.com
     */
    public record Destination(URI endpoint, String token, Duration timeout) {
        @Override public String toString() { return "SignatureDestination[redacted]"; }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid trusted signature gateway configuration"); }
}
