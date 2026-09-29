package io.agentflow.finance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * V50 至 V51 保留原付款及历史修订，新增结束证据必须关联同租户的真实执行版本。
 * @author owlzhangfq@gmail.com
 */
class PaymentRetirementMigrationTest {
    @Test void upgradePreservesOldTransactionsAndConstrainsRetirementProofAndBusinessOccupation() {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_PAYMENT_RETIREMENT_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_PAYMENT_RETIREMENT_MIGRATION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_PAYMENT_RETIREMENT_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("50").load().migrate(); var jdbc = new JdbcTemplate(source);
        String app = UUID.randomUUID().toString(), business = UUID.randomUUID().toString(), voucher = UUID.randomUUID().toString(), id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id) VALUES(?,'retirement-upgrade','OLD-APP','fixture',1,'alice','旧付款','{}','APPROVED',1,5,'ADVANCE_REQUEST',?)", app, business);
        jdbc.update("""
                INSERT INTO voucher_operation(tenant_id,id,business_type,business_id,application_id,round_no,kind,application_version,business_version,input_json,command_digest,state_json,
                version,status,attempts,highest_revision,created_at,updated_at) VALUES('retirement-upgrade',?,'ADVANCE_REQUEST',?,?,1,'EMPLOYEE_ADVANCE',5,3,'{}',?,'{}',3,'POSTED',1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, voucher, business, app, "a".repeat(64));
        String insert = """
                INSERT INTO payment_authorization(tenant_id,id,business_type,business_id,application_id,round_no,application_version,business_version,purpose,voucher_operation_id,voucher_kind,
                terms_json,decision_json,state_json,version,status,active_business_id,authorized_at,expires_at,updated_at)
                VALUES('retirement-upgrade',?,'ADVANCE_REQUEST',?,?,1,5,3,'EMPLOYEE_ADVANCE',?,'EMPLOYEE_ADVANCE','{}','{}','{"retained":true}',2,'EXECUTION_REGISTERED',?,TIMESTAMP '2026-09-28 12:00:00',TIMESTAMP '2026-09-28 13:00:00',TIMESTAMP '2026-09-28 12:01:00')
                """;
        jdbc.update(insert, id, business, app, voucher, business);
        jdbc.update("INSERT INTO payment_authorization_revision(tenant_id,authorization_id,version,state_json) VALUES('retirement-upgrade',?,2,'{\"original\":true}')", id);
        jdbc.update("INSERT INTO payment_operation(tenant_id,id,input_json,command_digest,state_json,version,status,attempts,dispatches,highest_revision,created_at,updated_at) VALUES('retirement-upgrade',?,'{}',?,'{\"failed\":true}',4,'FAILED',1,1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", id, "a".repeat(64));
        jdbc.update("INSERT INTO payment_operation_revision(tenant_id,operation_id,version,state_json) VALUES('retirement-upgrade',?,4,'{\"failed\":true}')", id);
        var before = jdbc.queryForList("SELECT * FROM payment_authorization"); var operations = jdbc.queryForList("SELECT * FROM payment_operation");
        var history = jdbc.queryForList("SELECT * FROM payment_authorization_revision");
        var upgrade = Flyway.configure().dataSource(source).target("51").load(); assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM payment_authorization")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM payment_operation")).isEqualTo(operations);
        assertThat(jdbc.queryForList("SELECT * FROM payment_authorization_revision")).isEqualTo(history);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID().toString(), business, app, voucher, business)).isInstanceOf(DataIntegrityViolationException.class);
        for (String change : new String[]{"status='RETIRED'", "version=3", "active_business_id=NULL", "application_version=0", "business_version=0", "status='UNRECOGNIZED'", "version=NULL", "status=NULL"}) {
            assertThatThrownBy(() -> jdbc.update("UPDATE payment_authorization SET " + change + " WHERE tenant_id='retirement-upgrade' AND id=?", id)).isInstanceOf(DataIntegrityViolationException.class);
        }
        jdbc.update("UPDATE payment_authorization SET status='RETIRED',version=3,active_business_id=NULL WHERE tenant_id='retirement-upgrade' AND id=?", id);
        jdbc.update("INSERT INTO payment_authorization_revision(tenant_id,authorization_id,version,state_json) VALUES('retirement-upgrade',?,3,'{\"retired\":true}')", id);
        String proof = "INSERT INTO payment_retirement(tenant_id,authorization_id,authorization_version,operation_version,basis,retired_by,retired_at,retirement_json) VALUES(?,?,3,?,'CONFIRMED_FAILED','finance',CURRENT_TIMESTAMP,'{}')";
        assertThatThrownBy(() -> jdbc.update(proof, "foreign", id, 4)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(proof, "retirement-upgrade", id, 3)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(proof, "retirement-upgrade", id, 4);
        assertThatThrownBy(() -> jdbc.update(proof, "retirement-upgrade", id, 4)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM payment_operation_revision WHERE tenant_id='retirement-upgrade' AND operation_id=?", id)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM payment_authorization_revision WHERE tenant_id='retirement-upgrade' AND authorization_id=? AND version=3", id)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(insert, UUID.randomUUID().toString(), business, app, voucher, business);
        assertThat(upgrade.migrate().migrationsExecuted).isZero(); assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
    }
}
