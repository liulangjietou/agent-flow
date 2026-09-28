package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.EmployeeAccountSnapshot;
import io.agentflow.finance.Money;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 覆盖金额守恒、提交快照、全量校验后核减及原始证据不可变等财务风险。
 * @author owlzhangfq@gmail.com
 */
class ExpenseReportTest {
    private static final UUID ENTITY = UUID.randomUUID();
    private static final Instant SUBMITTED = Instant.parse("2026-09-28T12:00:00Z");
    private static final Instant ADJUSTED = SUBMITTED.plusSeconds(60);

    @Test
    void moneyRejectsPrecisionOverflowNegativeAndImplicitCurrencyConversion() {
        assertThat(money("1.2300", "CNY").value().toPlainString()).isEqualTo("1.23");
        for (String value : List.of("-0.01", "1.001", "1000000000000000")) fails("INVALID_MONEY", () -> money(value, "CNY"));
        for (String currency : List.of("cny", "BAD", "JPY", "KWD")) fails("INVALID_MONEY", () -> money("1", currency));
        fails("CURRENCY_MISMATCH", () -> money("10", "CNY").plus(money("10", "USD")));
        fails("INVALID_MONEY", () -> money("10", "CNY").minus(money("11", "CNY")));
    }

    @Test
    void allocationConservesSingleCentWithStableTiesAndSupportsZeroOrForeignCurrencyTotal() {
        var allocations = List.of(allocation("a", "1", "USD"), allocation("b", "1", "USD"), allocation("c", "1", "USD"));
        var converted = CostAllocation.apportion(allocations, money("0.01", "CNY"));
        assertThat(converted.stream().map(it -> it.amount().value().toPlainString())).containsExactly("0.01", "0.00", "0.00");
        assertThat(converted).allMatch(it -> "CNY".equals(it.amount().currency()));
        var zero = CostAllocation.apportion(allocations, Money.zero("CNY"));
        assertThat(zero).allMatch(it -> it.amount().value().signum() == 0);
        var unequal = CostAllocation.apportion(List.of(allocation("a", "0.01", "CNY"), allocation("b", "0.02", "CNY"), allocation("c", "0.03", "CNY")), money("0.05", "CNY"));
        assertThat(unequal.stream().map(it -> it.amount().value().toPlainString())).containsExactly("0.01", "0.02", "0.02");
    }

    @Test
    void invoicesCannotBeDuplicatedWithinALineOrAcrossTheReport() {
        UUID invoice = UUID.randomUUID();
        fails("INVALID_EXPENSE_LINE", () -> line(1, "10", "0", "CNY", List.of(invoice, invoice), null));
        var first = line(1, "10", "0", "CNY", List.of(invoice), null);
        var second = line(2, "10", "0", "CNY", List.of(invoice), null);
        fails("INVOICE_REUSED", () -> content(List.of(first, second), List.of()));
        fails("INVALID_EXPENSE_LINE", () -> content(List.of(first, first), List.of()));
    }

    @Test
    void originalAllocationsMustBePositiveUniqueAndExactlyBalanced() {
        fails("ALLOCATION_UNBALANCED", () -> line(1, "10", "0", "CNY", List.of(), List.of(allocation("a", "9.99", "CNY"))));
        fails("INVALID_EXPENSE_LINE", () -> line(1, "10", "0", "CNY", List.of(), List.of(allocation("a", "5", "CNY"), allocation("a", "5", "CNY"))));
        fails("INVALID_EXPENSE_LINE", () -> line(1, "10", "0", "CNY", List.of(), List.of(allocation("a", "10", "CNY"), allocation("b", "0", "CNY"))));
        fails("INVALID_EXPENSE_LINE", () -> line(1, "10", "10.01", "CNY", List.of(), null));
    }

