package io.agentflow.agent;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.DatatypeFactory;
import org.w3c.dom.Element;
import static io.agentflow.agent.InvoiceOfdXml.*;

/**
 * 将文档内的分页批注绑定到实际页面；只提供静态外观，不执行交互参数或动作。
 * @author owlzhangfq@gmail.com
 */
final class InvoiceOfdAnnotations {
    private static final int MAX_ANNOTATIONS = 1024;
    private static final int MAX_DATE_LENGTH = 64;
    private static final Set<String> TYPES = Set.of("Link", "Path", "Highlight", "Stamp", "Watermark");
    private static final Set<String> ATTRIBUTES = Set.of("ID", "Type", "Creator", "LastModDate", "Subtype",
            "Visible", "Print", "NoZoom", "NoRotate", "ReadOnly");
    private InvoiceOfdAnnotations() { }

    /** 先核对全部索引、标识与静态外观，未知页面及重复映射不能被静默遗漏或合并。 */
    static Map<InvoiceOfdDocument.Page, List<Appearance>> read(InvoiceOfdArchive archive,
                                                            InvoiceOfdDocument.Contents contents) throws IOException {
        var result = new HashMap<InvoiceOfdDocument.Page, List<Appearance>>();
        var documents = new HashSet<String>();
        int count = 0;
        for (var page : contents.pages()) {
            if (!documents.add(page.documentFile())) continue;
            Element location = child(contents.root(page.documentFile(), "Document"), "Annotations", false);
            if (location == null) continue;
            shape(location, Set.of(), Set.of());
            String indexFile = archive.file(parent(page.documentFile()), text(location).trim());
            Element index = contents.root(indexFile, "Annotations");
            shape(index, Set.of(), Set.of("Page"));
            var pages = new HashMap<Long, InvoiceOfdDocument.Page>();
            for (var candidate : contents.pages()) if (candidate.documentFile().equals(page.documentFile())) pages.put(candidate.id(), candidate);
            var identifiers = new HashSet<Long>();
            for (Element mapping : children(index)) {
                shape(mapping, Set.of("PageID"), Set.of("FileLoc"));
                var target = pages.get(id(mapping, "PageID"));
                if (target == null || result.containsKey(target)) throw invalid();
                Element fileLocation = child(mapping, "FileLoc", true);
                shape(fileLocation, Set.of(), Set.of());
                String file = archive.file(parent(indexFile), text(fileLocation).trim());
                Element annotations = contents.root(file, "PageAnnot");
                shape(annotations, Set.of(), Set.of("Annot"));
                var appearances = new ArrayList<Appearance>();
                for (Element annotation : children(annotations)) {
                    if (++count > MAX_ANNOTATIONS || !identifiers.add(id(annotation, "ID"))) throw invalid();
                    appearances.add(appearance(annotation));
                }
                if (appearances.isEmpty()) throw invalid();
                result.put(target, List.copyOf(appearances));
            }
        }
        return Map.copyOf(result);
    }

    private static Appearance appearance(Element annotation) throws IOException {
        shape(annotation, ATTRIBUTES, Set.of("Remark", "Parameters", "Appearance"));
        if (!TYPES.contains(required(annotation, "Type"))) throw invalid();
        // 渲染不判定文件规范符合性；真实票面省略这两项非绘制元数据时仍保留静态外观。
        if (annotation.hasAttribute("Creator")) required(annotation, "Creator");
        if (annotation.hasAttribute("LastModDate")) date(required(annotation, "LastModDate"));
        boolean visible = bool(annotation, "Visible", true);
        // GB/T 33190 表 61 中这些标志作用于 Remark 的交互和打印，不改变静态 Appearance。
        bool(annotation, "Print", true); bool(annotation, "NoZoom", false);
        bool(annotation, "NoRotate", false); bool(annotation, "ReadOnly", true);
        Element remark = child(annotation, "Remark", false);
        if (remark != null) { shape(remark, Set.of(), Set.of()); text(remark); }
        Element parameters = child(annotation, "Parameters", false);
        if (parameters != null) {
            shape(parameters, Set.of(), Set.of("Parameter"));
            var names = new HashSet<String>();
            for (Element parameter : children(parameters)) {
                shape(parameter, Set.of("Name"), Set.of());
                if (!names.add(required(parameter, "Name"))) throw invalid();
                text(parameter);
            }
            if (names.isEmpty()) throw invalid();
        }
        Element appearance = child(annotation, "Appearance", true);
        shape(appearance, Set.of("ID", "Boundary"), InvoiceOfdRenderer.OBJECTS);
        if (appearance.hasAttribute("ID")) id(appearance, "ID");
        // 附录 A.4 的 Boundary 为可选；省略时沿用所在页面坐标，不合成额外边界。
        var boundary = appearance.hasAttribute("Boundary") ? InvoiceOfdVector.boundary(appearance) : null;
        return new Appearance(appearance, boundary, visible);
    }

    private static void date(String value) throws IOException {
        if (value.length() > MAX_DATE_LENGTH) throw invalid();
        try {
            var date = DatatypeFactory.newDefaultInstance().newXMLGregorianCalendar(value);
            if (!date.isValid() || !date.getXMLSchemaType().equals(DatatypeConstants.DATE)) throw invalid();
        } catch (IllegalArgumentException | IllegalStateException malformed) { throw invalid(); }
    }

    /**
     * 外观保留原 XML；图元检查、字体加载与实际绘制仍复用页面管线及其累计预算。
     * @author owlzhangfq@gmail.com
     */
    record Appearance(Element block, Rectangle2D.Double boundary, boolean visible) { }
}
