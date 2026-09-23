package io.agentflow.system;

import org.flywaydb.core.Flyway;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * 只读诊断实际依赖，不执行迁移、修复、部署或业务写入。
 * @author owlzhangfq@gmail.com
 */
@Component
public class SystemDiagnostics {
    private static final int QUERY_TIMEOUT_SECONDS = 3;
    private final DataSource dataSource;
    private final Flyway flyway;
    private final RepositoryService repository;
    private final RuntimeService runtime;
    private final TaskService tasks;
    private final HistoryService history;

    /** 复用运行时依赖，避免诊断独立连接与真实应用配置不一致。 */
    public SystemDiagnostics(DataSource dataSource, Flyway flyway, RepositoryService repository,
                             RuntimeService runtime, TaskService tasks, HistoryService history) {
        this.dataSource = dataSource;
        this.flyway = flyway;
        this.repository = repository;
        this.runtime = runtime;
        this.tasks = tasks;
        this.history = history;
    }

    /** 在真实连接上执行轻量查询，连接和语句均在本次检查后释放。 */
    public void database() {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (var result = statement.executeQuery("SELECT 1")) {
                if (!result.next() || result.getInt(1) != 1) throw new IllegalStateException("Database probe failed");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Database probe failed", exception);
        }
    }

    /** 验证已应用迁移的校验和及待执行项，只返回版本号。 */
    public String migrations() {
        var validation = flyway.validateWithResult();
        var info = flyway.info();
        var current = info.current();
        if (!validation.validationSuccessful || current == null || info.pending().length != 0) {
            throw new IllegalStateException("Migration validation failed");
        }
        return current.getVersion().getVersion();
    }

    /** 查询当前租户的四类引擎表；无流程或任务是有效状态，不返回数量及其他租户的信息。 */
    public void flowable(String tenantId) {
        repository.createProcessDefinitionQuery().processDefinitionTenantId(tenantId).count();
        runtime.createProcessInstanceQuery().processInstanceTenantId(tenantId).count();
        tasks.createTaskQuery().taskTenantId(tenantId).count();
        history.createHistoricProcessInstanceQuery().processInstanceTenantId(tenantId).count();
    }
}
