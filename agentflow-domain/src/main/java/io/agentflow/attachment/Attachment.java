package io.agentflow.attachment;

import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.UUID;

/**
 * 文件身份和内容指纹在登记时固定，准备完成后不能覆盖或退回失败状态。
 * @author owlzhangfq@gmail.com
 */
public record Attachment(UUID id, String tenantId, UUID applicationId, String fieldPath, String filename,
                         long size, String sha256, String createdBy, Instant createdAt, Status status) {
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
