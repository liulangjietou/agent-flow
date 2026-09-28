package io.agentflow.organization;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 组织及任职上下文迁移保留旧业务事实，跨租户关系由数据库约束拒绝。
 * @author owlzhangfq@gmail.com
 */
class OrganizationMigrationTest {
    @Test
    void upgradesV25WithoutRewritingExistingDataAndRejectsCrossTenantReferences() {
        var source = new DriverManagerDataSource(
                System.getProperty("agentflow.organization-migration.jdbc-url", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getProperty("agentflow.organization-migration.jdbc-user", "sa"),
                System.getProperty("agentflow.organization-migration.jdbc-password", ""));
        Flyway.configure().dataSource(source).target("25").load().migrate();
        var jdbc = new JdbcTemplate(source);
        String definition = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO approval_definition(id,tenant_id,process_key,name,version,revision,status,graph_json,start_enabled)
                VALUES(?,'legacy','retained','保留版本',1,2,'PUBLISHED','{}',FALSE)
                """, definition);
        jdbc.update("""
                INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version)
                VALUES(?,'legacy','RETAINED','retained',1,'person','旧申请','{}','RETURNED',1,3)
                """, UUID.randomUUID().toString());
        String session = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO AF_HTTP_SESSION VALUES (?,?,?,?,?,?,?)", session, UUID.randomUUID().toString(), 100L, 200L, 1800, 1800200L, "person");
        jdbc.update("INSERT INTO AF_HTTP_SESSION_ATTRIBUTES VALUES (?,?,?)", session, "identity", new byte[]{1, 2, 3});
        var tables = List.of("approval_definition", "approval_application", "AF_HTTP_SESSION", "AF_HTTP_SESSION_ATTRIBUTES");
        var before = tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table)).toList();
        var history = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");

        assertThat(Flyway.configure().dataSource(source).target("26").load().migrate().migrationsExecuted).isEqualTo(1);
        assertThat(tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table)).toList()).usingRecursiveComparison().isEqualTo(before);
        var after = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        assertThat(after).hasSize(history.size() + 1);
        assertThat(after.subList(0, history.size())).isEqualTo(history);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM organization_directory", Integer.class)).isZero();
        assertThat(Flyway.configure().dataSource(source).target("26").load().migrate().migrationsExecuted).isZero();

        for (String tenant : List.of("one", "two")) jdbc.update("INSERT INTO organization_directory VALUES (?,1,'admin',CURRENT_TIMESTAMP)", tenant);
        String legal = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO organization_unit VALUES ('one',?,'LEGAL_ENTITY','法人',NULL,NULL,TRUE,1)", legal);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO organization_unit VALUES ('two',?,'DEPARTMENT','跨租户',?,NULL,TRUE,1)", UUID.randomUUID().toString(), legal))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM organization_unit WHERE tenant_id='two'", Integer.class)).isZero();
    }
    @Test
    void upgradesV26PreservingAppointmentsAndAddingNoInventedHistoricalContext() {
        var source = new DriverManagerDataSource(
                System.getProperty("agentflow.context-migration.jdbc-url", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getProperty("agentflow.context-migration.jdbc-user", "sa"),
                System.getProperty("agentflow.context-migration.jdbc-password", ""));
        Flyway.configure().dataSource(source).target("26").load().migrate();
        var jdbc = new JdbcTemplate(source);
        String legal = UUID.randomUUID().toString(), department = UUID.randomUUID().toString(), position = UUID.randomUUID().toString();
        String person = UUID.randomUUID().toString(), appointment = UUID.randomUUID().toString(), application = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO organization_directory VALUES ('retained',1,'admin',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO organization_unit VALUES ('retained',?,'LEGAL_ENTITY','法人',NULL,NULL,TRUE,1)", legal);
        jdbc.update("INSERT INTO organization_unit VALUES ('retained',?,'DEPARTMENT','部门',?,NULL,TRUE,1)", department, legal);
        jdbc.update("INSERT INTO organization_unit VALUES ('retained',?,'POSITION','岗位',?,NULL,TRUE,1)", position, legal);
        jdbc.update("INSERT INTO organization_person VALUES ('retained',?,'subject','原人员',TRUE,TRUE,1)", person);
        jdbc.update("INSERT INTO organization_appointment VALUES ('retained',?,?,?,?,TRUE,1)", appointment, person, department, position);
        jdbc.update("""
                INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version)
                VALUES(?,'retained','OLD','legacy',1,'subject','原文','{}','IN_APPROVAL',1,2)
                """, application);
        jdbc.update("""
                INSERT INTO approval_submission_round(tenant_id,application_id,round_no,process_instance_id,definition_version,title,payload_json,submitted_by,submitted_at,status)
                VALUES('retained',?,1,'original-instance',1,'原文','{}','subject',CURRENT_TIMESTAMP,'IN_APPROVAL')
                """, application);
        var before = jdbc.queryForMap("SELECT * FROM organization_appointment WHERE id=?", appointment);
        var unitsBefore = jdbc.queryForList("SELECT * FROM organization_unit ORDER BY id");
        var applicationBefore = jdbc.queryForMap("SELECT * FROM approval_application WHERE id=?", application);
        var roundBefore = jdbc.queryForMap("SELECT * FROM approval_submission_round WHERE application_id=?", application);
        var historyBefore = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        assertThat(Flyway.configure().dataSource(source).target("28").load().migrate().migrationsExecuted).isEqualTo(2);
        var after = jdbc.queryForMap("SELECT * FROM organization_appointment WHERE id=?", appointment);
        assertThat(after.remove("SUPERVISOR_APPOINTMENT_ID")).isNull();
        assertThat(after).isEqualTo(before);
        var unitsAfter = jdbc.queryForList("SELECT * FROM organization_unit ORDER BY id");
        unitsAfter.forEach(unit -> assertThat(unit.remove("HEAD_APPOINTMENT_ID")).isNull());
        assertThat(unitsAfter).isEqualTo(unitsBefore);
        assertThat(jdbc.queryForMap("SELECT * FROM approval_application WHERE id=?", application)).isEqualTo(applicationBefore);
        var roundAfter = jdbc.queryForMap("SELECT * FROM approval_submission_round WHERE application_id=?", application);
        assertThat(roundAfter.remove("INITIATOR_CONTEXT_JSON")).isNull();
        assertThat(roundAfter).isEqualTo(roundBefore);
        var historyAfter = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        assertThat(historyAfter).hasSize(historyBefore.size() + 2);
        assertThat(historyAfter.subList(0, historyBefore.size())).isEqualTo(historyBefore);
        assertThat(Flyway.configure().dataSource(source).target("28").load().migrate().migrationsExecuted).isZero();

        // 目标任职确实存在于另一租户，两条新关系都不能借用该标识。
        String foreignAppointment = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO organization_directory VALUES ('foreign',1,'admin',CURRENT_TIMESTAMP)");
        for (String unitId : List.of(legal, department, position)) jdbc.update("""
                INSERT INTO organization_unit(tenant_id,id,kind,name,legal_entity_id,parent_department_id,active,revision)
                SELECT 'foreign',id,kind,name,legal_entity_id,parent_department_id,active,revision FROM organization_unit WHERE tenant_id='retained' AND id=?
                """, unitId);
        jdbc.update("""
                INSERT INTO organization_person(tenant_id,id,subject,display_name,active,approval_eligible,revision)
                SELECT 'foreign',id,subject,display_name,active,approval_eligible,revision FROM organization_person WHERE tenant_id='retained'
                """);
        jdbc.update("""
                INSERT INTO organization_appointment(tenant_id,id,person_id,department_id,position_id,active,revision)
                SELECT 'foreign',?,person_id,department_id,position_id,active,revision FROM organization_appointment WHERE tenant_id='retained'
                """, foreignAppointment);
        assertThatThrownBy(() -> jdbc.update("UPDATE organization_appointment SET supervisor_appointment_id=? WHERE tenant_id='retained' AND id=?", foreignAppointment, appointment))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE organization_unit SET head_appointment_id=? WHERE tenant_id='retained' AND id=?", foreignAppointment, department))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT supervisor_appointment_id FROM organization_appointment WHERE tenant_id='retained' AND id=?", String.class, appointment)).isNull();
        assertThat(jdbc.queryForObject("SELECT head_appointment_id FROM organization_unit WHERE tenant_id='retained' AND id=?", String.class, department)).isNull();
    }

}
