package io.agentflow.event;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 合成密钥仅供本地测试；签名覆盖真实提交的原始字节。
 * @author owlzhangfq@gmail.com
 */
public final class EventTestRequests {
    static final byte[] KEY = "event-test-signing-key-0123456789!".getBytes(StandardCharsets.UTF_8);
    public static final String SECRET = "whsec_" + Base64.getEncoder().encodeToString(KEY);
    private EventTestRequests() { }
    static String sign(byte[] key, String eventId, String time, byte[] body) {
        try {
            var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update((eventId + "." + time + ".").getBytes(StandardCharsets.UTF_8));
            return "v1," + Base64.getEncoder().encodeToString(mac.doFinal(body));
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
    static MockHttpServletRequest signed(String body, String eventId, Instant now) {
        return signed(body.getBytes(StandardCharsets.UTF_8), eventId, now);
    }
    static MockHttpServletRequest signed(byte[] body, String eventId, Instant now) {
        var request = new MockHttpServletRequest("POST", EventIngressVerifier.PATH);
        String time = Long.toString(now.getEpochSecond());
        request.setContentType("application/json"); request.setContent(body); request.addHeader("webhook-tenant", "demo");
        request.addHeader("webhook-source", "erp"); request.addHeader("webhook-id", eventId); request.addHeader("webhook-timestamp", time);
        request.addHeader("webhook-signature", sign(KEY, eventId, time, body)); return request;
    }
    /** 原始签名 HTTP 供收件和 OIDC 边界测试共用。 */
    public static MockHttpServletRequestBuilder request(String body, String eventId, Instant now) {
        var source = signed(body, eventId, now); var result = post(EventIngressVerifier.PATH).content(body).contentType("application/json");
        Collections.list(source.getHeaderNames()).forEach(name -> result.header(name, Collections.list(source.getHeaders(name)).toArray()));
        return result;
    }
}
