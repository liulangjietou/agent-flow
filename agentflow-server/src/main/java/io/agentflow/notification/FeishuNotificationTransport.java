package io.agentflow.notification;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.common.JsonUtil;
import io.agentflow.observability.DiagnosticContext;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static io.agentflow.notification.NotificationDeliveryConfiguration.FeishuApp;
import static io.agentflow.notification.NotificationDeliveryProgress.FailureCode;
import static io.agentflow.notification.NotificationDeliveryProgress.Outcome;

/**
 * 飞书（Feishu/Lark）自建应用最小单人提醒传输器；不持久化访问令牌或业务正文，响应限长防内存放大。
 * @author owlzhangfq@gmail.com
 */
@Component
public class FeishuNotificationTransport {
    private static final int MAX_RESPONSE_BYTES = 16384;
    private static final int MAX_TOKEN_BYTES = 512;
    private static final int MAX_TOKEN_CACHE_SECONDS = 86400;
    private static final int MAX_TOKEN_REFRESHES = 1;
    private static final int REQUEST_TIMEOUT_SECONDS = 4;
    private static final Set<Integer> TOKEN_EXPIRED_CODES = Set.of(99991661, 99991663, 99991668);
    private static final Set<Integer> RETRYABLE_CODES = Set.of(99991400, 11232, 99991403);
    private static final Set<Integer> INVALID_RECIPIENT_CODES = Set.of(230001, 230013, 230027);

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final Map<FeishuApp, TokenSlot> tokens = new ConcurrentHashMap<>();
    private final JsonUtil json;
    private final Clock clock;

    /** 构造飞书通知发送器。 */
    @Autowired
    public FeishuNotificationTransport(JsonUtil json) { this(json, Clock.systemUTC()); }

    FeishuNotificationTransport(JsonUtil json, Clock clock) {
        this.json = json;
        this.clock = clock;
    }

    /** 发送最小文本提醒至飞书 open_id；遇到过期令牌仅刷新一次。 */
    @Transactional(propagation = Propagation.NEVER)
    public Outcome send(NotificationDestinations.Destination destination) {
        FeishuApp app = destination.feishuApp();
        String contentJson = json.write(Map.of("text", NotificationMessageText.text(destination.publicUrl())));
        String body = json.write(Map.of(
                "receive_id", destination.address(),
                "msg_type", "text",
                "content", contentJson));
        try {
            for (int attempt = 0; attempt <= MAX_TOKEN_REFRESHES; attempt++) {
                Token token = token(app);
                JsonNode response = request(
                        URI.create(app.baseUrl() + "/open-apis/im/v1/messages?receive_id_type=open_id"),
                        body,
                        token.value(),
                        true);
                int code = responseCode(response, true);
                if (TOKEN_EXPIRED_CODES.contains(code)) {
                    invalidate(app, token);
                    if (attempt < MAX_TOKEN_REFRESHES) continue;
                    return Outcome.failed(FailureCode.IM_AUTH_FAILED);
                }
                if (code != 0) return classify(code, false);
                String messageId = response.path("data").path("message_id").textValue();
                if (StringUtils.isBlank(messageId) || messageId.length() > 1024
                        || messageId.chars().anyMatch(Character::isISOControl)) {
                    return Outcome.unknown(FailureCode.IM_RESULT_UNKNOWN);
                }
                return Outcome.accepted();
            }
            throw new IllegalStateException("Feishu token refresh exhausted");
        } catch (ChannelFailure failure) {
            return failure.outcome;
        }
    }

