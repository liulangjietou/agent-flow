package io.agentflow.notification;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 外部收件账号和服务器只来自部署配置，个人偏好接口不能覆盖。 @author owlzhangfq@gmail.com */
@Component
@ConfigurationProperties(prefix = "agentflow.notifications")
public class NotificationDeliveryConfiguration {
    private Map<String, SmtpServer> smtpServers = new LinkedHashMap<>();
    private Map<String, Binding> bindings = new LinkedHashMap<>();
    private String publicUrl;
    private boolean allowInsecureInDemo;

    public Map<String, SmtpServer> getSmtpServers() { return smtpServers; }
    public void setSmtpServers(Map<String, SmtpServer> values) { smtpServers = values; }
    public Map<String, Binding> getBindings() { return bindings; }
    public void setBindings(Map<String, Binding> values) { bindings = values; }
    public String getPublicUrl() { return publicUrl; }
    public void setPublicUrl(String value) { publicUrl = value; }
    public boolean isAllowInsecureInDemo() { return allowInsecureInDemo; }
    public void setAllowInsecureInDemo(boolean value) { allowInsecureInDemo = value; }

    public enum Security { STARTTLS, TLS, DEMO_PLAIN }

    /** 禁止把账号、密码、地址通过配置对象的字符串表示写入日志。 */
    public record SmtpServer(String tenantId, String host, int port, Security security,
                             String username, String password, String from, boolean enabled) {
        @Override public String toString() { return "NotificationSmtpServer[redacted]"; }
    }

    /** 每个租户、稳定主体和渠道只允许一个明确绑定。 */
    public record Binding(String tenantId, String recipient, NotificationChannel channel,
                          String serverId, String address, boolean enabled) {
        @Override public String toString() { return "NotificationBinding[redacted]"; }
    }
}
