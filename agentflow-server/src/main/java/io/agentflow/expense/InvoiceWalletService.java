package io.agentflow.expense;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 个人票夹上传、恢复与读取；文件传输位于事务外，归属不由管理员或客户端覆盖。
 * @author owlzhangfq@gmail.com
 */
@Service
public class InvoiceWalletService {
    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 100;
    private final CurrentActor actors;
    private final InvoiceRepository invoices;
    private final JdbcInvoiceOriginalRepository originals;
    private final InvoiceOriginalFiles files;
    private final TransactionTemplate transaction;
    private final long maximumBytes;
    private final int maximumUploads;

    /** 个人配额独立于申请附件配额，已发布原件始终保留。 */
    public InvoiceWalletService(CurrentActor actors, InvoiceRepository invoices, JdbcInvoiceOriginalRepository originals,
            InvoiceOriginalFiles files, PlatformTransactionManager transactions,
            @Value("${agentflow.invoices.max-wallet-bytes:1073741824}") long maximumBytes,
            @Value("${agentflow.invoices.max-wallet-uploads:1000}") int maximumUploads) {
        if (maximumBytes < files.maxFileBytes() || maximumUploads < 1 || maximumUploads > 100000) throw new IllegalArgumentException("Invalid invoice wallet capacity");
        this.actors = actors; this.invoices = invoices; this.originals = originals; this.files = files;
        this.transaction = new TransactionTemplate(transactions); this.maximumBytes = maximumBytes; this.maximumUploads = maximumUploads;
    }

    /** 展示真实类型与容量，不暴露路径或查验凭据。 */
    public Options options() { return new Options(files.available(), files.maxFileBytes(), maximumBytes, maximumUploads, List.of(InvoiceOriginal.Format.values())); }

    /** 登记原件和待查验发票，容量、财务资源及幂等回执在同一事务提交。 */
    @Transactional
    public Receipt reserve(UploadInput input) {
        if (!files.available()) throw new DomainException("FILE_STORAGE_UNAVAILABLE", "Document storage is unavailable");
        if (input.size() > files.maxFileBytes()) throw new DomainException("FILE_TOO_LARGE", "Invoice original exceeds the configured file limit");
        var actor = actors.actor();
        var original = new InvoiceOriginal(UUID.randomUUID(), UUID.randomUUID(), actor.tenantId(), actor.userId(), input.filename(),
                input.size(), input.sha256(), input.format(), Instant.now(), InvoiceOriginal.Status.UPLOADING);
        originals.reserveCapacity(actor.tenantId(), actor.userId(), original.size(), maximumBytes, maximumUploads);
        invoices.create(Invoice.uploaded(original.invoiceId(), original.tenantId(), original.ownerId(), original.id(), original.sha256()), actor.userId());
        originals.create(original);
        return new Receipt(original.invoiceId());
    }

    /** 原身份和指纹支持重传，已发布内容不能覆盖或被晚到失败降级。 */
    public OriginalMetadata upload(UUID id, InputStream input) {
        var original = owned(id); Path staged = null;
        try {
            staged = files.stage(original, input); Path content = staged;
            return transaction.execute(status -> {
                var current = originals.lock(original.tenantId(), id);
                files.publish(current, content);
                var ready = current.ready(); originals.updateStatus(ready);
                return metadata(ready);
            });
        } catch (DomainException failure) {
            transaction.executeWithoutResult(status -> originals.updateStatus(originals.lock(original.tenantId(), id).failed()));
            throw failure;
        } finally { files.discard(staged); }
    }

    /** 当前原件和票面只对本人开放，管理员没有个人票夹明文豁免。 */
    public Item get(UUID id) {
        var original = owned(id);
        return item(original, invoices.find(original.tenantId(), id).orElseThrow(InvoiceWalletService::notFound));
    }

    /** 下载完整性经过核对的原件，不以内联格式执行内容。 */
    public Download download(UUID id) {
        var original = owned(id); original.requireReady();
        return new Download(original.filename(), files.read(original));
    }

