package io.agentflow.organization;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 非空 V95 升级只新增代理存储，数据库拒绝跨租户关系和不完整撤销事实。
 * @author owlzhangfq@gmail.com
 */
class ApprovalProxyMigrationTest {
    @Test
    void preservesExistingBusinessRowsAndHistoryWithoutInventingGrants() {
        var source = new DriverManagerDataSource(
                System.getenv().getOrDefault("AGENTFLOW_PROXY_MIGRATION_URL", "jdbc:h2:mem:proxy-migration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_PROXY_TEST_USER", "sa"),
                System.getenv().getOrDefault("AGENTFLOW_PROXY_TEST_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("95").load().migrate();
        var jdbc = new JdbcTemplate(source);
        String definition = UUID.randomUUID().toString(), foreignDefinition = UUID.randomUUID().toString();
        String principal = UUID.randomUUID().toString(), substitute = UUID.randomUUID().toString(), foreignPerson = UUID.randomUUID().toString();
        for (String tenant : List.of("original", "foreign")) {
            jdbc.update("INSERT INTO organization_directory VALUES (?,1,'admin',CURRENT_TIMESTAMP)", tenant);
            jdbc.update("""
                    INSERT INTO approval_definition(id,tenant_id,process_key,name,version,revision,status,graph_json,start_enabled)
                    VALUES (?,?,'retained','原版本',1,2,'PUBLISHED','{}',FALSE)
                    """, tenant.equals("original") ? definition : foreignDefinition, tenant);
        }
        jdbc.update("INSERT INTO organization_person VALUES ('original',?,'principal','原审批人',TRUE,TRUE,1)", principal);
        jdbc.update("INSERT INTO organization_person VALUES ('original',?,'substitute','代理人',TRUE,TRUE,1)", substitute);
        jdbc.update("INSERT INTO organization_person VALUES ('foreign',?,'foreign','外租户',TRUE,TRUE,1)", foreignPerson);
        String application = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version)
                VALUES (?,'original','RETAINED','retained',1,'principal','原申请','{}','IN_APPROVAL',1,2)
                """, application);
        jdbc.update("""
                INSERT INTO approval_submission_round(tenant_id,application_id,round_no,process_instance_id,definition_version,title,payload_json,submitted_by,submitted_at,status)
                VALUES ('original',?,1,'original-instance',1,'原申请','{}','principal',CURRENT_TIMESTAMP,'IN_APPROVAL')
                """, application);
        jdbc.update("""
                INSERT INTO organization_change(tenant_id,revision,actor,kind,record_id,snapshot_json,occurred_at)
                VALUES ('original',2,'admin','PERSON',?,'{}',CURRENT_TIMESTAMP)
                """, principal);
        var tables = List.of("organization_directory", "organization_person", "organization_change", "approval_definition", "approval_application", "approval_submission_round");
        var before = tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table)).toList();
        var history = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        assertThat(Flyway.configure().dataSource(source).target("96").load().migrate().migrationsExecuted).isEqualTo(1);
        assertThat(tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table)).toList()).usingRecursiveComparison().isEqualTo(before);
        var after = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        assertThat(after.subList(0, history.size())).isEqualTo(history);
        assertThat(after).hasSize(history.size() + 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM organization_approval_proxy", Integer.class)).isZero();
        assertThat(Flyway.configure().dataSource(source).target("96").load().migrate().migrationsExecuted).isZero();

        assertThatThrownBy(() -> insert(jdbc, foreignDefinition, principal, substitute)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(jdbc, definition, principal, foreignPerson)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(jdbc, definition, foreignPerson, substitute)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(jdbc, definition, principal, principal)).isInstanceOf(DataIntegrityViolationException.class);
        String id = insert(jdbc, definition, principal, substitute);
        assertThatThrownBy(() -> jdbc.update("UPDATE organization_approval_proxy SET ends_at=starts_at WHERE id=?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE organization_approval_proxy SET revision=2,revoked_at=CURRENT_TIMESTAMP WHERE id=?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT revision FROM organization_approval_proxy WHERE id=?", Long.class, id)).isEqualTo(1L);
    }

    private String insert(JdbcTemplate jdbc, String definition, String principal, String substitute) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO organization_approval_proxy
                (tenant_id,id,definition_id,principal_id,substitute_id,starts_at,ends_at,reason,created_by,created_at,revision)
                VALUES ('original',?,?,?,?,'2030-01-01 00:00:00+00','2030-01-02 00:00:00+00','休假','admin',CURRENT_TIMESTAMP,1)
                """, id, definition, principal, substitute);
        return id;
    }
}
