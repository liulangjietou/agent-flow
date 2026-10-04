package io.agentflow.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentflow.calendar.CalendarRules;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.ExpenseRiskEvidence;
import io.agentflow.expense.InvoiceKey;
import io.agentflow.finance.Money;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 检查实际模型目录的去标识投影、来源依赖和字节界限；不以该测试冒充应用权限校验。
 * @author owlzhangfq@gmail.com
 */
class ExpenseRiskSourcesTest {
    private final JsonUtil json = new JsonUtil(new ObjectMapper().registerModule(new JavaTimeModule()));
    private final ExpenseRiskSources sources = new ExpenseRiskSources(json);
    private static final LocalDate DAY = LocalDate.of(2026, 10, 4);

    @Test void fourKindsRetainTheirEvidenceButNeverExposeLocalIdentityCalendarNotesOrInvoiceNumbers() {
        var catalog = catalog(); var input = sources.select(catalog, ids(catalog));
        assertThat(input.concerns()).extracting(ExpenseRiskInput.Concern::kind).contains(
                ExpenseRiskInput.Kind.SAME_DAY, ExpenseRiskInput.Kind.CROSS_DOCUMENT, ExpenseRiskInput.Kind.NON_WORKING_DAY, ExpenseRiskInput.Kind.CONSECUTIVE_INVOICES);
        String sent = json.write(input.sources());
        assertThat(sent).doesNotContain("20260000000000000001", "20260000000000000002", "内部日历负责人", "snapshotDigest", "applicationVersion", "financialVersion");
        for (var document : input.documents()) assertThat(sent).doesNotContain(document.reportId().toString(), document.applicationId().toString());
        assertThat(sent).doesNotContain(input.calendar().id().toString(), input.calendar().rulesDigest());
        assertThat(input.sources()).allSatisfy(source -> assertThat(source.reference().contentDigest()).isEqualTo(AssistConfiguration.digest(source.content())));
        assertThat(sent).contains("NON_WORKING", "DATE_OVERRIDE", "claimedGross");
    }

    @Test void missingScopeOrCoverageIsNeverSilentlySelectedAndUnknownSourcesAreDenied() {
        var catalog = catalog(); var ids = ids(catalog);
        for (var required : List.of("expense:document[1]", "expense:document[2]", ExpenseRiskInput.COVERAGE_SOURCE)) {
            error("INVALID_AGENT_INPUT", () -> sources.select(catalog, ids.stream().filter(id -> !id.equals(required)).toList()));
        }
        error("INVALID_AGENT_INPUT", () -> sources.select(catalog, ids.stream().filter(id -> !id.startsWith("expense:risk")).toList()));
        var forged = new ArrayList<>(ids); forged.add("expense:document[3]");
        error("FORBIDDEN", () -> sources.select(catalog, forged));
        var repeated = new ArrayList<>(ids); repeated.add(ids.get(0)); error("INVALID_AGENT_INPUT", () -> sources.select(catalog, repeated));
        var reversed = new ArrayList<>(ids); Collections.reverse(reversed);
        assertThat(sources.select(catalog, reversed)).isEqualTo(sources.select(catalog, ids));
    }

    @Test void partialInvoiceCoverageAndMissingCalendarRemainExplicitWithoutCreatingUnsupportedConcerns() {
        var first = line(1, 1, "TAXI", 2); var second = line(1, 2, "TAXI", 0);
        var doc = document(1, List.of(1, 2));
        var catalog = sources.available(List.of(doc), null, new ExpenseRiskEvidence.Input(List.of(first, second),
                List.of(ticket(first.id(), 1, "20260000000000000001")), null));
        assertThat(catalog.concerns()).extracting(ExpenseRiskInput.Concern::kind).containsExactly(ExpenseRiskInput.Kind.SAME_DAY);
        var coverage = json.map(catalog.sources().stream().filter(source -> source.reference().sourceId().equals(ExpenseRiskInput.COVERAGE_SOURCE)).findFirst().orElseThrow().content());
        assertThat(coverage).containsEntry("calendarProvided", false);
        assertThat((Map<?, ?>) coverage.get("invoiceCoverage")).extracting(value -> value.get("complete"), value -> value.get("verifiedReferences")).containsExactly(false, 1);
    }

    @Test void selectionWithoutObservationsDoesNotInviteTheModelToInventOne() {
        var line = line(1, 1, "TAXI", 0);
        var catalog = sources.available(List.of(document(1, List.of(1))), null, new ExpenseRiskEvidence.Input(List.of(line), List.of(), null));
        assertThat(catalog.concerns()).isEmpty();
        error("INVALID_AGENT_INPUT", () -> sources.select(catalog, ids(catalog)));
    }

