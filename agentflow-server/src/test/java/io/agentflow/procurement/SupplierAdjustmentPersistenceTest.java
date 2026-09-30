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
    private JdbcSupplierAdjustmentCompletions completions;
    private SupplierAdjustmentCompletionService completion;

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
        reservations = new JdbcProcurementPayableReservationRepository(jdbc, json, procurements, new JdbcProcurementInvoiceClaims(jdbc), returnGuard, new JdbcSupplierAdjustmentCompletions(jdbc, json));
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
        returnLedgers = new JdbcSupplierPaymentReturnsRepository(jdbc, json, payments, new SupplierPayableReturnGuard(jdbc), new JdbcSupplierAdjustmentCompletions(jdbc, json));
        returnChecks = new JdbcSupplierPaymentReturnCheckRepository(jdbc, json);
        returnRegistrations = new JdbcSupplierPaymentReturnRepository(jdbc, json, returnLedgers, returnChecks, payments, new JdbcFinanceReceiptCreditRepository(jdbc));
        adjustmentSources = new JdbcSupplierAdjustmentSources(jdbc, json, procurements, returnLedgers, reservations, payments);
        adjustmentIntents = new JdbcSupplierAdjustmentPreparationRepository(jdbc, json, adjustmentSources);
        adjustments = new JdbcSupplierPayableAdjustmentRepository(jdbc, json, adjustmentIntents, adjustmentSources);
        completions = new JdbcSupplierAdjustmentCompletions(jdbc, json);
        completion = proxy(new SupplierAdjustmentCompletionService(adjustmentSources, adjustments, payments, returnLedgers, returnChecks, completions,
                new JdbcFinanceReceiptCreditRepository(jdbc), reservations));
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


    @Test void atomicCompletionAccountsFundsEndsOriginalHoldAndRestoresExactHistoricalProof() {
        var source = source(); var done = adjusted(adjustment(intent(source)), "return-voucher");
        var proof = finish(done, 20); var paymentId = proof.bank().command().id();
        assertThat(proof.after().reviewRequired()).isFalse(); assertThat(proof.after().accountedEntryCount()).isEqualTo(1);
        assertThat(returnLedgers.find(tenant, paymentId)).contains(proof.after());
        var original = proof.bank().command().holdCommand().authorization().source().reservation();
        assertThat(reservations.find(tenant, original.id())).contains(original.adjust(done, proof.completedAt()));
        assertThat(adjustments.active(tenant, paymentId)).isEmpty(); assertThat(adjustments.awaitingLocalCompletion()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT supplier_adjustment_id FROM finance_receipt_credit WHERE tenant_id=?", String.class, tenant)).isEqualTo(done.command().id().toString());
        assertThat(new JdbcSupplierAdjustmentCompletions(jdbc, json).find(tenant, done.command().id())).contains(proof);
        assertThat(completions.find("other", done.command().id())).isEmpty();
        assertThat(completion.complete(done, proof.receipt(), clock())).isEqualTo(proof);
        assertThat(count("supplier_adjustment_completion")).isEqualTo(1);
        assertThat(adjustments.find(tenant, done.command().id())).contains(done);
    }

    @Test void adjustmentAfterOriginalSettlementPreservesOriginalCompletionAndReservationBytes() {
        var source = source(true); var original = source.returns().request().command().holdCommand().authorization().source().reservation();
        var saved = jdbc.queryForList("SELECT * FROM supplier_settlement_completion WHERE tenant_id=?", tenant);
        var state = jdbc.queryForObject("SELECT state_json FROM procurement_payable_reservation WHERE tenant_id=? AND id=?", String.class, tenant, original.id().toString());
        var proof = finish(adjusted(adjustment(intent(source)), "return-after-settlement"), 20);
        assertThat(proof.after().reviewRequired()).isFalse();
        assertThat(jdbc.queryForList("SELECT * FROM supplier_settlement_completion WHERE tenant_id=?", tenant)).isEqualTo(saved);
        assertThat(jdbc.queryForObject("SELECT state_json FROM procurement_payable_reservation WHERE tenant_id=? AND id=?", String.class, tenant, original.id().toString())).isEqualTo(state);
        assertThat(reservations.find(tenant, original.id()).orElseThrow().settlement()).isNotNull();
    }

    @Test void lateRegisteredFundsRemainPendingAndNextAdjustmentOnlyPostsNewMoney() {
        var source = source(); var done = adjusted(adjustment(intent(source)), "return-first"); var paymentId = source.returns().request().command().id();
        var funds = source.returns().entries().get(0).proof();
        var check = returnCheck(payments.find(tenant, paymentId).orElseThrow(), "finance", 11, funds, returnFunds("second", "10", clock()));
        registerReturn(check, returnDecision(check));
        var first = finish(done, 20); assertThat(first.after().reviewRequired()).isTrue(); assertThat(first.after().accountedEntryCount()).isEqualTo(1);
        var previous = new SupplierPayableAdjustmentSource.Previous(paymentId, done.command().source().returns().request().command().digest(), null,
                done.version(), done.command().source().returns().entries(), done.observation());
        var next = new SupplierPayableAdjustmentSource(first.after(), null, previous);
        var queued = adjustment(intent(next));
        // 旧完成进入查询仍不能夺走新命令的活动位置，且新的记账必须等待旧结果再次核清。
        var unknown = done.requestQuery(clock()); tx.executeWithoutResult(status -> adjustments.update(unknown));
        assertThat(adjustments.active(tenant, paymentId)).contains(queued); assertThat(completions.find(tenant, done.command().id())).contains(first);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> adjustmentSources.requireCurrent(next))).isInstanceOf(DomainException.class);
        var querying = unknown.claim(clock(), Duration.ofSeconds(30)); tx.executeWithoutResult(status -> adjustments.update(querying));
        var at = clock(); var reconciled = querying.complete(new FinanceResult.Success<>(new SupplierPayableAdjustmentObservation(done.command().id(), done.command().digest(),
                SupplierPayableAdjustmentObservation.Status.ADJUSTED, 2, at, done.observation().posting(), null)), at);
        tx.executeWithoutResult(status -> adjustments.update(reconciled));
        var secondDone = adjusted(queued, "return-second"); var second = finish(secondDone, 40);
        assertThat(second.after().reviewRequired()).isFalse(); assertThat(second.after().accountedEntryCount()).isEqualTo(2);
        assertThat(secondDone.observation().posting().returnedAmount()).isEqualTo(money("10"));
        assertThat(count("supplier_adjustment_completion")).isEqualTo(2);
        assertThat(completions.history(tenant, paymentId)).containsExactly(first, second);
        var original = source.returns().request().command().holdCommand().authorization().source().reservation();
        assertThat(reservations.find(tenant, original.id())).contains(original.adjust(done, first.completedAt()));
    }

    @Test void accountingEntryConflictRollsBackAllLocalCompletionWhileErpSuccessRemainsDurable() {
        finish(adjusted(adjustment(intent(source())), "shared-voucher"), 20);
        var next = source(); var done = adjusted(adjustment(intent(next)), "shared-voucher"); var paymentId = next.returns().request().command().id();
        var beforeRows = jdbc.queryForList("SELECT * FROM supplier_payment_returns_revision WHERE tenant_id=? AND payment_id=?", tenant, paymentId.toString());
        assertThatThrownBy(() -> finish(done, 20)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(adjustments.find(tenant, done.command().id())).contains(done); assertThat(adjustments.active(tenant, paymentId)).contains(done);
        assertThat(returnLedgers.find(tenant, paymentId)).contains(next.returns()); assertThat(completions.find(tenant, done.command().id())).isEmpty();
        assertThat(jdbc.queryForList("SELECT * FROM supplier_payment_returns_revision WHERE tenant_id=? AND payment_id=?", tenant, paymentId.toString())).isEqualTo(beforeRows);
        var original = next.returns().request().command().holdCommand().authorization().source().reservation(); assertThat(reservations.find(tenant, original.id())).contains(original);
        assertThat(jdbc.queryForObject("SELECT voucher_reference FROM finance_receipt_credit WHERE tenant_id=? AND business_id=?", String.class, tenant, original.source().requestId().toString())).isNull();
    }

    @Test void completedHigherBankRevisionCannotBeDowngradedByARegistrationQuery() {
        var source = source(); var done = adjusted(adjustment(intent(source)), "return-higher-bank"); var proof = finish(done, 20);
        var check = returnCheck(proof.bank(), "finance", 11, source.returns().entries().get(0).proof(), returnFunds("newer", "10", clock()));
        assertThatThrownBy(() -> registerReturn(check, returnDecision(check))).isInstanceOf(DomainException.class);
        assertThat(returnLedgers.find(tenant, proof.bank().command().id()).orElseThrow().entries()).isEqualTo(proof.after().entries());
        assertThat(count("finance_receipt_credit")).isEqualTo(1);
    }

    @Test void staleSuccessAndFreshButUnregisteredMoneyCannotCompleteAccounting() {
        var source = source(); var done = adjusted(adjustment(intent(source)), "return-protected"); var paymentId = source.returns().request().command().id();
        var check = returnCheck(payments.find(tenant, paymentId).orElseThrow(), "finance", 15,
                source.returns().entries().get(0).proof(), returnFunds("unregistered", "10", clock()));
        assertThatThrownBy(() -> finish(done, 20)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> completion.complete(done, check.receipt(), clock())).isInstanceOf(DomainException.class);
        var unknown = done.requestQuery(clock()); tx.executeWithoutResult(status -> adjustments.update(unknown));
        assertThatThrownBy(() -> finish(done, 20)).isInstanceOf(DomainException.class);
        assertThat(count("supplier_adjustment_completion")).isZero(); assertThat(count("finance_receipt_credit")).isEqualTo(1);
    }

    @Test void bankUnknownAfterAdjustmentBlocksFurtherPaymentsOnTheSamePayable() {
        var done = adjusted(adjustment(intent(source())), "return-bank-query"); var proof = finish(done, 20);
        var content = proof.bank().command().holdCommand().authorization().source().reservation().source().round().content();
        var guard = new SupplierPayableReturnGuard(jdbc); assertThat(guard.blocked(tenant, content)).isFalse();
        tx.executeWithoutResult(status -> bank.query(tenant, proof.bank().command().id(), proof.bank().version(), clock()));
        assertThat(guard.blocked(tenant, content)).isTrue();
    }

    @Test void erpUnknownAfterCompletedAdjustmentBlocksFurtherPaymentsOnTheSamePayable() {
        var done = adjusted(adjustment(intent(source())), "return-erp-query"); var proof = finish(done, 20);
        var content = proof.bank().command().holdCommand().authorization().source().reservation().source().round().content();
        var guard = new SupplierPayableReturnGuard(jdbc); assertThat(guard.blocked(tenant, content)).isFalse();
        tx.executeWithoutResult(status -> adjustments.update(done.requestQuery(clock())));
        assertThat(guard.blocked(tenant, content)).isTrue();
    }

    @Test void fullyReturnedBankBecomesClearOnlyAfterExactAccountingBeforeOrAfterOriginalSettlement() {
        for (boolean settled : List.of(false, true)) {
            var partial = source(settled); var paymentId = partial.returns().request().command().id();
            var reversed = reverseBank(payments.find(tenant, paymentId).orElseThrow());
            var check = returnCheck(reversed, "finance", 3, partial.returns().entries().get(0).proof(), returnFunds("remaining-" + settled, "50", clock()));
            var all = registerReturn(check, returnDecision(check)); var source = new SupplierPayableAdjustmentSource(all, partial.settlement(), null);
            var content = reversed.command().holdCommand().authorization().source().reservation().source().round().content();
            var guard = new SupplierPayableReturnGuard(jdbc); assertThat(guard.blocked(tenant, content)).isTrue();
            var done = adjusted(adjustment(intent(source)), "full-return-" + settled); var completed = finish(done, 20);
            assertThat(completed.after().totalReturned()).isEqualTo(reversed.command().amount());
            assertThat(done.observation().posting().netPaid()).isEqualTo(money("0"));
            assertThat(completed.after().reviewRequired()).isFalse(); assertThat(guard.blocked(tenant, content)).isFalse();
            assertThat(jdbc.queryForObject("SELECT bank_status FROM supplier_adjustment_completion WHERE tenant_id=? AND operation_id=?", String.class, tenant, done.command().id().toString())).isEqualTo("REVERSED");
        }
    }

    @Test void newBankReversalAfterPartialAccountingStillRequiresFullReturnVerification() {
        var done = adjusted(adjustment(intent(source())), "partial-before-reversal"); var completed = finish(done, 20);
        var reversed = reverseBank(completed.bank());
        var content = reversed.command().holdCommand().authorization().source().reservation().source().round().content();
        assertThat(returnLedgers.find(tenant, reversed.command().id()).orElseThrow().reviewRequired()).isFalse();
        assertThat(new SupplierPayableReturnGuard(jdbc).blocked(tenant, content)).isTrue();
    }

    @Test void concurrentCompletionUsesOneProofAndOneAccountingRevision() throws Exception {
        var done = adjusted(adjustment(intent(source())), "return-concurrent"); var at = clock();
        var ledger = done.command().source().returns(); var receipt = completionReceipt(ledger, 20, at);
        var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { start.await(); return completion.complete(done, receipt, at); });
            var second = pool.submit(() -> { start.await(); return completion.complete(done, receipt, at); }); start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(second.get(15, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
        assertThat(count("supplier_adjustment_completion")).isEqualTo(1);
        assertThat(returnLedgers.find(tenant, ledger.request().command().id()).orElseThrow().version()).isEqualTo(ledger.version() + 1);
        assertThat(count("finance_receipt_credit")).isEqualTo(1);
    }

    @Test void originalErpSucceededButLocalReservationStillHeldCanFinishViaAdjustment() {
        var payment = returnedSource(); var originalSettlement = originalSettlement(payment, false);
        var oldOperation = jdbc.queryForList("SELECT * FROM supplier_payable_settlement_operation WHERE tenant_id=?", tenant);
        var check = returnCheck(payment, "finance", 2, returnFunds("before-local-settlement", "20", clock()));
        var ledger = registerReturn(check, returnDecision(check)); var source = new SupplierPayableAdjustmentSource(ledger, originalSettlement, null);
        var original = payment.command().holdCommand().authorization().source().reservation();
        assertThat(reservations.find(tenant, original.id())).contains(original);
        var done = adjusted(adjustment(intent(source)), "after-erp-before-local"); var proof = finish(done, 20);
        assertThat(reservations.find(tenant, original.id())).contains(original.adjust(done, proof.completedAt()));
        assertThat(count("supplier_settlement_completion")).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM supplier_payable_settlement_operation WHERE tenant_id=?", tenant)).isEqualTo(oldOperation);
        assertThat(proof.after().reviewRequired()).isFalse();
        var nextCheck = returnCheck(payment, "finance", 21, ledger.entries().get(0).proof(), returnFunds("later-after-erp", "10", clock()));
        var nextLedger = registerReturn(nextCheck, returnDecision(nextCheck));
        var previous = new SupplierPayableAdjustmentSource.Previous(payment.command().id(), payment.command().digest(), originalSettlement.command().id(),
                done.version(), done.command().source().returns().entries(), done.observation());
        var nextSource = new SupplierPayableAdjustmentSource(nextLedger, originalSettlement, previous);
        var second = finish(adjusted(adjustment(intent(nextSource)), "second-after-erp-before-local"), 40);
        assertThat(second.after().reviewRequired()).isFalse(); assertThat(count("supplier_settlement_completion")).isZero();
        assertThat(reservations.find(tenant, original.id())).contains(original.adjust(done, proof.completedAt()));
    }

    @Test void originalSettlementUncertaintyAfterAdjustmentKeepsPayableFrozen() {
        var source = source(true); var done = adjusted(adjustment(intent(source)), "after-original-query"); var proof = finish(done, 20);
        var oldIntents = new JdbcSupplierSettlementPreparationRepository(jdbc, json, payments);
        var oldOperations = new JdbcSupplierPayableSettlementRepository(jdbc, json, oldIntents, payments, reservations);
        var original = oldOperations.find(tenant, source.settlement().command().id()).orElseThrow();
        tx.executeWithoutResult(status -> oldOperations.update(original.requestQuery(clock())));
        var content = proof.bank().command().holdCommand().authorization().source().reservation().source().round().content();
        assertThat(new SupplierPayableReturnGuard(jdbc).blocked(tenant, content)).isTrue();
    }

    private SupplierPaymentOperation reverseBank(SupplierPaymentOperation paid) {
        tx.executeWithoutResult(status -> bank.query(tenant, paid.command().id(), paid.version(), clock()));
        var claimed = bank.claim(tenant, paid.command().id(), clock()); var at = clock(); var original = paid.observation();
        var reversed = new PaymentObservation(paid.command().id(), paid.command().digest(), PaymentObservation.Status.REVERSED, original.revision() + 1, at,
                original.paymentReference(), original.paidAmount(), original.accountDigest(), at, "reversal-bank-receipt", null);
        bank.finish(claimed, new FinanceResult.Success<>(reversed), at); return payments.find(tenant, paid.command().id()).orElseThrow();
    }

    private SupplierPayableAdjustmentOperation adjusted(SupplierPayableAdjustmentOperation queued, String voucher) {
        var sent = dispatch(queued); var command = sent.command(); var source = command.source(); var at = clock();
        var payment = source.returns().request().command();
        var before = source.previous() != null ? source.previous().observation().posting().payableSettledAfter()
                : source.settlement() != null ? source.settlement().observation().posting().settledAfter() : payment.holdCommand().authorization().payable().settled();
        var after = (source.recognizesOriginalPayment() ? before.plus(payment.amount()) : before).minus(source.newReturned());
        var recognition = source.previous() != null ? source.previous().observation().posting().recognitionVoucherReference()
                : source.settlement() != null ? source.settlement().observation().posting().voucherReference() : "recognized-payment";
        var posting = new SupplierPayableAdjustmentObservation.Posting("adjust-" + command.id(), payment.held().holdReference(), "ledger-adjusted", recognition,
                source.newReturned(), source.returns().totalReturned(), source.netPaid(), before, after,
                java.util.stream.IntStream.range(0, source.newReturns().size()).mapToObj(index -> { var entry = source.newReturns().get(index); return new SupplierPayableAdjustmentObservation.ReturnEntry(entry.proof().transactionReference(), entry.proof().amount(), voucher, "return-entry-" + index); }).toList(),
                command.period().periodReference(), command.period().request().accountingDate(), at);
        var result = sent.complete(new FinanceResult.Success<>(new SupplierPayableAdjustmentObservation(command.id(), command.digest(), SupplierPayableAdjustmentObservation.Status.ADJUSTED, 1, at, posting, null)), at);
        assertThat(result.adjusted()).isTrue(); tx.executeWithoutResult(status -> adjustments.update(result)); return result;
    }
    private SupplierAdjustmentCompletion finish(SupplierPayableAdjustmentOperation done, long revision) {
        var ledger = returnLedgers.find(tenant, done.command().source().returns().request().command().id()).orElseThrow(); var at = clock();
        return completion.complete(done, completionReceipt(ledger, revision, at), at);
    }
    private SupplierPaymentReturnPort.Receipt completionReceipt(SupplierPaymentReturns ledger, long revision, Instant at) {
        var payment = ledger.request().command(); var original = payments.find(tenant, payment.id()).orElseThrow().observation();
        var observed = new PaymentObservation(payment.id(), payment.digest(), original.status(), revision, at, original.paymentReference(), payment.amount(), payment.payee().accountDigest(), original.completedAt(), original.receiptReference(), null);
        var status = original.status() == PaymentObservation.Status.REVERSED ? SupplierPaymentReturnPort.Status.RETURNED : SupplierPaymentReturnPort.Status.PARTIALLY_RETURNED;
        return new SupplierPaymentReturnPort.Receipt(ledger.request(), status, revision, at, at.plusSeconds(300), observed,
                ledger.entries().stream().map(SupplierPaymentReturns.Entry::proof).toList());
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
        var date = at.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        var period = new AccountingPeriodPort.OpenPeriod(new AccountingPeriodPort.Request(entity, "CNY", date), "period", "period-v1", date, date, at, at.plusSeconds(300));
        var settled = source.settlement();
        var originalPosting = settled == null ? null : new SupplierPayableSettlementObservation(settled.command().id(), settled.command().digest(),
                SupplierPayableSettlementObservation.Status.SETTLED, settled.observation().revision(), at, settled.observation().posting(), null);
        var previous = source.previous();
        var previousPosting = previous == null ? null : new SupplierPayableAdjustmentObservation(previous.observation().operationId(), previous.observation().commandDigest(),
                SupplierPayableAdjustmentObservation.Status.ADJUSTED, previous.observation().revision(), at, previous.observation().posting(), null);
        return new SupplierPayableAdjustmentEvidence(completionReceipt(source.returns(), previous == null ? 10 : 30, at), source.recognizesOriginalPayment() ? observed(payment.holdCommand().authorization(), at) : null,
                originalPosting, previousPosting, period, at);
    }

    private SupplierPayableAdjustmentSource.OriginalSettlement originalSettlement(SupplierPaymentOperation payment) { return originalSettlement(payment, true); }
    private SupplierPayableAdjustmentSource.OriginalSettlement originalSettlement(SupplierPaymentOperation payment, boolean completeLocally) {
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
            oldOperations.update(done); if (completeLocally) reservations.complete(done, payment, at);
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
            var bankReceipt = new PaymentObservation(payment.command().id(), payment.command().digest(), payment.observation().status(), revision, observed,
                    original.observation().paymentReference(), payment.command().amount(), payment.command().payee().accountDigest(), payment.observation().completedAt(), payment.observation().receiptReference(), null);
            var returnStatus = payment.status() == SupplierPaymentOperation.Status.REVERSED ? SupplierPaymentReturnPort.Status.RETURNED : SupplierPaymentReturnPort.Status.PARTIALLY_RETURNED;
            var proof = new SupplierPaymentReturnPort.Receipt(request, funds.length == 0 ? SupplierPaymentReturnPort.Status.CONFIRMED : returnStatus,
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
