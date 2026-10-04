package io.agentflow.storage;

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
import java.util.Optional;

/**
 * 审批附件与个人发票共用的不可变文件存储，只处理字节身份；业务归属与授权由各自用例负责。
 * @author owlzhangfq@gmail.com
 */
@Component
public class LocalDocumentStore {
    private static final Logger log = LoggerFactory.getLogger(LocalDocumentStore.class);
    public static final long MAX_SUPPORTED_BYTES = 100L * 1024 * 1024;
    private static final int COPY_BUFFER_BYTES = 64 * 1024;
    private final Path directory;
    private final long maxFileBytes;

    /** 显式目录未配置时保持关闭，不能默默写入容器临时层。 */
    public LocalDocumentStore(@Value("${agentflow.attachments.directory:}") String directory,
            @Value("${agentflow.attachments.max-file-bytes:20971520}") long maxFileBytes) {
        if (maxFileBytes < 1 || maxFileBytes > MAX_SUPPORTED_BYTES) throw new IllegalArgumentException("Invalid document file limit");
        this.maxFileBytes = maxFileBytes;
        if (directory.isBlank()) this.directory = null;
        else {
            Path root = Path.of(directory);
            if (!root.isAbsolute()) throw new IllegalArgumentException("Document directory must be absolute");
            try {
                Files.createDirectories(root);
                this.directory = root.toRealPath();
                Files.setPosixFilePermissions(this.directory, PosixFilePermissions.fromString("rwx------"));
            } catch (IOException failure) { throw unavailable(); }
        }
    }

    public boolean enabled() { return directory != null; }
    public long maxFileBytes() { return maxFileBytes; }

    /** 实际读取长度受限，不信任 HTTP Content-Length；指纹与登记值不符时不发布内容。 */
    public Path stage(Content file, InputStream input) {
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
                    if (size > maxFileBytes) throw new DomainException("FILE_TOO_LARGE", "Document exceeds the configured file limit");
                    if (size > file.size()) throw new DomainException("FILE_CONTENT_MISMATCH", "Document exceeds its registered size");
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
            }
            file.verify(size, HexFormat.of().formatHex(digest.digest()));
            // READY 之前同步文件内容，再发布稳定文件名。
            try (var channel = FileChannel.open(staged, StandardOpenOption.WRITE)) { channel.force(true); }
            return staged;
        } catch (IOException | RuntimeException failure) {
            discard(staged);
            if (failure instanceof DomainException domain) throw domain;
            log.error("Document staging failed, errorCode={}, fileId={}", "FILE_STORAGE_UNAVAILABLE", file.id(), failure);
            throw unavailable();
        }
    }

    /** 同文件系统硬链接以排他方式发布；重复上传只能验证既有对象，不能覆盖它。 */
    public void publish(Content file, Path staged) {
        try {
            Files.createLink(path(file), staged);
        } catch (FileAlreadyExistsException existing) {
            read(file);
        } catch (IOException failure) { throw unavailable(); }
    }

    /** 返回完整性已核对的字节，避免在向客户端写出一部分后才发现文件损坏。 */
    public byte[] read(Content file) {
        Path path = path(file);
        if (file.size() < 0 || file.size() > MAX_SUPPORTED_BYTES) throw unavailable();
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw unavailable();
            byte[] bytes;
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes((int) file.size() + 1);
            }
            file.verify(bytes.length, HexFormat.of().formatHex(digest().digest(bytes)));
            return bytes;
        } catch (IOException failure) { throw unavailable(); }
        catch (DomainException failure) {
            throw new DomainException("FILE_INTEGRITY_FAILED", "Stored attachment is missing or differs from its fingerprint");
        }
    }

    /** 恢复收集只在明确不存在时补取；无法判断、损坏或软链接仍按既有完整性契约拒绝读取。 */
    public Optional<byte[]> readIfPresent(Content file) {
        if (Files.notExists(path(file), LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        return Optional.of(read(file));
    }

    /** 只清理本次未发布的临时文件，已发布内容没有删除入口。 */
    public void discard(Path staged) {
        if (staged == null) return;
        try { Files.deleteIfExists(staged); }
        catch (IOException failure) {
            // 清理失败不改写已发布文件的成功状态，也不遮蔽原传输错误。
            log.warn("Document staging cleanup failed, errorCode={}, stagedFile={}", "FILE_STAGING_CLEANUP_FAILED", staged.getFileName(), failure);
        }
    }

    /** 配置检查不暴露主机路径；实际上传和下载仍分别检查 I/O 结果。 */
    public boolean available() { return enabled() && Files.isDirectory(directory) && Files.isReadable(directory) && Files.isWritable(directory); }

    private Path path(Content file) { requireEnabled(); return directory.resolve(file.id() + ".bin"); }
    private void requireEnabled() {
        if (!enabled()) throw new DomainException("FILE_STORAGE_UNAVAILABLE", "Document storage is not configured");
    }
    private static DomainException unavailable() { return new DomainException("FILE_STORAGE_UNAVAILABLE", "Document storage is unavailable"); }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }

    /**
     * 固定文件名和内容指纹，不包含审批申请、员工或其他业务身份。
     * @author owlzhangfq@gmail.com
     */
    public record Content(java.util.UUID id, long size, String sha256) {
        /** 基础存储只核对字节与预先登记的指纹，不决定业务可用性。 */
        public void verify(long actualSize, String actualDigest) {
            if (actualSize != size || !sha256.equals(actualDigest)) {
                throw new DomainException("FILE_CONTENT_MISMATCH", "Document content differs from its registered fingerprint");
            }
        }
    }
}
