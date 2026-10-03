package io.agentflow.expense;

import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 新借款控制按租户显式配置；未配置与明确允许分别展示，均不擅自禁止提交。
 * @author owlzhangfq@gmail.com
 */
@Component
@ConfigurationProperties(prefix = "agentflow.advances.overdue")
public class AdvanceOverdueConfiguration {
    private Map<String, Tenant> tenants = new LinkedHashMap<>();

    /** 配置了租户就必须明确是否阻断，拒绝漏填造成的隐式放行。 */
    @PostConstruct
    public void validate() {
        if (tenants == null) throw invalid();
        tenants.forEach((tenant, policy) -> {
            if (StringUtils.isBlank(tenant) || tenant.length() > 64 || policy == null || policy.blockNewRequests == null) throw invalid();
        });
    }

    /** 精确匹配当前租户，不继承其他租户的财务规则。 */
    public Policy policy(String tenant) {
        var value = tenants.get(tenant);
        return value == null ? Policy.UNCONFIGURED : Boolean.TRUE.equals(value.blockNewRequests) ? Policy.BLOCK : Policy.ALLOW;
    }

    private static IllegalStateException invalid() { return new IllegalStateException("Advance overdue control configuration is invalid"); }
    public Map<String, Tenant> getTenants() { return tenants; }
    public void setTenants(Map<String, Tenant> value) { tenants = value; }

    /**
     * 仅服务端配置可以控制新借款，申请接口不接受此字段。
     * @author owlzhangfq@gmail.com
     */
    public static class Tenant {
        private Boolean blockNewRequests;
        public Boolean getBlockNewRequests() { return blockNewRequests; }
        public void setBlockNewRequests(Boolean value) { blockNewRequests = value; }
    }

    /**
     * 未配置不等同于业务已确认允许，界面必须区分这两种状态。
     * @author owlzhangfq@gmail.com
     */
    public enum Policy { UNCONFIGURED, ALLOW, BLOCK }
}
