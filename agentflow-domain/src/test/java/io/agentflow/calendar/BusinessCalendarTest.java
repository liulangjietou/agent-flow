package io.agentflow.calendar;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 日历聚合只生成新修订，拒绝冲突、无工作规则和不确定的时间窗口。
 * @author owlzhangfq@gmail.com
 */
class BusinessCalendarTest {
    @Test
    void revisionKeepsOldRulesAndMetadataImmutable() {
        var oldRules = rules("UTC");
        var calendar = BusinessCalendar.create("demo", "office", "办公日历", oldRules, "admin", Instant.parse("2026-09-23T00:00:00.123456789Z"));
        var updated = calendar.revise("调整日历", rules("Asia/Shanghai"), 1, "another-admin", Instant.now());
        assertThat(calendar.revision()).isEqualTo(1); assertThat(calendar.rules()).isEqualTo(oldRules);
        assertThat(updated.revision()).isEqualTo(2); assertThat(updated.id()).isEqualTo(calendar.id());
        assertThat(updated.key()).isEqualTo("office"); assertThat(updated.updatedBy()).isEqualTo("another-admin");
        assertThat(calendar.updatedAt()).isEqualTo(Instant.parse("2026-09-23T00:00:00.123456Z"));
        assertThatThrownBy(() -> updated.revise("覆盖", oldRules, 1, "admin", Instant.now())).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("CONCURRENCY_CONFLICT");
    }

    @ParameterizedTest
    @CsvSource({"09:00,09:00", "18:00,09:00", "24:00,24:00", "9:00,12:00", "09:61,12:00"})
    void rejectsInvalidOrOvernightPeriods(String start, String end) { assertThatThrownBy(() -> new CalendarRules.Period(start, end)).isInstanceOf(DomainException.class); }

    @Test
    void rejectsOverlapsDuplicateOverridesEmptyRulesAndUnknownTimeZones() {
        var periods = List.of(new CalendarRules.Period("09:00", "12:00"), new CalendarRules.Period("11:00", "14:00"));
        assertThatThrownBy(() -> new CalendarRules("UTC", Map.of(DayOfWeek.MONDAY, periods), List.of())).isInstanceOf(DomainException.class);
        var holiday = new CalendarRules.DayOverride(LocalDate.parse("2026-09-23"), List.of(), "休息");
        assertThatThrownBy(() -> new CalendarRules("UTC", rules("UTC").weeklyHours(), List.of(holiday, holiday))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new CalendarRules("UTC", Map.of(), List.of())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> rules("Unknown/Zone")).isInstanceOf(DomainException.class);
    }

    @Test
    void inputCollectionsCannotMutatePublishedRules() {
        var periods = new ArrayList<>(List.of(new CalendarRules.Period("09:00", "12:00")));
        var week = new EnumMap<DayOfWeek, List<CalendarRules.Period>>(DayOfWeek.class); week.put(DayOfWeek.MONDAY, periods);
        var rules = new CalendarRules("UTC", week, new ArrayList<>()); periods.clear(); week.clear();
        assertThat(rules.periodsOn(LocalDate.parse("2026-09-21"))).hasSize(1);
        assertThatThrownBy(() -> rules.weeklyHours().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private CalendarRules rules(String zone) { return new CalendarRules(zone, Map.of(DayOfWeek.MONDAY, List.of(new CalendarRules.Period("09:00", "18:00"))), List.of()); }
}
