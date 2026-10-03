package io.agentflow.finance.callback;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.finance.FinanceGatewayConfiguration;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * 接入层只验原始字节、投递时间和最小信号，验签成功前不反序列化业务 JSON。
 * @author owlzhangfq@gmail.com
 */
@Component
public class PaymentCallbackVerifier {
    public static final String PATH = "/api/v1/integrations/payment/callbacks";
    public static final int MAX_BODY_BYTES = 8192;
    private static final int MAX_CLOCK_SKEW_SECONDS = 300;
    private final boolean enabled;
    private final Map<String, List<SecretKeySpec>> keys;
    private final FinanceGatewayConfiguration gateway;
    private final JsonUtil json;

    /** 启动时冻结有界租户密钥，配置错误只输出固定错误，不输出密钥原文。 */
    public PaymentCallbackVerifier(PaymentCallbackConfiguration configuration, FinanceGatewayConfiguration gateway, ObjectMapper mapper) {
        this.gateway = gateway; this.enabled = configuration.isEnabled();
        this.json = new JsonUtil(mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT));
        Map<String, List<SecretKeySpec>> validated = new HashMap<>();
        if (enabled) {
            try {
                if (configuration.getTenants() == null || configuration.getTenants().isEmpty() || configuration.getTenants().size() > 100) throw invalidConfiguration();
                configuration.getTenants().forEach((tenant, value) -> {
                    if (tenant == null || !tenant.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}") || value == null
                            || value.signingSecrets() == null || value.signingSecrets().isEmpty() || value.signingSecrets().size() > 2
                            || gateway.destination(tenant).isEmpty()) throw invalidConfiguration();
                    var secrets = new ArrayList<SecretKeySpec>();
                    for (String encoded : value.signingSecrets()) {
                        if (encoded == null || !encoded.startsWith("whsec_") || encoded.length() > 94) throw invalidConfiguration();
                        byte[] key = Base64.getDecoder().decode(encoded.substring(6));
                        if (key.length < 32 || key.length > 64 || !Base64.getEncoder().encodeToString(key).equals(encoded.substring(6))) throw invalidConfiguration();
                        secrets.add(new SecretKeySpec(key, "HmacSHA256"));
                    }
                    validated.put(tenant, List.copyOf(secrets));
                });
            } catch (RuntimeException failure) { throw invalidConfiguration(); }
        }
        keys = Map.copyOf(validated);
    }

    /** 只有精确 POST 路径交由签名认证，其他方法和子路径仍要求用户认证。 */
    public static boolean matches(HttpServletRequest request) { return "POST".equals(request.getMethod()) && PATH.equals(request.getRequestURI()); }

    /** 有界读取后验证标准签名字节，重复头、压缩体和非 UTF-8 JSON 均拒绝。 */
    public Verified verify(HttpServletRequest request, Instant now) throws IOException {
        if (!enabled) throw new DomainException("PAYMENT_CALLBACK_DISABLED", "Payment callbacks are disabled");
        String tenant = header(request, "webhook-tenant"), event = header(request, "webhook-id");
        String timestamp = header(request, "webhook-timestamp"), signatures = header(request, "webhook-signature");
        if (tenant == null || !keys.containsKey(tenant) || event == null || !event.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,127}")
                || timestamp == null || !timestamp.matches("[1-9][0-9]{0,11}") || signatures == null || signatures.length() > 600) throw unauthenticated();
        var deliveredAt = Instant.ofEpochSecond(Long.parseLong(timestamp));
        if (deliveredAt.isBefore(now.minusSeconds(MAX_CLOCK_SKEW_SECONDS)) || deliveredAt.isAfter(now.plusSeconds(MAX_CLOCK_SKEW_SECONDS))) throw unauthenticated();
        try {
            var contentType = MediaType.parseMediaType(header(request, "Content-Type"));
            if (!"application".equals(contentType.getType()) || !"json".equals(contentType.getSubtype())
                    || contentType.getCharset() != null && !StandardCharsets.UTF_8.equals(contentType.getCharset())
                    || request.getHeader("Content-Encoding") != null) throw invalidPayload();
        } catch (IllegalArgumentException failure) { throw invalidPayload(); }
        if (request.getContentLengthLong() > MAX_BODY_BYTES) throw tooLarge();
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) throw tooLarge();
        verifySignature(keys.get(tenant), event, timestamp, signatures, body);
        Signal signal;
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
            signal = json.read(text, Signal.class);
        } catch (CharacterCodingException | DomainException failure) { throw invalidPayload(); }
        if (signal == null || signal.contractVersion() != 1 || !"payment.changed".equals(signal.type()) || !tenant.equals(signal.tenantId())
                || signal.kind() == null || signal.authorizationId() == null || signal.commandDigest() == null
                || !signal.commandDigest().matches("[a-f0-9]{64}") || signal.sourceRevision() < 1) throw invalidPayload();
        var target = gateway.destination(tenant).orElseThrow(() -> new DomainException("PAYMENT_CALLBACK_DISABLED", "Payment callback gateway is unavailable"));
        return new Verified(event, digest(body), target.digest(tenant), signal);
    }

    private static void verifySignature(List<SecretKeySpec> keys, String event, String timestamp, String header, byte[] body) {
        String[] values = header.split(" ", -1);
        if (values.length > 4) throw unauthenticated();
        var signatures = new ArrayList<byte[]>();
        for (String value : values) {
            if (!value.matches("v[1-9][0-9]{0,2},[A-Za-z0-9+/=]{1,128}")) throw unauthenticated();
            if (!value.startsWith("v1,")) continue;
            try {
                byte[] decoded = Base64.getDecoder().decode(value.substring(3));
                if (decoded.length != 32 || !Base64.getEncoder().encodeToString(decoded).equals(value.substring(3))) throw unauthenticated();
                signatures.add(decoded);
            } catch (IllegalArgumentException failure) { throw unauthenticated(); }
        }
        boolean valid = false;
        try {
            for (var key : keys) {
                var mac = Mac.getInstance("HmacSHA256"); mac.init(key);
                mac.update((event + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8));
                byte[] expected = mac.doFinal(body);
                for (byte[] supplied : signatures) valid |= MessageDigest.isEqual(expected, supplied);
            }
        } catch (GeneralSecurityException impossible) { throw new IllegalStateException("HMAC unavailable", impossible); }
        if (!valid) throw unauthenticated();
    }
    private static String header(HttpServletRequest request, String name) {
        var values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) return null;
        String value = values.nextElement();
        if (values.hasMoreElements() || value.length() > 600) throw unauthenticated();
        return value;
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (GeneralSecurityException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
    private static DomainException invalidPayload() { return new DomainException("PAYMENT_CALLBACK_INVALID", "Invalid payment callback payload"); }
    private static DomainException unauthenticated() { return new DomainException("PAYMENT_CALLBACK_UNAUTHENTICATED", "Payment callback signature or delivery time is invalid"); }
    private static DomainException tooLarge() { return new DomainException("PAYMENT_CALLBACK_TOO_LARGE", "Payment callback body exceeds the byte limit"); }
    private static IllegalStateException invalidConfiguration() { return new IllegalStateException("Invalid payment callback configuration"); }

    /**
     * 回调仅唤醒原交易查询，不能携带可覆盖本地资金事实的金额、账户或成功声明。
     * @author owlzhangfq@gmail.com
     */
    public record Signal(int contractVersion, String type, String tenantId, Kind kind, UUID authorizationId, String commandDigest, long sourceRevision) { }
    /**
     * 两种已存在的支付指令分别进入其自身领域状态机。
     * @author owlzhangfq@gmail.com
     */
    public enum Kind { EMPLOYEE, SUPPLIER }
    /**
     * 仅在验签成功后传给应用服务，不保存密钥、签名或原始请求体。
     * @author owlzhangfq@gmail.com
     */
    public record Verified(String eventId, String payloadDigest, String targetDigest, Signal signal) { }
}
