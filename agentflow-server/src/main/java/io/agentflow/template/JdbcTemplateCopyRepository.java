package io.agentflow.template;


import io.agentflow.mybatis.SqlRows;
import io.agentflow.template.mapper.TemplateCopyRepositoryMapper;

import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/**
 * 出处 JDBC 适配器，连接定义时再次限定同租户，避免副本查询跨租户。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcTemplateCopyRepository implements TemplateCopyRepository {
    private final TemplateCopyRepositoryMapper sqlMapper;

    /** 创建出处仓储。 */
    public JdbcTemplateCopyRepository(TemplateCopyRepositoryMapper sqlMapper) {
        this.sqlMapper = sqlMapper;
    }

    @Override
    public void save(TemplateCopy copy) {
        sqlMapper.save(
                copy.tenantId(),
                copy.definitionId().toString(),
                copy.templateKey(),
                copy.templateVersion(),
                copy.copiedBy(),
                Timestamp.from(copy.copiedAt()));
    }

    @Override
    public List<CopyView> findByTemplate(String tenantId, String templateKey) {
        return SqlRows.map(
                sqlMapper.findByTemplate(tenantId, templateKey),
                row ->
                        new CopyView(
                                UUID.fromString(row.getString("definition_id")),
                                row.getString("process_key"),
                                row.getString("name"),
                                row.getString("status"),
                                row.getLong("version"),
                                row.getLong("revision"),
                                row.getLong("template_version"),
                                row.getString("copied_by"),
                                row.getTimestamp("copied_at").toInstant()));
    }
}
