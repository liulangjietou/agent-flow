package io.agentflow.definition;


import io.agentflow.common.JsonUtil;
import io.agentflow.definition.mapper.DefinitionPublicationRepositoryMapper;
import io.agentflow.mybatis.SqlRows;

import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

/**
 * 发布事实 JDBC 适配器，使用唯一键阻止覆盖，使用复合外键维持租户归属。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcDefinitionPublicationRepository implements DefinitionPublicationRepository {
    private final DefinitionPublicationRepositoryMapper sqlMapper;
    private final JsonUtil json;

    /** 创建发布仓储。 */
    public JdbcDefinitionPublicationRepository(
            DefinitionPublicationRepositoryMapper sqlMapper, JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    @Override
    public void save(DefinitionPublication publication) {
        sqlMapper.save(
                publication.tenantId(),
                publication.definitionId().toString(),
                publication.processKey(),
                publication.definitionVersion(),
                publication.publishedBy(),
                publication.authorizedRole(),
                Timestamp.from(publication.publishedAt()),
                publication.changeNote(),
                json.write(publication.validation()));
    }

    @Override
    public Optional<DefinitionPublication> findByDefinition(String tenantId, UUID definitionId) {
        return SqlRows.map(
                        sqlMapper.findByDefinition(tenantId, definitionId.toString()),
                        row ->
                                new DefinitionPublication(
                                        row.getString("tenant_id"),
                                        UUID.fromString(row.getString("definition_id")),
                                        row.getString("process_key"),
                                        row.getLong("definition_version"),
                                        row.getString("published_by"),
                                        row.getString("authorized_role"),
                                        row.getTimestamp("published_at").toInstant(),
                                        row.getString("change_note"),
                                        json.read(
                                                row.getString("validation_json"),
                                                DefinitionPublication.ValidationSummary.class)))
                .stream()
                .findFirst();
    }
}
