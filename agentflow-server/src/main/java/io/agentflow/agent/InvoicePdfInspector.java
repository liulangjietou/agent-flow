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
    private static final InvoiceDocumentProcess.Program PROGRAM = new InvoiceDocumentProcess.Program(InvoicePdfWorker.class, InvoicePdfWorker.INPUT_FILE, 256);
    private final InvoiceDocumentProcess process;

    InvoicePdfInspector() { this(Path.of(System.getProperty("java.io.tmpdir")), DEFAULT_TIMEOUT); }
    InvoicePdfInspector(Path temporaryRoot, Duration timeout) { process = new InvoiceDocumentProcess(temporaryRoot, timeout); }

    /** 完整检查成功才返回真实页数，PDF 的原有输入和八字节结果协议保持。 */
    int pageCount(byte[] bytes) { return process.execute(PROGRAM, bytes, List.of(), InvoicePdfInspector::read); }

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
