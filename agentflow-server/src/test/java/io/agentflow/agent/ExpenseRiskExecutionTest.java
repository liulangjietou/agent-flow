package io.agentflow.agent;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.agentflow.auth.DeferredActorAuthentication;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.JdbcExpenseReportRepository;

import jakarta.servlet.http.HttpServletRequest;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 使用实际迁移、仓储和 Spring 事务执行队列；来源授权及模型使用受控桩，不代替真实审批和企业模型验收。
 *
 * @author owlzhangfq@gmail.com
 */
class ExpenseRiskExecutionTest {
    private static final String TENANT = "risk-execution", TASK = "original-decision", CONCERN = "expense:risk[1]";
    private static final UUID PRIMARY = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID COMPARISON = UUID.fromString("00000000-0000-0000-0000-000000000012");
    private static final UUID PRIMARY_APP = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID COMPARISON_APP = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Actor REQUESTER = new Actor(TENANT, "first-reviewer", Set.of("APPROVER"));
    private static final DeferredActorAuthentication.LoginReference LOGIN = new DeferredActorAuthentication.LoginReference(DeferredActorAuthentication.Kind.DEMO_LOGIN, "f".repeat(64));
    private final JsonUtil json = new JsonUtil(new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    private final CurrentActor actors = new CurrentActor();
    private final ExpenseRiskAccess accessTarget = mock(ExpenseRiskAccess.class);
    private final DeferredActorAuthentication authentication = mock(DeferredActorAuthentication.class);
    private final ExpenseRiskModelPort model = mock(ExpenseRiskModelPort.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final ExpenseRiskSources sources = new ExpenseRiskSources(json);
    private final AssistConfiguration configuration = new AssistConfiguration();
    private final AtomicReference<ExpenseRiskSources.Catalog> current = new AtomicReference<>();
    private final ExpenseRiskAccess.Selection selection = new ExpenseRiskAccess.Selection(List.of(
            new ExpenseRiskAccess.SelectedDocument(PRIMARY, 1, List.of(1)), new ExpenseRiskAccess.SelectedDocument(COMPARISON, 1, List.of(1))), null);
    private DataSourceTransactionManager manager;
    private JdbcTemplate jdbc;
    private JdbcExpenseRiskRepository runs;
    private JdbcExpenseReportRepository reportTarget;
    private JdbcExpenseReportRepository reports;
    private ExpenseRiskAccess access;
    private ExpenseRiskService service;
    private ExpenseRiskWorker worker;

    @BeforeEach void databaseAndTransactionalServices() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:risk-execution-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate(); jdbc = new JdbcTemplate(db); manager = new DataSourceTransactionManager(db);
        seed(PRIMARY, PRIMARY_APP); seed(COMPARISON, COMPARISON_APP);
        runs = transactional(new JdbcExpenseRiskRepository(
                                io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                        (jdbc).getDataSource(),
                                        io.agentflow.agent.mapper.ExpenseRiskRepositoryMapper
                                                .class), json));
        reportTarget = spy(new JdbcExpenseReportRepository(
                                io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                        (jdbc).getDataSource(),
                                        io.agentflow.expense.mapper.ExpenseReportRepositoryMapper
                                                .class), json)); reports = transactional(reportTarget);
        access = transactional(accessTarget); current.set(catalog()); actors.set(REQUESTER);
        configuration.setEnabled(true); configuration.setEndpoint("http://127.0.0.1:9/v1/responses"); configuration.setModel("synthetic-risk"); configuration.setTimeoutSeconds(5);
        when(accessTarget.available(any(), any(), anyString(), any(), any())).thenAnswer(call -> current.get());
        when(authentication.available()).thenReturn(true); when(authentication.capture(eq(request), eq(REQUESTER), any())).thenReturn(LOGIN);
        when(authentication.resolve(eq(LOGIN), eq(TENANT), eq(REQUESTER.userId()), any())).thenReturn(Optional.of(REQUESTER));
        when(model.generate(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return suggestion(call.getArgument(0));
        });
        restartServices();
    }

    @AfterEach void closeOwnedDatabaseAndActor() { actors.clear(); if (jdbc != null) jdbc.execute("SHUTDOWN"); }

    @Test void queuedRunSurvivesServiceRecreationAndSendsOnceOutsideTransactions() {
        var before = businessRows(); var receipt = queue(); var persisted = runs.find(TENANT, receipt.id()).orElseThrow();
        assertThat(persisted.login()).isEqualTo(LOGIN); actors.clear(); restartServices(); worker.poll(); worker.poll();
        var run = loaded(receipt); assertThat(run.state().status()).isEqualTo(ExpenseRiskRun.Status.COMPLETED);
        assertThat(run.state().suggestion()).isEqualTo(suggestion(run.context())); assertThat(versions(receipt)).containsExactly(1L, 2L, 3L);
        verify(model, times(1)).generate(run.context()); assertThat(businessRows()).isEqualTo(before);
        var locks = inOrder(reportTarget); locks.verify(reportTarget).lock(TENANT, COMPARISON); locks.verify(reportTarget).lock(TENANT, PRIMARY);
    }

    @Test void previewAndHistoryDoNotSerializeLoginOrLocalSourceIdentity() {
        var options = service.input(PRIMARY, TASK, selection); assertThat(options.enabled()).isTrue();
        assertThat(json.write(options)).doesNotContain(LOGIN.value(), PRIMARY.toString(), COMPARISON.toString(), "snapshotDigest", "requestedBy");
        var receipt = queue(options); worker.poll(); var detail = service.get(PRIMARY, receipt.id());
        assertThat(detail.adoptable()).isTrue(); assertThat(detail.sources()).containsExactlyElementsOf(current.get().sources());
        assertThat(json.write(detail)).doesNotContain(LOGIN.value(), PRIMARY.toString(), COMPARISON.toString(), "authentication_kind", "context");
    }

    @ParameterizedTest @ValueSource(strings = {"application", "financial", "snapshot", "source"})
    void previewVersionOrContentChangesRequireFreshExplicitConsent(String change) {
        var options = service.input(PRIMARY, TASK, selection); changeCatalog(change);
        error("AGENT_INPUT_CHANGED", () -> queue(options)); assertThat(runCount()).isZero(); verify(authentication, never()).capture(any(), any(), any());
    }

    @Test void previewCannotBeBorrowedByAnotherActorOrTask() {
        var options = service.input(PRIMARY, TASK, selection); actors.set(new Actor(TENANT, "second-reviewer", Set.of("APPROVER")));
        error("AGENT_INPUT_CHANGED", () -> queue(options)); actors.set(REQUESTER);
        error("AGENT_INPUT_CHANGED", () -> service.queue(PRIMARY, "different-task", selection, options.inputDigest(), options.targetDigest(), sourceIds(), request));
        assertThat(runCount()).isZero();
    }

    @Test void queueRequiresCurrentDestinationEveryRequiredSourceAndActualLogin() {
        var options = service.input(PRIMARY, TASK, selection);
        error("AGENT_TARGET_CHANGED", () -> service.queue(PRIMARY, TASK, selection, options.inputDigest(), "b".repeat(64), sourceIds(), request));
        error("INVALID_AGENT_INPUT", () -> service.queue(PRIMARY, TASK, selection, options.inputDigest(), options.targetDigest(), List.of(CONCERN), request));
        when(authentication.capture(eq(request), eq(REQUESTER), any())).thenThrow(new DomainException("UNAUTHENTICATED", "Original login ended"));
        error("UNAUTHENTICATED", () -> queue(options)); assertThat(runCount()).isZero();
    }

    @Test void disabledModelMissingSharedAuthenticationAndNoObservationsExposeNoSendableSources() {
        configuration.setEnabled(false); assertUnavailable("AGENT_MODEL_DISABLED"); configuration.setEnabled(true);
        when(authentication.available()).thenReturn(false); assertUnavailable("DEFERRED_AUTHENTICATION_UNAVAILABLE"); when(authentication.available()).thenReturn(true);
        var prior = current.get(); current.set(new ExpenseRiskSources.Catalog(prior.documents(), prior.calendar(), List.of(), prior.sources().subList(0, 3)));
        assertUnavailable("NO_RISK_OBSERVATIONS"); assertThat(runCount()).isZero();
    }

    @Test void revokedOriginalLoginCannotBorrowAnotherRequestActor() {
        var receipt = queue(); actors.set(new Actor(TENANT, "admin", Set.of("ADMIN")));
        when(authentication.resolve(eq(LOGIN), eq(TENANT), eq(REQUESTER.userId()), any())).thenReturn(Optional.empty());
        worker.poll(); assertFailed(receipt, AssistRun.Failure.INPUT_UNAVAILABLE); verifyNoInteractions(model);
    }

    @ParameterizedTest @ValueSource(strings = {"FORBIDDEN", "NOT_FOUND", "AGENT_INPUT_CHANGED"})
    void permissionTransactionRollbackStillCommitsFailureInAnotherTransaction(String code) {
        var receipt = queue();
        when(accessTarget.available(any(), any(), anyString(), any(), any())).thenThrow(new DomainException(code, "Selected source is unavailable"));
        actors.clear(); worker.poll(); assertFailed(receipt, AssistRun.Failure.INPUT_UNAVAILABLE);
        assertThat(versions(receipt)).containsExactly(1L, 2L, 3L); verifyNoInteractions(model);
    }

    @Test void sendCheckUsesFreshlyRestoredRolesAndRejectsChangedEvidence() {
        var receipt = queue(); var restored = new Actor(TENANT, REQUESTER.userId(), Set.of());
        when(authentication.resolve(eq(LOGIN), eq(TENANT), eq(REQUESTER.userId()), any())).thenReturn(Optional.of(restored));
        changeCatalog("snapshot"); worker.poll(); assertFailed(receipt, AssistRun.Failure.INPUT_UNAVAILABLE);
        verify(accessTarget).available(eq(restored), eq(PRIMARY), eq(TASK), eq(selection), any()); verifyNoInteractions(model);
    }

    @Test void changedTargetBeforeClaimDoesNotSendOriginalConsentToNewModel() {
        var receipt = queue(); configuration.setModel("different-model"); worker.poll();
        assertFailed(receipt, AssistRun.Failure.MODEL_UNAVAILABLE); verifyNoInteractions(model);
    }

    @Test void changeAfterClaimIsCheckedAgainBeforeAnySend() {
        var receipt = queue(); var context = service.claim(TENANT, receipt.id(), Instant.now()); assertThat(context).isNotNull();
        configuration.setModel("different-model"); assertThat(service.sendable(context, Instant.now())).isFalse();
        assertFailed(receipt, AssistRun.Failure.MODEL_UNAVAILABLE); verifyNoInteractions(model);
    }

    @Test void expiredRunningLeaseSurvivesRecreationWithoutResendAndRejectsLateOutput() {
        var receipt = queue(); var context = service.claim(TENANT, receipt.id(), Instant.now()); var lease = loaded(receipt).state().leaseUntil();
        restartServices(); assertThat(service.claim(TENANT, receipt.id(), lease.minusMillis(1))).isNull();
        assertThat(loaded(receipt).state().leaseUntil()).isEqualTo(lease);
        assertThat(service.claim(TENANT, receipt.id(), lease)).isNull(); service.finish(TENANT, receipt.id(), suggestion(context), null, lease.plusSeconds(1));
        assertFailed(receipt, AssistRun.Failure.MODEL_TIMEOUT); assertThat(versions(receipt)).containsExactly(1L, 2L, 3L); verifyNoInteractions(model);
    }

    @ParameterizedTest @EnumSource(AssistRun.Failure.class)
    void modelFailureIsSavedOnceWithoutAutomaticRetry(AssistRun.Failure failure) {
        var receipt = queue(); doThrow(new AssistModelPort.ModelFailure(failure)).when(model).generate(any());
        worker.poll(); worker.poll(); assertFailed(receipt, failure); verify(model, times(1)).generate(any());
    }

    @Test void invalidModelOutputCannotBecomeACompletedRun() {
        var receipt = queue(); doReturn(null).when(model).generate(any()); worker.poll();
        assertFailed(receipt, AssistRun.Failure.INVALID_MODEL_OUTPUT);
    }

    @Test void databaseOutageIsNotMisreportedAsRevokedPermissionAndExpiresWithoutResend() {
        var receipt = queue(); when(accessTarget.available(any(), any(), anyString(), any(), any())).thenThrow(new DataAccessResourceFailureException("Synthetic unavailable source database"));
        worker.poll(); var running = loaded(receipt); assertThat(running.state().status()).isEqualTo(ExpenseRiskRun.Status.RUNNING);
        service.claim(TENANT, receipt.id(), running.state().leaseUntil()); assertFailed(receipt, AssistRun.Failure.MODEL_TIMEOUT); verifyNoInteractions(model);
    }

    @Test void callerTransactionCannotEncloseModelPolling() {
        var receipt = queue();
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(ignored -> worker.poll()))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(loaded(receipt).state().status()).isEqualTo(ExpenseRiskRun.Status.QUEUED); verifyNoInteractions(model);
    }

    @Test void concurrentServiceClaimsHaveOneWinnerUnderRealSourceAndRunLocks() throws Exception {
        var receipt = queue(); var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var one = pool.submit(() -> { start.await(); return service.claim(TENANT, receipt.id(), Instant.now()); });
            var two = pool.submit(() -> { start.await(); return service.claim(TENANT, receipt.id(), Instant.now()); }); start.countDown();
            var first = one.get(10, TimeUnit.SECONDS); var second = two.get(10, TimeUnit.SECONDS);
            assertThat((first == null ? 0 : 1) + (second == null ? 0 : 1)).isEqualTo(1); assertThat(versions(receipt)).containsExactly(1L, 2L);
        } finally { pool.shutdownNow(); }
    }

    @Test void authorizedCurrentReviewerCanAdoptWithoutOriginalLoginAndWithoutBusinessWrites() {
        var before = businessRows(); var receipt = queue(); worker.poll(); var original = loaded(receipt).state().suggestion();
        var reviewer = new Actor(TENANT, "second-reviewer", Set.of("APPROVER")); actors.set(reviewer); clearInvocations(authentication);
        configuration.setEnabled(false);
        var result = service.review(PRIMARY, receipt.id(), 3, AssistExecutionService.ReviewAction.ADOPT, List.of(CONCERN), "人工核对后采纳");
        assertThat(result.status()).isEqualTo(ExpenseRiskRun.Status.ADOPTED); var run = loaded(receipt);
        assertThat(run.state().review().actor()).isEqualTo(reviewer.userId()); assertThat(run.state().suggestion()).isEqualTo(original);
        verify(accessTarget).requireDecision(reviewer, run.context().input().documents().get(0), TASK);
        verifyNoInteractions(authentication); assertThat(businessRows()).isEqualTo(before);
    }

    @Test void oppositePrimarySelectionsAcquireSharedSourceLocksInTheSameOrder() throws Exception {
        var first = queue(); var original = loaded(first).context(); var input = original.input(); var documents = input.documents();
        var reversed = new ArrayList<ExpenseRiskInput.Document>();
        for (int index = 0; index < 2; index++) {
            var d = documents.get(1 - index);
            reversed.add(new ExpenseRiskInput.Document(index + 1, d.reportId(), d.applicationId(), d.applicationVersion(), d.roundNo(), d.financialVersion(), d.snapshotDigest(), d.lineNos()));
        }
        var second = new ExpenseRiskRun(new ExpenseRiskRun.Context(UUID.randomUUID(), TENANT, REQUESTER.userId(), TASK, original.createdAt(),
                new ExpenseRiskInput(reversed, input.calendar(), input.concerns(), input.sources()), original.targetDigest()));
        new TransactionTemplate(manager).executeWithoutResult(ignored -> runs.create(second, LOGIN));
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var one = pool.submit(() -> { start.await(); return service.claim(TENANT, first.id(), Instant.now()); });
            var two = pool.submit(() -> { start.await(); return service.claim(TENANT, second.context().id(), Instant.now()); }); start.countDown();
            assertThat(one.get(10, TimeUnit.SECONDS)).isNotNull(); assertThat(two.get(10, TimeUnit.SECONDS)).isNotNull();
        } finally { pool.shutdownNow(); }
    }

    @Test void staleSourcesPreventAdoptionButCurrentAuthorizedReviewerCanDismiss() {
        var receipt = queue(); worker.poll(); changeCatalog("snapshot"); var detail = service.get(PRIMARY, receipt.id());
        assertThat(detail.reviewable()).isTrue(); assertThat(detail.adoptable()).isFalse(); assertThat(detail.unavailableCode()).isEqualTo("AGENT_INPUT_CHANGED");
        error("AGENT_INPUT_CHANGED", () -> service.review(PRIMARY, receipt.id(), 3, AssistExecutionService.ReviewAction.ADOPT, List.of(CONCERN), null));
        assertThat(loaded(receipt).state().status()).isEqualTo(ExpenseRiskRun.Status.COMPLETED);
        assertThat(service.review(PRIMARY, receipt.id(), 3, AssistExecutionService.ReviewAction.DISMISS, List.of(), "来源已更新").status()).isEqualTo(ExpenseRiskRun.Status.DISMISSED);
    }

    @Test void historicalDetailsAndDismissalStillEnforceEveryOriginalReadAndCurrentDecision() {
        var receipt = queue(); worker.poll();
        doThrow(new DomainException("FORBIDDEN", "Comparison permission ended")).when(accessTarget).requireReadable(any(), any());
        error("FORBIDDEN", () -> service.get(PRIMARY, receipt.id()));
        error("FORBIDDEN", () -> service.review(PRIMARY, receipt.id(), 3, AssistExecutionService.ReviewAction.DISMISS, List.of(), null));
        doNothing().when(accessTarget).requireReadable(any(), any());
        doThrow(new DomainException("FORBIDDEN", "Task decision ended")).when(accessTarget).requireDecision(any(), any(), anyString());
        assertThat(service.get(PRIMARY, receipt.id()).reviewable()).isFalse();
        error("FORBIDDEN", () -> service.review(PRIMARY, receipt.id(), 3, AssistExecutionService.ReviewAction.DISMISS, List.of(), null));
        assertThat(versions(receipt)).containsExactly(1L, 2L, 3L);
    }

    @Test void historyHintHandlesTransactionalPermissionFailureWithoutLeakingOrRollbackErrors() {
        var receipt = queue(); worker.poll();
        when(accessTarget.available(any(), any(), anyString(), any(), any())).thenThrow(new DomainException("FORBIDDEN", "Current decision ended"));
        var detail = new TransactionTemplate(manager).execute(ignored -> service.get(PRIMARY, receipt.id()));
        assertThat(detail.adoptable()).isFalse(); assertThat(detail.unavailableCode()).isEqualTo("FORBIDDEN");
    }

    @Test void listAndReplayAuthorizationUseOriginalPrimaryRoundAndWrongReportCannotReadRun() {
        var receipt = queue(); service.authorizeSelection(PRIMARY, selection); assertThat(service.list(PRIMARY, 1, 0, 10).total()).isEqualTo(1);
        verify(accessTarget, times(2)).requirePrimaryReadable(REQUESTER, PRIMARY, 1);
        verify(accessTarget).requirePrimaryReadable(REQUESTER, COMPARISON, 1);
        service.authorizeRun(PRIMARY, receipt.id()); verify(accessTarget).requireReadable(REQUESTER, loaded(receipt).context().input());
        error("INVALID_AGENT_INPUT", () -> service.authorizeSelection(COMPARISON, selection));
        error("NOT_FOUND", () -> service.get(COMPARISON, receipt.id()));
        actors.set(new Actor("foreign", REQUESTER.userId(), Set.of("ADMIN"))); error("NOT_FOUND", () -> service.get(PRIMARY, receipt.id()));
    }

    private void restartServices() {
        service = transactional(new ExpenseRiskService(actors, reports, access, sources, runs, authentication, configuration, json));
        worker = transactional(new ExpenseRiskWorker(runs, service, model, new AgentExecutionTelemetry(transactional(new AgentUsageRepository(io.agentflow.mybatis.MyBatisTestSupport.mapper(jdbc.getDataSource(), io.agentflow.agent.mapper.AgentUsageMapper.class), json)))));
    }
    @SuppressWarnings("unchecked") private <T> T transactional(T target) {
        var proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource())); return (T) proxy.getProxy();
    }
    private ExpenseRiskService.Receipt queue() { return queue(service.input(PRIMARY, TASK, selection)); }
    private ExpenseRiskService.Receipt queue(ExpenseRiskService.InputOptions options) {
        return service.queue(PRIMARY, TASK, selection, options.inputDigest(), options.targetDigest(), sourceIds(), request);
    }
    private List<String> sourceIds() { return current.get().sources().stream().map(value -> value.reference().sourceId()).toList(); }
    private ExpenseRiskRun loaded(ExpenseRiskService.Receipt receipt) { return runs.find(TENANT, receipt.id()).orElseThrow().run(); }
    private List<Long> versions(ExpenseRiskService.Receipt receipt) { return jdbc.queryForList(
                "SELECT run_version FROM agent_expense_risk_transition WHERE run_id=? ORDER BY"
                        + " run_version", Long.class, receipt.id().toString()); }
    private int runCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM agent_expense_risk_run", Integer.class); }
    private void assertFailed(ExpenseRiskService.Receipt receipt, AssistRun.Failure failure) {
        var state = loaded(receipt).state(); assertThat(state.status()).isEqualTo(ExpenseRiskRun.Status.FAILED); assertThat(state.failure()).isEqualTo(failure);
    }
    private void assertUnavailable(String code) {
        var input = service.input(PRIMARY, TASK, selection); assertThat(input.enabled()).isFalse(); assertThat(input.unavailableCode()).isEqualTo(code);
        assertThat(input.inputDigest()).isNull(); assertThat(input.targetDigest()).isNull(); assertThat(input.sources()).isEmpty();
    }
    private ExpenseRiskSources.Catalog catalog() {
        return new ExpenseRiskSources.Catalog(List.of(new ExpenseRiskInput.Document(1, PRIMARY, PRIMARY_APP, 1, 1, 1, "a".repeat(64), List.of(1)),
                new ExpenseRiskInput.Document(2, COMPARISON, COMPARISON_APP, 1, 1, 1, "b".repeat(64), List.of(1))), null,
                List.of(new ExpenseRiskInput.Concern(CONCERN, ExpenseRiskInput.Kind.CROSS_DOCUMENT, List.of(1, 2))),
                List.of(source("expense:document[1]", "primary"), source("expense:document[2]", "comparison"), source(ExpenseRiskInput.COVERAGE_SOURCE, "scope"), source(CONCERN, "observation")));
    }
    private void changeCatalog(String change) {
        var prior = current.get(); var docs = new ArrayList<>(prior.documents()); var document = docs.get(1);
        docs.set(1, new ExpenseRiskInput.Document(document.ordinal(), document.reportId(), document.applicationId(), change.equals("application") ? 2 : document.applicationVersion(),
                document.roundNo(), change.equals("financial") ? 2 : document.financialVersion(), change.equals("snapshot") ? "e".repeat(64) : document.snapshotDigest(), document.lineNos()));
        var contents = new ArrayList<>(prior.sources()); if (change.equals("source")) contents.set(3, source(CONCERN, "changed observation"));
        current.set(new ExpenseRiskSources.Catalog(docs, prior.calendar(), prior.concerns(), contents));
    }
    private AssistModelPort.Source source(String id, String content) {
        return new AssistModelPort.Source(new AssistInput.Reference(id, AssistConfiguration.digest(content)), "合成费用事实", content);
    }
    private static ExpenseRiskSuggestion suggestion(ExpenseRiskRun.Context context) {
        return new ExpenseRiskSuggestion("synthetic", "test-v1", ExpenseRiskRun.PROMPT_VERSION,
                List.of(new ExpenseRiskSuggestion.Item(CONCERN, ExpenseRiskInput.Kind.CROSS_DOCUMENT, "所选同类费用分布在两份单据", "不证明规避审批",
                        List.of("人工核对用途和行程"), context.input().sources().stream().map(AssistModelPort.Source::reference).toList())));
    }
    private void seed(UUID report, UUID app) {
        jdbc.update(
                "INSERT INTO"
                    + " approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id)"
                    + " VALUES(?,?,?,'fixture',1,'employee','保留申请','{}','IN_APPROVAL',1,1,'EXPENSE',?)", app.toString(), TENANT, "RISK-" + app, report.toString());
        jdbc.update(
                "INSERT INTO"
                    + " expense_report(id,tenant_id,application_id,employee_id,version,state_json)"
                    + " VALUES(?,?,?,'employee',1,'{\"retained\":true}')", report.toString(), TENANT, app.toString());
        jdbc.update(
                "INSERT INTO"
                    + " expense_report_revision(tenant_id,report_id,financial_version,actor_id,operation,state_json)"
                    + " SELECT tenant_id,id,version,employee_id,'CREATE',state_json FROM"
                    + " expense_report WHERE id=?", report.toString());
        jdbc.update(
                "INSERT INTO"
                    + " approval_submission_round(tenant_id,application_id,round_no,process_instance_id,definition_version,title,payload_json,submitted_by,submitted_at,status)"
                    + " VALUES(?,?,1,?,1,'原轮次','{}','employee',?,'IN_APPROVAL')", TENANT, app.toString(), "fixture-" + app, Timestamp.from(Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MILLIS)));
    }
    private List<List<Map<String, Object>>> businessRows() {
        return List.of("approval_application", "expense_report", "expense_report_revision", "approval_submission_round").stream().map(table -> jdbc.queryForList("SELECT * FROM " + table)).toList();
    }
    private static void error(String code, Runnable action) { assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, failure -> assertThat(failure.code()).isEqualTo(code)); }
}
