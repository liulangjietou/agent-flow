package io.agentflow.agent;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.w3c.dom.Element;
import static io.agentflow.agent.InvoiceOfdResult.*;
import static io.agentflow.agent.InvoiceOfdXml.*;

/**
 * 将已经完整预检的 OFD 页面绘制为有序图片；尚未接入公开内容适配器。
 * @author owlzhangfq@gmail.com
 */
final class InvoiceOfdRenderer {
    private static final double PIXELS_PER_MM = 144.0 / 25.4;
    private static final int MAX_SEAL_DEPTH = 4;
    private static final int MAX_OBJECTS = 20_000;
    private static final int MAX_LAYER_PAINTS = 20_000;
    private static final int MAX_SEGMENTS = 1_000_000;
    private static final int MAX_BLOCK_DEPTH = 64;
    private static final int MAX_COMPOSITE_DEPTH = 16;
    private static final long MAX_LIVE_GROUP_PIXELS = 16_000_000;
    private static final long MAX_TOTAL_GROUP_PIXELS = 40_000_000;
    static final Set<String> OBJECTS = Set.of("PageBlock", "PathObject", "TextObject", "ImageObject", "CompositeObject");
    private static final Set<String> RESOURCE_OBJECT_ATTRIBUTES = InvoiceOfdVector.attributes("ResourceID");
    private static final List<String> LAYER_ORDER = List.of("Background", "Body", "Foreground");
    private int objects;
    private int segments;
    private int layerPaints;
    private int blockDepth;
    private long liveGroupPixels;
    private long totalGroupPixels;
    private long totalPagePixels;
    private long totalStampPixels;
    private final InvoiceOfdDocument.XmlBudget xmlBudget = new InvoiceOfdDocument.XmlBudget();
    private final Map<InvoiceOfdSeal.Picture, DocumentData> nestedDocuments = new IdentityHashMap<>();
    private final Set<Element> activeComposites = Collections.newSetFromMap(new IdentityHashMap<>());
    private final InvoiceOfdClips clips = new InvoiceOfdClips();
    private final Map<Element, List<Element>> layerCache = new IdentityHashMap<>();

    private InvoiceOfdRenderer() { }

    /** 所有页面成功后才交出图片，任何绘制失败都不得返回前面页面的部分结果。 */
    static List<byte[]> render(InvoiceOfdArchive archive) throws IOException {
        return render(archive, null);
    }

    /** 字体清单必须来自可信部署配置，不能取自 OFD 内容；同次调用的各文档和页面共享字体所有者。 */
    static List<byte[]> render(InvoiceOfdArchive archive, Path fontCatalog) throws IOException {
        var renderer = new InvoiceOfdRenderer();
        var document = renderer.document(archive);
        var result = new ArrayList<byte[]>();
        int bytes = 0;
        try (var fonts = InvoiceOfdFonts.open(archive, fontCatalog)) {
            for (var page : document.contents().pages()) {
                byte[] image = renderer.page(document, page, MAX_PNG_BYTES - bytes, fonts);
                bytes += image.length;
                result.add(image);
            }
        }
        return List.copyOf(result);
    }

    private DocumentData document(InvoiceOfdArchive archive) throws IOException {
        var contents = InvoiceOfdDocument.read(archive, xmlBudget);
        inspectDrawingScope(contents);
        for (var page : contents.pages()) {
            totalPagePixels += (long) pixels(page.width()) * pixels(page.height());
            if (totalPagePixels > MAX_TOTAL_PIXELS) throw invalid();
        }
        return new DocumentData(archive, contents, InvoiceOfdAnnotations.read(archive, contents), InvoiceOfdSignatures.read(archive, contents));
    }

