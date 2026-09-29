package io.agentflow.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.*;
import io.agentflow.notification.NotificationTexts;
import io.agentflow.organization.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import static org.assertj.core.api.Assertions.*;

/**
 * 实际批准聚合、组织、事务与回环资金 HTTP 验证原付款执行，不将合成回执当作真实银行到账。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.finance-gateway.enabled=true", "agentflow.payments.worker-enabled=false", "agentflow.payments.lease-seconds=15",
        "agentflow.payments.request-worker-enabled=false", "agentflow.payments.request-lease-seconds=15", "agentflow.payments.payee-review-worker-enabled=false",
        "agentflow.vouchers.worker-enabled=false", "agentflow.vouchers.preparation-worker-enabled=false", "agentflow.budgets.worker-enabled=false",
        "agentflow.invoices.verification-worker-enabled=false", "agentflow.expenses.precheck-worker-enabled=false", "agentflow.advance-requests.precheck-worker-enabled=false"})
@Import(PaymentOperationIntegrationTest.ListenerConfiguration.class)
class PaymentOperationIntegrationTest {
    private static final UUID ENTITY = UUID.randomUUID();
    private static final ExecutorService HTTP_THREADS = Executors.newCachedThreadPool();
    private static final HttpServer SERVER = server();
    private static final String ENDPOINT = "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/finance";
    private static final AtomicReference<BiFunction<String, JsonNode, Object>> RESPONDER = new AtomicReference<>();
    private static final AtomicInteger WRITES = new AtomicInteger(), QUERIES = new AtomicInteger(), ACCOUNT_READS = new AtomicInteger();
    private static final AtomicInteger VOUCHER_WRITES = new AtomicInteger(), VOUCHER_QUERIES = new AtomicInteger();
    private static final AtomicReference<String> LAST_KEY = new AtomicReference<>();
    private static final Map<UUID, PaymentCommand> COMMANDS = new ConcurrentHashMap<>();
    private static final Map<UUID, VoucherCommand> VOUCHER_COMMANDS = new ConcurrentHashMap<>();
    private static JsonUtil wire;
    private final List<UUID> fixtures = new ArrayList<>();
    @Autowired JsonUtil json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ApplicationRepository applications;
    @Autowired AdvanceRequestRepository advances;
    @Autowired EmployeeAdvanceRepository balances;
    @Autowired OrganizationRepository organization;
    @Autowired PaymentPersonnel personnel;
    @Autowired JdbcVoucherOperationRepository vouchers;
    @Autowired VoucherOperationService voucherExecution;
    @Autowired ApprovedPaymentSources sources;
    @Autowired JdbcPaymentAuthorizationRepository authorizations;
    @Autowired JdbcPaymentOperationRepository operations;
    @Autowired PaymentOperationService execution;
    @Autowired PaymentOperationWorker worker;
    @Autowired AdvanceDisbursementService disbursements;
    @Autowired JdbcPaymentExecutionRequestRepository executionRequests;
    @Autowired PaymentExecutionRequestService requestService;
    @Autowired PaymentExecutionRequestWorker requestWorker;
    @Autowired PaymentPayeeReviewService payeeReviewService;
    @Autowired PaymentPayeeReviewWorker payeeReviewWorker;
    @Autowired JdbcPaymentPayeeReviewRepository payeeReviews;
    @Autowired FinanceGatewayConfiguration configuration;
    @Autowired JdbcVoucherPreparationRepository preparations;
    @Autowired VoucherPreparationService preparationService;
    @Autowired VoucherPreparationWorker preparationWorker;
    @Autowired VoucherOperationWorker voucherWorker;
    @Autowired PaymentVoucherSources paymentVoucherSources;
    @Autowired FailureListener listener;
    @Autowired org.springframework.context.ApplicationEventPublisher events;
    @Autowired JdbcPaymentDisputeResolutionRepository disputeDecisions;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry values) {
        values.add("agentflow.finance-gateway.tenants.demo.endpoint", () -> ENDPOINT);
        values.add("agentflow.finance-gateway.tenants.demo.allow-unauthenticated-loopback", () -> "true");
        values.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_PAYMENT_OPERATION_TEST_URL", "jdbc:h2:mem:payment-operations;DB_CLOSE_DELAY=-1"));
        values.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_PAYMENT_OPERATION_TEST_DRIVER", "org.h2.Driver"));
        values.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_PAYMENT_OPERATION_TEST_USER", "sa"));
        values.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_PAYMENT_OPERATION_TEST_PASSWORD", ""));
    }
    @BeforeEach void setup() {
        wire = json; WRITES.set(0); QUERIES.set(0); ACCOUNT_READS.set(0); listener.reject.set(false); configuration.setEnabled(true);
        VOUCHER_WRITES.set(0); VOUCHER_QUERIES.set(0); VOUCHER_COMMANDS.clear();
        configuration.getTenants().get("demo").setEndpoint(ENDPOINT); configuration.getTenants().get("demo").setTimeoutSeconds(2);
        RESPONDER.set(PaymentOperationIntegrationTest::response); setupOrganization();
    }
    @AfterEach void removeOnlyPaymentFixtures() {
        for (var id : fixtures) {
            jdbc.update("DELETE FROM payment_payee_review_revision WHERE tenant_id='demo' AND review_id IN (SELECT id FROM payment_payee_review WHERE tenant_id='demo' AND (original_authorization_id=? OR consumed_authorization_id=?))", id.toString(), id.toString());
            jdbc.update("DELETE FROM payment_payee_review WHERE tenant_id='demo' AND (original_authorization_id=? OR consumed_authorization_id=?)", id.toString(), id.toString());
        }
        for (var id : fixtures) {
            jdbc.update("DELETE FROM payment_dispute_resolution WHERE tenant_id='demo' AND payment_id=?", id.toString());
            jdbc.update("DELETE FROM payment_retirement WHERE tenant_id='demo' AND authorization_id=?", id.toString());
            String application = "SELECT application_id FROM payment_authorization WHERE tenant_id='demo' AND id=?";
            jdbc.update("DELETE FROM voucher_preparation_revision WHERE tenant_id='demo' AND preparation_id IN (SELECT id FROM voucher_preparation WHERE tenant_id='demo' AND kind='PAYMENT' AND application_id IN (" + application + "))", id.toString());
            jdbc.update("DELETE FROM voucher_preparation WHERE tenant_id='demo' AND kind='PAYMENT' AND application_id IN (" + application + ")", id.toString());
            jdbc.update("DELETE FROM voucher_operation_revision WHERE tenant_id='demo' AND operation_id IN (SELECT id FROM voucher_operation WHERE tenant_id='demo' AND kind='PAYMENT' AND application_id IN (" + application + "))", id.toString());
            jdbc.update("DELETE FROM voucher_operation WHERE tenant_id='demo' AND kind='PAYMENT' AND application_id IN (" + application + ")", id.toString());
            jdbc.update("DELETE FROM payment_execution_request_revision WHERE tenant_id='demo' AND request_id IN (SELECT id FROM payment_execution_request WHERE tenant_id='demo' AND authorization_id=?)", id.toString());
            jdbc.update("DELETE FROM payment_execution_request WHERE tenant_id='demo' AND authorization_id=?", id.toString());
            jdbc.update("DELETE FROM payment_operation_revision WHERE tenant_id='demo' AND operation_id=?", id.toString());
            jdbc.update("DELETE FROM payment_operation WHERE tenant_id='demo' AND id=?", id.toString());
            jdbc.update("DELETE FROM payment_authorization_revision WHERE tenant_id='demo' AND authorization_id=?", id.toString());
            jdbc.update("DELETE FROM payment_authorization WHERE tenant_id='demo' AND id=?", id.toString()); COMMANDS.remove(id);
        }
        jdbc.update("UPDATE organization_person SET active=TRUE WHERE tenant_id='demo' AND subject IN ('finance','cashier')");
    }
    @AfterAll static void stop() { SERVER.stop(0); HTTP_THREADS.shutdownNow(); }

    @Test void financeRetirementWhileAccountReadIsInFlightPreventsLateWorkerFromSending() throws Exception {
        var job = job(); var id = job.input().command().id(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        RESPONDER.set((path, data) -> { if (path.endsWith("debit-accounts")) { entered.countDown(); await(release); } return response(path, data); });
        var pool = Executors.newSingleThreadExecutor();
        try {
            var running = pool.submit(worker::poll); assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            var checking = reload(job); assertThat(checking.status()).isEqualTo(PaymentOperation.Status.CHECKING);
            tx().executeWithoutResult(status -> {
                var original = authorizations.find("demo", id).orElseThrow(); sources.lock(original);
                var stopped = execution.stopForRetirement("demo", id, checking.version(), now());
                authorizations.update(original.retire(stopped, "finance", "结束未发送的原付款", now()));
            });
            release.countDown(); running.get(4, TimeUnit.SECONDS); worker.poll();
            assertThat(WRITES.get()).isZero(); assertThat(reload(job).status()).isEqualTo(PaymentOperation.Status.VOIDED);
            var retired = authorizations.find("demo", id).orElseThrow(); assertThat(retired.matchesRetirement(reload(job))).isTrue();
            assertThat(authorizations.active("demo", BusinessReference.Type.ADVANCE_REQUEST, job.input().command().binding().businessId())).isEmpty();
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void workerRechecksBothAccountsAndOnlySendsTheCommittedOriginalCommand() {
        var job = job(); assertThat(WRITES.get()).isZero(); worker.poll(); var done = reload(job);
        assertThat(done.status()).isEqualTo(PaymentOperation.Status.SUCCEEDED); assertThat(done.settleable()).isTrue();
        assertThat(done.version()).isEqualTo(4); assertThat(done.dispatches()).isEqualTo(1); assertThat(WRITES.get()).isEqualTo(1); assertThat(ACCOUNT_READS.get()).isEqualTo(2);
        assertThat(LAST_KEY.get()).isEqualTo(job.input().command().id().toString()); assertThat(QUERIES.get()).isZero();
        assertThat(new JdbcPaymentOperationRepository(jdbc, json, authorizations).find("demo", job.input().command().id())).contains(done);
        assertThat(jdbc.queryForList("SELECT version FROM payment_operation_revision WHERE tenant_id='demo' AND operation_id=? ORDER BY version", Long.class, job.input().command().id().toString())).containsExactly(1L, 2L, 3L, 4L);
        assertThat(operations.find("foreign", job.input().command().id())).isEmpty();
    }
    @Test void successfulPaymentCreatesOneActualBalanceAndQueriesCannotResetIt() {
        var job = job(); var id = job.input().command().binding().businessId();
        assertThat(balances.find("demo", id)).isEmpty();
        worker.poll();
        var advance = balances.find("demo", id).orElseThrow();
        assertThat(advance.balance().limit()).isEqualTo(job.input().command().amount());
        assertThat(advance.paymentReference()).isEqualTo(reload(job).observation().paymentReference());
        assertThat(advance.dueOn()).isEqualTo(advances.find("demo", id).orElseThrow().currentRound().content().dueOn());
        recheck(job); worker.poll();
        assertThat(balances.find("demo", id).orElseThrow().state()).isEqualTo(advance.state());
        assertThat(balances.find("foreign", id)).isEmpty();
        assertThat(WRITES.get()).isEqualTo(1);
    }

    @Test void confirmedBankReceiptQueuesItsSeparatePaymentVoucher() {
        var payment = job(); worker.poll();
        assertThat(reload(payment).status()).isEqualTo(PaymentOperation.Status.SUCCEEDED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM voucher_preparation WHERE tenant_id='demo' AND application_id=? AND kind='PAYMENT'",
                Integer.class, payment.input().command().binding().applicationId().toString())).isEqualTo(1);
        assertThat(WRITES.get()).isEqualTo(1);
    }

    @Test void repeatedBankQueriesKeepOriginalSuccessfulRevisionAndPostOneSeparateVoucher() {
        var payment = job(); worker.poll(); var original = reload(payment); var queued = paymentPreparation(payment);
        assertThat(queued.input().source().paymentVersion()).isEqualTo(original.version());
        recheck(payment); worker.poll(); assertThat(reload(payment).version()).isGreaterThan(original.version());
        assertThat(paymentPreparation(payment)).isEqualTo(queued);
        preparationWorker.poll(); var prepared = paymentVoucher(payment);
        assertThat(prepared.input().command().payment().receipt()).isEqualTo(original.observation());
        assertThat(prepared.input().command().lines().get(1).account().selector()).isEqualTo(payment.input().command().debitAccountReference());
        voucherWorker.poll(); assertThat(paymentVoucher(payment).status()).isEqualTo(VoucherOperation.Status.POSTED);
        preparationWorker.poll(); voucherWorker.poll();
        assertThat(VOUCHER_WRITES.get()).isEqualTo(1); assertThat(WRITES.get()).isEqualTo(1);
        assertThat(paymentVoucherSources.derive(queued.input().source()).matches(prepared.input().command())).isTrue();
    }

    @Test void laterApprovalRevocationCannotRewritePaidAccountingBinding() {
        var payment = job(); worker.poll(); revoke(payment); preparationWorker.poll(); voucherWorker.poll();
        var voucher = paymentVoucher(payment);
        assertThat(voucher.status()).isEqualTo(VoucherOperation.Status.POSTED);
        assertThat(voucher.input().command().binding().applicationVersion()).isEqualTo(payment.input().command().binding().applicationVersion());
        assertThat(applications.findById("demo", payment.input().command().binding().applicationId()).orElseThrow().status()).isEqualTo(ApplicationStatus.REVOKED);
    }

    @Test void aQueryCompletingDuringAccountingReadsCannotReplaceTheFrozenReceipt() throws Exception {
        var payment = job(); worker.poll(); var original = reload(payment).observation();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        RESPONDER.set((path, data) -> { if (path.endsWith("account-mapping")) { entered.countDown(); await(release); } return response(path, data); });
        var thread = Executors.newSingleThreadExecutor();
        try {
            var running = thread.submit(preparationWorker::poll); assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            recheck(payment); worker.poll(); release.countDown(); running.get(4, TimeUnit.SECONDS);
            assertThat(paymentPreparation(payment).status()).isEqualTo(VoucherPreparation.Status.READY);
            assertThat(paymentVoucher(payment).input().command().payment().receipt()).isEqualTo(original);
        } finally { release.countDown(); thread.shutdownNow(); }
    }

    @Test void accountingRejectionRetainsBankSuccessAndExplicitRetryKeepsOriginalProof() {
        var payment = job(); worker.poll(); var source = paymentPreparation(payment).input().source();
        RESPONDER.set((path, data) -> path.endsWith("accounting-period") ? new FinanceResult.Rejected<>(FinanceResult.Reason.ACCOUNTING_PERIOD_CLOSED) : response(path, data));
        preparationWorker.poll(); assertThat(paymentPreparation(payment).status()).isEqualTo(VoucherPreparation.Status.BLOCKED);
        assertThat(reload(payment).status()).isEqualTo(PaymentOperation.Status.SUCCEEDED); assertThat(VOUCHER_WRITES.get()).isZero();
        recheck(payment); worker.poll();
        tx().executeWithoutResult(status -> preparationService.retryPayment("demo", source.applicationId(), source.roundNo(), "finance", now()));
        assertThat(paymentPreparation(payment).input().source()).isEqualTo(source);
        RESPONDER.set(PaymentOperationIntegrationTest::response); preparationWorker.poll(); voucherWorker.poll();
        assertThat(paymentPreparation(payment).input().attempt()).isEqualTo(2); assertThat(paymentVoucher(payment).usablePosted()).isTrue();
        assertThat(WRITES.get()).isEqualTo(1);
    }

    @Test void bankReversalBeforeVoucherSendStopsNewPostingAndKeepsPreparedProof() {
        var payment = job(); worker.poll(); preparationWorker.poll(); var prepared = paymentVoucher(payment);
        reverse(payment); voucherWorker.poll();
        var stopped = paymentVoucher(payment); assertThat(stopped.status()).isEqualTo(VoucherOperation.Status.VOIDED);
        assertThat(stopped.input()).isEqualTo(prepared.input()); assertThat(VOUCHER_WRITES.get()).isZero();
        assertThat(reload(payment).status()).isEqualTo(PaymentOperation.Status.REVERSED);
    }

    @Test void postedVoucherRemainsQueryableAfterBankReturnWithoutAnotherPosting() {
        var payment = job(); worker.poll(); preparationWorker.poll(); voucherWorker.poll(); var posted = paymentVoucher(payment);
        reverse(payment);
        tx().executeWithoutResult(status -> voucherExecution.query("demo", posted.input().command().id(), posted.version(), now()));
        voucherWorker.poll(); assertThat(paymentVoucher(payment).usablePosted()).isTrue();
        assertThat(paymentVoucher(payment).input()).isEqualTo(posted.input());
        assertThat(VOUCHER_WRITES.get()).isEqualTo(1); assertThat(VOUCHER_QUERIES.get()).isEqualTo(1);
    }

    @Test void upgradeBackfillAndRepeatedPollingDoNotSendOrQueryBank() {
        var payment = job(); worker.poll(); var preparation = paymentPreparation(payment);
        jdbc.update("DELETE FROM voucher_preparation_revision WHERE tenant_id='demo' AND preparation_id=?", preparation.input().id().toString());
        jdbc.update("DELETE FROM voucher_preparation WHERE tenant_id='demo' AND id=?", preparation.input().id().toString());
        assertThat(operations.missingVoucherPreparations()).extracting(JdbcPaymentOperationRepository.Candidate::id).contains(payment.input().command().id());
        preparationWorker.poll(); preparationWorker.poll();
        assertThat(paymentPreparation(payment).status()).isEqualTo(VoucherPreparation.Status.READY);
        assertThat(operations.missingVoucherPreparations()).extracting(JdbcPaymentOperationRepository.Candidate::id).doesNotContain(payment.input().command().id());
        assertThat(WRITES.get()).isEqualTo(1); assertThat(QUERIES.get()).isZero();
    }

    @Test void callerCannotRegisterPaymentVoucherWithoutPersistedPreparationOrSwapSuccessfulRevision() {
        var payment = job(); worker.poll(); var source = paymentPreparation(payment).input().source(); var plan = paymentVoucherSources.derive(source);
        var at = now(); var date = plan.accountingDate();
        var forged = plan.prepare(UUID.randomUUID(), new AccountingPeriodPort.OpenPeriod(plan.periodRequest(), "period", "v1", date.minusDays(1), date.plusDays(1), at, at.plusSeconds(300)),
                new AccountMappingPort.Mapping(plan.mappingRequest(), "v1", at, at.plusSeconds(300), plan.mappingRequest().keys().stream().map(key -> new AccountMappingPort.Entry(key, "synthetic-" + key.role())).toList()), at);
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> voucherExecution.register(forged, configuration.destination("demo").orElseThrow().digest("demo"), now())))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo("PAYMENT_VOUCHER_SOURCE_CHANGED"));
        var pending = new VoucherPreparation.Source(source.tenantId(), source.businessType(), source.businessId(), source.applicationId(), source.roundNo(), source.applicationVersion(), source.businessVersion(), source.employeeId(), source.paymentOperationId(), 1L);
        assertThatThrownBy(() -> paymentVoucherSources.derive(pending)).isInstanceOf(DomainException.class);
        assertThat(operations.revision("foreign", source.paymentOperationId(), source.paymentVersion())).isEmpty();
    }

    @Test void rollbackAndTransactionGuardPreventAnyExternalRequest() {
        var authorization = authorized();
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> { register(authorization); throw new IllegalStateException("Synthetic rollback"); })).isInstanceOf(IllegalStateException.class);
        assertThat(operations.find("demo", authorization.terms().id())).isEmpty(); assertThat(authorizations.find("demo", authorization.terms().id())).contains(authorization);
        worker.poll(); assertThat(WRITES.get()).isZero(); assertThat(ACCOUNT_READS.get()).isZero();
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> worker.poll())).hasMessageContaining("outside a database transaction");
    }
    @Test void changedApprovalOrDisabledOriginalCashierStopsBeforeAnyNetworkCall() {
        var job = job(); revoke(job); worker.poll(); assertThat(reload(job).status()).isEqualTo(PaymentOperation.Status.VOIDED);
        var second = job(); jdbc.update("UPDATE organization_person SET active=FALSE WHERE tenant_id='demo' AND subject='cashier'"); worker.poll();
        assertThat(reload(second).status()).isEqualTo(PaymentOperation.Status.VOIDED); assertThat(WRITES.get()).isZero(); assertThat(ACCOUNT_READS.get()).isZero();
        assertThat(personnel.eligible("foreign", "finance", ENTITY)).isFalse(); assertThat(personnel.eligible("demo", "finance", UUID.randomUUID())).isFalse();
    }
    @Test void actualApprovedAmountsAndFrozenPayeeCannotBeReplacedByCallerTerms() {
        var authorization = authorized(); var terms = authorization.terms();
        var changed = new PaymentAuthorization.Terms(terms.id(), terms.tenantId(), terms.purpose(), terms.binding(), terms.amount(),
                new EmployeeAccountSnapshot(ENTITY, "alice", "different-account", "****5678", "b".repeat(64), "v2"), terms.voucherOperationId(), terms.voucherCommandDigest(), terms.voucherRevision(), terms.voucherReference(), terms.targetDigest());
        var forged = new PaymentAuthorization(changed, authorization.decision(), 1, PaymentAuthorization.Status.AUTHORIZED, authorization.updatedAt(), null, null);
        assertThatThrownBy(() -> sources.requireCurrent(forged, now())).isInstanceOf(DomainException.class);
        jdbc.update("UPDATE approval_application SET version=version+1 WHERE tenant_id='demo' AND id=?", terms.binding().applicationId().toString());
        assertThatThrownBy(() -> sources.requireCurrent(authorization, now())).isInstanceOf(DomainException.class); assertThat(WRITES.get()).isZero();
    }
    @Test void anotherWorkerCannotSendAndAccountWaitingDoesNotHoldTheApplicationLock() throws Exception {
        var job = job(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        RESPONDER.set((path, request) -> { if (path.endsWith("debit-accounts")) { entered.countDown(); await(release); } return response(path, request); });
        var threads = Executors.newFixedThreadPool(2);
        try {
            var first = threads.submit(worker::poll); assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            threads.submit(() -> tx().executeWithoutResult(status -> advances.lock("demo", job.input().command().binding().businessId()))).get(1, TimeUnit.SECONDS);
            threads.submit(worker::poll).get(1, TimeUnit.SECONDS); assertThat(WRITES.get()).isZero();
            release.countDown(); first.get(4, TimeUnit.SECONDS); assertThat(WRITES.get()).isEqualTo(1); assertThat(reload(job).settleable()).isTrue();
        } finally { release.countDown(); threads.shutdownNow(); }
    }
    @Test void approvalChangingDuringAccountReadIsCaughtBeforeMarkingPossibleSend() throws Exception {
        var job = job(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        RESPONDER.set((path, request) -> { if (path.endsWith("debit-accounts")) { entered.countDown(); await(release); } return response(path, request); });
        var thread = Executors.newSingleThreadExecutor();
        try {
            var running = thread.submit(worker::poll); assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            tx().executeWithoutResult(status -> { advances.lock("demo", job.input().command().binding().businessId()); revoke(job); });
            release.countDown(); running.get(4, TimeUnit.SECONDS);
            assertThat(reload(job).status()).isEqualTo(PaymentOperation.Status.VOIDED); assertThat(reload(job).dispatches()).isZero(); assertThat(WRITES.get()).isZero();
        } finally { release.countDown(); thread.shutdownNow(); }
    }
    @Test void currentPayeeChangeAndAccountBusinessRejectionStopWithoutPayment() {
        var changed = job(); RESPONDER.set((path, request) -> path.endsWith("employee-account") ? new EmployeeAccountPort.Account(
                new EmployeeAccountSnapshot(ENTITY, "alice", "changed-account", "****5678", "b".repeat(64), "v2"), now().plusSeconds(60)) : response(path, request));
        worker.poll(); assertThat(reload(changed).status()).isEqualTo(PaymentOperation.Status.VOIDED); assertThat(reload(changed).failure()).isEqualTo(PaymentOperation.Failure.ACCOUNT_CHANGED);
        var blocked = job(); RESPONDER.set((path, request) -> new FinanceResult.Rejected<>(FinanceResult.Reason.CASHIER_UNAVAILABLE));
        worker.poll(); assertThat(reload(blocked).status()).isEqualTo(PaymentOperation.Status.VOIDED); assertThat(WRITES.get()).isZero();
    }
    @Test void changedTargetRemainsUnsentAndAccountReadFailureDoesNotQueryBank() {
        var job = job(); configuration.getTenants().get("demo").setEndpoint(ENDPOINT + "/changed"); worker.poll();
        var current = reload(job); assertThat(current.status()).isEqualTo(PaymentOperation.Status.QUEUED); assertThat(current.failure()).isEqualTo(PaymentOperation.Failure.TARGET_CHANGED);
        assertThat(current.dispatches()).isZero(); assertThat(WRITES.get()).isZero(); assertThat(ACCOUNT_READS.get()).isZero(); assertThat(QUERIES.get()).isZero();
    }
    @Test void lostSendingLeaseQueriesOriginalEvenWhenSourceWasRevokedAndStaleCompletionCannotOverwrite() {
        var job = job(); var at = now().minusSeconds(20); var checking = execution.claim("demo", job.input().command().id(), at);
        var sending = execution.readyToSend(checking, directory(at), account(at), at); assertThat(sending).isNotNull(); revoke(job);
        worker.poll(); assertThat(reload(job).status()).isEqualTo(PaymentOperation.Status.UNKNOWN); worker.poll();
        var done = reload(job); assertThat(done.status()).isEqualTo(PaymentOperation.Status.SUCCEEDED); assertThat(WRITES.get()).isZero(); assertThat(QUERIES.get()).isEqualTo(1); assertThat(ACCOUNT_READS.get()).isZero();
        assertThat(balances.find("demo", job.input().command().binding().businessId())).isPresent();
        execution.finish(sending, new FinanceResult.Success<>(paid(job.input().command(), 1)), now()); assertThat(reload(job)).isEqualTo(done);
        preparationWorker.poll(); voucherWorker.poll();
        assertThat(paymentVoucher(job).usablePosted()).isTrue(); assertThat(WRITES.get()).isZero();
    }
    @Test void localSettlementFailureRollsBackThenOnlyQueriesOriginalPayment() {
        var job = job(); listener.reject.set(true); worker.poll(); var unknown = reload(job);
        assertThat(unknown.status()).isEqualTo(PaymentOperation.Status.UNKNOWN); assertThat(unknown.observation()).isNull(); assertThat(WRITES.get()).isEqualTo(1);
        assertThat(balances.find("demo", job.input().command().binding().businessId())).isEmpty();
        recheck(job); worker.poll(); assertThat(reload(job).status()).isEqualTo(PaymentOperation.Status.SUCCEEDED); assertThat(WRITES.get()).isEqualTo(1); assertThat(QUERIES.get()).isEqualTo(1);
        assertThat(balances.find("demo", job.input().command().binding().businessId())).isPresent();
    }

    @Test void oldSuccessfulPaymentIsRecoveredWithoutAnotherSendOrQuery() {
        var job = job(); worker.poll(); var id = job.input().command().binding().businessId();
        jdbc.update("DELETE FROM finance_resource_revision WHERE tenant_id='demo' AND resource_type='ADVANCE' AND resource_id=?", id.toString());
        jdbc.update("DELETE FROM finance_resource WHERE tenant_id='demo' AND resource_type='ADVANCE' AND id=?", id.toString());
        assertThat(operations.missingAdvanceBalances()).extracting(JdbcPaymentOperationRepository.Candidate::id).contains(job.input().command().id());
        worker.poll(); disbursements.recover("demo", job.input().command().id());
        assertThat(balances.find("demo", id).orElseThrow().version()).isEqualTo(1);
        assertThat(WRITES.get()).isEqualTo(1); assertThat(QUERIES.get()).isZero();
    }

    @Test void persistedSuccessfulDecisionUnfreezesOnlyOriginalBalanceAndResumesOriginalPaymentVoucher() {
        var job = job(); worker.poll(); var command = job.input().command(); var id = command.binding().businessId(); var paid = reload(job).observation();
        var ledger = balances.find("demo", id).orElseThrow().balance(); reverse(job);
        var at = now(); var corrected = new PaymentObservation(command.id(), command.digest(), PaymentObservation.Status.SUCCEEDED, 3L, at,
                paid.paymentReference(), paid.paidAmount(), paid.accountDigest(), paid.completedAt(), paid.receiptReference(), null);
        RESPONDER.set((path, data) -> path.endsWith("payment-query") ? corrected : response(path, data)); recheck(job); worker.poll();
        var disputed = reload(job); var frozen = balances.find("demo", id).orElseThrow(); assertThat(frozen.paymentReviewRequired()).isTrue();
        var history = operations.disputeEvidence("demo", command.id());
        tx().executeWithoutResult(status -> {
            sources.lock(authorizations.find("demo", command.id()).orElseThrow()); var now = now();
            var resolved = disputed.resolveDispute(PaymentObservation.Status.SUCCEEDED, history.firstSuccess(), history.fundingObserved(), now);
            var decision = new PaymentDisputeResolution(UUID.randomUUID(), "demo", command.id(), disputed.version(), resolved.version(), resolved.observation(), "finance", now, "BANK-ADVANCE-001", "原借款到账事实确认有效");
            operations.update(resolved); disputeDecisions.create(decision); events.publishEvent(new PaymentOperationChanged(disputed, resolved)); events.publishEvent(new PaymentDisputeResolved(resolved, decision));
        });
        var restored = balances.find("demo", id).orElseThrow(); assertThat(restored.balance()).isEqualTo(ledger);
        assertThat(restored.paymentReviewRequired()).isFalse(); assertThat(restored.available()).isEqualTo(command.amount());
        assertThat(restored.version()).isEqualTo(frozen.version() + 1); recheck(job); worker.poll();
        assertThat(balances.find("demo", id).orElseThrow().state()).isEqualTo(restored.state());
        preparationWorker.poll(); voucherWorker.poll(); assertThat(paymentVoucher(job).usablePosted()).isTrue(); assertThat(WRITES.get()).isEqualTo(1);
    }

    @Test void reversalFreezesThePaidBalanceAndRepeatedReceiptDoesNotEraseIt() {
        var job = job(); worker.poll(); var id = job.input().command().binding().businessId(); var original = balances.find("demo", id).orElseThrow();
        var success = reload(job).observation(); var returnedAt = now(); var reversed = new PaymentObservation(success.authorizationId(), success.commandDigest(), PaymentObservation.Status.REVERSED,
                2L, returnedAt, success.paymentReference(), success.paidAmount(), success.accountDigest(), returnedAt, "returned-" + job.input().command().id(), null);
        RESPONDER.set((path, data) -> path.endsWith("payment-query") ? reversed : response(path, data));
        recheck(job); worker.poll();
        var held = balances.find("demo", id).orElseThrow(); assertThat(reload(job).status()).isEqualTo(PaymentOperation.Status.REVERSED);
        assertThat(held.paymentReviewRequired()).isTrue(); assertThat(held.balance()).isEqualTo(original.balance());
        assertThat(held.available()).isEqualTo(Money.zero("CNY")); assertThat(held.version()).isEqualTo(2);
        recheck(job); worker.poll(); assertThat(balances.find("demo", id).orElseThrow().state()).isEqualTo(held.state());
    }

    @Test void conflictingReceiptFreezesTheOriginalBalanceWithoutReplacingItsPaymentReference() {
        var job = job(); worker.poll(); var success = reload(job).observation();
        var conflict = new PaymentObservation(success.authorizationId(), success.commandDigest(), PaymentObservation.Status.SUCCEEDED, 1L, now(),
                "different-bank-reference", success.paidAmount(), success.accountDigest(), success.completedAt(), success.receiptReference(), null);
        RESPONDER.set((path, data) -> path.endsWith("payment-query") ? conflict : response(path, data)); recheck(job); worker.poll();
        assertThat(reload(job).status()).isEqualTo(PaymentOperation.Status.RECONCILING);
        var held = balances.find("demo", job.input().command().binding().businessId()).orElseThrow();
        assertThat(held.paymentReviewRequired()).isTrue(); assertThat(held.paymentReference()).isEqualTo(success.paymentReference());
        assertThat(held.balance().limit()).isEqualTo(success.paidAmount());
    }
    @Test void missingAfterUncertainSendRequiresExplicitOriginalResendAndFreshAccountReads() {
        var job = job(); RESPONDER.set((path, request) -> path.endsWith("payment-command") ? Map.of("invalid", true) : response(path, request)); worker.poll();
        assertThat(reload(job).status()).isEqualTo(PaymentOperation.Status.UNKNOWN); assertThat(WRITES.get()).isEqualTo(1);
        RESPONDER.set((path, request) -> path.endsWith("payment-query") ? missing(job.input().command()) : response(path, request)); recheck(job); worker.poll();
        assertThat(reload(job).status()).isEqualTo(PaymentOperation.Status.NOT_FOUND); worker.poll(); assertThat(WRITES.get()).isEqualTo(1);
        tx().executeWithoutResult(status -> execution.resend("demo", job.input().command().id(), reload(job).version(), now())); RESPONDER.set(PaymentOperationIntegrationTest::response); worker.poll();
        assertThat(reload(job).status()).isEqualTo(PaymentOperation.Status.SUCCEEDED); assertThat(WRITES.get()).isEqualTo(2); assertThat(ACCOUNT_READS.get()).isEqualTo(4);
        assertThat(reload(job).input()).isEqualTo(job.input()); assertThat(LAST_KEY.get()).isEqualTo(job.input().command().id().toString());
    }

    @Test void cashierChoiceIsCommittedBeforeAnyAccountCallAndReadyOnlyRegistersOriginalPayment() {
        var authorization = authorized(); var request = request(authorization, "v1");
        assertThat(ACCOUNT_READS.get()).isZero(); assertThat(authorizations.find("demo", authorization.terms().id())).contains(authorization);
        assertThat(executionRequests.find("foreign", request.input().id())).isEmpty(); requestWorker.poll();
        assertThat(executionRequests.find("demo", request.input().id()).orElseThrow().status()).isEqualTo(PaymentExecutionRequest.Status.READY);
        var registered = authorizations.find("demo", authorization.terms().id()).orElseThrow(); var queued = operations.find("demo", authorization.terms().id()).orElseThrow();
        COMMANDS.put(authorization.terms().id(), queued.input().command());
        assertThat(registered.status()).isEqualTo(PaymentAuthorization.Status.EXECUTION_REGISTERED); assertThat(queued.status()).isEqualTo(PaymentOperation.Status.QUEUED);
        assertThat(WRITES.get()).isZero(); assertThat(ACCOUNT_READS.get()).isEqualTo(2);
        worker.poll(); assertThat(reload(queued).settleable()).isTrue(); assertThat(WRITES.get()).isEqualTo(1); assertThat(ACCOUNT_READS.get()).isEqualTo(4);
    }
    @Test void reviewedAdvanceAccountPreservesOriginalApprovalCreatesOneBalanceAndPostsPaymentVoucher() {
        var original = authorized(); var ended = original.voidBeforeExecution("finance", "合成账户变更", now());
        tx().executeWithoutResult(status -> authorizations.update(ended));
        var voucher = vouchers.find("demo", original.terms().voucherOperationId()).orElseThrow();
        var review = tx().execute(status -> payeeReviewService.register(ended, voucher.version(), "finance", now()));
        var updatedAccount = new EmployeeAccountSnapshot(ENTITY, "alice", "synthetic-updated-account", "****9876", "b".repeat(64), "v2");
        RESPONDER.set((path, data) -> path.endsWith("employee-account") ? new EmployeeAccountPort.Account(updatedAccount, now().plusSeconds(600)) : response(path, data));
        payeeReviewWorker.poll(); var ready = payeeReviews.find("demo", review.input().id()).orElseThrow();
        assertThat(ready.status()).isEqualTo(PaymentPayeeReview.Status.READY); assertThat(WRITES.get()).isZero();
        assertThatThrownBy(() -> tx().execute(status -> payeeReviewService.requireReady("demo", ready.input().id(), ready.version(), voucher, "cashier", now())))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo("PAYMENT_PAYEE_REVIEW_UNAVAILABLE"));
        var replacement = tx().execute(status -> {
            var at = now(); var proof = payeeReviewService.requireReady("demo", ready.input().id(), ready.version(), voucher, "finance", at);
            var value = PaymentAuthorization.issue(UUID.randomUUID(), voucher, proof.account().snapshot(), "finance", at, at.plusSeconds(600));
            authorizations.create(value); payeeReviewService.consume(proof, value, at); return value;
        }); fixtures.add(replacement.terms().id());
        tx().executeWithoutResult(status -> {
            jdbc.update("DELETE FROM payment_payee_review_revision WHERE tenant_id='demo' AND review_id=?", ready.input().id().toString());
            jdbc.update("DELETE FROM payment_payee_review WHERE tenant_id='demo' AND id=?", ready.input().id().toString());
            assertThatThrownBy(() -> sources.requireCurrent(replacement, now())).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo("PAYMENT_PAYEE_EVIDENCE_CHANGED"));
            status.setRollbackOnly();
        });
        tx().executeWithoutResult(status -> requestService.register(replacement, "cashier", "debit-1", "v1", now())); requestWorker.poll(); worker.poll();
        var payment = operations.find("demo", replacement.terms().id()).orElseThrow(); assertThat(payment.settleable()).isTrue();
        assertThat(payment.input().command().payee()).isEqualTo(updatedAccount); assertThat(WRITES.get()).isEqualTo(1);
        var advance = advances.find("demo", original.terms().binding().businessId()).orElseThrow();
        assertThat(advance.currentRound().account()).isEqualTo(original.terms().payee());
        var balance = balances.find("demo", advance.id()).orElseThrow(); assertThat(balance.available()).isEqualTo(payment.input().command().amount());
        disbursements.recover("demo", replacement.terms().id()); assertThat(balances.find("demo", advance.id()).orElseThrow().state()).isEqualTo(balance.state());
        preparationWorker.poll(); voucherWorker.poll(); assertThat(paymentVoucher(payment).usablePosted()).isTrue();
        assertThat(authorizations.find("demo", original.terms().id())).contains(ended);
    }
    @Test void expiredUnexecutedAuthorizationCanRegisterReviewWithoutReleasingAnyExecutedTransaction() {
        var original = authorized(); var voucher = vouchers.find("demo", original.terms().voucherOperationId()).orElseThrow();
        var review = tx().execute(status -> payeeReviewService.register(original, voucher.version(), "finance", original.decision().expiresAt()));
        assertThat(authorizations.find("demo", original.terms().id()).orElseThrow().status()).isEqualTo(PaymentAuthorization.Status.EXPIRED);
        assertThat(review.input().authorizationVersion()).isEqualTo(2); assertThat(review.status()).isEqualTo(PaymentPayeeReview.Status.QUEUED);
        assertThat(ACCOUNT_READS.get()).isZero(); assertThat(WRITES.get()).isZero();
    }
    @Test void concurrentFinanceConfirmationsConsumeOneReviewAndCreateOneNewAuthorization() throws Exception {
        var original = authorized(); var ended = original.voidBeforeExecution("finance", "并发复核样例", now());
        tx().executeWithoutResult(status -> authorizations.update(ended));
        var voucher = vouchers.find("demo", original.terms().voucherOperationId()).orElseThrow();
        var queued = tx().execute(status -> payeeReviewService.register(ended, voucher.version(), "finance", now())); payeeReviewWorker.poll();
        var ready = payeeReviews.find("demo", queued.input().id()).orElseThrow();
        var gate = new java.util.concurrent.CountDownLatch(1); var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.Callable<PaymentAuthorization> confirm = () -> {
            gate.await(); return tx().execute(status -> {
                var at = now(); var proof = payeeReviewService.requireReady("demo", ready.input().id(), ready.version(), voucher, "finance", at);
                var value = PaymentAuthorization.issue(UUID.randomUUID(), voucher, proof.account().snapshot(), "finance", at, at.plusSeconds(600));
                authorizations.create(value); payeeReviewService.consume(proof, value, at); return value;
            });
        };
        int saved = 0, rejected = 0; UUID winner = null;
        try {
            var first = pool.submit(confirm); var second = pool.submit(confirm); gate.countDown();
            for (var result : List.of(first, second)) {
                try { var value = result.get(10, java.util.concurrent.TimeUnit.SECONDS); fixtures.add(value.terms().id()); winner = value.terms().id(); saved++; }
                catch (java.util.concurrent.ExecutionException failed) { assertThat(failed.getCause()).isInstanceOf(DomainException.class); rejected++; }
            }
        } finally { pool.shutdownNow(); }
        assertThat(saved).isEqualTo(1); assertThat(rejected).isEqualTo(1);
        assertThat(payeeReviews.find("demo", ready.input().id()).orElseThrow().consumedAuthorizationId()).isEqualTo(winner);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_authorization WHERE tenant_id='demo' AND business_id=?", Integer.class, original.terms().binding().businessId().toString())).isEqualTo(2);
        assertThat(WRITES.get()).isZero();
    }
    @Test void duplicateCashierSelectionAndChangedDisplayedAccountVersionCannotRegisterPayment() {
        var authorization = authorized(); var request = request(authorization, "outdated");
        assertThatThrownBy(() -> request(authorization, "v1")).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo("PAYMENT_EXECUTION_ALREADY_REQUESTED"));
        requestWorker.poll(); assertThat(executionRequests.find("demo", request.input().id()).orElseThrow().status()).isEqualTo(PaymentExecutionRequest.Status.BLOCKED);
        assertThat(authorizations.find("demo", authorization.terms().id())).contains(authorization); assertThat(operations.find("demo", authorization.terms().id())).isEmpty(); assertThat(WRITES.get()).isZero();
    }
    @Test void requestLeaseRecoveryKeepsChoiceAndLateWorkerCannotRegisterAgain() {
        var authorization = authorized(); var request = request(authorization, "v1");
        var stale = requestService.claim("demo", request.input().id(), now().minusSeconds(20));
        requestWorker.poll(); assertThat(executionRequests.find("demo", request.input().id()).orElseThrow().status()).isEqualTo(PaymentExecutionRequest.Status.QUEUED);
        requestWorker.poll(); var ready = executionRequests.find("demo", request.input().id()).orElseThrow(); assertThat(ready.status()).isEqualTo(PaymentExecutionRequest.Status.READY);
        requestService.finish(stale, directory(now()), account(now()), now()); assertThat(executionRequests.find("demo", request.input().id())).contains(ready);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_operation WHERE tenant_id='demo' AND id=?", Long.class, authorization.terms().id().toString())).isEqualTo(1);
        assertThat(WRITES.get()).isZero();
    }
    @Test void requestHistoryFailureRollsBackAuthorizationAndPaymentQueueTogether() {
        var authorization = authorized(); var request = request(authorization, "v1"); var work = requestService.claim("demo", request.input().id(), now());
        jdbc.update("INSERT INTO payment_execution_request_revision(tenant_id,request_id,version,state_json) VALUES('demo',?,3,'{}')", request.input().id().toString());
        assertThatThrownBy(() -> requestService.finish(work, directory(now()), account(now()), now())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(authorizations.find("demo", authorization.terms().id())).contains(authorization); assertThat(operations.find("demo", authorization.terms().id())).isEmpty();
        assertThat(executionRequests.find("demo", request.input().id())).contains(work.request()); assertThat(WRITES.get()).isZero();
    }
    @Test void financeVoidingAuthorizationDuringRequestReadsPreventsAnyPaymentQueue() throws Exception {
        var authorization = authorized(); var request = request(authorization, "v1"); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        RESPONDER.set((path, data) -> { if (path.endsWith("debit-accounts")) { entered.countDown(); await(release); } return response(path, data); });
        var thread = Executors.newSingleThreadExecutor();
        try {
            var running = thread.submit(requestWorker::poll); assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            tx().executeWithoutResult(status -> { sources.lock(authorization); authorizations.update(authorization.voidBeforeExecution("finance", "取消尚未完成的执行检查", now())); });
            release.countDown(); running.get(4, TimeUnit.SECONDS);
            assertThat(executionRequests.find("demo", request.input().id()).orElseThrow().status()).isEqualTo(PaymentExecutionRequest.Status.VOIDED);
            assertThat(operations.find("demo", authorization.terms().id())).isEmpty(); assertThat(WRITES.get()).isZero();
        } finally { release.countDown(); thread.shutdownNow(); }
    }
    @Test void authorizationExpiryDuringRequestReadClosesBothUnexecutedRecords() {
        var authorization = authorized(); var request = request(authorization, "v1"); var until = authorization.decision().expiresAt();
        var work = requestService.claim("demo", request.input().id(), until.minusSeconds(1));
        requestService.finish(work, directory(until), account(until), until);
        assertThat(authorizations.find("demo", authorization.terms().id()).orElseThrow().status()).isEqualTo(PaymentAuthorization.Status.EXPIRED);
        assertThat(executionRequests.find("demo", request.input().id()).orElseThrow().status()).isEqualTo(PaymentExecutionRequest.Status.EXPIRED);
        assertThat(operations.find("demo", authorization.terms().id())).isEmpty(); assertThat(WRITES.get()).isZero();
    }

    @Test void storedCashierChoiceCannotBeRewrittenAndRelationalIdentityTamperingFailsClosed() {
        var authorization = authorized(); var request = request(authorization, "v1"); var input = request.input(); var claimed = request.claim(now(), Duration.ofSeconds(15));
        var changedInput = new PaymentExecutionRequest.Input(input.id(), input.tenantId(), input.authorizationId(), input.authorizationVersion(), input.cashier(), "another-debit", input.debitVersion());
        var changed = new PaymentExecutionRequest(changedInput, claimed.version(), claimed.status(), claimed.attempts(), claimed.createdAt(), claimed.updatedAt(), claimed.nextAttemptAt(), claimed.leaseUntil(), claimed.failure());
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> executionRequests.update(changed))).isInstanceOf(DomainException.class);
        jdbc.update("UPDATE payment_execution_request SET cashier_id='another-person' WHERE tenant_id='demo' AND id=?", input.id().toString());
        assertThatThrownBy(() -> executionRequests.find("demo", input.id())).isInstanceOf(IllegalStateException.class);
    }

    private PaymentExecutionRequest request(PaymentAuthorization authorization, String accountVersion) {
        return tx().execute(status -> requestService.register(authorization, "cashier", "debit-1", accountVersion, now().minusSeconds(30)));
    }

    private PaymentOperation job() { return register(authorized()); }
    private PaymentOperation register(PaymentAuthorization authorization) {
        return tx().execute(status -> {
            var at = now().minusSeconds(30); var executed = authorization.registerExecution("cashier", directory(at), "debit-1", account(at), sources.requireCurrent(authorization, at), at);
            authorizations.update(executed); var operation = execution.register(executed, at); COMMANDS.put(executed.terms().id(), executed.execution().command()); return operation;
        });
    }
    private PaymentAuthorization authorized() {
        var request = advance(); var plan = VoucherSource.advance(applications.findById("demo", request.applicationId()).orElseThrow(), request); var at = now().minusSeconds(60); var date = plan.accountingDate();
        var period = new AccountingPeriodPort.OpenPeriod(plan.periodRequest(), "synthetic-period", "v1", date.minusDays(30), date.plusDays(30), at, at.plusSeconds(600));
        var mapping = new AccountMappingPort.Mapping(plan.mappingRequest(), "v1", at, at.plusSeconds(600), plan.mappingRequest().keys().stream().map(key -> new AccountMappingPort.Entry(key, "synthetic-" + key.role())).toList());
        var command = plan.prepare(UUID.randomUUID(), period, mapping, at);
        var queued = tx().execute(status -> voucherExecution.register(command, configuration.destination("demo").orElseThrow().digest("demo"), at));
        var claimed = queued.claim(at, Duration.ofSeconds(15)); var observed = at.plusSeconds(1);
        var posted = claimed.complete(new FinanceResult.Success<>(new VoucherObservation(command.id(), command.digest(), VoucherObservation.Status.POSTED, 1L, observed,
                "posting-1", "voucher-1", period.periodReference(), date, command.totals().gross(), command.totals().gross(), observed, null)), observed);
        tx().executeWithoutResult(status -> { vouchers.update(claimed); vouchers.update(posted); });
        var authorization = PaymentAuthorization.issue(UUID.randomUUID(), posted, sources.payee(posted, observed), "finance", at.plusSeconds(10), at.plusSeconds(600));
        fixtures.add(authorization.terms().id()); tx().executeWithoutResult(status -> authorizations.create(authorization)); return authorization;
    }
    private AdvanceRequest advance() {
        var at = now().minusSeconds(120); var id = UUID.randomUUID();
        var app = Application.draftBusiness(UUID.randomUUID(), "demo", "SYNTHETIC-PAYMENT-" + id, "fixture", 1, "alice", "合成付款", Map.of(), null, null, null, new BusinessReference(BusinessReference.Type.ADVANCE_REQUEST, id));
        var request = AdvanceRequest.draft(id, "demo", app.id(), "alice", new AdvanceRequestContent(ENTITY, "合成借款", "合成用途", new Money(new BigDecimal("100.00"), "CNY"), at.atZone(ZoneOffset.UTC).toLocalDate().plusDays(10)));
        tx().executeWithoutResult(status -> {
            applications.save(app); advances.create(request, "alice");
            var catalog = new FinanceCatalog("alice", "v1", at.plusSeconds(600), List.of(new FinanceCatalog.LegalEntity(ENTITY, "法人", "CNY", false, "v1", "UTC")), List.of(), List.of(), List.of(), List.of());
            request.freeze(1, 1, catalog, account(at), new InitiatorContext(UUID.randomUUID(), UUID.randomUUID(), "alice", 1, ENTITY, "法人", UUID.randomUUID(), "部门", UUID.randomUUID(), "岗位"), at);
            advances.update(request, 1, "alice", "SYNTHETIC_SUBMIT"); request.approve(2, 1, 5, "manager", at); advances.update(request, 2, "manager", "SYNTHETIC_APPROVE");
            applications.update(Application.restore(app.id(), "demo", app.businessNo(), app.processKey(), 1, "alice", app.title(), AdvanceRequestFormContract.submittedPayload(request.currentRound()),
                    ApplicationStatus.APPROVED, 1, 5, null, null, NotificationTexts.EMPTY, app.businessReference()), 1);
        }); return request;
    }
    private void setupOrganization() {
        if (organization.unit("demo", ENTITY).isPresent()) return;
        tx().executeWithoutResult(status -> {
            if (!organization.initialized("demo")) organization.initialize("demo", "admin", now());
            organization.save("demo", new OrganizationUnit(ENTITY, OrganizationUnit.Kind.LEGAL_ENTITY, "付款法人", null, null, true, 1), 0);
            var department = new OrganizationUnit(UUID.randomUUID(), OrganizationUnit.Kind.DEPARTMENT, "财务部", ENTITY, null, true, 1);
            var position = new OrganizationUnit(UUID.randomUUID(), OrganizationUnit.Kind.POSITION, "财务执行岗位", ENTITY, null, true, 1);
            organization.save("demo", department, 0); organization.save("demo", position, 0);
            for (String user : List.of("finance", "cashier")) {
                var person = new OrganizationPerson(UUID.randomUUID(), user, user, true, true, 1); organization.save("demo", person, 0);
                organization.save("demo", new OrganizationAppointment(UUID.randomUUID(), person.id(), department.id(), position.id(), true, 1), 0);
            }
        });
    }
    private void revoke(PaymentOperation job) { jdbc.update("UPDATE approval_application SET status='REVOKED',version=version+1 WHERE tenant_id='demo' AND id=?", job.input().command().binding().applicationId().toString()); }
    private PaymentOperation reload(PaymentOperation value) { return operations.find("demo", value.input().command().id()).orElseThrow(); }
    private VoucherPreparation paymentPreparation(PaymentOperation payment) { return preparations.latest("demo", payment.input().command().binding().applicationId(), 1, VoucherCommand.Kind.PAYMENT).orElseThrow(); }
    private VoucherOperation paymentVoucher(PaymentOperation payment) { return vouchers.forRound("demo", payment.input().command().binding().applicationId(), 1, VoucherCommand.Kind.PAYMENT).orElseThrow(); }
    private void reverse(PaymentOperation payment) {
        var success = reload(payment).observation(); var at = now();
        var reversal = new PaymentObservation(success.authorizationId(), success.commandDigest(), PaymentObservation.Status.REVERSED, 2L, at,
                success.paymentReference(), success.paidAmount(), success.accountDigest(), at, "returned-" + payment.input().command().id(), null);
        RESPONDER.set((path, data) -> path.endsWith("payment-query") ? reversal : response(path, data)); recheck(payment); worker.poll();
        assertThat(reload(payment).status()).isEqualTo(PaymentOperation.Status.REVERSED);
    }
    private void recheck(PaymentOperation value) { tx().executeWithoutResult(status -> execution.query("demo", value.input().command().id(), reload(value).version(), now())); }
    private TransactionTemplate tx() { return new TransactionTemplate(transactions); }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private static PaymentAccountsPort.Directory directory(Instant at) {
        return new PaymentAccountsPort.Directory(new PaymentAccountsPort.Request(ENTITY, "CNY", "cashier"), "v1", at, at.plusSeconds(600),
                List.of(new PaymentAccountsPort.DebitAccount("debit-1", "业务账户", "****5678", "CNY", "v1")));
    }
    private static EmployeeAccountPort.Account account(Instant at) { return new EmployeeAccountPort.Account(new EmployeeAccountSnapshot(ENTITY, "alice", "synthetic-account", "****1234", "a".repeat(64), "v1"), at.plusSeconds(600)); }
    private static PaymentObservation paid(PaymentCommand command, long revision) { return new PaymentObservation(command.id(), command.digest(), PaymentObservation.Status.SUCCEEDED, revision, now(), "bank-" + command.id(), command.amount(), command.payee().accountDigest(), command.authorization().authorizedAt(), "receipt-" + command.id(), null); }
    private static PaymentObservation missing(PaymentCommand command) { return new PaymentObservation(command.id(), command.digest(), PaymentObservation.Status.NOT_FOUND, 0L, now(), null, null, null, null, null, null); }
    private static Object response(String path, JsonNode request) {
        if (path.endsWith("debit-accounts")) return directory(now());
        if (path.endsWith("employee-account")) return account(now());
        var data = request.path("data"); var at = now();
        if (path.endsWith("accounting-period")) {
            var period = wire.read(data.toString(), AccountingPeriodPort.Request.class); var date = period.accountingDate();
            return new AccountingPeriodPort.OpenPeriod(period, "synthetic-period", "v1", date.minusDays(30), date.plusDays(30), at, at.plusSeconds(300));
        }
        if (path.endsWith("account-mapping")) {
            var mapping = wire.read(data.toString(), AccountMappingPort.Request.class);
            return new AccountMappingPort.Mapping(mapping, "v1", at, at.plusSeconds(300), mapping.keys().stream().map(key -> new AccountMappingPort.Entry(key, "synthetic-" + key.role())).toList());
        }
        if (path.endsWith("voucher-command") || path.endsWith("voucher-query")) {
            var voucher = path.endsWith("voucher-command") ? wire.read(data.path("command").toString(), VoucherCommand.class) : VOUCHER_COMMANDS.get(UUID.fromString(data.path("operationId").asText()));
            VOUCHER_COMMANDS.put(voucher.id(), voucher);
            return new VoucherObservation(voucher.id(), voucher.digest(), VoucherObservation.Status.POSTED, 1L, at, "posted-" + voucher.id(), "voucher-" + voucher.id(),
                    voucher.period().periodReference(), voucher.accountingDate(), voucher.totals().gross(), voucher.totals().gross(), voucher.createdAt(), null);
        }
        var command = path.endsWith("payment-command") ? wire.read(request.at("/data/command").toString(), PaymentCommand.class) : COMMANDS.get(UUID.fromString(request.at("/data/authorizationId").asText()));
        return paid(command, 1);
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic payment wait expired"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Synthetic payment interrupted"); }
    }
    private static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(HTTP_THREADS);
            server.createContext("/finance/", exchange -> {
                var path = exchange.getRequestURI().getPath();
                if (path.endsWith("payment-command")) WRITES.incrementAndGet(); else if (path.endsWith("payment-query")) QUERIES.incrementAndGet();
                else if (path.endsWith("voucher-command")) VOUCHER_WRITES.incrementAndGet(); else if (path.endsWith("voucher-query")) VOUCHER_QUERIES.incrementAndGet();
                else if (path.endsWith("debit-accounts") || path.endsWith("employee-account")) ACCOUNT_READS.incrementAndGet();
                LAST_KEY.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                var request = wire.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonNode.class); var value = RESPONDER.get().apply(path, request);
                var envelope = value instanceof FinanceResult.Rejected<?> rejected
                        ? Map.of("contractVersion", 1, "tenantId", request.path("tenantId").asText(), "requestId", request.path("requestId").asText(), "outcome", "REJECTED", "reason", rejected.reason().name())
                        : Map.of("contractVersion", 1, "tenantId", request.path("tenantId").asText(), "requestId", request.path("requestId").asText(), "outcome", "SUCCESS", "data", value);
                byte[] body = wire.write(envelope).getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, 0);
                try { exchange.getResponseBody().write(body); } finally { exchange.close(); }
            }); server.start(); return server;
        } catch (Exception failed) { throw new IllegalStateException(failed); }
    }
    /**
     * 模拟资金已到账后本地消费者失败，验证持久确认和结算的共同回滚。
     * @author owlzhangfq@gmail.com
     */
    static class FailureListener {
        private final AtomicBoolean reject = new AtomicBoolean();
        @EventListener public void changed(PaymentOperationChanged event) {
            if (event.current().status() == PaymentOperation.Status.SUCCEEDED && reject.compareAndSet(true, false)) throw new IllegalStateException("Synthetic settlement failure");
        }
    }
    /**
     * 故障消费者仅在此测试中启用。
     * @author owlzhangfq@gmail.com
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class ListenerConfiguration { @Bean FailureListener paymentFailureListener() { return new FailureListener(); } }
}