    private Token token(FeishuApp app) {
        TokenSlot slot = tokens.computeIfAbsent(app, ignored -> new TokenSlot());
        synchronized (slot) {
            Instant requestedAt = clock.instant();
            if (slot.token != null && requestedAt.isBefore(slot.token.expiresAt())) return slot.token;
            String body = json.write(Map.of("app_id", app.appId(), "app_secret", app.appSecret()));
            JsonNode response = request(
                    URI.create(app.baseUrl() + "/open-apis/auth/v3/tenant_access_token/internal"),
                    body,
                    null,
                    false);
            int code = responseCode(response, false);
            if (code != 0) throw new ChannelFailure(classify(code, true));
            JsonNode tokenNode = response.path("tenant_access_token");
            JsonNode expireNode = response.path("expire");
            if (!tokenNode.isTextual() || StringUtils.isBlank(tokenNode.textValue())
                    || tokenNode.textValue().getBytes(StandardCharsets.UTF_8).length > MAX_TOKEN_BYTES
                    || tokenNode.textValue().chars().anyMatch(Character::isISOControl)
                    || !expireNode.isIntegralNumber() || !expireNode.canConvertToInt() || expireNode.intValue() < 1) {
                throw unreadable(false);
            }
            int lifetime = Math.min(expireNode.intValue(), MAX_TOKEN_CACHE_SECONDS);
            long refreshEarly = Math.min(60, Math.max(1, lifetime / 10));
            slot.token = new Token(tokenNode.textValue(), requestedAt.plusSeconds(lifetime - refreshEarly));
            return slot.token;
        }
    }

    private void invalidate(FeishuApp app, Token rejected) {
        TokenSlot slot = tokens.get(app);
        if (slot == null) return;
        synchronized (slot) {
            if (slot.token == rejected) slot.token = null;
        }
    }

    private JsonNode request(URI uri, String body, String bearerToken, boolean sending) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .header(DiagnosticContext.HEADER, DiagnosticContext.capture().traceId())
                .header("Accept", "application/json")
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        var future = client.sendAsync(builder.build(), ignored -> new BoundedBody());
        try {
            var response = future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            int status = response.statusCode();
            if (status == 429 || status >= 500) {
                throw new ChannelFailure(Outcome.retryable(FailureCode.IM_TEMPORARY_REJECTION));
            }
            if (status != 200 && status != 400 && status != 401) {
                throw unreadable(sending);
            }
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(response.body())).toString();
            JsonNode parsed = json.readStrict(text, JsonNode.class);
            if (parsed == null || !parsed.isObject()) throw unreadable(sending);
            return parsed;
        } catch (ChannelFailure known) {
            throw known;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unreadable(sending);
        } catch (Exception invalidResponse) {
            throw unreadable(sending);
        } finally {
            if (!future.isDone()) future.cancel(true);
        }
    }

    private static int responseCode(JsonNode response, boolean sending) {
        JsonNode code = response.path("code");
        if (!code.isIntegralNumber() || !code.canConvertToInt()) throw unreadable(sending);
        return code.intValue();
    }

    private static Outcome classify(int code, boolean tokenRequest) {
        if (RETRYABLE_CODES.contains(code)) return Outcome.retryable(FailureCode.IM_TEMPORARY_REJECTION);
        if (tokenRequest || TOKEN_EXPIRED_CODES.contains(code)) return Outcome.failed(FailureCode.IM_AUTH_FAILED);
        if (INVALID_RECIPIENT_CODES.contains(code)) return Outcome.failed(FailureCode.IM_RECIPIENT_REJECTED);
        return Outcome.failed(FailureCode.IM_PERMANENT_REJECTION);
    }

    private static ChannelFailure unreadable(boolean sending) {
        return new ChannelFailure(sending
                ? Outcome.unknown(FailureCode.IM_RESULT_UNKNOWN)
                : Outcome.retryable(FailureCode.IM_TOKEN_UNAVAILABLE));
    }

    /** @author owlzhangfq@gmail.com */
    private static final class TokenSlot { private Token token; }

    /** @author owlzhangfq@gmail.com */
    private record Token(String value, Instant expiresAt) {
        @Override public String toString() { return "FeishuToken[redacted]"; }
    }

    /** @author owlzhangfq@gmail.com */
    private static final class ChannelFailure extends RuntimeException {
        private final Outcome outcome;
        private ChannelFailure(Outcome outcome) { super("Feishu notification request failed", null, false, false); this.outcome = outcome; }
    }

    /** @author owlzhangfq@gmail.com */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; subscription.request(1); }
        @Override public void onNext(List<ByteBuffer> values) {
            for (var value : values) {
                if (value.remaining() > MAX_RESPONSE_BYTES - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IllegalStateException("Feishu response exceeds limit"));
                    return;
                }
                byte[] part = new byte[value.remaining()];
                value.get(part);
                bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
