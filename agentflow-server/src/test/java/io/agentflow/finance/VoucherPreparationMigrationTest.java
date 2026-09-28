package io.agentflow.finance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * V43 原凭证及修订在 V44 升级中保留，新增准备必须绑定原租户、轮次及同编号凭证。
 * @author owlzhangfq@gmail.com
 */
class VoucherPreparationMigrationTest {
    @Test void upgradePreservesVoucherFactsAndRejectsInvalidActiveOrReadyBindings() {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_PREPARATION_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_PREPARATION_MIGRATION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_PREPARATION_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("43").load().migrate(); var jdbc = new JdbcTemplate(source);
        String app = UUID.randomUUID().toString(), business = UUID.randomUUID().toString(), operation = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id) VALUES(?,'preparation-upgrade','APP-1','fixture',1,'alice','旧批准','{}','APPROVED',1,5,'ADVANCE_REQUEST',?)", app, business);
        jdbc.update("""
                INSERT INTO voucher_operation(tenant_id,id,business_type,business_id,application_id,round_no,kind,application_version,business_version,
                input_json,command_digest,state_json,version,status,attempts,highest_revision,created_at,updated_at,next_attempt_at)
                VALUES('preparation-upgrade',?,'ADVANCE_REQUEST',?,?,1,'EMPLOYEE_ADVANCE',5,3,'{}',?,'{"retained":true}',1,'QUEUED',0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, operation, business, app, "a".repeat(64));
        jdbc.update("INSERT INTO voucher_operation_revision(tenant_id,operation_id,version,state_json) VALUES('preparation-upgrade',?,1,'{\"retained\":true}')", operation);
        var vouchers = jdbc.queryForList("SELECT * FROM voucher_operation ORDER BY id"); var revisions = jdbc.queryForList("SELECT * FROM voucher_operation_revision ORDER BY operation_id,version");
        var upgraded = Flyway.configure().dataSource(source).target("44").load(); assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM voucher_operation ORDER BY id")).isEqualTo(vouchers);
        assertThat(jdbc.queryForList("SELECT * FROM voucher_operation_revision ORDER BY operation_id,version")).isEqualTo(revisions);
        String insert = """
                INSERT INTO voucher_preparation(tenant_id,id,business_type,business_id,application_id,round_no,kind,application_version,business_version,
                employee_id,attempt_no,input_json,state_json,version,status,active_application_id,created_at)
                VALUES(?,?,'ADVANCE_REQUEST',?,?,1,?,5,3,'alice',?,'{}','{}',1,'QUEUED',?,CURRENT_TIMESTAMP)
                """;
        assertThatThrownBy(() -> jdbc.update(insert, "foreign", operation, business, app, "EMPLOYEE_ADVANCE", 1, app)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "preparation-upgrade", operation, business, app, "EXPENSE_ACCRUAL", 1, app)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "preparation-upgrade", operation, business, app, "EMPLOYEE_ADVANCE", 1, null)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(insert, "preparation-upgrade", operation, business, app, "EMPLOYEE_ADVANCE", 1, app);
        assertThatThrownBy(() -> jdbc.update(insert, "preparation-upgrade", UUID.randomUUID().toString(), business, app, "EMPLOYEE_ADVANCE", 2, app)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE voucher_preparation SET status='READY' WHERE tenant_id='preparation-upgrade' AND id=?", operation)).isInstanceOf(DataIntegrityViolationException.class);
        String ready = "UPDATE voucher_preparation SET status='READY',version=3,active_application_id=NULL,operation_id=?,started_at=CURRENT_TIMESTAMP,lease_until=CURRENT_TIMESTAMP,completed_at=CURRENT_TIMESTAMP WHERE tenant_id='preparation-upgrade' AND id=?";
        assertThatThrownBy(() -> jdbc.update(ready, UUID.randomUUID().toString(), operation)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(ready, operation, operation);
        String second = UUID.randomUUID().toString(); jdbc.update(insert, "preparation-upgrade", second, business, app, "EMPLOYEE_ADVANCE", 2, app);
        assertThatThrownBy(() -> jdbc.update(ready, second, second)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO voucher_preparation_revision(tenant_id,preparation_id,version,state_json) VALUES('preparation-upgrade',?,1,'{}')", operation);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO voucher_preparation_revision(tenant_id,preparation_id,version,state_json) VALUES('foreign',?,2,'{}')", operation)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(upgraded.migrate().migrationsExecuted).isZero(); assertThat(upgraded.validateWithResult().validationSuccessful).isTrue();
    }
}
