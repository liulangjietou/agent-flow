package io.agentflow.agent;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
 * 从包内复合资源绘制到像素，覆盖实例变换、整体透明度和有界资源展开。
 * @author owlzhangfq@gmail.com
 */
class InvoiceOfdCompositeTest {
    @TempDir Path directory;

    @Test
    void clipsResourceDimensionsWithoutFittingAndKeepsRepeatedInstancesIndependent() throws Exception {
        var files = fixture(1);
        units(files, unit(30, "3", "4", path("0 0 10 10", "255 0 0", "")));
        page(files, 0, "", composite(40, 30, "1 2 8 8", "CTM=\"2 0 0 2 0 0\"")
                + composite(41, 30, "12 2 6 6", "") + path("12 12 5 5", "0 0 255", ""));
        byte[] image = render(files).get(0);
        assertThat(pixel(image, 2, 3)).isEqualTo(Color.RED.getRGB());
        assertThat(pixel(image, 8, 3)).isEqualTo(Color.WHITE.getRGB());
        assertThat(pixel(image, 2, 11)).isEqualTo(Color.WHITE.getRGB());
        assertThat(pixel(image, 13, 3)).isEqualTo(Color.RED.getRGB());
        assertThat(pixel(image, 16, 3)).isEqualTo(Color.WHITE.getRGB());
        assertThat(pixel(image, 13, 13)).isEqualTo(Color.BLUE.getRGB());
    }

    @Test
    void nestsVectorContentAndInheritsStylesWithoutMutatingSharedResources() throws Exception {
        var files = fixture(1);
        String rectangle = path("0 0 4 4", "0 0 0", "").replace("<ofd:FillColor Value=\"0 0 0\"/>", "");
        units(files, unit(30, "4", "4", "<ofd:PageBlock>" + rectangle + "</ofd:PageBlock>")
                + unit(31, "10", "10", composite(42, 30, "1 1 8 8", "CTM=\"2 0 0 2 0 0\"")));
        replace(files, "Doc_0/Resources.xml", "</ofd:Res>", "<ofd:DrawParams>"
                + "<ofd:DrawParam ID=\"32\"><ofd:FillColor Value=\"255 0 0\"/></ofd:DrawParam>"
                + "<ofd:DrawParam ID=\"33\"><ofd:FillColor Value=\"0 0 255\"/></ofd:DrawParam>"
                + "</ofd:DrawParams></ofd:Res>");
        page(files, 0, "", composite(40, 31, "2 2 10 10", "DrawParam=\"32\"")
                + composite(41, 30, "14 2 4 4", "DrawParam=\"33\""));
        byte[] image = render(files).get(0);
        assertThat(pixel(image, 4, 4)).isEqualTo(Color.RED.getRGB());
        assertThat(pixel(image, 2.5, 4)).isEqualTo(Color.WHITE.getRGB());
        assertThat(pixel(image, 15, 3)).isEqualTo(Color.BLUE.getRGB());
    }

    @Test
    void appliesCompositeAlphaOnceToOverlappingChildren() throws Exception {
        var files = fixture(1);
        units(files, unit(30, "12", "8", path("0 0 8 8", "255 0 0", "")
                + path("4 0 8 8", "0 0 255", "").replace("ID=\"20\"", "ID=\"21\"")));
        page(files, 0, "", composite(40, 30, "1 1 12 8", "Alpha=\"128\"")
                + path("14 1 4 4", "0 0 255", ""));
        byte[] image = render(files).get(0);
        assertThat(pixel(image, 3, 3)).isEqualTo(new Color(255, 127, 127).getRGB());
        assertThat(pixel(image, 7, 3)).isEqualTo(new Color(127, 127, 255).getRGB());
        assertThat(pixel(image, 11, 3)).isEqualTo(pixel(image, 7, 3));
        assertThat(pixel(image, 15, 3)).isEqualTo(Color.BLUE.getRGB());
    }

    @Test
    void paintsCompositeInsideAnnotationAppearance() throws Exception {
        var files = fixture(1);
        units(files, unit(30, "4", "4", path("0 0 4 4", "255 0 0", "")));
        InvoiceOfdAnnotationsTest.index(files, InvoiceOfdAnnotationsTest.entry(1, "Composite.xml"));
        InvoiceOfdAnnotationsTest.file(files, "Composite.xml", InvoiceOfdAnnotationsTest.annot(500, "Stamp", "", "5 5 10 10",
                composite(40, 30, "1 1 4 4", "")));
        byte[] image = render(files).get(0);
        assertThat(pixel(image, 7, 7)).isEqualTo(Color.RED.getRGB());
        assertThat(pixel(image, 5, 7)).isEqualTo(Color.WHITE.getRGB());
    }

