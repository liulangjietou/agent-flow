package io.agentflow.definition;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/**
 * 版本治理事实的同库适配器，按租户和版本标识读取。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcDefinitionAvailabilityRepository implements DefinitionAvailabilityRepository {
    private final JdbcTemplate jdbc;

    /** 注入业务数据源，参与外层用例事务。 */
    public JdbcDefinitionAvailabilityRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void append(DefinitionAvailabilityChange change) {
        jdbc.update("""
                INSERT INTO definition_availability_change
                (tenant_id,definition_id,revision,previous_enabled,start_enabled,changed_by,authorized_role,changed_at,reason)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, change.tenantId(), change.definitionId().toString(), change.revision(), change.previousEnabled(),
                change.startEnabled(), change.changedBy(), change.authorizedRole(), Timestamp.from(change.changedAt()), change.reason());
    }

    @Override
    public List<DefinitionAvailabilityChange> history(String tenantId, UUID definitionId, long beforeRevision, int limit) {
        return jdbc.query("""
                SELECT * FROM definition_availability_change
                WHERE tenant_id=? AND definition_id=? AND revision<? ORDER BY revision DESC LIMIT ?
                """, (row, index) -> new DefinitionAvailabilityChange(row.getString("tenant_id"),
                UUID.fromString(row.getString("definition_id")), row.getLong("revision"), row.getBoolean("previous_enabled"),
                row.getBoolean("start_enabled"), row.getString("changed_by"), row.getString("authorized_role"),
                row.getTimestamp("changed_at").toInstant(), row.getString("reason")),
                tenantId, definitionId.toString(), beforeRevision, limit + 1);
    }
}
