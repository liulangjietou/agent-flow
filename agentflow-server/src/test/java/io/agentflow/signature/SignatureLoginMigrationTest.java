package io.agentflow.signature;

import io.agentflow.auth.DeferredActorAuthentication.Kind;
import io.agentflow.auth.DeferredActorAuthentication.LoginReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.agentflow.signature.SignaturePersistenceFixtures.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 非空 V112 升级保留旧业务与会话，引用不阻止注销，独立 H2 恢复不补造登录。
 * @author owlzhangfq@gmail.com
 */
class SignatureLoginMigrationTest {
    @TempDir Path directory;

    @Test void upgradePreservesEveryExistingTableAndReferenceSurvivesSessionCleanupAndDatabaseRestore() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:signature-login-migration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source); migrate(source, "112"); var queued = seed(jdbc); var operations = repository(source);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source)); tx.executeWithoutResult(ignored -> operations.create(queued));
        String primary = UUID.randomUUID().toString(), browserSession = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO AF_HTTP_SESSION VALUES(?,?,1,2,1800,1800002,'alice')", primary, browserSession);
        jdbc.update("INSERT INTO AF_HTTP_SESSION_ATTRIBUTES VALUES(?,'fixture',?)", primary, new byte[]{1, 2, 3});
        Map<String, List<Map<String, Object>>> before = new LinkedHashMap<>();
        try (var connection = source.getConnection(); var tables = connection.getMetaData().getTables(null, connection.getSchema(), "%", new String[]{"TABLE"})) {
            while (tables.next()) { String table = tables.getString("TABLE_NAME"); if (!table.equalsIgnoreCase("flyway_schema_history")) before.put(table, jdbc.queryForList("SELECT * FROM " + quoted(table))); }
        }
        var migration = Flyway.configure().dataSource(source).target("113").load(); assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
        before.forEach((table, rows) -> assertThat(jdbc.queryForList("SELECT * FROM " + quoted(table))).as(table)
                .usingRecursiveFieldByFieldElementComparator().containsExactlyInAnyOrderElementsOf(rows));
        assertThat(operations.find("tenant-a", queued.input().request().id())).contains(queued);
        var logins = new JdbcSignatureLoginRepository(jdbc); assertThat(logins.find(queued)).isEmpty();
        var reference = new LoginReference(Kind.OIDC_SESSION, primary); tx.executeWithoutResult(ignored -> logins.insert(queued, reference));
        jdbc.update("DELETE FROM AF_HTTP_SESSION WHERE PRIMARY_ID=?", primary);
        assertThat(jdbc.queryForList("SELECT * FROM AF_HTTP_SESSION_ATTRIBUTES")).isEmpty(); assertThat(logins.find(queued)).contains(reference);
        assertThat(migration.migrate().migrationsExecuted).isZero();
        Path backup = directory.resolve("signing.sql"); jdbc.execute("SCRIPT TO '" + backup.toString().replace("'", "''") + "'");
        var restored = new DriverManagerDataSource("jdbc:h2:mem:signature-login-restored-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var restoredJdbc = new JdbcTemplate(restored); restoredJdbc.execute("RUNSCRIPT FROM '" + backup.toString().replace("'", "''") + "'");
        assertThat(Flyway.configure().dataSource(restored).target("113").load().validateWithResult().validationSuccessful).isTrue();
        assertThat(new JdbcSignatureLoginRepository(restoredJdbc).find(queued)).contains(reference);
        assertThat(repository(restored).find("tenant-a", queued.input().request().id())).contains(queued);
        assertThat(restoredJdbc.queryForList("SELECT * FROM AF_HTTP_SESSION")).isEmpty();
        System.out.println("Signature login migration preserved tables=" + before.size() + ", original operation=1, restored reference=1, restored sessions=0");
    }
    private static String quoted(String value) { return "\"" + value.replace("\"", "\"\"") + "\""; }
}
