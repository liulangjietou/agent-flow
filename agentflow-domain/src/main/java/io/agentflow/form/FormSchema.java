package io.agentflow.form;

import io.agentflow.common.DomainException;
import io.agentflow.definition.DefinitionModels;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 与流程定义共同发布的不可变表单契约；草稿允许缺项，提交要求必填完整。
 * @author owlzhangfq@gmail.com
 */
public record FormSchema(int schemaVersion, List<Field> fields) {
    public static final int CURRENT_VERSION = 1;
    public static final int MAX_FIELDS = 50;
    public static final int MAX_TEXT_LENGTH = 10000;
    private static final int MAX_OPTIONS = 50;
    private static final int MAX_LABEL_LENGTH = 128;
    private static final int MAX_HELP_LENGTH = 1000;
    private static final int MAX_NUMBER_LENGTH = 80;
    private static final int MAX_DECIMAL_PRECISION = 38;
    private static final int MAX_DECIMAL_SCALE = 18;
    private static final Pattern FIELD_KEY = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{0,63}");
    private static final Pattern DECIMAL = Pattern.compile("-?[0-9]+(?:\\.[0-9]+)?");
    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Set<String> RESERVED_FIELDS = Set.of("constructor", "prototype", "tenantId", "applicationId",
            "businessNo", "roundNo", "formData", "formFieldTypes", "lastAction");

    public FormSchema {
        if (schemaVersion != CURRENT_VERSION || fields == null || fields.size() > MAX_FIELDS || fields.stream().anyMatch(java.util.Objects::isNull)) {
            throw invalid("Unsupported schema version or field collection");
        }
        fields = List.copyOf(fields);
        if (fields.stream().map(Field::key).distinct().count() != fields.size()) {
            throw invalid("Field keys must be unique");
        }
    }

    /** 校验已经填写的值，不要求草稿包含全部必填字段。 */
    public void validateDraft(Map<String, Object> payload) { validate(payload, false); }

    /** 提交时同时校验类型、范围、字段白名单和必填完整性。 */
    public void validateSubmission(Map<String, Object> payload) { validate(payload, true); }

    /** 返回领域求值器与引擎共同使用的可信类型映射。 */
    public Map<String, String> fieldTypes() {
        Map<String, String> types = new LinkedHashMap<>();
        fields.forEach(field -> types.put(field.key(), field.type().name()));
        return Map.copyOf(types);
    }

    /** 校验条件仅引用存在的字段，并且运算符和字面量符合字段类型。 */
    public void validateCondition(DefinitionModels.ConditionAst ast) {
        if (ast instanceof DefinitionModels.Logical logical) {
            logical.terms().forEach(this::validateCondition);
            return;
        }
        DefinitionModels.Comparison comparison = (DefinitionModels.Comparison) ast;
        Field field = fields.stream().filter(candidate -> candidate.key().equals(comparison.field())).findFirst()
                .orElseThrow(() -> new DomainException("INVALID_CONDITION", "Condition field is not declared in the form schema"));
        if (comparison.operator() == DefinitionModels.Operator.EXISTS || comparison.operator() == DefinitionModels.Operator.NOT_EXISTS) return;
        if (field.type() != FieldType.NUMBER && field.type() != FieldType.DATE
                && comparison.operator() != DefinitionModels.Operator.EQ && comparison.operator() != DefinitionModels.Operator.NE) {
            throw new DomainException("INVALID_CONDITION", "Condition operator does not match the field type");
        }
        Object literal = comparison.literal();
        if (field.type() == FieldType.BOOLEAN) {
            if (!"true".equals(literal) && !"false".equals(literal)) {
                throw new DomainException("INVALID_CONDITION", "Boolean condition literal must be true or false");
            }
            literal = Boolean.valueOf(comparison.literal());
        }
        // 分支阈值允许落在表单数值区间之外；但类型、精度和枚举值必须正确。
        if (field.valueError(literal, false) != null) {
            throw new DomainException("INVALID_CONDITION", "Condition literal does not match the field type");
        }
    }

    private void validate(Map<String, Object> payload, boolean submitted) {
        Map<String, Object> values = payload == null ? Map.of() : payload;
        Map<String, String> errors = new LinkedHashMap<>();
        Set<String> keys = fieldTypes().keySet();
        values.keySet().forEach(key -> { if (!keys.contains(key)) errors.put(key, "UNKNOWN_FIELD"); });
        for (Field field : fields) {
            Object value = values.get(field.key());
            if (empty(value, field.type())) {
                if (submitted && field.required()) errors.put(field.key(), "REQUIRED");
            } else {
                String error = field.valueError(value, true);
                if (error != null) errors.put(field.key(), error);
            }
        }
        if (!errors.isEmpty()) throw new FormValidationException(errors);
    }

