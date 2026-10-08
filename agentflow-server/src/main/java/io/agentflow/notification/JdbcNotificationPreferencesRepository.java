package io.agentflow.notification;


import io.agentflow.common.DomainException;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.notification.mapper.NotificationPreferencesRepositoryMapper;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;

/**
 * 个人偏好与完整启停代次历史共用事务，不能被并发初次创建覆盖。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcNotificationPreferencesRepository implements NotificationPreferencesRepository {
    private final NotificationPreferencesRepositoryMapper sqlMapper;

    /** 使用平台统一事务数据源。 */
    public JdbcNotificationPreferencesRepository(
            NotificationPreferencesRepositoryMapper sqlMapper) {
        this.sqlMapper = sqlMapper;
    }

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
        return SqlRows.map(
                        sqlMapper.readQuery(lock, new Object[] {tenant, recipient}),
                        row ->
                                new NotificationPreferences(
                                        row.getString("tenant_id"),
                                        row.getString("recipient_id"),
                                        row.getBoolean("email_enabled"),
                                        row.getBoolean("enterprise_im_enabled"),
                                        row.getLong("version"),
                                        row.getLong("email_generation"),
                                        row.getLong("enterprise_im_generation"),
                                        row.getTimestamp("updated_at").toInstant()))
                .stream()
                .findFirst()
                .orElseGet(() -> NotificationPreferences.defaults(tenant, recipient));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void save(NotificationPreferences previous, NotificationPreferences current) {
        if (current == previous) return;
        int changed;
        try {
            if (previous.version() == 0)
                changed =
                        sqlMapper.save(
                                current.tenantId(),
                                current.recipient(),
                                current.emailEnabled(),
                                current.enterpriseImEnabled(),
                                current.version(),
                                current.emailGeneration(),
                                current.enterpriseImGeneration(),
                                Timestamp.from(current.updatedAt()));
            else
                changed =
                        sqlMapper.save2(
                                current.emailEnabled(),
                                current.enterpriseImEnabled(),
                                current.version(),
                                current.emailGeneration(),
                                current.enterpriseImGeneration(),
                                Timestamp.from(current.updatedAt()),
                                current.tenantId(),
                                current.recipient(),
                                previous.version());
        } catch (DuplicateKeyException conflict) {
            // PostgreSQL 竞争失败后退出原事务，由入口报告冲突，不能在已中止事务内继续查询。
            throw new DomainException(
                    "CONCURRENCY_CONFLICT", "Notification preferences were created concurrently");
        }
        if (changed != 1)
            throw new DomainException("CONCURRENCY_CONFLICT", "Notification preferences changed");
        sqlMapper.save3(
                current.tenantId(),
                current.recipient(),
                current.version(),
                current.emailEnabled(),
                current.enterpriseImEnabled(),
                current.emailGeneration(),
                current.enterpriseImGeneration(),
                Timestamp.from(current.updatedAt()));
    }
}
