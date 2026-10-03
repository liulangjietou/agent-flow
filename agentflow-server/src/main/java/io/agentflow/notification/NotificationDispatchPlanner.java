package io.agentflow.notification;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 将新站内事实投影为外发意向，不在审批事务内调用渠道或复制业务正文。
 * @author owlzhangfq@gmail.com
 */
@Component
public class NotificationDispatchPlanner {
    private final JdbcNotificationDeliveryStore deliveries;
    private final NotificationPreferencesRepository preferences;
    private final NotificationDestinations destinations;

    /** 使用同一数据库记录消息、偏好代次和外发意向。 */
    public NotificationDispatchPlanner(JdbcNotificationDeliveryStore deliveries, NotificationPreferencesRepository preferences, NotificationDestinations destinations) {
        this.deliveries = deliveries; this.preferences = preferences; this.destinations = destinations;
    }

    /** 只为这次首次落库的站内消息建意向；开启渠道不补发以前的消息。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(InboxMessage message) {
        var current = preferences.get(message.tenantId(), message.recipient());
        for (var channel : NotificationChannel.values()) {
            if (!current.enabled(channel)) continue;
            var target = destinations.find(message.tenantId(), message.recipient(), channel).filter(NotificationDestinations.Destination::enabled).orElse(null);
            deliveries.append(message, channel, current.generation(channel), target);
        }
    }

    /** 关闭或更换同意代次只抑制未发送意向，保留原站内消息和外发历史。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void suppressRevoked(NotificationPreferences current) {
        deliveries.suppressRevoked(current);
    }
}
