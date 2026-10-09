package io.agentflow.finance;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.*;
import io.agentflow.observability.DiagnosticContext;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

/**
 * 数据库、真实 HTTP 与后台执行器共同验证预算副作用恢复；金额和外部服务均为合成夹具。
 *
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.finance-gateway.enabled=true", "agentflow.budgets.worker-enabled=false",
        "agentflow.budgets.lease-seconds=15", "agentflow.invoices.verification-worker-enabled=false", "agentflow.expenses.precheck-worker-enabled=false",
        "agentflow.notifications.delivery-worker-enabled=false"})
class BudgetOperationIntegrationTest {
    private static final LocalDate DATE = LocalDate.of(2026, 9, 28);
    private static final UUID ENTITY = UUID.randomUUID();
    private static final ExecutorService HTTP_THREADS = Executors.newCachedThreadPool();
    private static final HttpServer SERVER = server();
    private static final String ENDPOINT = "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/finance";
    private static final AtomicReference<BiFunction<String, JsonNode, Object>> RESPONDER = new AtomicReference<>();
    private static final AtomicInteger WRITES = new AtomicInteger(), QUERIES = new AtomicInteger();
    private static final AtomicReference<String> LAST_KEY = new AtomicReference<>();
    private static final List<String> TRACES = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static JsonUtil wire;
    private final List<UUID> fixtures = new ArrayList<>();
    @Autowired JsonUtil json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ApplicationRepository applications;
    @Autowired ExpenseReportRepository reports;
    @Autowired JdbcBudgetOperationRepository operations;
    @Autowired JdbcBudgetOccupationRepository occupations;
    @Autowired BudgetOperationService execution;
    @Autowired BudgetOperationWorker worker;
    @Autowired BudgetSystemPort gateway;
    @Autowired FinanceGatewayConfiguration configuration;
    @Autowired io.agentflow.organization.OrganizationRepository organization;
    @Autowired io.agentflow.notification.NotificationPreferencesService notificationPreferences;
    @Autowired io.agentflow.notification.NotificationDeliveryService notificationDeliveries;
    @Autowired io.agentflow.notification.JdbcNotificationDeliveryStore notificationStore;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry values) {
        values.add("agentflow.finance-gateway.tenants.demo.endpoint", () -> ENDPOINT);
        values.add("agentflow.finance-gateway.tenants.demo.allow-unauthenticated-loopback", () -> "true");
        values.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_BUDGET_TEST_URL", "jdbc:h2:mem:budget-operations;DB_CLOSE_DELAY=-1"));
        values.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_BUDGET_TEST_DRIVER", "org.h2.Driver"));
        values.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_BUDGET_TEST_USER", "sa"));
        values.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_BUDGET_TEST_PASSWORD", ""));
    }
    @BeforeEach
    void setup() {
        wire = json; WRITES.set(0); QUERIES.set(0); configuration.setEnabled(true);
        configuration.getTenants().get("demo").setEndpoint(ENDPOINT); configuration.getTenants().get("demo").setTimeoutSeconds(3);
        RESPONDER.set((path, request) -> applied(json.read(request.at("/data/command").toString(), BudgetCommand.class)));
    }
    @AfterEach
    void removeOnlyFixtureBudgetTasks() {
        // 保留合成报销历史；清理本测试的任务，防止其他测试的 poll 消费这些故意留下的未知结果。
        for (var report : fixtures) {
            jdbc.update(
                    "DELETE FROM budget_operation_revision WHERE tenant_id='demo' AND operation_id"
                            + " IN (SELECT id FROM budget_operation WHERE tenant_id='demo' AND"
                            + " report_id=?)", report.toString());
            jdbc.update("DELETE FROM budget_operation WHERE tenant_id='demo' AND report_id=?", report.toString());
            jdbc.update("DELETE FROM budget_occupation_revision WHERE tenant_id='demo' AND report_id=?", report.toString());
            jdbc.update("DELETE FROM budget_occupation WHERE tenant_id='demo' AND report_id=?", report.toString());
        }
    }
    @AfterAll static void stop() { SERVER.stop(0); HTTP_THREADS.shutdownNow(); }

    @Test
    void tracePersistsThroughQueueAndReachesFinanceGateway() throws Exception {
        var report = report(true); String expectedTrace = UUID.randomUUID().toString();
        BudgetOperation queued;
        try (var trace = new DiagnosticContext(expectedTrace, "demo").open()) { queued = reserve(report); }
        UUID id = queued.input().command().id();
        assertThat(DiagnosticContext.validTrace(expectedTrace)).isTrue();
        assertThat(MDC.get(DiagnosticContext.TRACE_ID)).isNull();
        assertThat(jdbc.queryForMap(
                                        "SELECT * FROM budget_operation WHERE tenant_id='demo' AND"
                                                + " id=?", id.toString())
                .get("TRACE_ID")).isEqualTo(expectedTrace);
        TRACES.clear();
        worker.poll();
        assertThat(reload(queued).status()).isEqualTo(BudgetOperation.Status.APPLIED);
        assertThat(TRACES).isNotEmpty().containsOnly(expectedTrace);
        assertThat(jdbc.queryForMap(
                                        "SELECT * FROM budget_operation WHERE tenant_id='demo' AND"
                                                + " id=?", id.toString())
                .get("TRACE_ID")).isEqualTo(expectedTrace);
        assertThat(MDC.get(DiagnosticContext.TRACE_ID)).isNull();
        assertThat(MDC.get(DiagnosticContext.TENANT_ID)).isNull();
    }

    @Test
    void unknownBudgetResultNotifiesOriginalApplicantOnceAndDoesNotExposeFinancialDetails() {
        noticePeople(); var report = report(true); var job = reserve(report); UUID id = job.input().command().id();
        var claimed = execution.claim("demo", id, job.createdAt()); execution.fail(claimed, claimed.updatedAt().plusSeconds(1));
        assertThat(noticeRecipients(id, "UNKNOWN")).containsExactly("alice");
        var unknown = reload(job); var query = execution.claim("demo", id, unknown.nextAttemptAt());
        execution.fail(query, query.updatedAt().plusSeconds(1));
        assertThat(noticeRecipients(id, "UNKNOWN")).containsExactly("alice");
        assertThat(jdbc.queryForList("SELECT content FROM notification_inbox WHERE event_key=?", String.class, "budget:" + id + ":UNKNOWN"))
                .allSatisfy(content -> assertThat(content).contains("暂不明确").doesNotContain("100.00", "synthetic-account", "1234", "合成明细"));
        assertThat(WRITES.get()).isZero(); assertThat(reload(job).status()).isEqualTo(BudgetOperation.Status.UNKNOWN);
    }

    @Test
    void expiredBudgetLeaseNotifiesWithoutInventingRejectionOrBlindResend() {
        noticePeople(); var report = report(true); var job = reserve(report); UUID id = job.input().command().id();
        var claimed = execution.claim("demo", id, job.createdAt());
        assertThat(execution.claim("demo", id, claimed.leaseUntil())).isNull();
        assertThat(reload(job).failure()).isEqualTo(BudgetOperation.Failure.LEASE_EXPIRED);
        assertThat(noticeRecipients(id, "UNKNOWN")).containsExactly("alice");
        assertThat(noticeRecipients(id, "REJECTED")).isEmpty(); assertThat(WRITES.get()).isZero();
    }

    private List<String> noticeRecipients(UUID id, String fact) {
        return jdbc.queryForList(
                "SELECT recipient_id FROM notification_inbox WHERE tenant_id='demo' AND event_key=?"
                        + " ORDER BY recipient_id",
                String.class, "budget:" + id + ":" + fact);
    }

    @Test
    void acceptedPendingIsQuietAndAuthoritativeAbsenceKeepsOriginalRetryThenReportsAppliedOnce() {
        noticePeople(); var report = report(true); var job = reserve(report); var command = job.input().command();
        var claim = execution.claim("demo", command.id(), job.createdAt());
        execution.finish(claim, new FinanceResult.Success<>(new BudgetObservation(command.id(), command.digest(), BudgetObservation.Status.PENDING, null, null, null, null)), claim.updatedAt().plusSeconds(1));
        assertThat(noticeRecipients(command.id(), "UNKNOWN")).isEmpty();
        var pending = reload(job); var query = execution.claim("demo", command.id(), pending.nextAttemptAt());
        execution.finish(query, new FinanceResult.Success<>(new BudgetObservation(command.id(), command.digest(), BudgetObservation.Status.NOT_FOUND, null, null, null, null)), query.updatedAt().plusSeconds(1));
        assertThat(noticeRecipients(command.id(), "NOT_FOUND")).containsExactly("alice");
        var retry = execution.claim("demo", command.id(), reload(job).nextAttemptAt());
        assertThat(retry.input()).isEqualTo(job.input());
        execution.finish(retry, new FinanceResult.Success<>(applied(command)), retry.updatedAt().plusSeconds(1));
        execution.finish(query, rejected(query, BudgetObservation.Rejection.BUDGET_INSUFFICIENT), retry.updatedAt().plusSeconds(2));
        assertThat(noticeRecipients(command.id(), "APPLIED")).containsExactly("alice");
        assertThat(noticeRecipients(command.id(), "REJECTED")).isEmpty(); assertThat(reload(job).status()).isEqualTo(BudgetOperation.Status.APPLIED);
    }

    @Test
    void noticeAndOutboundIntentRollbackWithBudgetStateAndInactiveRecipientCannotSend() {
        noticePeople(); var recipient = new io.agentflow.common.Actor("demo", "alice", java.util.Set.of("USER"));
        var preference = notificationPreferences.get(recipient); notificationPreferences.revise(recipient, preference.version(), true, false);
        var report = report(true); var job = reserve(report); UUID id = job.input().command().id();
        var claim = execution.claim("demo", id, job.createdAt());
        try {
            jdbc.execute(
                    "ALTER TABLE notification_inbox ADD CONSTRAINT budget_notice_fixture"
                            + " CHECK(application_id<>'"
                            + report.applicationId() + "' OR kind<>'BUDGET_ATTENTION')");
            try { assertThatThrownBy(() -> execution.fail(claim, claim.updatedAt().plusSeconds(1))).isInstanceOf(DataIntegrityViolationException.class); }
            finally { jdbc.execute("ALTER TABLE notification_inbox DROP CONSTRAINT budget_notice_fixture"); }
            assertThat(reload(job)).isEqualTo(claim); assertThat(noticeRecipients(id, "UNKNOWN")).isEmpty();
            assertThat(jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM notification_dispatch d JOIN"
                                            + " notification_inbox n ON d.inbox_id=n.id WHERE"
                                            + " n.application_id=?", Long.class,
                    report.applicationId().toString())).isZero();
            execution.fail(claim, claim.updatedAt().plusSeconds(1));
            String messageId = jdbc.queryForObject(
                            "SELECT id FROM notification_inbox WHERE tenant_id='demo' AND"
                                    + " event_key=? AND recipient_id='alice'", String.class, "budget:" + id + ":UNKNOWN");
            var deliveries = jdbc.queryForList(
                            "SELECT id FROM notification_dispatch WHERE tenant_id='demo' AND"
                                    + " inbox_id=?", String.class, messageId);
            assertThat(deliveries).hasSize(1); UUID deliveryId = UUID.fromString(deliveries.get(0));
            var other = reserve(report(true)); UUID otherId = other.input().command().id();
            var otherClaim = execution.claim("demo", otherId, other.createdAt()); execution.fail(otherClaim, otherClaim.updatedAt().plusSeconds(1));
            String otherMessage = jdbc.queryForObject(
                            "SELECT id FROM notification_inbox WHERE tenant_id='demo' AND"
                                    + " event_key=? AND recipient_id='alice'", String.class, "budget:" + otherId + ":UNKNOWN");
            UUID otherDelivery = UUID.fromString(jdbc.queryForObject(
                                    "SELECT id FROM notification_dispatch WHERE tenant_id='demo'"
                                            + " AND inbox_id=?", String.class, otherMessage));
            jdbc.update("UPDATE notification_inbox SET round_no=2 WHERE id=?", otherMessage);
            try { assertThat(notificationDeliveries.claim(otherDelivery, now().plusSeconds(30))).isNull(); }
            finally { jdbc.update("UPDATE notification_inbox SET round_no=1 WHERE id=?", otherMessage); }
            var mismatched = notificationStore.get(recipient, otherDelivery).orElseThrow();
            assertThat(mismatched.progress().errorCode()).isEqualTo(io.agentflow.notification.NotificationDeliveryProgress.FailureCode.MESSAGE_UNAVAILABLE);
            assertThat(mismatched.progress().attempts()).isZero();
            jdbc.update(
                    "UPDATE organization_person SET active=FALSE WHERE tenant_id='demo' AND"
                            + " subject='alice'");
            assertThat(notificationDeliveries.claim(deliveryId, now().plusSeconds(30))).isNull();
            var delivery = notificationStore.get(recipient, deliveryId).orElseThrow();
            assertThat(delivery.progress().errorCode()).isEqualTo(io.agentflow.notification.NotificationDeliveryProgress.FailureCode.RECIPIENT_INACTIVE);
            assertThat(delivery.progress().attempts()).isZero();
        } finally {
            jdbc.update(
                    "UPDATE organization_person SET active=TRUE WHERE tenant_id='demo' AND"
                            + " subject='alice'");
            var current = notificationPreferences.get(recipient); notificationPreferences.revise(recipient, current.version(), preference.emailEnabled(), preference.enterpriseImEnabled());
        }
    }

    private void noticePeople() {
        tx().executeWithoutResult(status -> {
            if (!organization.initialized("demo")) organization.initialize("demo", "admin", now());
            for (String user : List.of("alice", "finance")) {
                if (organization.personBySubject("demo", user).isEmpty()) organization.save("demo",
                        new io.agentflow.organization.OrganizationPerson(UUID.randomUUID(), user, user, true, !user.equals("alice"), 1), 0);
            }
        });
    }

    @Test
    void actualWorkerPublishesCommittedCommandAndPersistsConfirmedLedgerWithEveryRevision() {
        var report = report(true); var job = reserve(report);
        assertThat(WRITES.get()).isZero(); assertThat(occupation(report).frozenFor(position(report))).isFalse();
        worker.poll();
        var done = reload(job); assertThat(done.status()).isEqualTo(BudgetOperation.Status.APPLIED);
        assertThat(done.version()).isEqualTo(3); assertThat(done.attempts()).isEqualTo(1);
        assertThat(occupation(report).frozenFor(position(report))).isTrue(); assertThat(occupation(report).version()).isEqualTo(2);
        assertThat(WRITES.get()).isEqualTo(1); assertThat(QUERIES.get()).isZero(); assertThat(LAST_KEY.get()).isEqualTo(job.input().command().id().toString());
        assertThat(revisions(job)).containsExactly(1L, 2L, 3L);
        assertThat(new JdbcBudgetOperationRepository(
                                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                                (jdbc).getDataSource(),
                                                io.agentflow.finance.mapper
                                                        .BudgetOperationRepositoryMapper.class), json).find("demo", job.input().command().id())).contains(done);
        assertThat(operations.find("foreign", job.input().command().id())).isEmpty();
    }

    @Test
    void lateLocalRollbackRemovesFreezeAndOutboxWithoutAnyExternalWrite() {
        var report = report(false);
        assertThatThrownBy(() -> tx().executeWithoutResult(transaction -> {
            freeze(report); reports.update(report, 1, "alice", "SYNTHETIC_SUBMIT");
            execution.reserve("demo", report.id(), 2, DATE, target(), now());
            throw new IllegalStateException(
                                                            "synthetic failure after outbox"
                                                                    + " registration");
        })).hasMessageContaining("synthetic failure");
        assertThat(reports.find("demo", report.id()).orElseThrow().version()).isEqualTo(1);
        assertThat(occupations.find("demo", report.id())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_operation WHERE report_id=?", Integer.class, report.id().toString())).isZero();
        worker.poll(); assertThat(WRITES.get()).isZero();
        assertThatThrownBy(() -> execution.reserve("demo", report.id(), 1, DATE, target(), now())).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(() -> tx().executeWithoutResult(transaction -> worker.poll())).hasMessageContaining("outside a database transaction");
    }

    @Test
    void concurrentWorkersSendOnceWhileHttpWaitHoldsNoReportLock() throws Exception {
        var report = report(true); var job = reserve(report); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        RESPONDER.set((path, request) -> { entered.countDown(); await(release); return applied(job.input().command()); });
        var threads = Executors.newFixedThreadPool(3);
        try {
            var first = threads.submit(worker::poll); assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            threads.submit(() -> tx().executeWithoutResult(transaction -> reports.lock("demo", report.id()))).get(2, TimeUnit.SECONDS);
            threads.submit(worker::poll).get(2, TimeUnit.SECONDS);
            assertThatThrownBy(() -> reserve(report)).isInstanceOf(DomainException.class).hasMessageContaining("reconciled");
            assertThat(reload(job).attempts()).isEqualTo(1); assertThat(WRITES.get()).isEqualTo(1);
            release.countDown(); first.get(5, TimeUnit.SECONDS);
            assertThat(reload(job).status()).isEqualTo(BudgetOperation.Status.APPLIED);
        } finally { release.countDown(); threads.shutdownNow(); }
    }

    @Test
    void actualHttpTimeoutQueriesOriginalOperationAndNeverResendsBlindly() {
        var report = report(true); var job = reserve(report); var release = new CountDownLatch(1);
        configuration.getTenants().get("demo").setTimeoutSeconds(1);
        RESPONDER.set((path, request) -> { await(release); return applied(job.input().command()); });
        try {
            worker.poll(); var unknown = reload(job);
            assertThat(unknown.status()).isEqualTo(BudgetOperation.Status.UNKNOWN); assertThat(unknown.failure()).isEqualTo(BudgetOperation.Failure.TIMEOUT);
            assertThat(occupation(report).pendingOperationId()).isEqualTo(job.input().command().id());
            assertThatThrownBy(() -> tx().executeWithoutResult(transaction -> execution.finalizeOccupation("demo", report.id(), BudgetCommand.Action.RELEASE, now())))
                    .isInstanceOf(DomainException.class).hasMessageContaining("reconciled");
            RESPONDER.set((path, request) -> applied(job.input().command()));
            var query = execution.claim("demo", job.input().command().id(), unknown.nextAttemptAt());
            assertThat(query.status()).isEqualTo(BudgetOperation.Status.QUERYING);
            execution.finish(query, gateway.query(query.input().targetDigest(), query.input().command()), query.updatedAt().plusSeconds(1));
            assertThat(reload(job).status()).isEqualTo(BudgetOperation.Status.APPLIED);
            assertThat(occupation(report).frozenFor(position(report))).isTrue();
            assertThat(WRITES.get()).isEqualTo(1); assertThat(QUERIES.get()).isEqualTo(1);
        } finally { release.countDown(); }
    }

    @Test
    void abandonedClaimQueriesBeforeRetryAndLateExecutorCannotOverwriteRecoveredResult() {
        var report = report(true); var job = reserve(report); var id = job.input().command().id();
        var abandoned = execution.claim("demo", id, job.createdAt());
        assertThat(execution.claim("demo", id, abandoned.leaseUntil())).isNull();
        var unknown = reload(job); assertThat(unknown.failure()).isEqualTo(BudgetOperation.Failure.LEASE_EXPIRED);
        var query = execution.claim("demo", id, unknown.nextAttemptAt());
        RESPONDER.set((path, request) -> new BudgetObservation(id, job.input().command().digest(), BudgetObservation.Status.NOT_FOUND, null, null, null, null));
        execution.finish(query, gateway.query(target(), job.input().command()), query.updatedAt().plusSeconds(1));
        var queued = reload(job); assertThat(queued.status()).isEqualTo(BudgetOperation.Status.QUEUED);
        var retry = execution.claim("demo", id, queued.nextAttemptAt());
        RESPONDER.set((path, request) -> applied(job.input().command()));
        execution.finish(retry, gateway.execute(target(), retry.input().command()), retry.updatedAt().plusSeconds(1));
        var confirmed = reload(job);
        execution.finish(abandoned, rejected(abandoned, BudgetObservation.Rejection.BUDGET_INSUFFICIENT), retry.updatedAt().plusSeconds(2));
        assertThat(reload(job)).isEqualTo(confirmed); assertThat(revisions(job)).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L);
        assertThat(WRITES.get()).isEqualTo(1); assertThat(QUERIES.get()).isEqualTo(1);
        assertThat(retry.input()).isEqualTo(abandoned.input());
    }

    @Test
    void failedAdjustmentRetainsOldFrozenLedgerUntilNewOperationConfirmsReducedVersion() {
        var report = report(true); reserve(report); worker.poll(); var initial = occupation(report).confirmed();
        report.reduce(2, List.of(new ExpenseReport.Reduction(1, money("80"), money("0"))), "finance", "INELIGIBLE", "合成核减", now());
        var adjustment = tx().execute(transaction -> {
            reports.update(report, 2, "finance", "SYNTHETIC_REDUCE");
            return execution.reserve("demo", report.id(), 3, DATE, target(), now());
        });
        RESPONDER.set((path, request) -> rejected(adjustment, BudgetObservation.Rejection.BUDGET_INSUFFICIENT).requireValue());
        worker.poll(); assertThat(reload(adjustment).status()).isEqualTo(BudgetOperation.Status.REJECTED);
        assertThat(occupation(report).confirmed()).isEqualTo(initial); assertThat(occupation(report).frozenFor(position(report))).isFalse();
        var retry = reserve(report); RESPONDER.set((path, request) -> applied(retry.input().command())); worker.poll();
        assertThat(occupation(report).frozenFor(position(report))).isTrue(); assertThat(occupation(report).confirmed().revision()).isEqualTo(2);
        assertThat(retry.input().command().id()).isNotEqualTo(adjustment.input().command().id());
        assertThat(retry.input().command().expected()).isEqualTo(adjustment.input().command().expected());
    }

    @Test
    void releaseAndConsumeUseLastConfirmedPositionAndSealTheLedger() {
        noticePeople();
        for (var action : List.of(BudgetCommand.Action.RELEASE, BudgetCommand.Action.CONSUME)) {
            var report = report(true); reserve(report); worker.poll(); var held = occupation(report).confirmed();
            var last = tx().execute(transaction -> execution.finalizeOccupation("demo", report.id(), action, now())); worker.poll();
            assertThat(last.input().command().position()).isEqualTo(held.position());
            assertThat(occupation(report).status().name()).isEqualTo(action == BudgetCommand.Action.RELEASE ? "RELEASED" : "CONSUMED");
            assertThat(occupation(report).confirmed().revision()).isEqualTo(2);
            assertThat(noticeRecipients(last.input().command().id(), "APPLIED")).containsExactly("alice");
            assertThatThrownBy(() -> reserve(report)).isInstanceOf(DomainException.class);
        }
    }

    @Test
    void releasedLedgerRefreezesANewRoundWithTheOriginalReleaseReceipt() {
        var report = report(true); reserve(report); worker.poll();
        tx().executeWithoutResult(transaction -> execution.finalizeOccupation("demo", report.id(), BudgetCommand.Action.RELEASE, now()));
        worker.poll(); var released = occupation(report).confirmed();
        long previous = report.version(); freeze(report); reports.update(report, previous, "alice", "SYNTHETIC_RESUBMIT");
        var next = reserve(report);
        assertThat(next.input().command().action()).isEqualTo(BudgetCommand.Action.FREEZE);
        assertThat(next.input().command().expected()).isEqualTo(released.expected());
        assertThat(occupation(report).status()).isEqualTo(BudgetOccupation.Status.RELEASED);
        assertThat(occupation(report).frozenFor(position(report))).isFalse();
        worker.poll();
        assertThat(occupation(report).frozenFor(position(report))).isTrue();
        assertThat(occupation(report).confirmed().revision()).isEqualTo(3);
        assertThat(operations.find("demo", next.input().command().id()).orElseThrow().status()).isEqualTo(BudgetOperation.Status.APPLIED);
        assertThat(WRITES.get()).isEqualTo(3);
    }

    @Test
    void destinationChangeRetainsUnknownOperationAndDatabaseProtectsImmutableInputAndActiveUniqueness() {
        var report = report(true); var job = reserve(report);
        configuration.getTenants().get("demo").setEndpoint(ENDPOINT + "/changed"); worker.poll();
        var unknown = reload(job); assertThat(unknown.failure()).isEqualTo(BudgetOperation.Failure.TARGET_CHANGED);
        assertThat(WRITES.get()).isZero(); assertThat(QUERIES.get()).isZero();
        assertThat(occupation(report).pendingOperationId()).isEqualTo(job.input().command().id());
        var original = job.input().command();
        var duplicate = new BudgetCommand(UUID.randomUUID(), "demo", original.action(), original.position(), null);
        assertThatThrownBy(() -> tx().executeWithoutResult(transaction -> operations.create(BudgetOperation.queue(new BudgetOperation.Input(duplicate, job.input().targetDigest()), now()))))
                .isInstanceOf(DataIntegrityViolationException.class);
        var altered = new BudgetOperation(new BudgetOperation.Input(original, "b".repeat(64)), unknown.version() + 1, unknown.status(), unknown.attempts(),
                unknown.createdAt(), unknown.updatedAt(), unknown.nextAttemptAt(), null, unknown.observation(), unknown.failure());
        assertThatThrownBy(() -> tx().executeWithoutResult(transaction -> operations.update(altered))).isInstanceOf(DomainException.class);
        assertThat(reload(job)).isEqualTo(unknown);
        jdbc.update("UPDATE budget_operation SET command_digest=? WHERE tenant_id='demo' AND id=?", "c".repeat(64), original.id().toString());
        assertThatThrownBy(() -> reload(job)).isInstanceOf(IllegalStateException.class).hasMessageContaining("inconsistent");
    }

    private ExpenseReport report(boolean frozen) {
        UUID id = UUID.randomUUID(); fixtures.add(id);
        var app = Application.draftBusiness(UUID.randomUUID(), "demo", "SYNTHETIC-" + UUID.randomUUID(), "fixture", 1, "alice", "预算持久恢复夹具", Map.of(), null, null, null,
                new BusinessReference(BusinessReference.Type.EXPENSE, id)); applications.save(app);
        var line = new ExpenseLine(1, "DAILY", DATE, null, "SH", BigDecimal.ONE, ExpenseLine.Unit.ITEM, money("100"), money("0"), List.of(), null,
                List.of(new CostAllocation("IT", null, money("100"))), "合成明细", null);
        var report = ExpenseReport.draft(id, "demo", app.id(), "alice", new ExpenseContent(ENTITY, ExpenseContent.Type.DAILY, "合成预算", List.of(line), List.of()));
        reports.create(report, "alice");
        if (frozen) { freeze(report); reports.update(report, 1, "alice", "SYNTHETIC_FREEZE"); }
        return report;
    }
    private void freeze(ExpenseReport report) {
        var assessment = new ExpenseAssessment(new ExpenseExchangeRate("CNY", "CNY", BigDecimal.ONE, "synthetic-rate", DATE),
                new ExpensePolicySnapshot(UUID.randomUUID(), 1, money("100"), money("100"), ExpensePolicySnapshot.Decision.WITHIN_LIMIT, "synthetic-tax", "synthetic-policy"), money("0"));
        report.freeze(report.version(), report.rounds().size() + 1, "CNY", new EmployeeAccountSnapshot(ENTITY, "alice", "synthetic-account", "****1234", "a".repeat(64), "v1"), Map.of(1, assessment), "alice", now());
    }
    private BudgetOperation reserve(ExpenseReport report) { return tx().execute(transaction -> execution.reserve("demo", report.id(), report.version(), DATE, target(), now())); }
    private BudgetOccupation occupation(ExpenseReport report) { return occupations.find("demo", report.id()).orElseThrow(); }
    private BudgetPrecheckPort.Request position(ExpenseReport report) { return BudgetPrecheckPort.Request.fromCurrent(report, DATE); }
    private BudgetOperation reload(BudgetOperation operation) { return operations.find("demo", operation.input().command().id()).orElseThrow(); }
    private List<Long> revisions(BudgetOperation operation) { return jdbc.queryForList(
                "SELECT version FROM budget_operation_revision WHERE tenant_id='demo' AND"
                        + " operation_id=? ORDER BY version", Long.class, operation.input().command().id().toString()); }
    private TransactionTemplate tx() { return new TransactionTemplate(transactions); }
    private String target() { return configuration.destination("demo").orElseThrow().digest("demo"); }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private static Money money(String amount) { return new Money(new BigDecimal(amount), "CNY"); }
    private static BudgetObservation applied(BudgetCommand command) {
        long revision = command.expected() == null ? 1 : command.expected().revision() + 1;
        return new BudgetObservation(command.id(), command.digest(), BudgetObservation.Status.APPLIED, revision, "synthetic-v" + revision, now(), null);
    }
    private static FinanceResult<BudgetObservation> rejected(BudgetOperation operation, BudgetObservation.Rejection reason) {
        var command = operation.input().command();
        return new FinanceResult.Success<>(new BudgetObservation(command.id(), command.digest(), BudgetObservation.Status.REJECTED, null, null, null, reason));
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic gateway wait expired"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Synthetic gateway interrupted"); }
    }
    private static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(HTTP_THREADS);
            server.createContext("/finance/", exchange -> {
                TRACES.add(exchange.getRequestHeaders().getFirst("X-Trace-Id"));
                var path = exchange.getRequestURI().getPath(); if (path.endsWith("budget-command")) WRITES.incrementAndGet(); else QUERIES.incrementAndGet();
                LAST_KEY.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                var request = wire.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonNode.class);
                var value = RESPONDER.get().apply(path, request);
                byte[] body = wire.write(Map.of("contractVersion", 1, "tenantId", request.path("tenantId").asText(), "requestId", request.path("requestId").asText(),
                        "outcome", "SUCCESS", "data", value)).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, 0);
                try { exchange.getResponseBody().write(body); } finally { exchange.close(); }
            }); server.start(); return server;
        } catch (Exception failed) { throw new IllegalStateException(failed); }
    }
}
