package io.agentflow.attachment;

import io.agentflow.approval.model.Application;
import io.agentflow.common.DomainException;
import org.springframework.stereotype.Service;

/**
 * 跨申请与附件的引用核对，在申请写事务中执行，领域聚合不依赖文件系统。
 * @author owlzhangfq@gmail.com
 */
@Service
public class AttachmentReferenceService {
    private final JdbcAttachmentRepository files;
    private final LocalAttachmentStore store;
    /** 注入本用例共享的持久化依赖。 */
    public AttachmentReferenceService(JdbcAttachmentRepository files, LocalAttachmentStore store) { this.files = files; this.store = store; }

    /** 草稿可保存尚在上传的引用；提交前每份文件必须准备完成且内容完整。 */
    public void validate(Application application, boolean submitted) {
        for (var reference : AttachmentReferences.collect(application.formSchema(), application.payload())) {
            Attachment file;
            try { file = files.get(application.tenantId(), application.id(), reference.id()); }
            catch (DomainException missing) { throw new DomainException("INVALID_ATTACHMENT_REFERENCE", "Attachment does not belong to this application"); }
            if (!file.fieldPath().equals(reference.fieldPath())) throw new DomainException("INVALID_ATTACHMENT_REFERENCE", "Attachment belongs to a different field");
            if (submitted) { file.requireReady(); store.read(file); }
        }
    }

    /** 服务已核对本次引用；轮次行和附件引用一并提交或回滚。 */
    public void freeze(Application application) {
        for (var reference : AttachmentReferences.collect(application.formSchema(), application.payload())) {
            files.freeze(files.get(application.tenantId(), application.id(), reference.id()), application.roundNo());
        }
    }
}
