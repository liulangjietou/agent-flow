package io.agentflow.procurement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.approval.JdbcApplicationRepository;
import io.agentflow.expense.FinanceJsonConfiguration;
import io.agentflow.expense.InvoiceKey;
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.finance.Money;
import io.agentflow.organization.InitiatorContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static io.agentflow.procurement.ProcurementPaymentCheck.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 实际数据库验证采购绑定、原始修订、持久预检租约及多表失败回滚。
 * @author owlzhangfq@gmail.com
 */
class ProcurementPersistenceTest {
    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    private final String tenant = "procurement-" + UUID.randomUUID();
    private final UUID entity = UUID.randomUUID();
    private final JsonUtil json = new JsonUtil(new ObjectMapper().registerModule(new JavaTimeModule())
            .registerModule(new FinanceJsonConfiguration().financeMoneyModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private JdbcProcurementPaymentRepository requests;
    private JdbcProcurementPaymentCheckRepository checks;
    private JdbcProcurementPayableReservationRepository reservations;
    private ProcurementPayableReservations reservationService;
    private JdbcApplicationRepository applications;

    @BeforeEach void database() {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_PROCUREMENT_PERSISTENCE_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_PROCUREMENT_PERSISTENCE_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_PROCUREMENT_PERSISTENCE_PASSWORD", ""));
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source); tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        requests = new JdbcProcurementPaymentRepository(jdbc, json); checks = new JdbcProcurementPaymentCheckRepository(jdbc, json);
        reservations = new JdbcProcurementPayableReservationRepository(jdbc, json, requests, new JdbcProcurementInvoiceClaims(jdbc), new SupplierPayableReturnGuard(jdbc));
        reservationService = new ProcurementPayableReservations(reservations); applications = new JdbcApplicationRepository(jdbc, json);
    }

    @Test void currentStateAndOriginalRevisionRestoreWithoutCrossTenantOrWrongBusinessBinding() {
        var request = create(); var before = request.state();
        assertThat(requests.find(tenant, request.id()).orElseThrow().state()).isEqualTo(before);
        assertThat(requests.find("foreign", request.id())).isEmpty();
        tx.executeWithoutResult(status -> { requests.lock(tenant, request.id()); request.revise(1, content("60")); requests.update(request, 1, "alice", "REVISE"); });
        assertThat(requests.find(tenant, request.id()).orElseThrow().content().amount()).isEqualTo(money("60"));
        assertThat(json.read(jdbc.queryForObject("SELECT state_json FROM procurement_payment_revision WHERE tenant_id=? AND request_id=? AND request_version=1", String.class, tenant, request.id().toString()), ProcurementPaymentRequest.State.class)).isEqualTo(before);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> requests.update(request, 1, "alice", "REVISE"))).isInstanceOf(DomainException.class);
        var fake = ProcurementPaymentRequest.draft(UUID.randomUUID(), tenant, request.applicationId(), "alice", content("50"));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> requests.create(fake, "alice"))).isInstanceOf(DomainException.class);
    }

    @Test void revisionAppendFailureRollsBackPrimaryStateAndPreservesTheOriginalSource() {
        var request = create(); var before = request.state(); request.revise(1, content("60"));
        jdbc.update("INSERT INTO procurement_payment_revision(tenant_id,request_id,request_version,actor_id,operation,state_json) VALUES(?,?,2,'alice','SYNTHETIC_COLLISION',?)", tenant, request.id().toString(), json.write(request.state()));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> requests.update(request, 1, "alice", "REVISE"))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(requests.find(tenant, request.id()).orElseThrow().state()).isEqualTo(before);
    }

    @Test void queuedClaimReadyAndHistoricalAttemptsSurviveRepositoryReconstruction() {
        var request = create(); var job = queue(input(request, 1), NOW);
        tx.executeWithoutResult(status -> checks.create(job));
        assertThat(checks.active(tenant, request.id())).isTrue();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> checks.create(queue(input(request, 2), NOW)))).isInstanceOf(DomainException.class);
        var running = job.start(NOW, NOW.plusSeconds(60)); tx.executeWithoutResult(status -> checks.update(running));
        var done = running.finish(Result.ready(evidence(request)), NOW.plusSeconds(2)); tx.executeWithoutResult(status -> checks.update(done));
        var reopened = new JdbcProcurementPaymentCheckRepository(jdbc, json);
        assertThat(reopened.find(tenant, job.input().id()).orElseThrow()).isEqualTo(done);
        assertThat(reopened.active(tenant, request.id())).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM procurement_payment_check_revision WHERE tenant_id=? AND job_id=?", Integer.class, tenant, job.input().id().toString())).isEqualTo(3);
        var next = queue(input(request, 2), NOW.plusSeconds(3)); tx.executeWithoutResult(status -> reopened.create(next));
        assertThat(reopened.latestAttempt(tenant, request.id())).isEqualTo(2); assertThat(reopened.latestId(tenant, request.id())).contains(next.input().id());
        assertThat(reopened.find(tenant, done.input().id())).contains(done); assertThat(reopened.find("foreign", job.input().id())).isEmpty();
    }

    @Test void wrongDraftAndForgedLeaseCannotBeSavedAndDatabaseIdentityTamperingIsDetected() {
        var request = create(); var input = input(request, 1);
        var altered = new Input(input.id(), tenant, request.id(), request.applicationId(), "alice", 1, 1, 1, 1, input.initiator(), input.targetDigest(), content("60"));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> checks.create(queue(altered, NOW)))).isInstanceOf(DomainException.class);
        var queued = queue(input, NOW); tx.executeWithoutResult(status -> checks.create(queued));
        var running = queued.start(NOW, NOW.plusSeconds(30)); tx.executeWithoutResult(status -> checks.update(running));
        var forged = new ProcurementPaymentCheck(input, 3, Status.UNAVAILABLE, NOW, NOW, NOW.plusSeconds(60), NOW.plusSeconds(5), Result.unavailable("TIMEOUT"));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> checks.update(forged))).isInstanceOf(DomainException.class);
        assertThat(checks.find(tenant, input.id())).contains(running);
        var timeout = running.finish(Result.ready(evidence(request)), NOW.plusSeconds(30)); tx.executeWithoutResult(status -> checks.update(timeout));
        assertThat(checks.find(tenant, input.id()).orElseThrow().result().code()).isEqualTo("TIMEOUT");
        jdbc.update("UPDATE procurement_payment_check_job SET application_version=2 WHERE tenant_id=? AND id=?", tenant, input.id().toString());
        assertThatThrownBy(() -> checks.find(tenant, input.id())).isInstanceOf(IllegalStateException.class);
    }

    @Test void concurrentRequestsCannotPromiseTheSameOutstandingPayable() throws Exception {
        var first = create(); var second = create(); freeze(first); freeze(second);
        var start = new CountDownLatch(1); var executor = Executors.newFixedThreadPool(2);
        try {
            var results = List.of(first, second).stream().map(request -> executor.submit(() -> {
                if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Concurrent start timed out");
                try {
                    tx.executeWithoutResult(status -> { requests.lock(tenant, request.id()); hold(request); });
                    return "HELD";
                } catch (DomainException rejected) { return rejected.code(); }
            })).toList();
            start.countDown();
            assertThat(List.of(results.get(0).get(10, TimeUnit.SECONDS), results.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("HELD", "PROCUREMENT_PAYABLE_OCCUPIED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM procurement_payable_reservation WHERE tenant_id=?", Integer.class, tenant)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM procurement_payable_reservation_revision WHERE tenant_id=?", Integer.class, tenant)).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }

    @Test void returnedWithdrawnAndApprovedRequestsKeepHoldUntilAnActualStoppedApplication() {
        var request = create(); freeze(request); tx.executeWithoutResult(status -> hold(request));
        var original = reservations.active(tenant, request.id()).orElseThrow();
        for (String state : List.of("IN_APPROVAL", "RETURNED", "WITHDRAWN", "APPROVED")) {
            jdbc.update("UPDATE approval_application SET status=? WHERE tenant_id=? AND id=?", state, tenant, request.applicationId().toString());
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> reservationService.releaseStopped(applications.findById(tenant, request.applicationId()).orElseThrow(), "alice", NOW.plusSeconds(3))))
                    .isInstanceOf(DomainException.class);
            assertThat(reservations.active(tenant, request.id())).contains(original);
        }
        jdbc.update("UPDATE approval_application SET status='CANCELLED' WHERE tenant_id=? AND id=?", tenant, request.applicationId().toString());
        tx.executeWithoutResult(status -> reservationService.releaseStopped(applications.findById(tenant, request.applicationId()).orElseThrow(), "alice", NOW.plusSeconds(3)));
        tx.executeWithoutResult(status -> reservationService.releaseStopped(applications.findById(tenant, request.applicationId()).orElseThrow(), "alice", NOW.plusSeconds(4)));
        assertThat(reservations.active(tenant, request.id())).isEmpty();
        var history = reservations.history(tenant, request.id()); assertThat(history).hasSize(1);
        assertThat(history.get(0).source()).isEqualTo(original.source()); assertThat(history.get(0).release().reason()).isEqualTo(ProcurementPayableReservation.ReleaseReason.CANCELLED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM procurement_payable_reservation_revision WHERE tenant_id=?", Integer.class, tenant)).isEqualTo(2);
        var next = create(); freeze(next); tx.executeWithoutResult(status -> hold(next));
        assertThat(reservations.active(tenant, next.id())).isPresent();
    }

    @Test void failedResubmissionRestoresOldHoldAndEntireFinancialRevision() {
        var first = create(); freeze(first); tx.executeWithoutResult(status -> hold(first)); var before = requests.find(tenant, first.id()).orElseThrow().state();
        var other = create(); other.revise(1, new ProcurementPaymentContent(entity, "采购付款", "另笔应付", "supplier", "other-payable", money("70")));
        tx.executeWithoutResult(status -> requests.update(other, 1, "alice", "REVISE")); freeze(other); tx.executeWithoutResult(status -> hold(other));
        var originalHold = reservations.active(tenant, first.id()).orElseThrow();
        jdbc.update("UPDATE approval_application SET status='WITHDRAWN' WHERE tenant_id=? AND id=?", tenant, first.applicationId().toString());
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            requests.lock(tenant, first.id()); first.revise(2, other.content()); requests.update(first, 2, "alice", "REVISE");
            freeze(first); hold(first);
        })).isInstanceOf(DomainException.class);
        assertThat(requests.find(tenant, first.id()).orElseThrow().state()).isEqualTo(before);
        assertThat(reservations.active(tenant, first.id())).contains(originalHold);
        assertThat(reservations.history(tenant, first.id())).containsExactly(originalHold);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM procurement_payable_reservation_revision WHERE tenant_id=?", Integer.class, tenant)).isEqualTo(2);
    }

    @Test void resubmissionReplacesOnlyItsOriginalHoldAndPreservesBothRounds() {
        var request = create(); freeze(request); tx.executeWithoutResult(status -> hold(request)); var first = reservations.active(tenant, request.id()).orElseThrow();
        jdbc.update("UPDATE approval_application SET status='WITHDRAWN' WHERE tenant_id=? AND id=?", tenant, request.applicationId().toString());
        tx.executeWithoutResult(status -> {
            request.revise(2, content("60")); requests.update(request, 2, "alice", "REVISE"); freeze(request); hold(request); hold(request);
        });
        var history = reservations.history(tenant, request.id()); assertThat(history).hasSize(2);
        assertThat(history.get(0).source()).isEqualTo(first.source()); assertThat(history.get(0).release().reason()).isEqualTo(ProcurementPayableReservation.ReleaseReason.RESUBMITTED);
        assertThat(history.get(1).source().round().roundNo()).isEqualTo(2); assertThat(history.get(1).source().round().content().amount()).isEqualTo(money("60"));
        assertThat(history.get(1).held()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM procurement_payable_reservation_revision WHERE tenant_id=?", Integer.class, tenant)).isEqualTo(3);
    }

    private ProcurementPaymentRequest create() {
        var request = ProcurementPaymentRequest.draft(UUID.randomUUID(), tenant, UUID.randomUUID(), "alice", content("70"));
        tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id) VALUES(?,?,?,'fixture',1,'alice','采购付款','{}','DRAFT',1,1,'PROCUREMENT_PAYMENT',?)",
                    request.applicationId().toString(), tenant, UUID.randomUUID().toString(), request.id().toString());
            requests.create(request, "alice");
        });
        return request;
    }
    private ProcurementPaymentContent content(String amount) { return new ProcurementPaymentContent(entity, "采购付款", "已验收货物付款", "supplier", "payable", money(amount)); }
    private Input input(ProcurementPaymentRequest request, long attempt) {
        return new Input(UUID.randomUUID(), tenant, request.id(), request.applicationId(), "alice", 1, request.version(), request.rounds().size() + 1, attempt, initiator(), "a".repeat(64), request.content());
    }
    private void freeze(ProcurementPaymentRequest request) {
        tx.executeWithoutResult(status -> {
            long version = request.version(); var evidence = evidence(request);
            request.freeze(version, request.rounds().size() + 1, evidence.catalog(), "a".repeat(64), evidence.preview().payable(), initiator(), NOW.plusSeconds(1));
            requests.update(request, version, "alice", "SUBMIT");
        });
    }
    private void hold(ProcurementPaymentRequest request) {
        reservationService.reserve(applications.findById(tenant, request.applicationId()).orElseThrow(), request, "alice", NOW.plusSeconds(2));
    }
    private InitiatorContext initiator() { return new InitiatorContext(UUID.randomUUID(), UUID.randomUUID(), "alice", 1, entity, "法人", UUID.randomUUID(), "部门", UUID.randomUUID(), "岗位"); }
    private Evidence evidence(ProcurementPaymentRequest original) {
        var catalog = new FinanceCatalog("alice", "v1", NOW.plusSeconds(300), List.of(new FinanceCatalog.LegalEntity(entity, "法人", "CNY", false, "v1", "Asia/Shanghai")), List.of(), List.of(), List.of(), List.of());
        var line = new ProcurementPayablePort.MatchedLine(1, 1, "receipt", new InvoiceKey(InvoiceKey.Type.DIGITAL, null, String.format("%020d", Integer.toUnsignedLong(original.content().payableReference().hashCode()))), 1, "b".repeat(64), "verified", "件",
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, money("100"), money("100"), money("100"), money("6"));
        var payable = new ProcurementPayablePort.Payable(original.content().payableRequest("alice"), "v1", NOW, NOW.plusSeconds(300), "供应商",
                new SupplierAccountSnapshot(entity, "supplier", "account", "****1234", "c".repeat(64), "v1"), "contract", "order", "matching", "accrual", "budget", LocalDate.parse("2026-10-01"), money("100"), money("30"), List.of(line));
        var preview = ProcurementPaymentRequest.restore(original.state()); preview.freeze(original.version(), original.rounds().size() + 1, catalog, "a".repeat(64), payable, initiator(), NOW.plusSeconds(1));
        return new Evidence(catalog, preview.currentRound(), NOW.plusSeconds(300));
    }
    private static Money money(String amount) { return new Money(new BigDecimal(amount), "CNY"); }
}
