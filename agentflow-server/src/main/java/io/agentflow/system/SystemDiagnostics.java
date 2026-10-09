package io.agentflow.system;

import io.agentflow.agent.AssistConfiguration;
import io.agentflow.attachment.LocalAttachmentStore;
import io.agentflow.notification.NotificationChannel;
import io.agentflow.notification.NotificationDestinations;
import io.agentflow.organization.OrganizationSyncConfiguration;
import io.agentflow.system.mapper.SystemDiagnosticsMapper;

import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 只读诊断实际依赖，不执行迁移、修复、部署或业务写入。
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class SystemDiagnostics {
    private final SystemDiagnosticsMapper sqlMapper;
    private final Flyway flyway;
    private final RepositoryService repository;
    private final RuntimeService runtime;
    private final TaskService tasks;
    private final HistoryService history;
    private final LocalAttachmentStore attachments;
    private final AssistConfiguration assist;
    private final NotificationDestinations destinations;
    private final OrganizationSyncConfiguration organizationSync;
    private final boolean assistWorkerEnabled;
    private final boolean notificationWorkerEnabled;

    /** 复用运行时依赖，避免诊断独立连接与真实应用配置不一致。 */
    public SystemDiagnostics(
            SystemDiagnosticsMapper sqlMapper,
            Flyway flyway,
            RepositoryService repository,
            RuntimeService runtime,
            TaskService tasks,
            HistoryService history,
            LocalAttachmentStore attachments,
            AssistConfiguration assist,
            NotificationDestinations destinations,
            OrganizationSyncConfiguration organizationSync,
            @Value("${agentflow.assist.worker-enabled:true}") boolean assistWorkerEnabled,
            @Value("${agentflow.notifications.delivery-worker-enabled:false}")
                    boolean notificationWorkerEnabled) {
        this.sqlMapper = sqlMapper;
        this.flyway = flyway;
        this.repository = repository;
        this.runtime = runtime;
        this.tasks = tasks;
        this.history = history;
        this.attachments = attachments;
        this.assist = assist;
        this.destinations = destinations;
        this.organizationSync = organizationSync;
        this.assistWorkerEnabled = assistWorkerEnabled;
        this.notificationWorkerEnabled = notificationWorkerEnabled;
    }

    /** 在真实连接上执行轻量查询，连接和语句均在本次检查后释放。 */
    public void database() {
        try {
            if (sqlMapper.database() != 1) throw new IllegalStateException("Database probe failed");
        } catch (DataAccessException exception) {
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

    /** 验证当前租户消息存储并读取已冻结的渠道配置，不触发发送或改变阅读状态。 */
    public NotificationConfiguration notifications(String tenantId) {
        try {
            sqlMapper.notifications(tenantId);
        } catch (DataAccessException exception) {
            throw new IllegalStateException("Notification storage probe failed", exception);
        }
        return new NotificationConfiguration(
                notificationWorkerEnabled, destinations.enabledChannels(tenantId));
    }

    /** 复用执行入口的配置校验；不请求令牌、连接模型或外发任何业务输入。 */
    public ModelConfiguration modelConfiguration() {
        if (!assist.isEnabled()) return ModelConfiguration.DISABLED;
        assist.requireAvailable();
        return assistWorkerEnabled ? ModelConfiguration.CONFIGURED : ModelConfiguration.WORKER_DISABLED;
    }

    /** 只读取当前租户的目录启用事实；不初始化目录，也不读取人员资料或推断审批资格。 */
    public boolean organization(String tenantId) {
        try {
            return !sqlMapper.organization(tenantId).isEmpty();
        } catch (DataAccessException exception) {
            throw new IllegalStateException("Organization storage probe failed", exception);
        }
    }

    /** 核对本租户可信来源与已登记身份，不注册来源、读取人员正文或发送网络请求。 */
    public SynchronizationConfiguration organizationSynchronization(String tenantId) {
        if (!organizationSync.isEnabled()) return SynchronizationConfiguration.DISABLED;
        var target = organizationSync.destination(tenantId);
        if (target.isEmpty()) return SynchronizationConfiguration.UNCONFIGURED;
        try {
            var sources = sqlMapper.organizationSource(tenantId);
            if (!sources.isEmpty() && !sources.get(0).equals(target.get().sourceKey()))
                return SynchronizationConfiguration.SOURCE_CHANGED;
        } catch (DataAccessException exception) {
            throw new IllegalStateException(
                    "Organization synchronization storage probe failed", exception);
        }
        return organizationSync.isWorkerEnabled()
                ? SynchronizationConfiguration.CONFIGURED
                : SynchronizationConfiguration.WORKER_DISABLED;
    }

    /** 查询附件元数据表和已配置目录的访问权限，不读取内容、写探针文件或声称完成扫描。 */
    public boolean attachments(String tenantId) {
        if (!attachments.enabled()) return false;
        if (!attachments.available())
            throw new IllegalStateException("Attachment directory probe failed");
        try {
            sqlMapper.attachments(tenantId);
            return true;
        } catch (DataAccessException exception) {
            throw new IllegalStateException("Attachment metadata probe failed", exception);
        }
    }

    /** 只验证会话表可查询，不读取标识、身份或令牌内容，也不创建测试会话。 */
    public void sessions() {
        try {
            sqlMapper.sessions();
        } catch (DataAccessException exception) {
            throw new IllegalStateException("Session storage probe failed", exception);
        }
    }

    /**
     * 不含地址、身份与凭据的本租户通知配置快照。
     *
     * @author owlzhangfq@gmail.com
     */
    public record NotificationConfiguration(
            boolean workerEnabled, Set<NotificationChannel> channels) {}

    /**
     * 配置校验与后台开关不代表真实模型连接或输出质量已验收。
     *
     * @author owlzhangfq@gmail.com
     */
    public enum ModelConfiguration {
        DISABLED,
        WORKER_DISABLED,
        CONFIGURED
    }

    /**
     * 配置和登记检查不代表来源连接、批次读取或人工应用已经成功。
     *
     * @author owlzhangfq@gmail.com
     */
    public enum SynchronizationConfiguration {
        DISABLED,
        UNCONFIGURED,
        SOURCE_CHANGED,
        WORKER_DISABLED,
        CONFIGURED
    }
}
