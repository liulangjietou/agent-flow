package io.agentflow.agent;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.PathIterator;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import org.w3c.dom.Element;
import static io.agentflow.agent.InvoiceOfdXml.*;

/**
 * 逐属性合并图层、引用链与图元样式；长度始终保持 OFD 对象坐标单位。
 * @author owlzhangfq@gmail.com
 */
record InvoiceOfdStyle(double lineWidth, int cap, int join, double miterLength,
                       double[] dash, double dashOffset, Element fill, Element stroke) {
    static final Set<String> ATTRIBUTES = Set.of("LineWidth", "Cap", "Join", "MiterLimit", "DashPattern", "DashOffset");
    static final InvoiceOfdStyle DEFAULT = new InvoiceOfdStyle(0.353, BasicStroke.CAP_BUTT,
            BasicStroke.JOIN_MITER, 3.528, null, 0, null, null);
    private static final Color TRANSPARENT = new Color(0, true);
    private static final int MAX_REFERENCE_DEPTH = 32;
    private static final int MAX_DASH_ITEMS = 256;
    private static final int MAX_DASH_TRANSITIONS = 25_000;
    private static final double SCALE_TOLERANCE = 1e-9;

    /** 引用链不补默认值，先叠加到已有图层样式上，最后覆盖图元显式属性。 */
    static InvoiceOfdStyle resolve(Element element, InvoiceOfdResources resources, InvoiceOfdStyle inherited) throws IOException {
        InvoiceOfdStyle result = inherited;
        if (element.hasAttribute("DrawParam")) {
            long reference = id(element, "DrawParam");
            var chain = new ArrayList<Element>();
            var seen = new HashSet<Long>();
            var attributes = new HashSet<>(ATTRIBUTES); attributes.add("ID"); attributes.add("Relative");
            while (true) {
                if (!seen.add(reference) || seen.size() > MAX_REFERENCE_DEPTH) throw invalid();
                Element parameter = resources.element(reference, "DrawParam");
                shape(parameter, attributes, Set.of("FillColor", "StrokeColor"));
                chain.add(parameter);
                if (!parameter.hasAttribute("Relative")) break;
                reference = id(parameter, "Relative");
            }
            for (int i = chain.size() - 1; i >= 0; i--) result = result.overlay(chain.get(i));
        }
        return result.overlay(element);
    }

    private InvoiceOfdStyle overlay(Element element) throws IOException {
        double width = number(element, "LineWidth", lineWidth);
        double limit = number(element, "MiterLimit", miterLength);
        if (width < 0 || limit < 0) throw invalid();
        int newCap = !element.hasAttribute("Cap") ? cap : switch (element.getAttribute("Cap")) {
            case "Butt" -> BasicStroke.CAP_BUTT;
            case "Round" -> BasicStroke.CAP_ROUND;
            case "Square" -> BasicStroke.CAP_SQUARE;
            default -> throw invalid();
        };
        int newJoin = !element.hasAttribute("Join") ? join : switch (element.getAttribute("Join")) {
            case "Miter" -> BasicStroke.JOIN_MITER;
            case "Round" -> BasicStroke.JOIN_ROUND;
            case "Bevel" -> BasicStroke.JOIN_BEVEL;
            default -> throw invalid();
        };
        double[] pattern = dash;
        if (element.hasAttribute("DashPattern")) {
            String[] values = element.getAttribute("DashPattern").trim().split("\\s+");
            if (values.length < 2 || values.length > MAX_DASH_ITEMS) throw invalid();
            pattern = new double[values.length]; double total = 0;
            for (int i = 0; i < values.length; i++) {
                pattern[i] = number(values[i]);
                if (pattern[i] < 0 || pattern[i] > 0 && (float) pattern[i] == 0) throw invalid();
                total += pattern[i];
            }
            if (total == 0) throw invalid();
        }
        Element fillColor = child(element, "FillColor", false), strokeColor = child(element, "StrokeColor", false);
        return new InvoiceOfdStyle(width, newCap, newJoin, limit, pattern, number(element, "DashOffset", dashOffset),
                fillColor == null ? fill : fillColor, strokeColor == null ? stroke : strokeColor);
    }

    Color fillColor(InvoiceOfdResources resources, boolean text) throws IOException { return resources.color(fill, text ? Color.BLACK : TRANSPARENT); }
    Color strokeColor(InvoiceOfdResources resources, boolean text) throws IOException { return resources.color(stroke, text ? TRANSPARENT : Color.BLACK); }

    /** OFD 斜接值是内外角之间的长度，Java 接口要求该长度与线宽的比值。 */
    Shape stroke(Shape path, AffineTransform toPixels) throws IOException {
        double width = rasterWidth(toPixels);
        float strokeWidth = (float) width;
        if (!Float.isFinite(strokeWidth) || strokeWidth <= 0) throw invalid();
        double ratio = miterLength / width;
        int actualJoin = join == BasicStroke.JOIN_MITER && ratio < 1 ? BasicStroke.JOIN_BEVEL : join;
        float[] pattern = null; float phase = 0;
        if (dash != null) {
            double period = 0; pattern = new float[dash.length];
            for (int i = 0; i < dash.length; i++) { pattern[i] = (float) dash[i]; period += dash[i]; }
            // 在 Java 展开虚线之前检查长度预算，避免极短周期形成海量子路径。
            if (Math.ceil(lengthBound(path) / period + 1) * dash.length > MAX_DASH_TRANSITIONS) throw invalid();
            if (dash.length % 2 != 0) period *= 2;
            phase = (float) ((dashOffset % period + period) % period);
        }
        return new BasicStroke(strokeWidth, cap, actualJoin, (float) Math.max(1, ratio), pattern, phase).createStrokedShape(path);
    }

    private double rasterWidth(AffineTransform transform) throws IOException {
        double a = transform.getScaleX(), b = transform.getShearY(), c = transform.getShearX(), d = transform.getScaleY();
        double first = a * a + b * b, second = c * c + d * d;
        double maximum = Math.sqrt((first + second + Math.hypot(first - second, 2 * (a * c + b * d))) / 2);
        double minimum = Math.abs(transform.getDeterminant()) / maximum;
        if (!Double.isFinite(minimum) || minimum <= 0) throw invalid();
        if (lineWidth > 0 && lineWidth * minimum >= 2) return lineWidth;
        // 非等比变换下的设备最小线宽需要逐段处理，不能直接把整条线放大到最小奇异值。
        if (maximum - minimum > maximum * SCALE_TOLERANCE) throw invalid();
        return lineWidth == 0 ? 1 / minimum : Math.max(lineWidth, 2 / minimum);
    }

    /** 曲线控制多边形长度是实际弧长的上界，预算检查无需先细分曲线。 */
    private static double lengthBound(Shape shape) {
        double[] values = new double[6]; double x = 0, y = 0, startX = 0, startY = 0, length = 0;
        for (var path = shape.getPathIterator(null); !path.isDone(); path.next()) {
            int type = path.currentSegment(values);
            if (type == PathIterator.SEG_MOVETO) { x = startX = values[0]; y = startY = values[1]; continue; }
            if (type == PathIterator.SEG_CLOSE) { length += Math.hypot(startX - x, startY - y); x = startX; y = startY; continue; }
            int count = type == PathIterator.SEG_CUBICTO ? 6 : type == PathIterator.SEG_QUADTO ? 4 : 2;
            for (int i = 0; i < count; i += 2) { length += Math.hypot(values[i] - x, values[i + 1] - y); x = values[i]; y = values[i + 1]; }
        }
        return length;
    }
}
