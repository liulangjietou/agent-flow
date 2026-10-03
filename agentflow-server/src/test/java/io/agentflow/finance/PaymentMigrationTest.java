package io.agentflow.finance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * V44 至 V45 保留既有凭证与审批，数据库约束禁止跨租户绑定、重复授权及无发送事实的到账状态。
 * @author owlzhangfq@gmail.com
 */
class PaymentMigrationTest {
    @Test void upgradeRetainsAccountingAndEnforcesOriginalAuthorizationBindingsAndSchedules() {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_PAYMENT_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_PAYMENT_MIGRATION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_PAYMENT_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("44").load().migrate(); var jdbc = new JdbcTemplate(source);
        String application = UUID.randomUUID().toString(), business = UUID.randomUUID().toString(), voucher = UUID.randomUUID().toString(), authorization = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id) VALUES(?,'payment-upgrade','OLD-APP','fixture',1,'alice','旧借款','{}','APPROVED',1,5,'ADVANCE_REQUEST',?)", application, business);
        jdbc.update("""
                INSERT INTO voucher_operation(tenant_id,id,business_type,business_id,application_id,round_no,kind,application_version,business_version,
                input_json,command_digest,state_json,version,status,attempts,highest_revision,created_at,updated_at)
                VALUES('payment-upgrade',?,'ADVANCE_REQUEST',?,?,1,'EMPLOYEE_ADVANCE',5,3,'{}',?,'{"retained":true}',3,'POSTED',1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, voucher, business, application, "a".repeat(64));
        jdbc.update("INSERT INTO voucher_operation_revision(tenant_id,operation_id,version,state_json) VALUES('payment-upgrade',?,3,'{\"retained\":true}')", voucher);
        var applications = jdbc.queryForList("SELECT * FROM approval_application ORDER BY id"); var vouchers = jdbc.queryForList("SELECT * FROM voucher_operation ORDER BY id");
        var revisions = jdbc.queryForList("SELECT * FROM voucher_operation_revision ORDER BY operation_id,version");
        var upgraded = Flyway.configure().dataSource(source).target("45").load(); assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM approval_application ORDER BY id")).isEqualTo(applications);
        assertThat(jdbc.queryForList("SELECT * FROM voucher_operation ORDER BY id")).isEqualTo(vouchers);
        assertThat(jdbc.queryForList("SELECT * FROM voucher_operation_revision ORDER BY operation_id,version")).isEqualTo(revisions);
        String insert = """
                INSERT INTO payment_authorization(tenant_id,id,business_type,business_id,application_id,round_no,application_version,business_version,
                purpose,voucher_operation_id,voucher_kind,terms_json,decision_json,state_json,version,status,active_business_id,authorized_at,expires_at,updated_at)
                VALUES(?,?,'ADVANCE_REQUEST',?,?,1,5,3,'EMPLOYEE_ADVANCE',?,'EMPLOYEE_ADVANCE','{}','{}','{}',1,'AUTHORIZED',?,?,?,?)
                """;
        var now = Timestamp.from(Instant.parse("2026-09-28T12:00:00Z")); var until = Timestamp.from(now.toInstant().plusSeconds(60));
        assertThatThrownBy(() -> jdbc.update(insert, "foreign", authorization, business, application, voucher, business, now, until, now)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "payment-upgrade", authorization, business, application, UUID.randomUUID().toString(), business, now, until, now)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(insert, "payment-upgrade", authorization, business, application, voucher, business, now, until, now);
        assertThatThrownBy(() -> jdbc.update(insert, "payment-upgrade", UUID.randomUUID().toString(), business, application, voucher, business, now, until, now)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_authorization SET status='EXECUTION_REGISTERED' WHERE tenant_id='payment-upgrade' AND id=?", authorization)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_authorization SET active_business_id=NULL WHERE tenant_id='payment-upgrade' AND id=?", authorization)).isInstanceOf(DataIntegrityViolationException.class);
        String operation = "INSERT INTO payment_operation(tenant_id,id,input_json,command_digest,state_json,version,status,attempts,dispatches,highest_revision,created_at,updated_at,next_attempt_at) VALUES('payment-upgrade',?,'{}',?,'{}',1,'QUEUED',0,0,0,?,?,?)";
        assertThatThrownBy(() -> jdbc.update(operation, UUID.randomUUID().toString(), "a".repeat(64), now, now, now)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(operation, authorization, "a".repeat(64), now, now, now);
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_operation SET status='SENDING',attempts=1,next_attempt_at=NULL,lease_until=? WHERE tenant_id='payment-upgrade' AND id=?", until, authorization)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_operation SET status='SUCCEEDED',next_attempt_at=NULL WHERE tenant_id='payment-upgrade' AND id=?", authorization)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_operation SET dispatches=2 WHERE tenant_id='payment-upgrade' AND id=?", authorization)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO payment_authorization_revision(tenant_id,authorization_id,version,state_json) VALUES('payment-upgrade',?,1,'{}')", authorization);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO payment_authorization_revision(tenant_id,authorization_id,version,state_json) VALUES('foreign',?,1,'{}')", authorization)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO payment_operation_revision(tenant_id,operation_id,version,state_json) VALUES('payment-upgrade',?,1,'{}')", authorization);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO payment_operation_revision(tenant_id,operation_id,version,state_json) VALUES('payment-upgrade',?,1,'{}')", authorization)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(upgraded.migrate().migrationsExecuted).isZero(); assertThat(upgraded.validateWithResult().validationSuccessful).isTrue();
    }
}
