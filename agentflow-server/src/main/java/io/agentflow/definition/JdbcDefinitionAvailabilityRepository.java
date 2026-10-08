package io.agentflow.definition;


import io.agentflow.definition.mapper.DefinitionAvailabilityRepositoryMapper;
import io.agentflow.mybatis.SqlRows;

import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/**
 * 版本治理事实的同库适配器，按租户和版本标识读取。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcDefinitionAvailabilityRepository implements DefinitionAvailabilityRepository {
    private final DefinitionAvailabilityRepositoryMapper sqlMapper;

    /** 注入业务数据源，参与外层用例事务。 */
    public JdbcDefinitionAvailabilityRepository(DefinitionAvailabilityRepositoryMapper sqlMapper) {
        this.sqlMapper = sqlMapper;
    }

    @Override
    public void append(DefinitionAvailabilityChange change) {
        sqlMapper.append(
                change.tenantId(),
                change.definitionId().toString(),
                change.revision(),
                change.previousEnabled(),
                change.startEnabled(),
                change.changedBy(),
                change.authorizedRole(),
                Timestamp.from(change.changedAt()),
                change.reason());
    }

    @Override
    public List<DefinitionAvailabilityChange> history(
            String tenantId, UUID definitionId, long beforeRevision, int limit) {
        return SqlRows.map(
                sqlMapper.history(tenantId, definitionId.toString(), beforeRevision, limit + 1),
                row ->
                        new DefinitionAvailabilityChange(
                                row.getString("tenant_id"),
                                UUID.fromString(row.getString("definition_id")),
                                row.getLong("revision"),
                                row.getBoolean("previous_enabled"),
                                row.getBoolean("start_enabled"),
                                row.getString("changed_by"),
                                row.getString("authorized_role"),
                                row.getTimestamp("changed_at").toInstant(),
                                row.getString("reason")));
    }
}
