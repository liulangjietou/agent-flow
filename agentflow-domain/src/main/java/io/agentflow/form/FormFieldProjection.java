package io.agentflow.form;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按已确定节点身份生成表单展示副本，实际查询与设计预览共用同一字段规则。
 * 调用方负责资源授权和确定节点身份，本类型不查询人员或业务资源。
 * @author owlzhangfq@gmail.com
 */
public record FormFieldProjection(FormSchema schema, Map<String, Object> payload, boolean restricted) {
    private static final String MASKED_VALUE = "已脱敏";

    /** 不修改原表单或内容；隐藏同时移除字段配置与值，脱敏不保留原值特征。 */
    public static FormFieldProjection forNodes(FormSchema schema, Map<String, Object> payload, Set<String> nodes) {
        var fields = new ArrayList<FormSchema.Field>();
        var values = new LinkedHashMap<String, Object>();
        boolean restricted = false;
        for (var field : schema.fields()) {
            var projected = field(field, nodes);
            restricted |= !field.equals(projected);
            if (projected == null) continue;
            fields.add(projected);
            if (field.visibility(nodes) == FieldVisibility.MASKED) values.put(field.key(), MASKED_VALUE);
            else if (payload.containsKey(field.key())) values.put(field.key(), value(field, payload.get(field.key()), nodes));
        }
        return new FormFieldProjection(new FormSchema(schema.schemaVersion(), fields), java.util.Collections.unmodifiableMap(values), restricted);
    }

    private static FormSchema.Field field(FormSchema.Field field, Set<String> nodes) {
        var access = field.visibility(nodes);
        if (access == FieldVisibility.HIDDEN) return null;
        if (access == FieldVisibility.MASKED) return new FormSchema.Field(field.key(), field.label(), FormSchema.FieldType.TEXT,
                false, "该字段已按权限脱敏", null, null, null, null);
        if (field.type() != FormSchema.FieldType.TABLE) return field;
        var columns = field.columns().stream().map(column -> field(column, nodes)).filter(java.util.Objects::nonNull).toList();
        if (columns.isEmpty()) return null;
        return new FormSchema.Field(field.key(), field.label(), field.type(), field.required(), field.helpText(), field.maxLength(),
                field.minimum(), field.maximum(), field.options(), columns, field.maxRows(), field.sensitive(), field.nodeAccess());
    }

    private static Object value(FormSchema.Field field, Object raw, Set<String> nodes) {
        if (field.visibility(nodes) == FieldVisibility.MASKED) return MASKED_VALUE;
        if (field.type() != FormSchema.FieldType.TABLE || !(raw instanceof List<?> rows)) return raw;
        return rows.stream().map(row -> {
            var visible = new LinkedHashMap<String, Object>();
            if (row instanceof Map<?, ?> cells) for (var column : field.columns()) {
                var access = column.visibility(nodes);
                if (access == FieldVisibility.MASKED) visible.put(column.key(), MASKED_VALUE);
                else if (access == FieldVisibility.READ_ONLY && cells.containsKey(column.key())) visible.put(column.key(), cells.get(column.key()));
            }
            return java.util.Collections.unmodifiableMap(visible);
        }).toList();
    }
}
