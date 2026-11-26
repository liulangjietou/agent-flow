package io.agentflow.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import io.agentflow.common.ErrorCode;
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
    private static final int DEFAULT_MAX_COMPLETION_TOKENS = 4096;
    private static final int MAX_COMPLETION_TOKENS_LIMIT = 32768;
    private static final Pattern VERSION_SUFFIX = Pattern.compile("-v\\d+$");
    private boolean enabled;
    private String endpoint = "";
    private String providerId = "openai-compatible";
    private String model = "";
    private String apiKey = "";
    private int timeoutSeconds = 45;
    /** 单次回复的最大输出 token，默认 4096；可按用途覆盖。 */
    private int maxCompletionTokens = DEFAULT_MAX_COMPLETION_TOKENS;
    /** 协议校验失败后的修复重试次数，仅用于受控模型输出修复；0 表示不重试。 */
    private int invalidOutputRepairAttempts = 1;
    /** 是否向 OpenAI 兼容网关启用 strict json_schema 结构化输出；默认关闭以兼容轻量网关。 */
    private boolean structuredOutputsEnabled;
    /** 是否在请求体中附带 store=false；关闭时兼容不识别该字段的第三方推理网关。 */
    private boolean includeStoreParam = true;
    /** 单租户每日总 Token 配额上限（0 表示不限额），超限时在模型外呼前熔断拒绝。 */
    private long dailyTokenQuota;
    /** 按用途覆盖端点、模型、密钥与输出上限，键为去掉版本后缀的提示标识，如 invoice-extraction、expense-handling-agent。 */
    private Map<String, Purpose> purposes = new LinkedHashMap<>();

    /** 配置不完整时保留只读历史能力，创建运行直接失败。 */
    public void requireAvailable() {
        if (!enabled) throw new DomainException(ErrorCode.AGENT_MODEL_DISABLED, "Assist model is disabled");
        if (StringUtils.isBlank(model) || model.length() > AssistSuggestion.MAX_VERSION_LENGTH
                || StringUtils.isBlank(providerId) || providerId.length() > AssistSuggestion.MAX_VERSION_LENGTH
                || timeoutSeconds < 1 || timeoutSeconds > 120 || apiKey == null || apiKey.contains("\r") || apiKey.contains("\n")
                || !validTokens(maxCompletionTokens) || invalidOutputRepairAttempts < 0 || invalidOutputRepairAttempts > 3
                || dailyTokenQuota < 0) throw invalid();
        for (Purpose purpose : purposes.values()) {
            if (purpose.getModel() != null && (StringUtils.isBlank(purpose.getModel()) || purpose.getModel().length() > AssistSuggestion.MAX_VERSION_LENGTH)) throw invalid();
            if (purpose.getProviderId() != null && (StringUtils.isBlank(purpose.getProviderId()) || purpose.getProviderId().length() > AssistSuggestion.MAX_VERSION_LENGTH)) throw invalid();
            if (purpose.getApiKey() != null && (purpose.getApiKey().contains("\r") || purpose.getApiKey().contains("\n"))) throw invalid();
            if (purpose.getTimeoutSeconds() != null && (purpose.getTimeoutSeconds() < 1 || purpose.getTimeoutSeconds() > 120)) throw invalid();
            if (purpose.getEndpoint() != null) validateUri(purpose.getEndpoint());
            if (purpose.getMaxCompletionTokens() != null && !validTokens(purpose.getMaxCompletionTokens())) throw invalid();
        }
        uri();
    }

    /** 不追随重定向；明文 HTTP 仅用于明确回环主机。 */
    public URI uri() { return validateUri(endpoint); }

    /** 按用途解析模型端点，未覆盖时回退全局端点。 */
    public URI uriFor(String promptVersion) {
        Purpose purpose = purposes.get(purposeKey(promptVersion));
        return validateUri(purpose == null || purpose.getEndpoint() == null ? endpoint : purpose.getEndpoint());
    }

    /** 按用途解析提供者标识，未覆盖时回退全局提供者。 */
    public String providerFor(String promptVersion) {
        Purpose purpose = purposes.get(purposeKey(promptVersion));
        return purpose == null || purpose.getProviderId() == null ? providerId : purpose.getProviderId();
    }

    /** 按用途解析调用密钥，未覆盖时回退全局密钥。 */
    public String apiKeyFor(String promptVersion) {
        Purpose purpose = purposes.get(purposeKey(promptVersion));
        return purpose == null || purpose.getApiKey() == null ? apiKey : purpose.getApiKey();
    }

    /** 按用途解析超时秒数，未覆盖时回退全局超时。 */
    public int timeoutSecondsFor(String promptVersion) {
        Purpose purpose = purposes.get(purposeKey(promptVersion));
        return purpose == null || purpose.getTimeoutSeconds() == null ? timeoutSeconds : purpose.getTimeoutSeconds();
    }

    private static URI validateUri(String raw) {
        if (StringUtils.isBlank(raw)) throw invalid();
        try {
            URI uri = URI.create(raw);
            boolean secure = "https".equalsIgnoreCase(uri.getScheme());
            boolean loopback = "http".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null && Set.of("127.0.0.1", "localhost", "[::1]", "::1").contains(uri.getHost());
            if ((!secure && !loopback) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getFragment() != null || uri.getQuery() != null) throw invalid();
            return uri;
        } catch (IllegalArgumentException failure) { throw invalid(); }
    }

    /** 冻结服务目标，配置改变后不能将旧授权内容发送到新目的地。 */
    public String targetDigest() { return targetDigest(PROMPT_VERSION); }

    /** 不同业务提示分别冻结授权；按用途覆盖的端点、提供者与模型均计入指纹。 */
    public String targetDigest(String promptVersion) {
        Purpose purpose = purposes.get(purposeKey(promptVersion));
        String resolvedEndpoint = purpose == null || purpose.getEndpoint() == null ? endpoint : purpose.getEndpoint();
        return digest(resolvedEndpoint + "\n" + providerFor(promptVersion) + "\n" + modelFor(promptVersion) + "\n" + promptVersion);
    }

    /** 用途覆盖了模型时使用覆盖值，否则使用全局模型；覆盖值进入目标指纹，变更后需重新授权。 */
    public String modelFor(String promptVersion) {
        Purpose purpose = purposes.get(purposeKey(promptVersion));
        return purpose == null || purpose.getModel() == null ? model : purpose.getModel();
    }

    /** 用途覆盖了输出上限时使用覆盖值。 */
    public int maxCompletionTokensFor(String promptVersion) {
        Purpose purpose = purposes.get(purposeKey(promptVersion));
        return purpose == null || purpose.getMaxCompletionTokens() == null ? maxCompletionTokens : purpose.getMaxCompletionTokens();
    }

    /** 提示版本去掉 -vN 后缀即为用途标识，提示升版不影响用途配置。 */
    static String purposeKey(String promptVersion) { return VERSION_SUFFIX.matcher(promptVersion).replaceFirst(""); }

    private static boolean validTokens(int value) { return value >= 1 && value <= MAX_COMPLETION_TOKENS_LIMIT; }

    /** 指纹只用于内容与目标绑定，不存储明文凭据。 */
    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
    private static DomainException invalid() { return new DomainException(ErrorCode.AGENT_MODEL_UNCONFIGURED, "Assist model configuration is invalid"); }
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
    public int getMaxCompletionTokens() { return maxCompletionTokens; }
    public void setMaxCompletionTokens(int value) { maxCompletionTokens = value; }
    public int getInvalidOutputRepairAttempts() { return invalidOutputRepairAttempts; }
    public void setInvalidOutputRepairAttempts(int value) { invalidOutputRepairAttempts = value; }
    public boolean isStructuredOutputsEnabled() { return structuredOutputsEnabled; }
    public void setStructuredOutputsEnabled(boolean value) { structuredOutputsEnabled = value; }
    public boolean isIncludeStoreParam() { return includeStoreParam; }
    public void setIncludeStoreParam(boolean value) { includeStoreParam = value; }
    public long getDailyTokenQuota() { return dailyTokenQuota; }
    public void setDailyTokenQuota(long value) { dailyTokenQuota = value; }
    public Map<String, Purpose> getPurposes() { return purposes; }
    public void setPurposes(Map<String, Purpose> value) { purposes = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value); }

    /**
     * 单一用途的模型、端点与凭据覆盖；未设置的字段沿用全局配置。
     * @author owlzhangfq@gmail.com
     */
    public static class Purpose {
        private String endpoint;
        private String providerId;
        private String model;
        private String apiKey;
        private Integer timeoutSeconds;
        private Integer maxCompletionTokens;
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String value) { endpoint = value; }
        public String getProviderId() { return providerId; }
        public void setProviderId(String value) { providerId = value; }
        public String getModel() { return model; }
        public void setModel(String value) { model = value; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String value) { apiKey = value; }
        public Integer getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(Integer value) { timeoutSeconds = value; }
        public Integer getMaxCompletionTokens() { return maxCompletionTokens; }
        public void setMaxCompletionTokens(Integer value) { maxCompletionTokens = value; }
    }
}
