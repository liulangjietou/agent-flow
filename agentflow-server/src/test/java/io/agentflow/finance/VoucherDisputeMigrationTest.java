package io.agentflow.finance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * V55 只追加裁决结构，保留 V54 原凭证及不可变修订，并约束租户和前后版本。
 * @author owlzhangfq@gmail.com
 */
class VoucherDisputeMigrationTest {
    @Test void upgradePreservesVoucherHistoryAndRequiresSameTenantConsecutiveDecisionRevisions() {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_VOUCHER_DISPUTE_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_VOUCHER_DISPUTE_MIGRATION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_VOUCHER_DISPUTE_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("54").load().migrate(); var jdbc = new JdbcTemplate(source);
        String app = UUID.randomUUID().toString(), business = UUID.randomUUID().toString(), operation = UUID.randomUUID().toString(), tenant = "voucher-dispute-upgrade";
        jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id) VALUES(?,?,'OLD-VOUCHER','fixture',1,'alice','旧借款','{}','APPROVED',1,5,'ADVANCE_REQUEST',?)", app, tenant, business);
        jdbc.update("INSERT INTO voucher_operation(tenant_id,id,business_type,business_id,application_id,round_no,kind,application_version,business_version,input_json,command_digest,state_json,version,status,attempts,highest_revision,created_at,updated_at) VALUES(?,?,'ADVANCE_REQUEST',?,?,1,'EMPLOYEE_ADVANCE',5,3,'{}',?,'{}',4,'RECONCILING',1,2,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", tenant, operation, business, app, "a".repeat(64));
        jdbc.update("INSERT INTO voucher_operation_revision(tenant_id,operation_id,version,state_json) VALUES(?,?,4,'{\"conflict\":true}')", tenant, operation);
        var original = jdbc.queryForList("SELECT * FROM voucher_operation"); var revisions = jdbc.queryForList("SELECT * FROM voucher_operation_revision");
        var upgrade = Flyway.configure().dataSource(source).target("55").load(); assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM voucher_operation")).isEqualTo(original); assertThat(jdbc.queryForList("SELECT * FROM voucher_operation_revision")).isEqualTo(revisions);
        String insert = "INSERT INTO voucher_dispute_resolution(tenant_id,id,operation_id,disputed_version,resolved_version,outcome,resolved_by,observed_at,resolved_at,state_json) VALUES(?,?,?,4,5,'POSTED','finance',TIMESTAMP '2026-09-29 00:00:00',TIMESTAMP '2026-09-29 00:00:01','{}')";
        assertThatThrownBy(() -> jdbc.update(insert, tenant, UUID.randomUUID().toString(), operation)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO voucher_operation_revision(tenant_id,operation_id,version,state_json) VALUES(?,?,5,'{\"resolved\":true}')", tenant, operation);
        assertThatThrownBy(() -> jdbc.update(insert, "foreign", UUID.randomUUID().toString(), operation)).isInstanceOf(DataIntegrityViolationException.class);
        String decision = UUID.randomUUID().toString(); jdbc.update(insert, tenant, decision, operation);
        assertThatThrownBy(() -> jdbc.update(insert, tenant, UUID.randomUUID().toString(), operation)).isInstanceOf(DataIntegrityViolationException.class);
        for (String change : new String[]{"resolved_version=6", "disputed_version=3", "outcome='PENDING'", "resolved_at=TIMESTAMP '2026-09-28 00:00:00'"}) {
            assertThatThrownBy(() -> jdbc.update("UPDATE voucher_dispute_resolution SET " + change + " WHERE tenant_id=? AND id=?", tenant, decision)).isInstanceOf(DataIntegrityViolationException.class);
        }
        for (int version : new int[]{4,5}) assertThatThrownBy(() -> jdbc.update("DELETE FROM voucher_operation_revision WHERE tenant_id=? AND operation_id=? AND version=?", tenant, operation, version)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(upgrade.migrate().migrationsExecuted).isZero(); assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
    }
}