    @Test
    void combinesNestedGroupAndChildAlphaAndKeepsHiddenGroupsInvisible() throws Exception {
        var files = fixture(1);
        units(files, unit(30, "4", "4", path("0 0 4 4", "255 0 0", "Alpha=\"128\""))
                + unit(31, "4", "4", composite(42, 30, "0 0 4 4", "Alpha=\"128\"")));
        page(files, 0, "", composite(40, 31, "1 1 4 4", "Alpha=\"128\"")
                + composite(41, 31, "7 1 4 4", "Visible=\"false\"")
                + composite(43, 31, "13 1 4 4", "Alpha=\"0\""));
        byte[] image = render(files).get(0);
        assertThat(pixel(image, 2, 2)).isEqualTo(new Color(255, 223, 223).getRGB());
        assertThat(pixel(image, 8, 2)).isEqualTo(Color.WHITE.getRGB());
        assertThat(pixel(image, 14, 2)).isEqualTo(Color.WHITE.getRGB());
    }

    @Test
    void keepsTransparentGroupInDeviceCoordinatesUnderRotationAndPhysicalOrigin() throws Exception {
        var files = fixture(1);
        units(files, unit(30, "8", "8", path("0 0 4 4", "255 0 0", "")));
        page(files, 0, "<ofd:Area><ofd:PhysicalBox>5 7 20 20</ofd:PhysicalBox></ofd:Area>",
                composite(40, 30, "5 7 12 12", "CTM=\"0 1 -1 0 8 0\" Alpha=\"128\""));
        byte[] image = render(files).get(0);
        assertThat(pixel(image, 5, 1)).isEqualTo(new Color(255, 127, 127).getRGB());
        assertThat(pixel(image, 1, 1)).isEqualTo(Color.WHITE.getRGB());
        assertThat(pixel(image, 5, 5)).isEqualTo(Color.WHITE.getRGB());
    }

    @Test
    void preservesClipTransformOrderForOpaqueAndTranslucentInstances() throws Exception {
        for (String alpha : List.of("255", "128")) {
            var files = fixture(1);
            units(files, unit(30, "10", "10", path("0 0 10 10", "255 0 0", "")));
            String clip = "<ofd:Clips TransFlag=\"true\"><ofd:Clip><ofd:Area>"
                    + path("0 0 3 10", "0 0 0", "").replace("PathObject", "Path").replace(" ID=\"20\"", "")
                    + "</ofd:Area></ofd:Clip></ofd:Clips>";
            String object = composite(40, 30, "0 0 20 20", "CTM=\"2 0 0 2 0 0\" Alpha=\"" + alpha + "\"")
                    .replace("/>", ">" + clip + "</ofd:CompositeObject>");
            page(files, 0, "", object);
            byte[] image = render(files).get(0);
            assertThat(pixel(image, 5, 5)).isEqualTo(new Color(255, 255 - Integer.parseInt(alpha), 255 - Integer.parseInt(alpha)).getRGB());
            assertThat(pixel(image, 7, 5)).isEqualTo(Color.WHITE.getRGB());
            page(files, 0, "", object.replace("TransFlag=\"true\"", "TransFlag=\"false\""));
            assertThat(pixel(render(files).get(0), 5, 5)).isEqualTo(Color.WHITE.getRGB());
        }
    }

