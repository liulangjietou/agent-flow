package io.agentflow.attachment;

import io.agentflow.common.DomainException;
import io.agentflow.storage.LocalDocumentStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * 审批附件使用公共不可变文件存储，保留原有容量和错误契约；字段授权仍属于附件用例。
 * @author owlzhangfq@gmail.com
 */
@Component
public class LocalAttachmentStore {
    public static final long MAX_SUPPORTED_BYTES = LocalDocumentStore.MAX_SUPPORTED_BYTES;
    private final LocalDocumentStore documents;
    private final long maxApplicationBytes;
    private final int maxApplicationUploads;

    /** 共享物理目录，但申请配额不影响个人票夹的独立配额。 */
    public LocalAttachmentStore(LocalDocumentStore documents,
            @Value("${agentflow.attachments.max-application-bytes:268435456}") long maxApplicationBytes,
            @Value("${agentflow.attachments.max-application-uploads:100}") int maxApplicationUploads) {
        if (maxApplicationBytes < documents.maxFileBytes() || maxApplicationUploads < 1 || maxApplicationUploads > 10000) {
            throw new IllegalArgumentException("Invalid attachment storage limits");
        }
        this.documents = documents; this.maxApplicationBytes = maxApplicationBytes; this.maxApplicationUploads = maxApplicationUploads;
    }

    public boolean enabled() { return documents.enabled(); }
    public long maxFileBytes() { return documents.maxFileBytes(); }
    public long maxApplicationBytes() { return maxApplicationBytes; }
    public int maxApplicationUploads() { return maxApplicationUploads; }

    /** 附件身份只转换为字节指纹，传输仍在事务外进行。 */
    public Path stage(Attachment attachment, InputStream input) {
        attachment.requireUploadOwner();
        return originalContract(() -> documents.stage(content(attachment), input));
    }

    /** 发布不允许覆盖，同一内容重试保持原义。 */
    public void publish(Attachment attachment, Path staged) {
        attachment.requireUploadOwner();
        originalContract(() -> { documents.publish(content(attachment), staged); return null; });
    }

    /** 完整性校验错误继续使用既有附件错误码。 */
    public byte[] read(Attachment attachment) { return originalContract(() -> documents.read(content(attachment))); }

    /** 仅删除本次暂存文件，不开放原件删除。 */
    public void discard(Path staged) { documents.discard(staged); }

    /** 配置检查不公开实际目录。 */
    public boolean available() { return documents.available(); }

    private static LocalDocumentStore.Content content(Attachment value) { return new LocalDocumentStore.Content(value.contentId(), value.size(), value.sha256()); }
    private static <T> T originalContract(Supplier<T> operation) {
        try { return operation.get(); }
        catch (DomainException failure) { throw new DomainException(failure.code().replace("FILE_", "ATTACHMENT_"), failure.getMessage()); }
    }
}
