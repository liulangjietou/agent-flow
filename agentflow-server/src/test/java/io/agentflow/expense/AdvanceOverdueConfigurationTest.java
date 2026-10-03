package io.agentflow.expense;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

/**
 * 未配置、显式允许及阻断严格区分，漏填租户规则必须在启动时失败。
 * @author owlzhangfq@gmail.com
 */
class AdvanceOverdueConfigurationTest {
    @Test void policyIsExplicitAndIsolatedByTenant() {
        var configuration = new AdvanceOverdueConfiguration(); configuration.validate();
        assertThat(configuration.policy("a")).isEqualTo(AdvanceOverdueConfiguration.Policy.UNCONFIGURED);
        var policy = new AdvanceOverdueConfiguration.Tenant(); policy.setBlockNewRequests(false);
        configuration.setTenants(Map.of("a", policy)); configuration.validate();
        assertThat(configuration.policy("a")).isEqualTo(AdvanceOverdueConfiguration.Policy.ALLOW);
        policy.setBlockNewRequests(true); configuration.validate();
        assertThat(configuration.policy("a")).isEqualTo(AdvanceOverdueConfiguration.Policy.BLOCK);
        assertThat(configuration.policy("b")).isEqualTo(AdvanceOverdueConfiguration.Policy.UNCONFIGURED);
    }

    @Test void missingDecisionOrInvalidTenantCannotSilentlyAllow() {
        var configuration = new AdvanceOverdueConfiguration();
        configuration.setTenants(Map.of("a", new AdvanceOverdueConfiguration.Tenant()));
        assertThatThrownBy(configuration::validate).isInstanceOf(IllegalStateException.class);
        var policy = new AdvanceOverdueConfiguration.Tenant(); policy.setBlockNewRequests(true);
        for (String tenant : new String[]{"", " ", "a".repeat(65)}) {
            configuration.setTenants(Map.of(tenant, policy));
            assertThatThrownBy(configuration::validate).isInstanceOf(IllegalStateException.class);
        }
    }
}
