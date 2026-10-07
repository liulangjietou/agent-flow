package io.agentflow.agent;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.DOMException;
import org.w3c.dom.Element;
import static io.agentflow.agent.InvoiceOfdXml.*;

/**
 * 从签名索引取得所有静态印章及原页归属；不执行验证服务或签名内链接。
 * @author owlzhangfq@gmail.com
 */
final class InvoiceOfdSignatures {
    private static final int MAX_SIGNATURES = 128;
    private static final int MAX_STAMPS = 1024;
    private static final int MAX_IDENTIFIER_LENGTH = 256;
    private InvoiceOfdSignatures() { }

    /** 无外观签名保留正文；有外观却无法完整取得图像时拒绝整个来源。 */
    static Map<InvoiceOfdDocument.Page, List<Stamp>> read(InvoiceOfdArchive archive,
                                                        InvoiceOfdDocument.Contents contents) throws IOException {
        var result = new HashMap<InvoiceOfdDocument.Page, List<Stamp>>();
        int count = 0, stamps = 0;
        for (Element body : children(contents.root("OFD.xml", "OFD"))) {
            Element location = child(body, "Signatures", false);
            if (location == null) continue;
            shape(location, Set.of(), Set.of());
            String document = archive.file("", text(child(body, "DocRoot", true)).trim());
            var pages = new HashMap<Long, InvoiceOfdDocument.Page>();
            for (var page : contents.pages()) if (page.documentFile().equals(document)) pages.put(page.id(), page);
            String indexFile = archive.file("", text(location).trim());
            Element index = contents.root(indexFile, "Signatures");
            shape(index, Set.of(), Set.of("MaxSignId", "Signature"));
            Element maximum = child(index, "MaxSignId", false);
            if (maximum != null) { shape(maximum, Set.of(), Set.of()); identifier(maximum, text(maximum)); }
            var identifiers = new HashSet<String>();
            var stampIds = new HashSet<String>();
            var signatureFiles = new HashSet<String>();
            for (Element definition : children(index, "Signature")) {
                shape(definition, Set.of("ID", "Type", "BaseLoc"), Set.of());
                if (++count > MAX_SIGNATURES || !identifiers.add(identifier(definition, required(definition, "ID")))) throw invalid();
                if (definition.hasAttribute("Type") && !Set.of("Seal", "Sign").contains(definition.getAttribute("Type"))) throw invalid();
                String file = archive.file(parent(indexFile), required(definition, "BaseLoc"));
                if (!signatureFiles.add(file)) throw invalid();
                Element signature = contents.root(file, "Signature");
                shape(signature, Set.of(), Set.of("SignedInfo", "SignedValue"));
                Element info = child(signature, "SignedInfo", true);
                shape(info, Set.of(), Set.of("Provider", "SignatureMethod", "SignatureDateTime", "References", "StampAnnot", "Seal"));
                Element value = child(signature, "SignedValue", true);
                shape(value, Set.of(), Set.of());
                String valueFile = archive.file(parent(file), text(value).trim());
                List<Element> appearances = children(info, "StampAnnot");
                if (appearances.isEmpty()) continue;
                Element seal = child(info, "Seal", false);
                String imageFile = valueFile;
                if (seal != null) {
                    shape(seal, Set.of(), Set.of("BaseLoc"));
                    Element base = child(seal, "BaseLoc", true);
                    shape(base, Set.of(), Set.of());
                    imageFile = archive.file(parent(file), text(base).trim());
                }
                InvoiceOfdSeal.Picture picture;
                try (var stream = archive.open(imageFile)) { picture = InvoiceOfdSeal.read(stream.readAllBytes(), seal == null); }
                for (Element appearance : appearances) {
                    shape(appearance, Set.of("ID", "PageRef", "Boundary", "Clip"), Set.of());
                    if (++stamps > MAX_STAMPS || !stampIds.add(identifier(appearance, required(appearance, "ID")))) throw invalid();
                    var page = pages.get(id(appearance, "PageRef"));
                    if (page == null) throw invalid();
                    var boundary = box(required(appearance, "Boundary"));
                    var clip = appearance.hasAttribute("Clip") ? box(appearance.getAttribute("Clip")) : null;
                    result.computeIfAbsent(page, ignored -> new ArrayList<>()).add(new Stamp(picture, boundary, clip));
                }
            }
        }
        result.replaceAll((page, values) -> List.copyOf(values));
        return Map.copyOf(result);
    }

    /** 签名使用 xs:ID 而非数字 ST_ID；DOM 校验 XML 名称，不挂入文档，兼容已有数字标识。 */
    private static String identifier(Element element, String raw) throws IOException {
        String value = raw.trim();
        if (value.isEmpty() || value.length() > MAX_IDENTIFIER_LENGTH || value.indexOf(':') >= 0) throw invalid();
        if (value.matches("\\+?[0-9]+")) return Long.toString(integer(value, 0, 0xffff_ffffL));
        try { element.getOwnerDocument().createElement(value); }
        catch (DOMException rejected) { throw invalid(); }
        return value;
    }

    private static Rectangle2D.Double box(String value) throws IOException {
        double[] fields = numbers(value, 4);
        if (fields[2] < 0 || fields[3] < 0) throw invalid();
        return new Rectangle2D.Double(fields[0], fields[1], fields[2], fields[3]);
    }

    /**
     * Clip 坐标相对印章 Boundary 左上角，图片字节仍为原始内容。
     * @author owlzhangfq@gmail.com
     */
    record Stamp(InvoiceOfdSeal.Picture picture, Rectangle2D.Double boundary, Rectangle2D.Double clip) { }
}
