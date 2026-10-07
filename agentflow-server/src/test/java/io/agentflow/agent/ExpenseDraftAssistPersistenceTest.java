package io.agentflow.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.ExpenseContent;
import io.agentflow.expense.ExpenseLine;
import io.agentflow.finance.FinanceCatalog;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
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
import static org.assertj.core.api.Assertions.*;

/**
 * 真实 JDBC 事务验证原快照、并发领取、租约、人工选择和追加轨迹的原子持久化。
 * @author owlzhangfq@gmail.com
 */
class ExpenseDraftAssistPersistenceTest {
    private static final Instant AT = Instant.parse("2026-10-04T02:00:00Z");
    private static final String TENANT = "retained";
    private final UUID report = UUID.randomUUID();
    private final UUID application = UUID.randomUUID();
    private final JsonUtil json = new JsonUtil(new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    private JdbcTemplate jdbc;
    private JdbcExpenseDraftAssistRepository runs;
    private TransactionTemplate tx;

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:expense-draft-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).load().migrate(); jdbc = new JdbcTemplate(source);
        var manager = new DataSourceTransactionManager(source); tx = new TransactionTemplate(manager);
        var proxy = new ProxyFactory(new JdbcExpenseDraftAssistRepository(jdbc, json));
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        runs = (JdbcExpenseDraftAssistRepository) proxy.getProxy();
        jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id) VALUES(?,'retained',?,'fixture',1,'alice','保留申请','{}','DRAFT',1,1,'EXPENSE',?)", application.toString(), "DRAFT-" + application, report.toString());
        jdbc.update("INSERT INTO expense_report(id,tenant_id,application_id,employee_id,version,state_json) VALUES(?,'retained',?,'alice',1,'{\"retained\":true}')", report.toString(), application.toString());
        jdbc.update("INSERT INTO expense_report_revision(tenant_id,report_id,financial_version,actor_id,operation,state_json) SELECT tenant_id,id,version,employee_id,'CREATE',state_json FROM expense_report WHERE id=?", report.toString());
    }

    @Test void retainsFullInputOutputAndSelectedPartsWithoutChangingFinancialOrApplicationRows() {
        var tables = List.of("approval_application", "expense_report", "expense_report_revision");
        var before = tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table)).toList();
        var run = completed(); var original = run.context();
        run.confirm(3, 1, 1, "alice", List.of(new ExpenseDraftAssistRun.Selection("line1", Set.of(ExpenseDraftAssistRun.Part.ITINERARY))), "仅确认行程", AT.plusSeconds(3));
        tx.executeWithoutResult(ignored -> runs.update(run, 3));
        var restored = new JdbcExpenseDraftAssistRepository(jdbc, json).find(TENANT, run.context().id()).orElseThrow();
        assertThat(restored.context()).isEqualTo(original); assertThat(restored.state()).isEqualTo(run.state());
        assertThat(restored.state().review().selected().get(0).parts()).containsExactly(ExpenseDraftAssistRun.Part.ITINERARY);
        assertThat(versions(run)).containsExactly(1L, 2L, 3L, 4L);
        var next = fresh(); tx.executeWithoutResult(ignored -> runs.create(next));
        var page = runs.page(TENANT, report, 0, 1); var second = runs.page(TENANT, report, 1, 1);
        assertThat(page.total()).isEqualTo(2); assertThat(page.items()).hasSize(1); assertThat(second.items()).hasSize(1);
        assertThat(second.items().get(0).id()).isNotEqualTo(page.items().get(0).id());
        assertThat(json.write(page)).doesNotContain("sources", "现场调研", "suggestion", "costCenters");
        assertThat(runs.find("foreign", run.context().id())).isEmpty(); assertThat(runs.page("foreign", report, 0, 20).items()).isEmpty();
        assertThat(runs.page(TENANT, UUID.randomUUID(), 0, 20).items()).isEmpty();
        Boolean foreignLock = tx.execute(ignored -> runs.lock("foreign", run.context().id()));
        assertThat(foreignLock).isFalse();
        assertThat(tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table)).toList()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"application-version", "financial-version", "in-approval", "owner", "tenant", "application"})
    void queueRequiresTheOriginalOwnerEditableApplicationAndBothCurrentVersions(String variant) {
        var initial = fresh(); var c = initial.context(); var i = c.input();
        if (variant.equals("application-version")) jdbc.update("UPDATE approval_application SET version=2 WHERE id=?", application.toString());
        if (variant.equals("financial-version")) jdbc.update("UPDATE expense_report SET version=2 WHERE id=?", report.toString());
        if (variant.equals("in-approval")) jdbc.update("UPDATE approval_application SET status='IN_APPROVAL' WHERE id=?", application.toString());
        if (variant.equals("application")) i = new ExpenseDraftAssistInput(i.reportId(), UUID.randomUUID(), 1, 1, i.legalEntityId(), i.reportType(),
                i.catalogVersion(), i.validUntil(), i.financeTargetDigest(), i.itinerary(), i.options(), i.sources());
        var run = new ExpenseDraftAssistRun(new ExpenseDraftAssistRun.Context(c.id(), variant.equals("tenant") ? "foreign" : TENANT,
                variant.equals("owner") ? "admin" : "alice", AT, i, c.targetDigest()));
        assertCode(() -> tx.executeWithoutResult(ignored -> runs.create(run)), "AGENT_INPUT_CHANGED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_expense_draft_run", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_expense_draft_transition", Long.class)).isZero();
    }

    @Test void writesRequireACallerTransactionAndStaleVersionsCannotAppendHistory() {
        var run = fresh(); assertThatThrownBy(() -> runs.create(run)).isInstanceOf(IllegalTransactionStateException.class);
        tx.executeWithoutResult(ignored -> runs.create(run));
        assertThatThrownBy(() -> runs.lock(TENANT, run.context().id())).isInstanceOf(IllegalTransactionStateException.class);
        var stale = loaded(run); run.start(1, AT, AT.plusSeconds(60)); stale.start(1, AT, AT.plusSeconds(60));
        assertThatThrownBy(() -> runs.update(run, 1)).isInstanceOf(IllegalTransactionStateException.class);
        tx.executeWithoutResult(ignored -> runs.update(run, 1));
        assertCode(() -> tx.executeWithoutResult(ignored -> runs.update(stale, 1)), "CONCURRENCY_CONFLICT");
        assertThat(versions(run)).containsExactly(1L, 2L);
    }

    @Test void callerCannotSwapTheFrozenModelDestinationDuringAStateUpdate() {
        var run = fresh(); tx.executeWithoutResult(ignored -> runs.create(run)); var c = run.context();
        var replacement = new ExpenseDraftAssistRun(new ExpenseDraftAssistRun.Context(c.id(), c.tenantId(), c.requestedBy(), c.createdAt(), c.input(), "f".repeat(64)));
        replacement.start(1, AT, AT.plusSeconds(60));
        assertCode(() -> tx.executeWithoutResult(ignored -> runs.update(replacement, 1)), "CONCURRENCY_CONFLICT");
        assertThat(loaded(run).context()).isEqualTo(c); assertThat(versions(run)).containsExactly(1L);
    }

    @Test void duplicateActiveQueueAndLockedConcurrentClaimsHaveOneWinner() throws Exception {
        var executor = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            java.util.concurrent.Callable<String> create = () -> {
                start.await();
                try { tx.executeWithoutResult(ignored -> runs.create(fresh())); return "CREATED"; }
                catch (DomainException rejected) { return rejected.code(); }
            };
            var first = executor.submit(create); var second = executor.submit(create); start.countDown();
            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))).containsExactlyInAnyOrder("CREATED", "AGENT_RUN_ACTIVE");
            UUID id = runs.due(AT).get(0).id(); var claimStart = new CountDownLatch(1);
            java.util.concurrent.Callable<Boolean> claim = () -> {
                claimStart.await();
                return tx.execute(ignored -> {
                    runs.lock(TENANT, id); var run = runs.find(TENANT, id).orElseThrow();
                    if (run.state().status() != ExpenseDraftAssistRun.Status.QUEUED) return false;
                    run.start(1, AT, AT.plusSeconds(60)); runs.update(run, 1); return true;
                });
            };
            var firstClaim = executor.submit(claim); var secondClaim = executor.submit(claim); claimStart.countDown();
            assertThat(List.of(firstClaim.get(5, TimeUnit.SECONDS), secondClaim.get(5, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
            assertThat(versions(runs.find(TENANT, id).orElseThrow())).containsExactly(1L, 2L);
        } finally { executor.shutdownNow(); }
    }

    @Test void reloadedRunningLeaseExpiresOnceAndLateCompletionCannotReplaceFailure() {
        var run = fresh(); tx.executeWithoutResult(ignored -> runs.create(run)); run.start(1, AT, AT.plusSeconds(60));
        tx.executeWithoutResult(ignored -> runs.update(run, 1));
        var reopened = new JdbcExpenseDraftAssistRepository(jdbc, json); var restored = reopened.find(TENANT, run.context().id()).orElseThrow();
        assertThat(reopened.due(AT.plusSeconds(59))).isEmpty(); assertThat(reopened.due(AT.plusSeconds(60))).hasSize(1);
        assertThat(restored.expired(AT.plusSeconds(60))).isTrue(); restored.fail(2, AssistRun.Failure.MODEL_TIMEOUT, AT.plusSeconds(60));
        tx.executeWithoutResult(ignored -> runs.update(restored, 2));
        run.complete(2, suggestion(run.context().input()), AT.plusSeconds(2));
        assertCode(() -> tx.executeWithoutResult(ignored -> runs.update(run, 2)), "CONCURRENCY_CONFLICT");
        assertThat(loaded(restored).state().failure()).isEqualTo(AssistRun.Failure.MODEL_TIMEOUT); assertThat(reopened.due(AT.plusSeconds(120))).isEmpty();
    }

    @Test void failedReviewTraceRollsBackStatusAndRetryPreservesTheOriginalSuggestion() {
        var run = completed(); var original = loaded(run).state();
        run.confirm(3, 1, 1, "alice", List.of(new ExpenseDraftAssistRun.Selection("line1", Set.of(ExpenseDraftAssistRun.Part.ITINERARY))), null, AT.plusSeconds(3));
        jdbc.execute("ALTER TABLE agent_expense_draft_transition ADD CONSTRAINT ck_draft_fixture_review CHECK (status<>'CONFIRMED')");
        try {
            assertThatThrownBy(() -> tx.executeWithoutResult(ignored -> runs.update(run, 3))).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(loaded(run).state()).isEqualTo(original); assertThat(versions(run)).containsExactly(1L, 2L, 3L);
        } finally { jdbc.execute("ALTER TABLE agent_expense_draft_transition DROP CONSTRAINT ck_draft_fixture_review"); }
        tx.executeWithoutResult(ignored -> runs.update(run, 3));
        assertThat(loaded(run).state()).isEqualTo(run.state()); assertThat(versions(run)).containsExactly(1L, 2L, 3L, 4L);
    }

    @Test void failedInitialTraceDoesNotLeaveAQueuedRowOrConsumeTheActiveSlot() {
        var run = fresh(); jdbc.execute("ALTER TABLE agent_expense_draft_transition ADD CONSTRAINT ck_draft_fixture_queue CHECK (status<>'QUEUED')");
        try {
            assertThatThrownBy(() -> tx.executeWithoutResult(ignored -> runs.create(run))).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(runs.find(TENANT, run.context().id())).isEmpty(); assertThat(runs.due(AT)).isEmpty();
        } finally { jdbc.execute("ALTER TABLE agent_expense_draft_transition DROP CONSTRAINT ck_draft_fixture_queue"); }
        tx.executeWithoutResult(ignored -> runs.create(run)); assertThat(versions(run)).containsExactly(1L);
    }

    @ParameterizedTest @ValueSource(strings = {"application-version", "created-at", "lease", "state"})
    void inconsistentPersistedIndexOrStateFailsRestoration(String variant) {
        var run = fresh(); tx.executeWithoutResult(ignored -> runs.create(run));
        if (variant.equals("application-version")) jdbc.update("UPDATE agent_expense_draft_run SET application_version=2 WHERE id=?", run.context().id().toString());
        if (variant.equals("created-at")) jdbc.update("UPDATE agent_expense_draft_run SET created_at=? WHERE id=?", java.sql.Timestamp.from(AT.plusSeconds(1)), run.context().id().toString());
        if (variant.equals("lease")) {
            run.start(1, AT, AT.plusSeconds(60)); tx.executeWithoutResult(ignored -> runs.update(run, 1));
            jdbc.update("UPDATE agent_expense_draft_run SET lease_until=? WHERE id=?", java.sql.Timestamp.from(AT.plusSeconds(61)), run.context().id().toString());
        }
        if (variant.equals("state")) jdbc.update("UPDATE agent_expense_draft_run SET state_json=? WHERE id=?",
                json.write(new ExpenseDraftAssistRun.State(ExpenseDraftAssistRun.Status.CONFIRMED, 4, null, null, null, null, null, null)), run.context().id().toString());
        assertThatThrownBy(() -> runs.find(TENANT, run.context().id())).isInstanceOf(IllegalStateException.class);
    }

    private ExpenseDraftAssistRun completed() {
        var run = fresh(); tx.executeWithoutResult(ignored -> runs.create(run)); run.start(1, AT, AT.plusSeconds(60));
        tx.executeWithoutResult(ignored -> runs.update(run, 1)); run.complete(2, suggestion(run.context().input()), AT.plusSeconds(2));
        tx.executeWithoutResult(ignored -> runs.update(run, 2)); return run;
    }
    private ExpenseDraftAssistRun fresh() {
        var leg = new ExpenseDraftAssistInput.Leg(1, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-03"), "SH", "现场调研");
        var options = new ExpenseDraftAssistInput.Options(List.of(new FinanceCatalog.Category("HOTEL", "住宿", List.of(ExpenseLine.Unit.NIGHT))),
                List.of(new ExpenseDraftAssistInput.Choice("IT", "研发")), List.of(), List.of(new ExpenseDraftAssistInput.Choice("SH", "上海")));
        var input = new ExpenseDraftAssistInput(report, application, 1, 1, UUID.randomUUID(), ExpenseContent.Type.TRAVEL, "catalog-v1", AT.plusSeconds(300),
                "b".repeat(64), List.of(leg), options, List.of(source(ExpenseDraftAssistInput.BRIEF, "整理行程住宿"),
                source(ExpenseDraftAssistInput.CATALOG, options), source(leg.sourceId(), leg)));
        return new ExpenseDraftAssistRun(new ExpenseDraftAssistRun.Context(UUID.randomUUID(), TENANT, "alice", AT, input, "a".repeat(64)));
    }
    private AssistModelPort.Source source(String id, Object value) {
        String content = json.write(value); return new AssistModelPort.Source(new AssistInput.Reference(id, AssistConfiguration.digest(content)), "合成来源", content);
    }
    private static ExpenseDraftSuggestion suggestion(ExpenseDraftAssistInput input) {
        return new ExpenseDraftSuggestion("synthetic", "v1", ExpenseDraftAssistRun.PROMPT_VERSION, List.of(new ExpenseDraftSuggestion.Line("line1", 1,
                "HOTEL", ExpenseLine.Unit.NIGHT, "现场调研住宿", List.of(new ExpenseDraftSuggestion.Allocation("IT", null, new BigDecimal("100"))),
                List.of(input.reference("expense:itinerary[1]"), input.reference(ExpenseDraftAssistInput.CATALOG)))));
    }
    private ExpenseDraftAssistRun loaded(ExpenseDraftAssistRun run) { return runs.find(TENANT, run.context().id()).orElseThrow(); }
    private List<Long> versions(ExpenseDraftAssistRun run) {
        return jdbc.queryForList("SELECT run_version FROM agent_expense_draft_transition WHERE tenant_id=? AND run_id=? ORDER BY run_version", Long.class, TENANT, run.context().id().toString());
    }
    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, failure -> assertThat(failure.code()).isEqualTo(code));
    }
}
