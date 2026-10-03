package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.expense.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 两侧外部成功与本地资源完成分别保存，后继只能采用真实完成状态和完整已用回款。
 * @author owlzhangfq@gmail.com
 */
class ExpensePartialAdjustmentTest {
    private static final Instant NOW = Instant.parse("2026-09-30T18:30:05Z");
    private static final Duration LEASE = Duration.ofSeconds(30);

    @Test void twoIndependentEffectsRemainSeparateUntilLocalResourcesComplete() {
        var value = adjustment(); var original = value.input().basis().funding().financial();
        var budget = budget(value, NOW); var accrual = accrual(value, NOW);
        value = value.authorizeBudget(budget, NOW).authorizeAccrual(accrual, NOW);
        assertThat(value.status()).isEqualTo(ExpensePartialAdjustment.Status.WAITING_FINANCE);
        value = applyBudget(value, NOW.plusSeconds(1));
        assertThat(value.status()).isEqualTo(ExpensePartialAdjustment.Status.WAITING_FINANCE);
        var oneSide = value; invalid(() -> oneSide.completeResources(NOW.plusSeconds(2)));
        value = postAccrual(value, NOW.plusSeconds(2));
        assertThat(value.status()).isEqualTo(ExpensePartialAdjustment.Status.READY);
        assertThat(value.completion()).isNull();
        value = value.completeResources(NOW.plusSeconds(3));
        assertThat(value.status()).isEqualTo(ExpensePartialAdjustment.Status.APPLIED);
        assertThat(value.completion().budget()).isEqualTo(value.budget().observation());
        assertThat(value.completion().accrual()).isEqualTo(value.accrual().observation());
        assertThat(value.input().basis().funding().financial()).isEqualTo(original);
        var completed = value; invalid(() -> completed.completeResources(NOW.plusSeconds(4)));
        invalid(() -> completed.retire("finance", "end", "不能结束已执行调整", NOW.plusSeconds(4)));
    }

    @Test void nextAdjustmentUsesCompletedProjectionAndExactPreviouslyConsumedBankEntries() {
        var fixture = ExpenseAdjustmentFundingSourceTest.fixture(List.of("20", "20", "20"));
        var source = source(fixture, "80", "4", List.of(), List.of(fixture.returns().entries().get(0)));
        var previous = complete(start(source, null), NOW);
        var change = source.financial().change().after().reduce(List.of(target("60", "3")));
        var nextFunding = ExpenseAdjustmentFundingSourceTest.source(fixture, change, source.selectedReturns(), List.of(fixture.returns().entries().get(1)));
        var next = start(nextFunding, previous, NOW.plusSeconds(10));
        assertThat(next.input().basis().previous().id()).isEqualTo(previous.id());
        assertThat(next.input().basis().previous().version()).isEqualTo(previous.version());
        assertThat(next.input().basis().previous().usedReturns()).containsExactlyElementsOf(source.selectedReturns());
        assertThat(budget(next, NOW.plusSeconds(10)).input().command().previous()).isEqualTo(previous.completion().budget());
        assertThat(accrual(next, NOW.plusSeconds(10)).input().command().previous()).isEqualTo(previous.completion().accrual());
        invalid(() -> start(nextFunding, null, NOW.plusSeconds(10)));
        var replaced = ExpenseAdjustmentFundingSourceTest.source(fixture, change, List.of(fixture.returns().entries().get(1)), List.of(fixture.returns().entries().get(2)));
        invalid(() -> start(replaced, previous, NOW.plusSeconds(10)));
        invalid(() -> start(nextFunding, previous.requireReview("ORIGINAL_CHANGED", NOW.plusSeconds(4)), NOW.plusSeconds(10)));
    }

