package io.agentflow.definition;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.form.FormSchema;
import io.agentflow.notification.NotificationTexts;
import org.springframework.dao.DuplicateKeyException;
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

/**
 * 流程定义 JDBC 适配器，所有读取都带租户边界。
 * @author owlzhangfq@gmail.com
 */
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
        String schemaJson = draft.formSchema() == null ? null : jsonUtil.write(draft.formSchema());
        String textsJson = jsonUtil.write(draft.notificationTexts());
        Object persistedVersion = draft.status() == DraftStatus.DRAFT ? null : draft.version();
        long expectedRevision = draft.revision() - 1;
        int updated;
        try {
            updated = jdbcTemplate.update("""
                    UPDATE approval_definition SET name=?, version=?, revision=?, status=?, graph_json=?, form_schema_json=?, notification_texts_json=?, start_enabled=?,
                        published_at=CASE WHEN ?='PUBLISHED' THEN COALESCE(published_at, CURRENT_TIMESTAMP) ELSE published_at END,
                        updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=? AND revision=?
                    """, draft.name(), persistedVersion, draft.revision(), draft.status().name(), graphJson, schemaJson, textsJson, draft.startEnabled(),
                    draft.status().name(), draft.tenantId(), draft.id().toString(), expectedRevision);
        } catch (DuplicateKeyException exception) {
            throw new DomainException("CONCURRENCY_CONFLICT", "Definition version already exists");
        }
        if (updated == 0) {
            if (jdbcTemplate.queryForObject("SELECT COUNT(*) FROM approval_definition WHERE tenant_id=? AND id=?",
                    Integer.class, draft.tenantId(), draft.id().toString()) > 0) {
                throw new DomainException("CONCURRENCY_CONFLICT", "Definition revision has changed");
            }
            if (draft.revision() != 0) {
                throw new DomainException("CONCURRENCY_CONFLICT", "Definition was deleted while it was being updated");
            }
            try {
                jdbcTemplate.update("""
                    INSERT INTO approval_definition (id, tenant_id, process_key, name, version, revision, status, graph_json, form_schema_json, notification_texts_json, start_enabled)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, draft.id().toString(), draft.tenantId(), draft.key(), draft.name(), persistedVersion,
                        draft.revision(), draft.status().name(), graphJson, schemaJson, textsJson, draft.startEnabled());
            } catch (DuplicateKeyException exception) {
                throw new DomainException("CONCURRENCY_CONFLICT", "Definition version or id already exists");
            }
        }
        return draft;
    }

    @Override
    public long nextVersion(String tenantId, String key) {
        Long next = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(version), 0) + 1 FROM approval_definition
                WHERE tenant_id=? AND process_key=? AND status='PUBLISHED'
                """, Long.class, tenantId, key);
        return next == null ? 1L : next;
    }

    @Override
    public Optional<DefinitionDraft> findById(String tenantId, UUID id) {
        return jdbcTemplate.query("SELECT * FROM approval_definition WHERE tenant_id=? AND id=?",
                this::map, tenantId, id.toString()).stream().findFirst();
    }

    @Override
    public Optional<DefinitionDraft> findPublished(String tenantId, String key, long version) {
        return jdbcTemplate.query("SELECT * FROM approval_definition WHERE tenant_id=? AND process_key=? AND version=? AND status='PUBLISHED'",
                this::map, tenantId, key, version).stream().findFirst();
    }

    @Override
    public Optional<DefinitionDraft> lockPublished(String tenantId, String key, long version) {
        return jdbcTemplate.query("SELECT * FROM approval_definition WHERE tenant_id=? AND process_key=? AND version=? AND status='PUBLISHED' FOR UPDATE",
                this::map, tenantId, key, version).stream().findFirst();
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
        Number version = (Number) resultSet.getObject("version");
        return DefinitionDraft.restore(UUID.fromString(resultSet.getString("id")), resultSet.getString("tenant_id"),
                resultSet.getString("process_key"), resultSet.getString("name"), version == null ? 0 : version.longValue(),
                resultSet.getLong("revision"), DraftStatus.valueOf(resultSet.getString("status")),
                jsonUtil.read(resultSet.getString("graph_json"), Graph.class),
                resultSet.getString("form_schema_json") == null ? null : jsonUtil.read(resultSet.getString("form_schema_json"), FormSchema.class),
                resultSet.getString("notification_texts_json") == null ? null
                        : jsonUtil.read(resultSet.getString("notification_texts_json"), NotificationTexts.class), resultSet.getBoolean("start_enabled"));
    }
}
