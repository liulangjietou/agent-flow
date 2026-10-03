package io.agentflow.expense;

import io.agentflow.approval.SubmissionRoundCompleted;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.common.DomainException;
import io.agentflow.finance.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/**
 * 真实仓储和事务验证轮次捕获、到期编排和重提竞争，预算结果使用明确的合成凭据。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.expenses.budget-retention.worker-enabled=false", "agentflow.budgets.worker-enabled=false",
        "agentflow.invoices.verification-worker-enabled=false", "agentflow.expenses.precheck-worker-enabled=false"})
class ExpenseBudgetRetentionIntegrationTest {
    private static final String TENANT = "demo";
    private static final Instant SUBMITTED = Instant.parse("2026-10-03T10:00:00Z");
    private static final Instant STOPPED = SUBMITTED.plusSeconds(60);
    private static final LocalDate DATE = LocalDate.of(2026, 10, 3);
    private static final UUID ENTITY = UUID.randomUUID();
    @Autowired ApplicationRepository applications;
    @Autowired SubmissionRoundRepository rounds;
    @Autowired ExpenseReportRepository reports;
    @Autowired ExpenseBudgetRetentionConfiguration configuration;
    @Autowired ExpenseBudgetRetentionService service;
    @Autowired JdbcExpenseBudgetRetentionRepository retentions;
    @Autowired JdbcBudgetOccupationRepository occupations;
    @Autowired JdbcBudgetOperationRepository operations;
    @Autowired BudgetOperationService budgets;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @DynamicPropertySource static void database(DynamicPropertyRegistry values) {
        values.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_RETENTION_TEST_URL", "jdbc:h2:mem:budget-retention;DB_CLOSE_DELAY=-1"));
        values.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_RETENTION_TEST_DRIVER", "org.h2.Driver"));
        values.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_RETENTION_TEST_USER", "sa"));
        values.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_RETENTION_TEST_PASSWORD", ""));
    }

    @BeforeEach void explicitPolicy() {
        var tenant = new ExpenseBudgetRetentionConfiguration.Tenant(); tenant.setEnabled(true); tenant.setRetentionDays(3);
        configuration.setTenants(Map.of(TENANT, tenant)); configuration.validate();
    }

    @Test void bothStopConclusionsCapturePersistedTimeAndEditsOrPolicyChangesDoNotMoveTheDeadline() {
        for (var status : List.of(SubmissionRound.Status.RETURNED, SubmissionRound.Status.WITHDRAWN)) {
            var fixture = fixture(true); stop(fixture.report(), status, STOPPED.plusNanos(123456789));
            var retained = retention(fixture.report());
            assertThat(retained.retainedAt()).isEqualTo(rounds.findByRound(TENANT, fixture.report().applicationId(), 1).orElseThrow().completedAt());
            assertThat(retained.expiresAt()).isEqualTo(retained.retainedAt().plusSeconds(3 * 86400));
            configuration.getTenants().get(TENANT).setRetentionDays(9);
            tx().executeWithoutResult(transaction -> {
                reports.lock(TENANT, fixture.report().id()); var current = reports.find(TENANT, fixture.report().id()).orElseThrow();
                long version = current.version(); current.revise(version, current.content()); reports.update(current, version, "alice", "SYNTHETIC_EDIT");
                service.retain(new SubmissionRoundCompleted(TENANT, current.applicationId(), 1, status), new ExpenseBudgetRetention.Policy(9));
            });
            assertThat(retention(fixture.report())).isEqualTo(retained);
            service.process(candidate(fixture.report()), retained.expiresAt().minusNanos(1000));
            assertThat(retention(fixture.report()).status()).isEqualTo(ExpenseBudgetRetention.Status.RETAINED);
            explicitPolicy();
        }
    }

    @Test void missingOrDisabledPolicyDoesNotRetroactivelyCreateExpiry() {
        for (boolean absent : List.of(true, false)) {
            var fixture = fixture(true);
            if (absent) configuration.setTenants(Map.of()); else configuration.getTenants().get(TENANT).setEnabled(false);
            stop(fixture.report(), SubmissionRound.Status.WITHDRAWN, STOPPED);
            assertThat(retentions.find(TENANT, fixture.report().id(), 1)).isEmpty();
            explicitPolicy();
            assertThat(retentions.find(TENANT, fixture.report().id(), 1)).isEmpty();
        }
    }

    @Test void confirmedReleaseIsRequiredBeforeRefreezingANewRoundAndLateOldResultIsHarmless() {
        var fixture = fixture(true); var report = fixture.report(); stop(report, SubmissionRound.Status.WITHDRAWN, STOPPED);
        var expires = retention(report).expiresAt(); service.process(candidate(report), expires);
        var queued = retention(report); var release = operation(queued.releaseOperationId());
        assertThat(queued.status()).isEqualTo(ExpenseBudgetRetention.Status.RELEASE_QUEUED);
        assertThat(occupations.find(TENANT, report.id()).orElseThrow().status()).isEqualTo(BudgetOccupation.Status.FROZEN);
        service.process(candidate(report), expires.plusSeconds(1)); assertThat(retention(report)).isEqualTo(queued);
        var claimed = budgets.claim(TENANT, release.input().command().id(), expires);
        budgets.fail(claimed, expires.plusSeconds(1));
        assertThatThrownBy(() -> resubmit(report, expires.plusSeconds(2))).isInstanceOf(DomainException.class).hasMessageContaining("reconciled");
        assertThat(application(report).status()).isEqualTo(ApplicationStatus.WITHDRAWN);
        assertThat(reports.find(TENANT, report.id()).orElseThrow().version()).isEqualTo(report.version());
        var unknown = operation(release.input().command().id());
        var query = budgets.claim(TENANT, release.input().command().id(), unknown.nextAttemptAt());
        finishApplied(query, query.updatedAt());
        // 调度尚未读取释放回执时也允许正常重提，旧保留记录只会核对它自己的释放编号。
        var next = resubmit(report, query.updatedAt().plusSeconds(1));
        apply(next, next.createdAt()); service.process(candidate(report), next.createdAt().plusSeconds(1));
        assertThat(retention(report).status()).isEqualTo(ExpenseBudgetRetention.Status.RELEASED);
        var frozen = occupations.find(TENANT, report.id()).orElseThrow();
        assertThat(frozen.status()).isEqualTo(BudgetOccupation.Status.FROZEN); assertThat(frozen.confirmed().position().roundNo()).isEqualTo(2);
        assertThat(frozen.confirmed().revision()).isEqualTo(3);
        finishApplied(claimed, next.createdAt().plusSeconds(2));
        assertThat(occupations.find(TENANT, report.id()).orElseThrow()).isEqualTo(frozen);
    }

    @Test void unresolvedOriginalFreezeIsReconciledBeforeAnyReleaseAndKnownRejectionNeedsNoRelease() {
        var fixture = fixture(false); var report = fixture.report(); stop(report, SubmissionRound.Status.RETURNED, STOPPED);
        var expires = retention(report).expiresAt(); service.process(candidate(report), expires);
        assertThat(retention(report).status()).isEqualTo(ExpenseBudgetRetention.Status.RECONCILING);
        assertThat(commandCount(report)).isEqualTo(1);
        apply(fixture.operation(), expires.plusSeconds(1)); service.process(candidate(report), expires.plusSeconds(2));
        assertThat(retention(report).status()).isEqualTo(ExpenseBudgetRetention.Status.RELEASE_QUEUED); assertThat(commandCount(report)).isEqualTo(2);
        var rejected = fixture(false); stop(rejected.report(), SubmissionRound.Status.RETURNED, STOPPED);
        reject(rejected.operation(), expires); service.process(candidate(rejected.report()), expires.plusSeconds(1));
        assertThat(retention(rejected.report()).status()).isEqualTo(ExpenseBudgetRetention.Status.NO_FROZEN_BUDGET);
        assertThat(retention(rejected.report()).releaseOperationId()).isNull(); assertThat(commandCount(rejected.report())).isEqualTo(1);
    }

    @Test void explicitReleaseRejectionStopsAutomaticRetriesAndPreservesActualFrozenBudget() {
        var fixture = fixture(true); var report = fixture.report(); stop(report, SubmissionRound.Status.WITHDRAWN, STOPPED);
        var expires = retention(report).expiresAt(); service.process(candidate(report), expires);
        reject(operation(retention(report).releaseOperationId()), expires.plusSeconds(1));
        service.process(candidate(report), expires.plusSeconds(2)); service.process(candidate(report), expires.plusSeconds(3));
        assertThat(retention(report).status()).isEqualTo(ExpenseBudgetRetention.Status.RELEASE_REJECTED);
        assertThat(retention(report).issue()).isEqualTo("LEDGER_VERSION_CONFLICT"); assertThat(commandCount(report)).isEqualTo(2);
        assertThat(occupations.find(TENANT, report.id()).orElseThrow().status()).isEqualTo(BudgetOccupation.Status.FROZEN);
    }

    @Test void resubmitWinningTheReportLockSupersedesOldExpiryWithoutReleasingTheNewRound() throws Exception {
        var fixture = fixture(true); var report = fixture.report(); stop(report, SubmissionRound.Status.WITHDRAWN, STOPPED);
        var expires = retention(report).expiresAt(); var locked = new CountDownLatch(1); var resume = new CountDownLatch(1);
        var threads = Executors.newFixedThreadPool(2);
        try {
            var submit = threads.submit(() -> tx().execute(transaction -> {
                reports.lock(TENANT, report.id()); locked.countDown(); await(resume); return resubmit(report, expires);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var expiry = threads.submit(() -> service.process(candidate(report), expires));
            resume.countDown(); var adjustment = submit.get(5, TimeUnit.SECONDS); expiry.get(5, TimeUnit.SECONDS);
            assertThat(retention(report).status()).isEqualTo(ExpenseBudgetRetention.Status.SUPERSEDED);
            assertThat(commandCount(report)).isEqualTo(2); assertThat(adjustment.input().command().action()).isEqualTo(BudgetCommand.Action.ADJUST);
            apply(adjustment, expires.plusSeconds(1)); service.process(candidate(report), expires.plusSeconds(2));
            assertThat(occupations.find(TENANT, report.id()).orElseThrow().confirmed().position().roundNo()).isEqualTo(2);
            assertThat(commandCount(report)).isEqualTo(2);
        } finally { resume.countDown(); threads.shutdownNow(); }
    }

    @Test void roundAndExpiryOutboxRegistrationBothRollbackWithTheirOriginalTransaction() {
        var fixture = fixture(true); var report = fixture.report();
        assertThatThrownBy(() -> tx().executeWithoutResult(transaction -> {
            stop(report, SubmissionRound.Status.WITHDRAWN, STOPPED); throw new IllegalStateException("synthetic rollback");
        })).hasMessageContaining("synthetic rollback");
        assertThat(application(report).status()).isEqualTo(ApplicationStatus.IN_APPROVAL);
        assertThat(retentions.find(TENANT, report.id(), 1)).isEmpty();
        stop(report, SubmissionRound.Status.WITHDRAWN, STOPPED); var original = retention(report);
        assertThatThrownBy(() -> tx().executeWithoutResult(transaction -> {
            service.process(candidate(report), original.expiresAt()); throw new IllegalStateException("synthetic rollback");
        })).hasMessageContaining("synthetic rollback");
        assertThat(retention(report)).isEqualTo(original); assertThat(commandCount(report)).isEqualTo(1);
        assertThat(occupations.find(TENANT, report.id()).orElseThrow().pendingOperationId()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_budget_retention_revision WHERE report_id=?", Integer.class, report.id().toString())).isEqualTo(1);
    }

    @Test void completingARoundWithoutItsBusinessTransactionCannotLeaveAPartialConclusion() {
        var fixture = fixture(true); var report = fixture.report();
        var round = rounds.findByRound(TENANT, report.applicationId(), 1).orElseThrow();
        assertThatThrownBy(() -> rounds.complete(TENANT, report.applicationId(), 1, round.processInstanceId(),
                SubmissionRound.Status.WITHDRAWN, "缺失业务事务", "alice", STOPPED))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(rounds.findByRound(TENANT, report.applicationId(), 1)).contains(round);
        assertThat(retentions.find(TENANT, report.id(), 1)).isEmpty();
    }

    @Test void expiryWinningTheReportLockBlocksConcurrentResubmitWithoutLeavingANewRound() throws Exception {
        var fixture = fixture(true); var report = fixture.report(); stop(report, SubmissionRound.Status.WITHDRAWN, STOPPED);
        var expires = retention(report).expiresAt(); var queued = new CountDownLatch(1); var resume = new CountDownLatch(1);
        var threads = Executors.newFixedThreadPool(2);
        try {
            var expiry = threads.submit(() -> tx().executeWithoutResult(transaction -> {
                service.process(candidate(report), expires); queued.countDown(); await(resume);
            }));
            assertThat(queued.await(5, TimeUnit.SECONDS)).isTrue();
            var submit = threads.submit(() -> resubmit(report, expires.plusSeconds(1)));
            resume.countDown(); expiry.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> submit.get(5, TimeUnit.SECONDS)).hasRootCauseInstanceOf(DomainException.class);
            assertThat(application(report).status()).isEqualTo(ApplicationStatus.WITHDRAWN);
            assertThat(application(report).roundNo()).isEqualTo(1);
            assertThat(reports.find(TENANT, report.id()).orElseThrow().rounds()).hasSize(1);
            assertThat(commandCount(report)).isEqualTo(2);
        } finally { resume.countDown(); threads.shutdownNow(); }
    }

    @Test void databaseCursorTraversesMoreThanOnePageOfUnresolvedBudgetRecords() {
        var created = new java.util.HashSet<JdbcExpenseBudgetRetentionRepository.Candidate>();
        for (int i = 0; i <= JdbcExpenseBudgetRetentionRepository.BATCH_SIZE; i++) {
            var fixture = fixture(false); stop(fixture.report(), SubmissionRound.Status.RETURNED, STOPPED); created.add(candidate(fixture.report()));
        }
        var seen = new java.util.HashSet<JdbcExpenseBudgetRetentionRepository.Candidate>();
        JdbcExpenseBudgetRetentionRepository.Candidate after = null; int pages = 0;
        do {
            var page = retentions.candidates(STOPPED.plusSeconds(3 * 86400), after); pages++;
            for (var item : page) assertThat(seen.add(item)).isTrue();
            after = page.size() == JdbcExpenseBudgetRetentionRepository.BATCH_SIZE ? page.get(page.size() - 1) : null;
        } while (after != null && pages < 10);
        assertThat(after).isNull(); assertThat(pages).isGreaterThan(1); assertThat(seen).containsAll(created);
    }

    @Test void tenantAndImmutableDeadlineAreCheckedAndSnapshotCorruptionFailsClosed() {
        var fixture = fixture(true); var report = fixture.report(); stop(report, SubmissionRound.Status.RETURNED, STOPPED);
        var original = retention(report);
        assertThat(retentions.find("foreign", report.id(), 1)).isEmpty();
        var tampered = new ExpenseBudgetRetention(TENANT, report.id(), report.applicationId(), 1, original.stoppedStatus(), STOPPED,
                new ExpenseBudgetRetention.Policy(4), STOPPED.plusSeconds(4 * 86400), ExpenseBudgetRetention.Status.RECONCILING,
                null, null, 2, STOPPED.plusSeconds(4 * 86400));
        assertThatThrownBy(() -> tx().executeWithoutResult(transaction -> retentions.update(tampered))).isInstanceOf(DomainException.class);
        assertThat(retention(report)).isEqualTo(original);
        jdbc.update("UPDATE expense_budget_retention SET retention_days=4 WHERE report_id=?", report.id().toString());
        assertThatThrownBy(() -> retention(report)).isInstanceOf(IllegalStateException.class).hasMessageContaining("inconsistent");
    }

    private Fixture fixture(boolean applied) {
        var reportId = UUID.randomUUID();
        var app = Application.draftBusiness(UUID.randomUUID(), TENANT, "RETENTION-" + UUID.randomUUID(), "fixture", 1, "alice",
                "合成预算到期场景", Map.of(), null, null, null, new BusinessReference(BusinessReference.Type.EXPENSE, reportId));
        applications.save(app);
        var line = new ExpenseLine(1, "DAILY", DATE, null, "SH", BigDecimal.ONE, ExpenseLine.Unit.ITEM, money("100"), money("0"),
                List.of(), null, List.of(new CostAllocation("IT", null, money("100"))), "合成明细", null);
        var report = ExpenseReport.draft(reportId, TENANT, app.id(), "alice", new ExpenseContent(ENTITY, ExpenseContent.Type.DAILY, "合成预算", List.of(line), List.of()));
        reports.create(report, "alice");
        var operation = tx().execute(transaction -> submit(app, report, SUBMITTED));
        if (applied) apply(operation, SUBMITTED.plusSeconds(1));
        return new Fixture(report, operation);
    }
    private BudgetOperation submit(Application app, ExpenseReport report, Instant at) {
        long financialVersion = report.version();
        var assessment = new ExpenseAssessment(new ExpenseExchangeRate("CNY", "CNY", BigDecimal.ONE, "synthetic-rate", DATE),
                new ExpensePolicySnapshot(UUID.randomUUID(), 1, money("100"), money("100"), ExpensePolicySnapshot.Decision.WITHIN_LIMIT,
                        "synthetic-tax", "synthetic-policy"), money("0"));
        report.freeze(financialVersion, report.rounds().size() + 1, "CNY", new EmployeeAccountSnapshot(ENTITY, "alice", "synthetic-account",
                "****1234", "a".repeat(64), "v1"), Map.of(1, assessment), "alice", at);
        reports.update(report, financialVersion, "alice", "SYNTHETIC_SUBMIT");
        long version = app.version(); app.submit(version); applications.update(app, version);
        rounds.append(SubmissionRound.submitted(app, "retention-" + UUID.randomUUID(), "alice", at));
        return budgets.reserve(TENANT, report.id(), report.version(), DATE, "a".repeat(64), at);
    }
    private BudgetOperation resubmit(ExpenseReport report, Instant at) {
        return tx().execute(transaction -> { reports.lock(TENANT, report.id()); return submit(application(report), reports.find(TENANT, report.id()).orElseThrow(), at); });
    }
    private void stop(ExpenseReport report, SubmissionRound.Status status, Instant at) {
        tx().executeWithoutResult(transaction -> {
            reports.lock(TENANT, report.id()); var app = application(report); long version = app.version();
            if (status == SubmissionRound.Status.WITHDRAWN) app.withdraw(version); else app.returnToApplicant(version);
            applications.update(app, version); var round = rounds.findByRound(TENANT, app.id(), app.roundNo()).orElseThrow();
            rounds.complete(TENANT, app.id(), app.roundNo(), round.processInstanceId(), status, "合成停止", "alice", at);
        });
    }
    private void apply(BudgetOperation operation, Instant at) { finishApplied(budgets.claim(TENANT, operation.input().command().id(), at), at); }
    private void finishApplied(BudgetOperation claimed, Instant at) {
        var command = claimed.input().command(); long revision = command.expected() == null ? 1 : command.expected().revision() + 1;
        budgets.finish(claimed, new FinanceResult.Success<>(new BudgetObservation(command.id(), command.digest(), BudgetObservation.Status.APPLIED,
                revision, "synthetic-v" + revision, at, null)), at);
    }
    private void reject(BudgetOperation operation, Instant at) {
        var claimed = budgets.claim(TENANT, operation.input().command().id(), at); var command = claimed.input().command();
        budgets.finish(claimed, new FinanceResult.Success<>(new BudgetObservation(command.id(), command.digest(), BudgetObservation.Status.REJECTED,
                null, null, null, BudgetObservation.Rejection.LEDGER_VERSION_CONFLICT)), at);
    }
    private Application application(ExpenseReport report) { return applications.findById(TENANT, report.applicationId()).orElseThrow(); }
    private ExpenseBudgetRetention retention(ExpenseReport report) { return retentions.find(TENANT, report.id(), 1).orElseThrow(); }
    private BudgetOperation operation(UUID id) { return operations.find(TENANT, id).orElseThrow(); }
    private int commandCount(ExpenseReport report) { return jdbc.queryForObject("SELECT COUNT(*) FROM budget_operation WHERE report_id=?", Integer.class, report.id().toString()); }
    private JdbcExpenseBudgetRetentionRepository.Candidate candidate(ExpenseReport report) { return new JdbcExpenseBudgetRetentionRepository.Candidate(TENANT, report.id(), 1); }
    private TransactionTemplate tx() { return new TransactionTemplate(transactions); }
    private static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic latch expired"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }
    private record Fixture(ExpenseReport report, BudgetOperation operation) { }
}
