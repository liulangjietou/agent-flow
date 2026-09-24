package io.agentflow.system;

import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.template.ClasspathProcessTemplateCatalog;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 依赖异常、无权限及不响应中断时的有界行为。
 * @author owlzhangfq@gmail.com
 */
class SystemCheckServiceTest {
    private final SystemDiagnostics diagnostics = mock(SystemDiagnostics.class);
    private final ClasspathProcessTemplateCatalog catalog = mock(ClasspathProcessTemplateCatalog.class);
    private final Actor admin = new Actor("tenant-for-check", "admin", Set.of("ADMIN"));

    @Test
    void rejectsBeforeQueryingAnyDependency() {
        var service = new SystemCheckService(diagnostics, catalog, true);
        try {
            assertThatThrownBy(() -> service.check(new Actor("demo", "designer", Set.of("PROCESS_ADMIN"))))
                    .isInstanceOf(DomainException.class).hasMessage("Required role is missing");
            verifyNoInteractions(diagnostics, catalog);
        } finally { service.close(); }
    }

    @Test
    void configuredOidcDoesNotClaimProviderHealthOrOrganizationIntegration() {
        when(catalog.list()).thenReturn(List.of());
        var service = new SystemCheckService(diagnostics, catalog, false, true);
        try {
            var report = service.check(admin);
            assertThat(report.checks().get(4).code()).isEqualTo("OIDC_CONFIGURED");
            assertThat(report.checks().get(4).status()).isEqualTo(SystemCheckService.Status.WARNING);
            assertThat(report.checks()).filteredOn(check -> check.id().equals("organization"))
                    .allSatisfy(check -> assertThat(check.status()).isEqualTo(SystemCheckService.Status.NOT_IMPLEMENTED));
        } finally { service.close(); }
    }

    @Test
    void redactsFailureAndContinuesOtherChecksInTheActorsTenant() {
        doThrow(new IllegalStateException("jdbc:secret-host password=secret-value")).when(diagnostics).database();
        when(diagnostics.migrations()).thenReturn("8");
        when(catalog.list()).thenReturn(List.of());
        var service = new SystemCheckService(diagnostics, catalog, false);
        try {
            var report = service.check(admin);
            assertThat(report.checks().get(0).status()).isEqualTo(SystemCheckService.Status.DOWN);
            assertThat(report.checks().get(1).status()).isEqualTo(SystemCheckService.Status.UP);
            assertThat(report.checks().get(2).status()).isEqualTo(SystemCheckService.Status.UP);
            assertThat(report.toString()).doesNotContain("secret-host", "secret-value");
            assertThat(report.checks().get(4).code()).isEqualTo("AUTH_PROVIDER_NOT_CONFIGURED");
            verify(diagnostics).flowable(admin.tenantId());
        } finally { service.close(); }
    }

    @Test
    void sharedSessionStorageMustActuallyAnswerAndFailuresAreRedacted() {
        when(catalog.list()).thenReturn(List.of());
        var service = new SystemCheckService(diagnostics, catalog, false, true, true);
        try {
            assertThat(service.check(admin).checks()).filteredOn(check -> check.id().equals("sessionStorage"))
                    .singleElement().satisfies(check -> assertThat(check.status()).isEqualTo(SystemCheckService.Status.UP));
            doThrow(new IllegalStateException("session secret-data")).when(diagnostics).sessions();
            var report = service.check(admin);
            assertThat(report.checks()).filteredOn(check -> check.id().equals("sessionStorage"))
                    .singleElement().satisfies(check -> assertThat(check.status()).isEqualTo(SystemCheckService.Status.DOWN));
            assertThat(report.toString()).doesNotContain("secret-data");
        } finally { service.close(); }
    }

    @Test
    void uninterruptibleDependencyTimesOutWithoutLaunchingMoreQueries() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        doAnswer(invocation -> {
            try {
                while (release.getCount() != 0) {
                    try { release.await(); } catch (InterruptedException ignored) { /* 模拟忽略中断的驱动。 */ }
                }
                return null;
            } finally { completed.countDown(); }
        }).when(diagnostics).database();
        when(catalog.list()).thenReturn(List.of());
        var service = new SystemCheckService(diagnostics, catalog, true, Duration.ofMillis(30));
        try {
            var report = service.check(admin);
            assertThat(report.checks().subList(0, 3)).allSatisfy(check -> {
                assertThat(check.status()).isEqualTo(SystemCheckService.Status.UNKNOWN);
                assertThat(check.code()).isEqualTo("CHECK_TIMEOUT");
            });
            assertThat(report.checks().get(4).code()).isEqualTo("DEMO_AUTH_ONLY");
            verify(diagnostics, never()).migrations();
            verify(diagnostics, never()).flowable(anyString());
        } finally {
            release.countDown();
            service.close();
            assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }
}
