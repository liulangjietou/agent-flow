package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.expense.CostAllocation;
import io.agentflow.expense.ExpenseAdjustmentAmounts;
import io.agentflow.expense.ExpenseReport;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 已消费预算按原位置逐次减少，后一次只承接前一次实际结果，不能改写原消费或重用全额冲正。
 * @author owlzhangfq@gmail.com
 */
class BudgetConsumptionReductionTest {
    private static final Instant NOW = Instant.parse("2026-09-30T19:30:00Z");
    private static final LocalDate DATE = LocalDate.of(2026, 9, 30);

    @Test void firstReductionRetainsOriginalConsumptionAndExactCostPositions() {
        var command = command();
        assertThat(command.expected()).isEqualTo(new BudgetCommand.Expected(2, "original-consumption"));
        assertThat(command.reducedAmount()).isEqualTo(money("20"));
        assertThat(command.source().position().total()).isEqualTo(money("100"));
        assertThat(command.digest()).matches("[a-f0-9]{64}").isEqualTo(copy(command, command.after(), command.period(), "finance", "material").digest());
        assertThat(command.toString()).doesNotContain("alice", "finance", "material", "original-consumption");
        assertThatThrownBy(() -> command.after().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void expenseFactoryUsesFinancialSourceDeltasIncludingZeroOriginalPositions() {
        var fixture = ExpenseAdjustmentFinancialSourceTest.fixture("30");
        var change = ExpenseAdjustmentAmounts.from(fixture.report()).reduce(List.of(new ExpenseReport.Reduction(1, money("0"), money("0"))));
        var source = new ExpenseAdjustmentFinancialSource(change, fixture.settlement(), fixture.budget(), fixture.accrual());
        var command = BudgetConsumptionReductionCommand.forExpense(UUID.randomUUID(), UUID.randomUUID(), source, null,
                period(source.consumption().input().command().position().legalEntityId(), DATE), "finance", "material", "全部取消剩余费用", NOW, NOW.plusSeconds(120));
        assertThat(command.before()).isEqualTo(source.budgetBefore());
        assertThat(command.after()).isEqualTo(source.budgetAfter()).hasSize(2);
        assertThat(command.reducedAmount()).isEqualTo(money("100"));
    }

    @Test void nextReductionRequiresTheSameOriginalAndExactPreviousRemainingPositions() {
        var first = command();
        var applied = applied(first, 3, "reduced-once", first.before(), first.after(), NOW.plusSeconds(1));
        var next = next(first, applied, allocations("48", "32"), allocations("30", "20"));
        assertThat(next.expected()).isEqualTo(new BudgetCommand.Expected(3, "reduced-once"));
        assertThat(next.reducedAmount()).isEqualTo(money("30"));
        assertThat(next.source()).isEqualTo(first.source());
        invalid(() -> next(first, null, first.after(), allocations("30", "20")));
        invalid(() -> next(first, applied, allocations("47", "33"), allocations("30", "20")));
        var other = command();
        invalid(() -> next(first, applied(other, 3, "foreign", other.before(), other.after(), NOW.plusSeconds(1)), first.after(), allocations("30", "20")));
        invalid(() -> next(first, outcome(first, BudgetConsumptionReductionObservation.Status.PENDING, NOW), first.after(), allocations("30", "20")));
        invalid(() -> new BudgetConsumptionReductionCommand(first.id(), UUID.randomUUID(), first.source(), first.consumed(), applied, first.after(), allocations("30", "20"),
                first.period(), "finance", "material", "重复使用原号", NOW.plusSeconds(2), NOW.plusSeconds(120)));
        invalid(() -> new BudgetConsumptionReductionCommand(UUID.randomUUID(), first.adjustmentId(), first.source(), first.consumed(), applied, first.after(), allocations("30", "20"),
                first.period(), "finance", "material", "重复使用原调整", NOW.plusSeconds(2), NOW.plusSeconds(120)));
    }

    @Test void unchangedIncreasedRelocatedOrTruncatedAmountsCannotBecomeAReduction() {
        var value = command();
        invalid(() -> copy(value, value.before(), value.period(), "finance", "material"));
        invalid(() -> copy(value, allocations("61", "19"), value.period(), "finance", "material"));
        invalid(() -> copy(value, List.of(value.after().get(0)), value.period(), "finance", "material"));
        var first = value.after().get(0);
        var changed = new BudgetPrecheckPort.Allocation(first.expenseLineNo(), first.allocationNo(), first.categoryCode(), new CostAllocation("other-center", null, first.cost().amount()));
        invalid(() -> copy(value, List.of(changed, value.after().get(1)), value.period(), "finance", "material"));
        invalid(() -> copy(value, List.of(value.after().get(1), value.after().get(0)), value.period(), "finance", "material"));
        var frozen = new BudgetCommand(value.source().id(), "demo", BudgetCommand.Action.FREEZE, value.source().position(), null);
        invalid(() -> new BudgetConsumptionReductionCommand(value.id(), value.adjustmentId(), frozen, value.consumed(), null, value.before(), value.after(),
                value.period(), "finance", "material", "减额", NOW, NOW.plusSeconds(120)));
    }

    @Test void independentActorFreshOpenPeriodAndExplicitSendingWindowAreRequired() {
        var value = command();
        invalid(() -> copy(value, value.after(), value.period(), "alice", "material"));
        invalid(() -> copy(value, value.after(), period(UUID.randomUUID(), DATE), "finance", "material"));
        invalid(() -> copy(value, value.after(), period(value.source().position().legalEntityId(), DATE.minusDays(1)), "finance", "material"));
        invalid(() -> copy(value, value.after(), value.period(), "finance\n", "material"));
        var period = value.period();
        var stale = new AccountingPeriodPort.OpenPeriod(period.request(), period.periodReference(), period.sourceVersion(), period.startsOn(), period.endsOn(), NOW.minusSeconds(301), NOW.plusSeconds(300));
        invalid(() -> copy(value, value.after(), stale, "finance", "material"));
        assertThatCode(() -> value.requireSendAt(NOW)).doesNotThrowAnyException();
        invalid(() -> value.requireSendAt(value.expiresAt()));
        invalid(() -> value.requireSendAt(NOW.minusNanos(1)));
    }

    @Test void digestBindsRemainingPositionsPeriodAndIndependentAuthorization() {
        var value = command();
        assertThat(copy(value, allocations("47", "32"), value.period(), "finance", "material").digest()).isNotEqualTo(value.digest());
        assertThat(copy(value, value.after(), period(value.source().position().legalEntityId(), DATE.plusDays(1)), "finance", "material").digest()).isNotEqualTo(value.digest());
        assertThat(copy(value, value.after(), value.period(), "another-finance", "material").digest()).isNotEqualTo(value.digest());
        assertThat(copy(value, value.after(), value.period(), "finance", "another-proof").digest()).isNotEqualTo(value.digest());
    }

    @Test void successRequiresExactBeforeAfterPositionsNextRevisionAndDistinctEvidence() {
        var command = command();
        assertThat(applied(command, 3, "reduced", command.before(), command.after(), NOW).matches(command, false, NOW.plusSeconds(1))).isTrue();
        for (var bad : List.of(applied(command, 2, "reduced", command.before(), command.after(), NOW),
                applied(command, 4, "reduced", command.before(), command.after(), NOW),
                applied(command, 3, command.consumed().reference(), command.before(), command.after(), NOW),
                applied(command, 3, "reduced", allocations("59", "41"), command.after(), NOW),
                applied(command, 3, "reduced", command.before(), allocations("47", "33"), NOW),
                applied(command, 3, "reduced", command.before(), command.after(), NOW.minusSeconds(1)))) {
            assertThat(bad.matches(command, false, NOW.plusSeconds(1))).isFalse();
        }
        var observed = applied(command, 3, "reduced", command.before(), command.after(), NOW);
        assertThat(observed.matches(command, false, NOW.minusSeconds(1))).isFalse();
        invalid(() -> applied(command, 3, "reduced", command.before(), command.before(), NOW));
        invalid(() -> new BudgetConsumptionReductionObservation(command.id(), command.adjustmentId(), command.digest(), BudgetConsumptionReductionObservation.Status.PENDING,
                NOW, observed.posting(), null));
    }

    @Test void expiredAuthorizationCanQueryOriginalIdentityButNotInterpretNotFoundAsApplied() {
        var command = command();
        var missing = outcome(command, BudgetConsumptionReductionObservation.Status.NOT_FOUND, NOW.plusSeconds(500));
        assertThat(missing.matches(command, true, NOW.plusSeconds(501))).isTrue();
        assertThat(missing.matches(command, false, NOW.plusSeconds(501))).isFalse();
        var rejected = new BudgetConsumptionReductionObservation(command.id(), command.adjustmentId(), command.digest(), BudgetConsumptionReductionObservation.Status.REJECTED,
                NOW, null, BudgetConsumptionReductionObservation.Rejection.LEDGER_VERSION_CONFLICT);
        assertThat(rejected.matches(command, false, NOW)).isTrue();
    }

    private static BudgetConsumptionReductionCommand command() {
        var position = new BudgetPrecheckPort.Request(UUID.randomUUID(), 1, 2, "alice", UUID.randomUUID(), "CNY", DATE, allocations("60", "40"));
        var original = new BudgetCommand(UUID.randomUUID(), "demo", BudgetCommand.Action.CONSUME, position, new BudgetCommand.Expected(1, "original-freeze"));
        var consumed = new BudgetObservation(original.id(), original.digest(), BudgetObservation.Status.APPLIED, 2L, "original-consumption", NOW.minusSeconds(10), null);
        return new BudgetConsumptionReductionCommand(UUID.randomUUID(), UUID.randomUUID(), original, consumed, null, position.allocations(), allocations("48", "32"),
                period(position.legalEntityId(), DATE), "finance", "material", "减少实际消费", NOW, NOW.plusSeconds(120));
    }
    private static BudgetConsumptionReductionCommand next(BudgetConsumptionReductionCommand first, BudgetConsumptionReductionObservation previous,
            List<BudgetPrecheckPort.Allocation> before, List<BudgetPrecheckPort.Allocation> after) {
        return new BudgetConsumptionReductionCommand(UUID.randomUUID(), UUID.randomUUID(), first.source(), first.consumed(), previous, before, after,
                first.period(), "finance", "material", "后续减少", NOW.plusSeconds(2), NOW.plusSeconds(120));
    }
    private static BudgetConsumptionReductionCommand copy(BudgetConsumptionReductionCommand value, List<BudgetPrecheckPort.Allocation> after,
            AccountingPeriodPort.OpenPeriod period, String actor, String proof) {
        return new BudgetConsumptionReductionCommand(value.id(), value.adjustmentId(), value.source(), value.consumed(), value.previous(), value.before(), after,
                period, actor, proof, value.reason(), value.createdAt(), value.expiresAt());
    }
    private static BudgetConsumptionReductionObservation applied(BudgetConsumptionReductionCommand value, long revision, String reference,
            List<BudgetPrecheckPort.Allocation> before, List<BudgetPrecheckPort.Allocation> after, Instant at) {
        var posting = new BudgetConsumptionReductionObservation.Posting(value.source().id(), value.source().digest(), value.consumed().reference(), revision, reference,
                before, after, value.period().periodReference(), value.period().request().accountingDate(), at);
        return new BudgetConsumptionReductionObservation(value.id(), value.adjustmentId(), value.digest(), BudgetConsumptionReductionObservation.Status.APPLIED,
                NOW.plusSeconds(1), posting, null);
    }
    private static BudgetConsumptionReductionObservation outcome(BudgetConsumptionReductionCommand value, BudgetConsumptionReductionObservation.Status status, Instant at) {
        return new BudgetConsumptionReductionObservation(value.id(), value.adjustmentId(), value.digest(), status, at, null, null);
    }
    private static List<BudgetPrecheckPort.Allocation> allocations(String first, String second) {
        return List.of(new BudgetPrecheckPort.Allocation(1, 1, "OFFICE", new CostAllocation("A", null, money(first))),
                new BudgetPrecheckPort.Allocation(1, 2, "OFFICE", new CostAllocation("B", "PROJECT", money(second))));
    }
    private static AccountingPeriodPort.OpenPeriod period(UUID entity, LocalDate date) {
        return new AccountingPeriodPort.OpenPeriod(new AccountingPeriodPort.Request(entity, "CNY", date), "current-period", "v1", DATE.minusDays(10), DATE.plusDays(10), NOW.minusSeconds(1), NOW.plusSeconds(300));
    }
    private static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
    private static void invalid(Runnable action) { assertThatThrownBy(action::run).isInstanceOf(DomainException.class); }
}
