package io.agentflow.approval.operations;


import io.agentflow.approval.operations.mapper.AuditSearchAdapterMapper;
import io.agentflow.mybatis.SqlRows;

import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 以追加事件时间做有界查询，关联申请仅限同租户，保留无法关联的旧事件。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcAuditSearchAdapter implements AuditSearchPort {
    private final AuditSearchAdapterMapper sqlMapper;

    /** 复用业务数据库，只读取审计投影与申请摘要。 */
    public JdbcAuditSearchAdapter(AuditSearchAdapterMapper sqlMapper) {
        this.sqlMapper = sqlMapper;
    }

    @Override
    public List<Item> search(String tenantId, Query query) {
        var parameters = new ArrayList<Object>(List.of(tenantId));

        if (!query.text().isEmpty()) {
            String pattern =
                    "%"
                            + query.text()
                                    .toLowerCase(Locale.ROOT)
                                    .replace("!", "!!")
                                    .replace("%", "!%")
                                    .replace("_", "!_")
                            + "%";

            parameters.addAll(List.of(pattern, pattern));
        }
        if (!query.actor().isEmpty()) {
            parameters.add(query.actor());
        }
        if (!query.action().isEmpty()) {
            parameters.add(query.action());
        }
        if (!query.source().isEmpty()) {
            parameters.add(query.source());
        }
        if (query.applicationId() != null) {
            parameters.add(query.applicationId().toString());
        }
        if (query.occurredFrom() != null) {
            parameters.add(Timestamp.from(query.occurredFrom()));
        }
        if (query.occurredBefore() != null) {
            parameters.add(Timestamp.from(query.occurredBefore()));
        }
        if (query.beforeTime() != null) {

            parameters.add(Timestamp.from(query.beforeTime()));
            parameters.add(Timestamp.from(query.beforeTime()));
            parameters.add(query.beforeId().toString());
        }
        parameters.add(query.limit() + 1);
        return SqlRows.map(
                sqlMapper.searchQuery(
                        (!query.text().isEmpty()),
                        (!query.actor().isEmpty()),
                        (!query.action().isEmpty()),
                        (!query.source().isEmpty()),
                        (query.applicationId() != null),
                        (query.occurredFrom() != null),
                        (query.occurredBefore() != null),
                        (query.beforeTime() != null),
                        parameters.toArray()),
                row ->
                        new Item(
                                UUID.fromString(row.getString("id")),
                                row.getString("event_id"),
                                row.getString("aggregate_type"),
                                row.getString("aggregate_id"),
                                row.getLong("aggregate_version"),
                                row.getString("action"),
                                row.getString("actor_id"),
                                row.getTimestamp("occurred_at").toInstant(),
                                row.getString("linked_id") == null
                                        ? null
                                        : UUID.fromString(row.getString("linked_id")),
                                row.getString("business_no"),
                                row.getString("title")));
    }
}
