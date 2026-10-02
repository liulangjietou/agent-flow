package io.agentflow.agent;

import io.agentflow.common.DomainException;
import io.agentflow.expense.InvoiceOriginal;
import io.agentflow.expense.InvoiceOriginalFiles;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * 在事务外准备模型输入；调用方先确认本人归属，文件存储核对原件完整性。
 * @author owlzhangfq@gmail.com
 */
@Component
public class InvoiceExtractionSources {
    private static final long MAX_IMAGE_PIXELS = 20_000_000;
    private static final int MAX_IMAGE_SIDE = 12_000;
    private static final int MAX_XML_TEXT_BYTES = 64 * 1024;
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
            String text = xmlText(bytes);
            return new Prepared(input, List.of(Map.of("type", "text", "text", text)), text);
        }
        requireImage(original.format(), bytes);
        String url = "data:" + original.format().mediaType() + ";base64," + Base64.getEncoder().encodeToString(bytes);
        return new Prepared(input, List.of(Map.of("type", "image_url", "image_url", Map.of("url", url, "detail", "high"))), null);
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

    private static String xmlText(byte[] bytes) {
        var factory = SAXParserFactory.newDefaultInstance();
        factory.setNamespaceAware(true); factory.setXIncludeAware(false);
        try (var input = new ByteArrayInputStream(bytes)) {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            var reader = factory.newSAXParser().getXMLReader();
            reader.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            reader.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            reader.setFeature("http://xml.org/sax/features/namespace-prefixes", true);
            var handler = new XmlText();
            reader.setContentHandler(handler); reader.setErrorHandler(handler);
            reader.setEntityResolver((publicId, systemId) -> { throw new SAXException("External XML resolution is disabled"); });
            reader.parse(new InputSource(input));
            return handler.text.toString();
        } catch (IOException | SAXException malformed) { throw invalidSource(); }
        catch (ParserConfigurationException unavailable) { throw new IllegalStateException("Secure XML parser is unavailable", unavailable); }
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
        private Prepared(InvoiceExtractionInput input, List<Map<String, Object>> parts, String text) {
            this.input = input; this.parts = List.copyOf(parts); this.text = text;
        }
        public InvoiceExtractionInput input() { return input; }
        List<Map<String, Object>> parts() { return parts; }

        /** XML 摘录还需存在于实际发送文本，图片摘录仍须本人对照原件。 */
        void requireQuote(String quote) {
            if (text != null && !text.contains(quote)) throw new DomainException("INVALID_AGENT_OUTPUT", "Invoice quote is absent from the source text");
        }
    }

    /**
     * 保留正文、元素名与属性，以实际 UTF-8 总量限额，不解析业务含义或验证数字签名。
     * @author owlzhangfq@gmail.com
     */
    private static final class XmlText extends DefaultHandler {
        private final StringBuilder text = new StringBuilder();
        private int bytes;
        private void append(String value) throws SAXException {
            int length = value.getBytes(StandardCharsets.UTF_8).length;
            if (length > MAX_XML_TEXT_BYTES - bytes) throw new SAXException("Invoice XML text exceeds the input limit");
            bytes += length; text.append(value);
        }
        @Override public void startElement(String uri, String localName, String name, Attributes attributes) throws SAXException {
            append("<" + name);
            for (int i = 0; i < attributes.getLength(); i++) append(" " + attributes.getQName(i) + "=\"" + attributes.getValue(i) + "\"");
            append(">");
        }
        @Override public void endElement(String uri, String localName, String name) throws SAXException { append("</" + name + ">"); }
        @Override public void characters(char[] value, int start, int length) throws SAXException { append(new String(value, start, length)); }
        @Override public void error(SAXParseException failure) throws SAXException { throw failure; }
        @Override public void fatalError(SAXParseException failure) throws SAXException { throw failure; }
    }
}
