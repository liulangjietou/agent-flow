package io.agentflow.definition;

import io.agentflow.common.DomainException;
import io.agentflow.servicetask.ServiceTaskContract;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 服务节点只引用固定白名单契约和显式字段映射，不接收地址、脚本、Bean 或远端结果写入配置。
 * @author owlzhangfq@gmail.com
 */
public record ServiceTaskPolicy(String operationKey, long operationVersion, String contractDigest, Map<String, String> inputs) {
    public static final String KEY_PROPERTY = "serviceOperationKey";
    public static final String VERSION_PROPERTY = "serviceOperationVersion";
    public static final String DIGEST_PROPERTY = "serviceContractDigest";
    public static final String INPUT_PREFIX = "serviceInput.";
    private static final Set<String> REFERENCE_PROPERTIES = Set.of(KEY_PROPERTY, VERSION_PROPERTY, DIGEST_PROPERTY);
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9._-]{0,63}");
    private static final Pattern FIELD = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{0,63}");
    private static final Pattern DIGEST = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern VERSION = Pattern.compile("[1-9][0-9]{0,18}");

    public ServiceTaskPolicy {
        if (operationKey == null || !KEY.matcher(operationKey).matches() || operationVersion < 1
                || contractDigest == null || !DIGEST.matcher(contractDigest).matches() || inputs == null
                || inputs.size() > ServiceTaskContract.MAX_PARAMETERS || inputs.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                        || !FIELD.matcher(entry.getKey()).matches() || entry.getValue() == null || !FIELD.matcher(entry.getValue()).matches())) throw invalid();
        inputs = Map.copyOf(inputs);
    }

    /** 属性集合本身是白名单，未知属性不能被悄悄保存为未来可执行配置。 */
    public static ServiceTaskPolicy fromProperties(Map<String, String> properties) {
        if (properties == null || !properties.keySet().containsAll(REFERENCE_PROPERTIES)) throw invalid();
        String version = properties.get(VERSION_PROPERTY);
        if (version == null || !VERSION.matcher(version).matches()) throw invalid();
        var inputs = new LinkedHashMap<String, String>();
        for (var entry : properties.entrySet()) {
            if (entry.getKey() == null) throw invalid();
            if (REFERENCE_PROPERTIES.contains(entry.getKey())) continue;
            if (!entry.getKey().startsWith(INPUT_PREFIX)) throw invalid();
            inputs.put(entry.getKey().substring(INPUT_PREFIX.length()), entry.getValue());
        }
        try { return new ServiceTaskPolicy(properties.get(KEY_PROPERTY), Long.parseLong(version), properties.get(DIGEST_PROPERTY), inputs); }
        catch (NumberFormatException exception) { throw invalid(); }
    }

    /** 供流程结构校验识别被放在其他节点上的服务配置。 */
    public static boolean hasProperties(Map<String, String> properties) {
        return properties.keySet().stream().anyMatch(key -> REFERENCE_PROPERTIES.contains(key) || key.startsWith(INPUT_PREFIX));
    }

    private static DomainException invalid() { return new DomainException("INVALID_SERVICE_TASK_POLICY", "Service task policy requires an exact contract reference and explicit field mappings"); }
}
