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
    private final Map<Key, Destination> destinations;

    /** 生产连接要求 TLS；演示明文仅允许显式启用的回环地址。 */
    public NotificationDestinations(NotificationDeliveryConfiguration configuration,
                                   @Value("${agentflow.auth.demo-enabled:false}") boolean demo) {
        try {
            if (configuration.getSmtpServers() == null || configuration.getBindings() == null
                    || configuration.getSmtpServers().size() > 20 || configuration.getBindings().size() > 10000) throw invalid();
            boolean insecureDemo = demo && configuration.isAllowInsecureInDemo();
            configuration.getSmtpServers().forEach((id, server) -> validateServer(id, server, insecureDemo));
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
                            || binding.channel() != NotificationChannel.EMAIL) throw invalid();
                    var server = configuration.getSmtpServers().get(binding.serverId());
                    if (server == null || !server.tenantId().equals(binding.tenantId())) throw invalid();
                    validateMailbox(binding.address());
                    String fingerprint = String.join("\n", binding.tenantId(), binding.recipient(), binding.channel().name(),
                            id, binding.address(), binding.serverId(), server.host(), Integer.toString(server.port()),
                            server.security().name(), StringUtils.defaultString(server.username()), server.from(), publicUri.toASCIIString());
                    var destination = new Destination(id, binding.tenantId(), binding.recipient(), binding.channel(), binding.address(),
                            server, publicUri.toASCIIString(), binding.enabled() && server.enabled(), digest(fingerprint));
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
                              SmtpServer server, String publicUrl, boolean enabled, String digest) {
        @Override public String toString() { return "NotificationDestination[redacted]"; }
    }
}
