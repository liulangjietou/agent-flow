package io.agentflow.expense;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.JsonUtil;
import io.agentflow.finance.Money;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 升级从不可变凭证修订补录冻结，已明确恢复的凭证和旧账原文保持不变。
 * @author owlzhangfq@gmail.com
 */
class AdvanceVoucherReviewMigrationTest {
    private static final String TENANT = "advance-voucher-upgrade";
    private final JsonUtil json = new JsonUtil(new ObjectMapper().findAndRegisterModules());

    @Test void upgradePreservesOriginalFactsAndRecoversEachUnresolvedVoucherIncludingPendingQueries() {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_ADVANCE_VOUCHER_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_ADVANCE_VOUCHER_MIGRATION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_ADVANCE_VOUCHER_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("59").load().migrate(); var jdbc = new JdbcTemplate(source);
        var both = loan(jdbc); var resolved = loan(jdbc); var querying = loan(jdbc); var renewed = loan(jdbc);
        conflict(jdbc, both.accrual(), false, false); conflict(jdbc, both.paymentVoucher(), false, false);
        conflict(jdbc, resolved.accrual(), true, false);
        conflict(jdbc, querying.accrual(), false, true); conflict(jdbc, renewed.accrual(), true, false);
        jdbc.update("INSERT INTO voucher_operation_revision(tenant_id,operation_id,version,state_json) VALUES(?,?,6,'{\"status\":\"RECONCILING\"}')", TENANT, renewed.accrual());
        jdbc.update("UPDATE voucher_operation SET status='RECONCILING',version=6 WHERE tenant_id=? AND id=?", TENANT, renewed.accrual());
        var originals = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (var table : List.of("approval_application", "payment_authorization", "voucher_operation", "voucher_operation_revision", "voucher_dispute_resolution", "finance_amount_use")) {
            originals.put(table, jdbc.queryForList("SELECT * FROM " + table));
        }
        var balances = jdbc.queryForList("SELECT * FROM finance_resource"); var revisions = jdbc.queryForList("SELECT * FROM finance_resource_revision");
        var upgrade = Flyway.configure().dataSource(source).target("60").load(); assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        originals.forEach((table, rows) -> assertThat(jdbc.queryForList("SELECT * FROM " + table)).as(table).containsExactlyInAnyOrderElementsOf(rows));
        assertThat(jdbc.queryForList("SELECT * FROM finance_resource_revision WHERE operation<>'VOUCHER_REVIEW_UPGRADE'")).containsExactlyInAnyOrderElementsOf(revisions);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finance_resource_revision WHERE operation='VOUCHER_REVIEW_UPGRADE'", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT * FROM finance_resource WHERE id=?", resolved.loan())).containsExactlyElementsOf(balances.stream().filter(row -> resolved.loan().equals(column(row, "id"))).toList());
        for (var item : List.of(both, querying, renewed)) {
            var after = EmployeeAdvance.restore(json.read(jdbc.queryForObject("SELECT state_json FROM finance_resource WHERE tenant_id=? AND id=?", String.class, TENANT, item.loan()), EmployeeAdvance.State.class));
            assertThat(after.version()).isEqualTo(3); assertThat(after.balance()).isEqualTo(item.state().balance()); assertThat(after.outstanding()).isEqualTo(money("100"));
            assertThat(after.available()).isEqualTo(money("0")); assertThat(after.status()).isEqualTo(EmployeeAdvance.Status.VOUCHER_REVIEW);
            assertThat(after.voucherReviews()).contains(UUID.fromString(item.accrual()));
            if (item == both) assertThat(after.voucherReviews()).containsExactlyInAnyOrder(UUID.fromString(item.accrual()), UUID.fromString(item.paymentVoucher()));
            else assertThat(after.voucherReviews()).hasSize(1);
            var old = json.map(json.write(item.state())); var changed = json.map(json.write(after.state()));
            old.remove("version"); old.remove("voucherReviews"); changed.remove("version"); changed.remove("voucherReviews"); assertThat(changed).isEqualTo(old);
        }
        var held = jdbc.queryForList("SELECT * FROM finance_resource"); var allRevisions = jdbc.queryForList("SELECT * FROM finance_resource_revision");
        assertThat(upgrade.migrate().migrationsExecuted).isZero(); assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
        assertThat(jdbc.queryForList("SELECT * FROM finance_resource")).containsExactlyInAnyOrderElementsOf(held);
        assertThat(jdbc.queryForList("SELECT * FROM finance_resource_revision")).containsExactlyInAnyOrderElementsOf(allRevisions);
    }

    private Fixture loan(JdbcTemplate jdbc) {
        var id = UUID.randomUUID(); var application = UUID.randomUUID().toString(); var accrual = UUID.randomUUID().toString();
        var payment = UUID.randomUUID().toString(); var paymentVoucher = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id) VALUES(?,?,?,'fixture',1,'alice','原借款','{}','APPROVED',1,5,'ADVANCE_REQUEST',?)", application, TENANT, "OLD-" + id, id.toString());
        for (var voucher : List.of(accrual, paymentVoucher)) {
            var kind = voucher.equals(accrual) ? "EMPLOYEE_ADVANCE" : "PAYMENT";
            var input = json.write(Map.of("command", Map.of("payment", Map.of("command", Map.of("id", payment)))));
            jdbc.update("""
                    INSERT INTO voucher_operation(tenant_id,id,business_type,business_id,application_id,round_no,kind,application_version,business_version,input_json,command_digest,state_json,
                    version,status,attempts,highest_revision,created_at,updated_at) VALUES(?,?,'ADVANCE_REQUEST',?,?,1,?,5,3,?,?,'{}',3,'POSTED',1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """, TENANT, voucher, id.toString(), application, kind, input, "a".repeat(64));
            jdbc.update("INSERT INTO voucher_operation_revision(tenant_id,operation_id,version,state_json) VALUES(?,?,3,'{\"status\":\"POSTED\"}')", TENANT, voucher);
        }
        jdbc.update("""
                INSERT INTO payment_authorization(tenant_id,id,business_type,business_id,application_id,round_no,application_version,business_version,purpose,voucher_operation_id,voucher_kind,
                terms_json,decision_json,state_json,version,status,active_business_id,authorized_at,expires_at,updated_at)
                VALUES(?,?,'ADVANCE_REQUEST',?,?,1,5,3,'EMPLOYEE_ADVANCE',?,'EMPLOYEE_ADVANCE','{}','{}','{}',2,'EXECUTION_REGISTERED',?,TIMESTAMP '2026-09-29 12:00:00',TIMESTAMP '2026-09-29 13:00:00',TIMESTAMP '2026-09-29 12:01:00')
                """, TENANT, payment, id.toString(), application, accrual, id.toString());
        var advance = new EmployeeAdvance(id, TENANT, UUID.randomUUID(), "alice", money("100"), "bank-" + payment, LocalDate.now(), LocalDate.now().plusDays(30));
        advance.reserve(1, new ExpenseUse(UUID.randomUUID(), 1, 0), money("20")); var state = advance.state();
        var legacy = new LinkedHashMap<>(json.map(json.write(state))); legacy.remove("voucherReviews"); var stored = json.write(legacy);
        jdbc.update("INSERT INTO finance_resource(tenant_id,resource_type,id,owner_id,source_reference,version,context_json,state_json) VALUES(?,'ADVANCE',?,'alice',?,2,'{}',?)", TENANT, id.toString(), advance.paymentReference(), stored);
        jdbc.update("INSERT INTO finance_resource_revision(tenant_id,resource_type,resource_id,version,actor_id,operation,state_json) VALUES(?,'ADVANCE',?,2,'fixture','RESERVE',?)", TENANT, id.toString(), stored);
        return new Fixture(id.toString(), accrual, paymentVoucher, state);
    }

    private void conflict(JdbcTemplate jdbc, String voucher, boolean resolved, boolean querying) {
        jdbc.update("INSERT INTO voucher_operation_revision(tenant_id,operation_id,version,state_json) VALUES(?,?,4,'{\"status\":\"REVERSED\"}')", TENANT, voucher);
        jdbc.update("UPDATE voucher_operation SET status='REVERSED',version=4 WHERE tenant_id=? AND id=?", TENANT, voucher);
        if (resolved || querying) {
            jdbc.update("INSERT INTO voucher_operation_revision(tenant_id,operation_id,version,state_json) VALUES(?,?,5,?)", TENANT, voucher, resolved ? "{\"status\":\"POSTED\"}" : "{\"status\":\"UNKNOWN\"}");
            jdbc.update("UPDATE voucher_operation SET status=?,version=5,next_attempt_at=? WHERE tenant_id=? AND id=?", resolved ? "POSTED" : "UNKNOWN", resolved ? null : java.sql.Timestamp.from(java.time.Instant.now()), TENANT, voucher);
        }
        if (resolved) jdbc.update("INSERT INTO voucher_dispute_resolution(tenant_id,id,operation_id,disputed_version,resolved_version,outcome,resolved_by,observed_at,resolved_at,state_json) VALUES(?,?,?,4,5,'POSTED','finance',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'{}')", TENANT, UUID.randomUUID().toString(), voucher);
    }
    private static Object column(Map<String, Object> row, String key) { return row.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(key)).findFirst().orElseThrow().getValue(); }
    private static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
    /**
     * 原放款及两类原凭证的数据库身份。
     * @author owlzhangfq@gmail.com
     */
    private record Fixture(String loan, String accrual, String paymentVoucher, EmployeeAdvance.State state) { }
}
