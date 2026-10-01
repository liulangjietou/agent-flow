package io.agentflow.event;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 来源身份由部署配置提供；普通管理员不能通过事件正文创建凭据或新来源。
 * @author owlzhangfq@gmail.com
 */
@Component
@ConfigurationProperties(prefix = "agentflow.events")
public class EventIngressConfiguration {
    private boolean enabled;
    private Map<String, Source> sources = new LinkedHashMap<>();
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Map<String, Source> getSources() { return sources; }
    public void setSources(Map<String, Source> sources) { this.sources = sources; }

    /**
     * 信任修订变化隔离已接收的旧消息；常规密钥轮换保留修订并支持双密钥。
     * @author owlzhangfq@gmail.com
     */
    public record Source(String tenantId, String sourceKey, long trustRevision, boolean enabled, List<String> signingSecrets) {
        @Override public String toString() { return "EventSource[redacted]"; }
    }
}
