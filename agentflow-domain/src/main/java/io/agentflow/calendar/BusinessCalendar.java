package io.agentflow.calendar;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 工作日历聚合：标识和业务键固定，每次明确保存产生不可变修订，旧版本继续有效。
 * @author owlzhangfq@gmail.com
 */
public record BusinessCalendar(UUID id, String tenantId, String key, String name, long revision,
                               CalendarRules rules, String updatedBy, Instant updatedAt) {
    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    private static final int MAX_NAME_LENGTH = 128;

    /** 创建首版，名称和规则由管理员明确提供，不生成默认企业制度。 */
    public static BusinessCalendar create(String tenantId, String key, String name, CalendarRules rules, String actor, Instant now) {
        if (key == null || !KEY_PATTERN.matcher(key).matches()) throw new DomainException("INVALID_CALENDAR", "Calendar key is invalid");
        requireSettings(name, rules);
        return new BusinessCalendar(UUID.randomUUID(), tenantId, key, name.strip(), 1, rules, actor, now.truncatedTo(ChronoUnit.MICROS));
    }

    /** 使用用户核对过的版本创建下一修订，不更换身份或覆盖旧规则。 */
    public BusinessCalendar revise(String name, CalendarRules rules, long expectedRevision, String actor, Instant now) {
        if (expectedRevision != revision) throw new DomainException("CONCURRENCY_CONFLICT", "Calendar revision changed");
        requireSettings(name, rules);
        return new BusinessCalendar(id, tenantId, key, name.strip(), Math.addExact(revision, 1), rules, actor, now.truncatedTo(ChronoUnit.MICROS));
    }

    private static void requireSettings(String name, CalendarRules rules) {
        if (StringUtils.isBlank(name) || name.length() > MAX_NAME_LENGTH || rules == null) throw new DomainException("INVALID_CALENDAR", "Calendar name and rules are required");
    }
}
