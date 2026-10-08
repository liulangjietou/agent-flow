package io.agentflow.definition;

import static io.agentflow.definition.DefinitionModels.DefinitionDraft;
import static io.agentflow.definition.DefinitionModels.DraftStatus;
import static io.agentflow.definition.DefinitionModels.Graph;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.mapper.DefinitionDraftRepositoryMapper;
import io.agentflow.form.FormSchema;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.notification.NotificationTexts;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 流程定义 JDBC 适配器，所有读取都带租户边界。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcDefinitionDraftRepository implements DefinitionDraftRepository {
    private final DefinitionDraftRepositoryMapper sqlMapper;
    private final JsonUtil jsonUtil;

    /** 创建仓储适配器。 */
    public JdbcDefinitionDraftRepository(
            DefinitionDraftRepositoryMapper sqlMapper, JsonUtil jsonUtil) {
        this.sqlMapper = sqlMapper;
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
            // 已发布或归档的原图不再序列化回写，新增布局缺省值不能改变在审绑定摘要。
            updated =
                    sqlMapper.save(
                            draft.name(),
                            persistedVersion,
                            draft.revision(),
                            draft.status().name(),
                            graphJson,
                            schemaJson,
                            textsJson,
                            draft.startEnabled(),
                            draft.status().name(),
                            draft.tenantId(),
                            draft.id().toString(),
                            expectedRevision);
        } catch (DuplicateKeyException exception) {
            throw new DomainException("CONCURRENCY_CONFLICT", "Definition version already exists");
        }
        if (updated == 0) {
            if (SqlRows.single(sqlMapper.save2(draft.tenantId(), draft.id().toString())) > 0) {
                throw new DomainException(
                        "CONCURRENCY_CONFLICT", "Definition revision has changed");
            }
            if (draft.revision() != 0) {
                throw new DomainException(
                        "CONCURRENCY_CONFLICT",
                        "Definition was deleted while it was being updated");
            }
            try {
                sqlMapper.save3(
                        draft.id().toString(),
                        draft.tenantId(),
                        draft.key(),
                        draft.name(),
                        persistedVersion,
                        draft.revision(),
                        draft.status().name(),
                        graphJson,
                        schemaJson,
                        textsJson,
                        draft.startEnabled());
            } catch (DuplicateKeyException exception) {
                throw new DomainException(
                        "CONCURRENCY_CONFLICT", "Definition version or id already exists");
            }
        }
        return draft;
    }

    @Override
    public long nextVersion(String tenantId, String key) {
        Long next = SqlRows.single(sqlMapper.nextVersion(tenantId, key));
        return next == null ? 1L : next;
    }

    @Override
    public Optional<DefinitionDraft> findById(String tenantId, UUID id) {
        return SqlRows.map(sqlMapper.findById(tenantId, id.toString()), this::map).stream()
                .findFirst();
    }

    @Override
    public Optional<DefinitionDraft> findPublished(String tenantId, String key, long version) {
        return SqlRows.map(sqlMapper.findPublished(tenantId, key, version), this::map).stream()
                .findFirst();
    }

    @Override
    public Optional<DefinitionDraft> lockPublished(String tenantId, String key, long version) {
        return SqlRows.map(sqlMapper.lockPublished(tenantId, key, version), this::map).stream()
                .findFirst();
    }

    @Override
    public List<DefinitionDraft> findAll(String tenantId, String status) {
        if (status == null || status.isBlank()) {
            return SqlRows.map(sqlMapper.findAll(tenantId), this::map);
        }
        return SqlRows.map(
                sqlMapper.findAll2(tenantId, status.toUpperCase(java.util.Locale.ROOT)), this::map);
    }

    private DefinitionDraft map(SqlRow resultSet) {
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