    @Test
    void resolvesNestedImageAndFontRelativeToThePageResourceFile() throws Exception {
        var files = fixture(1);
        String content = text("", "<ofd:TextCode X=\"1\" Y=\"6\">A</ofd:TextCode>").replace("Font=\"10\"", "Font=\"32\"")
                + "<ofd:ImageObject ID=\"22\" ResourceID=\"31\" Boundary=\"10 1 4 4\" CTM=\"4 0 0 4 0 0\"/>";
        files.put("Doc_0/Pages/Local/Res.xml", xml("Res", "<ofd:Fonts><ofd:Font ID=\"32\" FontName=\"AgentFlowSyntheticA\"><ofd:FontFile>a.ttf</ofd:FontFile></ofd:Font></ofd:Fonts>"
                + media(31, "picture.png") + "<ofd:CompositeGraphicUnits>" + unit(30, "20", "20", content) + "</ofd:CompositeGraphicUnits>"));
        files.put("Doc_0/Pages/Local/a.ttf", InvoiceOfdFontTest.bytes("a.ttf"));
        files.put("Doc_0/Pages/Local/picture.png", solidImage(Color.BLUE));
        page(files, 0, "<ofd:PageRes>Local/Res.xml</ofd:PageRes>", composite(40, 30, "0 0 20 20", ""));
        byte[] image = render(files).get(0);
        assertThat(pixel(image, 2, 4)).isEqualTo(Color.BLACK.getRGB());
        assertThat(pixel(image, 12, 3)).isEqualTo(Color.BLUE.getRGB());
        files.remove("Doc_0/Pages/Local/picture.png");
        assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
    }

    @Test
    void keepsResourceIdentifiersLocalToPagesTemplatesAndDocuments() throws Exception {
        var files = fixture(2);
        for (int i = 0; i < 2; i++) {
            String color = i == 0 ? "255 0 0" : "0 0 255";
            files.put("Doc_0/Pages/R" + i + ".xml", xml("Res", "<ofd:CompositeGraphicUnits>"
                    + unit(30, "10", "10", path("0 0 10 10", color, "")) + "</ofd:CompositeGraphicUnits>"));
            page(files, i, "<ofd:PageRes>R" + i + ".xml</ofd:PageRes>", composite(40, 30, "0 0 10 10", ""));
        }
        replace(files, "Doc_0/Document.xml", "</ofd:CommonData>", "<ofd:TemplatePage ID=\"900\" BaseLoc=\"Template.xml\"/></ofd:CommonData>");
        files.put("Doc_0/Template.xml", xml("Page", "<ofd:PageRes>TemplateRes.xml</ofd:PageRes><ofd:Content><ofd:Layer>"
                + composite(44, 30, "12 0 6 6", "") + "</ofd:Layer></ofd:Content>"));
        files.put("Doc_0/TemplateRes.xml", xml("Res", "<ofd:CompositeGraphicUnits>"
                + unit(30, "6", "6", path("0 0 6 6", "0 255 0", "")) + "</ofd:CompositeGraphicUnits>"));
        replace(files, "Doc_0/Pages/P0.xml", "<ofd:Content>", "<ofd:Template TemplateID=\"900\"/><ofd:Content>");
        for (var entry : Map.copyOf(files).entrySet()) if (entry.getKey().startsWith("Doc_0/")) files.put(entry.getKey().replace("Doc_0/", "Doc_1/"), entry.getValue().clone());
        replace(files, "OFD.xml", "</ofd:OFD>", "<ofd:DocBody><ofd:DocRoot>Doc_1/Document.xml</ofd:DocRoot></ofd:DocBody></ofd:OFD>");
        replace(files, "Doc_1/Pages/R0.xml", "255 0 0", "255 255 0");
        var pages = render(files);
        assertThat(pages).hasSize(4);
        assertThat(pixel(pages.get(0), 3, 3)).isEqualTo(Color.RED.getRGB());
        assertThat(pixel(pages.get(0), 14, 3)).isEqualTo(Color.GREEN.getRGB());
        assertThat(pixel(pages.get(1), 3, 3)).isEqualTo(Color.BLUE.getRGB());
        assertThat(pixel(pages.get(2), 3, 3)).isEqualTo(Color.YELLOW.getRGB());
        replace(files, "Doc_0/Pages/P1.xml", "<ofd:PageRes>R1.xml</ofd:PageRes>", "");
        assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
    }

    @Test
    void validatesPreviewReferencesWithoutSubstitutingThemForVectorContent() throws Exception {
        var files = fixture(1);
        String definition = unit(30, "10", "10", path("0 0 10 10", "255 0 0", ""))
                .replace("<ofd:Content>", "<ofd:Thumbnail>31</ofd:Thumbnail><ofd:Substitution>31</ofd:Substitution><ofd:Content>");
        units(files, definition);
        replace(files, "Doc_0/Resources.xml", "</ofd:Res>", media(31, "blue.png") + "</ofd:Res>");
        files.put("Doc_0/Res/blue.png", solidImage(Color.BLUE));
        page(files, 0, "", composite(40, 30, "0 0 10 10", ""));
        assertThat(pixel(render(files).get(0), 3, 3)).isEqualTo(Color.RED.getRGB());
        replace(files, "Doc_0/Resources.xml", "<ofd:Thumbnail>31</ofd:Thumbnail>", "<ofd:Thumbnail>10</ofd:Thumbnail>");
        assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
    }

