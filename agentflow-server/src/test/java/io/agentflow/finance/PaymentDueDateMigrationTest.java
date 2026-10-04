package io.agentflow.finance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * V116 非空付款表升级只增加可空日期，所有旧列、命令和修订均保持原值。
 * @author owlzhangfq@gmail.com
 */
class PaymentDueDateMigrationTest {
    @Test void nonemptyUpgradeLeavesEveryOldColumnUnchangedAndDoesNotInventDates() {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_PAYMENT_DUE_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_PAYMENT_DUE_MIGRATION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_PAYMENT_DUE_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("116").load().migrate(); var jdbc = new JdbcTemplate(source);
        String application = UUID.randomUUID().toString(), business = UUID.randomUUID().toString(), voucher = UUID.randomUUID().toString(), authorization = UUID.randomUUID().toString();
        var now = Timestamp.from(Instant.parse("2026-10-01T12:00:00Z")); var until = Timestamp.from(now.toInstant().plusSeconds(900));
        jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id) VALUES(?,'due-upgrade','OLD-APP','fixture',1,'alice','旧借款','{}','APPROVED',1,5,'ADVANCE_REQUEST',?)", application, business);
        jdbc.update("""
                INSERT INTO voucher_operation(tenant_id,id,business_type,business_id,application_id,round_no,kind,application_version,business_version,input_json,command_digest,state_json,
                version,status,attempts,highest_revision,created_at,updated_at) VALUES('due-upgrade',?,'ADVANCE_REQUEST',?,?,1,'EMPLOYEE_ADVANCE',5,3,'{}',?,'{}',3,'POSTED',1,1,?,?)
                """, voucher, business, application, "a".repeat(64), now, now);
        jdbc.update("""
                INSERT INTO payment_authorization(tenant_id,id,business_type,business_id,application_id,round_no,application_version,business_version,purpose,voucher_operation_id,voucher_kind,
                terms_json,decision_json,state_json,version,status,active_business_id,authorized_at,expires_at,updated_at)
                VALUES('due-upgrade',?,'ADVANCE_REQUEST',?,?,1,5,3,'EMPLOYEE_ADVANCE',?,'EMPLOYEE_ADVANCE','{}','{}','{"retained":true}',2,'EXECUTION_REGISTERED',?,?,?,?)
                """, authorization, business, application, voucher, business, now, until, now);
        jdbc.update("INSERT INTO payment_authorization_revision(tenant_id,authorization_id,version,state_json) VALUES('due-upgrade',?,2,'{\"retained\":true}')", authorization);
        jdbc.update("INSERT INTO payment_operation(tenant_id,id,input_json,command_digest,state_json,version,status,attempts,dispatches,highest_revision,created_at,updated_at,next_attempt_at) VALUES('due-upgrade',?,'{\"originalCommand\":true}',?,'{}',1,'QUEUED',0,0,0,?,?,?)", authorization, "b".repeat(64), now, now, now);
        var authorizations = jdbc.queryForList("SELECT * FROM payment_authorization");
        var operations = jdbc.queryForList("SELECT * FROM payment_operation");
        var revisions = jdbc.queryForList("SELECT * FROM payment_authorization_revision");
        var upgrade = Flyway.configure().dataSource(source).target("117").load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        var after = jdbc.queryForList("SELECT * FROM payment_authorization");
        after.forEach(row -> { assertThat(row.remove("DUE_DATE")).isNull(); row.remove("due_date"); });
        assertThat(after).isEqualTo(authorizations);
        assertThat(jdbc.queryForList("SELECT * FROM payment_operation")).isEqualTo(operations);
        assertThat(jdbc.queryForList("SELECT * FROM payment_authorization_revision")).isEqualTo(revisions);
        assertThat(jdbc.queryForObject("SELECT due_date FROM payment_authorization", LocalDate.class)).isNull();
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
        assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
    }
}
