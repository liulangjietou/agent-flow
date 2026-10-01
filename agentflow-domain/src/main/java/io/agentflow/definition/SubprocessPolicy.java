package io.agentflow.definition;

import io.agentflow.common.DomainException;
import io.agentflow.form.FormSchema;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 子流程只引用明确发布版本，输入逐字段映射；不接受脚本、路径求值或隐式变量继承。
 * @author owlzhangfq@gmail.com
 */
public record SubprocessPolicy(String processKey, long version, Map<String, String> inputs) {
    public static final int MAX_CALL_DEPTH = 16;
    public static final String KEY_PROPERTY = "subprocessKey";
    public static final String VERSION_PROPERTY = "subprocessVersion";
    public static final String INPUT_PREFIX = "subprocessInput.";
    private static final int MAX_PROCESS_KEY_LENGTH = 128;
    private static final Pattern FIELD_KEY = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{0,63}");
    private static final Set<String> RESERVED_FIELDS = Set.of("constructor", "prototype", "tenantId", "applicationId",
            "businessNo", "roundNo", "formData", "formFieldTypes", "lastAction");

    public SubprocessPolicy {
        if (processKey == null || processKey.isBlank() || processKey.length() > MAX_PROCESS_KEY_LENGTH
                || !processKey.equals(processKey.strip()) || processKey.contains("${") || processKey.contains("#{")
                || processKey.codePoints().anyMatch(Character::isISOControl) || version < 1 || version > Integer.MAX_VALUE) {
            throw new DomainException("SUBPROCESS_REFERENCE_INVALID", "An explicit process key and published version are required");
        }
        if (inputs == null || inputs.size() > FormSchema.MAX_FIELDS || inputs.entrySet().stream()
                .anyMatch(entry -> !fieldKey(entry.getKey()) || !fieldKey(entry.getValue()))) {
            throw new DomainException("SUBPROCESS_INPUT_MAPPING_INVALID", "Subprocess inputs must map declared field names");
        }
        inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
    }

    /** 版本按规范十进制读取，拒绝 latest、补零、表达式和越界数值。 */
    public static SubprocessPolicy fromProperties(Map<String, String> properties) {
        String key = properties.get(KEY_PROPERTY), version = properties.get(VERSION_PROPERTY);
        if (key == null || version == null) {
            throw new DomainException("SUBPROCESS_REFERENCE_REQUIRED", "Select a published subprocess version");
        }
        if (!version.matches("[1-9][0-9]{0,9}")) {
            throw new DomainException("SUBPROCESS_REFERENCE_INVALID", "Subprocess version must be a canonical positive integer");
        }
        var inputs = new LinkedHashMap<String, String>();
        properties.forEach((name, source) -> {
            if (name.startsWith(INPUT_PREFIX)) inputs.put(name.substring(INPUT_PREFIX.length()), source);
        });
        return new SubprocessPolicy(key, Long.parseLong(version), inputs);
    }

    /** 图、模板与差异比较使用相同字面量属性，读取目录不能改变引用版本。 */
    public Map<String, String> properties() {
        var values = new LinkedHashMap<String, String>();
        values.put(KEY_PROPERTY, processKey); values.put(VERSION_PROPERTY, Long.toString(version));
        inputs.forEach((target, source) -> values.put(INPUT_PREFIX + target, source));
        return Collections.unmodifiableMap(values);
    }

    /** 其他节点不得携带子流程属性，避免切换节点后留下隐含调用规则。 */
    public static boolean hasProperties(Map<String, String> properties) {
        return properties.keySet().stream().anyMatch(key -> key.equals(KEY_PROPERTY) || key.equals(VERSION_PROPERTY) || key.startsWith(INPUT_PREFIX));
    }

    private static boolean fieldKey(String value) {
        return value != null && FIELD_KEY.matcher(value).matches() && !RESERVED_FIELDS.contains(value);
    }
}