    @Test
    void rejectsMalformedDefinitionsAndUnknownCompositeProperties() throws Exception {
        String valid = unit(30, "10", "10", path("0 0 10 10", "255 0 0", ""));
        for (String definition : List.of(valid.replace("Width=\"10\"", "Width=\"-1\""), valid.replace("Height=\"10\"", "Height=\"NaN\""),
                valid.replace(" Width=\"10\"", ""), valid.replace("<ofd:Content>", "<ofd:Content/><ofd:Content>"),
                valid.replace("Content", "Unknown"), valid.replace("PathObject", "VideoObject"),
                valid.replace("<ofd:Content>", "<ofd:Thumbnail>999</ofd:Thumbnail><ofd:Content>"),
                valid.replace("<ofd:Content>", "<ofd:Content ID=\"0\">"))) {
            var files = fixture(1); units(files, definition);
            page(files, 0, "", composite(40, 30, "0 0 10 10", ""));
            assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
        }
        for (String extra : List.of("Unknown=\"true\"", "Alpha=\"256\"", "CTM=\"1 0 0 1 0 NaN\"")) {
            var files = fixture(1); units(files, valid); page(files, 0, "", composite(40, 30, "0 0 10 10", extra));
            assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
        }
    }

    @Test
    void validatesHiddenZeroSizedAndOffPageContentInsteadOfReturningPartialPages() throws Exception {
        for (String extra : List.of("Visible=\"false\"", "Alpha=\"0\"", "Alpha=\"128\"")) {
            var files = fixture(2);
            units(files, unit(30, "0", "0", path("0 0 10 10", "255 0 0", "")));
            page(files, 0, "", path("0 0 10 10", "0 0 255", ""));
            page(files, 1, "", composite(40, 30, "50 50 10 10", extra));
            assertThat(pixel(render(files).get(1), 3, 3)).isEqualTo(Color.WHITE.getRGB());
            replace(files, "Doc_0/Resources.xml", "L 10 0", "Z 10 0");
            assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
        }
    }

    @Test
    void rejectsDirectAndIndirectCyclesEvenInInvisibleContent() throws Exception {
        for (boolean indirect : List.of(false, true)) {
            var files = fixture(1);
            units(files, unit(30, "10", "10", composite(42, indirect ? 31 : 30, "0 0 10 10", ""))
                    + (indirect ? unit(31, "10", "10", composite(43, 30, "0 0 10 10", "")) : ""));
            page(files, 0, "", composite(40, 30, "0 0 10 10", "Visible=\"false\""));
            assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
        }
    }

    @Test
    void boundsCompositeDepthAndCombinedPageBlockDepthAcrossResources() throws Exception {
        var files = fixture(1); units(files, chain(16, "10", ""));
        page(files, 0, "", composite(40, 1000, "0 0 10 10", ""));
        assertThat(render(files)).hasSize(1);
        var tooDeep = fixture(1); units(tooDeep, chain(17, "10", ""));
        page(tooDeep, 0, "", composite(40, 1000, "0 0 10 10", ""));
        assertThatThrownBy(() -> render(tooDeep)).isInstanceOf(IOException.class);
        var combined = fixture(1);
        units(combined, unit(30, "10", "10", blocks(31, composite(42, 31, "0 0 10 10", "")))
                + unit(31, "10", "10", blocks(30, "")));
        page(combined, 0, "", composite(40, 30, "0 0 10 10", ""));
        assertThat(render(combined)).hasSize(1);
        replace(combined, "Doc_0/Resources.xml", blocks(30, ""), blocks(31, ""));
        assertThatThrownBy(() -> render(combined)).isInstanceOf(IOException.class);
    }