    @Test void postedCommandsWithoutLocalCompletionCannotSupplyNextHistory() {
        var fixture = ExpenseAdjustmentFundingSourceTest.fixture(List.of("20", "20"));
        var funding = source(fixture, "80", "4", List.of(), List.of(fixture.returns().entries().get(0)));
        var initial = start(funding, null);
        var ready = ready(initial, NOW);
        var change = funding.financial().change().after().reduce(List.of(target("60", "3")));
        var next = ExpenseAdjustmentFundingSourceTest.source(fixture, change, funding.selectedReturns(), List.of(fixture.returns().entries().get(1)));
        invalid(() -> start(next, initial, NOW.plusSeconds(10)));
        invalid(() -> start(next, ready, NOW.plusSeconds(10)));
    }

    @Test void zeroPayableAdjustmentUsesNoBankAndStillRequiresBothActualEffects() {
        var financial = ExpenseAccrualReductionTest.financial("100", "80", "4");
        var funding = new ExpenseAdjustmentFundingSource(financial, null, null, null, null, null, List.of(), List.of());
        var completed = complete(start(funding, null), NOW);
        assertThat(completed.status()).isEqualTo(ExpensePartialAdjustment.Status.APPLIED);
        assertThat(completed.input().basis().usedReturns()).isEmpty();
        assertThat(completed.input().basis().funding().financial().change().advanceReversals()).extracting(AdvanceOffset::amount).containsExactly(money("20"));
    }

    @Test void expiredUnsentBudgetCanBeReauthorizedWithoutRepeatingPostedAccounting() {
        var initial = adjustment();
        var value = initial.authorizeBudget(budget(initial, NOW), NOW).authorizeAccrual(accrual(initial, NOW), NOW);
        value = postAccrual(value, NOW.plusSeconds(1));
        var expiry = NOW.plusSeconds(600);
        value = value.withBudget(value.budget().claim(expiry, LEASE), expiry);
        var retained = value.accrual(); var oldBudget = value.budget();
        var renewed = value.authorizeBudget(budget(value, expiry), expiry);
        assertThat(renewed.budget().input().command().id()).isNotEqualTo(oldBudget.input().command().id());
        assertThat(renewed.accrual()).isEqualTo(retained);
        assertThat(applyBudget(renewed, expiry.plusSeconds(1)).status()).isEqualTo(ExpensePartialAdjustment.Status.READY);
        var current = renewed;
        invalid(() -> current.authorizeAccrual(accrual(current, expiry), expiry));
    }

    @Test void unknownAndAuthoritativelyAbsentWritesCannotBeReplacedOrRetired() {
        var initial = adjustment(); var at = NOW.plusSeconds(1);
        var queued = initial.authorizeBudget(budget(initial, NOW), NOW);
        var running = queued.withBudget(queued.budget().claim(at, LEASE), at);
        var unknown = running.withBudget(running.budget().unavailable(BudgetConsumptionReductionOperation.Failure.TIMEOUT, at), at);
        invalid(() -> unknown.authorizeBudget(budget(unknown, at), at));
        invalid(() -> unknown.retire("finance", "end", "未知结果不能结束", at));
        var queryAt = at.plusSeconds(5); var querying = unknown.withBudget(unknown.budget().claim(queryAt, LEASE), queryAt);
        var command = querying.budget().input().command();
        var missing = new BudgetConsumptionReductionObservation(command.id(), command.adjustmentId(), command.digest(), BudgetConsumptionReductionObservation.Status.NOT_FOUND, queryAt, null, null);
        var absent = querying.withBudget(querying.budget().complete(new FinanceResult.Success<>(missing), queryAt), queryAt);
        invalid(() -> absent.authorizeBudget(budget(absent, queryAt), queryAt));
        invalid(() -> absent.retire("finance", "end", "查无仍沿原号恢复", queryAt));
    }

