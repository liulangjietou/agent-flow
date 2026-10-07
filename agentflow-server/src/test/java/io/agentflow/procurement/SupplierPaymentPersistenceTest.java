package io.agentflow.procurement;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentflow.approval.JdbcApplicationRepository;
import io.agentflow.common.DomainException;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.FinanceJsonConfiguration;
import io.agentflow.expense.InvoiceKey;
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.finance.FinanceResult;
import io.agentflow.finance.Money;
import io.agentflow.finance.PaymentAccountsPort;
import io.agentflow.finance.PaymentObservation;
import io.agentflow.finance.PaymentPersonnel;
import io.agentflow.finance.JdbcFinanceReceiptCreditRepository;
import io.agentflow.organization.InitiatorContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * 真实数据库验证出纳意图、唯一银行命令及崩溃恢复，网关替身只验证协议和事务边界。
 * @author owlzhangfq@gmail.com
 */
class SupplierPaymentPersistenceTest {
    private final String tenant = "supplier-bank-" + UUID.randomUUID();
    private final UUID entity = UUID.randomUUID();
    private final Instant now = Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.MICROS);
    private final Set<String> disabled = new HashSet<>();
    private final JsonUtil json = new JsonUtil(new ObjectMapper().registerModule(new JavaTimeModule()).registerModule(new FinanceJsonConfiguration().financeMoneyModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).setSerializationInclusion(JsonInclude.Include.NON_NULL));
    private JdbcTemplate jdbc;
    private DriverManagerDataSource dataSource;
    private String schema;
    private DataSourceTransactionManager manager;
    private TransactionTemplate tx;
    private JdbcProcurementPaymentRepository procurements;
    private JdbcProcurementPayableReservationRepository reservations;
    private ApprovedSupplierPaymentSources approvedSources;
    private JdbcSupplierPaymentAuthorizationRepository authorizations;
    private JdbcSupplierPayableHoldRepository holds;
    private SupplierPayableHoldService holdService;
    private SupplierPaymentSources sources;
    private JdbcSupplierPaymentExecutionRepository requests;
    private JdbcSupplierPaymentOperationRepository payments;
    private SupplierPaymentExecutionService preparation;
    private SupplierPaymentService bank;
    private JdbcSupplierPaymentReturnsRepository returnLedgers;
    private JdbcSupplierPaymentReturnCheckRepository returnChecks;
    private JdbcSupplierPaymentReturnRepository returnRegistrations;
    private SupplierPaymentReturnService returnService;
    private CurrentActor returnActor;
    private SupplierSettlementAccess returnAccess;
    private static final PaymentAccountsPort.DebitAccount DEBIT = new PaymentAccountsPort.DebitAccount("debit-1", "法人基本户", "****5678", "CNY", "v1");

    @BeforeEach void database() { database(null); }

    private void database(String target) {
        dataSource = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_SUPPLIER_PAYMENT_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_SUPPLIER_PAYMENT_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_SUPPLIER_PAYMENT_PASSWORD", ""));
        schema = "supplier_payment_" + UUID.randomUUID().toString().replace("-", ""); new JdbcTemplate(dataSource).execute("CREATE SCHEMA \"" + schema + "\""); dataSource.setSchema(schema);
        var migration = Flyway.configure().dataSource(dataSource).defaultSchema(schema); if (target != null) migration.target(target); migration.load().migrate();
        jdbc = target == null ? new JdbcTemplate(dataSource) : new SupplierMigrationJdbcTemplate(dataSource); manager = new DataSourceTransactionManager(dataSource); tx = new TransactionTemplate(manager);
        procurements = new JdbcProcurementPaymentRepository(jdbc, json);
        // 旧版本迁移夹具只建立当时的原件，V80 新守卫由当前版本用例验证。
        var returnGuard = target == null ? new SupplierPayableReturnGuard(jdbc) : mock(SupplierPayableReturnGuard.class);
        reservations = new JdbcProcurementPayableReservationRepository(jdbc, json, procurements, new JdbcProcurementInvoiceClaims(jdbc), returnGuard, new JdbcSupplierAdjustmentCompletions(jdbc, json));
        approvedSources = new ApprovedSupplierPaymentSources(new JdbcApplicationRepository(jdbc, json), procurements, reservations, returnGuard);
        authorizations = new JdbcSupplierPaymentAuthorizationRepository(jdbc, json, approvedSources); holds = new JdbcSupplierPayableHoldRepository(jdbc, json, authorizations);
        var personnel = mock(PaymentPersonnel.class);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            if (disabled.contains(invocation.<String>getArgument(1))) throw new DomainException("PAYMENT_ACTOR_UNAVAILABLE", "Synthetic inactive appointment");
            return null;
        }).when(personnel).requireEligible(eq(tenant), anyString(), eq(entity));
        holdService = proxy(new SupplierPayableHoldService(approvedSources, authorizations, holds, personnel, 30, event -> { }));
        sources = new SupplierPaymentSources(approvedSources, authorizations, holds, personnel);
        requests = new JdbcSupplierPaymentExecutionRepository(jdbc, json, holds);
        payments = new JdbcSupplierPaymentOperationRepository(jdbc, json, requests, holds, authorizations);
        preparation = proxy(new SupplierPaymentExecutionService(sources, requests, payments, event -> { }, 30)); bank = proxy(new SupplierPaymentService(sources, payments, event -> { }, 30));
        returnLedgers = new JdbcSupplierPaymentReturnsRepository(jdbc, json, payments, new SupplierPayableReturnGuard(jdbc), new JdbcSupplierAdjustmentCompletions(jdbc, json));
        returnChecks = new JdbcSupplierPaymentReturnCheckRepository(jdbc, json);
        returnRegistrations = new JdbcSupplierPaymentReturnRepository(jdbc, json, returnLedgers, returnChecks, payments, new JdbcFinanceReceiptCreditRepository(jdbc));
        returnActor = mock(CurrentActor.class); when(returnActor.actor()).thenReturn(new Actor(tenant, "finance", Set.of("FINANCE")));
        returnAccess = mock(SupplierSettlementAccess.class);
        var returnSources = new SupplierPaymentReturnSources(sources, payments, new SupplierSettlementSources(sources, approvedSources, payments, personnel, returnGuard));
        returnService = proxy(new SupplierPaymentReturnService(returnActor, returnAccess, returnSources, returnChecks, returnLedgers, returnRegistrations, jdbc, json, org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class)));
    }

    @Test void cashierChoiceRegistrationAndAllBankRevisionsSurviveRepositoryRecreation() {
        var hold = confirmed(); var request = register(hold); var queued = prepared(request); var id = hold.command().id();
        assertThat(requests.find(tenant, request.input().id()).orElseThrow().status()).isEqualTo(SupplierPaymentExecutionRequest.Status.READY);
        assertThat(requests.owner(tenant, id)).isPresent(); assertThat(payments.find("other", id)).isEmpty(); assertThat(requests.owner("other", id)).isEmpty();
        assertThat(payments.revision(tenant, id, 1)).contains(queued); assertThat(requests.revision(tenant, request.input().id(), 1)).contains(request);
        var sending = sending(queued); bank.finish(sending, new FinanceResult.Success<>(paid(sending.command(), sending.updatedAt())), sending.updatedAt());
        var reopenedRequests = new JdbcSupplierPaymentExecutionRepository(jdbc, json, holds);
        var reopened = new JdbcSupplierPaymentOperationRepository(jdbc, json, reopenedRequests, holds, authorizations);
        var restored = reopened.find(tenant, id).orElseThrow(); assertThat(restored.settleable()).isTrue(); assertThat(restored.command()).isEqualTo(queued.command());
        assertThat(restored.version()).isEqualTo(4); assertThat(restored.dispatches()).isEqualTo(1);
        assertThat(reopened.revision(tenant, id, 3)).contains(sending); assertThat(count("supplier_payment_revision")).isEqualTo(4);
        assertThat(authorizations.find(tenant, id)).contains(hold.command().authorization()); assertThat(holds.find(tenant, id)).contains(hold);
        assertThatThrownBy(() -> register(hold)).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo("SUPPLIER_PAYMENT_ALREADY_REGISTERED"));
    }

    @Test void directOperationUpdateCannotDropDisputeWithoutADecision() {
        var disputed = disputedPayment(); var original = payments.revision(tenant, disputed.command().id(), 4).orElseThrow().observation();
        var after = disputed.resolveDispute(PaymentObservation.Status.SUCCEEDED,
                new SupplierPaymentOperation.ResolutionHistory(original, null, true), disputed.updatedAt());
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> payments.update(after))).isInstanceOf(DomainException.class);
        assertThat(payments.find(tenant, disputed.command().id())).contains(disputed);
        assertThat(payments.revision(tenant, disputed.command().id(), after.version())).isEmpty();
    }

    @Test void disputeDecisionAndOriginalBankHistorySurviveRepositoryRecreation() {
        var before = disputedPayment(); var command = before.command(); var id = command.id();
        var original = payments.revision(tenant, id, 4).orElseThrow().observation(); var decision = decision(before);
        assertThat(payments.resolutionHistory(tenant, id)).isEqualTo(new SupplierPaymentOperation.ResolutionHistory(original, null, true));
        var after = tx.execute(status -> payments.resolve(decision));
        var reopened = new JdbcSupplierPaymentOperationRepository(jdbc, json, requests, holds, authorizations);
        assertThat(reopened.latestResolution(tenant, id)).contains(decision); assertThat(reopened.latestResolution("other", id)).isEmpty();
        assertThat(reopened.find(tenant, id)).contains(after); assertThat(after.settleable()).isTrue(); assertThat(after.command()).isEqualTo(command);
        assertThat(after.dispatches()).isEqualTo(before.dispatches()); assertThat(reopened.revision(tenant, id, before.version())).contains(before);
        assertThat(holds.find(tenant, id).orElseThrow().status()).isEqualTo(SupplierPayableHoldOperation.Status.HELD);
        assertThat(authorizations.find(tenant, id)).contains(command.holdCommand().authorization());
        assertThatThrownBy(() -> tx.execute(status -> payments.resolve(decision))).isInstanceOf(DomainException.class);
        assertThat(count("supplier_payment_dispute_resolution")).isEqualTo(1);
    }

    @Test void decisionInsertFailureRollsBackClearedDisputeAndResultRevision() {
        var before = disputedPayment(); var decision = decision(before);
        jdbc.execute("ALTER TABLE supplier_payment_dispute_resolution ADD CONSTRAINT reject_test_supplier_resolution CHECK (outcome<>'SUCCEEDED')");
        assertThatThrownBy(() -> tx.execute(status -> payments.resolve(decision))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(payments.find(tenant, before.command().id())).contains(before);
        assertThat(payments.revision(tenant, before.command().id(), decision.resolvedVersion())).isEmpty();
        assertThat(count("supplier_payment_dispute_resolution")).isZero();
        jdbc.execute("ALTER TABLE supplier_payment_dispute_resolution DROP CONSTRAINT reject_test_supplier_resolution");
        assertThat(tx.execute(status -> payments.resolve(decision)).settleable()).isTrue();
    }

    @Test void concurrentDecisionsAcceptExactlyOneOriginalVersion() throws Exception {
        var before = disputedPayment(); var first = decision(before); var second = decision(before);
        var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> { start.await(); return resolutionOutcome(first); });
            var b = pool.submit(() -> { start.await(); return resolutionOutcome(second); }); start.countDown();
            assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder("OK", "CONFLICT");
        } finally { pool.shutdownNow(); }
        assertThat(count("supplier_payment_dispute_resolution")).isEqualTo(1);
        assertThat(payments.find(tenant, before.command().id()).orElseThrow().version()).isEqualTo(before.version() + 1);
    }

    @Test void missingHistoricalRevisionPreventsResolutionAndCorruptedDecisionIsRejected() {
        var before = disputedPayment(); var decision = decision(before); var id = before.command().id();
        var original = jdbc.queryForObject("SELECT state_json FROM supplier_payment_revision WHERE tenant_id=? AND operation_id=? AND version=4", String.class, tenant, id.toString());
        jdbc.update("DELETE FROM supplier_payment_revision WHERE tenant_id=? AND operation_id=? AND version=4", tenant, id.toString());
        assertThatThrownBy(() -> tx.execute(status -> payments.resolve(decision))).isInstanceOf(DomainException.class);
        assertThat(payments.find(tenant, id)).contains(before);
        jdbc.update("INSERT INTO supplier_payment_revision(tenant_id,operation_id,version,state_json) VALUES(?,?,4,?)", tenant, id.toString(), original);
        tx.executeWithoutResult(status -> payments.resolve(decision));
        jdbc.update("UPDATE supplier_payment_dispute_resolution SET resolved_by='another-finance' WHERE tenant_id=?", tenant);
        assertThatThrownBy(() -> payments.latestResolution(tenant, id)).isInstanceOf(DomainException.class);
    }

    @Test void v77UpgradePreservesExistingBankConflictAndPermitsOnlyARecordedResolution() {
        database("77"); var before = disputedPayment();
        var tables = List.of("approval_application", "procurement_payment", "procurement_payment_revision", "procurement_payable_reservation", "procurement_payable_reservation_revision",
                "invoice_active_claim", "supplier_payment_authorization", "supplier_payable_hold_operation", "supplier_payable_hold_revision", "supplier_payment_execution_request",
                "supplier_payment_execution_revision", "supplier_payment_operation", "supplier_payment_revision");
        var original = tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table + " WHERE tenant_id=?", tenant)).toList();
        var migration = Flyway.configure().dataSource(dataSource).defaultSchema(schema).target("78").load();
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table + " WHERE tenant_id=?", tenant)).toList()).isEqualTo(original);
        assertThat(migration.migrate().migrationsExecuted).isZero(); assertThat(migration.validateWithResult().validationSuccessful).isTrue();
        var after = tx.execute(status -> payments.resolve(decision(before))); assertThat(after.settleable()).isTrue();
        assertThat(payments.revision(tenant, before.command().id(), before.version())).contains(before);
    }

    @Test void concurrentCashierSelectionsHaveOneOwnerAndOneImmutableBankCommand() throws Exception {
        var hold = confirmed(); var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> { start.await(); return registrationOutcome(hold); });
            var b = pool.submit(() -> { start.await(); return registrationOutcome(hold); }); start.countDown();
            assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder("OK", "SUPPLIER_PAYMENT_EXECUTION_PENDING");
        } finally { pool.shutdownNow(); }
        assertThat(count("supplier_payment_execution_request")).isEqualTo(1); prepared(requests.owner(tenant, hold.command().id()).orElseThrow());
        assertThat(count("supplier_payment_operation")).isEqualTo(1); assertThat(count("supplier_payment_execution_revision")).isEqualTo(3);
    }

    @Test void preparationRevisionFailureRollsBackBothBankRegistrationAndReadyMarker() {
        var request = register(confirmed()); var claimed = preparation.claim(tenant, request.input().id(), at());
        jdbc.update("INSERT INTO supplier_payment_execution_revision(tenant_id,execution_request_id,version,state_json) VALUES(?,?,3,?)", tenant, request.input().id().toString(), json.write(claimed));
        assertThatThrownBy(() -> preparation.finish(claimed, evidence(authorization(request), at()), at())).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(requests.find(tenant, request.input().id())).contains(claimed); assertThat(count("supplier_payment_operation")).isZero(); assertThat(count("supplier_payment_revision")).isZero();
        assertThat(holds.find(tenant, request.input().authorizationId()).orElseThrow().status()).isEqualTo(SupplierPayableHoldOperation.Status.HELD);
    }

    @Test void preparationLeaseLossIgnoresLateResultAndPreservesOriginalSelection() {
        var request = register(confirmed()); var claimed = preparation.claim(tenant, request.input().id(), at());
        assertThat(preparation.claim(tenant, request.input().id(), claimed.leaseUntil())).isNull();
        var recovery = preparation.claim(tenant, request.input().id(), claimed.leaseUntil()); assertThat(recovery.input()).isEqualTo(request.input());
        preparation.finish(claimed, evidence(authorization(request), recovery.updatedAt()), recovery.updatedAt());
        assertThat(requests.find(tenant, request.input().id())).contains(recovery); assertThat(count("supplier_payment_operation")).isZero();
        preparation.finish(recovery, evidence(authorization(request), recovery.updatedAt()), recovery.updatedAt());
        assertThat(count("supplier_payment_operation")).isEqualTo(1); assertThat(requests.owner(tenant, request.input().authorizationId()).orElseThrow().attempts()).isEqualTo(2);
    }

    @Test void rejectedOrChangedPreparationStopsOnlyThatChoiceAndNeverReleasesTheErpHold() {
        var hold = confirmed(); var request = register(hold); var claimed = preparation.claim(tenant, request.input().id(), at());
        var snapshot = snapshot(authorization(request), at()); var changed = new PaymentAccountsPort.DebitAccount(DEBIT.reference(), DEBIT.displayName(), DEBIT.maskedAccount(), "CNY", "v2");
        var directory = new PaymentAccountsPort.Directory(snapshot.directory().request(), "directory-v2", at(), at().plusSeconds(600), List.of(changed));
        preparation.finish(claimed, new FinanceResult.Success<>(new SupplierPaymentEvidenceReader.Snapshot(snapshot.held(), snapshot.payable(), directory)), at());
        assertThat(requests.find(tenant, request.input().id()).orElseThrow().status()).isEqualTo(SupplierPaymentExecutionRequest.Status.BLOCKED);
        assertThat(requests.owner(tenant, hold.command().id())).isEmpty(); assertThat(count("supplier_payment_operation")).isZero(); assertThat(holds.find(tenant, hold.command().id())).contains(hold);
        var retry = register(hold); assertThat(retry.input().id()).isNotEqualTo(request.input().id()); prepared(retry);
        assertThat(requests.find(tenant, request.input().id()).orElseThrow().status()).isEqualTo(SupplierPaymentExecutionRequest.Status.BLOCKED); assertThat(count("supplier_payment_operation")).isEqualTo(1);
    }

    @Test void unavailablePreparationRetriesWithoutInventingABankTransaction() {
        var request = register(confirmed()); var claimed = preparation.claim(tenant, request.input().id(), at());
        preparation.finish(claimed, new FinanceResult.Unavailable<>(FinanceResult.Failure.TIMEOUT), at());
        var waiting = requests.find(tenant, request.input().id()).orElseThrow(); assertThat(waiting.status()).isEqualTo(SupplierPaymentExecutionRequest.Status.QUEUED);
        assertThat(waiting.failure()).isEqualTo(SupplierPaymentExecutionRequest.Failure.TIMEOUT); assertThat(count("supplier_payment_operation")).isZero();
        assertThat(preparation.claim(tenant, request.input().id(), at())).isNull();
        var retry = preparation.claim(tenant, request.input().id(), waiting.nextAttemptAt()); preparation.finish(retry, evidence(authorization(request), retry.updatedAt()), retry.updatedAt());
        assertThat(payments.find(tenant, request.input().authorizationId()).orElseThrow().dispatches()).isZero();
    }

    @Test void realApprovalAndBothCurrentPersonnelAreRequiredBeforeNewCommandsAndSends() {
        var hold = confirmed(); var request = register(hold); disabled.add("cashier");
        assertThat(preparation.claim(tenant, request.input().id(), at())).isNull(); assertThat(requests.find(tenant, request.input().id()).orElseThrow().status()).isEqualTo(SupplierPaymentExecutionRequest.Status.VOIDED);
        disabled.clear(); var payment = prepared(register(hold)); disabled.add("finance");
        assertThat(bank.claim(tenant, payment.command().id(), at())).isNull(); assertThat(payments.find(tenant, payment.command().id()).orElseThrow().status()).isEqualTo(SupplierPaymentOperation.Status.VOIDED);
        assertThat(payments.find(tenant, payment.command().id()).orElseThrow().dispatches()).isZero(); assertThat(holds.find(tenant, hold.command().id())).contains(hold);
        disabled.clear(); var other = confirmed(); var pending = register(other); cancel(other.command().authorization());
        assertThat(preparation.claim(tenant, pending.input().id(), at())).isNull(); assertThat(count("supplier_payment_operation")).isEqualTo(1);
    }

    @Test void changedSourceDuringNetworkReadIsRecheckedBeforePossibleSend() {
        var payment = prepared(register(confirmed())); var claimed = bank.claim(tenant, payment.command().id(), at());
        cancel(payment.command().holdCommand().authorization());
        assertThat(bank.ready(claimed, evidence(payment.command().holdCommand().authorization(), at()), at())).isNull();
        var stopped = payments.find(tenant, payment.command().id()).orElseThrow(); assertThat(stopped.status()).isEqualTo(SupplierPaymentOperation.Status.VOIDED); assertThat(stopped.dispatches()).isZero();
    }

    @Test void unknownBankResultQueriesAfterAuthorizationExpiryAndSourceOrPersonnelChanges() {
        var payment = prepared(register(confirmed())); var sending = sending(payment); bank.fail(sending, at());
        cancel(payment.command().holdCommand().authorization()); disabled.addAll(List.of("cashier", "finance"));
        var later = payment.command().holdCommand().authorization().expiresAt().plusSeconds(1); var query = bank.claim(tenant, payment.command().id(), later);
        assertThat(query.status()).isEqualTo(SupplierPaymentOperation.Status.QUERYING); assertThat(query.command()).isEqualTo(payment.command());
        bank.finish(query, new FinanceResult.Success<>(paid(query.command(), later)), later);
        var success = payments.find(tenant, payment.command().id()).orElseThrow(); assertThat(success.settleable()).isTrue(); assertThat(success.dispatches()).isEqualTo(1);
        assertThat(holds.find(tenant, payment.command().id()).orElseThrow().status()).isEqualTo(SupplierPayableHoldOperation.Status.HELD);
    }

    @Test void twoBankProcessesClaimOnceAndExpiredSendRecoversOnlyByOriginalQuery() throws Exception {
        var payment = prepared(register(confirmed())); var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2); SupplierPaymentOperation claimed;
        try {
            var a = pool.submit(() -> { start.await(); return bank.claim(tenant, payment.command().id(), at()); });
            var b = pool.submit(() -> { start.await(); return bank.claim(tenant, payment.command().id(), at()); }); start.countDown();
            var first = a.get(15, TimeUnit.SECONDS); var second = b.get(15, TimeUnit.SECONDS); assertThat(first == null ^ second == null).isTrue(); claimed = first == null ? second : first;
        } finally { pool.shutdownNow(); }
        var sending = bank.ready(claimed, evidence(payment.command().holdCommand().authorization(), at()), at());
        assertThat(bank.claim(tenant, payment.command().id(), sending.leaseUntil())).isNull();
        var query = bank.claim(tenant, payment.command().id(), sending.leaseUntil()); assertThat(query.status()).isEqualTo(SupplierPaymentOperation.Status.QUERYING);
        bank.finish(sending, new FinanceResult.Success<>(paid(sending.command(), query.updatedAt())), query.updatedAt()); assertThat(payments.find(tenant, payment.command().id())).contains(query);
        bank.finish(query, new FinanceResult.Success<>(paid(query.command(), query.updatedAt())), query.updatedAt());
        assertThat(payments.find(tenant, payment.command().id()).orElseThrow().dispatches()).isEqualTo(1);
    }

    @Test void authoritativeNotFoundNeedsExplicitSameCommandRetryAndFreshRead() {
        var payment = prepared(register(confirmed())); var sending = sending(payment); bank.fail(sending, at());
        var unknown = payments.find(tenant, payment.command().id()).orElseThrow(); var query = bank.claim(tenant, payment.command().id(), unknown.nextAttemptAt());
        var missing = new PaymentObservation(query.command().id(), query.command().digest(), PaymentObservation.Status.NOT_FOUND, 0L, query.updatedAt(), null, null, null, null, null, null);
        bank.finish(query, new FinanceResult.Success<>(missing), query.updatedAt()); var absent = payments.find(tenant, payment.command().id()).orElseThrow();
        assertThat(bank.claim(tenant, payment.command().id(), query.updatedAt())).isNull();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> bank.resend(tenant, payment.command().id(), query.version(), query.updatedAt()))).isInstanceOf(DomainException.class);
        var retry = tx.execute(status -> bank.resend(tenant, payment.command().id(), absent.version(), absent.updatedAt()));
        assertThat(retry.command()).isEqualTo(payment.command()); var again = bank.claim(tenant, payment.command().id(), retry.updatedAt());
        assertThat(again.status()).isEqualTo(SupplierPaymentOperation.Status.CHECKING); assertThat(again.dispatches()).isEqualTo(1);
        var dispatch = bank.ready(again, evidence(payment.command().holdCommand().authorization(), again.updatedAt()), again.updatedAt()); assertThat(dispatch.dispatches()).isEqualTo(2);
    }

    @Test void preparationAndBankWorkersCommitBeforeEachExternalCallAndRecoverLostWriteWithQueryOnly() {
        var request = register(confirmed()); var reads = new AtomicInteger(); var writes = new AtomicInteger(); var queries = new AtomicInteger();
        var authorization = authorization(request); var holdPort = mock(SupplierPayableHoldPort.class); var payablePort = mock(ProcurementPayablePort.class); var accounts = mock(PaymentAccountsPort.class);
        when(holdPort.query(any())).thenAnswer(invocation -> {
            outside(); reads.incrementAndGet(); assertThat(invocation.<SupplierPayableHoldCommand>getArgument(0).authorization()).isEqualTo(authorization);
            return new FinanceResult.Success<>(observed(authorization, clock()));
        });
        when(payablePort.payable(eq(tenant), eq(authorization.source().reservation().source().round().targetDigest()), eq(authorization.payable().request())))
                .thenAnswer(invocation -> { outside(); return new FinanceResult.Success<>(snapshot(authorization, clock()).payable()); });
        when(accounts.debitAccounts(eq(tenant), eq(authorization.source().reservation().source().round().targetDigest()), eq(new PaymentAccountsPort.Request(entity, "CNY", "cashier"))))
                .thenAnswer(invocation -> { outside(); return new FinanceResult.Success<>(snapshot(authorization, clock()).directory()); });
        var reader = new SupplierPaymentEvidenceReader(holdPort, payablePort, accounts);
        new SupplierPaymentExecutionWorker(requests, preparation, reader).poll(); assertThat(payments.find(tenant, authorization.id()).orElseThrow().dispatches()).isZero();
        var gateway = mock(SupplierPaymentPort.class);
        when(gateway.execute(any())).thenAnswer(invocation -> {
            outside(); writes.incrementAndGet(); var persisted = payments.find(tenant, authorization.id()).orElseThrow();
            assertThat(persisted.status()).isEqualTo(SupplierPaymentOperation.Status.SENDING); assertThat(persisted.dispatches()).isEqualTo(1);
            assertThat(invocation.<SupplierPaymentCommand>getArgument(0)).isEqualTo(persisted.command()); throw new IllegalStateException("Synthetic lost bank response");
        });
        when(gateway.query(any())).thenAnswer(invocation -> { outside(); queries.incrementAndGet(); return new FinanceResult.Success<>(paid(invocation.getArgument(0), clock())); });
        var worker = new SupplierPaymentWorker(payments, bank, reader, gateway); worker.poll();
        var unknown = payments.find(tenant, authorization.id()).orElseThrow(); assertThat(unknown.status()).isEqualTo(SupplierPaymentOperation.Status.UNKNOWN);
        disabled.addAll(List.of("cashier", "finance")); cancel(authorization);
        tx.executeWithoutResult(status -> bank.query(tenant, authorization.id(), unknown.version(), clock())); worker.poll();
        assertThat(writes.get()).isEqualTo(1); assertThat(queries.get()).isEqualTo(1); assertThat(reads.get()).isEqualTo(2);
        assertThat(payments.find(tenant, authorization.id()).orElseThrow().settleable()).isTrue();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> worker.poll())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> reader.read(authorization, "cashier"))).isInstanceOf(IllegalStateException.class);
    }

    @Test void databaseRejectsReadyWithoutRegistrationAndForeignSourceRevisions() {
        var request = register(confirmed()); var claimed = preparation.claim(tenant, request.input().id(), at());
        assertThatThrownBy(() -> jdbc.update("UPDATE supplier_payment_execution_request SET status='READY',lease_until=NULL,registered_authorization_id=authorization_id WHERE tenant_id=? AND id=?", tenant, request.input().id().toString()))
                .isInstanceOf(DataIntegrityViolationException.class);
        preparation.finish(claimed, evidence(authorization(request), at()), at());
        assertThatThrownBy(() -> jdbc.update("UPDATE supplier_payment_operation SET hold_version=999 WHERE tenant_id=?", tenant)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE supplier_payment_operation SET execution_request_version=999 WHERE tenant_id=?", tenant)).isInstanceOf(DataIntegrityViolationException.class);
        var other = register(confirmed());
        assertThatThrownBy(() -> jdbc.update("UPDATE supplier_payment_operation SET execution_request_id=? WHERE tenant_id=?", other.input().id().toString(), tenant)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void corruptedCommandOrHeadersFailClosedAndRevisionAppendFailureRollsBackClaim() {
        var payment = prepared(register(confirmed())); var id = payment.command().id();
        jdbc.update("INSERT INTO supplier_payment_revision(tenant_id,operation_id,version,state_json) VALUES(?,?,2,?)", tenant, id.toString(), json.write(payment));
        assertThatThrownBy(() -> bank.claim(tenant, id, at())).isInstanceOf(DataIntegrityViolationException.class); assertThat(payments.find(tenant, id)).contains(payment);
        jdbc.update("UPDATE supplier_payment_operation SET command_digest=? WHERE tenant_id=? AND id=?", "e".repeat(64), tenant, id.toString());
        assertThatThrownBy(() -> payments.find(tenant, id)).isInstanceOf(IllegalStateException.class).hasMessageContaining("inconsistent");
    }

    @Test void v70UpgradeKeepsOriginalApprovalInvoiceClaimsAuthorizationAndConfirmedErpHold() {
        database("70"); var hold = confirmed();
        var tables = List.of("approval_application", "procurement_payment", "procurement_payment_revision", "procurement_payable_reservation", "procurement_payable_reservation_revision", "invoice_active_claim",
                "supplier_payment_authorization", "supplier_payable_hold_operation", "supplier_payable_hold_revision");
        var original = tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table + " WHERE tenant_id=?", tenant)).toList();
        var migration = Flyway.configure().dataSource(dataSource).defaultSchema(schema).target("71").load(); assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table + " WHERE tenant_id=?", tenant)).toList()).isEqualTo(original);
        assertThat(migration.migrate().migrationsExecuted).isZero(); assertThat(migration.validateWithResult().validationSuccessful).isTrue();
        assertThat(prepared(register(hold)).command().holdCommand()).isEqualTo(hold.command());
    }

    @Test void authorizationExpiresWithoutCreatingOrSendingABankCommandAndNeverReleasesOriginalHold() {
        var hold = confirmed(); var request = register(hold); var deadline = hold.command().authorization().expiresAt();
        assertThat(preparation.claim(tenant, request.input().id(), deadline)).isNull();
        assertThat(requests.find(tenant, request.input().id()).orElseThrow().status()).isEqualTo(SupplierPaymentExecutionRequest.Status.EXPIRED); assertThat(count("supplier_payment_operation")).isZero();
        var another = confirmed(); var queued = prepared(register(another));
        assertThat(bank.claim(tenant, queued.command().id(), deadline)).isNull(); assertThat(payments.find(tenant, queued.command().id()).orElseThrow().status()).isEqualTo(SupplierPaymentOperation.Status.EXPIRED);
        assertThat(holds.find(tenant, hold.command().id())).contains(hold); assertThat(holds.find(tenant, another.command().id())).contains(another);
    }

    private SupplierPaymentOperation disputedPayment() {
        var sent = sending(prepared(register(confirmed()))); var original = paid(sent.command(), sent.updatedAt());
        bank.finish(sent, new FinanceResult.Success<>(original), sent.updatedAt());
        for (long revision : List.of(2L, 3L)) {
            var before = payments.find(tenant, sent.command().id()).orElseThrow(); var observedAt = sent.updatedAt().plusSeconds(revision);
            tx.executeWithoutResult(status -> bank.query(tenant, sent.command().id(), before.version(), observedAt));
            var claim = bank.claim(tenant, sent.command().id(), observedAt);
            var incoming = new PaymentObservation(original.authorizationId(), original.commandDigest(), original.status(), revision, observedAt,
                    original.paymentReference(), original.paidAmount(), original.accountDigest(), original.completedAt(), revision == 2 ? "conflicting-receipt" : original.receiptReference(), null);
            bank.finish(claim, new FinanceResult.Success<>(incoming), observedAt);
        }
        return payments.find(tenant, sent.command().id()).orElseThrow();
    }

    private SupplierPaymentDisputeResolution decision(SupplierPaymentOperation value) {
        return new SupplierPaymentDisputeResolution(UUID.randomUUID(), tenant, value.command().id(), value.version(), value.version() + 1,
                value.conflictingObservation(), "finance", value.updatedAt(), "bank-statement-1", "核对原付款终态");
    }
    private String resolutionOutcome(SupplierPaymentDisputeResolution value) {
        try { tx.execute(status -> payments.resolve(value)); return "OK"; } catch (DomainException rejected) { return "CONFLICT"; }
    }

    @Test void returnRegistrationPreservesOriginalBankAndNanosecondEvidenceAcrossRepositoryRecreation() {
        var payment = returnedSource(); var id = payment.command().id();
        var check = returnCheck(payment, "finance", 2, returnFunds("one", "20", clock()));
        var before = returnLedgers.find(tenant, id).orElseThrow(); var decision = returnDecision(check);
        var after = tx.execute(status -> returnRegistrations.register(decision, before.version(), check.version()));
        var reopened = new JdbcSupplierPaymentReturnRepository(jdbc, json, new JdbcSupplierPaymentReturnsRepository(jdbc, json, payments, new SupplierPayableReturnGuard(jdbc), new JdbcSupplierAdjustmentCompletions(jdbc, json)),
                new JdbcSupplierPaymentReturnCheckRepository(jdbc, json), payments, new JdbcFinanceReceiptCreditRepository(jdbc));
        assertThat(reopened.history(tenant, id)).containsExactly(decision); assertThat(reopened.history("other", id)).isEmpty();
        assertThat(returnLedgers.find(tenant, id)).contains(after); assertThat(returnLedgers.find("other", id)).isEmpty();
        assertThat(returnChecks.find(tenant, check.input().id()).orElseThrow().status()).isEqualTo(SupplierPaymentReturnCheck.Status.RESOLVED);
        assertThat(after.totalReturned()).isEqualTo(money("20")); assertThat(after.reviewRequired()).isTrue();
        assertThat(payments.find(tenant, id)).contains(payment); assertThat(payments.firstSuccessfulRevision(tenant, id)).contains(payment);
        assertThat(reservations.find(tenant, payment.command().holdCommand().authorization().source().reservation().id()).orElseThrow().held()).isTrue();
        assertThat(count("supplier_payment_return_registration")).isEqualTo(1); assertThat(count("finance_receipt_credit")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT voucher_reference FROM finance_receipt_credit WHERE tenant_id=?", String.class, tenant)).isNull();
        assertThat(decision.receipt().observedAt().getNano() % 1000).isNotZero();
    }

    @Test void cumulativeReturnsOnlyReserveNewBankFundsAndKeepFirstDecisionOwner() {
        var payment = returnedSource(); var one = returnFunds("one", "20", clock());
        var first = returnCheck(payment, "finance", 2, one); var decision = returnDecision(first);
        var saved = registerReturn(first, decision);
        var second = returnCheck(payment, "finance-two", 3, one, returnFunds("two", "10", clock()));
        var next = registerReturn(second, returnDecision(second));
        assertThat(next.totalReturned()).isEqualTo(money("30")); assertThat(next.entries().get(0)).isEqualTo(saved.entries().get(0));
        assertThat(count("finance_receipt_credit")).isEqualTo(2);
        var repeated = returnCheck(payment, "finance", 4, second.receipt().returns().toArray(SupplierPaymentReturnPort.BankReceipt[]::new));
        var same = registerReturn(repeated, returnDecision(repeated));
        assertThat(same.entries()).isEqualTo(next.entries()); assertThat(count("finance_receipt_credit")).isEqualTo(2);
        assertThat(returnRegistrations.history(tenant, payment.command().id())).hasSize(3);
        assertThatThrownBy(() -> registerReturn(repeated, returnDecision(repeated))).isInstanceOf(DomainException.class);
    }

    @Test void secondReceiptFailureRollsBackFirstFundsLedgerDecisionAndCheckConsumption() {
        var payment = returnedSource(); var check = returnCheck(payment, "finance", 2,
                returnFunds("one", "20", clock()), returnFunds("two", "10", clock()));
        var before = returnLedgers.find(tenant, payment.command().id()).orElseThrow();
        jdbc.execute("ALTER TABLE finance_receipt_credit ADD CONSTRAINT reject_test_second_return CHECK (transaction_reference<>'return-two')");
        assertThatThrownBy(() -> registerReturn(check, returnDecision(check))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(returnLedgers.find(tenant, payment.command().id())).contains(before); assertThat(returnChecks.find(tenant, check.input().id())).contains(check);
        assertThat(count("finance_receipt_credit")).isZero(); assertThat(count("supplier_payment_return_registration")).isZero();
        jdbc.execute("ALTER TABLE finance_receipt_credit DROP CONSTRAINT reject_test_second_return");
        assertThat(registerReturn(check, returnDecision(check)).totalReturned()).isEqualTo(money("30"));
    }

    @Test void sameCompanyBankFundsCannotBeRegisteredAgainstAnotherSupplierPayment() {
        var first = returnedSource(); var second = returnedSource(); var funds = returnFunds("shared", "20", clock());
        var firstCheck = returnCheck(first, "finance", 2, funds); registerReturn(firstCheck, returnDecision(firstCheck));
        var secondCheck = returnCheck(second, "finance", 2, funds);
        assertThatThrownBy(() -> registerReturn(secondCheck, returnDecision(secondCheck))).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(returnLedgers.find(tenant, second.command().id()).orElseThrow().entries()).isEmpty();
        assertThat(returnChecks.find(tenant, secondCheck.input().id())).contains(secondCheck);
        assertThat(count("finance_receipt_credit")).isEqualTo(1);
    }

    @Test void unregisteredHistoryAndCurrentBankDisputeStillBlockRegistration() {
        var payment = returnedSource();
        returnCheck(payment, "finance", 2, returnFunds("one", "20", clock()));
        var missing = returnCheck(payment, "finance-two", 3, returnFunds("two", "10", clock()));
        assertThatThrownBy(() -> registerReturn(missing, returnDecision(missing))).isInstanceOf(DomainException.class);
        var all = returnCheck(payment, "finance-two", 4, returnChecks.history(tenant, payment.command().id()).stream()
                .flatMap(value -> value.receipt().returns().stream()).distinct().toArray(SupplierPaymentReturnPort.BankReceipt[]::new));
        tx.executeWithoutResult(status -> bank.query(tenant, payment.command().id(), payment.version(), clock()));
        assertThatThrownBy(() -> registerReturn(all, returnDecision(all))).isInstanceOf(DomainException.class);
        assertThat(count("supplier_payment_return_registration")).isZero();
    }

    @Test void returnReadIdentityAndSingleActiveQueryCannotBeForged() {
        var payment = returnedSource(); var request = new SupplierPaymentReturnPort.Request(payment.command(), payment.observation()); var time = clock();
        tx.executeWithoutResult(status -> returnLedgers.create(SupplierPaymentReturns.open(request, time)));
        var wrong = SupplierPaymentReturnCheck.queue(new SupplierPaymentReturnCheck.Input(UUID.randomUUID(), tenant, payment.command().targetDigest(), payment.version() - 1, request, "finance", time));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> returnChecks.create(wrong))).isInstanceOf(DomainException.class);
        var queued = SupplierPaymentReturnCheck.queue(new SupplierPaymentReturnCheck.Input(UUID.randomUUID(), tenant, payment.command().targetDigest(), payment.version(), request, "finance", time));
        String sourceTrace = UUID.randomUUID().toString();
        try (var scope = new io.agentflow.observability.DiagnosticContext(sourceTrace, tenant).open()) {
            tx.executeWithoutResult(status -> returnChecks.create(queued));
        }
        var duplicate = SupplierPaymentReturnCheck.queue(new SupplierPaymentReturnCheck.Input(UUID.randomUUID(), tenant, payment.command().targetDigest(), payment.version(), request, "finance", time));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> returnChecks.create(duplicate))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(returnChecks.due(time)).extracting(JdbcSupplierPaymentReturnCheckRepository.Candidate::tenantId,
                JdbcSupplierPaymentReturnCheckRepository.Candidate::id, JdbcSupplierPaymentReturnCheckRepository.Candidate::traceId)
                .contains(org.assertj.core.api.Assertions.tuple(tenant, queued.input().id(), sourceTrace));
        assertThat(returnChecks.find("other", queued.input().id())).isEmpty();
    }

    @Test void competingReturnDecisionsConsumeOneCheckExactlyOnce() throws Exception {
        var payment = returnedSource(); var check = returnCheck(payment, "finance", 2, returnFunds("one", "20", clock()));
        var expected = returnLedgers.find(tenant, payment.command().id()).orElseThrow().version();
        var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> { start.await(); return returnOutcome(check, expected); });
            var b = pool.submit(() -> { start.await(); return returnOutcome(check, expected); }); start.countDown();
            assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder("OK", "CONFLICT");
        } finally { pool.shutdownNow(); }
        assertThat(count("supplier_payment_return_registration")).isEqualTo(1); assertThat(count("finance_receipt_credit")).isEqualTo(1);
    }

    @Test void v79UpgradePreservesOriginalBankAndCreatesEmptyReturnLedgers() {
        database("79"); var payment = returnedSource();
        var tables = List.of("procurement_payment", "procurement_payment_revision", "procurement_payable_reservation", "procurement_payable_reservation_revision",
                "supplier_payment_authorization", "supplier_payable_hold_operation", "supplier_payable_hold_revision", "supplier_payment_execution_request",
                "supplier_payment_execution_revision", "supplier_payment_operation", "supplier_payment_revision", "supplier_payment_dispute_resolution",
                "supplier_payable_settlement_operation", "supplier_payable_settlement_revision", "supplier_settlement_completion", "supplier_settlement_dispute_resolution", "finance_receipt_credit");
        var saved = new java.util.HashMap<String, List<java.util.Map<String,Object>>>();
        tables.forEach(table -> saved.put(table, jdbc.queryForList("SELECT * FROM " + table + " WHERE tenant_id=?", tenant)));
        var migration = Flyway.configure().dataSource(dataSource).defaultSchema(schema).target("80").load();
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
        tables.forEach(table -> assertThat(jdbc.queryForList("SELECT * FROM " + table + " WHERE tenant_id=?", tenant)).containsExactlyInAnyOrderElementsOf(saved.get(table)));
        assertThat(payments.find(tenant, payment.command().id())).contains(payment);
        assertThat(count("supplier_payment_returns")).isZero(); assertThat(count("supplier_payment_return_check")).isZero();
        assertThat(migration.migrate().migrationsExecuted).isZero(); assertThat(migration.validateWithResult().validationSuccessful).isTrue();
    }

    @Test void returnWorkerReadsOutsideTransactionsAndRequiresExplicitRegistration() {
        var payment = returnedSource(); var intent = queueReturn(payment); var gateway = mock(SupplierPaymentReturnPort.class);
        when(gateway.query(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(returnChecks.find(tenant, intent.checkId()).orElseThrow().status()).isEqualTo(SupplierPaymentReturnCheck.Status.RUNNING);
            return new FinanceResult.Success<>(returnProof(invocation.getArgument(0), 2, returnFunds("worker", "20", clock())));
        });
        var worker = new SupplierPaymentReturnWorker(returnChecks, returnService, gateway); worker.poll(); worker.poll();
        verify(gateway, times(1)).query(any()); assertThat(count("finance_receipt_credit")).isZero();
        var check = returnChecks.find(tenant, intent.checkId()).orElseThrow(); var ledger = returnLedgers.find(tenant, payment.command().id()).orElseThrow();
        assertThat(ledger.reviewRequired()).isTrue(); assertThat(ledger.entries()).isEmpty(); assertThat(check.status()).isEqualTo(SupplierPaymentReturnCheck.Status.CHECKED);
        var result = returnService.register(payment.command().id(), returnInput(payment, check));
        assertThat(result.registrationId()).isNotNull(); assertThat(count("finance_receipt_credit")).isEqualTo(1);
        assertThat(returnLedgers.find(tenant, payment.command().id()).orElseThrow().totalReturned()).isEqualTo(money("20"));
        assertThat(payments.find(tenant, payment.command().id())).contains(payment);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE tenant_id=? AND action LIKE 'SUPPLIER_PAYMENT_RETURN_%'", Integer.class, tenant)).isEqualTo(2);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> worker.poll())).isInstanceOf(IllegalStateException.class);
    }

    @Test void uncertainReturnNeedsFreshIndependentConfirmationToClearOnlyItsOwnFreeze() {
        var payment = returnedSource(); var intent = queueReturn(payment); var claimed = returnService.claim(tenant, intent.checkId(), clock()); var at = clock();
        returnService.finish(claimed, new FinanceResult.Success<>(new SupplierPaymentReturnPort.Receipt(claimed.input().request(),
                SupplierPaymentReturnPort.Status.UNRESOLVED, 1, at, at.plusSeconds(120), null, List.of())), at);
        assertThat(returnLedgers.find(tenant, payment.command().id()).orElseThrow().reviewRequired()).isTrue();
        var next = queueReturn(payment); var query = returnService.claim(tenant, next.checkId(), clock());
        returnService.finish(query, new FinanceResult.Success<>(returnProof(query.input().request(), 2)), clock());
        var checked = returnChecks.find(tenant, next.checkId()).orElseThrow();
        assertThat(returnLedgers.find(tenant, payment.command().id()).orElseThrow().reviewRequired()).isTrue();
        when(returnActor.actor()).thenReturn(new Actor(tenant, "finance-two", Set.of("FINANCE")));
        assertThatThrownBy(() -> returnService.register(payment.command().id(), returnInput(payment, checked))).isInstanceOf(DomainException.class);
        when(returnActor.actor()).thenReturn(new Actor(tenant, "finance", Set.of("FINANCE")));
        returnService.register(payment.command().id(), returnInput(payment, checked));
        assertThat(returnLedgers.find(tenant, payment.command().id()).orElseThrow().reviewRequired()).isFalse();
        assertThat(count("finance_receipt_credit")).isZero();
        assertThatThrownBy(() -> returnService.register(payment.command().id(), returnInput(payment, checked))).isInstanceOf(DomainException.class);
    }

    @Test void returnRegistrationAndAuditFailureRollBackTheEntireFinancialDecision() {
        var payment = returnedSource(); var check = checkedReturn(payment, returnFunds("audit", "20", clock())); var input = returnInput(payment, check);
        var before = returnLedgers.find(tenant, payment.command().id()).orElseThrow();
        jdbc.execute("ALTER TABLE audit_event ADD CONSTRAINT reject_supplier_return_audit CHECK (action<>'SUPPLIER_PAYMENT_RETURN_REGISTER')");
        assertThatThrownBy(() -> returnService.register(payment.command().id(), input)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(returnChecks.find(tenant, check.input().id())).contains(check); assertThat(returnLedgers.find(tenant, payment.command().id())).contains(before);
        assertThat(count("finance_receipt_credit")).isZero(); assertThat(count("supplier_payment_return_registration")).isZero();
        jdbc.execute("ALTER TABLE audit_event DROP CONSTRAINT reject_supplier_return_audit");
        returnService.register(payment.command().id(), input); assertThat(count("finance_receipt_credit")).isEqualTo(1);
    }

    @Test void returnQueryExpiryAndChangedFinanceNeverAcceptALateWorkerResult() {
        var payment = returnedSource(); var intent = queueReturn(payment); var claimed = returnService.claim(tenant, intent.checkId(), clock());
        assertThat(returnService.claim(tenant, intent.checkId(), claimed.leaseUntil())).isNull();
        returnService.finish(claimed, new FinanceResult.Success<>(returnProof(claimed.input().request(), 2, returnFunds("late", "20", clock()))), claimed.leaseUntil());
        var expired = returnChecks.find(tenant, intent.checkId()).orElseThrow(); assertThat(expired.issue()).isEqualTo(SupplierPaymentReturnCheck.Issue.TIMEOUT);
        assertThat(count("finance_receipt_credit")).isZero(); var next = queueReturn(payment); disabled.add("finance");
        assertThat(returnService.claim(tenant, next.checkId(), clock())).isNull();
        assertThat(returnChecks.find(tenant, next.checkId()).orElseThrow().status()).isEqualTo(SupplierPaymentReturnCheck.Status.VOIDED);
    }

    @Test void returnServiceRechecksCurrentAccessAndOriginalBankBeforeEveryDecision() {
        var payment = returnedSource(); var check = checkedReturn(payment, returnFunds("access", "20", clock())); var input = returnInput(payment, check);
        when(returnAccess.requireFinance(payment.command().id())).thenThrow(new DomainException("FORBIDDEN", "Synthetic finance role removed"));
        assertThatThrownBy(() -> returnService.register(payment.command().id(), input)).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo("FORBIDDEN"));
        reset(returnAccess); tx.executeWithoutResult(status -> bank.query(tenant, payment.command().id(), payment.version(), clock()));
        var pending = payments.find(tenant, payment.command().id()).orElseThrow();
        assertThatThrownBy(() -> returnService.register(payment.command().id(), returnInput(pending, check))).isInstanceOfSatisfying(DomainException.class,
                error -> assertThat(error.code()).isEqualTo("SUPPLIER_PAYMENT_RETURN_PAYMENT_UNRESOLVED"));
        assertThat(count("finance_receipt_credit")).isZero(); assertThat(returnChecks.find(tenant, check.input().id())).contains(check);
    }

    @Test void returnReadFailureRetainsPriorFreezeAndDoesNotAutomaticallyRetry() {
        var payment = returnedSource(); checkedReturn(payment, returnFunds("held", "20", clock())); var before = returnLedgers.find(tenant, payment.command().id()).orElseThrow();
        var intent = queueReturn(payment); var gateway = mock(SupplierPaymentReturnPort.class);
        when(gateway.query(any())).thenThrow(new IllegalStateException("Synthetic read failure"));
        var worker = new SupplierPaymentReturnWorker(returnChecks, returnService, gateway); worker.poll(); worker.poll(); verify(gateway, times(1)).query(any());
        assertThat(returnChecks.find(tenant, intent.checkId()).orElseThrow().issue()).isEqualTo(SupplierPaymentReturnCheck.Issue.INTERNAL_ERROR);
        assertThat(returnLedgers.find(tenant, payment.command().id())).contains(before); assertThat(count("finance_receipt_credit")).isZero();
    }

    @Test void partialThenFullReturnUsesOriginalSuccessAndAddsOnlyTheRemainingBankFunds() {
        var payment = returnedSource(); var firstFunds = returnFunds("partial", "20", clock()); var first = checkedReturn(payment, firstFunds);
        var firstDecision = returnService.register(payment.command().id(), returnInput(payment, first)); var original = payment.observation();
        tx.executeWithoutResult(status -> bank.query(tenant, payment.command().id(), payment.version(), clock()));
        var bankClaim = bank.claim(tenant, payment.command().id(), clock()); var at = clock();
        var reversal = new PaymentObservation(payment.command().id(), payment.command().digest(), PaymentObservation.Status.REVERSED, 2L, at,
                original.paymentReference(), original.paidAmount(), original.accountDigest(), at, "reversal-bank-receipt", null);
        bank.finish(bankClaim, new FinanceResult.Success<>(reversal), at); var reversed = payments.find(tenant, payment.command().id()).orElseThrow();
        assertThat(reversed.status()).isEqualTo(SupplierPaymentOperation.Status.REVERSED);
        var intent = queueReturn(reversed); var claimed = returnService.claim(tenant, intent.checkId(), clock()); var observed = clock();
        var proof = new SupplierPaymentReturnPort.Receipt(claimed.input().request(), SupplierPaymentReturnPort.Status.RETURNED, 3,
                observed, observed.plusSeconds(120), reversal, List.of(firstFunds, returnFunds("remaining", "50", observed)));
        returnService.finish(claimed, new FinanceResult.Success<>(proof), observed); var check = returnChecks.find(tenant, intent.checkId()).orElseThrow();
        returnService.register(payment.command().id(), returnInput(reversed, check));
        var ledger = returnLedgers.find(tenant, payment.command().id()).orElseThrow(); assertThat(ledger.totalReturned()).isEqualTo(money("70"));
        assertThat(ledger.entries().get(0).registrationId()).isEqualTo(firstDecision.registrationId()); assertThat(ledger.reviewRequired()).isTrue();
        assertThat(payments.firstSuccessfulRevision(tenant, payment.command().id())).contains(payment); assertThat(payments.find(tenant, payment.command().id())).contains(reversed);
        assertThat(count("finance_receipt_credit")).isEqualTo(2); assertThat(count("supplier_payable_settlement_operation")).isZero();
    }

    @Test void duplicateBankReceiptIsAStableFinanceConflictAndLeavesTheSecondQueryUnconsumed() {
        var first = returnedSource(); var second = returnedSource(); var funds = returnFunds("duplicate-service", "20", clock());
        var firstCheck = checkedReturn(first, funds); returnService.register(first.command().id(), returnInput(first, firstCheck));
        var secondCheck = checkedReturn(second, funds); var before = returnLedgers.find(tenant, second.command().id()).orElseThrow();
        assertThatThrownBy(() -> returnService.register(second.command().id(), returnInput(second, secondCheck))).isInstanceOfSatisfying(DomainException.class,
                error -> assertThat(error.code()).isEqualTo("SUPPLIER_PAYMENT_RETURN_ALREADY_RECORDED"));
        assertThat(returnChecks.find(tenant, secondCheck.input().id())).contains(secondCheck); assertThat(returnLedgers.find(tenant, second.command().id())).contains(before);
        assertThat(count("finance_receipt_credit")).isEqualTo(1); assertThat(count("supplier_payment_return_registration")).isEqualTo(1);
    }

    private SupplierPaymentReturnService.ActionReceipt queueReturn(SupplierPaymentOperation payment) {
        var version = returnLedgers.find(tenant, payment.command().id()).map(SupplierPaymentReturns::version).orElse(0L);
        return returnService.queue(payment.command().id(), new SupplierPaymentReturnService.QueryInput(payment.version(), version, "查询原付款实际入款"));
    }
    private SupplierPaymentReturnCheck checkedReturn(SupplierPaymentOperation payment, SupplierPaymentReturnPort.BankReceipt... funds) {
        var intent = queueReturn(payment); var claimed = returnService.claim(tenant, intent.checkId(), clock());
        returnService.finish(claimed, new FinanceResult.Success<>(returnProof(claimed.input().request(), 2, funds)), clock());
        return returnChecks.find(tenant, intent.checkId()).orElseThrow();
    }
    private SupplierPaymentReturnPort.Receipt returnProof(SupplierPaymentReturnPort.Request request, long revision, SupplierPaymentReturnPort.BankReceipt... funds) {
        var at = clock(); var original = request.original();
        var current = new PaymentObservation(original.authorizationId(), original.commandDigest(), original.status(), original.revision(), at,
                original.paymentReference(), original.paidAmount(), original.accountDigest(), original.completedAt(), original.receiptReference(), null);
        return new SupplierPaymentReturnPort.Receipt(request, funds.length == 0 ? SupplierPaymentReturnPort.Status.CONFIRMED : SupplierPaymentReturnPort.Status.PARTIALLY_RETURNED,
                revision, at, at.plusSeconds(120), current, List.of(funds));
    }
    private SupplierPaymentReturnService.RegisterInput returnInput(SupplierPaymentOperation payment, SupplierPaymentReturnCheck check) {
        return new SupplierPaymentReturnService.RegisterInput(payment.version(), returnLedgers.find(tenant, payment.command().id()).orElseThrow().version(),
                check.input().id(), check.version(), check.receipt().status(), "bank-statement-1", "核对原公司账户已实际收到回款");
    }

    private SupplierPaymentOperation returnedSource() {
        var sending = sending(prepared(register(confirmed()))); bank.finish(sending, new FinanceResult.Success<>(paid(sending.command(), at())), at());
        return payments.find(tenant, sending.command().id()).orElseThrow();
    }
    private SupplierPaymentReturnCheck returnCheck(SupplierPaymentOperation payment, String actor, long revision, SupplierPaymentReturnPort.BankReceipt... funds) {
        var original = payments.firstSuccessfulRevision(tenant, payment.command().id()).orElseThrow();
        var request = new SupplierPaymentReturnPort.Request(original.command(), original.observation());
        var time = clock().plusNanos(321); var observed = time.plusNanos(123);
        return tx.execute(status -> {
            if (returnLedgers.find(tenant, payment.command().id()).isEmpty()) returnLedgers.create(SupplierPaymentReturns.open(request, time));
            var queued = SupplierPaymentReturnCheck.queue(new SupplierPaymentReturnCheck.Input(UUID.randomUUID(), tenant, payment.command().targetDigest(), original.version(), request, actor, time));
            returnChecks.create(queued); var running = queued.claim(time, java.time.Duration.ofSeconds(90)); returnChecks.update(running);
            var bankReceipt = new PaymentObservation(payment.command().id(), payment.command().digest(), PaymentObservation.Status.SUCCEEDED, revision, observed,
                    original.observation().paymentReference(), payment.command().amount(), payment.command().payee().accountDigest(), original.observation().completedAt(), original.observation().receiptReference(), null);
            var proof = new SupplierPaymentReturnPort.Receipt(request, funds.length == 0 ? SupplierPaymentReturnPort.Status.CONFIRMED : SupplierPaymentReturnPort.Status.PARTIALLY_RETURNED,
                    revision, observed, observed.plusSeconds(120), bankReceipt, List.of(funds));
            var checked = running.complete(new FinanceResult.Success<>(proof), observed); returnChecks.update(checked);
            returnLedgers.requireReview(tenant, payment.command().id(), observed); return checked;
        });
    }
    private SupplierPaymentReturnPort.BankReceipt returnFunds(String id, String amount, Instant time) {
        return new SupplierPaymentReturnPort.BankReceipt("return-" + id, DEBIT.reference(), money(amount), time);
    }
    private SupplierPaymentReturn returnDecision(SupplierPaymentReturnCheck check) {
        return new SupplierPaymentReturn(UUID.randomUUID(), tenant, check.input().id(), check.receipt(), check.input().requestedBy(), check.updatedAt().plusNanos(123), "bank-statement", "核对原供应商回款");
    }
    private SupplierPaymentReturns registerReturn(SupplierPaymentReturnCheck check, SupplierPaymentReturn decision) {
        return tx.execute(status -> returnRegistrations.register(decision, returnLedgers.find(tenant, check.input().request().command().id()).orElseThrow().version(), check.version()));
    }
    private String returnOutcome(SupplierPaymentReturnCheck check, long expected) {
        try { tx.execute(status -> returnRegistrations.register(returnDecision(check), expected, check.version())); return "OK"; }
        catch (DomainException rejected) { return "CONFLICT"; }
    }

    private SupplierPaymentAuthorization approved() {
        var ref = UUID.randomUUID().toString(); var content = new ProcurementPaymentContent(entity, "采购付款", "已验收货物付款", "supplier-1", ref, money("70"));
        var request = ProcurementPaymentRequest.draft(UUID.randomUUID(), tenant, UUID.randomUUID(), "alice", content);
        var payable = payable(request); var catalog = new FinanceCatalog("alice", "v1", now.plusSeconds(600), List.of(new FinanceCatalog.LegalEntity(entity, "法人", "CNY", false, "v1", "Asia/Shanghai")), List.of(), List.of(), List.of(), List.of());
        var initiator = new InitiatorContext(UUID.randomUUID(), UUID.randomUUID(), "alice", 1, entity, "法人", UUID.randomUUID(), "部门", UUID.randomUUID(), "岗位");
        return tx.execute(status -> {
            jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id) VALUES(?,?,?,'fixture',1,'alice','采购付款','{}','DRAFT',1,1,'PROCUREMENT_PAYMENT',?)",
                    request.applicationId().toString(), tenant, ref, request.id().toString());
            procurements.create(request, "alice"); request.freeze(1, 1, catalog, "a".repeat(64), payable, initiator, now); procurements.update(request, 1, "alice", "SUBMIT");
            var hold = ProcurementPayableReservation.hold(UUID.randomUUID(), request, now); reservations.create(hold);
            request.approve(2, 1, 8, "manager", now.plusSeconds(1)); procurements.update(request, 2, "manager", "APPROVE");
            jdbc.update("UPDATE approval_application SET status='APPROVED',version=8,payload_json=? WHERE tenant_id=? AND id=?", json.write(ProcurementPaymentFormContract.submittedPayload(request.currentRound())), tenant, request.applicationId().toString());
            return new SupplierPaymentAuthorization(UUID.randomUUID(), ApprovedProcurementPayment.from(request, hold), payable, "finance", authorizedAt(), authorizedAt().plusSeconds(86400));
        });
    }
    private ProcurementPayablePort.Payable payable(ProcurementPaymentRequest request) {
        var line = new ProcurementPayablePort.MatchedLine(1, 1, "receipt-1", new InvoiceKey(InvoiceKey.Type.DIGITAL, null, String.format("%020d", Integer.toUnsignedLong(request.id().hashCode()))), 1,
                "c".repeat(64), "verified-1", "件", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, money("100"), money("100"), money("100"), money("6"));
        return new ProcurementPayablePort.Payable(request.content().payableRequest("alice"), "v1", now, now.plusSeconds(600), "供应商",
                new SupplierAccountSnapshot(entity, "supplier-1", "supplier-account", "****1234", "b".repeat(64), "v1"), "contract", "order", "matching", "voucher", "budget", LocalDate.parse("2026-10-01"), money("100"), money("30"), List.of(line));
    }
    private SupplierPayableHoldOperation confirmed() {
        var authorization = approved(); tx.executeWithoutResult(status -> holdService.register(authorization, authorizedAt()));
        var claimed = holdService.claim(tenant, authorization.id(), authorizedAt()); holdService.finish(claimed, new FinanceResult.Success<>(observed(authorization, authorizedAt().plusSeconds(1))), authorizedAt().plusSeconds(1));
        return holds.find(tenant, authorization.id()).orElseThrow();
    }
    private SupplierPaymentExecutionRequest register(SupplierPayableHoldOperation hold) { return tx.execute(status -> preparation.register(tenant, hold.command().id(), hold.version(), "cashier", DEBIT.reference(), DEBIT.sourceVersion(), at())); }
    private String registrationOutcome(SupplierPayableHoldOperation hold) { try { register(hold); return "OK"; } catch (DomainException error) { return error.code(); } }
    private SupplierPaymentAuthorization authorization(SupplierPaymentExecutionRequest request) { return authorizations.find(tenant, request.input().authorizationId()).orElseThrow(); }
    private SupplierPaymentOperation prepared(SupplierPaymentExecutionRequest request) {
        var claimed = preparation.claim(tenant, request.input().id(), at()); preparation.finish(claimed, evidence(authorization(request), at()), at()); return payments.find(tenant, request.input().authorizationId()).orElseThrow();
    }
    private SupplierPaymentOperation sending(SupplierPaymentOperation payment) {
        var claimed = bank.claim(tenant, payment.command().id(), at()); return bank.ready(claimed, evidence(payment.command().holdCommand().authorization(), at()), at());
    }
    private FinanceResult<SupplierPaymentEvidenceReader.Snapshot> evidence(SupplierPaymentAuthorization authorization, Instant at) { return new FinanceResult.Success<>(snapshot(authorization, at)); }
    private SupplierPaymentEvidenceReader.Snapshot snapshot(SupplierPaymentAuthorization authorization, Instant at) {
        var old = authorization.payable(); var payable = new ProcurementPayablePort.Payable(old.request(), old.sourceVersion(), at, at.plusSeconds(600), old.supplierName(), old.account(), old.contractReference(),
                old.orderReference(), old.matchingReference(), old.accrualVoucherReference(), old.budgetRecognitionReference(), old.dueOn(), old.gross(), old.settled(), old.lines());
        var directory = new PaymentAccountsPort.Directory(new PaymentAccountsPort.Request(entity, "CNY", "cashier"), "directory-v1", at, at.plusSeconds(600), List.of(DEBIT));
        return new SupplierPaymentEvidenceReader.Snapshot(observed(authorization, at), payable, directory);
    }
    private SupplierPayableHoldObservation observed(SupplierPaymentAuthorization authorization, Instant at) {
        return new SupplierPayableHoldObservation(authorization.id(), new SupplierPayableHoldCommand(authorization).digest(), SupplierPayableHoldObservation.Status.HELD, 1L, at,
                "erp-hold-1", "ledger-1", authorization.source().amount(), authorization.payable().account().accountDigest(), authorizedAt(), null);
    }
    private PaymentObservation paid(SupplierPaymentCommand command, Instant at) { return new PaymentObservation(command.id(), command.digest(), PaymentObservation.Status.SUCCEEDED, 1L, at, "bank-1", command.amount(), command.payee().accountDigest(), command.registeredAt(), "receipt-1", null); }
    private void cancel(SupplierPaymentAuthorization authorization) { jdbc.update("UPDATE approval_application SET status='CANCELLED' WHERE tenant_id=? AND id=?", tenant, authorization.source().reservation().source().applicationId().toString()); }
    private void outside() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); }
    @SuppressWarnings("unchecked") private <T> T proxy(T value) {
        var factory = new ProxyFactory(value); factory.setProxyTargetClass(true); factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource())); return (T) factory.getProxy();
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id=?", Integer.class, tenant); }
    private Instant at() { return now.plusSeconds(4); }
    private Instant authorizedAt() { return now.plusSeconds(2); }
    private Instant clock() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private static Money money(String amount) { return new Money(new BigDecimal(amount), "CNY"); }
}
