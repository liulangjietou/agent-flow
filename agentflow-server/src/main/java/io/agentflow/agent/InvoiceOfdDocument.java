package io.agentflow.agent;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * OFD 包内的有界 XML、页面和资源引用清单；不负责渲染或发票真实性判断。
 * @author owlzhangfq@gmail.com
 */
final class InvoiceOfdDocument {
    private static final String NAMESPACE = "http://www.ofdspec.org/2016";
    private static final int MAX_XML_BYTES = 2 * 1024 * 1024;
    private static final int MAX_TOTAL_XML_BYTES = 8 * 1024 * 1024;
    private static final int MAX_ELEMENTS = 100_000;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_ATTRIBUTES = 64;
    private static final int MAX_SCALAR_LENGTH = 512;
    private static final int MAX_PAGE_MM = 1000;
    private static final int MAX_TEMPLATES_PER_DOCUMENT = 64;
    private static final int MAX_TEMPLATE_REFERENCES_PER_PAGE = 64;
    private static final long MAX_ID = 0xffff_ffffL;
    private static final Set<String> VERSIONS = Set.of("1.0", "1.1");
    private static final Set<String> FILE_TEXT_REFERENCES = Set.of("DocBody/DocRoot", "DocBody/Signatures",
            "CommonData/PublicRes", "CommonData/DocumentRes", "Page/PageRes", "Document/Annotations", "Document/Attachments",
            "Document/CustomTags", "Document/Extensions", "Font/FontFile", "MultiMedia/MediaFile", "Attachment/FileLoc",
            "CustomTag/FileLoc", "Page/FileLoc", "CustomTag/SchemaLoc", "Extension/ExtendData", "Signature/SignedValue", "Seal/BaseLoc");
    private static final Map<String, String> XML_TEXT_REFERENCES = Map.ofEntries(
            Map.entry("DocRoot", "Document"), Map.entry("Signatures", "Signatures"), Map.entry("PublicRes", "Res"),
            Map.entry("DocumentRes", "Res"), Map.entry("PageRes", "Res"), Map.entry("Annotations", "Annotations"),
            Map.entry("Attachments", "Attachments"), Map.entry("CustomTags", "CustomTags"), Map.entry("Extensions", "Extensions"));
    private static final Map<String, String> FILE_ATTRIBUTES = Map.of("FileRef", "Reference", "FileLoc", "File", "Profile", "ColorSpace");
    private static final Set<String> BASE_LOC_REFERENCES = Set.of("Pages/Page", "CommonData/TemplatePage", "Signatures/Signature", "Versions/Version");
    private InvoiceOfdDocument() { }

    /** 按 DocBody 和 Pages 的原始顺序核对全部页面，不默认只取第一个文档。 */
    static List<Page> inspect(InvoiceOfdArchive archive) throws IOException {
        return read(archive).pages();
    }

