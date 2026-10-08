package io.agentflow.signature;

import static io.agentflow.signature.SignaturePersistenceFixtures.*;
import static org.assertj.core.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 非空 V110 升级逐表核对旧事实，统一文件清单的列类型与旧文件保持不变。
 *
 * @author owlzhangfq@gmail.com
 */
class SignatureMigrationTest {
    @Test void nonEmptyUpgradePreservesEveryOldTableAndDoesNotInventSignatureHistory() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:signature-migration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        try {
            migrate(source, "110"); seed(jdbc);
            var before = new LinkedHashMap<String, List<Map<String, Object>>>();
            try (var connection = source.getConnection(); var tables = connection.getMetaData().getTables(null, connection.getSchema(), "%", new String[]{"TABLE"})) {
                while (tables.next()) {
                    String name = tables.getString("TABLE_NAME");
                    if (!name.equalsIgnoreCase("flyway_schema_history")) before.put(name, jdbc.queryForList("SELECT * FROM " + quote(name)));
                }
            }
            assertThat(before).hasSizeGreaterThan(100);
            long oldRows = before.values().stream().mapToLong(List::size).sum();
            assertThat(oldRows).isGreaterThanOrEqualTo(6);
            var inventory = jdbc.queryForList("SELECT * FROM stored_document_inventory");
            var columns = columns(jdbc);
            var history = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
            var upgrade = Flyway.configure().dataSource(source).target("111").load();
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
            before.forEach((name, rows) -> assertThat(jdbc.queryForList("SELECT * FROM " + quote(name))).as(name).containsExactlyInAnyOrderElementsOf(rows));
            assertThat(jdbc.queryForList("SELECT * FROM stored_document_inventory")).containsExactlyInAnyOrderElementsOf(inventory);
            assertThat(columns(jdbc)).isEqualTo(columns);
            assertThat(jdbc.queryForList(
                                    "SELECT * FROM \"flyway_schema_history\" WHERE \"version\" IS"
                                        + " NULL OR \"version\"<>'111' ORDER BY"
                                        + " \"installed_rank\"")).isEqualTo(history);
            for (String name : List.of("signature_operation", "signature_source_document", "signature_operation_revision", "signature_result_file")) {
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + name, Integer.class)).as(name).isZero();
            }
            assertThat(upgrade.migrate().migrationsExecuted).isZero();
            assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
            System.out.printf(
                    "Signature migration verified, previousTables=%d, previousRows=%d,"
                        + " previousFiles=%d%n", before.size(), oldRows, inventory.size());
        } finally { jdbc.execute("SHUTDOWN"); }
    }

    private List<String> columns(JdbcTemplate jdbc) {
        return jdbc.query("SELECT * FROM stored_document_inventory WHERE 1=0", rows -> {
            var metadata = rows.getMetaData(); var result = new ArrayList<String>();
            for (int index = 1; index <= metadata.getColumnCount(); index++) result.add(column(metadata, index));
            return result;
        });
    }
    private String column(java.sql.ResultSetMetaData metadata, int index) throws SQLException {
        return metadata.getColumnName(index) + ":" + metadata.getColumnType(index) + ":" + metadata.getPrecision(index);
    }
    private static String quote(String name) { return "\"" + name.replace("\"", "\"\"") + "\""; }
}
