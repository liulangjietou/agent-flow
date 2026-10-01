package io.agentflow.notification;

import io.agentflow.common.DomainException;
import java.sql.Timestamp;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 个人偏好与完整启停代次历史共用事务，不能被并发初次创建覆盖。 @author owlzhangfq@gmail.com */
@Repository
public class JdbcNotificationPreferencesRepository implements NotificationPreferencesRepository {
    private final JdbcTemplate jdbc;

    /** 使用平台统一事务数据源。 */
    public JdbcNotificationPreferencesRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public NotificationPreferences get(String tenant, String recipient) {
        return read(tenant, recipient, false);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public NotificationPreferences lock(String tenant, String recipient) {
        return read(tenant, recipient, true);
    }

    private NotificationPreferences read(String tenant, String recipient, boolean lock) {
        return jdbc.query("SELECT * FROM notification_preferences WHERE tenant_id=? AND recipient_id=?" + (lock ? " FOR UPDATE" : ""),
                (row, index) -> new NotificationPreferences(row.getString("tenant_id"), row.getString("recipient_id"),
                        row.getBoolean("email_enabled"), row.getBoolean("enterprise_im_enabled"), row.getLong("version"),
                        row.getLong("email_generation"), row.getLong("enterprise_im_generation"), row.getTimestamp("updated_at").toInstant()),
                tenant, recipient).stream().findFirst().orElseGet(() -> NotificationPreferences.defaults(tenant, recipient));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void save(NotificationPreferences previous, NotificationPreferences current) {
        if (current == previous) return;
        int changed;
        try {
            if (previous.version() == 0) changed = jdbc.update("""
                    INSERT INTO notification_preferences
                    (tenant_id,recipient_id,email_enabled,enterprise_im_enabled,version,email_generation,enterprise_im_generation,updated_at)
                    VALUES (?,?,?,?,?,?,?,?)
                    """, current.tenantId(), current.recipient(), current.emailEnabled(), current.enterpriseImEnabled(), current.version(),
                    current.emailGeneration(), current.enterpriseImGeneration(), Timestamp.from(current.updatedAt()));
            else changed = jdbc.update("""
                    UPDATE notification_preferences SET email_enabled=?,enterprise_im_enabled=?,version=?,email_generation=?,
                    enterprise_im_generation=?,updated_at=? WHERE tenant_id=? AND recipient_id=? AND version=?
                    """, current.emailEnabled(), current.enterpriseImEnabled(), current.version(), current.emailGeneration(),
                    current.enterpriseImGeneration(), Timestamp.from(current.updatedAt()), current.tenantId(), current.recipient(), previous.version());
        } catch (DuplicateKeyException conflict) {
            // PostgreSQL 竞争失败后退出原事务，由入口报告冲突，不能在已中止事务内继续查询。
            throw new DomainException("CONCURRENCY_CONFLICT", "Notification preferences were created concurrently");
        }
        if (changed != 1) throw new DomainException("CONCURRENCY_CONFLICT", "Notification preferences changed");
        jdbc.update("""
                INSERT INTO notification_preference_change
                (tenant_id,recipient_id,version,email_enabled,enterprise_im_enabled,email_generation,enterprise_im_generation,changed_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, current.tenantId(), current.recipient(), current.version(), current.emailEnabled(), current.enterpriseImEnabled(),
                current.emailGeneration(), current.enterpriseImGeneration(), Timestamp.from(current.updatedAt()));
    }
}
