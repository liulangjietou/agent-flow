package io.agentflow.notification;

import io.agentflow.common.Actor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 消息仓储在每个查询和更新中绑定租户与接收人；不根据消息扩大申请可见范围。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcInboxRepository implements InboxRepository {
    private final JdbcTemplate jdbc;
    private final NotificationDispatchPlanner dispatches;
    private static final RowMapper<InboxMessage> MAPPER = (row, index) -> new InboxMessage(
            UUID.fromString(row.getString("id")), row.getString("tenant_id"), row.getString("recipient_id"),
            UUID.fromString(row.getString("application_id")), row.getString("title"), row.getString("business_no"),
            InboxMessage.Kind.valueOf(row.getString("kind")), row.getString("actor_id"), row.getString("task_id"),
            row.getString("node_name"), row.getInt("round_no"), row.getTimestamp("created_at").toInstant(),
            row.getTimestamp("read_at") == null ? null : row.getTimestamp("read_at").toInstant(), row.getString("content"));

    /** 复用审批事务所用数据源。 */
    public JdbcInboxRepository(JdbcTemplate jdbc, NotificationDispatchPlanner dispatches) { this.jdbc = jdbc; this.dispatches = dispatches; }

    @Override
    @Transactional
    public void append(String eventKey, InboxMessage message) {
        int inserted = jdbc.update("""
                INSERT INTO notification_inbox
                (id,tenant_id,recipient_id,event_key,application_id,title,business_no,kind,actor_id,task_id,node_name,round_no,created_at,content)
                SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?,? WHERE NOT EXISTS
                (SELECT 1 FROM notification_inbox WHERE tenant_id=? AND recipient_id=? AND event_key=?)
                """, message.id().toString(), message.tenantId(), message.recipient(), eventKey,
                message.applicationId().toString(), message.title(), message.businessNo(), message.kind().name(),
                message.actor(), message.taskId(), message.nodeName(), message.roundNo(), Timestamp.from(message.createdAt()), message.content(),
                message.tenantId(), message.recipient(), eventKey);
        if (inserted == 1) dispatches.append(message);
    }

    @Override
    public List<InboxMessage> list(Actor actor, Query query) {
        var parameters = new ArrayList<Object>(List.of(actor.tenantId(), actor.userId()));
        StringBuilder sql = new StringBuilder("SELECT * FROM notification_inbox WHERE tenant_id=? AND recipient_id=?");
        if (query.unreadOnly()) sql.append(" AND read_at IS NULL");
        if (query.beforeTime() != null) {
            sql.append(" AND (created_at<? OR (created_at=? AND id<?))");
            parameters.add(Timestamp.from(query.beforeTime())); parameters.add(Timestamp.from(query.beforeTime()));
            parameters.add(query.beforeId().toString());
        }
        sql.append(" ORDER BY created_at DESC,id DESC LIMIT ?"); parameters.add(query.limit() + 1);
        return jdbc.query(sql.toString(), MAPPER, parameters.toArray());
    }

    @Override
    public long unreadCount(Actor actor) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE tenant_id=? AND recipient_id=? AND read_at IS NULL",
                Long.class, actor.tenantId(), actor.userId());
    }

    @Override
    public Optional<InboxMessage> find(Actor actor, UUID id) {
        return jdbc.query("SELECT * FROM notification_inbox WHERE tenant_id=? AND recipient_id=? AND id=?",
                MAPPER, actor.tenantId(), actor.userId(), id.toString()).stream().findFirst();
    }

    @Override
    public InboxMessage saveRead(InboxMessage message) {
        jdbc.update("UPDATE notification_inbox SET read_at=? WHERE tenant_id=? AND recipient_id=? AND id=? AND read_at IS NULL",
                Timestamp.from(message.readAt()), message.tenantId(), message.recipient(), message.id().toString());
        return jdbc.queryForObject("SELECT * FROM notification_inbox WHERE tenant_id=? AND recipient_id=? AND id=?",
                MAPPER, message.tenantId(), message.recipient(), message.id().toString());
    }
}
