package io.agentflow.integration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 仅由部署方提供目的地与签名密钥，HTTP 接口不接受地址或密钥覆盖。
 * @author owlzhangfq@gmail.com
 */
@Component
@ConfigurationProperties(prefix = "agentflow.webhooks")
public class WebhookConfiguration {
    private Map<String, Target> targets = new LinkedHashMap<>();
    private boolean allowInsecureHttpInDemo;
    public Map<String, Target> getTargets() { return targets; }
    public void setTargets(Map<String, Target> targets) { this.targets = targets; }
    public boolean isAllowInsecureHttpInDemo() { return allowInsecureHttpInDemo; }
    public void setAllowInsecureHttpInDemo(boolean value) { allowInsecureHttpInDemo = value; }

    /**
     * 密钥禁止进入自动生成的字符串表示及 API 响应。
     * @author owlzhangfq@gmail.com
     */
    public record Target(String tenantId, String label, String url, String signingSecret, boolean enabled) {
        @Override
        public String toString() { return "WebhookTarget[redacted]"; }
    }
}
