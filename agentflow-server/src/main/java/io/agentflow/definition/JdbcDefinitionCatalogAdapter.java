package io.agentflow.definition;


import io.agentflow.definition.mapper.DefinitionCatalogAdapterMapper;
import io.agentflow.mybatis.SqlRows;

import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 目录查询只投影摘要列，使用绑定参数和租户条件执行有界分页。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcDefinitionCatalogAdapter implements DefinitionCatalogPort {
    private final DefinitionCatalogAdapterMapper sqlMapper;

    /** 复用业务数据库连接。 */
    public JdbcDefinitionCatalogAdapter(DefinitionCatalogAdapterMapper sqlMapper) {
        this.sqlMapper = sqlMapper;
    }

    @Override
    public List<Item> search(String tenantId, Query query) {
        var arguments = new ArrayList<Object>(List.of(tenantId));

        if (!query.text().isEmpty()) {
            String pattern =
                    "%"
                            + query.text()
                                    .toLowerCase(Locale.ROOT)
                                    .replace("!", "!!")
                                    .replace("%", "!%")
                                    .replace("_", "!_")
                            + "%";

            arguments.addAll(List.of(pattern, pattern));
        }
        if (!query.status().isEmpty()) {
            arguments.add(query.status());
        }
        if (query.startEnabled() != null) {
            arguments.add(query.startEnabled());
        }
        if (!query.processKey().isEmpty()) {
            arguments.add(query.processKey());
        }
        if (query.version() != null) {
            arguments.add(query.version());
        }
        if (query.beforeTime() != null) {

            arguments.add(Timestamp.from(query.beforeTime()));
            arguments.add(Timestamp.from(query.beforeTime()));
            arguments.add(query.beforeId().toString());
        }
        arguments.add(query.limit() + 1);
        return SqlRows.map(
                sqlMapper.searchQuery(
                        (!query.text().isEmpty()),
                        (!query.status().isEmpty()),
                        (query.startEnabled() != null),
                        (!query.processKey().isEmpty()),
                        (query.version() != null),
                        (query.beforeTime() != null),
                        arguments.toArray()),
                row ->
                        new Item(
                                UUID.fromString(row.getString("id")),
                                row.getString("process_key"),
                                row.getString("name"),
                                row.getString("status"),
                                row.getLong("version"),
                                row.getLong("revision"),
                                row.getBoolean("start_enabled"),
                                row.getTimestamp("created_at").toInstant(),
                                row.getTimestamp("updated_at").toInstant()));
    }
}