    @Test void explicitRetirementStopsUnsentCommandsAndPreservesTheirOriginalBytes() {
        var initial = adjustment();
        var queued = initial.authorizeBudget(budget(initial, NOW), NOW).authorizeAccrual(accrual(initial, NOW), NOW);
        var stopped = queued.retire("other-finance", "retirement-proof", "重新核对资料", NOW.plusSeconds(1));
        assertThat(stopped.status()).isEqualTo(ExpensePartialAdjustment.Status.RETIRED);
        assertThat(stopped.budget().status()).isEqualTo(BudgetConsumptionReductionOperation.Status.VOIDED);
        assertThat(stopped.accrual().status()).isEqualTo(ExpenseAccrualReductionOperation.Status.VOIDED);
        assertThat(stopped.budget().input()).isEqualTo(queued.budget().input());
        assertThat(stopped.retirement().actor()).isEqualTo("other-finance");
        invalid(() -> stopped.authorizeBudget(budget(stopped, NOW.plusSeconds(2)), NOW.plusSeconds(2)));
        invalid(() -> queued.retire("cashier", "end", "不能由原出纳结束", NOW.plusSeconds(1)));
    }

    @Test void foreignTargetsSourcesAmountsAndApplicantOrCashierAuthorizationAreRejected() {
        var value = adjustment(); var foreign = adjustment();
        invalid(() -> value.authorizeBudget(budget(foreign, NOW), NOW));
        invalid(() -> value.authorizeAccrual(accrual(foreign, NOW), NOW));
        var operation = budget(value, NOW);
        var changed = BudgetConsumptionReductionOperation.queue(new BudgetConsumptionReductionOperation.Input(operation.input().consumedVersion(), operation.input().command(), "b".repeat(64)), NOW);
        invalid(() -> value.authorizeBudget(changed, NOW));
        var source = value.input().basis().funding();
        invalid(() -> ExpensePartialAdjustment.begin(new ExpensePartialAdjustment.Input(UUID.randomUUID(), value.input().basis(), "alice", "proof", "不能申请人自批", NOW)));
        invalid(() -> ExpensePartialAdjustment.begin(new ExpensePartialAdjustment.Input(UUID.randomUUID(), value.input().basis(), "cashier", "proof", "不能出纳自批", NOW)));
        assertThat(value.toString()).doesNotContain("alice", "bank-0", "original-account");
        assertThat(source.selectedReturns()).hasSize(1);
    }

    @Test void queryAfterCompletionKeepsAcceptedProofUntilExplicitConfirmation() {
        var completed = complete(adjustment(), NOW); var at = NOW.plusSeconds(5);
        var rechecking = completed.withBudget(completed.budget().requestQuery(at), at);
        assertThat(rechecking.status()).isEqualTo(ExpensePartialAdjustment.Status.REVIEW_REQUIRED);
        assertThat(rechecking.completion()).isEqualTo(completed.completion());
        var querying = rechecking.withBudget(rechecking.budget().claim(at, LEASE), at);
        var receipt = completed.completion().budget();
        var fresh = new BudgetConsumptionReductionObservation(receipt.operationId(), receipt.adjustmentId(), receipt.commandDigest(), receipt.status(), at, receipt.posting(), null);
        var verified = querying.withBudget(querying.budget().complete(new FinanceResult.Success<>(fresh), at), at);
        assertThat(verified.status()).isEqualTo(ExpensePartialAdjustment.Status.REVIEW_REQUIRED);
        assertThat(verified.confirmCurrent(at).status()).isEqualTo(ExpensePartialAdjustment.Status.APPLIED);
        assertThat(verified.confirmCurrent(at).completion()).isEqualTo(completed.completion());
        invalid(() -> querying.confirmCurrent(at));
    }

    @Test void restoredSnapshotCannotInventCompletionOrReplaceExecutionIdentity() {
        var value = adjustment(); var completed = complete(value, NOW);
        invalid(() -> new ExpensePartialAdjustment(value.input(), 2, null, null, completed.completion(), null, null, NOW.plusSeconds(3)));
        var queued = value.authorizeBudget(budget(value, NOW), NOW);
        invalid(() -> queued.withBudget(budget(value, NOW), NOW));
        invalid(() -> queued.withBudget(queued.budget().claim(NOW, LEASE), NOW.minusSeconds(1)));
        var success = applyBudget(queued, NOW.plusSeconds(1)).budget();
        var forged = new BudgetConsumptionReductionOperation(success.input(), 2, success.status(), success.attempts(), success.createdAt(), success.updatedAt(),
                null, null, success.observation(), null, null);
        invalid(() -> queued.withBudget(forged, forged.updatedAt()));
    }

