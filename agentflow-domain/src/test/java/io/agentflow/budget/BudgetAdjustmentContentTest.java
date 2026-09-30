package io.agentflow.budget;

import io.agentflow.finance.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static io.agentflow.budget.BudgetAdjustmentContent.Type.*;
import static io.agentflow.budget.BudgetAdjustmentTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 独立预算调整守住占用余额、调拨守恒、期间及原台账身份，不依赖已有报销预检。
 * @author owlzhangfq@gmail.com
 */
class BudgetAdjustmentContentTest {
    @Test void transferConservesTotalAtAvailableBoundaryWithoutChangingOccupationsOrDependingOnResponseOrder() {
        var content = content(TRANSFER, "250.00");
        var source = position("budget-source", "1000", "300", "450");
        var target = position("budget-target", "2000", "100", "200");
        var ledger = ledger(content, target, source);
        var changes = content.changes(ledger);
        assertThat(changes).containsExactly(
                new BudgetAdjustmentContent.Change("budget-source", "v1", money("1000"), money("750")),
                new BudgetAdjustmentContent.Change("budget-target", "v1", money("2000"), money("2250")));
        assertThat(changes.stream().map(change -> change.afterLimit().value()).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("3000");
        assertThat(ledger.position("budget-source").committed()).isEqualTo(money("300"));
        assertThat(ledger.position("budget-source").consumed()).isEqualTo(money("450"));
        assertThatThrownBy(() -> changes.clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void reductionCannotUseCommittedOrConsumedAmountsAndOneCentAboveAvailableFails() {
        var allowed = content(DECREASE, "250");
        assertThat(allowed.changes(ledger(allowed)).get(0).afterLimit()).isEqualTo(money("750"));
        for (var type : List.of(DECREASE, TRANSFER)) {
            var excessive = content(type, "250.01");
            fails("BUDGET_ADJUSTMENT_INSUFFICIENT", () -> excessive.changes(ledger(excessive)));
        }
        var overdrawn = position("budget-source", "100", "70", "50");
        assertThat(overdrawn.available()).isEqualTo(money("0"));
        var decrease = content(DECREASE, "0.01");
        fails("BUDGET_ADJUSTMENT_INSUFFICIENT", () -> decrease.changes(ledger(decrease, overdrawn)));
    }

    @Test void increaseAcceptsRealOverdrawnFactsButNeverOverflowsSupportedAmount() {
        var increase = content(INCREASE, "10");
        var overdrawn = position("budget-target", "100", "70", "50");
        assertThat(increase.changes(ledger(increase, overdrawn)).get(0).afterLimit()).isEqualTo(money("110"));
        var full = position("budget-target", Money.MAX_VALUE.toPlainString(), "0", "0");
        fails("INVALID_MONEY", () -> increase.changes(ledger(increase, full)));
    }

    @Test void transferRejectsClosedPeriodsMismatchedPeriodsAndCurrencyWithoutImplicitConversion() {
        var content = content(TRANSFER, "10");
        var source = position("budget-source", "1000", "0", "0");
        var target = position("budget-target", "1000", "0", "0");
        var closed = new BudgetLedgerPort.Position(ENTITY, target.reference(), target.name(), target.version(), target.periodReference(),
                target.periodStart(), target.periodEnd(), BudgetLedgerPort.PeriodStatus.CLOSED, target.limit(), target.committed(), target.consumed());
        fails("BUDGET_PERIOD_CLOSED", () -> content.changes(ledger(content, source, closed)));
        var otherPeriod = new BudgetLedgerPort.Position(ENTITY, target.reference(), target.name(), target.version(), "2026-Q3",
                LocalDate.parse("2026-07-01"), LocalDate.parse("2026-09-30"), target.periodStatus(), target.limit(), target.committed(), target.consumed());
        fails("BUDGET_TRANSFER_PERIOD_MISMATCH", () -> content.changes(ledger(content, source, otherPeriod)));
        var conflictingDates = new BudgetLedgerPort.Position(ENTITY, target.reference(), target.name(), target.version(), source.periodReference(),
                otherPeriod.periodStart(), otherPeriod.periodEnd(), target.periodStatus(), target.limit(), target.committed(), target.consumed());
        fails("BUDGET_TRANSFER_PERIOD_MISMATCH", () -> content.changes(ledger(content, source, conflictingDates)));
        var dollars = new BudgetLedgerPort.Position(ENTITY, target.reference(), target.name(), target.version(), target.periodReference(),
                target.periodStart(), target.periodEnd(), target.periodStatus(), new Money(new BigDecimal("100"), "USD"), Money.zero("USD"), Money.zero("USD"));
        fails("CURRENCY_MISMATCH", () -> content.changes(ledger(content, source, dollars)));
    }

    @Test void intentRejectsContradictoryReferencesAndDoesNotAcceptZeroOrSignedAmounts() {
        fails("INVALID_BUDGET_ADJUSTMENT", () -> content(INCREASE, "0"));
        fails("INVALID_MONEY", () -> content(DECREASE, "-1"));
        fails("INVALID_BUDGET_ADJUSTMENT", () -> new BudgetAdjustmentContent(ENTITY, "调整", "理由", INCREASE, DATE, "source", "target", money("1")));
        fails("INVALID_BUDGET_ADJUSTMENT", () -> new BudgetAdjustmentContent(ENTITY, "调整", "理由", DECREASE, DATE, "source", "target", money("1")));
        fails("INVALID_BUDGET_ADJUSTMENT", () -> new BudgetAdjustmentContent(ENTITY, "调整", "理由", TRANSFER, DATE, "source", "source", money("1")));
        fails("INVALID_BUDGET_ADJUSTMENT", () -> new BudgetAdjustmentContent(ENTITY, "调整", "理由", TRANSFER, DATE, " source", "target", money("1")));
        fails("INVALID_BUDGET_ADJUSTMENT", () -> new BudgetAdjustmentContent(ENTITY, "调整", "理由", DECREASE, DATE, null, null, money("1")));
    }

    @Test void ledgerRejectsForeignMissingDuplicateAndOutOfPeriodPositions() {
        var content = content(TRANSFER, "10");
        var source = position("budget-source", "1000", "0", "0");
        var target = position("budget-target", "1000", "0", "0");
        fails("INVALID_BUDGET_LEDGER", () -> ledger(content, source));
        fails("INVALID_BUDGET_LEDGER", () -> ledger(content, source, source));
        fails("INVALID_BUDGET_LEDGER", () -> ledger(content, source, position("unrequested", "1", "0", "0")));
        var foreign = new BudgetLedgerPort.Position(UUID.randomUUID(), target.reference(), target.name(), target.version(), target.periodReference(),
                target.periodStart(), target.periodEnd(), target.periodStatus(), target.limit(), target.committed(), target.consumed());
        fails("INVALID_BUDGET_LEDGER", () -> ledger(content, source, foreign));
        var past = new BudgetLedgerPort.Position(ENTITY, target.reference(), target.name(), target.version(), "2025", target.periodStart().minusYears(1),
                target.periodEnd().minusYears(1), target.periodStatus(), target.limit(), target.committed(), target.consumed());
        fails("INVALID_BUDGET_LEDGER", () -> ledger(content, source, past));
        fails("INVALID_BUDGET_LEDGER", () -> new BudgetLedgerPort.Request(ENTITY, "alice", DATE, List.of("same", "same")));
    }

    @Test void freshnessChecksApplicantOriginalDateFiveMinuteBoundaryAndFutureObservation() {
        var content = content(INCREASE, "10");
        var ledger = ledger(content);
        assertThat(ledger.matches(content.ledgerRequest("alice"), NOW)).isTrue();
        assertThat(ledger.matches(content.ledgerRequest("bob"), NOW)).isFalse();
        assertThat(ledger.matches(content.ledgerRequest("alice"), ledger.observedAt().plusSeconds(300))).isFalse();
        assertThat(ledger.matches(content.ledgerRequest("alice"), ledger.observedAt().minusNanos(1))).isFalse();
        var expired = new BudgetLedgerPort.Snapshot(ledger.request(), ledger.sourceVersion(), NOW.minusSeconds(1), NOW, ledger.positions());
        assertThat(expired.matches(ledger.request(), NOW)).isFalse();
        assertThat(ledger.toString()).doesNotContain("1000", "budget-target");
    }
}
