package io.agentflow.budget;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentflow.approval.JdbcApplicationRepository;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.FinanceJsonConfiguration;
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.finance.FinanceResult;
import io.agentflow.finance.Money;
import io.agentflow.organization.InitiatorContext;
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
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 H2/PostgreSQL 验证预算授权的单次证据、并发独占、租约恢复和安全结束原子性。
 * @author owlzhangfq@gmail.com
 */
class BudgetAdjustmentExecutionPersistenceTest {
    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");
    private final String tenant = "budget-execution-" + UUID.randomUUID();
    private final UUID entity = UUID.randomUUID();
    private final JsonUtil json = new JsonUtil(new ObjectMapper().registerModule(new JavaTimeModule())
            .registerModule(new FinanceJsonConfiguration().financeMoneyModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    private DriverManagerDataSource dataSource;
    private String schema;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private JdbcBudgetAdjustmentRepository requests;
    private ApprovedBudgetAdjustmentSources sources;
    private JdbcBudgetAdjustmentReviewRepository reviews;
    private JdbcBudgetAdjustmentOperationRepository operations;

    @BeforeEach void database() { database(null); }
    private void database(String target) {
        dataSource = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_BUDGET_EXECUTION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_BUDGET_EXECUTION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_BUDGET_EXECUTION_PASSWORD", ""));
        schema = "budget_execution_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(dataSource).execute("CREATE SCHEMA \"" + schema + "\""); dataSource.setSchema(schema);
        var flyway = Flyway.configure().dataSource(dataSource).defaultSchema(schema); if (target != null) flyway.target(target);
        flyway.load().migrate(); jdbc = new JdbcTemplate(dataSource); tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        requests = new JdbcBudgetAdjustmentRepository(jdbc, json); sources = new ApprovedBudgetAdjustmentSources(new JdbcApplicationRepository(jdbc, json), requests);
        reviews = new JdbcBudgetAdjustmentReviewRepository(jdbc, json, sources); operations = new JdbcBudgetAdjustmentOperationRepository(jdbc, json, sources, reviews);
    }

    @Test void originalApprovalReadyRevisionAndSingleConsumptionSurviveRepositoryReconstruction() {
        var source = approved(); var ready = ready(source, "finance", 2); var queued = authorize(ready);
        var reopenedReviews = new JdbcBudgetAdjustmentReviewRepository(jdbc, json, sources);
        var reopened = new JdbcBudgetAdjustmentOperationRepository(jdbc, json, sources, reopenedReviews);
        assertThat(reopened.find(tenant, queued.command().id())).contains(queued);
        assertThat(reopened.find("foreign", queued.command().id())).isEmpty();
        assertThat(reopenedReviews.find(tenant, ready.input().id()).orElseThrow().supports(queued.command())).isTrue();
        assertThat(reopenedReviews.revision(tenant, ready.input().id(), 3)).contains(ready);
        assertThat(reopened.activeForRequest(tenant, source.requestId())).contains(queued);
        assertThat(reopened.due(queued.createdAt())).containsExactly(new JdbcBudgetAdjustmentOperationRepository.Candidate(tenant, queued.command().id()));
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
        jdbc.update("UPDATE budget_adjustment_review SET requested_at=? WHERE tenant_id=? AND id=?", java.sql.Timestamp.from(NOW), tenant, second.input().id().toString());
        assertThatThrownBy(() -> reviews.find(tenant, second.input().id())).isInstanceOf(IllegalStateException.class);
        assertThat(count("budget_adjustment_operation")).isZero();
    }

    @Test void originalReadyEvidenceCannotReplaceMissingCurrentConsumptionProof() {
        var ready = ready(approved(), "finance", 2); var queued = authorize(ready);
        jdbc.update("UPDATE budget_adjustment_review SET state_json=?,version=3,status='READY',updated_at=?,consumed_operation_id=NULL WHERE tenant_id=? AND id=?",
                json.write(ready), java.sql.Timestamp.from(ready.updatedAt()), tenant, ready.input().id().toString());
        assertThatThrownBy(() -> operations.find(tenant, queued.command().id())).isInstanceOf(IllegalStateException.class);
    }

    @Test void twoFinanceActorsCannotAuthorizeTheSameApprovedBudgetTwice() throws Exception {
        var source = approved(); var first = ready(source, "finance", 2); var second = ready(source, "finance-2", 2);
        assertThat(race(() -> authorize(first), () -> authorize(second))).containsExactlyInAnyOrder("SAVED", "BUDGET_ADJUSTMENT_ALREADY_AUTHORIZED");
        assertThat(count("budget_adjustment_operation")).isEqualTo(1); assertThat(count("budget_adjustment_operation_revision")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_adjustment_review WHERE tenant_id=? AND status='CONSUMED'", Integer.class, tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_adjustment_review WHERE tenant_id=? AND status='READY'", Integer.class, tenant)).isEqualTo(1);
    }

