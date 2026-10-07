package io.agentflow.servicetask;

import io.agentflow.observability.DiagnosticContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentflow.common.JsonUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * 实际回环 HTTP 验证目标绑定、只读查询、严格信封与有界正文，协议夹具不代表真实企业验收。
 * @author owlzhangfq@gmail.com
 */
class ServiceTaskGatewayTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private ServiceTaskTestProvider provider;
    private ServiceTaskGatewayConfiguration config;
    private ServiceTaskGatewayConfiguration.Operation declaration;
    private HttpServiceTaskGateway gateway;
    private ServiceTaskOperation.Input input;

    @BeforeEach
    void setup() throws Exception {
        provider = new ServiceTaskTestProvider(new JsonUtil(mapper)); declaration = provider.declaration("receipt.register");
        config = new ServiceTaskGatewayConfiguration(); config.setEnabled(true); config.setTenants(Map.of("demo", List.of(declaration))); config.validate();
        gateway = new HttpServiceTaskGateway(config, mapper);
        var selected = config.find("demo", "receipt.register", 1).orElseThrow();
        var binding = new ServiceTaskCommand.Binding(UUID.randomUUID(), 1, "request", 1, "a".repeat(64), "process", "execution", "service");
        input = new ServiceTaskOperation.Input(new ServiceTaskCommand(UUID.randomUUID(), "demo", binding, selected.contract(), Map.of("memo", "private-value")), selected.targetDigest());
    }
    @AfterEach void close() { provider.close(); }

    @Test
    void executeAndReadOnlyQueryShareOriginalIdentityWithoutResendingInput() {
        assertThat(gateway.execute(input)).isInstanceOf(ServiceTaskGateway.Observed.class);
        assertThat(gateway.query(input)).isInstanceOf(ServiceTaskGateway.Observed.class);
        assertThat(provider.effects).hasValue(1);
        assertThat(provider.calls).hasSize(2);
        assertThat(provider.calls.get(0).idempotencyKey()).isEqualTo(input.command().id().toString());
        assertThat(provider.calls.get(0).authorization()).isEqualTo("Bearer synthetic-service-token");
        var query = provider.calls.get(1);
        assertThat(query.path()).isEqualTo("/query"); assertThat(query.idempotencyKey()).isNull();
        assertThat(query.request().path("operationId").asText()).isEqualTo(input.command().id().toString());
        assertThat(query.request().toString()).doesNotContain("private-value", "inputs", "binding");
    }

    @Test
    void timeoutAfterActualEffectRecoversByOriginalQueryWithoutAnotherEffect() {
        provider.loseNextExecuteResponse.set(true);
        assertThat(gateway.execute(input)).isInstanceOf(ServiceTaskGateway.Unavailable.class);
        assertThat(provider.effects).hasValue(1);
        assertThat(gateway.query(input)).isInstanceOfSatisfying(ServiceTaskGateway.Observed.class,
                value -> assertThat(value.value().status()).isEqualTo(ServiceTaskObservation.Status.APPLIED));
        assertThat(provider.calls).extracting(ServiceTaskTestProvider.Call::path).containsExactly("/execute", "/query");
        assertThat(provider.effects).hasValue(1);
    }

    @Test
    void changingTargetOrDisablingExecutionCannotRedirectOriginalCommands() {
        declaration.setEndpoint(provider.endpoint() + "another/");
        assertFailure(gateway.execute(input), ServiceTaskOperation.Failure.TARGET_CHANGED);
        assertThat(provider.calls).isEmpty(); declaration.setEndpoint(provider.endpoint());
        declaration.setEnabled(false);
        assertFailure(gateway.execute(input), ServiceTaskOperation.Failure.OPERATION_DISABLED);
        assertThat(gateway.query(input)).isInstanceOfSatisfying(ServiceTaskGateway.Observed.class, value -> assertThat(value.value().status()).isEqualTo(ServiceTaskObservation.Status.NOT_FOUND));
        declaration.setEnabled(true); declaration.setToken("rotated-token");
        assertThat(config.find("demo", "receipt.register", 1).orElseThrow().targetDigest()).isEqualTo(input.targetDigest());
        assertThat(gateway.execute(input)).isInstanceOf(ServiceTaskGateway.Observed.class);
    }

    @Test
    void rejectsRedirectOversizedBodyAmbiguousJsonAndWrongTenant() {
        provider.mode = ServiceTaskTestProvider.Mode.REDIRECT; assertFailure(gateway.execute(input), ServiceTaskOperation.Failure.REMOTE_FAILURE);
        assertThat(provider.calls).hasSize(1);
        provider.mode = ServiceTaskTestProvider.Mode.TOO_LARGE; assertFailure(gateway.execute(input), ServiceTaskOperation.Failure.RESPONSE_TOO_LARGE);
        provider.mode = ServiceTaskTestProvider.Mode.DUPLICATE_FIELD; assertFailure(gateway.execute(input), ServiceTaskOperation.Failure.INVALID_RESPONSE);
        provider.mode = ServiceTaskTestProvider.Mode.WRONG_TENANT; assertFailure(gateway.execute(input), ServiceTaskOperation.Failure.INVALID_RESPONSE);
    }

    @Test
    void boundsEntireSlowResponseAndRefusesCallsInsideDatabaseTransaction() {
        declaration.setTimeoutSeconds(1); provider.mode = ServiceTaskTestProvider.Mode.SLOW_BODY;
        Instant started = Instant.now(); assertFailure(gateway.execute(input), ServiceTaskOperation.Failure.TIMEOUT);
        assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(4));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThatThrownBy(() -> gateway.query(input)).isInstanceOf(IllegalStateException.class); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }

    @Test
    void configurationRejectsUnsafeTargetsAndAmbiguousVersions() {
        for (String endpoint : List.of("http://example.invalid", "https://user:pass@example.invalid", "https://example.invalid/path?x=1", "https://example.invalid/#fragment", "file:///tmp/file")) {
            declaration.setEndpoint(endpoint); assertThatThrownBy(config::validate).isInstanceOf(IllegalStateException.class);
        }
        declaration.setEndpoint(provider.endpoint()); config.setTenants(Map.of("demo", List.of(declaration, declaration)));
        assertThatThrownBy(config::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void deploymentPropertiesBindTypedParametersAndExplicitDisabledState() {
        String prefix = "agentflow.service-tasks.gateway";
        var properties = Map.ofEntries(
                Map.entry(prefix + ".enabled", "true"),
                Map.entry(prefix + ".tenants.demo[0].key", "receipt.configured"),
                Map.entry(prefix + ".tenants.demo[0].version", "2"),
                Map.entry(prefix + ".tenants.demo[0].name", "部署配置验收"),
                Map.entry(prefix + ".tenants.demo[0].enabled", "false"),
                Map.entry(prefix + ".tenants.demo[0].endpoint", provider.endpoint()),
                Map.entry(prefix + ".tenants.demo[0].token", "configured-token"),
                Map.entry(prefix + ".tenants.demo[0].parameters[0].name", "count"),
                Map.entry(prefix + ".tenants.demo[0].parameters[0].type", "NUMBER"),
                Map.entry(prefix + ".tenants.demo[0].parameters[0].required", "true"),
                Map.entry(prefix + ".tenants.demo[0].parameters[0].sensitive", "false"));
        var bound = new Binder(new MapConfigurationPropertySource(properties)).bind(prefix, Bindable.of(ServiceTaskGatewayConfiguration.class)).get();
        bound.validate(); var selected = bound.find("demo", "receipt.configured", 2).orElseThrow();
        assertThat(selected.enabled()).isFalse();
        assertThat(selected.contract().parameters()).containsExactly(new ServiceTaskContract.Parameter("count", ServiceTaskContract.Type.NUMBER, true, false));
        assertThat(bound.find("foreign", "receipt.configured", 2)).isEmpty();
    }

    private void assertFailure(ServiceTaskGateway.Result result, ServiceTaskOperation.Failure failure) {
        assertThat(result).isInstanceOfSatisfying(ServiceTaskGateway.Unavailable.class, value -> assertThat(value.failure()).isEqualTo(failure));
    }

    @Test void sourceTraceUsesHeaderWithoutReplacingCommandIdentity() {
        String trace = UUID.randomUUID().toString();
        try (var scope = new DiagnosticContext(trace, "demo").open()) {
            executeAndReadOnlyQueryShareOriginalIdentityWithoutResendingInput();
        }
        assertThat(provider.traceIds).containsExactly(trace, trace);
        assertThat(provider.calls).allSatisfy(call -> assertThat(call.request().toString()).doesNotContain(trace));
    }

}
