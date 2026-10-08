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
import io.agentflow.finance.AccountingPeriodPort;
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.finance.FinanceResult;
import io.agentflow.finance.JdbcFinanceReceiptCreditRepository;
import io.agentflow.finance.Money;
import io.agentflow.finance.PaymentAccountsPort;
import io.agentflow.finance.PaymentObservation;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 已登记回款驱动独立调整，验证真实来源、唯一办理位置、连续修订和无损升级。
 *
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
    private PaymentPersonnel personnel;
    private JdbcSupplierPaymentReturnsRepository returnLedgers;
    private JdbcSupplierPaymentReturnCheckRepository returnChecks;
    private JdbcSupplierPaymentReturnRepository returnRegistrations;
    private static final PaymentAccountsPort.DebitAccount DEBIT = new PaymentAccountsPort.DebitAccount("debit-1", "法人基本户", "****5678", "CNY", "v1");

    private JdbcSupplierAdjustmentSources adjustmentSources;
    private JdbcSupplierAdjustmentPreparationRepository adjustmentIntents;
    private JdbcSupplierPayableAdjustmentRepository adjustments;
    private JdbcSupplierAdjustmentCompletions completions;
    private SupplierAdjustmentCompletionService completion;
    private SupplierPaymentReturnPort readReturns;
    private SupplierPayableHoldPort readHolds;
    private SupplierPayableSettlementPort readSettlements;
    private SupplierPayableAdjustmentPort readAdjustments;
    private AccountingPeriodPort readPeriods;
    private SupplierAdjustmentPreparationService adjustmentPreparation;
    private SupplierAdjustmentService adjustmentExecution;
    private SupplierAdjustmentPreparationWorker intentWorker;
    private SupplierAdjustmentWorker adjustmentWorker;
    private SupplierAdjustmentEvidenceReader workerReader;
    private SupplierPayableAdjustmentObservation remoteAdjustment;

    @BeforeEach void database() { database(null); }

    private void database(String target) {
        dataSource = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_SUPPLIER_ADJUSTMENT_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_SUPPLIER_ADJUSTMENT_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_SUPPLIER_ADJUSTMENT_PASSWORD", ""));
        schema = "supplier_adjustment_" + UUID.randomUUID().toString().replace("-", ""); new JdbcTemplate(dataSource).execute("CREATE SCHEMA \"" + schema + "\""); dataSource.setSchema(schema);
        var migration = Flyway.configure().dataSource(dataSource).defaultSchema(schema); if (target != null) migration.target(target); migration.load().migrate();
        jdbc = target == null ? new JdbcTemplate(dataSource) : new SupplierMigrationJdbcTemplate(dataSource); manager = new DataSourceTransactionManager(dataSource); tx = new TransactionTemplate(manager);
        procurements = new JdbcProcurementPaymentRepository(
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
                                        .ProcurementPayableReservationRepositoryMapper.class), json, procurements, new JdbcProcurementInvoiceClaims(
                                SupplierMigrationJdbcTemplate.mapper(jdbc,
                                        io.agentflow.procurement.mapper
                                                .ProcurementInvoiceClaimsMapper.class)),
                        returnGuard,
                        new JdbcSupplierAdjustmentCompletions(
                                SupplierMigrationJdbcTemplate.mapper(jdbc,
                                        io.agentflow.procurement.mapper
                                                .SupplierAdjustmentCompletionsMapper.class), json));
        approvedSources = new ApprovedSupplierPaymentSources(new JdbcApplicationRepository(
                                SupplierMigrationJdbcTemplate.mapper(jdbc,
                                        io.agentflow.approval.mapper.ApplicationRepositoryMapper
                                                .class), json), procurements, reservations, returnGuard);
        authorizations = new JdbcSupplierPaymentAuthorizationRepository(
                        SupplierMigrationJdbcTemplate.mapper(jdbc,
                                io.agentflow.procurement.mapper
                                        .SupplierPaymentAuthorizationRepositoryMapper.class), json, approvedSources); holds = new JdbcSupplierPayableHoldRepository(
                        SupplierMigrationJdbcTemplate.mapper(jdbc,
                                io.agentflow.procurement.mapper.SupplierPayableHoldRepositoryMapper
                                        .class), json, authorizations);
        personnel = mock(PaymentPersonnel.class);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return null;
        }).when(personnel).requireEligible(eq(tenant), anyString(), eq(entity));
        holdService = proxy(new SupplierPayableHoldService(approvedSources, authorizations, holds, personnel, 30, event -> { }));
        sources = new SupplierPaymentSources(approvedSources, authorizations, holds, personnel);
        requests = new JdbcSupplierPaymentExecutionRepository(
                        SupplierMigrationJdbcTemplate.mapper(jdbc,
                                io.agentflow.procurement.mapper
                                        .SupplierPaymentExecutionRepositoryMapper.class), json, holds);
        payments = new JdbcSupplierPaymentOperationRepository(
                        SupplierMigrationJdbcTemplate.mapper(jdbc,
                                io.agentflow.procurement.mapper
                                        .SupplierPaymentOperationRepositoryMapper.class), json, requests, holds, authorizations);
        preparation = proxy(new SupplierPaymentExecutionService(sources, requests, payments, event -> { }, 30)); bank = proxy(new SupplierPaymentService(sources, payments, event -> { }, 30));
        returnLedgers = new JdbcSupplierPaymentReturnsRepository(
                        SupplierMigrationJdbcTemplate.mapper(jdbc,
                                io.agentflow.procurement.mapper
                                        .SupplierPaymentReturnsRepositoryMapper.class), json, payments, new SupplierPayableReturnGuard(
                                SupplierMigrationJdbcTemplate.mapper(jdbc,
                                        io.agentflow.procurement.mapper
                                                .SupplierPayableReturnGuardMapper.class)),
                        new JdbcSupplierAdjustmentCompletions(
                                SupplierMigrationJdbcTemplate.mapper(jdbc,
                                        io.agentflow.procurement.mapper
                                                .SupplierAdjustmentCompletionsMapper.class), json));
        returnChecks = new JdbcSupplierPaymentReturnCheckRepository(
                        SupplierMigrationJdbcTemplate.mapper(jdbc,
                                io.agentflow.procurement.mapper
                                        .SupplierPaymentReturnCheckRepositoryMapper.class), json);
        returnRegistrations = new JdbcSupplierPaymentReturnRepository(
                        SupplierMigrationJdbcTemplate.mapper(jdbc,
                                io.agentflow.procurement.mapper
                                        .SupplierPaymentReturnRepositoryMapper.class), json, returnLedgers, returnChecks, payments, new JdbcFinanceReceiptCreditRepository(
                                SupplierMigrationJdbcTemplate.mapper(jdbc,
                                        io.agentflow.finance.mapper
                                                .FinanceReceiptCreditRepositoryMapper.class)));
        adjustmentSources = new JdbcSupplierAdjustmentSources(
                        SupplierMigrationJdbcTemplate.mapper(jdbc,
                                io.agentflow.procurement.mapper.SupplierAdjustmentSourcesMapper
                                        .class), json, procurements, returnLedgers, reservations, payments, new JdbcSupplierAdjustmentCompletions(
                                SupplierMigrationJdbcTemplate.mapper(jdbc,
                                        io.agentflow.procurement.mapper
                                                .SupplierAdjustmentCompletionsMapper.class), json), returnChecks);
        adjustmentIntents = new JdbcSupplierAdjustmentPreparationRepository(
                        SupplierMigrationJdbcTemplate.mapper(jdbc,
                                io.agentflow.procurement.mapper
                                        .SupplierAdjustmentPreparationRepositoryMapper.class), json, adjustmentSources);
        adjustments = new JdbcSupplierPayableAdjustmentRepository(
                        SupplierMigrationJdbcTemplate.mapper(jdbc,
                                io.agentflow.procurement.mapper
                                        .SupplierPayableAdjustmentRepositoryMapper.class), json, adjustmentIntents, adjustmentSources);
        completions = new JdbcSupplierAdjustmentCompletions(
                        SupplierMigrationJdbcTemplate.mapper(jdbc,
                                io.agentflow.procurement.mapper.SupplierAdjustmentCompletionsMapper
                                        .class), json);
        completion = proxy(new SupplierAdjustmentCompletionService(adjustmentSources, adjustments, payments, returnLedgers, returnChecks, completions,
                new JdbcFinanceReceiptCreditRepository(
                                        SupplierMigrationJdbcTemplate.mapper(jdbc,
                                                io.agentflow.finance.mapper
                                                        .FinanceReceiptCreditRepositoryMapper
                                                        .class)), reservations, event -> { }));
        var settlementSources = new SupplierSettlementSources(sources, approvedSources, payments, personnel, returnGuard);
        var eligibility = new SupplierAdjustmentSources(adjustmentSources, settlementSources, sources);
        adjustmentPreparation = proxy(new SupplierAdjustmentPreparationService(adjustmentSources, eligibility, adjustmentIntents, adjustments, event -> { }, 30));
        adjustmentExecution = proxy(new SupplierAdjustmentService(adjustmentSources, eligibility, settlementSources, adjustments, event -> { }, 30));
    }


    @Test void registeredSourceAndExactIntentSurviveReopeningWithoutChangingOriginalFunds() {
        var source = source(); var value = adjustment(intent(source)); var id = value.command().id();
        var reopenedIntents = new JdbcSupplierAdjustmentPreparationRepository(
                        SupplierMigrationJdbcTemplate.mapper(jdbc,
                                io.agentflow.procurement.mapper
                                        .SupplierAdjustmentPreparationRepositoryMapper.class), json, adjustmentSources);
        var reopened = new JdbcSupplierPayableAdjustmentRepository(
                        SupplierMigrationJdbcTemplate.mapper(jdbc,
                                io.agentflow.procurement.mapper
                                        .SupplierPayableAdjustmentRepositoryMapper.class), json, reopenedIntents, adjustmentSources);
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
        jdbc.update(
                "INSERT INTO"
                    + " supplier_adjustment_preparation_revision(tenant_id,preparation_id,version,state_json)"
                    + " VALUES(?,?,?,?)", tenant, id.toString(), claimed.version() + 1, json.write(claimed));
        assertThatThrownBy(() -> registerAdjustment(claimed)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(adjustmentIntents.find(tenant, id)).contains(claimed); assertThat(count("supplier_payable_adjustment_operation")).isZero();
        jdbc.update(
                "DELETE FROM supplier_adjustment_preparation_revision WHERE tenant_id=? AND"
                        + " preparation_id=? AND version=?", tenant, id.toString(), claimed.version() + 1);
        var expired = claimed.expireLease(claimed.leaseUntil()); tx.executeWithoutResult(status -> adjustmentIntents.update(expired));
        var next = expired.claim(expired.updatedAt(), Duration.ofSeconds(30)); tx.executeWithoutResult(status -> adjustmentIntents.update(next));
        assertThatThrownBy(() -> registerAdjustment(claimed)).isInstanceOf(DomainException.class);
        var registered = registerAdjustment(next); assertThat(registered.command().source()).isEqualTo(value.input().source());
        assertThat(registered.command().period().request().accountingDate()).isEqualTo(value.input().accountingDate());
    }

    @Test void lostWritePersistsOriginalCommandAndOnlyResumesQuerying() {
        var sent = dispatch(adjustment(intent(source()))); var missing = sent.unavailable(SupplierPayableAdjustmentOperation.Failure.TIMEOUT, sent.updatedAt());
        tx.executeWithoutResult(status -> adjustments.update(missing));
        var reopened = new JdbcSupplierPayableAdjustmentRepository(
                        SupplierMigrationJdbcTemplate.mapper(jdbc,
                                io.agentflow.procurement.mapper
                                        .SupplierPayableAdjustmentRepositoryMapper.class), json, adjustmentIntents, adjustmentSources);
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
        var originalIntents = new JdbcSupplierSettlementPreparationRepository(
                        SupplierMigrationJdbcTemplate.mapper(jdbc,
                                io.agentflow.procurement.mapper
                                        .SupplierSettlementPreparationRepositoryMapper.class), json, payments);
        var originalOperations = new JdbcSupplierPayableSettlementRepository(
                        SupplierMigrationJdbcTemplate.mapper(jdbc,
                                io.agentflow.procurement.mapper
                                        .SupplierPayableSettlementRepositoryMapper.class), json, originalIntents, payments, reservations);
        var original = originalOperations.find(tenant, source.settlement().command().id()).orElseThrow();
        tx.executeWithoutResult(status -> originalOperations.update(original.requestQuery(clock())));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> adjustmentSources.requireCurrent(source))).isInstanceOf(DomainException.class);
        assertThat(adjustments.find(tenant, queued.command().id())).contains(queued);
        assertThat(jdbc.queryForList("SELECT * FROM supplier_settlement_completion WHERE tenant_id=?", tenant)).isEqualTo(before);
    }

    @Test void anExistingOriginalSettlementPreparationMustStopBeforeIndependentAdjustment() {
        var source = source(); var payment = payments.find(tenant, source.returns().request().command().id()).orElseThrow();
        var oldIntents = new JdbcSupplierSettlementPreparationRepository(
                        SupplierMigrationJdbcTemplate.mapper(jdbc,
                                io.agentflow.procurement.mapper
                                        .SupplierSettlementPreparationRepositoryMapper.class), json, payments); var at = clock();
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
        assertThat(jdbc.queryForObject(
                                "SELECT COUNT(*) FROM finance_receipt_credit WHERE tenant_id=? AND"
                                        + " supplier_adjustment_id IS NULL AND voucher_reference IS"
                                        + " NULL", Integer.class, tenant)).isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update(
                                        "UPDATE finance_receipt_credit SET"
                                            + " voucher_reference='forged',entry_reference='entry'"
                                            + " WHERE tenant_id=?", tenant)).isInstanceOf(DataIntegrityViolationException.class);
    }


    @Test void atomicCompletionAccountsFundsEndsOriginalHoldAndRestoresExactHistoricalProof() {
        var source = source(); var done = adjusted(adjustment(intent(source)), "return-voucher");
        var proof = finish(done, 20); var paymentId = proof.bank().command().id();
        assertThat(proof.after().reviewRequired()).isFalse(); assertThat(proof.after().accountedEntryCount()).isEqualTo(1);
        assertThat(returnLedgers.find(tenant, paymentId)).contains(proof.after());
        var original = proof.bank().command().holdCommand().authorization().source().reservation();
        assertThat(reservations.find(tenant, original.id())).contains(original.adjust(done, proof.completedAt()));
        assertThat(adjustments.active(tenant, paymentId)).isEmpty(); assertThat(adjustments.awaitingLocalCompletion()).isEmpty();
        assertThat(jdbc.queryForObject(
                                "SELECT supplier_adjustment_id FROM finance_receipt_credit WHERE"
                                        + " tenant_id=?", String.class, tenant)).isEqualTo(done.command().id().toString());
        assertThat(new JdbcSupplierAdjustmentCompletions(
                                        SupplierMigrationJdbcTemplate.mapper(jdbc,
                                                io.agentflow.procurement.mapper
                                                        .SupplierAdjustmentCompletionsMapper.class), json).find(tenant, done.command().id())).contains(proof);
        assertThat(completions.find("other", done.command().id())).isEmpty();
        assertThat(completion.complete(done, proof.receipt(), clock())).isEqualTo(proof);
        assertThat(count("supplier_adjustment_completion")).isEqualTo(1);
        assertThat(adjustments.find(tenant, done.command().id())).contains(done);
    }

    @Test void adjustmentAfterOriginalSettlementPreservesOriginalCompletionAndReservationBytes() {
        var source = source(true); var original = source.returns().request().command().holdCommand().authorization().source().reservation();
        var saved = jdbc.queryForList("SELECT * FROM supplier_settlement_completion WHERE tenant_id=?", tenant);
        var state = jdbc.queryForObject(
                        "SELECT state_json FROM procurement_payable_reservation WHERE tenant_id=?"
                                + " AND id=?", String.class, tenant, original.id().toString());
        var proof = finish(adjusted(adjustment(intent(source)), "return-after-settlement"), 20);
        assertThat(proof.after().reviewRequired()).isFalse();
        assertThat(jdbc.queryForList("SELECT * FROM supplier_settlement_completion WHERE tenant_id=?", tenant)).isEqualTo(saved);
        assertThat(jdbc.queryForObject(
                                "SELECT state_json FROM procurement_payable_reservation WHERE"
                                        + " tenant_id=? AND id=?", String.class, tenant, original.id().toString())).isEqualTo(state);
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
        var beforeRows = jdbc.queryForList(
                        "SELECT * FROM supplier_payment_returns_revision WHERE tenant_id=? AND"
                                + " payment_id=?", tenant, paymentId.toString());
        assertThatThrownBy(() -> finish(done, 20)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(adjustments.find(tenant, done.command().id())).contains(done); assertThat(adjustments.active(tenant, paymentId)).contains(done);
        assertThat(returnLedgers.find(tenant, paymentId)).contains(next.returns()); assertThat(completions.find(tenant, done.command().id())).isEmpty();
        assertThat(jdbc.queryForList(
                                "SELECT * FROM supplier_payment_returns_revision WHERE tenant_id=?"
                                        + " AND payment_id=?", tenant, paymentId.toString())).isEqualTo(beforeRows);
        var original = next.returns().request().command().holdCommand().authorization().source().reservation(); assertThat(reservations.find(tenant, original.id())).contains(original);
        assertThat(jdbc.queryForObject(
                                "SELECT voucher_reference FROM finance_receipt_credit WHERE"
                                        + " tenant_id=? AND business_id=?", String.class, tenant, original.source().requestId().toString())).isNull();
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
        var guard = new SupplierPayableReturnGuard(
                        SupplierMigrationJdbcTemplate.mapper(jdbc,
                                io.agentflow.procurement.mapper.SupplierPayableReturnGuardMapper
                                        .class)); assertThat(guard.blocked(tenant, content)).isFalse();
        tx.executeWithoutResult(status -> bank.query(tenant, proof.bank().command().id(), proof.bank().version(), clock()));
        assertThat(guard.blocked(tenant, content)).isTrue();
    }

    @Test void erpUnknownAfterCompletedAdjustmentBlocksFurtherPaymentsOnTheSamePayable() {
        var done = adjusted(adjustment(intent(source())), "return-erp-query"); var proof = finish(done, 20);
        var content = proof.bank().command().holdCommand().authorization().source().reservation().source().round().content();
        var guard = new SupplierPayableReturnGuard(
                        SupplierMigrationJdbcTemplate.mapper(jdbc,
                                io.agentflow.procurement.mapper.SupplierPayableReturnGuardMapper
                                        .class)); assertThat(guard.blocked(tenant, content)).isFalse();
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
            var guard = new SupplierPayableReturnGuard(
                            SupplierMigrationJdbcTemplate.mapper(jdbc,
                                    io.agentflow.procurement.mapper.SupplierPayableReturnGuardMapper
                                            .class)); assertThat(guard.blocked(tenant, content)).isTrue();
            var done = adjusted(adjustment(intent(source)), "full-return-" + settled); var completed = finish(done, 20);
            assertThat(completed.after().totalReturned()).isEqualTo(reversed.command().amount());
            assertThat(done.observation().posting().netPaid()).isEqualTo(money("0"));
            assertThat(completed.after().reviewRequired()).isFalse(); assertThat(guard.blocked(tenant, content)).isFalse();
            assertThat(jdbc.queryForObject(
                                    "SELECT bank_status FROM supplier_adjustment_completion WHERE"
                                            + " tenant_id=? AND operation_id=?", String.class, tenant, done.command().id().toString())).isEqualTo("REVERSED");
        }
    }

    @Test void newBankReversalAfterPartialAccountingStillRequiresFullReturnVerification() {
        var done = adjusted(adjustment(intent(source())), "partial-before-reversal"); var completed = finish(done, 20);
        var reversed = reverseBank(completed.bank());
        var content = reversed.command().holdCommand().authorization().source().reservation().source().round().content();
        assertThat(returnLedgers.find(tenant, reversed.command().id()).orElseThrow().reviewRequired()).isFalse();
        assertThat(new SupplierPayableReturnGuard(
                                        SupplierMigrationJdbcTemplate.mapper(jdbc,
                                                io.agentflow.procurement.mapper
                                                        .SupplierPayableReturnGuardMapper.class)).blocked(tenant, content)).isTrue();
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
        assertThat(jdbc.queryForList(
                                "SELECT * FROM supplier_payable_settlement_operation WHERE"
                                        + " tenant_id=?", tenant)).isEqualTo(oldOperation);
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
        var oldIntents = new JdbcSupplierSettlementPreparationRepository(
                        SupplierMigrationJdbcTemplate.mapper(jdbc,
                                io.agentflow.procurement.mapper
                                        .SupplierSettlementPreparationRepositoryMapper.class), json, payments);
        var oldOperations = new JdbcSupplierPayableSettlementRepository(
                        SupplierMigrationJdbcTemplate.mapper(jdbc,
                                io.agentflow.procurement.mapper
                                        .SupplierPayableSettlementRepositoryMapper.class), json, oldIntents, payments, reservations);
        var original = oldOperations.find(tenant, source.settlement().command().id()).orElseThrow();
        tx.executeWithoutResult(status -> oldOperations.update(original.requestQuery(clock())));
        var content = proof.bank().command().holdCommand().authorization().source().reservation().source().round().content();
        assertThat(new SupplierPayableReturnGuard(
                                        SupplierMigrationJdbcTemplate.mapper(jdbc,
                                                io.agentflow.procurement.mapper
                                                        .SupplierPayableReturnGuardMapper.class)).blocked(tenant, content)).isTrue();
    }

    @Test void adjustmentReaderUsesFixedBankOriginalErpAndDateWithoutWritingOrHoldingATransaction() {
        for (boolean settled : List.of(false, true)) {
            var source = source(settled); var at = clock(); var evidence = adjustmentEvidence(source, at);
            var input = SupplierAdjustmentPreparation.queue(UUID.randomUUID(), source, "finance", evidence.period().request().accountingDate(), at).input();
            var reader = reader(evidence); var snapshot = reader.read(source, input.accountingDate()).requireValue();
            var command = input.command(snapshot.evidence(), clock());
            assertThat(command.source()).isEqualTo(source); assertThat(command.period().request().accountingDate()).isEqualTo(input.accountingDate());
            verify(readReturns).query(source.returns().request());
            if (settled) { verify(readSettlements).query(source.settlement().command()); verifyNoInteractions(readHolds); }
            else { verify(readHolds).query(source.returns().request().command().holdCommand()); verifyNoInteractions(readSettlements); }
            verifyNoInteractions(readAdjustments);
            var payment = source.returns().request().command(); verify(readPeriods).period(tenant, payment.targetDigest(), evidence.period().request());
            assertThat(count("supplier_payable_adjustment_operation")).isEqualTo(settled ? 1 : 0);
            // 首份回款完成记账后，才能为同一应付建立下一份读取夹具。
            if (!settled) finish(adjusted(adjustment(intent(source)), "reader-first-completed"), 20);
        }
    }

    @Test void adjustmentReaderRestoresPreviousQueryCommandFromActualCompletionAndNeverReusesHeldEvidence() {
        var firstSource = source(); var done = adjusted(adjustment(intent(firstSource)), "reader-previous"); var completed = finish(done, 20);
        var payment = completed.bank(); var check = returnCheck(payment, "finance", 21, firstSource.returns().entries().get(0).proof(), returnFunds("reader-next", "10", clock()));
        var ledger = registerReturn(check, returnDecision(check));
        var previous = new SupplierPayableAdjustmentSource.Previous(payment.command().id(), payment.command().digest(), null, done.version(), firstSource.returns().entries(), done.observation());
        var source = new SupplierPayableAdjustmentSource(ledger, null, previous); var at = clock(); var evidence = adjustmentEvidence(source, at);
        var input = SupplierAdjustmentPreparation.queue(UUID.randomUUID(), source, "finance", evidence.period().request().accountingDate(), at).input();
        var reader = reader(evidence); var snapshot = reader.read(source, input.accountingDate()).requireValue();
        assertThat(input.command(snapshot.evidence(), clock()).source().previous()).isEqualTo(previous);
        verify(readAdjustments).query(done.command()); verify(readAdjustments, never()).adjust(any(), any()); verifyNoInteractions(readHolds, readSettlements);
        assertThat(completions.find(tenant, done.command().id())).contains(completed); assertThat(count("supplier_payable_adjustment_operation")).isEqualTo(1);
    }

    @Test void adjustmentReaderStopsOnUnavailableBankOrOriginalErpAndPreservesClosedPeriodRejection() {
        var source = source(true); var evidence = adjustmentEvidence(source, clock()); var reader = reader(evidence); var date = evidence.period().request().accountingDate();
        when(readReturns.query(any())).thenReturn(new FinanceResult.Unavailable<>(FinanceResult.Failure.TARGET_CHANGED));
        assertThat(reader.read(source, date)).isEqualTo(new FinanceResult.Unavailable<>(FinanceResult.Failure.TARGET_CHANGED));
        verifyNoInteractions(readHolds, readSettlements, readAdjustments, readPeriods);
        when(readReturns.query(any())).thenReturn(new FinanceResult.Success<>(evidence.bank()));
        when(readSettlements.query(any())).thenReturn(new FinanceResult.Unavailable<>(FinanceResult.Failure.TIMEOUT));
        assertThat(reader.read(source, date)).isEqualTo(new FinanceResult.Unavailable<>(FinanceResult.Failure.TIMEOUT)); verifyNoInteractions(readPeriods);
        when(readSettlements.query(any())).thenReturn(new FinanceResult.Success<>(evidence.settlement()));
        when(readPeriods.period(anyString(), anyString(), any())).thenReturn(new FinanceResult.Rejected<>(FinanceResult.Reason.ACCOUNTING_PERIOD_CLOSED));
        assertThat(reader.read(source, date)).isEqualTo(new FinanceResult.Rejected<>(FinanceResult.Reason.ACCOUNTING_PERIOD_CLOSED));
        assertThat(count("supplier_payable_adjustment_operation")).isZero();
    }

    @Test void adjustmentReaderRejectsTransactionsBeforeCallingAnyExternalSystem() {
        var source = source(); var evidence = adjustmentEvidence(source, clock()); var reader = reader(evidence);
        assertThatThrownBy(() -> tx.execute(status -> reader.read(source, evidence.period().request().accountingDate()))).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(readReturns, readHolds, readSettlements, readAdjustments, readPeriods);
    }

    @Test void currentAdjustmentSourceIsDerivedFromActualVersionsAndPreviousCompletedReceipts() {
        var source = source(); var payment = payments.find(tenant, source.returns().request().command().id()).orElseThrow();
        var originalSource = tx.execute(status -> adjustmentSources.current(tenant, payment.command().id(), payment.version(), source.returns().version()));
        assertThat(originalSource).isEqualTo(source);
        assertThatThrownBy(() -> tx.execute(status -> adjustmentSources.current(tenant, payment.command().id(), payment.version() - 1, source.returns().version()))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> tx.execute(status -> adjustmentSources.current(tenant, payment.command().id(), payment.version(), source.returns().version() - 1))).isInstanceOf(DomainException.class);
        var done = adjusted(adjustment(intent(source)), "derived-first"); var proof = finish(done, 20);
        var check = returnCheck(payment, "finance", 21, source.returns().entries().get(0).proof(), returnFunds("derived-next", "10", clock()));
        var ledger = registerReturn(check, returnDecision(check));
        var derived = tx.execute(status -> adjustmentSources.current(tenant, payment.command().id(), payment.version(), ledger.version()));
        assertThat(derived.returns()).isEqualTo(ledger); assertThat(derived.previous().version()).isEqualTo(done.version());
        assertThat(derived.previous().observation()).isEqualTo(done.observation()); assertThat(derived.previous().entries()).isEqualTo(source.returns().entries());
        assertThat(derived.newReturned()).isEqualTo(money("10")); assertThat(completions.find(tenant, done.command().id())).contains(proof);
    }

    @Test void currentAdjustmentSourceKeepsFirstSettlementWhileSendEvidenceMustReachItsLatestRevision() {
        var source = source(true); var payment = payments.find(tenant, source.returns().request().command().id()).orElseThrow();
        var oldIntents = new JdbcSupplierSettlementPreparationRepository(
                        SupplierMigrationJdbcTemplate.mapper(jdbc,
                                io.agentflow.procurement.mapper
                                        .SupplierSettlementPreparationRepositoryMapper.class), json, payments);
        var oldOperations = new JdbcSupplierPayableSettlementRepository(
                        SupplierMigrationJdbcTemplate.mapper(jdbc,
                                io.agentflow.procurement.mapper
                                        .SupplierPayableSettlementRepositoryMapper.class), json, oldIntents, payments, reservations);
        var first = oldOperations.find(tenant, source.settlement().command().id()).orElseThrow(); var pending = first.requestQuery(clock());
        tx.executeWithoutResult(status -> oldOperations.update(pending)); var querying = pending.claim(clock(), Duration.ofSeconds(30));
        tx.executeWithoutResult(status -> oldOperations.update(querying)); var at = clock();
        var currentObservation = new SupplierPayableSettlementObservation(first.command().id(), first.command().digest(), SupplierPayableSettlementObservation.Status.SETTLED, 5L, at, first.observation().posting(), null);
        var current = querying.complete(new FinanceResult.Success<>(currentObservation), at); tx.executeWithoutResult(status -> oldOperations.update(current));
        var derived = tx.execute(status -> adjustmentSources.current(tenant, payment.command().id(), payment.version(), source.returns().version()));
        assertThat(derived).isEqualTo(source);
        var stale = adjustmentEvidence(derived, clock());
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> adjustmentSources.requireEvidence(derived, stale))).isInstanceOf(DomainException.class);
        var fresh = new SupplierPayableAdjustmentEvidence(stale.bank(), stale.hold(), currentObservation, stale.previous(), stale.period(), stale.checkedAt());
        tx.executeWithoutResult(status -> adjustmentSources.requireEvidence(derived, fresh));
    }

    @Test void adjustmentSendEvidencePreservesHigherKnownBankReadsAndLatestPreviousAccounting() {
        var source = source(); var payment = payments.find(tenant, source.returns().request().command().id()).orElseThrow();
        returnCheck(payment, "finance", 11, source.returns().entries().get(0).proof());
        var staleBank = adjustmentEvidence(source, clock());
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> adjustmentSources.requireEvidence(source, staleBank))).isInstanceOf(DomainException.class);
        var done = adjusted(adjustment(intent(source)), "derived-prior"); var proof = finish(done, 20);
        var check = returnCheck(payment, "finance", 21, source.returns().entries().get(0).proof(), returnFunds("evidence-next", "10", clock()));
        var ledger = registerReturn(check, returnDecision(check));
        var next = tx.execute(status -> adjustmentSources.current(tenant, payment.command().id(), payment.version(), ledger.version()));
        var pending = done.requestQuery(clock()); tx.executeWithoutResult(status -> adjustments.update(pending));
        var querying = pending.claim(clock(), Duration.ofSeconds(30)); tx.executeWithoutResult(status -> adjustments.update(querying)); var at = clock();
        var observed = new SupplierPayableAdjustmentObservation(done.command().id(), done.command().digest(), SupplierPayableAdjustmentObservation.Status.ADJUSTED, 5, at, done.observation().posting(), null);
        var current = querying.complete(new FinanceResult.Success<>(observed), at); tx.executeWithoutResult(status -> adjustments.update(current));
        var stalePrevious = adjustmentEvidence(next, clock());
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> adjustmentSources.requireEvidence(next, stalePrevious))).isInstanceOf(DomainException.class);
        var fresh = new SupplierPayableAdjustmentEvidence(stalePrevious.bank(), stalePrevious.hold(), stalePrevious.settlement(), observed, stalePrevious.period(), stalePrevious.checkedAt());
        tx.executeWithoutResult(status -> adjustmentSources.requireEvidence(next, fresh));
        assertThat(completions.find(tenant, done.command().id())).contains(proof);
    }

    @Test void adjustmentWorkersPrepareSendAndCompleteOnceFromPersistedIntent() {
        var source = source(); workers(source); var intent = submitted(source);
        intentWorker.poll(); var queued = adjustments.find(tenant, intent.input().id()).orElseThrow();
        assertThat(queued.status()).isEqualTo(SupplierPayableAdjustmentOperation.Status.QUEUED);
        verify(readAdjustments, never()).adjust(any(), any());
        adjustmentWorker.poll(); adjustmentWorker.poll(); intentWorker.poll();
        var done = adjustments.find(tenant, queued.command().id()).orElseThrow(); assertThat(done.adjusted()).isTrue();
        assertThat(completions.find(tenant, done.command().id())).isPresent();
        assertThat(returnLedgers.find(tenant, source.returns().request().command().id()).orElseThrow().reviewRequired()).isFalse();
        verify(readAdjustments, times(1)).adjust(eq(queued.command()), any()); verify(readAdjustments, never()).query(any());
        assertThat(count("supplier_payment_operation")).isEqualTo(1); assertThat(count("supplier_adjustment_completion")).isEqualTo(1);
    }

    @Test void adjustmentWorkersRecoverLostResponseByOriginalQueryAfterFinanceLeavesAndPeriodCloses() {
        var source = source(); workers(source); var intent = submitted(source); intentWorker.poll();
        var command = adjustments.find(tenant, intent.input().id()).orElseThrow().command();
        doAnswer(call -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            remoteAdjustment = adjustmentObservation(call.getArgument(0), "worker-lost", clock()); return new FinanceResult.Unavailable<>(FinanceResult.Failure.TIMEOUT);
        }).when(readAdjustments).adjust(any(), any());
        adjustmentWorker.poll(); var unknown = adjustments.find(tenant, command.id()).orElseThrow();
        assertThat(unknown.status()).isEqualTo(SupplierPayableAdjustmentOperation.Status.UNKNOWN); assertThat(completions.find(tenant, command.id())).isEmpty();
        doThrow(new DomainException("FINANCE_UNAVAILABLE", "Finance actor left")).when(personnel).requireEligible(tenant, "finance", entity);
        doReturn(new FinanceResult.Rejected<>(FinanceResult.Reason.ACCOUNTING_PERIOD_CLOSED)).when(readPeriods).period(anyString(), anyString(), any());
        tx.executeWithoutResult(status -> adjustmentExecution.query(tenant, command.id(), unknown.version(), clock()));
        clearInvocations(readPeriods, readHolds, readSettlements); adjustmentWorker.poll();
        var done = adjustments.find(tenant, command.id()).orElseThrow(); assertThat(done.adjusted()).isTrue(); assertThat(done.command()).isEqualTo(command);
        assertThat(completions.find(tenant, command.id())).isPresent(); verify(readAdjustments, times(1)).adjust(eq(command), any()); verify(readAdjustments).query(command);
        verifyNoInteractions(readPeriods, readHolds, readSettlements);
    }

    @Test void adjustmentWorkersKeepErpSuccessWhenCompletionBankReadFailsAndOnlyRetryLocalWork() {
        var source = source(); workers(source); var intent = submitted(source); intentWorker.poll();
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (reads.incrementAndGet() == 2) return new FinanceResult.Unavailable<>(FinanceResult.Failure.CONNECTION);
            return new FinanceResult.Success<>(completionReceipt(returnLedgers.find(tenant, source.returns().request().command().id()).orElseThrow(), 100, clock()));
        }).when(readReturns).query(any());
        adjustmentWorker.poll(); var done = adjustments.find(tenant, intent.input().id()).orElseThrow();
        assertThat(done.adjusted()).isTrue(); assertThat(completions.find(tenant, done.command().id())).isEmpty();
        assertThat(returnLedgers.find(tenant, source.returns().request().command().id()).orElseThrow().reviewRequired()).isTrue();
        adjustmentWorker.poll(); assertThat(completions.find(tenant, done.command().id())).isPresent();
        assertThat(adjustments.find(tenant, done.command().id())).contains(done); verify(readAdjustments, times(1)).adjust(any(), any()); verify(readAdjustments, never()).query(any());
    }

    @Test void adjustmentWorkersKeepErpSuccessWhenLocalTransactionFailsAndResumeWithoutRedispatch() {
        var source = source(); workers(source); var intent = submitted(source); intentWorker.poll();
        var failedCompletion = mock(SupplierAdjustmentCompletionService.class);
        doThrow(new DomainException("CONCURRENCY_CONFLICT", "Simulated local completion failure")).when(failedCompletion).complete(any(), any(), any());
        var interrupted = new SupplierAdjustmentWorker(adjustments, adjustmentExecution, workerReader, readAdjustments, readReturns, failedCompletion);
        interrupted.poll(); var done = adjustments.find(tenant, intent.input().id()).orElseThrow();
        assertThat(done.adjusted()).isTrue(); assertThat(completions.find(tenant, done.command().id())).isEmpty();
        adjustmentWorker.poll(); assertThat(completions.find(tenant, done.command().id())).isPresent();
        assertThat(adjustments.find(tenant, done.command().id())).contains(done); verify(readAdjustments, times(1)).adjust(any(), any()); verify(readAdjustments, never()).query(any());
    }

    @Test void adjustmentWorkersRequireCurrentFinanceForPreparationAndAgainBeforeDispatch() {
        var source = source(); workers(source); var intent = submitted(source);
        doThrow(new DomainException("FINANCE_UNAVAILABLE", "Finance actor left")).when(personnel).requireEligible(tenant, "finance", entity);
        intentWorker.poll(); assertThat(adjustmentIntents.find(tenant, intent.input().id()).orElseThrow().status()).isEqualTo(SupplierAdjustmentPreparation.Status.VOIDED);
        verifyNoInteractions(readReturns, readAdjustments);
        doNothing().when(personnel).requireEligible(tenant, "finance", entity);
        var next = submitted(source); intentWorker.poll(); var checking = adjustmentExecution.claim(tenant, next.input().id(), clock());
        var evidence = workerReader.read(source, next.input().accountingDate());
        doThrow(new DomainException("FINANCE_UNAVAILABLE", "Finance actor left")).when(personnel).requireEligible(tenant, "finance", entity);
        assertThat(adjustmentExecution.ready(checking, evidence, clock())).isNull();
        var stopped = adjustments.find(tenant, next.input().id()).orElseThrow(); assertThat(stopped.status()).isEqualTo(SupplierPayableAdjustmentOperation.Status.VOIDED);
        assertThat(stopped.dispatches()).isZero(); verify(readAdjustments, never()).adjust(any(), any());
    }

    @Test void adjustmentWorkersDiscardLateReadsAfterLeaseExpiryAndAfterSafeRetirement() {
        var source = source(); workers(source); var intent = submitted(source); var claimed = adjustmentPreparation.claim(tenant, intent.input().id(), clock());
        var evidence = workerReader.read(source, intent.input().accountingDate());
        assertThat(adjustmentPreparation.claim(tenant, intent.input().id(), claimed.leaseUntil())).isNull();
        adjustmentPreparation.finish(claimed, evidence, clock()); assertThat(adjustments.find(tenant, intent.input().id())).isEmpty();
        // 终止过期意图后重新准备，验证安全结束与迟到发送；保留上一份意图的未来租约时刻。
        var pending = adjustmentIntents.find(tenant, intent.input().id()).orElseThrow();
        tx.executeWithoutResult(status -> adjustmentIntents.update(pending.voidSource(pending.updatedAt())));
        var queued = adjustment(intent(source)); var checking = adjustmentExecution.claim(tenant, queued.command().id(), clock());
        var checked = workerReader.read(source, queued.command().period().request().accountingDate());
        tx.executeWithoutResult(status -> adjustmentExecution.retire(tenant, queued.command().id(), checking.version(), "another-finance", clock()));
        assertThat(adjustmentExecution.ready(checking, checked, clock())).isNull();
        assertThat(adjustments.retirement(tenant, queued.command().id())).isPresent(); verify(readAdjustments, never()).adjust(any(), any());
    }

    @Test void adjustmentWorkersRequireExplicitRetryAfterNotFoundAndPreserveOriginalCommand() {
        var source = source(); workers(source); var intent = submitted(source); intentWorker.poll();
        doReturn(new FinanceResult.Unavailable<>(FinanceResult.Failure.TIMEOUT)).when(readAdjustments).adjust(any(), any());
        adjustmentWorker.poll(); var unknown = adjustments.find(tenant, intent.input().id()).orElseThrow(); var command = unknown.command();
        doAnswer(call -> new FinanceResult.Success<>(new SupplierPayableAdjustmentObservation(command.id(), command.digest(), SupplierPayableAdjustmentObservation.Status.NOT_FOUND, 0, clock(), null, null))).when(readAdjustments).query(any());
        tx.executeWithoutResult(status -> adjustmentExecution.query(tenant, command.id(), unknown.version(), clock())); adjustmentWorker.poll(); adjustmentWorker.poll();
        var missing = adjustments.find(tenant, command.id()).orElseThrow(); assertThat(missing.status()).isEqualTo(SupplierPayableAdjustmentOperation.Status.NOT_FOUND);
        verify(readAdjustments, times(1)).adjust(any(), any()); assertThat(completions.find(tenant, command.id())).isEmpty();
        tx.executeWithoutResult(status -> adjustmentExecution.resend(tenant, command.id(), missing.version(), clock()));
        doAnswer(call -> { remoteAdjustment = adjustmentObservation(call.getArgument(0), "worker-retry", clock()); return new FinanceResult.Success<>(remoteAdjustment); }).when(readAdjustments).adjust(any(), any());
        adjustmentWorker.poll(); var done = adjustments.find(tenant, command.id()).orElseThrow();
        assertThat(done.adjusted()).isTrue(); assertThat(done.command()).isEqualTo(command); assertThat(done.dispatches()).isEqualTo(2);
        assertThat(completions.find(tenant, command.id())).isPresent(); verify(readAdjustments, times(2)).adjust(eq(command), any());
    }

    @Test void adjustmentWorkersBlockClosedPreparationWithoutRegisteringAnErpCommand() {
        var source = source(); workers(source); var intent = submitted(source);
        doReturn(new FinanceResult.Rejected<>(FinanceResult.Reason.ACCOUNTING_PERIOD_CLOSED)).when(readPeriods).period(anyString(), anyString(), any());
        intentWorker.poll(); intentWorker.poll();
        var blocked = adjustmentIntents.find(tenant, intent.input().id()).orElseThrow();
        assertThat(blocked.status()).isEqualTo(SupplierAdjustmentPreparation.Status.BLOCKED);
        assertThat(blocked.issue()).isEqualTo(SupplierAdjustmentPreparation.Issue.ACCOUNTING_PERIOD_REJECTED);
        assertThat(blocked.input().accountingDate()).isEqualTo(intent.input().accountingDate());
        assertThat(adjustments.find(tenant, intent.input().id())).isEmpty(); verify(readAdjustments, never()).adjust(any(), any());
    }

    @Test void adjustmentWorkersTreatUnexpectedReadFailureAsUnsentAndKeepTheOriginalCommand() {
        var source = source(); workers(source); var intent = submitted(source); intentWorker.poll();
        var command = adjustments.find(tenant, intent.input().id()).orElseThrow().command();
        doThrow(new IllegalStateException("Simulated read transport failure")).when(readReturns).query(any());
        adjustmentWorker.poll(); var current = adjustments.find(tenant, command.id()).orElseThrow();
        assertThat(current.status()).isEqualTo(SupplierPayableAdjustmentOperation.Status.QUEUED); assertThat(current.dispatches()).isZero();
        assertThat(current.failure()).isEqualTo(SupplierPayableAdjustmentOperation.Failure.INTERNAL_ERROR); assertThat(current.command()).isEqualTo(command);
        assertThat(current.nextAttemptAt()).isAfter(current.updatedAt()); verify(readAdjustments, never()).adjust(any(), any());
    }

    @Test void adjustmentWorkersTreatUnexpectedWriteFailureAsPossiblySentAndOnlyKeepQueryRecovery() {
        var source = source(); workers(source); var intent = submitted(source); intentWorker.poll();
        var command = adjustments.find(tenant, intent.input().id()).orElseThrow().command();
        doThrow(new IllegalStateException("Simulated write transport failure")).when(readAdjustments).adjust(any(), any());
        adjustmentWorker.poll(); var current = adjustments.find(tenant, command.id()).orElseThrow();
        assertThat(current.status()).isEqualTo(SupplierPayableAdjustmentOperation.Status.UNKNOWN); assertThat(current.dispatches()).isEqualTo(1);
        assertThat(current.failure()).isEqualTo(SupplierPayableAdjustmentOperation.Failure.INTERNAL_ERROR); assertThat(current.command()).isEqualTo(command);
        assertThat(completions.find(tenant, command.id())).isEmpty(); verify(readAdjustments, times(1)).adjust(eq(command), any());
    }

    @Test void adjustmentWorkersExpirePossibleSendLeaseIntoQueryAndIgnoreItsLateSuccess() {
        var source = source(); workers(source); var intent = submitted(source); intentWorker.poll();
        var checking = adjustmentExecution.claim(tenant, intent.input().id(), clock());
        var sent = adjustmentExecution.ready(checking, workerReader.read(source, intent.input().accountingDate()), clock());
        assertThat(sent.status()).isEqualTo(SupplierPayableAdjustmentOperation.Status.ADJUSTING);
        assertThat(adjustmentExecution.claim(tenant, sent.command().id(), sent.leaseUntil())).isNull();
        var expired = adjustments.find(tenant, sent.command().id()).orElseThrow(); assertThat(expired.status()).isEqualTo(SupplierPayableAdjustmentOperation.Status.UNKNOWN);
        adjustmentExecution.finish(sent, new FinanceResult.Success<>(adjustmentObservation(sent.command(), "late-lease", clock())), clock());
        assertThat(adjustments.find(tenant, sent.command().id())).contains(expired);
        var querying = adjustmentExecution.claim(tenant, sent.command().id(), expired.nextAttemptAt());
        assertThat(querying.status()).isEqualTo(SupplierPayableAdjustmentOperation.Status.QUERYING); assertThat(querying.command()).isEqualTo(sent.command());
        assertThat(querying.dispatches()).isEqualTo(1); assertThat(completions.find(tenant, sent.command().id())).isEmpty();
    }

    @Test void adjustmentDisputeResolutionRequiresNamedDecisionAndPersistsExactRevisions() {
        var current = disputedAdjustment(); var decision = adjustmentDecision(current); var history = adjustments.resolutionHistory(tenant, current.command().id());
        assertThat(history.firstAdjustment()).isNull(); assertThat(history.adjustmentObserved()).isTrue();
        var proposed = decision.resolve(current, history);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> adjustments.update(proposed))).isInstanceOf(DomainException.class);
        assertThat(adjustments.find(tenant, current.command().id())).contains(current);
        var resolved = tx.execute(status -> adjustments.resolve(decision));
        assertThat(resolved).isEqualTo(proposed); assertThat(resolved.dispatches()).isEqualTo(1);
        assertThat(adjustments.revision(tenant, current.command().id(), current.version())).contains(current);
        assertThat(adjustments.revision(tenant, current.command().id(), resolved.version())).contains(resolved);
        assertThat(adjustments.latestResolution(tenant, current.command().id())).contains(decision);
        assertThat(adjustments.latestResolution("other", current.command().id())).isEmpty();
        assertThat(completions.find(tenant, current.command().id())).isEmpty();
        var proof = finish(resolved, 20);
        assertThat(proof.operation()).isEqualTo(resolved); assertThat(adjustments.active(tenant, proof.after().request().command().id())).isEmpty();
        assertThatThrownBy(() -> tx.execute(status -> adjustments.resolve(decision))).isInstanceOf(DomainException.class);
        assertThat(count("supplier_adjustment_dispute_resolution")).isEqualTo(1);
    }

    @Test void completedAdjustmentResolutionKeepsCompletionAndCannotReclaimActivePosition() {
        var done = adjusted(adjustment(intent(source())), "return-original"); var proof = finish(done, 20); var id = done.command().id();
        var credits = jdbc.queryForList("SELECT * FROM finance_receipt_credit WHERE tenant_id=?", tenant);
        var candidate = adjustmentQuery(done, new SupplierPayableAdjustmentObservation(id, done.command().digest(), SupplierPayableAdjustmentObservation.Status.REJECTED,
                2, clock(), null, SupplierPayableAdjustmentObservation.Rejection.ACCOUNTING_PERIOD_CLOSED));
        assertThatThrownBy(() -> tx.execute(status -> adjustments.resolve(adjustmentDecision(candidate)))).isInstanceOf(DomainException.class);
        var restored = adjustmentQuery(candidate, new SupplierPayableAdjustmentObservation(id, done.command().digest(), SupplierPayableAdjustmentObservation.Status.ADJUSTED,
                3, clock(), done.observation().posting(), null));
        var decision = adjustmentDecision(restored); var resolved = tx.execute(status -> adjustments.resolve(decision));
        assertThat(resolved.adjusted()).isTrue(); assertThat(resolved.command()).isEqualTo(done.command()); assertThat(resolved.dispatches()).isEqualTo(1);
        assertThat(adjustments.active(tenant, proof.after().request().command().id())).isEmpty(); assertThat(completions.find(tenant, id)).contains(proof);
        assertThat(returnLedgers.find(tenant, proof.after().request().command().id())).contains(proof.after());
        assertThat(jdbc.queryForList("SELECT * FROM finance_receipt_credit WHERE tenant_id=?", tenant)).isEqualTo(credits);
        assertThat(completion.complete(resolved, proof.receipt(), clock())).isEqualTo(proof);
        assertThat(adjustments.resolutionHistory(tenant, id).firstAdjustment()).isEqualTo(done.observation());
    }

    @Test void overwrittenAdjustmentSuccessAndAlreadyAdjustedRemainVisibleInPersistentHistory() {
        var current = disputedAdjustment(); var id = current.command().id();
        var rejected = adjustmentQuery(current, new SupplierPayableAdjustmentObservation(id, current.command().digest(), SupplierPayableAdjustmentObservation.Status.REJECTED,
                3, clock(), null, SupplierPayableAdjustmentObservation.Rejection.ACCOUNTING_PERIOD_CLOSED));
        assertThat(rejected.observation().status()).isEqualTo(SupplierPayableAdjustmentObservation.Status.PENDING);
        assertThat(adjustments.resolutionHistory(tenant, id).adjustmentObserved()).isTrue();
        assertThatThrownBy(() -> tx.execute(status -> adjustments.resolve(adjustmentDecision(rejected)))).isInstanceOf(DomainException.class);
        assertThat(count("supplier_adjustment_dispute_resolution")).isZero();
        var candidate = adjustmentQuery(rejected, new SupplierPayableAdjustmentObservation(id, current.command().digest(), SupplierPayableAdjustmentObservation.Status.ADJUSTED,
                4, clock(), current.conflictingObservation().posting(), null));
        var decision = adjustmentDecision(candidate); var resolved = tx.execute(status -> adjustments.resolve(decision));
        assertThat(adjustments.latestResolution(tenant, id)).contains(decision);
        jdbc.update(
                "UPDATE supplier_adjustment_dispute_resolution SET resolved_by='tampered' WHERE"
                        + " tenant_id=? AND id=?", tenant, decision.id().toString());
        assertThatThrownBy(() -> adjustments.latestResolution(tenant, id)).isInstanceOf(DomainException.class);
        assertThat(adjustments.find(tenant, id)).contains(resolved);
    }

    @Test void failedAdjustmentDecisionInsertRollsBackStateAndNewRevision() {
        var current = disputedAdjustment(); var first = adjustmentDecision(current); var resolved = tx.execute(status -> adjustments.resolve(first));
        var pending = adjustmentQuery(resolved, new SupplierPayableAdjustmentObservation(current.command().id(), current.command().digest(), SupplierPayableAdjustmentObservation.Status.PENDING,
                3, clock(), null, null));
        var candidate = adjustmentQuery(pending, new SupplierPayableAdjustmentObservation(current.command().id(), current.command().digest(), SupplierPayableAdjustmentObservation.Status.ADJUSTED,
                4, clock(), resolved.observation().posting(), null));
        var valid = adjustmentDecision(candidate); var duplicate = new SupplierAdjustmentDisputeResolution(first.id(), tenant, valid.adjustmentId(), valid.disputedVersion(), valid.resolvedVersion(),
                valid.observation(), valid.resolvedBy(), valid.resolvedAt(), valid.evidenceReference(), valid.reason());
        int revisions = count("supplier_payable_adjustment_revision");
        assertThatThrownBy(() -> tx.execute(status -> adjustments.resolve(duplicate))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(adjustments.find(tenant, candidate.command().id())).contains(candidate);
        assertThat(count("supplier_payable_adjustment_revision")).isEqualTo(revisions); assertThat(count("supplier_adjustment_dispute_resolution")).isEqualTo(1);
        assertThat(adjustments.latestResolution(tenant, candidate.command().id())).contains(first);
    }

    @Test void concurrentAdjustmentDecisionsOnlyAppendOneResolvedRevision() throws Exception {
        var current = disputedAdjustment(); var decision = adjustmentDecision(current); var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Boolean> action = () -> {
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                try { tx.execute(status -> adjustments.resolve(decision)); return true; }
                catch (DomainException conflict) { return false; }
            };
            var first = executor.submit(action); var second = executor.submit(action); start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
            assertThat(count("supplier_adjustment_dispute_resolution")).isEqualTo(1);
            assertThat(adjustments.find(tenant, current.command().id()).orElseThrow().version()).isEqualTo(current.version() + 1);
            assertThat(adjustments.latestResolution(tenant, current.command().id())).contains(decision);
        } finally { executor.shutdownNow(); }
    }

    @Test void v82MigrationPreservesUnresolvedAdjustmentAndOriginalCompletion() {
        database("81"); var current = disputedAdjustment(); var done = adjusted(adjustment(intent(source(true))), "return-upgrade"); var proof = finish(done, 20);
        var bankRows = jdbc.queryForList("SELECT * FROM supplier_payment_operation WHERE tenant_id=?", tenant);
        var adjustmentRows = jdbc.queryForList("SELECT * FROM supplier_payable_adjustment_operation WHERE tenant_id=?", tenant);
        var oldCompletions = jdbc.queryForList("SELECT * FROM supplier_settlement_completion WHERE tenant_id=?", tenant);
        assertThat(Flyway.configure().dataSource(dataSource).defaultSchema(schema).target("82").load().migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM supplier_payment_operation WHERE tenant_id=?", tenant)).isEqualTo(bankRows);
        assertThat(jdbc.queryForList(
                                "SELECT * FROM supplier_payable_adjustment_operation WHERE"
                                        + " tenant_id=?", tenant)).isEqualTo(adjustmentRows);
        assertThat(jdbc.queryForList("SELECT * FROM supplier_settlement_completion WHERE tenant_id=?", tenant)).isEqualTo(oldCompletions);
        assertThat(count("supplier_adjustment_dispute_resolution")).isZero(); assertThat(adjustments.find(tenant, current.command().id())).contains(current);
        assertThat(completions.find(tenant, done.command().id())).contains(proof);
        assertThat(tx.execute(status -> adjustments.resolve(adjustmentDecision(current))).adjusted()).isTrue();
    }

    private SupplierPayableAdjustmentOperation disputedAdjustment() {
        var sent = dispatch(adjustment(intent(source()))); var id = sent.command().id(); var at = clock();
        var pending = sent.complete(new FinanceResult.Success<>(new SupplierPayableAdjustmentObservation(id, sent.command().digest(), SupplierPayableAdjustmentObservation.Status.PENDING,
                1, at, null, null)), at); tx.executeWithoutResult(status -> adjustments.update(pending));
        var missing = adjustmentQuery(pending, new SupplierPayableAdjustmentObservation(id, sent.command().digest(), SupplierPayableAdjustmentObservation.Status.NOT_FOUND, 0, clock(), null, null));
        var posting = adjustmentObservation(sent.command(), "return-disputed", clock());
        return adjustmentQuery(missing, new SupplierPayableAdjustmentObservation(id, sent.command().digest(), SupplierPayableAdjustmentObservation.Status.ADJUSTED, 2, clock(), posting.posting(), null));
    }
    private SupplierPayableAdjustmentOperation adjustmentQuery(SupplierPayableAdjustmentOperation before, SupplierPayableAdjustmentObservation incoming) {
        return tx.execute(status -> {
            var requested = before.requestQuery(incoming.observedAt()); adjustments.update(requested);
            var queried = requested.claim(incoming.observedAt(), Duration.ofSeconds(30)); adjustments.update(queried);
            var after = queried.complete(new FinanceResult.Success<>(incoming), incoming.observedAt()); adjustments.update(after); return after;
        });
    }
    private SupplierAdjustmentDisputeResolution adjustmentDecision(SupplierPayableAdjustmentOperation current) {
        return new SupplierAdjustmentDisputeResolution(UUID.randomUUID(), tenant, current.command().id(), current.version(), current.version() + 1,
                current.conflictingObservation(), "finance", clock(), "erp-return-statement", "核对原回款调整与全部实际分录");
    }

    private SupplierAdjustmentPreparation submitted(SupplierPayableAdjustmentSource source) {
        var payment = payments.find(tenant, source.returns().request().command().id()).orElseThrow(); var at = clock();
        return tx.execute(status -> adjustmentPreparation.register(tenant, payment.command().id(), payment.version(), source.returns().version(), "finance", at.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate(), at));
    }
    private void workers(SupplierPayableAdjustmentSource source) {
        workerReader = reader(adjustmentEvidence(source, clock()));
        doAnswer(call -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new FinanceResult.Success<>(completionReceipt(returnLedgers.find(tenant, source.returns().request().command().id()).orElseThrow(), 100, clock()));
        }).when(readReturns).query(any());
        doAnswer(call -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            remoteAdjustment = adjustmentObservation(call.getArgument(0), "worker-return", clock()); return new FinanceResult.Success<>(remoteAdjustment);
        }).when(readAdjustments).adjust(any(), any());
        doAnswer(call -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            SupplierPayableAdjustmentCommand command = call.getArgument(0); assertThat(remoteAdjustment.operationId()).isEqualTo(command.id());
            return new FinanceResult.Success<>(new SupplierPayableAdjustmentObservation(command.id(), command.digest(), SupplierPayableAdjustmentObservation.Status.ADJUSTED,
                    remoteAdjustment.revision(), clock(), remoteAdjustment.posting(), null));
        }).when(readAdjustments).query(any());
        intentWorker = new SupplierAdjustmentPreparationWorker(adjustmentIntents, adjustmentPreparation, workerReader);
        adjustmentWorker = new SupplierAdjustmentWorker(adjustments, adjustmentExecution, workerReader, readAdjustments, readReturns, completion);
    }

    private SupplierAdjustmentEvidenceReader reader(SupplierPayableAdjustmentEvidence evidence) {
        readReturns = mock(SupplierPaymentReturnPort.class); readHolds = mock(SupplierPayableHoldPort.class);
        readSettlements = mock(SupplierPayableSettlementPort.class); readAdjustments = mock(SupplierPayableAdjustmentPort.class); readPeriods = mock(AccountingPeriodPort.class);
        when(readReturns.query(any())).thenAnswer(call -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return new FinanceResult.Success<>(evidence.bank()); });
        when(readHolds.query(any())).thenAnswer(call -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return new FinanceResult.Success<>(evidence.hold()); });
        when(readSettlements.query(any())).thenAnswer(call -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return new FinanceResult.Success<>(evidence.settlement()); });
        when(readAdjustments.query(any())).thenAnswer(call -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return new FinanceResult.Success<>(evidence.previous()); });
        when(readPeriods.period(anyString(), anyString(), any())).thenAnswer(call -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return new FinanceResult.Success<>(evidence.period()); });
        return new SupplierAdjustmentEvidenceReader(readReturns, readHolds, readSettlements, readAdjustments, readPeriods, completions);
    }

    private SupplierPaymentOperation reverseBank(SupplierPaymentOperation paid) {
        tx.executeWithoutResult(status -> bank.query(tenant, paid.command().id(), paid.version(), clock()));
        var claimed = bank.claim(tenant, paid.command().id(), clock()); var at = clock(); var original = paid.observation();
        var reversed = new PaymentObservation(paid.command().id(), paid.command().digest(), PaymentObservation.Status.REVERSED, original.revision() + 1, at,
                original.paymentReference(), original.paidAmount(), original.accountDigest(), at, "reversal-bank-receipt", null);
        bank.finish(claimed, new FinanceResult.Success<>(reversed), at); return payments.find(tenant, paid.command().id()).orElseThrow();
    }

    private SupplierPayableAdjustmentOperation adjusted(SupplierPayableAdjustmentOperation queued, String voucher) {
        var sent = dispatch(queued); var at = clock();
        var result = sent.complete(new FinanceResult.Success<>(adjustmentObservation(sent.command(), voucher, at)), at);
        assertThat(result.adjusted()).isTrue(); tx.executeWithoutResult(status -> adjustments.update(result)); return result;
    }
    private SupplierPayableAdjustmentObservation adjustmentObservation(SupplierPayableAdjustmentCommand command, String voucher, Instant at) {
        var source = command.source();
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
        return new SupplierPayableAdjustmentObservation(command.id(), command.digest(), SupplierPayableAdjustmentObservation.Status.ADJUSTED, 1, at, posting, null);
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
        var oldIntents = new JdbcSupplierSettlementPreparationRepository(
                        SupplierMigrationJdbcTemplate.mapper(jdbc,
                                io.agentflow.procurement.mapper
                                        .SupplierSettlementPreparationRepositoryMapper.class), json, payments);
        var oldOperations = new JdbcSupplierPayableSettlementRepository(
                        SupplierMigrationJdbcTemplate.mapper(jdbc,
                                io.agentflow.procurement.mapper
                                        .SupplierPayableSettlementRepositoryMapper.class), json, oldIntents, payments, reservations);
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
            jdbc.update(
                            "INSERT INTO"
                                + " approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id)"
                                + " VALUES(?,?,?,'fixture',1,'alice','采购付款','{}','DRAFT',1,1,'PROCUREMENT_PAYMENT',?)",
                    request.applicationId().toString(), tenant, ref, request.id().toString());
            procurements.create(request, "alice"); request.freeze(1, 1, catalog, "a".repeat(64), payable, initiator, now); procurements.update(request, 1, "alice", "SUBMIT");
            var hold = ProcurementPayableReservation.hold(UUID.randomUUID(), request, now); reservations.create(hold);
            request.approve(2, 1, 8, "manager", now.plusSeconds(1)); procurements.update(request, 2, "manager", "APPROVE");
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
