package io.agentflow.agent;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Element;
import static io.agentflow.agent.InvoiceOfdXml.*;

/**
 * 从文档和页面资源表定位包内内容；未嵌入字体只查询渲染会话的明确部署映射。
 * @author owlzhangfq@gmail.com
 */
final class InvoiceOfdResources {
    private static final Map<String, String> GROUPS = Map.of("Fonts", "Font", "ColorSpaces", "ColorSpace", "DrawParams", "DrawParam", "MultiMedias", "MultiMedia", "CompositeGraphicUnits", "CompositeGraphicUnit");
    private final InvoiceOfdArchive archive;
    private final Map<Long, Resource> resources = new HashMap<>();
    private final Map<Long, InvoiceOfdFont> fonts = new HashMap<>();
    private final InvoiceOfdFonts fontSession;
    private final InvoiceOfdColors colors;

    private InvoiceOfdResources(InvoiceOfdArchive archive, InvoiceOfdDocument.Contents contents,
                                String documentFile, String pageFile, InvoiceOfdColors.ProfileBudget profileBudget,
                                InvoiceOfdFonts fontSession) throws IOException {
        this.archive = archive;
        this.fontSession = fontSession;
        Element common = child(contents.root(documentFile, "Document"), "CommonData", true);
        var loaded = new HashSet<String>();
        for (String name : new String[]{"PublicRes", "DocumentRes"}) {
            for (Element location : children(common, name)) load(contents, archive.file(parent(documentFile), text(location).trim()), loaded);
        }
        for (Element location : children(contents.root(pageFile, "Page"), "PageRes")) load(contents, archive.file(parent(pageFile), text(location).trim()), loaded);
        Element defaultColor = child(common, "DefaultCS", false);
        Long defaultId = defaultColor == null ? null : Long.valueOf(integer(text(defaultColor).trim(), 1, 0xffff_ffffL));
        colors = new InvoiceOfdColors(this, defaultId, profileBudget);
    }

    Element element(long id, String type) throws IOException { return resource(id, type).element(); }
    Color color(Element element, Color fallback) throws IOException { return colors.read(element, fallback); }

    byte[] colorProfile(long id) throws IOException {
        Resource resource = resource(id, "ColorSpace");
        String file = archive.file(resource.base(), required(resource.element(), "Profile"));
        try (var input = archive.open(file)) { return input.readAllBytes(); }
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
        boolean bold = bool(definition, "Bold", false), italic = bool(definition, "Italic", false);
        bool(definition, "Serif", false); bool(definition, "FixedWidth", false);
        Element location = child(definition, "FontFile", false);
        // 包内字体存在但无效时直接失败，不能用部署字体掩盖缺件、截断或错误字形。
        var font = location == null ? fontSession.deployed(name, bold, italic)
                : fontSession.embedded(archive, archive.file(resource.base(), text(location).trim()), name);
        fonts.put(id, font);
        return font;
    }

    BufferedImage image(long id) throws IOException {
        Resource resource = resource(id, "MultiMedia");
        Element definition = resource.element();
        try (var input = archive.open(imageFile(resource))) {
            return InvoiceOfdImages.read(input, definition.hasAttribute("Format") ? definition.getAttribute("Format") : null);
        }
    }

    /** 预览图片只校验引用；实际输出始终完整绘制必需的矢量 Content。 */
    Composite composite(long id) throws IOException {
        Element definition = element(id, "CompositeGraphicUnit");
        shape(definition, Set.of("ID", "Width", "Height"), Set.of("Thumbnail", "Substitution", "Content"));
        double width = number(required(definition, "Width")), height = number(required(definition, "Height"));
        if (width < 0 || height < 0) throw invalid();
        for (String name : new String[]{"Thumbnail", "Substitution"}) {
            Element preview = child(definition, name, false);
            if (preview != null) {
                shape(preview, Set.of(), Set.of());
                imageFile(resource(integer(text(preview).trim(), 1, 0xffff_ffffL), "MultiMedia"));
            }
        }
        Element content = child(definition, "Content", true);
        shape(content, Set.of("ID"), InvoiceOfdRenderer.OBJECTS);
        if (content.hasAttribute("ID")) id(content, "ID");
        return new Composite(content, width, height);
    }

    private String imageFile(Resource resource) throws IOException {
        Element definition = resource.element();
        shape(definition, Set.of("ID", "Type", "Format"), Set.of("MediaFile"));
        if (!required(definition, "Type").equals("Image")) throw invalid();
        Element file = child(definition, "MediaFile", true);
        shape(file, Set.of(), Set.of());
        return archive.file(resource.base(), text(file).trim());
    }

    private Resource resource(long id, String type) throws IOException {
        Resource resource = resources.get(id);
        if (resource == null || !resource.element().getLocalName().equals(type)) throw invalid();
        return resource;
    }

    /**
     * 资源位置相对它所属的资源文件，而非当前页面。
     * @author owlzhangfq@gmail.com
     */
    private record Resource(Element element, String base) { }

    /**
     * 保留原 Content 身份以检查活动引用链；宽高是裁剪尺寸，不是自动缩放目标。
     * @author owlzhangfq@gmail.com
     */
    record Composite(Element content, double width, double height) { }

    /**
     * 一个输出页内复用模板资源作用域；字体由整次渲染会话持有，资源 ID 仍按私有作用域解析。
     * @author owlzhangfq@gmail.com
     */
    static final class Scopes {
        private final InvoiceOfdArchive archive;
        private final InvoiceOfdDocument.Contents contents;
        private final String documentFile;
        private final InvoiceOfdFonts fontSession;
        private final InvoiceOfdColors.ProfileBudget profileBudget;
        private final Map<String, InvoiceOfdResources> pages = new HashMap<>();

        Scopes(InvoiceOfdArchive archive, InvoiceOfdDocument.Contents contents, String documentFile, InvoiceOfdFonts fontSession) {
            this(archive, contents, documentFile, fontSession, new InvoiceOfdColors.ProfileBudget());
        }

        Scopes(InvoiceOfdArchive archive, InvoiceOfdDocument.Contents contents, String documentFile,
               InvoiceOfdFonts fontSession, InvoiceOfdColors.ProfileBudget profileBudget) {
            this.archive = archive; this.contents = contents; this.documentFile = documentFile;
            this.fontSession = fontSession; this.profileBudget = profileBudget;
        }

        InvoiceOfdResources page(String file) throws IOException {
            var resources = pages.get(file);
            if (resources == null) {
                resources = new InvoiceOfdResources(archive, contents, documentFile, file, profileBudget, fontSession);
                pages.put(file, resources);
            }
            return resources;
        }

    }
}
