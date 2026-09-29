package io.agentflow.procurement;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentflow.approval.JdbcApplicationRepository;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.FinanceJsonConfiguration;
import io.agentflow.expense.InvoiceKey;
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.finance.FinanceResult;
import io.agentflow.finance.Money;
import io.agentflow.finance.PaymentPersonnel;
import io.agentflow.organization.InitiatorContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 真实数据库验证原应付读取、财务单次授权与队列的事务边界，ERP 使用合成响应。
 * @author owlzhangfq@gmail.com
 */
class SupplierPayableReviewPersistenceTest {
    private final String tenant = "review-" + UUID.randomUUID();
    private final UUID entity = UUID.randomUUID();
    private final Instant now = Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.MICROS);
    private final JsonUtil json = new JsonUtil(new ObjectMapper().registerModule(new JavaTimeModule()).registerModule(new FinanceJsonConfiguration().financeMoneyModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).setSerializationInclusion(JsonInclude.Include.NON_NULL));
    private final AtomicBoolean eligible = new AtomicBoolean(true);
    private JdbcTemplate jdbc;
    private DriverManagerDataSource dataSource;
    private String schema;
    private TransactionTemplate tx;
    private DataSourceTransactionManager manager;
    private JdbcProcurementPaymentRepository requests;
    private JdbcProcurementPayableReservationRepository reservations;
    private ApprovedSupplierPaymentSources sources;
    private JdbcSupplierPaymentAuthorizationRepository authorizations;
    private JdbcSupplierPayableHoldRepository operations;
    private JdbcSupplierPayableReviewRepository reviews;
    private SupplierPayableHoldService holds;
    private SupplierPayableReviewService service;

    @BeforeEach void database() { database(null); }

    private void database(String target) {
        dataSource = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_SUPPLIER_HOLD_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_SUPPLIER_HOLD_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_SUPPLIER_HOLD_PASSWORD", ""));
        schema = "supplier_review_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(dataSource).execute("CREATE SCHEMA \"" + schema + "\""); dataSource.setSchema(schema);
        var migration = Flyway.configure().dataSource(dataSource).defaultSchema(schema); if (target != null) migration.target(target);
        migration.load().migrate(); jdbc = new JdbcTemplate(dataSource); manager = new DataSourceTransactionManager(dataSource); tx = new TransactionTemplate(manager);
        requests = new JdbcProcurementPaymentRepository(jdbc, json);
        reservations = new JdbcProcurementPayableReservationRepository(jdbc, json, requests, new JdbcProcurementInvoiceClaims(jdbc));
        sources = new ApprovedSupplierPaymentSources(new JdbcApplicationRepository(jdbc, json), requests, reservations);
        authorizations = new JdbcSupplierPaymentAuthorizationRepository(jdbc, json, sources); operations = new JdbcSupplierPayableHoldRepository(jdbc, json, authorizations);
        reviews = new JdbcSupplierPayableReviewRepository(jdbc, json, sources, authorizations);
        var personnel = mock(PaymentPersonnel.class);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            if (!eligible.get()) throw new DomainException("PAYMENT_ACTOR_UNAVAILABLE", "Synthetic inactive appointment");
            return null;
        }).when(personnel).requireEligible(eq(tenant), eq("finance"), eq(entity));
        holds = proxy(new SupplierPayableHoldService(sources, authorizations, operations, personnel, 30));
        service = proxy(new SupplierPayableReviewService(sources, reviews, authorizations, holds, personnel, 30));
    }

    @Test void readAndAuthorizationRestoreExactOriginalFactsAndConsumptionSurvivesRestart() {
        var source = approved(); var queued = register(source); var ready = ready(queued, payable(source, at()));
        assertThat(reviews.find("other", queued.input().id())).isEmpty();
        assertThat(reviews.latest(tenant, requestId(source), "other-finance")).isEmpty();
        assertThat(reviews.latest(tenant, requestId(source), "finance")).contains(ready);
        assertThat(count("supplier_payment_authorization")).isZero(); assertThat(count("supplier_payable_hold_operation")).isZero();
        var hold = authorize(ready); var authorization = hold.command().authorization();
        var reopened = new JdbcSupplierPayableReviewRepository(jdbc, json, sources, authorizations);
        var consumed = reopened.find(tenant, queued.input().id()).orElseThrow();
        assertThat(consumed.status()).isEqualTo(SupplierPayableReview.Status.CONSUMED); assertThat(consumed.supports(authorization)).isTrue();
        assertThat(reopened.forAuthorization(tenant, authorization.id())).contains(consumed);
        assertThat(authorization.source()).isEqualTo(source); assertThat(authorization.payable()).isEqualTo(ready.payable());
        assertThat(operations.find(tenant, authorization.id())).contains(hold);
        assertThat(jdbc.queryForList("SELECT state_json FROM supplier_payable_review_revision WHERE tenant_id=? ORDER BY version", String.class, tenant))
                .hasSize(4).first().isEqualTo(json.write(queued));
        fails("SUPPLIER_PAYABLE_REVIEW_UNAVAILABLE", () -> authorize(ready));
        tx.executeWithoutResult(status -> holds.retire(tenant, authorization.id(), hold.version(), "finance", hold.updatedAt()));
        fails("SUPPLIER_PAYABLE_REVIEW_UNAVAILABLE", () -> authorize(consumed));
        assertThat(register(source).input().id()).isNotEqualTo(queued.input().id());
    }

    @Test void pendingReadAndSimultaneousConsumptionCannotProduceDuplicateAuthorizations() throws Exception {
        var source = approved(); var queued = register(source);
        fails("SUPPLIER_PAYABLE_REVIEW_PENDING", () -> register(source));
        var ready = ready(queued, payable(source, at())); var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { start.await(); return outcome(ready); }); var second = pool.submit(() -> { start.await(); return outcome(ready); }); start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder("OK", "SUPPLIER_PAYABLE_REVIEW_UNAVAILABLE");
        } finally { pool.shutdownNow(); }
        assertThat(count("supplier_payment_authorization")).isEqualTo(1); assertThat(count("supplier_payable_hold_operation")).isEqualTo(1);
        assertThat(count("supplier_payable_review_revision")).isEqualTo(4);
        fails("SUPPLIER_PAYMENT_ALREADY_AUTHORIZED", () -> register(source));
    }

    @Test void consumptionFailureRollsBackAuthorizationQueueAndEvidenceTogether() {
        var ready = ready(register(approved()));
        jdbc.execute("ALTER TABLE supplier_payable_review_revision ADD CONSTRAINT fixture_no_consumption CHECK(version<4)");
        assertThatThrownBy(() -> authorize(ready)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(reviews.find(tenant, ready.input().id())).contains(ready);
        assertThat(count("supplier_payment_authorization")).isZero(); assertThat(count("supplier_payable_hold_operation")).isZero(); assertThat(count("supplier_payable_hold_revision")).isZero();
        jdbc.execute("ALTER TABLE supplier_payable_review_revision DROP CONSTRAINT fixture_no_consumption");
        assertThat(authorize(ready).status()).isEqualTo(SupplierPayableHoldOperation.Status.QUEUED);
    }

    @Test void transactionRequesterAndOriginalObservationDeadlineAreMandatory() {
        var source = approved();
        assertThatThrownBy(() -> service.register(source, "finance", at())).hasMessageContaining("No existing transaction");
        var ready = ready(register(source), payable(source, at().minusSeconds(295)));
        assertThatThrownBy(() -> service.authorize(tenant, ready.input().id(), ready.version(), "finance", at().plusSeconds(1))).hasMessageContaining("No existing transaction");
        fails("SUPPLIER_PAYABLE_REVIEW_UNAVAILABLE", () -> tx.execute(status -> service.authorize(tenant, ready.input().id(), ready.version(), "other-finance", at().plusSeconds(1))));
        fails("SUPPLIER_PAYABLE_REVIEW_UNAVAILABLE", () -> tx.execute(status -> service.authorize(tenant, ready.input().id(), ready.version(), "finance", at().plusSeconds(5))));
        assertThat(count("supplier_payment_authorization")).isZero(); assertThat(reviews.find(tenant, ready.input().id())).contains(ready);
    }

    @Test void originalApprovalPersonnelAndActiveAuthorizationAreRecheckedBeforeConsumption() {
        var first = ready(register(approved())); eligible.set(false);
        fails("PAYMENT_ACTOR_UNAVAILABLE", () -> authorize(first)); eligible.set(true);
        jdbc.update("UPDATE approval_application SET payload_json='{}' WHERE tenant_id=? AND id=?", tenant, first.input().source().reservation().source().applicationId().toString());
        fails("PROCUREMENT_PAYMENT_SOURCE_CHANGED", () -> authorize(first));
        var next = ready(register(approved())); var competing = ready(register(next.input().source())); authorize(competing);
        fails("SUPPLIER_PAYMENT_ALREADY_AUTHORIZED", () -> authorize(next));
        assertThat(reviews.find(tenant, first.input().id())).contains(first); assertThat(reviews.find(tenant, next.input().id())).contains(next);
        assertThat(count("supplier_payment_authorization")).isEqualTo(1);
    }

    @Test void oneReaderClaimsAndLateResultsCannotOverwriteLeaseRecovery() throws Exception {
        var queued = register(approved()); var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2); SupplierPayableReview claimed;
        try {
            var first = pool.submit(() -> { start.await(); return service.claim(tenant, queued.input().id(), at()); });
            var second = pool.submit(() -> { start.await(); return service.claim(tenant, queued.input().id(), at()); }); start.countDown();
            var a = first.get(15, TimeUnit.SECONDS); var b = second.get(15, TimeUnit.SECONDS); assertThat(a == null ^ b == null).isTrue(); claimed = a == null ? b : a;
        } finally { pool.shutdownNow(); }
        assertThat(service.claim(tenant, claimed.input().id(), claimed.leaseUntil())).isNull();
        var recovered = service.claim(tenant, claimed.input().id(), claimed.leaseUntil()); assertThat(recovered.attempts()).isEqualTo(2); assertThat(recovered.input()).isEqualTo(queued.input());
        service.finish(claimed, new FinanceResult.Success<>(payable(claimed.input().source(), claimed.leaseUntil())), claimed.leaseUntil());
        assertThat(reviews.find(tenant, claimed.input().id())).contains(recovered);
        service.finish(recovered, new FinanceResult.Success<>(payable(recovered.input().source(), recovered.updatedAt())), recovered.updatedAt());
        assertThat(reviews.find(tenant, claimed.input().id()).orElseThrow().status()).isEqualTo(SupplierPayableReview.Status.READY);
    }

    @Test void readStopsIfPersonnelOrApprovedSourceChangesBeforeClaimOrCompletion() {
        var queued = register(approved()); eligible.set(false);
        assertThat(service.claim(tenant, queued.input().id(), at())).isNull(); assertVoided(queued); eligible.set(true);
        var running = service.claim(tenant, register(approved()).input().id(), at());
        jdbc.update("UPDATE approval_application SET status='CANCELLED' WHERE tenant_id=? AND id=?", tenant, running.input().source().reservation().source().applicationId().toString());
        service.finish(running, new FinanceResult.Success<>(payable(running.input().source(), at())), at().plusSeconds(1)); assertVoided(running);
        var source = approved(); var oldRead = ready(register(source)); var waiting = register(source); authorize(oldRead);
        assertThat(service.claim(tenant, waiting.input().id(), at().plusSeconds(1))).isNull(); assertVoided(waiting);
    }

    @Test void workerReadsOnlyOriginalTargetOutsideTransactionAndNeverAutomaticallyAuthorizes() {
        var queued = register(approved()); var port = mock(ProcurementPayablePort.class);
        when(port.payable(anyString(), anyString(), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(reviews.find(tenant, queued.input().id()).orElseThrow().status()).isEqualTo(SupplierPayableReview.Status.RUNNING);
            return new FinanceResult.Success<>(payable(queued.input().source(), Instant.now().truncatedTo(ChronoUnit.MICROS)));
        });
        var worker = new SupplierPayableReviewWorker(reviews, service, port); worker.poll();
        var source = queued.input().source().reservation().source(); verify(port).payable(tenant, source.round().targetDigest(), source.round().payable().request());
        assertThat(reviews.find(tenant, queued.input().id()).orElseThrow().status()).isEqualTo(SupplierPayableReview.Status.READY);
        assertThat(count("supplier_payment_authorization")).isZero(); assertThat(count("supplier_payable_hold_operation")).isZero();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> worker.poll())).isInstanceOf(IllegalStateException.class);
    }

    @Test void unavailableReadDoesNotRetainEvidenceAndAllowsAnotherExplicitRead() {
        var queued = register(approved()); var port = mock(ProcurementPayablePort.class);
        when(port.payable(anyString(), anyString(), any())).thenThrow(new IllegalStateException("Synthetic confidential transport body"));
        new SupplierPayableReviewWorker(reviews, service, port).poll();
        var unavailable = reviews.find(tenant, queued.input().id()).orElseThrow(); assertThat(unavailable.status()).isEqualTo(SupplierPayableReview.Status.UNAVAILABLE);
        assertThat(unavailable.payable()).isNull(); assertThat(unavailable.issue()).isEqualTo(SupplierPayableReview.Issue.INTERNAL_ERROR);
        assertThat(json.write(unavailable)).doesNotContain("Synthetic confidential transport body");
        assertThat(register(queued.input().source()).input().id()).isNotEqualTo(queued.input().id());
    }

    @Test void persistedInputAndConsumptionCannotSwitchToAnotherApprovedRequest() {
        var first = ready(register(approved())); var second = ready(register(approved())); var hold = authorize(first);
        assertThatThrownBy(() -> jdbc.update("UPDATE supplier_payable_review SET consumed_authorization_id=? WHERE tenant_id=? AND id=?",
                hold.command().id().toString(), tenant, second.input().id().toString())).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE supplier_payable_review SET input_json=? WHERE tenant_id=? AND id=?", json.write(first.input()), tenant, second.input().id().toString());
        assertThatThrownBy(() -> reviews.find(tenant, second.input().id())).isInstanceOf(IllegalStateException.class).hasMessageContaining("identity is inconsistent");
    }

    @Test void v69UpgradePreservesAuthorizationHistoryPayableHoldsInvoicesAndOriginalApprovals() {
        database("69"); var source = approved(); var auth = new SupplierPaymentAuthorization(UUID.randomUUID(), source, payable(source, at()), "finance", at(), at().plusSeconds(86400));
        var hold = tx.execute(status -> holds.register(auth, at())); tx.executeWithoutResult(status -> holds.retire(tenant, auth.id(), hold.version(), "finance", at()));
        var tables = List.of("approval_application", "procurement_payment", "procurement_payment_revision", "procurement_payable_reservation", "procurement_payable_reservation_revision", "invoice_active_claim",
                "supplier_payment_authorization", "supplier_authorization_retirement", "supplier_payable_hold_operation", "supplier_payable_hold_revision");
        var originals = tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table + " WHERE tenant_id=?", tenant)).toList();
        var migration = Flyway.configure().dataSource(dataSource).defaultSchema(schema).target("70").load();
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table + " WHERE tenant_id=?", tenant)).toList()).isEqualTo(originals);
        assertThat(migration.migrate().migrationsExecuted).isZero(); assertThat(migration.validateWithResult().validationSuccessful).isTrue();
        assertThat(authorizations.find(tenant, auth.id())).contains(auth); assertThat(authorize(ready(register(source))).command().id()).isNotEqualTo(auth.id());
    }

    private ApprovedProcurementPayment approved() {
        var ref = UUID.randomUUID().toString(); var content = new ProcurementPaymentContent(entity, "采购付款", "已验收货物付款", "supplier-1", ref, money("70"));
        var request = ProcurementPaymentRequest.draft(UUID.randomUUID(), tenant, UUID.randomUUID(), "alice", content);
        var line = new ProcurementPayablePort.MatchedLine(1, 1, "receipt-1", new InvoiceKey(InvoiceKey.Type.DIGITAL, null, String.format("%020d", Integer.toUnsignedLong(request.id().hashCode()))), 1,
                "c".repeat(64), "verified-1", "件", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, money("100"), money("100"), money("100"), money("6"));
        var payable = new ProcurementPayablePort.Payable(content.payableRequest("alice"), "v1", now, now.plusSeconds(600), "供应商",
                new SupplierAccountSnapshot(entity, "supplier-1", "supplier-account", "****1234", "b".repeat(64), "v1"), "contract", "order", "matching", "voucher", "budget", LocalDate.parse("2026-10-01"), money("100"), money("30"), List.of(line));
        var catalog = new FinanceCatalog("alice", "v1", now.plusSeconds(600), List.of(new FinanceCatalog.LegalEntity(entity, "法人", "CNY", false, "v1", "Asia/Shanghai")), List.of(), List.of(), List.of(), List.of());
        var initiator = new InitiatorContext(UUID.randomUUID(), UUID.randomUUID(), "alice", 1, entity, "法人", UUID.randomUUID(), "部门", UUID.randomUUID(), "岗位");
        return tx.execute(status -> {
            jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id) VALUES(?,?,?,'fixture',1,'alice','采购付款','{}','DRAFT',1,1,'PROCUREMENT_PAYMENT',?)",
                    request.applicationId().toString(), tenant, ref, request.id().toString());
            requests.create(request, "alice"); request.freeze(1, 1, catalog, "a".repeat(64), payable, initiator, now); requests.update(request, 1, "alice", "SUBMIT");
            var hold = ProcurementPayableReservation.hold(UUID.randomUUID(), request, now); reservations.create(hold);
            request.approve(2, 1, 8, "manager", now.plusSeconds(1)); requests.update(request, 2, "manager", "APPROVE");
            jdbc.update("UPDATE approval_application SET status='APPROVED',version=8,payload_json=? WHERE tenant_id=? AND id=?", json.write(ProcurementPaymentFormContract.submittedPayload(request.currentRound())), tenant, request.applicationId().toString());
            return ApprovedProcurementPayment.from(request, hold);
        });
    }
    private ProcurementPayablePort.Payable payable(ApprovedProcurementPayment source, Instant observedAt) {
        var original = source.reservation().source().round().payable();
        return new ProcurementPayablePort.Payable(original.request(), "fresh-v2", observedAt, observedAt.plusSeconds(600), original.supplierName(), original.account(), original.contractReference(),
                original.orderReference(), original.matchingReference(), original.accrualVoucherReference(), original.budgetRecognitionReference(), original.dueOn(), original.gross(), original.settled(), original.lines());
    }
    private SupplierPayableReview register(ApprovedProcurementPayment source) { return tx.execute(status -> service.register(source, "finance", at())); }
    private SupplierPayableReview ready(SupplierPayableReview queued) { return ready(queued, payable(queued.input().source(), at())); }
    private SupplierPayableReview ready(SupplierPayableReview queued, ProcurementPayablePort.Payable payable) {
        var claimed = service.claim(tenant, queued.input().id(), at()); service.finish(claimed, new FinanceResult.Success<>(payable), at().plusSeconds(1));
        return reviews.find(tenant, queued.input().id()).orElseThrow();
    }
    private SupplierPayableHoldOperation authorize(SupplierPayableReview ready) { return tx.execute(status -> service.authorize(tenant, ready.input().id(), ready.version(), "finance", at().plusSeconds(1))); }
    private String outcome(SupplierPayableReview ready) { try { authorize(ready); return "OK"; } catch (DomainException failure) { return failure.code(); } }
    private void assertVoided(SupplierPayableReview value) { assertThat(reviews.find(tenant, value.input().id()).orElseThrow().status()).isEqualTo(SupplierPayableReview.Status.VOIDED); }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id=?", Integer.class, tenant); }
    private Instant at() { return now.plusSeconds(2); }
    private static UUID requestId(ApprovedProcurementPayment source) { return source.reservation().source().requestId(); }
    private static Money money(String amount) { return new Money(new BigDecimal(amount), "CNY"); }
    @SuppressWarnings("unchecked") private <T> T proxy(T target) {
        var factory = new ProxyFactory(target); factory.setProxyTargetClass(true); factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource())); return (T) factory.getProxy();
    }
    private static void fails(String code, Runnable action) { assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo(code)); }
}
