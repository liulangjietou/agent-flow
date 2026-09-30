package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.EmployeeAccountSnapshot;
import io.agentflow.finance.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 已核销金额的独立差额只减少原位置，不能用再次分摊增加其他成本或抹去原批准。
 * @author owlzhangfq@gmail.com
 */
class ExpenseAdjustmentAmountsTest {
    private static final UUID ENTITY = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-30T18:30:00Z");

    @Test void partialReductionPreservesOriginalReportAndBalancesTaxCostsCashAndOffsets() {
        var advance = UUID.randomUUID(); var report = frozen("100", "6", List.of("60", "40"), List.of(new AdvanceOffset(advance, money("30"))));
        var original = report.state(); var before = ExpenseAdjustmentAmounts.from(report);
        var change = before.reduce(List.of(target(1, "80", "4")));
        assertThat(report.state()).isEqualTo(original); assertThat(change.after().original()).isEqualTo(original);
        assertThat(change.gross()).isEqualTo(money("20")); assertThat(change.tax()).isEqualTo(money("2"));
        assertThat(change.bankReturn()).isEqualTo(money("20")); assertThat(change.advanceReversals()).isEmpty();
        assertThat(change.after().gross()).isEqualTo(money("80")); assertThat(change.after().offsets()).isEqualTo(before.offsets());
        assertThat(change.lines().get(0).allocations()).containsExactly(
                new ExpenseAdjustmentAmounts.Allocation("center-1", null, money("12"), money("1.2")),
                new ExpenseAdjustmentAmounts.Allocation("center-2", null, money("8"), money("0.8")));
        assertThat(before.gross()).isEqualTo(money("100")); assertThat(before.tax()).isEqualTo(money("6"));
    }

    @Test void advanceReductionRestoresLastSelectedOffsetsOnlyAfterOriginalPayableIsExhausted() {
        var first = UUID.randomUUID(); var last = UUID.randomUUID();
        var before = ExpenseAdjustmentAmounts.from(frozen("100", "0", List.of("100"), List.of(new AdvanceOffset(first, money("40")), new AdvanceOffset(last, money("50")))));
        var change = before.reduce(List.of(target(1, "23", "0")));
        assertThat(change.bankReturn()).isEqualTo(money("10"));
        assertThat(change.advanceReversals()).containsExactly(new AdvanceOffset(first, money("17")), new AdvanceOffset(last, money("50")));
        assertThat(change.after().offsets()).containsExactly(new AdvanceOffset(first, money("23")), new AdvanceOffset(last, money("0")));
        var next = change.after().reduce(List.of(target(1, "10", "0")));
        assertThat(next.gross()).isEqualTo(money("13")); assertThat(next.bankReturn()).isEqualTo(money("0"));
        assertThat(next.advanceReversals()).containsExactly(new AdvanceOffset(first, money("13")));
        assertThat(next.after().original()).isEqualTo(before.original());
    }

    @Test void zeroPayableReductionRequiresOnlyOffsetReversalAndFullCancellationKeepsZeroPositions() {
        var advance = UUID.randomUUID();
        var before = ExpenseAdjustmentAmounts.from(frozen("100", "6", List.of("100"), List.of(new AdvanceOffset(advance, money("100")))));
        var first = before.reduce(List.of(target(1, "60", "3")));
        assertThat(first.bankReturn()).isEqualTo(money("0"));
        var last = first.after().reduce(List.of(target(1, "0", "0")));
        assertThat(last.gross()).isEqualTo(money("60")); assertThat(last.tax()).isEqualTo(money("3"));
        assertThat(last.after().lines()).hasSize(1); assertThat(last.after().lines().get(0).allocations()).hasSize(1);
        assertThat(last.after().gross()).isEqualTo(money("0")); assertThat(last.after().offsetTotal()).isEqualTo(money("0"));
        invalid(() -> last.after().reduce(List.of(target(1, "0", "0"))));
    }

    @Test void subtractingOneCentCannotIncreaseAnExistingCostPositionAfterPriorApprovalReduction() {
        var report = frozen("51", "0", List.of("15", "15", "9", "5", "5", "2"), List.of());
        report.reduce(2, List.of(target(1, "0.26", "0")), "finance", "APPROVAL_REDUCTION", "批准前核减", NOW.plusSeconds(1));
        var before = ExpenseAdjustmentAmounts.from(report); var change = before.reduce(List.of(target(1, "0.25", "0")));
        assertThat(before.lines().get(0).allocations().stream().map(ExpenseAdjustmentAmounts.Allocation::gross))
                .containsExactly(money("0.08"), money("0.08"), money("0.05"), money("0.02"), money("0.02"), money("0.01"));
        assertThat(change.after().lines().get(0).allocations().stream().map(ExpenseAdjustmentAmounts.Allocation::gross))
                .containsExactly(money("0.07"), money("0.08"), money("0.05"), money("0.02"), money("0.02"), money("0.01"));
        assertThat(change.gross()).isEqualTo(money("0.01"));
    }

    @Test void taxAndNetExpenseReduceWithinTheirOwnOriginalPositionsWithoutMovingCents() {
        var before = ExpenseAdjustmentAmounts.from(frozen("0.03", "0.02", List.of("0.01", "0.01", "0.01"), List.of()));
        var change = before.reduce(List.of(target(1, "0.02", "0.01")));
        assertThat(change.after().lines().get(0).allocations()).containsExactly(
                new ExpenseAdjustmentAmounts.Allocation("center-1", null, money("0"), money("0")),
                new ExpenseAdjustmentAmounts.Allocation("center-2", null, money("0.01"), money("0.01")),
                new ExpenseAdjustmentAmounts.Allocation("center-3", null, money("0.01"), money("0")));
        assertThat(change.lines().get(0).gross()).isEqualTo(money("0.01"));
        assertThat(change.lines().get(0).tax()).isEqualTo(money("0.01"));
    }

