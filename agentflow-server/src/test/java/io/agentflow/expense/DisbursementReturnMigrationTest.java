package io.agentflow.expense;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * V58 升级保留原还款，回填共用入款账本并约束独立银行退回的资金及分录。
 * @author owlzhangfq@gmail.com
 */
class DisbursementReturnMigrationTest {
    @Test void upgradePreservesLegacyRepaymentsAndSharesIncomingCreditUniqueness() {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_DISBURSEMENT_RETURN_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_DISBURSEMENT_RETURN_MIGRATION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_DISBURSEMENT_RETURN_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("58").load().migrate(); var jdbc = new JdbcTemplate(source);
        String tenant = "repayment-review-upgrade", app = UUID.randomUUID().toString(), business = UUID.randomUUID().toString(), voucher = UUID.randomUUID().toString(), id = UUID.randomUUID().toString();
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

        String check = UUID.randomUUID().toString(), repayment = UUID.randomUUID().toString(), entity = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO advance_repayment_check(tenant_id,id,advance_id,payment_id,payment_version,requested_by,receipt_reference,input_json,state_json,version,status,created_at,updated_at) VALUES(?,?,?,?,4,'finance','original-receipt','{}','{}',4,'RECORDED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", tenant, check, business, id);
        jdbc.update("INSERT INTO advance_repayment_check_revision(tenant_id,check_id,version,state_json) VALUES(?,?,4,'{}')", tenant, check);
        jdbc.update("INSERT INTO finance_resource_revision(tenant_id,resource_type,resource_id,version,actor_id,operation,state_json) VALUES(?,'ADVANCE',?,2,'fixture','REPAYMENT','{\"legacyHold\":true}')", tenant, business);
        jdbc.update("INSERT INTO advance_repayment(tenant_id,id,advance_id,advance_version,check_id,check_version,legal_entity_id,receipt_reference,channel,transaction_reference,voucher_reference,entry_reference,amount,currency,recorded_by,recorded_at,state_json) VALUES(?,?,?,2,?,4,?,'original-receipt','CASH','original-funds','original-voucher','credit',25.00,'CNY','finance',CURRENT_TIMESTAMP,'{\"original\":true}')", tenant, repayment, business, check, entity);
        var before = new java.util.LinkedHashMap<String, java.util.List<java.util.Map<String, Object>>>();
        for (String table : java.util.List.of("approval_application", "voucher_operation", "payment_authorization", "payment_operation", "payment_operation_revision", "finance_resource", "finance_resource_revision", "advance_repayment_check", "advance_repayment_check_revision", "advance_repayment")) {
            before.put(table, jdbc.queryForList("SELECT * FROM " + table));
        }
        var upgrade = Flyway.configure().dataSource(source).target("59").load(); assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        before.forEach((table, rows) -> assertThat(jdbc.queryForList("SELECT * FROM " + table)).as(table).containsExactlyInAnyOrderElementsOf(rows));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM advance_receipt_credit WHERE tenant_id=? AND legal_entity_id=? AND advance_id=? AND repayment_id=? AND disbursement_resolution_id IS NULL AND channel='CASH' AND transaction_reference='original-funds' AND voucher_reference='original-voucher' AND entry_reference='credit' AND amount=25.00 AND currency='CNY'", Integer.class, tenant, entity, business, repayment)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM advance_repayment WHERE tenant_id=? AND id=?", tenant, repayment)).isInstanceOf(DataIntegrityViolationException.class);
        String review = UUID.randomUUID().toString(), decision = UUID.randomUUID().toString();
        String query = "INSERT INTO disbursement_return_check(tenant_id,id,advance_id,payment_id,payment_version,requested_by,input_json,state_json,version,status,created_at,updated_at) VALUES(?,?,?,?,4,'finance','{}','{}',4,'RESOLVED',TIMESTAMP '2026-09-29 12:00:00',TIMESTAMP '2026-09-29 12:01:00')";
        assertThatThrownBy(() -> jdbc.update(query, "foreign", review, business, id)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(query, tenant, review, business, id);
        for (String mutation : new String[]{"status='RUNNING'", "lease_until=CURRENT_TIMESTAMP", "updated_at=TIMESTAMP '2026-09-28 12:00:00'", "payment_version=3"}) {
            assertThatThrownBy(() -> jdbc.update("UPDATE disbursement_return_check SET " + mutation + " WHERE tenant_id=? AND id=?", tenant, review)).isInstanceOf(DataIntegrityViolationException.class);
        }
        String decide = "INSERT INTO advance_disbursement_resolution(tenant_id,id,advance_id,advance_version,check_id,check_version,outcome,resolved_by,observed_at,resolved_at,state_json) VALUES(?,?,?,3,?,4,'PARTIALLY_RETURNED','finance',TIMESTAMP '2026-09-29 12:00:00',TIMESTAMP '2026-09-29 12:01:00','{}')";
        assertThatThrownBy(() -> jdbc.update(decide, tenant, decision, business, review)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO disbursement_return_check_revision(tenant_id,check_id,version,state_json) VALUES(?,?,4,'{}')", tenant, review);
        jdbc.update("INSERT INTO finance_resource_revision(tenant_id,resource_type,resource_id,version,actor_id,operation,state_json) VALUES(?,'ADVANCE',?,3,'finance','DISBURSEMENT_RETURN_RESOLVED','{}')", tenant, business);
        jdbc.update(decide, tenant, decision, business, review);
        String credit = "INSERT INTO advance_receipt_credit(tenant_id,legal_entity_id,advance_id,channel,transaction_reference,voucher_reference,entry_reference,amount,currency,disbursement_resolution_id) VALUES(?,?,?,'BANK_TRANSFER',?,?,?,5.00,'CNY',?)";
        jdbc.update(credit, tenant, entity, business, "new-bank-funds", "new-credit", "row-1", decision);
        jdbc.update(credit, tenant, entity, business, "second-bank-funds", "new-credit", "row-2", decision);
        assertThatThrownBy(() -> jdbc.update(credit, tenant, entity, business, "new-bank-funds", "unique-credit", "row-1", decision)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(credit, tenant, entity, business, "unique-funds", "original-voucher", "credit", decision)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(credit, tenant, entity, business, "unique-funds", "new-credit", "row-1", decision)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(credit, tenant, entity, business, "unique-funds", "unique-credit", "row-1", UUID.randomUUID().toString())).isInstanceOf(DataIntegrityViolationException.class);
        for (String mutation : new String[]{"amount=0", "channel='CASH'", "repayment_id='" + repayment + "'", "disbursement_resolution_id=NULL"}) {
            assertThatThrownBy(() -> jdbc.update("UPDATE advance_receipt_credit SET " + mutation + " WHERE tenant_id=? AND disbursement_resolution_id=?", tenant, decision)).isInstanceOf(DataIntegrityViolationException.class);
        }
        for (String mutation : new String[]{"advance_version=1", "check_version=3", "outcome='UNRESOLVED'", "resolved_at=TIMESTAMP '2026-09-28 12:00:00'"}) {
            assertThatThrownBy(() -> jdbc.update("UPDATE advance_disbursement_resolution SET " + mutation + " WHERE tenant_id=? AND id=?", tenant, decision)).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> jdbc.update("DELETE FROM advance_disbursement_resolution WHERE tenant_id=? AND id=?", tenant, decision)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM disbursement_return_check_revision WHERE tenant_id=? AND check_id=?", tenant, review)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(upgrade.migrate().migrationsExecuted).isZero(); assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
    }
}
