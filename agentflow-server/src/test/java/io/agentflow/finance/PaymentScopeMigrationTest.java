package io.agentflow.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.JsonUtil;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * V46 至 V47 只从原授权条款建立法人范围，损坏、跨身份或非规范标识不推断权限。
 * @author owlzhangfq@gmail.com
 */
class PaymentScopeMigrationTest {
    @Test void originalEntityIsIndexedWithoutChangingTermsOrGrantingScopeForInvalidLegacyData() {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_PAYMENT_SCOPE_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_PAYMENT_SCOPE_MIGRATION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_PAYMENT_SCOPE_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("46").load().migrate(); var jdbc = new JdbcTemplate(source); var json = new JsonUtil(new ObjectMapper());
        String application = UUID.randomUUID().toString(), business = UUID.randomUUID().toString(), voucher = UUID.randomUUID().toString(), entity = UUID.randomUUID().toString();
        var now = Timestamp.from(Instant.parse("2026-09-28T12:00:00Z")); var until = Timestamp.from(now.toInstant().plusSeconds(60));
        jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id) VALUES(?,'scope-upgrade','OLD-APP','fixture',1,'alice','旧借款','{}','APPROVED',1,5,'ADVANCE_REQUEST',?)", application, business);
        jdbc.update("""
                INSERT INTO voucher_operation(tenant_id,id,business_type,business_id,application_id,round_no,kind,application_version,business_version,input_json,command_digest,state_json,
                version,status,attempts,highest_revision,created_at,updated_at) VALUES('scope-upgrade',?,'ADVANCE_REQUEST',?,?,1,'EMPLOYEE_ADVANCE',5,3,'{}',?,'{}',3,'POSTED',1,1,?,?)
                """, voucher, business, application, "a".repeat(64), now, now);
        var ids = java.util.stream.IntStream.range(0, 5).mapToObj(index -> UUID.randomUUID().toString()).toList();
        var terms = List.of(json.write(Map.of("tenantId", "scope-upgrade", "id", ids.get(0), "payee", Map.of("legalEntityId", entity))),
                "{malformed", "{}", json.write(Map.of("tenantId", "foreign", "id", ids.get(3), "payee", Map.of("legalEntityId", entity))),
                json.write(Map.of("tenantId", "scope-upgrade", "id", ids.get(4), "payee", Map.of("legalEntityId", "1-1-1-1-1"))));
        for (int index = 0; index < ids.size(); index++) {
            jdbc.update("""
                    INSERT INTO payment_authorization(tenant_id,id,business_type,business_id,application_id,round_no,application_version,business_version,purpose,voucher_operation_id,voucher_kind,
                    terms_json,decision_json,state_json,version,status,active_business_id,authorized_at,expires_at,updated_at)
                    VALUES('scope-upgrade',?,'ADVANCE_REQUEST',?,?,1,5,3,'EMPLOYEE_ADVANCE',?,'EMPLOYEE_ADVANCE',?,'{}','{"retained":true}',2,'VOIDED',NULL,?,?,?)
                    """, ids.get(index), business, application, voucher, terms.get(index), now, until, now);
        }
        String original = "SELECT tenant_id,id,terms_json,decision_json,state_json,status,version FROM payment_authorization ORDER BY id"; var before = jdbc.queryForList(original);
        var upgrade = Flyway.configure().dataSource(source).target("47").load(); assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList(original)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT legal_entity_id FROM payment_authorization WHERE tenant_id='scope-upgrade' AND id=?", String.class, ids.get(0))).isEqualTo(entity);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_authorization WHERE tenant_id='scope-upgrade' AND legal_entity_id IS NULL", Long.class)).isEqualTo(4);
        assertThat(upgrade.migrate().migrationsExecuted).isZero(); assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
    }
}
