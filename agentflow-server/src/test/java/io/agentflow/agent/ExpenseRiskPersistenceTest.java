package io.agentflow.agent;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.agentflow.auth.DeferredActorAuthentication;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 使用实际迁移和 JDBC 事务验证来源绑定、独占队列及审计回滚；合成 SQL 来源不冒充完整费用审批运行。
 *
 * @author owlzhangfq@gmail.com
 */
class ExpenseRiskPersistenceTest {
    private static final String TENANT = "risk-fixture";
    private static final Instant AT = Instant.parse("2026-10-04T12:00:00Z");
    private static final String CONCERN = "expense:risk[1]";
    private static final List<String> BUSINESS_TABLES = List.of("approval_application", "expense_report", "expense_report_revision", "approval_submission_round");
    private static final DeferredActorAuthentication.LoginReference LOGIN = new DeferredActorAuthentication.LoginReference(DeferredActorAuthentication.Kind.DEMO_LOGIN, "f".repeat(64));
    private final UUID primaryReport = UUID.randomUUID(), primaryApplication = UUID.randomUUID();
    private final UUID comparisonReport = UUID.randomUUID(), comparisonApplication = UUID.randomUUID();
    private final JsonUtil json = new JsonUtil(new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    private JdbcTemplate jdbc;
    private JdbcExpenseRiskRepository runs;
    private TransactionTemplate tx;
    private List<List<Map<String, Object>>> beforeMigration;

    @BeforeEach void migrateNonemptyV114Database() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:expense-risk-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).target("114").load().migrate(); jdbc = new JdbcTemplate(source);
        seed(primaryReport, primaryApplication); seed(comparisonReport, comparisonApplication); beforeMigration = businessRows();
        Flyway.configure().dataSource(source).load().migrate();
        // 后续迁移允许新增查询投影，但原列和原行必须完整保留；执行前再冻结包含新列的完整基线。
        var migrated = businessRows();
        assertThat(migrated).hasSize(beforeMigration.size());
        for (int table = 0; table < beforeMigration.size(); table++) {
            assertThat(migrated.get(table)).hasSize(beforeMigration.get(table).size());
            for (int row = 0; row < beforeMigration.get(table).size(); row++) {
                assertThat(migrated.get(table).get(row)).containsAllEntriesOf(beforeMigration.get(table).get(row));
            }
        }
        beforeMigration = migrated;
        var manager = new DataSourceTransactionManager(source); tx = new TransactionTemplate(manager);
        var proxy = new ProxyFactory(new JdbcExpenseRiskRepository(
                                io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                        (jdbc).getDataSource(),
                                        io.agentflow.agent.mapper.ExpenseRiskRepositoryMapper
                                                .class), json));
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        runs = (JdbcExpenseRiskRepository) proxy.getProxy();
    }
    @AfterEach void closeOwnedInMemoryDatabase() { if (jdbc != null) jdbc.execute("SHUTDOWN"); }

    @Test void migrationAndCompleteReviewPreserveOriginalBusinessRowsAndEverySourceBinding() {
        assertThat(businessRows()).isEqualTo(beforeMigration);
        var run = completed(); var context = run.context(); var original = run.state().suggestion();
        run.adopt(3, context.input(), "second-current-reviewer", List.of(CONCERN), "人工核对完成", AT.plusSeconds(3));
        tx.executeWithoutResult(ignored -> runs.update(run, 3));
        var restored = runs.find(TENANT, context.id()).orElseThrow();
        assertThat(restored.run().context()).isEqualTo(context); assertThat(restored.run().state()).isEqualTo(run.state());
        assertThat(restored.login()).isEqualTo(LOGIN); assertThat(restored.run().state().suggestion()).isEqualTo(original);
        assertThat(versions(run)).containsExactly(1L, 2L, 3L, 4L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_expense_risk_document WHERE run_id=?", Integer.class, context.id().toString())).isEqualTo(2);
        assertThat(businessRows()).isEqualTo(beforeMigration);
    }

    @Test void queuedAndRunningStatesRestoreAndExpiredLeaseIsNeverExtendedOrResent() {
        var run = fresh(); tx.executeWithoutResult(ignored -> runs.create(run, LOGIN));
        assertThat(runs.due(AT)).extracting(JdbcExpenseRiskRepository.Candidate::id).contains(run.context().id());
        var queued = runs.find(TENANT, run.context().id()).orElseThrow().run();
        queued.start(1, AT, AT.plusSeconds(30)); tx.executeWithoutResult(ignored -> runs.update(queued, 1));
        assertThat(runs.due(AT.plusSeconds(29))).isEmpty();
        assertThat(runs.due(AT.plusSeconds(30))).extracting(JdbcExpenseRiskRepository.Candidate::id).containsExactly(run.context().id());
        var reloaded = runs.find(TENANT, run.context().id()).orElseThrow().run();
        assertThat(reloaded.state().leaseUntil()).isEqualTo(AT.plusSeconds(30));
        reloaded.fail(2, AssistRun.Failure.MODEL_TIMEOUT, AT.plusSeconds(30)); tx.executeWithoutResult(ignored -> runs.update(reloaded, 2));
        assertThat(runs.due(AT.plusSeconds(31))).isEmpty();
        error("AGENT_RUN_STATE_CONFLICT", () -> reloaded.complete(3, suggestion(reloaded.context().input()), AT.plusSeconds(31)));
        assertThat(versions(run)).containsExactly(1L, 2L, 3L);
    }

    @ParameterizedTest @ValueSource(strings = {"primary-application-version", "primary-financial-version", "primary-status", "primary-round", "comparison-application-version", "comparison-financial-version", "tenant"})
    void queueVersionFailureRollsBackTheWholeRunAndAllComparisonRows(String changed) {
        UUID application = changed.startsWith("comparison") ? comparisonApplication : primaryApplication;
        UUID report = changed.startsWith("comparison") ? comparisonReport : primaryReport;
        if (changed.endsWith("application-version")) jdbc.update("UPDATE approval_application SET version=2 WHERE id=?", application.toString());
        if (changed.endsWith("financial-version")) jdbc.update("UPDATE expense_report SET version=2 WHERE id=?", report.toString());
        if (changed.equals("primary-status")) jdbc.update("UPDATE approval_application SET status='APPROVED' WHERE id=?", primaryApplication.toString());
        if (changed.equals("primary-round")) jdbc.update("UPDATE approval_application SET round_no=2 WHERE id=?", primaryApplication.toString());
        var initial = fresh(); var context = initial.context();
        var run = changed.equals("tenant") ? new ExpenseRiskRun(new ExpenseRiskRun.Context(context.id(), "foreign", context.requestedBy(), context.taskId(), AT, context.input(), context.targetDigest())) : initial;
        error("AGENT_INPUT_CHANGED", () -> tx.executeWithoutResult(ignored -> runs.create(run, LOGIN)));
        assertEmptyRiskTables();
    }

    @Test void missingComparisonRevisionIsRejectedByTheRealForeignKeyAndRollsBackPrimaryInsertion() {
        jdbc.update("DELETE FROM expense_report_revision WHERE report_id=?", comparisonReport.toString());
        assertThatThrownBy(() -> tx.executeWithoutResult(ignored -> runs.create(fresh(), LOGIN))).isInstanceOf(DataIntegrityViolationException.class);
        assertEmptyRiskTables();
    }

    @Test void writesRequireCallerTransactionsAndStaleUpdatesCannotAppendAudit() {
        var run = fresh(); assertThatThrownBy(() -> runs.create(run, LOGIN)).isInstanceOf(IllegalTransactionStateException.class);
        tx.executeWithoutResult(ignored -> runs.create(run, LOGIN));
        var first = loaded(run); var stale = loaded(run); first.start(1, AT, AT.plusSeconds(30));
        tx.executeWithoutResult(ignored -> runs.update(first, 1)); stale.start(1, AT, AT.plusSeconds(60));
        error("CONCURRENCY_CONFLICT", () -> tx.executeWithoutResult(ignored -> runs.update(stale, 1)));
        assertThat(loaded(run).state().leaseUntil()).isEqualTo(AT.plusSeconds(30)); assertThat(versions(run)).containsExactly(1L, 2L);
    }

    @Test void callerCannotReplaceImmutableContextDuringAStateUpdate() {
        var run = fresh(); tx.executeWithoutResult(ignored -> runs.create(run, LOGIN)); var c = run.context();
        var forged = new ExpenseRiskRun(new ExpenseRiskRun.Context(c.id(), c.tenantId(), c.requestedBy(), c.taskId(), c.createdAt(), c.input(), "b".repeat(64)));
        forged.start(1, AT, AT.plusSeconds(30));
        error("CONCURRENCY_CONFLICT", () -> tx.executeWithoutResult(ignored -> runs.update(forged, 1)));
        assertThat(loaded(run).context()).isEqualTo(c); assertThat(versions(run)).containsExactly(1L);
    }

    @Test void samePrimaryHasOnlyOneActiveRunEvenWhenTwoTransactionsQueueConcurrently() throws Exception {
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> queueWhenReleased(start)); var second = pool.submit(() -> queueWhenReleased(start)); start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder("CREATED", "AGENT_RUN_ACTIVE");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_expense_risk_run", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_expense_risk_document", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_expense_risk_transition", Integer.class)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test void lockedConcurrentClaimsHaveOneWinner() throws Exception {
        var run = fresh(); tx.executeWithoutResult(ignored -> runs.create(run, LOGIN));
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> claimWhenReleased(start, run.context().id())); var second = pool.submit(() -> claimWhenReleased(start, run.context().id())); start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
            assertThat(versions(run)).containsExactly(1L, 2L);
        } finally { pool.shutdownNow(); }
    }

    @Test void failedReviewAuditRollsBackStateAndOriginalOutputSurvivesRetry() {
        var run = completed(); var original = run.state();
        jdbc.execute(
                "ALTER TABLE agent_expense_risk_transition ADD CONSTRAINT fixture_deny_adoption"
                        + " CHECK(status<>'ADOPTED')");
        run.adopt(3, run.context().input(), "reviewer", List.of(CONCERN), null, AT.plusSeconds(3));
        assertThatThrownBy(() -> tx.executeWithoutResult(ignored -> runs.update(run, 3))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(loaded(run).state()).isEqualTo(original); assertThat(versions(run)).containsExactly(1L, 2L, 3L);
        jdbc.execute("ALTER TABLE agent_expense_risk_transition DROP CONSTRAINT fixture_deny_adoption");
        var retry = loaded(run); retry.adopt(3, retry.context().input(), "reviewer", List.of(CONCERN), null, AT.plusSeconds(4));
        tx.executeWithoutResult(ignored -> runs.update(retry, 3)); assertThat(versions(run)).containsExactly(1L, 2L, 3L, 4L);
        assertThat(loaded(run).state().suggestion()).isEqualTo(original.suggestion());
    }

    @Test void failedInitialAuditLeavesNeitherSourcesNorAnOccupiedActiveSlot() {
        jdbc.execute(
                "ALTER TABLE agent_expense_risk_transition ADD CONSTRAINT fixture_deny_initial"
                        + " CHECK(run_version>1)");
        assertThatThrownBy(() -> tx.executeWithoutResult(ignored -> runs.create(fresh(), LOGIN))).isInstanceOf(DataIntegrityViolationException.class);
        assertEmptyRiskTables(); jdbc.execute("ALTER TABLE agent_expense_risk_transition DROP CONSTRAINT fixture_deny_initial");
        var next = fresh(); tx.executeWithoutResult(ignored -> runs.create(next, LOGIN)); assertThat(loaded(next).state().status()).isEqualTo(ExpenseRiskRun.Status.QUEUED);
    }

    @ParameterizedTest @ValueSource(strings = {"requested-by", "task-id", "created-at", "document-version", "missing-document", "source-content", "state"})
    void inconsistentPersistedBindingFailsInsteadOfSilentlyRepairingHistory(String changed) {
        var run = fresh(); tx.executeWithoutResult(ignored -> runs.create(run, LOGIN)); String id = run.context().id().toString();
        switch (changed) {
            case "requested-by" -> jdbc.update("UPDATE agent_expense_risk_run SET requested_by='different' WHERE id=?", id);
            case "task-id" -> jdbc.update("UPDATE agent_expense_risk_run SET task_id='different-task' WHERE id=?", id);
            case "created-at" -> jdbc.update("UPDATE agent_expense_risk_run SET created_at=? WHERE id=?", Timestamp.from(AT.plusSeconds(1)), id);
            case "document-version" -> jdbc.update(
                            "UPDATE agent_expense_risk_document SET application_version=99 WHERE"
                                    + " run_id=? AND ordinal=2", id);
            case "missing-document" -> jdbc.update("DELETE FROM agent_expense_risk_document WHERE run_id=? AND ordinal=2", id);
            case "source-content" -> jdbc.update("UPDATE agent_expense_risk_run SET context_json=? WHERE id=?", json.write(contextWithTamperedSource(run)), id);
            case "state" -> jdbc.update("UPDATE agent_expense_risk_run SET state_json=? WHERE id=?", json.write(new ExpenseRiskRun.State(ExpenseRiskRun.Status.COMPLETED, 3, null, null, null, null, null, null)), id);
            default -> throw new IllegalArgumentException(changed);
        }
        assertThatThrownBy(() -> runs.find(TENANT, run.context().id())).isInstanceOf(IllegalStateException.class);
    }

    @Test void sourceDigestMustMatchBeforeCreatingAnyPersistentRecords() {
        var forged = new ExpenseRiskRun(contextWithTamperedSource(fresh()));
        error("INVALID_AGENT_INPUT", () -> tx.executeWithoutResult(ignored -> runs.create(forged, LOGIN))); assertEmptyRiskTables();
    }

    @Test void historyIsTenantAndRoundScopedAndNeverReturnsLoginOrComparisonContent() {
        var first = completed(); var second = fresh(); tx.executeWithoutResult(ignored -> runs.create(second, LOGIN));
        var page = runs.page(TENANT, primaryReport, 1, 0, 1); var next = runs.page(TENANT, primaryReport, 1, 1, 1);
        assertThat(page.total()).isEqualTo(2); assertThat(page.items()).hasSize(1); assertThat(next.items()).hasSize(1);
        assertThat(page.items().get(0).id()).isNotEqualTo(next.items().get(0).id());
        assertThat(json.write(page)).doesNotContain(LOGIN.value(), comparisonReport.toString(), "sources", "suggestion", "requestedBy");
        assertThat(runs.page("foreign", primaryReport, 1, 0, 20).total()).isZero(); assertThat(runs.page(TENANT, primaryReport, 2, 0, 20).total()).isZero();
        assertThat(runs.find("foreign", first.context().id())).isEmpty();
        Boolean locked = tx.execute(ignored -> runs.lock("foreign", first.context().id()));
        assertThat(locked).isFalse();
    }

    private void seed(UUID report, UUID application) {
        jdbc.update(
                "INSERT INTO"
                    + " approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id)"
                    + " VALUES(?,?,?,'fixture',1,'employee','保留申请','{}','IN_APPROVAL',1,1,'EXPENSE',?)", application.toString(), TENANT, "RISK-" + application, report.toString());
        jdbc.update(
                "INSERT INTO"
                    + " expense_report(id,tenant_id,application_id,employee_id,version,state_json)"
                    + " VALUES(?,?,?,'employee',1,'{\"retained\":true}')", report.toString(), TENANT, application.toString());
        jdbc.update(
                "INSERT INTO"
                    + " expense_report_revision(tenant_id,report_id,financial_version,actor_id,operation,state_json)"
                    + " SELECT tenant_id,id,version,employee_id,'CREATE',state_json FROM"
                    + " expense_report WHERE id=?", report.toString());
        jdbc.update(
                "INSERT INTO"
                    + " approval_submission_round(tenant_id,application_id,round_no,process_instance_id,definition_version,title,payload_json,submitted_by,submitted_at,status)"
                    + " VALUES(?,?,1,?,1,'原轮次','{}','employee',?,'IN_APPROVAL')", TENANT, application.toString(), "fixture-" + application, Timestamp.from(AT.minusSeconds(60)));
    }
    private ExpenseRiskRun fresh() {
        var documents = List.of(new ExpenseRiskInput.Document(1, primaryReport, primaryApplication, 1, 1, 1, "a".repeat(64), List.of(1)),
                new ExpenseRiskInput.Document(2, comparisonReport, comparisonApplication, 1, 1, 1, "b".repeat(64), List.of(1)));
        var input = new ExpenseRiskInput(documents, null, List.of(new ExpenseRiskInput.Concern(CONCERN, ExpenseRiskInput.Kind.CROSS_DOCUMENT, List.of(1, 2))),
                List.of(source("expense:document[1]"), source("expense:document[2]"), source(ExpenseRiskInput.COVERAGE_SOURCE), source(CONCERN)));
        return new ExpenseRiskRun(new ExpenseRiskRun.Context(UUID.randomUUID(), TENANT, "reviewer", "current-task", AT, input, "c".repeat(64)));
    }
    private ExpenseRiskRun completed() {
        var run = fresh(); tx.executeWithoutResult(ignored -> runs.create(run, LOGIN)); run.start(1, AT, AT.plusSeconds(30));
        tx.executeWithoutResult(ignored -> runs.update(run, 1)); run.complete(2, suggestion(run.context().input()), AT.plusSeconds(2));
        tx.executeWithoutResult(ignored -> runs.update(run, 2)); return run;
    }
    private String queueWhenReleased(CountDownLatch start) throws InterruptedException {
        start.await(); try { tx.executeWithoutResult(ignored -> runs.create(fresh(), LOGIN)); return "CREATED"; } catch (DomainException failure) { return failure.code(); }
    }
    private boolean claimWhenReleased(CountDownLatch start, UUID id) throws InterruptedException {
        start.await(); return Boolean.TRUE.equals(tx.execute(ignored -> {
            runs.lock(TENANT, id); var run = runs.find(TENANT, id).orElseThrow().run(); if (run.state().status() != ExpenseRiskRun.Status.QUEUED) return false;
            run.start(1, AT, AT.plusSeconds(30)); runs.update(run, 1); return true;
        }));
    }
    private AssistModelPort.Source source(String id) { String content = json.write(Map.of("synthetic", id)); return new AssistModelPort.Source(new AssistInput.Reference(id, AssistConfiguration.digest(content)), "合成费用来源", content); }
    private ExpenseRiskRun.Context contextWithTamperedSource(ExpenseRiskRun run) {
        var context = run.context(); var input = context.input(); var sources = new ArrayList<>(input.sources()); var original = sources.get(0);
        sources.set(0, new AssistModelPort.Source(original.reference(), original.label(), "{\"tampered\":true}"));
        return new ExpenseRiskRun.Context(context.id(), context.tenantId(), context.requestedBy(), context.taskId(), context.createdAt(),
                new ExpenseRiskInput(input.documents(), input.calendar(), input.concerns(), sources), context.targetDigest());
    }
    private static ExpenseRiskSuggestion suggestion(ExpenseRiskInput input) { return new ExpenseRiskSuggestion("synthetic", "v1", ExpenseRiskRun.PROMPT_VERSION,
            List.of(new ExpenseRiskSuggestion.Item(CONCERN, ExpenseRiskInput.Kind.CROSS_DOCUMENT, "所选单据包含同类费用", "只限已选范围，不能认定规避审批", List.of("人工核对行程与用途"),
                    input.sources().stream().map(AssistModelPort.Source::reference).toList()))); }
    private ExpenseRiskRun loaded(ExpenseRiskRun run) { return runs.find(TENANT, run.context().id()).orElseThrow().run(); }
    private List<Long> versions(ExpenseRiskRun run) { return jdbc.queryForList(
                "SELECT run_version FROM agent_expense_risk_transition WHERE run_id=? ORDER BY"
                        + " run_version", Long.class, run.context().id().toString()); }
    private List<List<Map<String, Object>>> businessRows() { return BUSINESS_TABLES.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1,2")).toList(); }
    private void assertEmptyRiskTables() { for (var table : List.of("agent_expense_risk_run", "agent_expense_risk_document", "agent_expense_risk_transition")) assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero(); }
    private static void error(String code, Runnable action) { assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, failure -> assertThat(failure.code()).isEqualTo(code)); }
}
