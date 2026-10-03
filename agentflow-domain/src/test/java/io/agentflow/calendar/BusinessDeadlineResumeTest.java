package io.agentflow.calendar;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 暂停接续以原日历的真实时间区间计量，覆盖跨日、休息、夏令时和已超时事实。
 * @author owlzhangfq@gmail.com
 */
class BusinessDeadlineResumeTest {
    @Test
    void preservesMillisecondsAcrossWeekendHolidayAndAnotherClosing() {
        var rules = weekdays(List.of(new CalendarRules.DayOverride(LocalDate.parse("2026-09-28"), List.of(), "假日")));
        assertThat(resume(rules, "2026-09-25T17:30:12.345Z", "2026-09-29T09:30:00Z", "2026-09-29T17:30:20.125Z"))
                .isEqualTo(Instant.parse("2026-09-30T09:30:07.780Z"));
    }

    @Test
    void pausingAtLunchDoesNotConsumeTheUnfinishedFractionOfAMinute() {
        var rules = weekdays(List.of());
        assertThat(resume(rules, "2026-09-23T12:30:00Z", "2026-09-23T13:00:30.250Z", "2026-09-23T13:15:00.125Z"))
                .isEqualTo(Instant.parse("2026-09-23T13:15:30.375Z"));
    }

    @Test
    void repeatedClockWindowsPreserveTheirRealWorkWithoutCountingTheGap() {
        var rules = new CalendarRules("America/New_York", Map.of(DayOfWeek.SUNDAY,
                List.of(new CalendarRules.Period("01:15", "01:45"))), List.of());
        assertThat(resume(rules, "2024-11-03T05:30:00.125Z", "2024-11-03T06:45:00Z", "2024-11-10T06:15:00Z"))
                .isEqualTo(Instant.parse("2024-11-17T06:29:59.875Z"));
    }

    @Test
    void skippedSpringHourDoesNotBecomeAdditionalAllowanceOnResume() {
        var rules = new CalendarRules("America/New_York", Map.of(DayOfWeek.SUNDAY,
                List.of(new CalendarRules.Period("02:00", "04:00"))), List.of());
        assertThat(resume(rules, "2024-03-10T06:59:00Z", "2024-03-10T08:00:00Z", "2024-03-17T06:30:00Z"))
                .isEqualTo(Instant.parse("2024-03-17T07:30:00Z"));
    }

    @Test
    void repeatedPausesContinueOnlyTheRemainderAndOverdueTasksStayOverdue() {
        var rules = weekdays(List.of());
        var first = resume(rules, "2026-09-23T09:15:00Z", "2026-09-23T10:00:00Z", "2026-09-24T09:00:00Z");
        assertThat(first).isEqualTo(Instant.parse("2026-09-24T09:45:00Z"));
        assertThat(BusinessDeadline.resume(rules, Instant.parse("2026-09-24T09:30:00Z"), first,
                Instant.parse("2026-09-25T09:00:00Z"))).isEqualTo(Instant.parse("2026-09-25T09:15:00Z"));
        for (String pause : List.of("2026-09-23T10:00:00Z", "2026-09-23T10:00:00.001Z")) {
            assertThat(resume(rules, pause, "2026-09-23T10:00:00Z", "2026-09-25T09:00:00Z"))
                    .isEqualTo(Instant.parse("2026-09-23T10:00:00Z"));
        }
    }

    @Test
    void rejectsClockRegressionAndUnavailableFutureWorkInsteadOfResettingTheDeadline() {
        var rules = weekdays(List.of());
        assertThatThrownBy(() -> resume(rules, "2026-09-23T09:30:00Z", "2026-09-23T10:00:00Z", "2026-09-23T09:29:59Z"))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("INVALID_DEADLINE_RESUME");
        var singleOpening = new CalendarRules("UTC", Map.of(), List.of(new CalendarRules.DayOverride(
                LocalDate.parse("2026-09-23"), List.of(new CalendarRules.Period("09:00", "10:00")), "单次开放")));
        assertThatThrownBy(() -> resume(singleOpening, "2026-09-23T09:30:00Z", "2026-09-23T10:00:00Z", "2026-09-24T09:00:00Z"))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("CALENDAR_HORIZON_EXCEEDED");
    }

    private Instant resume(CalendarRules rules, String pause, String due, String resume) {
        return BusinessDeadline.resume(rules, Instant.parse(pause), Instant.parse(due), Instant.parse(resume));
    }
    private CalendarRules weekdays(List<CalendarRules.DayOverride> overrides) {
        var week = new EnumMap<DayOfWeek, List<CalendarRules.Period>>(DayOfWeek.class);
        for (var day : List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)) {
            week.put(day, List.of(new CalendarRules.Period("09:00", "12:00"), new CalendarRules.Period("13:00", "18:00")));
        }
        return new CalendarRules("UTC", week, overrides);
    }
}
