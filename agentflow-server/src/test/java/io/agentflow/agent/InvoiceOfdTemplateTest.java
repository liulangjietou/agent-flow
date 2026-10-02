package io.agentflow.agent;

import java.awt.Color;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.agentflow.agent.InvoiceOfdArchiveTest.zip;
import static io.agentflow.agent.InvoiceOfdRendererTest.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 模板与正文按六个绘制阶段合成；资源、尺寸和失败结果不能串页或跨文档。
 * @author owlzhangfq@gmail.com
 */
class InvoiceOfdTemplateTest {
    @TempDir Path directory;

    @Test
    void interleavesTemplateBucketsWithTheCorrespondingPageLayers() throws Exception {
        var files = fixture(1);
        replace(files, "Doc_0/Document.xml", "20 20", "40 20");
        template(files, 902, "Foreground", "", layer(1902, "Background", bar(15, "0 255 255", "")));
        template(files, 901, "Body", "", layer(1901, "Body", bar(25, "0 255 0", "")));
        template(files, 900, "Background", "", layer(1900, "Foreground", bar(35, "255 0 0", "")));
        files.put("Doc_0/Pages/P0.xml", xml("Page", ref(902, "") + ref(900, "") + ref(901, "") + "<ofd:Content>"
                + layer(101, "Foreground", bar(10, "255 0 255", ""))
                + layer(102, "Body", bar(20, "255 255 0", ""))
                + layer(103, "Background", bar(30, "0 0 255", "")) + "</ofd:Content>"));
        byte[] image = render(files).get(0);
        Color[] colors = {Color.MAGENTA, Color.CYAN, Color.YELLOW, Color.GREEN, Color.BLUE, Color.RED};
        double[] x = {5, 12, 17, 22, 27, 32};
        for (int i = 0; i < colors.length; i++) assertThat(pixel(image, x[i], 5)).as("bucket %s", i).isEqualTo(colors[i].getRGB());
    }

    @Test
    void referenceOrderWinsOverDefinitionOrderWithinTheSameBucket() throws Exception {
        var files = fixture(1);
        template(files, 900, "", "", layer(1900, "", bar(10, "255 0 0", "")));
        template(files, 901, "", "", layer(1901, "", bar(10, "0 0 255", "")));
        page(files, 0, ref(901, "") + ref(900, ""), "");
        assertThat(pixel(render(files).get(0), 5, 5)).isEqualTo(Color.RED.getRGB());
        page(files, 0, ref(900, "") + ref(901, ""), "");
        assertThat(pixel(render(files).get(0), 5, 5)).isEqualTo(Color.BLUE.getRGB());
    }

    @Test
    void appliesReferenceOverridesWithoutMutatingLaterPageDefaults() throws Exception {
        var files = fixture(2);
        template(files, 900, "Foreground", "", layer(1900, "", bar(10, "255 0 0", "")));
        page(files, 0, ref(900, "ZOrder=\"Background\""), bar(10, "0 0 255", ""));
        page(files, 1, ref(900, ""), bar(10, "0 0 255", ""));
        var pages = render(files);
        assertThat(pixel(pages.get(0), 5, 5)).isEqualTo(Color.BLUE.getRGB());
        assertThat(pixel(pages.get(1), 5, 5)).isEqualTo(Color.RED.getRGB());
    }

    @Test
    void paintsEveryRepeatedTemplateReferenceWithItsTransparency() throws Exception {
        var files = fixture(1);
        template(files, 900, "", "", layer(1900, "", bar(10, "255 0 0", "Alpha=\"128\"")));
        page(files, 0, ref(900, "") + ref(900, ""), "");
        assertThat(pixel(render(files).get(0), 5, 5)).isEqualTo(new Color(255, 63, 63).getRGB());
    }

    @Test
    void inheritsTemplateAreaBeforeCommonAreaAndPreservesPhysicalOrigin() throws Exception {
        var files = fixture(1);
        template(files, 900, "", area("5 7 30 15"), layer(1900, "", path("5 7 10 10", "255 0 0", "")));
        page(files, 0, ref(900, ""), path("20 7 10 10", "0 0 255", ""));
        var source = archive(files);
        var declared = InvoiceOfdDocument.inspect(source).get(0);
        assertThat(declared.x()).isEqualTo(5); assertThat(declared.y()).isEqualTo(7);
        assertThat(declared.width()).isEqualTo(30); assertThat(declared.height()).isEqualTo(15);
        byte[] image = InvoiceOfdRenderer.render(source).get(0);
        var decoded = ImageIO.read(new ByteArrayInputStream(image));
        try { assertThat(decoded.getWidth()).isEqualTo(171); assertThat(decoded.getHeight()).isEqualTo(86); }
        finally { decoded.flush(); }
        assertThat(pixel(image, 2, 2)).isEqualTo(Color.RED.getRGB());
        assertThat(pixel(image, 17, 2)).isEqualTo(Color.BLUE.getRGB());
    }

