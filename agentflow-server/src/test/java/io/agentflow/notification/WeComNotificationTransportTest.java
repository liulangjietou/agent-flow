package io.agentflow.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.JsonUtil;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static io.agentflow.notification.NotificationDeliveryProgress.*;
import static org.assertj.core.api.Assertions.*;

/** 使用实际 HTTP 核对缓存、回执及不确定发送的停发边界。 @author owlzhangfq@gmail.com */
class WeComNotificationTransportTest {
    private final JsonUtil json = new JsonUtil(new ObjectMapper());

    @Test void sendsSingleRecipientMinimalContentAndCachesTokenPerApplication() throws Exception {
        try (var server = new LocalWeComServer()) {
            var target = target(server); var transport = new WeComNotificationTransport(json);
            assertThat(transport.send(target)).isEqualTo(Outcome.accepted());
            assertThat(transport.send(target)).isEqualTo(Outcome.accepted());
            assertThat(server.tokenRequests).hasSize(1); assertThat(server.messages).hasSize(2);
            var request = server.messages.get(0); assertThat(request.method()).isEqualTo("POST");
            var body = json.map(request.body());
            assertThat(body).containsOnlyKeys("touser", "msgtype", "agentid", "text", "safe", "enable_id_trans", "enable_duplicate_check");
            assertThat(body).containsEntry("touser", "User01").containsEntry("agentid", 100001).containsEntry("msgtype", "text");
            assertThat(body).containsEntry("enable_id_trans", 0).containsEntry("enable_duplicate_check", 0);
            assertThat(request.body()).doesNotContain("fixture-secret", "fixture-corp", "fixture-token");
            assertThat(body.get("text")).isEqualTo(Map.of("content", NotificationMessageText.text(target.publicUrl())));
            var otherApp = target(server, Map.of("wecom-apps.app.agent-id", "100002", "wecom-apps.app.secret", "second-secret"));
            assertThat(transport.send(otherApp)).isEqualTo(Outcome.accepted()); assertThat(server.tokenRequests).hasSize(2);
            assertThat(server.tokenRequests.get(1).query()).contains("corpsecret=second-secret");
            var rotated = target(server, Map.of("wecom-apps.app.secret", "rotated+&=secret"));
            assertThat(rotated.digest()).isEqualTo(target.digest());
            assertThat(transport.send(rotated)).isEqualTo(Outcome.accepted()); assertThat(server.tokenRequests).hasSize(3);
            assertThat(server.tokenRequests.get(2).query()).contains("corpsecret=rotated%2B%26%3Dsecret");
        }
    }

    @Test void concurrentMessagesShareOneTokenAndRefreshBeforeExpiry() throws Exception {
        try (var server = new LocalWeComServer()) {
            var clock = new MutableClock(); var transport = new WeComNotificationTransport(json, clock); var target = target(server);
            var threads = Executors.newFixedThreadPool(4);
            try {
                var tasks = java.util.stream.IntStream.range(0, 4).mapToObj(i -> threads.submit(() -> transport.send(target))).toList();
                for (var task : tasks) assertThat(task.get(10, TimeUnit.SECONDS)).isEqualTo(Outcome.accepted());
            } finally { threads.shutdownNow(); }
            assertThat(server.tokenRequests).hasSize(1); assertThat(server.messages).hasSize(4);
            clock.now = clock.now.plusSeconds(7140); transport.send(target); assertThat(server.tokenRequests).hasSize(2);
        }
    }

    @Test void explicitExpiredTokenRefreshesOnceButRepeatedInvalidTokenStops() throws Exception {
        for (boolean rejectAgain : java.util.List.of(false, true)) try (var server = new LocalWeComServer()) {
            server.messageReplies.add(LocalWeComServer.Reply.json("{\"errcode\":42001}"));
            if (rejectAgain) server.messageReplies.add(LocalWeComServer.Reply.json("{\"errcode\":40014}"));
            var result = new WeComNotificationTransport(json).send(target(server));
            assertThat(result).isEqualTo(rejectAgain ? Outcome.failed(FailureCode.IM_AUTH_FAILED) : Outcome.accepted());
            assertThat(server.tokenRequests).hasSize(2); assertThat(server.messages).hasSize(2);
        }
    }

    @Test void tokenFailuresNeverSubmitMessagesOrExposeRemoteError() throws Exception {
        for (String body : java.util.List.of("{}", "{\"errcode\":0,\"access_token\":\"token\",\"expires_in\":\"7200\"}",
                "{\"errcode\":0,\"access_token\":\"" + "x".repeat(513) + "\",\"expires_in\":7200}",
                "{\"errcode\":40001,\"errmsg\":\"fixture-secret\"}")) try (var server = new LocalWeComServer()) {
            server.tokenReplies.add(LocalWeComServer.Reply.json(body));
            var outcome = new WeComNotificationTransport(json).send(target(server));
            assertThat(outcome).isEqualTo(body.contains("40001") ? Outcome.failed(FailureCode.IM_AUTH_FAILED) : Outcome.retryable(FailureCode.IM_TOKEN_UNAVAILABLE));
            assertThat(outcome.toString()).doesNotContain("fixture-secret"); assertThat(server.messages).isEmpty();
        }
    }

