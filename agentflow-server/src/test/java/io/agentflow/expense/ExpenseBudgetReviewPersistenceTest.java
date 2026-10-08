package io.agentflow.expense;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.finance.*;
import io.agentflow.organization.InitiatorContext;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 真实 JDBC 复核每轮不可变来源、并发版本与事务回滚；原生任务编排由后续流程测试验证。
 *
 * @author owlzhangfq@gmail.com
 */
class ExpenseBudgetReviewPersistenceTest {
    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
    private static final LocalDate DATE = LocalDate.of(2026, 10, 4);
    private static final String TENANT = "review-fixture", TARGET = "a".repeat(64);
    private final JsonUtil json = new JsonUtil(new ObjectMapper().registerModule(new JavaTimeModule())
            .registerModule(new FinanceJsonConfiguration().financeMoneyModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private JdbcExpenseReportRepository reports;
    private JdbcExpensePrecheckRepository prechecks;
    private JdbcExpenseSubmissionControlRepository controls;
    private JdbcBudgetOperationRepository operations;
    private JdbcBudgetOccupationRepository occupations;
    private JdbcExpenseBudgetReviewRepository reviews;

    @BeforeEach void database() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        Flyway.configure().dataSource(source).load().migrate(); jdbc = new JdbcTemplate(source); tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        reports = new JdbcExpenseReportRepository(
                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                (jdbc).getDataSource(),
                                io.agentflow.expense.mapper.ExpenseReportRepositoryMapper.class), json); prechecks = new JdbcExpensePrecheckRepository(
                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                (jdbc).getDataSource(),
                                io.agentflow.expense.mapper.ExpensePrecheckRepositoryMapper.class), json);
        controls = new JdbcExpenseSubmissionControlRepository(
                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                (jdbc).getDataSource(),
                                io.agentflow.expense.mapper.ExpenseSubmissionControlRepositoryMapper
                                        .class), json); operations = new JdbcBudgetOperationRepository(
                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                (jdbc).getDataSource(),
                                io.agentflow.finance.mapper.BudgetOperationRepositoryMapper.class), json);
        occupations = new JdbcBudgetOccupationRepository(
                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                (jdbc).getDataSource(),
                                io.agentflow.finance.mapper.BudgetOccupationRepositoryMapper.class), json); reviews = repository();
    }

    @Test void budgetRecoveryFollowsAuthorizedOperationInsteadOfSubmissionThread() {
        String submitted = UUID.randomUUID().toString(), authorized = UUID.randomUUID().toString(); ExpenseBudgetReview initial;
        try (var scope = new io.agentflow.observability.DiagnosticContext(submitted, TENANT).open()) { initial = fixture(); }
        var refused = finish(initial, false); var required = initial.observe(refused, NOW.plusSeconds(2)); tx.executeWithoutResult(status -> reviews.update(required));
        UUID audit = UUID.randomUUID(); var approved = required.authorize(refused, UUID.randomUUID(), "budget-task", "owner", audit, NOW.plusSeconds(3));
        try (var scope = new io.agentflow.observability.DiagnosticContext(authorized, TENANT).open()) {
            tx.executeWithoutResult(status -> { audit(required, audit, "budget-task", "owner", "APPROVE", NOW.plusSeconds(3)); register(approved, refused); reviews.update(approved); });
        }
        tx.executeWithoutResult(status -> {
            var queued = operations.find(TENANT, approved.authorizedOperationId()).orElseThrow(); var claimed = queued.claim(NOW.plusSeconds(3), Duration.ofSeconds(15)); operations.update(claimed);
            var command = claimed.input().command();
            var result = claimed.complete(new FinanceResult.Success<>(new BudgetObservation(command.id(), command.digest(), BudgetObservation.Status.APPLIED, 1L, "ledger-2", NOW.plusSeconds(4), null)), NOW.plusSeconds(4));
            assertThat(result.status()).isEqualTo(BudgetOperation.Status.APPLIED); operations.update(result);
        });
        assertThat(reviews.due(NOW.plusSeconds(5))).hasSize(1);
        var recovery = org.mockito.Mockito.mock(io.agentflow.approval.process.ExpenseBudgetReviewRecovery.class); var observed = new java.util.ArrayList<String>();
        org.mockito.Mockito.doAnswer(invocation -> { observed.add(org.slf4j.MDC.get("traceId")); return null; }).when(recovery).recover(org.mockito.ArgumentMatchers.any());
        new ExpenseBudgetReviewWorker(repository(), recovery).poll();
        assertThat(observed).containsExactly(authorized);
        assertThat(jdbc.queryForObject(
                                "SELECT trace_id FROM expense_budget_review WHERE tenant_id=? AND"
                                        + " report_id=? AND round_no=1", String.class, TENANT, initial.input().reportId().toString())).isEqualTo(submitted);
    }

    @Test void staleBudgetRecoveryCannotAdvanceAReplacementAuthorization() {
        var initial = fixture(); var refused = finish(initial, false);
        var stale = reviews.due(NOW.plusSeconds(2)).get(0);
        var required = initial.observe(refused, NOW.plusSeconds(2)); tx.executeWithoutResult(status -> reviews.update(required));
        UUID audit = UUID.randomUUID(); var approved = required.authorize(refused, UUID.randomUUID(), "budget-task", "owner", audit, NOW.plusSeconds(3));
        tx.executeWithoutResult(status -> { audit(required, audit, "budget-task", "owner", "APPROVE", NOW.plusSeconds(3)); register(approved, refused); reviews.update(approved); });
        tx.executeWithoutResult(status -> {
            var queued = operations.find(TENANT, approved.authorizedOperationId()).orElseThrow(); var claimed = queued.claim(NOW.plusSeconds(3), Duration.ofSeconds(15)); operations.update(claimed);
            var command = claimed.input().command();
            operations.update(claimed.complete(new FinanceResult.Success<>(new BudgetObservation(command.id(), command.digest(), BudgetObservation.Status.APPLIED,
                    1L, "replacement-ledger", NOW.plusSeconds(4), null)), NOW.plusSeconds(4)));
        });
        var application = mock(io.agentflow.approval.model.Application.class);
        when(application.id()).thenReturn(initial.input().applicationId()); when(application.tenantId()).thenReturn(TENANT);
        when(application.status()).thenReturn(io.agentflow.approval.model.ApplicationStatus.IN_APPROVAL); when(application.roundNo()).thenReturn(1);
        when(application.runtimeDefinitionId()).thenReturn("original-definition");
        var applications = mock(io.agentflow.approval.repository.ApplicationRepository.class);
        when(applications.findById(TENANT, application.id())).thenReturn(java.util.Optional.of(application));
        var completion = mock(io.agentflow.approval.process.ApprovalCompletionService.class);
        when(completion.lockForProgress(application)).thenReturn(new io.agentflow.approval.SubprocessExecutionLocks.LockedPath(List.of(application),
                io.agentflow.approval.SubprocessExecutionLocks.AncestorState.ACTIVE));
        var round = mock(io.agentflow.approval.model.SubmissionRound.class);
        when(round.processInstanceId()).thenReturn("original-instance"); when(round.status()).thenReturn(io.agentflow.approval.model.SubmissionRound.Status.IN_APPROVAL);
        var rounds = mock(io.agentflow.approval.repository.SubmissionRoundRepository.class);
        when(rounds.findByRound(TENANT, application.id(), 1)).thenReturn(java.util.Optional.of(round));
        var runtime = mock(org.flowable.engine.RuntimeService.class, RETURNS_DEEP_STUBS);
        var instance = runtime.createProcessInstanceQuery().processInstanceId("original-instance").singleResult();
        when(instance.getTenantId()).thenReturn(TENANT); when(instance.getProcessDefinitionId()).thenReturn("original-definition");
        var outcomes = mock(ExpenseBudgetOutcomeHandler.class);
        var recovery = new io.agentflow.approval.process.ExpenseBudgetReviewRecovery(applications, rounds, reviews, operations, outcomes, completion,
                mock(io.agentflow.approval.process.ExpenseBudgetReviewProgress.class), runtime, mock(io.agentflow.approval.process.SubprocessProgressService.class),
                mock(io.agentflow.notification.ApprovalNotificationService.class));
        recovery.recover(stale);
        verifyNoInteractions(outcomes);
        recovery.recover(reviews.due(NOW.plusSeconds(5)).get(0));
        verify(outcomes).completed(any());
    }

    @Test void restoredRecordKeepsOriginalSourcesAndDatabaseRejectsAnotherReportOperation() {
        var first = fixture(); var other = fixture();
        assertThat(repository().find(TENANT, first.input().reportId(), 1)).contains(first);
        assertThat(reviews.find("foreign", first.input().reportId(), 1)).isEmpty();
        assertThat(reviews.find(TENANT, first.input().reportId(), 2)).isEmpty();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> reviews.create(first))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                                        "UPDATE expense_budget_review SET original_operation_id=?"
                                                + " WHERE tenant_id=? AND report_id=?",
                other.input().originalOperationId().toString(), TENANT, first.input().reportId().toString())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                                        "UPDATE expense_budget_review SET"
                                            + " status='AUTHORIZED',version=3 WHERE tenant_id=? AND"
                                            + " report_id=?",
                TENANT, first.input().reportId().toString())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void authorizationRequiresActualAuditAndNewOperationAndSurvivesRepositoryRecreation() {
        var initial = fixture(); var refused = finish(initial, false); var required = initial.observe(refused, NOW.plusSeconds(2));
        tx.executeWithoutResult(status -> reviews.update(required));
        UUID audit = UUID.randomUUID();
        var approved = required.authorize(refused, UUID.randomUUID(), "budget-task", "owner", audit, NOW.plusSeconds(3));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> reviews.update(approved))).isInstanceOf(IllegalStateException.class);
        tx.executeWithoutResult(status -> { audit(required, audit, "budget-task", "owner", "APPROVE", NOW.plusSeconds(3)); register(approved, refused); reviews.update(approved); });
        assertThat(repository().find(TENANT, initial.input().reportId(), 1)).contains(approved);
        assertThat(operations.find(TENANT, approved.authorizedOperationId()).orElseThrow().input().command()).isEqualTo(approved.retryCommand(refused));
        assertThat(revisions(initial)).containsExactly(1L, 2L, 3L);
        jdbc.update("UPDATE audit_event SET actor_id='other-owner' WHERE tenant_id=? AND event_id=?", TENANT, audit.toString());
        assertThatThrownBy(() -> repository().find(TENANT, initial.input().reportId(), 1)).isInstanceOf(IllegalStateException.class);
    }

    @Test void laterFailureRollsBackAuditAuthorizedBudgetAndReviewTogether() {
        var initial = fixture(); var refused = finish(initial, false); var required = initial.observe(refused, NOW.plusSeconds(2));
        tx.executeWithoutResult(status -> reviews.update(required));
        UUID audit = UUID.randomUUID(); var approved = required.authorize(refused, UUID.randomUUID(), "budget-task", "owner", audit, NOW.plusSeconds(3));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            audit(required, audit, "budget-task", "owner", "APPROVE", NOW.plusSeconds(3)); register(approved, refused); reviews.update(approved);
            throw new IllegalStateException("synthetic subsequent transaction failure");
        })).hasMessage("synthetic subsequent transaction failure");
        assertThat(reviews.find(TENANT, initial.input().reportId(), 1)).contains(required);
        assertThat(operations.find(TENANT, approved.authorizedOperationId())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE tenant_id=? AND event_id=?", Integer.class, TENANT, audit.toString())).isZero();
        assertThat(occupations.find(TENANT, initial.input().reportId()).orElseThrow().pendingOperationId()).isNull();
        assertThat(revisions(initial)).containsExactly(1L, 2L);
    }

    @Test void concurrentOriginalResultUpdatesHaveOneWinnerAndNoDuplicateRevision() throws Exception {
        var initial = fixture(); var refused = finish(initial, false); var required = initial.observe(refused, NOW.plusSeconds(2));
        var gate = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<String> attempt = () -> {
                assertThat(gate.await(5, TimeUnit.SECONDS)).isTrue();
                try { tx.executeWithoutResult(status -> reviews.update(required)); return "saved"; }
                catch (DomainException conflict) { return conflict.code(); }
            };
            var first = pool.submit(attempt); var second = pool.submit(attempt); gate.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder("saved", "CONCURRENCY_CONFLICT");
        } finally { gate.countDown(); pool.shutdownNow(); }
        assertThat(revisions(initial)).containsExactly(1L, 2L);
    }

    @Test void automaticPassHasSourceAuditAndClosedRoundDoesNotReenterRecovery() {
        var initial = fixture(); var applied = finish(initial, true); var confirmed = initial.observe(applied, NOW.plusSeconds(2));
        tx.executeWithoutResult(status -> reviews.update(confirmed));
        assertThat(reviews.due(NOW.plusSeconds(2))).hasSize(1);
        var candidate = reviews.due(NOW.plusSeconds(2)).get(0);
        tx.executeWithoutResult(status -> reviews.reschedule(candidate, NOW.plusSeconds(12)));
        assertThat(reviews.due(NOW.plusSeconds(3))).isEmpty(); assertThat(revisions(initial)).containsExactly(1L, 2L);
        UUID audit = UUID.randomUUID(); var passed = confirmed.pass("budget-task", audit, NOW.plusSeconds(3));
        tx.executeWithoutResult(status -> { audit(initial, audit, "budget-task", "system:budget", "AUTO_PASSED_BUDGET", NOW.plusSeconds(3)); reviews.update(passed); });
        assertThat(reviews.due(NOW.plusSeconds(20))).isEmpty();
        var closed = passed.close(ExpenseBudgetReview.Closure.WITHDRAWN, NOW.plusSeconds(4));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> reviews.update(closed))).isInstanceOf(IllegalStateException.class);
        tx.executeWithoutResult(status -> {
            jdbc.update(
                            "UPDATE approval_submission_round SET"
                                + " status='WITHDRAWN',reason='合成撤回',completed_by='alice',completed_at=?"
                                + " WHERE tenant_id=? AND application_id=? AND round_no=1",
                    Timestamp.from(NOW.plusSeconds(4)), TENANT, initial.input().applicationId().toString());
            reviews.update(closed);
        });
        assertThat(repository().find(TENANT, initial.input().reportId(), 1)).contains(closed);
        assertThat(reviews.due(NOW.plusSeconds(30))).isEmpty();
    }

    @Test void matchingStateAndIndexTamperingStillFailsAgainstOriginalPrecheckPolicy() {
        var initial = fixture();
        var state = json.read(json.write(initial), com.fasterxml.jackson.databind.node.ObjectNode.class);
        ((com.fasterxml.jackson.databind.node.ObjectNode) state.at("/input/policy")).put("reference", "forged-policy");
        jdbc.update(
                "UPDATE expense_budget_review SET policy_reference=?,input_json=?,state_json=?"
                        + " WHERE tenant_id=? AND report_id=?",
                "forged-policy", state.path("input").toString(), state.toString(), TENANT, initial.input().reportId().toString());
        assertThatThrownBy(() -> repository().find(TENANT, initial.input().reportId(), 1)).isInstanceOf(IllegalStateException.class);
    }

    private ExpenseBudgetReview fixture() {
        return tx.execute(status -> {
            UUID reportId = UUID.randomUUID(), app = UUID.randomUUID(), entity = UUID.randomUUID(), checkedId = UUID.randomUUID();
            jdbc.update(
                            "INSERT INTO"
                                + " approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id)"
                                + " VALUES(?,?,?,'fixture',1,'alice','合成预算例外','{}','DRAFT',1,1,'EXPENSE',?)", app.toString(), TENANT, "REVIEW-"+reportId, reportId.toString());
            var line = new ExpenseLine(1, "TRAVEL", DATE, null, "SH", BigDecimal.ONE, ExpenseLine.Unit.ITEM, money("100"), money("0"), List.of(), null,
                    List.of(new CostAllocation("IT", null, money("100"))), "合成明细", null);
            var report = ExpenseReport.draft(reportId, TENANT, app, "alice", new ExpenseContent(entity, ExpenseContent.Type.DAILY, "合成预算", List.of(line), List.of()));
            reports.create(report, "alice"); var preview = ExpenseReport.restore(report.state()); freeze(preview, NOW.minusSeconds(8));
            var policy = new BudgetExceptionPolicy("policy-flex-1");
            var assessment = new BudgetPrecheckPort.Assessment(BudgetPrecheckPort.Request.from(preview, DATE), "precheck-1", NOW.minusSeconds(8), NOW.plusSeconds(60), policy);
            var evidence = new ExpensePrecheckEvidence("catalog-1", new FinanceCatalog.LegalEntity(entity, "合成法人", "CNY", false, "entity-1", "Asia/Shanghai"), DATE,
                    assessment, preview.currentRound(), List.of(), List.of(), NOW.plusSeconds(60));
            var initiator = new InitiatorContext(UUID.randomUUID(), UUID.randomUUID(), "alice", 1, entity, "合成法人", UUID.randomUUID(), "部门", UUID.randomUUID(), "岗位");
            var check = ExpensePrecheckJob.queue(new ExpensePrecheckJob.Input(checkedId, TENANT, reportId, app, "alice", 1, 1, 1, 1, initiator, DATE, TARGET), NOW.minusSeconds(10));
            prechecks.create(check); check = check.start(NOW.minusSeconds(9), NOW.plusSeconds(30)); prechecks.update(check);
            prechecks.update(check.finish(new ExpensePrecheckJob.Result(evidence, List.of()), NOW.minusSeconds(7)));
            freeze(report, NOW); reports.update(report, 1, "alice", "SUBMIT");
            jdbc.update(
                            "UPDATE approval_application SET status='IN_APPROVAL' WHERE tenant_id=?"
                                    + " AND id=?", TENANT, app.toString());
            jdbc.update(
                            "INSERT INTO"
                                + " approval_submission_round(tenant_id,application_id,round_no,process_instance_id,definition_version,title,payload_json,submitted_by,submitted_at,status)"
                                + " VALUES(?,?,1,?,1,'合成轮次','{}','alice',?,'IN_APPROVAL')", TENANT, app.toString(), UUID.randomUUID().toString(), Timestamp.from(NOW));
            controls.create(ExpenseSubmissionControl.submitted(new ExpenseSubmissionControl.Input(TENANT, reportId, app, "alice", 1, 2, checkedId, DATE, false,
                    Map.of("budget", ExpenseProcessPolicy.Stage.BUDGET_REVIEW, "finance", ExpenseProcessPolicy.Stage.FINANCE_REVIEW)), NOW));
            var command = new BudgetCommand(UUID.randomUUID(), TENANT, BudgetCommand.Action.FREEZE, BudgetPrecheckPort.Request.fromCurrent(report, DATE), null);
            var original = BudgetOperation.queue(new BudgetOperation.Input(command, TARGET), NOW);
            occupations.create(BudgetOccupation.begin(original.input())); operations.create(original);
            var value = ExpenseBudgetReview.submitted(new ExpenseBudgetReview.Input(TENANT, reportId, app, "alice", 1, 2, checkedId, "budget", policy, command.id(), TARGET), NOW);
            reviews.create(value); return value;
        });
    }
    private BudgetOperation finish(ExpenseBudgetReview value, boolean applied) {
        return tx.execute(status -> {
            var original = operations.find(TENANT, value.input().originalOperationId()).orElseThrow(); var command = original.input().command();
            var claimed = original.claim(NOW, Duration.ofSeconds(15)); operations.update(claimed);
            var observed = applied ? new BudgetObservation(command.id(), command.digest(), BudgetObservation.Status.APPLIED, 1L, "ledger-1", NOW.plusSeconds(1), null)
                    : new BudgetObservation(command.id(), command.digest(), BudgetObservation.Status.REJECTED, null, null, null,
                            BudgetObservation.Rejection.BUDGET_EXCEPTION_REQUIRED, new BudgetExceptionOffer("policy-flex-1", "offer-1"));
            var result = claimed.complete(new FinanceResult.Success<>(observed), NOW.plusSeconds(1)); operations.update(result);
            occupations.update(occupations.find(TENANT, value.input().reportId()).orElseThrow().complete(result)); return result;
        });
    }
    private void register(ExpenseBudgetReview approved, BudgetOperation original) {
        var input = new BudgetOperation.Input(approved.retryCommand(original), TARGET);
        occupations.update(occupations.find(TENANT, approved.input().reportId()).orElseThrow().enqueue(input));
        operations.create(BudgetOperation.queue(input, approved.updatedAt()));
    }
    private void audit(ExpenseBudgetReview value, UUID id, String task, String actor, String action, Instant at) {
        var payload = new java.util.HashMap<String, Object>(Map.of("roundNo", 1, "nodeId", "budget", "actor", actor, "action", action, "applicationId", value.input().applicationId().toString()));
        if (ExpenseBudgetApprovalPolicy.AUTOMATIC_ACTION.equals(action)) {
            var original = operations.find(TENANT, value.input().originalOperationId()).orElseThrow().input().command();
            payload.put("budgetConfirmation", Map.of("operationId", original.id(), "commandDigest", original.digest()));
        }
        jdbc.update(
                "INSERT INTO"
                    + " audit_event(id,tenant_id,event_id,aggregate_type,aggregate_id,aggregate_version,application_id,action,actor_id,payload_json,occurred_at)"
                    + " VALUES(?,?,?,'Task',?,3,?,?,?,?,?)",
                UUID.randomUUID().toString(), TENANT, id.toString(), task, value.input().applicationId().toString(), action, actor, json.write(payload), Timestamp.from(at));
    }
    private List<Long> revisions(ExpenseBudgetReview value) { return jdbc.queryForList(
                "SELECT version FROM expense_budget_review_revision WHERE tenant_id=? AND"
                        + " report_id=? ORDER BY version", Long.class, TENANT, value.input().reportId().toString()); }
    private JdbcExpenseBudgetReviewRepository repository() { return new JdbcExpenseBudgetReviewRepository(
                io.agentflow.mybatis.MyBatisTestSupport.mapper(
                        (jdbc).getDataSource(),
                        io.agentflow.expense.mapper.ExpenseBudgetReviewRepositoryMapper.class), json, operations, controls, prechecks); }
    private void freeze(ExpenseReport report, Instant at) {
        var assessment = new ExpenseAssessment(new ExpenseExchangeRate("CNY", "CNY", BigDecimal.ONE, "rate-1", DATE),
                new ExpensePolicySnapshot(UUID.randomUUID(), 1, money("100"), money("100"), ExpensePolicySnapshot.Decision.WITHIN_LIMIT, "tax-1", "policy-1"), money("0"));
        report.freeze(1, 1, "CNY", new EmployeeAccountSnapshot(report.content().legalEntityId(), "alice", "account-1", "****1234", TARGET, "v1"), Map.of(1, assessment), "alice", at);
    }
    private static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
}
