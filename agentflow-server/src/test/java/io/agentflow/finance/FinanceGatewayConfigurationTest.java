package io.agentflow.finance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

/**
 * 财务目标只来自部署配置，未配置和配置错误不能降级到任意服务。
 * @author owlzhangfq@gmail.com
 */
class FinanceGatewayConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = {"http://remote.example/finance", "http://localhost/finance", "file:///tmp/finance",
            "https://user:password@example.com/finance", "https://example.com/finance?token=secret", "https://example.com/finance#secret",
            "https://example.com/../finance", "https://example.com/%2e%2e/finance", "https://example.com:0/finance", "", "http:/broken"})
    void invalidTargetsFailWithoutPrintingTheirValues(String endpoint) {
        var config = configured(endpoint, "private-test-token");
        assertThatThrownBy(config::validate).isInstanceOf(IllegalStateException.class)
                .hasMessage("Finance gateway configuration is invalid");
    }

    @Test
    void authenticationIsRequiredExceptForExplicitLiteralLoopbackFixtures() {
        var secure = configured("https://finance.example/api", "");
        secure.getTenants().get("tenant-a").setAllowUnauthenticatedLoopback(true);
        assertThatThrownBy(secure::validate).isInstanceOf(IllegalStateException.class);
        var local = configured("http://127.0.0.1:19000/finance", "");
        assertThatThrownBy(local::validate).isInstanceOf(IllegalStateException.class);
        local.getTenants().get("tenant-a").setAllowUnauthenticatedLoopback(true);
        local.validate();
        assertThat(local.destination("tenant-a")).isPresent();
        assertThat(local.destination("tenant-b")).isEmpty();
    }

    @Test
    void tokenAndTimeoutValidationDoNotExposeSecretsOrChangeDisabledDefaults() {
        var config = configured("https://finance.example", "private-test-token");
        config.validate();
        assertThat(config.destination("tenant-a").orElseThrow().toString()).doesNotContain("private-test-token", "finance.example");
        config.getTenants().get("tenant-a").setToken("private\nInjected: value");
        assertThatThrownBy(config::validate).hasMessage("Finance gateway configuration is invalid");
        config.getTenants().get("tenant-a").setToken("private-test-token");
        config.getTenants().get("tenant-a").setTimeoutSeconds(61);
        assertThatThrownBy(config::validate).hasMessage("Finance gateway configuration is invalid");
        config.setEnabled(false); config.validate();
        assertThat(config.destination("tenant-a")).isEmpty();
        assertThat(new FinanceGatewayConfiguration().destination("tenant-a")).isEmpty();
    }

    static FinanceGatewayConfiguration configured(String endpoint, String token) {
        var target = new FinanceGatewayConfiguration.Target(); target.setEndpoint(endpoint); target.setToken(token);
        var config = new FinanceGatewayConfiguration(); config.setEnabled(true); config.setTenants(Map.of("tenant-a", target));
        return config;
    }
}
