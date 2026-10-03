package io.agentflow.finance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * V52 升级保持原付款及冲突修订，裁决只能引用同租户连续的前后两份修订。
 * @author owlzhangfq@gmail.com
 */
class PaymentDisputeMigrationTest {
    @Test void upgradePreservesOriginalConflictAndConstrainsTenantVersionsUniquenessAndTime() {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_PAYMENT_DISPUTE_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_PAYMENT_DISPUTE_MIGRATION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_PAYMENT_DISPUTE_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("52").load().migrate(); var jdbc = new JdbcTemplate(source);
        String tenant = "dispute-upgrade", app = UUID.randomUUID().toString(), business = UUID.randomUUID().toString(), voucher = UUID.randomUUID().toString(), id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id) VALUES(?,?,'OLD-APP','fixture',1,'alice','旧付款争议','{}','APPROVED',1,5,'ADVANCE_REQUEST',?)", app, tenant, business);
        jdbc.update("""
                INSERT INTO voucher_operation(tenant_id,id,business_type,business_id,application_id,round_no,kind,application_version,business_version,input_json,command_digest,state_json,
                version,status,attempts,highest_revision,created_at,updated_at) VALUES(?,?,'ADVANCE_REQUEST',?,?,1,'EMPLOYEE_ADVANCE',5,3,'{}',?,'{}',3,'POSTED',1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, tenant, voucher, business, app, "a".repeat(64));
        jdbc.update("""
                INSERT INTO payment_authorization(tenant_id,id,business_type,business_id,application_id,round_no,application_version,business_version,purpose,voucher_operation_id,voucher_kind,
                terms_json,decision_json,state_json,version,status,active_business_id,authorized_at,expires_at,updated_at)
                VALUES(?,?,'ADVANCE_REQUEST',?,?,1,5,3,'EMPLOYEE_ADVANCE',?,'EMPLOYEE_ADVANCE','{}','{}','{}',2,'EXECUTION_REGISTERED',?,TIMESTAMP '2026-09-28 12:00:00',TIMESTAMP '2026-09-28 13:00:00',TIMESTAMP '2026-09-28 12:01:00')
                """, tenant, id, business, app, voucher, business);
        jdbc.update("INSERT INTO payment_operation(tenant_id,id,input_json,command_digest,state_json,version,status,attempts,dispatches,highest_revision,created_at,updated_at) VALUES(?,?,'{}',?,'{\"disputed\":true}',4,'RECONCILING',1,1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", tenant, id, "a".repeat(64));
        jdbc.update("INSERT INTO payment_operation_revision(tenant_id,operation_id,version,state_json) VALUES(?,?,4,'{\"originalConflict\":true}')", tenant, id);
        var before = jdbc.queryForList("SELECT * FROM payment_operation"); var history = jdbc.queryForList("SELECT * FROM payment_operation_revision");
        var upgrade = Flyway.configure().dataSource(source).target("53").load(); assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM payment_operation")).isEqualTo(before); assertThat(jdbc.queryForList("SELECT * FROM payment_operation_revision")).isEqualTo(history);
        String insert = "INSERT INTO payment_dispute_resolution(tenant_id,id,payment_id,disputed_version,resolved_version,outcome,resolved_by,observed_at,resolved_at,state_json) VALUES(?,?,?,4,5,'SUCCEEDED','finance',TIMESTAMP '2026-09-29 00:00:00',TIMESTAMP '2026-09-29 00:00:01','{}')";
        assertThatThrownBy(() -> jdbc.update(insert, tenant, UUID.randomUUID().toString(), id)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO payment_operation_revision(tenant_id,operation_id,version,state_json) VALUES(?,?,5,'{\"resolved\":true}')", tenant, id);
        assertThatThrownBy(() -> jdbc.update(insert, "foreign", UUID.randomUUID().toString(), id)).isInstanceOf(DataIntegrityViolationException.class);
        String decision = UUID.randomUUID().toString(); jdbc.update(insert, tenant, decision, id);
        assertThatThrownBy(() -> jdbc.update(insert, tenant, UUID.randomUUID().toString(), id)).isInstanceOf(DataIntegrityViolationException.class);
        for (String change : new String[]{"resolved_version=6", "disputed_version=3", "outcome='PENDING'", "resolved_at=TIMESTAMP '2026-09-28 00:00:00'"}) {
            assertThatThrownBy(() -> jdbc.update("UPDATE payment_dispute_resolution SET " + change + " WHERE tenant_id=? AND id=?", tenant, decision)).isInstanceOf(DataIntegrityViolationException.class);
        }
        for (int version : new int[]{4,5}) assertThatThrownBy(() -> jdbc.update("DELETE FROM payment_operation_revision WHERE tenant_id=? AND operation_id=? AND version=?", tenant, id, version)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(upgrade.migrate().migrationsExecuted).isZero(); assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
    }
}
