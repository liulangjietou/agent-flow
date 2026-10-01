package io.agentflow.notification;

import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.organization.OrganizationPerson;
import io.agentflow.organization.OrganizationRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static io.agentflow.notification.NotificationDeliveryProgress.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 实际数据库并发、同意撤销、绑定变化及事务外 SMTP 的完整后台边界。 @author owlzhangfq@gmail.com */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "agentflow.notifications.delivery-worker-enabled=false",
        "agentflow.sla.reminders-enabled=false", "agentflow.timers.enabled=false"})
class NotificationDeliveryIntegrationTest {
    private static final Actor ALICE = new Actor("demo", "alice", Set.of("EMPLOYEE"));
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcNotificationDeliveryStore store;
    @Autowired NotificationDeliveryService deliveries;
    @Autowired NotificationPreferencesService preferences;
    @Autowired InboxRepository inbox;
    @Autowired NotificationDeliveryWorker worker;
    @Autowired PlatformTransactionManager transactions;
    @Autowired OrganizationRepository organization;
    @SpyBean NotificationDestinations destinations;
    @SpyBean SmtpNotificationTransport smtp;
    @SpyBean WeComNotificationTransport wecom;

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_NOTIFICATION_DELIVERY_URL", "jdbc:h2:mem:notification-delivery;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_NOTIFICATION_DELIVERY_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_NOTIFICATION_DELIVERY_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_NOTIFICATION_DELIVERY_PASSWORD", ""));
    }

    @BeforeEach void isolate() {
        for (var table : java.util.List.of("notification_delivery_event", "notification_dispatch", "notification_inbox", "notification_preference_change", "notification_preferences"))
            jdbc.update("DELETE FROM " + table);
    }

    @Test void realSmtpRunsOutsideTransactionAndAcceptedIsNotSentAgain() throws Exception {
        try (var server = new LocalSmtpServer(LocalSmtpServer.Mode.ACCEPT)) {
            bind(SmtpNotificationTransportTest.target(server.port(), NotificationDeliveryConfiguration.Security.DEMO_PLAIN, false));
            doAnswer(call -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return call.callRealMethod(); })
                    .when(smtpTarget()).send(any(), any());
            var value = enqueue(ALICE); worker.runOnce(); worker.runOnce();
            assertThat(get(value).progress().status()).isEqualTo(Status.ACCEPTED);
            assertThat(get(value).progress().attempts()).isEqualTo(1);
            assertThat(server.messages).hasSize(1);
            assertThat(events(value)).isEqualTo(java.util.List.of("PENDING", "IN_FLIGHT", "ACCEPTED"));
            assertThat(jdbc.queryForObject("SELECT content FROM notification_inbox WHERE id=?", String.class, value.inboxId().toString())).contains("敏感正文");
            assertThat(server.messages.get(0)).doesNotContain("敏感正文", "BUSINESS-SECRET");
        }
    }

    @Test void concurrentClaimsHaveOneWinnerAndReadsAreSelfOnly() throws Exception {
        bind(target()); var value = enqueue(ALICE); var start = new CountDownLatch(1); var executor = Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<NotificationDeliveryService.Claim>>();
            for (int index = 0; index < 4; index++) futures.add(executor.submit(() -> { start.await(); return deliveries.claim(value.id(), Instant.now()); }));
            start.countDown(); var winners = new ArrayList<NotificationDeliveryService.Claim>();
            for (var future : futures) { var result = future.get(15, TimeUnit.SECONDS); if (result != null) winners.add(result); }
            assertThat(winners).hasSize(1); assertThat(get(value).progress().attempts()).isEqualTo(1);
            assertThat(deliveries.finish(winners.get(0).delivery(), Outcome.accepted(), Instant.now())).isTrue();
            assertThat(deliveries.finish(winners.get(0).delivery(), Outcome.accepted(), Instant.now())).isFalse();
        } finally { executor.shutdownNow(); }
        for (var actor : java.util.List.of(new Actor("demo", "admin", Set.of("ADMIN")), new Actor("other", "alice", Set.of("ADMIN")))) {
            assertThat(store.get(actor, value.id())).isEmpty();
            assertThatThrownBy(() -> deliveries.retry(actor, value.id(), get(value).progress().version(), true, "拒绝越权", Instant.now()))
                    .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("NOTIFICATION_DELIVERY_NOT_FOUND"));
        }
    }

    @Test void realImRunsOutsideTransactionAndUsesIndependentChannelConsent() throws Exception {
        try (var server = new LocalWeComServer()) {
            var target = WeComNotificationTransportTest.target(server); bind(target);
            WeComNotificationTransport actual = AopTestUtils.getUltimateTargetObject(wecom);
            doAnswer(call -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return call.callRealMethod(); }).when(actual).send(any());
            preferences.revise(ALICE, 0, true, true);
            var message = message(ALICE); inbox.append(UUID.randomUUID().toString(), message);
            var im = imByInbox(message.id()); preferences.revise(ALICE, 1, false, true);
            worker.runOnce(); worker.runOnce();
            assertThat(get(im).progress().status()).isEqualTo(Status.ACCEPTED);
            assertThat(server.messages).hasSize(1);
            assertThat(server.messages.get(0).body()).doesNotContain("BUSINESS-SECRET", "敏感", message.id().toString());
            assertThat(events(im)).containsExactly("PENDING", "IN_FLIGHT", "ACCEPTED");
            assertThat(byInbox(ALICE, message.id()).progress().status()).isEqualTo(Status.SUPPRESSED);
            assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> wecom.send(target)))
                    .isInstanceOf(IllegalTransactionStateException.class);
        }
    }

    @Test void unknownImWaitsForExplicitOriginalRetryAndRevokedConsentStopsFutureSends() throws Exception {
        try (var server = new LocalWeComServer()) {
            bind(WeComNotificationTransportTest.target(server)); preferences.revise(ALICE, 0, false, true);
            var message = message(ALICE); inbox.append(UUID.randomUUID().toString(), message); var value = imByInbox(message.id());
            server.messageReplies.add(LocalWeComServer.Reply.json("{}"));
            worker.runOnce(); worker.runOnce(); value = get(value);
            assertThat(value.progress().status()).isEqualTo(Status.UNKNOWN);
            assertThat(value.progress().errorCode()).isEqualTo(FailureCode.IM_RESULT_UNKNOWN); assertThat(server.messages).hasSize(1);
            var unknown = value;
            assertThatThrownBy(() -> deliveries.retry(ALICE, unknown.id(), unknown.progress().version(), false, "未确认重复", Instant.now()))
                    .isInstanceOf(DomainException.class);
            deliveries.retry(ALICE, value.id(), value.progress().version(), true, "接受重复后恢复", Instant.now()); worker.runOnce();
            assertThat(get(value).progress().status()).isEqualTo(Status.ACCEPTED); assertThat(server.messages).hasSize(2);
            var next = message(ALICE); inbox.append(UUID.randomUUID().toString(), next);
            preferences.revise(ALICE, 1, false, false); worker.runOnce();
            assertThat(imByInbox(next.id()).progress().status()).isEqualTo(Status.SUPPRESSED); assertThat(server.messages).hasSize(2);
        }
    }

    @Test void lateImBindingCannotBackfillOrRedirectOriginalMessages() throws Exception {
        try (var server = new LocalWeComServer()) {
            preferences.revise(ALICE, 0, false, true);
            var unbound = message(ALICE); inbox.append(UUID.randomUUID().toString(), unbound);
            bind(WeComNotificationTransportTest.target(server)); worker.runOnce();
            assertThat(imByInbox(unbound.id()).progress().errorCode()).isEqualTo(FailureCode.BINDING_NOT_CAPTURED);
            var original = message(ALICE); inbox.append(UUID.randomUUID().toString(), original);
            bind(WeComNotificationConfigurationTest.destination(java.util.Map.of("wecom-apps.app.base-url", server.baseUrl(),
                    "allow-insecure-in-demo", "true", "bindings.user-im.address", "User02"), true));
            worker.runOnce();
            assertThat(imByInbox(original.id()).progress().errorCode()).isEqualTo(FailureCode.BINDING_CHANGED);
            assertThat(imByInbox(original.id()).progress().attempts()).isZero();
            assertThat(server.tokenRequests).isEmpty(); assertThat(server.messages).isEmpty();
        }
    }

    @Test void closingAfterStartKeepsRealReceiptButPreventsFurtherAttemptsAndOldGeneration() {
        bind(target()); var value = enqueue(ALICE); var claim = deliveries.claim(value.id(), Instant.now());
        preferences.revise(ALICE, 1, false, false);
        assertThat(get(value).progress().status()).isEqualTo(Status.IN_FLIGHT);
        deliveries.finish(claim.delivery(), Outcome.retryable(FailureCode.SMTP_TEMPORARY_REJECTION), Instant.now());
        assertThat(deliveries.claim(value.id(), Instant.now().plusSeconds(40))).isNull();
        assertThat(get(value).progress().status()).isEqualTo(Status.SUPPRESSED);
        preferences.revise(ALICE, 2, true, false);
        assertThat(deliveries.claim(value.id(), Instant.now().plusSeconds(50))).isNull();
        var next = enqueue(ALICE); assertThat(next.consentGeneration()).isEqualTo(3);
        var nextClaim = deliveries.claim(next.id(), Instant.now());
        preferences.revise(ALICE, 3, false, false);
        deliveries.finish(nextClaim.delivery(), Outcome.accepted(), Instant.now());
        assertThat(get(next).progress().status()).isEqualTo(Status.ACCEPTED);
    }

    @Test void lateQueueAppendWithStaleConsentIsSuppressedBeforeSending() {
        bind(target()); preferences.revise(ALICE, 0, true, false); preferences.revise(ALICE, 1, false, false);
        var message = message(ALICE); inbox.append(UUID.randomUUID().toString(), message);
        new TransactionTemplate(transactions).executeWithoutResult(status -> store.append(message, NotificationChannel.EMAIL, 1, target()));
        var value = byInbox(ALICE, message.id());
        assertThat(deliveries.claim(value.id(), Instant.now())).isNull();
        assertThat(get(value).progress().errorCode()).isEqualTo(FailureCode.CONSENT_REVOKED);
        assertThat(get(value).progress().attempts()).isZero();
    }

    @Test void expiredClaimNeverResendsAndExplicitRetryRejectsLateOriginalReceipt() {
        bind(target()); var value = enqueue(ALICE); var claim = deliveries.claim(value.id(), Instant.now());
        assertThat(deliveries.claim(value.id(), claim.delivery().progress().leaseUntil())).isNull();
        var unknown = get(value); assertThat(unknown.progress().status()).isEqualTo(Status.UNKNOWN);
        assertThat(store.due(Instant.now().plusSeconds(5000))).doesNotContain(value.id());
        assertThat(deliveries.finish(claim.delivery(), Outcome.accepted(), Instant.now())).isFalse();
        assertThatThrownBy(() -> deliveries.retry(ALICE, value.id(), unknown.progress().version(), false, "核实后重试", Instant.now()))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("NOTIFICATION_DUPLICATE_ACK_REQUIRED"));
        var retried = deliveries.retry(ALICE, value.id(), unknown.progress().version(), true, "核实后接受可能重复", Instant.now());
        assertThat(retried.id()).isEqualTo(value.id()); assertThat(retried.destinationDigest()).isEqualTo(value.destinationDigest());
        assertThat(deliveries.finish(claim.delivery(), Outcome.accepted(), Instant.now())).isFalse();
        assertThat(jdbc.queryForObject("SELECT reason FROM notification_delivery_event WHERE delivery_id=? AND version=?", String.class, value.id().toString(), retried.progress().version()))
                .isEqualTo("核实后接受可能重复");
        var second = deliveries.claim(value.id(), Instant.now());
        assertThat(second.delivery().progress().attempts()).isEqualTo(2);
        deliveries.finish(second.delivery(), Outcome.accepted(), Instant.now());
    }

    @Test void absentOrChangedBindingCannotRedirectOldIntent() {
        var absent = enqueue(ALICE); bind(target());
        assertThat(deliveries.claim(absent.id(), Instant.now())).isNull();
        assertThat(get(absent).progress().errorCode()).isEqualTo(FailureCode.BINDING_NOT_CAPTURED);
        assertThatThrownBy(() -> deliveries.retry(ALICE, absent.id(), get(absent).progress().version(), false, "补绑无效", Instant.now()))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("NOTIFICATION_BINDING_UNAVAILABLE"));
        var original = enqueue(ALICE); var target = target();
        bind(new NotificationDestinations.Destination(target.id(), target.tenantId(), target.recipient(), target.channel(), "changed@example.invalid",
                target.server(), target.publicUrl(), true, "changed-digest", null));
        assertThat(deliveries.claim(original.id(), Instant.now())).isNull();
        assertThat(get(original).progress().errorCode()).isEqualTo(FailureCode.BINDING_CHANGED);
        assertThat(get(original).progress().attempts()).isZero();
    }

    @Test void inactiveOrganizationMemberAndWrongInboxOwnerAreSuppressedWithoutGrantingRoles() {
        String tenant = "delivery-" + UUID.randomUUID(); var actor = new Actor(tenant, "alice", Set.of()); var target = target();
        bind(new NotificationDestinations.Destination(target.id(), tenant, "alice", target.channel(), target.address(), target.server(), target.publicUrl(), true, target.digest(), null));
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            organization.initialize(tenant, "admin", Instant.now());
            organization.save(tenant, new OrganizationPerson(UUID.randomUUID(), "alice", "Alice", false, false, 1), 0);
        });
        var inactive = enqueue(actor); assertThat(deliveries.claim(inactive.id(), Instant.now())).isNull();
        assertThat(store.get(actor, inactive.id()).orElseThrow().progress().errorCode()).isEqualTo(FailureCode.RECIPIENT_INACTIVE);
        bind(target()); var otherOwner = enqueue(ALICE);
        jdbc.update("UPDATE notification_inbox SET recipient_id='bob' WHERE id=?", otherOwner.inboxId().toString());
        assertThat(deliveries.claim(otherOwner.id(), Instant.now())).isNull();
        assertThat(get(otherOwner).progress().errorCode()).isEqualTo(FailureCode.MESSAGE_UNAVAILABLE);
    }

    @Test void cashierDoesNotNeedAnApproverRoleToReceiveOwnNotification() {
        var cashier = new Actor("demo", "cashier", Set.of("CASHIER")); var target = target();
        bind(new NotificationDestinations.Destination("cashier-email", "demo", "cashier", target.channel(), "cashier@example.invalid", target.server(), target.publicUrl(), true, target.digest(), null));
        var value = enqueue(cashier); assertThat(deliveries.claim(value.id(), Instant.now())).isNotNull();
    }

    @Test void newMessageAndItsDeliveryHistoryRollBackTogetherAndTransportRejectsCallerTransaction() {
        bind(target()); preferences.revise(ALICE, 0, true, false);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            inbox.append("rollback", message(ALICE)); throw new IllegalStateException("synthetic rollback");
        })).isInstanceOf(IllegalStateException.class);
        for (String table : java.util.List.of("notification_inbox", "notification_dispatch", "notification_delivery_event"))
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)).isZero();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> smtp.send(target(), SmtpNotificationTransportTest.delivery(target()))))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> worker.runOnce()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private NotificationDestinations.Destination target() { return SmtpNotificationTransportTest.target(2525, NotificationDeliveryConfiguration.Security.DEMO_PLAIN, false); }
    private void bind(NotificationDestinations.Destination value) { doReturn(Optional.of(value)).when(destinations).find(value.tenantId(), value.recipient(), value.channel()); }
    private NotificationDelivery enqueue(Actor actor) {
        var pref = preferences.get(actor); if (!pref.emailEnabled()) preferences.revise(actor, pref.version(), true, false);
        var message = message(actor); inbox.append(UUID.randomUUID().toString(), message); return byInbox(actor, message.id());
    }
    private NotificationDelivery byInbox(Actor actor, UUID inboxId) {
        String id = jdbc.queryForObject("SELECT id FROM notification_dispatch WHERE inbox_id=? AND channel='EMAIL'", String.class, inboxId.toString());
        return store.get(actor, UUID.fromString(id)).orElseThrow();
    }
    private NotificationDelivery imByInbox(UUID inboxId) {
        String id = jdbc.queryForObject("SELECT id FROM notification_dispatch WHERE inbox_id=? AND channel='ENTERPRISE_IM'", String.class, inboxId.toString());
        return store.get(ALICE, UUID.fromString(id)).orElseThrow();
    }
    private NotificationDelivery get(NotificationDelivery value) { return store.get(ALICE, value.id()).orElseThrow(); }
    private java.util.List<String> events(NotificationDelivery value) { return jdbc.queryForList("SELECT status FROM notification_delivery_event WHERE delivery_id=? ORDER BY version", String.class, value.id().toString()); }
    private SmtpNotificationTransport smtpTarget() { return AopTestUtils.getUltimateTargetObject(smtp); }
    private InboxMessage message(Actor actor) { return new InboxMessage(UUID.randomUUID(), actor.tenantId(), actor.userId(), UUID.randomUUID(), "敏感标题", "BUSINESS-SECRET", InboxMessage.Kind.COMMENT_MENTIONED, "manager", null, null, 1, Instant.now(), null, "敏感正文只存站内"); }
}
