package io.agentflow.onboarding;

import io.agentflow.calendar.BusinessCalendar;
import io.agentflow.common.Actor;
import io.agentflow.notification.NotificationPreferences;
import io.agentflow.organization.InitiatorContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

/**
 * 租户首次配置的不可变事实；记录原任职、日历与本人选择，后续修改不能重写此凭据。
 * @author owlzhangfq@gmail.com
 */
public record TenantInitialization(UUID id, String tenantId, String workspaceName, String initializedBy,
                                   Instant initializedAt, Set<String> administratorRoles,
                                   InitiatorContext organization, String administratorName,
                                   BusinessCalendar calendar, NotificationPreferences notifications) {
    /** 复制已验证的角色集合，调用方不能在初始化后改变历史权限快照。 */
    public TenantInitialization { administratorRoles = Set.copyOf(administratorRoles); }

    /** 身份与角色只承接认证结果，不接受页面指定的租户、主体或授权。 */
    public static TenantInitialization completed(Actor actor, String name, InitiatorContext organization,
                                                 String administratorName, BusinessCalendar calendar,
                                                 NotificationPreferences notifications, Instant now) {
        return new TenantInitialization(UUID.randomUUID(), actor.tenantId(), name, actor.userId(),
                now.truncatedTo(ChronoUnit.MICROS), actor.roles(), organization, administratorName, calendar, notifications);
    }
}