    /** 绘制复用同一次预检的 DOM 和预算，不在下游重新放宽 XML 解析设置。 */
    static Contents read(InvoiceOfdArchive archive) throws IOException {
        var xml = new XmlFiles(archive);
        for (String file : archive.files()) {
            if (file.toLowerCase(Locale.ROOT).endsWith(".xml")) xml.read(file);
        }
        Element ofd = root(xml.read("OFD.xml"), "OFD");
        if (!VERSIONS.contains(ofd.getAttribute("Version")) || !ofd.getAttribute("DocType").equals("OFD")) throw invalid();
        var bodies = children(ofd, "DocBody");
        if (bodies.isEmpty() || bodies.size() > InvoiceExtractionInput.MAX_PAGES) throw invalid();
        var pages = new ArrayList<Page>();
        var documents = new HashSet<String>();
        for (Element body : bodies) {
            String documentFile = archive.file("", text(child(body, "DocRoot", true)));
            if (!documents.add(documentFile)) throw invalid();
            Element document = root(xml.read(documentFile), "Document");
            Element common = child(document, "CommonData", true);
            double[] commonBox = box(child(common, "PageArea", false));
            Map<Long, Template> templates = templates(archive, xml, documentFile, common);
            var definitions = children(child(document, "Pages", true), "Page");
            if (definitions.isEmpty() || pages.size() + definitions.size() > InvoiceExtractionInput.MAX_PAGES) throw invalid();
            var ids = new HashSet<>(templates.keySet());
            var files = new HashSet<String>();
            for (Element definition : definitions) {
                long pageId = id(definition.getAttribute("ID"));
                if (!ids.add(pageId)) throw invalid();
                String file = archive.file(parent(documentFile), definition.getAttribute("BaseLoc"));
                if (!files.add(file)) throw invalid();
                Element page = root(xml.read(file), "Page");
                double[] pageBox = box(child(page, "Area", false));
                List<Template> references = templateReferences(page, templates);
                double[] inherited = templateBox(xml, references, pageBox == null);
                double[] area = pageBox != null ? pageBox : inherited != null ? inherited : commonBox;
                if (area == null) throw invalid();
                pages.add(new Page(documentFile, file, pageId, area[0], area[1], area[2], area[3], references));
            }
        }
        // 引用校验可能发现没有 XML 扩展名的资源文件；迭代检查，避免只检查后缀名。
        var checked = new HashSet<String>();
        while (checked.size() < xml.documents.size()) {
            for (String file : List.copyOf(xml.documents.keySet())) {
                if (checked.add(file)) references(archive, xml, file);
            }
        }
        return new Contents(xml, List.copyOf(pages));
    }

    /** 模板目录按所属文档解析；模板页中的 Template 节点无效，不递归展开。 */
    private static Map<Long, Template> templates(InvoiceOfdArchive archive, XmlFiles xml,
                                                String documentFile, Element common) throws IOException {
        var definitions = children(common, "TemplatePage");
        if (definitions.size() > MAX_TEMPLATES_PER_DOCUMENT) throw invalid();
        var result = new HashMap<Long, Template>();
        for (Element definition : definitions) {
            InvoiceOfdXml.shape(definition, Set.of("ID", "Name", "ZOrder", "BaseLoc"), Set.of());
            long id = id(definition.getAttribute("ID"));
            String file = archive.file(parent(documentFile), definition.getAttribute("BaseLoc"));
            var template = new Template(file, order(definition, "Background"));
            if (result.putIfAbsent(id, template) != null) throw invalid();
            Element root = root(xml.read(file), "Page");
            for (Element reference : children(root, "Template")) validateTemplateReference(reference);
        }
        return Map.copyOf(result);
    }

    private static List<Template> templateReferences(Element page, Map<Long, Template> definitions) throws IOException {
        var references = children(page, "Template");
        if (references.size() > MAX_TEMPLATE_REFERENCES_PER_PAGE) throw invalid();
        var result = new ArrayList<Template>();
        for (Element reference : references) {
            Template definition = definitions.get(validateTemplateReference(reference));
            if (definition == null) throw invalid();
            String order = reference.hasAttribute("ZOrder") ? reference.getAttribute("ZOrder") : definition.zOrder();
            result.add(new Template(definition.file(), order));
        }
        return List.copyOf(result);
    }

    private static long validateTemplateReference(Element reference) throws IOException {
        InvoiceOfdXml.shape(reference, Set.of("TemplateID", "ZOrder"), Set.of());
        order(reference, "Background");
        return id(reference.getAttribute("TemplateID"));
    }

    private static String order(Element element, String fallback) throws IOException {
        String value = element.hasAttribute("ZOrder") ? element.getAttribute("ZOrder") : fallback;
        if (!Set.of("Background", "Body", "Foreground").contains(value)) throw invalid();
        return value;
    }

