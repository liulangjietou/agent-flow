package io.agentflow.notification;

import io.agentflow.common.DomainException;
import java.time.Instant;

/**
 * 接收人拥有自己的外部提醒偏好；渠道代次隔离关闭后重新开启之前的旧通知。
 * @author owlzhangfq@gmail.com
 */
public record NotificationPreferences(String tenantId, String recipient, boolean emailEnabled,
                                     boolean enterpriseImEnabled, long version, long emailGeneration,
                                     long enterpriseImGeneration, Instant updatedAt) {
    /** 首次访问只读默认值，不隐式开启或写入任何订阅。 */
    public static NotificationPreferences defaults(String tenant, String recipient) {
        return new NotificationPreferences(tenant, recipient, false, false, 0, 0, 0, null);
    }

    /** 版本冲突先拒绝；只有渠道启停变化才推进其代次，不影响另一个渠道。 */
    public NotificationPreferences revise(long expectedVersion, boolean email, boolean enterpriseIm, Instant time) {
        if (version != expectedVersion) throw new DomainException("CONCURRENCY_CONFLICT", "Notification preferences changed");
        if (emailEnabled == email && enterpriseImEnabled == enterpriseIm) return this;
        return new NotificationPreferences(tenantId, recipient, email, enterpriseIm, version + 1,
                emailGeneration + (emailEnabled == email ? 0 : 1),
                enterpriseImGeneration + (enterpriseImEnabled == enterpriseIm ? 0 : 1),
                time.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }

    /** 开关只影响外部渠道，不能关闭站内事实记录。 */
    public boolean enabled(NotificationChannel channel) {
        return channel == NotificationChannel.EMAIL ? emailEnabled : enterpriseImEnabled;
    }

    /** 新一代同意不恢复旧代次的积压消息。 */
    public long generation(NotificationChannel channel) {
        return channel == NotificationChannel.EMAIL ? emailGeneration : enterpriseImGeneration;
    }

    /** 入队快照在实际发送前必须再次核对同租户、本人和原渠道代次。 */
    public boolean permits(String tenant, String user, NotificationChannel channel, long generation) {
        return tenantId.equals(tenant) && recipient.equals(user) && enabled(channel) && generation(channel) == generation;
    }
}