    @Test
    void conversionRoundsEachLineBeforeAddingAndFreezesSourceRateAndPolicy() {
        var first = line(1, "0.01", "0", "USD", List.of(), null);
        var second = line(2, "0.01", "0", "USD", List.of(), null);
        var report = draft(content(List.of(first, second), List.of()));
        var facts = Map.of(1, assessment(first, "1.5"), 2, assessment(second, "1.5"));
        report.freeze(1, 1, "CNY", account(), facts, "alice", SUBMITTED);
        assertThat(report.version()).isEqualTo(2);
        var round = report.currentRound();
        assertThat(round.approvedGross()).isEqualTo(money("0.04", "CNY"));
        assertThat(round.payable()).isEqualTo(money("0.04", "CNY"));
        assertThat(round.originalLines().get(0).assessment()).isSameAs(facts.get(1));
        assertThat(round.submittedFinancialVersion()).isEqualTo(1);
        assertThat(round.content()).isEqualTo(report.content());
    }

    @Test
    void missingMismatchedOrDeniedFactsCannotCreateARound() {
        var line = line(1, "100", "6", "CNY", List.of(), null);
        var report = draft(content(List.of(line), List.of()));
        fails("EXPENSE_PRECHECK_REQUIRED", () -> report.freeze(1, 1, "CNY", account(), Map.of(), "alice", SUBMITTED));
        var allowed = assessment(line, "1");
        var badAmount = new ExpenseAssessment(allowed.exchangeRate(), policy(money("99", "CNY"), money("99", "CNY"), ExpensePolicySnapshot.Decision.WITHIN_LIMIT), allowed.deductibleTax());
        fails("EXPENSE_PRECHECK_REQUIRED", () -> report.freeze(1, 1, "CNY", account(), Map.of(1, badAmount), "alice", SUBMITTED));
        var badTax = new ExpenseAssessment(allowed.exchangeRate(), allowed.policy(), money("6.01", "CNY"));
        fails("EXPENSE_PRECHECK_REQUIRED", () -> report.freeze(1, 1, "CNY", account(), Map.of(1, badTax), "alice", SUBMITTED));
        var denied = new ExpenseAssessment(allowed.exchangeRate(), policy(line.claimedGross(), Money.zero("CNY"), ExpensePolicySnapshot.Decision.DENIED), allowed.deductibleTax());
        fails("EXPENSE_POLICY_DENIED", () -> report.freeze(1, 1, "CNY", account(), Map.of(1, denied), "alice", SUBMITTED));
        assertThat(report.rounds()).isEmpty(); assertThat(report.version()).isEqualTo(1);
    }

    @Test
    void overLimitRequiresAnExplicitExceptionReasonAndDoesNotInventTax() {
        var line = line(1, "100", "6", "CNY", List.of(), null);
        var report = draft(content(List.of(line), List.of()));
        var fact = new ExpenseAssessment(assessment(line, "1").exchangeRate(), policy(line.claimedGross(), money("80", "CNY"), ExpensePolicySnapshot.Decision.REQUIRES_EXCEPTION), Money.zero("CNY"));
        fails("EXPENSE_EXCEPTION_REASON_REQUIRED", () -> report.freeze(1, 1, "CNY", account(), Map.of(1, fact), "alice", SUBMITTED));
        var explained = new ExpenseLine(line.lineNo(), line.categoryCode(), line.incurredOn(), null, line.cityCode(), line.quantity(), line.unit(), line.claimedGross(), line.claimedTax(), line.invoiceIds(), null, line.allocations(), line.description(), "行程调整导致超标");
        report.revise(1, content(List.of(explained), List.of()));
        report.freeze(2, 1, "CNY", account(), Map.of(1, fact), "alice", SUBMITTED);
        assertThat(report.currentRound().approvedTax()).isEqualTo(Money.zero("CNY"));
        assertThat(report.currentRound().originalLines().get(0).original().claimedTax()).isEqualTo(money("6", "CNY"));
    }

