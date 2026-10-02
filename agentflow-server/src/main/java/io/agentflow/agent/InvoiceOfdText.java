package io.agentflow.agent;

import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.w3c.dom.Element;
import static io.agentflow.agent.InvoiceOfdXml.*;

/**
 * 按 OFD 文字段的绘制点和局部字形变换生成轮廓，不套用系统字体排版。
 * @author owlzhangfq@gmail.com
 */
final class InvoiceOfdText {
    private static final int MAX_GLYPHS = 25_000;
    private static final int MAX_OUTLINE_SEGMENTS = 100_000;
    private static final Pattern HEX_ESCAPE = Pattern.compile("[0-9a-fA-F]{4}");
    private InvoiceOfdText() { }

    /** 字体已明确选定；缺字、越界变换和不完整位置都必须让整个文字对象失败。 */
    static Path2D.Double outline(Element object, InvoiceOfdFont font) throws IOException {
        double size = number(required(object, "Size")), scale = number(object, "HScale", 1);
        if (size <= 0 || scale <= 0 || bool(object, "Italic", false) || number(object, "Weight", 400) != 400) throw invalid();
        int direction = direction(object, "CharDirection"); direction(object, "ReadDirection");
        var result = new Path2D.Double(); var pending = new ArrayList<Element>();
        Double previousX = null, previousY = null;
        int total = 0, codes = 0, segments = 0;
        for (Element element : children(object)) {
            if (element.getLocalName().equals("CGTransform")) { pending.add(element); continue; }
            if (!element.getLocalName().equals("TextCode")) continue;
            shape(element, Set.of("X", "Y", "DeltaX", "DeltaY"), Set.of());
            int[] points = characters(text(element));
            Map<Integer, Replacement> replacements = replacements(pending, points.length); pending.clear();
            var glyphs = new ArrayList<Glyph>();
            for (int i = 0; i < points.length;) {
                Replacement replacement = replacements.get(i);
                if (replacement == null) { glyphs.add(new Glyph(points[i], false)); i++; }
                else {
                    for (int glyph : replacement.glyphs()) glyphs.add(new Glyph(glyph, true));
                    i += replacement.count();
                }
                if (glyphs.size() + total > MAX_GLYPHS) throw invalid();
            }
            total += glyphs.size(); codes++;
            if (element.hasAttribute("X")) previousX = number(element.getAttribute("X"));
            if (element.hasAttribute("Y")) previousY = number(element.getAttribute("Y"));
            if (previousX == null || previousY == null) throw invalid();
            double[] deltaX = offsets(element, "DeltaX", glyphs.size()), deltaY = offsets(element, "DeltaY", glyphs.size());
            double x = previousX, y = previousY;
            for (int i = 0; i < glyphs.size(); i++) {
                if (i > 0) { x += deltaX[i - 1]; y += deltaY[i - 1]; bounded(x); bounded(y); }
                var placement = AffineTransform.getTranslateInstance(x, y);
                placement.rotate(Math.toRadians(direction)); placement.scale(size * scale, -size);
                // Delta 已给出对象坐标中的实际偏移；ReadDirection 不重新排版或覆盖这些坐标。
                Glyph glyph = glyphs.get(i);
                GeneralPath outline = glyph.explicit() ? font.glyph(glyph.value()) : font.unicode(glyph.value());
                segments = append(result, outline, placement, segments);
            }
        }
        if (codes == 0 || !pending.isEmpty()) throw invalid();
        return result;
    }

    /** 边展开边计数，不能先复制所有轮廓后再检查总量；字体可把很短的正文展开成大量曲线。 */
    private static int append(Path2D.Double target, GeneralPath outline, AffineTransform placement, int segments) throws IOException {
        var coordinates = new double[6];
        for (var path = outline.getPathIterator(placement); !path.isDone(); path.next()) {
            if (++segments > MAX_OUTLINE_SEGMENTS) throw invalid();
            int type = path.currentSegment(coordinates);
            int count = switch (type) {
                case PathIterator.SEG_MOVETO, PathIterator.SEG_LINETO -> 2;
                case PathIterator.SEG_QUADTO -> 4;
                case PathIterator.SEG_CUBICTO -> 6;
                default -> 0;
            };
            for (int i = 0; i < count; i++) bounded(coordinates[i]);
            switch (type) {
                case PathIterator.SEG_MOVETO -> target.moveTo(coordinates[0], coordinates[1]);
                case PathIterator.SEG_LINETO -> target.lineTo(coordinates[0], coordinates[1]);
                case PathIterator.SEG_QUADTO -> target.quadTo(coordinates[0], coordinates[1], coordinates[2], coordinates[3]);
                case PathIterator.SEG_CUBICTO -> target.curveTo(coordinates[0], coordinates[1], coordinates[2], coordinates[3], coordinates[4], coordinates[5]);
                case PathIterator.SEG_CLOSE -> target.closePath();
                default -> throw invalid();
            }
        }
        return segments;
    }

