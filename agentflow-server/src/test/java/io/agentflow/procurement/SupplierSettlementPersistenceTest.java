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
import io.agentflow.finance.AccountingPeriodPort;
import java.time.Duration;
import java.time.ZoneId;
import io.agentflow.finance.FinanceResult;
import io.agentflow.finance.Money;
import io.agentflow.finance.PaymentAccountsPort;
import io.agentflow.finance.PaymentObservation;
import io.agentflow.finance.PaymentPersonnel;
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
 * 真实数据库验证财务结算准备、原银行独占、安全结束及占用完成的原子边界。
 * @author owlzhangfq@gmail.com
 */
class SupplierSettlementPersistenceTest {
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
    private JdbcSupplierSettlementPreparationRepository intents;
    private JdbcSupplierPayableSettlementRepository settlements;
    private SupplierSettlementPreparationService settlementPreparation;
    private SupplierSettlementService settlementExecution;
    private static final PaymentAccountsPort.DebitAccount DEBIT = new PaymentAccountsPort.DebitAccount("debit-1", "法人基本户", "****5678", "CNY", "v1");

    @BeforeEach void database() { database(null); }

    private void database(String target) {
        dataSource = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_SUPPLIER_SETTLEMENT_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_SUPPLIER_SETTLEMENT_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_SUPPLIER_SETTLEMENT_PASSWORD", ""));
        schema = "supplier_settlement_" + UUID.randomUUID().toString().replace("-", ""); new JdbcTemplate(dataSource).execute("CREATE SCHEMA \"" + schema + "\""); dataSource.setSchema(schema);
        var migration = Flyway.configure().dataSource(dataSource).defaultSchema(schema); if (target != null) migration.target(target); migration.load().migrate();
        jdbc = new JdbcTemplate(dataSource); manager = new DataSourceTransactionManager(dataSource); tx = new TransactionTemplate(manager);
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
        intents = new JdbcSupplierSettlementPreparationRepository(jdbc, json, payments);
        settlements = new JdbcSupplierPayableSettlementRepository(jdbc, json, intents, payments, reservations);
        var settlementSources = new SupplierSettlementSources(sources, approvedSources, payments, personnel, returnGuard);
        settlementPreparation = proxy(new SupplierSettlementPreparationService(settlementSources, intents, settlements, org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class), 30));
        settlementExecution = proxy(new SupplierSettlementService(settlementSources, settlements, reservations, org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class), 30));
    }

    @Test void immutableIntentAndCommandSurviveRecreationWithExactPaidSourceAndTenantIsolation() {
        var payment = supplierBank(); var queued = settlement(payment); var id = queued.command().id();
        var reopenedIntents = new JdbcSupplierSettlementPreparationRepository(jdbc, json, payments);
        var reopened = new JdbcSupplierPayableSettlementRepository(jdbc, json, reopenedIntents, payments, reservations);
        assertThat(reopened.find(tenant, id)).contains(queued); assertThat(reopened.find("other", id)).isEmpty();
        assertThat(reopenedIntents.find(tenant, id).orElseThrow().status()).isEqualTo(SupplierSettlementPreparation.Status.READY);
        assertThat(reopenedIntents.active(tenant, payment.command().id())).isEmpty(); assertThat(reopened.active(tenant, payment.command().id())).contains(queued);
        assertThat(reopened.revision(tenant, id, 1)).contains(queued); assertThat(reopenedIntents.revision(tenant, id, 2).orElseThrow().input().payment()).isEqualTo(payment);
        assertThat(count("supplier_payment_operation")).isEqualTo(1); assertThat(count("supplier_settlement_preparation_revision")).isEqualTo(3);
        assertThatThrownBy(() -> intent(payment)).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo("SUPPLIER_SETTLEMENT_PENDING"));
    }

    @Test void twoFinancePreparationsCannotOwnTheSameOriginalPayment() throws Exception {
        var payment = supplierBank(); var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { start.await(); return intentOutcome(payment); });
            var second = pool.submit(() -> { start.await(); return intentOutcome(payment); }); start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder("OK", "SUPPLIER_SETTLEMENT_PENDING");
        } finally { pool.shutdownNow(); }
        assertThat(count("supplier_settlement_preparation")).isEqualTo(1); assertThat(count("supplier_payable_settlement_operation")).isZero();
    }

    @Test void fabricatedPaidStateOrChangedCurrentBankCannotRegisterANewIntent() {
        var queued = prepared(register(confirmed())); var sent = sending(queued);
        var fabricated = sent.complete(new FinanceResult.Success<>(paid(sent.command(), at())), at());
        assertThatThrownBy(() -> intent(fabricated)).isInstanceOf(DomainException.class); assertThat(count("supplier_settlement_preparation")).isZero();
        bank.finish(sent, new FinanceResult.Success<>(paid(sent.command(), at())), at());
        tx.executeWithoutResult(status -> bank.query(tenant, fabricated.command().id(), fabricated.version(), at()));
        assertThatThrownBy(() -> intent(fabricated)).isInstanceOf(DomainException.class); assertThat(count("supplier_settlement_preparation")).isZero();
    }

    @Test void readyRevisionFailureRollsBackTheCommandAndPreservesTheOriginalReadClaim() {
        var claimed = claim(intent(supplierBank())); var id = claimed.input().id();
        jdbc.update("INSERT INTO supplier_settlement_preparation_revision(tenant_id,preparation_id,version,state_json) VALUES(?,?,?,?)", tenant, id.toString(), claimed.version() + 1, json.write(claimed));
        assertThatThrownBy(() -> registerSettlement(claimed)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(intents.find(tenant, id)).contains(claimed); assertThat(count("supplier_payable_settlement_operation")).isZero(); assertThat(count("supplier_payable_settlement_revision")).isZero();
    }

    @Test void expiredReadReclaimsSameInputAndRejectsLateCommandRegistration() {
        var claimed = claim(intent(supplierBank()));
        var expired = claimed.expireLease(claimed.leaseUntil()); tx.executeWithoutResult(status -> intents.update(expired));
        var next = expired.claim(expired.updatedAt(), Duration.ofSeconds(30)); tx.executeWithoutResult(status -> intents.update(next));
        assertThatThrownBy(() -> registerSettlement(claimed)).isInstanceOf(DomainException.class);
        var queued = registerSettlement(next); assertThat(queued.command().id()).isEqualTo(claimed.input().id()); assertThat(next.input()).isEqualTo(claimed.input());
        assertThat(count("supplier_payable_settlement_operation")).isEqualTo(1);
    }

    @Test void namedSafeRetirementKeepsHistoryAndAllowsANewDateWithoutANewBankPayment() {
        var payment = supplierBank(); var old = settlement(payment); var checking = old.claim(old.updatedAt(), Duration.ofSeconds(30));
        tx.executeWithoutResult(status -> settlements.update(checking)); var stopped = checking.stopForRetirement(checking.updatedAt());
        var decision = SupplierSettlementRetirement.from(stopped, "finance", stopped.updatedAt());
        tx.executeWithoutResult(status -> { sources.lock(tenant, payment.command().id()); settlements.update(stopped); settlements.retire(tenant, decision); });
        assertThat(settlements.active(tenant, payment.command().id())).isEmpty(); assertThat(settlements.find(tenant, old.command().id())).contains(stopped);
        assertThat(settlements.retirement(tenant, old.command().id())).contains(decision); assertThat(settlements.due(stopped.updatedAt())).isEmpty();
        var fresh = SupplierSettlementPreparation.queue(UUID.randomUUID(), payment, "finance", date().plusDays(1), at());
        tx.executeWithoutResult(status -> { sources.lock(tenant, payment.command().id()); intents.create(fresh); }); var newer = registerSettlement(claim(fresh));
        assertThat(newer.command().id()).isNotEqualTo(old.command().id()); assertThat(newer.command().period().request().accountingDate()).isEqualTo(date().plusDays(1));
        assertThat(settlements.history(tenant, payment.command().id())).hasSize(2); assertThat(count("supplier_payment_operation")).isEqualTo(1);
        assertThat(intents.find(tenant, old.command().id()).orElseThrow().status()).isEqualTo(SupplierSettlementPreparation.Status.READY);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> settlements.retire(tenant, decision))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> settlements.update(checking.readyToSend(settlementEvidence(payment, checking.updatedAt(), date()), checking.updatedAt())))).isInstanceOf(DomainException.class);
    }

    @Test void retiredRejectionCannotBeRequeriedAndReleaseMarkerCannotBypassNamedEvidence() {
        var sent = dispatch(settlement(supplierBank()));
        var receipt = new SupplierPayableSettlementObservation(sent.command().id(), sent.command().digest(), SupplierPayableSettlementObservation.Status.REJECTED, 1L, sent.updatedAt(), null, SupplierPayableSettlementObservation.Rejection.ACCOUNTING_PERIOD_CLOSED);
        var rejected = sent.complete(new FinanceResult.Success<>(receipt), sent.updatedAt()); tx.executeWithoutResult(status -> settlements.update(rejected));
        assertThatThrownBy(() -> jdbc.update("UPDATE supplier_payable_settlement_operation SET retired_version=version,active_payment_id=NULL WHERE tenant_id=?", tenant)).isInstanceOf(DataIntegrityViolationException.class);
        var decision = SupplierSettlementRetirement.from(rejected, "finance", rejected.updatedAt()); tx.executeWithoutResult(status -> settlements.retire(tenant, decision));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> settlements.update(rejected.requestQuery(rejected.updatedAt())))).isInstanceOf(DomainException.class);
        assertThat(settlements.find(tenant, rejected.command().id())).contains(rejected); assertThat(count("supplier_payable_settlement_revision")).isEqualTo(4);
    }

    @Test void unknownAndAlreadySettledCannotForgeSafeRetirementOrChangeAccountingDate() {
        for (boolean rejected : new boolean[] { false, true }) {
            var payment = supplierBank(); var sent = dispatch(settlement(payment));
            var result = rejected ? sent.complete(new FinanceResult.Success<>(new SupplierPayableSettlementObservation(sent.command().id(), sent.command().digest(), SupplierPayableSettlementObservation.Status.REJECTED, 1L, sent.updatedAt(), null, SupplierPayableSettlementObservation.Rejection.ALREADY_SETTLED)), sent.updatedAt())
                    : sent.unavailable(SupplierPayableSettlementOperation.Failure.TIMEOUT, sent.updatedAt());
            tx.executeWithoutResult(status -> settlements.update(result));
            var fake = new SupplierSettlementRetirement(result.command().id(), payment.command().id(), result.version(), SupplierPayableSettlementOperation.RetirementBasis.CONFIRMED_REJECTED, "finance", result.updatedAt());
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> settlements.retire(tenant, fake))).isInstanceOf(DomainException.class);
            assertThatThrownBy(() -> intent(payment)).isInstanceOf(DomainException.class); assertThat(settlements.active(tenant, payment.command().id())).contains(result);
        }
        assertThat(count("supplier_settlement_retirement")).isZero();
    }

    @Test void actualSettlementCompletesOnlyOriginalReservationAndPreservesEverySourceFact() {
        var payment = supplierBank(); var sent = dispatch(settlement(payment)); var original = sent.command().payment().holdCommand().authorization().source().reservation();
        var done = settled(sent);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> reservations.complete(done, payment, done.updatedAt()))).isInstanceOf(DomainException.class);
        var completed = tx.execute(status -> { settlements.update(done); return reservations.complete(done, payment, done.updatedAt()); });
        assertThat(reservations.active(tenant, original.source().requestId())).isEmpty(); assertThat(reservations.history(tenant, original.source().requestId())).containsExactly(completed);
        assertThat(completed.release()).isNull(); assertThat(completed.source()).isEqualTo(original.source()); assertThat(completed.settlement().operationVersion()).isEqualTo(done.version());
        assertThat(completed.heldAt()).isEqualTo(original.heldAt()); assertThat(count("supplier_settlement_completion")).isEqualTo(1);
        assertThat(settlements.active(tenant, payment.command().id())).contains(done); assertThat(payments.find(tenant, payment.command().id())).contains(payment);
        assertThat(holds.find(tenant, payment.command().id()).orElseThrow().status()).isEqualTo(SupplierPayableHoldOperation.Status.HELD);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> reservations.complete(done, payment, done.updatedAt()))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> reservations.release(completed))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> intent(payment)).isInstanceOf(DomainException.class);
    }

    @Test void completionFailureRollsBackErpResultProofAndLocalOccupancyAsOneUnit() {
        var payment = supplierBank(); var sent = dispatch(settlement(payment)); var original = sent.command().payment().holdCommand().authorization().source().reservation();
        jdbc.update("INSERT INTO procurement_payable_reservation_revision(tenant_id,reservation_id,version,state_json) VALUES(?,?,2,?)", tenant, original.id().toString(), json.write(original));
        var done = settled(sent);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> { settlements.update(done); reservations.complete(done, payment, done.updatedAt()); })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(settlements.find(tenant, sent.command().id())).contains(sent); assertThat(reservations.active(tenant, original.source().requestId())).contains(original);
        assertThat(count("supplier_settlement_completion")).isZero(); assertThat(settlements.revision(tenant, sent.command().id(), done.version())).isEmpty();
    }

    @Test void currentBankChangeBlocksCompletionButLaterQueriesDoNotEraseHistoricalCompletion() {
        var payment = supplierBank(); var done = settled(dispatch(settlement(payment))); tx.executeWithoutResult(status -> settlements.update(done));
        var querying = tx.execute(status -> bank.query(tenant, payment.command().id(), payment.version(), done.updatedAt()));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> reservations.complete(done, payment, done.updatedAt()))).isInstanceOf(DomainException.class);
        var claim = bank.claim(tenant, payment.command().id(), querying.updatedAt()); bank.finish(claim, new FinanceResult.Success<>(paid(payment.command(), claim.updatedAt())), claim.updatedAt());
        var currentBank = payments.find(tenant, payment.command().id()).orElseThrow();
        var completed = tx.execute(status -> reservations.complete(done, currentBank, currentBank.updatedAt()));
        tx.executeWithoutResult(status -> bank.query(tenant, currentBank.command().id(), currentBank.version(), currentBank.updatedAt()));
        assertThat(reservations.history(tenant, completed.source().requestId())).containsExactly(completed);
    }

    @Test void foreignKeysRejectFabricatedReadyPaymentRevisionAndCompletionMarkers() {
        var payment = supplierBank(); var pending = intent(payment);
        assertThatThrownBy(() -> jdbc.update("UPDATE supplier_settlement_preparation SET status='READY',attempts=1,active_payment_id=NULL,next_attempt_at=NULL,registered_operation_id=id WHERE tenant_id=?", tenant)).isInstanceOf(DataIntegrityViolationException.class);
        var queued = registerSettlement(claim(pending));
        assertThatThrownBy(() -> jdbc.update("UPDATE supplier_payable_settlement_operation SET payment_version=999 WHERE tenant_id=?", tenant)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE supplier_payable_settlement_operation SET preparation_version=999 WHERE tenant_id=?", tenant)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE procurement_payable_reservation SET version=2,active_request_id=NULL,active_payable_reference=NULL,settled_at=CURRENT_TIMESTAMP,settlement_id=?,settlement_version=1 WHERE tenant_id=?", queued.command().id().toString(), tenant)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE supplier_payable_settlement_operation SET command_digest=? WHERE tenant_id=?", "f".repeat(64), tenant);
        assertThatThrownBy(() -> settlements.find(tenant, queued.command().id())).isInstanceOf(IllegalStateException.class).hasMessageContaining("inconsistent");
    }

    @Test void completionTimeCannotPrecedeItsActualBankEvidenceRevision() {
        var payment = supplierBank(); var done = settled(dispatch(settlement(payment))); tx.executeWithoutResult(status -> settlements.update(done));
        var later = done.updatedAt().plusSeconds(1); tx.executeWithoutResult(status -> bank.query(tenant, payment.command().id(), payment.version(), later));
        var claim = bank.claim(tenant, payment.command().id(), later); bank.finish(claim, new FinanceResult.Success<>(paid(payment.command(), later)), later);
        var refreshed = payments.find(tenant, payment.command().id()).orElseThrow();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> reservations.complete(done, refreshed, done.updatedAt()))).isInstanceOf(DomainException.class);
        assertThat(count("supplier_settlement_completion")).isZero();
    }

    @Test void v72UpgradePreservesOriginalPaidBankAndAllExistingSourceRows() {
        database("72"); var payment = supplierBank();
        var tables = List.of("approval_application", "procurement_payment", "procurement_payment_revision", "procurement_payable_reservation_revision", "invoice_active_claim",
                "supplier_payment_authorization", "supplier_payable_hold_operation", "supplier_payable_hold_revision", "supplier_payment_execution_request", "supplier_payment_execution_revision", "supplier_payment_operation", "supplier_payment_revision");
        var original = tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table + " WHERE tenant_id=?", tenant)).toList();
        var reservationsBefore = reservations.history(tenant, payment.command().holdCommand().authorization().source().reservation().source().requestId());
        var migration = Flyway.configure().dataSource(dataSource).defaultSchema(schema).target("73").load(); assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table + " WHERE tenant_id=?", tenant)).toList()).isEqualTo(original);
        assertThat(reservations.history(tenant, reservationsBefore.get(0).source().requestId())).isEqualTo(reservationsBefore);
        assertThat(migration.migrate().migrationsExecuted).isZero(); assertThat(migration.validateWithResult().validationSuccessful).isTrue();
        assertThat(settlement(payment).command().payment()).isEqualTo(payment.command());
    }

    @Test void explicitCurrentFinanceCanSettlePaidBankAfterOriginalAuthorizationExpiresAndCashierLeaves() {
        var payment = supplierBank(); disabled.addAll(List.of("finance", "cashier")); var later = payment.command().holdCommand().authorization().expiresAt().plusSeconds(1);
        var date = later.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        var pending = tx.execute(status -> settlementPreparation.register(tenant, payment.command().id(), payment.version(), "finance-2", date, later));
        var claimed = settlementPreparation.claim(tenant, pending.input().id(), later);
        settlementPreparation.finish(claimed, read(payment, later, date), later.plusSeconds(1));
        var queued = settlements.find(tenant, pending.input().id()).orElseThrow(); assertThat(queued.command().financeActor()).isEqualTo("finance-2");
        assertThat(queued.command().payment()).isEqualTo(payment.command()); assertThat(queued.command().registeredAt()).isEqualTo(later.plusSeconds(1));
        var checking = settlementExecution.claim(tenant, queued.command().id(), queued.updatedAt());
        var sending = settlementExecution.ready(checking, read(payment, checking.updatedAt(), date), checking.updatedAt());
        settlementExecution.finish(sending, new FinanceResult.Success<>(settled(sending).observation()), sending.updatedAt());
        assertThat(settlements.find(tenant, queued.command().id()).orElseThrow().settled()).isTrue();
        assertThat(count("supplier_settlement_completion")).isEqualTo(1); assertThat(payments.find(tenant, payment.command().id())).contains(payment);
    }

    @Test void preparationDistinguishesUnavailableReadsFromClosedPeriodsAndPreservesIntent() {
        var payment = supplierBank(); var pending = serviceIntent(payment); var claimed = settlementPreparation.claim(tenant, pending.input().id(), at());
        settlementPreparation.finish(claimed, new FinanceResult.Unavailable<>(FinanceResult.Failure.TIMEOUT), at());
        var waiting = intents.find(tenant, pending.input().id()).orElseThrow(); assertThat(waiting.input()).isEqualTo(pending.input()); assertThat(waiting.status()).isEqualTo(SupplierSettlementPreparation.Status.QUEUED);
        assertThat(settlementPreparation.claim(tenant, pending.input().id(), at())).isNull();
        var retry = settlementPreparation.claim(tenant, pending.input().id(), waiting.nextAttemptAt());
        settlementPreparation.finish(retry, new FinanceResult.Rejected<>(FinanceResult.Reason.ACCOUNTING_PERIOD_CLOSED), retry.updatedAt());
        var blocked = intents.find(tenant, pending.input().id()).orElseThrow(); assertThat(blocked.status()).isEqualTo(SupplierSettlementPreparation.Status.BLOCKED);
        assertThat(blocked.issue()).isEqualTo(SupplierSettlementPreparation.Issue.ACCOUNTING_PERIOD_REJECTED); assertThat(count("supplier_payable_settlement_operation")).isZero();
        assertThat(serviceIntent(payment).input().id()).isNotEqualTo(pending.input().id());
    }

    @Test void sourceChangesDuringPreparationAndSendReadStopOnlyTheOriginalUnsentIntent() {
        var payment = supplierBank(); var pending = serviceIntent(payment); var claimed = settlementPreparation.claim(tenant, pending.input().id(), at());
        disabled.add("finance"); settlementPreparation.finish(claimed, read(payment, at(), date()), at());
        assertThat(intents.find(tenant, pending.input().id()).orElseThrow().status()).isEqualTo(SupplierSettlementPreparation.Status.VOIDED);
        assertThat(count("supplier_payable_settlement_operation")).isZero(); disabled.clear();
        var queued = serviceSettlement(payment); var checking = settlementExecution.claim(tenant, queued.command().id(), at());
        tx.executeWithoutResult(status -> bank.query(tenant, payment.command().id(), payment.version(), at()));
        assertThat(settlementExecution.ready(checking, read(payment, at(), date()), at())).isNull();
        var stopped = settlements.find(tenant, queued.command().id()).orElseThrow(); assertThat(stopped.status()).isEqualTo(SupplierPayableSettlementOperation.Status.VOIDED); assertThat(stopped.dispatches()).isZero();
        assertThat(count("supplier_settlement_completion")).isZero(); assertThat(holds.find(tenant, payment.command().id()).orElseThrow().status()).isEqualTo(SupplierPayableHoldOperation.Status.HELD);
    }

    @Test void concurrentClaimsAndExpiredPossibleSendRecoverWithQueryAndIgnoreLateReceipts() throws Exception {
        var queued = serviceSettlement(supplierBank()); var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2); SupplierPayableSettlementOperation claim;
        try {
            var first = pool.submit(() -> { start.await(); return settlementExecution.claim(tenant, queued.command().id(), at()); });
            var second = pool.submit(() -> { start.await(); return settlementExecution.claim(tenant, queued.command().id(), at()); }); start.countDown();
            var a = first.get(15, TimeUnit.SECONDS); var b = second.get(15, TimeUnit.SECONDS); assertThat(a == null ^ b == null).isTrue(); claim = a == null ? b : a;
        } finally { pool.shutdownNow(); }
        var payment = payments.find(tenant, queued.command().payment().id()).orElseThrow(); var sent = settlementExecution.ready(claim, read(payment, at(), date()), at());
        assertThat(settlementExecution.claim(tenant, queued.command().id(), sent.leaseUntil())).isNull();
        var query = settlementExecution.claim(tenant, queued.command().id(), sent.leaseUntil()); assertThat(query.status()).isEqualTo(SupplierPayableSettlementOperation.Status.QUERYING);
        settlementExecution.finish(sent, new FinanceResult.Success<>(settled(sent).observation()), query.updatedAt()); assertThat(settlements.find(tenant, queued.command().id())).contains(query);
        settlementExecution.finish(query, new FinanceResult.Success<>(settled(query).observation()), query.updatedAt());
        assertThat(settlements.find(tenant, queued.command().id()).orElseThrow().dispatches()).isEqualTo(1); assertThat(count("supplier_settlement_completion")).isEqualTo(1);
    }

    @Test void retiringAReadClaimMakesLateReadHarmlessAndBlocksEveryOldAction() {
        var payment = supplierBank(); var queued = serviceSettlement(payment); var claim = settlementExecution.claim(tenant, queued.command().id(), at());
        var decision = tx.execute(status -> settlementExecution.retire(tenant, queued.command().id(), claim.version(), "finance", at()));
        assertThat(settlementExecution.ready(claim, read(payment, at(), date()), at())).isNull(); assertThat(settlementExecution.claim(tenant, queued.command().id(), at())).isNull();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> settlementExecution.query(tenant, queued.command().id(), decision.operationVersion(), at()))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> settlementExecution.resend(tenant, queued.command().id(), decision.operationVersion(), at()))).isInstanceOf(DomainException.class);
        assertThat(serviceIntent(payment).input().id()).isNotEqualTo(queued.command().id()); assertThat(count("supplier_payment_operation")).isEqualTo(1);
    }

    @Test void workersReadOutsideTransactionsAndLostSettlementResponseRecoversWithOneOriginalQuery() {
        var payment = supplierBank(); var pending = serviceIntent(payment); var writes = new AtomicInteger(); var queries = new AtomicInteger();
        var reader = liveReader(payment); var worker = new SupplierSettlementPreparationWorker(intents, settlementPreparation, reader); worker.poll();
        var queued = settlements.active(tenant, payment.command().id()).orElseThrow(); assertThat(queued.command().id()).isEqualTo(pending.input().id());
        var gateway = mock(SupplierPayableSettlementPort.class);
        when(gateway.settle(any(), any())).thenAnswer(invocation -> {
            outside(); writes.incrementAndGet(); var saved = settlements.find(tenant, queued.command().id()).orElseThrow();
            assertThat(saved.status()).isEqualTo(SupplierPayableSettlementOperation.Status.SETTLING); assertThat(saved.dispatches()).isEqualTo(1);
            assertThat(invocation.<SupplierPayableSettlementCommand>getArgument(0)).isEqualTo(queued.command()); throw new IllegalStateException("Synthetic lost settlement response");
        });
        when(gateway.query(any())).thenAnswer(invocation -> { outside(); queries.incrementAndGet(); return new FinanceResult.Success<>(settled(settlements.find(tenant, queued.command().id()).orElseThrow()).observation()); });
        var executionWorker = new SupplierSettlementWorker(settlements, settlementExecution, reader, gateway); executionWorker.poll();
        var unknown = settlements.find(tenant, queued.command().id()).orElseThrow(); assertThat(unknown.status()).isEqualTo(SupplierPayableSettlementOperation.Status.UNKNOWN);
        disabled.add("finance"); jdbc.update("UPDATE approval_application SET status='CANCELLED' WHERE tenant_id=?", tenant);
        tx.executeWithoutResult(status -> settlementExecution.query(tenant, queued.command().id(), unknown.version(), clock())); executionWorker.poll();
        assertThat(writes.get()).isEqualTo(1); assertThat(queries.get()).isEqualTo(1); assertThat(settlements.find(tenant, queued.command().id()).orElseThrow().settled()).isTrue();
        assertThat(count("supplier_settlement_completion")).isEqualTo(1); assertThat(count("supplier_payment_operation")).isEqualTo(1);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> worker.poll())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> executionWorker.poll())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> reader.read(payment.command(), date()))).isInstanceOf(IllegalStateException.class);
    }

    @Test void confirmedErpSuccessWaitsForBankRecheckThenCompletesLocallyWithoutAnotherExternalWrite() {
        var payment = supplierBank(); var queued = serviceSettlement(payment); var claim = settlementExecution.claim(tenant, queued.command().id(), at());
        var sent = settlementExecution.ready(claim, read(payment, at(), date()), at());
        var bankUnknown = tx.execute(status -> bank.query(tenant, payment.command().id(), payment.version(), at()));
        settlementExecution.finish(sent, new FinanceResult.Success<>(settled(sent).observation()), at());
        assertThat(settlements.find(tenant, queued.command().id()).orElseThrow().settled()).isTrue(); assertThat(count("supplier_settlement_completion")).isZero(); assertThat(settlements.awaitingLocalCompletion()).isEmpty();
        var bankClaim = bank.claim(tenant, payment.command().id(), bankUnknown.updatedAt()); bank.finish(bankClaim, new FinanceResult.Success<>(paid(payment.command(), at())), at());
        assertThat(settlements.awaitingLocalCompletion()).hasSize(1);
        var gateway = mock(SupplierPayableSettlementPort.class); var reader = mock(SupplierSettlementEvidenceReader.class);
        var worker = new SupplierSettlementWorker(settlements, settlementExecution, reader, gateway); worker.poll(); worker.poll();
        verifyNoInteractions(reader, gateway); assertThat(count("supplier_settlement_completion")).isEqualTo(1); assertThat(settlements.awaitingLocalCompletion()).isEmpty();
    }

    @Test void failedAtomicCompletionBecomesOriginalQueryAndCanRecoverWithoutResending() {
        var payment = supplierBank(); var queued = serviceSettlement(payment); var claim = settlementExecution.claim(tenant, queued.command().id(), at());
        var sent = settlementExecution.ready(claim, read(payment, at(), date()), at()); var original = payment.command().holdCommand().authorization().source().reservation();
        jdbc.update("INSERT INTO procurement_payable_reservation_revision(tenant_id,reservation_id,version,state_json) VALUES(?,?,2,?)", tenant, original.id().toString(), json.write(original));
        assertThatThrownBy(() -> settlementExecution.finish(sent, new FinanceResult.Success<>(settled(sent).observation()), at())).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(settlements.find(tenant, queued.command().id())).contains(sent); settlementExecution.fail(sent, at());
        var unknown = settlements.find(tenant, queued.command().id()).orElseThrow(); assertThat(unknown.status()).isEqualTo(SupplierPayableSettlementOperation.Status.UNKNOWN);
        jdbc.update("DELETE FROM procurement_payable_reservation_revision WHERE tenant_id=? AND reservation_id=? AND version=2", tenant, original.id().toString());
        var query = settlementExecution.claim(tenant, queued.command().id(), unknown.nextAttemptAt()); settlementExecution.finish(query, new FinanceResult.Success<>(settled(query).observation()), query.updatedAt());
        assertThat(settlements.find(tenant, queued.command().id()).orElseThrow().dispatches()).isEqualTo(1); assertThat(count("supplier_settlement_completion")).isEqualTo(1);
    }

    @Test void authoritativeNotFoundRequiresExplicitSameCommandRetryAndCurrentEligibility() {
        var payment = supplierBank(); var queued = serviceSettlement(payment); var claim = settlementExecution.claim(tenant, queued.command().id(), at());
        var sent = settlementExecution.ready(claim, read(payment, at(), date()), at()); settlementExecution.fail(sent, at());
        var unknown = settlements.find(tenant, queued.command().id()).orElseThrow(); var query = settlementExecution.claim(tenant, queued.command().id(), unknown.nextAttemptAt());
        var missing = new SupplierPayableSettlementObservation(queued.command().id(), queued.command().digest(), SupplierPayableSettlementObservation.Status.NOT_FOUND, 0L, query.updatedAt(), null, null);
        settlementExecution.finish(query, new FinanceResult.Success<>(missing), query.updatedAt()); var absent = settlements.find(tenant, queued.command().id()).orElseThrow();
        assertThat(settlementExecution.claim(tenant, queued.command().id(), absent.updatedAt())).isNull();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> settlementExecution.retire(tenant, absent.command().id(), absent.version(), "finance", absent.updatedAt()))).isInstanceOf(DomainException.class);
        disabled.add("finance"); assertThatThrownBy(() -> tx.executeWithoutResult(status -> settlementExecution.resend(tenant, absent.command().id(), absent.version(), absent.updatedAt()))).isInstanceOf(DomainException.class); disabled.clear();
        var retry = tx.execute(status -> settlementExecution.resend(tenant, absent.command().id(), absent.version(), absent.updatedAt())); assertThat(retry.command()).isEqualTo(queued.command());
        var checking = settlementExecution.claim(tenant, queued.command().id(), retry.updatedAt()); assertThat(checking.status()).isEqualTo(SupplierPayableSettlementOperation.Status.CHECKING);
        assertThat(settlementExecution.ready(checking, new FinanceResult.Rejected<>(FinanceResult.Reason.ACCOUNTING_PERIOD_CLOSED), checking.updatedAt())).isNull();
        var stopped = settlements.find(tenant, queued.command().id()).orElseThrow(); assertThat(stopped.dispatches()).isEqualTo(1); assertThat(stopped.retirementBasis()).isNull();
    }

    @Test void ordinaryUpdateCannotClearAnErpDisputeWithoutAnIndependentDecision() {
        var disputed = disputedSettlement();
        var resolved = disputed.resolveDispute(SupplierPayableSettlementObservation.Status.SETTLED,
                new SupplierPayableSettlementOperation.ResolutionHistory(null, true), disputed.updatedAt());
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> settlements.update(resolved))).isInstanceOf(DomainException.class);
        assertThat(settlements.find(tenant, disputed.command().id())).contains(disputed);
        assertThat(settlements.revision(tenant, disputed.command().id(), resolved.version())).isEmpty();
    }

    @Test void explicitResolutionSurvivesRecreationAndLocalCompletionDoesNotResendBankOrErp() {
        var disputed = disputedSettlement(); var old = disputed.conflictingObservation();
        var precise = new SupplierPayableSettlementObservation(old.operationId(), old.commandDigest(), old.status(), 3L,
                disputed.updatedAt().plusNanos(333), old.posting(), null);
        var fresh = querySettlement(disputed, precise); var decision = settlementDecision(fresh);
        var resolved = tx.execute(status -> settlements.resolve(decision));
        var reopened = new JdbcSupplierPayableSettlementRepository(jdbc, json, intents, payments, reservations);
        assertThat(reopened.find(tenant, old.operationId())).contains(resolved); assertThat(reopened.latestResolution(tenant, old.operationId())).contains(decision);
        assertThat(reopened.latestResolution("other", old.operationId())).isEmpty();
        assertThat(reopened.revision(tenant, old.operationId(), fresh.version())).contains(fresh);
        assertThat(reopened.resolutionHistory(tenant, old.operationId()).firstSettlement()).isEqualTo(precise);
        settlementExecution.completeLocal(tenant, old.operationId(), resolved.updatedAt());
        settlementExecution.completeLocal(tenant, old.operationId(), resolved.updatedAt());
        assertThat(count("supplier_settlement_completion")).isEqualTo(1);
        assertThat(count("supplier_settlement_dispute_resolution")).isEqualTo(1);
        assertThat(count("supplier_payment_operation")).isEqualTo(1); assertThat(count("supplier_payable_settlement_operation")).isEqualTo(1);
        assertThat(resolved.dispatches()).isEqualTo(1); assertThat(reopened.find(tenant, old.operationId())).contains(resolved);
        assertThat(reopened.latestResolution(tenant, old.operationId())).contains(decision);
    }

    @Test void resolutionInsertFailureRollsBackStateAndRevisionTogether() {
        var disputed = disputedSettlement(); var decision = settlementDecision(disputed);
        jdbc.execute("ALTER TABLE supplier_settlement_dispute_resolution ADD CONSTRAINT synthetic_resolution_failure CHECK (resolved_by<>'finance')");
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> settlements.resolve(decision))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(settlements.find(tenant, decision.settlementId())).contains(disputed);
        assertThat(settlements.revision(tenant, decision.settlementId(), decision.resolvedVersion())).isEmpty();
        assertThat(count("supplier_settlement_dispute_resolution")).isZero(); assertThat(count("supplier_settlement_completion")).isZero();
        assertThat(settlements.active(tenant, disputed.command().payment().id())).contains(disputed);
    }

    @Test void racingDecisionsConsumeTheSameDisputedRevisionOnlyOnce() throws Exception {
        var disputed = disputedSettlement(); var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { start.await(); return settlementResolutionOutcome(disputed); });
            var second = pool.submit(() -> { start.await(); return settlementResolutionOutcome(disputed); }); start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "INVALID_SUPPLIER_SETTLEMENT_DISPUTE_RESOLUTION");
        } finally { pool.shutdownNow(); }
        assertThat(count("supplier_settlement_dispute_resolution")).isEqualTo(1);
        var result = settlements.find(tenant, disputed.command().id()).orElseThrow();
        assertThat(result.version()).isEqualTo(disputed.version() + 1); assertThat(result.dispatches()).isEqualTo(1);
    }

    @Test void overwrittenPostingAndAlreadySettledEvidenceRemainVisibleInHistory() {
        var disputed = disputedSettlement(); var at = disputed.updatedAt().plusSeconds(1); var command = disputed.command();
        var refused = querySettlement(disputed, new SupplierPayableSettlementObservation(command.id(), command.digest(), SupplierPayableSettlementObservation.Status.REJECTED,
                3L, at, null, SupplierPayableSettlementObservation.Rejection.ACCOUNTING_PERIOD_CLOSED));
        var proof = settlements.resolutionHistory(tenant, command.id()); assertThat(proof.firstSettlement()).isNull(); assertThat(proof.settlementObserved()).isTrue();
        assertThat(refused.observation().status()).isEqualTo(SupplierPayableSettlementObservation.Status.PENDING);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> settlements.resolve(settlementDecision(refused))))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo("SUPPLIER_SETTLEMENT_DISPUTE_UNRESOLVABLE"));
        var other = dispatch(settlement(supplierBank())); var id = other.command().id();
        var already = other.complete(new FinanceResult.Success<>(new SupplierPayableSettlementObservation(id, other.command().digest(), SupplierPayableSettlementObservation.Status.REJECTED,
                1L, other.updatedAt(), null, SupplierPayableSettlementObservation.Rejection.ALREADY_SETTLED)), other.updatedAt());
        tx.executeWithoutResult(status -> settlements.update(already));
        assertThat(settlements.resolutionHistory(tenant, id).settlementObserved()).isTrue();
        assertThat(count("supplier_settlement_dispute_resolution")).isZero();
    }

    @Test void brokenHistoryCannotAuthorizeResolutionAndForeignKeysProtectBothRevisions() {
        var disputed = disputedSettlement(); var d = settlementDecision(disputed);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO supplier_settlement_dispute_resolution(tenant_id,id,settlement_id,disputed_version,resolved_version,outcome,resolved_by,observed_at,resolved_at,state_json)
                VALUES(?,?,?,?,?,'SETTLED','finance',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?)
                """, tenant, d.id().toString(), d.settlementId().toString(), d.disputedVersion(), d.resolvedVersion(), json.write(d)))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("DELETE FROM supplier_payable_settlement_revision WHERE tenant_id=? AND operation_id=? AND version=2", tenant, d.settlementId().toString());
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> settlements.resolve(d))).isInstanceOf(DomainException.class);
        assertThat(settlements.find(tenant, d.settlementId())).contains(disputed); assertThat(count("supplier_settlement_dispute_resolution")).isZero();
    }

    @Test void laterQueriesAndDecisionsPreserveOriginalLocalCompletionAndEarlierDecision() {
        var first = disputedSettlement(); var firstDecision = settlementDecision(first);
        var resolved = tx.execute(status -> settlements.resolve(firstDecision)); settlementExecution.completeLocal(tenant, resolved.command().id(), resolved.updatedAt());
        var completionBefore = jdbc.queryForList("SELECT * FROM supplier_settlement_completion WHERE tenant_id=?", tenant);
        var reservationBefore = jdbc.queryForList("SELECT * FROM procurement_payable_reservation WHERE tenant_id=?", tenant);
        var posting = resolved.observation().posting(); var at = resolved.updatedAt().plusSeconds(1);
        var conflicting = new SupplierPayableSettlementObservation.Posting(posting.settlementReference(), posting.holdReference(), posting.ledgerVersion(), posting.settledAmount(),
                posting.settledBefore(), posting.settledAfter(), posting.bankPaymentReference(), posting.bankReceiptReference(), "different-voucher", posting.periodReference(), posting.accountingDate(), posting.settledAt());
        var bad = querySettlement(resolved, new SupplierPayableSettlementObservation(resolved.command().id(), resolved.command().digest(), SupplierPayableSettlementObservation.Status.SETTLED, 3L, at, conflicting, null));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> settlements.resolve(settlementDecision(bad)))).isInstanceOf(DomainException.class);
        var fresh = querySettlement(bad, new SupplierPayableSettlementObservation(resolved.command().id(), resolved.command().digest(), SupplierPayableSettlementObservation.Status.SETTLED, 4L, at.plusSeconds(1), posting, null));
        var latestDecision = settlementDecision(fresh); var latest = tx.execute(status -> settlements.resolve(latestDecision));
        settlementExecution.completeLocal(tenant, latest.command().id(), latest.updatedAt());
        assertThat(jdbc.queryForList("SELECT * FROM supplier_settlement_completion WHERE tenant_id=?", tenant)).isEqualTo(completionBefore);
        assertThat(jdbc.queryForList("SELECT * FROM procurement_payable_reservation WHERE tenant_id=?", tenant)).isEqualTo(reservationBefore);
        assertThat(count("supplier_settlement_dispute_resolution")).isEqualTo(2);
        assertThat(settlements.latestResolution(tenant, latest.command().id())).contains(latestDecision);
        assertThat(settlements.revision(tenant, first.command().id(), firstDecision.disputedVersion())).contains(first);
        assertThat(latest.dispatches()).isEqualTo(1);
    }

    @Test void v78UpgradeOnlyAddsDecisionsAndPreservesExistingSettlementAndBankFacts() {
        database("78"); var disputed = disputedSettlement();
        var tables = List.of("supplier_payment_authorization", "supplier_payable_hold_operation", "supplier_payable_hold_revision", "supplier_payment_execution_request",
                "supplier_payment_execution_revision", "supplier_payment_operation", "supplier_payment_revision", "supplier_payment_dispute_resolution",
                "supplier_settlement_preparation", "supplier_settlement_preparation_revision", "supplier_payable_settlement_operation", "supplier_payable_settlement_revision",
                "procurement_payable_reservation", "procurement_payable_reservation_revision", "supplier_settlement_completion", "supplier_settlement_retirement");
        var original = tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table + " WHERE tenant_id=?", tenant)).toList();
        var migration = Flyway.configure().dataSource(dataSource).defaultSchema(schema).target("79").load();
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table + " WHERE tenant_id=?", tenant)).toList()).isEqualTo(original);
        assertThat(count("supplier_settlement_dispute_resolution")).isZero();
        assertThat(migration.migrate().migrationsExecuted).isZero(); assertThat(migration.validateWithResult().validationSuccessful).isTrue();
        assertThat(tx.execute(status -> settlements.resolve(settlementDecision(disputed))).settled()).isTrue();
    }

    private SupplierSettlementDisputeResolution settlementDecision(SupplierPayableSettlementOperation disputed) {
        return new SupplierSettlementDisputeResolution(UUID.randomUUID(), tenant, disputed.command().id(), disputed.version(), disputed.version() + 1,
                disputed.conflictingObservation(), "finance", disputed.updatedAt(), "erp-evidence-1", "核对原核销凭证与应付余额");
    }
    private String settlementResolutionOutcome(SupplierPayableSettlementOperation disputed) {
        try { tx.executeWithoutResult(status -> settlements.resolve(settlementDecision(disputed))); return "OK"; }
        catch (DomainException conflict) { return conflict.code(); }
    }

    private SupplierPayableSettlementOperation disputedSettlement() {
        var sent = dispatch(settlement(supplierBank())); var command = sent.command();
        var pending = sent.complete(new FinanceResult.Success<>(new SupplierPayableSettlementObservation(command.id(), command.digest(), SupplierPayableSettlementObservation.Status.PENDING,
                1L, sent.updatedAt(), null, null)), sent.updatedAt()); tx.executeWithoutResult(status -> settlements.update(pending));
        var missing = querySettlement(pending, new SupplierPayableSettlementObservation(command.id(), command.digest(), SupplierPayableSettlementObservation.Status.NOT_FOUND,
                0L, pending.updatedAt().plusSeconds(1), null, null));
        return querySettlement(missing, new SupplierPayableSettlementObservation(command.id(), command.digest(), SupplierPayableSettlementObservation.Status.SETTLED,
                2L, missing.updatedAt().plusSeconds(1), settled(sent).observation().posting(), null));
    }
    private SupplierPayableSettlementOperation querySettlement(SupplierPayableSettlementOperation current, SupplierPayableSettlementObservation observation) {
        var observed = observation.observedAt(); var micros = observed.truncatedTo(ChronoUnit.MICROS);
        var completedAt = micros.isBefore(observed) ? micros.plusNanos(1000) : micros;
        return tx.execute(status -> {
            sources.lock(tenant, current.command().payment().id());
            var queued = current.requestQuery(completedAt); settlements.update(queued);
            var claimed = queued.claim(completedAt, Duration.ofSeconds(30)); settlements.update(claimed);
            var result = claimed.complete(new FinanceResult.Success<>(observation), completedAt); settlements.update(result); return result;
        });
    }

    @Test void returnReviewStopsNewSettlementWithoutChangingTheOriginalBank() {
        var payment = supplierBank(); freezeReturn(payment);
        assertThatThrownBy(() -> serviceIntent(payment)).isInstanceOfSatisfying(DomainException.class,
                error -> assertThat(error.code()).isEqualTo("SUPPLIER_PAYMENT_RETURN_REVIEW_REQUIRED"));
        assertThat(payments.find(tenant, payment.command().id())).contains(payment);
        assertThat(count("supplier_settlement_preparation")).isZero();
    }

    @Test void returnReviewBeforeFirstErpSendVoidsOnlyTheUnsentSettlement() {
        var payment = supplierBank(); var queued = serviceSettlement(payment); freezeReturn(payment);
        assertThat(settlementExecution.claim(tenant, queued.command().id(), clock())).isNull();
        var stopped = settlements.find(tenant, queued.command().id()).orElseThrow();
        assertThat(stopped.status()).isEqualTo(SupplierPayableSettlementOperation.Status.VOIDED);
        assertThat(stopped.dispatches()).isZero(); assertThat(payments.find(tenant, payment.command().id())).contains(payment);
    }

    @Test void returnReviewDuringErpCallKeepsActualPostingAndBlocksLocalCompletion() {
        var payment = supplierBank(); var queued = serviceSettlement(payment); var claimed = settlementExecution.claim(tenant, queued.command().id(), at());
        var sent = settlementExecution.ready(claimed, read(payment, at(), date()), at()); freezeReturn(payment);
        settlementExecution.finish(sent, new FinanceResult.Success<>(settled(sent).observation()), clock());
        var actual = settlements.find(tenant, queued.command().id()).orElseThrow(); assertThat(actual.settled()).isTrue();
        assertThat(count("supplier_settlement_completion")).isZero(); assertThat(settlements.awaitingLocalCompletion()).isEmpty();
        settlementExecution.completeLocal(tenant, actual.command().id(), clock());
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> reservations.complete(actual, payment, clock()))).isInstanceOf(DomainException.class);
        var original = payment.command().holdCommand().authorization().source().reservation();
        assertThat(reservations.find(tenant, original.id())).contains(original); assertThat(payments.find(tenant, payment.command().id())).contains(payment);
    }

    @Test void returnAfterCompletionBlocksAnotherRequestAndKeepsOriginalCompletion() {
        var payment = supplierBank(); var queued = serviceSettlement(payment); var claimed = settlementExecution.claim(tenant, queued.command().id(), at());
        var sent = settlementExecution.ready(claimed, read(payment, at(), date()), at());
        settlementExecution.finish(sent, new FinanceResult.Success<>(settled(sent).observation()), at());
        var original = payment.command().holdCommand().authorization().source().reservation();
        var completed = reservations.find(tenant, original.id()).orElseThrow(); var originalErp = settlements.find(tenant, queued.command().id()).orElseThrow();
        freezeReturn(payment);
        assertThatThrownBy(() -> approved(original.source().round().content())).isInstanceOfSatisfying(DomainException.class,
                error -> assertThat(error.code()).isEqualTo("SUPPLIER_PAYMENT_RETURN_REVIEW_REQUIRED"));
        assertThat(approved().source().reservation().source().round().content().payableReference()).isNotEqualTo(original.source().round().content().payableReference());
        assertThat(reservations.find(tenant, original.id())).contains(completed); assertThat(settlements.find(tenant, queued.command().id())).contains(originalErp);
        assertThat(count("supplier_settlement_completion")).isEqualTo(1); assertThat(count("supplier_payment_operation")).isEqualTo(1);
    }

    @Test void newPayableReservationWaitsForConcurrentReturnFreezeToCommit() throws Exception {
        var payment = supplierBank(); var queued = serviceSettlement(payment); var claimed = settlementExecution.claim(tenant, queued.command().id(), at());
        var sent = settlementExecution.ready(claimed, read(payment, at(), date()), at());
        settlementExecution.finish(sent, new FinanceResult.Success<>(settled(sent).observation()), at());
        var request = new SupplierPaymentReturnPort.Request(payment.command(), payment.observation());
        var ledgers = new JdbcSupplierPaymentReturnsRepository(jdbc, json, payments, new SupplierPayableReturnGuard(jdbc), new JdbcSupplierAdjustmentCompletions(jdbc, json));
        tx.executeWithoutResult(status -> ledgers.create(SupplierPaymentReturns.open(request, clock())));
        var frozen = new CountDownLatch(1); var release = new CountDownLatch(1); var attempting = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> tx.executeWithoutResult(status -> {
                sources.lock(tenant, payment.command().id()); ledgers.requireReview(tenant, payment.command().id(), clock()); frozen.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic freeze release timed out"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            }));
            assertThat(frozen.await(10, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> { attempting.countDown(); try { approved(request.command().holdCommand().authorization().source().reservation().source().round().content()); return "OK"; }
                catch (DomainException rejected) { return rejected.code(); } });
            assertThat(attempting.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown(); first.get(10, TimeUnit.SECONDS); assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo("SUPPLIER_PAYMENT_RETURN_REVIEW_REQUIRED");
        } finally { release.countDown(); pool.shutdownNow(); }
        assertThat(count("procurement_payment")).isEqualTo(1); assertThat(count("supplier_settlement_completion")).isEqualTo(1);
    }

    @Test void completedPayableWithNewBankUncertaintyCannotStartAnotherPaymentRequest() {
        var payment = supplierBank(); var queued = serviceSettlement(payment); var claimed = settlementExecution.claim(tenant, queued.command().id(), at());
        var sent = settlementExecution.ready(claimed, read(payment, at(), date()), at());
        settlementExecution.finish(sent, new FinanceResult.Success<>(settled(sent).observation()), at());
        var original = payment.command().holdCommand().authorization().source().reservation(); var completed = reservations.find(tenant, original.id()).orElseThrow();
        tx.executeWithoutResult(status -> bank.query(tenant, payment.command().id(), payment.version(), clock()));
        assertThatThrownBy(() -> approved(original.source().round().content())).isInstanceOfSatisfying(DomainException.class,
                error -> assertThat(error.code()).isEqualTo("SUPPLIER_PAYMENT_RETURN_REVIEW_REQUIRED"));
        assertThat(count("supplier_payment_returns")).isZero(); assertThat(reservations.find(tenant, original.id())).contains(completed);
        var query = bank.claim(tenant, payment.command().id(), clock()); bank.finish(query, new FinanceResult.Success<>(paid(payment.command(), clock())), clock());
        assertThat(new SupplierPayableReturnGuard(jdbc).blocked(tenant, original.source().round().content())).isFalse();
    }

    @Test void localCompletionQueueExcludesAnEarlierCompletedPaymentWithNewBankUncertainty() {
        var firstContent = new ProcurementPaymentContent(entity, "采购付款", "原应付分次付款", "supplier-1", UUID.randomUUID().toString(), money("20"));
        var first = supplierBank(approved(firstContent)); var firstQueued = serviceSettlement(first);
        var firstClaim = settlementExecution.claim(tenant, firstQueued.command().id(), at());
        var firstSent = settlementExecution.ready(firstClaim, read(first, at(), date()), at());
        settlementExecution.finish(firstSent, new FinanceResult.Success<>(settled(firstSent).observation()), at());
        var laterContent = new ProcurementPaymentContent(entity, "采购付款", "原应付后续付款", "supplier-1", firstContent.payableReference(), money("10"));
        var later = supplierBank(approved(laterContent)); var laterQueued = serviceSettlement(later);
        var laterClaim = settlementExecution.claim(tenant, laterQueued.command().id(), at());
        var laterSent = settlementExecution.ready(laterClaim, read(later, at(), date()), at());
        tx.executeWithoutResult(status -> bank.query(tenant, first.command().id(), first.version(), clock()));
        settlementExecution.finish(laterSent, new FinanceResult.Success<>(settled(laterSent).observation()), clock());
        assertThat(settlements.find(tenant, laterQueued.command().id()).orElseThrow().settled()).isTrue();
        assertThat(count("supplier_settlement_completion")).isEqualTo(1);
        assertThat(settlements.awaitingLocalCompletion()).isEmpty();
    }

    private void freezeReturn(SupplierPaymentOperation payment) {
        var ledgers = new JdbcSupplierPaymentReturnsRepository(jdbc, json, payments, new SupplierPayableReturnGuard(jdbc), new JdbcSupplierAdjustmentCompletions(jdbc, json)); var current = clock();
        tx.executeWithoutResult(status -> {
            sources.lock(tenant, payment.command().id());
            ledgers.create(SupplierPaymentReturns.open(new SupplierPaymentReturnPort.Request(payment.command(), payment.observation()), current));
            ledgers.requireReview(tenant, payment.command().id(), current);
        });
    }

    private SupplierSettlementPreparation serviceIntent(SupplierPaymentOperation payment) { return tx.execute(status -> settlementPreparation.register(tenant, payment.command().id(), payment.version(), "finance", date(), at())); }
    private SupplierPayableSettlementOperation serviceSettlement(SupplierPaymentOperation payment) {
        var pending = serviceIntent(payment); var claimed = settlementPreparation.claim(tenant, pending.input().id(), at());
        settlementPreparation.finish(claimed, read(payment, at(), date()), at()); return settlements.find(tenant, pending.input().id()).orElseThrow();
    }
    private FinanceResult<SupplierSettlementEvidenceReader.Snapshot> read(SupplierPaymentOperation payment, Instant at, LocalDate date) {
        var value = settlementEvidence(payment, at, date); return new FinanceResult.Success<>(new SupplierSettlementEvidenceReader.Snapshot(value.hold(), value.paid(), value.period(), value.checkedAt()));
    }
    private SupplierSettlementEvidenceReader liveReader(SupplierPaymentOperation payment) {
        var hold = mock(SupplierPayableHoldPort.class); var paid = mock(SupplierPaymentPort.class); var periods = mock(AccountingPeriodPort.class);
        when(hold.query(eq(payment.command().holdCommand()))).thenAnswer(invocation -> { outside(); return new FinanceResult.Success<>(observed(payment.command().holdCommand().authorization(), clock())); });
        when(paid.query(eq(payment.command()))).thenAnswer(invocation -> { outside(); return new FinanceResult.Success<>(paid(payment.command(), clock())); });
        when(periods.period(eq(tenant), eq(payment.command().targetDigest()), eq(new AccountingPeriodPort.Request(entity, "CNY", date()))))
                .thenAnswer(invocation -> { outside(); return new FinanceResult.Success<>(settlementEvidence(payment, clock(), date()).period()); });
        return new SupplierSettlementEvidenceReader(hold, paid, periods);
    }
    private void outside() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); }
    private Instant clock() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }

    private SupplierPaymentOperation supplierBank() {
        return supplierBank(approved());
    }
    private SupplierPaymentOperation supplierBank(SupplierPaymentAuthorization authorization) {
        var sent = sending(prepared(register(confirmed(authorization)))); bank.finish(sent, new FinanceResult.Success<>(paid(sent.command(), at())), at());
        return payments.find(tenant, sent.command().id()).orElseThrow();
    }
    private SupplierSettlementPreparation intent(SupplierPaymentOperation payment) {
        return tx.execute(status -> { sources.lock(tenant, payment.command().id()); var value = SupplierSettlementPreparation.queue(UUID.randomUUID(), payment, "finance", date(), at()); intents.create(value); return value; });
    }
    private String intentOutcome(SupplierPaymentOperation payment) { try { intent(payment); return "OK"; } catch (DomainException error) { return error.code(); } }
    private SupplierSettlementPreparation claim(SupplierSettlementPreparation pending) {
        return tx.execute(status -> { sources.lock(tenant, pending.input().payment().command().id()); var value = pending.claim(pending.updatedAt(), Duration.ofSeconds(30)); intents.update(value); return value; });
    }
    private SupplierPayableSettlementOperation registerSettlement(SupplierSettlementPreparation claimed) {
        return tx.execute(status -> {
            sources.lock(tenant, claimed.input().payment().command().id()); var at = claimed.updatedAt();
            var command = claimed.input().command(settlementEvidence(claimed.input().payment(), at, claimed.input().accountingDate()), at);
            var queued = SupplierPayableSettlementOperation.queue(command, at); settlements.create(claimed, queued); intents.update(claimed.ready(command, at)); return queued;
        });
    }
    private SupplierPayableSettlementOperation settlement(SupplierPaymentOperation payment) { return registerSettlement(claim(intent(payment))); }
    private SupplierPayableSettlementOperation dispatch(SupplierPayableSettlementOperation queued) {
        return tx.execute(status -> {
            sources.lock(tenant, queued.command().payment().id()); var checking = queued.claim(queued.updatedAt(), Duration.ofSeconds(30)); settlements.update(checking);
            var payment = payments.find(tenant, queued.command().payment().id()).orElseThrow();
            var sent = checking.readyToSend(settlementEvidence(payment, checking.updatedAt(), queued.command().period().request().accountingDate()), checking.updatedAt()); settlements.update(sent); return sent;
        });
    }
    private SupplierPayableSettlementEvidence settlementEvidence(SupplierPaymentOperation payment, Instant at, LocalDate date) {
        var request = new AccountingPeriodPort.Request(entity, "CNY", date);
        var period = new AccountingPeriodPort.OpenPeriod(request, "period-" + date.getMonthValue(), "v1", date.withDayOfMonth(1), date.withDayOfMonth(date.lengthOfMonth()), at, at.plusSeconds(600));
        return new SupplierPayableSettlementEvidence(observed(payment.command().holdCommand().authorization(), at), paid(payment.command(), at), period, at);
    }
    private SupplierPayableSettlementOperation settled(SupplierPayableSettlementOperation sent) {
        var command = sent.command(); var posting = new SupplierPayableSettlementObservation.Posting("settlement-1", command.payment().held().holdReference(), "ledger-2", command.payment().amount(), money("30"), money("30").plus(command.payment().amount()),
                command.paid().paymentReference(), command.paid().receiptReference(), "voucher-settlement", command.period().periodReference(), command.period().request().accountingDate(), sent.updatedAt());
        return sent.complete(new FinanceResult.Success<>(new SupplierPayableSettlementObservation(command.id(), command.digest(), SupplierPayableSettlementObservation.Status.SETTLED, 1L, sent.updatedAt(), posting, null)), sent.updatedAt());
    }
    private LocalDate date() { return at().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate(); }

    private SupplierPaymentAuthorization approved() {
        return approved(new ProcurementPaymentContent(entity, "采购付款", "已验收货物付款", "supplier-1", UUID.randomUUID().toString(), money("70")));
    }
    private SupplierPaymentAuthorization approved(ProcurementPaymentContent content) {
        var ref = UUID.randomUUID().toString();
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
        return confirmed(approved());
    }
    private SupplierPayableHoldOperation confirmed(SupplierPaymentAuthorization authorization) {
        tx.executeWithoutResult(status -> holdService.register(authorization, authorizedAt()));
        var claimed = holdService.claim(tenant, authorization.id(), authorizedAt()); holdService.finish(claimed, new FinanceResult.Success<>(observed(authorization, authorizedAt().plusSeconds(1))), authorizedAt().plusSeconds(1));
        return holds.find(tenant, authorization.id()).orElseThrow();
    }
    private SupplierPaymentExecutionRequest register(SupplierPayableHoldOperation hold) { return tx.execute(status -> preparation.register(tenant, hold.command().id(), hold.version(), "cashier", DEBIT.reference(), DEBIT.sourceVersion(), at())); }
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
    @SuppressWarnings("unchecked") private <T> T proxy(T value) {
        var factory = new ProxyFactory(value); factory.setProxyTargetClass(true); factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource())); return (T) factory.getProxy();
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id=?", Integer.class, tenant); }
    private Instant at() { return now.plusSeconds(4); }
    private Instant authorizedAt() { return now.plusSeconds(2); }
    private static Money money(String amount) { return new Money(new BigDecimal(amount), "CNY"); }
}