    /** 页面、批注和签章必须属于明确支持的形状，不允许遗漏未知绘制内容。 */
    private static void inspectDrawingScope(InvoiceOfdDocument.Contents contents) throws IOException {
        Element ofd = contents.root("OFD.xml", "OFD");
        shape(ofd, Set.of("Version", "DocType"), Set.of("DocBody"));
        for (Element body : children(ofd)) shape(body, Set.of(), Set.of("DocInfo", "DocRoot", "Signatures"));
        var documents = new HashSet<String>();
        var pageFiles = new HashSet<String>();
        long pixels = 0;
        for (var page : contents.pages()) {
            bounded(page.x()); bounded(page.y());
            long pagePixels = (long) pixels(page.width()) * pixels(page.height());
            pixels += pagePixels;
            if (pagePixels > MAX_PAGE_PIXELS || pixels > MAX_TOTAL_PIXELS) throw invalid();
            if (documents.add(page.documentFile())) {
                Element document = contents.root(page.documentFile(), "Document");
                shape(document, Set.of(), Set.of("CommonData", "Pages", "Attachments", "CustomTags", "Annotations"));
                Element common = child(document, "CommonData", true);
                shape(common, Set.of(), Set.of("MaxUnitID", "PageArea", "PublicRes", "DocumentRes", "DefaultCS", "TemplatePage"));
                area(child(common, "PageArea", false));
                Element definitions = child(document, "Pages", true);
                shape(definitions, Set.of(), Set.of("Page"));
                for (Element definition : children(definitions)) shape(definition, Set.of("ID", "BaseLoc"), Set.of());
            }
            inspectPage(contents, page.file(), pageFiles);
            for (var template : page.templates()) inspectPage(contents, template.file(), pageFiles);
        }
    }

    private static void inspectPage(InvoiceOfdDocument.Contents contents, String file, Set<String> checked) throws IOException {
        if (!checked.add(file)) return;
        Element root = contents.root(file, "Page");
        shape(root, Set.of(), Set.of("Area", "PageRes", "Content", "Template"));
        area(child(root, "Area", false));
    }

    private static void area(Element area) throws IOException {
        if (area == null) return;
        shape(area, Set.of(), Set.of("PhysicalBox", "ApplicationBox", "ContentBox", "BleedBox", "CropBox"));
        Element crop = child(area, "CropBox", false);
        if (crop != null) {
            // 仅兼容公开票据中等同物理边界的冗余框；其他扩展语义不明，不能猜测后裁掉内容。
            Element physical = child(area, "PhysicalBox", true);
            if (!Arrays.equals(numbers(text(crop), 4), numbers(text(physical), 4))) throw invalid();
        }
        for (Element box : children(area)) {
            shape(box, Set.of(), Set.of());
            double[] values = numbers(text(box), 4);
            if (values[2] < 0 || values[3] < 0) throw invalid();
        }
    }

    private byte[] page(DocumentData document, InvoiceOfdDocument.Page page,
                        int remainingBytes, InvoiceOfdFonts fonts) throws IOException {
        var image = new BufferedImage(pixels(page.width()), pixels(page.height()), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setClip(0, 0, image.getWidth(), image.getHeight());
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.scale(PIXELS_PER_MM, PIXELS_PER_MM);
            paintPage(graphics, document, page, fonts, 0, new InvoiceOfdColors.ProfileBudget());
            return png(image, remainingBytes);
        } finally { graphics.dispose(); image.flush(); }
    }

    /** 正文页与嵌套章共用矢量绘制；这里只裁剪和绘制，不填白底或重新分配整页位图。 */
    private void paintPage(Graphics2D parent, DocumentData document, InvoiceOfdDocument.Page page,
                           InvoiceOfdFonts fonts, int sealDepth, InvoiceOfdColors.ProfileBudget profileBudget) throws IOException {
        Graphics2D graphics = (Graphics2D) parent.create();
        try {
            graphics.clip(new Rectangle2D.Double(0, 0, page.width(), page.height()));
            graphics.translate(-page.x(), -page.y());
            var scopes = new InvoiceOfdResources.Scopes(document.archive(), document.contents(), page.documentFile(), fonts, profileBudget);
            var resources = scopes.page(page.file());
            var layers = layers(document.contents(), page.file());
            // 每种类型先模板后正文；模板作为整体处于其引用指定的类型中。
            for (String type : LAYER_ORDER) {
                for (var template : page.templates()) if (type.equals(template.zOrder())) {
                    var templateResources = scopes.page(template.file());
                    var templateLayers = layers(document.contents(), template.file());
                    for (String innerType : LAYER_ORDER) paintLayers(graphics, templateLayers, templateResources, innerType);
                }
                paintLayers(graphics, layers, resources, type);
            }
            paintStamps(graphics, document, page, fonts, sealDepth, profileBudget);
            paintAnnotations(graphics, document.annotations().getOrDefault(page, List.of()), resources);
        } finally { graphics.dispose(); }
    }

