package io.agentflow.finance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * V49 替换旧类型约束但保留全部准备内容，付款准备必须绑定同轮真实支付修订。
 * @author owlzhangfq@gmail.com
 */
class PaymentVoucherMigrationTest {
    @Test void upgradeRetainsOldPreparationsAndRejectsMissingOrForeignPaymentEvidence() {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_PAYMENT_VOUCHER_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_PAYMENT_VOUCHER_MIGRATION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_PAYMENT_VOUCHER_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("48").load().migrate(); var jdbc = new JdbcTemplate(source);
        String application = UUID.randomUUID().toString(), business = UUID.randomUUID().toString(), voucher = UUID.randomUUID().toString(), payment = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id) VALUES(?,'payment-voucher-upgrade','OLD-APP','fixture',1,'alice','旧借款','{}','APPROVED',1,5,'ADVANCE_REQUEST',?)", application, business);
        jdbc.update("""
                INSERT INTO voucher_operation(tenant_id,id,business_type,business_id,application_id,round_no,kind,application_version,business_version,
                input_json,command_digest,state_json,version,status,attempts,highest_revision,created_at,updated_at)
                VALUES('payment-voucher-upgrade',?,'ADVANCE_REQUEST',?,?,1,'EMPLOYEE_ADVANCE',5,3,'{}',?,'{"retained":true}',3,'POSTED',1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, voucher, business, application, "a".repeat(64));
        jdbc.update("""
                INSERT INTO payment_authorization(tenant_id,id,business_type,business_id,application_id,round_no,application_version,business_version,
                purpose,voucher_operation_id,voucher_kind,terms_json,decision_json,state_json,version,status,active_business_id,authorized_at,expires_at,updated_at)
                VALUES('payment-voucher-upgrade',?,'ADVANCE_REQUEST',?,?,1,5,3,'EMPLOYEE_ADVANCE',?,'EMPLOYEE_ADVANCE','{}','{}','{}',2,'EXECUTION_REGISTERED',?,
                '2026-09-28 12:00:00+00','2026-09-28 12:05:00+00','2026-09-28 12:01:00+00')
                """, payment, business, application, voucher, business);
        jdbc.update("""
                INSERT INTO payment_operation(tenant_id,id,input_json,command_digest,state_json,version,status,attempts,dispatches,highest_revision,created_at,updated_at)
                VALUES('payment-voucher-upgrade',?,'{}',?,'{"success":true}',4,'SUCCEEDED',1,1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, payment, "a".repeat(64));
        jdbc.update("INSERT INTO payment_operation_revision(tenant_id,operation_id,version,state_json) VALUES('payment-voucher-upgrade',?,4,'{\"success\":true}')", payment);
        jdbc.update("""
                INSERT INTO voucher_preparation(tenant_id,id,business_type,business_id,application_id,round_no,kind,application_version,business_version,
                employee_id,attempt_no,input_json,state_json,version,status,operation_id,created_at,started_at,lease_until,completed_at)
                VALUES('payment-voucher-upgrade',?,'ADVANCE_REQUEST',?,?,1,'EMPLOYEE_ADVANCE',5,3,'alice',1,'{"original":true}','{"ready":true}',3,'READY',?,
                CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, voucher, business, application, voucher);
        jdbc.update("INSERT INTO voucher_preparation_revision(tenant_id,preparation_id,version,state_json) VALUES('payment-voucher-upgrade',?,3,'{\"ready\":true}')", voucher);
        var old = jdbc.queryForList("SELECT tenant_id,id,kind,input_json,state_json,version,status,operation_id FROM voucher_preparation ORDER BY id");
        var history = jdbc.queryForList("SELECT * FROM voucher_preparation_revision ORDER BY preparation_id,version");
        var payments = jdbc.queryForList("SELECT * FROM payment_operation ORDER BY id");
        var upgraded = Flyway.configure().dataSource(source).target("49").load(); assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT tenant_id,id,kind,input_json,state_json,version,status,operation_id FROM voucher_preparation ORDER BY id")).isEqualTo(old);
        assertThat(jdbc.queryForList("SELECT * FROM voucher_preparation_revision ORDER BY preparation_id,version")).isEqualTo(history);
        assertThat(jdbc.queryForList("SELECT * FROM payment_operation ORDER BY id")).isEqualTo(payments);
        String insert = """
                INSERT INTO voucher_preparation(tenant_id,id,business_type,business_id,application_id,round_no,kind,application_version,business_version,
                employee_id,attempt_no,input_json,state_json,version,status,active_application_id,created_at,payment_operation_id,payment_version)
                VALUES(?,?,'ADVANCE_REQUEST',?,?,?, ?,5,3,'alice',?,'{}','{}',1,'QUEUED',?,CURRENT_TIMESTAMP,?,?)
                """;
        String next = UUID.randomUUID().toString();
        assertThatThrownBy(() -> jdbc.update(insert, "payment-voucher-upgrade", next, business, application, 1, "PAYMENT", 1, application, null, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "payment-voucher-upgrade", next, business, application, 1, "PAYMENT", 1, application, payment, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "payment-voucher-upgrade", next, business, application, 1, "PAYMENT", 1, application, payment, 3)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "payment-voucher-upgrade", next, business, application, 2, "PAYMENT", 1, application, payment, 4)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "foreign", next, business, application, 1, "PAYMENT", 1, application, payment, 4)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "payment-voucher-upgrade", next, business, application, 1, "EMPLOYEE_ADVANCE", 2, application, payment, 4)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "payment-voucher-upgrade", next, business, application, 1, "UNSUPPORTED", 1, application, null, null)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(insert, "payment-voucher-upgrade", next, business, application, 1, "PAYMENT", 1, application, payment, 4);
        assertThatThrownBy(() -> jdbc.update(insert, "payment-voucher-upgrade", UUID.randomUUID().toString(), business, application, 1, "PAYMENT", 2, application, payment, 4)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE voucher_preparation SET status='READY',version=3,active_application_id=NULL,operation_id=?,started_at=CURRENT_TIMESTAMP,lease_until=CURRENT_TIMESTAMP,completed_at=CURRENT_TIMESTAMP WHERE id=?", voucher, next)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(upgraded.migrate().migrationsExecuted).isZero(); assertThat(upgraded.validateWithResult().validationSuccessful).isTrue();
    }
}