    private static Map<Integer, Replacement> replacements(List<Element> elements, int codeCount) throws IOException {
        var result = new HashMap<Integer, Replacement>(); var occupied = new boolean[codeCount];
        for (Element element : elements) {
            shape(element, Set.of("CodePosition", "CodeCount", "GlyphCount"), Set.of("Glyphs"));
            int start = (int) integer(required(element, "CodePosition"), 0, MAX_GLYPHS);
            int count = (int) integer(element.hasAttribute("CodeCount") ? element.getAttribute("CodeCount") : "1", 1, MAX_GLYPHS);
            int glyphCount = (int) integer(element.hasAttribute("GlyphCount") ? element.getAttribute("GlyphCount") : "1", 1, MAX_GLYPHS);
            if (start + count > codeCount) throw invalid();
            for (int i = start; i < start + count; i++) { if (occupied[i]) throw invalid(); occupied[i] = true; }
            String[] values = text(child(element, "Glyphs", true)).trim().split("\\s+");
            if (values.length != glyphCount) throw invalid();
            var glyphs = new int[glyphCount];
            for (int i = 0; i < values.length; i++) glyphs[i] = (int) integer(values[i], 1, 65_535);
            result.put(start, new Replacement(count, glyphs));
        }
        return result;
    }

    private static int[] characters(String text) throws IOException {
        if (text.length() > MAX_GLYPHS * 6) throw invalid();
        var decoded = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (value == '\\') {
                if (i + 4 >= text.length()) throw invalid();
                String code = text.substring(i + 1, i + 5);
                if (!HEX_ESCAPE.matcher(code).matches()) throw invalid();
                value = (char) Integer.parseInt(code, 16); i += 4;
            }
            decoded.append(value);
        }
        int[] result = decoded.codePoints().toArray();
        if (result.length == 0 || result.length > MAX_GLYPHS) throw invalid();
        for (int value : result) if (value >= Character.MIN_SURROGATE && value <= Character.MAX_SURROGATE) throw invalid();
        return result;
    }

    private static double[] offsets(Element element, String name, int count) throws IOException {
        var result = new double[Math.max(0, count - 1)];
        if (!element.hasAttribute(name)) return result;
        String value = element.getAttribute(name);
        if (value.length() > MAX_GLYPHS * 64) throw invalid();
        String[] tokens = value.trim().split("\\s+"); int position = 0;
        for (int i = 0; i < tokens.length; i++) {
            int repeat = 1;
            if (tokens[i].equals("g")) {
                if (i + 2 >= tokens.length) throw invalid();
                repeat = (int) integer(tokens[++i], 1, MAX_GLYPHS); i++;
            }
            double offset = number(tokens[i]);
            if (position + repeat > MAX_GLYPHS) throw invalid();
            for (int end = position + repeat; position < end; position++) if (position < result.length) result[position] = offset;
        }
        // 多余偏移不对应任何绘制点，仍全部校验；不足时不猜测或重复最后一个间距。
        if (position < result.length) throw invalid();
        return result;
    }

    private static int direction(Element element, String attribute) throws IOException {
        int value = (int) integer(element.hasAttribute(attribute) ? element.getAttribute(attribute) : "0", 0, 270);
        if (value % 90 != 0) throw invalid();
        return value;
    }

    /**
     * 映射只对紧随其后的 TextCode 有效，不能与相邻段混用偏移。
     * @author owlzhangfq@gmail.com
     */
    private record Replacement(int count, int[] glyphs) { }

    /**
     * 先保存有界编号，在追加时逐个读取轮廓，避免为重复文字同时持有所有字体路径。
     * @author owlzhangfq@gmail.com
     */
    private record Glyph(int value, boolean explicit) { }
}