    @Test
    void explicitPageAreaOverridesTemplatesAndAbsentTemplateAreaUsesCommon() throws Exception {
        var files = fixture(2);
        template(files, 900, "", area("0 0 60 60"), layer(1900, "", bar(10, "255 0 0", "")));
        template(files, 901, "", "", layer(1901, "", bar(10, "0 0 255", "")));
        page(files, 0, area("0 0 30 10") + ref(900, ""), "");
        page(files, 1, ref(901, ""), "");
        var pages = InvoiceOfdDocument.inspect(archive(files));
        assertThat(pages.get(0).width()).isEqualTo(30); assertThat(pages.get(0).height()).isEqualTo(10);
        assertThat(pages.get(1).width()).isEqualTo(20); assertThat(pages.get(1).height()).isEqualTo(20);
    }

    @Test
    void rejectsConflictingInheritedAreasUnlessThePageStatesItsOwnArea() throws Exception {
        var files = fixture(1);
        template(files, 900, "", area("0 0 20 20"), "");
        template(files, 901, "", area("0 0 30 30"), "");
        page(files, 0, ref(900, "") + ref(901, ""), "");
        assertThatThrownBy(() -> InvoiceOfdDocument.inspect(archive(files))).isInstanceOf(IOException.class);
        page(files, 0, area("0 0 40 40") + ref(900, "") + ref(901, ""), "");
        assertThat(InvoiceOfdDocument.inspect(archive(files)).get(0).width()).isEqualTo(40);
    }

    @Test
    void isolatesEachTemplatePageResourcesFromTheMainPageAndOtherTemplates() throws Exception {
        var files = fixture(1);
        replace(files, "Doc_0/Document.xml", "20 20", "40 20");
        template(files, 900, "", "", layer(1900, "", styledRectangle("1 1 10 10", 30)));
        template(files, 901, "", "", layer(1901, "", styledRectangle("13 1 10 10", 31)));
        pageResource(files, "Doc_0/Templates/T900/Content.xml", "Doc_0/Templates/T900/Res.xml", "Res.xml", 30, "255 0 0");
        pageResource(files, "Doc_0/Templates/T901/Content.xml", "Doc_0/Templates/T901/Res.xml", "Res.xml", 31, "0 255 0");
        page(files, 0, ref(900, "") + ref(901, ""), styledRectangle("25 1 10 10", 32));
        pageResource(files, "Doc_0/Pages/P0.xml", "Doc_0/Pages/MainRes.xml", "MainRes.xml", 32, "0 0 255");
        byte[] image = render(files).get(0);
        assertThat(pixel(image, 5, 5)).isEqualTo(Color.RED.getRGB());
        assertThat(pixel(image, 17, 5)).isEqualTo(Color.GREEN.getRGB());
        assertThat(pixel(image, 29, 5)).isEqualTo(Color.BLUE.getRGB());
        replace(files, "Doc_0/Templates/T900/Content.xml", "DrawParam=\"30\"", "DrawParam=\"32\"");
        assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
        replace(files, "Doc_0/Templates/T900/Content.xml", "DrawParam=\"32\"", "DrawParam=\"31\"");
        assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
    }

    @Test
    void templateLocalFontFilesStayRelativeToTheirOwnResourceTable() throws Exception {
        var files = fixture(1);
        template(files, 900, "", "", layer(1900, "", text("", "<ofd:TextCode X=\"1\" Y=\"6\">A</ofd:TextCode>").replace("Font=\"10\"", "Font=\"30\"")));
        replace(files, "Doc_0/Templates/T900/Content.xml", "<ofd:Content>", "<ofd:PageRes>Res.xml</ofd:PageRes><ofd:Content>");
        files.put("Doc_0/Templates/T900/Res.xml", res("assets", "<ofd:Fonts><ofd:Font ID=\"30\" FontName=\"AgentFlowSyntheticA\"><ofd:FontFile>a.ttf</ofd:FontFile></ofd:Font></ofd:Fonts>"));
        files.put("Doc_0/Templates/T900/assets/a.ttf", files.get("Doc_0/Res/a.ttf"));
        page(files, 0, ref(900, ""), "");
        assertThat(pixel(render(files).get(0), 2, 4)).isEqualTo(Color.BLACK.getRGB());
        files.remove("Doc_0/Templates/T900/assets/a.ttf");
        assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
    }

