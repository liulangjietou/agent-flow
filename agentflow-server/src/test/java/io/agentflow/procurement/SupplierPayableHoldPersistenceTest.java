package io.agentflow.procurement;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 真实数据库验证供应商授权、不可变预留队列、原申请锁及短事务恢复，网关在测试中不连接真实 ERP。
 *
 * @author owlzhangfq@gmail.com
 */
class SupplierPayableHoldPersistenceTest {
    private final String tenant = "supplier-" + UUID.randomUUID();
    private final UUID entity = UUID.randomUUID();
    private final Instant now = Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.MICROS);
    private final JsonUtil json = new JsonUtil(new ObjectMapper().registerModule(new JavaTimeModule()).registerModule(new FinanceJsonConfiguration().financeMoneyModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).setSerializationInclusion(JsonInclude.Include.NON_NULL));
    private final AtomicBoolean eligible = new AtomicBoolean(true);
    private JdbcTemplate jdbc;
    private DriverManagerDataSource dataSource;
    private String schema;
    private TransactionTemplate tx;
    private JdbcProcurementPaymentRepository requests;
    private JdbcProcurementPayableReservationRepository reservations;
    private ApprovedSupplierPaymentSources sources;
    private JdbcSupplierPaymentAuthorizationRepository authorizations;
    private JdbcSupplierPayableHoldRepository operations;
    private SupplierPayableHoldService service;

    @BeforeEach void database() { database(null); }

    private void database(String target) {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_SUPPLIER_HOLD_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_SUPPLIER_HOLD_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_SUPPLIER_HOLD_PASSWORD", ""));
        schema = "supplier_hold_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(source).execute("CREATE SCHEMA \"" + schema + "\""); source.setSchema(schema); dataSource = source;
        var migration = Flyway.configure().dataSource(source).defaultSchema(schema); if (target != null) migration.target(target);
        migration.load().migrate(); jdbc = target == null ? new JdbcTemplate(source) : new SupplierMigrationJdbcTemplate(source);
        var manager = new DataSourceTransactionManager(source); tx = new TransactionTemplate(manager);
        requests = new JdbcProcurementPaymentRepository(
                        SupplierMigrationJdbcTemplate.mapper(jdbc,
                                io.agentflow.procurement.mapper.ProcurementPaymentRepositoryMapper
                                        .class), json);
        // 旧版本迁移夹具只建立当时的原件，V80 新守卫由当前版本用例验证。
        var returnGuard = target == null ? new SupplierPayableReturnGuard(
                                SupplierMigrationJdbcTemplate.mapper(jdbc,
                                        io.agentflow.procurement.mapper
                                                .SupplierPayableReturnGuardMapper.class)) : mock(SupplierPayableReturnGuard.class);
        reservations = new JdbcProcurementPayableReservationRepository(
                        SupplierMigrationJdbcTemplate.mapper(jdbc,
                                io.agentflow.procurement.mapper
                                        .ProcurementPayableReservationRepositoryMapper.class), json, requests, new JdbcProcurementInvoiceClaims(
                                SupplierMigrationJdbcTemplate.mapper(jdbc,
                                        io.agentflow.procurement.mapper
                                                .ProcurementInvoiceClaimsMapper.class)),
                        returnGuard,
                        new JdbcSupplierAdjustmentCompletions(
                                SupplierMigrationJdbcTemplate.mapper(jdbc,
                                        io.agentflow.procurement.mapper
                                                .SupplierAdjustmentCompletionsMapper.class), json));
        sources = new ApprovedSupplierPaymentSources(new JdbcApplicationRepository(
                                SupplierMigrationJdbcTemplate.mapper(jdbc,
                                        io.agentflow.approval.mapper.ApplicationRepositoryMapper
                                                .class), json), requests, reservations, returnGuard);
        authorizations = new JdbcSupplierPaymentAuthorizationRepository(
                        SupplierMigrationJdbcTemplate.mapper(jdbc,
                                io.agentflow.procurement.mapper
                                        .SupplierPaymentAuthorizationRepositoryMapper.class), json, sources); operations = new JdbcSupplierPayableHoldRepository(
                        SupplierMigrationJdbcTemplate.mapper(jdbc,
                                io.agentflow.procurement.mapper.SupplierPayableHoldRepositoryMapper
                                        .class), json, authorizations);
        var personnel = mock(PaymentPersonnel.class);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            if (!eligible.get()) throw new DomainException("PAYMENT_ACTOR_UNAVAILABLE", "Synthetic inactive appointment");
            return null;
        }).when(personnel).requireEligible(eq(tenant), eq("finance"), eq(entity));
        var factory = new ProxyFactory(new SupplierPayableHoldService(sources, authorizations, operations, personnel, 30, event -> { })); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource())); service = (SupplierPayableHoldService) factory.getProxy();
    }

    @Test void originalAuthorizationQueueAndEveryRevisionRestoreAndRemainTenantScoped() {
        var authorization = approved(); var queued = register(authorization); var id = authorization.id();
        assertThat(authorizations.find(tenant, id)).contains(authorization); assertThat(authorizations.forRequest(tenant, authorization.source().reservation().source().requestId())).contains(authorization);
        assertThat(authorizations.find("other", id)).isEmpty(); assertThat(operations.find("other", id)).isEmpty();
        assertThat(operations.revision(tenant, id, 1)).contains(queued);
        var claimed = service.claim(tenant, id, authorizedAt());
        assertThat(operations.revision(tenant, id, 2)).contains(claimed);
        service.finish(claimed, new FinanceResult.Success<>(held(claimed, authorizedAt().plusSeconds(1))), authorizedAt().plusSeconds(1));
        var reopened = new JdbcSupplierPayableHoldRepository(
                        SupplierMigrationJdbcTemplate.mapper(jdbc,
                                io.agentflow.procurement.mapper.SupplierPayableHoldRepositoryMapper
                                        .class),
                        json,
                        new JdbcSupplierPaymentAuthorizationRepository(
                                SupplierMigrationJdbcTemplate.mapper(jdbc,
                                        io.agentflow.procurement.mapper
                                                .SupplierPaymentAuthorizationRepositoryMapper
                                                .class), json, sources));
        var restored = reopened.find(tenant, id).orElseThrow(); assertThat(restored.status()).isEqualTo(SupplierPayableHoldOperation.Status.HELD);
        assertThat(restored.command()).isEqualTo(queued.command()); assertThat(restored.version()).isEqualTo(3);
        assertThat(reopened.revision(tenant, id, 1)).contains(queued); assertThat(reopened.revision(tenant, id, 2)).contains(claimed);
        assertThat(register(authorization)).isEqualTo(restored);
        assertThat(count("supplier_payment_authorization")).isEqualTo(1); assertThat(count("supplier_payable_hold_revision")).isEqualTo(3);
    }

    @Test void registrationRollbackLeavesNoAuthorizationQueueOrRevisionAndNeverCallsErp() {
        var authorization = approved();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> { service.register(authorization, authorizedAt()); throw new IllegalStateException("Synthetic caller rollback"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("supplier_payment_authorization")).isZero(); assertThat(count("supplier_payable_hold_operation")).isZero(); assertThat(count("supplier_payable_hold_revision")).isZero();
        assertThat(reservations.active(tenant, authorization.source().reservation().source().requestId())).contains(authorization.source().reservation());
        assertThat(register(authorization).status()).isEqualTo(SupplierPayableHoldOperation.Status.QUEUED);
        assertThatThrownBy(() -> service.register(authorization, authorizedAt())).hasMessageContaining("No existing transaction");
    }

    @Test void realApplicationStatusVersionPayloadAndReservationAreRequired() {
        var authorization = approved(); var source = authorization.source().reservation().source();
        jdbc.update("UPDATE approval_application SET status='DRAFT' WHERE tenant_id=? AND id=?", tenant, source.applicationId().toString());
        rejectedRegistration(authorization);
        jdbc.update(
                "UPDATE approval_application SET status='APPROVED',version=9 WHERE tenant_id=? AND"
                        + " id=?", tenant, source.applicationId().toString());
        rejectedRegistration(authorization);
        jdbc.update(
                "UPDATE approval_application SET version=8,payload_json='{}' WHERE tenant_id=? AND"
                        + " id=?", tenant, source.applicationId().toString());
        rejectedRegistration(authorization);
        jdbc.update("UPDATE approval_application SET payload_json=? WHERE tenant_id=? AND id=?", json.write(ProcurementPaymentFormContract.submittedPayload(source.round())), tenant, source.applicationId().toString());
        tx.executeWithoutResult(status -> reservations.release(authorization.source().reservation().release(ProcurementPayableReservation.ReleaseReason.CANCELLED, "alice", authorizedAt())));
        rejectedRegistration(authorization);
        assertThat(count("supplier_payment_authorization")).isZero();
    }

    @Test void simultaneousAuthorizationsCannotCreateTwoCommandsForOneApprovedRequest() throws Exception {
        var first = approved(); var second = new SupplierPaymentAuthorization(UUID.randomUUID(), first.source(), first.payable(), "finance", first.authorizedAt(), first.expiresAt());
        var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> { start.await(); return outcome(first); }); var b = pool.submit(() -> { start.await(); return outcome(second); }); start.countDown();
            assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder("OK", "SUPPLIER_PAYMENT_ALREADY_AUTHORIZED");
            assertThat(count("supplier_payment_authorization")).isEqualTo(1); assertThat(count("supplier_payable_hold_operation")).isEqualTo(1); assertThat(count("supplier_payable_hold_revision")).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test void onlyOneProcessClaimsAndLateResultCannotOverwriteRecovery() throws Exception {
        var queued = register(approved()); var id = queued.command().id(); var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        SupplierPayableHoldOperation claimed;
        try {
            var a = pool.submit(() -> { start.await(); return service.claim(tenant, id, authorizedAt()); });
            var b = pool.submit(() -> { start.await(); return service.claim(tenant, id, authorizedAt()); }); start.countDown();
            var first = a.get(15, TimeUnit.SECONDS); var second = b.get(15, TimeUnit.SECONDS); assertThat(first == null ^ second == null).isTrue(); claimed = first == null ? second : first;
        } finally { pool.shutdownNow(); }
        assertThat(service.claim(tenant, id, claimed.leaseUntil())).isNull();
        var recovery = service.claim(tenant, id, claimed.leaseUntil()); assertThat(recovery.status()).isEqualTo(SupplierPayableHoldOperation.Status.QUERYING);
        service.finish(claimed, new FinanceResult.Success<>(held(claimed, claimed.leaseUntil())), claimed.leaseUntil());
        assertThat(operations.find(tenant, id)).contains(recovery);
        service.finish(recovery, new FinanceResult.Success<>(held(recovery, recovery.updatedAt())), recovery.updatedAt());
        assertThat(operations.find(tenant, id).orElseThrow().status()).isEqualTo(SupplierPayableHoldOperation.Status.HELD);
        assertThat(operations.find(tenant, id).orElseThrow().dispatches()).isEqualTo(1);
    }

    @Test void appendFailureRollsBackClaimAndCommandTamperingCannotReplaceItsIdentity() {
        var queued = register(approved()); var id = queued.command().id();
        jdbc.update(
                "INSERT INTO"
                    + " supplier_payable_hold_revision(tenant_id,operation_id,version,state_json)"
                    + " VALUES(?,?,2,?)", tenant, id.toString(), json.write(queued));
        assertThatThrownBy(() -> service.claim(tenant, id, authorizedAt())).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(operations.find(tenant, id)).contains(queued);
        jdbc.update(
                "UPDATE supplier_payable_hold_operation SET command_digest=? WHERE tenant_id=? AND"
                        + " id=?", "b".repeat(64), tenant, id.toString());
        assertThatThrownBy(() -> operations.find(tenant, id)).isInstanceOf(IllegalStateException.class).hasMessageContaining("identity is inconsistent");
    }

    @Test void staleCallerVersionCannotRequestRecoveryOrWriteItsPreviousClaim() {
        var queued = register(approved()); var id = queued.command().id(); var claimed = service.claim(tenant, id, authorizedAt());
        service.fail(claimed, SupplierPayableHoldOperation.Failure.TIMEOUT, authorizedAt().plusSeconds(1));
        var unknown = operations.find(tenant, id).orElseThrow();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> service.query(tenant, id, claimed.version(), unknown.updatedAt()))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> operations.update(claimed))).isInstanceOf(DomainException.class);
        assertThat(operations.find(tenant, id)).contains(unknown);
    }

    @Test void newSendStopsForInactiveFinanceWhileUnknownStillQueriesAfterSourceChangeAndExpiry() {
        var queued = register(approved()); eligible.set(false);
        assertThat(service.claim(tenant, queued.command().id(), authorizedAt())).isNull();
        var stopped = operations.find(tenant, queued.command().id()).orElseThrow(); assertThat(stopped.status()).isEqualTo(SupplierPayableHoldOperation.Status.VOIDED); assertThat(stopped.dispatches()).isZero();
        eligible.set(true); var another = register(approved()); var sending = service.claim(tenant, another.command().id(), authorizedAt());
        service.fail(sending, SupplierPayableHoldOperation.Failure.TIMEOUT, authorizedAt().plusSeconds(1)); eligible.set(false);
        jdbc.update("UPDATE approval_application SET status='CANCELLED' WHERE tenant_id=? AND id=?", tenant, another.command().authorization().source().reservation().source().applicationId().toString());
        var later = another.command().authorization().expiresAt().plusSeconds(1); var query = service.claim(tenant, another.command().id(), later);
        assertThat(query.status()).isEqualTo(SupplierPayableHoldOperation.Status.QUERYING);
        service.finish(query, new FinanceResult.Success<>(held(query, later)), later);
        assertThat(operations.find(tenant, query.command().id()).orElseThrow().status()).isEqualTo(SupplierPayableHoldOperation.Status.HELD);
        assertThat(reservations.active(tenant, another.command().authorization().source().reservation().source().requestId())).isPresent();
    }

    @Test void workerCommitsClaimBeforeTransportAndRecoversAThrownWriteUsingQueryOnly() {
        var queued = register(approved()); var writes = new AtomicInteger(); var reads = new AtomicInteger();
        var gateway = mock(SupplierPayableHoldPort.class);
        when(gateway.reserve(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); writes.incrementAndGet();
            assertThat(operations.find(tenant, queued.command().id()).orElseThrow().status()).isEqualTo(SupplierPayableHoldOperation.Status.RESERVING);
            throw new IllegalStateException("Synthetic lost response");
        });
        when(gateway.query(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); reads.incrementAndGet();
            var current = operations.find(tenant, queued.command().id()).orElseThrow(); return new FinanceResult.Success<>(held(current, Instant.now().truncatedTo(ChronoUnit.MICROS)));
        });
        var worker = new SupplierPayableHoldWorker(operations, service, gateway); worker.poll();
        var unknown = operations.find(tenant, queued.command().id()).orElseThrow(); assertThat(unknown.status()).isEqualTo(SupplierPayableHoldOperation.Status.UNKNOWN);
        tx.executeWithoutResult(status -> service.query(tenant, queued.command().id(), unknown.version(), Instant.now())); worker.poll();
        assertThat(writes.get()).isEqualTo(1); assertThat(reads.get()).isEqualTo(1);
        assertThat(operations.find(tenant, queued.command().id()).orElseThrow().status()).isEqualTo(SupplierPayableHoldOperation.Status.HELD);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> worker.poll())).isInstanceOf(IllegalStateException.class);
    }

    @Test void v67UpgradeKeepsOriginalApprovedProcurementReservationsAndInvoiceOrigins() {
        database("67"); var authorization = approved();
        var tables = List.of("approval_application", "procurement_payment", "procurement_payment_revision", "procurement_payable_reservation", "procurement_payable_reservation_revision", "invoice_active_claim");
        var originals = tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table + " WHERE tenant_id=?", tenant)).toList();
        var migration = Flyway.configure().dataSource(dataSource).defaultSchema(schema).target("68").load();
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table + " WHERE tenant_id=?", tenant)).toList()).isEqualTo(originals);
        assertThat(migration.migrate().migrationsExecuted).isZero(); assertThat(migration.validateWithResult().validationSuccessful).isTrue();
        Flyway.configure().dataSource(dataSource).defaultSchema(schema).load().migrate();
        assertThat(register(authorization).command().authorization()).isEqualTo(authorization);
        assertThatThrownBy(() -> jdbc.update(
                                        "UPDATE supplier_payment_authorization SET reservation_id=?"
                                                + " WHERE tenant_id=?", UUID.randomUUID().toString(), tenant)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                                        "UPDATE supplier_payment_authorization SET"
                                                + " request_version=99 WHERE tenant_id=?", tenant)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                                        "UPDATE supplier_payment_authorization SET"
                                                + " authorized_by=employee_id WHERE tenant_id=?", tenant)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void neverDispatchedRetirementAllowsNewIdentityAndPreservesTheOriginalAuthorizationAndLocalHold() {
        var original = approved(); var queued = register(original);
        var decision = tx.execute(status -> service.retire(tenant, original.id(), queued.version(), "finance", authorizedAt()));
        assertThat(decision.basis()).isEqualTo(SupplierPayableHoldOperation.RetirementBasis.NEVER_DISPATCHED);
        assertThat(authorizations.find(tenant, original.id())).contains(original); assertThat(authorizations.retirement(tenant, original.id())).contains(decision);
        assertThat(authorizations.activeForRequest(tenant, original.source().reservation().source().requestId())).isEmpty();
        assertThat(service.claim(tenant, original.id(), authorizedAt())).isNull();
        var replacement = new SupplierPaymentAuthorization(UUID.randomUUID(), original.source(), original.payable(), "finance", authorizedAt().plusSeconds(1), original.expiresAt());
        var next = tx.execute(status -> service.register(replacement, replacement.authorizedAt()));
        assertThat(next.command().id()).isNotEqualTo(queued.command().id()); assertThat(authorizations.forRequest(tenant, original.source().reservation().source().requestId())).contains(replacement);
        assertThat(authorizations.activeForRequest(tenant, original.source().reservation().source().requestId())).contains(replacement);
        assertThat(reservations.active(tenant, original.source().reservation().source().requestId())).contains(original.source().reservation());
        assertThat(operations.revision(tenant, original.id(), 1)).contains(queued); assertThat(count("supplier_payment_authorization")).isEqualTo(2);
        var stopped = operations.find(tenant, original.id()).orElseThrow();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> service.query(tenant, original.id(), stopped.version(), authorizedAt()))).isInstanceOf(DomainException.class);
    }

    @Test void rejectedOriginalCommandCanRetireButUnknownNotFoundAndHeldCannot() {
        var queued = register(approved()); var sending = service.claim(tenant, queued.command().id(), authorizedAt());
        var rejected = new SupplierPayableHoldObservation(sending.command().id(), sending.command().digest(), SupplierPayableHoldObservation.Status.REJECTED, 1L, authorizedAt(),
                null, null, null, null, null, SupplierPayableHoldObservation.Rejection.PAYABLE_VERSION_CONFLICT);
        service.finish(sending, new FinanceResult.Success<>(rejected), authorizedAt()); var terminal = operations.find(tenant, sending.command().id()).orElseThrow();
        var retirement = tx.execute(status -> service.retire(tenant, sending.command().id(), terminal.version(), "finance", authorizedAt()));
        assertThat(retirement.basis()).isEqualTo(SupplierPayableHoldOperation.RetirementBasis.CONFIRMED_REJECTED); assertThat(operations.find(tenant, sending.command().id())).contains(terminal);
        var another = register(approved()); var dispatched = service.claim(tenant, another.command().id(), authorizedAt()); assertUnsafeRetirement(dispatched);
        service.fail(dispatched, SupplierPayableHoldOperation.Failure.TIMEOUT, authorizedAt()); var unknown = operations.find(tenant, dispatched.command().id()).orElseThrow(); assertUnsafeRetirement(unknown);
        var query = service.claim(tenant, dispatched.command().id(), unknown.nextAttemptAt());
        var absent = new SupplierPayableHoldObservation(query.command().id(), query.command().digest(), SupplierPayableHoldObservation.Status.NOT_FOUND, 0L, query.updatedAt(), null, null, null, null, null, null);
        service.finish(query, new FinanceResult.Success<>(absent), query.updatedAt()); var missing = operations.find(tenant, query.command().id()).orElseThrow(); assertUnsafeRetirement(missing);
        tx.executeWithoutResult(status -> service.resend(tenant, missing.command().id(), missing.version(), missing.updatedAt()));
        var secondSend = service.claim(tenant, missing.command().id(), missing.updatedAt()); service.finish(secondSend, new FinanceResult.Success<>(held(secondSend, secondSend.updatedAt())), secondSend.updatedAt());
        assertUnsafeRetirement(operations.find(tenant, missing.command().id()).orElseThrow());
        assertThat(authorizations.activeForRequest(tenant, another.command().authorization().source().reservation().source().requestId())).isPresent();
    }

    @Test void retirementWriteFailureRollsBackQueueStopAndKeepsItsActiveIdentity() {
        var queued = register(approved()); var id = queued.command().id();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> service.retire(tenant, id, queued.version(), "alice", authorizedAt()))).isInstanceOf(DomainException.class);
        assertThat(operations.find(tenant, id)).contains(queued);
        jdbc.execute(
                "ALTER TABLE supplier_payment_authorization ADD CONSTRAINT synthetic_keep_active"
                        + " CHECK (active_request_id IS NOT NULL)");
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> service.retire(tenant, id, queued.version(), "finance", authorizedAt()))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(operations.find(tenant, id)).contains(queued); assertThat(authorizations.retirement(tenant, id)).isEmpty();
        assertThat(authorizations.activeForRequest(tenant, queued.command().authorization().source().reservation().source().requestId())).isPresent();
        assertThat(count("supplier_payable_hold_revision")).isEqualTo(1);
    }

    @Test void retireAndClaimRaceCannotBothSucceed() throws Exception {
        var queued = register(approved()); var id = queued.command().id(); var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var claim = pool.submit(() -> { start.await(); return service.claim(tenant, id, authorizedAt()); });
            var retire = pool.submit(() -> { start.await(); try { tx.executeWithoutResult(status -> service.retire(tenant, id, queued.version(), "finance", authorizedAt())); return "RETIRED"; } catch (DomainException failure) { return failure.code(); } });
            start.countDown(); var claimed = claim.get(15, TimeUnit.SECONDS); var result = retire.get(15, TimeUnit.SECONDS);
            if (claimed == null) { assertThat(result).isEqualTo("RETIRED"); assertThat(authorizations.retirement(tenant, id)).isPresent(); }
            else { assertThat(result).isEqualTo("CONCURRENCY_CONFLICT"); assertThat(authorizations.retirement(tenant, id)).isEmpty(); assertThat(claimed.dispatches()).isEqualTo(1); }
        } finally { pool.shutdownNow(); }
    }

    @Test void v68UpgradeKeepsAuthorizationCommandAndRevisionWhileBackfillingActiveSource() {
        database("68"); var value = approved(); var approved = value.source(); var reservation = approved.reservation(); var source = reservation.source();
        jdbc.update(
                """
INSERT INTO supplier_payment_authorization(tenant_id,id,request_id,application_id,employee_id,round_no,application_version,request_version,
reservation_id,legal_entity_id,authorized_by,authorized_at,expires_at,state_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
""", tenant, value.id().toString(), source.requestId().toString(), source.applicationId().toString(), "alice", 1, approved.approval().applicationVersion(), approved.approvedRequestVersion(),
                reservation.id().toString(), entity.toString(), "finance", java.sql.Timestamp.from(value.authorizedAt()), java.sql.Timestamp.from(value.expiresAt()), json.write(value));
        var queued = SupplierPayableHoldOperation.queue(new SupplierPayableHoldCommand(value), authorizedAt());
        jdbc.update(
                """
INSERT INTO supplier_payable_hold_operation(tenant_id,id,command_json,command_digest,state_json,version,status,attempts,dispatches,highest_revision,created_at,updated_at,next_attempt_at)
VALUES(?,?,?,?,?,1,'QUEUED',0,0,0,?,?,?)
""", tenant, value.id().toString(), json.write(queued.command()), queued.command().digest(), json.write(queued), java.sql.Timestamp.from(queued.createdAt()), java.sql.Timestamp.from(queued.updatedAt()), java.sql.Timestamp.from(queued.nextAttemptAt()));
        jdbc.update(
                "INSERT INTO"
                    + " supplier_payable_hold_revision(tenant_id,operation_id,version,state_json)"
                    + " VALUES(?,?,1,?)", tenant, value.id().toString(), json.write(queued));
        var operationRows = jdbc.queryForList("SELECT * FROM supplier_payable_hold_operation"); var revisionRows = jdbc.queryForList("SELECT * FROM supplier_payable_hold_revision");
        var migration = Flyway.configure().dataSource(dataSource).defaultSchema(schema).target("69").load(); assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM supplier_payable_hold_operation")).isEqualTo(operationRows); assertThat(jdbc.queryForList("SELECT * FROM supplier_payable_hold_revision")).isEqualTo(revisionRows);
        assertThat(authorizations.find(tenant, value.id())).contains(value); assertThat(authorizations.activeForRequest(tenant, source.requestId())).contains(value);
        assertThat(operations.find(tenant, value.id())).contains(queued); assertThat(migration.migrate().migrationsExecuted).isZero();
        assertThatThrownBy(() -> jdbc.update(
                                        "UPDATE supplier_payment_authorization SET"
                                                + " active_request_id=NULL WHERE tenant_id=?", tenant)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                                        "UPDATE supplier_payment_authorization SET"
                                            + " active_request_id=NULL,retired_hold_version=1 WHERE"
                                            + " tenant_id=?", tenant)).isInstanceOf(DataIntegrityViolationException.class);
        tx.executeWithoutResult(status -> service.retire(tenant, value.id(), queued.version(), "finance", authorizedAt()));
        assertThat(authorizations.find(tenant, value.id())).contains(value);
    }

    private void assertUnsafeRetirement(SupplierPayableHoldOperation value) {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> service.retire(tenant, value.command().id(), value.version(), "finance", value.updatedAt())))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo("SUPPLIER_AUTHORIZATION_RETIREMENT_UNSAFE"));
        assertThat(operations.find(tenant, value.command().id())).contains(value); assertThat(authorizations.retirement(tenant, value.command().id())).isEmpty();
    }

    private SupplierPaymentAuthorization approved() {
        var ref = UUID.randomUUID().toString(); var content = new ProcurementPaymentContent(entity, "采购付款", "已验收货物付款", "supplier-1", ref, money("70"));
        var request = ProcurementPaymentRequest.draft(UUID.randomUUID(), tenant, UUID.randomUUID(), "alice", content);
        var payable = payable(request); var catalog = new FinanceCatalog("alice", "v1", now.plusSeconds(600), List.of(new FinanceCatalog.LegalEntity(entity, "法人", "CNY", false, "v1", "Asia/Shanghai")), List.of(), List.of(), List.of(), List.of());
        var initiator = new InitiatorContext(UUID.randomUUID(), UUID.randomUUID(), "alice", 1, entity, "法人", UUID.randomUUID(), "部门", UUID.randomUUID(), "岗位");
        return tx.execute(status -> {
            jdbc.update(
                            "INSERT INTO"
                                + " approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id)"
                                + " VALUES(?,?,?,'fixture',1,'alice','采购付款','{}','DRAFT',1,1,'PROCUREMENT_PAYMENT',?)",
                    request.applicationId().toString(), tenant, ref, request.id().toString());
            requests.create(request, "alice"); request.freeze(1, 1, catalog, "a".repeat(64), payable, initiator, now); requests.update(request, 1, "alice", "SUBMIT");
            var hold = ProcurementPayableReservation.hold(UUID.randomUUID(), request, now); reservations.create(hold);
            request.approve(2, 1, 8, "manager", now.plusSeconds(1)); requests.update(request, 2, "manager", "APPROVE");
            jdbc.update(
                            "UPDATE approval_application SET"
                                + " status='APPROVED',version=8,payload_json=? WHERE tenant_id=?"
                                + " AND id=?", json.write(ProcurementPaymentFormContract.submittedPayload(request.currentRound())), tenant, request.applicationId().toString());
            return new SupplierPaymentAuthorization(UUID.randomUUID(), ApprovedProcurementPayment.from(request, hold), payable, "finance", authorizedAt(), authorizedAt().plusSeconds(86400));
        });
    }
    private ProcurementPayablePort.Payable payable(ProcurementPaymentRequest request) {
        var line = new ProcurementPayablePort.MatchedLine(1, 1, "receipt-1", new InvoiceKey(InvoiceKey.Type.DIGITAL, null, String.format("%020d", Integer.toUnsignedLong(request.id().hashCode()))), 1,
                "c".repeat(64), "verified-1", "件", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, money("100"), money("100"), money("100"), money("6"));
        return new ProcurementPayablePort.Payable(request.content().payableRequest("alice"), "v1", now, now.plusSeconds(600), "供应商",
                new SupplierAccountSnapshot(entity, "supplier-1", "supplier-account", "****1234", "b".repeat(64), "v1"),
                "contract", "order", "matching", "voucher", "budget", LocalDate.parse("2026-10-01"), money("100"), money("30"), List.of(line));
    }
    private SupplierPayableHoldOperation register(SupplierPaymentAuthorization value) { return tx.execute(status -> service.register(value, authorizedAt())); }
    private void rejectedRegistration(SupplierPaymentAuthorization value) {
        assertThatThrownBy(() -> register(value)).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo("PROCUREMENT_PAYMENT_SOURCE_CHANGED"));
    }
    private String outcome(SupplierPaymentAuthorization value) { try { register(value); return "OK"; } catch (DomainException failure) { return failure.code(); } }
    private Instant authorizedAt() { return now.plusSeconds(2); }
    private SupplierPayableHoldObservation held(SupplierPayableHoldOperation value, Instant observedAt) {
        return new SupplierPayableHoldObservation(value.command().id(), value.command().digest(), SupplierPayableHoldObservation.Status.HELD, 1L, observedAt,
                "erp-hold-1", "ledger-2", money("70"), value.command().authorization().payable().account().accountDigest(), authorizedAt(), null);
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id=?", Integer.class, tenant); }
    private static Money money(String amount) { return new Money(new BigDecimal(amount), "CNY"); }
}
