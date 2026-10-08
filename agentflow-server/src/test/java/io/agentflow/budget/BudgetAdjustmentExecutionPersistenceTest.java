package io.agentflow.budget;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.agentflow.approval.JdbcApplicationRepository;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.FinanceJsonConfiguration;
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.finance.FinanceGatewayConfiguration;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 真实 H2/PostgreSQL 验证预算授权的单次证据、并发独占、租约恢复和安全结束原子性。
 *
 * @author owlzhangfq@gmail.com
 */
class BudgetAdjustmentExecutionPersistenceTest {
    private final Instant now = Instant.now().minusSeconds(20).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    private final String tenant = "budget-execution-" + UUID.randomUUID();
    private final UUID entity = UUID.randomUUID();
    private final JsonUtil json = new JsonUtil(new ObjectMapper().registerModule(new JavaTimeModule())
            .registerModule(new FinanceJsonConfiguration().financeMoneyModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    private DriverManagerDataSource dataSource;
    private String schema;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private DataSourceTransactionManager manager;
    private JdbcBudgetAdjustmentRepository requests;
    private ApprovedBudgetAdjustmentSources sources;
    private JdbcBudgetAdjustmentReviewRepository reviews;
    private JdbcBudgetAdjustmentOperationRepository operations;
    private FinanceGatewayConfiguration configuration;
    private final AtomicBoolean eligible = new AtomicBoolean(true);
    private BudgetAdjustmentExecutionService execution;
    private BudgetAdjustmentReviewService reviewService;

    @BeforeEach void database() { database(null); }
    private void database(String target) {
        dataSource = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_BUDGET_EXECUTION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_BUDGET_EXECUTION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_BUDGET_EXECUTION_PASSWORD", ""));
        schema = "budget_execution_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(dataSource).execute("CREATE SCHEMA \"" + schema + "\""); dataSource.setSchema(schema);
        var flyway = Flyway.configure().dataSource(dataSource).defaultSchema(schema); if (target != null) flyway.target(target);
        flyway.load().migrate(); jdbc = new JdbcTemplate(dataSource); manager = new DataSourceTransactionManager(dataSource); tx = new TransactionTemplate(manager);
        requests = new JdbcBudgetAdjustmentRepository(
                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                (jdbc).getDataSource(),
                                io.agentflow.budget.mapper.BudgetAdjustmentRepositoryMapper.class), json); sources = new ApprovedBudgetAdjustmentSources(new JdbcApplicationRepository(
                                io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                        (jdbc).getDataSource(),
                                        io.agentflow.approval.mapper.ApplicationRepositoryMapper
                                                .class), json), requests);
        reviews = new JdbcBudgetAdjustmentReviewRepository(
                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                (jdbc).getDataSource(),
                                io.agentflow.budget.mapper.BudgetAdjustmentReviewRepositoryMapper
                                        .class), json, sources); operations = new JdbcBudgetAdjustmentOperationRepository(
                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                (jdbc).getDataSource(),
                                io.agentflow.budget.mapper.BudgetAdjustmentOperationRepositoryMapper
                                        .class), json, sources, reviews);
        configuration = new FinanceGatewayConfiguration(); configuration.setEnabled(true);
        var financeTarget = new FinanceGatewayConfiguration.Target(); financeTarget.setEndpoint("https://budget-fixture.example/finance"); financeTarget.setToken("synthetic-token");
        configuration.getTenants().put(tenant, financeTarget);
        var personnel = mock(PaymentPersonnel.class);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            if (!eligible.get()) throw new DomainException("PAYMENT_ACTOR_UNAVAILABLE", "Synthetic inactive finance appointment"); return null;
        }).when(personnel).requireEligible(anyString(), anyString(), any());
        execution = proxy(new BudgetAdjustmentExecutionService(sources, operations, personnel, configuration, mock(org.springframework.context.ApplicationEventPublisher.class), 30));
        reviewService = proxy(new BudgetAdjustmentReviewService(sources, reviews, operations, execution, mock(org.springframework.context.ApplicationEventPublisher.class), 30));
    }

    @Test void originalApprovalReadyRevisionAndSingleConsumptionSurviveRepositoryReconstruction() {
        var source = approved(); var ready = ready(source, "finance", 2);
        String origin = UUID.randomUUID().toString(); BudgetAdjustmentOperation queued;
        try (var scope = new io.agentflow.observability.DiagnosticContext(origin, tenant).open()) { queued = authorize(ready); }
        var reopenedReviews = new JdbcBudgetAdjustmentReviewRepository(
                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                (jdbc).getDataSource(),
                                io.agentflow.budget.mapper.BudgetAdjustmentReviewRepositoryMapper
                                        .class), json, sources);
        var reopened = new JdbcBudgetAdjustmentOperationRepository(
                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                (jdbc).getDataSource(),
                                io.agentflow.budget.mapper.BudgetAdjustmentOperationRepositoryMapper
                                        .class), json, sources, reopenedReviews);
        assertThat(reopened.find(tenant, queued.command().id())).contains(queued);
        assertThat(reopened.find("foreign", queued.command().id())).isEmpty();
        assertThat(reopenedReviews.find(tenant, ready.input().id()).orElseThrow().supports(queued.command())).isTrue();
        assertThat(reopenedReviews.revision(tenant, ready.input().id(), 3)).contains(ready);
        assertThat(reopened.activeForRequest(tenant, source.requestId())).contains(queued);
        assertThat(reopened.due(queued.createdAt())).singleElement().satisfies(candidate -> {
            assertThat(candidate.tenantId()).isEqualTo(tenant);
            assertThat(candidate.id()).isEqualTo(queued.command().id());
            assertThat(candidate.traceId()).isEqualTo(origin);
            assertThat(candidate.businessNo()).isEqualTo(new JdbcApplicationRepository(
                                                            io.agentflow.mybatis.MyBatisTestSupport
                                                                    .mapper(
                                                                            (jdbc).getDataSource(),
                                                                            io.agentflow.approval
                                                                                    .mapper
                                                                                    .ApplicationRepositoryMapper
                                                                                    .class), json).findById(tenant, queued.command().source().applicationId()).orElseThrow().businessNo());
            assertThat(candidate.processInstanceId()).isNull();
        });
        assertThat(count("budget_adjustment_review_revision")).isEqualTo(4); assertThat(count("budget_adjustment_operation_revision")).isEqualTo(1);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> operations.create(queued, ready.input().id()))).isInstanceOf(DomainException.class);
        assertThat(count("budget_adjustment_operation")).isEqualTo(1);
    }

    @Test void staleReviewChangedActualApprovalAndCorruptedOwnerCannotAuthorize() {
        var source = approved(); var first = ready(source, "finance", 2); var second = ready(source, "finance", 6);
        assertThatThrownBy(() -> authorize(first)).isInstanceOf(DomainException.class);
        jdbc.update("UPDATE approval_application SET payload_json='{}' WHERE tenant_id=? AND id=?", tenant, source.applicationId().toString());
        assertThatThrownBy(() -> authorize(second)).isInstanceOf(DomainException.class);
        jdbc.update("UPDATE approval_application SET payload_json=? WHERE tenant_id=? AND id=?", json.write(BudgetAdjustmentFormContract.submittedPayload(source.round())), tenant, source.applicationId().toString());
        jdbc.update("UPDATE budget_adjustment_review SET requested_at=? WHERE tenant_id=? AND id=?", java.sql.Timestamp.from(now), tenant, second.input().id().toString());
        assertThatThrownBy(() -> reviews.find(tenant, second.input().id())).isInstanceOf(IllegalStateException.class);
        assertThat(count("budget_adjustment_operation")).isZero();
    }

    @Test void originalReadyEvidenceCannotReplaceMissingCurrentConsumptionProof() {
        var ready = ready(approved(), "finance", 2); var queued = authorize(ready);
        jdbc.update(
                "UPDATE budget_adjustment_review SET"
                    + " state_json=?,version=3,status='READY',updated_at=?,consumed_operation_id=NULL"
                    + " WHERE tenant_id=? AND id=?",
                json.write(ready), java.sql.Timestamp.from(ready.updatedAt()), tenant, ready.input().id().toString());
        assertThatThrownBy(() -> operations.find(tenant, queued.command().id())).isInstanceOf(IllegalStateException.class);
    }

    @Test void twoFinanceActorsCannotAuthorizeTheSameApprovedBudgetTwice() throws Exception {
        var source = approved(); var first = ready(source, "finance", 2); var second = ready(source, "finance-2", 2);
        assertThat(race(() -> authorize(first), () -> authorize(second))).containsExactlyInAnyOrder("SAVED", "BUDGET_ADJUSTMENT_ALREADY_AUTHORIZED");
        assertThat(count("budget_adjustment_operation")).isEqualTo(1); assertThat(count("budget_adjustment_operation_revision")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                                "SELECT COUNT(*) FROM budget_adjustment_review WHERE tenant_id=?"
                                        + " AND status='CONSUMED'", Integer.class, tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                                "SELECT COUNT(*) FROM budget_adjustment_review WHERE tenant_id=?"
                                        + " AND status='READY'", Integer.class, tenant)).isEqualTo(1);
    }

    @Test void competingClaimsPreserveOneLeaseAndCannotSkipExecutionIntoSuccess() throws Exception {
        var queued = authorize(ready(approved(), "finance", 2)); var running = queued.claim(now.plusSeconds(6), Duration.ofSeconds(10));
        assertThat(race(() -> save(running), () -> save(running))).containsExactlyInAnyOrder("SAVED", "CONCURRENCY_CONFLICT");
        assertThat(operations.find(tenant, queued.command().id())).contains(running);
        var another = authorize(ready(approved(), "finance", 2)); var receipt = applied(another.command(), now.plusSeconds(7));
        var forged = new BudgetAdjustmentOperation(another.command(), 2, BudgetAdjustmentOperation.Status.APPLIED, 1, another.createdAt(), now.plusSeconds(7), null, null, receipt, null, null);
        assertThatThrownBy(() -> save(forged)).isInstanceOf(DomainException.class);
        assertThat(operations.find(tenant, another.command().id())).contains(another);
    }

    @Test void lostLeaseAndLateReceiptRecoverOnlyThroughOriginalQuery() {
        var queued = authorize(ready(approved(), "finance", 2)); var running = queued.claim(now.plusSeconds(6), Duration.ofSeconds(10)); save(running);
        var expired = running.expire(now.plusSeconds(16)); save(expired);
        assertThatThrownBy(() -> save(running.complete(new FinanceResult.Success<>(applied(running.command(), now.plusSeconds(7))), now.plusSeconds(7)))).isInstanceOf(DomainException.class);
        var query = expired.claim(now.plusSeconds(400), Duration.ofSeconds(10)); save(query);
        assertThat(query.status()).isEqualTo(BudgetAdjustmentOperation.Status.QUERYING);
        var done = query.complete(new FinanceResult.Success<>(applied(query.command(), now.plusSeconds(401))), now.plusSeconds(401)); save(done);
        assertThat(operations.find(tenant, query.command().id())).contains(done);
        assertThat(operations.revision(tenant, query.command().id(), running.version())).contains(running);
        assertThat(operations.activeForRequest(tenant, query.command().source().requestId())).contains(done);
    }

    @Test void evidenceConsumptionAppendFailureRollsBackNewCommandAndQueue() {
        var ready = ready(approved(), "finance", 2); var command = command(ready); var consumed = ready.consume(command, command.authorizedAt());
        jdbc.update(
                "INSERT INTO"
                    + " budget_adjustment_review_revision(tenant_id,review_id,version,state_json)"
                    + " VALUES(?,?,4,?)", tenant, ready.input().id().toString(), json.write(consumed));
        var queued = BudgetAdjustmentOperation.queue(command, command.authorizedAt());
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> operations.create(queued, ready.input().id()))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(reviews.find(tenant, ready.input().id())).contains(ready);
        assertThat(count("budget_adjustment_operation")).isZero(); assertThat(count("budget_adjustment_operation_revision")).isZero();
    }

    @Test void safeRetirementAndReplacementKeepOriginalCommandAndPreventResurrection() {
        var source = approved(); var queued = authorize(ready(source, "finance", 2));
        tx.executeWithoutResult(status -> {
            var stopped = queued.voidBeforeSend(now.plusSeconds(6)); operations.update(stopped);
            operations.retire(tenant, BudgetAdjustmentRetirement.from(stopped, "finance", now.plusSeconds(7)));
        });
        assertThat(operations.activeForRequest(tenant, source.requestId())).isEmpty();
        var retired = operations.find(tenant, queued.command().id()).orElseThrow();
        assertThat(operations.retirement(tenant, queued.command().id()).orElseThrow().matches(retired)).isTrue();
        assertThatThrownBy(() -> save(queued.claim(now.plusSeconds(6), Duration.ofSeconds(10)))).isInstanceOf(DomainException.class);
        var next = authorize(ready(source, "finance", 8));
        assertThat(operations.list(tenant, source.requestId(), null, 10)).containsExactlyInAnyOrder(retired, next);
        assertThat(operations.activeForRequest(tenant, source.requestId())).contains(next);
        assertThat(operations.revision(tenant, queued.command().id(), 1)).contains(queued);
        assertThat(reviews.find(tenant, readyId(queued)).orElseThrow().supports(queued.command())).isTrue();
    }

    @Test void claimAndSafeStopRaceCannotBothSucceedAndRollbackKeepsOriginalActive() throws Exception {
        var queued = authorize(ready(approved(), "finance", 2));
        Callable<Object> stop = () -> {
            tx.executeWithoutResult(status -> {
                var stopped = queued.voidBeforeSend(now.plusSeconds(6)); operations.update(stopped);
                operations.retire(tenant, BudgetAdjustmentRetirement.from(stopped, "finance", now.plusSeconds(7)));
            }); return null;
        };
        assertThat(race(stop, () -> save(queued.claim(now.plusSeconds(6), Duration.ofSeconds(10))))).containsExactlyInAnyOrder("SAVED", "CONCURRENCY_CONFLICT");
        var result = operations.find(tenant, queued.command().id()).orElseThrow();
        assertThat(result.status()).isIn(BudgetAdjustmentOperation.Status.VOIDED, BudgetAdjustmentOperation.Status.EXECUTING);
        assertThat(operations.retirement(tenant, queued.command().id()).isPresent()).isEqualTo(result.status() == BudgetAdjustmentOperation.Status.VOIDED);
        var second = authorize(ready(approved(), "finance", 2));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            var stopped = second.voidBeforeSend(now.plusSeconds(6)); operations.update(stopped);
            operations.retire(tenant, BudgetAdjustmentRetirement.from(stopped, "finance", now.plusSeconds(7)));
            throw new IllegalStateException("Synthetic transaction failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(operations.find(tenant, second.command().id())).contains(second);
        assertThat(operations.retirement(tenant, second.command().id())).isEmpty();
    }

    @Test void version76UpgradeKeepsExistingBudgetApprovalAndRejectsForeignEvidenceBindings() {
        database("76"); var source = approved();
        var applications = jdbc.queryForList("SELECT * FROM approval_application"); var original = jdbc.queryForList("SELECT * FROM budget_adjustment_revision ORDER BY request_version");
        var upgrade = Flyway.configure().dataSource(dataSource).defaultSchema(schema).target("77").load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM approval_application")).isEqualTo(applications);
        assertThat(jdbc.queryForList(
                                "SELECT * FROM budget_adjustment_revision ORDER BY"
                                        + " request_version")).isEqualTo(original);
        assertThat(sources.derive(tenant, source.requestId())).isEqualTo(source);
        assertThat(upgrade.migrate().migrationsExecuted).isZero(); assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
        // 先独立验证 V77 保持原批准，再升级到当前结构供当前仓储写入新增诊断列。
        Flyway.configure().dataSource(dataSource).defaultSchema(schema).load().migrate();
        var ready = ready(source, "finance", 2); var queued = authorize(ready);
        assertThatThrownBy(() -> jdbc.update(
                                        "UPDATE budget_adjustment_operation SET review_version=2"
                                                + " WHERE tenant_id=? AND id=?", tenant, queued.command().id().toString())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                                        "UPDATE budget_adjustment_operation SET"
                                            + " active_request_id=NULL WHERE tenant_id=? AND id=?", tenant, queued.command().id().toString())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                                        "UPDATE budget_adjustment_operation SET"
                                            + " authorized_by='other' WHERE tenant_id=? AND id=?", tenant, queued.command().id().toString())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void workersUseShortTransactionsAndReadNeverAutomaticallyAuthorizes() {
        var source = approved(); var ledger = mock(BudgetLedgerPort.class); var gateway = mock(BudgetAdjustmentPort.class);
        var reviewWorker = new BudgetAdjustmentReviewWorker(reviews, reviewService, ledger);
        var worker = new BudgetAdjustmentExecutionWorker(operations, execution, gateway);
        when(ledger.read(anyString(), anyString(), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(invocation.<String>getArgument(1)).isEqualTo(source.round().targetDigest());
            var observed = Instant.now().minusMillis(1);
            return new FinanceResult.Success<>(new BudgetLedgerPort.Snapshot(source.round().ledger().request(), "fresh-v2", observed, observed.plusSeconds(300), source.round().ledger().positions()));
        });
        var queuedReview = tx.execute(status -> reviewService.register(source, "finance", Instant.now()));
        reviewWorker.poll(); var ready = reviews.find(tenant, queuedReview.input().id()).orElseThrow();
        assertThat(ready.status()).isEqualTo(BudgetAdjustmentReview.Status.READY); assertThat(count("budget_adjustment_operation")).isZero();
        var queued = tx.execute(status -> reviewService.authorize(tenant, ready.input().id(), ready.version(), "finance", "独立复核", Instant.now()));
        when(gateway.execute(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var command = invocation.<BudgetAdjustmentCommand>getArgument(0);
            assertThat(command).isEqualTo(queued.command());
            return new FinanceResult.Success<>(applied(command, Instant.now()));
        });
        worker.poll(); worker.poll(); reviewWorker.poll();
        assertThat(operations.find(tenant, queued.command().id()).orElseThrow().status()).isEqualTo(BudgetAdjustmentOperation.Status.APPLIED);
        verify(gateway).execute(queued.command()); verify(gateway, never()).query(any());
        assertThat(sources.derive(tenant, source.requestId())).isEqualTo(source);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> reviewWorker.poll())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> worker.poll())).isInstanceOf(IllegalStateException.class);
    }

    @Test void inactiveFinanceOrChangedDestinationStopsNewDispatchAndStillAllowsSafeRetirement() {
        var first = authorize(ready(approved(), "finance", 2)); eligible.set(false);
        assertThat(execution.claim(tenant, first.command().id(), now.plusSeconds(6))).isNull();
        var stopped = operations.find(tenant, first.command().id()).orElseThrow();
        assertThat(stopped.status()).isEqualTo(BudgetAdjustmentOperation.Status.VOIDED); assertThat(stopped.attempts()).isZero();
        eligible.set(true); var second = authorize(ready(approved(), "finance", 2));
        configuration.getTenants().get(tenant).setEndpoint("https://different-budget.example/finance");
        assertThat(execution.claim(tenant, second.command().id(), now.plusSeconds(6))).isNull();
        var changed = operations.find(tenant, second.command().id()).orElseThrow();
        tx.executeWithoutResult(status -> execution.retire(tenant, second.command().id(), changed.version(), "finance", now.plusSeconds(7)));
        assertThat(operations.activeForRequest(tenant, second.command().source().requestId())).isEmpty();
    }

    @Test void reviewFinishingAfterQualificationLossCannotBecomeAnAuthorizationBasis() {
        var source = approved(); var queued = tx.execute(status -> reviewService.register(source, "finance", now.plusSeconds(2)));
        var running = reviewService.claim(tenant, queued.input().id(), now.plusSeconds(3));
        eligible.set(false); reviewService.finish(running, new FinanceResult.Success<>(fresh(source, 4)), now.plusSeconds(5));
        assertThat(reviews.find(tenant, queued.input().id()).orElseThrow().status()).isEqualTo(BudgetAdjustmentReview.Status.VOIDED);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> reviewService.authorize(tenant, queued.input().id(), 3, "finance", "不应授权", now.plusSeconds(6)))).isInstanceOf(DomainException.class);
        assertThat(count("budget_adjustment_operation")).isZero();
    }

    @Test void expiredReadLeaseIsNotRetriedAndLateCompletionCannotReopenIt() {
        var source = approved(); var queued = tx.execute(status -> reviewService.register(source, "finance", now.plusSeconds(2)));
        var running = reviewService.claim(tenant, queued.input().id(), now.plusSeconds(3));
        assertThat(reviewService.claim(tenant, queued.input().id(), now.plusSeconds(33))).isNull();
        var timeout = reviews.find(tenant, queued.input().id()).orElseThrow(); assertThat(timeout.issue()).isEqualTo(BudgetAdjustmentReview.Issue.TIMEOUT);
        reviewService.finish(running, new FinanceResult.Success<>(fresh(source, 4)), now.plusSeconds(5));
        assertThat(reviews.find(tenant, queued.input().id())).contains(timeout);
        assertThat(reviewService.claim(tenant, queued.input().id(), now.plusSeconds(34))).isNull();
    }

    @Test void unknownOperationStillQueriesOriginalAfterSourceAndFinanceBecomeUnavailable() {
        var queued = authorize(ready(approved(), "finance", 2)); var running = execution.claim(tenant, queued.command().id(), now.plusSeconds(6));
        execution.finish(running, new FinanceResult.Unavailable<>(FinanceResult.Failure.TIMEOUT), now.plusSeconds(7));
        eligible.set(false); configuration.setEnabled(false);
        jdbc.update("UPDATE approval_application SET status='CANCELLED' WHERE tenant_id=? AND id=?", tenant, queued.command().source().applicationId().toString());
        var unknown = operations.find(tenant, queued.command().id()).orElseThrow();
        tx.executeWithoutResult(status -> execution.query(tenant, queued.command().id(), unknown.version(), now.plusSeconds(8)));
        var query = execution.claim(tenant, queued.command().id(), now.plusSeconds(400));
        assertThat(query.status()).isEqualTo(BudgetAdjustmentOperation.Status.QUERYING); assertThat(query.command()).isEqualTo(queued.command());
        execution.finish(query, new FinanceResult.Success<>(applied(query.command(), now.plusSeconds(401))), now.plusSeconds(401));
        assertThat(operations.find(tenant, queued.command().id()).orElseThrow().status()).isEqualTo(BudgetAdjustmentOperation.Status.APPLIED);
    }

    @Test void authoritativeNotFoundNeedsOriginalAuthorizerAndUnexpiredOriginalCommand() {
        var queued = authorize(ready(approved(), "finance", 2)); var running = execution.claim(tenant, queued.command().id(), now.plusSeconds(6));
        execution.fail(running, now.plusSeconds(7)); var query = execution.claim(tenant, queued.command().id(), now.plusSeconds(12));
        var missing = new BudgetAdjustmentObservation(queued.command().id(), queued.command().digest(), BudgetAdjustmentObservation.Status.NOT_FOUND, 0,
                now.plusSeconds(13), null, null, List.of(), null);
        execution.finish(query, new FinanceResult.Success<>(missing), now.plusSeconds(13));
        var found = operations.find(tenant, queued.command().id()).orElseThrow();
        assertThat(execution.claim(tenant, queued.command().id(), now.plusSeconds(14))).isNull();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> execution.retire(tenant, queued.command().id(), found.version(), "finance", now.plusSeconds(14)))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> execution.resend(tenant, queued.command().id(), found.version(), "finance-2", now.plusSeconds(14)))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> execution.resend(tenant, queued.command().id(), found.version(), "finance", queued.command().expiresAt()))).isInstanceOf(DomainException.class);
        var retry = tx.execute(status -> execution.resend(tenant, queued.command().id(), found.version(), "finance", now.plusSeconds(14)));
        assertThat(retry.command()).isEqualTo(queued.command()); assertThat(retry.status()).isEqualTo(BudgetAdjustmentOperation.Status.QUEUED);
    }

    @Test void workerExceptionPersistsUnknownAndExplicitRecoveryCallsOnlyOriginalQuery() {
        var queued = authorize(ready(approved(), "finance", 2)); var gateway = mock(BudgetAdjustmentPort.class);
        var worker = new BudgetAdjustmentExecutionWorker(operations, execution, gateway);
        when(gateway.execute(any())).thenThrow(new IllegalStateException("Synthetic lost response"));
        worker.poll(); var unknown = operations.find(tenant, queued.command().id()).orElseThrow();
        assertThat(unknown.status()).isEqualTo(BudgetAdjustmentOperation.Status.UNKNOWN);
        assertThat(unknown.failure()).isEqualTo(BudgetAdjustmentOperation.Failure.INTERNAL_ERROR);
        eligible.set(false);
        tx.executeWithoutResult(status -> execution.query(tenant, queued.command().id(), unknown.version(), Instant.now()));
        when(gateway.query(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(invocation.<BudgetAdjustmentCommand>getArgument(0)).isEqualTo(queued.command());
            return new FinanceResult.Success<>(applied(queued.command(), Instant.now()));
        });
        worker.poll(); worker.poll();
        verify(gateway).execute(queued.command()); verify(gateway).query(queued.command());
        assertThat(operations.find(tenant, queued.command().id()).orElseThrow().status()).isEqualTo(BudgetAdjustmentOperation.Status.APPLIED);
    }

    private ApprovedBudgetAdjustment approved() {
        var content = new BudgetAdjustmentContent(entity, "预算调整", "同法人调拨", BudgetAdjustmentContent.Type.TRANSFER, LocalDate.of(2026, 9, 30), "source", "target", money("70"));
        var request = BudgetAdjustmentRequest.draft(UUID.randomUUID(), tenant, UUID.randomUUID(), "alice", content);
        var catalog = new FinanceCatalog("alice", "v1", now.plusSeconds(300), List.of(new FinanceCatalog.LegalEntity(entity, "法人", "CNY", false, "v1", "Asia/Shanghai")), List.of(), List.of(), List.of(), List.of());
        var positions = content.ledgerRequest("alice").budgetReferences().stream().map(reference -> new BudgetLedgerPort.Position(entity, reference, "预算", "v1", "2026",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), BudgetLedgerPort.PeriodStatus.OPEN, money("1000"), money("300"), money("450"))).toList();
        var ledger = new BudgetLedgerPort.Snapshot(content.ledgerRequest("alice"), "ledger-v1", now, now.plusSeconds(300), positions);
        var initiator = new InitiatorContext(UUID.randomUUID(), UUID.randomUUID(), "alice", 1, entity, "法人", UUID.randomUUID(), "部门", UUID.randomUUID(), "岗位");
        return tx.execute(status -> {
            jdbc.update(
                            "INSERT INTO"
                                + " approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id)"
                                + " VALUES(?,?,?,'fixture',1,'alice','预算调整','{}','DRAFT',1,1,'BUDGET_ADJUSTMENT',?)",
                    request.applicationId().toString(), tenant, UUID.randomUUID().toString(), request.id().toString());
            requests.create(request, "alice"); request.freeze(1, 1, catalog, configuration.destination(tenant).orElseThrow().digest(tenant), ledger, initiator, now); requests.update(request, 1, "alice", "SUBMIT");
            request.approve(2, 1, 8, "manager", now.plusSeconds(1)); requests.update(request, 2, "manager", "APPROVE");
            jdbc.update(
                            "UPDATE approval_application SET"
                                + " status='APPROVED',version=8,payload_json=? WHERE tenant_id=?"
                                + " AND id=?", json.write(BudgetAdjustmentFormContract.submittedPayload(request.currentRound())), tenant, request.applicationId().toString());
            return sources.derive(tenant, request.id());
        });
    }
    private BudgetAdjustmentReview ready(ApprovedBudgetAdjustment source, String actor, int at) {
        var input = new BudgetAdjustmentReview.Input(UUID.randomUUID(), source, actor, reviews.latestAttempt(tenant, source.requestId(), actor) + 1, now.plusSeconds(at));
        var queued = BudgetAdjustmentReview.queue(input); tx.executeWithoutResult(status -> reviews.create(queued));
        var running = queued.claim(input.requestedAt(), Duration.ofSeconds(30)); tx.executeWithoutResult(status -> reviews.update(running));
        var ledger = new BudgetLedgerPort.Snapshot(source.round().ledger().request(), "latest-v2", now.plusSeconds(at + 1), now.plusSeconds(at + 301), source.round().ledger().positions());
        var ready = running.complete(new FinanceResult.Success<>(ledger), now.plusSeconds(at + 2)); tx.executeWithoutResult(status -> reviews.update(ready)); return ready;
    }
    private BudgetAdjustmentCommand command(BudgetAdjustmentReview review) {
        return BudgetAdjustmentCommand.authorize(UUID.randomUUID(), review.input().source(), review.ledger(), review.input().requestedBy(), "确认最新台账", review.updatedAt().plusSeconds(1));
    }
    private BudgetLedgerPort.Snapshot fresh(ApprovedBudgetAdjustment source, int seconds) {
        return new BudgetLedgerPort.Snapshot(source.round().ledger().request(), "fresh-v2", now.plusSeconds(seconds), now.plusSeconds(seconds + 300), source.round().ledger().positions());
    }
    @SuppressWarnings("unchecked") private <T> T proxy(T target) {
        var factory = new ProxyFactory(target); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource())); return (T) factory.getProxy();
    }
    private BudgetAdjustmentOperation authorize(BudgetAdjustmentReview review) {
        var command = command(review); var queued = BudgetAdjustmentOperation.queue(command, command.authorizedAt());
        tx.executeWithoutResult(status -> operations.create(queued, review.input().id())); return queued;
    }
    private BudgetAdjustmentOperation save(BudgetAdjustmentOperation value) { tx.executeWithoutResult(status -> operations.update(value)); return value; }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id=?", Integer.class, tenant); }
    private UUID readyId(BudgetAdjustmentOperation value) { return UUID.fromString(jdbc.queryForObject(
                        "SELECT review_id FROM budget_adjustment_operation WHERE tenant_id=? AND"
                                + " id=?", String.class, tenant, value.command().id().toString())); }
    private BudgetAdjustmentObservation applied(BudgetAdjustmentCommand command, Instant at) {
        var changes = command.changes().stream().map(change -> {
            var position = command.ledger().position(change.budgetReference());
            return new BudgetAdjustmentObservation.AppliedChange(change.budgetReference(), change.expectedVersion(), "v2", position.periodReference(), command.source().round().content().accountingDate(),
                    change.beforeLimit(), change.afterLimit(), position.committed(), position.consumed());
        }).toList();
        return new BudgetAdjustmentObservation(command.id(), command.digest(), BudgetAdjustmentObservation.Status.APPLIED, 1, at, "synthetic-adjustment", command.authorizedAt(), changes, null);
    }
    private List<String> race(Callable<?> first, Callable<?> second) throws Exception {
        var start = new CountDownLatch(1); var executor = Executors.newFixedThreadPool(2);
        try {
            var values = List.of(first, second).stream().map(action -> executor.submit(() -> {
                if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Concurrent start timed out");
                try { action.call(); return "SAVED"; } catch (DomainException conflict) { return conflict.code(); }
            })).toList(); start.countDown();
            return List.of(values.get(0).get(15, TimeUnit.SECONDS), values.get(1).get(15, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }
    private static Money money(String amount) { return new Money(new BigDecimal(amount), "CNY"); }
}
