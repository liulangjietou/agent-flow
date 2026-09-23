package io.agentflow.template;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/**
 * 出处 JDBC 适配器，连接定义时再次限定同租户，避免副本查询跨租户。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcTemplateCopyRepository implements TemplateCopyRepository {
    private final JdbcTemplate jdbc;

    /** 创建出处仓储。 */
    public JdbcTemplateCopyRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void save(TemplateCopy copy) {
        jdbc.update("""
                INSERT INTO template_copy (tenant_id,definition_id,template_key,template_version,copied_by,copied_at)
                VALUES (?,?,?,?,?,?)
                """, copy.tenantId(), copy.definitionId().toString(), copy.templateKey(), copy.templateVersion(),
                copy.copiedBy(), Timestamp.from(copy.copiedAt()));
    }

    @Override
    public List<CopyView> findByTemplate(String tenantId, String templateKey) {
        return jdbc.query("""
                SELECT c.definition_id,d.process_key,d.name,d.status,COALESCE(d.version,0) AS version,d.revision,
                       c.template_version,c.copied_by,c.copied_at
                FROM template_copy c JOIN approval_definition d ON d.tenant_id=c.tenant_id AND d.id=c.definition_id
                WHERE c.tenant_id=? AND c.template_key=? ORDER BY c.copied_at DESC,c.definition_id
                """, (row, number) -> new CopyView(UUID.fromString(row.getString("definition_id")), row.getString("process_key"),
                row.getString("name"), row.getString("status"), row.getLong("version"), row.getLong("revision"),
                row.getLong("template_version"), row.getString("copied_by"), row.getTimestamp("copied_at").toInstant()), tenantId, templateKey);
    }
}
