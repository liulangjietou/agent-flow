package io.agentflow.servicetask;

import io.agentflow.common.DomainException;
import io.agentflow.form.FormSchema;
import org.apache.commons.lang3.StringUtils;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 可信适配器声明的固定版本输入契约，不包含地址、Bean 名称、脚本或结果到流程变量的映射。
 * @author owlzhangfq@gmail.com
 */
public record ServiceTaskContract(String key, long version, String name, List<Parameter> parameters) {
    public static final int MAX_PARAMETERS = 16;
    public static final int MAX_TEXT_BYTES = 8192;
    public static final int MAX_INPUT_BYTES = 32768;
    private static final int MAX_NAME_LENGTH = 200;
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9._-]{0,63}");
    private static final Pattern PARAMETER = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{0,63}");
    private static final Pattern DATE_FORMAT = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");

    /** 固定排序使同一组参数的声明顺序不改变协议摘要。 */
    public ServiceTaskContract {
        if (key == null || !KEY.matcher(key).matches() || version < 1 || StringUtils.isBlank(name)
                || name.length() > MAX_NAME_LENGTH || name.codePoints().anyMatch(Character::isISOControl) || !unicode(name)
                || parameters == null || parameters.size() > MAX_PARAMETERS) throw invalid();
        Set<String> names = new HashSet<>();
        for (Parameter parameter : parameters) {
            if (parameter == null || !names.add(parameter.name())) throw invalid();
        }
        parameters = parameters.stream().sorted(java.util.Comparator.comparing(Parameter::name)).toList();
    }

    /** 仅复制显式声明的标量；未知键、隐式类型转换和超大输入都在冻结边界拒绝。 */
    public Map<String, Object> freezeInputs(Map<String, Object> values) {
        if (values == null || values.size() > parameters.size()
                || values.keySet().stream().anyMatch(key -> parameters.stream().noneMatch(parameter -> parameter.name().equals(key)))) {
            throw invalidInputs();
        }
        int bytes = 0;
        for (Parameter parameter : parameters) {
            Object value = values.get(parameter.name());
            if (value == null) {
                if (parameter.required() || values.containsKey(parameter.name())) throw invalidInputs();
                continue;
            }
            if (!parameter.type().accepts(value) || parameter.required() && value instanceof String text && StringUtils.isBlank(text)) {
                throw invalidInputs();
            }
            bytes += parameter.name().getBytes(StandardCharsets.UTF_8).length + value.toString().getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_INPUT_BYTES) throw invalidInputs();
        }
        return Map.copyOf(values);
    }

    /** 版本和完整参数语义一起参与摘要；同版本变更任何声明都可被持久绑定识别。 */
    public String digest() {
        var digest = sha256();
        add(digest, "agentflow-service-contract-1", key, Long.toString(version), name, Integer.toString(parameters.size()));
        for (Parameter parameter : parameters) add(digest, parameter.name(), parameter.type().name(), Boolean.toString(parameter.required()), Boolean.toString(parameter.sensitive()));
        return HexFormat.of().formatHex(digest.digest());
    }

    static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }

    static boolean unicode(String value) {
        return value.codePoints().noneMatch(point -> point >= Character.MIN_SURROGATE && point <= Character.MAX_SURROGATE);
    }

    static void add(MessageDigest digest, String... values) {
        for (String value : values) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
    }

    /**
     * 参数是固定白名单中的单个标量，不接受整个表单、附件原件或嵌套对象。
     * @author owlzhangfq@gmail.com
     */
    public record Parameter(String name, Type type, boolean required, boolean sensitive) {
        public Parameter {
            if (name == null || !PARAMETER.matcher(name).matches() || type == null) throw invalid();
        }
    }

    /**
     * 金额等数字沿用表单的规范十进制字符串，避免浮点精度和自动转换改变命令。
     * @author owlzhangfq@gmail.com
     */
    public enum Type {
        TEXT, NUMBER, BOOLEAN, DATE;

        private boolean accepts(Object value) {
            if (this == BOOLEAN) return value instanceof Boolean;
            if (!(value instanceof String text) || text.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES
                    || !unicode(text)) return false;
            return switch (this) {
                case TEXT -> true;
                case NUMBER -> {
                    try { FormSchema.decimal(text); yield true; }
                    catch (IllegalArgumentException invalid) { yield false; }
                }
                case DATE -> {
                    try { yield DATE_FORMAT.matcher(text).matches() && LocalDate.parse(text).getYear() > 0; }
                    catch (DateTimeParseException invalid) { yield false; }
                }
                case BOOLEAN -> throw new IllegalStateException("Boolean inputs are handled before text inputs");
            };
        }
    }

    private static DomainException invalid() { return new DomainException("INVALID_SERVICE_TASK_CONTRACT", "Service task contract must declare a bounded, versioned input schema"); }
    private static DomainException invalidInputs() { return new DomainException("INVALID_SERVICE_TASK_INPUTS", "Service task inputs must match the declared scalar schema and limits"); }
}