    /** 有界游标查询不能覆盖当前租户与员工身份。 */
    public Page list(Map<String, String> parameters) {
        int limit = DEFAULT_LIMIT; UUID before = null;
        try {
            if (!Set.of("limit", "beforeId").containsAll(parameters.keySet())) throw new IllegalArgumentException();
            if (parameters.containsKey("limit")) limit = Integer.parseInt(parameters.get("limit"));
            if (limit < 1 || limit > MAX_LIMIT) throw new IllegalArgumentException();
            if (parameters.containsKey("beforeId")) {
                before = UUID.fromString(parameters.get("beforeId"));
                if (!before.toString().equals(parameters.get("beforeId"))) throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) { throw new DomainException("INVALID_INVOICE_QUERY", "Invoice pagination parameters are invalid"); }
        var actor = actors.actor(); var entries = originals.list(actor.tenantId(), actor.userId(), before, limit + 1);
        int count = Math.min(limit, entries.size());
        return new Page(entries.subList(0, count).stream().map(entry -> item(entry.original(), entry.invoice())).toList(),
                entries.size() > count ? entries.get(count - 1).invoice().id() : null);
    }

    private InvoiceOriginal owned(UUID id) {
        var actor = actors.actor();
        return originals.find(actor.tenantId(), id).filter(original -> actor.userId().equals(original.ownerId())).orElseThrow(InvoiceWalletService::notFound);
    }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Invoice original not found"); }
    private static OriginalMetadata metadata(InvoiceOriginal value) { return new OriginalMetadata(value.id(), value.filename(), value.size(), value.sha256(), value.format(), value.status(), value.createdAt()); }
    private static Item item(InvoiceOriginal original, Invoice value) {
        return new Item(value.id(), value.version(), metadata(original), value.verification(), value.occupation(), value.facts(), value.use(), value.failureCode(), value.checkedAt());
    }

    /**
     * 客户端只能登记文件事实，不接收票面金额、查验状态或他人身份。
     * @author owlzhangfq@gmail.com
     */
    public record UploadInput(@NotBlank @Size(max = 255) @Pattern(regexp = "[^\\p{Cntrl}/\\\\]+") String filename,
            @NotNull @Min(1) @Max(InvoiceVerificationPort.Request.MAX_ORIGINAL_BYTES) Long size,
            @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String sha256, @NotNull InvoiceOriginal.Format format) { }
    /**
     * 登记回执只定位发票，后续状态通过实时查询取得。
     * @author owlzhangfq@gmail.com
     */
    public record Receipt(UUID id) { }
    /**
     * 稳定原件元数据，不公开主机路径。
     * @author owlzhangfq@gmail.com
     */
    public record OriginalMetadata(UUID id, String filename, long size, String sha256, InvoiceOriginal.Format format, InvoiceOriginal.Status status, Instant createdAt) { }
    /**
     * 原件准备与查验、占用状态分别展示。
     * @author owlzhangfq@gmail.com
     */
    public record Item(UUID id, long version, OriginalMetadata original, Invoice.Verification verification, Invoice.Occupation occupation,
                       Invoice.VerifiedFacts facts, ExpenseUse use, String failureCode, Instant checkedAt) { }
    /**
     * 固定发票身份排序的游标，不代表总数量或上传时间顺序。
     * @author owlzhangfq@gmail.com
     */
    public record Page(List<Item> items, UUID nextBeforeId) { }
    /**
     * 实际容量和类型，不声称已启用内容扫描或发票查验。
     * @author owlzhangfq@gmail.com
     */
    public record Options(boolean enabled, long maxFileBytes, long maxWalletBytes, int maxWalletUploads, List<InvoiceOriginal.Format> formats) { }
    /**
     * 已验证本人权限与完整指纹的下载字节。
     * @author owlzhangfq@gmail.com
     */
    public record Download(String filename, byte[] content) { }
}
