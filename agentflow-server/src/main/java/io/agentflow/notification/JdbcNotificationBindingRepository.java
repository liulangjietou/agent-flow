package io.agentflow.notification;

import io.agentflow.common.DomainException;
import io.agentflow.common.ErrorCode;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.notification.mapper.NotificationBindingMapper;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 动态员工通知收件绑定仓储；服务器凭据仍保存在部署配置，数据库仅存收件映射与版本。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcNotificationBindingRepository {
    private final NotificationBindingMapper sqlMapper;

    /** 复用统一 MyBatis 事务上下文。 */
    public JdbcNotificationBindingRepository(NotificationBindingMapper sqlMapper) { this.sqlMapper = sqlMapper; }

    /** 按租户、收件主体与渠道读取动态绑定。 */
    @Transactional(readOnly = true)
    public Optional<Record> find(String tenantId, String recipientId, NotificationChannel channel) {
        return Optional.ofNullable(sqlMapper.find(tenantId, recipientId, channel.name())).map(JdbcNotificationBindingRepository::map);
    }

    /** 列出本租户全部动态绑定记录。 */
    @Transactional(readOnly = true)
    public List<Record> list(String tenantId) {
        return sqlMapper.listByTenant(tenantId).stream().map(JdbcNotificationBindingRepository::map).toList();
    }

    /** 读取本租户已启用的动态渠道与服务器编号，用于运行诊断。 */
    @Transactional(readOnly = true)
    public List<EnabledChannel> enabledChannels(String tenantId) {
        return sqlMapper.enabledChannels(tenantId).stream()
                .map(row -> new EnabledChannel(NotificationChannel.valueOf(row.getString("channel")), row.getString("server_id")))
                .toList();
    }

    /** 按租户+收件人+渠道新增或更新动态绑定，存在时校验乐观版本。 */
    @Transactional
    public Record upsert(String id, String tenantId, String recipientId, NotificationChannel channel,
                         String serverId, String address, boolean enabled, Long expectedVersion, String actorId, Instant now) {
        var existing = find(tenantId, recipientId, channel);
        var byId = Optional.ofNullable(sqlMapper.findById(tenantId, id)).map(JdbcNotificationBindingRepository::map);
        if (byId.isPresent() && (!byId.get().recipientId().equals(recipientId) || byId.get().channel() != channel)) {
            throw new DomainException(ErrorCode.CONCURRENCY_CONFLICT, "Notification binding id is already used by another recipient or channel");
        }
        var timestamp = now.atOffset(ZoneOffset.UTC);
        if (existing.isEmpty()) {
            if (expectedVersion != null && expectedVersion != 0L) {
                throw new DomainException(ErrorCode.CONCURRENCY_CONFLICT, "Notification binding version does not match");
            }
            sqlMapper.insert(id, tenantId, recipientId, channel.name(), serverId, address, enabled, 1L, actorId, timestamp, timestamp);
            return new Record(id, tenantId, recipientId, channel, serverId, address, enabled, 1L, actorId, now, now);
        }
        var current = existing.get();
        if (expectedVersion != null && current.version() != expectedVersion) {
            throw new DomainException(ErrorCode.CONCURRENCY_CONFLICT, "Notification binding version changed");
        }
        long nextVersion = current.version() + 1;
        int updated = sqlMapper.update(id, tenantId, recipientId, channel.name(), serverId, address, enabled,
                current.version(), nextVersion, actorId, timestamp);
        if (updated != 1) throw new DomainException(ErrorCode.CONCURRENCY_CONFLICT, "Notification binding version changed");
        return new Record(id, tenantId, recipientId, channel, serverId, address, enabled, nextVersion, actorId, current.createdAt(), now);
    }

    /** 删除本租户内指定的动态绑定。 */
    @Transactional
    public boolean delete(String tenantId, String id) {
        return sqlMapper.deleteById(tenantId, id) == 1;
    }

    private static Record map(SqlRow row) {
        return new Record(row.getString("id"), row.getString("tenant_id"), row.getString("recipient_id"),
                NotificationChannel.valueOf(row.getString("channel")), row.getString("server_id"), row.getString("address"),
                row.getBoolean("enabled"), row.getLong("version"), row.getString("updated_by"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
    }

    /**
     * 动态通知收件绑定持久化记录。
     * @author owlzhangfq@gmail.com
     */
    public record Record(String id, String tenantId, String recipientId, NotificationChannel channel, String serverId,
                         String address, boolean enabled, long version, String updatedBy, Instant createdAt, Instant updatedAt) {
        @Override public String toString() { return "NotificationBindingRecord[id=" + id + ", channel=" + channel + "]"; }
    }

    /**
     * 已启用渠道与所属部署服务器引用。
     * @author owlzhangfq@gmail.com
     */
    public record EnabledChannel(NotificationChannel channel, String serverId) { }
}
