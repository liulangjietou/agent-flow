package io.agentflow.approval;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证金额索引升级只增加查询列，旧正文、业务版本和迁移历史保持原样。
 * @author owlzhangfq@gmail.com
 */
class TaskQueueMigrationTest {
    @Test
    void backfillsCanonicalAmountsWithoutTreatingTextOrMissingValuesAsMoney() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:task-queue-migration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).target("11").load().migrate();
        var jdbc = new JdbcTemplate(source);
        String[] payloads = {"{\"amount\":\"100.01\"}", "{\"amount\":0}", "{}", "{\"amount\":\"bad\"}",
                "{\"amount\":\"50\"}", "{\"amount\":\"99999999999999999999999999999999999999\"}", "broken-json"};
        for (int index = 0; index < payloads.length; index++) jdbc.update("""
                INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,form_schema_json)
                VALUES(?,'demo',?,'legacy',1,'alice','原正文',?,'DRAFT',1,1,?)
                """, UUID.randomUUID().toString(), "OLD-" + index, payloads[index], index == 4
                        ? "{\"schemaVersion\":1,\"fields\":[{\"key\":\"amount\",\"type\":\"TEXT\"}]}" : null);
        var before = jdbc.queryForList("SELECT * FROM approval_application ORDER BY business_no");
        var history = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        assertThat(Flyway.configure().dataSource(source).load().migrate().migrationsExecuted).isEqualTo(1);
        var after = jdbc.queryForList("SELECT * FROM approval_application ORDER BY business_no");
        assertThat(after.get(0).get("SEARCH_AMOUNT").toString()).isEqualTo("100.010000000000000000");
        assertThat(((java.math.BigDecimal) after.get(1).get("SEARCH_AMOUNT"))).isEqualByComparingTo("0");
        for (int index : new int[]{2,3,4,6}) assertThat(after.get(index).get("SEARCH_AMOUNT")).isNull();
        assertThat(after.get(5).get("SEARCH_AMOUNT").toString()).startsWith("99999999999999999999999999999999999999.");
        after.forEach(row -> row.remove("SEARCH_AMOUNT")); assertThat(after).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" WHERE \"version\"<>'12' OR \"version\" IS NULL ORDER BY \"installed_rank\""))
                .isEqualTo(history);
        assertThat(Flyway.configure().dataSource(source).load().migrate().migrationsExecuted).isZero();
    }
}
