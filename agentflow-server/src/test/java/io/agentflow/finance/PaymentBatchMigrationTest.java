package io.agentflow.finance;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/**
 * V75 保留既有单笔请求，只允许批次引用真实原授权及第一版请求。
 * @author owlzhangfq@gmail.com
 */
class PaymentBatchMigrationTest {
    @Test void preservesUnbatchedRequestsAndRejectsForeignMismatchedOrDuplicateMembership() {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_BATCH_MIGRATION_URL", "jdbc:h2:mem:batch-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_BATCH_MIGRATION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_BATCH_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("74").load().migrate(); var jdbc = new JdbcTemplate(source);
        var first = request(jdbc); var second = request(jdbc); var before = new LinkedHashMap<String, List<java.util.Map<String, Object>>>();
        for (var table : List.of("approval_application", "voucher_operation", "payment_authorization", "payment_authorization_revision", "payment_execution_request", "payment_execution_request_revision")) {
            before.put(table, jdbc.queryForList("SELECT * FROM " + table));
        }
        var migration = Flyway.configure().dataSource(source).target("75").load(); assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
        before.forEach((table, rows) -> assertThat(jdbc.queryForList("SELECT * FROM " + table)).containsExactlyInAnyOrderElementsOf(rows));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_batch", Integer.class)).isZero();
        var batch = UUID.randomUUID().toString(); var other = UUID.randomUUID().toString();
        for (var id : List.of(batch, other)) jdbc.update("INSERT INTO payment_batch(tenant_id,id,legal_entity_id,currency,cashier_id,item_count,total_value,created_at,state_json) VALUES('batch-upgrade',?,?,'CNY','cashier',1,100.00,?,'{}')",
                id, UUID.randomUUID().toString(), Timestamp.from(Instant.parse("2026-09-29T12:00:00Z")));
        String insert = "INSERT INTO payment_batch_item(tenant_id,batch_id,line_no,authorization_id,authorization_version,request_id,request_version) VALUES(?,?,1,?,?,?,?)";
        assertThatThrownBy(() -> jdbc.update(insert, "foreign", batch, first[0], 1, first[1], 1)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "batch-upgrade", batch, first[0], 1, second[1], 1)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "batch-upgrade", batch, first[0], 2, first[1], 1)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "batch-upgrade", batch, first[0], 1, first[1], 2)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(insert, "batch-upgrade", batch, first[0], 1, first[1], 1);
        assertThatThrownBy(() -> jdbc.update(insert, "batch-upgrade", other, first[0], 1, first[1], 1)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_execution_request SET authorization_id=? WHERE id=?", second[0], first[1])).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM payment_execution_request_revision WHERE request_id=?", first[1])).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(migration.migrate().migrationsExecuted).isZero(); assertThat(migration.validateWithResult().validationSuccessful).isTrue();
    }

    private static String[] request(JdbcTemplate jdbc) {
        String application = UUID.randomUUID().toString(), business = UUID.randomUUID().toString(), voucher = UUID.randomUUID().toString(), authorization = UUID.randomUUID().toString(), request = UUID.randomUUID().toString();
        var now = Timestamp.from(Instant.parse("2026-09-29T12:00:00Z")); var until = Timestamp.from(now.toInstant().plusSeconds(60));
        jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id) VALUES(?,'batch-upgrade',?,'fixture',1,'alice','旧借款','{}','APPROVED',1,5,'ADVANCE_REQUEST',?)", application, "OLD-" + application, business);
        jdbc.update("""
                INSERT INTO voucher_operation(tenant_id,id,business_type,business_id,application_id,round_no,kind,application_version,business_version,input_json,command_digest,state_json,
                version,status,attempts,highest_revision,created_at,updated_at) VALUES('batch-upgrade',?,'ADVANCE_REQUEST',?,?,1,'EMPLOYEE_ADVANCE',5,3,'{}',?,'{}',3,'POSTED',1,1,?,?)
                """, voucher, business, application, "a".repeat(64), now, now);
        jdbc.update("""
                INSERT INTO payment_authorization(tenant_id,id,business_type,business_id,application_id,round_no,application_version,business_version,purpose,voucher_operation_id,voucher_kind,
                terms_json,decision_json,state_json,version,status,active_business_id,authorized_at,expires_at,updated_at)
                VALUES('batch-upgrade',?,'ADVANCE_REQUEST',?,?,1,5,3,'EMPLOYEE_ADVANCE',?,'EMPLOYEE_ADVANCE','{}','{}','{"retained":true}',1,'AUTHORIZED',?,?,?,?)
                """, authorization, business, application, voucher, business, now, until, now);
        jdbc.update("INSERT INTO payment_authorization_revision(tenant_id,authorization_id,version,state_json) VALUES('batch-upgrade',?,1,'{\"retained\":true}')", authorization);
        jdbc.update("INSERT INTO payment_execution_request(tenant_id,id,authorization_id,authorization_version,cashier_id,input_json,state_json,version,status,attempts,created_at,updated_at,next_attempt_at) VALUES('batch-upgrade',?,?,1,'cashier','{}','{\"retained\":true}',1,'QUEUED',0,?,?,?)", request, authorization, now, now, now);
        jdbc.update("INSERT INTO payment_execution_request_revision(tenant_id,request_id,version,state_json) VALUES('batch-upgrade',?,1,'{\"retained\":true}')", request);
        return new String[]{authorization, request};
    }
}
