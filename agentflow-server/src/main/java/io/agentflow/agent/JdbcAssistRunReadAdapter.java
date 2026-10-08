package io.agentflow.agent;


import io.agentflow.agent.mapper.AssistRunReadAdapterMapper;
import io.agentflow.mybatis.SqlRows;

import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 数据库执行有界摘要投影，列表不能带出 context_json 或 state_json。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcAssistRunReadAdapter implements AssistRunReadPort {
    private final AssistRunReadAdapterMapper sqlMapper;

    /** 注入与审批平台相同的数据源。 */
    public JdbcAssistRunReadAdapter(AssistRunReadAdapterMapper sqlMapper) {
        this.sqlMapper = sqlMapper;
    }

    @Override
    public List<Item> list(String tenantId, UUID applicationId, Query query) {

        var arguments = new ArrayList<Object>(List.of(tenantId, applicationId.toString()));
        if (query.roundNo() != null) {
            arguments.add(query.roundNo());
        }
        if (query.beforeTime() != null) {

            Timestamp time = Timestamp.from(query.beforeTime());
            arguments.add(time);
            arguments.add(time);
            arguments.add(query.beforeId().toString());
        }

        arguments.add(query.limit() + 1);
        return SqlRows.map(
                sqlMapper.listQuery(
                        (query.roundNo() != null),
                        (query.beforeTime() != null),
                        arguments.toArray()),
                row ->
                        new Item(
                                UUID.fromString(row.getString("id")),
                                row.getLong("application_version"),
                                row.getInt("round_no"),
                                AssistRun.Status.valueOf(row.getString("status")),
                                row.getLong("version"),
                                row.getTimestamp("created_at").toInstant()));
    }
}
