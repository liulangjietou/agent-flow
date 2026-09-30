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
import io.agentflow.finance.PaymentAccountsPort;
import io.agentflow.finance.PaymentObservation;
import io.agentflow.finance.PaymentPersonnel;
import io.agentflow.finance.JdbcFinanceReceiptCreditRepository;
import io.agentflow.organization.InitiatorContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import java.time.ZoneId;
import io.agentflow.finance.AccountingPeriodPort;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
 * 已登记回款驱动独立调整，验证真实来源、唯一办理位置、连续修订和无损升级。
 * @author owlzhangfq@gmail.com
 */
class SupplierAdjustmentPersistenceTest {
    private final String tenant = "supplier-bank-" + UUID.randomUUID();
    private final UUID entity = UUID.randomUUID();
    private final Instant now = Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.MICROS);
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
    private static final PaymentAccountsPort.DebitAccount DEBIT = new PaymentAccountsPort.DebitAccount("debit-1", "法人基本户", "****5678", "CNY", "v1");

    private JdbcSupplierAdjustmentSources adjustmentSources;
    private JdbcSupplierAdjustmentPreparationRepository adjustmentIntents;
    private JdbcSupplierPayableAdjustmentRepository adjustments;

    @BeforeEach void database() { database(null); }

    private void database(String target) {
        dataSource = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_SUPPLIER_ADJUSTMENT_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_SUPPLIER_ADJUSTMENT_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_SUPPLIER_ADJUSTMENT_PASSWORD", ""));
        schema = "supplier_adjustment_" + UUID.randomUUID().toString().replace("-", ""); new JdbcTemplate(dataSource).execute("CREATE SCHEMA \"" + schema + "\""); dataSource.setSchema(schema);
        var migration = Flyway.configure().dataSource(dataSource).defaultSchema(schema); if (target != null) migration.target(target); migration.load().migrate();
        jdbc = new JdbcTemplate(dataSource); manager = new DataSourceTransactionManager(dataSource); tx = new TransactionTemplate(manager);
        procurements = new JdbcProcurementPaymentRepository(jdbc, json);
        // 旧版本迁移夹具只建立当时的原件，V80 新守卫由当前版本用例验证。
        var returnGuard = target == null ? new SupplierPayableReturnGuard(jdbc) : mock(SupplierPayableReturnGuard.class);
        reservations = new JdbcProcurementPayableReservationRepository(jdbc, json, procurements, new JdbcProcurementInvoiceClaims(jdbc), returnGuard);
        approvedSources = new ApprovedSupplierPaymentSources(new JdbcApplicationRepository(jdbc, json), procurements, reservations, returnGuard);
        authorizations = new JdbcSupplierPaymentAuthorizationRepository(jdbc, json, approvedSources); holds = new JdbcSupplierPayableHoldRepository(jdbc, json, authorizations);
        var personnel = mock(PaymentPersonnel.class);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return null;
        }).when(personnel).requireEligible(eq(tenant), anyString(), eq(entity));
        holdService = proxy(new SupplierPayableHoldService(approvedSources, authorizations, holds, personnel, 30));
        sources = new SupplierPaymentSources(approvedSources, authorizations, holds, personnel);
        requests = new JdbcSupplierPaymentExecutionRepository(jdbc, json, holds);
        payments = new JdbcSupplierPaymentOperationRepository(jdbc, json, requests, holds, authorizations);
        preparation = proxy(new SupplierPaymentExecutionService(sources, requests, payments, 30)); bank = proxy(new SupplierPaymentService(sources, payments, 30));
        returnLedgers = new JdbcSupplierPaymentReturnsRepository(jdbc, json, payments, new SupplierPayableReturnGuard(jdbc));
        returnChecks = new JdbcSupplierPaymentReturnCheckRepository(jdbc, json);
        returnRegistrations = new JdbcSupplierPaymentReturnRepository(jdbc, json, returnLedgers, returnChecks, payments, new JdbcFinanceReceiptCreditRepository(jdbc));
        adjustmentSources = new JdbcSupplierAdjustmentSources(jdbc, json, procurements, returnLedgers, reservations, payments);
        adjustmentIntents = new JdbcSupplierAdjustmentPreparationRepository(jdbc, json, adjustmentSources);
        adjustments = new JdbcSupplierPayableAdjustmentRepository(jdbc, json, adjustmentIntents, adjustmentSources);
    }


    @Test void registeredSourceAndExactIntentSurviveReopeningWithoutChangingOriginalFunds() {
        var source = source(); var value = adjustment(intent(source)); var id = value.command().id();
        var reopenedIntents = new JdbcSupplierAdjustmentPreparationRepository(jdbc, json, adjustmentSources);
        var reopened = new JdbcSupplierPayableAdjustmentRepository(jdbc, json, reopenedIntents, adjustmentSources);
        assertThat(reopened.find(tenant, id)).contains(value); assertThat(reopened.find("other", id)).isEmpty();
        assertThat(reopenedIntents.find(tenant, id).orElseThrow().status()).isEqualTo(SupplierAdjustmentPreparation.Status.READY);
        assertThat(reopenedIntents.active(tenant, source.returns().request().command().id())).isEmpty();
        assertThat(reopened.active(tenant, source.returns().request().command().id())).contains(value);
        assertThat(reopened.revision(tenant, id, 1)).contains(value);
        assertThat(returnLedgers.find(tenant, source.returns().request().command().id())).contains(source.returns());
        assertThat(count("finance_receipt_credit")).isEqualTo(1); assertThat(count("supplier_payment_operation")).isEqualTo(1);
        assertThatThrownBy(() -> intent(source)).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo("SUPPLIER_ADJUSTMENT_PENDING"));
    }

    @Test void concurrentPreparationsHaveOneOwnerAndDoNotCreateAnyErpCommands() throws Exception {
        var source = source(); var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> { start.await(); return intentOutcome(source); });
            var two = pool.submit(() -> { start.await(); return intentOutcome(source); }); start.countDown();
            assertThat(List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder("OK", "SUPPLIER_ADJUSTMENT_PENDING");
        } finally { pool.shutdownNow(); }
        assertThat(count("supplier_adjustment_preparation")).isEqualTo(1); assertThat(count("supplier_payable_adjustment_operation")).isZero();
    }

    @Test void readyAppendFailureRollsBackTheCommandAndExpiredClaimCannotRegisterLate() {
        var value = intent(source()); var claimed = claim(value); var id = value.input().id();
        jdbc.update("INSERT INTO supplier_adjustment_preparation_revision(tenant_id,preparation_id,version,state_json) VALUES(?,?,?,?)", tenant, id.toString(), claimed.version() + 1, json.write(claimed));
        assertThatThrownBy(() -> registerAdjustment(claimed)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(adjustmentIntents.find(tenant, id)).contains(claimed); assertThat(count("supplier_payable_adjustment_operation")).isZero();
        jdbc.update("DELETE FROM supplier_adjustment_preparation_revision WHERE tenant_id=? AND preparation_id=? AND version=?", tenant, id.toString(), claimed.version() + 1);
        var expired = claimed.expireLease(claimed.leaseUntil()); tx.executeWithoutResult(status -> adjustmentIntents.update(expired));
        var next = expired.claim(expired.updatedAt(), Duration.ofSeconds(30)); tx.executeWithoutResult(status -> adjustmentIntents.update(next));
        assertThatThrownBy(() -> registerAdjustment(claimed)).isInstanceOf(DomainException.class);
        var registered = registerAdjustment(next); assertThat(registered.command().source()).isEqualTo(value.input().source());
        assertThat(registered.command().period().request().accountingDate()).isEqualTo(value.input().accountingDate());
    }

    @Test void lostWritePersistsOriginalCommandAndOnlyResumesQuerying() {
        var sent = dispatch(adjustment(intent(source()))); var missing = sent.unavailable(SupplierPayableAdjustmentOperation.Failure.TIMEOUT, sent.updatedAt());
        tx.executeWithoutResult(status -> adjustments.update(missing));
        var reopened = new JdbcSupplierPayableAdjustmentRepository(jdbc, json, adjustmentIntents, adjustmentSources);
        var restored = reopened.find(tenant, sent.command().id()).orElseThrow(); var querying = restored.claim(restored.nextAttemptAt(), Duration.ofSeconds(30));
        tx.executeWithoutResult(status -> reopened.update(querying));
        assertThat(querying.status()).isEqualTo(SupplierPayableAdjustmentOperation.Status.QUERYING);
        assertThat(querying.command()).isEqualTo(sent.command()); assertThat(querying.dispatches()).isEqualTo(1);
        assertThatThrownBy(() -> intent(sent.command().source())).isInstanceOf(DomainException.class);
        var forged = new SupplierAdjustmentRetirement(sent.command().id(), sent.command().source().returns().request().command().id(), querying.version(), SupplierPayableAdjustmentOperation.RetirementBasis.CONFIRMED_REJECTED, "finance", querying.updatedAt());
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> reopened.retire(tenant, forged))).isInstanceOf(DomainException.class);
        assertThat(count("supplier_adjustment_retirement")).isZero(); assertThat(count("supplier_adjustment_completion")).isZero();
    }

    @Test void safeRetirementReleasesOnlyTheAttemptAndLateSendCannotReviveIt() {
        var source = source(); var queued = adjustment(intent(source)); var checking = queued.claim(queued.updatedAt(), Duration.ofSeconds(30));
        tx.executeWithoutResult(status -> adjustments.update(checking)); var stopped = checking.stopForRetirement(checking.updatedAt());
        var decision = SupplierAdjustmentRetirement.from(stopped, "finance", stopped.updatedAt());
        tx.executeWithoutResult(status -> { adjustmentSources.lock(tenant, source.returns().request().command().id()); adjustments.update(stopped); adjustments.retire(tenant, decision); });
        assertThat(adjustments.active(tenant, source.returns().request().command().id())).isEmpty(); assertThat(adjustments.retirement(tenant, stopped.command().id())).contains(decision);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> adjustments.update(checking.readyToSend(adjustmentEvidence(source, checking.updatedAt()), checking.updatedAt())))).isInstanceOf(DomainException.class);
        assertThat(adjustment(intent(source)).command().id()).isNotEqualTo(queued.command().id());
        assertThat(returnLedgers.find(tenant, source.returns().request().command().id())).contains(source.returns());
        assertThat(count("supplier_payment_operation")).isEqualTo(1);
    }

    @Test void changedLedgerCannotBeReplacedByAnEarlierSnapshotOrFabricatedFundingOwner() {
        var source = source(); var old = source.returns(); var first = old.entries().get(0);
        var forged = new SupplierPaymentReturns(old.request(), old.version(), List.of(new SupplierPaymentReturns.Entry(UUID.randomUUID(), first.proof())), true, old.createdAt(), old.updatedAt());
        assertThatThrownBy(() -> intent(new SupplierPayableAdjustmentSource(forged, null, null))).isInstanceOf(DomainException.class);
        var payment = payments.find(tenant, old.request().command().id()).orElseThrow();
        var check = returnCheck(payment, "finance", 4, first.proof(), returnFunds("later", "10", clock())); registerReturn(check, returnDecision(check));
        assertThatThrownBy(() -> intent(source)).isInstanceOf(DomainException.class); assertThat(count("supplier_adjustment_preparation")).isZero();
    }

    @Test void currentUnknownBankCannotBeOverriddenByEarlierSuccessfulReturnRegistration() {
        var source = source(); var payment = payments.find(tenant, source.returns().request().command().id()).orElseThrow();
        tx.executeWithoutResult(status -> bank.query(tenant, payment.command().id(), payment.version(), clock()));
        assertThatThrownBy(() -> intent(source)).isInstanceOf(DomainException.class);
        assertThat(count("supplier_adjustment_preparation")).isZero();
    }

    @Test void originalSettledProofIsRetainedAndItsLaterUnknownStateBlocksNewAdjustments() {
        var source = source(true); var before = jdbc.queryForList("SELECT * FROM supplier_settlement_completion WHERE tenant_id=?", tenant);
        var queued = adjustment(intent(source)); assertThat(queued.command().source().recognizesOriginalPayment()).isFalse();
        assertThat(jdbc.queryForList("SELECT * FROM supplier_settlement_completion WHERE tenant_id=?", tenant)).isEqualTo(before);
        var originalIntents = new JdbcSupplierSettlementPreparationRepository(jdbc, json, payments);
        var originalOperations = new JdbcSupplierPayableSettlementRepository(jdbc, json, originalIntents, payments, reservations);
        var original = originalOperations.find(tenant, source.settlement().command().id()).orElseThrow();
        tx.executeWithoutResult(status -> originalOperations.update(original.requestQuery(clock())));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> adjustmentSources.requireCurrent(source))).isInstanceOf(DomainException.class);
        assertThat(adjustments.find(tenant, queued.command().id())).contains(queued);
        assertThat(jdbc.queryForList("SELECT * FROM supplier_settlement_completion WHERE tenant_id=?", tenant)).isEqualTo(before);
    }

    @Test void anExistingOriginalSettlementPreparationMustStopBeforeIndependentAdjustment() {
        var source = source(); var payment = payments.find(tenant, source.returns().request().command().id()).orElseThrow();
        var oldIntents = new JdbcSupplierSettlementPreparationRepository(jdbc, json, payments); var at = clock();
        var pending = SupplierSettlementPreparation.queue(UUID.randomUUID(), payment, "finance", at.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate(), at);
        tx.executeWithoutResult(status -> { sources.lock(tenant, payment.command().id()); oldIntents.create(pending); });
        assertThatThrownBy(() -> intent(source)).isInstanceOf(DomainException.class);
        tx.executeWithoutResult(status -> oldIntents.update(pending.voidSource(clock())));
        assertThat(adjustment(intent(source)).command().source()).isEqualTo(source);
        assertThat(count("supplier_payable_settlement_operation")).isZero();
    }

    @Test void v80UpgradePreservesNonemptyBankReturnsCreditsAndAllOriginalJson() {
        database("80"); var source = source(); var settledSource = source(true);
        var oldRows = new java.util.LinkedHashMap<String, List<java.util.Map<String, Object>>>();
        for (var table : List.of("supplier_payment_operation", "supplier_payment_revision", "supplier_payment_returns", "supplier_payment_returns_revision", "supplier_payment_return_registration", "finance_receipt_credit", "procurement_payable_reservation", "procurement_payable_reservation_revision", "supplier_payable_settlement_operation", "supplier_payable_settlement_revision", "supplier_settlement_completion")) {
            oldRows.put(table, jdbc.queryForList("SELECT * FROM " + table + " WHERE tenant_id=?", tenant));
        }
        assertThat(Flyway.configure().dataSource(dataSource).defaultSchema(schema).target("81").load().migrate().migrationsExecuted).isEqualTo(1);
        oldRows.forEach((table, rows) -> {
            var current = jdbc.queryForList("SELECT * FROM " + table + " WHERE tenant_id=?", tenant);
            assertThat(current).hasSize(rows.size());
            for (var row : rows) assertThat(current.stream().anyMatch(candidate -> row.entrySet().stream().allMatch(entry -> java.util.Objects.equals(candidate.get(entry.getKey()), entry.getValue())))).isTrue();
        });
        assertThat(adjustment(intent(source)).command().source()).isEqualTo(source);
        assertThat(adjustment(intent(settledSource)).command().source()).isEqualTo(settledSource);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finance_receipt_credit WHERE tenant_id=? AND supplier_adjustment_id IS NULL AND voucher_reference IS NULL", Integer.class, tenant)).isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update("UPDATE finance_receipt_credit SET voucher_reference='forged',entry_reference='entry' WHERE tenant_id=?", tenant)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private SupplierPayableAdjustmentSource source() { return source(false); }
    private SupplierPayableAdjustmentSource source(boolean alreadySettled) {
        var payment = returnedSource(); var settlement = alreadySettled ? originalSettlement(payment) : null;
        var check = returnCheck(payment, "finance", 2, returnFunds(UUID.randomUUID().toString(), "20", clock()));
        return new SupplierPayableAdjustmentSource(registerReturn(check, returnDecision(check)), settlement, null);
    }
    private SupplierAdjustmentPreparation intent(SupplierPayableAdjustmentSource source) {
        var at = clock().plusNanos(321); var value = SupplierAdjustmentPreparation.queue(UUID.randomUUID(), source, "finance", at.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate(), at);
        tx.executeWithoutResult(status -> adjustmentIntents.create(value)); return value;
    }
    private String intentOutcome(SupplierPayableAdjustmentSource source) {
        try { intent(source); return "OK"; } catch (DomainException failure) { return failure.code(); }
    }
    private SupplierAdjustmentPreparation claim(SupplierAdjustmentPreparation value) {
        var next = value.claim(clock(), Duration.ofSeconds(30)); tx.executeWithoutResult(status -> adjustmentIntents.update(next)); return next;
    }
    private SupplierPayableAdjustmentOperation adjustment(SupplierAdjustmentPreparation value) { return registerAdjustment(claim(value)); }
    private SupplierPayableAdjustmentOperation registerAdjustment(SupplierAdjustmentPreparation claimed) {
        var at = clock().isAfter(claimed.updatedAt()) ? clock() : claimed.updatedAt();
        var command = claimed.input().command(adjustmentEvidence(claimed.input().source(), at), at); var queued = SupplierPayableAdjustmentOperation.queue(command, at);
        return tx.execute(status -> { adjustments.create(claimed, queued); adjustmentIntents.update(claimed.ready(command, at)); return queued; });
    }
    private SupplierPayableAdjustmentOperation dispatch(SupplierPayableAdjustmentOperation value) {
        var checking = value.claim(clock(), Duration.ofSeconds(30)); tx.executeWithoutResult(status -> adjustments.update(checking));
        var at = clock(); var sent = checking.readyToSend(adjustmentEvidence(value.command().source(), at), at); tx.executeWithoutResult(status -> adjustments.update(sent)); return sent;
    }
    private SupplierPayableAdjustmentEvidence adjustmentEvidence(SupplierPayableAdjustmentSource source, Instant at) {
        var request = source.returns().request(); var original = request.original(); var payment = request.command();
        var current = new PaymentObservation(payment.id(), payment.digest(), PaymentObservation.Status.SUCCEEDED, 10L, at, original.paymentReference(), payment.amount(), payment.payee().accountDigest(), original.completedAt(), original.receiptReference(), null);
        var receipt = new SupplierPaymentReturnPort.Receipt(request, SupplierPaymentReturnPort.Status.PARTIALLY_RETURNED, 10, at, at.plusSeconds(300), current, source.returns().entries().stream().map(SupplierPaymentReturns.Entry::proof).toList());
        var date = at.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        var period = new AccountingPeriodPort.OpenPeriod(new AccountingPeriodPort.Request(entity, "CNY", date), "period", "period-v1", date, date, at, at.plusSeconds(300));
        var settled = source.settlement();
        var originalPosting = settled == null ? null : new SupplierPayableSettlementObservation(settled.command().id(), settled.command().digest(),
                SupplierPayableSettlementObservation.Status.SETTLED, settled.observation().revision(), at, settled.observation().posting(), null);
        return new SupplierPayableAdjustmentEvidence(receipt, settled == null ? observed(payment.holdCommand().authorization(), at) : null, originalPosting, null, period, at);
    }

    private SupplierPayableAdjustmentSource.OriginalSettlement originalSettlement(SupplierPaymentOperation payment) {
        var oldIntents = new JdbcSupplierSettlementPreparationRepository(jdbc, json, payments);
        var oldOperations = new JdbcSupplierPayableSettlementRepository(jdbc, json, oldIntents, payments, reservations);
        var at = clock(); var date = at.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        return tx.execute(status -> {
            sources.lock(tenant, payment.command().id());
            var queued = SupplierSettlementPreparation.queue(UUID.randomUUID(), payment, "finance", date, at); oldIntents.create(queued);
            var claimed = queued.claim(at, Duration.ofSeconds(30)); oldIntents.update(claimed);
            var period = new AccountingPeriodPort.OpenPeriod(new AccountingPeriodPort.Request(entity, "CNY", date), "period", "period-v1", date, date, at, at.plusSeconds(300));
            var evidence = new SupplierPayableSettlementEvidence(observed(payment.command().holdCommand().authorization(), at), paid(payment.command(), at), period, at);
            var command = claimed.input().command(evidence, at); var operation = SupplierPayableSettlementOperation.queue(command, at);
            oldOperations.create(claimed, operation); oldIntents.update(claimed.ready(command, at));
            var checking = operation.claim(at, Duration.ofSeconds(30)); oldOperations.update(checking);
            var sent = checking.readyToSend(evidence, at); oldOperations.update(sent);
            var posting = new SupplierPayableSettlementObservation.Posting("settlement", payment.command().held().holdReference(), "ledger-2", payment.command().amount(), money("30"), money("100"),
                    payment.observation().paymentReference(), payment.observation().receiptReference(), "original-payment-voucher", period.periodReference(), date, at);
            var done = sent.complete(new FinanceResult.Success<>(new SupplierPayableSettlementObservation(command.id(), command.digest(), SupplierPayableSettlementObservation.Status.SETTLED, 1L, at, posting, null)), at);
            oldOperations.update(done); reservations.complete(done, payment, at);
            return new SupplierPayableAdjustmentSource.OriginalSettlement(done.version(), command, done.observation());
        });
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
    private Instant clock() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private static Money money(String amount) { return new Money(new BigDecimal(amount), "CNY"); }
}
