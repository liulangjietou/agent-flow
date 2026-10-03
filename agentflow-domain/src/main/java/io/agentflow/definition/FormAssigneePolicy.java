package io.agentflow.definition;

import io.agentflow.common.DomainException;
import io.agentflow.form.FormSchema;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 人工节点从版本化单选字段关联本地组织对象，字段值不是账号、角色或可执行表达式。
 * @author owlzhangfq@gmail.com
 */
public record FormAssigneePolicy(String fieldKey, Relation relation) {
    public static final String PREFIX = "field:";
    private static final Pattern FIELD_KEY = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{0,63}");
    private static final Pattern UUID_VALUE = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    public FormAssigneePolicy {
        if (fieldKey == null || !FIELD_KEY.matcher(fieldKey).matches() || relation == null) throw invalidRule();
    }

    /** 只识别新的独立命名空间，旧 user／role 规则保持原语义。 */
    public static boolean isFieldRule(String rule) { return rule != null && rule.startsWith(PREFIX); }

    /** 解析发布器允许的固定关系名称，拒绝嵌套路径和脚本。 */
    public static FormAssigneePolicy parse(String rule) {
        if (!isFieldRule(rule)) throw invalidRule();
        String[] parts = rule.substring(PREFIX.length()).split(":", -1);
        if (parts.length != 2) throw invalidRule();
        try { return new FormAssigneePolicy(parts[0], Relation.valueOf(parts[1])); }
        catch (IllegalArgumentException invalid) { throw invalidRule(); }
    }

    /** 保存规范规则，不把字段值拼进引擎表达式。 */
    public String rule() { return PREFIX + fieldKey + ":" + relation.name(); }

    /** 缺失、可选或非单选字段不能决定审批责任；组织对象类型由发布应用服务核对。 */
    public FormSchema.Field field(FormSchema schema) {
        var field = schema == null ? null : schema.fields().stream().filter(value -> value.key().equals(fieldKey)).findFirst().orElse(null);
        if (field == null || field.type() != FormSchema.FieldType.SELECT || !field.required()) {
            throw new DomainException("FORM_ASSIGNEE_FIELD_REQUIRED", "Form assignee requires a required top-level select field");
        }
        if (field.options().stream().anyMatch(option -> !UUID_VALUE.matcher(option.value()).matches())) {
            throw new DomainException("FORM_ASSIGNEE_OPTION_INVALID", "Form assignee options must contain canonical organization identifiers");
        }
        return field;
    }

    /** 返回已声明的对象标识，发布检查全部选项而非仅检查当前测试值。 */
    public List<UUID> references(FormSchema schema) { return field(schema).options().stream().map(option -> UUID.fromString(option.value())).toList(); }

    private static DomainException invalidRule() { return new DomainException("FORM_ASSIGNEE_RULE_INVALID", "Form assignee rule must name a field and a supported organization relation"); }

    /**
     * 同一种组织来源可用于不同业务关系，关系含义随流程版本保存。
     * @author owlzhangfq@gmail.com
     */
    public enum Relation {
        PERSON, DEPARTMENT_HEAD, DEPARTMENT_MEMBERS, POSITION_MEMBERS;

        /** 返回配置字段的目录来源类型。 */
        public SourceKind sourceKind() {
            return switch (this) {
                case PERSON -> SourceKind.PERSON;
                case DEPARTMENT_HEAD, DEPARTMENT_MEMBERS -> SourceKind.DEPARTMENT;
                case POSITION_MEMBERS -> SourceKind.POSITION;
            };
        }
    }

    /**
     * 设计器可以绑定的本地对象，不提供任意角色或跨租户账号输入。
     * @author owlzhangfq@gmail.com
     */
    public enum SourceKind { PERSON, DEPARTMENT, POSITION }
}
