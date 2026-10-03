package io.agentflow.finance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * V42 升级保留原审批和借款版本，V43 用租户业务外键、同轮唯一键和调度约束守住凭证边界。
 * @author owlzhangfq@gmail.com
 */
class VoucherOperationMigrationTest {
    @Test void upgradePreservesSourceRowsAndEnforcesVoucherIdentityAndSchedule() {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_VOUCHER_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_VOUCHER_MIGRATION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_VOUCHER_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("42").load().migrate(); var jdbc = new JdbcTemplate(source);
        String application = UUID.randomUUID().toString(), business = UUID.randomUUID().toString(), operation = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id) VALUES(?,'voucher-upgrade','OLD-APP','fixture',1,'alice','旧借款','{}','APPROVED',1,5,'ADVANCE_REQUEST',?)", application, business);
        jdbc.update("INSERT INTO advance_request(id,tenant_id,application_id,employee_id,version,state_json) VALUES(?,'voucher-upgrade',?,'alice',3,'{\"retained\":true}')", business, application);
        jdbc.update("INSERT INTO advance_request_revision(tenant_id,request_id,request_version,actor_id,operation,state_json) VALUES('voucher-upgrade',?,3,'manager','APPROVE','{\"retained\":true}')", business);
        var applications = jdbc.queryForList("SELECT * FROM approval_application ORDER BY id"); var requests = jdbc.queryForList("SELECT * FROM advance_request ORDER BY id");
        var revisions = jdbc.queryForList("SELECT * FROM advance_request_revision ORDER BY request_id,request_version");
        var upgraded = Flyway.configure().dataSource(source).target("43").load(); assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM approval_application ORDER BY id")).isEqualTo(applications);
        assertThat(jdbc.queryForList("SELECT * FROM advance_request ORDER BY id")).isEqualTo(requests);
        assertThat(jdbc.queryForList("SELECT * FROM advance_request_revision ORDER BY request_id,request_version")).isEqualTo(revisions);
        String insert = """
                INSERT INTO voucher_operation(tenant_id,id,business_type,business_id,application_id,round_no,kind,application_version,business_version,
                input_json,command_digest,state_json,version,status,attempts,highest_revision,created_at,updated_at,next_attempt_at)
                VALUES(?,?,'ADVANCE_REQUEST',?,?,1,'EMPLOYEE_ADVANCE',5,3,'{}',?,'{}',1,'QUEUED',0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """;
        assertThatThrownBy(() -> jdbc.update(insert, "foreign", operation, business, application, "a".repeat(64))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "voucher-upgrade", operation, UUID.randomUUID().toString(), application, "a".repeat(64))).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(insert, "voucher-upgrade", operation, business, application, "a".repeat(64));
        assertThatThrownBy(() -> jdbc.update(insert, "voucher-upgrade", UUID.randomUUID().toString(), business, application, "a".repeat(64))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE voucher_operation SET status='POSTING' WHERE tenant_id='voucher-upgrade' AND id=?", operation)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE voucher_operation SET status='POSTED' WHERE tenant_id='voucher-upgrade' AND id=?", operation)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE voucher_operation SET highest_revision=-1 WHERE tenant_id='voucher-upgrade' AND id=?", operation)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO voucher_operation_revision(tenant_id,operation_id,version,state_json) VALUES('voucher-upgrade',?,1,'{}')", operation);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO voucher_operation_revision(tenant_id,operation_id,version,state_json) VALUES('foreign',?,2,'{}')", operation)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO voucher_operation_revision(tenant_id,operation_id,version,state_json) VALUES('voucher-upgrade',?,1,'{}')", operation)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(upgraded.migrate().migrationsExecuted).isZero(); assertThat(upgraded.validateWithResult().validationSuccessful).isTrue();
    }
}