    @Test
    void countsEveryRepeatedExpansionAgainstTheWholeCallObjectBudget() throws Exception {
        var files = fixture(2);
        units(files, unit(30, "10", "10", "<ofd:PageBlock/>".repeat(9_999)));
        for (int i = 0; i < 2; i++) page(files, i, "", composite(40 + i, 30, "0 0 10 10", "Visible=\"false\""));
        assertThat(render(files)).hasSize(2);
        replace(files, "Doc_0/Resources.xml", "</ofd:Content>", "<ofd:PageBlock/></ofd:Content>");
        assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
    }

    @Test
    void boundsLiveTransparentSurfacesAndCumulativePixelsAcrossPages() throws Exception {
        var files = fixture(1); replace(files, "Doc_0/Document.xml", "20 20", "400 400");
        units(files, chain(3, "400", "Alpha=\"128\""));
        page(files, 0, "", composite(40, 1000, "0 0 400 400", "Alpha=\"128\""));
        assertThat(render(files)).hasSize(1);
        var deep = fixture(1); replace(deep, "Doc_0/Document.xml", "20 20", "400 400");
        units(deep, chain(4, "400", "Alpha=\"128\""));
        page(deep, 0, "", composite(40, 1000, "0 0 400 400", "Alpha=\"128\""));
        assertThatThrownBy(() -> render(deep)).isInstanceOf(IOException.class);
        var repeated = fixture(2); replace(repeated, "Doc_0/Document.xml", "20 20", "300 300");
        units(repeated, unit(30, "300", "300", ""));
        String object = composite(40, 30, "0 0 300 300", "Alpha=\"128\"");
        page(repeated, 0, "", object.repeat(6)); page(repeated, 1, "", object.repeat(7));
        assertThat(render(repeated)).hasSize(2);
        page(repeated, 0, "", object.repeat(7));
        assertThatThrownBy(() -> render(repeated)).isInstanceOf(IOException.class);
    }

    @Test
    void clipsHugeDeclaredResourcesToThePageBeforeAllocatingTransparentSurface() throws Exception {
        var files = fixture(1);
        units(files, unit(30, "1000000", "1000000", path("0 0 10 10", "255 0 0", "")));
        page(files, 0, "", composite(40, 30, "0 0 1000000 1000000", "Alpha=\"128\""));
        assertThat(pixel(render(files).get(0), 3, 3)).isEqualTo(new Color(255, 127, 127).getRGB());
    }

    private static String blocks(int count, String content) {
        return "<ofd:PageBlock>".repeat(count) + content + "</ofd:PageBlock>".repeat(count);
    }

    static String chain(int count, String size, String attributes) {
        var definitions = new StringBuilder();
        for (int i = 0; i < count; i++) definitions.append(unit(1000 + i, size, size,
                i + 1 == count ? "" : composite(2000 + i, 1001 + i, "0 0 " + size + " " + size, attributes)));
        return definitions.toString();
    }

    private static String media(int id, String file) {
        return "<ofd:MultiMedias><ofd:MultiMedia ID=\"" + id + "\" Type=\"Image\" Format=\"PNG\"><ofd:MediaFile>"
                + file + "</ofd:MediaFile></ofd:MultiMedia></ofd:MultiMedias>";
    }

    private static byte[] solidImage(Color color) throws IOException {
        var image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, color.getRGB());
        var bytes = new ByteArrayOutputStream(); ImageIO.write(image, "PNG", bytes); image.flush();
        return bytes.toByteArray();
    }

    private List<byte[]> render(Map<String, byte[]> files) throws IOException {
        Path source = Files.write(directory.resolve(UUID.randomUUID() + ".ofd"), zip(files));
        return InvoiceOfdRenderer.render(InvoiceOfdArchive.read(source));
    }

    static void units(Map<String, byte[]> files, String definitions) {
        replace(files, "Doc_0/Resources.xml", "</ofd:Res>", "<ofd:CompositeGraphicUnits>" + definitions + "</ofd:CompositeGraphicUnits></ofd:Res>");
    }

    static String unit(int id, String width, String height, String content) {
        return "<ofd:CompositeGraphicUnit ID=\"" + id + "\" Width=\"" + width + "\" Height=\"" + height
                + "\"><ofd:Content>" + content + "</ofd:Content></ofd:CompositeGraphicUnit>";
    }

    static String composite(int id, int resource, String boundary, String extra) {
        return "<ofd:CompositeObject ID=\"" + id + "\" ResourceID=\"" + resource + "\" Boundary=\"" + boundary + "\" " + extra + "/>";
    }
}