    @Test void explicitReviewBlocksQueuedWritesButKeepsUnknownResultQueriesAvailable() {
        var initial = adjustment(); var at = NOW.plusSeconds(1);
        var queued = initial.authorizeBudget(budget(initial, NOW), NOW).authorizeAccrual(accrual(initial, NOW), NOW);
        var held = queued.requireReview("SOURCE_RECHECK_REQUIRED", at);
        invalid(() -> held.withBudget(held.budget().claim(at, LEASE), at));
        invalid(() -> held.withAccrual(held.accrual().claim(at, LEASE), at));

        var budgetRunning = queued.withBudget(queued.budget().claim(at, LEASE), at);
        var running = budgetRunning.withAccrual(budgetRunning.accrual().claim(at, LEASE), at);
        var budgetUnknown = running.withBudget(running.budget().unavailable(BudgetConsumptionReductionOperation.Failure.TIMEOUT, at), at);
        var unknown = budgetUnknown.withAccrual(budgetUnknown.accrual().unavailable(ExpenseAccrualReductionOperation.Failure.TIMEOUT, at), at);
        var reviewed = unknown.requireReview("SOURCE_RECHECK_REQUIRED", at);
        var queryAt = reviewed.budget().nextAttemptAt();
        var budgetQuery = reviewed.withBudget(reviewed.budget().claim(queryAt, LEASE), queryAt);
        var both = budgetQuery.withAccrual(budgetQuery.accrual().claim(queryAt, LEASE), queryAt);
        assertThat(both.status()).isEqualTo(ExpensePartialAdjustment.Status.REVIEW_REQUIRED);
        assertThat(both.budget().status()).isEqualTo(BudgetConsumptionReductionOperation.Status.QUERYING);
        assertThat(both.accrual().status()).isEqualTo(ExpenseAccrualReductionOperation.Status.QUERYING);
    }