    private void paintStamps(Graphics2D parent, DocumentData document, InvoiceOfdDocument.Page page,
                             InvoiceOfdFonts fonts, int sealDepth, InvoiceOfdColors.ProfileBudget profileBudget) throws IOException {
        for (var stamp : document.stamps().getOrDefault(page, List.of())) {
            if (++objects > MAX_OBJECTS) throw invalid();
            Graphics2D graphics = (Graphics2D) parent.create();
            try {
                var box = stamp.boundary();
                graphics.clip(box);
                graphics.translate(box.x, box.y);
                if (stamp.clip() != null) graphics.clip(stamp.clip());
                if (stamp.picture().format().equals("OFD")) {
                    if (sealDepth >= MAX_SEAL_DEPTH) throw invalid();
                    DocumentData nested = nestedDocuments.get(stamp.picture());
                    if (nested == null) {
                        nested = document(document.archive().nested(stamp.picture().bytes()));
                        // 单个印章图像没有选择页的语义；多页章必须整份拒绝，不能默认丢弃后页。
                        if (nested.contents().pages().size() != 1) throw invalid();
                        nestedDocuments.put(stamp.picture(), nested);
                    }
                    var nestedPage = nested.contents().pages().get(0);
                    graphics.scale(box.width / nestedPage.width(), box.height / nestedPage.height());
                    paintPage(graphics, nested, nestedPage, fonts, sealDepth + 1, profileBudget);
                } else {
                    try (var source = new ByteArrayInputStream(stamp.picture().bytes())) {
                        BufferedImage image = InvoiceOfdImages.read(source, stamp.picture().format());
                        try {
                            totalStampPixels += (long) image.getWidth() * image.getHeight();
                            if (totalStampPixels > MAX_TOTAL_PIXELS) throw invalid();
                            graphics.drawImage(image, AffineTransform.getScaleInstance(box.width / image.getWidth(), box.height / image.getHeight()), null);
                        } finally { image.flush(); }
                    }
                }
            } finally { graphics.dispose(); }
        }
    }

    private void paintAnnotations(Graphics2D parent, List<InvoiceOfdAnnotations.Appearance> annotations,
                                  InvoiceOfdResources resources) throws IOException {
        for (var appearance : annotations) {
            if (++objects > MAX_OBJECTS) throw invalid();
            Graphics2D graphics = (Graphics2D) parent.create();
            try {
                var box = appearance.boundary();
                if (box != null) { graphics.clip(box); graphics.translate(box.x, box.y); }
                // 隐藏只影响像素，仍检查图元并核算资源；空裁剪不会被子图元的 Alpha 覆盖。
                if (!appearance.visible()) graphics.clip(new Rectangle2D.Double());
                block(graphics, appearance.block(), resources, InvoiceOfdStyle.DEFAULT);
            } finally { graphics.dispose(); }
        }
    }

    private List<Element> layers(InvoiceOfdDocument.Contents contents, String file) throws IOException {
        Element root = contents.root(file, "Page");
        var cached = layerCache.get(root);
        if (cached != null) return cached;
        Element content = child(root, "Content", false);
        if (content == null) { layerCache.put(root, List.of()); return List.of(); }
        shape(content, Set.of(), Set.of("Layer"));
        List<Element> layers = children(content);
        for (Element layer : layers) {
            shape(layer, Set.of("ID", "Type", "DrawParam"), OBJECTS);
            if (layer.hasAttribute("ID")) id(layer, "ID");
            if (!LAYER_ORDER.contains(layerType(layer))) throw invalid();
        }
        layerCache.put(root, List.copyOf(layers));
        return layerCache.get(root);
    }

    private void paintLayers(Graphics2D graphics, List<Element> layers, InvoiceOfdResources resources, String type) throws IOException {
        for (Element layer : layers) if (type.equals(layerType(layer))) {
            // 空图层也消耗实际展开预算，不能利用重复模板绕过图元数量限制。
            if (++layerPaints > MAX_LAYER_PAINTS) throw invalid();
            block(graphics, layer, resources, InvoiceOfdStyle.resolve(layer, resources, InvoiceOfdStyle.DEFAULT));
        }
    }

