package io.agentflow.agent;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * 在独立进程检查 PDF 页数，父服务只读取固定长度结果，不加载原件对象。
 * @author owlzhangfq@gmail.com
 */
final class InvoicePdfInspector {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration DEFAULT_RENDER_TIMEOUT = Duration.ofSeconds(30);
    private static final InvoiceDocumentProcess.Program PROGRAM = new InvoiceDocumentProcess.Program(InvoicePdfWorker.class, InvoicePdfWorker.INPUT_FILE, 256);
    private static final InvoiceDocumentProcess.Program RENDER_PROGRAM = new InvoiceDocumentProcess.Program(InvoicePdfWorker.class, InvoicePdfWorker.INPUT_FILE, 512);
    private final InvoiceDocumentProcess process;
    private final InvoiceDocumentProcess renderProcess;

    InvoicePdfInspector() { this(Path.of(System.getProperty("java.io.tmpdir")), DEFAULT_TIMEOUT, DEFAULT_RENDER_TIMEOUT); }
    InvoicePdfInspector(Path temporaryRoot, Duration timeout) { this(temporaryRoot, timeout, timeout); }
    InvoicePdfInspector(Path temporaryRoot, Duration timeout, Duration renderTimeout) {
        process = new InvoiceDocumentProcess(temporaryRoot, timeout);
        renderProcess = new InvoiceDocumentProcess(temporaryRoot, renderTimeout);
    }

    /** 完整检查成功才返回真实页数，PDF 的原有输入和八字节结果协议保持。 */
    int pageCount(byte[] bytes) { return process.execute(PROGRAM, bytes, List.of(), InvoicePdfInspector::read); }

    /** 在独立 JVM 中将已校验的 PDF 逐页渲染为有序 PNG，适配仅支持 image_url 的视觉模型网关。 */
    List<byte[]> render(byte[] bytes) {
        return renderProcess.execute(RENDER_PROGRAM, bytes, List.of(InvoicePdfWorker.RENDER_FLAG),
                directory -> InvoiceOfdResult.read(directory.resolve(InvoiceOfdResult.FILE)));
    }

    private static int read(Path directory) throws IOException {
        Path result = directory.resolve(InvoicePdfWorker.RESULT_FILE);
        if (!Files.isRegularFile(result, LinkOption.NOFOLLOW_LINKS) || Files.size(result) != InvoicePdfWorker.RESULT_BYTES) throw invalid();
        try (var input = new DataInputStream(Files.newInputStream(result, LinkOption.NOFOLLOW_LINKS))) {
            if (input.readInt() != InvoicePdfWorker.RESULT_MAGIC) throw invalid();
            int count = input.readInt();
            if (count < 1 || count > InvoiceExtractionInput.MAX_PAGES || input.read() != -1) throw invalid();
            return count;
        }
    }

    private static IOException invalid() { return new IOException("PDF worker result is invalid"); }
}