    @Test void repeatedReductionsUseRemainingAmountsAndCannotRecoverAlreadyReducedMoney() {
        var before = ExpenseAdjustmentAmounts.from(frozen("100", "6", List.of("50", "50"), List.of()));
        var first = before.reduce(List.of(target(1, "75", "4"))); var second = first.after().reduce(List.of(target(1, "20", "1")));
        assertThat(first.gross().plus(second.gross())).isEqualTo(money("80"));
        assertThat(first.tax().plus(second.tax())).isEqualTo(money("5"));
        invalid(() -> second.after().reduce(List.of(target(1, "21", "1"))));
        invalid(() -> second.after().reduce(List.of(target(1, "20", "0"))));
        assertThat(before.gross()).isEqualTo(money("100"));
    }

    @Test void invalidFinalLineNeverPartiallyChangesAnEarlierLine() {
        var report = frozen("100", "6", List.of("100"), List.of()); var before = ExpenseAdjustmentAmounts.from(report);
        for (var targets : List.of(List.of(target(1, "80", "4"), target(2, "1", "0")), List.of(target(1, "80", "4"), target(1, "60", "3")),
                List.of(target(1, "101", "6")), List.of(target(1, "95", "0")), List.of(target(1, "90", "7")), List.of(target(1, "1", "2")))) {
            invalid(() -> before.reduce(targets)); assertThat(before.gross()).isEqualTo(money("100"));
        }
        invalid(() -> before.reduce(List.of(new ExpenseReport.Reduction(1, new Money(new BigDecimal("80"), "USD"), money("4")))));
        invalid(() -> before.reduce(List.of())); invalid(() -> before.reduce(null));
    }

    @Test void restorationRejectsChangedCostIdentitiesAmountsTaxAndOffsetOwnership() {
        var id = UUID.randomUUID(); var before = ExpenseAdjustmentAmounts.from(frozen("100", "6", List.of("100"), List.of(new AdvanceOffset(id, money("30")))));
        invalid(() -> new ExpenseAdjustmentAmounts(before.original(), List.of(), before.offsets()));
        for (var allocation : List.of(new ExpenseAdjustmentAmounts.Allocation("foreign", null, money("50"), money("3")),
                new ExpenseAdjustmentAmounts.Allocation("center-1", null, money("101"), money("6")),
                new ExpenseAdjustmentAmounts.Allocation("center-1", null, money("100"), money("5")))) {
            invalid(() -> new ExpenseAdjustmentAmounts(before.original(), List.of(new ExpenseAdjustmentAmounts.Line(1, List.of(allocation))), before.offsets()));
        }
        invalid(() -> new ExpenseAdjustmentAmounts(before.original(), before.lines(), List.of(new AdvanceOffset(UUID.randomUUID(), money("30")))));
        invalid(() -> new ExpenseAdjustmentAmounts(before.original(), before.lines(), List.of(new AdvanceOffset(id, money("31")))));
        assertThatThrownBy(() -> before.lines().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> before.lines().get(0).allocations().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> before.offsets().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void unfrozenDraftCannotBeUsedAsAnAlreadyApprovedAmountBasis() {
        var report = frozen("100", "6", List.of("100"), List.of()); report.revise(report.version(), report.content());
        invalid(() -> ExpenseAdjustmentAmounts.from(report));
        var draft = ExpenseReport.draft(UUID.randomUUID(), "demo", UUID.randomUUID(), "alice", report.content());
        invalid(() -> ExpenseAdjustmentAmounts.from(draft));
    }

    private static ExpenseReport frozen(String gross, String tax, List<String> amounts, List<AdvanceOffset> offsets) {
        var allocations = new java.util.ArrayList<CostAllocation>();
        for (int i = 0; i < amounts.size(); i++) allocations.add(new CostAllocation("center-" + (i + 1), null, money(amounts.get(i))));
        var line = new ExpenseLine(1, "OFFICE", LocalDate.of(2026, 9, 30), null, "SH", BigDecimal.ONE, ExpenseLine.Unit.ITEM,
                money(gross), money(tax), List.of(UUID.randomUUID()), new ExpenseLine.PriorRequestLine(UUID.randomUUID(), 1), allocations, "已核销费用", null);
        var report = ExpenseReport.draft(UUID.randomUUID(), "demo", UUID.randomUUID(), "alice", new ExpenseContent(ENTITY, ExpenseContent.Type.DAILY, "独立差额验收", List.of(line), offsets));
        var assessment = new ExpenseAssessment(new ExpenseExchangeRate("CNY", "CNY", BigDecimal.ONE, "local", line.incurredOn()),
                new ExpensePolicySnapshot(UUID.randomUUID(), 1, money(gross), money(gross), ExpensePolicySnapshot.Decision.WITHIN_LIMIT, "tax", "policy"), money(tax));
        report.freeze(1, 1, "CNY", new EmployeeAccountSnapshot(ENTITY, "alice", "original-account", "****1234", "a".repeat(64), "v1"), Map.of(1, assessment), "alice", NOW);
        return report;
    }
    private static ExpenseReport.Reduction target(int line, String gross, String tax) { return new ExpenseReport.Reduction(line, money(gross), money(tax)); }
    private static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
    private static void invalid(Runnable action) { assertThatThrownBy(action::run).isInstanceOf(DomainException.class); }
}
