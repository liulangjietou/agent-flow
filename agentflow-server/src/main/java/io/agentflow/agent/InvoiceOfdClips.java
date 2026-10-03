package io.agentflow.agent;

import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.PathIterator;
import java.io.IOException;
import java.util.Set;
import org.w3c.dom.Element;
import static io.agentflow.agent.InvoiceOfdXml.*;

/**
 * 以几何区域完成 Clip 的并集／交集，沿用原对象的坐标、样式及字体；不以颜色代替裁剪。
 * @author owlzhangfq@gmail.com
 */
final class InvoiceOfdClips {
    private static final int MAX_AREAS = 256;
    private static final int MAX_DEPTH = 8;
    private static final int MAX_SEGMENTS = 20_000;
    private static final long MAX_BOOLEAN_WORK = 4_000_000;
    private int areas;
    private long booleanWork;

    /** toPixels 描述调用方已经确定的裁剪坐标空间；返回同一空间中的区域。 */
    Area read(Element clips, InvoiceOfdResources resources, InvoiceOfdStyle inherited, AffineTransform toPixels) throws IOException {
        return read(clips, resources, inherited, toPixels, 1);
    }

    private Area read(Element clips, InvoiceOfdResources resources, InvoiceOfdStyle inherited,
                      AffineTransform toPixels, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw invalid();
        shape(clips, Set.of("TransFlag"), Set.of("Clip"));
        var values = children(clips);
        if (values.isEmpty()) throw invalid();
        Area result = null;
        for (Element clip : values) {
            shape(clip, Set.of(), Set.of("Area"));
            var parts = children(clip);
            if (parts.isEmpty()) throw invalid();
            var union = new Area();
            for (Element part : parts) {
                if (++areas > MAX_AREAS) throw invalid();
                combine(union, part(part, resources, inherited, toPixels, depth), false);
            }
            if (result == null) result = union;
            else combine(result, union, true);
        }
        return result;
    }

    private Area part(Element area, InvoiceOfdResources resources, InvoiceOfdStyle inherited,
                      AffineTransform toPixels, int depth) throws IOException {
        shape(area, Set.of("DrawParam", "CTM"), Set.of("Path", "Text"));
        var children = children(area);
        if (children.size() != 1) throw invalid();
        Element vector = children.get(0);
        Shape outline = InvoiceOfdVector.outline(vector, resources);
        var style = InvoiceOfdStyle.resolve(vector, resources, InvoiceOfdStyle.resolve(area, resources, inherited));
        var boundary = InvoiceOfdVector.boundary(vector);
        AffineTransform areaTransform = transform(area), own = transform(vector);
        var boundaryToPixels = new AffineTransform(toPixels); boundaryToPixels.concatenate(areaTransform); boundaryToPixels.translate(boundary.x, boundary.y);
        var objectToPixels = new AffineTransform(boundaryToPixels); objectToPixels.concatenate(own);
        // 非默认可见性／透明度的裁剪含义尚未验明，暂不把它们当作普通不透明路径。
        if (!bool(vector, "Visible", true) || number(vector, "Alpha", 255) != 255) throw invalid();
        boolean text = InvoiceOfdVector.isText(vector);
        var region = new Area();
        if (bool(vector, "Fill", text)) combine(region, geometry(outline), false);
        if (bool(vector, "Stroke", !text)) combine(region, geometry(style.stroke(outline, objectToPixels)), false);
        Element nested = child(vector, "Clips", false);
        boolean transformClip = nested != null && bool(nested, "TransFlag", false);
        if (transformClip) combine(region, read(nested, resources, style, objectToPixels, depth + 1), true);
        region.transform(own);
        if (nested != null && !transformClip) combine(region, read(nested, resources, style, boundaryToPixels, depth + 1), true);
        region.transform(AffineTransform.getTranslateInstance(boundary.x, boundary.y));
        combine(region, geometry(boundary), true);
        region.transform(areaTransform); count(region);
        return region;
    }

    /** 布尔运算可能放大交点数，在调用 Java Area 前先计入输入规模预算。 */
    private Area geometry(Shape shape) throws IOException {
        int count = count(shape); charge((long) count * count);
        var result = new Area(shape); count(result); return result;
    }

    private void combine(Area target, Area value, boolean intersection) throws IOException {
        charge((long) count(target) * count(value));
        if (intersection) target.intersect(value); else target.add(value);
        count(target);
    }

    private void charge(long cost) throws IOException { booleanWork += cost; if (booleanWork > MAX_BOOLEAN_WORK) throw invalid(); }

    private static int count(Shape shape) throws IOException {
        int count = 0; double[] coordinates = new double[6];
        for (var path = shape.getPathIterator(null); !path.isDone(); path.next()) {
            if (++count > MAX_SEGMENTS) throw invalid();
            int length = switch (path.currentSegment(coordinates)) {
                case PathIterator.SEG_MOVETO, PathIterator.SEG_LINETO -> 2;
                case PathIterator.SEG_QUADTO -> 4;
                case PathIterator.SEG_CUBICTO -> 6;
                default -> 0;
            };
            for (int i = 0; i < length; i++) bounded(coordinates[i]);
        }
        return count;
    }
}
