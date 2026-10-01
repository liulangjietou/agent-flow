package io.agentflow.system;

import io.agentflow.agent.AssistConfiguration;
import io.agentflow.attachment.LocalAttachmentStore;
import io.agentflow.common.DomainException;
import io.agentflow.notification.NotificationDestinations;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** 模型诊断沿用真实配置校验，并区分停用适配器和停用后台。
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

    private SystemDiagnostics diagnostics(AssistConfiguration configuration, boolean worker) {
        return new SystemDiagnostics(mock(DataSource.class), mock(Flyway.class), mock(RepositoryService.class),
                mock(RuntimeService.class), mock(TaskService.class), mock(HistoryService.class),
                mock(LocalAttachmentStore.class), configuration, mock(NotificationDestinations.class), worker, false);
    }
}
