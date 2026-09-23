package io.agentflow.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 启动时冻结经过校验的租户目的地；关闭或改址的旧投递不得转发到新地址。
 * @author owlzhangfq@gmail.com
 */
@Component
public class WebhookTargets {
    private final Map<String, Destination> destinations;

    /** 无配置时目录为空；演示 HTTP 需要显式开关，生产模式始终要求 HTTPS。 */
    public WebhookTargets(WebhookConfiguration configuration, @Value("${agentflow.auth.demo-enabled:false}") boolean demo) {
        Map<String, Destination> values = new LinkedHashMap<>();
        if (configuration.getTargets() == null || configuration.getTargets().size() > 20) throw invalid();
        configuration.getTargets().forEach((id, target) -> {
            try {
                if (!id.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}") || target == null
                        || !text(target.tenantId(), 64) || !text(target.label(), 128)) throw invalid();
                URI uri = URI.create(target.url());
                boolean https = "https".equals(uri.getScheme());
                boolean demoHttp = demo && configuration.isAllowInsecureHttpInDemo() && "http".equals(uri.getScheme());
                if ((!https && !demoHttp) || uri.getHost() == null || uri.getRawUserInfo() != null
                        || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535
                        || target.url().length() > 2000 || !target.signingSecret().startsWith("whsec_")) throw invalid();
                byte[] key = Base64.getDecoder().decode(target.signingSecret().substring(6));
                if (key.length < 32 || key.length > 64) throw invalid();
                values.put(id, new Destination(id, target.tenantId(), target.label(), uri, key, target.enabled(),
                        digest(target.tenantId() + "\n" + id + "\n" + uri.toASCIIString())));
            } catch (RuntimeException invalidConfiguration) { throw invalid(); }
        });
        destinations = Map.copyOf(values);
    }

    /** 只向匹配租户且部署启用的目的地排队，不向新目的地补发旧审计。 */
    public List<Destination> enabled(String tenantId) {
        return destinations.values().stream().filter(value -> value.enabled() && value.tenantId().equals(tenantId)).toList();
    }

    /** 发送和人工重试均重新核对当前部署的目的地身份。 */
    public Optional<Destination> find(String tenantId, String id) {
        return Optional.ofNullable(destinations.get(id)).filter(value -> value.tenantId().equals(tenantId));
    }

    /** 管理页面不返回 URL、签名密钥或解码后的密钥。 */
    public List<TargetView> views(String tenantId) {
        return destinations.values().stream().filter(value -> value.tenantId().equals(tenantId))
                .sorted(java.util.Comparator.comparing(Destination::id)).map(value -> new TargetView(value.id(), value.label(), value.enabled())).toList();
    }

    private static boolean text(String value, int max) { return value != null && !value.isBlank() && value.length() <= max && value.chars().noneMatch(Character::isISOControl); }
    private static IllegalStateException invalid() { return new IllegalStateException("Invalid webhook target configuration"); }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }

    /**
     * 仅适配器可使用的目的地，不作为控制器响应。
     * @author owlzhangfq@gmail.com
     */
    public record Destination(String id, String tenantId, String label, URI uri, byte[] key, boolean enabled, String digest) {
        public Destination { key = key.clone(); }
        @Override public byte[] key() { return key.clone(); }
        @Override public String toString() { return "WebhookDestination[redacted]"; }
    }
    /**
     * 租户管理员可见的部署状态，不暴露连接凭据。
     * @author owlzhangfq@gmail.com
     */
    public record TargetView(String id, String label, boolean enabled) { }
}
