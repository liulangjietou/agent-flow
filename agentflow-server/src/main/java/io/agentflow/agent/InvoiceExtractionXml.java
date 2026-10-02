package io.agentflow.agent;

import io.agentflow.common.DomainException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;
import static io.agentflow.agent.InvoiceExtractionSuggestion.Field.*;

/**
 * 本地识别已核对的 EInvoice 0.31 无命名空间原件；其他 XML 只准备待授权的模型文本。
 * @author owlzhangfq@gmail.com
 */
final class InvoiceExtractionXml {
    static final String PROCESSOR_VERSION = "einvoice-0.31-v1";
    private static final String ROOT = "/EInvoice";
    private static final String VERSION_PATH = ROOT + "/Header/Version";
    private static final String DOCUMENT_VERSION = "0.31";
    private static final int MAX_XML_TEXT_BYTES = 64 * 1024;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_ELEMENTS = 100_000;
    private static final int MAX_ATTRIBUTES = 64;
    private static final int MAX_PATH_LENGTH = 2048;
    private static final Map<String, InvoiceExtractionSuggestion.Field> FIELDS = Map.ofEntries(
            Map.entry(ROOT + "/TaxSupervisionInfo/InvoiceNumber", INVOICE_NUMBER),
            Map.entry(ROOT + "/TaxSupervisionInfo/IssueTime", ISSUE_DATE),
            Map.entry(ROOT + "/EInvoiceData/SellerInformation/SellerName", SELLER_NAME),
            Map.entry(ROOT + "/EInvoiceData/SellerInformation/SellerIdNum", SELLER_TAX_ID),
            Map.entry(ROOT + "/EInvoiceData/BuyerInformation/BuyerName", BUYER_NAME),
            Map.entry(ROOT + "/EInvoiceData/BuyerInformation/BuyerIdNum", BUYER_TAX_ID),
            Map.entry(ROOT + "/EInvoiceData/BasicInformation/TotalAmWithoutTax", NET_AMOUNT),
            Map.entry(ROOT + "/EInvoiceData/BasicInformation/TotalTaxAm", TAX_AMOUNT),
            Map.entry(ROOT + "/EInvoiceData/BasicInformation/TotalTax-includedAmount", GROSS_AMOUNT));
    private static final Set<String> SINGLE_CONTAINERS = Set.of(ROOT + "/Header", ROOT + "/TaxSupervisionInfo",
            ROOT + "/EInvoiceData", ROOT + "/EInvoiceData/SellerInformation", ROOT + "/EInvoiceData/BuyerInformation",
            ROOT + "/EInvoiceData/BasicInformation");

    private InvoiceExtractionXml() { }

