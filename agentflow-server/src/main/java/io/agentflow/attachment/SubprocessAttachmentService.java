package io.agentflow.attachment;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.SubprocessCall;
import io.agentflow.common.DomainException;
import io.agentflow.definition.SubprocessInputs;
import io.agentflow.form.FormSchema;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 子调用的附件编排核对父引用和真实原件，再保存独立授权身份；不复制文件，也不扩大下载权限。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SubprocessAttachmentService {
    private final JdbcAttachmentRepository files;
    private final LocalAttachmentStore store;
    private final JdbcTemplate jdbc;

    /** 复用既有附件配额、物理完整性校验及同事务数据源。 */
    public SubprocessAttachmentService(JdbcAttachmentRepository files, LocalAttachmentStore store, JdbcTemplate jdbc) {
        this.files = files; this.store = store; this.jdbc = jdbc;
    }

    /** 在创建子申请前分配独立引用，输入来自已验证的固定版本映射，调用方持有父申请锁。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Prepared prepare(Application parent, UUID childId, FormSchema childSchema, SubprocessInputs.Projection projection,
                            String actor, Instant at) {
        var sourceReferences = AttachmentReferences.collect(parent.formSchema(), parent.payload());
        var replacements = new LinkedHashMap<AttachmentReferences.Reference, UUID>();
        var transfers = new ArrayList<Transfer>();
        for (var input : projection.attachments()) {
            if (!sourceReferences.contains(new AttachmentReferences.Reference(input.sourceFieldPath(), input.sourceAttachmentId()))) {
                throw invalidReference();
            }
            var source = files.get(parent.tenantId(), parent.id(), input.sourceAttachmentId());
            if (!source.fieldPath().equals(input.sourceFieldPath())) throw invalidReference();
            source.requireReady(); store.read(source);
            var target = source.rebind(UUID.randomUUID(), childId, input.targetFieldPath(), actor, at);
            replacements.put(new AttachmentReferences.Reference(input.targetFieldPath(), source.id()), target.id());
            transfers.add(new Transfer(source, target));
        }
        var values = replace(childSchema, projection.values(), replacements);
        return new Prepared(parent.tenantId(), parent.id(), childId, values, transfers);
    }

    /** 子申请、首轮和父子关系已入库后追加授权及来源；任一配额或外键失败使整个调用回滚。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void persist(SubprocessCall call, Prepared prepared) {
        if (!call.tenantId().equals(prepared.tenantId()) || !call.parentApplicationId().equals(prepared.parentId())
                || !call.childApplicationId().equals(prepared.childId())) throw invalidReference();
        for (var transfer : prepared.transfers()) {
            var source = transfer.source(); var target = transfer.target();
            files.requireCapacity(call.tenantId(), call.childApplicationId(), target.size(), store.maxApplicationBytes(), store.maxApplicationUploads());
            files.insert(target);
            jdbc.update("""
                    INSERT INTO approval_subprocess_attachment
                    (tenant_id,call_id,parent_application_id,child_application_id,source_field_path,source_attachment_id,target_field_path,target_attachment_id)
                    VALUES (?,?,?,?,?,?,?,?)
                    """, call.tenantId(), call.id().toString(), call.parentApplicationId().toString(), call.childApplicationId().toString(),
                    source.fieldPath(), source.id().toString(), target.fieldPath(), target.id().toString());
            files.freeze(target, SubprocessCall.CHILD_ROUND);
        }
    }

    private static Map<String, Object> replace(FormSchema schema, Map<String, Object> values,
                                               Map<AttachmentReferences.Reference, UUID> replacements) {
        var result = new LinkedHashMap<>(values);
        if (schema == null) return Collections.unmodifiableMap(result);
        for (var field : schema.fields()) {
            if (field.type() == FormSchema.FieldType.ATTACHMENT && values.get(field.key()) instanceof List<?> ids) {
                result.put(field.key(), replaceIds(field.key(), ids, replacements));
            } else if (field.type() == FormSchema.FieldType.TABLE && values.get(field.key()) instanceof List<?> rows) {
                var mapped = new ArrayList<Map<String, Object>>();
                for (var item : rows) {
                    var row = new LinkedHashMap<String, Object>();
                    ((Map<?, ?>) item).forEach((key, value) -> row.put((String) key, value));
                    for (var column : field.columns()) {
                        if (column.type() == FormSchema.FieldType.ATTACHMENT && row.get(column.key()) instanceof List<?> ids) {
                            row.put(column.key(), replaceIds(field.key() + "." + column.key(), ids, replacements));
                        }
                    }
                    mapped.add(Collections.unmodifiableMap(row));
                }
                result.put(field.key(), List.copyOf(mapped));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static List<String> replaceIds(String path, List<?> ids, Map<AttachmentReferences.Reference, UUID> replacements) {
        return ids.stream().map(id -> {
            var target = replacements.get(new AttachmentReferences.Reference(path, UUID.fromString((String) id)));
            if (target == null) throw invalidReference();
            return target.toString();
        }).toList();
    }

    private static DomainException invalidReference() {
        return new DomainException("INVALID_ATTACHMENT_REFERENCE", "Subprocess attachment input differs from its declared source");
    }

    /**
     * 私有构造确保新引用由本服务核验并生成，调用方只取得待写入的子表单。
     * @author owlzhangfq@gmail.com
     */
    public static final class Prepared {
        private final String tenantId;
        private final UUID parentId;
        private final UUID childId;
        private final Map<String, Object> values;
        private final List<Transfer> transfers;
        private Prepared(String tenantId, UUID parentId, UUID childId, Map<String, Object> values, List<Transfer> transfers) {
            this.tenantId = tenantId; this.parentId = parentId; this.childId = childId;
            this.values = values; this.transfers = List.copyOf(transfers);
        }
        public Map<String, Object> values() { return values; }
        private String tenantId() { return tenantId; }
        private UUID parentId() { return parentId; }
        private UUID childId() { return childId; }
        private List<Transfer> transfers() { return transfers; }
    }

    /** @author owlzhangfq@gmail.com */
    private record Transfer(Attachment source, Attachment target) { }
}
