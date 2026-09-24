package io.agentflow.auth;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 企业认证的显式部署配置；空映射不授予任何租户或角色，不从请求补齐可信地址。
 * @author owlzhangfq@gmail.com
 */
@ConfigurationProperties("agentflow.auth.oidc")
public record OidcProperties(boolean enabled, String issuer, String clientId, String clientSecret,
                             String tenantClaim, String rolesClaim, Map<String, String> tenantMappings,
                             Map<String, Set<String>> roleMappings, boolean allowInsecureLoopback) {
    public OidcProperties {
        tenantMappings = tenantMappings == null ? Map.of() : Map.copyOf(tenantMappings);
        roleMappings = roleMappings == null ? Map.of() : roleMappings.entrySet().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
    }

    /** 配置对象禁止通过自动生成的字符串表示暴露客户端密钥。 */
    @Override
    public String toString() { return "OidcProperties[enabled=" + enabled + "]"; }

    /** 启用时一次核对所有必需配置；演示与企业认证不能同时启用。 */
    public void validate(boolean demoEnabled, boolean production, String webOrigin) {
        if (!enabled) return;
        if (demoEnabled || (production && allowInsecureLoopback) || StringUtils.isAnyBlank(clientId, clientSecret,
                tenantClaim, rolesClaim) || tenantMappings.isEmpty() || roleMappings.isEmpty()) {
            throw new IllegalArgumentException("Explicit OIDC credentials and identity mappings are required; demo authentication must be disabled");
        }
        if (!tenantClaim.matches("[A-Za-z][A-Za-z0-9_]{0,63}") || !rolesClaim.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("OIDC claims must use explicit top-level names");
        }
        requireTrustedUrl(issuer);
        URI origin = requireTrustedUrl(webOrigin);
        if (StringUtils.isNotEmpty(origin.getPath()) && !"/".equals(origin.getPath())) {
            throw new IllegalArgumentException("OIDC frontend origin cannot contain a path");
        }
        if (tenantMappings.entrySet().stream().anyMatch(entry -> StringUtils.isBlank(entry.getKey())
                || !entry.getValue().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))
                || roleMappings.entrySet().stream().anyMatch(entry -> StringUtils.isBlank(entry.getKey())
                || entry.getValue().isEmpty() || entry.getValue().stream().anyMatch(role -> !role.matches("[A-Za-z0-9_][A-Za-z0-9_.@-]{0,127}")))) {
            throw new IllegalArgumentException("OIDC tenant or role mapping is invalid");
        }
    }

    /** 发现文档中的端点也遵守同一传输边界；仅显式本机验收允许回环 HTTP。 */
    public URI requireTrustedUrl(String value) {
        URI uri;
        try { uri = URI.create(value); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("OIDC endpoint URL is invalid"); }
        String host = uri.getHost();
        boolean loopback = "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
        if (host == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null || uri.getRawQuery() != null
                || !("https".equals(uri.getScheme()) || allowInsecureLoopback && loopback && "http".equals(uri.getScheme()))) {
            throw new IllegalArgumentException("OIDC endpoints require HTTPS; only explicit loopback tests may use HTTP");
        }
        return uri;
    }
}
