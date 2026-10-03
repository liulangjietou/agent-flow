package io.agentflow.notification;

import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.jdbc.JdbcTimestampPrecision;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static io.agentflow.notification.NotificationDeliveryProgress.*;

/** 投递身份、乐观状态和追加历史共用事务，不在仓储中决定人员资格或执行外部发送。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcNotificationDeliveryStore {
    private final JdbcTemplate jdbc;

    /** 复用业务事务与当前目录，不在消息中保存外部地址或凭据。 */
    public JdbcNotificationDeliveryStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 在原站内消息事务中冻结可用绑定；没有绑定的意向以后也不能自动补绑。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(InboxMessage message, NotificationChannel channel, long generation, NotificationDestinations.Destination target) {
        var value = new NotificationDelivery(UUID.randomUUID(), message.tenantId(), message.recipient(), message.id(), channel, generation,
                target == null ? null : target.id(), target == null ? null : target.digest(), message.createdAt(), pending(message.createdAt()));
        jdbc.update("""
                INSERT INTO notification_dispatch
                (id,tenant_id,recipient_id,inbox_id,channel,consent_generation,status,created_at,updated_at,binding_id,destination_digest,version,next_attempt_at)
                VALUES (?,?,?,?,?,?,'PENDING',?,?,?,?,1,?)
                """, value.id().toString(), value.tenantId(), value.recipient(), value.inboxId().toString(), channel.name(), generation,
                stamp(value.createdAt()), stamp(value.createdAt()), value.bindingId(), value.destinationDigest(), stamp(value.createdAt()));
        history(value, null, null);
    }

    /** 偏好行已经由保存事务锁定，抑制尚未开始的旧代次并追加事实历史。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void suppressRevoked(NotificationPreferences current) {
        var queued = jdbc.query("""
                SELECT * FROM notification_dispatch WHERE tenant_id=? AND recipient_id=?
                AND status IN ('PENDING','RETRY_WAIT') ORDER BY id FOR UPDATE
                """, JdbcNotificationDeliveryStore::map, current.tenantId(), current.recipient());
        for (var value : queued) {
            if (!current.permits(value.tenantId(), value.recipient(), value.channel(), value.consentGeneration()))
                save(value, value.progress().suppress(FailureCode.CONSENT_REVOKED, current.updatedAt()), null, null);
        }
    }

    /** 到期租约只进入结果未知；一次最多处理十项，避免单次轮询无界增长。 */
    public List<UUID> due(Instant now) {
        return jdbc.queryForList("""
                SELECT id FROM notification_dispatch
                WHERE (status IN ('PENDING','RETRY_WAIT') AND next_attempt_at<=?) OR (status='IN_FLIGHT' AND lease_until<=?)
                ORDER BY updated_at,id LIMIT 10
                """, String.class, stamp(now), stamp(now)).stream().map(UUID::fromString).toList();
    }

    /** 后台按内部标识读取身份；公开查询必须使用带本人范围的 get。 */
    public Optional<NotificationDelivery> find(UUID id) { return find(id, false); }

    /** 编排层已经锁定组织和偏好后，再锁定当前投递行。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<NotificationDelivery> lock(UUID id) { return find(id, true); }

    /** 本人才能读取自己的投递，管理员角色不提供跨人员读取捷径。 */
    public Optional<NotificationDelivery> get(Actor actor, UUID id) {
        return jdbc.query("SELECT * FROM notification_dispatch WHERE id=? AND tenant_id=? AND recipient_id=?",
                JdbcNotificationDeliveryStore::map, id.toString(), actor.tenantId(), actor.userId()).stream().findFirst();
    }

    /** 本人列表每次最多读取一页加一条，不选择任意租户或接收人。 */
    public List<NotificationDelivery> search(Actor actor, NotificationDeliveryQueryParameters.Search query) {
        var parameters = new ArrayList<Object>(List.of(actor.tenantId(), actor.userId()));
        var sql = new StringBuilder("SELECT * FROM notification_dispatch WHERE tenant_id=? AND recipient_id=?");
        if (!query.channel().isEmpty()) { sql.append(" AND channel=?"); parameters.add(query.channel()); }
        if (!query.status().isEmpty()) { sql.append(" AND status=?"); parameters.add(query.status()); }
        if (query.beforeTime() != null) {
            sql.append(" AND (created_at<? OR (created_at=? AND id<?))");
            parameters.add(stamp(query.beforeTime())); parameters.add(stamp(query.beforeTime())); parameters.add(query.beforeId().toString());
        }
        sql.append(" ORDER BY created_at DESC,id DESC LIMIT ?"); parameters.add(query.limit() + 1);
        return jdbc.query(sql.toString(), JdbcNotificationDeliveryStore::map, parameters.toArray());
    }

    /** 历史再次关联当前本人范围，并受详情读取版本限制，避免混入晚到的新版本。 */
    public List<NotificationDeliveryViews.Event> history(Actor actor, UUID id, long throughVersion, NotificationDeliveryQueryParameters.History query) {
        var parameters = new ArrayList<Object>(List.of(id.toString(), actor.tenantId(), actor.userId(), throughVersion));
        var sql = new StringBuilder("""
                SELECT e.* FROM notification_delivery_event e JOIN notification_dispatch d ON d.id=e.delivery_id
                WHERE d.id=? AND d.tenant_id=? AND d.recipient_id=? AND e.version<=?
                """);
        if (query.beforeVersion() != null) { sql.append(" AND e.version<?"); parameters.add(query.beforeVersion()); }
        sql.append(" ORDER BY e.version DESC LIMIT ?"); parameters.add(query.limit() + 1);
        return jdbc.query(sql.toString(), (row, index) -> new NotificationDeliveryViews.Event(row.getLong("version"), Status.valueOf(row.getString("status")),
                row.getInt("attempts"), row.getInt("cycle_attempts"), row.getString("error_code") == null ? null : FailureCode.valueOf(row.getString("error_code")),
                row.getString("actor_id"), row.getString("reason"), time(row, "occurred_at")), parameters.toArray());
    }

    /** 只检查原消息的身份归属，不读取标题、表单或评论正文。 */
    public boolean ownsMessage(NotificationDelivery value) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM notification_inbox WHERE id=? AND tenant_id=? AND recipient_id=?)",
                Boolean.class, value.inboxId().toString(), value.tenantId(), value.recipient()));
    }
    private Optional<NotificationDelivery> find(UUID id, boolean lock) {
        return jdbc.query("SELECT * FROM notification_dispatch WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                JdbcNotificationDeliveryStore::map, id.toString()).stream().findFirst();
    }
    /** 状态和完整版本历史一起保存，任何一步失败均回滚。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public NotificationDelivery save(NotificationDelivery previous, NotificationDeliveryProgress next, String actor, String reason) {
        // 返回与重读相同的租约边界，避免调用方持有数据库舍入前的到期时间。
        next = new NotificationDeliveryProgress(next.status(), next.version(), next.attempts(), next.cycleAttempts(),
                persistedTime(next.nextAttemptAt()), persistedTime(next.leaseUntil()), next.leaseToken(), next.errorCode(), persistedTime(next.changedAt()));
        int changed = jdbc.update("""
                UPDATE notification_dispatch SET status=?,version=?,attempts=?,cycle_attempts=?,next_attempt_at=?,lease_until=?,lease_token=?,error_code=?,updated_at=?
                WHERE id=? AND version=?
                """, next.status().name(), next.version(), next.attempts(), next.cycleAttempts(), stamp(next.nextAttemptAt()), stamp(next.leaseUntil()),
                next.leaseToken() == null ? null : next.leaseToken().toString(), next.errorCode() == null ? null : next.errorCode().name(),
                stamp(next.changedAt()), previous.id().toString(), previous.progress().version());
        if (changed != 1) throw new DomainException("CONCURRENCY_CONFLICT", "Notification delivery changed");
        var value = previous.withProgress(next); history(value, actor, reason); return value;
    }
    private void history(NotificationDelivery value, String actor, String reason) {
        var p = value.progress();
        jdbc.update("""
                INSERT INTO notification_delivery_event(delivery_id,version,status,attempts,cycle_attempts,error_code,actor_id,reason,occurred_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, value.id().toString(), p.version(), p.status().name(), p.attempts(), p.cycleAttempts(),
                p.errorCode() == null ? null : p.errorCode().name(), actor, reason, stamp(p.changedAt()));
    }
    private static NotificationDelivery map(ResultSet row, int index) throws SQLException {
        String token = row.getString("lease_token"), code = row.getString("error_code");
        var p = new NotificationDeliveryProgress(Status.valueOf(row.getString("status")), row.getLong("version"), row.getInt("attempts"), row.getInt("cycle_attempts"),
                time(row, "next_attempt_at"), time(row, "lease_until"), token == null ? null : UUID.fromString(token), code == null ? null : FailureCode.valueOf(code), time(row, "updated_at"));
        return new NotificationDelivery(UUID.fromString(row.getString("id")), row.getString("tenant_id"), row.getString("recipient_id"), UUID.fromString(row.getString("inbox_id")),
                NotificationChannel.valueOf(row.getString("channel")), row.getLong("consent_generation"), row.getString("binding_id"), row.getString("destination_digest"), time(row, "created_at"), p);
    }
    private static Instant persistedTime(Instant value) { return value == null ? null : JdbcTimestampPrecision.roundedToMicros(value); }
    private static Timestamp stamp(Instant value) { return value == null ? null : Timestamp.from(persistedTime(value)); }
    private static Instant time(ResultSet row, String column) throws SQLException { var value = row.getTimestamp(column); return value == null ? null : value.toInstant(); }

}
