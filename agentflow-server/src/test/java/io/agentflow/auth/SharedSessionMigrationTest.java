package io.agentflow.auth;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话迁移只建立空基础设施表；原定义及业务状态逐字段保留。
 * @author owlzhangfq@gmail.com
 */
class SharedSessionMigrationTest {
    @Test
    void upgradePreservesPublishedDefinitionsAndCreatesEmptySessionTables() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:session-migration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).target("20").load().migrate();
        var jdbc = new JdbcTemplate(source);
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO approval_definition(id,tenant_id,process_key,name,version,revision,status,graph_json,form_schema_json)
                VALUES (?, 'demo', 'migration-original', '原审批流程', 1, 2, 'PUBLISHED', ?, ?)
                """, id, "{\"nodes\":[],\"edges\":[]}", "{\"schemaVersion\":1,\"fields\":[]}");
        var before = jdbc.queryForList("SELECT * FROM approval_definition");
        Flyway.configure().dataSource(source).load().migrate();
        var after = jdbc.queryForList("SELECT * FROM approval_definition");
        after.forEach(row -> assertThat(row.remove("NOTIFICATION_TEXTS_JSON")).isNull());
        assertThat(after).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM AF_HTTP_SESSION", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM AF_HTTP_SESSION_ATTRIBUTES", Integer.class)).isZero();
    }
}
