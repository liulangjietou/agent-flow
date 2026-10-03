package io.agentflow.expense;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.util.UUID;

/**
 * 个人票夹原件独立于审批申请；登记后身份、字节指纹和归属不可修改。
 * @author owlzhangfq@gmail.com
 */
public record InvoiceOriginal(UUID id, UUID invoiceId, String tenantId, String ownerId, String filename,
                              long size, String sha256, Format format, Instant createdAt, Status status) {
    /** 原件只接受支持的类型和有限内容，查验成功由发票聚合单独维护。 */
    public InvoiceOriginal {
        if (id == null || invoiceId == null || StringUtils.isBlank(tenantId) || tenantId.length() > 64
                || StringUtils.isBlank(ownerId) || ownerId.length() > 128 || StringUtils.isBlank(filename) || filename.length() > 255
                || !filename.matches("[^\\p{Cntrl}/\\\\]+") || size < 1 || size > InvoiceVerificationPort.Request.MAX_ORIGINAL_BYTES
                || sha256 == null || !sha256.matches("[a-f0-9]{64}") || format == null || createdAt == null || status == null) {
            throw new DomainException("INVALID_INVOICE_ORIGINAL", "Invoice original metadata is invalid");
        }
    }

    /** 文件完整性和格式识别通过后成为可下载原件，仍未表示发票真实有效。 */
    public InvoiceOriginal ready() { return withStatus(Status.READY); }

    /** 失败的重复传输不能把已经发布的原件降级。 */
    public InvoiceOriginal failed() { return status == Status.READY ? this : withStatus(Status.FAILED); }

    /** 完整原件才可发送查验或下载。 */
    public void requireReady() {
        if (status != Status.READY) throw new DomainException("INVOICE_ORIGINAL_NOT_READY", "Invoice original upload is not complete");
    }
    private InvoiceOriginal withStatus(Status next) { return new InvoiceOriginal(id, invoiceId, tenantId, ownerId, filename, size, sha256, format, createdAt, next); }

    /**
     * 仅表示文件内容上传完成，不替代票面查验状态。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { UPLOADING, READY, FAILED }

    /**
     * 封闭的原件格式，媒体类型由服务端产生。
     * @author owlzhangfq@gmail.com
     */
    public enum Format {
        PDF("application/pdf"), OFD("application/ofd"), PNG("image/png"), JPEG("image/jpeg"), XML("application/xml");
        private final String mediaType;
        Format(String mediaType) { this.mediaType = mediaType; }
        public String mediaType() { return mediaType; }
    }
}
