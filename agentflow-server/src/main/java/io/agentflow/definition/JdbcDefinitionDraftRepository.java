package io.agentflow.definition;

import io.agentflow.common.JsonUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static io.agentflow.definition.DefinitionModels.DefinitionDraft;
import static io.agentflow.definition.DefinitionModels.DraftStatus;
import static io.agentflow.definition.DefinitionModels.Graph;

/** 流程定义 JDBC 适配器，所有读取都带租户边界。 */
@Repository
public class JdbcDefinitionDraftRepository implements DefinitionDraftRepository {
    private final JdbcTemplate jdbcTemplate;
    private final JsonUtil jsonUtil;

    /** 创建仓储适配器。 */
    public JdbcDefinitionDraftRepository(JdbcTemplate jdbcTemplate, JsonUtil jsonUtil) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonUtil = jsonUtil;
    }

    @Override
    public DefinitionDraft save(DefinitionDraft draft) {
        String graphJson = jsonUtil.write(draft.graph());
        int updated = jdbcTemplate.update("""
                UPDATE approval_definition SET name=?, version=?, revision=?, status=?, graph_json=?,
                    published_at=CASE WHEN ?='PUBLISHED' THEN COALESCE(published_at, CURRENT_TIMESTAMP) ELSE published_at END,
                    updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?
                """, draft.name(), draft.version(), draft.revision(), draft.status().name(), graphJson,
                draft.status().name(), draft.tenantId(), draft.id().toString());
        if (updated == 0) {
            jdbcTemplate.update("""
                    INSERT INTO approval_definition (id, tenant_id, process_key, name, version, revision, status, graph_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, draft.id().toString(), draft.tenantId(), draft.key(), draft.name(), draft.version(),
                    draft.revision(), draft.status().name(), graphJson);
        }
        return draft;
    }

    @Override
    public Optional<DefinitionDraft> findById(String tenantId, UUID id) {
        return jdbcTemplate.query("SELECT * FROM approval_definition WHERE tenant_id=? AND id=?",
                this::map, tenantId, id.toString()).stream().findFirst();
    }

    @Override
    public List<DefinitionDraft> findAll(String tenantId, String status) {
        if (status == null || status.isBlank()) {
            return jdbcTemplate.query("SELECT * FROM approval_definition WHERE tenant_id=? ORDER BY updated_at DESC",
                    this::map, tenantId);
        }
        return jdbcTemplate.query("SELECT * FROM approval_definition WHERE tenant_id=? AND status=? ORDER BY updated_at DESC",
                this::map, tenantId, status.toUpperCase(java.util.Locale.ROOT));
    }

    private DefinitionDraft map(ResultSet resultSet, int rowNum) throws SQLException {
        return DefinitionDraft.restore(UUID.fromString(resultSet.getString("id")), resultSet.getString("tenant_id"),
                resultSet.getString("process_key"), resultSet.getString("name"), resultSet.getLong("version"),
                resultSet.getLong("revision"), DraftStatus.valueOf(resultSet.getString("status")),
                jsonUtil.read(resultSet.getString("graph_json"), Graph.class));
    }
}
