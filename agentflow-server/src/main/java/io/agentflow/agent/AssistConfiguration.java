package io.agentflow.agent;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/**
 * 目的地、模型和凭据仅来自部署配置；默认关闭，HTTP 请求不能覆盖。
 * @author owlzhangfq@gmail.com
 */
@Component
@ConfigurationProperties(prefix = "agentflow.assist")
public class AssistConfiguration {
    public static final String PROMPT_VERSION = "approval-summary-v1";
    private boolean enabled;
    private String endpoint = "";
    private String providerId = "openai-compatible";
    private String model = "";
    private String apiKey = "";
    private int timeoutSeconds = 45;

    /** 配置不完整时保留只读历史能力，创建运行直接失败。 */
    public void requireAvailable() {
        if (!enabled) throw new DomainException("AGENT_MODEL_DISABLED", "Assist model is disabled");
        if (StringUtils.isBlank(model) || model.length() > AssistSuggestion.MAX_VERSION_LENGTH
                || StringUtils.isBlank(providerId) || providerId.length() > AssistSuggestion.MAX_VERSION_LENGTH
                || timeoutSeconds < 1 || timeoutSeconds > 120 || apiKey == null || apiKey.contains("\r") || apiKey.contains("\n")) throw invalid();
        uri();
    }

    /** 不追随重定向；明文 HTTP 仅用于明确回环主机。 */
    public URI uri() {
        if (StringUtils.isBlank(endpoint)) throw invalid();
        try {
            URI uri = URI.create(endpoint);
            boolean secure = "https".equalsIgnoreCase(uri.getScheme());
            boolean loopback = "http".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null && Set.of("127.0.0.1", "localhost", "[::1]", "::1").contains(uri.getHost());
            if ((!secure && !loopback) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getFragment() != null || uri.getQuery() != null) throw invalid();
            return uri;
        } catch (IllegalArgumentException failure) { throw invalid(); }
    }

    /** 冻结服务目标，配置改变后不能将旧授权内容发送到新目的地。 */
    public String targetDigest() { return digest(endpoint + "\n" + providerId + "\n" + model + "\n" + PROMPT_VERSION); }

    /** 指纹只用于内容与目标绑定，不存储明文凭据。 */
    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
    private static DomainException invalid() { return new DomainException("AGENT_MODEL_UNCONFIGURED", "Assist model configuration is invalid"); }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String value) { endpoint = value; }
    public String getProviderId() { return providerId; }
    public void setProviderId(String value) { providerId = value; }
    public String getModel() { return model; }
    public void setModel(String value) { model = value; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String value) { apiKey = value; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int value) { timeoutSeconds = value; }
}
