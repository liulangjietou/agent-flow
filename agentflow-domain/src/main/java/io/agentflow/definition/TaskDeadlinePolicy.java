package io.agentflow.definition;

import io.agentflow.calendar.BusinessDeadline;
import io.agentflow.common.DomainException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 人工节点引用不可变日历修订与明确工时；不保存动态“最新版本”或引擎表达式。
 * @author owlzhangfq@gmail.com
 */
public record TaskDeadlinePolicy(UUID calendarId, long calendarRevision, int workingMinutes) {
    public static final String CALENDAR_ID = "deadlineCalendarId";
    public static final String CALENDAR_REVISION = "deadlineCalendarRevision";
    public static final String WORKING_MINUTES = "deadlineWorkingMinutes";
    public static final Set<String> PROPERTY_KEYS = Set.of(CALENDAR_ID, CALENDAR_REVISION, WORKING_MINUTES);

    /** 完整规则在值对象入口一次校验，后续计算使用已解析的强类型值。 */
    public TaskDeadlinePolicy {
        if (calendarId == null || calendarRevision < 1 || workingMinutes < 1 || workingMinutes > BusinessDeadline.MAX_WORKING_MINUTES) {
            throw invalid();
        }
    }

    /** 全部未配置表示无期限；部分填写或非法字面量不能被当作无期限静默忽略。 */
    public static Optional<TaskDeadlinePolicy> fromProperties(Map<String, String> properties) {
        if (PROPERTY_KEYS.stream().noneMatch(properties::containsKey)) return Optional.empty();
        if (!properties.keySet().containsAll(PROPERTY_KEYS)) throw invalid();
        try {
            String rawId = properties.get(CALENDAR_ID);
            UUID id = UUID.fromString(rawId);
            if (!id.toString().equalsIgnoreCase(rawId)) throw invalid();
            long revision = positiveLiteral(properties.get(CALENDAR_REVISION));
            long minutes = positiveLiteral(properties.get(WORKING_MINUTES));
            if (minutes > BusinessDeadline.MAX_WORKING_MINUTES) throw invalid();
            return Optional.of(new TaskDeadlinePolicy(id, revision, (int) minutes));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw invalid();
        }
    }

    private static long positiveLiteral(String value) {
        if (value == null || !value.matches("[1-9][0-9]*")) throw invalid();
        return Long.parseLong(value);
    }

    private static DomainException invalid() {
        return new DomainException("INVALID_TASK_DEADLINE", "Deadline requires a fixed calendar UUID, positive revision and supported working minutes");
    }
}
