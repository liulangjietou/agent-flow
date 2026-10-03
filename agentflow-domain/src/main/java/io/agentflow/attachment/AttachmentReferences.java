package io.agentflow.attachment;

import io.agentflow.form.FormSchema;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 从已校验或已授权投影的表单提取文件引用，不把普通文本中的标识当作附件。
 * @author owlzhangfq@gmail.com
 */
public final class AttachmentReferences {
    private AttachmentReferences() { }

    /** 明细列按稳定字段路径绑定；行序由原始轮次 payload 保留。 */
    public static Set<Reference> collect(FormSchema schema, Map<String, Object> payload) {
        Set<Reference> result = new LinkedHashSet<>();
        if (schema == null) return result;
        for (var field : schema.fields()) {
            if (field.type() == FormSchema.FieldType.ATTACHMENT) add(result, field.key(), payload.get(field.key()));
            else if (field.type() == FormSchema.FieldType.TABLE && payload.get(field.key()) instanceof List<?> rows) {
                for (Object item : rows) if (item instanceof Map<?, ?> row) {
                    for (var column : field.columns()) if (column.type() == FormSchema.FieldType.ATTACHMENT) {
                        add(result, field.key() + "." + column.key(), row.get(column.key()));
                    }
                }
            }
        }
        return Set.copyOf(result);
    }

    /** 隐藏、脱敏字段在投影后不存在或已成为文本，因此不会被识别为可读附件。 */
    public static boolean containsField(FormSchema schema, String path) {
        if (schema == null) return false;
        for (var field : schema.fields()) {
            if (field.type() == FormSchema.FieldType.ATTACHMENT && field.key().equals(path)) return true;
            if (field.type() == FormSchema.FieldType.TABLE && field.columns().stream().anyMatch(column ->
                    column.type() == FormSchema.FieldType.ATTACHMENT && (field.key() + "." + column.key()).equals(path))) return true;
        }
        return false;
    }

    private static void add(Set<Reference> references, String path, Object value) {
        if (value instanceof List<?> ids) for (Object id : ids) references.add(new Reference(path, UUID.fromString((String) id)));
    }

    /**
     * 一个文件只属于本申请的一个字段，不能借另一宽权限字段取回原内容。
     * @author owlzhangfq@gmail.com
     */
    public record Reference(String fieldPath, UUID id) { }
}
