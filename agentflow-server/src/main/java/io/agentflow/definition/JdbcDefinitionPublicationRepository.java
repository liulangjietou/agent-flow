package io.agentflow.definition;

import io.agentflow.common.JsonUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

/**
 * 发布事实 JDBC 适配器，使用唯一键阻止覆盖，使用复合外键维持租户归属。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcDefinitionPublicationRepository implements DefinitionPublicationRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;

    /** 创建发布仓储。 */
    public JdbcDefinitionPublicationRepository(JdbcTemplate jdbc, JsonUtil json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void save(DefinitionPublication publication) {
        jdbc.update("""
                INSERT INTO definition_publication
                    (tenant_id, definition_id, process_key, definition_version, published_by, authorized_role,
                     published_at, change_note, validation_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, publication.tenantId(), publication.definitionId().toString(), publication.processKey(),
                publication.definitionVersion(), publication.publishedBy(), publication.authorizedRole(),
                Timestamp.from(publication.publishedAt()), publication.changeNote(), json.write(publication.validation()));
    }

    @Override
    public Optional<DefinitionPublication> findByDefinition(String tenantId, UUID definitionId) {
        return jdbc.query("SELECT * FROM definition_publication WHERE tenant_id=? AND definition_id=?", (row, index) ->
                new DefinitionPublication(row.getString("tenant_id"), UUID.fromString(row.getString("definition_id")),
                        row.getString("process_key"), row.getLong("definition_version"), row.getString("published_by"),
                        row.getString("authorized_role"), row.getTimestamp("published_at").toInstant(), row.getString("change_note"),
                        json.read(row.getString("validation_json"), DefinitionPublication.ValidationSummary.class)),
                tenantId, definitionId.toString()).stream().findFirst();
    }
}
