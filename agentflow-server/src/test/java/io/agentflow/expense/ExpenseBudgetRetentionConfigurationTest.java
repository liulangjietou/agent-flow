package io.agentflow.expense;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

/**
 * 企业保留期限没有隐含默认值，显式租户开关不能越过配置完整性检查。
 * @author owlzhangfq@gmail.com
 */
class ExpenseBudgetRetentionConfigurationTest {
    @Test void absentAndDisabledTenantsHaveNoExpiryPolicyAndReturnedPoliciesAreImmutable() {
        var config = new ExpenseBudgetRetentionConfiguration(); config.validate();
        assertThat(config.policy("a")).isNull();
        var tenant = new ExpenseBudgetRetentionConfiguration.Tenant(); tenant.setEnabled(false);
        config.setTenants(Map.of("a", tenant)); config.validate(); assertThat(config.policy("a")).isNull();
        tenant.setEnabled(true); tenant.setRetentionDays(3); config.validate();
        var captured = config.policy("a"); tenant.setRetentionDays(7);
        assertThat(captured.retentionDays()).isEqualTo(3); assertThat(config.policy("a").retentionDays()).isEqualTo(7);
        assertThat(config.policy("b")).isNull();
    }

    @Test void missingDecisionMissingDurationAndInvalidDurationsFail() {
        var config = new ExpenseBudgetRetentionConfiguration(); var tenant = new ExpenseBudgetRetentionConfiguration.Tenant();
        config.setTenants(Map.of("a", tenant)); assertThatThrownBy(config::validate).isInstanceOf(IllegalStateException.class);
        tenant.setEnabled(true); assertThatThrownBy(config::validate).isInstanceOf(IllegalStateException.class);
        for (int days : new int[]{0, -1, 3661}) { tenant.setRetentionDays(days); assertThatThrownBy(config::validate).isInstanceOf(DomainException.class); }
        tenant.setRetentionDays(3);
        for (String id : new String[]{"", " ", "x".repeat(65)}) { config.setTenants(Map.of(id, tenant)); assertThatThrownBy(config::validate).isInstanceOf(IllegalStateException.class); }
    }
}