    @Test void mismatchedFrozenLinesAndChangedCalendarRulesAreRejectedBeforePreparingSendableSources() {
        var first = line(1, 1, "TAXI", 0); var facts = new ExpenseRiskEvidence.Input(List.of(first), List.of(), null);
        error("INVALID_AGENT_INPUT", () -> sources.available(List.of(document(1, List.of(2))), null, facts));
        var rules = new CalendarRules("Asia/Shanghai", workingWeek(), List.of());
        var reference = new ExpenseRiskInput.CalendarReference(UUID.randomUUID(), 1, "a".repeat(64));
        error("INVALID_AGENT_INPUT", () -> sources.available(List.of(document(1, List.of(1))), reference, new ExpenseRiskEvidence.Input(List.of(first), List.of(), rules)));
        error("INVALID_AGENT_INPUT", () -> sources.available(List.of(document(1, List.of(1))), null, new ExpenseRiskEvidence.Input(List.of(first), List.of(), rules)));
    }

    @Test void calendarDigestBindsRulesUsingAStableWeekdayOrder() {
        var first = new java.util.LinkedHashMap<DayOfWeek, List<CalendarRules.Period>>();
        first.put(DayOfWeek.FRIDAY, List.of(new CalendarRules.Period("09:00", "17:00")));
        first.put(DayOfWeek.MONDAY, List.of(new CalendarRules.Period("10:00", "18:00")));
        var reverse = new java.util.LinkedHashMap<DayOfWeek, List<CalendarRules.Period>>();
        reverse.put(DayOfWeek.MONDAY, first.get(DayOfWeek.MONDAY)); reverse.put(DayOfWeek.FRIDAY, first.get(DayOfWeek.FRIDAY));
        String original = sources.calendarDigest(new CalendarRules("Asia/Shanghai", first, List.of()));
        assertThat(sources.calendarDigest(new CalendarRules("Asia/Shanghai", reverse, List.of()))).isEqualTo(original);
        assertThat(sources.calendarDigest(new CalendarRules("UTC", reverse, List.of()))).isNotEqualTo(original);
        reverse.put(DayOfWeek.MONDAY, List.of(new CalendarRules.Period("11:00", "18:00")));
        assertThat(sources.calendarDigest(new CalendarRules("Asia/Shanghai", reverse, List.of()))).isNotEqualTo(original);
    }

    @Test void byteBoundUsesActualUtf8EncodingAndDoesNotSilentlyTruncateExpenses() {
        var lines = java.util.stream.IntStream.rangeClosed(1, ExpenseRiskEvidence.MAX_SELECTED_LINES)
                .mapToObj(index -> line(1, index, "费".repeat(64), 0)).toList();
        var document = document(1, lines.stream().map(line -> line.id().lineNo()).toList());
        var catalog = sources.available(List.of(document), null, new ExpenseRiskEvidence.Input(lines, List.of(), null));
        assertThat(json.write(catalog.sources()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isGreaterThan(AssistInputService.MAX_INPUT_BYTES);
        error("INVALID_AGENT_INPUT", () -> sources.select(catalog, ids(catalog)));
    }

    private ExpenseRiskSources.Catalog catalog() {
        var first = line(1, 1, "TAXI", 1); var second = line(2, 1, "TAXI", 1);
        var rules = new CalendarRules("Asia/Shanghai", workingWeek(), List.of(new CalendarRules.DayOverride(DAY, List.of(), "内部日历负责人")));
        var reference = new ExpenseRiskInput.CalendarReference(UUID.randomUUID(), 3, sources.calendarDigest(rules));
        return sources.available(List.of(document(1, List.of(1)), document(2, List.of(1))), reference,
                new ExpenseRiskEvidence.Input(List.of(second, first), List.of(ticket(second.id(), 1, "20260000000000000002"), ticket(first.id(), 1, "20260000000000000001")), rules));
    }
    private static Map<DayOfWeek, List<CalendarRules.Period>> workingWeek() { return Map.of(DayOfWeek.MONDAY, List.of(new CalendarRules.Period("09:00", "18:00"))); }
    private static List<String> ids(ExpenseRiskSources.Catalog catalog) { return catalog.sources().stream().map(source -> source.reference().sourceId()).toList(); }
    private static ExpenseRiskInput.Document document(int ordinal, List<Integer> lines) { return new ExpenseRiskInput.Document(ordinal, UUID.randomUUID(), UUID.randomUUID(), 2, 1, 3, "c".repeat(64), lines); }
    private static ExpenseRiskEvidence.Line line(int document, int line, String category, int count) {
        return new ExpenseRiskEvidence.Line(new ExpenseRiskEvidence.LineId(document, line), category, DAY, new Money(new BigDecimal("10.00"), "CNY"), count);
    }
    private static ExpenseRiskEvidence.Invoice ticket(ExpenseRiskEvidence.LineId line, int ordinal, String number) { return new ExpenseRiskEvidence.Invoice(new ExpenseRiskEvidence.InvoiceId(line, ordinal), new InvoiceKey(InvoiceKey.Type.DIGITAL, null, number)); }
    private static void error(String code, Runnable action) { assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo(code)); }
}
