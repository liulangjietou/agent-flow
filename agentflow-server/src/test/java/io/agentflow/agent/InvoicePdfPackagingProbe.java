package io.agentflow.agent;

import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

/**
 * 从实际可执行 jar 加载父检查器，再由它启动同一安装包内的子进程，验证嵌套类路径。
 * @author owlzhangfq@gmail.com
 */
public final class InvoicePdfPackagingProbe {
    private InvoicePdfPackagingProbe() { }

    /** 只生成合成 PDF；由交付验证命令显式调用，不作为生产入口打包。 */
    public static void main(String[] args) throws Exception {
        byte[] bytes;
        try (var document = new PDDocument()) {
            for (int i = 0; i < 3; i++) document.addPage(new PDPage());
            var output = new ByteArrayOutputStream(); document.save(output); bytes = output.toByteArray();
        }
        int count = new InvoicePdfInspector().pageCount(bytes);
        if (count != 3) throw new IllegalStateException("PDF packaged probe returned the wrong page count");
        System.out.println("PDF_PACKAGED_PROBE_OK pages=" + count);
    }
}