    @Test
    void wrongPayeeEntityCurrencyAndExcessOffsetsFailBeforeFreezing() {
        var line = line(1, "100", "0", "CNY", List.of(), null);
        var report = draft(content(List.of(line), List.of()));
        var facts = Map.of(1, assessment(line, "1"));
        var another = new EmployeeAccountSnapshot(ENTITY, "bob", "account", "***1234", "a".repeat(64), "v1");
        fails("EXPENSE_ACCOUNT_MISMATCH", () -> report.freeze(1, 1, "CNY", another, facts, "alice", SUBMITTED));
        fails("EXPENSE_ACCOUNT_MISMATCH", () -> report.freeze(1, 1, "CNY", account(), facts, "bob", SUBMITTED));
        var foreign = new EmployeeAccountSnapshot(UUID.randomUUID(), "alice", "account", "***1234", "a".repeat(64), "v1");
        fails("EXPENSE_ACCOUNT_MISMATCH", () -> report.freeze(1, 1, "CNY", foreign, facts, "alice", SUBMITTED));
        for (String currency : List.of("CNY", "USD")) {
            var withOffset = draft(content(List.of(line), List.of(new AdvanceOffset(UUID.randomUUID(), money("100.01", currency)))));
            fails(currency.equals("CNY") ? "ADVANCE_OFFSET_EXCEEDS_EXPENSE" : "CURRENCY_MISMATCH",
                    () -> withOffset.freeze(1, 1, "CNY", account(), facts, "alice", SUBMITTED));
            assertThat(withOffset.rounds()).isEmpty(); assertThat(withOffset.version()).isEqualTo(1);
        }
        assertThat(report.rounds()).isEmpty();
    }

    @Test
    void reductionRebalancesAllocationsAndReleasesLastSelectedAdvancesFirst() {
        var first = line(1, "3", "0.30", "CNY", List.of(UUID.randomUUID()), List.of(allocation("a", "1", "CNY"), allocation("b", "1", "CNY"), allocation("c", "1", "CNY")));
        var second = line(2, "97", "0", "CNY", List.of(), null);
        UUID advance1 = UUID.randomUUID(), advance2 = UUID.randomUUID();
        var report = draft(content(List.of(first, second), List.of(new AdvanceOffset(advance1, money("40", "CNY")), new AdvanceOffset(advance2, money("50", "CNY")))));
        report.freeze(1, 1, "CNY", account(), Map.of(1, assessment(first, "1"), 2, assessment(second, "1")), "alice", SUBMITTED);
        var original = report.currentRound();
        var change = report.reduce(2, List.of(reduction(1, "0.01", "0"), reduction(2, "20", "0")), "finance", "UNSUPPORTED_COST", "剔除无凭据部分", ADJUSTED);
        var adjusted = report.currentRound();
        assertThat(adjusted.approvedGross()).isEqualTo(money("20.01", "CNY"));
        assertThat(adjusted.payable()).isEqualTo(Money.zero("CNY"));
        assertThat(adjusted.advanceOffsets()).containsExactly(new AdvanceOffset(advance1, money("20.01", "CNY")), new AdvanceOffset(advance2, Money.zero("CNY")));
        assertThat(change.offsetChanges().stream().map(ExpenseAdjustment.OffsetChange::advanceId)).containsExactly(advance2, advance1);
        assertThat(adjusted.approvedLines().get(0).allocations().stream().map(it -> it.amount().value().toPlainString())).containsExactly("0.01", "0.00", "0.00");
        assertThat(original.approvedGross()).isEqualTo(money("100", "CNY"));
        assertThat(original.adjustments()).isEmpty();
        assertThat(adjusted.originalLines()).isEqualTo(original.originalLines());
        assertThat(change.previousFinancialVersion()).isEqualTo(2); assertThat(report.version()).isEqualTo(3);
    }

    @Test
    void invalidLastLineCannotPartiallyReduceEarlierLinesOrAppendAudit() {
        var first = line(1, "10", "1", "CNY", List.of(), null);
        var second = line(2, "20", "2", "CNY", List.of(), null);
        var report = frozen(first, second);
        var before = report.state();
        fails("INVALID_EXPENSE_REDUCTION", () -> report.reduce(2, List.of(reduction(1, "5", "0.5"), reduction(2, "21", "1")), "finance", "ADJUST", "核减", ADJUSTED));
        assertThat(report.state()).isEqualTo(before);
        fails("INVALID_EXPENSE_REDUCTION", () -> report.reduce(2, List.of(reduction(1, "5", "0.5"), reduction(3, "1", "0")), "finance", "ADJUST", "核减", ADJUSTED));
        assertThat(report.state()).isEqualTo(before);
    }

