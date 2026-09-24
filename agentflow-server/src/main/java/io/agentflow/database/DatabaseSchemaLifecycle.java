package io.agentflow.database;

import java.sql.SQLException;
import javax.sql.DataSource;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.impl.cfg.StandaloneProcessEngineConfiguration;
import org.flywaydb.core.Flyway;

/**
 * 部署阶段的业务与引擎结构生命周期；不装配 Web、业务调度或示例流程。
 * @author owlzhangfq@gmail.com
 */
public final class DatabaseSchemaLifecycle {
    private DatabaseSchemaLifecycle() { }

    /** 使用发布包中的迁移，禁止清库、自动基线及忽略未知的未来版本。 */
    public static Flyway flyway(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .cleanDisabled(true).baselineOnMigrate(false).ignoreMigrationPatterns(new String[0]).load();
    }

    /** 业务表迁移完成后初始化同版本引擎表；失败保留现场，由部署人员处理。 */
    public static void migrate(DataSource dataSource) {
        flyway(dataSource).migrate();
        checkEngine(dataSource, ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
        validate(dataSource);
    }

    /** 核对完整发布包的业务迁移和引擎结构，不执行迁移或部署流程。 */
    public static void validate(DataSource dataSource) {
        validateBusiness(flyway(dataSource));
        checkEngine(dataSource, ProcessEngineConfiguration.DB_SCHEMA_UPDATE_FALSE);
    }

    /** 生产启动复用此检查；待执行和高于当前发布包的迁移均不能放行。 */
    public static void validateBusiness(Flyway flyway) {
        // 发布包决定所需版本，运行参数不能通过 target 或 locations 隐藏缺失迁移。
        var strict = flyway(flyway.getConfiguration().getDataSource());
        if (strict.info().pending().length != 0) {
            throw new IllegalStateException("Database has a pending migration; run the offline schema command before starting production");
        }
        strict.validate();
    }

    /** 业务和引擎必须使用同一连接的当前 schema，防止元数据检查误读其他 schema。 */
    public static String currentSchema(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            return connection.getSchema();
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot resolve database schema", exception);
        }
    }

    private static void checkEngine(DataSource dataSource, String mode) {
        var engine = new SchemaOnlyEngineConfiguration()
                .setDataSource(dataSource).setDatabaseSchema(currentSchema(dataSource)).setDatabaseSchemaUpdate(mode)
                .setAsyncExecutorActivate(false).setAsyncHistoryExecutorActivate(false).buildProcessEngine();
        engine.close();
    }

    /**
     * 使用 Flowable 配置扩展点关闭仅运行节点才需要的锁清理，保留正常资源关闭。
     * @author owlzhangfq@gmail.com
     */
    private static final class SchemaOnlyEngineConfiguration extends StandaloneProcessEngineConfiguration {
        /** 此引擎从未执行任务；默认关闭回调的 UPDATE 会破坏 PostgreSQL 只读校验。 */
        @Override
        public Runnable getProcessEngineCloseRunnable() { return null; }
    }
}
