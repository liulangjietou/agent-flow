package io.agentflow.expense;

import io.agentflow.calendar.CalendarRules;
import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 对照数学相邻、日期覆盖和金额范围等真实边界，不用模型结论替代业务事实。
 * @author owlzhangfq@gmail.com
 */
class ExpenseRiskEvidenceTest {
    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 2);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 10, 4);
    private static final CalendarRules.Period WORKING = new CalendarRules.Period("09:00", "18:00");

    @Test void dayGroupsUseOriginalCurrencyAndKeepSingleAndCrossDocumentEvidenceDistinct() {
        var first = line(1, 1, FRIDAY, "TAXI", "12.34", "CNY", 0);
        var second = line(1, 2, FRIDAY, "TAXI", "5.66", "CNY", 0);
        var third = line(2, 1, FRIDAY, "TAXI", "2.00", "CNY", 0);
        var otherCurrency = line(2, 2, FRIDAY, "TAXI", "2.00", "USD", 0);
        var otherCategory = line(2, 3, FRIDAY, "HOTEL", "2.00", "CNY", 0);
        var otherDate = line(2, 4, SUNDAY, "TAXI", "2.00", "CNY", 0);
        var result = derive(List.of(third, otherDate, second, otherCurrency, otherCategory, first), List.of(), null);
        assertThat(result.sameDay()).hasSize(1); var group = result.sameDay().get(0);
        assertThat(group.claimedTotal()).isEqualByComparingTo("20.00"); assertThat(group.documentCount()).isEqualTo(2);
        assertThat(group.lines()).containsExactly(first.id(), second.id(), third.id()); assertThat(group.currency()).isEqualTo("CNY");
    }

    @Test void observedSumMayExceedSingleFinancialAmountLimitWithoutOverflowOrSilentTruncation() {
        var result = derive(List.of(line(1, 1, FRIDAY, "TAXI", Money.MAX_VALUE.toPlainString(), "CNY", 0),
                line(2, 1, FRIDAY, "TAXI", Money.MAX_VALUE.toPlainString(), "CNY", 0)), List.of(), null);
        assertThat(result.sameDay().get(0).claimedTotal()).isEqualByComparingTo("1999999999999999.98");
    }

    @Test void selectedCrossDocumentFactsRetainActualDateExtentAndNeverInventAConfiguredWindow() {
        var first = line(1, 1, FRIDAY, "TAXI", "12.34", "CNY", 0);
        var second = line(2, 1, FRIDAY.plusDays(40), "TAXI", "7.66", "CNY", 0);
        var third = line(2, 2, FRIDAY, "TAXI", "1.00", "USD", 0);
        var result = derive(List.of(second, third, first), List.of(), null);
        assertThat(result.sameDay()).isEmpty(); assertThat(result.crossDocument()).hasSize(1);
        var group = result.crossDocument().get(0);
        assertThat(group.claimedTotal()).isEqualByComparingTo("20.00"); assertThat(group.documentCount()).isEqualTo(2);
        assertThat(group.firstIncurredOn()).isEqualTo(FRIDAY); assertThat(group.lastIncurredOn()).isEqualTo(FRIDAY.plusDays(40));
        assertThat(group.lines()).containsExactly(first.id(), second.id()); assertThat(group.currency()).isEqualTo("CNY");
    }

    @Test void severalLinesInOneDocumentNeverBecomeCrossDocumentEvidence() {
        var result = derive(List.of(line(1, 1, FRIDAY, "TAXI", "1.00", "CNY", 0),
                line(1, 2, SUNDAY, "TAXI", "1.00", "CNY", 0)), List.of(), null);
        assertThat(result.crossDocument()).isEmpty();
    }

    @Test void missingCalendarDoesNotTurnSundayIntoAnAssumedHoliday() {
        var result = derive(List.of(line(1, 1, SUNDAY, "TAXI", "10", "CNY", 0)), List.of(), null);
        assertThat(result.workdays().get(0).status()).isEqualTo(ExpenseRiskEvidence.DayStatus.NOT_PROVIDED);
        assertThat(result.workdays().get(0).basis()).isEqualTo(ExpenseRiskEvidence.DayBasis.NOT_PROVIDED);
    }

    @Test void explicitRestAndMakeupWorkOverridesWinOverTheWeekdaySchedule() {
        var calendar = new CalendarRules("Asia/Shanghai", Map.of(DayOfWeek.FRIDAY, List.of(WORKING)),
                List.of(new CalendarRules.DayOverride(FRIDAY, List.of(), "明确休息"), new CalendarRules.DayOverride(SUNDAY, List.of(WORKING), "调休工作")));
        var result = derive(List.of(line(1, 1, FRIDAY, "TAXI", "10", "CNY", 0), line(1, 2, SUNDAY, "TAXI", "10", "CNY", 0)), List.of(), calendar);
        assertThat(result.workdays()).extracting(ExpenseRiskEvidence.Workday::status).containsExactly(ExpenseRiskEvidence.DayStatus.NON_WORKING, ExpenseRiskEvidence.DayStatus.WORKING);
        assertThat(result.workdays()).allMatch(day -> day.basis() == ExpenseRiskEvidence.DayBasis.DATE_OVERRIDE);
        var weekly = derive(List.of(line(1, 1, FRIDAY.plusWeeks(1), "TAXI", "10", "CNY", 0)), List.of(), calendar);
        assertThat(weekly.workdays().get(0).basis()).isEqualTo(ExpenseRiskEvidence.DayBasis.WEEKLY);
        assertThat(weekly.workdays().get(0).status()).isEqualTo(ExpenseRiskEvidence.DayStatus.WORKING);
    }

    @Test void twentyDigitSequencesUseExactArithmeticBeyondLongAndNeverReturnFullNumbers() {
        var line = line(1, 1, FRIDAY, "TAXI", "10", "CNY", 3);
        var tickets = List.of(digital(line.id(), 3, "99999999999999999999"), digital(line.id(), 1, "99999999999999999997"), digital(line.id(), 2, "99999999999999999998"));
        var result = derive(List.of(line), tickets, null);
        assertThat(result.invoiceSequences()).hasSize(1); var sequence = result.invoiceSequences().get(0);
        assertThat(sequence.numberWidth()).isEqualTo(20); assertThat(sequence.numbers()).hasSize(3);
        assertThat(sequence.numbers()).extracting(group -> group.invoices().get(0).ordinal()).containsExactly(1, 2, 3);
        assertThat(result.toString()).doesNotContain("99999999999999999997", "99999999999999999998", "99999999999999999999");
    }

    @Test void typeCodeWidthAndLeadingZerosPreventFalseAdjacencyAcrossDifferentIdentities() {
        var line = line(1, 1, FRIDAY, "TAXI", "10", "CNY", 6);
        var result = derive(List.of(line), List.of(traditional(line.id(), 1, "001", "009"), traditional(line.id(), 2, "001", "010"),
                traditional(line.id(), 3, "002", "011"), traditional(line.id(), 4, "001", "11"),
                traditional(line.id(), 5, "001", "00000000000000000012"), digital(line.id(), 6, "00000000000000000013")), null);
        assertThat(result.invoiceSequences()).hasSize(1);
        assertThat(result.invoiceSequences().get(0).numbers()).extracting(group -> group.invoices().get(0).ordinal()).containsExactly(1, 2);
        assertThat(result.toString()).doesNotContain("code=", "number=", "00000000000000000013");
    }

    @Test void duplicateIdentityReferencesDoNotCreateConsecutiveLengthAndNumericGapsSplitSequences() {
        var first = line(1, 1, FRIDAY, "TAXI", "10", "CNY", 3); var second = line(2, 1, FRIDAY, "TAXI", "10", "CNY", 3);
        var tickets = List.of(traditional(first.id(), 1, "001", "01"), traditional(second.id(), 1, "001", "01"),
                traditional(first.id(), 2, "001", "02"), traditional(second.id(), 2, "001", "05"),
                traditional(first.id(), 3, "001", "06"), traditional(second.id(), 3, "001", "09"));
        var result = derive(List.of(first, second), tickets, null);
        assertThat(result.invoiceSequences()).hasSize(2);
        assertThat(result.invoiceSequences()).allMatch(sequence -> sequence.numbers().size() == 2);
        assertThat(result.invoiceSequences().get(0).numbers().get(0).invoices()).hasSize(2);
        assertThat(result.invoiceCoverage().verifiedReferences()).isEqualTo(6); assertThat(result.invoiceCoverage().distinctIdentities()).isEqualTo(5);
        var duplicateOnly = derive(List.of(line(1, 1, FRIDAY, "TAXI", "10", "CNY", 2)),
                List.of(traditional(first.id(), 1, "001", "01"), traditional(first.id(), 2, "001", "01")), null);
        assertThat(duplicateOnly.invoiceSequences()).isEmpty();
    }

    @Test void incompleteInvoiceFactsStayVisibleEvenWhenNoSequenceIsFound() {
        var line = line(1, 1, FRIDAY, "TAXI", "10", "CNY", 3);
        var result = derive(List.of(line), List.of(digital(line.id(), 2, "20260000000000000001")), null);
        assertThat(result.invoiceSequences()).isEmpty(); assertThat(result.invoiceCoverage().complete()).isFalse();
        assertThat(result.invoiceCoverage().selectedCount()).isEqualTo(3); assertThat(result.invoiceCoverage().verifiedReferences()).isEqualTo(1);
        assertThat(derive(List.of(line(1, 1, FRIDAY, "TAXI", "10", "CNY", 0)), List.of(), null).invoiceCoverage().complete()).isTrue();
    }

    @Test void repeatedLinesUnknownParentsDuplicateTicketPositionsAndOverflowPositionsFailAtInputBoundary() {
        var line = line(1, 1, FRIDAY, "TAXI", "10", "CNY", 1); var ticket = digital(line.id(), 1, "20260000000000000001");
        invalid(() -> new ExpenseRiskEvidence.Input(List.of(line, line), List.of(), null));
        invalid(() -> new ExpenseRiskEvidence.Input(List.of(line), List.of(ticket, ticket), null));
        invalid(() -> new ExpenseRiskEvidence.Input(List.of(line), List.of(digital(new ExpenseRiskEvidence.LineId(2, 1), 1, "20260000000000000001")), null));
        invalid(() -> new ExpenseRiskEvidence.Input(List.of(line), List.of(digital(line.id(), 2, "20260000000000000001")), null));
        invalid(() -> new ExpenseRiskEvidence.Input(List.of(), List.of(), null));
        invalid(() -> new ExpenseRiskEvidence.Input(Collections.nCopies(ExpenseRiskEvidence.MAX_SELECTED_LINES + 1, line), List.of(), null));
        invalid(() -> new ExpenseRiskEvidence.Input(List.of(line), Collections.nCopies(ExpenseRiskEvidence.MAX_SELECTED_INVOICES + 1, ticket), null));
        invalid(() -> new ExpenseRiskEvidence.LineId(ExpenseRiskEvidence.MAX_DOCUMENTS + 1, 1));
        invalid(() -> new ExpenseRiskEvidence.InvoiceId(line.id(), ExpenseLine.MAX_INVOICES + 1));
    }

    @Test void inputAndOutputsRemainImmutableAndOrderingDoesNotDependOnQueryOrder() {
        var first = line(1, 1, FRIDAY, "TAXI", "10", "CNY", 1); var second = line(2, 1, FRIDAY, "TAXI", "10", "CNY", 1);
        var lines = new ArrayList<>(List.of(first, second)); var tickets = new ArrayList<>(List.of(traditional(first.id(), 1, "001", "01"), traditional(second.id(), 1, "001", "02")));
        var input = new ExpenseRiskEvidence.Input(lines, tickets, null); var result = ExpenseRiskEvidence.derive(input);
        lines.clear(); tickets.clear(); assertThat(input.lines()).hasSize(2); assertThat(input.invoices()).hasSize(2);
        assertThat(derive(List.of(second, first), List.of(input.invoices().get(1), input.invoices().get(0)), null)).isEqualTo(result);
        assertThatThrownBy(() -> result.sameDay().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.invoiceSequences().get(0).numbers().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.invoiceSequences().get(0).numbers().get(0).invoices().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private static ExpenseRiskEvidence.Evidence derive(List<ExpenseRiskEvidence.Line> lines, List<ExpenseRiskEvidence.Invoice> tickets, CalendarRules calendar) {
        return ExpenseRiskEvidence.derive(new ExpenseRiskEvidence.Input(lines, tickets, calendar));
    }
    private static ExpenseRiskEvidence.Line line(int document, int number, LocalDate date, String category, String amount, String currency, int invoices) {
        return new ExpenseRiskEvidence.Line(new ExpenseRiskEvidence.LineId(document, number), category, date, new Money(new BigDecimal(amount), currency), invoices);
    }
    private static ExpenseRiskEvidence.Invoice digital(ExpenseRiskEvidence.LineId line, int ordinal, String number) {
        return new ExpenseRiskEvidence.Invoice(new ExpenseRiskEvidence.InvoiceId(line, ordinal), new InvoiceKey(InvoiceKey.Type.DIGITAL, null, number));
    }
    private static ExpenseRiskEvidence.Invoice traditional(ExpenseRiskEvidence.LineId line, int ordinal, String code, String number) {
        return new ExpenseRiskEvidence.Invoice(new ExpenseRiskEvidence.InvoiceId(line, ordinal), new InvoiceKey(InvoiceKey.Type.TRADITIONAL, code, number));
    }
    private static void invalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("INVALID_EXPENSE_RISK_FACTS"));
    }
}
