package io.agentflow.agent;

import io.agentflow.expense.InvoiceVerificationPort;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdmodel.PDPageTree;

/**
 * 独立 JVM 内的 PDF 页树检查入口，不启动 Spring、不渲染、不读取外部附件或执行文档动作。
 * @author owlzhangfq@gmail.com
 */
public final class InvoicePdfWorker {
    static final String INPUT_FILE = "original.pdf";
    static final String RESULT_FILE = "pages.bin";
    static final int RESULT_MAGIC = 0x41465049;
    static final int RESULT_BYTES = 2 * Integer.BYTES;
    private static final int MAX_TREE_DEPTH = 16;
    private static final int MAX_TREE_NODES = 128;
    private static final int MAX_PAGE_POINTS = 3000;

    private InvoicePdfWorker() { }

    /** 固定文件名只位于调用方创建的私有工作目录；成功才写出八字节结果。 */
    public static void main(String[] args) {
        try {
            if (args.length != 0) throw invalid();
            Path input = Path.of(INPUT_FILE);
            long length = Files.size(input);
            if (length < 1 || length > InvoiceVerificationPort.Request.MAX_ORIGINAL_BYTES || Files.isSymbolicLink(input)) throw invalid();
            int count;
            try (var source = new RandomAccessReadBufferedFile(input);
                 var document = new PDFParser(source).parse(false)) {
                if (document.isEncrypted()) throw invalid();
                var root = document.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.PAGES);
                if (!(root instanceof COSDictionary pages) || !COSName.PAGES.equals(pages.getCOSName(COSName.TYPE))) throw invalid();
                count = countPages(pages, null, 1, Collections.newSetFromMap(new IdentityHashMap<>()));
                if (count < 1 || count > InvoiceExtractionInput.MAX_PAGES) throw invalid();
            }
            try (var output = new DataOutputStream(Files.newOutputStream(Path.of(RESULT_FILE), StandardOpenOption.CREATE_NEW))) {
                output.writeInt(RESULT_MAGIC); output.writeInt(count);
            }
        } catch (Exception | LinkageError | StackOverflowError rejected) {
            // 不把解析器异常、原件正文或路径输出给父进程；OOM 由 JVM 退出策略处理。
            System.exit(1);
        }
    }

    private static int countPages(COSDictionary node, COSDictionary parent, int depth, Set<COSDictionary> seen) throws IOException {
        if (depth > MAX_TREE_DEPTH || !seen.add(node) || seen.size() > MAX_TREE_NODES
                || node.getDictionaryObject(COSName.PARENT) != parent) throw invalid();
        if (COSName.PAGE.equals(node.getCOSName(COSName.TYPE))) {
            if (node.containsKey(COSName.KIDS)) throw invalid();
            var inherited = PDPageTree.getInheritableAttribute(node, COSName.MEDIA_BOX);
            if (!(inherited instanceof COSArray box) || box.size() != 4) throw invalid();
            double[] coordinates = new double[4];
            for (int i = 0; i < coordinates.length; i++) {
                if (!(box.getObject(i) instanceof COSNumber number)) throw invalid();
                coordinates[i] = number.floatValue();
                if (!Double.isFinite(coordinates[i])) throw invalid();
            }
            double width = coordinates[2] - coordinates[0], height = coordinates[3] - coordinates[1];
            if (width <= 0 || height <= 0 || width > MAX_PAGE_POINTS || height > MAX_PAGE_POINTS) throw invalid();
            return 1;
        }
        if (!COSName.PAGES.equals(node.getCOSName(COSName.TYPE))
                || !(node.getDictionaryObject(COSName.KIDS) instanceof COSArray kids)
                || kids.size() == 0 || kids.size() > MAX_TREE_NODES) throw invalid();
        int count = 0;
        for (int i = 0; i < kids.size(); i++) {
            if (!(kids.getObject(i) instanceof COSDictionary child)) throw invalid();
            count += countPages(child, node, depth + 1, seen);
            if (count > InvoiceExtractionInput.MAX_PAGES) throw invalid();
        }
        // Count 是文件自报值，必须与实际唯一叶子页一致，不能只用它限制页数。
        if (!(node.getDictionaryObject(COSName.COUNT) instanceof COSInteger declared) || declared.longValue() != count) throw invalid();
        return count;
    }

    private static IOException invalid() { return new IOException("PDF source is invalid or exceeds inspection limits"); }
}
