package io.agentflow.calendar;

import io.agentflow.common.DomainException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 沿真实开放区间累加工作分钟，休息、假日、缺失钟点均不消耗 SLA 时长。
 * @author owlzhangfq@gmail.com
 */
public final class BusinessDeadline {
    public static final int MAX_WORKING_MINUTES = 527040;
    private static final int MAX_LOOKAHEAD_DAYS = 3660;
    private static final Instant MAX_RESULT_EXCLUSIVE = Instant.parse("+10000-01-01T00:00:00Z");
    private BusinessDeadline() { }

    /** 试算以明确日历规则和本地开始时间为输入，不读取系统时钟或修改审批状态。 */
    public static Result calculate(CalendarRules rules, LocalDateTime startLocal, CalendarRules.OverlapChoice overlapChoice, int workingMinutes) {
        if (startLocal == null || startLocal.getYear() < 1 || startLocal.getYear() > 9998 || workingMinutes < 1 || workingMinutes > MAX_WORKING_MINUTES) {
            throw new DomainException("INVALID_CALENDAR_CALCULATION", "Start year must be 1..9998 and working minutes 1..527040");
        }
        Instant start = rules.resolveStart(startLocal, overlapChoice);
        return calculateFrom(rules, start, workingMinutes);
    }

    /** 引擎已有真实创建时刻，直接沿时间轴计算，避免夏令时重复钟点被重新解释。 */
    public static Result calculate(CalendarRules rules, Instant start, int workingMinutes) {
        if (start == null || workingMinutes < 1 || workingMinutes > MAX_WORKING_MINUTES) {
            throw new DomainException("INVALID_CALENDAR_CALCULATION", "A start instant and supported working minutes are required");
        }
        LocalDate date = start.atZone(ZoneId.of(rules.zoneId())).toLocalDate();
        if (date.getYear() < 1 || date.getYear() > 9998) {
            throw new DomainException("INVALID_CALENDAR_CALCULATION", "Start year must be 1..9998");
        }
        return calculateFrom(rules, start, workingMinutes);
    }

    private static Result calculateFrom(CalendarRules rules, Instant start, int workingMinutes) {
        var result = addWorkingTime(rules, start, Duration.ofMinutes(workingMinutes));
        return new Result(start, result.dueAt(), rules.zoneId(), workingMinutes, result.usedPeriods());
    }

    /**
     * 恢复暂停的任务，只接续原到期时刻前尚未消耗的工作时长，保留小于一分钟的精度。
     * 暂停前已到期的任务保留原到期事实，不重新获得办理时间；调用方必须提供原绑定日历修订。
     */
    public static Instant resume(CalendarRules rules, Instant pausedAt, Instant originalDueAt, Instant resumedAt) {
        if (pausedAt == null || originalDueAt == null || resumedAt == null || resumedAt.isBefore(pausedAt)) {
            throw new DomainException("INVALID_DEADLINE_RESUME", "Resume time must not precede the recorded pause");
        }
        if (!originalDueAt.isAfter(pausedAt) || resumedAt.equals(pausedAt)) return originalDueAt;
        ZoneId zone = ZoneId.of(rules.zoneId());
        LocalDate date = pausedAt.atZone(zone).toLocalDate();
        LocalDate last = originalDueAt.atZone(zone).toLocalDate();
        Duration remaining = Duration.ZERO;
        for (int day = 0; day < MAX_LOOKAHEAD_DAYS && !date.isAfter(last); day++, date = date.plusDays(1)) {
            for (var period : rules.instantPeriodsOn(date)) {
                Instant from = pausedAt.isAfter(period.start()) ? pausedAt : period.start();
                Instant until = originalDueAt.isBefore(period.end()) ? originalDueAt : period.end();
                if (from.isBefore(until)) remaining = remaining.plus(Duration.between(from, until));
            }
        }
        if (!date.isAfter(last)) throw horizonExceeded();
        if (remaining.isZero()) {
            throw new DomainException("DEADLINE_STATE_INVALID", "The original deadline has no remaining bound working time");
        }
        int year = resumedAt.atZone(zone).getYear();
        if (year < 1 || year > 9998) throw horizonExceeded();
        return addWorkingTime(rules, resumedAt, remaining).dueAt();
    }

    private static Calculation addWorkingTime(CalendarRules rules, Instant start, Duration remaining) {
        LocalDate date = start.atZone(ZoneId.of(rules.zoneId())).toLocalDate();
        int usedPeriods = 0;
        for (int day = 0; day < MAX_LOOKAHEAD_DAYS && date.getYear() <= 9999; day++, date = date.plusDays(1)) {
            for (var period : rules.instantPeriodsOn(date)) {
                Instant from = start.isAfter(period.start()) ? start : period.start();
                if (!from.isBefore(period.end())) continue;
                Duration available = Duration.between(from, period.end());
                usedPeriods++;
                if (remaining.compareTo(available) <= 0) {
                    Instant dueAt = from.plus(remaining);
                    // API 时间戳使用四位年份，边界计算也不能输出无法被客户端解析的扩展年份。
                    if (!dueAt.isBefore(MAX_RESULT_EXCLUSIVE)) throw horizonExceeded();
                    return new Calculation(dueAt, usedPeriods);
                }
                remaining = remaining.minus(available);
            }
        }
        throw horizonExceeded();
    }

    private static DomainException horizonExceeded() {
        return new DomainException("CALENDAR_HORIZON_EXCEEDED", "Insufficient working time within the supported calendar horizon");
    }

    /** @author owlzhangfq@gmail.com */
    private record Calculation(Instant dueAt, int usedPeriods) { }

    /**
     * 到期值为真实时间轴上的时刻；usedPeriods 可用于解释跨午休、跨日或重复钟点。
     * @author owlzhangfq@gmail.com
     */
    public record Result(Instant startAt, Instant dueAt, String zoneId, int workingMinutes, int usedPeriods) { }
}
