package io.agentflow.attachment;

import io.agentflow.common.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 单机持久文件存储；路径只由服务端 UUID 构造，内容发布后不可覆盖，不提供物理删除业务。
 * @author owlzhangfq@gmail.com
 */
@Component
public class LocalAttachmentStore {
    private static final Logger log = LoggerFactory.getLogger(LocalAttachmentStore.class);
    public static final long MAX_SUPPORTED_BYTES = 100L * 1024 * 1024;
    private static final int COPY_BUFFER_BYTES = 64 * 1024;
    private final Path directory;
    private final long maxFileBytes;
    private final long maxApplicationBytes;
    private final int maxApplicationUploads;

    /** 显式目录未配置时保持关闭，不能默默写入容器临时层。 */
    public LocalAttachmentStore(@Value("${agentflow.attachments.directory:}") String directory,
            @Value("${agentflow.attachments.max-file-bytes:20971520}") long maxFileBytes,
            @Value("${agentflow.attachments.max-application-bytes:268435456}") long maxApplicationBytes,
            @Value("${agentflow.attachments.max-application-uploads:100}") int maxApplicationUploads) {
        if (maxFileBytes < 1 || maxFileBytes > MAX_SUPPORTED_BYTES || maxApplicationBytes < maxFileBytes
                || maxApplicationUploads < 1 || maxApplicationUploads > 10000) {
            throw new IllegalArgumentException("Invalid attachment storage limits");
        }
        this.maxFileBytes = maxFileBytes;
        this.maxApplicationBytes = maxApplicationBytes;
        this.maxApplicationUploads = maxApplicationUploads;
        if (directory.isBlank()) this.directory = null;
        else {
            Path root = Path.of(directory);
            if (!root.isAbsolute()) throw new IllegalArgumentException("Attachment directory must be absolute");
            try {
                Files.createDirectories(root);
                this.directory = root.toRealPath();
                Files.setPosixFilePermissions(this.directory, PosixFilePermissions.fromString("rwx------"));
            } catch (IOException failure) { throw unavailable(); }
        }
    }

    public boolean enabled() { return directory != null; }
    public long maxFileBytes() { return maxFileBytes; }
    public long maxApplicationBytes() { return maxApplicationBytes; }
    public int maxApplicationUploads() { return maxApplicationUploads; }

    /** 实际读取长度受限，不信任 HTTP Content-Length；指纹与登记值不符时不发布内容。 */
    public Path stage(Attachment attachment, InputStream input) {
        requireEnabled();
        Path staged = null;
        try {
            staged = Files.createTempFile(directory, ".upload-", ".part",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            MessageDigest digest = digest();
            long size = 0;
            try (var output = Files.newOutputStream(staged)) {
                byte[] buffer = new byte[COPY_BUFFER_BYTES];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    size += count;
                    if (size > maxFileBytes) throw new DomainException("ATTACHMENT_TOO_LARGE", "Attachment exceeds the configured file limit");
                    if (size > attachment.size()) throw new DomainException("ATTACHMENT_CONTENT_MISMATCH", "Attachment exceeds its registered size");
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
            }
            attachment.verify(size, HexFormat.of().formatHex(digest.digest()));
            // READY 之前同步文件内容，再发布稳定文件名。
            try (var channel = FileChannel.open(staged, StandardOpenOption.WRITE)) { channel.force(true); }
            return staged;
        } catch (IOException | RuntimeException failure) {
            discard(staged);
            if (failure instanceof DomainException domain) throw domain;
            log.error("Attachment staging failed, errorCode={}, attachmentId={}", "ATTACHMENT_STORAGE_UNAVAILABLE", attachment.id(), failure);
            throw unavailable();
        }
    }

    /** 同文件系统硬链接以排他方式发布；重复上传只能验证既有对象，不能覆盖它。 */
    public void publish(Attachment attachment, Path staged) {
        try {
            Files.createLink(path(attachment), staged);
        } catch (FileAlreadyExistsException existing) {
            read(attachment);
        } catch (IOException failure) { throw unavailable(); }
    }

    /** 返回完整性已核对的字节，避免在向客户端写出一部分后才发现文件损坏。 */
    public byte[] read(Attachment attachment) {
        Path path = path(attachment);
        if (attachment.size() < 0 || attachment.size() > MAX_SUPPORTED_BYTES) throw unavailable();
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw unavailable();
            byte[] bytes;
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes((int) attachment.size() + 1);
            }
            attachment.verify(bytes.length, HexFormat.of().formatHex(digest().digest(bytes)));
            return bytes;
        } catch (IOException failure) { throw unavailable(); }
        catch (DomainException failure) {
            throw new DomainException("ATTACHMENT_INTEGRITY_FAILED", "Stored attachment is missing or differs from its fingerprint");
        }
    }

    /** 只清理本次未发布的临时文件，已发布内容没有删除入口。 */
    public void discard(Path staged) {
        if (staged == null) return;
        try { Files.deleteIfExists(staged); }
        catch (IOException failure) {
            // 清理失败不改写已发布文件的成功状态，也不遮蔽原传输错误。
            log.warn("Attachment staging cleanup failed, errorCode={}, stagedFile={}", "ATTACHMENT_STAGING_CLEANUP_FAILED", staged.getFileName(), failure);
        }
    }

    /** 配置检查不暴露主机路径；实际上传和下载仍分别检查 I/O 结果。 */
    public boolean available() { return enabled() && Files.isDirectory(directory) && Files.isReadable(directory) && Files.isWritable(directory); }

    private Path path(Attachment attachment) { requireEnabled(); return directory.resolve(attachment.id() + ".bin"); }
    private void requireEnabled() {
        if (!enabled()) throw new DomainException("ATTACHMENT_STORAGE_UNAVAILABLE", "Attachment storage is not configured");
    }
    private static DomainException unavailable() { return new DomainException("ATTACHMENT_STORAGE_UNAVAILABLE", "Attachment storage is unavailable"); }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }
}
