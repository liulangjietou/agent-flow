package io.agentflow.organization;

import io.agentflow.observability.DiagnosticContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.JsonUtil;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;

/**
 * 真实 HTTP 验证可信目标、精确范围、整个流的时长和大小，以及部署配置绑定。
 * @author owlzhangfq@gmail.com
 */
class OrganizationSyncSourceTest {
    private OrganizationSyncTestSource fixture;
    private OrganizationSyncConfiguration configuration;
    private OrganizationSyncConfiguration.Target target;
    private HttpOrganizationSyncSource source;
    private OrganizationSyncBatch.Context context;

    @BeforeEach void setup() throws Exception {
        var json = new JsonUtil(new ObjectMapper()); fixture = new OrganizationSyncTestSource(json); target = fixture.target();
        configuration = new OrganizationSyncConfiguration(); configuration.setEnabled(true); configuration.setTenants(Map.of("tenant", target)); configuration.validate();
        source = new HttpOrganizationSyncSource(configuration, new OrganizationSyncCodec(json));
        context = new OrganizationSyncBatch.Context(UUID.randomUUID(), "tenant", "hr", 5, configuration.require("tenant").digest("tenant"), "admin", Instant.now(), null);
    }
    @AfterEach void close() { fixture.close(); }

    @Test void readsOnlyFixedSourceAndAppliedCursorWithoutSendingLocalOrganizationFacts() {
        var result = read(); assertThat(result.failure()).isNull(); assertThat(result.delta().afterRevision()).isEqualTo(5); assertThat(result.delta().revision()).isEqualTo(6);
        assertThat(fixture.calls).hasSize(1); var call = fixture.calls.get(0); assertThat(call.method()).isEqualTo("GET"); assertThat(call.path()).isEqualTo("/changes");
        assertThat(call.query()).containsExactlyInAnyOrderEntriesOf(Map.of("tenantId", "tenant", "sourceKey", "hr", "afterRevision", "5"));
        assertThat(call.authorization()).isEqualTo("Bearer synthetic-source-token"); assertThat(call.body()).isEmpty();
        assertThat(configuration.require("tenant").toString()).doesNotContain("synthetic-source-token", "127.0.0.1");
    }

    @ParameterizedTest @EnumSource(value = OrganizationSyncTestSource.Mode.class, names = {"TOO_LARGE", "INVALID_UTF8", "WRONG_CONTENT_TYPE", "WRONG_TENANT", "WRONG_SOURCE", "WRONG_CURSOR", "ROLE_FIELD", "DUPLICATE", "TRAILING"})
    void refusesUntrustedOrUnboundedResponseWithoutReturningPartialFacts(OrganizationSyncTestSource.Mode mode) {
        fixture.mode = mode; var result = read(); assertThat(result.failure()).isEqualTo(OrganizationSyncBatch.Failure.INVALID_SOURCE_DATA); assertThat(result.delta()).isNull();
        assertThat(fixture.calls).hasSize(1);
    }

    @ParameterizedTest @EnumSource(value = OrganizationSyncTestSource.Mode.class, names = {"REDIRECT", "HTTP_ERROR"})
    void refusesRemoteErrorsAndNeverFollowsRedirect(OrganizationSyncTestSource.Mode mode) {
        fixture.mode = mode; assertThat(read().failure()).isEqualTo(OrganizationSyncBatch.Failure.SOURCE_UNAVAILABLE); assertThat(fixture.calls).hasSize(1);
    }

    @Test void slowBodyUsesWholeRequestDeadlineAndExpiredLeaseSendsNothing() {
        target.setTimeoutSeconds(1); fixture.mode = OrganizationSyncTestSource.Mode.SLOW_BODY; Instant at = Instant.now();
        assertThat(read().failure()).isEqualTo(OrganizationSyncBatch.Failure.SOURCE_TIMEOUT); assertThat(Duration.between(at, Instant.now())).isLessThan(Duration.ofSeconds(4));
        assertThat(source.read(context, Instant.now().minusSeconds(1)).failure()).isEqualTo(OrganizationSyncBatch.Failure.SOURCE_TIMEOUT); assertThat(fixture.calls).hasSize(1);
    }

    @Test void targetAndSourceDriftCannotRedirectAnOldBatchButCredentialRotationKeepsIdentity() {
        target.setEndpoint(fixture.endpoint() + "other/"); assertThat(read().failure()).isEqualTo(OrganizationSyncBatch.Failure.SOURCE_CHANGED); assertThat(fixture.calls).isEmpty();
        target.setEndpoint(fixture.endpoint()); target.setSourceKey("other"); assertThat(read().failure()).isEqualTo(OrganizationSyncBatch.Failure.SOURCE_CHANGED);
        target.setSourceKey("hr"); target.setToken("rotated-token"); assertThat(read().failure()).isNull();
        assertThat(fixture.calls.get(0).authorization()).isEqualTo("Bearer rotated-token"); configuration.setEnabled(false);
        assertThat(read().failure()).isEqualTo(OrganizationSyncBatch.Failure.SOURCE_CHANGED); assertThat(fixture.calls).hasSize(1);
    }

    @Test void noNetworkMayRunInsideAnOrganizationTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThatThrownBy(this::read).isInstanceOf(IllegalStateException.class); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        assertThat(fixture.calls).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"http://example.invalid", "file:///tmp/source", "https://user:secret@example.invalid", "https://example.invalid/?q=1", "https://example.invalid/#fragment", "https://example.invalid:0/", "https://example.invalid/%2fprivate", "https://example.invalid/a.b"})
    void configurationRejectsUnsupportedOrAmbiguousTargets(String endpoint) {
        target.setEndpoint(endpoint); assertThatThrownBy(configuration::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test void deploymentBindingHasNoTenantFallbackAndRequiresExplicitUnauthenticatedLoopback() {
        var values = Map.of("agentflow.organization-sync.enabled", "true", "agentflow.organization-sync.worker-enabled", "false",
                "agentflow.organization-sync.tenants[tenant].source-key", "hr", "agentflow.organization-sync.tenants[tenant].endpoint", fixture.endpoint(),
                "agentflow.organization-sync.tenants[tenant].allow-unauthenticated-loopback", "true");
        var bound = new Binder(new MapConfigurationPropertySource(values)).bind("agentflow.organization-sync", Bindable.of(OrganizationSyncConfiguration.class)).orElseThrow(AssertionError::new);
        bound.validate(); assertThat(bound.require("tenant").token()).isEmpty(); assertThat(bound.isWorkerEnabled()).isFalse(); assertThat(bound.destination("foreign")).isEmpty();
        target.setToken(""); assertThatThrownBy(configuration::validate).isInstanceOf(IllegalStateException.class);
        target.setAllowUnauthenticatedLoopback(true); configuration.validate(); target.setEndpoint("https://example.invalid/");
        assertThatThrownBy(configuration::validate).isInstanceOf(IllegalStateException.class);
    }

    private HttpOrganizationSyncSource.Result read() { return source.read(context, Instant.now().plusSeconds(5)); }

    @Test void sourceTraceUsesHeaderWithoutChangingCursorOrSendingLocalFacts() {
        String trace = UUID.randomUUID().toString();
        try (var scope = new DiagnosticContext(trace, "tenant").open()) {
            readsOnlyFixedSourceAndAppliedCursorWithoutSendingLocalOrganizationFacts();
        }
        assertThat(fixture.traceIds).containsExactly(trace);
    }

}
