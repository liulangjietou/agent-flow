package io.agentflow.database;

import java.util.UUID;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 迁移保持既有流程运行事实，校验拒绝缺失、篡改和高版本迁移。
 * @author owlzhangfq@gmail.com
 */
class DatabaseSchemaLifecycleTest {
    @Test
    void runtimeTargetOverrideCannotHideMigrationsRequiredByTheRelease() {
        var dataSource = database();
        var limited = Flyway.configure().dataSource(dataSource).target("22").load();
        limited.migrate();
        assertThatThrownBy(() -> DatabaseSchemaLifecycle.validateBusiness(limited))
                .hasMessageContaining("pending migration");
    }

    @Test
    void initializesBothSchemasWithoutDeployingExamplesAndCanRepeat() {
        var dataSource = database();
        DatabaseSchemaLifecycle.migrate(dataSource);
        var jdbc = new JdbcTemplate(dataSource);
        var history = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ACT_RE_PROCDEF", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_application", Integer.class)).isZero();

        DatabaseSchemaLifecycle.migrate(dataSource);
        DatabaseSchemaLifecycle.validate(dataSource);
        assertThat(jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\""))
                .isEqualTo(history);
    }

    @Test
    void upgradesBusinessSchemaWhileAnExistingUserTaskCanStillComplete() {
        var dataSource = database();
        Flyway.configure().dataSource(dataSource).target("22").load().migrate();
        var engine = ProcessEngineConfiguration.createStandaloneProcessEngineConfiguration().setDataSource(dataSource)
                .setDatabaseSchemaUpdate("true").setAsyncExecutorActivate(false).buildProcessEngine();
        String taskId;
        String instanceId;
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
            instanceId = engine.getRuntimeService().startProcessInstanceByKey("maintenance").getId();
            taskId = engine.getTaskService().createTaskQuery().processInstanceId(instanceId).singleResult().getId();
        } finally { engine.close(); }
        var jdbc = new JdbcTemplate(dataSource);
        var task = jdbc.queryForMap("SELECT * FROM ACT_RU_TASK WHERE ID_=?", taskId);
        DatabaseSchemaLifecycle.migrate(dataSource);
        assertThat(jdbc.queryForMap("SELECT * FROM ACT_RU_TASK WHERE ID_=?", taskId)).isEqualTo(task);
        engine = ProcessEngineConfiguration.createStandaloneProcessEngineConfiguration().setDataSource(dataSource)
                .setDatabaseSchemaUpdate("false").setAsyncExecutorActivate(false).buildProcessEngine();
        try {
            engine.getTaskService().complete(taskId);
            assertThat(engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceId(instanceId)
                    .finished().count()).isEqualTo(1);
        } finally { engine.close(); }
    }

    @Test
    void corruptedChecksumsCannotBeRepairedByMigrateOrValidation() {
        var dataSource = database();
        DatabaseSchemaLifecycle.migrate(dataSource);
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.update("UPDATE \"flyway_schema_history\" SET \"checksum\"=123 WHERE \"version\"='1'");
        var history = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\"");
        assertThatThrownBy(() -> DatabaseSchemaLifecycle.validate(dataSource)).hasMessageContaining("checksum mismatch");
        assertThatThrownBy(() -> DatabaseSchemaLifecycle.migrate(dataSource)).hasMessageContaining("checksum mismatch");
        assertThat(jdbc.queryForList("SELECT * FROM \"flyway_schema_history\"")).isEqualTo(history);
    }

    @Test
    void olderBinaryCannotAcceptOrMigrateAnUnknownFutureVersion() {
        var dataSource = database();
        DatabaseSchemaLifecycle.migrate(dataSource);
        new JdbcTemplate(dataSource).update("UPDATE \"flyway_schema_history\" SET \"version\"='999' WHERE \"version\"='23'");
        assertThatThrownBy(() -> DatabaseSchemaLifecycle.validate(dataSource)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> DatabaseSchemaLifecycle.migrate(dataSource)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void aForeignDatabaseIsNotSilentlyBaselined() {
        var dataSource = database();
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE customer_data (id INTEGER PRIMARY KEY)");
        jdbc.update("INSERT INTO customer_data VALUES (1)");
        assertThatThrownBy(() -> DatabaseSchemaLifecycle.migrate(dataSource)).hasMessageContaining("non-empty schema");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_data", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC'", Integer.class))
                .isEqualTo(1);
    }

    private static DriverManagerDataSource database() {
        return new DriverManagerDataSource("jdbc:h2:mem:schema-lifecycle-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    }
}
