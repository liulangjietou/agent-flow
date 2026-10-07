package io.agentflow.system;

import io.agentflow.common.Actor;
import io.agentflow.observability.DiagnosticContext;
import io.agentflow.template.ClasspathProcessTemplateCatalog;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 真实诊断线程在成功、异常及相邻租户检查之间保留请求来源并清理业务字段。
 * @author owlzhangfq@gmail.com
 */
class SystemCheckTraceTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualProbeThreadUsesAuthorizedTenantAndRestoresContextAfterEachRequest(boolean failMigration) throws Exception {
        var observed = new ArrayList<Map<String, String>>();
        var threads = new ArrayList<Thread>();
        Runnable capture = () -> {
            var context = MDC.getCopyOfContextMap();
            observed.add(context == null ? Map.of() : context);
            threads.add(Thread.currentThread());
        };
        var diagnostics = mock(SystemDiagnostics.class);
        var catalog = mock(ClasspathProcessTemplateCatalog.class);
        when(catalog.list()).thenReturn(List.of());
        doAnswer(call -> { capture.run(); return null; }).when(diagnostics).database();
        when(diagnostics.migrations()).thenAnswer(call -> {
            capture.run();
            if (failMigration) throw new IllegalStateException("private-diagnostic-failure");
            return "128";
        });
        doAnswer(call -> { capture.run(); return null; }).when(diagnostics).flowable(anyString());
        when(diagnostics.notifications(anyString())).thenAnswer(call -> {
            capture.run(); return new SystemDiagnostics.NotificationConfiguration(false, Set.of());
        });
        doAnswer(call -> { capture.run(); return null; }).when(diagnostics).sessions();
        when(diagnostics.organization(anyString())).thenAnswer(call -> { capture.run(); return false; });
        when(diagnostics.organizationSynchronization(anyString())).thenAnswer(call -> {
            capture.run(); return SystemDiagnostics.SynchronizationConfiguration.DISABLED;
        });
        when(diagnostics.attachments(anyString())).thenAnswer(call -> { capture.run(); return false; });
        when(diagnostics.modelConfiguration()).thenAnswer(call -> {
            capture.run(); return SystemDiagnostics.ModelConfiguration.DISABLED;
        });
        var service = new SystemCheckService(diagnostics, catalog, false, true, true);
        // 检查现有私有执行器的真实线程尾部，避免增加仅供测试使用的生产入口。
        var executor = (ThreadPoolExecutor) ReflectionTestUtils.getField(service, "executor");
        try {
            for (String tenant : List.of("tenant-a", "tenant-b")) {
                String traceId = UUID.randomUUID().toString();
                observed.clear();
                try (var scope = new DiagnosticContext(traceId, "outer-tenant", "outer-business", "outer-instance", "outer-task").open()) {
                    var before = MDC.getCopyOfContextMap();
                    var report = service.check(new Actor(tenant, "admin", Set.of("ADMIN")));
                    assertThat(observed).hasSize(9).allSatisfy(context -> assertThat(context)
                            .containsEntry("traceId", traceId).containsEntry("tenantId", tenant)
                            .doesNotContainKeys("businessNo", "processInstanceId", "taskId"));
                    assertThat(report.checks()).filteredOn(check -> check.id().equals("migrations")).singleElement()
                            .satisfies(check -> assertThat(check.code()).isEqualTo(failMigration ? "CHECK_FAILED" : "CHECK_PASSED"));
                    assertThat(report.toString()).doesNotContain("private-diagnostic-failure");
                    assertThat(MDC.getCopyOfContextMap()).isEqualTo(before);
                    assertThat(executor.submit(MDC::getCopyOfContextMap).get(2, TimeUnit.SECONDS)).isNullOrEmpty();
                }
            }
            assertThat(threads).doesNotContain(Thread.currentThread());
            assertThat(threads.stream().distinct()).hasSize(1);
        } finally { service.close(); }
    }
}
