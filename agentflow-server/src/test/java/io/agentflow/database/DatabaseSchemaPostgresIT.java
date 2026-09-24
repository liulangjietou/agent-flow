package io.agentflow.database;

import io.agentflow.AgentflowApplication;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flywaydb.core.Flyway;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 在明确提供的隔离 PostgreSQL 中验证部署命令；使用 -Dtest=DatabaseSchemaPostgresIT 单独执行。
 * @author owlzhangfq@gmail.com
 */
class DatabaseSchemaPostgresIT {
    @Test
    void upgradesAnOldBusinessSchemaAndPreservesAnExistingTaskAndDraft() {
        var database = database();
        Flyway.configure().dataSource(database).target("22").load().migrate();
        var jdbc = new JdbcTemplate(database);
        jdbc.update("""
                INSERT INTO approval_application
                  (id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version)
                VALUES (?, 'maintenance', 'OLD-1', 'maintenance', 1, 'employee', '待保留草稿', '{"amount":"100.25"}', 'DRAFT', 0, 1)
                """, UUID.randomUUID().toString());
        var engine = ProcessEngineConfiguration.createStandaloneProcessEngineConfiguration().setDataSource(database)
                .setDatabaseSchema(DatabaseSchemaLifecycle.currentSchema(database)).setDatabaseSchemaUpdate("true")
                .setAsyncExecutorActivate(false).buildProcessEngine();
        String instance;
        String task;
        try {
            engine.getRepositoryService().createDeployment().addString("maintenance.bpmn20.xml", """
                    <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="maintenance">
                      <process id="maintenance" isExecutable="true">
                        <startEvent id="start"/><sequenceFlow id="begin" sourceRef="start" targetRef="approve"/>
                        <userTask id="approve"/><sequenceFlow id="finish" sourceRef="approve" targetRef="end"/>
                        <endEvent id="end"/>
                      </process>
                    </definitions>
                    """).deploy();
            instance = engine.getRuntimeService().startProcessInstanceByKey("maintenance").getId();
            task = engine.getTaskService().createTaskQuery().processInstanceId(instance).singleResult().getId();
        } finally { engine.close(); }
        var draftBefore = jdbc.queryForList("SELECT * FROM approval_application");
        var taskBefore = jdbc.queryForList("SELECT * FROM ACT_RU_TASK");
        var outcome = command(database, "migrate");
        assertThat(outcome.code()).as(outcome.output()).isZero();
        assertThat(command(database, "validate").code()).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM approval_application")).isEqualTo(draftBefore);
        assertThat(jdbc.queryForList("SELECT * FROM ACT_RU_TASK")).isEqualTo(taskBefore);
        engine = ProcessEngineConfiguration.createStandaloneProcessEngineConfiguration().setDataSource(database)
                .setDatabaseSchema(DatabaseSchemaLifecycle.currentSchema(database)).setDatabaseSchemaUpdate("false")
                .setAsyncExecutorActivate(false).buildProcessEngine();
        try {
            engine.getTaskService().complete(task);
            assertThat(engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceId(instance).finished().count())
                    .isEqualTo(1);
        } finally { engine.close(); }
    }

    @Test
    void commandInitializesBothSchemasAndValidatesThroughEnforcedReadOnlyConnections() {
        var database = database();
        assertThat(command(database, "validate").code()).isEqualTo(1);
        assertThat(tables(database)).isEmpty();
        assertThat(command(database, "migrate").code()).isZero();
        var before = snapshot(database);
        assertThat(command(database, "validate").code()).isZero();
        assertThat(command(database, "migrate").code()).isZero();
        assertThat(snapshot(database)).isEqualTo(before);
        assertThat(new JdbcTemplate(database).queryForObject("SELECT COUNT(*) FROM ACT_RE_PROCDEF", Integer.class)).isZero();
    }

