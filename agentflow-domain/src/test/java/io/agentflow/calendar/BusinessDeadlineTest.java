package io.agentflow.calendar;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 覆盖午休、日期例外和时区跳变，避免把日历分钟当作固定工作分钟。
 * @author owlzhangfq@gmail.com
 */
class BusinessDeadlineTest {
    @Test
    void crossesLunchWeekendHolidayAndMakeupDayUsingExplicitRules() {
        var rules = rules("Asia/Shanghai", weekdays(), List.of(
                new CalendarRules.DayOverride(LocalDate.parse("2026-09-28"), List.of(), "明确休息日"),
                new CalendarRules.DayOverride(LocalDate.parse("2026-09-26"), List.of(period("09:00", "10:00")), "周六调休")));
        assertThat(calculate(rules, "2026-09-25T17:30", 120).dueAt()).isEqualTo(Instant.parse("2026-09-29T01:30:00Z"));
        assertThat(calculate(rules, "2026-09-23T11:30", 90).dueAt()).isEqualTo(Instant.parse("2026-09-23T06:00:00Z"));
    }

    @Test
    void startsAtNextOpeningAndFinishesExactlyAtClosingWithoutRoundingSeconds() {
        var rules = rules("UTC", weekdays(), List.of());
        assertThat(calculate(rules, "2026-09-23T08:00", 180).dueAt()).isEqualTo(Instant.parse("2026-09-23T12:00:00Z"));
        assertThat(calculate(rules, "2026-09-23T11:59:30", 1).dueAt()).isEqualTo(Instant.parse("2026-09-23T13:00:30Z"));
        assertThat(calculate(rules, "2026-09-23T18:00", 1).dueAt()).isEqualTo(Instant.parse("2026-09-24T09:01:00Z"));
    }

    @Test
    void skippedSpringClockHourDoesNotConsumeWorkingTime() {
        var rules = rules("America/New_York", Map.of(DayOfWeek.SUNDAY, List.of(period("02:00", "04:00"))), List.of());
        assertThat(calculate(rules, "2024-03-10T01:30", 60).dueAt()).isEqualTo(Instant.parse("2024-03-10T08:00:00Z"));
        assertThatThrownBy(() -> calculate(rules, "2024-03-10T02:30", 1)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("CALENDAR_NONEXISTENT_START");
    }

    @Test
    void repeatedWindowIsCountedTwiceWithoutIncludingTheGapBetweenItsOccurrences() {
        var rules = rules("America/New_York", Map.of(DayOfWeek.SUNDAY, List.of(period("01:15", "01:45"))), List.of());
        assertThat(calculate(rules, "2024-11-03T00:00", 60).dueAt()).isEqualTo(Instant.parse("2024-11-03T06:45:00Z"));
        assertThat(calculate(rules, "2024-11-03T00:00", 31).dueAt()).isEqualTo(Instant.parse("2024-11-03T06:16:00Z"));
        assertThatThrownBy(() -> calculate(rules, "2024-11-03T01:30", 1)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("CALENDAR_AMBIGUOUS_START");
        assertThat(BusinessDeadline.calculate(rules, LocalDateTime.parse("2024-11-03T01:30"), CalendarRules.OverlapChoice.EARLIER, 1).dueAt())
                .isEqualTo(Instant.parse("2024-11-03T05:31:00Z"));
        assertThat(BusinessDeadline.calculate(rules, LocalDateTime.parse("2024-11-03T01:30"), CalendarRules.OverlapChoice.LATER, 1).dueAt())
                .isEqualTo(Instant.parse("2024-11-03T06:31:00Z"));
    }

    @Test
    void realTaskCreationInstantPreservesWhichRepeatedClockHourOccurred() {
        var rules = rules("America/New_York", Map.of(DayOfWeek.SUNDAY, List.of(period("01:15", "01:45"))), List.of());
        assertThat(BusinessDeadline.calculate(rules, Instant.parse("2024-11-03T05:30:00Z"), 1).dueAt())
                .isEqualTo(Instant.parse("2024-11-03T05:31:00Z"));
        assertThat(BusinessDeadline.calculate(rules, Instant.parse("2024-11-03T06:30:00Z"), 1).dueAt())
                .isEqualTo(Instant.parse("2024-11-03T06:31:00Z"));
    }

    @Test
    void skippedWholeLocalDateDoesNotCreateImaginaryWork() {
        var week = new EnumMap<DayOfWeek, List<CalendarRules.Period>>(DayOfWeek.class);
        for (var day : DayOfWeek.values()) week.put(day, List.of(period("09:00", "17:00")));
        var rules = rules("Pacific/Apia", week, List.of());
        assertThat(calculate(rules, "2011-12-29T16:50", 20).dueAt()).isEqualTo(Instant.parse("2011-12-30T19:10:00Z"));
    }

    @Test
    void boundedSearchFailsClearlyInsteadOfLoopingForeverWhenOnlyPastExceptionsAreOpen() {
        var rules = rules("UTC", Map.of(), List.of(new CalendarRules.DayOverride(LocalDate.parse("2020-01-01"), List.of(period("09:00", "10:00")), "旧例外")));
        assertThatThrownBy(() -> calculate(rules, "2026-09-23T09:00", 1)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("CALENDAR_HORIZON_EXCEEDED");
        assertThatThrownBy(() -> calculate(rules, "2026-09-23T09:00", 0)).isInstanceOf(DomainException.class);
    }

    @Test
    void overridesReplaceTheWholeDayAndAllDayPeriodsAcceptMidnightEnd() {
        var rules = rules("UTC", weekdays(), List.of(new CalendarRules.DayOverride(LocalDate.parse("2026-09-23"), List.of(period("00:00", "24:00")), "全天")));
        assertThat(calculate(rules, "2026-09-23T00:00", 1440).dueAt()).isEqualTo(Instant.parse("2026-09-24T00:00:00Z"));
    }

    @Test
    void deadlineCannotEscapeTheFourDigitYearContractAtCalendarBoundary() {
        var rules = rules("UTC", Map.of(), List.of(new CalendarRules.DayOverride(LocalDate.parse("9999-12-31"), List.of(period("00:00", "24:00")), "边界")));
        assertThatThrownBy(() -> calculate(rules, "9998-12-31T00:00", 1440)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("CALENDAR_HORIZON_EXCEEDED");
    }

    private BusinessDeadline.Result calculate(CalendarRules rules, String local, int minutes) { return BusinessDeadline.calculate(rules, LocalDateTime.parse(local), null, minutes); }
    private CalendarRules rules(String zone, Map<DayOfWeek, List<CalendarRules.Period>> week, List<CalendarRules.DayOverride> overrides) { return new CalendarRules(zone, week, overrides); }
    private CalendarRules.Period period(String start, String end) { return new CalendarRules.Period(start, end); }
    private Map<DayOfWeek, List<CalendarRules.Period>> weekdays() {
        var week = new EnumMap<DayOfWeek, List<CalendarRules.Period>>(DayOfWeek.class);
        for (var day : List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)) week.put(day, List.of(period("09:00", "12:00"), period("13:00", "18:00")));
        return week;
    }
}
