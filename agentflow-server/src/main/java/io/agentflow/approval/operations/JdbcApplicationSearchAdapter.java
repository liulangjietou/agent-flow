package io.agentflow.approval.operations;


import io.agentflow.approval.operations.mapper.ApplicationSearchAdapterMapper;
import io.agentflow.approval.process.FlowableParticipationParameters;
import io.agentflow.common.Actor;
import io.agentflow.mybatis.SqlRows;

import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 申请检索直接投影有界摘要，不加载正文、审计或引擎实体。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcApplicationSearchAdapter implements ApplicationSearchPort {
    private final ApplicationSearchAdapterMapper sqlMapper;

    /** 复用业务数据库连接。 */
    public JdbcApplicationSearchAdapter(ApplicationSearchAdapterMapper sqlMapper) {
        this.sqlMapper = sqlMapper;
    }

    @Override
    public List<Item> search(Actor actor, Query query) {
        var parameters = new ArrayList<Object>(List.of(actor.tenantId()));

        if (!actor.hasRole("ADMIN")) {
            parameters.add(actor.userId());
            FlowableParticipationParameters.append(actor, parameters);
            // 转交会改变引擎指派人，真实办理审计保留原办理人的读取权限。

            parameters.add(actor.userId());
        }
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
        if (!query.status().isEmpty()) {
            parameters.add(query.status());
        }
        if (!query.processKey().isEmpty()) {
            parameters.add(query.processKey());
        }
        if (query.definitionVersion() != null) {
            parameters.add(query.definitionVersion());
        }
        if (!query.applicant().isEmpty()) {
            parameters.add(query.applicant());
        }
        if (query.createdFrom() != null) {
            parameters.add(Timestamp.from(query.createdFrom()));
        }
        if (query.createdBefore() != null) {
            parameters.add(Timestamp.from(query.createdBefore()));
        }
        if (query.beforeTime() != null) {

            parameters.add(Timestamp.from(query.beforeTime()));
            parameters.add(Timestamp.from(query.beforeTime()));
            parameters.add(query.beforeId().toString());
        }
        parameters.add(query.limit() + 1);
        return SqlRows.map(
                sqlMapper.searchQuery(
                        actor.roles().size(),
                        (!actor.roles().isEmpty()),
                        (!actor.hasRole("ADMIN")),
                        (!query.text().isEmpty()),
                        (!query.status().isEmpty()),
                        (!query.processKey().isEmpty()),
                        (query.definitionVersion() != null),
                        (!query.applicant().isEmpty()),
                        (query.createdFrom() != null),
                        (query.createdBefore() != null),
                        (query.beforeTime() != null),
                        parameters.toArray()),
                row ->
                        new Item(
                                UUID.fromString(row.getString("id")),
                                row.getString("business_no"),
                                row.getString("title"),
                                row.getString("process_key"),
                                row.getLong("definition_version"),
                                row.getString("created_by"),
                                row.getString("status"),
                                row.getInt("round_no"),
                                row.getTimestamp("created_at").toInstant(),
                                row.getTimestamp("updated_at").toInstant()));
    }
}
