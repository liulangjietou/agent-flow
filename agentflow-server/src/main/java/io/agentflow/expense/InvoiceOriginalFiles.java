package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.storage.LocalDocumentStore;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipFile;

/**
 * 发票原件复用不可变字节存储；这里只识别容器格式，不解析票面、不执行内容、不宣称真实性。
 * @author owlzhangfq@gmail.com
 */
@Component
public class InvoiceOriginalFiles {
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    private final LocalDocumentStore documents;

    /** 共用目录使数据库和原件能够使用同一个配套恢复点。 */
    public InvoiceOriginalFiles(LocalDocumentStore documents) { this.documents = documents; }

    /** 流式接收后识别声明格式，失败即清理本次暂存，不修改已发布文件。 */
    public Path stage(InvoiceOriginal original, InputStream input) {
        Path staged = documents.stage(content(original), input);
        try { requireFormat(original.format(), staged); return staged; }
        catch (RuntimeException failure) { documents.discard(staged); throw failure; }
    }

    /** 发布只允许首次创建或相同字节的重试。 */
    public void publish(InvoiceOriginal original, Path staged) { documents.publish(content(original), staged); }

    /** 授权和状态必须由业务用例先检查，物理读取再核对完整指纹。 */
    public byte[] read(InvoiceOriginal original) { return documents.read(content(original)); }

    /** 发布内容不提供删除接口，暂存文件可以在传输结束后清理。 */
    public void discard(Path staged) { documents.discard(staged); }
    public boolean available() { return documents.available(); }
    public long maxFileBytes() { return Math.min(documents.maxFileBytes(), InvoiceVerificationPort.Request.MAX_ORIGINAL_BYTES); }

    private static LocalDocumentStore.Content content(InvoiceOriginal value) { return new LocalDocumentStore.Content(value.id(), value.size(), value.sha256()); }
    private void requireFormat(InvoiceOriginal.Format format, Path staged) {
        try {
            byte[] header;
            try (var input = Files.newInputStream(staged)) { header = input.readNBytes(PNG_SIGNATURE.length); }
            boolean matches = switch (format) {
                case PDF -> header.length >= 5 && "%PDF-".equals(new String(header, 0, 5, StandardCharsets.US_ASCII));
                case PNG -> Arrays.equals(header, PNG_SIGNATURE);
                case JPEG -> header.length >= 3 && header[0] == (byte) 0xff && header[1] == (byte) 0xd8 && header[2] == (byte) 0xff;
                case OFD -> isOfdContainer(staged);
            };
            if (!matches) throw invalid();
        } catch (IOException malformed) { throw invalid(); }
    }

    private boolean isOfdContainer(Path staged) throws IOException {
        // 只看 ZIP 中央目录中的 OFD 主描述文件；不解压、不读取外部实体或容器内路径。
        try (var archive = new ZipFile(staged.toFile())) {
            var descriptor = archive.getEntry("OFD.xml");
            return descriptor != null && !descriptor.isDirectory() && descriptor.getSize() > 0;
        }
    }
    private static DomainException invalid() { return new DomainException("INVOICE_ORIGINAL_FORMAT_MISMATCH", "Original content does not match its declared invoice format"); }
}
