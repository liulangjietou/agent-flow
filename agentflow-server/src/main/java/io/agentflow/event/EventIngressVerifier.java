package io.agentflow.event;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * 原始字节签名先于业务反序列化；来源和租户必须同时匹配可信配置及签名正文。
 * @author owlzhangfq@gmail.com
 */
@Component
public class EventIngressVerifier {
    public static final String PATH = "/api/v1/integrations/events";
    public static final int MAX_BODY_BYTES = 8192;
    private static final int CLOCK_SKEW_SECONDS = 300;
    private static final int MAX_HEADER_LENGTH = 600;
    private final boolean enabled;
    private final Map<SourceId, Source> sources;
    private final JsonUtil json;

    /** 启动时冻结可信来源；配置异常只输出固定信息，不回显凭据。 */
    public EventIngressVerifier(EventIngressConfiguration configuration, ObjectMapper mapper) {
        enabled = configuration.isEnabled();
        json = new JsonUtil(mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT));
        var configured = new HashMap<SourceId, Source>();
        var usedSecrets = new HashSet<String>();
        if (enabled) {
            try {
                if (configuration.getSources() == null || configuration.getSources().isEmpty() || configuration.getSources().size() > 100) throw invalidConfiguration();
                for (var value : configuration.getSources().values()) {
                    if (value == null || value.tenantId() == null || !value.tenantId().matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}")
                            || value.trustRevision() < 1) throw invalidConfiguration();
                    EventContract.requireKey(value.sourceKey());
                    var keys = new ArrayList<SecretKeySpec>();
                    if (value.enabled() && (value.signingSecrets() == null || value.signingSecrets().isEmpty())) throw invalidConfiguration();
                    if (value.signingSecrets() != null) {
                        if (value.signingSecrets().size() > 2) throw invalidConfiguration();
                        for (String encoded : value.signingSecrets()) {
                            if (encoded == null || !encoded.startsWith("whsec_") || encoded.length() > 94) throw invalidConfiguration();
                            byte[] key = Base64.getDecoder().decode(encoded.substring(6));
                            if (key.length < 32 || key.length > 64 || !Base64.getEncoder().encodeToString(key).equals(encoded.substring(6))
                                    || !usedSecrets.add(encoded)) throw invalidConfiguration();
                            keys.add(new SecretKeySpec(key, "HmacSHA256"));
                        }
                    }
                    if (configured.put(new SourceId(value.tenantId(), value.sourceKey()), new Source(value.trustRevision(), value.enabled(), List.copyOf(keys))) != null) throw invalidConfiguration();
                }
            } catch (RuntimeException failure) { throw invalidConfiguration(); }
        }
        sources = Map.copyOf(configured);
    }

    /** 只有精确的签名 POST 入口跳过用户会话；管理和子路径仍由原用户认证保护。 */
    public static boolean matches(HttpServletRequest request) { return "POST".equals(request.getMethod()) && PATH.equals(request.getRequestURI()); }

    /** 检查原收件的信任修订，不因当前密钥轮换而把已验签消息换成新身份。 */
    public Availability availability(ReceivedEvent event) {
        if (!enabled) return Availability.DISABLED;
        var source = sources.get(new SourceId(event.signal().tenantId(), event.signal().sourceKey()));
        if (source == null || source.revision() != event.trustRevision()) return Availability.CHANGED;
        return source.enabled() ? Availability.AVAILABLE : Availability.DISABLED;
    }

    /** 有界读取、验证投递时间与原始字节，再解析唯一且无附加字段的事件信封。 */
    public ReceivedEvent verify(HttpServletRequest request, Instant now) throws IOException {
        if (!enabled) throw new DomainException("EVENT_INGRESS_DISABLED", "Event ingress is disabled");
        if (request.getQueryString() != null) throw invalid();
        String tenant = header(request, "webhook-tenant"), sourceKey = header(request, "webhook-source"), eventId = header(request, "webhook-id");
        String timestamp = header(request, "webhook-timestamp"), signature = header(request, "webhook-signature");
        var source = sources.get(new SourceId(tenant, sourceKey));
        if (source == null || !source.enabled() || eventId == null || !eventId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
                || timestamp == null || !timestamp.matches("[1-9][0-9]{0,11}") || signature == null) throw unauthenticated();
        var delivered = Instant.ofEpochSecond(Long.parseLong(timestamp));
        if (delivered.isBefore(now.minusSeconds(CLOCK_SKEW_SECONDS)) || delivered.isAfter(now.plusSeconds(CLOCK_SKEW_SECONDS))) throw unauthenticated();
        try {
            var type = MediaType.parseMediaType(header(request, "Content-Type"));
            if (!"application".equals(type.getType()) || !"json".equals(type.getSubtype())
                    || type.getCharset() != null && !StandardCharsets.UTF_8.equals(type.getCharset()) || request.getHeader("Content-Encoding") != null) throw invalid();
        } catch (IllegalArgumentException failure) { throw invalid(); }
        if (request.getContentLengthLong() > MAX_BODY_BYTES) throw tooLarge();
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) throw tooLarge();
        verifySignature(source.keys(), eventId, timestamp, signature, body);
        EventSignal signal;
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
            signal = json.read(text, EventSignal.class);
        } catch (CharacterCodingException | DomainException failure) { throw invalid(); }
        if (signal == null || !tenant.equals(signal.tenantId()) || !sourceKey.equals(signal.sourceKey())) throw invalid();
        return new ReceivedEvent(eventId, digest(body), source.revision(), signal);
    }

    private static void verifySignature(List<SecretKeySpec> keys, String eventId, String timestamp, String header, byte[] body) {
        String[] values = header.split(" ", -1); if (values.length > 4) throw unauthenticated();
        var supplied = new ArrayList<byte[]>();
        for (String value : values) {
            if (!value.matches("v[1-9][0-9]{0,2}[a-z]?,[A-Za-z0-9+/=]{1,128}")) throw unauthenticated();
            if (!value.startsWith("v1,")) continue;
            try {
                byte[] bytes = Base64.getDecoder().decode(value.substring(3));
                if (bytes.length != 32 || !Base64.getEncoder().encodeToString(bytes).equals(value.substring(3))) throw unauthenticated();
                supplied.add(bytes);
            } catch (IllegalArgumentException invalid) { throw unauthenticated(); }
        }
        boolean valid = false;
        try {
            for (var key : keys) {
                var mac = Mac.getInstance("HmacSHA256"); mac.init(key);
                mac.update((eventId + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8));
                byte[] expected = mac.doFinal(body);
                for (byte[] value : supplied) valid |= MessageDigest.isEqual(expected, value);
            }
        } catch (GeneralSecurityException impossible) { throw new IllegalStateException("HMAC unavailable", impossible); }
        if (!valid) throw unauthenticated();
    }
    private static String header(HttpServletRequest request, String name) {
        var values = request.getHeaders(name); if (values == null || !values.hasMoreElements()) return null;
        String value = values.nextElement();
        if (values.hasMoreElements() || value.length() > MAX_HEADER_LENGTH) throw unauthenticated();
        return value;
    }
    private static String digest(byte[] body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)); }
        catch (GeneralSecurityException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
    private static DomainException invalid() { return new DomainException("EVENT_INPUT_INVALID", "Event envelope is invalid"); }
    private static DomainException unauthenticated() { return new DomainException("EVENT_UNAUTHENTICATED", "Event signature or delivery time is invalid"); }
    private static DomainException tooLarge() { return new DomainException("EVENT_BODY_TOO_LARGE", "Event body exceeds the byte limit"); }
    private static IllegalStateException invalidConfiguration() { return new IllegalStateException("Invalid event ingress configuration"); }
    /** @author owlzhangfq@gmail.com */
    public enum Availability { AVAILABLE, DISABLED, CHANGED }
    /** @author owlzhangfq@gmail.com */
    private record SourceId(String tenantId, String sourceKey) { }
    /** @author owlzhangfq@gmail.com */
    private record Source(long revision, boolean enabled, List<SecretKeySpec> keys) { }
}