    /** 只读一次完整 XML，不下载 schema、签名引用或任何外部资源。 */
    static Content read(InvoiceExtractionInput source, byte[] bytes) {
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
            var handler = new ContentReader();
            reader.setContentHandler(handler); reader.setErrorHandler(handler);
            reader.setEntityResolver((publicId, systemId) -> { throw new SAXException("External XML resolution is disabled"); });
            reader.parse(new InputSource(input));
            return handler.result(source);
        } catch (IOException | SAXException malformed) { throw invalidSource(); }
        catch (ParserConfigurationException unavailable) { throw new IllegalStateException("Secure XML parser is unavailable", unavailable); }
    }

    private static DomainException invalidSource() {
        return new DomainException("INVOICE_EXTRACTION_SOURCE_UNAVAILABLE", "Invoice XML is unreadable, ambiguous or exceeds extraction limits");
    }

    /**
     * 两种结果互斥，已识别结构不准备模型正文；未识别结构仍需要上层单独授权外发。
     * @author owlzhangfq@gmail.com
     */
    record Content(InvoiceExtractionSuggestion structured, String modelText) { }

    /**
     * 按完整路径和命名空间识别字段，不搜索任意同名后代，不合并重复发票或重复分组。
     * @author owlzhangfq@gmail.com
     */
    private static final class ContentReader extends DefaultHandler {
        private final ArrayDeque<Element> stack = new ArrayDeque<>();
        private final Map<String, String> values = new HashMap<>();
        private final Set<String> seen = new HashSet<>();
        private final StringBuilder modelText = new StringBuilder();
        private boolean modelTextExceeded;
        private boolean supportedRoot;
        private int elements;

        @Override public void startElement(String uri, String localName, String name, Attributes attributes) throws SAXException {
            if (stack.size() >= MAX_DEPTH || ++elements > MAX_ELEMENTS || attributes.getLength() > MAX_ATTRIBUTES) throw limit();
            Element parent = stack.peek();
            String path = (parent == null ? "" : parent.path) + "/" + localName;
            boolean noNamespace = uri.isEmpty() && (parent == null || parent.noNamespace);
            if (path.length() > MAX_PATH_LENGTH) throw limit();
            if (parent == null) supportedRoot = ROOT.equals(path) && noNamespace;
            boolean scalar = supportedRoot && (VERSION_PATH.equals(path) || FIELDS.containsKey(path));
            boolean known = scalar || supportedRoot && SINGLE_CONTAINERS.contains(path);
            if (parent != null && parent.value != null || known && (!noNamespace || !seen.add(path))) throw limit();
            if (scalar) for (int i = 0; i < attributes.getLength(); i++) {
                if (!"xmlns".equals(attributes.getQName(i)) && !attributes.getQName(i).startsWith("xmlns:")) throw limit();
            }
            stack.push(new Element(path, noNamespace, scalar ? new StringBuilder() : null));
            appendModel("<" + name);
            for (int i = 0; i < attributes.getLength(); i++) appendModel(" " + attributes.getQName(i) + "=\"" + attributes.getValue(i) + "\"");
            appendModel(">");
        }

        @Override public void endElement(String uri, String localName, String name) {
            var element = stack.pop();
            if (element.value != null) values.put(element.path, element.value.toString().strip());
            appendModel("</" + name + ">");
        }

        @Override public void characters(char[] text, int start, int length) throws SAXException {
            var current = stack.peek();
            if (current != null && current.value != null) {
                if (length > InvoiceExtractionSuggestion.MAX_QUOTE_LENGTH - current.value.length()) throw limit();
                current.value.append(text, start, length);
            }
            appendModel(new String(text, start, length));
        }

        private void appendModel(String value) {
            // 先限制内存中的字符量，最终按完整 UTF-8 编码计数，不依赖 SAX 对代理字符对的分段方式。
            if (modelTextExceeded) return;
            if (value.length() > MAX_XML_TEXT_BYTES - modelText.length()) {
                modelTextExceeded = true; modelText.setLength(0); return;
            }
            modelText.append(value);
        }

        private Content result(InvoiceExtractionInput source) {
            if (supportedRoot && DOCUMENT_VERSION.equals(values.get(VERSION_PATH))) {
                var proposals = new ArrayList<InvoiceExtractionSuggestion.Proposal>();
                // 稳定按领域字段顺序输出；币种和发票代码没有经核对的映射，不从国别或流水号推测。
                for (var mapping : FIELDS.entrySet().stream().sorted(Map.Entry.comparingByValue()).toList()) {
                    String value = values.get(mapping.getKey());
                    if (value == null || value.isEmpty()) continue;
                    try {
                        var evidence = new InvoiceExtractionSuggestion.Evidence(source.originalId(), source.originalDigest(), 1,
                                value, mapping.getKey().replaceAll("([^/]+)", "$1[1]"));
                        proposals.add(new InvoiceExtractionSuggestion.Proposal(mapping.getValue(), value,
                                InvoiceExtractionSuggestion.Confidence.HIGH, List.of(evidence)));
                    } catch (DomainException malformed) { throw invalidSource(); }
                }
                var suggestion = new InvoiceExtractionSuggestion(InvoiceExtractionSuggestion.Method.STRUCTURED_XML,
                        "local-xml", PROCESSOR_VERSION, InvoiceExtractionRun.CONTRACT_VERSION, proposals);
                suggestion.requireMatches(source);
                return new Content(suggestion, null);
            }
            if (modelTextExceeded) throw invalidSource();
            String text = modelText.toString();
            if (text.getBytes(StandardCharsets.UTF_8).length > MAX_XML_TEXT_BYTES) throw invalidSource();
            return new Content(null, text);
        }

        @Override public void error(SAXParseException failure) throws SAXException { throw failure; }
        @Override public void fatalError(SAXParseException failure) throws SAXException { throw failure; }
        private static SAXException limit() { return new SAXException("Invoice XML structure is ambiguous or exceeds limits"); }
    }

    /**
     * 只为白名单标量暂存正文，其余节点仅跟踪路径，不构建整棵 DOM。
     * @author owlzhangfq@gmail.com
     */
    private record Element(String path, boolean noNamespace, StringBuilder value) { }
}
