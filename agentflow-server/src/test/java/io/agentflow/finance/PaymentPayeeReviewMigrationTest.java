package io.agentflow.finance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * V51 升级保持原授权条款原文，复核只能引用同租户原修订并单次绑定新授权。
 * @author owlzhangfq@gmail.com
 */
class PaymentPayeeReviewMigrationTest {
    @Test void upgradePreservesOriginalAuthorizationAndConstrainsReviewLeaseAndSingleConsumption() {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_PAYEE_REVIEW_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_PAYEE_REVIEW_MIGRATION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_PAYEE_REVIEW_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("51").load().migrate(); var jdbc = new JdbcTemplate(source);
        String tenant = "payee-review-upgrade", app = UUID.randomUUID().toString(), business = UUID.randomUUID().toString(), voucher = UUID.randomUUID().toString();
        String original = UUID.randomUUID().toString(), replacement = UUID.randomUUID().toString(), review = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id) VALUES(?,?,'OLD-APP','fixture',1,'alice','原账户批准','{}','APPROVED',1,5,'ADVANCE_REQUEST',?)", app, tenant, business);
        jdbc.update("""
                INSERT INTO voucher_operation(tenant_id,id,business_type,business_id,application_id,round_no,kind,application_version,business_version,input_json,command_digest,state_json,
                version,status,attempts,highest_revision,created_at,updated_at) VALUES(?,?,'ADVANCE_REQUEST',?,?,1,'EMPLOYEE_ADVANCE',5,3,'{}',?,'{}',3,'POSTED',1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, tenant, voucher, business, app, "a".repeat(64));
        jdbc.update("INSERT INTO voucher_operation_revision(tenant_id,operation_id,version,state_json) VALUES(?,?,3,'{}')", tenant, voucher);
        String authorization = """
                INSERT INTO payment_authorization(tenant_id,id,business_type,business_id,application_id,round_no,application_version,business_version,purpose,voucher_operation_id,voucher_kind,
                terms_json,decision_json,state_json,version,status,active_business_id,authorized_at,expires_at,updated_at)
                VALUES(?,?,'ADVANCE_REQUEST',?,?,1,5,3,'EMPLOYEE_ADVANCE',?,'EMPLOYEE_ADVANCE','{"oldTerms":true}','{}','{}',?,?,?,TIMESTAMP '2026-09-28 12:00:00',TIMESTAMP '2026-09-28 13:00:00',TIMESTAMP '2026-09-28 12:01:00')
                """;
        jdbc.update(authorization, tenant, original, business, app, voucher, 2, "VOIDED", null);
        jdbc.update("INSERT INTO payment_authorization_revision(tenant_id,authorization_id,version,state_json) VALUES(?,?,2,'{\"original\":true}')", tenant, original);
        var before = jdbc.queryForList("SELECT * FROM payment_authorization"); var history = jdbc.queryForList("SELECT * FROM payment_authorization_revision");
        var upgrade = Flyway.configure().dataSource(source).target("52").load(); assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM payment_authorization")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM payment_authorization_revision")).isEqualTo(history);
        String insert = """
                INSERT INTO payment_payee_review(tenant_id,id,original_authorization_id,original_authorization_version,voucher_operation_id,voucher_version,requested_by,
                input_json,state_json,version,status,attempts,created_at,updated_at) VALUES(?,?,?,2,?,?,'finance','{}','{}',1,'QUEUED',0,TIMESTAMP '2026-09-29 00:00:00',TIMESTAMP '2026-09-29 00:00:00')
                """;
        assertThatThrownBy(() -> jdbc.update(insert, "foreign", review, original, voucher, 3)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, tenant, review, original, voucher, 2)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(insert, tenant, review, original, voucher, 3);
        for (String change : new String[]{"status='RUNNING'", "status='READY'", "status='CONSUMED'", "lease_until=CURRENT_TIMESTAMP", "original_authorization_version=1", "voucher_version=2", "status='VOIDED'", "checked_at=CURRENT_TIMESTAMP"}) {
            assertThatThrownBy(() -> jdbc.update("UPDATE payment_payee_review SET " + change + " WHERE tenant_id=? AND id=?", tenant, review)).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> jdbc.update("DELETE FROM payment_authorization_revision WHERE tenant_id=? AND authorization_id=?", tenant, original)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM voucher_operation_revision WHERE tenant_id=? AND operation_id=?", tenant, voucher)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(authorization, tenant, replacement, business, app, voucher, 1, "AUTHORIZED", business);
        String consume = "UPDATE payment_payee_review SET status='CONSUMED',attempts=1,checked_at=created_at,valid_until=TIMESTAMP '2026-09-29 00:05:00',consumed_authorization_id=? WHERE tenant_id=? AND id=?";
        assertThatThrownBy(() -> jdbc.update(consume, original, tenant, review)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(consume, UUID.randomUUID().toString(), tenant, review)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(consume, replacement, tenant, review);
        String second = UUID.randomUUID().toString(); jdbc.update(insert, tenant, second, original, voucher, 3);
        assertThatThrownBy(() -> jdbc.update(consume, replacement, tenant, second)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(upgrade.migrate().migrationsExecuted).isZero(); assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
    }
}