    @Test
    void taxIncreasesTaxAboveGrossDuplicateAndNoChangeRequestsAreRejected() {
        var report = frozen(line(1, "100", "6", "CNY", List.of(), null));
        for (var request : List.of(List.of(reduction(1, "90", "6.01")), List.of(reduction(1, "1", "2")),
                List.of(reduction(1, "100", "6")), List.of(reduction(1, "90", "5"), reduction(1, "80", "5")))) {
            fails("INVALID_EXPENSE_REDUCTION", () -> report.reduce(2, request, "finance", "ADJUST", "核减", ADJUSTED));
        }
        assertThat(report.version()).isEqualTo(2);
        report.reduce(2, List.of(reduction(1, "0", "0")), "finance", "NO_EVIDENCE", "全额核减", ADJUSTED);
        assertThat(report.currentRound().payable()).isEqualTo(Money.zero("CNY"));
        assertThat(report.currentRound().approvedLines().get(0).allocations()).allMatch(it -> it.amount().value().signum() == 0);
    }

    @Test
    void financialVersionTimeAndRoundSequenceAreIndependentGuards() {
        var line = line(1, "10", "0", "CNY", List.of(), null);
        var report = frozen(line);
        var before = report.state();
        fails("CONCURRENCY_CONFLICT", () -> report.reduce(1, List.of(reduction(1, "5", "0")), "finance", "ADJUST", "核减", ADJUSTED));
        fails("INVALID_EXPENSE_TIME", () -> report.reduce(2, List.of(reduction(1, "5", "0")), "finance", "ADJUST", "核减", SUBMITTED.minusSeconds(1)));
        fails("EXPENSE_ROUND_CONFLICT", () -> report.freeze(2, 1, "CNY", account(), Map.of(1, assessment(line, "1")), "alice", ADJUSTED));
        assertThat(report.state()).isEqualTo(before);
    }

    @Test
    void correctionAndResubmissionCannotAlterPreviousRoundOrItsAdjustments() {
        var line = line(1, "10", "1", "CNY", List.of(), null);
        var report = frozen(line);
        report.reduce(2, List.of(reduction(1, "5", "0.5")), "finance", "ADJUST", "核减", ADJUSTED);
        var firstRound = report.currentRound();
        var corrected = line(1, "7", "0", "USD", List.of(UUID.randomUUID()), null);
        report.revise(3, content(List.of(corrected), List.of()));
        report.freeze(4, 2, "CNY", account(), Map.of(1, assessment(corrected, "7.1")), "alice", ADJUSTED.plusSeconds(1));
        assertThat(report.rounds().get(0)).isEqualTo(firstRound);
        assertThat(report.currentRound().approvedGross()).isEqualTo(money("49.70", "CNY"));
        assertThat(report.currentRound().adjustments()).isEmpty();
        assertThat(report.version()).isEqualTo(5);
        assertThat(ExpenseReport.restore(report.state()).state()).isEqualTo(report.state());
    }

