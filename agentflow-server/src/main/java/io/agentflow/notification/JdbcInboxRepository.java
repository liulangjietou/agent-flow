package io.agentflow.notification;

import io.agentflow.common.Actor;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.notification.mapper.InboxRepositoryMapper;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * 消息仓储在每个查询和更新中绑定租户与接收人；不根据消息扩大申请可见范围。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcInboxRepository implements InboxRepository {
    private final InboxRepositoryMapper sqlMapper;
    private final NotificationDispatchPlanner dispatches;
    private static final Function<SqlRow, InboxMessage> MAPPER =
            row ->
                    new InboxMessage(
                            UUID.fromString(row.getString("id")),
                            row.getString("tenant_id"),
                            row.getString("recipient_id"),
                            UUID.fromString(row.getString("application_id")),
                            row.getString("title"),
                            row.getString("business_no"),
                            InboxMessage.Kind.valueOf(row.getString("kind")),
                            row.getString("actor_id"),
                            row.getString("task_id"),
                            row.getString("node_name"),
                            row.getInt("round_no"),
                            row.getTimestamp("created_at").toInstant(),
                            row.getTimestamp("read_at") == null
                                    ? null
                                    : row.getTimestamp("read_at").toInstant(),
                            row.getString("content"));

    /** 复用审批事务所用数据源。 */
    public JdbcInboxRepository(
            InboxRepositoryMapper sqlMapper, NotificationDispatchPlanner dispatches) {
        this.sqlMapper = sqlMapper;
        this.dispatches = dispatches;
    }

    @Override
    @Transactional
    public void append(String eventKey, InboxMessage message) {
        int inserted =
                sqlMapper.append(
                        message.id().toString(),
                        message.tenantId(),
                        message.recipient(),
                        eventKey,
                        message.applicationId().toString(),
                        message.title(),
                        message.businessNo(),
                        message.kind().name(),
                        message.actor(),
                        message.taskId(),
                        message.nodeName(),
                        message.roundNo(),
                        Timestamp.from(message.createdAt()),
                        message.content(),
                        message.tenantId(),
                        message.recipient(),
                        eventKey);
        if (inserted == 1) dispatches.append(message);
    }

    @Override
    public List<InboxMessage> list(Actor actor, Query query) {
        var parameters = new ArrayList<Object>(List.of(actor.tenantId(), actor.userId()));

        if (query.beforeTime() != null) {

            parameters.add(Timestamp.from(query.beforeTime()));
            parameters.add(Timestamp.from(query.beforeTime()));
            parameters.add(query.beforeId().toString());
        }
        parameters.add(query.limit() + 1);
        return SqlRows.map(
                sqlMapper.listQuery(
                        (query.unreadOnly()), (query.beforeTime() != null), parameters.toArray()),
                MAPPER);
    }

    @Override
    public long unreadCount(Actor actor) {
        return SqlRows.single(sqlMapper.unreadCount(actor.tenantId(), actor.userId()));
    }

    @Override
    public Optional<InboxMessage> find(Actor actor, UUID id) {
        return SqlRows.map(sqlMapper.find(actor.tenantId(), actor.userId(), id.toString()), MAPPER)
                .stream()
                .findFirst();
    }

    @Override
    public InboxMessage saveRead(InboxMessage message) {
        sqlMapper.saveRead(
                Timestamp.from(message.readAt()),
                message.tenantId(),
                message.recipient(),
                message.id().toString());
        return SqlRows.single(
                SqlRows.map(
                        sqlMapper.saveRead2(
                                message.tenantId(), message.recipient(), message.id().toString()),
                        MAPPER));
    }
}
