package io.agentflow.definition;

import io.agentflow.common.DomainException;
import io.agentflow.form.FieldVisibility;
import io.agentflow.form.FormSchema;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 输入映射只投影已声明字段；来源权限和敏感标记不能借新字段名降级，附件另行建立子申请引用。
 * @author owlzhangfq@gmail.com
 */
public final class SubprocessInputs {
    private final FormSchema targetSchema;
    private final List<Binding> bindings;

    private SubprocessInputs(FormSchema targetSchema, List<Binding> bindings) {
        this.targetSchema = targetSchema; this.bindings = List.copyOf(bindings);
    }

    /** 发布前核对两份固定表单契约，运行时复用同一映射，不读取最新定义或猜测同名字段。 */
    public static SubprocessInputs bind(SubprocessPolicy policy, String nodeId, FormSchema source, FormSchema target) {
        var sourceFields = fields(source); var targetFields = fields(target);
        var bindings = new ArrayList<Binding>();
        policy.inputs().forEach((targetKey, sourceKey) -> {
            var from = sourceFields.get(sourceKey); var to = targetFields.get(targetKey);
            if (from == null || to == null) throw invalid("SUBPROCESS_INPUT_FIELD_UNKNOWN", "Subprocess mapping must use declared fields");
            bindings.add(binding(from, to, nodeId));
        });
        if (targetFields.values().stream().anyMatch(field -> field.required() && !policy.inputs().containsKey(field.key()))) {
            throw invalid("SUBPROCESS_REQUIRED_INPUT_MISSING", "Every required subprocess field needs an explicit input mapping");
        }
        return new SubprocessInputs(target, bindings);
    }

    /** 激活时冻结候选输入；附件转移清单须由应用服务完成受控重新绑定后才能提交子申请。 */
    public Projection project(Map<String, Object> sourceValues) {
        var values = new LinkedHashMap<String, Object>(); var attachments = new ArrayList<AttachmentInput>();
        for (var binding : bindings) {
            if (sourceValues.containsKey(binding.source().key())) {
                values.put(binding.target().key(), copy(binding, sourceValues.get(binding.source().key()),
                        binding.source().key(), binding.target().key(), attachments));
            }
        }
        if (targetSchema != null) targetSchema.validateSubmission(values);
        return new Projection(Collections.unmodifiableMap(values), List.copyOf(attachments));
    }

    private static Binding binding(FormSchema.Field source, FormSchema.Field target, String nodeId) {
        if (source.type() != target.type()) throw invalid("SUBPROCESS_INPUT_TYPE_MISMATCH", "Mapped field types must match");
        if (source.visibility(Set.of(nodeId)) != FieldVisibility.READ_ONLY) {
            throw invalid("SUBPROCESS_INPUT_NOT_READABLE", "Hidden or masked input cannot be passed to a subprocess");
        }
        boolean restricted = Boolean.TRUE.equals(source.sensitive()) || source.nodeAccess() != null
                && source.nodeAccess().values().stream().anyMatch(value -> value != FieldVisibility.READ_ONLY);
        if (restricted && !Boolean.TRUE.equals(target.sensitive())) {
            throw invalid("SUBPROCESS_INPUT_SENSITIVITY_LOSS", "Subprocess fields must retain the source sensitivity");
        }
        var columns = new ArrayList<Binding>();
        if (source.type() == FormSchema.FieldType.TABLE) {
            var sourceColumns = index(source.columns());
            for (var targetColumn : target.columns()) {
                var sourceColumn = sourceColumns.get(targetColumn.key());
                if (sourceColumn == null) {
                    if (targetColumn.required()) throw invalid("SUBPROCESS_REQUIRED_INPUT_MISSING", "Required subprocess table columns need a source column");
                } else columns.add(binding(sourceColumn, targetColumn, nodeId));
            }
        }
        return new Binding(source, target, List.copyOf(columns));
    }

    private static Object copy(Binding binding, Object value, String sourcePath, String targetPath, List<AttachmentInput> attachments) {
        if (value == null) return null;
        if (binding.target().type() == FormSchema.FieldType.TABLE) {
            if (!(value instanceof List<?> rows)) throw invalid("SUBPROCESS_INPUT_VALUE_INVALID", "Mapped detail input must be a row collection");
            var result = new ArrayList<Map<String, Object>>();
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                if (!(rows.get(rowIndex) instanceof Map<?, ?> row)) throw invalid("SUBPROCESS_INPUT_VALUE_INVALID", "Mapped detail rows must be objects");
                var mapped = new LinkedHashMap<String, Object>();
                for (var column : binding.columns()) if (row.containsKey(column.source().key())) {
                    mapped.put(column.target().key(), copy(column, row.get(column.source().key()),
                            sourcePath + "[" + rowIndex + "]." + column.source().key(),
                            targetPath + "[" + rowIndex + "]." + column.target().key(), attachments));
                }
                result.add(Collections.unmodifiableMap(mapped));
            }
            return List.copyOf(result);
        }
        if (binding.target().type() == FormSchema.FieldType.ATTACHMENT) {
            if (!(value instanceof List<?> ids) || ids.stream().anyMatch(id -> !(id instanceof String))) {
                throw invalid("SUBPROCESS_INPUT_VALUE_INVALID", "Mapped attachments must be identifiers");
            }
            var copied = List.copyOf(ids);
            for (var id : copied) {
                try { attachments.add(new AttachmentInput(sourcePath, targetPath, UUID.fromString((String) id))); }
                catch (IllegalArgumentException invalid) { throw invalid("SUBPROCESS_INPUT_VALUE_INVALID", "Mapped attachment identifier is invalid"); }
            }
            return copied;
        }
        // 基础字段只允许不可变的原始值，其他类型交给目标表单统一报错。
        return value;
    }

    private static Map<String, FormSchema.Field> fields(FormSchema schema) { return schema == null ? Map.of() : index(schema.fields()); }
    private static Map<String, FormSchema.Field> index(List<FormSchema.Field> fields) {
        var result = new LinkedHashMap<String, FormSchema.Field>(); fields.forEach(field -> result.put(field.key(), field)); return result;
    }
    private static DomainException invalid(String code, String message) { return new DomainException(code, message); }

    /** @author owlzhangfq@gmail.com */
    private record Binding(FormSchema.Field source, FormSchema.Field target, List<Binding> columns) { }
    /**
     * 源附件只作为输入证明，不能将其原编号直接当作子申请附件授权。
     * @author owlzhangfq@gmail.com
     */
    public record AttachmentInput(String sourceFieldPath, String targetFieldPath, UUID sourceAttachmentId) { }
    /**
     * 候选表单与待重新绑定的附件保持一致；纯领域投影不读取文件或授予下载权限。
     * @author owlzhangfq@gmail.com
     */
    public record Projection(Map<String, Object> values, List<AttachmentInput> attachments) { }
}
