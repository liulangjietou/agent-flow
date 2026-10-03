package io.agentflow.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.common.DomainException;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.*;
import io.agentflow.notification.NotificationTexts;
import io.agentflow.organization.InitiatorContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import static org.assertj.core.api.Assertions.*;

/**
 * 合成借款实际聚合、数据库和回环 ERP 共同验证持久副作用，不替代企业 ERP 验收。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.finance-gateway.enabled=true", "agentflow.vouchers.worker-enabled=false",
        "agentflow.vouchers.preparation-worker-enabled=false", "agentflow.vouchers.preparation-lease-seconds=15",
        "agentflow.vouchers.lease-seconds=15", "agentflow.budgets.worker-enabled=false", "agentflow.invoices.verification-worker-enabled=false",
        "agentflow.expenses.precheck-worker-enabled=false", "agentflow.advance-requests.precheck-worker-enabled=false", "agentflow.notifications.delivery-worker-enabled=false"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@Import(VoucherOperationIntegrationTest.ListenerConfiguration.class)
class VoucherOperationIntegrationTest {
    private final UUID entity = UUID.randomUUID();
    private static final ExecutorService HTTP_THREADS = Executors.newCachedThreadPool();
    private static final HttpServer SERVER = server();
    private static final String ENDPOINT = "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/finance";
    private static final AtomicReference<BiFunction<String, JsonNode, Object>> RESPONDER = new AtomicReference<>();
    private static final AtomicInteger WRITES = new AtomicInteger(), QUERIES = new AtomicInteger();
    private static final AtomicReference<String> LAST_KEY = new AtomicReference<>();
    private static JsonUtil wire;
    private final List<UUID> fixtures = new ArrayList<>();
    @Autowired JsonUtil json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ApplicationRepository applications;
    @Autowired AdvanceRequestRepository advances;
    @Autowired JdbcVoucherOperationRepository operations;
    @Autowired VoucherOperationService execution;
    @Autowired VoucherOperationWorker worker;
    @Autowired FinanceGatewayConfiguration configuration;
    @Autowired FailureListener listener;
    @Autowired ApprovedVoucherSources sources;
    @Autowired JdbcVoucherPreparationRepository preparations;
    @Autowired VoucherPreparationService preparationService;
    @Autowired VoucherPreparationWorker preparationWorker;
    @Autowired AccountMappingConfigurationService mappingConfiguration;
    @Autowired ExpenseConfigurationService expenseConfiguration;
    @Autowired JdbcExpenseConfigurationRepository configurationLocks;
    @Autowired io.agentflow.organization.OrganizationRepository organization;
    @Autowired io.agentflow.approval.repository.SubmissionRoundRepository submissionRounds;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired io.agentflow.auth.AuthService auth;
    @Autowired org.springframework.context.ApplicationEventPublisher events;
    @Autowired io.agentflow.notification.NotificationPreferencesService notificationPreferences;
    @Autowired io.agentflow.notification.NotificationDeliveryService notificationDeliveries;
    @Autowired io.agentflow.notification.JdbcNotificationDeliveryStore notificationStore;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry values) {
        values.add("agentflow.finance-gateway.tenants.demo.endpoint", () -> ENDPOINT);
        values.add("agentflow.finance-gateway.tenants.demo.allow-unauthenticated-loopback", () -> "true");
        values.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_VOUCHER_TEST_URL", "jdbc:h2:mem:voucher-operations;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"));
        values.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_VOUCHER_TEST_DRIVER", "org.h2.Driver"));
        values.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_VOUCHER_TEST_USER", "sa"));
        values.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_VOUCHER_TEST_PASSWORD", ""));
    }

    @BeforeEach void setup() {
        wire = json; WRITES.set(0); QUERIES.set(0); listener.reject.set(false); configuration.setEnabled(true);
        configuration.getTenants().get("demo").setEndpoint(ENDPOINT); configuration.getTenants().get("demo").setTimeoutSeconds(2);
        RESPONDER.set((path, request) -> posted(json.read(request.at("/data/command").toString(), VoucherCommand.class), 1));
    }
    @AfterEach void removeOnlyFixtureOperations() {
        for (var business : fixtures) {
            jdbc.update("DELETE FROM voucher_preparation_revision WHERE tenant_id='demo' AND preparation_id IN (SELECT id FROM voucher_preparation WHERE tenant_id='demo' AND business_id=?)", business.toString());
            jdbc.update("DELETE FROM voucher_preparation WHERE tenant_id='demo' AND business_id=?", business.toString());
            jdbc.update("DELETE FROM voucher_operation_revision WHERE tenant_id='demo' AND operation_id IN (SELECT id FROM voucher_operation WHERE tenant_id='demo' AND business_id=?)", business.toString());
            jdbc.update("DELETE FROM voucher_operation WHERE tenant_id='demo' AND business_id=?", business.toString());
        }
    }
    @AfterAll static void stop() { SERVER.stop(0); HTTP_THREADS.shutdownNow(); }

    @Test void preparationFailureNotifiesOriginalParticipantsWithoutClaimingPostingFailed() {
        noticeOrganization(); var preparation = enqueue(advance()); configuration.setEnabled(false);
        preparationWorker.poll();
        assertThat(reload(preparation).status()).isEqualTo(VoucherPreparation.Status.UNAVAILABLE);
        assertThat(voucherNoticeRecipients(preparation.input().id())).containsExactlyInAnyOrder("alice", "manager");
        assertThat(jdbc.queryForList("SELECT content FROM notification_inbox WHERE tenant_id='demo' AND event_key LIKE ?", String.class,
                "voucher:" + preparation.input().id() + ":%")).allSatisfy(content ->
                assertThat(content).contains("准备", "尚未登记过账命令").doesNotContain("100.00", "synthetic-account", "1234", "合成用途"));
        assertThat(operations.find("demo", preparation.input().id())).isEmpty(); assertThat(WRITES.get()).isZero();
    }

    @Test void legacyDirectOperationNotifiesOnlyProvenApplicantAndRetainsOriginalTarget() throws Exception {
        noticeOrganization(); var advance = advance(); saveNoticeRound(advance); var original = register(command(advance)); worker.poll();
        UUID id = original.input().command().id();
        assertThat(preparations.find("demo", id)).isEmpty(); assertThat(voucherNoticeRecipients(id)).containsExactly("alice");
        var response = noticeGet(voucherNoticePath(id, "alice", "POSTED"), "alice"); assertThat(response.getStatus()).isEqualTo(200);
        var detail = json.read(response.getContentAsString(), JsonNode.class);
        assertThat(detail.path("preparation").isNull()).isTrue(); assertThat(detail.at("/operation/id").asText()).isEqualTo(id.toString());
        assertThat(detail.path("reversalBound").isBoolean()).isTrue(); assertThat(detail.path("reversalBound").asBoolean()).isFalse();
    }

    @Test void expiredPostingLeaseNotifiesOriginalParticipantsOnceWithoutResending() {
        noticeOrganization(); var preparation = enqueue(advance()); RESPONDER.set(VoucherOperationIntegrationTest::accountingEvidence);
        preparationWorker.poll(); assertThat(voucherNoticeRecipients(preparation.input().id())).isEmpty();
        var claimed = execution.claim("demo", preparation.input().id(), now());
        assertThat(execution.claim("demo", preparation.input().id(), claimed.leaseUntil())).isNull();
        var current = operations.find("demo", preparation.input().id()).orElseThrow();
        assertThat(current.failure()).isEqualTo(VoucherOperation.Failure.LEASE_EXPIRED);
        assertThat(voucherNoticeRecipients(preparation.input().id())).containsExactlyInAnyOrder("alice", "manager");
        var query = execution.claim("demo", preparation.input().id(), claimed.leaseUntil().plusSeconds(1));
        execution.fail(query, VoucherOperation.Failure.CONNECTION, query.updatedAt());
        assertThat(voucherNoticeRecipients(preparation.input().id())).containsExactlyInAnyOrder("alice", "manager");
        assertThat(WRITES.get()).isZero();
    }

    @Test void originalPreparationTargetCannotBeReplacedByLaterSuccessfulPreparationOrAnotherRecipient() throws Exception {
        noticeOrganization(); var advance = advance(); var first = enqueue(advance); configuration.setEnabled(false); preparationWorker.poll();
        saveNoticeRound(advance); String path = voucherNoticePath(first.input().id(), "alice", "PREPARATION_UNAVAILABLE");
        configuration.setEnabled(true); RESPONDER.set(VoucherOperationIntegrationTest::accountingEvidence);
        var second = preparationService.retry("demo", advance.applicationId(), 5, "finance", now()); preparationWorker.poll(); worker.poll();
        assertThat(operations.find("demo", second.input().id()).orElseThrow().status()).isEqualTo(VoucherOperation.Status.POSTED);
        assertThat(voucherNoticeRecipients(second.input().id())).containsExactlyInAnyOrder("alice", "finance");
        var response = noticeGet(path, "alice"); assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        var detail = json.read(response.getContentAsString(), JsonNode.class);
        assertThat(detail.path("voucherId").asText()).isEqualTo(first.input().id().toString());
        assertThat(detail.path("preparation").path("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(detail.path("operation").isNull()).isTrue();
        assertThat(response.getContentAsString()).doesNotContain("synthetic-account", "targetDigest", "100.00", second.input().id().toString(), "actions");
        for (String user : List.of("manager", "finance", "admin", "bob")) assertThat(noticeGet(path, user).getStatus()).isEqualTo(404);
        assertThat(noticeGet(path + "?roundNo=1", "alice").getStatus()).isEqualTo(400);
        String ownManager = voucherNoticePath(first.input().id(), "manager", "PREPARATION_UNAVAILABLE");
        // 最小提示不赋予财务字段权限，夹具没有给原发起人原轮次明细读取权限。
        assertThat(noticeGet(ownManager, "manager").getStatus()).isIn(403, 404);
        String key = "voucher:" + first.input().id() + ":PREPARATION_UNAVAILABLE";
        jdbc.update("UPDATE notification_inbox SET round_no=2 WHERE tenant_id='demo' AND event_key=? AND recipient_id='alice'", key);
        try { assertThat(noticeGet(path, "alice").getStatus()).isEqualTo(404); }
        finally { jdbc.update("UPDATE notification_inbox SET round_no=1 WHERE tenant_id='demo' AND event_key=? AND recipient_id='alice'", key); }
        jdbc.update("UPDATE organization_person SET active=FALSE WHERE tenant_id='demo' AND subject='alice'");
        try { assertThat(noticeGet(path, "alice").getStatus()).isIn(403, 404); }
        finally { jdbc.update("UPDATE organization_person SET active=TRUE WHERE tenant_id='demo' AND subject='alice'"); }
    }

    @Test void voucherPendingAndExplicitRecheckAreQuietWhileNewResultsAndConflictsAreDistinct() {
        noticeOrganization(); var preparation = enqueue(advance()); RESPONDER.set(VoucherOperationIntegrationTest::accountingEvidence); preparationWorker.poll();
        UUID id = preparation.input().id(); var operation = operations.find("demo", id).orElseThrow();
        var claimed = execution.claim("demo", id, now()); execution.finish(claimed, new FinanceResult.Success<>(pending(operation.input().command(), 1)), now());
        assertThat(operations.find("demo", id).orElseThrow().failure()).isNull(); assertThat(voucherNoticeRecipients(id)).isEmpty();
        tx().executeWithoutResult(status -> execution.query("demo", id, operations.find("demo", id).orElseThrow().version(), now()));
        assertThat(voucherNoticeRecipients(id)).isEmpty();
        claimed = execution.claim("demo", id, now()); execution.finish(claimed, new FinanceResult.Success<>(posted(operation.input().command(), 2)), now());
        var posted = operations.find("demo", id).orElseThrow();
        tx().executeWithoutResult(status -> { events.publishEvent(new VoucherOperationChanged(operation, posted)); events.publishEvent(new VoucherOperationChanged(operation, posted)); });
        assertThat(voucherNoticeRecipients(id)).containsExactlyInAnyOrder("alice", "manager");
        tx().executeWithoutResult(status -> execution.query("demo", id, posted.version(), now()));
        claimed = execution.claim("demo", id, now()); execution.finish(claimed, new FinanceResult.Success<>(notFound(operation.input().command())), now());
        assertThat(operations.find("demo", id).orElseThrow().status()).isEqualTo(VoucherOperation.Status.RECONCILING);
        assertThat(jdbc.queryForList("SELECT DISTINCT event_key FROM notification_inbox WHERE tenant_id='demo' AND event_key LIKE ?", String.class, "voucher:" + id + ":%"))
                .containsExactlyInAnyOrder("voucher:" + id + ":POSTED", "voucher:" + id + ":RECONCILING");
    }

    @Test void voucherExpiryNoticeAndOutboundIntentAreAtomicAndInactiveRecipientSuppressesDelivery() {
        noticeOrganization(); var recipient = new Actor("demo", "manager", java.util.Set.of("USER"));
        var preference = notificationPreferences.get(recipient); notificationPreferences.revise(recipient, preference.version(), true, false);
        var preparation = enqueue(advance()); var work = preparationService.claim("demo", preparation.input().id(), now());
        UUID id = preparation.input().id();
        try {
            jdbc.execute("ALTER TABLE notification_inbox ADD CONSTRAINT voucher_notice_fixture CHECK(application_id<>'" + preparation.input().source().applicationId() + "' OR kind<>'VOUCHER_ATTENTION')");
            try { assertThatThrownBy(() -> preparationService.claim("demo", id, work.preparation().leaseUntil())).isInstanceOf(DataIntegrityViolationException.class); }
            finally { jdbc.execute("ALTER TABLE notification_inbox DROP CONSTRAINT voucher_notice_fixture"); }
            assertThat(reload(preparation)).isEqualTo(work.preparation()); assertThat(voucherNoticeRecipients(id)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_dispatch d JOIN notification_inbox n ON d.inbox_id=n.id WHERE n.application_id=? AND n.kind='VOUCHER_ATTENTION'", Long.class,
                    preparation.input().source().applicationId().toString())).isZero();
            preparationService.claim("demo", id, work.preparation().leaseUntil());
            assertThat(voucherNoticeRecipients(id)).containsExactlyInAnyOrder("alice", "manager");
            String messageId = jdbc.queryForObject("SELECT id FROM notification_inbox WHERE tenant_id='demo' AND event_key=? AND recipient_id='manager'", String.class,
                    "voucher:" + id + ":PREPARATION_UNAVAILABLE");
            UUID deliveryId = UUID.fromString(jdbc.queryForObject("SELECT id FROM notification_dispatch WHERE tenant_id='demo' AND inbox_id=?", String.class, messageId));
            jdbc.update("UPDATE organization_person SET active=FALSE WHERE tenant_id='demo' AND subject='manager'");
            assertThat(notificationDeliveries.claim(deliveryId, now().plusSeconds(30))).isNull();
            var delivery = notificationStore.get(recipient, deliveryId).orElseThrow();
            assertThat(delivery.progress().errorCode()).isEqualTo(io.agentflow.notification.NotificationDeliveryProgress.FailureCode.RECIPIENT_INACTIVE);
            assertThat(delivery.progress().attempts()).isZero();
        } finally {
            jdbc.update("UPDATE organization_person SET active=TRUE WHERE tenant_id='demo' AND subject='manager'");
            var current = notificationPreferences.get(recipient); notificationPreferences.revise(recipient, current.version(), preference.emailEnabled(), preference.enterpriseImEnabled());
        }
    }

    @Test void commandExpiryAndSourceChangeBeforeSendProduceOriginalFactsWithoutErpWrites() {
        noticeOrganization(); var preparation = enqueue(advance()); RESPONDER.set(VoucherOperationIntegrationTest::accountingEvidence); preparationWorker.poll();
        var operation = operations.find("demo", preparation.input().id()).orElseThrow();
        execution.claim("demo", preparation.input().id(), operation.input().command().expiresAt());
        assertThat(voucherNoticeRecipients(preparation.input().id())).containsExactlyInAnyOrder("alice", "manager");
        assertThat(operations.find("demo", preparation.input().id()).orElseThrow().status()).isEqualTo(VoucherOperation.Status.EXPIRED);
        var another = enqueue(advance()); preparationWorker.poll();
        jdbc.update("UPDATE approval_application SET status='REVOKED',version=version+1 WHERE tenant_id='demo' AND id=?", another.input().source().applicationId().toString());
        execution.claim("demo", another.input().id(), now());
        assertThat(voucherNoticeRecipients(another.input().id())).containsExactlyInAnyOrder("alice", "manager");
        assertThat(operations.find("demo", another.input().id()).orElseThrow().status()).isEqualTo(VoucherOperation.Status.VOIDED); assertThat(WRITES.get()).isZero();
    }

    private void saveNoticeRound(AdvanceRequest advance) {
        var original = applications.findById("demo", advance.applicationId()).orElseThrow();
        tx().executeWithoutResult(status -> submissionRounds.append(new io.agentflow.approval.model.SubmissionRound("demo", original.id(), 1,
                "synthetic-" + original.id(), original.definitionVersion(), original.title(), original.payload(), "alice", now().minusSeconds(120),
                io.agentflow.approval.model.SubmissionRound.Status.APPROVED, null, "manager", now().minusSeconds(119), original.formSchema())));
    }
    private String voucherNoticePath(UUID id, String recipient, String fact) {
        return "/api/v1/notifications/" + jdbc.queryForObject("SELECT id FROM notification_inbox WHERE tenant_id='demo' AND recipient_id=? AND event_key=?", String.class,
                recipient, "voucher:" + id + ":" + fact) + "/voucher-target";
    }
    private org.springframework.mock.web.MockHttpServletResponse noticeGet(String path, String user) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                .header("Authorization", "Bearer " + auth.login("demo", user, "demo").token())).andReturn().getResponse();
    }

    private List<String> voucherNoticeRecipients(UUID id) {
        return jdbc.queryForList("SELECT recipient_id FROM notification_inbox WHERE tenant_id='demo' AND event_key LIKE ? ORDER BY recipient_id",
                String.class, "voucher:" + id + ":%");
    }

    private void noticeOrganization() {
        tx().executeWithoutResult(status -> {
            if (!organization.initialized("demo")) organization.initialize("demo", "admin", now());
            var legal = new io.agentflow.organization.OrganizationUnit(entity, io.agentflow.organization.OrganizationUnit.Kind.LEGAL_ENTITY, "合成凭证法人", null, null, true, 1);
            var department = new io.agentflow.organization.OrganizationUnit(UUID.randomUUID(), io.agentflow.organization.OrganizationUnit.Kind.DEPARTMENT, "合成凭证部门", entity, null, true, 1);
            var position = new io.agentflow.organization.OrganizationUnit(UUID.randomUUID(), io.agentflow.organization.OrganizationUnit.Kind.POSITION, "合成凭证岗位", entity, null, true, 1);
            organization.save("demo", legal, 0); organization.save("demo", department, 0); organization.save("demo", position, 0);
            for (String user : List.of("alice", "manager", "finance")) {
                var person = organization.personBySubject("demo", user).orElse(null);
                if (person == null) {
                    person = new io.agentflow.organization.OrganizationPerson(UUID.randomUUID(), user, user, true, !user.equals("alice"), 1);
                    organization.save("demo", person, 0);
                }
                organization.save("demo", new io.agentflow.organization.OrganizationAppointment(UUID.randomUUID(), person.id(), department.id(), position.id(), true, 1), 0);
            }
        });
    }

    @Test void workerPostsOnlyCommittedCommandAndRestoresAllRevisions() {
        var command = command(advance()); var job = register(command);
        assertThat(WRITES.get()).isZero(); assertThat(register(command)).isEqualTo(job);
        worker.poll(); var done = reload(job);
        assertThat(done.status()).isEqualTo(VoucherOperation.Status.POSTED); assertThat(done.usablePosted()).isTrue();
        assertThat(done.version()).isEqualTo(3); assertThat(done.attempts()).isEqualTo(1);
        assertThat(WRITES.get()).isEqualTo(1); assertThat(QUERIES.get()).isZero(); assertThat(LAST_KEY.get()).isEqualTo(command.id().toString());
        assertThat(revisions(job)).containsExactly(1L, 2L, 3L);
        assertThat(new JdbcVoucherOperationRepository(jdbc, json).find("demo", command.id())).contains(done);
        assertThat(operations.find("foreign", command.id())).isEmpty();
    }

    @Test void rolledBackRegistrationNeverReachesErp() {
        var command = command(advance());
        assertThatThrownBy(() -> tx().executeWithoutResult(transaction -> {
            execution.register(command, target(), command.createdAt()); throw new IllegalStateException("synthetic local rollback");
        })).hasMessageContaining("synthetic local rollback");
        assertThat(operations.find("demo", command.id())).isEmpty(); worker.poll(); assertThat(WRITES.get()).isZero();
        assertThatThrownBy(() -> execution.register(command, target(), now())).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(() -> tx().executeWithoutResult(transaction -> worker.poll())).hasMessageContaining("outside a database transaction");
    }

    @Test void concurrentWorkersSendOnceAndNetworkWaitDoesNotHoldApplicationOrBusinessLock() throws Exception {
        var advance = advance(); var job = register(command(advance)); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        RESPONDER.set((path, request) -> { entered.countDown(); await(release); return posted(job.input().command(), 1); });
        var threads = Executors.newFixedThreadPool(3);
        try {
            var first = threads.submit(worker::poll); assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            threads.submit(() -> tx().executeWithoutResult(transaction -> advances.lock("demo", advance.id()))).get(1, TimeUnit.SECONDS);
            threads.submit(worker::poll).get(1, TimeUnit.SECONDS); assertThat(WRITES.get()).isEqualTo(1);
            release.countDown(); first.get(5, TimeUnit.SECONDS); assertThat(reload(job).status()).isEqualTo(VoucherOperation.Status.POSTED);
        } finally { release.countDown(); threads.shutdownNow(); }
    }

    @Test void timedOutWriteQueriesSameCommandWithoutAutomaticResendEvenWhenErpReportsNotFound() throws Exception {
        var job = register(command(advance())); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        RESPONDER.set((path, request) -> { entered.countDown(); await(release); return posted(job.input().command(), 1); });
        var threads = Executors.newSingleThreadExecutor();
        try {
            var first = threads.submit(worker::poll); assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue(); first.get(5, TimeUnit.SECONDS);
            assertThat(reload(job).failure()).isEqualTo(VoucherOperation.Failure.TIMEOUT);
        } finally { release.countDown(); threads.shutdownNow(); }
        RESPONDER.set((path, request) -> {
            assertThat(path).endsWith("voucher-query"); assertThat(request.at("/data/operationId").asText()).isEqualTo(job.input().command().id().toString());
            assertThat(request.at("/data/commandDigest").asText()).isEqualTo(job.input().command().digest()); return notFound(job.input().command());
        });
        recheck(job); worker.poll(); assertThat(reload(job).status()).isEqualTo(VoucherOperation.Status.NOT_FOUND);
        worker.poll(); assertThat(WRITES.get()).isEqualTo(1); assertThat(QUERIES.get()).isEqualTo(1); assertThat(LAST_KEY.get()).isNull();
        tx().executeWithoutResult(transaction -> operations.update(reload(job).retryNotFound(now())));
        RESPONDER.set((path, request) -> posted(job.input().command(), 1)); worker.poll();
        assertThat(reload(job).status()).isEqualTo(VoucherOperation.Status.POSTED); assertThat(WRITES.get()).isEqualTo(2);
        assertThat(LAST_KEY.get()).isEqualTo(job.input().command().id().toString());
    }

    @Test void expiredLeaseRecoversByQueryAndLateWorkerCannotReplaceNewFact() {
        var job = register(command(advance())); var stale = execution.claim("demo", job.input().command().id(), now().minusSeconds(20));
        assertThat(stale.status()).isEqualTo(VoucherOperation.Status.POSTING);
        worker.poll(); assertThat(reload(job).failure()).isEqualTo(VoucherOperation.Failure.LEASE_EXPIRED); assertThat(WRITES.get()).isZero();
        RESPONDER.set((path, request) -> posted(job.input().command(), 2)); worker.poll(); var recovered = reload(job);
        assertThat(recovered.status()).isEqualTo(VoucherOperation.Status.POSTED); assertThat(QUERIES.get()).isEqualTo(1);
        execution.finish(stale, new FinanceResult.Success<>(posted(job.input().command(), 1)), now());
        assertThat(reload(job)).isEqualTo(recovered); assertThat(revisions(job)).containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    @Test void eventConsumerFailureRollsBackConfirmationAndRecoversAlreadyPostedVoucherByQuery() {
        var job = register(command(advance())); listener.reject.set(true); worker.poll();
        var unknown = reload(job); assertThat(unknown.status()).isEqualTo(VoucherOperation.Status.UNKNOWN);
        assertThat(unknown.failure()).isEqualTo(VoucherOperation.Failure.INTERNAL_ERROR); assertThat(unknown.observation()).isNull();
        assertThat(revisions(job)).containsExactly(1L, 2L, 3L);
        RESPONDER.set((path, request) -> posted(job.input().command(), 1)); recheck(job); worker.poll();
        assertThat(reload(job).status()).isEqualTo(VoucherOperation.Status.POSTED); assertThat(WRITES.get()).isEqualTo(1); assertThat(QUERIES.get()).isEqualTo(1);
    }

    @Test void persistedHighWaterAndOriginalPostingSurviveContradictoryAndOlderReceipts() {
        var job = register(command(advance())); RESPONDER.set((path, request) -> posted(job.input().command(), 3)); worker.poll();
        var accepted = reload(job).observation();
        RESPONDER.set((path, request) -> pending(job.input().command(), 8)); recheck(job); worker.poll();
        var disputed = reload(job); assertThat(disputed.status()).isEqualTo(VoucherOperation.Status.RECONCILING);
        assertThat(disputed.observation()).isEqualTo(accepted); assertThat(disputed.highestRevision()).isEqualTo(8); assertThat(disputed.usablePosted()).isFalse();
        assertThat(new JdbcVoucherOperationRepository(jdbc, json).find("demo", job.input().command().id())).contains(disputed);
        RESPONDER.set((path, request) -> accepted); recheck(job); worker.poll();
        assertThat(reload(job).highestRevision()).isEqualTo(8); assertThat(reload(job).failure()).isEqualTo(VoucherOperation.Failure.STALE_OBSERVATION);
        assertThat(reload(job).observation()).isEqualTo(accepted); assertThat(reload(job).status()).isEqualTo(VoucherOperation.Status.RECONCILING);
    }

    @Test void approvalChangedBeforeDispatchVoidsCommandAndRegistrationRejectsForgedSource() {
        var advance = advance(); var job = register(command(advance)); var original = job.input().command();
        jdbc.update("UPDATE approval_application SET status='REVOKED',version=version+1 WHERE tenant_id='demo' AND id=?", advance.applicationId().toString());
        worker.poll(); assertThat(reload(job).status()).isEqualTo(VoucherOperation.Status.VOIDED); assertThat(WRITES.get()).isZero();
        var unregistered = advance(); var command = command(unregistered);
        jdbc.update("UPDATE approval_application SET payload_json='{}' WHERE tenant_id='demo' AND id=?", unregistered.applicationId().toString());
        assertThatThrownBy(() -> register(command)).isInstanceOf(DomainException.class); assertThat(operations.find("demo", command.id())).isEmpty();
        assertThat(register(original).status()).isEqualTo(VoucherOperation.Status.VOIDED);
    }

    @Test void changedDestinationCannotSendAndImmutableCommandUniquenessAndSnapshotIntegrityRemainEnforced() {
        var advance = advance(); var job = register(command(advance)); configuration.getTenants().get("demo").setEndpoint(ENDPOINT + "/changed");
        worker.poll(); var unknown = reload(job); assertThat(unknown.failure()).isEqualTo(VoucherOperation.Failure.TARGET_CHANGED);
        assertThat(WRITES.get()).isZero(); assertThat(QUERIES.get()).isZero();
        assertThatThrownBy(() -> register(command(advance))).isInstanceOf(DomainException.class);
        var duplicate = VoucherOperation.queue(new VoucherOperation.Input(command(advance), job.input().targetDigest()), now());
        assertThatThrownBy(() -> tx().executeWithoutResult(transaction -> operations.create(duplicate))).isInstanceOf(DataIntegrityViolationException.class);
        var altered = new VoucherOperation(new VoucherOperation.Input(job.input().command(), "b".repeat(64)), unknown.version() + 1, unknown.status(), unknown.attempts(),
                unknown.createdAt(), unknown.updatedAt(), unknown.nextAttemptAt(), null, unknown.observation(), null, unknown.highestRevision(), unknown.failure());
        assertThatThrownBy(() -> tx().executeWithoutResult(transaction -> operations.update(altered))).isInstanceOf(DomainException.class);
        assertThat(reload(job)).isEqualTo(unknown);
        jdbc.update("UPDATE voucher_operation SET command_digest=? WHERE tenant_id='demo' AND id=?", "c".repeat(64), job.input().command().id().toString());
        assertThatThrownBy(() -> reload(job)).isInstanceOf(IllegalStateException.class).hasMessageContaining("inconsistent");
    }

    @Test void preparedAccountingEvidenceRegistersOneOriginalVoucherBeforeAnyPosting() {
        var advance = advance(); var preparation = enqueue(advance); RESPONDER.set(VoucherOperationIntegrationTest::accountingEvidence);
        assertThat(enqueue(advance)).isEqualTo(preparation); assertThat(QUERIES.get()).isZero();
        preparationWorker.poll(); var ready = reload(preparation);
        assertThat(ready.status()).isEqualTo(VoucherPreparation.Status.READY); assertThat(QUERIES.get()).isEqualTo(2); assertThat(WRITES.get()).isZero();
        var operation = operations.find("demo", ready.result().operationId()).orElseThrow();
        assertThat(operation.status()).isEqualTo(VoucherOperation.Status.QUEUED); assertThat(operation.input().command().id()).isEqualTo(preparation.input().id());
        assertThat(operation.input().command().binding().businessVersion()).isEqualTo(advance.version());
        assertThat(operation.input().command().accountingDate()).isEqualTo(advance.approval().approvedAt().atZone(ZoneOffset.UTC).toLocalDate());
        assertThat(preparations.find("foreign", preparation.input().id())).isEmpty();
        assertThatThrownBy(() -> preparationService.retry("demo", advance.applicationId(), 5, "finance", now())).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo("VOUCHER_OPERATION_EXISTS"));
        worker.poll(); assertThat(reload(operation).status()).isEqualTo(VoucherOperation.Status.POSTED); assertThat(WRITES.get()).isEqualTo(1);
    }

    @Test void missingConfigurationBecomesVisibleAndExplicitRetryCreatesANewReadAttempt() {
        var advance = advance(); configuration.setEnabled(false); var first = enqueue(advance); preparationWorker.poll();
        assertThat(reload(first).result().code()).isEqualTo("NOT_CONFIGURED"); assertThat(QUERIES.get()).isZero();
        configuration.setEnabled(true); RESPONDER.set(VoucherOperationIntegrationTest::accountingEvidence);
        assertThatThrownBy(() -> preparationService.retry("demo", advance.applicationId(), 4, "finance", now())).isInstanceOf(DomainException.class);
        var second = preparationService.retry("demo", advance.applicationId(), 5, "finance", now());
        assertThat(second.input().id()).isNotEqualTo(first.input().id()); assertThat(second.input().attempt()).isEqualTo(2);
        assertThat(second.input().targetDigest()).isEqualTo(target()); assertThat(reload(first).input().targetDigest()).isNull();
        assertThat(preparationService.retry("demo", advance.applicationId(), 5, "finance", now())).isEqualTo(second);
        preparationWorker.poll(); assertThat(reload(second).status()).isEqualTo(VoucherPreparation.Status.READY);
        assertThat(reload(first).status()).isEqualTo(VoucherPreparation.Status.UNAVAILABLE); assertThat(WRITES.get()).isZero();
    }

    @Test void closedPeriodAndMissingMappingBlockWithoutInventedDefaultsOrVoucherCommands() {
        var advance = advance(); var period = enqueue(advance);
        RESPONDER.set((path, request) -> new FinanceResult.Rejected<>(FinanceResult.Reason.ACCOUNTING_PERIOD_CLOSED)); preparationWorker.poll();
        assertThat(reload(period).status()).isEqualTo(VoucherPreparation.Status.BLOCKED); assertThat(reload(period).result().code()).isEqualTo("ACCOUNTING_PERIOD_CLOSED");
        assertThat(QUERIES.get()).isEqualTo(1); assertThat(operations.find("demo", period.input().id())).isEmpty();
        var mapping = preparationService.retry("demo", advance.applicationId(), 5, "finance", now());
        RESPONDER.set((path, request) -> path.endsWith("account-mapping") ? new FinanceResult.Rejected<>(FinanceResult.Reason.ACCOUNT_MAPPING_UNAVAILABLE) : accountingEvidence(path, request));
        preparationWorker.poll(); assertThat(reload(mapping).result().code()).isEqualTo("ACCOUNT_MAPPING_UNAVAILABLE");
        assertThat(QUERIES.get()).isEqualTo(3); assertThat(operations.find("demo", mapping.input().id())).isEmpty(); assertThat(WRITES.get()).isZero();
    }

    @Test void changedApprovalDuringReadWaitVoidsPreparationWithoutHoldingTheApprovalLock() throws Exception {
        var advance = advance(); var preparation = enqueue(advance); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        RESPONDER.set((path, request) -> { if (path.endsWith("accounting-period")) { entered.countDown(); await(release); } return accountingEvidence(path, request); });
        var threads = Executors.newFixedThreadPool(3);
        try {
            var first = threads.submit(preparationWorker::poll); assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            threads.submit(preparationWorker::poll).get(1, TimeUnit.SECONDS); assertThat(QUERIES.get()).isEqualTo(1);
            threads.submit(() -> tx().executeWithoutResult(transaction -> {
                advances.lock("demo", advance.id()); jdbc.update("UPDATE approval_application SET status='REVOKED',version=version+1 WHERE tenant_id='demo' AND id=?", advance.applicationId().toString());
            })).get(1, TimeUnit.SECONDS);
            release.countDown(); first.get(5, TimeUnit.SECONDS);
            assertThat(reload(preparation).status()).isEqualTo(VoucherPreparation.Status.VOIDED);
            assertThat(operations.find("demo", preparation.input().id())).isEmpty(); assertThat(WRITES.get()).isZero();
        } finally { release.countDown(); threads.shutdownNow(); }
    }

    @Test void expiredPreparationLeaseRejectsLateEvidenceAndRequiresAnExplicitNewReadAttempt() {
        var advance = advance(); var preparation = enqueue(advance); var work = preparationService.claim("demo", preparation.input().id(), now());
        var command = prepared(work); var until = work.preparation().leaseUntil();
        assertThat(preparationService.claim("demo", preparation.input().id(), until)).isNull();
        assertThat(reload(preparation).result().code()).isEqualTo("LEASE_EXPIRED");
        preparationService.finish(work.preparation(), command, null, until.plusSeconds(1));
        assertThat(operations.find("demo", command.id())).isEmpty(); assertThat(QUERIES.get()).isZero();
    }

    @Test void preparationAuditFailureRollsBackNewVoucherAndKeepsTheOriginalClaim() {
        var preparation = enqueue(advance()); var work = preparationService.claim("demo", preparation.input().id(), now()); var command = prepared(work);
        jdbc.update("INSERT INTO voucher_preparation_revision(tenant_id,preparation_id,version,state_json) VALUES('demo',?,3,'{}')", preparation.input().id().toString());
        assertThatThrownBy(() -> preparationService.finish(work.preparation(), command, null, now())).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(operations.find("demo", command.id())).isEmpty(); assertThat(reload(preparation).status()).isEqualTo(VoucherPreparation.Status.RUNNING);
        jdbc.update("DELETE FROM voucher_preparation_revision WHERE tenant_id='demo' AND preparation_id=? AND version=3", preparation.input().id().toString());
        preparationService.finish(work.preparation(), command, null, now());
        assertThat(reload(preparation).status()).isEqualTo(VoucherPreparation.Status.READY); assertThat(operations.find("demo", command.id())).isPresent();
        assertThat(WRITES.get()).isZero();
    }

    @Test void rolledBackFinalApprovalPreparationAndChangedTargetMakeNoExternalRequests() {
        var advance = advance(); var app = applications.findById("demo", advance.applicationId()).orElseThrow();
        assertThatThrownBy(() -> tx().executeWithoutResult(transaction -> { preparationService.approved(app, "manager"); throw new IllegalStateException("synthetic rollback"); })).hasMessageContaining("synthetic rollback");
        assertThat(preparations.latest(sources.reference(app))).isEmpty(); preparationWorker.poll(); assertThat(QUERIES.get()).isZero();
        var preparation = enqueue(advance); configuration.getTenants().get("demo").setEndpoint(ENDPOINT + "/other"); preparationWorker.poll();
        assertThat(reload(preparation).result().code()).isEqualTo("TARGET_CHANGED"); assertThat(QUERIES.get()).isZero(); assertThat(WRITES.get()).isZero();
    }

    @Test void publishedMappingIsUsedByTheActualPreparationWorker() {
        var advance = advance(); var preparation = enqueue(advance);
        var published = publishMapping("first", true);
        RESPONDER.set(VoucherOperationIntegrationTest::accountingEvidence); preparationWorker.poll();
        assertThat(reload(preparation).status()).isEqualTo(VoucherPreparation.Status.READY);
        var mapping = operations.find("demo", preparation.input().id()).orElseThrow().input().command().mapping();
        assertThat(mapping.request().managedMapping()).isNotNull();
        assertThat(mapping.request().managedMapping().selection().mappingId()).isEqualTo(published.activeMapping().mappingId());
        assertThat(mapping.entries()).isEqualTo(published.activeMapping().definition().entries());
        assertThat(WRITES.get()).isZero();
    }

    @Test void firstPublicationWhileLegacyEvidenceIsInFlightPreventsRegistration() throws Exception {
        var preparation = enqueue(advance()); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        configuration.getTenants().get("demo").setTimeoutSeconds(10);
        RESPONDER.set((path, request) -> { entered.countDown(); await(release); return accountingEvidence(path, request); });
        var threads = Executors.newFixedThreadPool(2);
        try {
            var running = threads.submit(preparationWorker::poll); assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            threads.submit(() -> publishMapping("first", true)).get(2, TimeUnit.SECONDS);
            release.countDown(); running.get(5, TimeUnit.SECONDS);
            assertThat(operations.find("demo", preparation.input().id())).isEmpty();
            assertThat(reload(preparation).result().code()).isEqualTo("ACCOUNT_MAPPING_CHANGED");
            assertThat(WRITES.get()).isZero();
        } finally { release.countDown(); threads.shutdownNow(); }
    }

    @Test void aPersistedSelectionAndOriginalPostingSurviveLaterPublication() {
        var preparation = enqueue(advance()); var first = publishMapping("first", true);
        String originalInput = jdbc.queryForObject("SELECT input_json FROM voucher_preparation WHERE tenant_id='demo' AND id=?", String.class, preparation.input().id().toString());
        assertThat(json.write(preparation)).doesNotContain("mappingRequest");
        var work = preparationService.claim("demo", preparation.input().id(), now());
        assertThat(reload(preparation)).isEqualTo(work.preparation());
        var command = prepared(work); preparationService.finish(work.preparation(), command, null, now());
        var original = operations.find("demo", command.id()).orElseThrow();
        assertThat(reload(preparation).mappingRequest()).isEqualTo(command.mapping().request());
        assertThat(jdbc.queryForObject("SELECT input_json FROM voucher_preparation WHERE tenant_id='demo' AND id=?", String.class, command.id().toString())).isEqualTo(originalInput);
        var history = jdbc.queryForList("SELECT state_json FROM voucher_preparation_revision WHERE tenant_id='demo' AND preparation_id=? ORDER BY version", String.class, command.id().toString());
        assertThat(history).hasSize(3);
        assertThat(json.read(history.get(1), VoucherPreparation.class).mappingRequest()).isEqualTo(work.preparation().mappingRequest());
        assertThat(json.read(history.get(2), VoucherPreparation.class)).isEqualTo(reload(preparation));
        publishMapping("second", true);
        var receipt = posted(command, 1); RESPONDER.set((path, request) -> receipt); worker.poll();
        assertThat(reload(original).usablePosted()).isTrue(); recheck(original); worker.poll();
        assertThat(reload(original).input()).isEqualTo(original.input());
        assertThat(reload(original).input().command().mapping().request().managedMapping().selection().mappingId()).isEqualTo(first.activeMapping().mappingId());
        assertThat(WRITES.get()).isEqualTo(1);
    }

    @Test void replacementWhileManagedEvidenceIsInFlightRequiresANewExplicitAttempt() throws Exception {
        var advance = advance(); var preparation = enqueue(advance()); publishMapping("first", true);
        configuration.getTenants().get("demo").setTimeoutSeconds(10);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        RESPONDER.set((path, request) -> { if (path.endsWith("account-mapping")) { entered.countDown(); await(release); } return accountingEvidence(path, request); });
        var threads = Executors.newFixedThreadPool(2);
        try {
            var running = threads.submit(preparationWorker::poll); assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = threads.submit(() -> publishMapping("second", true)).get(2, TimeUnit.SECONDS);
            release.countDown(); running.get(5, TimeUnit.SECONDS);
            assertThat(reload(preparation).result().code()).isEqualTo("ACCOUNT_MAPPING_CHANGED");
            assertThat(operations.find("demo", preparation.input().id())).isEmpty();
            var retry = preparationService.retry("demo", advance.applicationId(), 5, "finance", now());
            RESPONDER.set(VoucherOperationIntegrationTest::accountingEvidence); preparationWorker.poll();
            assertThat(reload(retry).status()).isEqualTo(VoucherPreparation.Status.READY);
            assertThat(reload(retry).mappingRequest().managedMapping().selection().mappingId()).isEqualTo(second.activeMapping().mappingId());
            assertThat(reload(preparation).mappingRequest().managedMapping().selection().activeRevision()).isEqualTo(1);
        } finally { release.countDown(); threads.shutdownNow(); }
    }

    @Test void incompletePublishedMappingCannotFallBackToErpDefaults() {
        var advance = advance(); var preparation = enqueue(advance()); publishMapping("incomplete", false);
        preparationWorker.poll();
        assertThat(reload(preparation).status()).isEqualTo(VoucherPreparation.Status.BLOCKED);
        assertThat(reload(preparation).result().code()).isEqualTo("ACCOUNT_MAPPING_UNAVAILABLE");
        assertThat(QUERIES.get()).isZero(); assertThat(operations.find("demo", preparation.input().id())).isEmpty();
        publishMapping("complete", true); var retry = preparationService.retry("demo", advance.applicationId(), 5, "finance", now());
        RESPONDER.set(VoucherOperationIntegrationTest::accountingEvidence); preparationWorker.poll();
        assertThat(reload(retry).status()).isEqualTo(VoucherPreparation.Status.READY);
    }

    @Test void finisherRejectsEvidenceThatDropsTheClaimedManagedSelection() {
        var preparation = enqueue(advance()); publishMapping("first", true);
        var work = preparationService.claim("demo", preparation.input().id(), now());
        var command = prepared(work, work.source().mappingRequest());
        preparationService.finish(work.preparation(), command, null, now());
        assertThat(reload(preparation).result().code()).isEqualTo("ACCOUNT_MAPPING_EVIDENCE_MISMATCH");
        assertThat(operations.find("demo", command.id())).isEmpty();
    }

    @Test void legacyRunningStateCanBeRestoredButCannotRegisterUnboundEvidence() {
        var advance = advance(); var preparation = enqueue(advance()); var started = preparation.start(now(), now().plusSeconds(15));
        tx().executeWithoutResult(status -> preparations.update(started));
        assertThat(json.write(started)).doesNotContain("mappingRequest"); assertThat(reload(preparation)).isEqualTo(started);
        var plan = sources.derive(started.input().source());
        preparationService.finish(started, prepared(new VoucherPreparationService.Work(started, plan)), null, now());
        assertThat(reload(preparation).result().code()).isEqualTo("ACCOUNT_MAPPING_SELECTION_MISSING");
        assertThat(operations.find("demo", preparation.input().id())).isEmpty();
        var retry = preparationService.retry("demo", advance.applicationId(), 5, "finance", now());
        RESPONDER.set(VoucherOperationIntegrationTest::accountingEvidence); preparationWorker.poll();
        assertThat(reload(retry).status()).isEqualTo(VoucherPreparation.Status.READY);
    }

    @Test void aPublishedMappingForThePreviousTargetStopsBeforeAnyExternalRead() {
        var advance = advance(); publishMapping("first", true);
        configuration.getTenants().get("demo").setEndpoint(ENDPOINT + "/changed");
        var preparation = enqueue(advance()); preparationWorker.poll();
        assertThat(reload(preparation).result().code()).isEqualTo("TARGET_CHANGED");
        assertThat(QUERIES.get()).isZero(); assertThat(WRITES.get()).isZero();
    }

    @Test void registrationWaitsForThePublicationLockAndObservesItsCommittedReplacement() throws Exception {
        var preparation = enqueue(advance()); publishMapping("first", true);
        var work = preparationService.claim("demo", preparation.input().id(), now()); var command = prepared(work);
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1); var finishing = new CountDownLatch(1);
        var threads = Executors.newFixedThreadPool(2);
        try {
            var publication = threads.submit(() -> tx().executeWithoutResult(status -> {
                configurationLocks.lock("demo"); locked.countDown(); await(release); publishMapping("second", true);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var registration = threads.submit(() -> { finishing.countDown(); preparationService.finish(work.preparation(), command, null, now()); });
            assertThat(finishing.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> registration.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown(); publication.get(5, TimeUnit.SECONDS); registration.get(5, TimeUnit.SECONDS);
            assertThat(reload(preparation).result().code()).isEqualTo("ACCOUNT_MAPPING_CHANGED");
            assertThat(operations.find("demo", command.id())).isEmpty();
        } finally { release.countDown(); threads.shutdownNow(); }
    }

    @Test void waitingForTheConfigurationLockCannotExtendThePreparationLease() throws Exception {
        var advance = advance(); publishMapping("first", true);
        var source = sources.reference(applications.findById("demo", advance.applicationId()).orElseThrow());
        // 恢复已消耗十二秒的有效十五秒租约，以短等待覆盖真实过期边界。
        var startedAt = now().minusSeconds(12);
        var preparation = VoucherPreparation.queue(new VoucherPreparation.Input(UUID.randomUUID(), source, 1, "manager", target()), startedAt);
        tx().executeWithoutResult(status -> preparations.create(preparation));
        var work = preparationService.claim("demo", preparation.input().id(), startedAt); var command = prepared(work);
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1); var finishing = new CountDownLatch(1);
        var threads = Executors.newFixedThreadPool(2);
        try {
            var holder = threads.submit(() -> tx().executeWithoutResult(status -> { configurationLocks.lock("demo"); locked.countDown(); await(release); }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var registration = threads.submit(() -> { var at = now(); finishing.countDown(); preparationService.finish(work.preparation(), command, null, at); });
            assertThat(finishing.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> registration.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            Thread.sleep(Math.max(1, java.time.Duration.between(Instant.now(), work.preparation().leaseUntil()).toMillis() + 30));
            release.countDown(); holder.get(5, TimeUnit.SECONDS); registration.get(5, TimeUnit.SECONDS);
            assertThat(reload(preparation).status()).isEqualTo(VoucherPreparation.Status.UNAVAILABLE);
            assertThat(reload(preparation).result().code()).isEqualTo("LEASE_EXPIRED");
            assertThat(operations.find("demo", command.id())).isEmpty();
        } finally { release.countDown(); threads.shutdownNow(); }
    }

    @Test void categoryRevisionAloneDoesNotRebindAnAlreadyPublishedMapping() {
        var preparation = enqueue(advance()); var published = publishMapping("first", true);
        var work = preparationService.claim("demo", preparation.input().id(), now());
        var current = expenseConfiguration.categories("demo"); var categories = new ArrayList<>(current.categories());
        categories.add(new ExpenseCategoryCatalog.Category("synthetic-" + UUID.randomUUID(), "新增合成类别", List.of(ExpenseLine.Unit.ITEM), true));
        expenseConfiguration.saveCategories(new Actor("demo", "admin", java.util.Set.of("FINANCE_CONFIG_ADMIN")), current.version(), categories, "新增类别不改写原科目发布");
        preparationService.finish(work.preparation(), prepared(work), null, now());
        assertThat(reload(preparation).status()).isEqualTo(VoucherPreparation.Status.READY);
        assertThat(reload(preparation).mappingRequest().managedMapping().selection().categoryRevision()).isEqualTo(published.activeMapping().categoryRevision());
    }

    private AccountMappingConfigurationService.Current publishMapping(String name, boolean complete) {
        var actor = new Actor("demo", "admin", java.util.Set.of("FINANCE_CONFIG_ADMIN"));
        var entries = List.of(new AccountMappingPort.Entry(new AccountMappingPort.Key(AccountMappingPort.Role.EMPLOYEE_RECEIVABLE, ""), "synthetic-" + name + "-receivable"),
                new AccountMappingPort.Entry(new AccountMappingPort.Key(AccountMappingPort.Role.EMPLOYEE_PAYABLE, ""), "synthetic-" + name + "-payable"));
        String key = "mapping-" + UUID.randomUUID();
        var draft = mappingConfiguration.saveDraft(actor, key, 0, new AccountMappingDefinition("合成科目", entity, "CNY", complete ? entries : entries.subList(0, 1)), "合成测试配置");
        var current = mappingConfiguration.current("demo", entity, "CNY");
        return mappingConfiguration.publish(actor, key, draft.revision(), current.categoryRevision(), current.activeRevision(), "合成测试发布");
    }

    private AdvanceRequest advance() {
        var at = now().minusSeconds(60); var id = UUID.randomUUID(); fixtures.add(id);
        var app = Application.draftBusiness(UUID.randomUUID(), "demo", "SYNTHETIC-" + id, "fixture", 1, "alice", "合成凭证", Map.of(), null, null, null,
                new BusinessReference(BusinessReference.Type.ADVANCE_REQUEST, id));
        var request = AdvanceRequest.draft(id, "demo", app.id(), "alice", new AdvanceRequestContent(entity, "合成借款", "合成用途", new Money(new BigDecimal("100.00"), "CNY"), at.atZone(ZoneOffset.UTC).toLocalDate().plusDays(10)));
        tx().executeWithoutResult(transaction -> {
            applications.save(app); advances.create(request, "alice");
            var catalog = new FinanceCatalog("alice", "v1", at.plusSeconds(600), List.of(new FinanceCatalog.LegalEntity(entity, "法人", "CNY", false, "v1", "UTC")), List.of(), List.of(), List.of(), List.of());
            var account = new EmployeeAccountSnapshot(entity, "alice", "synthetic-account", "****1234", "a".repeat(64), "v1");
            request.freeze(1, 1, catalog, new EmployeeAccountPort.Account(account, at.plusSeconds(600)),
                    new InitiatorContext(UUID.randomUUID(), UUID.randomUUID(), "alice", 1, entity, "法人", UUID.randomUUID(), "部门", UUID.randomUUID(), "岗位"), at);
            advances.update(request, 1, "alice", "SYNTHETIC_SUBMIT"); request.approve(2, 1, 5, "manager", at); advances.update(request, 2, "manager", "SYNTHETIC_APPROVE");
            applications.update(Application.restore(app.id(), "demo", app.businessNo(), app.processKey(), 1, "alice", app.title(), AdvanceRequestFormContract.submittedPayload(request.currentRound()),
                    ApplicationStatus.APPROVED, 1, 5, null, null, NotificationTexts.EMPTY, app.businessReference()), 1);
        }); return request;
    }
    private VoucherCommand command(AdvanceRequest advance) {
        var plan = VoucherSource.advance(applications.findById("demo", advance.applicationId()).orElseThrow(), advance); var at = now().minusSeconds(30); var date = plan.accountingDate();
        var period = new AccountingPeriodPort.OpenPeriod(plan.periodRequest(), "synthetic-period", "period-v1", date.minusDays(30), date.plusDays(30), at, at.plusSeconds(600));
        var mapping = new AccountMappingPort.Mapping(plan.mappingRequest(), "map-v1", at, at.plusSeconds(600), plan.mappingRequest().keys().stream().map(key -> new AccountMappingPort.Entry(key, "synthetic-" + key.role().name())).toList());
        return plan.prepare(UUID.randomUUID(), period, mapping, at);
    }
    private VoucherOperation register(VoucherCommand command) { return tx().execute(transaction -> execution.register(command, target(), command.createdAt())); }
    private VoucherOperation reload(VoucherOperation value) { return operations.find("demo", value.input().command().id()).orElseThrow(); }
    private VoucherPreparation reload(VoucherPreparation value) { return preparations.find("demo", value.input().id()).orElseThrow(); }
    private VoucherPreparation enqueue(AdvanceRequest advance) {
        var app = applications.findById("demo", advance.applicationId()).orElseThrow();
        tx().executeWithoutResult(transaction -> preparationService.approved(app, "manager"));
        return preparations.latest(sources.reference(app)).orElseThrow();
    }
    private static VoucherCommand prepared(VoucherPreparationService.Work work) {
        return prepared(work, work.preparation().mappingRequest() == null ? work.source().mappingRequest() : work.preparation().mappingRequest());
    }
    private static VoucherCommand prepared(VoucherPreparationService.Work work, AccountMappingPort.Request request) {
        var plan = work.source(); var at = now(); var date = plan.accountingDate();
        return plan.prepare(work.preparation().input().id(), new AccountingPeriodPort.OpenPeriod(plan.periodRequest(), "synthetic-period", "v1", date.minusDays(30), date.plusDays(30), at, at.plusSeconds(300)),
                new AccountMappingPort.Mapping(request, "v1", at, at.plusSeconds(300), request.managedMapping() == null
                        ? request.keys().stream().map(key -> new AccountMappingPort.Entry(key, "synthetic-" + key.role())).toList() : request.managedMapping().entries()), at);
    }
    private static Object accountingEvidence(String path, JsonNode envelope) {
        var data = envelope.path("data"); var at = now();
        if (path.endsWith("accounting-period")) {
            var request = wire.read(data.toString(), AccountingPeriodPort.Request.class); var date = request.accountingDate();
            return new AccountingPeriodPort.OpenPeriod(request, "synthetic-period", "v1", date.minusDays(30), date.plusDays(30), at, at.plusSeconds(300));
        }
        if (path.endsWith("account-mapping")) {
            var request = wire.read(data.toString(), AccountMappingPort.Request.class);
            var entries = request.managedMapping() == null ? request.keys().stream().map(key -> new AccountMappingPort.Entry(key, "synthetic-" + key.role())).toList() : request.managedMapping().entries();
            return new AccountMappingPort.Mapping(request, "v1", at, at.plusSeconds(300), entries);
        }
        return posted(wire.read(data.path("command").toString(), VoucherCommand.class), 1);
    }
    private void recheck(VoucherOperation value) { tx().executeWithoutResult(transaction -> operations.update(reload(value).requestQuery(now()))); }
    private List<Long> revisions(VoucherOperation value) { return jdbc.queryForList("SELECT version FROM voucher_operation_revision WHERE tenant_id='demo' AND operation_id=? ORDER BY version", Long.class, value.input().command().id().toString()); }
    private String target() { return configuration.destination("demo").orElseThrow().digest("demo"); }
    private TransactionTemplate tx() { return new TransactionTemplate(transactions); }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private static VoucherObservation posted(VoucherCommand command, long revision) { return new VoucherObservation(command.id(), command.digest(), VoucherObservation.Status.POSTED, revision, now(), "synthetic-posting", "synthetic-voucher", command.period().periodReference(), command.accountingDate(), command.totals().gross(), command.totals().gross(), command.createdAt(), null); }
    private static VoucherObservation pending(VoucherCommand command, long revision) { return new VoucherObservation(command.id(), command.digest(), VoucherObservation.Status.PENDING, revision, now(), "synthetic-posting", null, null, null, null, null, null, null); }
    private static VoucherObservation notFound(VoucherCommand command) { return new VoucherObservation(command.id(), command.digest(), VoucherObservation.Status.NOT_FOUND, 0L, now(), null, null, null, null, null, null, null, null); }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic ERP wait expired"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Synthetic ERP interrupted"); }
    }
    private static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(HTTP_THREADS);
            server.createContext("/finance/", exchange -> {
                var path = exchange.getRequestURI().getPath(); if (path.endsWith("voucher-command")) WRITES.incrementAndGet(); else QUERIES.incrementAndGet();
                LAST_KEY.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                var request = wire.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonNode.class);
                var value = RESPONDER.get().apply(path, request);
                var envelope = value instanceof FinanceResult.Rejected<?> rejected
                        ? Map.of("contractVersion", 1, "tenantId", request.path("tenantId").asText(), "requestId", request.path("requestId").asText(), "outcome", "REJECTED", "reason", rejected.reason().name())
                        : Map.of("contractVersion", 1, "tenantId", request.path("tenantId").asText(), "requestId", request.path("requestId").asText(), "outcome", "SUCCESS", "data", value);
                byte[] body = wire.write(envelope).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, 0);
                try { exchange.getResponseBody().write(body); } finally { exchange.close(); }
            }); server.start(); return server;
        } catch (Exception failed) { throw new IllegalStateException(failed); }
    }

    /**
     * 模拟业务消费者在 ERP 已过账后本地落账失败，验证真实事务代理的回滚边界。
     * @author owlzhangfq@gmail.com
     */
    static class FailureListener {
        private final AtomicBoolean reject = new AtomicBoolean();
        @EventListener public void changed(VoucherOperationChanged event) {
            if (event.current().status() == VoucherOperation.Status.POSTED && reject.compareAndSet(true, false)) throw new IllegalStateException("Synthetic settlement failure");
        }
    }
    /**
     * 仅本测试上下文启用故障消费者。
     * @author owlzhangfq@gmail.com
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class ListenerConfiguration { @Bean FailureListener voucherFailureListener() { return new FailureListener(); } }
}
