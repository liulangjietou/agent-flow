package io.agentflow.agent;

import java.awt.Shape;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.w3c.dom.Element;
import static io.agentflow.agent.InvoiceOfdXml.*;

/**
 * 页面绘制和裁剪共用相同的路径、文字与边界解释，不为裁剪另写宽松解析器。
 * @author owlzhangfq@gmail.com
 */
final class InvoiceOfdVector {
    private static final Set<String> PATH_ATTRIBUTES = attributes("Fill", "Stroke", "Rule");
    private static final Set<String> TEXT_ATTRIBUTES = attributes("Fill", "Stroke", "Font", "Size", "HScale", "CharDirection", "ReadDirection", "Italic", "Weight");
    private InvoiceOfdVector() { }

    static Set<String> attributes(String... extra) {
        var attributes = new HashSet<>(Set.of("ID", "Name", "Boundary", "CTM", "Alpha", "Visible", "DrawParam", "BlendMode"));
        attributes.addAll(InvoiceOfdStyle.ATTRIBUTES); attributes.addAll(Arrays.asList(extra));
        return Set.copyOf(attributes);
    }

    static boolean isText(Element element) { return Set.of("Text", "TextObject").contains(element.getLocalName()); }

    static Shape outline(Element element, InvoiceOfdResources resources) throws IOException {
        if (element.hasAttribute("ID")) id(element, "ID");
        if (isText(element)) {
            shape(element, TEXT_ATTRIBUTES, Set.of("FillColor", "StrokeColor", "Clips", "CGTransform", "TextCode"));
            return InvoiceOfdText.outline(element, resources.font(id(element, "Font")));
        }
        if (!Set.of("Path", "PathObject").contains(element.getLocalName())) throw invalid();
        shape(element, PATH_ATTRIBUTES, Set.of("FillColor", "StrokeColor", "Clips", "AbbreviatedData"));
        String rule = element.hasAttribute("Rule") ? element.getAttribute("Rule") : "NonZero";
        if (!rule.equals("NonZero") && !rule.equals("Even-Odd")) throw invalid();
        Element data = child(element, "AbbreviatedData", true);
        shape(data, Set.of(), Set.of());
        return InvoiceOfdPath.parse(text(data), rule.equals("Even-Odd"));
    }

    static Rectangle2D.Double boundary(Element element) throws IOException {
        double[] box = numbers(required(element, "Boundary"), 4);
        if (box[2] < 0 || box[3] < 0) throw invalid();
        return new Rectangle2D.Double(box[0], box[1], box[2], box[3]);
    }
}
