package io.agentflow.system;

import io.agentflow.agent.AssistConfiguration;
import io.agentflow.attachment.LocalAttachmentStore;
import io.agentflow.common.DomainException;
import io.agentflow.notification.NotificationDestinations;
import io.agentflow.organization.OrganizationSyncConfiguration;
import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** 模型和组织来源诊断沿用真实配置校验，并核对当前租户的只读登记。
 * @author owlzhangfq@gmail.com
 */
class SystemDiagnosticsConfigurationTest {
    @Test
    void modelReadinessRequiresEnabledValidConfigurationAndWorker() {
        var configuration = new AssistConfiguration();
        assertThat(diagnostics(configuration, true).modelConfiguration()).isEqualTo(SystemDiagnostics.ModelConfiguration.DISABLED);
        configuration.setEnabled(true);
        assertThatThrownBy(() -> diagnostics(configuration, false).modelConfiguration()).isInstanceOf(DomainException.class);
        configuration.setEndpoint("https://model.example.invalid/v1/chat/completions");
        configuration.setModel("configured-model");
        assertThat(diagnostics(configuration, false).modelConfiguration()).isEqualTo(SystemDiagnostics.ModelConfiguration.WORKER_DISABLED);
        assertThat(diagnostics(configuration, true).modelConfiguration()).isEqualTo(SystemDiagnostics.ModelConfiguration.CONFIGURED);
    }

    @ParameterizedTest @ValueSource(strings = {"disabled", "unconfigured", "worker-disabled", "configured", "source-changed"})
    void synchronizationReadinessUsesOnlyTheCurrentTenantAndDoesNotRegisterOrChangeSource(String state) {
        var data = new DriverManagerDataSource("jdbc:h2:mem:sync-diagnostics-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(data);
        jdbc.execute("CREATE TABLE organization_sync_source(tenant_id VARCHAR(64) PRIMARY KEY, source_key VARCHAR(64))");
        jdbc.update("INSERT INTO organization_sync_source VALUES('other','other-source')");
        var configuration = new OrganizationSyncConfiguration(); var target = new OrganizationSyncConfiguration.Target();
        target.setSourceKey("hr"); target.setEndpoint("https://organization.example.invalid/source/"); target.setToken("synthetic-secret");
        configuration.setEnabled(!state.equals("disabled")); configuration.setWorkerEnabled(!state.equals("worker-disabled"));
        configuration.setTenants(Map.of(state.equals("unconfigured") ? "other" : "tenant", target)); configuration.validate();
        if (state.equals("source-changed")) jdbc.update("INSERT INTO organization_sync_source VALUES('tenant','previous')");
        var before = jdbc.queryForList("SELECT * FROM organization_sync_source ORDER BY tenant_id");
        try {
            var result = diagnostics(data, configuration).organizationSynchronization("tenant");
            assertThat(result.name()).isEqualTo(state.toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
            assertThat(jdbc.queryForList("SELECT * FROM organization_sync_source ORDER BY tenant_id")).isEqualTo(before);
        } finally { jdbc.execute("SHUTDOWN"); }
    }

    @Test void configuredSourceCannotHideMissingSynchronizationStorage() {
        var data = new DriverManagerDataSource("jdbc:h2:mem:sync-diagnostics-missing-" + UUID.randomUUID(), "sa", "");
        var configuration = new OrganizationSyncConfiguration(); var target = new OrganizationSyncConfiguration.Target();
        target.setSourceKey("hr"); target.setEndpoint("https://organization.example.invalid/"); target.setToken("synthetic-secret");
        configuration.setEnabled(true); configuration.setTenants(Map.of("tenant", target));
        assertThatThrownBy(() -> diagnostics(data, configuration).organizationSynchronization("tenant"))
                .isInstanceOf(IllegalStateException.class).hasMessage("Organization synchronization storage probe failed");
    }

    private SystemDiagnostics diagnostics(DataSource data, OrganizationSyncConfiguration configuration) {
        return new SystemDiagnostics(data, mock(Flyway.class), mock(RepositoryService.class), mock(RuntimeService.class),
                mock(TaskService.class), mock(HistoryService.class), mock(LocalAttachmentStore.class),
                new AssistConfiguration(), mock(NotificationDestinations.class), configuration, true, false);
    }

    private SystemDiagnostics diagnostics(AssistConfiguration configuration, boolean worker) {
        return new SystemDiagnostics(mock(DataSource.class), mock(Flyway.class), mock(RepositoryService.class),
                mock(RuntimeService.class), mock(TaskService.class), mock(HistoryService.class),
                mock(LocalAttachmentStore.class), configuration, mock(NotificationDestinations.class), new OrganizationSyncConfiguration(), worker, false);
    }
}
