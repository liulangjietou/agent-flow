package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.storage.LocalDocumentStore;
import org.springframework.stereotype.Component;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
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
    private static final int MAX_XML_DEPTH = 64;
    private static final int MAX_XML_ELEMENTS = 100_000;
    private static final int MAX_XML_ATTRIBUTES = 64;
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
                case XML -> isXmlDocument(staged);
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
    private boolean isXmlDocument(Path staged) throws IOException {
        // 直接读取原字节，保留声明编码、BOM、签名和摘要；不构建 DOM、不解析票面或执行引用。
        var factory = SAXParserFactory.newDefaultInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        try (var input = Files.newInputStream(staged)) {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            var reader = factory.newSAXParser().getXMLReader();
            reader.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            reader.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            // 将命名空间声明计入每个元素的属性限制，避免只限制普通属性。
            reader.setFeature("http://xml.org/sax/features/namespace-prefixes", true);
            var handler = new XmlStructureLimits();
            reader.setContentHandler(handler);
            reader.setErrorHandler(handler);
            reader.setEntityResolver((publicId, systemId) -> { throw new SAXException("External XML resolution is disabled"); });
            reader.parse(new InputSource(input));
            return true;
        } catch (SAXException malformed) {
            // 不向响应或日志泄露原文、系统路径和解析器上下文。
            return false;
        } catch (ParserConfigurationException unavailable) {
            throw new IllegalStateException("Secure XML parser is unavailable", unavailable);
        }
    }

    /**
     * 流式结构上限只识别 XML 容器，是否为真实有效发票仍由查验端口判断。
     * @author owlzhangfq@gmail.com
     */
    private static final class XmlStructureLimits extends DefaultHandler {
        private int depth;
        private int elements;

        @Override public void startElement(String uri, String localName, String qualifiedName, Attributes attributes) throws SAXException {
            if (++depth > MAX_XML_DEPTH || ++elements > MAX_XML_ELEMENTS || attributes.getLength() > MAX_XML_ATTRIBUTES) {
                throw new SAXException("Invoice XML structure exceeds limits");
            }
        }
        @Override public void endElement(String uri, String localName, String qualifiedName) { depth--; }
        @Override public void error(SAXParseException failure) throws SAXException { throw failure; }
        @Override public void fatalError(SAXParseException failure) throws SAXException { throw failure; }
    }
    private static DomainException invalid() { return new DomainException("INVOICE_ORIGINAL_FORMAT_MISMATCH", "Original content does not match its declared invoice format"); }
}
