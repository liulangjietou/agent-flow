package io.agentflow.notification;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import static io.agentflow.notification.NotificationDeliveryProgress.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 公开本人范围、双重分页、原键恢复和恢复事务的数据库验收。 @author owlzhangfq@gmail.com */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "agentflow.notifications.delivery-worker-enabled=false", "agentflow.sla.reminders-enabled=false", "agentflow.timers.enabled=false"})
@AutoConfigureMockMvc
class NotificationDeliveryApiIntegrationTest {
    private static final String PATH = "/api/v1/notifications/deliveries";
    private static final Actor ALICE = new Actor("demo", "alice", Set.of("EMPLOYEE"));
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;
    @Autowired JdbcTemplate jdbc;
    @Autowired NotificationPreferencesService preferences;
    @Autowired InboxRepository inbox;
    @Autowired NotificationDeliveryService deliveries;
    @Autowired NotificationDeliveryWorker worker;
    @SpyBean JdbcNotificationDeliveryStore store;
    @SpyBean NotificationDestinations destinations;

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_NOTIFICATION_DELIVERY_API_URL", "jdbc:h2:mem:notification-delivery-api;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_NOTIFICATION_DELIVERY_API_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_NOTIFICATION_DELIVERY_API_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_NOTIFICATION_DELIVERY_API_PASSWORD", ""));
    }

    @BeforeEach void isolate() {
        for (var table : java.util.List.of("notification_delivery_event", "notification_dispatch", "notification_inbox", "notification_preference_change", "notification_preferences")) jdbc.update("DELETE FROM " + table);
        doReturn(Optional.of(target())).when(destinations).find("demo", "alice", NotificationChannel.EMAIL);
    }

    @Test void selfOnlyReadsAreNoStoreAndContainNoBindingOrBusinessPayload() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        assertThat(read(PATH, "alice").path("items")).isEmpty();
        var value = failed(); long events = countEvents(value.id());
        var detail = read(PATH + "/" + value.id(), "alice");
        assertThat(detail.path("delivery").path("status").asText()).isEqualTo("FAILED");
        assertThat(detail.path("retry").path("allowed").asBoolean()).isTrue();
        assertThat(detail.toString()).doesNotContain("example.invalid", "敏感标题", "BUSINESS-SECRET", "destinationDigest", "bindingId", "leaseToken");
        assertThat(countEvents(value.id())).isEqualTo(events);
        for (String user : java.util.List.of("admin", "bob")) {
            assertThat(read(PATH, user).path("items")).isEmpty();
            mvc.perform(get(PATH + "/" + value.id()).header("Authorization", token(user))).andExpect(status().isNotFound());
            mvc.perform(get(PATH + "/" + value.id() + "/history").header("Authorization", token(user))).andExpect(status().isNotFound());
            retry(value, user, input(value, false), UUID.randomUUID().toString(), 404);
        }
        mvc.perform(get(PATH + "/" + UUID.randomUUID()).header("Authorization", token("alice"))).andExpect(status().isNotFound());
        for (String query : java.util.List.of("recipient=bob", "tenant=other", "limit=101", "limit=0", "status=DELIVERED", "channel=SMS", "cursor=", "cursor=not-a-cursor"))
            mvc.perform(get(PATH + "?" + query).header("Authorization", token("alice"))).andExpect(status().isBadRequest());
        mvc.perform(get(PATH).queryParam("limit", "1", "2").header("Authorization", token("alice"))).andExpect(status().isBadRequest());
        mvc.perform(get(PATH + "/" + value.id()).queryParam("channel", "EMAIL").header("Authorization", token("alice"))).andExpect(status().isBadRequest());
    }

    @Test void listCursorIsBoundToIdentityAndFiltersAndDoesNotLoseSameTimeRows() throws Exception {
        var expected = new HashSet<String>(); for (int index = 0; index < 5; index++) expected.add(failed().id().toString());
        jdbc.update("UPDATE notification_dispatch SET created_at=?", java.sql.Timestamp.from(Instant.parse("2026-10-01T12:00:00Z")));
        var actual = new ArrayList<String>(); String cursor = null; int pages = 0;
        do {
            var request = get(PATH).queryParam("channel", "EMAIL").queryParam("status", "FAILED").queryParam("limit", "2").header("Authorization", token("alice"));
            if (cursor != null) request.queryParam("cursor", cursor);
            var page = body(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            page.path("items").forEach(item -> actual.add(item.path("id").asText()));
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText(); pages++;
            if (cursor != null) {
                mvc.perform(get(PATH).queryParam("channel", "EMAIL").queryParam("status", "FAILED").queryParam("cursor", cursor).header("Authorization", token("bob"))).andExpect(status().isBadRequest());
                mvc.perform(get(PATH).queryParam("channel", "EMAIL").queryParam("cursor", cursor).header("Authorization", token("alice"))).andExpect(status().isBadRequest());
            }
        } while (cursor != null && pages < 10);
        assertThat(pages).isEqualTo(3); assertThat(actual).hasSize(5).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test void fullHistoryPagesUseOriginalVersionsAndRejectAnotherDeliveryCursor() throws Exception {
        var value = failed();
        for (int index = 0; index < 8; index++) {
            value = deliveries.retry(ALICE, value.id(), value.progress().version(), false, "第 " + index + " 次人工核实", Instant.now());
            value = failClaim(value);
        }
        var detail = read(PATH + "/" + value.id(), "alice");
        long through = detail.path("delivery").path("version").asLong();
        var versions = new ArrayList<Long>(); detail.path("history").path("items").forEach(item -> versions.add(item.path("version").asLong()));
        String cursor = detail.path("history").path("nextCursor").asText(); assertThat(cursor).isNotBlank(); assertThat(versions).hasSize(20);
        var other = failed();
        mvc.perform(get(PATH + "/" + other.id() + "/history").queryParam("cursor", cursor).header("Authorization", token("alice"))).andExpect(status().isBadRequest());
        deliveries.retry(ALICE, value.id(), value.progress().version(), false, "首段之后的新操作", Instant.now());
        var tail = body(mvc.perform(get(PATH + "/" + value.id() + "/history").queryParam("cursor", cursor).header("Authorization", token("alice"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        tail.path("items").forEach(item -> versions.add(item.path("version").asLong()));
        assertThat(tail.path("nextCursor").isNull()).isTrue(); assertThat(versions).hasSize((int) through).doesNotHaveDuplicates();
        assertThat(versions).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(versions.get(0)).isEqualTo(through); assertThat(versions.get(versions.size() - 1)).isEqualTo(1L);
    }

    @Test void originalRetryReceiptReplaysAfterAcceptanceAndLaterPreferenceCloseWithoutSendingAgain() throws Exception {
        var value = failed(); String key = UUID.randomUUID().toString(); var input = input(value, false);
        var receipt = retry(value, "alice", input, key, 200);
        assertThat(receipt.path("version").asLong()).isEqualTo(value.progress().version() + 1);
        var claim = deliveries.claim(value.id(), Instant.now()); deliveries.finish(claim.delivery(), Outcome.accepted(), Instant.now());
        preferences.revise(ALICE, 1, false, false); long events = countEvents(value.id());
        var replay = mvc.perform(post(PATH + "/" + value.id() + "/retry").header("Authorization", token("alice")).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json.write(input))).andExpect(status().isOk()).andExpect(header().string("Idempotency-Replayed", "true")).andReturn();
        assertThat(body(replay.getResponse().getContentAsString())).isEqualTo(receipt);
        assertThat(store.get(ALICE, value.id()).orElseThrow().progress().status()).isEqualTo(Status.ACCEPTED);
        assertThat(countEvents(value.id())).isEqualTo(events); assertThat(store.get(ALICE, value.id()).orElseThrow().progress().attempts()).isEqualTo(2);
        retry(value, "alice", Map.of("expectedVersion", value.progress().version(), "acknowledgePossibleDuplicate", false, "reason", "不同请求"), key, 409);
    }

    @Test void unknownRequiresAcknowledgementAndInputCannotOverrideIdentityOrDestination() throws Exception {
        var value = queued(); var claim = deliveries.claim(value.id(), Instant.now());
        deliveries.finish(claim.delivery(), Outcome.unknown(FailureCode.SMTP_RESULT_UNKNOWN), Instant.now()); value = store.get(ALICE, value.id()).orElseThrow();
        var detail = read(PATH + "/" + value.id(), "alice"); assertThat(detail.path("retry").path("requiresDuplicateAcknowledgement").asBoolean()).isTrue();
        retry(value, "alice", input(value, false), UUID.randomUUID().toString(), 400);
        for (String key : java.util.List.of("recipient", "tenantId", "emailAddress", "status")) {
            var invalid = new HashMap<String,Object>(input(value, true)); invalid.put(key, "override");
            retry(value, "alice", invalid, UUID.randomUUID().toString(), 400);
        }
        for (Object invalid : java.util.List.of(Map.of("expectedVersion", value.progress().version(), "reason", "缺少确认"),
                Map.of("expectedVersion", 0, "acknowledgePossibleDuplicate", true, "reason", "版本错误"),
                Map.of("expectedVersion", value.progress().version(), "acknowledgePossibleDuplicate", true, "reason", " "),
                Map.of("expectedVersion", value.progress().version(), "acknowledgePossibleDuplicate", true, "reason", "x".repeat(1001))))
            retry(value, "alice", invalid, UUID.randomUUID().toString(), 400);
        assertThat(retry(value, "alice", input(value, true), UUID.randomUUID().toString(), 200).path("status").asText()).isEqualTo("PENDING");
        retry(value, "alice", input(value, true), UUID.randomUUID().toString(), 409);
    }

    @Test void currentConsentAndOriginalBindingControlRetryEvenAfterReadingAnAllowedDetail() throws Exception {
        var value = failed(); assertThat(read(PATH + "/" + value.id(), "alice").path("retry").path("allowed").asBoolean()).isTrue();
        preferences.revise(ALICE, 1, false, false);
        var eligibility = read(PATH + "/" + value.id(), "alice").path("retry");
        assertThat(eligibility.path("allowed").asBoolean()).isFalse(); assertThat(eligibility.path("blockedCode").asText()).isEqualTo("CONSENT_REVOKED");
        retry(value, "alice", input(value, false), UUID.randomUUID().toString(), 409);
        var current = failed(); doReturn(Optional.empty()).when(destinations).find("demo", "alice", NotificationChannel.EMAIL);
        assertThat(read(PATH + "/" + current.id(), "alice").path("retry").path("blockedCode").asText()).isEqualTo("BINDING_UNAVAILABLE");
        retry(current, "alice", input(current, false), UUID.randomUUID().toString(), 409);
    }

    @Test void enterpriseImUnknownIsSelfOnlyAndOriginalHttpRecoveryDoesNotSendTwice() throws Exception {
        try (var server = new LocalWeComServer()) {
            var target = WeComNotificationTransportTest.target(server);
            doReturn(Optional.of(target)).when(destinations).find("demo", "alice", NotificationChannel.ENTERPRISE_IM);
            preferences.revise(ALICE, 0, false, true);
            var message = new InboxMessage(UUID.randomUUID(), "demo", "alice", UUID.randomUUID(), "敏感标题", "BUSINESS-SECRET", InboxMessage.Kind.COMMENT_MENTIONED,
                    "manager", null, null, 1, Instant.now(), null, "正文");
            inbox.append(UUID.randomUUID().toString(), message);
            var id = UUID.fromString(jdbc.queryForObject("SELECT id FROM notification_dispatch WHERE inbox_id=?", String.class, message.id().toString()));
            server.messageReplies.add(LocalWeComServer.Reply.json("{}")); worker.runOnce();
            var value = store.get(ALICE, id).orElseThrow();
            var detail = read(PATH + "/" + id, "alice");
            assertThat(detail.path("delivery").path("channel").asText()).isEqualTo("ENTERPRISE_IM");
            assertThat(detail.path("delivery").path("errorCode").asText()).isEqualTo("IM_RESULT_UNKNOWN");
            assertThat(detail.toString()).doesNotContain("User01", "fixture-secret", "fixture-token", "fixture-corp", "敏感标题");
            assertThat(read(PATH + "?channel=ENTERPRISE_IM&status=UNKNOWN", "alice").path("items")).hasSize(1);
            mvc.perform(get(PATH + "/" + id).header("Authorization", token("admin"))).andExpect(status().isNotFound());
            retry(value, "alice", input(value, false), UUID.randomUUID().toString(), 400);
            String key = UUID.randomUUID().toString(); var receipt = retry(value, "alice", input(value, true), key, 200);
            worker.runOnce(); assertThat(store.get(ALICE, id).orElseThrow().progress().status()).isEqualTo(Status.ACCEPTED);
            assertThat(retry(value, "alice", input(value, true), key, 200)).isEqualTo(receipt);
            worker.runOnce(); assertThat(server.messages).hasSize(2);
        }
    }

    @Test void historyFailureRollsBackRetryAndIdempotencySoOriginalKeyCanSafelyExecute() throws Exception {
        var value = failed(); String key = UUID.randomUUID().toString(); long events = countEvents(value.id());
        JdbcNotificationDeliveryStore spy = AopTestUtils.getUltimateTargetObject(store);
        doAnswer(call -> { call.callRealMethod(); throw new DomainException("DEPENDENCY_UNAVAILABLE", "Synthetic history failure"); })
                .when(spy).save(any(), any(), eq("alice"), anyString());
        retry(value, "alice", input(value, false), key, 503);
        assertThat(store.get(ALICE, value.id()).orElseThrow().progress().version()).isEqualTo(value.progress().version());
        assertThat(countEvents(value.id())).isEqualTo(events);
        doCallRealMethod().when(spy).save(any(), any(), eq("alice"), anyString());
        var first = retry(value, "alice", input(value, false), key, 200);
        assertThat(retry(value, "alice", input(value, false), key, 200)).isEqualTo(first);
        assertThat(countEvents(value.id())).isEqualTo(events + 1);
    }

    private NotificationDestinations.Destination target() { return SmtpNotificationTransportTest.target(2525, NotificationDeliveryConfiguration.Security.DEMO_PLAIN, false); }
    private NotificationDelivery queued() {
        var current = preferences.get(ALICE); if (!current.emailEnabled()) preferences.revise(ALICE, current.version(), true, false);
        var message = new InboxMessage(UUID.randomUUID(), "demo", "alice", UUID.randomUUID(), "敏感标题", "BUSINESS-SECRET", InboxMessage.Kind.COMMENT_MENTIONED, "manager", null, null, 1, Instant.now(), null, "正文");
        inbox.append(UUID.randomUUID().toString(), message);
        var id = jdbc.queryForObject("SELECT id FROM notification_dispatch WHERE inbox_id=?", String.class, message.id().toString()); return store.get(ALICE, UUID.fromString(id)).orElseThrow();
    }
    private NotificationDelivery failed() { return failClaim(queued()); }
    private NotificationDelivery failClaim(NotificationDelivery value) { var claim = deliveries.claim(value.id(), Instant.now()); deliveries.finish(claim.delivery(), Outcome.failed(FailureCode.SMTP_PERMANENT_REJECTION), Instant.now()); return store.get(ALICE, value.id()).orElseThrow(); }
    private long countEvents(UUID id) { return jdbc.queryForObject("SELECT COUNT(*) FROM notification_delivery_event WHERE delivery_id=?", Long.class, id.toString()); }
    private Map<String,Object> input(NotificationDelivery value, boolean duplicate) { return Map.of("expectedVersion", value.progress().version(), "acknowledgePossibleDuplicate", duplicate, "reason", "已核实原结果后重试"); }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private JsonNode body(String value) { return json.read(value, JsonNode.class); }
    private JsonNode read(String path, String user) throws Exception { return body(mvc.perform(get(path).header("Authorization", token(user))).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString()); }
    private JsonNode retry(NotificationDelivery value, String user, Object input, String key, int status) throws Exception {
        return body(mvc.perform(post(PATH + "/" + value.id() + "/retry").header("Authorization", token(user)).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json.write(input)))
                .andExpect(status().is(status)).andReturn().getResponse().getContentAsString());
    }
}
