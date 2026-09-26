package io.agentflow.template;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V7 升级只增加出处结构，原定义逐字段保留，外键拒绝跨租户绑定。
 * @author owlzhangfq@gmail.com
 */
class TemplateCopyMigrationTest {
    @Test
    void preservesOldDefinitionsAndRejectsForeignTenantProvenance() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:template-migration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).target("7").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO approval_definition (id,tenant_id,process_key,name,version,revision,status,graph_json,form_schema_json)
                VALUES (?, 'demo', 'legacy', '原定义', 1, 4, 'PUBLISHED', ?, ?)
                """, id, "{\"nodes\":[],\"edges\":[]}", "{\"schemaVersion\":1,\"fields\":[]}");
        var before = jdbc.queryForMap("SELECT * FROM approval_definition WHERE id=?", id);

        Flyway.configure().dataSource(dataSource).load().migrate();

        var after = jdbc.queryForMap("SELECT * FROM approval_definition WHERE id=?", id);
        assertThat(after.remove("NOTIFICATION_TEXTS_JSON")).isNull();
        assertThat(after).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM template_copy", Integer.class)).isZero();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO template_copy (definition_id,tenant_id,template_key,template_version,copied_by,copied_at)
                VALUES (?, 'foreign', 'leave-request', 1, 'admin', CURRENT_TIMESTAMP)
                """, id)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("""
                INSERT INTO template_copy (definition_id,tenant_id,template_key,template_version,copied_by,copied_at)
                VALUES (?, 'demo', 'leave-request', 1, 'admin', CURRENT_TIMESTAMP)
                """, id);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO template_copy (definition_id,tenant_id,template_key,template_version,copied_by,copied_at)
                VALUES (?, 'demo', 'contract-review', 1, 'admin', CURRENT_TIMESTAMP)
                """, id)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
