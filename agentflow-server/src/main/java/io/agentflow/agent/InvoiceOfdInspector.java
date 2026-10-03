package io.agentflow.agent;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * 将 OFD 原件交给独立 JVM；父服务不解压 ZIP、不解释字体或解码图像。
 * @author owlzhangfq@gmail.com
 */
final class InvoiceOfdInspector {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final InvoiceDocumentProcess.Program PROGRAM = new InvoiceDocumentProcess.Program(InvoiceOfdWorker.class, InvoiceOfdWorker.INPUT_FILE, 512);
    private final InvoiceDocumentProcess process;

    InvoiceOfdInspector() { this(Path.of(System.getProperty("java.io.tmpdir")), DEFAULT_TIMEOUT); }
    InvoiceOfdInspector(Path temporaryRoot, Duration timeout) { process = new InvoiceDocumentProcess(temporaryRoot, timeout); }

    /** 字体清单仅来自可信部署方；返回全部有序 PNG，失败时不保留可外发的部分页面。 */
    List<byte[]> render(byte[] original, Path fontCatalog) {
        return process.execute(PROGRAM, original, fontCatalog == null ? List.of() : List.of(fontCatalog.toString()),
                directory -> InvoiceOfdResult.read(directory.resolve(InvoiceOfdResult.FILE)));
    }
}
