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
import io.agentflow.organization.InitiatorContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * 合成借款实际聚合、数据库和回环 ERP 共同验证持久副作用，不替代企业 ERP 验收。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.finance-gateway.enabled=true", "agentflow.vouchers.worker-enabled=false",
        "agentflow.vouchers.preparation-worker-enabled=false", "agentflow.vouchers.preparation-lease-seconds=15",
        "agentflow.vouchers.lease-seconds=15", "agentflow.budgets.worker-enabled=false", "agentflow.invoices.verification-worker-enabled=false",
        "agentflow.expenses.precheck-worker-enabled=false", "agentflow.advance-requests.precheck-worker-enabled=false"})
@Import(VoucherOperationIntegrationTest.ListenerConfiguration.class)
class VoucherOperationIntegrationTest {
    private static final UUID ENTITY = UUID.randomUUID();
    private static final ExecutorService HTTP_THREADS = Executors.newCachedThreadPool();
    private static final HttpServer SERVER = server();
    private static final String ENDPOINT = "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/finance";
    private static final AtomicReference<BiFunction<String, JsonNode, Object>> RESPONDER = new AtomicReference<>();
    private static final AtomicInteger WRITES = new AtomicInteger(), QUERIES = new AtomicInteger();
    private static final AtomicReference<String> LAST_KEY = new AtomicReference<>();
    private static JsonUtil wire;
    private final List<UUID> fixtures = new ArrayList<>();
    @Autowired JsonUtil json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ApplicationRepository applications;
    @Autowired AdvanceRequestRepository advances;
    @Autowired JdbcVoucherOperationRepository operations;
    @Autowired VoucherOperationService execution;
    @Autowired VoucherOperationWorker worker;
    @Autowired FinanceGatewayConfiguration configuration;
    @Autowired FailureListener listener;
    @Autowired ApprovedVoucherSources sources;
    @Autowired JdbcVoucherPreparationRepository preparations;
    @Autowired VoucherPreparationService preparationService;
    @Autowired VoucherPreparationWorker preparationWorker;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry values) {
        values.add("agentflow.finance-gateway.tenants.demo.endpoint", () -> ENDPOINT);
        values.add("agentflow.finance-gateway.tenants.demo.allow-unauthenticated-loopback", () -> "true");
        values.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_VOUCHER_TEST_URL", "jdbc:h2:mem:voucher-operations;DB_CLOSE_DELAY=-1"));
        values.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_VOUCHER_TEST_DRIVER", "org.h2.Driver"));
        values.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_VOUCHER_TEST_USER", "sa"));
        values.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_VOUCHER_TEST_PASSWORD", ""));
    }

    @BeforeEach void setup() {
        wire = json; WRITES.set(0); QUERIES.set(0); listener.reject.set(false); configuration.setEnabled(true);
        configuration.getTenants().get("demo").setEndpoint(ENDPOINT); configuration.getTenants().get("demo").setTimeoutSeconds(2);
        RESPONDER.set((path, request) -> posted(json.read(request.at("/data/command").toString(), VoucherCommand.class), 1));
    }
    @AfterEach void removeOnlyFixtureOperations() {
        for (var business : fixtures) {
            jdbc.update("DELETE FROM voucher_preparation_revision WHERE tenant_id='demo' AND preparation_id IN (SELECT id FROM voucher_preparation WHERE tenant_id='demo' AND business_id=?)", business.toString());
            jdbc.update("DELETE FROM voucher_preparation WHERE tenant_id='demo' AND business_id=?", business.toString());
            jdbc.update("DELETE FROM voucher_operation_revision WHERE tenant_id='demo' AND operation_id IN (SELECT id FROM voucher_operation WHERE tenant_id='demo' AND business_id=?)", business.toString());
            jdbc.update("DELETE FROM voucher_operation WHERE tenant_id='demo' AND business_id=?", business.toString());
        }
    }
    @AfterAll static void stop() { SERVER.stop(0); HTTP_THREADS.shutdownNow(); }

    @Test void workerPostsOnlyCommittedCommandAndRestoresAllRevisions() {
        var command = command(advance()); var job = register(command);
        assertThat(WRITES.get()).isZero(); assertThat(register(command)).isEqualTo(job);
        worker.poll(); var done = reload(job);
        assertThat(done.status()).isEqualTo(VoucherOperation.Status.POSTED); assertThat(done.usablePosted()).isTrue();
        assertThat(done.version()).isEqualTo(3); assertThat(done.attempts()).isEqualTo(1);
        assertThat(WRITES.get()).isEqualTo(1); assertThat(QUERIES.get()).isZero(); assertThat(LAST_KEY.get()).isEqualTo(command.id().toString());
        assertThat(revisions(job)).containsExactly(1L, 2L, 3L);
        assertThat(new JdbcVoucherOperationRepository(jdbc, json).find("demo", command.id())).contains(done);
        assertThat(operations.find("foreign", command.id())).isEmpty();
    }

    @Test void rolledBackRegistrationNeverReachesErp() {
        var command = command(advance());
        assertThatThrownBy(() -> tx().executeWithoutResult(transaction -> {
            execution.register(command, target(), command.createdAt()); throw new IllegalStateException("synthetic local rollback");
        })).hasMessageContaining("synthetic local rollback");
        assertThat(operations.find("demo", command.id())).isEmpty(); worker.poll(); assertThat(WRITES.get()).isZero();
        assertThatThrownBy(() -> execution.register(command, target(), now())).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(() -> tx().executeWithoutResult(transaction -> worker.poll())).hasMessageContaining("outside a database transaction");
    }

    @Test void concurrentWorkersSendOnceAndNetworkWaitDoesNotHoldApplicationOrBusinessLock() throws Exception {
        var advance = advance(); var job = register(command(advance)); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        RESPONDER.set((path, request) -> { entered.countDown(); await(release); return posted(job.input().command(), 1); });
        var threads = Executors.newFixedThreadPool(3);
        try {
            var first = threads.submit(worker::poll); assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            threads.submit(() -> tx().executeWithoutResult(transaction -> advances.lock("demo", advance.id()))).get(1, TimeUnit.SECONDS);
            threads.submit(worker::poll).get(1, TimeUnit.SECONDS); assertThat(WRITES.get()).isEqualTo(1);
            release.countDown(); first.get(5, TimeUnit.SECONDS); assertThat(reload(job).status()).isEqualTo(VoucherOperation.Status.POSTED);
        } finally { release.countDown(); threads.shutdownNow(); }
    }

    @Test void timedOutWriteQueriesSameCommandWithoutAutomaticResendEvenWhenErpReportsNotFound() throws Exception {
        var job = register(command(advance())); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        RESPONDER.set((path, request) -> { entered.countDown(); await(release); return posted(job.input().command(), 1); });
        var threads = Executors.newSingleThreadExecutor();
        try {
            var first = threads.submit(worker::poll); assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue(); first.get(5, TimeUnit.SECONDS);
            assertThat(reload(job).failure()).isEqualTo(VoucherOperation.Failure.TIMEOUT);
        } finally { release.countDown(); threads.shutdownNow(); }
        RESPONDER.set((path, request) -> {
            assertThat(path).endsWith("voucher-query"); assertThat(request.at("/data/operationId").asText()).isEqualTo(job.input().command().id().toString());
            assertThat(request.at("/data/commandDigest").asText()).isEqualTo(job.input().command().digest()); return notFound(job.input().command());
        });
        recheck(job); worker.poll(); assertThat(reload(job).status()).isEqualTo(VoucherOperation.Status.NOT_FOUND);
        worker.poll(); assertThat(WRITES.get()).isEqualTo(1); assertThat(QUERIES.get()).isEqualTo(1); assertThat(LAST_KEY.get()).isNull();
        tx().executeWithoutResult(transaction -> operations.update(reload(job).retryNotFound(now())));
        RESPONDER.set((path, request) -> posted(job.input().command(), 1)); worker.poll();
        assertThat(reload(job).status()).isEqualTo(VoucherOperation.Status.POSTED); assertThat(WRITES.get()).isEqualTo(2);
        assertThat(LAST_KEY.get()).isEqualTo(job.input().command().id().toString());
    }

    @Test void expiredLeaseRecoversByQueryAndLateWorkerCannotReplaceNewFact() {
        var job = register(command(advance())); var stale = execution.claim("demo", job.input().command().id(), now().minusSeconds(20));
        assertThat(stale.status()).isEqualTo(VoucherOperation.Status.POSTING);
        worker.poll(); assertThat(reload(job).failure()).isEqualTo(VoucherOperation.Failure.LEASE_EXPIRED); assertThat(WRITES.get()).isZero();
        RESPONDER.set((path, request) -> posted(job.input().command(), 2)); worker.poll(); var recovered = reload(job);
        assertThat(recovered.status()).isEqualTo(VoucherOperation.Status.POSTED); assertThat(QUERIES.get()).isEqualTo(1);
        execution.finish(stale, new FinanceResult.Success<>(posted(job.input().command(), 1)), now());
        assertThat(reload(job)).isEqualTo(recovered); assertThat(revisions(job)).containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    @Test void eventConsumerFailureRollsBackConfirmationAndRecoversAlreadyPostedVoucherByQuery() {
        var job = register(command(advance())); listener.reject.set(true); worker.poll();
        var unknown = reload(job); assertThat(unknown.status()).isEqualTo(VoucherOperation.Status.UNKNOWN);
        assertThat(unknown.failure()).isEqualTo(VoucherOperation.Failure.INTERNAL_ERROR); assertThat(unknown.observation()).isNull();
        assertThat(revisions(job)).containsExactly(1L, 2L, 3L);
        RESPONDER.set((path, request) -> posted(job.input().command(), 1)); recheck(job); worker.poll();
        assertThat(reload(job).status()).isEqualTo(VoucherOperation.Status.POSTED); assertThat(WRITES.get()).isEqualTo(1); assertThat(QUERIES.get()).isEqualTo(1);
    }

    @Test void persistedHighWaterAndOriginalPostingSurviveContradictoryAndOlderReceipts() {
        var job = register(command(advance())); RESPONDER.set((path, request) -> posted(job.input().command(), 3)); worker.poll();
        var accepted = reload(job).observation();
        RESPONDER.set((path, request) -> pending(job.input().command(), 8)); recheck(job); worker.poll();
        var disputed = reload(job); assertThat(disputed.status()).isEqualTo(VoucherOperation.Status.RECONCILING);
        assertThat(disputed.observation()).isEqualTo(accepted); assertThat(disputed.highestRevision()).isEqualTo(8); assertThat(disputed.usablePosted()).isFalse();
        assertThat(new JdbcVoucherOperationRepository(jdbc, json).find("demo", job.input().command().id())).contains(disputed);
        RESPONDER.set((path, request) -> accepted); recheck(job); worker.poll();
        assertThat(reload(job).highestRevision()).isEqualTo(8); assertThat(reload(job).failure()).isEqualTo(VoucherOperation.Failure.STALE_OBSERVATION);
        assertThat(reload(job).observation()).isEqualTo(accepted); assertThat(reload(job).status()).isEqualTo(VoucherOperation.Status.RECONCILING);
    }

    @Test void approvalChangedBeforeDispatchVoidsCommandAndRegistrationRejectsForgedSource() {
        var advance = advance(); var job = register(command(advance)); var original = job.input().command();
        jdbc.update("UPDATE approval_application SET status='REVOKED',version=version+1 WHERE tenant_id='demo' AND id=?", advance.applicationId().toString());
        worker.poll(); assertThat(reload(job).status()).isEqualTo(VoucherOperation.Status.VOIDED); assertThat(WRITES.get()).isZero();
        var unregistered = advance(); var command = command(unregistered);
        jdbc.update("UPDATE approval_application SET payload_json='{}' WHERE tenant_id='demo' AND id=?", unregistered.applicationId().toString());
        assertThatThrownBy(() -> register(command)).isInstanceOf(DomainException.class); assertThat(operations.find("demo", command.id())).isEmpty();
        assertThat(register(original).status()).isEqualTo(VoucherOperation.Status.VOIDED);
    }

    @Test void changedDestinationCannotSendAndImmutableCommandUniquenessAndSnapshotIntegrityRemainEnforced() {
        var advance = advance(); var job = register(command(advance)); configuration.getTenants().get("demo").setEndpoint(ENDPOINT + "/changed");
        worker.poll(); var unknown = reload(job); assertThat(unknown.failure()).isEqualTo(VoucherOperation.Failure.TARGET_CHANGED);
        assertThat(WRITES.get()).isZero(); assertThat(QUERIES.get()).isZero();
        assertThatThrownBy(() -> register(command(advance))).isInstanceOf(DomainException.class);
        var duplicate = VoucherOperation.queue(new VoucherOperation.Input(command(advance), job.input().targetDigest()), now());
        assertThatThrownBy(() -> tx().executeWithoutResult(transaction -> operations.create(duplicate))).isInstanceOf(DataIntegrityViolationException.class);
        var altered = new VoucherOperation(new VoucherOperation.Input(job.input().command(), "b".repeat(64)), unknown.version() + 1, unknown.status(), unknown.attempts(),
                unknown.createdAt(), unknown.updatedAt(), unknown.nextAttemptAt(), null, unknown.observation(), null, unknown.highestRevision(), unknown.failure());
        assertThatThrownBy(() -> tx().executeWithoutResult(transaction -> operations.update(altered))).isInstanceOf(DomainException.class);
        assertThat(reload(job)).isEqualTo(unknown);
        jdbc.update("UPDATE voucher_operation SET command_digest=? WHERE tenant_id='demo' AND id=?", "c".repeat(64), job.input().command().id().toString());
        assertThatThrownBy(() -> reload(job)).isInstanceOf(IllegalStateException.class).hasMessageContaining("inconsistent");
    }

    @Test void preparedAccountingEvidenceRegistersOneOriginalVoucherBeforeAnyPosting() {
        var advance = advance(); var preparation = enqueue(advance); RESPONDER.set(VoucherOperationIntegrationTest::accountingEvidence);
        assertThat(enqueue(advance)).isEqualTo(preparation); assertThat(QUERIES.get()).isZero();
        preparationWorker.poll(); var ready = reload(preparation);
        assertThat(ready.status()).isEqualTo(VoucherPreparation.Status.READY); assertThat(QUERIES.get()).isEqualTo(2); assertThat(WRITES.get()).isZero();
        var operation = operations.find("demo", ready.result().operationId()).orElseThrow();
        assertThat(operation.status()).isEqualTo(VoucherOperation.Status.QUEUED); assertThat(operation.input().command().id()).isEqualTo(preparation.input().id());
        assertThat(operation.input().command().binding().businessVersion()).isEqualTo(advance.version());
        assertThat(operation.input().command().accountingDate()).isEqualTo(advance.approval().approvedAt().atZone(ZoneOffset.UTC).toLocalDate());
        assertThat(preparations.find("foreign", preparation.input().id())).isEmpty();
        assertThatThrownBy(() -> preparationService.retry("demo", advance.applicationId(), 5, "finance", now())).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo("VOUCHER_OPERATION_EXISTS"));
        worker.poll(); assertThat(reload(operation).status()).isEqualTo(VoucherOperation.Status.POSTED); assertThat(WRITES.get()).isEqualTo(1);
    }

    @Test void missingConfigurationBecomesVisibleAndExplicitRetryCreatesANewReadAttempt() {
        var advance = advance(); configuration.setEnabled(false); var first = enqueue(advance); preparationWorker.poll();
        assertThat(reload(first).result().code()).isEqualTo("NOT_CONFIGURED"); assertThat(QUERIES.get()).isZero();
        configuration.setEnabled(true); RESPONDER.set(VoucherOperationIntegrationTest::accountingEvidence);
        assertThatThrownBy(() -> preparationService.retry("demo", advance.applicationId(), 4, "finance", now())).isInstanceOf(DomainException.class);
        var second = preparationService.retry("demo", advance.applicationId(), 5, "finance", now());
        assertThat(second.input().id()).isNotEqualTo(first.input().id()); assertThat(second.input().attempt()).isEqualTo(2);
        assertThat(second.input().targetDigest()).isEqualTo(target()); assertThat(reload(first).input().targetDigest()).isNull();
        assertThat(preparationService.retry("demo", advance.applicationId(), 5, "finance", now())).isEqualTo(second);
        preparationWorker.poll(); assertThat(reload(second).status()).isEqualTo(VoucherPreparation.Status.READY);
        assertThat(reload(first).status()).isEqualTo(VoucherPreparation.Status.UNAVAILABLE); assertThat(WRITES.get()).isZero();
    }

    @Test void closedPeriodAndMissingMappingBlockWithoutInventedDefaultsOrVoucherCommands() {
        var advance = advance(); var period = enqueue(advance);
        RESPONDER.set((path, request) -> new FinanceResult.Rejected<>(FinanceResult.Reason.ACCOUNTING_PERIOD_CLOSED)); preparationWorker.poll();
        assertThat(reload(period).status()).isEqualTo(VoucherPreparation.Status.BLOCKED); assertThat(reload(period).result().code()).isEqualTo("ACCOUNTING_PERIOD_CLOSED");
        assertThat(QUERIES.get()).isEqualTo(1); assertThat(operations.find("demo", period.input().id())).isEmpty();
        var mapping = preparationService.retry("demo", advance.applicationId(), 5, "finance", now());
        RESPONDER.set((path, request) -> path.endsWith("account-mapping") ? new FinanceResult.Rejected<>(FinanceResult.Reason.ACCOUNT_MAPPING_UNAVAILABLE) : accountingEvidence(path, request));
        preparationWorker.poll(); assertThat(reload(mapping).result().code()).isEqualTo("ACCOUNT_MAPPING_UNAVAILABLE");
        assertThat(QUERIES.get()).isEqualTo(3); assertThat(operations.find("demo", mapping.input().id())).isEmpty(); assertThat(WRITES.get()).isZero();
    }

    @Test void changedApprovalDuringReadWaitVoidsPreparationWithoutHoldingTheApprovalLock() throws Exception {
        var advance = advance(); var preparation = enqueue(advance); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        RESPONDER.set((path, request) -> { if (path.endsWith("accounting-period")) { entered.countDown(); await(release); } return accountingEvidence(path, request); });
        var threads = Executors.newFixedThreadPool(3);
        try {
            var first = threads.submit(preparationWorker::poll); assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            threads.submit(preparationWorker::poll).get(1, TimeUnit.SECONDS); assertThat(QUERIES.get()).isEqualTo(1);
            threads.submit(() -> tx().executeWithoutResult(transaction -> {
                advances.lock("demo", advance.id()); jdbc.update("UPDATE approval_application SET status='REVOKED',version=version+1 WHERE tenant_id='demo' AND id=?", advance.applicationId().toString());
            })).get(1, TimeUnit.SECONDS);
            release.countDown(); first.get(5, TimeUnit.SECONDS);
            assertThat(reload(preparation).status()).isEqualTo(VoucherPreparation.Status.VOIDED);
            assertThat(operations.find("demo", preparation.input().id())).isEmpty(); assertThat(WRITES.get()).isZero();
        } finally { release.countDown(); threads.shutdownNow(); }
    }

    @Test void expiredPreparationLeaseRejectsLateEvidenceAndRequiresAnExplicitNewReadAttempt() {
        var advance = advance(); var preparation = enqueue(advance); var work = preparationService.claim("demo", preparation.input().id(), now());
        var command = prepared(work); var until = work.preparation().leaseUntil();
        assertThat(preparationService.claim("demo", preparation.input().id(), until)).isNull();
        assertThat(reload(preparation).result().code()).isEqualTo("LEASE_EXPIRED");
        preparationService.finish(work.preparation(), command, null, until.plusSeconds(1));
        assertThat(operations.find("demo", command.id())).isEmpty(); assertThat(QUERIES.get()).isZero();
    }

    @Test void preparationAuditFailureRollsBackNewVoucherAndKeepsTheOriginalClaim() {
        var preparation = enqueue(advance()); var work = preparationService.claim("demo", preparation.input().id(), now()); var command = prepared(work);
        jdbc.update("INSERT INTO voucher_preparation_revision(tenant_id,preparation_id,version,state_json) VALUES('demo',?,3,'{}')", preparation.input().id().toString());
        assertThatThrownBy(() -> preparationService.finish(work.preparation(), command, null, now())).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(operations.find("demo", command.id())).isEmpty(); assertThat(reload(preparation).status()).isEqualTo(VoucherPreparation.Status.RUNNING);
        jdbc.update("DELETE FROM voucher_preparation_revision WHERE tenant_id='demo' AND preparation_id=? AND version=3", preparation.input().id().toString());
        preparationService.finish(work.preparation(), command, null, now());
        assertThat(reload(preparation).status()).isEqualTo(VoucherPreparation.Status.READY); assertThat(operations.find("demo", command.id())).isPresent();
        assertThat(WRITES.get()).isZero();
    }

    @Test void rolledBackFinalApprovalPreparationAndChangedTargetMakeNoExternalRequests() {
        var advance = advance(); var app = applications.findById("demo", advance.applicationId()).orElseThrow();
        assertThatThrownBy(() -> tx().executeWithoutResult(transaction -> { preparationService.approved(app, "manager"); throw new IllegalStateException("synthetic rollback"); })).hasMessageContaining("synthetic rollback");
        assertThat(preparations.latest(sources.reference(app))).isEmpty(); preparationWorker.poll(); assertThat(QUERIES.get()).isZero();
        var preparation = enqueue(advance); configuration.getTenants().get("demo").setEndpoint(ENDPOINT + "/other"); preparationWorker.poll();
        assertThat(reload(preparation).result().code()).isEqualTo("TARGET_CHANGED"); assertThat(QUERIES.get()).isZero(); assertThat(WRITES.get()).isZero();
    }

    private AdvanceRequest advance() {
        var at = now().minusSeconds(60); var id = UUID.randomUUID(); fixtures.add(id);
        var app = Application.draftBusiness(UUID.randomUUID(), "demo", "SYNTHETIC-" + id, "fixture", 1, "alice", "合成凭证", Map.of(), null, null, null,
                new BusinessReference(BusinessReference.Type.ADVANCE_REQUEST, id));
        var request = AdvanceRequest.draft(id, "demo", app.id(), "alice", new AdvanceRequestContent(ENTITY, "合成借款", "合成用途", new Money(new BigDecimal("100.00"), "CNY"), at.atZone(ZoneOffset.UTC).toLocalDate().plusDays(10)));
        tx().executeWithoutResult(transaction -> {
            applications.save(app); advances.create(request, "alice");
            var catalog = new FinanceCatalog("alice", "v1", at.plusSeconds(600), List.of(new FinanceCatalog.LegalEntity(ENTITY, "法人", "CNY", false, "v1", "UTC")), List.of(), List.of(), List.of(), List.of());
            var account = new EmployeeAccountSnapshot(ENTITY, "alice", "synthetic-account", "****1234", "a".repeat(64), "v1");
            request.freeze(1, 1, catalog, new EmployeeAccountPort.Account(account, at.plusSeconds(600)),
                    new InitiatorContext(UUID.randomUUID(), UUID.randomUUID(), "alice", 1, ENTITY, "法人", UUID.randomUUID(), "部门", UUID.randomUUID(), "岗位"), at);
            advances.update(request, 1, "alice", "SYNTHETIC_SUBMIT"); request.approve(2, 1, 5, "manager", at); advances.update(request, 2, "manager", "SYNTHETIC_APPROVE");
            applications.update(Application.restore(app.id(), "demo", app.businessNo(), app.processKey(), 1, "alice", app.title(), AdvanceRequestFormContract.submittedPayload(request.currentRound()),
                    ApplicationStatus.APPROVED, 1, 5, null, null, NotificationTexts.EMPTY, app.businessReference()), 1);
        }); return request;
    }
    private VoucherCommand command(AdvanceRequest advance) {
        var plan = VoucherSource.advance(applications.findById("demo", advance.applicationId()).orElseThrow(), advance); var at = now().minusSeconds(30); var date = plan.accountingDate();
        var period = new AccountingPeriodPort.OpenPeriod(plan.periodRequest(), "synthetic-period", "period-v1", date.minusDays(30), date.plusDays(30), at, at.plusSeconds(600));
        var mapping = new AccountMappingPort.Mapping(plan.mappingRequest(), "map-v1", at, at.plusSeconds(600), plan.mappingRequest().keys().stream().map(key -> new AccountMappingPort.Entry(key, "synthetic-" + key.role().name())).toList());
        return plan.prepare(UUID.randomUUID(), period, mapping, at);
    }
    private VoucherOperation register(VoucherCommand command) { return tx().execute(transaction -> execution.register(command, target(), command.createdAt())); }
    private VoucherOperation reload(VoucherOperation value) { return operations.find("demo", value.input().command().id()).orElseThrow(); }
    private VoucherPreparation reload(VoucherPreparation value) { return preparations.find("demo", value.input().id()).orElseThrow(); }
    private VoucherPreparation enqueue(AdvanceRequest advance) {
        var app = applications.findById("demo", advance.applicationId()).orElseThrow();
        tx().executeWithoutResult(transaction -> preparationService.approved(app, "manager"));
        return preparations.latest(sources.reference(app)).orElseThrow();
    }
    private static VoucherCommand prepared(VoucherPreparationService.Work work) {
        var plan = work.source(); var at = now(); var date = plan.accountingDate();
        return plan.prepare(work.preparation().input().id(), new AccountingPeriodPort.OpenPeriod(plan.periodRequest(), "synthetic-period", "v1", date.minusDays(30), date.plusDays(30), at, at.plusSeconds(300)),
                new AccountMappingPort.Mapping(plan.mappingRequest(), "v1", at, at.plusSeconds(300), plan.mappingRequest().keys().stream().map(key -> new AccountMappingPort.Entry(key, "synthetic-" + key.role())).toList()), at);
    }
    private static Object accountingEvidence(String path, JsonNode envelope) {
        var data = envelope.path("data"); var at = now();
        if (path.endsWith("accounting-period")) {
            var request = wire.read(data.toString(), AccountingPeriodPort.Request.class); var date = request.accountingDate();
            return new AccountingPeriodPort.OpenPeriod(request, "synthetic-period", "v1", date.minusDays(30), date.plusDays(30), at, at.plusSeconds(300));
        }
        if (path.endsWith("account-mapping")) {
            var request = wire.read(data.toString(), AccountMappingPort.Request.class);
            return new AccountMappingPort.Mapping(request, "v1", at, at.plusSeconds(300), request.keys().stream().map(key -> new AccountMappingPort.Entry(key, "synthetic-" + key.role())).toList());
        }
        return posted(wire.read(data.path("command").toString(), VoucherCommand.class), 1);
    }
    private void recheck(VoucherOperation value) { tx().executeWithoutResult(transaction -> operations.update(reload(value).requestQuery(now()))); }
    private List<Long> revisions(VoucherOperation value) { return jdbc.queryForList("SELECT version FROM voucher_operation_revision WHERE tenant_id='demo' AND operation_id=? ORDER BY version", Long.class, value.input().command().id().toString()); }
    private String target() { return configuration.destination("demo").orElseThrow().digest("demo"); }
    private TransactionTemplate tx() { return new TransactionTemplate(transactions); }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private static VoucherObservation posted(VoucherCommand command, long revision) { return new VoucherObservation(command.id(), command.digest(), VoucherObservation.Status.POSTED, revision, now(), "synthetic-posting", "synthetic-voucher", command.period().periodReference(), command.accountingDate(), command.totals().gross(), command.totals().gross(), command.createdAt(), null); }
    private static VoucherObservation pending(VoucherCommand command, long revision) { return new VoucherObservation(command.id(), command.digest(), VoucherObservation.Status.PENDING, revision, now(), "synthetic-posting", null, null, null, null, null, null, null); }
    private static VoucherObservation notFound(VoucherCommand command) { return new VoucherObservation(command.id(), command.digest(), VoucherObservation.Status.NOT_FOUND, 0L, now(), null, null, null, null, null, null, null, null); }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic ERP wait expired"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Synthetic ERP interrupted"); }
    }
    private static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(HTTP_THREADS);
            server.createContext("/finance/", exchange -> {
                var path = exchange.getRequestURI().getPath(); if (path.endsWith("voucher-command")) WRITES.incrementAndGet(); else QUERIES.incrementAndGet();
                LAST_KEY.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                var request = wire.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonNode.class);
                var value = RESPONDER.get().apply(path, request);
                var envelope = value instanceof FinanceResult.Rejected<?> rejected
                        ? Map.of("contractVersion", 1, "tenantId", request.path("tenantId").asText(), "requestId", request.path("requestId").asText(), "outcome", "REJECTED", "reason", rejected.reason().name())
                        : Map.of("contractVersion", 1, "tenantId", request.path("tenantId").asText(), "requestId", request.path("requestId").asText(), "outcome", "SUCCESS", "data", value);
                byte[] body = wire.write(envelope).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, 0);
                try { exchange.getResponseBody().write(body); } finally { exchange.close(); }
            }); server.start(); return server;
        } catch (Exception failed) { throw new IllegalStateException(failed); }
    }

    /**
     * 模拟业务消费者在 ERP 已过账后本地落账失败，验证真实事务代理的回滚边界。
     * @author owlzhangfq@gmail.com
     */
    static class FailureListener {
        private final AtomicBoolean reject = new AtomicBoolean();
        @EventListener public void changed(VoucherOperationChanged event) {
            if (event.current().status() == VoucherOperation.Status.POSTED && reject.compareAndSet(true, false)) throw new IllegalStateException("Synthetic settlement failure");
        }
    }
    /**
     * 仅本测试上下文启用故障消费者。
     * @author owlzhangfq@gmail.com
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class ListenerConfiguration { @Bean FailureListener voucherFailureListener() { return new FailureListener(); } }
}
