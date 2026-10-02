package io.agentflow.agent;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.PathIterator;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.w3c.dom.Element;
import static io.agentflow.agent.InvoiceOfdXml.*;

/**
 * 将已经完整预检的 OFD 页面绘制为有序图片；尚未接入公开内容适配器。
 * @author owlzhangfq@gmail.com
 */
final class InvoiceOfdRenderer {
    private static final double PIXELS_PER_MM = 144.0 / 25.4;
    private static final long MAX_PAGE_PIXELS = 8_000_000;
    private static final long MAX_TOTAL_PIXELS = 40_000_000;
    private static final int MAX_PNG_BYTES = 20 * 1024 * 1024;
    private static final int MAX_OBJECTS = 20_000;
    private static final int MAX_SEGMENTS = 1_000_000;
    private static final Set<String> OBJECTS = Set.of("PageBlock", "PathObject", "TextObject", "ImageObject");
    private static final Set<String> IMAGE_ATTRIBUTES = InvoiceOfdVector.attributes("ResourceID");
    private static final List<String> LAYER_ORDER = List.of("Background", "Body", "Foreground");
    private int objects;
    private int segments;
    private final InvoiceOfdClips clips = new InvoiceOfdClips();

    private InvoiceOfdRenderer() { }

    /** 所有页面成功后才交出图片，任何绘制失败都不得返回前面页面的部分结果。 */
    static List<byte[]> render(InvoiceOfdArchive archive) throws IOException {
        var contents = InvoiceOfdDocument.read(archive);
        inspectDrawingScope(contents);
        var renderer = new InvoiceOfdRenderer();
        var result = new ArrayList<byte[]>();
        int bytes = 0;
        for (var page : contents.pages()) {
            byte[] image = renderer.page(archive, contents, page, MAX_PNG_BYTES - bytes);
            bytes += image.length;
            result.add(image);
        }
        return List.copyOf(result);
    }

    /** 尚未实现的模板、批注和签章在入口拒绝，不能输出少内容的“成功”图片。 */
    private static void inspectDrawingScope(InvoiceOfdDocument.Contents contents) throws IOException {
        Element ofd = contents.root("OFD.xml", "OFD");
        shape(ofd, Set.of("Version", "DocType"), Set.of("DocBody"));
        for (Element body : children(ofd)) shape(body, Set.of(), Set.of("DocInfo", "DocRoot"));
        var documents = new HashSet<String>();
        long pixels = 0;
        for (var page : contents.pages()) {
            bounded(page.x()); bounded(page.y());
            long pagePixels = (long) pixels(page.width()) * pixels(page.height());
            pixels += pagePixels;
            if (pagePixels > MAX_PAGE_PIXELS || pixels > MAX_TOTAL_PIXELS) throw invalid();
            if (documents.add(page.documentFile())) {
                Element document = contents.root(page.documentFile(), "Document");
                shape(document, Set.of(), Set.of("CommonData", "Pages", "Attachments", "CustomTags"));
                Element common = child(document, "CommonData", true);
                shape(common, Set.of(), Set.of("MaxUnitID", "PageArea", "PublicRes", "DocumentRes", "DefaultCS"));
                area(child(common, "PageArea", false));
                Element definitions = child(document, "Pages", true);
                shape(definitions, Set.of(), Set.of("Page"));
                for (Element definition : children(definitions)) shape(definition, Set.of("ID", "BaseLoc"), Set.of());
            }
            Element root = contents.root(page.file(), "Page");
            shape(root, Set.of(), Set.of("Area", "PageRes", "Content"));
            area(child(root, "Area", false));
        }
    }

    private static void area(Element area) throws IOException {
        if (area == null) return;
        shape(area, Set.of(), Set.of("PhysicalBox", "ApplicationBox", "ContentBox", "BleedBox"));
        for (Element box : children(area)) {
            shape(box, Set.of(), Set.of());
            double[] values = numbers(text(box), 4);
            if (values[2] < 0 || values[3] < 0) throw invalid();
        }
    }

