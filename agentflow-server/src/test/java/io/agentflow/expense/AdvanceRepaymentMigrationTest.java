package io.agentflow.expense;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * V53 升级不改写旧放款，查询和还款必须绑定原租户的已保存修订。
 * @author owlzhangfq@gmail.com
 */
class AdvanceRepaymentMigrationTest {
    @Test void upgradePreservesOldDisbursementsAndEnforcesEvidenceLinksLeasesAndUniqueFunds() {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_REPAYMENT_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_REPAYMENT_MIGRATION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_REPAYMENT_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("53").load().migrate(); var jdbc = new JdbcTemplate(source);
        String tenant = "repayment-upgrade", app = UUID.randomUUID().toString(), business = UUID.randomUUID().toString(), voucher = UUID.randomUUID().toString(), id = UUID.randomUUID().toString();
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
        jdbc.update("INSERT INTO finance_resource(tenant_id,resource_type,id,owner_id,source_reference,version,context_json,state_json) VALUES(?,'ADVANCE',?,'alice','original-bank',1,'{}',?)", tenant, business, "{\"legacy\":true}");
        jdbc.update("INSERT INTO finance_resource_revision(tenant_id,resource_type,resource_id,version,actor_id,operation,state_json) VALUES(?,'ADVANCE',?,1,'fixture','PAYMENT','{}')", tenant, business);
        var oldPayment = jdbc.queryForList("SELECT * FROM payment_operation_revision"); var oldAdvance = jdbc.queryForList("SELECT * FROM finance_resource"); var oldVersions = jdbc.queryForList("SELECT * FROM finance_resource_revision");
        var upgrade = Flyway.configure().dataSource(source).target("54").load(); assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM payment_operation_revision")).isEqualTo(oldPayment); assertThat(jdbc.queryForList("SELECT * FROM finance_resource")).isEqualTo(oldAdvance); assertThat(jdbc.queryForList("SELECT * FROM finance_resource_revision")).isEqualTo(oldVersions);
        String check = UUID.randomUUID().toString(), second = UUID.randomUUID().toString(), legalEntity = UUID.randomUUID().toString();
        String create = "INSERT INTO advance_repayment_check(tenant_id,id,advance_id,payment_id,payment_version,requested_by,receipt_reference,input_json,state_json,version,status,created_at,updated_at) VALUES(?,?,?,?,4,'finance',?,'{}','{}',4,'RECORDED',TIMESTAMP '2026-09-29 12:00:00',TIMESTAMP '2026-09-29 12:01:00')";
        assertThatThrownBy(() -> jdbc.update(create, "foreign", check, business, id, "receipt")).isInstanceOf(DataIntegrityViolationException.class);
        for (String queryId : new String[]{check,second}) {
            jdbc.update(create, tenant, queryId, business, id, "receipt-" + queryId);
            jdbc.update("INSERT INTO advance_repayment_check_revision(tenant_id,check_id,version,state_json) VALUES(?,?,4,'{}')", tenant, queryId);
        }
        for (String mutation : new String[]{"status='RUNNING'", "lease_until=TIMESTAMP '2026-09-29 13:00:00'", "updated_at=TIMESTAMP '2026-09-28 12:00:00'", "payment_version=3"}) {
            assertThatThrownBy(() -> jdbc.update("UPDATE advance_repayment_check SET " + mutation + " WHERE tenant_id=? AND id=?", tenant, check)).isInstanceOf(DataIntegrityViolationException.class);
        }
        String insert = "INSERT INTO advance_repayment(tenant_id,id,advance_id,advance_version,check_id,check_version,legal_entity_id,receipt_reference,channel,transaction_reference,voucher_reference,entry_reference,amount,currency,recorded_by,recorded_at,state_json) VALUES(?,?,?,2,?,4,?,?,'BANK_TRANSFER',?,?,'row-1',25.00,'CNY','finance',TIMESTAMP '2026-09-29 12:01:00','{}')";
        String record = UUID.randomUUID().toString();
        assertThatThrownBy(() -> jdbc.update(insert, tenant, record, business, check, legalEntity, "receipt", "funds", "voucher")).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO finance_resource_revision(tenant_id,resource_type,resource_id,version,actor_id,operation,state_json) VALUES(?,'ADVANCE',?,2,'finance','REPAYMENT_RECORDED','{}')", tenant, business);
        jdbc.update(insert, tenant, record, business, check, legalEntity, "receipt", "funds", "voucher");
        for (String collision : new String[]{"receipt","funds","voucher"}) {
            assertThatThrownBy(() -> jdbc.update(insert, tenant, UUID.randomUUID().toString(), business, second, legalEntity, collision.equals("receipt") ? "receipt" : "other", collision.equals("funds") ? "funds" : "other", collision.equals("voucher") ? "voucher" : "other")).isInstanceOf(DataIntegrityViolationException.class);
        }
        for (String mutation : new String[]{"advance_version=1", "check_version=3", "amount=0", "channel='MANUAL'"}) {
            assertThatThrownBy(() -> jdbc.update("UPDATE advance_repayment SET " + mutation + " WHERE tenant_id=? AND id=?", tenant, record)).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> jdbc.update("DELETE FROM advance_repayment_check_revision WHERE tenant_id=? AND check_id=?", tenant, check)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM finance_resource_revision WHERE tenant_id=? AND resource_id=? AND version=2", tenant, business)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(upgrade.migrate().migrationsExecuted).isZero(); assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
    }
}