    /** 未填写和显式清空等价；false 与十进制字符串 0 都不是空值。 */
    public static boolean empty(Object value, FieldType type) {
        return value == null || value instanceof String text && (text.isEmpty()
                || (type == FieldType.TEXT || type == FieldType.TEXTAREA) && text.isBlank());
    }

    /** 解析规范十进制字符串，拒绝指数、空白和超出平台精度的值，保留原字符串表示。 */
    public static BigDecimal decimal(String value) {
        if (value == null || value.length() > MAX_NUMBER_LENGTH || !DECIMAL.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid canonical decimal");
        }
        BigDecimal number = new BigDecimal(value);
        if (number.precision() > MAX_DECIMAL_PRECISION || number.scale() > MAX_DECIMAL_SCALE) {
            throw new IllegalArgumentException("Decimal precision or scale exceeded");
        }
        return number;
    }

    private static boolean validDate(String value) {
        if (!DATE.matcher(value).matches()) return false;
        try { return LocalDate.parse(value).getYear() > 0; }
        catch (DateTimeParseException exception) { return false; }
    }

    private static DomainException invalid(String message) { return new DomainException("INVALID_FORM_SCHEMA", message); }

    /**
     * 支持的六种表单输入类型。
     * @author owlzhangfq@gmail.com
     */
    public enum FieldType { TEXT, TEXTAREA, NUMBER, DATE, SELECT, BOOLEAN }

    /**
     * 不可变字段定义，不包含客户端脚本或动态表达式。
     * @author owlzhangfq@gmail.com
     */
    public record Field(String key, String label, FieldType type, boolean required, String helpText,
                        Integer maxLength, String minimum, String maximum, List<Option> options) {
        public Field {
            if (key == null || !FIELD_KEY.matcher(key).matches() || RESERVED_FIELDS.contains(key)
                    || label == null || label.isBlank() || label.length() > MAX_LABEL_LENGTH || type == null
                    || helpText != null && helpText.length() > MAX_HELP_LENGTH) {
                throw invalid("Field key, label, type or help text is invalid");
            }
            if (maxLength != null && (type != FieldType.TEXT && type != FieldType.TEXTAREA
                    || maxLength < 1 || maxLength > MAX_TEXT_LENGTH)) throw invalid("Text maximum length is invalid");
            if ((minimum != null || maximum != null) && type != FieldType.NUMBER) throw invalid("Only number fields have numeric bounds");
            try {
                BigDecimal min = minimum == null ? null : decimal(minimum);
                BigDecimal max = maximum == null ? null : decimal(maximum);
                if (min != null && max != null && min.compareTo(max) > 0) throw invalid("Minimum exceeds maximum");
            } catch (IllegalArgumentException exception) { throw invalid("Numeric bounds must be canonical decimal strings"); }
            if (type == FieldType.SELECT) {
                if (options == null || options.isEmpty() || options.size() > MAX_OPTIONS || options.stream().anyMatch(java.util.Objects::isNull)
                        || options.stream().map(Option::value).distinct().count() != options.size()) {
                    throw invalid("Select options must be nonempty and unique");
                }
            } else if (options != null && !options.isEmpty()) throw invalid("Only select fields can declare options");
            options = options == null ? null : List.copyOf(options);
        }

        private String valueError(Object value, boolean checkBounds) {
            if (type == FieldType.BOOLEAN) return value instanceof Boolean ? null : "INVALID_TYPE";
            if (!(value instanceof String text)) return "INVALID_TYPE";
            return switch (type) {
                case TEXT, TEXTAREA -> text.codePointCount(0, text.length()) > (maxLength == null ? MAX_TEXT_LENGTH : maxLength) ? "TOO_LONG" : null;
                case DATE -> validDate(text) ? null : "INVALID_DATE";
                case SELECT -> options.stream().anyMatch(option -> option.value().equals(text)) ? null : "INVALID_OPTION";
                case NUMBER -> numberError(text, checkBounds);
                case BOOLEAN -> throw new IllegalStateException("Boolean handled before text fields");
            };
        }

        private String numberError(String text, boolean checkBounds) {
            BigDecimal number;
            try { number = decimal(text); }
            catch (IllegalArgumentException exception) { return "INVALID_NUMBER"; }
            if (checkBounds && minimum != null && number.compareTo(decimal(minimum)) < 0) return "BELOW_MINIMUM";
            if (checkBounds && maximum != null && number.compareTo(decimal(maximum)) > 0) return "ABOVE_MAXIMUM";
            return null;
        }
    }

    /**
     * 下拉框保存稳定 value，label 仅用于当前版本展示。
     * @author owlzhangfq@gmail.com
     */
    public record Option(String value, String label) {
        public Option {
            if (value == null || value.isBlank() || value.length() > MAX_LABEL_LENGTH || label == null || label.isBlank() || label.length() > MAX_LABEL_LENGTH) {
                throw invalid("Select option value or label is invalid");
            }
        }
    }
}