    @Test
    void collectionsRemainImmutableAcrossDraftFreezeAndRestoration() {
        var invoices = new ArrayList<>(List.of(UUID.randomUUID()));
        var line = line(1, "1", "0", "CNY", invoices, null);
        var lines = new ArrayList<>(List.of(line));
        var report = draft(content(lines, List.of()));
        invoices.clear(); lines.clear();
        assertThat(report.content().lines()).hasSize(1);
        assertThat(line.invoiceIds()).hasSize(1);
        assertThatThrownBy(() -> report.content().lines().clear()).isInstanceOf(UnsupportedOperationException.class);
        report.freeze(1, 1, "CNY", account(), Map.of(1, assessment(line, "1")), "alice", SUBMITTED);
        assertThatThrownBy(() -> report.currentRound().approvedLines().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> report.state().rounds().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void exchangeRatesRequireNamedSourcesDatesAndIdentityForSameCurrency() {
        fails("INVALID_EXCHANGE_RATE", () -> new ExpenseExchangeRate("CNY", "CNY", new BigDecimal("1.1"), "treasury", LocalDate.now()));
        fails("INVALID_EXCHANGE_RATE", () -> new ExpenseExchangeRate("USD", "CNY", BigDecimal.ZERO, "treasury", LocalDate.now()));
        fails("INVALID_EXCHANGE_RATE", () -> new ExpenseExchangeRate("USD", "CNY", BigDecimal.ONE, " ", LocalDate.now()));
        fails("INVALID_EXCHANGE_RATE", () -> new ExpenseExchangeRate("USD", "CNY", new BigDecimal("0.1234567890123"), "treasury", LocalDate.now()));
        fails("CURRENCY_MISMATCH", () -> assessment(line(1, "1", "0", "USD", List.of(), null), "7").exchangeRate().convert(money("1", "CNY")));
    }

    private static ExpenseReport frozen(ExpenseLine... lines) {
        var report = draft(content(List.of(lines), List.of()));
        var facts = new java.util.HashMap<Integer, ExpenseAssessment>();
        for (var line : lines) facts.put(line.lineNo(), assessment(line, "1"));
        report.freeze(1, 1, "CNY", account(), facts, "alice", SUBMITTED);
        return report;
    }
    private static ExpenseReport draft(ExpenseContent content) { return ExpenseReport.draft(UUID.randomUUID(), "demo", UUID.randomUUID(), "alice", content); }
    private static ExpenseContent content(List<ExpenseLine> lines, List<AdvanceOffset> offsets) { return new ExpenseContent(ENTITY, ExpenseContent.Type.DAILY, "费用申请", lines, offsets); }
    private static Money money(String value, String currency) { return new Money(new BigDecimal(value), currency); }
    private static CostAllocation allocation(String center, String value, String currency) { return new CostAllocation(center, null, money(value, currency)); }
    private static ExpenseReport.Reduction reduction(int no, String gross, String tax) { return new ExpenseReport.Reduction(no, money(gross, "CNY"), money(tax, "CNY")); }
    private static EmployeeAccountSnapshot account() { return new EmployeeAccountSnapshot(ENTITY, "alice", "employee-account-1", "***1234", "a".repeat(64), "accounts-v1"); }
    private static ExpenseLine line(int no, String gross, String tax, String currency, List<UUID> invoices, List<CostAllocation> allocations) {
        return new ExpenseLine(no, "TRAVEL", LocalDate.parse("2026-09-28"), null, "BEIJING", BigDecimal.ONE, ExpenseLine.Unit.ITEM,
                money(gross, currency), money(tax, currency), invoices, null,
                allocations == null ? List.of(allocation("engineering", gross, currency)) : allocations, "费用说明", null);
    }
    private static ExpenseAssessment assessment(ExpenseLine line, String rate) {
        return new ExpenseAssessment(new ExpenseExchangeRate(line.claimedGross().currency(), "CNY", new BigDecimal(rate), "treasury-test", LocalDate.parse("2026-09-28")),
                policy(line.claimedGross(), line.claimedGross(), ExpensePolicySnapshot.Decision.WITHIN_LIMIT), line.claimedTax());
    }
    private static ExpensePolicySnapshot policy(Money gross, Money limit, ExpensePolicySnapshot.Decision decision) {
        return new ExpensePolicySnapshot(UUID.randomUUID(), 1, gross, limit, decision, "tax-policy-test", "assessment-test");
    }
    private static void fails(String code, Runnable action) {
        assertThatExceptionOfType(DomainException.class).isThrownBy(action::run).satisfies(exception -> assertThat(exception.code()).isEqualTo(code));
    }
}
