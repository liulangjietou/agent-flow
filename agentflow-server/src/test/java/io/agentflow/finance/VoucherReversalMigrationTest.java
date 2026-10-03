package io.agentflow.finance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * V61 只新增冲销结构，旧凭证原字节保留，租户、精确修订和法人内反向凭证身份由数据库约束。
 * @author owlzhangfq@gmail.com
 */
class VoucherReversalMigrationTest {
    @Test void upgradePreservesOriginalsAndRequiresIndependentSameTenantUniqueEvidence() {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_VOUCHER_REVERSAL_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_VOUCHER_REVERSAL_MIGRATION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_VOUCHER_REVERSAL_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("60").load().migrate(); var jdbc = new JdbcTemplate(source);
        String tenant = "reversal-upgrade", application = UUID.randomUUID().toString(), business = UUID.randomUUID().toString(), operation = UUID.randomUUID().toString(), payment = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id) VALUES(?,?,'OLD-VOUCHER','fixture',1,'alice','旧借款','{}','APPROVED',1,5,'ADVANCE_REQUEST',?)", application, tenant, business);
        for (var id : new String[]{operation, payment}) {
            jdbc.update("""
                    INSERT INTO voucher_operation(tenant_id,id,business_type,business_id,application_id,round_no,kind,application_version,business_version,input_json,command_digest,state_json,version,status,attempts,highest_revision,created_at,updated_at)
                    VALUES(?,?,'ADVANCE_REQUEST',?,?,1,?,5,3,'{}',?,'{"original":"retained"}',4,'REVERSED',1,2,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """, tenant, id, business, application, id.equals(operation) ? "EMPLOYEE_ADVANCE" : "PAYMENT", "a".repeat(64));
            for (int version : new int[]{3,4}) jdbc.update("INSERT INTO voucher_operation_revision(tenant_id,operation_id,version,state_json) VALUES(?,?,?,'{" + "\"original\":\"retained\"}')", tenant, id, version);
        }
        var originals = jdbc.queryForList("SELECT * FROM voucher_operation ORDER BY id"); var revisions = jdbc.queryForList("SELECT * FROM voucher_operation_revision ORDER BY operation_id,version");
        var upgrade = Flyway.configure().dataSource(source).target("61").load(); assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM voucher_operation ORDER BY id")).isEqualTo(originals);
        assertThat(jdbc.queryForList("SELECT * FROM voucher_operation_revision ORDER BY operation_id,version")).isEqualTo(revisions);
        String query = "INSERT INTO voucher_reversal_check(tenant_id,id,operation_id,original_version,requested_by,input_json,state_json,version,status,created_at,updated_at) VALUES(?,?,?,3,'finance','{}','{}',1,'QUEUED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)";
        assertThatThrownBy(() -> jdbc.update(query, "foreign", UUID.randomUUID().toString(), operation)).isInstanceOf(DataIntegrityViolationException.class);
        String check = UUID.randomUUID().toString(), second = UUID.randomUUID().toString(), entity = UUID.randomUUID().toString();
        jdbc.update(query, tenant, check, operation); jdbc.update(query, tenant, second, payment);
        for (var id : new String[]{check, second}) {
            assertThatThrownBy(() -> jdbc.update("UPDATE voucher_reversal_check SET status='RUNNING' WHERE tenant_id=? AND id=?", tenant, id)).isInstanceOf(DataIntegrityViolationException.class);
            jdbc.update("INSERT INTO voucher_reversal_check_revision(tenant_id,check_id,version,state_json) VALUES(?,?,2,'{}')", tenant, id);
        }
        String insert = """
                INSERT INTO voucher_reversal_record(tenant_id,id,operation_id,operation_version,check_id,check_version,legal_entity_id,reversal_posting_reference,reversal_voucher_reference,recorded_by,observed_at,recorded_at,state_json)
                VALUES(?,?,?,4,?,2,?,?,?,'finance',TIMESTAMP '2026-09-29 00:00:00',TIMESTAMP '2026-09-29 00:00:01','{}')
                """;
        String record = UUID.randomUUID().toString();
        assertThatThrownBy(() -> jdbc.update(insert, "foreign", record, operation, check, entity, "reverse-posting", "reverse-voucher")).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(insert, tenant, record, operation, check, entity, "reverse-posting", "reverse-voucher");
        assertThatThrownBy(() -> jdbc.update(insert, tenant, UUID.randomUUID().toString(), operation, check, entity, "other-posting", "other-voucher")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, tenant, UUID.randomUUID().toString(), payment, second, entity, "reverse-posting", "other-voucher")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, tenant, UUID.randomUUID().toString(), payment, second, entity, "other-posting", "reverse-voucher")).isInstanceOf(DataIntegrityViolationException.class);
        for (String change : new String[]{"operation_version=2", "check_version=3", "recorded_at=TIMESTAMP '2026-09-28 00:00:00'"}) assertThatThrownBy(() -> jdbc.update("UPDATE voucher_reversal_record SET " + change + " WHERE tenant_id=? AND id=?", tenant, record)).isInstanceOf(DataIntegrityViolationException.class);
        for (int version : new int[]{3,4}) assertThatThrownBy(() -> jdbc.update("DELETE FROM voucher_operation_revision WHERE tenant_id=? AND operation_id=? AND version=?", tenant, operation, version)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(upgrade.migrate().migrationsExecuted).isZero(); assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
    }
}
