package io.agentflow.finance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * V63 无损回填当前占用，历史结束必须引用同租户同原件的精确修订。
 * @author owlzhangfq@gmail.com
 */
class VoucherReversalRetirementMigrationTest {
    @Test void upgradePreservesAllCommandsAndAllowsOnlyOneActiveReversalWithImmutableRetirementReferences() {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_REVERSAL_RETIREMENT_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_REVERSAL_RETIREMENT_MIGRATION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_REVERSAL_RETIREMENT_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("62").load().migrate(); var jdbc = new JdbcTemplate(source);
        String tenant = "retirement-upgrade", app = UUID.randomUUID().toString(), business = UUID.randomUUID().toString();
        String original = UUID.randomUUID().toString(), old = UUID.randomUUID().toString(), next = UUID.randomUUID().toString(), third = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id) VALUES(?,?,'OLD-REVERSE','fixture',1,'alice','旧冲销','{}','APPROVED',1,5,'ADVANCE_REQUEST',?)", app, tenant, business);
        jdbc.update("""
                INSERT INTO voucher_operation(tenant_id,id,business_type,business_id,application_id,round_no,kind,application_version,business_version,input_json,command_digest,state_json,version,status,attempts,highest_revision,created_at,updated_at)
                VALUES(?,?,'ADVANCE_REQUEST',?,?,1,'EMPLOYEE_ADVANCE',5,3,'{"retained":true}',?,'{"original":true}',3,'POSTED',1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, tenant, original, business, app, "a".repeat(64));
        jdbc.update("INSERT INTO voucher_operation_revision(tenant_id,operation_id,version,state_json) VALUES(?,?,3,'{" + "\"original\":true}')", tenant, original);
        for (String id : new String[]{old, next, third}) {
            jdbc.update("INSERT INTO voucher_reversal_preparation(tenant_id,id,operation_id,original_version,requested_by,input_json,state_json,version,status,created_at,updated_at) VALUES(?,?,?,3,'finance','{}','{}',4,'AUTHORIZED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", tenant, id, original);
            jdbc.update("INSERT INTO voucher_reversal_preparation_revision(tenant_id,preparation_id,version,state_json) VALUES(?,?,4,'{}')", tenant, id);
        }
        String insert = """
                INSERT INTO voucher_reversal_operation(tenant_id,id,operation_id,original_version,preparation_version,input_json,command_digest,state_json,version,status,attempts,highest_revision,created_at,updated_at,next_attempt_at%s)
                VALUES(?,?,?,3,4,'{"command":"kept"}',?,'{"old":"kept"}',1,'QUEUED',0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP%s)
                """;
        jdbc.update(insert.formatted("", ""), tenant, old, original, "b".repeat(64));
        jdbc.update("INSERT INTO voucher_reversal_operation_revision(tenant_id,reversal_id,version,state_json) VALUES(?,?,1,'{" + "\"old\":true}')", tenant, old);
        jdbc.update("UPDATE voucher_operation SET reversal_id=? WHERE tenant_id=? AND id=?", old, tenant, original);
        var commands = jdbc.queryForList("SELECT id,input_json,state_json,version FROM voucher_reversal_operation");
        var originals = jdbc.queryForList("SELECT * FROM voucher_operation"); var revisions = jdbc.queryForList("SELECT * FROM voucher_reversal_operation_revision");
        var upgrade = Flyway.configure().dataSource(source).target("63").load(); assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT id,input_json,state_json,version FROM voucher_reversal_operation")).isEqualTo(commands);
        assertThat(jdbc.queryForList("SELECT * FROM voucher_operation")).isEqualTo(originals); assertThat(jdbc.queryForList("SELECT * FROM voucher_reversal_operation_revision")).isEqualTo(revisions);
        assertThat(jdbc.queryForObject("SELECT active_operation_id FROM voucher_reversal_operation WHERE id=?", String.class, old)).isEqualTo(original);
        assertThatThrownBy(() -> jdbc.update(insert.formatted(",active_operation_id", ",?"), tenant, next, original, "c".repeat(64), original)).isInstanceOf(DataIntegrityViolationException.class);
        String retire = """
                INSERT INTO voucher_reversal_retirement(tenant_id,id,operation_id,reversal_id,original_version,released_version,reversal_version,stopped_version,basis,retired_by,retired_at,retirement_json)
                VALUES(?,?,?,?,3,4,1,2,'NEVER_DISPATCHED','finance',CURRENT_TIMESTAMP,'{}')
                """;
        var proof = UUID.randomUUID().toString();
        assertThatThrownBy(() -> jdbc.update(retire, tenant, proof, original, old)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO voucher_operation_revision(tenant_id,operation_id,version,state_json) VALUES(?,?,4,'{}')", tenant, original);
        jdbc.update("INSERT INTO voucher_reversal_operation_revision(tenant_id,reversal_id,version,state_json) VALUES(?,?,2,'{}')", tenant, old);
        assertThatThrownBy(() -> jdbc.update(retire, "foreign", proof, original, old)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(retire, tenant, proof, UUID.randomUUID().toString(), old)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(retire, tenant, proof, original, old);
        jdbc.update("UPDATE voucher_reversal_operation SET active_operation_id=NULL,retired_at=CURRENT_TIMESTAMP WHERE id=?", old);
        jdbc.update(insert.formatted(",active_operation_id", ",?"), tenant, next, original, "c".repeat(64), original);
        assertThatThrownBy(() -> jdbc.update(insert.formatted(",active_operation_id", ",?"), tenant, third, original, "d".repeat(64), original)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM voucher_reversal_operation_revision WHERE reversal_id=? AND version=1", old)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM voucher_operation_revision WHERE operation_id=? AND version=4", original)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(upgrade.migrate().migrationsExecuted).isZero(); assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
    }
}