    /** 多个模板提供不同物理区域时，正文页必须显式给出区域，避免任意选择导致裁掉票据。 */
    private static double[] templateBox(XmlFiles xml, List<Template> templates, boolean inherited) throws IOException {
        double[] result = null;
        for (Template template : templates) {
            double[] candidate = box(child(root(xml.read(template.file()), "Page"), "Area", false));
            if (candidate == null) continue;
            if (inherited && result != null) {
                for (int i = 0; i < result.length; i++) if (result[i] != candidate[i]) throw invalid();
            }
            result = candidate;
        }
        return result;
    }

    private static void references(InvoiceOfdArchive archive, XmlFiles xml, String file) throws IOException {
        Element root = xml.read(file).getDocumentElement();
        String base = parent(file);
        if (named(root, "Res") && root.hasAttribute("BaseLoc")) base = archive.directory(base, root.getAttribute("BaseLoc"));
        var pending = new ArrayDeque<Element>(); pending.add(root);
        while (!pending.isEmpty()) {
            Element element = pending.removeFirst();
            if (NAMESPACE.equals(element.getNamespaceURI())) {
                String name = element.getLocalName();
                if (FILE_TEXT_REFERENCES.contains(context(element))) {
                    String target = archive.file(base, text(element));
                    String expected = XML_TEXT_REFERENCES.get(name);
                    if (expected != null) root(xml.read(target), expected);
                    if (name.equals("FileLoc") && element.getParentNode() instanceof Element parent && named(parent, "Page")) {
                        root(xml.read(target), "PageAnnot");
                    }
                }
                if (element.hasAttribute("BaseLoc") && BASE_LOC_REFERENCES.contains(context(element))) {
                    String target = archive.file(base, element.getAttribute("BaseLoc"));
                    if (name.equals("Page") || name.equals("TemplatePage")) root(xml.read(target), "Page");
                    if (name.equals("Signature")) root(xml.read(target), "Signature");
                }
                for (var attribute : FILE_ATTRIBUTES.entrySet()) {
                    if (name.equals(attribute.getValue()) && element.hasAttribute(attribute.getKey())) archive.file(base, element.getAttribute(attribute.getKey()));
                }
            }
            addChildren(element, pending);
        }
    }

    private static String context(Element element) {
        if (!(element.getParentNode() instanceof Element parent) || !NAMESPACE.equals(parent.getNamespaceURI())) return "";
        return parent.getLocalName() + "/" + element.getLocalName();
    }

    private static long id(String value) throws IOException {
        if (!value.matches("\\+?[0-9]{1,10}")) throw invalid();
        long id = Long.parseLong(value);
        if (id == 0 || id > MAX_ID) throw invalid();
        return id;
    }

    private static double[] box(Element area) throws IOException {
        if (area == null) return null;
        Element physical = child(area, "PhysicalBox", false);
        if (physical == null) return null;
        String[] fields = text(physical).split("\\s+");
        if (fields.length != 4) throw invalid();
        double[] values = new double[4];
        try {
            for (int i = 0; i < values.length; i++) {
                values[i] = Double.parseDouble(fields[i]);
                if (!Double.isFinite(values[i])) throw invalid();
            }
        } catch (NumberFormatException malformed) { throw invalid(); }
        if (values[2] <= 0 || values[3] <= 0 || values[2] > MAX_PAGE_MM || values[3] > MAX_PAGE_MM) throw invalid();
        return values;
    }

    private static Element root(Document document, String name) throws IOException {
        Element root = document.getDocumentElement();
        if (!named(root, name)) throw invalid();
        return root;
    }

    private static boolean named(Element element, String name) {
        return NAMESPACE.equals(element.getNamespaceURI()) && name.equals(element.getLocalName());
    }

    private static Element child(Element parent, String name, boolean required) throws IOException {
        var children = children(parent, name);
        if (children.size() > 1 || required && children.isEmpty()) throw invalid();
        return children.isEmpty() ? null : children.get(0);
    }

