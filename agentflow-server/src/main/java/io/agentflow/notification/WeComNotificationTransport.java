package io.agentflow.notification;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.common.JsonUtil;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static io.agentflow.notification.NotificationDeliveryConfiguration.WeComApp;
import static io.agentflow.notification.NotificationDeliveryProgress.*;

/** 企业微信自建应用的最小单人提醒；不保存令牌、远端正文或企业回执标识。
 * @author owlzhangfq@gmail.com
 */
@Component
public class WeComNotificationTransport {
    private static final int MAX_RESPONSE_BYTES = 16384;
    private static final int MAX_TOKEN_BYTES = 512;
    private static final int MAX_TOKEN_CACHE_SECONDS = 86400;
    private static final int MAX_RECEIPT_ID_CHARS = 1024;
    private static final int MAX_TOKEN_REFRESHES = 1;
    private static final int REQUEST_TIMEOUT_SECONDS = 4;
    private static final int TOKEN_INVALID = 40014;
    private static final int TOKEN_EXPIRED = 42001;
    private static final int SYSTEM_BUSY = -1;
    private static final int RATE_LIMITED = 45009;
    private static final int INVALID_RECIPIENT = 40003;
    private static final int NO_RECIPIENT = 81013;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(2)).build();
    private final Map<WeComApp, TokenSlot> tokens = new ConcurrentHashMap<>();
    private final JsonUtil json;
    private final Clock clock;

    /** 应用配置经过启动门禁，发送器只负责传输及供应商回执分类。 */
    @Autowired
    public WeComNotificationTransport(JsonUtil json) { this(json, Clock.systemUTC()); }
    WeComNotificationTransport(JsonUtil json, Clock clock) { this.json = json; this.clock = clock; }

    /** 仅在服务端明确拒绝过期令牌时刷新一次；回执未知不能在本次调用里重发。 */
    @Transactional(propagation = Propagation.NEVER)
    public Outcome send(NotificationDestinations.Destination destination) {
        var app = destination.wecomApp();
        String body = json.write(Map.of("touser", destination.address(), "msgtype", "text", "agentid", app.agentId(),
                "text", Map.of("content", NotificationMessageText.text(destination.publicUrl())),
                "safe", 1, "enable_id_trans", 0, "enable_duplicate_check", 0));
        try {
            for (int attempt = 0; attempt <= MAX_TOKEN_REFRESHES; attempt++) {
                Token token = token(app);
                var response = request(URI.create(app.baseUrl() + "/cgi-bin/message/send?access_token=" + encode(token.value)), body, true);
                int code = errorCode(response, true);
                if (code == TOKEN_INVALID || code == TOKEN_EXPIRED) {
                    invalidate(app, token);
                    if (attempt < MAX_TOKEN_REFRESHES) continue;
                    return Outcome.failed(FailureCode.IM_AUTH_FAILED);
                }
                if (code != 0) return rejected(code, false);
                return receipt(response, destination.address());
            }
            throw new IllegalStateException("WeCom token refresh exhausted");
        } catch (ChannelFailure failure) { return failure.outcome; }
    }

    private Token token(WeComApp app) {
        var slot = tokens.computeIfAbsent(app, ignored -> new TokenSlot());
        synchronized (slot) {
            Instant requestedAt = clock.instant();
            if (slot.token != null && requestedAt.isBefore(slot.token.expiresAt)) return slot.token;
            var response = request(URI.create(app.baseUrl() + "/cgi-bin/gettoken?corpid=" + encode(app.corpId())
                    + "&corpsecret=" + encode(app.secret())), null, false);
            int code = errorCode(response, false);
            if (code != 0) throw new ChannelFailure(rejected(code, true));
            var value = response.path("access_token"); var seconds = response.path("expires_in");
            if (!value.isTextual() || StringUtils.isBlank(value.textValue())
                    || value.textValue().getBytes(StandardCharsets.UTF_8).length > MAX_TOKEN_BYTES
                    || value.textValue().chars().anyMatch(Character::isISOControl)
                    || !seconds.isIntegralNumber() || !seconds.canConvertToInt() || seconds.intValue() < 1) throw unreadable(false);
            int lifetime = Math.min(seconds.intValue(), MAX_TOKEN_CACHE_SECONDS);
            long refreshEarly = Math.min(60, Math.max(1, lifetime / 10));
            slot.token = new Token(value.textValue(), requestedAt.plusSeconds(lifetime - refreshEarly));
            return slot.token;
        }
    }

    private void invalidate(WeComApp app, Token rejected) {
        var slot = tokens.get(app);
        synchronized (slot) { if (slot.token == rejected) slot.token = null; }
    }

    private JsonNode request(URI uri, String body, boolean sending) {
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .header("Accept", "application/json");
        if (body == null) builder.GET();
        else builder.header("Content-Type", "application/json; charset=utf-8").POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        var future = client.sendAsync(builder.build(), ignored -> new BoundedBody());
        try {
            var response = future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (response.statusCode() != 200) throw unreadable(sending);
            var parsed = json.readStrict(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(response.body())).toString(), JsonNode.class);
            if (parsed == null || !parsed.isObject()) throw unreadable(sending);
            return parsed;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw unreadable(sending);
        } catch (Exception invalidResponse) {
            // HTTP 状态、异常正文和请求 URI 可能包含凭据；只对外返回固定分类。
            throw unreadable(sending);
        } finally { if (!future.isDone()) future.cancel(true); }
    }

    private static int errorCode(JsonNode response, boolean sending) {
        var code = response.path("errcode");
        if (!code.isIntegralNumber() || !code.canConvertToInt()) throw unreadable(sending);
        return code.intValue();
    }

    private static Outcome rejected(int code, boolean tokenRequest) {
        if (code == SYSTEM_BUSY || code == RATE_LIMITED) return Outcome.retryable(FailureCode.IM_TEMPORARY_REJECTION);
        if (tokenRequest || code == TOKEN_INVALID || code == TOKEN_EXPIRED) return Outcome.failed(FailureCode.IM_AUTH_FAILED);
        if (code == INVALID_RECIPIENT || code == NO_RECIPIENT) return Outcome.failed(FailureCode.IM_RECIPIENT_REJECTED);
        return Outcome.failed(FailureCode.IM_PERMANENT_REJECTION);
    }

    private static Outcome receipt(JsonNode response, String recipient) {
        boolean rejectedRecipient = false;
        for (String field : List.of("invaliduser", "unlicenseduser", "invalidparty", "invalidtag")) {
            var value = response.get(field);
            if (value == null) continue;
            if (!value.isTextual()) throw unreadable(true);
            if (value.textValue().isEmpty()) continue;
            // 只请求一个明确用户。其他对象的拒绝名单说明回执无法对应本次请求。
            if (!(field.equals("invaliduser") || field.equals("unlicenseduser"))
                    || !value.textValue().equalsIgnoreCase(recipient)) throw unreadable(true);
            rejectedRecipient = true;
        }
        if (rejectedRecipient) return Outcome.failed(FailureCode.IM_RECIPIENT_REJECTED);
        var id = response.path("msgid");
        if (!id.isTextual() || StringUtils.isBlank(id.textValue()) || id.textValue().length() > MAX_RECEIPT_ID_CHARS
                || id.textValue().chars().anyMatch(Character::isISOControl)) throw unreadable(true);
        return Outcome.accepted();
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static ChannelFailure unreadable(boolean sending) {
        return new ChannelFailure(sending ? Outcome.unknown(FailureCode.IM_RESULT_UNKNOWN) : Outcome.retryable(FailureCode.IM_TOKEN_UNAVAILABLE));
    }
    /**
     * @author owlzhangfq@gmail.com
     */
    private static final class TokenSlot { private Token token; }
    /**
     * @author owlzhangfq@gmail.com
     */
    private record Token(String value, Instant expiresAt) {
        @Override public String toString() { return "WeComToken[redacted]"; }
    }
    /**
     * @author owlzhangfq@gmail.com
     */
    private static final class ChannelFailure extends RuntimeException {
        private final Outcome outcome;
        private ChannelFailure(Outcome outcome) { super("WeCom notification request failed", null, false, false); this.outcome = outcome; }
    }

    /** 分块接收时立即限制总量；整次等待同时覆盖慢响应体，不能只等待响应头。
     * @author owlzhangfq@gmail.com
     */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; subscription.request(1); }
        @Override public void onNext(List<ByteBuffer> values) {
            for (var value : values) {
                if (value.remaining() > MAX_RESPONSE_BYTES - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(new IllegalStateException("WeCom response exceeds limit")); return;
                }
                var part = new byte[value.remaining()]; value.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
