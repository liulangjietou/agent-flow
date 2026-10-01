package io.agentflow.notification;

import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 将新站内事实投影为外发意向，不在审批事务内调用渠道或复制业务正文。
 * @author owlzhangfq@gmail.com
 */
@Component
public class NotificationDispatchPlanner {
    private final JdbcTemplate jdbc;
    private final NotificationPreferencesRepository preferences;

    /** 使用同一数据库记录消息、偏好代次和外发意向。 */
    public NotificationDispatchPlanner(JdbcTemplate jdbc, NotificationPreferencesRepository preferences) {
        this.jdbc = jdbc; this.preferences = preferences;
    }

    /** 只为这次首次落库的站内消息建意向；开启渠道不补发以前的消息。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(InboxMessage message) {
        var current = preferences.get(message.tenantId(), message.recipient());
        for (var channel : NotificationChannel.values()) {
            if (!current.enabled(channel)) continue;
            jdbc.update("""
                    INSERT INTO notification_dispatch
                    (id,tenant_id,recipient_id,inbox_id,channel,consent_generation,status,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,'PENDING',?,?)
                    """, UUID.randomUUID().toString(), message.tenantId(), message.recipient(), message.id().toString(), channel.name(),
                    current.generation(channel), Timestamp.from(message.createdAt()), Timestamp.from(message.createdAt()));
        }
    }

    /** 关闭或更换同意代次只抑制未发送意向，保留原站内消息和外发历史。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void suppressRevoked(NotificationPreferences current) {
        for (var channel : NotificationChannel.values()) {
            jdbc.update("""
                    UPDATE notification_dispatch SET status='SUPPRESSED',updated_at=?
                    WHERE tenant_id=? AND recipient_id=? AND channel=? AND status='PENDING'
                    AND (?=FALSE OR consent_generation<>?)
                    """, Timestamp.from(current.updatedAt()), current.tenantId(), current.recipient(), channel.name(),
                    current.enabled(channel), current.generation(channel));
        }
    }
}
