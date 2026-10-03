package io.agentflow.finance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * V62 保留原凭证与旧冲销登记，准备、消费版本、命令和原件停用通过精确外键绑定。
 * @author owlzhangfq@gmail.com
 */
class VoucherReversalExecutionMigrationTest {
    @Test void upgradePreservesOriginalBytesAndRequiresSameOriginalTenantAndConsumedPreparation() {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_REVERSAL_EXECUTION_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_REVERSAL_EXECUTION_MIGRATION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_REVERSAL_EXECUTION_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("61").load().migrate(); var jdbc = new JdbcTemplate(source);
        String tenant = "reversal-execution", application = UUID.randomUUID().toString(), business = UUID.randomUUID().toString();
        String original = UUID.randomUUID().toString(), other = UUID.randomUUID().toString(), reversal = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id) VALUES(?,?,'OLD-ORIGINAL','fixture',1,'alice','旧借款','{}','APPROVED',1,5,'ADVANCE_REQUEST',?)", application, tenant, business);
        for (String id : new String[]{original, other}) {
            jdbc.update("""
                    INSERT INTO voucher_operation(tenant_id,id,business_type,business_id,application_id,round_no,kind,application_version,business_version,input_json,command_digest,state_json,version,status,attempts,highest_revision,created_at,updated_at)
                    VALUES(?,?,'ADVANCE_REQUEST',?,?,1,?,5,3,'{}',?,'{"retained":true}',3,'POSTED',1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """, tenant, id, business, application, id.equals(original) ? "EMPLOYEE_ADVANCE" : "PAYMENT", "a".repeat(64));
            jdbc.update("INSERT INTO voucher_operation_revision(tenant_id,operation_id,version,state_json) VALUES(?,?,3,'{" + "\"retained\":true}')", tenant, id);
        }
        var previous = jdbc.queryForList("SELECT id,input_json,state_json,version FROM voucher_operation ORDER BY id");
        var revisions = jdbc.queryForList("SELECT * FROM voucher_operation_revision ORDER BY operation_id");
        var upgrade = Flyway.configure().dataSource(source).target("62").load(); assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT id,input_json,state_json,version FROM voucher_operation ORDER BY id")).isEqualTo(previous);
        assertThat(jdbc.queryForList("SELECT * FROM voucher_operation_revision ORDER BY operation_id")).isEqualTo(revisions);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM voucher_operation WHERE reversal_id IS NOT NULL", Integer.class)).isZero();
        String prepare = "INSERT INTO voucher_reversal_preparation(tenant_id,id,operation_id,original_version,requested_by,input_json,state_json,version,status,created_at,updated_at) VALUES(?,?,?,3,'finance','{}','{}',4,'AUTHORIZED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)";
        assertThatThrownBy(() -> jdbc.update(prepare, "foreign", reversal, original)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(prepare, tenant, reversal, original);
        String execute = """
                INSERT INTO voucher_reversal_operation(tenant_id,id,operation_id,original_version,preparation_version,input_json,command_digest,state_json,version,status,attempts,highest_revision,created_at,updated_at,next_attempt_at)
                VALUES(?,?,?,3,4,'{}',?,'{}',1,'QUEUED',0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """;
        assertThatThrownBy(() -> jdbc.update(execute, tenant, reversal, original, "a".repeat(64))).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO voucher_reversal_preparation_revision(tenant_id,preparation_id,version,state_json) VALUES(?,?,4,'{}')", tenant, reversal);
        jdbc.update(execute, tenant, reversal, original, "a".repeat(64));
        assertThatThrownBy(() -> jdbc.update("UPDATE voucher_operation SET reversal_id=? WHERE tenant_id=? AND id=?", reversal, tenant, other)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE voucher_operation SET reversal_id=? WHERE tenant_id=? AND id=?", reversal, tenant, original);
        assertThatThrownBy(() -> jdbc.update("UPDATE voucher_reversal_operation SET status='POSTING',next_attempt_at=NULL WHERE tenant_id=? AND id=?", tenant, reversal)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM voucher_reversal_preparation_revision WHERE tenant_id=? AND preparation_id=?", tenant, reversal)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM voucher_reversal_operation WHERE tenant_id=? AND id=?", tenant, reversal)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(upgrade.migrate().migrationsExecuted).isZero(); assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
    }
}
