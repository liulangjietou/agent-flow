package io.agentflow.attachment;

import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.UUID;

/**
 * 文件身份和内容指纹在登记时固定，准备完成后不能覆盖或退回失败状态。
 * @author owlzhangfq@gmail.com
 */
public record Attachment(UUID id, String tenantId, UUID applicationId, String fieldPath, String filename,
                         long size, String sha256, String createdBy, Instant createdAt, Status status, UUID contentId) {
    /** 普通上传的文件身份就是物理原件身份，兼容现有上传和历史记录。 */
    public Attachment(UUID id, String tenantId, UUID applicationId, String fieldPath, String filename,
                      long size, String sha256, String createdBy, Instant createdAt, Status status) {
        this(id, tenantId, applicationId, fieldPath, filename, size, sha256, createdBy, createdAt, status, id);
    }

    /** 老记录没有独立原件编号时继续使用自身编号，引用只能指向已经准备完成的内容。 */
    public Attachment {
        if (contentId == null) contentId = id;
        if (!id.equals(contentId) && status != Status.READY) {
            throw new DomainException("ATTACHMENT_NOT_READY", "A shared attachment must reference ready content");
        }
    }

    /** 子申请另有附件身份和字段归属，连续子调用仍直接引用最初原件。 */
    public Attachment rebind(UUID referenceId, UUID targetApplication, String targetField, String actor, Instant at) {
        requireReady();
        return new Attachment(referenceId, tenantId, targetApplication, targetField, filename, size, sha256,
                actor, at, Status.READY, contentId);
    }

    /** 共享引用不接受上传，防止通过另一个申请触碰原件。 */
    public void requireUploadOwner() {
        if (!id.equals(contentId)) throw new DomainException("ATTACHMENT_SHARED_CONTENT", "Shared attachment content cannot be uploaded");
    }

    /** 只有内容已校验的附件可进入提交快照。 */
    public void requireReady() {
        if (status != Status.READY) throw new DomainException("ATTACHMENT_NOT_READY", "Referenced attachment content is not ready");
    }

    /** 内容与登记事实必须逐字节对应；失败的上传可使用原标识重试。 */
    public void verify(long actualSize, String actualDigest) {
        if (actualSize != size || !sha256.equals(actualDigest)) {
            throw new DomainException("ATTACHMENT_CONTENT_MISMATCH", "Attachment content differs from its registered fingerprint");
        }
    }

    /**
     * 上传状态与审批状态独立，准备完成后不可覆盖。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { UPLOADING, READY, FAILED }
}