    @Test void explicitRejectionsAreClassifiedWithoutImmediateResend() throws Exception {
        var expected = Map.of(-1, Outcome.retryable(FailureCode.IM_TEMPORARY_REJECTION), 45009, Outcome.retryable(FailureCode.IM_TEMPORARY_REJECTION),
                40003, Outcome.failed(FailureCode.IM_RECIPIENT_REJECTED), 81013, Outcome.failed(FailureCode.IM_RECIPIENT_REJECTED),
                40058, Outcome.failed(FailureCode.IM_PERMANENT_REJECTION));
        for (var entry : expected.entrySet()) try (var server = new LocalWeComServer()) {
            server.messageReplies.add(LocalWeComServer.Reply.json("{\"errcode\":" + entry.getKey() + "}"));
            assertThat(new WeComNotificationTransport(json).send(target(server))).isEqualTo(entry.getValue());
            assertThat(server.messages).hasSize(1);
        }
    }

    @Test void invalidOrUnlicensedSingleUserCannotCountAsAccepted() throws Exception {
        for (String field : java.util.List.of("invaliduser", "unlicenseduser")) try (var server = new LocalWeComServer()) {
            server.messageReplies.add(LocalWeComServer.Reply.json("{\"errcode\":0,\"msgid\":\"x\",\"" + field + "\":\"user01\"}"));
            assertThat(new WeComNotificationTransport(json).send(target(server))).isEqualTo(Outcome.failed(FailureCode.IM_RECIPIENT_REJECTED));
        }
    }

    @Test void malformedOversizeOrMismatchedReceiptsAreUnknownWithoutResend() throws Exception {
        for (String body : java.util.List.of("{}", "{\"errcode\":\"0\",\"msgid\":\"x\"}", "{\"errcode\":0}",
                "{\"errcode\":0,\"msgid\":\"x\",\"invaliduser\":null}", "{\"errcode\":0,\"msgid\":\"x\",\"invaliduser\":\"different-user\"}",
                "{\"errcode\":0,\"msgid\":\"x\",\"padding\":\"" + "x".repeat(17000) + "\"}")) try (var server = new LocalWeComServer()) {
            server.messageReplies.add(LocalWeComServer.Reply.json(body));
            assertThat(new WeComNotificationTransport(json).send(target(server))).isEqualTo(Outcome.unknown(FailureCode.IM_RESULT_UNKNOWN));
            assertThat(server.messages).hasSize(1); assertThat(server.tokenRequests).hasSize(1);
        }
    }

    @Test void redirectsAreNeverFollowedAndSlowBodyIsBounded() throws Exception {
        try (var server = new LocalWeComServer()) {
            var transport = new WeComNotificationTransport(json); var target = target(server);
            server.tokenReplies.add(new LocalWeComServer.Reply(302, "{}", 0));
            assertThat(transport.send(target)).isEqualTo(Outcome.retryable(FailureCode.IM_TOKEN_UNAVAILABLE));
            assertThat(server.messages).isEmpty(); assertThat(server.unexpected).isEmpty();
            server.messageReplies.add(new LocalWeComServer.Reply(302, "{}", 0));
            assertThat(transport.send(target)).isEqualTo(Outcome.unknown(FailureCode.IM_RESULT_UNKNOWN));
            assertThat(server.unexpected).isEmpty();
            server.messageReplies.add(new LocalWeComServer.Reply(200, LocalWeComServer.ACCEPT, 10000));
            var start = Instant.now();
            assertThat(transport.send(target)).isEqualTo(Outcome.unknown(FailureCode.IM_RESULT_UNKNOWN));
            assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(7));
            assertThat(server.messages).hasSize(2);
        }
    }

    @Test void trailingDocumentsAndDuplicateResultKeysCannotCreateAcceptedFact() throws Exception {
        for (String body : java.util.List.of("{\"errcode\":0,\"msgid\":\"x\"} {\"errcode\":81013}",
                "{\"errcode\":81013,\"errcode\":0,\"msgid\":\"x\"}")) try (var server = new LocalWeComServer()) {
            server.messageReplies.add(LocalWeComServer.Reply.json(body));
            assertThat(new WeComNotificationTransport(json).send(target(server))).isEqualTo(Outcome.unknown(FailureCode.IM_RESULT_UNKNOWN));
            assertThat(server.messages).hasSize(1);
        }
    }

    static NotificationDestinations.Destination target(LocalWeComServer server) { return target(server, Map.of()); }
    private static NotificationDestinations.Destination target(LocalWeComServer server, Map<String, String> changes) {
        var values = new java.util.LinkedHashMap<>(changes); values.put("wecom-apps.app.base-url", server.baseUrl()); values.put("allow-insecure-in-demo", "true");
        return WeComNotificationConfigurationTest.destination(values, true);
    }
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
