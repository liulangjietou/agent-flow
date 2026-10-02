package io.agentflow.agent;

import io.agentflow.common.DomainException;
import io.agentflow.expense.InvoiceOriginal;
import io.agentflow.expense.InvoiceOriginalFiles;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 在事务外优先读取已支持的 XML 结构，其他内容准备模型输入；文件存储核对完整性。
 * @author owlzhangfq@gmail.com
 */
@Component
public class InvoiceExtractionSources {
    private static final long MAX_IMAGE_PIXELS = 20_000_000;
    private static final int MAX_IMAGE_SIDE = 12_000;
    private static final List<InvoiceOriginal.Format> SUPPORTED = List.of(
            InvoiceOriginal.Format.PNG, InvoiceOriginal.Format.JPEG, InvoiceOriginal.Format.XML);
    private final InvoiceOriginalFiles files;

    /** 保留原有不可变文件读取和摘要校验，不额外复制文件到业务记录。 */
    public InvoiceExtractionSources(InvoiceOriginalFiles files) { this.files = files; }

    /** 只公布已经实现的内容适配器；PDF 和 OFD 在适配完成前不接受外发。 */
    public List<InvoiceOriginal.Format> supportedFormats() { return SUPPORTED; }

    /** 完整原件一次性限额，超限或不可读直接失败，不能静默丢弃内容后发送。 */
    public Prepared prepare(InvoiceOriginal original) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Invoice source preparation must run outside a transaction");
        }
        original.requireReady();
        if (!SUPPORTED.contains(original.format())) throw new DomainException("INVOICE_EXTRACTION_FORMAT_UNSUPPORTED", "Invoice extraction format is not supported");
        byte[] bytes = files.read(original);
        var input = new InvoiceExtractionInput(original.invoiceId(), original.id(), original.sha256(), original.format(), original.size(), 1);
        if (original.format() == InvoiceOriginal.Format.XML) {
            var xml = InvoiceExtractionXml.read(input, bytes);
            if (xml.structured() != null) return new Prepared(input, List.of(), null, xml.structured());
            return new Prepared(input, List.of(Map.of("type", "text", "text", xml.modelText())), xml.modelText(), null);
        }
        requireImage(original.format(), bytes);
        String url = "data:" + original.format().mediaType() + ";base64," + Base64.getEncoder().encodeToString(bytes);
        return new Prepared(input, List.of(Map.of("type", "image_url", "image_url", Map.of("url", url, "detail", "high"))), null, null);
    }

    private static void requireImage(InvoiceOriginal.Format format, byte[] bytes) {
        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw invalidSource();
            var reader = readers.next();
            try {
                if (!reader.getFormatName().equalsIgnoreCase(format.name())) throw invalidSource();
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_IMAGE_SIDE || height > MAX_IMAGE_SIDE
                        || (long) width * height > MAX_IMAGE_PIXELS) throw invalidSource();
                var image = reader.read(0);
                if (image == null) throw invalidSource();
                image.flush();
            } finally { reader.dispose(); }
        } catch (IOException | IllegalArgumentException malformed) { throw invalidSource(); }
    }

    private static DomainException invalidSource() {
        return new DomainException("INVOICE_EXTRACTION_SOURCE_UNAVAILABLE", "Invoice original is unreadable or exceeds extraction limits");
    }

    /**
     * 模型输入只在当前调用内存中存在；持久层只保存 input 中的完整原件引用。
     * @author owlzhangfq@gmail.com
     */
    public static final class Prepared {
        private final InvoiceExtractionInput input;
        private final List<Map<String, Object>> parts;
        private final String text;
        private final InvoiceExtractionSuggestion structured;
        private Prepared(InvoiceExtractionInput input, List<Map<String, Object>> parts, String text, InvoiceExtractionSuggestion structured) {
            this.input = input; this.parts = List.copyOf(parts); this.text = text; this.structured = structured;
        }
        public InvoiceExtractionInput input() { return input; }
        /** 创建运行时固定实际执行方式；已解析 XML 不需要模型配置或外发授权。 */
        public InvoiceExtractionSuggestion.Method method() {
            return structured == null ? InvoiceExtractionSuggestion.Method.MODEL : InvoiceExtractionSuggestion.Method.STRUCTURED_XML;
        }
        InvoiceExtractionSuggestion structured() { return structured; }
        List<Map<String, Object>> parts() { return parts; }

        /** XML 摘录还需存在于实际发送文本，图片摘录仍须本人对照原件。 */
        void requireQuote(String quote) {
            if (text != null && !text.contains(quote)) throw new DomainException("INVALID_AGENT_OUTPUT", "Invoice quote is absent from the source text");
        }
    }

}