    @Test
    void doesNotExpandTemplateReferencesInsideTemplateBodies() throws Exception {
        var files = fixture(1);
        template(files, 900, "", "", layer(1900, "", bar(10, "255 0 0", "Alpha=\"128\"")));
        replace(files, "Doc_0/Templates/T900/Content.xml", "<ofd:Content>", ref(900, "") + "<ofd:Content>");
        page(files, 0, ref(900, ""), "");
        assertThat(pixel(render(files).get(0), 5, 5)).isEqualTo(new Color(255, 127, 127).getRGB());
    }

    @Test
    void keepsTheSameTemplateIdentifierLocalToEachDocument() throws Exception {
        var files = fixture(1);
        template(files, 900, "", "", layer(1900, "", bar(10, "255 0 0", "")));
        page(files, 0, ref(900, ""), "");
        for (var entry : Map.copyOf(files).entrySet()) {
            if (entry.getKey().startsWith("Doc_0/")) files.put(entry.getKey().replace("Doc_0/", "Doc_1/"), entry.getValue().clone());
        }
        replace(files, "OFD.xml", "</ofd:OFD>", "<ofd:DocBody><ofd:DocRoot>Doc_1/Document.xml</ofd:DocRoot></ofd:DocBody></ofd:OFD>");
        replace(files, "Doc_1/Templates/T900/Content.xml", "255 0 0", "0 0 255");
        var pages = render(files);
        assertThat(pages).hasSize(2);
        assertThat(pixel(pages.get(0), 5, 5)).isEqualTo(Color.RED.getRGB());
        assertThat(pixel(pages.get(1), 5, 5)).isEqualTo(Color.BLUE.getRGB());
    }

    @Test
    void failsTheWholeDocumentWhenALaterTemplateCannotBeDrawn() throws Exception {
        var files = fixture(2);
        template(files, 900, "", "", layer(1900, "", text("", "<ofd:TextCode X=\"1\" Y=\"6\">B</ofd:TextCode>")));
        page(files, 0, "", bar(10, "255 0 0", ""));
        page(files, 1, ref(900, ""), "");
        assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
    }

    @Test
    void rejectsUnknownDuplicatedOrMalformedTemplateDeclarationsAndReferences() throws Exception {
        for (String change : List.of("missing", "duplicate", "page-id", "declaration-order", "reference-order", "unknown")) {
            var files = fixture(1);
            template(files, 900, "", "", "");
            page(files, 0, ref(900, ""), "");
            switch (change) {
                case "missing" -> files.remove("Doc_0/Templates/T900/Content.xml");
                case "duplicate" -> replace(files, "Doc_0/Document.xml", "</ofd:CommonData>", "<ofd:TemplatePage ID=\"900\" BaseLoc=\"Templates/T900/Content.xml\"/></ofd:CommonData>");
                case "page-id" -> replace(files, "Doc_0/Document.xml", "ID=\"900\"", "ID=\"1\"");
                case "declaration-order" -> replace(files, "Doc_0/Document.xml", "ID=\"900\"", "ID=\"900\" ZOrder=\"Wrong\"");
                case "reference-order" -> replace(files, "Doc_0/Pages/P0.xml", "TemplateID=\"900\"", "TemplateID=\"900\" ZOrder=\"Wrong\"");
                case "unknown" -> replace(files, "Doc_0/Pages/P0.xml", "TemplateID=\"900\"", "TemplateID=\"901\"");
            }
            assertThatThrownBy(() -> render(files)).as(change).isInstanceOf(IOException.class);
        }
    }

    @Test
    void boundsTemplateDeclarationsAndPerPageExpansionIncludingEmptyTemplates() throws Exception {
        var files = fixture(1);
        for (int i = 0; i < 64; i++) template(files, 900 + i, "", "", "");
        page(files, 0, ref(900, "").repeat(64), "");
        assertThat(pixel(render(files).get(0), 5, 5)).isEqualTo(Color.WHITE.getRGB());
        page(files, 0, ref(900, "").repeat(65), "");
        assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
        page(files, 0, ref(900, ""), "");
        template(files, 964, "", "", "");
        assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
    }