    @Test void competingClaimsPreserveOneLeaseAndCannotSkipExecutionIntoSuccess() throws Exception {
        var queued = authorize(ready(approved(), "finance", 2)); var running = queued.claim(NOW.plusSeconds(6), Duration.ofSeconds(10));
        assertThat(race(() -> save(running), () -> save(running))).containsExactlyInAnyOrder("SAVED", "CONCURRENCY_CONFLICT");
        assertThat(operations.find(tenant, queued.command().id())).contains(running);
        var another = authorize(ready(approved(), "finance", 2)); var receipt = applied(another.command(), NOW.plusSeconds(7));
        var forged = new BudgetAdjustmentOperation(another.command(), 2, BudgetAdjustmentOperation.Status.APPLIED, 1, another.createdAt(), NOW.plusSeconds(7), null, null, receipt, null, null);
        assertThatThrownBy(() -> save(forged)).isInstanceOf(DomainException.class);
        assertThat(operations.find(tenant, another.command().id())).contains(another);
    }

    @Test void lostLeaseAndLateReceiptRecoverOnlyThroughOriginalQuery() {
        var queued = authorize(ready(approved(), "finance", 2)); var running = queued.claim(NOW.plusSeconds(6), Duration.ofSeconds(10)); save(running);
        var expired = running.expire(NOW.plusSeconds(16)); save(expired);
        assertThatThrownBy(() -> save(running.complete(new FinanceResult.Success<>(applied(running.command(), NOW.plusSeconds(7))), NOW.plusSeconds(7)))).isInstanceOf(DomainException.class);
        var query = expired.claim(NOW.plusSeconds(400), Duration.ofSeconds(10)); save(query);
        assertThat(query.status()).isEqualTo(BudgetAdjustmentOperation.Status.QUERYING);
        var done = query.complete(new FinanceResult.Success<>(applied(query.command(), NOW.plusSeconds(401))), NOW.plusSeconds(401)); save(done);
        assertThat(operations.find(tenant, query.command().id())).contains(done);
        assertThat(operations.revision(tenant, query.command().id(), running.version())).contains(running);
        assertThat(operations.activeForRequest(tenant, query.command().source().requestId())).contains(done);
    }

