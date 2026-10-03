package io.agentflow.finance.callback;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 合成银行的签名请求；测试密钥不用于演示部署或真实银行。
 * @author owlzhangfq@gmail.com
 */
public final class PaymentCallbackTestRequests {
    public static final byte[] KEY = "synthetic-callback-key-0123456789!".getBytes(StandardCharsets.UTF_8);
    public static final String SECRET = "whsec_" + Base64.getEncoder().encodeToString(KEY);
    private PaymentCallbackTestRequests() { }
    /** 模拟发送方按照标准原始字节签名，与平台验签实现隔离。 */
    public static String sign(byte[] key, String event, String timestamp, byte[] body) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256"); hmac.init(new SecretKeySpec(key, "HmacSHA256"));
            hmac.update((event + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return "v1," + Base64.getEncoder().encodeToString(hmac.doFinal(body));
        } catch (java.security.GeneralSecurityException failure) { throw new IllegalStateException(failure); }
    }
    /** 每次重投更新时间戳，正文和事件号由测试保留。 */
    public static MockHttpServletRequestBuilder request(String event, String body) {
        String timestamp = Long.toString(Instant.now().getEpochSecond()); byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return post(PaymentCallbackVerifier.PATH).contentType("application/json").content(bytes)
                .header("webhook-tenant", "demo").header("webhook-id", event).header("webhook-timestamp", timestamp)
                .header("webhook-signature", sign(KEY, event, timestamp, bytes));
    }
}