    private static String layerType(Element layer) { return layer.hasAttribute("Type") ? layer.getAttribute("Type") : "Body"; }

    private void block(Graphics2D graphics, Element block, InvoiceOfdResources resources, InvoiceOfdStyle style) throws IOException {
        // XML 深度只约束单文件；资源间跳转与 PageBlock 必须合计，才能约束实际 Java 调用栈。
        if (++blockDepth > MAX_BLOCK_DEPTH) throw invalid();
        try {
            for (Element object : children(block)) {
                if (++objects > MAX_OBJECTS) throw invalid();
                if (object.getLocalName().equals("PageBlock")) {
                    shape(object, Set.of("ID"), OBJECTS);
                    if (object.hasAttribute("ID")) id(object, "ID");
                    block(graphics, object, resources, style);
                } else draw(graphics, object, resources, style);
            }
        } finally { blockDepth--; }
    }

    private void draw(Graphics2D parent, Element object, InvoiceOfdResources resources, InvoiceOfdStyle inherited) throws IOException {
        boolean image = object.getLocalName().equals("ImageObject");
        boolean composite = object.getLocalName().equals("CompositeObject");
        if (image || composite) shape(object, RESOURCE_OBJECT_ATTRIBUTES, Set.of("Clips"));
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
            else if (composite) composite(graphics, resources, object, style, alpha);
            else paint(graphics, resources, object, style);
        } finally { graphics.dispose(); }
    }

    private void composite(Graphics2D graphics, InvoiceOfdResources resources, Element object,
                           InvoiceOfdStyle style, int alpha) throws IOException {
        var resource = resources.composite(id(object, "ResourceID"));
        Element content = resource.content();
        if (activeComposites.size() >= MAX_COMPOSITE_DEPTH || !activeComposites.add(content)) throw invalid();
        try {
            graphics.clip(new Rectangle2D.Double(0, 0, resource.width(), resource.height()));
            // 隐藏不跳过展开和校验，避免损坏资源或递归环借此绕过预算。
            if (alpha == 0 || graphics.getTransform().getDeterminant() == 0) graphics.clip(new Rectangle2D.Double());
            if (alpha == 0 || alpha == 255 || graphics.getTransform().getDeterminant() == 0) block(graphics, content, resources, style);
            else translucentComposite(graphics, content, resources, style);
        } finally { activeComposites.remove(content); }
    }

    /** 先合成组内图元，再对整组应用一次 Alpha；子图元重叠不能重复叠加组透明度。 */
    private void translucentComposite(Graphics2D graphics, Element content, InvoiceOfdResources resources,
                                      InvoiceOfdStyle style) throws IOException {
        Shape clip = graphics.getClip();
        if (clip == null) throw invalid();
        var bounds = graphics.getTransform().createTransformedShape(clip).getBounds();
        if (bounds.isEmpty()) { block(graphics, content, resources, style); return; }
        long pixels = (long) bounds.width * bounds.height;
        if (pixels > MAX_PAGE_PIXELS || pixels > MAX_LIVE_GROUP_PIXELS - liveGroupPixels
                || pixels > MAX_TOTAL_GROUP_PIXELS - totalGroupPixels) throw invalid();
        liveGroupPixels += pixels; totalGroupPixels += pixels;
        var image = new BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D isolated = image.createGraphics();
        try {
            isolated.setRenderingHints(graphics.getRenderingHints());
            isolated.translate(-bounds.x, -bounds.y);
            isolated.transform(graphics.getTransform());
            isolated.setClip(clip);
            block(isolated, content, resources, style);
            Graphics2D device = (Graphics2D) graphics.create();
            try {
                device.setTransform(new AffineTransform());
                device.drawImage(image, bounds.x, bounds.y, null);
            } finally { device.dispose(); }
        } finally { isolated.dispose(); image.flush(); liveGroupPixels -= pixels; }
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
    /** 每个容器拥有自己的 DOM/资源身份，所有容器仍共享本次渲染累计额度。 */
    private record DocumentData(InvoiceOfdArchive archive, InvoiceOfdDocument.Contents contents,
                                Map<InvoiceOfdDocument.Page, List<InvoiceOfdAnnotations.Appearance>> annotations,
                                Map<InvoiceOfdDocument.Page, List<InvoiceOfdSignatures.Stamp>> stamps) { }

}
