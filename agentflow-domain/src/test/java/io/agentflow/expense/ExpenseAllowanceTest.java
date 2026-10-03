package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 补贴围绕日期、发布版本和不可手改金额验证，普通 DAY 费用保持原语义。
 * @author owlzhangfq@gmail.com
 */
class ExpenseAllowanceTest {
    private static final UUID ENTITY = UUID.randomUUID(), POLICY = UUID.randomUUID();
    private static final LocalDate START = LocalDate.of(2024, 2, 28), END = LocalDate.of(2024, 3, 1);
    private static final ExpenseAllowanceRule RULE = new ExpenseAllowanceRule(money("100"), ExpenseAllowanceRule.DayCountBasis.CALENDAR_DAYS_INCLUSIVE);

    @Test void sameDayLeapDayAndYearBoundaryUseInclusiveCalendarDays() {
        assertThat(RULE.calculate(START, START).days()).isEqualTo(1);
        assertThat(RULE.calculate(START, END).gross()).isEqualTo(money("300"));
        assertThat(RULE.calculate(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 1)).days()).isEqualTo(2);
    }

    @Test void exactDecimalDailyRateNeverUsesBinaryFloatingPoint() {
        var rate = new ExpenseAllowanceRule(money("12.34"), RULE.dayCountBasis());
        assertThat(rate.calculate(START, END).gross()).isEqualTo(money("37.02"));
    }

    @Test void invalidDatesMissingBasisAndZeroRateFailBeforeCreatingCalculation() {
        assertCode(() -> RULE.calculate(START, null), "ALLOWANCE_ITINERARY_REQUIRED");
        assertCode(() -> RULE.calculate(END, START), "ALLOWANCE_ITINERARY_REQUIRED");
        assertCode(() -> RULE.calculate(START, START.plusDays(1_000_000)), "ALLOWANCE_ITINERARY_TOO_LONG");
        assertCode(() -> new ExpenseAllowanceRule(money("0"), RULE.dayCountBasis()), "INVALID_ALLOWANCE_RULE");
        assertCode(() -> new ExpenseAllowanceRule(money("100"), null), "INVALID_ALLOWANCE_RULE");
    }

    @Test void persistedCalculationRejectsForgedDaysAndAmount() {
        assertCode(() -> new ExpenseAllowanceRule.Calculation(START, END, 4, RULE, money("400")), "ALLOWANCE_CALCULATION_MISMATCH");
        assertCode(() -> new ExpenseAllowanceRule.Calculation(START, END, 3, RULE, money("301")), "ALLOWANCE_CALCULATION_MISMATCH");
    }

    @Test void allowanceCannotSharePriceCapInvoiceAgeOrForbiddenEffect() {
        assertCode(() -> new ExpensePolicyDefinition.Constraints(ExpensePolicyDefinition.Effect.ALLOW, money("100"), ExpenseLine.Unit.DAY,
                null, null, List.of(), false, RULE), "INVALID_EXPENSE_POLICY_DEFINITION");
        assertCode(() -> new ExpensePolicyDefinition.Constraints(ExpensePolicyDefinition.Effect.ALLOW, null, null,
                180, ExpensePolicyDefinition.AgeAction.REJECT, List.of(), false, RULE), "INVALID_EXPENSE_POLICY_DEFINITION");
        assertCode(() -> new ExpensePolicyDefinition.Constraints(ExpensePolicyDefinition.Effect.DENY, null, null,
                null, null, List.of(), false, RULE), "INVALID_EXPENSE_POLICY_DEFINITION");
    }

    @Test void publicationRequiresExplicitCurrencyCategoryAndDayOnlyUnit() {
        var rule = definition(START, END);
        var valid = new ExpenseCategoryCatalog("demo", 1, List.of(new ExpenseCategoryCatalog.Category("ALLOWANCE", "补贴", List.of(ExpenseLine.Unit.DAY), true)));
        new ExpensePolicyDefinition("补贴", List.of(rule)).requirePublishable(valid);
        var mixed = new ExpenseCategoryCatalog("demo", 1, List.of(new ExpenseCategoryCatalog.Category("ALLOWANCE", "混合", List.of(ExpenseLine.Unit.DAY, ExpenseLine.Unit.ITEM), true)));
        assertCode(() -> new ExpensePolicyDefinition("补贴", List.of(rule)).requirePublishable(mixed), "ALLOWANCE_CATEGORY_UNIT_REQUIRED");
        assertCode(() -> new ExpensePolicyDefinition.Rule("daily", "补贴", match(List.of(), "CNY", START, END), rule.constraints()), "INVALID_EXPENSE_POLICY_DEFINITION");
        assertCode(() -> new ExpensePolicyDefinition.Rule("daily", "补贴", match(List.of("ALLOWANCE"), "USD", START, END), rule.constraints()), "INVALID_EXPENSE_POLICY_DEFINITION");
    }

    @Test void itineraryCannotCrossTheSelectedPolicyDateRange() {
        var line = line(1, START, END, "3", "300", "0", List.of());
        assertCode(() -> ExpenseAllowanceBasis.calculate(receipt(1, "source"), definition(START, END.minusDays(1)), ENTITY, line), "ALLOWANCE_POLICY_PERIOD_MISMATCH");
        assertThat(ExpenseAllowanceBasis.calculate(receipt(1, "source"), definition(START, END), ENTITY, line).calculation().days()).isEqualTo(3);
    }

    @Test void boundLineRejectsManualAmountQuantityTaxInvoiceAndDateChanges() {
        var original = line(1, START, END, "3", "300", "0", List.of());
        var basis = ExpenseAllowanceBasis.calculate(receipt(1, "source"), definition(START, END), ENTITY, original);
        assertThat(original.withAllowance(basis).allowance()).isEqualTo(basis);
        for (var forged : List.of(line(1, START, END, "4", "300", "0", List.of()),
                line(1, START, END, "3", "301", "0", List.of()), line(1, START, END, "3", "300", "1", List.of()),
                line(1, START, END, "3", "300", "0", List.of(UUID.randomUUID())),
                line(1, START, END.plusDays(1), "3", "300", "0", List.of()))) {
            assertCode(() -> forged.withAllowance(basis), "ALLOWANCE_CALCULATION_MISMATCH");
        }
    }

    @Test void changedPublicationRequiresRecalculationWhileSourceRefreshDoesNotRewriteHistory() {
        var line = line(1, START, END, "3", "300", "0", List.of());
        var original = ExpenseAllowanceBasis.calculate(receipt(1, "original-source"), definition(START, END), ENTITY, line);
        original.requireCurrent(ExpenseAllowanceBasis.calculate(receipt(1, "refreshed-source"), definition(START, END), ENTITY, line));
        assertCode(() -> original.requireCurrent(ExpenseAllowanceBasis.calculate(receipt(2, "new-version"), definition(START, END), ENTITY, line)), "ALLOWANCE_RECALCULATION_REQUIRED");
        assertThat(original.policy().factSourceReference()).isEqualTo("original-source");
    }

    @Test void overlappingSameCategoryAllowancesFailButAdjacentDaysRemainSeparate() {
        var first = bound(line(1, START, END, "3", "300", "0", List.of()));
        var overlapping = bound(line(2, END, END, "1", "100", "0", List.of()));
        assertCode(() -> content(List.of(first, overlapping)), "ALLOWANCE_ITINERARY_OVERLAP");
        assertThat(content(List.of(first, bound(line(2, END.plusDays(1), END.plusDays(1), "1", "100", "0", List.of())))).lines()).hasSize(2);
    }

    @Test void ordinaryDayExpensesKeepManualQuantityAndDoNotAcquireAnAllowanceBasis() {
        var ordinary = line(1, START, END, "1.5", "321.23", "12", List.of(UUID.randomUUID()));
        assertThat(ordinary.allowance()).isNull();
        assertThat(content(List.of(ordinary)).lines()).containsExactly(ordinary);
    }

    @Test void precheckRequiresSavedCalculationAndCannotIncreaseAllowedAmountOrTax() {
        var input = line(1, START, END, "3", "300", "0", List.of());
        var receipt = receipt(1, "source");
        var managed = new ManagedExpensePolicy(receipt.selection(), new ExpensePolicyDefinition("补贴", List.of(definition(null, null))),
                new ExpenseCategoryCatalog.Category("ALLOWANCE", "补贴", List.of(ExpenseLine.Unit.DAY), true));
        var rate = new ExpenseExchangeRate("CNY", "CNY", BigDecimal.ONE, "synthetic", START);
        var valid = new ExpensePolicySnapshot(POLICY, 1, money("300"), money("300"), ExpensePolicySnapshot.Decision.WITHIN_LIMIT,
                "synthetic-tax", "synthetic-rule", List.of(), receipt);
        assertCode(() -> input.requireCurrentAllowance(managed, ENTITY, valid, rate, money("0")), "ALLOWANCE_RECALCULATION_REQUIRED");
        var saved = bound(input);
        saved.requireCurrentAllowance(managed, ENTITY, valid, rate, money("0"));
        var forged = new ExpensePolicySnapshot(POLICY, 1, money("300"), money("301"), ExpensePolicySnapshot.Decision.WITHIN_LIMIT,
                "synthetic-tax", "synthetic-rule", List.of(), receipt);
        assertCode(() -> saved.requireCurrentAllowance(managed, ENTITY, forged, rate, money("0")), "ALLOWANCE_ASSESSMENT_MISMATCH");
        assertCode(() -> saved.requireCurrentAllowance(managed, ENTITY, valid, rate, money("1")), "ALLOWANCE_ASSESSMENT_MISMATCH");
        assertCode(() -> saved.requireCurrentAllowance(null, ENTITY, valid, rate, money("0")), "ALLOWANCE_RECALCULATION_REQUIRED");
    }

    private static ExpenseLine bound(ExpenseLine line) {
        return line.withAllowance(ExpenseAllowanceBasis.calculate(receipt(1, "source"), definition(null, null), ENTITY, line));
    }
    private static ExpenseContent content(List<ExpenseLine> lines) { return new ExpenseContent(ENTITY, ExpenseContent.Type.TRAVEL, "行程补贴", lines, List.of()); }
    private static ExpenseLine line(int number, LocalDate start, LocalDate end, String days, String gross, String tax, List<UUID> invoices) {
        return new ExpenseLine(number, "ALLOWANCE", start, end, "SH", new BigDecimal(days), ExpenseLine.Unit.DAY, money(gross), money(tax), invoices,
                null, List.of(new CostAllocation("IT", null, money(gross))), "差旅补贴", null);
    }
    private static ExpensePolicyReceipt receipt(long version, String source) {
        return new ExpensePolicyReceipt(new ExpensePolicySelection(POLICY, version, 1, version, "a".repeat(64)), "daily", source);
    }
    private static ExpensePolicyDefinition.Rule definition(LocalDate from, LocalDate through) {
        return new ExpensePolicyDefinition.Rule("daily", "每日补贴", match(List.of("ALLOWANCE"), "CNY", from, through),
                new ExpensePolicyDefinition.Constraints(ExpensePolicyDefinition.Effect.ALLOW, null, null, null, null, List.of(), false, RULE));
    }
    private static ExpensePolicyDefinition.Match match(List<String> categories, String currency, LocalDate from, LocalDate through) {
        return new ExpensePolicyDefinition.Match(List.of(), categories, List.of(), List.of(), from, through, currency);
    }
    private static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOf(DomainException.class).extracting(failure -> ((DomainException) failure).code()).isEqualTo(code);
    }
}
