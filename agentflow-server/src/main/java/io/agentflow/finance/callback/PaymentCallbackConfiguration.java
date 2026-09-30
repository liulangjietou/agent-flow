package io.agentflow.finance.callback;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 回调密钥仅由部署方配置，每个租户支持当前及轮换中的上一把密钥。
 * @author owlzhangfq@gmail.com
 */
@Component
@ConfigurationProperties(prefix = "agentflow.payment-callbacks")
public class PaymentCallbackConfiguration {
    private boolean enabled;
    private Map<String, Tenant> tenants = new LinkedHashMap<>();
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Map<String, Tenant> getTenants() { return tenants; }
    public void setTenants(Map<String, Tenant> tenants) { this.tenants = tenants; }

    /**
     * 密钥不进入日志和自动生成的字符串表示。
     * @author owlzhangfq@gmail.com
     */
    public record Tenant(List<String> signingSecrets) {
        @Override public String toString() { return "PaymentCallbackTenant[redacted]"; }
    }
}
