package io.agentflow.integration;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * 使用 Standard Webhooks 对称签名；不跟随重定向、不保存远端正文、限制整次请求等待。
 * @author owlzhangfq@gmail.com
 */
@Component
public class HttpWebhookTransport implements WebhookTransport {
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(2)).build();

    @Override
    public DeliveryProgress.Outcome send(WebhookTargets.Destination target, String eventId, String body) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Webhook transport must run outside a transaction");
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        HttpRequest request = HttpRequest.newBuilder(target.uri()).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json; charset=utf-8").header("User-Agent", "AgentFlow-Webhook/1")
                .header("webhook-id", eventId).header("webhook-timestamp", timestamp)
                .header("webhook-signature", signature(target.key(), eventId, timestamp, body))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        var future = client.sendAsync(request, HttpResponse.BodyHandlers.discarding());
        try { return DeliveryProgress.Outcome.http(future.get(6, TimeUnit.SECONDS).statusCode()); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return DeliveryProgress.Outcome.failed("INTERRUPTED", true);
        } catch (java.util.concurrent.TimeoutException timeout) { return DeliveryProgress.Outcome.failed("TIMEOUT", true); }
        catch (java.util.concurrent.ExecutionException failed) { return DeliveryProgress.Outcome.failed("CONNECTION_FAILED", true); }
        finally { if (!future.isDone()) future.cancel(true); }
    }

    /** 签名基于原始 UTF-8 请求体；接收方先验证时间窗口、签名，再按 eventId 去重。 */
    static String signature(byte[] key, String eventId, String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return "v1," + Base64.getEncoder().encodeToString(mac.doFinal((eventId + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException impossible) { throw new IllegalStateException("HMAC-SHA256 unavailable", impossible); }
    }
}