    private byte[] page(InvoiceOfdArchive archive, InvoiceOfdDocument.Contents contents,
                        InvoiceOfdDocument.Page page, int remainingBytes) throws IOException {
        var image = new BufferedImage(pixels(page.width()), pixels(page.height()), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try (var resources = new InvoiceOfdResources(archive, contents, page)) {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.scale(PIXELS_PER_MM, PIXELS_PER_MM);
            graphics.translate(-page.x(), -page.y());
            Element content = child(contents.root(page.file(), "Page"), "Content", false);
            if (content != null) layers(graphics, content, resources);
            return png(image, remainingBytes);
        } finally { graphics.dispose(); image.flush(); }
    }

    private void layers(Graphics2D graphics, Element content, InvoiceOfdResources resources) throws IOException {
        shape(content, Set.of(), Set.of("Layer"));
        List<Element> layers = children(content);
        for (Element layer : layers) {
            shape(layer, Set.of("ID", "Type", "DrawParam"), OBJECTS);
            id(layer, "ID");
            if (!LAYER_ORDER.contains(layerType(layer))) throw invalid();
        }
        // 同类型保持文档顺序；背景、正文、前景分层绘制，不依赖文件恰好已经排序。
        for (String type : LAYER_ORDER) {
            for (Element layer : layers) if (type.equals(layerType(layer))) {
                block(graphics, layer, resources, InvoiceOfdStyle.resolve(layer, resources, InvoiceOfdStyle.DEFAULT));
            }
        }
    }

    private static String layerType(Element layer) { return layer.hasAttribute("Type") ? layer.getAttribute("Type") : "Body"; }

    private void block(Graphics2D graphics, Element block, InvoiceOfdResources resources, InvoiceOfdStyle style) throws IOException {
        for (Element object : children(block)) {
            if (++objects > MAX_OBJECTS) throw invalid();
            if (object.getLocalName().equals("PageBlock")) {
                shape(object, Set.of("ID"), OBJECTS);
                if (object.hasAttribute("ID")) id(object, "ID");
                block(graphics, object, resources, style);
            } else draw(graphics, object, resources, style);
        }
    }

    private void draw(Graphics2D parent, Element object, InvoiceOfdResources resources, InvoiceOfdStyle inherited) throws IOException {
        boolean image = object.getLocalName().equals("ImageObject");
        if (image) shape(object, IMAGE_ATTRIBUTES, Set.of("Clips"));
        id(object, "ID");
        var box = InvoiceOfdVector.boundary(object);
        var style = InvoiceOfdStyle.resolve(object, resources, inherited);
        int alpha = object.hasAttribute("Alpha") ? (int) integer(object.getAttribute("Alpha"), 0, 255) : 255;
        if (!bool(object, "Visible", true)) alpha = 0;
        Graphics2D graphics = (Graphics2D) parent.create();
        try {
            // Boundary 属于父对象坐标；先裁剪再应用自身 CTM，不能让放大操作扩大边界。
            graphics.clip(box);
            graphics.translate(box.x, box.y);
            Element clipping = child(object, "Clips", false);
            boolean transformClip = clipping != null && bool(clipping, "TransFlag", false);
            if (transformClip) graphics.transform(transform(object));
            if (clipping != null) graphics.clip(clips.read(clipping, resources, style, graphics.getTransform()));
            if (!transformClip) graphics.transform(transform(object));
            double[] matrix = new double[6]; graphics.getTransform().getMatrix(matrix);
            for (double value : matrix) bounded(value);
            graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha / 255f));
            if (image) image(graphics, resources, object);
            else paint(graphics, resources, object, style);
        } finally { graphics.dispose(); }
    }

    private void paint(Graphics2D graphics, InvoiceOfdResources resources, Element object, InvoiceOfdStyle style) throws IOException {
        boolean textObject = InvoiceOfdVector.isText(object);
        boolean fill = bool(object, "Fill", textObject);
        boolean stroke = bool(object, "Stroke", !textObject);
        Color fillColor = style.fillColor(resources, textObject), strokeColor = style.strokeColor(resources, textObject);
        Shape shape = InvoiceOfdVector.outline(object, resources);
        inspectOutline(shape, graphics.getTransform());
        if (fill) { graphics.setColor(fillColor); graphics.fill(shape); }
        if (stroke) {
            Shape outline = style.stroke(shape, graphics.getTransform());
            inspectOutline(outline, graphics.getTransform());
            graphics.setColor(strokeColor); graphics.fill(outline);
        }
    }

    private void inspectOutline(Shape shape, AffineTransform transform) throws IOException {
        double[] coordinates = new double[6];
        for (var iterator = shape.getPathIterator(transform); !iterator.isDone(); iterator.next()) {
            if (++segments > MAX_SEGMENTS) throw invalid();
            int count = switch (iterator.currentSegment(coordinates)) {
                case PathIterator.SEG_MOVETO, PathIterator.SEG_LINETO -> 2;
                case PathIterator.SEG_QUADTO -> 4;
                case PathIterator.SEG_CUBICTO -> 6;
                default -> 0;
            };
            for (int i = 0; i < count; i++) bounded(coordinates[i]);
        }
    }

    private static void image(Graphics2D graphics, InvoiceOfdResources resources, Element object) throws IOException {
        BufferedImage image = resources.image(id(object, "ResourceID"));
        try {
            graphics.drawImage(image, AffineTransform.getScaleInstance(1.0 / image.getWidth(), 1.0 / image.getHeight()), null);
        } finally { image.flush(); }
    }

    private static int pixels(double millimeters) { return (int) Math.ceil(millimeters * PIXELS_PER_MM); }

    private static byte[] png(BufferedImage image, int remainingBytes) throws IOException {
        if (remainingBytes < 1) throw invalid();
        var bytes = new ByteArrayOutputStream();
        var writers = ImageIO.getImageWritersByFormatName("PNG");
        if (!writers.hasNext()) throw invalid();
        var writer = writers.next();
        try (var output = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);
            writer.write(null, new IIOImage(image, null, null), writer.getDefaultWriteParam());
            output.flush();
            if (bytes.size() > remainingBytes) throw invalid();
            return bytes.toByteArray();
        } finally { writer.dispose(); }
    }
}
