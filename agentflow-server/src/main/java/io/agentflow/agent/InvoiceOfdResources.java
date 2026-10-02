package io.agentflow.agent;

import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.w3c.dom.Element;
import static io.agentflow.agent.InvoiceOfdXml.*;

/**
 * 从当前文档和页面的资源表定位包内字体及位图，不访问主机路径或网络。
 * @author owlzhangfq@gmail.com
 */
final class InvoiceOfdResources implements Closeable {
    private static final long MAX_IMAGE_PIXELS = 20_000_000;
    private static final int MAX_IMAGE_SIDE = 12_000;
    private static final int TTC_TAG = 0x74746366;
    private static final Map<String, String> GROUPS = Map.of("Fonts", "Font", "ColorSpaces", "ColorSpace", "DrawParams", "DrawParam", "MultiMedias", "MultiMedia", "CompositeGraphicUnits", "CompositeGraphicUnit");
    private final InvoiceOfdArchive archive;
    private final Map<Long, Resource> resources = new HashMap<>();
    private final Map<Long, InvoiceOfdFont> fonts = new HashMap<>();

    InvoiceOfdResources(InvoiceOfdArchive archive, InvoiceOfdDocument.Contents contents, InvoiceOfdDocument.Page page) throws IOException {
        this.archive = archive;
        Element common = child(contents.root(page.documentFile(), "Document"), "CommonData", true);
        var loaded = new HashSet<String>();
        for (String name : new String[]{"PublicRes", "DocumentRes"}) {
            for (Element location : children(common, name)) load(contents, archive.file(parent(page.documentFile()), text(location).trim()), loaded);
        }
        for (Element location : children(contents.root(page.file(), "Page"), "PageRes")) load(contents, archive.file(parent(page.file()), text(location).trim()), loaded);
    }

    private void load(InvoiceOfdDocument.Contents contents, String file, Set<String> loaded) throws IOException {
        if (!loaded.add(file)) return;
        Element root = contents.root(file, "Res");
        shape(root, Set.of("BaseLoc"), GROUPS.keySet());
        String base = root.hasAttribute("BaseLoc") ? archive.directory(parent(file), root.getAttribute("BaseLoc")) : parent(file);
        for (Element group : children(root)) {
            shape(group, Set.of(), Set.of(GROUPS.get(group.getLocalName())));
            for (Element entry : children(group)) {
                if (resources.putIfAbsent(id(entry, "ID"), new Resource(entry, base)) != null) throw invalid();
            }
        }
    }

    InvoiceOfdFont font(long id) throws IOException {
        if (fonts.containsKey(id)) return fonts.get(id);
        Resource resource = resource(id, "Font"); Element definition = resource.element();
        shape(definition, Set.of("ID", "FontName", "FamilyName", "Charset", "Serif", "Bold", "Italic", "FixedWidth"), Set.of("FontFile"));
        String name = required(definition, "FontName");
        Element location = child(definition, "FontFile", true);
        byte[] bytes;
        try (var input = archive.open(archive.file(resource.base(), text(location).trim()))) { bytes = input.readAllBytes(); }
        String face = bytes.length >= Integer.BYTES && ByteBuffer.wrap(bytes).getInt() == TTC_TAG ? name : null;
        var font = InvoiceOfdFont.load(bytes, face); fonts.put(id, font); return font;
    }

    BufferedImage image(long id) throws IOException {
        Resource resource = resource(id, "MultiMedia"); Element definition = resource.element();
        shape(definition, Set.of("ID", "Type", "Format"), Set.of("MediaFile"));
        if (!required(definition, "Type").equals("Image")) throw invalid();
        String file = archive.file(resource.base(), text(child(definition, "MediaFile", true)).trim());
        try (var input = archive.open(file); var stream = new MemoryCacheImageInputStream(input)) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw invalid();
            var reader = readers.next();
            try {
                String format = reader.getFormatName().toUpperCase(java.util.Locale.ROOT);
                if (!Set.of("PNG", "JPEG").contains(format)) throw invalid();
                if (definition.hasAttribute("Format")) {
                    String declared = definition.getAttribute("Format").toUpperCase(java.util.Locale.ROOT);
                    if (declared.equals("JPG")) declared = "JPEG";
                    if (!declared.equals(format)) throw invalid();
                }
                reader.setInput(stream, true, true);
                var warned = new AtomicBoolean();
                reader.addIIOReadWarningListener((source, warning) -> warned.set(true));
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_IMAGE_SIDE || height > MAX_IMAGE_SIDE || (long) width * height > MAX_IMAGE_PIXELS) throw invalid();
                BufferedImage result = reader.read(0);
                // JPEG 等解码器会对截断数据仅告警并交出部分像素，这仍属于整页失败。
                if (result == null || warned.get()) {
                    if (result != null) result.flush();
                    throw invalid();
                }
                return result;
            } finally { reader.dispose(); }
        }
    }

    private Resource resource(long id, String type) throws IOException {
        Resource resource = resources.get(id);
        if (resource == null || !resource.element().getLocalName().equals(type)) throw invalid();
        return resource;
    }

    /** 页面结束时释放全部实际加载的字体；关闭错误不能阻止其他资源释放。 */
    @Override public void close() throws IOException {
        IOException failure = null;
        for (InvoiceOfdFont font : fonts.values()) {
            try { font.close(); } catch (IOException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        }
        fonts.clear();
        if (failure != null) throw failure;
    }

    /**
     * 资源位置相对它所属的资源文件，而非当前页面。
     * @author owlzhangfq@gmail.com
     */
    private record Resource(Element element, String base) { }
}
