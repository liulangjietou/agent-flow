package io.agentflow.attachment;

import io.agentflow.approval.ApprovalApplicationFacade;
import io.agentflow.approval.ApplicationFieldViews;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.form.FormSchema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/**
 * 附件用例编排：文件传输位于数据库事务外，发布和状态确认时重新锁定申请。
 * @author owlzhangfq@gmail.com
 */
@Service
public class AttachmentService {
    private final ApprovalApplicationFacade applications;
    private final ApplicationFieldViews fields;
    private final CurrentActor actors;
    private final JdbcAttachmentRepository files;
    private final LocalAttachmentStore store;
    private final TransactionTemplate transaction;

    /** 元数据、申请授权和本地文件由组合协作，不把 I/O 放入申请实体。 */
    public AttachmentService(ApprovalApplicationFacade applications, ApplicationFieldViews fields, CurrentActor actors,
            JdbcAttachmentRepository files, LocalAttachmentStore store, PlatformTransactionManager transactions) {
        this.applications = applications; this.fields = fields; this.actors = actors; this.files = files; this.store = store;
        this.transaction = new TransactionTemplate(transactions);
    }

    /** 返回实际部署限制，不公开主机路径；文件不自动执行、预览或送往扫描服务。 */
    public Options options() {
        return new Options(store.available(), store.maxFileBytes(), store.maxApplicationBytes(), store.maxApplicationUploads(), FormSchema.MAX_ATTACHMENTS, false);
    }

    /** 登记时校验字段归属和容量，同一幂等请求返回原附件身份。 */
    @Transactional
    public Metadata reserve(UUID applicationId, UploadInput input) {
        if (!store.available()) throw new DomainException("ATTACHMENT_STORAGE_UNAVAILABLE", "Attachment storage is unavailable");
        var actor = actors.actor();
        files.lockApplication(actor.tenantId(), applicationId);
        var application = applications.requireApplicant(applicationId);
        application.requireEditable(input.expectedVersion());
        if (!AttachmentReferences.containsField(application.formSchema(), input.fieldPath())) {
            throw new DomainException("INVALID_ATTACHMENT_REFERENCE", "Field is not an attachment field");
        }
        if (input.size() > store.maxFileBytes()) throw new DomainException("ATTACHMENT_TOO_LARGE", "Attachment exceeds the configured file limit");
        files.requireCapacity(actor.tenantId(), applicationId, input.size(), store.maxApplicationBytes(), store.maxApplicationUploads());
        var file = new Attachment(UUID.randomUUID(), actor.tenantId(), applicationId, input.fieldPath(), input.filename(),
                input.size(), input.sha256(), actor.userId(), Instant.now(), Attachment.Status.UPLOADING);
        files.insert(file);
        return Metadata.from(file);
    }

    /** 原标识和内容指纹支持断网重试，传输完成后再次核对申请状态及版本。 */
    public Metadata upload(UUID applicationId, UUID id, long expectedVersion, InputStream input) {
        var application = applications.requireApplicant(applicationId);
        application.requireEditable(expectedVersion);
        var file = files.get(application.tenantId(), applicationId, id);
        Path staged = null;
        try {
            staged = store.stage(file, input);
            Path content = staged;
            return transaction.execute(status -> {
                files.lockApplication(application.tenantId(), applicationId);
                applications.requireApplicant(applicationId).requireEditable(expectedVersion);
                store.publish(file, content);
                files.transition(file, Attachment.Status.READY);
                return Metadata.from(files.get(file.tenantId(), applicationId, id));
            });
        } catch (DomainException failure) {
            transaction.executeWithoutResult(status -> files.transition(file, Attachment.Status.FAILED));
            throw failure;
        } finally { store.discard(staged); }
    }

    /** 每次查询重新验证当轮权限；申请人可查询本申请尚未保存引用的上传以恢复操作。 */
    public Metadata metadata(UUID application, UUID id, Integer round) { return Metadata.from(readable(application, id, round)); }

    /** 授权通过后才读取文件字节，隐藏或脱敏字段不得下载原文件。 */
    public Download download(UUID application, UUID id, Integer round) {
        var file = readable(application, id, round);
        file.requireReady();
        return new Download(file.filename(), store.read(file));
    }

    private Attachment readable(UUID applicationId, UUID id, Integer round) {
        var application = applications.get(applicationId);
        var view = fields.attachmentView(application, round);
        var file = files.get(application.tenantId(), applicationId, id);
        if (!AttachmentReferences.containsField(view.schema(), file.fieldPath())) {
            throw new DomainException("FORBIDDEN", "Field permissions do not allow reading this attachment");
        }
        var reference = new AttachmentReferences.Reference(file.fieldPath(), id);
        boolean referenced = AttachmentReferences.collect(view.schema(), view.payload()).contains(reference);
        if (round != null ? !referenced || !files.frozen(file, round)
                : !referenced && !(application.createdBy().equals(actors.actor().userId()) && application.editable())) {
            throw new DomainException("NOT_FOUND", "Attachment is not referenced in this view");
        }
        return file;
    }

    /**
     * 登记入口只接受固定内容事实和乐观锁版本，不接受存储路径、URL 或租户。
     * @author owlzhangfq@gmail.com
     */
    public record UploadInput(@NotNull Long expectedVersion,
            @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]{0,63}(\\.[A-Za-z][A-Za-z0-9_]{0,63})?") String fieldPath,
            @NotBlank @Size(max = 255) @Pattern(regexp = "[^\\p{Cntrl}/\\\\]+") String filename,
            @NotNull @Min(0) @Max(LocalAttachmentStore.MAX_SUPPORTED_BYTES) Long size,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String sha256) { }

    /**
     * 可见字段的文件身份与状态，永不包含本地路径。
     * @author owlzhangfq@gmail.com
     */
    public record Metadata(UUID id, String fieldPath, String filename, long size, String sha256, Attachment.Status status) {
        static Metadata from(Attachment file) { return new Metadata(file.id(), file.fieldPath(), file.filename(), file.size(), file.sha256(), file.status()); }
    }

    /**
     * 当前部署实际可用的上传限制。
     * @author owlzhangfq@gmail.com
     */
    public record Options(boolean enabled, long maxFileBytes, long maxApplicationBytes, int maxApplicationUploads,
                          int maxAttachmentsPerField, boolean contentScanAvailable) { }

    /**
     * 已完成资源授权及完整性校验的下载内容。
     * @author owlzhangfq@gmail.com
     */
    public record Download(String filename, byte[] content) { }
}
