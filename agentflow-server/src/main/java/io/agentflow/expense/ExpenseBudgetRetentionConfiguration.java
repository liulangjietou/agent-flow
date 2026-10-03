package io.agentflow.expense;

import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 每个租户显式启用预算到期释放并给出保留天数；未配置时维持原保留行为。
 * @author owlzhangfq@gmail.com
 */
@Component
@ConfigurationProperties(prefix = "agentflow.expenses.budget-retention")
public class ExpenseBudgetRetentionConfiguration {
    private Map<String, Tenant> tenants = new LinkedHashMap<>();

    /** 配置错误在启动时失败，不能默默采用猜测的企业保留期。 */
    @PostConstruct
    public void validate() {
        if (tenants == null) throw invalid();
        tenants.forEach((tenant, value) -> {
            if (StringUtils.isBlank(tenant) || tenant.length() > 64 || value == null || value.enabled == null
                    || value.enabled && value.retentionDays == null) throw invalid();
            if (value.retentionDays != null) new ExpenseBudgetRetention.Policy(value.retentionDays);
        });
    }

    /** 本次轮次结束时取固定值，后续改配置不会影响已经创建的保留记录。 */
    public ExpenseBudgetRetention.Policy policy(String tenant) {
        var value = tenants.get(tenant);
        return value == null || !Boolean.TRUE.equals(value.enabled) ? null : new ExpenseBudgetRetention.Policy(value.retentionDays);
    }

    private static IllegalStateException invalid() { return new IllegalStateException("Expense budget retention configuration is invalid"); }
    public Map<String, Tenant> getTenants() { return tenants; }
    public void setTenants(Map<String, Tenant> value) { tenants = value; }

    /**
     * 开关与天数只能由服务端配置，业务接口不接受申请人自定释放期限。
     * @author owlzhangfq@gmail.com
     */
    public static class Tenant {
        private Boolean enabled;
        private Integer retentionDays;
        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean value) { enabled = value; }
        public Integer getRetentionDays() { return retentionDays; }
        public void setRetentionDays(Integer value) { retentionDays = value; }
    }
}
