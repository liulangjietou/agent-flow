package io.agentflow.calendar;

import io.agentflow.common.DomainException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 不可变工作日历规则；日期例外覆盖整天的周规则，空时段明确表示休息。
 * @author owlzhangfq@gmail.com
 */
public record CalendarRules(String zoneId, Map<DayOfWeek, List<Period>> weeklyHours, List<DayOverride> overrides) {
    private static final int MAX_PERIODS_PER_DAY = 8;
    private static final int MAX_OVERRIDES = 500;
    private static final int MINUTES_PER_DAY = 1440;
    private static final Set<String> ZONE_IDS = ZoneId.getAvailableZoneIds();

    /** 在创建规则时统一校验并复制输入，后续计算不会被调用方修改。 */
    public CalendarRules {
        if (zoneId == null || !ZONE_IDS.contains(zoneId)) throw invalid("An IANA time zone is required");
        if (weeklyHours == null || weeklyHours.size() > DayOfWeek.values().length || overrides == null || overrides.size() > MAX_OVERRIDES) {
            throw invalid("Calendar rules exceed the supported collection bounds");
        }
        var week = new EnumMap<DayOfWeek, List<Period>>(DayOfWeek.class);
        weeklyHours.forEach((day, periods) -> {
            if (day == null) throw invalid("Weekday is required");
            week.put(day, validatePeriods(periods));
        });
        weeklyHours = Map.copyOf(week);
        if (overrides.stream().anyMatch(java.util.Objects::isNull)
                || overrides.stream().map(DayOverride::date).distinct().count() != overrides.size()) throw invalid("Override dates must be unique");
        overrides = overrides.stream().sorted(Comparator.comparing(DayOverride::date)).toList();
        if (week.values().stream().allMatch(List::isEmpty) && overrides.stream().allMatch(day -> day.periods().isEmpty())) {
            throw invalid("At least one working period is required");
        }
    }

    /** 例外覆盖整天；未配置的周日期为休息日。 */
    public List<Period> periodsOn(LocalDate date) {
        return overrides.stream().filter(day -> day.date().equals(date)).findFirst()
                .map(DayOverride::periods).orElseGet(() -> weeklyHours.getOrDefault(date.getDayOfWeek(), List.of()));
    }

    /** 本地开始时刻不存在时拒绝；重复时刻必须由调用者明确选择第一次或第二次。 */
    public Instant resolveStart(LocalDateTime local, OverlapChoice overlapChoice) {
        var offsets = ZoneId.of(zoneId).getRules().getValidOffsets(local);
        if (offsets.isEmpty()) throw new DomainException("CALENDAR_NONEXISTENT_START", "The local start time does not exist in this time zone");
        if (offsets.size() > 1 && overlapChoice == null) throw new DomainException("CALENDAR_AMBIGUOUS_START", "Choose the earlier or later occurrence of the local start time");
        var candidates = offsets.stream().map(local::toInstant).sorted().toList();
        return overlapChoice == OverlapChoice.LATER ? candidates.get(candidates.size() - 1) : candidates.get(0);
    }

    /**
     * 将本地工作窗口与每个固定 UTC 偏移区段求交；跳过不存在的钟点，重复钟点分别计算。
     * 不能只转换两个端点，否则夏令时回拨会把窗口外的钟点错误算成工作时间。
     */
    public List<InstantPeriod> instantPeriodsOn(LocalDate date) {
        var periods = periodsOn(date);
        if (periods.isEmpty()) return List.of();
        ZoneId zone = ZoneId.of(zoneId);
        Instant segmentStart = date.atStartOfDay(zone).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
        var result = new ArrayList<InstantPeriod>();
        while (segmentStart.isBefore(dayEnd)) {
            var offset = zone.getRules().getOffset(segmentStart);
            var transition = zone.getRules().nextTransition(segmentStart);
            Instant segmentEnd = transition != null && transition.getInstant().isBefore(dayEnd) ? transition.getInstant() : dayEnd;
            for (Period period : periods) {
                Instant start = date.atStartOfDay().plusMinutes(period.startMinute()).toInstant(offset);
                Instant end = date.atStartOfDay().plusMinutes(period.endMinute()).toInstant(offset);
                if (start.isBefore(segmentStart)) start = segmentStart;
                if (end.isAfter(segmentEnd)) end = segmentEnd;
                if (start.isBefore(end)) result.add(new InstantPeriod(start, end));
            }
            segmentStart = segmentEnd;
        }
        return List.copyOf(result);
    }

    private static List<Period> validatePeriods(List<Period> periods) {
        if (periods == null || periods.size() > MAX_PERIODS_PER_DAY || periods.stream().anyMatch(java.util.Objects::isNull)) throw invalid("Invalid daily periods");
        var sorted = periods.stream().sorted(Comparator.comparingInt(Period::startMinute)).toList();
        for (int index = 1; index < sorted.size(); index++) {
            if (sorted.get(index).startMinute() < sorted.get(index - 1).endMinute()) throw invalid("Working periods must not overlap");
        }
        return sorted;
    }

    private static int minute(String value, boolean end) {
        if (end && "24:00".equals(value)) return MINUTES_PER_DAY;
        if (value == null || !value.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]")) throw invalid("Time must use HH:mm; 24:00 is allowed only as an end");
        return Integer.parseInt(value.substring(0, 2)) * 60 + Integer.parseInt(value.substring(3));
    }

    private static DomainException invalid(String message) { return new DomainException("INVALID_CALENDAR_RULES", message); }

    /**
     * 同一天内的半开时间窗口；跨午夜须拆成两个日期或周日期配置。
     * @author owlzhangfq@gmail.com
     */
    public record Period(String start, String end) {
        public Period { if (minute(start, false) >= minute(end, true)) throw invalid("A working period must end after it starts"); }
        int startMinute() { return minute(start, false); }
        int endMinute() { return minute(end, true); }
    }

    /**
     * 特定日期的完整替代规则，包含节假日休息和调休工作日。
     * @author owlzhangfq@gmail.com
     */
    public record DayOverride(LocalDate date, List<Period> periods, String note) {
        public DayOverride {
            if (date == null || date.getYear() < 1 || date.getYear() > 9999 || note != null && note.length() > 200) throw invalid("Invalid override date or note");
            periods = validatePeriods(periods);
            note = note == null ? "" : note.strip();
        }
    }

    /**
     * 已解析为真实时间轴的工作区间，仅用于领域计算。
     * @author owlzhangfq@gmail.com
     */
    public record InstantPeriod(Instant start, Instant end) { }

    /**
     * 本地钟点重复时的明确选择。
     * @author owlzhangfq@gmail.com
     */
    public enum OverlapChoice { EARLIER, LATER }
}
