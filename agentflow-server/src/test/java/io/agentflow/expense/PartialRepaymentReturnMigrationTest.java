package io.agentflow.expense;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * V57 升级保留单笔历史，扩充独立退回行并重新验证外键与资金分录防重。
 * @author owlzhangfq@gmail.com
 */
class PartialRepaymentReturnMigrationTest {
    @Test void upgradePreservesOriginalReturnsAndAllowsMultipleProofsWithoutWeakeningIdentity() {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_PARTIAL_RETURN_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_PARTIAL_RETURN_MIGRATION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_PARTIAL_RETURN_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("57").load().migrate(); var jdbc = new JdbcTemplate(source);
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
        String review = UUID.randomUUID().toString(), resolution = UUID.randomUUID().toString();
        String query = "INSERT INTO repayment_review_check(tenant_id,id,advance_id,repayment_id,requested_by,input_json,state_json,version,status,created_at,updated_at) VALUES(?,?,?,?,'finance','{}','{}',4,'RESOLVED',TIMESTAMP '2026-09-29 12:00:00',TIMESTAMP '2026-09-29 12:01:00')";
        assertThatThrownBy(() -> jdbc.update(query, "foreign", review, business, repayment)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(query, tenant, review, business, repayment);
        for (String mutation : new String[]{"status='RUNNING'", "lease_until=CURRENT_TIMESTAMP", "updated_at=TIMESTAMP '2026-09-28 12:00:00'"}) assertThatThrownBy(() -> jdbc.update("UPDATE repayment_review_check SET " + mutation + " WHERE tenant_id=? AND id=?", tenant, review)).isInstanceOf(DataIntegrityViolationException.class);
        String decide = "INSERT INTO advance_repayment_resolution(tenant_id,id,advance_id,advance_version,repayment_id,check_id,check_version,outcome,resolved_by,observed_at,resolved_at,state_json) VALUES(?,?,?,3,?,?,4,'RETURNED','finance',TIMESTAMP '2026-09-29 12:00:00',TIMESTAMP '2026-09-29 12:01:00','{}')";
        assertThatThrownBy(() -> jdbc.update(decide, tenant, resolution, business, repayment, review)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO repayment_review_check_revision(tenant_id,check_id,version,state_json) VALUES(?,?,4,'{}')", tenant, review);
        jdbc.update("INSERT INTO finance_resource_revision(tenant_id,resource_type,resource_id,version,actor_id,operation,state_json) VALUES(?,'ADVANCE',?,3,'finance','REPAYMENT_REVIEW_RESOLVED','{}')", tenant, business);
        jdbc.update(decide, tenant, resolution, business, repayment, review);
        jdbc.update("INSERT INTO advance_repayment_return(tenant_id,repayment_id,resolution_id,legal_entity_id,channel,transaction_reference,voucher_reference,entry_reference,amount,currency) VALUES(?,?,?,?,'CASH','return-funds','return-voucher','debit',25.00,'CNY')", tenant, repayment, resolution, entity);
        for (String mutation : new String[]{"advance_version=1", "check_version=3", "outcome='UNRESOLVED'", "resolved_at=TIMESTAMP '2026-09-28 12:00:00'"}) assertThatThrownBy(() -> jdbc.update("UPDATE advance_repayment_resolution SET " + mutation + " WHERE tenant_id=? AND id=?", tenant, resolution)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM repayment_review_check_revision WHERE tenant_id=? AND check_id=?", tenant, review)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE advance_repayment_return SET amount=0 WHERE tenant_id=? AND repayment_id=?", tenant, repayment)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM advance_repayment_resolution WHERE tenant_id=? AND id=?", tenant, resolution)).isInstanceOf(DataIntegrityViolationException.class);
        var tables = java.util.List.of("finance_resource", "finance_resource_revision", "advance_repayment", "advance_repayment_check_revision", "repayment_review_check", "repayment_review_check_revision", "advance_repayment_resolution", "advance_repayment_return");
        var snapshots = new java.util.LinkedHashMap<String, java.util.List<java.util.Map<String, Object>>>();
        for (String table : tables) snapshots.put(table, jdbc.queryForList("SELECT * FROM " + table));
        var upgrade = Flyway.configure().dataSource(source).target("58").load(); assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        for (String table : tables) assertThat(jdbc.queryForList("SELECT * FROM " + table)).as(table).containsExactlyInAnyOrderElementsOf(snapshots.get(table));
        String returned = "INSERT INTO advance_repayment_return(tenant_id,repayment_id,resolution_id,legal_entity_id,channel,transaction_reference,voucher_reference,entry_reference,amount,currency) VALUES(?,?,?,?,'CASH',?,?,?,1.00,'CNY')";
        // 多行结构与金额累计分工验证：这里核对防重/归属，合法累计金额由领域及业务集成测试核对。
        jdbc.update(returned, tenant, repayment, resolution, entity, "another-funds", "another-voucher", "debit");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM advance_repayment_return WHERE tenant_id=? AND repayment_id=?", Integer.class, tenant, repayment)).isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update(returned, tenant, repayment, resolution, entity, "another-funds", "third-voucher", "debit")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(returned, tenant, repayment, resolution, entity, "third-funds", "another-voucher", "debit")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(returned, tenant, UUID.randomUUID().toString(), resolution, entity, "foreign-funds", "foreign-voucher", "debit")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(returned, tenant, repayment, UUID.randomUUID().toString(), entity, "foreign-funds", "foreign-voucher", "debit")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE advance_repayment_return SET amount=0 WHERE tenant_id=?", tenant)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE advance_repayment_resolution SET outcome='PARTIALLY_RETURNED' WHERE tenant_id=? AND id=?", tenant, resolution);
        assertThatThrownBy(() -> jdbc.update("UPDATE advance_repayment_resolution SET outcome='UNRESOLVED' WHERE tenant_id=?", tenant)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM advance_repayment_resolution WHERE tenant_id=? AND id=?", tenant, resolution)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(upgrade.migrate().migrationsExecuted).isZero(); assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
    }
}
