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
 * V45 升级保留原授权，V46 只允许同授权唯一出纳请求及真实操作关联。
 * @author owlzhangfq@gmail.com
 */
class PaymentExecutionRequestMigrationTest {
    @Test void upgradeKeepsExistingAuthorizationAndRequiresUniqueSelectionAndRealReadyOperation() {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_PAYMENT_REQUEST_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_PAYMENT_REQUEST_MIGRATION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_PAYMENT_REQUEST_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("45").load().migrate(); var jdbc = new JdbcTemplate(source);
        String application = UUID.randomUUID().toString(), business = UUID.randomUUID().toString(), voucher = UUID.randomUUID().toString(), authorization = UUID.randomUUID().toString(), request = UUID.randomUUID().toString();
        var now = Timestamp.from(Instant.parse("2026-09-28T12:00:00Z")); var until = Timestamp.from(now.toInstant().plusSeconds(60));
        jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id) VALUES(?,'request-upgrade','OLD-APP','fixture',1,'alice','旧借款','{}','APPROVED',1,5,'ADVANCE_REQUEST',?)", application, business);
        jdbc.update("""
                INSERT INTO voucher_operation(tenant_id,id,business_type,business_id,application_id,round_no,kind,application_version,business_version,input_json,command_digest,state_json,
                version,status,attempts,highest_revision,created_at,updated_at) VALUES('request-upgrade',?,'ADVANCE_REQUEST',?,?,1,'EMPLOYEE_ADVANCE',5,3,'{}',?,'{}',3,'POSTED',1,1,?,?)
                """, voucher, business, application, "a".repeat(64), now, now);
        jdbc.update("""
                INSERT INTO payment_authorization(tenant_id,id,business_type,business_id,application_id,round_no,application_version,business_version,purpose,voucher_operation_id,voucher_kind,
                terms_json,decision_json,state_json,version,status,active_business_id,authorized_at,expires_at,updated_at)
                VALUES('request-upgrade',?,'ADVANCE_REQUEST',?,?,1,5,3,'EMPLOYEE_ADVANCE',?,'EMPLOYEE_ADVANCE','{}','{}','{"retained":true}',1,'AUTHORIZED',?,?,?,?)
                """, authorization, business, application, voucher, business, now, until, now);
        jdbc.update("INSERT INTO payment_authorization_revision(tenant_id,authorization_id,version,state_json) VALUES('request-upgrade',?,1,'{\"retained\":true}')", authorization);
        var before = jdbc.queryForList("SELECT * FROM payment_authorization ORDER BY id"); var revisions = jdbc.queryForList("SELECT * FROM payment_authorization_revision ORDER BY authorization_id,version");
        var upgrade = Flyway.configure().dataSource(source).target("46").load(); assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM payment_authorization ORDER BY id")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM payment_authorization_revision ORDER BY authorization_id,version")).isEqualTo(revisions);
        String insert = "INSERT INTO payment_execution_request(tenant_id,id,authorization_id,authorization_version,cashier_id,input_json,state_json,version,status,attempts,created_at,updated_at,next_attempt_at) VALUES(?,?,?,1,'cashier','{}','{}',1,'QUEUED',0,?,?,?)";
        assertThatThrownBy(() -> jdbc.update(insert, "foreign", request, authorization, now, now, now)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(insert, "request-upgrade", request, authorization, now, now, now);
        assertThatThrownBy(() -> jdbc.update(insert, "request-upgrade", UUID.randomUUID().toString(), authorization, now, now, now)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_execution_request SET status='RUNNING',next_attempt_at=NULL WHERE tenant_id='request-upgrade' AND id=?", request)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_execution_request SET status='READY',next_attempt_at=NULL WHERE tenant_id='request-upgrade' AND id=?", request)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_execution_request SET status='READY',operation_id=?,next_attempt_at=NULL WHERE tenant_id='request-upgrade' AND id=?", authorization, request)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO payment_operation(tenant_id,id,input_json,command_digest,state_json,version,status,attempts,dispatches,highest_revision,created_at,updated_at,next_attempt_at) VALUES('request-upgrade',?,'{}',?,'{}',1,'QUEUED',0,0,0,?,?,?)", authorization, "a".repeat(64), now, now, now);
        jdbc.update("UPDATE payment_execution_request SET status='READY',operation_id=?,attempts=1,version=3,next_attempt_at=NULL WHERE tenant_id='request-upgrade' AND id=?", authorization, request);
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_execution_request SET operation_id=? WHERE tenant_id='request-upgrade' AND id=?", UUID.randomUUID().toString(), request)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO payment_execution_request_revision(tenant_id,request_id,version,state_json) VALUES('request-upgrade',?,3,'{}')", request);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO payment_execution_request_revision(tenant_id,request_id,version,state_json) VALUES('foreign',?,3,'{}')", request)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(upgrade.migrate().migrationsExecuted).isZero(); assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
    }
}