    @Test
    void sharesNativeProfileBudgetAcrossTemplateAndMainPageScopes() throws Exception {
        var files = fixture(1); var references = new StringBuilder();
        for (int i = 0; i < 32; i++) {
            int template = 900 + i, color = 3000 + i;
            template(files, template, "", "", layer(1900 + i, "", coloredRectangle(color)));
            String base = "Doc_0/Templates/T" + template + "/";
            replace(files, base + "Content.xml", "<ofd:Content>", "<ofd:PageRes>Res.xml</ofd:PageRes><ofd:Content>");
            files.put(base + "Res.xml", res(".", profile(color)));
            files.put(base + "srgb.icc", ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData());
            references.append(ref(template, ""));
        }
        page(files, 0, references.toString(), "");
        assertThat(pixel(render(files).get(0), 5, 5)).isEqualTo(Color.RED.getRGB());
        page(files, 0, references.toString() + "<ofd:PageRes>MainRes.xml</ofd:PageRes>", coloredRectangle(3032));
        files.put("Doc_0/Pages/MainRes.xml", res(".", profile(3032)));
        files.put("Doc_0/Pages/srgb.icc", ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData());
        assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
    }

    @Test
    void boundsLayerExpansionEvenWhenTemplatesContainNoObjects() throws Exception {
        var files = fixture(1); var layers = new StringBuilder();
        for (int i = 0; i < 312; i++) layers.append(layer(3000 + i, "", ""));
        template(files, 900, "", "", layers.toString());
        page(files, 0, ref(900, "").repeat(64), "");
        assertThat(pixel(render(files).get(0), 5, 5)).isEqualTo(Color.WHITE.getRGB());
        replace(files, "Doc_0/Templates/T900/Content.xml", "</ofd:Content>", layer(3312, "", "") + "</ofd:Content>");
        assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
    }

    private List<byte[]> render(Map<String, byte[]> files) throws IOException { return InvoiceOfdRenderer.render(archive(files)); }
    private InvoiceOfdArchive archive(Map<String, byte[]> files) throws IOException {
        return InvoiceOfdArchive.read(Files.write(directory.resolve(UUID.randomUUID() + ".ofd"), zip(files)));
    }
    private static void template(Map<String, byte[]> files, int id, String order, String area, String layers) {
        String attr = order.isEmpty() ? "" : " ZOrder=\"" + order + "\"";
        replace(files, "Doc_0/Document.xml", "</ofd:CommonData>", "<ofd:TemplatePage ID=\"" + id + "\" BaseLoc=\"Templates/T" + id + "/Content.xml\"" + attr + "/></ofd:CommonData>");
        String content = area + (layers.isEmpty() ? "" : "<ofd:Content>" + layers + "</ofd:Content>");
        var identifiers = java.util.regex.Pattern.compile("\\bID=\"(?:20|21)\"").matcher(content);
        var next = new java.util.concurrent.atomic.AtomicInteger(10_000 + id * 10);
        files.put("Doc_0/Templates/T" + id + "/Content.xml", xml("Page", identifiers.replaceAll(match -> "ID=\"" + next.getAndIncrement() + "\"")));
    }
    private static String ref(int id, String attributes) { return "<ofd:Template TemplateID=\"" + id + "\" " + attributes + "/>"; }
    private static String area(String box) { return "<ofd:Area><ofd:PhysicalBox>" + box + "</ofd:PhysicalBox></ofd:Area>"; }
    private static String layer(int id, String type, String content) { return "<ofd:Layer ID=\"" + id + "\"" + (type.isEmpty() ? "" : " Type=\"" + type + "\"") + ">" + content + "</ofd:Layer>"; }
    private static String bar(int width, String color, String attributes) { return path("0 0 " + width + " 10", color, "CTM=\"" + width / 10.0 + " 0 0 1 0 0\" " + attributes); }
    private static String styledRectangle(String box, int style) { return path(box, "255 0 0", "DrawParam=\"" + style + "\"").replace("<ofd:FillColor Value=\"255 0 0\"/>", ""); }
    private static byte[] res(String base, String body) { return new String(xml("Res", body), StandardCharsets.UTF_8).replace("<ofd:Res ", "<ofd:Res BaseLoc=\"" + base + "\" ").getBytes(StandardCharsets.UTF_8); }
    private static void pageResource(Map<String, byte[]> files, String pageFile, String file, String location, int id, String color) {
        replace(files, pageFile, "<ofd:Content>", "<ofd:PageRes>" + location + "</ofd:PageRes><ofd:Content>");
        files.put(file, res(".", "<ofd:DrawParams><ofd:DrawParam ID=\"" + id + "\"><ofd:FillColor Value=\"" + color + "\"/></ofd:DrawParam></ofd:DrawParams>"));
    }
    private static String profile(int id) { return "<ofd:ColorSpaces><ofd:ColorSpace ID=\"" + id + "\" Type=\"RGB\" Profile=\"srgb.icc\"/></ofd:ColorSpaces>"; }
    private static String coloredRectangle(int color) { return path("0 0 10 10", "255 0 0", "").replace("<ofd:FillColor ", "<ofd:FillColor ColorSpace=\"" + color + "\" "); }
}