    @Test void evidenceConsumptionAppendFailureRollsBackNewCommandAndQueue() {
        var ready = ready(approved(), "finance", 2); var command = command(ready); var consumed = ready.consume(command, command.authorizedAt());
        jdbc.update("INSERT INTO budget_adjustment_review_revision(tenant_id,review_id,version,state_json) VALUES(?,?,4,?)", tenant, ready.input().id().toString(), json.write(consumed));
        var queued = BudgetAdjustmentOperation.queue(command, command.authorizedAt());
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> operations.create(queued, ready.input().id()))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(reviews.find(tenant, ready.input().id())).contains(ready);
        assertThat(count("budget_adjustment_operation")).isZero(); assertThat(count("budget_adjustment_operation_revision")).isZero();
    }

    @Test void safeRetirementAndReplacementKeepOriginalCommandAndPreventResurrection() {
        var source = approved(); var queued = authorize(ready(source, "finance", 2));
        tx.executeWithoutResult(status -> {
            var stopped = queued.voidBeforeSend(NOW.plusSeconds(6)); operations.update(stopped);
            operations.retire(tenant, BudgetAdjustmentRetirement.from(stopped, "finance", NOW.plusSeconds(7)));
        });
        assertThat(operations.activeForRequest(tenant, source.requestId())).isEmpty();
        var retired = operations.find(tenant, queued.command().id()).orElseThrow();
        assertThat(operations.retirement(tenant, queued.command().id()).orElseThrow().matches(retired)).isTrue();
        assertThatThrownBy(() -> save(queued.claim(NOW.plusSeconds(6), Duration.ofSeconds(10)))).isInstanceOf(DomainException.class);
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
                var stopped = queued.voidBeforeSend(NOW.plusSeconds(6)); operations.update(stopped);
                operations.retire(tenant, BudgetAdjustmentRetirement.from(stopped, "finance", NOW.plusSeconds(7)));
            }); return null;
        };
        assertThat(race(stop, () -> save(queued.claim(NOW.plusSeconds(6), Duration.ofSeconds(10))))).containsExactlyInAnyOrder("SAVED", "CONCURRENCY_CONFLICT");
        var result = operations.find(tenant, queued.command().id()).orElseThrow();
        assertThat(result.status()).isIn(BudgetAdjustmentOperation.Status.VOIDED, BudgetAdjustmentOperation.Status.EXECUTING);
        assertThat(operations.retirement(tenant, queued.command().id()).isPresent()).isEqualTo(result.status() == BudgetAdjustmentOperation.Status.VOIDED);
        var second = authorize(ready(approved(), "finance", 2));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            var stopped = second.voidBeforeSend(NOW.plusSeconds(6)); operations.update(stopped);
            operations.retire(tenant, BudgetAdjustmentRetirement.from(stopped, "finance", NOW.plusSeconds(7)));
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
        assertThat(jdbc.queryForList("SELECT * FROM budget_adjustment_revision ORDER BY request_version")).isEqualTo(original);
        assertThat(sources.derive(tenant, source.requestId())).isEqualTo(source);
        var ready = ready(source, "finance", 2); var queued = authorize(ready);
        assertThatThrownBy(() -> jdbc.update("UPDATE budget_adjustment_operation SET review_version=2 WHERE tenant_id=? AND id=?", tenant, queued.command().id().toString())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE budget_adjustment_operation SET active_request_id=NULL WHERE tenant_id=? AND id=?", tenant, queued.command().id().toString())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE budget_adjustment_operation SET authorized_by='other' WHERE tenant_id=? AND id=?", tenant, queued.command().id().toString())).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(upgrade.migrate().migrationsExecuted).isZero(); assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
    }

    private ApprovedBudgetAdjustment approved() {
        var content = new BudgetAdjustmentContent(entity, "预算调整", "同法人调拨", BudgetAdjustmentContent.Type.TRANSFER, LocalDate.of(2026, 9, 30), "source", "target", money("70"));
        var request = BudgetAdjustmentRequest.draft(UUID.randomUUID(), tenant, UUID.randomUUID(), "alice", content);
        var catalog = new FinanceCatalog("alice", "v1", NOW.plusSeconds(300), List.of(new FinanceCatalog.LegalEntity(entity, "法人", "CNY", false, "v1", "Asia/Shanghai")), List.of(), List.of(), List.of(), List.of());
        var positions = content.ledgerRequest("alice").budgetReferences().stream().map(reference -> new BudgetLedgerPort.Position(entity, reference, "预算", "v1", "2026",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), BudgetLedgerPort.PeriodStatus.OPEN, money("1000"), money("300"), money("450"))).toList();
        var ledger = new BudgetLedgerPort.Snapshot(content.ledgerRequest("alice"), "ledger-v1", NOW, NOW.plusSeconds(300), positions);
        var initiator = new InitiatorContext(UUID.randomUUID(), UUID.randomUUID(), "alice", 1, entity, "法人", UUID.randomUUID(), "部门", UUID.randomUUID(), "岗位");
        return tx.execute(status -> {
            jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id) VALUES(?,?,?,'fixture',1,'alice','预算调整','{}','DRAFT',1,1,'BUDGET_ADJUSTMENT',?)",
                    request.applicationId().toString(), tenant, UUID.randomUUID().toString(), request.id().toString());
            requests.create(request, "alice"); request.freeze(1, 1, catalog, "a".repeat(64), ledger, initiator, NOW); requests.update(request, 1, "alice", "SUBMIT");
            request.approve(2, 1, 8, "manager", NOW.plusSeconds(1)); requests.update(request, 2, "manager", "APPROVE");
            jdbc.update("UPDATE approval_application SET status='APPROVED',version=8,payload_json=? WHERE tenant_id=? AND id=?", json.write(BudgetAdjustmentFormContract.submittedPayload(request.currentRound())), tenant, request.applicationId().toString());
            return sources.derive(tenant, request.id());
        });
    }
    private BudgetAdjustmentReview ready(ApprovedBudgetAdjustment source, String actor, int at) {
        var input = new BudgetAdjustmentReview.Input(UUID.randomUUID(), source, actor, reviews.latestAttempt(tenant, source.requestId(), actor) + 1, NOW.plusSeconds(at));
        var queued = BudgetAdjustmentReview.queue(input); tx.executeWithoutResult(status -> reviews.create(queued));
        var running = queued.claim(input.requestedAt(), Duration.ofSeconds(30)); tx.executeWithoutResult(status -> reviews.update(running));
        var ledger = new BudgetLedgerPort.Snapshot(source.round().ledger().request(), "latest-v2", NOW.plusSeconds(at + 1), NOW.plusSeconds(at + 301), source.round().ledger().positions());
        var ready = running.complete(new FinanceResult.Success<>(ledger), NOW.plusSeconds(at + 2)); tx.executeWithoutResult(status -> reviews.update(ready)); return ready;
    }
    private BudgetAdjustmentCommand command(BudgetAdjustmentReview review) {
        return BudgetAdjustmentCommand.authorize(UUID.randomUUID(), review.input().source(), review.ledger(), review.input().requestedBy(), "确认最新台账", review.updatedAt().plusSeconds(1));
    }
    private BudgetAdjustmentOperation authorize(BudgetAdjustmentReview review) {
        var command = command(review); var queued = BudgetAdjustmentOperation.queue(command, command.authorizedAt());
        tx.executeWithoutResult(status -> operations.create(queued, review.input().id())); return queued;
    }
    private BudgetAdjustmentOperation save(BudgetAdjustmentOperation value) { tx.executeWithoutResult(status -> operations.update(value)); return value; }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id=?", Integer.class, tenant); }
    private UUID readyId(BudgetAdjustmentOperation value) { return UUID.fromString(jdbc.queryForObject("SELECT review_id FROM budget_adjustment_operation WHERE tenant_id=? AND id=?", String.class, tenant, value.command().id().toString())); }
    private BudgetAdjustmentObservation applied(BudgetAdjustmentCommand command, Instant at) {
        var changes = command.changes().stream().map(change -> {
            var position = command.ledger().position(change.budgetReference());
            return new BudgetAdjustmentObservation.AppliedChange(change.budgetReference(), change.expectedVersion(), "v2", position.periodReference(), command.source().round().content().accountingDate(),
                    change.beforeLimit(), change.afterLimit(), position.committed(), position.consumed());
        }).toList();
        return new BudgetAdjustmentObservation(command.id(), command.digest(), BudgetAdjustmentObservation.Status.APPLIED, 1, at, "synthetic-adjustment", command.authorizedAt().plusSeconds(1), changes, null);
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