    static ExpensePartialAdjustment adjustment() {
        var fixture = ExpenseAdjustmentFundingSourceTest.fixture(List.of("20"));
        return start(source(fixture, "80", "4", List.of(), fixture.returns().entries()), null);
    }
    private static ExpenseAdjustmentFundingSource source(ExpenseAdjustmentFundingSourceTest.Fixture fixture, String gross, String tax,
            List<ExpensePaymentReturns.Entry> previous, List<ExpensePaymentReturns.Entry> selected) {
        return ExpenseAdjustmentFundingSourceTest.source(fixture, ExpenseAdjustmentFundingSourceTest.change(fixture, gross, tax), previous, selected);
    }
    private static ExpensePartialAdjustment start(ExpenseAdjustmentFundingSource funding, ExpensePartialAdjustment previous) { return start(funding, previous, NOW); }
    private static ExpensePartialAdjustment start(ExpenseAdjustmentFundingSource funding, ExpensePartialAdjustment previous, Instant at) {
        return ExpensePartialAdjustment.begin(new ExpensePartialAdjustment.Input(UUID.randomUUID(), ExpensePartialAdjustmentBasis.from(funding, previous), "finance", "adjustment-proof", "部分取消报销", at));
    }
    static BudgetConsumptionReductionOperation budget(ExpensePartialAdjustment value, Instant at) {
        var basis = value.input().basis(); var financial = basis.funding().financial();
        var command = BudgetConsumptionReductionCommand.forExpense(UUID.randomUUID(), value.id(), financial,
                basis.previous() == null ? null : basis.previous().budget(), period(financial, at), "finance", "budget-proof", "预算差额", at, at.plusSeconds(120));
        return BudgetConsumptionReductionOperation.queue(new BudgetConsumptionReductionOperation.Input(financial.consumption().version(), command, financial.consumption().input().targetDigest()), at);
    }
    static ExpenseAccrualReductionOperation accrual(ExpensePartialAdjustment value, Instant at) {
        var basis = value.input().basis(); var financial = basis.funding().financial();
        var original = financial.accrual().observation();
        var fresh = new VoucherObservation(original.operationId(), original.commandDigest(), original.status(), original.revision(), at,
                original.postingReference(), original.voucherReference(), original.periodReference(), original.accountingDate(), original.debitTotal(), original.creditTotal(), original.postedAt(), null);
        var current = financial.accrual().requestQuery(at).claim(at, LEASE).complete(new FinanceResult.Success<>(fresh), at);
        financial = new ExpenseAdjustmentFinancialSource(financial.change(), financial.settlement(), financial.consumption(), current);
        var command = ExpenseAccrualReductionCommand.forExpense(UUID.randomUUID(), value.id(), financial,
                basis.previous() == null ? null : basis.previous().accrual(), period(financial, at), "finance", "accrual-proof", "挂账差额", at, at.plusSeconds(120));
        return ExpenseAccrualReductionOperation.queue(new ExpenseAccrualReductionOperation.Input(current.version(), command, current.input().targetDigest()), at);
    }
    static ExpensePartialAdjustment applyBudget(ExpensePartialAdjustment value, Instant at) {
        var running = value.withBudget(value.budget().claim(at, LEASE), at); var command = running.budget().input().command();
        var proof = new BudgetConsumptionReductionObservation.Posting(command.source().id(), command.source().digest(), command.consumed().reference(), command.expected().revision() + 1,
                "reduction-" + command.id(), command.beforeDigest(), command.afterDigest(), command.reducedAmount(), command.period().periodReference(), command.period().request().accountingDate(), at);
        var observed = new BudgetConsumptionReductionObservation(command.id(), command.adjustmentId(), command.digest(), BudgetConsumptionReductionObservation.Status.APPLIED, at, proof, null);
        return running.withBudget(running.budget().complete(new FinanceResult.Success<>(observed), at), at);
    }
    static ExpensePartialAdjustment postAccrual(ExpensePartialAdjustment value, Instant at) {
        var running = value.withAccrual(value.accrual().claim(at, LEASE), at);
        var observed = ExpenseAccrualReductionTest.posted(running.accrual().input().command(), at, running.accrual().input().command().id().toString());
        return running.withAccrual(running.accrual().complete(new FinanceResult.Success<>(observed), at), at);
    }
    private static ExpensePartialAdjustment ready(ExpensePartialAdjustment value, Instant at) {
        value = value.authorizeBudget(budget(value, at), at).authorizeAccrual(accrual(value, at), at);
        return postAccrual(applyBudget(value, at.plusSeconds(1)), at.plusSeconds(2));
    }
    static ExpensePartialAdjustment complete(ExpensePartialAdjustment value, Instant at) { return ready(value, at).completeResources(at.plusSeconds(3)); }
    private static AccountingPeriodPort.OpenPeriod period(ExpenseAdjustmentFinancialSource financial, Instant at) {
        var date = LocalDate.of(2026, 9, 30);
        return new AccountingPeriodPort.OpenPeriod(new AccountingPeriodPort.Request(financial.accrual().input().command().legalEntityId(), "CNY", date), "2026-09", "v1", date, date, at, at.plusSeconds(300));
    }
    private static ExpenseReport.Reduction target(String gross, String tax) { return new ExpenseReport.Reduction(1, money(gross), money(tax)); }
    private static Money money(String amount) { return new Money(new BigDecimal(amount), "CNY"); }
    private static void invalid(Runnable action) { assertThatThrownBy(action::run).isInstanceOf(DomainException.class); }
}