    private static List<Element> children(Element parent, String name) throws IOException {
        var result = new ArrayList<Element>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getLocalName())) {
                if (!named(element, name)) throw invalid();
                result.add(element);
            }
        }
        return result;
    }

    private static String text(Element element) throws IOException {
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element) throw invalid();
        }
        String value = element.getTextContent().trim();
        if (value.isEmpty() || value.length() > MAX_SCALAR_LENGTH) throw invalid();
        return value;
    }

    private static void addChildren(Element element, ArrayDeque<Element> target) {
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element nested) target.addLast(nested);
        }
    }

    private static String parent(String file) {
        int separator = file.lastIndexOf('/');
        return separator < 0 ? "" : file.substring(0, separator);
    }

    private static IOException invalid() { return new IOException("OFD document is invalid, ambiguous or exceeds inspection limits"); }

    /**
     * 全包 XML 共用字节和节点预算；仅解析内存中的原字节，拒绝所有外部解析请求。
     * @author owlzhangfq@gmail.com
     */
    private static final class XmlFiles {
        private final InvoiceOfdArchive archive;
        private final Map<String, Document> documents = new HashMap<>();
        private int bytes;
        private int elements;

        private XmlFiles(InvoiceOfdArchive archive) { this.archive = archive; }

        private Document read(String file) throws IOException {
            if (documents.containsKey(file)) return documents.get(file);
            int length = archive.size(file);
            bytes += length;
            if (length > MAX_XML_BYTES || bytes > MAX_TOTAL_XML_BYTES) throw invalid();
            var factory = DocumentBuilderFactory.newDefaultInstance();
            factory.setNamespaceAware(true); factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            try (var source = archive.open(file)) {
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                factory.setAttribute("jdk.xml.maxElementDepth", MAX_DEPTH);
                factory.setAttribute("jdk.xml.elementAttributeLimit", MAX_ATTRIBUTES);
                var builder = factory.newDocumentBuilder();
                builder.setEntityResolver((publicId, systemId) -> { throw new SAXException("External XML resolution is disabled"); });
                builder.setErrorHandler(new DefaultHandler() {
                    @Override public void error(SAXParseException error) throws SAXException { throw error; }
                    @Override public void fatalError(SAXParseException error) throws SAXException { throw error; }
                });
                Document document = builder.parse(source);
                var pending = new ArrayDeque<Element>(); pending.add(document.getDocumentElement());
                while (!pending.isEmpty()) {
                    Element element = pending.removeFirst();
                    if (++elements > MAX_ELEMENTS || element.getAttributes().getLength() > MAX_ATTRIBUTES
                            || element.hasAttributeNS(XMLConstants.XML_NS_URI, "base")
                            || "http://www.w3.org/2001/XInclude".equals(element.getNamespaceURI())) throw invalid();
                    addChildren(element, pending);
                }
                documents.put(file, document);
                return document;
            } catch (ParserConfigurationException | SAXException | IllegalArgumentException rejected) { throw invalid(); }
        }
    }

    /**
     * 保留文档内页面标识、包内来源及实际 PhysicalBox；对外页码仍由完整列表的位置确定。
     * @author owlzhangfq@gmail.com
     */
    record Page(String documentFile, String file, long id, double x, double y, double width, double height,
                List<Template> templates) { }

    /**
     * 保留每次引用的实际顺序及覆盖值，同一模板的重复引用不会被合并。
     * @author owlzhangfq@gmail.com
     */
    record Template(String file, String zOrder) { }

    /**
     * 渲染期间持有已预检内容；访问仍受原来的包内路径及 XML 总预算约束。
     * @author owlzhangfq@gmail.com
     */
    static final class Contents {
        private final XmlFiles xml;
        private final List<Page> pages;
        private Contents(XmlFiles xml, List<Page> pages) { this.xml = xml; this.pages = pages; }
        List<Page> pages() { return pages; }
        Element root(String file, String name) throws IOException { return InvoiceOfdDocument.root(xml.read(file), name); }
    }
}