    @Test
    void parallelMigrationCommandIsRejectedAndTheLockIsReleasedAfterCompletion() throws Exception {
        var database = database();
        try (var connection = database.getConnection(); var statement = connection.prepareStatement("SELECT pg_advisory_lock(?)")) {
            statement.setLong(1, DatabaseSchemaCommand.MIGRATION_LOCK);
            statement.execute();
            var result = command(database, "migrate");
            assertThat(result.code()).isEqualTo(1);
            assertThat(result.output()).contains("phase=lock");
            assertThat(tables(database)).isEmpty();
        }
        assertThat(command(database, "migrate").code()).isZero();
        assertThat(command(database, "validate").code()).isZero();
    }

    @Test
    void checksumFailuresDoNotRepairOrModifyTheDatabase() {
        var database = database();
        assertThat(command(database, "migrate").code()).isZero();
        new JdbcTemplate(database).update("UPDATE flyway_schema_history SET checksum=123 WHERE version='1'");
        var before = snapshot(database);
        assertThat(command(database, "validate").code()).isEqualTo(1);
        assertThat(command(database, "migrate").code()).isEqualTo(1);
        assertThat(snapshot(database)).isEqualTo(before);
    }

    @Test
    void migratedDatabaseStartsInProductionWithoutChangingMigrationsOrDeployingExamples() {
        var database = database();
        assertThat(command(database, "migrate").code()).isZero();
        var before = snapshot(database);
        try (var context = new SpringApplicationBuilder(AgentflowApplication.class).run(
                "--spring.profiles.active=prod", "--spring.datasource.url=" + database.getUrl(),
                "--spring.datasource.username=" + database.getUsername(), "--spring.datasource.password=" + database.getPassword(),
                "--agentflow.web.allowed-origin=https://flow.example", "--server.port=0", "--logging.level.root=ERROR")) {
            assertThat(context.isActive()).isTrue();
        }
        assertThat(snapshot(database)).isEqualTo(before);
    }

    private static Result command(DriverManagerDataSource database, String action) {
        var buffer = new ByteArrayOutputStream();
        var stream = new PrintStream(buffer);
        int code = DatabaseSchemaCommand.run(new String[]{"--schema=" + action}, Map.of(
                "AGENTFLOW_DATASOURCE_URL", database.getUrl(), "AGENTFLOW_DATASOURCE_USERNAME", database.getUsername(),
                "AGENTFLOW_DATASOURCE_PASSWORD", database.getPassword()), stream, stream);
        assertThat(buffer.toString()).doesNotContain(database.getPassword());
        return new Result(code, buffer.toString());
    }

    private static DriverManagerDataSource database() {
        String url = System.getenv("AGENTFLOW_SCHEMA_TEST_URL");
        assertThat(url).as("An isolated PostgreSQL URL is required").startsWith("jdbc:postgresql://").doesNotContain("?");
        String username = System.getenv("AGENTFLOW_SCHEMA_TEST_USERNAME");
        String password = System.getenv("AGENTFLOW_SCHEMA_TEST_PASSWORD");
        var administrator = new DriverManagerDataSource(url, username, password);
        String schema = "schema71_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(administrator).execute("CREATE SCHEMA " + schema);
        return new DriverManagerDataSource(url + "?currentSchema=" + schema, username, password);
    }

    private static List<String> tables(DriverManagerDataSource database) {
        return new JdbcTemplate(database).queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema=current_schema() ORDER BY table_name", String.class);
    }

    private static Map<String, List<String>> snapshot(DriverManagerDataSource database) {
        var result = new LinkedHashMap<String, List<String>>();
        var jdbc = new JdbcTemplate(database);
        for (String table : tables(database)) {
            result.put(table, jdbc.queryForList("SELECT row_to_json(t)::text FROM \"" + table + "\" t ORDER BY row_to_json(t)::text", String.class));
        }
        return result;
    }

    /**
     * 命令退出状态及不含凭证的可见输出。
     * @author owlzhangfq@gmail.com
     */
    private record Result(int code, String output) { }
}
