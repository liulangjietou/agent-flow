package io.agentflow.database;

import io.agentflow.AgentflowApplication;
import java.util.UUID;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 生产进程只能检查结构，不能在启动失败前悄悄迁移业务库。
 * @author owlzhangfq@gmail.com
 */
class ProductionSchemaStartupTest {
    @Test
    void rejectsPendingMigrationsWithoutAdvancingTheDatabase() {
        String url = database();
        var dataSource = new DriverManagerDataSource(url, "sa", "");
        Flyway.configure().dataSource(dataSource).target("22").load().migrate();
        var engine = ProcessEngineConfiguration.createStandaloneProcessEngineConfiguration()
                .setDataSource(dataSource).setDatabaseSchemaUpdate("true").setAsyncExecutorActivate(false).buildProcessEngine();
        engine.close();

        assertThatThrownBy(() -> start(url)).hasStackTraceContaining("pending migration");
        assertThat(new JdbcTemplate(dataSource).queryForObject(
                "SELECT MAX(CAST(\"version\" AS INTEGER)) FROM \"flyway_schema_history\"", Integer.class)).isEqualTo(22);
    }

    @Test
    void rejectsAnEmptyDatabaseWithoutCreatingBusinessTables() {
        String url = database();
        assertThatThrownBy(() -> start(url)).isInstanceOf(RuntimeException.class);
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC'", Integer.class))
                .isZero();
    }

    @Test
    void startsAfterOfflineMigrationWithoutDeployingExamples() {
        String url = database();
        var dataSource = new DriverManagerDataSource(url, "sa", "");
        DatabaseSchemaLifecycle.migrate(dataSource);
        var jdbc = new JdbcTemplate(dataSource);
        var before = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        start(url);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ACT_RE_PROCDEF", Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\""))
                .isEqualTo(before);
    }

    @Test
    void rejectsAutomaticEngineChangesBeforeCreatingBusinessTables() {
        String url = database();
        assertThatThrownBy(() -> start(url, "--flowable.database-schema-update=true"))
                .hasStackTraceContaining("Production requires Flyway validation");
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC'", Integer.class))
                .isZero();
    }

    @Test
    void aSeparateFlywayConnectionCannotValidateTheWrongApplicationDatabase() {
        String applicationUrl = database();
        String otherUrl = database();
        DatabaseSchemaLifecycle.migrate(new DriverManagerDataSource(otherUrl, "sa", ""));
        assertThatThrownBy(() -> start(applicationUrl, "--spring.flyway.url=" + otherUrl,
                "--spring.flyway.user=sa", "--spring.flyway.password="))
                .hasStackTraceContaining("pending migration");
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(applicationUrl, "sa", ""));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC'", Integer.class))
                .isZero();
    }

    private static void start(String url, String... overrides) {
        var arguments = new java.util.ArrayList<>(java.util.List.of(
                "--spring.profiles.active=prod", "--spring.datasource.url=" + url,
                "--spring.datasource.username=sa", "--spring.datasource.password=",
                "--spring.datasource.driver-class-name=org.h2.Driver",
                "--agentflow.web.allowed-origin=https://flow.example", "--server.port=0",
                "--logging.level.root=ERROR"));
        arguments.addAll(java.util.List.of(overrides));
        try (var context = new SpringApplicationBuilder(AgentflowApplication.class).run(arguments.toArray(String[]::new))) {
            assertThat(context.isActive()).isTrue();
        }
    }

    private static String database() { return "jdbc:h2:mem:production-schema-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"; }
}
