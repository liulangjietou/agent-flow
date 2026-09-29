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
    private static final PaymentAccountsPort.DebitAccount DEBIT = new PaymentAccountsPort.DebitAccount("debit-1", "法人基本户", "****5678", "CNY", "v1");

    @BeforeEach void database() { database(null); }

    private void database(String target) {
        dataSource = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_SUPPLIER_PAYMENT_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_SUPPLIER_PAYMENT_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_SUPPLIER_PAYMENT_PASSWORD", ""));
        schema = "supplier_payment_" + UUID.randomUUID().toString().replace("-", ""); new JdbcTemplate(dataSource).execute("CREATE SCHEMA \"" + schema + "\""); dataSource.setSchema(schema);
        var migration = Flyway.configure().dataSource(dataSource).defaultSchema(schema); if (target != null) migration.target(target); migration.load().migrate();
        jdbc = new JdbcTemplate(dataSource); manager = new DataSourceTransactionManager(dataSource); tx = new TransactionTemplate(manager);
        procurements = new JdbcProcurementPaymentRepository(jdbc, json);
        reservations = new JdbcProcurementPayableReservationRepository(jdbc, json, procurements, new JdbcProcurementInvoiceClaims(jdbc));
        approvedSources = new ApprovedSupplierPaymentSources(new JdbcApplicationRepository(jdbc, json), procurements, reservations);
        authorizations = new JdbcSupplierPaymentAuthorizationRepository(jdbc, json, approvedSources); holds = new JdbcSupplierPayableHoldRepository(jdbc, json, authorizations);
        var personnel = mock(PaymentPersonnel.class);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            if (disabled.contains(invocation.<String>getArgument(1))) throw new DomainException("PAYMENT_ACTOR_UNAVAILABLE", "Synthetic inactive appointment");
            return null;
        }).when(personnel).requireEligible(eq(tenant), anyString(), eq(entity));
        holdService = proxy(new SupplierPayableHoldService(approvedSources, authorizations, holds, personnel, 30));
        sources = new SupplierPaymentSources(approvedSources, authorizations, holds, personnel);
        requests = new JdbcSupplierPaymentExecutionRepository(jdbc, json, holds);
        payments = new JdbcSupplierPaymentOperationRepository(jdbc, json, requests, holds, authorizations);
        preparation = proxy(new SupplierPaymentExecutionService(sources, requests, payments, 30)); bank = proxy(new SupplierPaymentService(sources, payments, 30));
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
