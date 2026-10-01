package io.agentflow.notification;

import jakarta.mail.internet.InternetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import static io.agentflow.notification.NotificationDeliveryConfiguration.*;

/** 启动时校验并冻结部署绑定；旧消息不能随配置变更转投新账号。 @author owlzhangfq@gmail.com */
@Component
public class NotificationDestinations {
    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "::1");
    private static final int MAX_WECOM_TEXT_BYTES = 2048;
    private final Map<Key, Destination> destinations;

    /** 生产连接要求 TLS；演示明文仅允许显式启用的回环地址。 */
    public NotificationDestinations(NotificationDeliveryConfiguration configuration,
                                   @Value("${agentflow.auth.demo-enabled:false}") boolean demo) {
        try {
            if (configuration.getSmtpServers() == null || configuration.getWecomApps() == null || configuration.getBindings() == null
                    || configuration.getSmtpServers().size() + configuration.getWecomApps().size() > 20
                    || configuration.getBindings().size() > 10000) throw invalid();
            boolean insecureDemo = demo && configuration.isAllowInsecureInDemo();
            configuration.getSmtpServers().forEach((id, server) -> validateServer(id, server, insecureDemo));
            configuration.getWecomApps().forEach((id, app) -> validateWeCom(id, app, insecureDemo));
            Map<Key, Destination> values = new HashMap<>();
            if (!configuration.getBindings().isEmpty()) {
                URI publicUri = URI.create(configuration.getPublicUrl());
                boolean secure = "https".equals(publicUri.getScheme());
                boolean loopbackDemo = insecureDemo && "http".equals(publicUri.getScheme()) && LOOPBACK.contains(publicUri.getHost());
                if ((!secure && !loopbackDemo) || publicUri.getHost() == null || publicUri.getRawUserInfo() != null
                        || publicUri.getRawQuery() != null || publicUri.getRawFragment() != null
                        || publicUri.getPort() == 0 || publicUri.getPort() > 65535 || publicUri.toString().length() > 2000) throw invalid();
                configuration.getBindings().forEach((id, binding) -> {
                    if (!identifier(id) || binding == null || !text(binding.tenantId(), 64) || !text(binding.recipient(), 128)
                            || binding.channel() == null) throw invalid();
                    var destination = switch (binding.channel()) {
                        case EMAIL -> email(id, binding, configuration, publicUri.toASCIIString());
                        case ENTERPRISE_IM -> wecom(id, binding, configuration, publicUri.toASCIIString());
                    };
                    if (values.put(new Key(binding.tenantId(), binding.recipient(), binding.channel()), destination) != null) throw invalid();
                });
            }
            destinations = Map.copyOf(values);
        } catch (RuntimeException invalidConfiguration) {
            // 部署值可能包含凭据，不把底层解析异常或原值附到错误中。
            throw invalid();
        }
    }

    /** 返回当前身份的原渠道绑定，调用方必须同时核对启用状态和已冻结摘要。 */
    public Optional<Destination> find(String tenant, String recipient, NotificationChannel channel) {
        return Optional.ofNullable(destinations.get(new Key(tenant, recipient, channel)));
    }

    private static Destination email(String id, Binding binding, NotificationDeliveryConfiguration configuration, String publicUrl) {
        var server = configuration.getSmtpServers().get(binding.serverId());
        if (server == null || !server.tenantId().equals(binding.tenantId())) throw invalid();
        validateMailbox(binding.address());
        // 保持既有邮件摘要的顺序和编码，升级不能使原队列全部失效。
        String fingerprint = String.join("\n", binding.tenantId(), binding.recipient(), binding.channel().name(),
                id, binding.address(), binding.serverId(), server.host(), Integer.toString(server.port()),
                server.security().name(), StringUtils.defaultString(server.username()), server.from(), publicUrl);
        return new Destination(id, binding.tenantId(), binding.recipient(), binding.channel(), binding.address(),
                server, publicUrl, binding.enabled() && server.enabled(), digest(fingerprint), null);
    }

    private static Destination wecom(String id, Binding binding, NotificationDeliveryConfiguration configuration, String publicUrl) {
        var app = configuration.getWecomApps().get(binding.serverId());
        if (app == null || !app.tenantId().equals(binding.tenantId()) || binding.address() == null
                || !binding.address().matches("[A-Za-z0-9][A-Za-z0-9_@.-]{0,63}")
                || NotificationMessageText.text(publicUrl).getBytes(StandardCharsets.UTF_8).length > MAX_WECOM_TEXT_BYTES) throw invalid();
        String fingerprint = String.join("\n", binding.tenantId(), binding.recipient(), binding.channel().name(), id,
                binding.address(), binding.serverId(), app.corpId(), Long.toString(app.agentId()), app.baseUrl(), publicUrl);
        return new Destination(id, binding.tenantId(), binding.recipient(), binding.channel(), binding.address(),
                null, publicUrl, binding.enabled() && app.enabled(), digest(fingerprint), app);
    }

    private static void validateWeCom(String id, WeComApp app, boolean insecureDemo) {
        if (!identifier(id) || app == null || !text(app.tenantId(), 64) || !text(app.corpId(), 128)
                || !app.corpId().matches("[A-Za-z0-9_-]+") || app.agentId() < 1 || app.agentId() > Integer.MAX_VALUE
                || !text(app.secret(), 4096)) throw invalid();
        URI base = URI.create(app.baseUrl());
        boolean local = insecureDemo && "http".equals(base.getScheme()) && LOOPBACK.contains(base.getHost())
                && base.getPort() > 0 && base.getPort() <= 65535 && StringUtils.isEmpty(base.getRawPath())
                && base.getRawQuery() == null && base.getRawFragment() == null && base.getRawUserInfo() == null;
        if (!WeComApp.OFFICIAL_BASE_URL.equals(app.baseUrl()) && !local) throw invalid();
    }

    private static void validateServer(String id, SmtpServer server, boolean insecureDemo) {
        if (!identifier(id) || server == null || !text(server.tenantId(), 64) || !text(server.host(), 253)
                || !(server.host().matches("[A-Za-z0-9.-]+") || "::1".equals(server.host()))
                || server.port() < 1 || server.port() > 65535 || server.security() == null) throw invalid();
        if (server.security() == Security.DEMO_PLAIN && (!insecureDemo || !LOOPBACK.contains(server.host()))) throw invalid();
        boolean anonymous = StringUtils.isEmpty(server.username()) && StringUtils.isEmpty(server.password());
        if (!anonymous && (!text(server.username(), 256) || StringUtils.isEmpty(server.password()) || server.password().length() > 4096)) throw invalid();
        validateMailbox(server.from());
    }

    private static void validateMailbox(String value) {
        try {
            if (!text(value, 254) || value.chars().anyMatch(c -> c > 126 || Character.isWhitespace(c))) throw invalid();
            var addresses = InternetAddress.parse(value, true);
            if (addresses.length != 1 || addresses[0].isGroup() || addresses[0].getPersonal() != null
                    || !value.equals(addresses[0].getAddress())) throw invalid();
            addresses[0].validate();
        } catch (jakarta.mail.internet.AddressException invalidAddress) { throw invalid(); }
    }
    private static boolean identifier(String value) { return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"); }
    private static boolean text(String value, int max) { return StringUtils.isNotBlank(value) && value.length() <= max && value.chars().noneMatch(Character::isISOControl); }
    private static IllegalStateException invalid() { return new IllegalStateException("Invalid notification delivery configuration"); }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
    private record Key(String tenant, String recipient, NotificationChannel channel) { }

    /** 只供发送适配器使用，不作为 HTTP 响应或日志内容。 */
    public record Destination(String id, String tenantId, String recipient, NotificationChannel channel, String address,
                              SmtpServer server, String publicUrl, boolean enabled, String digest, WeComApp wecomApp) {
        @Override public String toString() { return "NotificationDestination[redacted]"; }
    }
}
