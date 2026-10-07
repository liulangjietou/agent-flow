package io.agentflow.agent;

import java.awt.Color;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.ByteArrayInputStream;
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
 * 以最终像素验证 OFD 样式覆盖、物理描边和复合裁剪，避免默认值掩盖缺失内容。
 * @author owlzhangfq@gmail.com
 */
class InvoiceOfdDrawingTest {
    @TempDir Path directory;

    @Test
    void cascadesIndividualPropertiesThroughLayerReferencesAndObjectOverrides() throws Exception {
        var files = drawingFixture();
        resource(files, "DrawParams", "<ofd:DrawParam ID=\"30\" LineWidth=\"1\"><ofd:FillColor Value=\"255 0 0\"/><ofd:StrokeColor Value=\"0 0 255\"/></ofd:DrawParam>"
                + "<ofd:DrawParam ID=\"31\" Relative=\"30\"><ofd:FillColor Value=\"0 255 0\"/></ofd:DrawParam>"
                + "<ofd:DrawParam ID=\"32\" Relative=\"31\" LineWidth=\"2\"/>");
        page(files, 0, "", rectangle("1 1 10 10", "DrawParam=\"32\"", "")
                + rectangle("13 1 10 10", "DrawParam=\"32\"", "<ofd:FillColor Value=\"0 0 255\"/>")
                + rectangle("25 1 10 10", "", "")
                + vector("0 20 40 10", "DrawParam=\"32\"", "", "M 2 5 L 35 5"));
        replace(files, "Doc_0/Pages/P0.xml", "<ofd:Layer ID=\"100\">", "<ofd:Layer ID=\"100\" DrawParam=\"30\">");
        byte[] image = render(files);
        assertThat(pixel(image, 5, 5)).isEqualTo(Color.GREEN.getRGB());
        assertThat(pixel(image, 17, 5)).isEqualTo(Color.BLUE.getRGB());
        assertThat(pixel(image, 29, 5)).isEqualTo(Color.RED.getRGB());
        assertThat(pixel(image, 4, 25.8)).isEqualTo(Color.BLUE.getRGB());
        assertThat(pixel(image, 4, 26.4)).isEqualTo(Color.WHITE.getRGB());
    }

    @Test
    void convertsPhysicalMiterCutoffInsteadOfTreatingItAsAWidthRatio() throws Exception {
        var files = drawingFixture();
        String corner = "M 2 10 L 10 2 L 18 10";
        page(files, 0, "", vector("0 0 20 20", "LineWidth=\"2\" MiterLimit=\"2.5\"", "", corner)
                + vector("20 0 20 20", "LineWidth=\"2\" MiterLimit=\"3\"", "", corner));
        byte[] image = render(files);
        // 采样点离开斜边的抗锯齿像素，但仍处于斜接与切角之间的差异区域。
        assertThat(pixel(image, 10, 1)).isEqualTo(Color.WHITE.getRGB());
        assertThat(pixel(image, 30, 1)).isEqualTo(Color.BLACK.getRGB());
    }

    @Test
    void preservesButtRoundAndSquareEndpointGeometry() throws Exception {
        var files = drawingFixture();
        page(files, 0, "", vector("0 0 12 12", "LineWidth=\"2\" Cap=\"Butt\"", "", "M 3 5 L 9 5")
                + vector("12 0 12 12", "LineWidth=\"2\" Cap=\"Round\"", "", "M 3 5 L 9 5")
                + vector("24 0 12 12", "LineWidth=\"2\" Cap=\"Square\"", "", "M 3 5 L 9 5"));
        byte[] image = render(files);
        assertThat(pixel(image, 2.2, 5)).isEqualTo(Color.WHITE.getRGB());
        assertThat(pixel(image, 14.2, 5)).isEqualTo(Color.BLACK.getRGB());
        assertThat(pixel(image, 14.2, 4.2)).isEqualTo(Color.WHITE.getRGB());
        assertThat(pixel(image, 26.2, 4.2)).isEqualTo(Color.BLACK.getRGB());
    }

    @Test
    void appliesMultiPartDashPatternsAndNormalizesEquivalentOffsets() throws Exception {
        var files = drawingFixture();
        page(files, 0, "", vector("0 0 40 10", "LineWidth=\"1\" DashPattern=\"4 2 1 2\" DashOffset=\"1\"", "", "M 1 5 L 39 5")
                + vector("0 10 40 10", "LineWidth=\"1\" DashPattern=\"4 2 1 2\" DashOffset=\"-8\"", "", "M 1 5 L 39 5"));
        byte[] image = render(files);
        for (int y : new int[]{5, 15}) {
            assertThat(pixel(image, 2, y)).isEqualTo(Color.BLACK.getRGB());
            assertThat(pixel(image, 5, y)).isEqualTo(Color.WHITE.getRGB());
            assertThat(pixel(image, 6.5, y)).isEqualTo(Color.BLACK.getRGB());
            assertThat(pixel(image, 8, y)).isEqualTo(Color.WHITE.getRGB());
        }
    }

    @Test
    void usesOnePixelForHairlinesAndTwoForPositiveThinLinesAfterUniformScaling() throws Exception {
        var files = drawingFixture();
        page(files, 0, "", vector("0 0 20 15", "LineWidth=\"0\" CTM=\"2 0 0 2 0 0\"", "", "M 1 3 L 8 3")
                + vector("0 20 20 15", "LineWidth=\"0.01\" CTM=\"2 0 0 2 0 0\"", "", "M 1 3 L 8 3"));
        var image = ImageIO.read(new ByteArrayInputStream(render(files)));
        try {
            assertThat(columnCoverage(image, 10, 0, 15)).isBetween(0.8, 1.2);
            assertThat(columnCoverage(image, 10, 20, 35)).isBetween(1.8, 2.2);
        } finally { image.flush(); }
    }

    @Test
    void keepsTextStrokeDefaultTransparentAndSupportsExplicitStrokeColour() throws Exception {
        var files = drawingFixture();
        String code = "<ofd:TextCode X=\"1\" Y=\"6\">A</ofd:TextCode>";
        page(files, 0, "", text("Stroke=\"true\" Fill=\"false\" LineWidth=\"0.5\"", code)
                + text("Stroke=\"true\" Fill=\"false\" LineWidth=\"0.5\"", "<ofd:StrokeColor Value=\"0 0 255\"/>" + code).replace("Boundary=\"0 0 20 20\"", "Boundary=\"20 0 20 20\""));
        byte[] image = render(files);
        assertThat(pixel(image, 2, 3.2)).isEqualTo(Color.WHITE.getRGB());
        assertThat(pixel(image, 22, 3.2)).isEqualTo(Color.BLUE.getRGB());
        assertThat(pixel(image, 22, 4.5)).isEqualTo(Color.WHITE.getRGB());
    }

    @Test
    void resolvesDefaultColourSpaceBitDepthPaletteAndValuePrecedence() throws Exception {
        var files = drawingFixture();
        resource(files, "ColorSpaces", "<ofd:ColorSpace ID=\"30\" Type=\"GRAY\" BitsPerComponent=\"4\"/>"
                + "<ofd:ColorSpace ID=\"31\" Type=\"RGB\" BitsPerComponent=\"16\"><ofd:Palette><ofd:CV>#FFFF 0 #8000</ofd:CV></ofd:Palette></ofd:ColorSpace>");
        replace(files, "Doc_0/Document.xml", "</ofd:CommonData>", "<ofd:DefaultCS>30</ofd:DefaultCS></ofd:CommonData>");
        page(files, 0, "", rectangle("0 0 10 10", "", "<ofd:FillColor Value=\"#8\"/>")
                + rectangle("12 0 10 10", "", "<ofd:FillColor ColorSpace=\"31\" Index=\"0\"/>")
                + rectangle("24 0 10 10", "", "<ofd:FillColor ColorSpace=\"31\" Index=\"99\" Value=\"0 #FFFF 0\"/>"));
        byte[] image = render(files);
        assertThat(pixel(image, 4, 4)).isEqualTo(new Color(136, 136, 136).getRGB());
        assertThat(pixel(image, 16, 4)).isEqualTo(new Color(255, 0, 128).getRGB());
        assertThat(pixel(image, 28, 4)).isEqualTo(Color.GREEN.getRGB());
    }

    @Test
    void convertsCmykAndLoadsAnExplicitPackageIccProfile() throws Exception {
        var files = drawingFixture();
        resource(files, "ColorSpaces", "<ofd:ColorSpace ID=\"30\" Type=\"CMYK\"/>"
                + "<ofd:ColorSpace ID=\"31\" Type=\"RGB\" Profile=\"srgb.icc\"/>");
        files.put("Doc_0/Res/srgb.icc", ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData());
        page(files, 0, "", rectangle("0 0 10 10", "", "<ofd:FillColor ColorSpace=\"30\" Value=\"255 0 0 0\"/>")
                + rectangle("12 0 10 10", "", "<ofd:FillColor ColorSpace=\"31\" Value=\"255 0 0\"/>"));
        byte[] image = render(files);
        Color cyan = new Color(pixel(image, 4, 4));
        assertThat(cyan.getRed()).isLessThan(50); assertThat(cyan.getBlue()).isGreaterThan(150); assertThat(cyan.getGreen()).isGreaterThan(120);
        assertThat(pixel(image, 16, 4)).isEqualTo(Color.RED.getRGB());
        files.put("Doc_0/Res/srgb.icc", new byte[]{1, 2, 3});
        assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
    }

    @Test
    void boundsProfileLoadsBeforeNativeConversion() throws Exception {
        byte[] small = ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();
        assertThat(pixel(render(profileFixture(32, small)), 4, 4)).isEqualTo(Color.RED.getRGB());
        assertThatThrownBy(() -> render(profileFixture(33, small))).isInstanceOf(IOException.class);
    }

    @Test
    void boundsTotalProfileBytesBeforeNativeConversion() throws Exception {
        byte[] small = ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();
        // 有效配置末尾使用不可压缩填充，确保命中颜色预算而非 ZIP 压缩比限制。
        byte[] large = new byte[1024 * 1024];
        new java.util.Random(21).nextBytes(large);
        System.arraycopy(small, 0, large, 0, small.length);
        java.nio.ByteBuffer.wrap(large).putInt(large.length);
        assertThat(pixel(render(profileFixture(8, large)), 4, 4)).isEqualTo(Color.RED.getRGB());
        assertThatThrownBy(() -> render(profileFixture(9, large))).isInstanceOf(IOException.class);
    }

    @Test
    void rejectsUnrepresentableStrokeWidthsBeforeJavaExpansion() {
        assertThatThrownBy(() -> InvoiceOfdStyle.DEFAULT.stroke(new java.awt.geom.Line2D.Double(1, 1, 10, 1),
                java.awt.geom.AffineTransform.getScaleInstance(1e-40, 1e-40))).isInstanceOf(IOException.class);
    }

    @Test
    void preservesNonuniformThickStrokesAndRejectsUnimplementedDeviceMinimums() throws Exception {
        var files = drawingFixture();
        page(files, 0, "", vector("0 0 20 10", "LineWidth=\"2\" CTM=\"2 0 0 1 0 0\"", "", "M 2 5 L 8 5")
                + vector("20 0 20 10", "LineWidth=\"2\" CTM=\"2 0 0 1 0 0\"", "", "M 5 2 L 5 8"));
        byte[] image = render(files);
        assertThat(pixel(image, 8, 5.7)).isEqualTo(Color.BLACK.getRGB());
        assertThat(pixel(image, 8, 6.4)).isEqualTo(Color.WHITE.getRGB());
        assertThat(pixel(image, 31.5, 5)).isEqualTo(Color.BLACK.getRGB());
        assertThat(pixel(image, 32.5, 5)).isEqualTo(Color.WHITE.getRGB());
        replace(files, "Doc_0/Pages/P0.xml", "LineWidth=\"2\"", "LineWidth=\"0.01\"");
        assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
    }

    @Test
    void unionsAreasAndIntersectsClipsBeforeTheObjectsOwnTransform() throws Exception {
        var files = drawingFixture();
        String clips = "<ofd:Clips><ofd:Clip>" + area("CTM=\"1 0 0 1 2 0\"", clipRectangle("0 0 10 10"))
                + area("CTM=\"1 0 0 1 18 0\"", clipRectangle("0 0 10 10")) + "</ofd:Clip><ofd:Clip>"
                + area("", clipRectangle("0 0 30 5")) + "</ofd:Clip></ofd:Clips>";
        page(files, 0, "", rectangle("5 5 30 30", "CTM=\"3 0 0 3 0 0\"", "<ofd:FillColor Value=\"255 0 0\"/>" + clips));
        byte[] image = render(files);
        assertThat(pixel(image, 8, 8)).isEqualTo(Color.RED.getRGB());
        assertThat(pixel(image, 20, 8)).isEqualTo(Color.WHITE.getRGB());
        assertThat(pixel(image, 25, 8)).isEqualTo(Color.RED.getRGB());
        assertThat(pixel(image, 8, 12)).isEqualTo(Color.WHITE.getRGB());
    }

    @Test
    void honorsExplicitClipTransformFlagAndDoesNotLeakTheClip() throws Exception {
        var files = drawingFixture();
        String clips = "<ofd:Clips TransFlag=\"true\"><ofd:Clip>" + area("", clipRectangle("0 0 3 10")) + "</ofd:Clip></ofd:Clips>";
        page(files, 0, "", rectangle("0 0 20 20", "CTM=\"2 0 0 2 0 0\"", "<ofd:FillColor Value=\"255 0 0\"/>" + clips)
                + rectangle("22 0 10 10", "", "<ofd:FillColor Value=\"0 0 255\"/>"));
        byte[] image = render(files);
        assertThat(pixel(image, 5, 5)).isEqualTo(Color.RED.getRGB());
        assertThat(pixel(image, 7, 5)).isEqualTo(Color.WHITE.getRGB());
        assertThat(pixel(image, 25, 5)).isEqualTo(Color.BLUE.getRGB());
    }

    @Test
    void clipsWithTextAndTheStrokedAreaUsingItsOwnDrawParameters() throws Exception {
        var files = drawingFixture();
        resource(files, "DrawParams", "<ofd:DrawParam ID=\"30\" LineWidth=\"2\"/>");
        String textClip = text("", "<ofd:TextCode X=\"1\" Y=\"6\">A</ofd:TextCode>")
                .replace("TextObject", "Text").replace(" ID=\"21\"", "");
        String band = "<ofd:Path Boundary=\"0 0 10 10\"><ofd:AbbreviatedData>M 1 5 L 9 5</ofd:AbbreviatedData></ofd:Path>";
        page(files, 0, "", rectangle("0 0 20 20", "", "<ofd:FillColor Value=\"255 0 0\"/><ofd:Clips><ofd:Clip>" + area("", textClip) + "</ofd:Clip></ofd:Clips>")
                + rectangle("20 0 10 10", "", "<ofd:FillColor Value=\"0 0 255\"/><ofd:Clips><ofd:Clip>" + area("DrawParam=\"30\"", band) + "</ofd:Clip></ofd:Clips>"));
        byte[] image = render(files);
        assertThat(pixel(image, 2, 4)).isEqualTo(Color.RED.getRGB());
        assertThat(pixel(image, 5, 4)).isEqualTo(Color.WHITE.getRGB());
        assertThat(pixel(image, 25, 5.7)).isEqualTo(Color.BLUE.getRGB());
        assertThat(pixel(image, 25, 7)).isEqualTo(Color.WHITE.getRGB());
    }

    @Test
    void retainsBoundaryAndTransformOrderForNestedClips() throws Exception {
        var files = drawingFixture();
        String path = "<ofd:Path Boundary=\"3 4 12 12\" CTM=\"2 0 0 2 0 0\" Fill=\"true\" Stroke=\"false\">"
                + "<ofd:AbbreviatedData>M 0 0 L 10 0 L 10 10 L 0 10 C</ofd:AbbreviatedData>"
                + "<ofd:Clips><ofd:Clip>" + area("", clipRectangle("0 0 3 10")) + "</ofd:Clip></ofd:Clips></ofd:Path>";
        String clip = "<ofd:Clips><ofd:Clip>" + area("CTM=\"1 0 0 1 2 1\"", path) + "</ofd:Clip></ofd:Clips>";
        String transformed = "<ofd:Clips><ofd:Clip>" + area("CTM=\"1 0 0 1 2 1\"", path.replace("<ofd:Clips>", "<ofd:Clips TransFlag=\"true\">")) + "</ofd:Clip></ofd:Clips>";
        page(files, 0, "", rectangle("0 0 20 40", "CTM=\"2 0 0 4 0 0\"", "<ofd:FillColor Value=\"255 0 0\"/>" + clip)
                + rectangle("20 0 20 40", "CTM=\"2 0 0 4 0 0\"", "<ofd:FillColor Value=\"0 0 255\"/>" + transformed));
        byte[] image = render(files);
        assertThat(pixel(image, 6, 6)).isEqualTo(Color.RED.getRGB());
        assertThat(pixel(image, 9, 6)).isEqualTo(Color.WHITE.getRGB());
        assertThat(pixel(image, 29, 6)).isEqualTo(Color.BLUE.getRGB());
        assertThat(pixel(image, 32, 6)).isEqualTo(Color.WHITE.getRGB());
        assertThat(pixel(image, 29, 18)).isEqualTo(Color.WHITE.getRGB());
    }

    @Test
    void boundsClipCountDepthAndBooleanWork() throws Exception {
        var files = drawingFixture();
        String area = area("", clipRectangle("0 0 10 10"));
        page(files, 0, "", rectangle("0 0 10 10", "", "<ofd:FillColor Value=\"255 0 0\"/><ofd:Clips><ofd:Clip>" + area.repeat(256) + "</ofd:Clip></ofd:Clips>"));
        assertThat(pixel(render(files), 4, 4)).isEqualTo(Color.RED.getRGB());
        replace(files, "Doc_0/Pages/P0.xml", "</ofd:Clip>", area + "</ofd:Clip>");
        assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);

        String nested = "";
        for (int depth = 1; depth <= 9; depth++) {
            String path = clipRectangle("0 0 10 10").replace("</ofd:Path>", nested + "</ofd:Path>");
            nested = "<ofd:Clips><ofd:Clip>" + area("", path) + "</ofd:Clip></ofd:Clips>";
            if (depth < 8) continue;
            page(files, 0, "", rectangle("0 0 10 10", "", "<ofd:FillColor Value=\"255 0 0\"/>" + nested));
            if (depth == 8) assertThat(pixel(render(files), 4, 4)).isEqualTo(Color.RED.getRGB());
            else assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
        }
        String complex = clipRectangle("0 0 10 10").replace("M 0 0 L 10 0 L 10 10 L 0 10 C", "M 0 0 " + "L 10 10 ".repeat(2000));
        page(files, 0, "", rectangle("0 0 10 10", "", "<ofd:Clips><ofd:Clip>" + area("", complex) + "</ofd:Clip></ofd:Clips>"));
        assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
    }

    @Test
    void rejectsCyclicStylesUnboundedDashesAndMalformedClipping() throws Exception {
        for (String style : List.of("Relative=\"30\"", "Relative=\"99\"", "DashPattern=\"0 0\"", "DashPattern=\"0.0000000001 0.0000000001\"", "LineWidth=\"-1\"", "Cap=\"Unknown\"")) {
            var files = drawingFixture(); resource(files, "DrawParams", "<ofd:DrawParam ID=\"30\" " + style + "/>");
            page(files, 0, "", vector("0 0 20 20", "DrawParam=\"30\"", "", "M 1 5 L 19 5"));
            assertThatThrownBy(() -> render(files)).as(style).isInstanceOf(IOException.class);
        }
        for (String clip : List.of("<ofd:Clips><ofd:Clip/></ofd:Clips>", "<ofd:Clips><ofd:Clip><ofd:Area/></ofd:Clip></ofd:Clips>")) {
            var files = drawingFixture(); page(files, 0, "", rectangle("0 0 20 20", "", "<ofd:FillColor Value=\"255 0 0\"/>" + clip));
            assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
        }
    }

    private byte[] render(Map<String, byte[]> files) throws IOException {
        Path source = Files.write(directory.resolve(UUID.randomUUID() + ".ofd"), zip(files));
        return InvoiceOfdRenderer.render(InvoiceOfdArchive.read(source)).get(0);
    }
    private static Map<String, byte[]> drawingFixture() throws IOException {
        var files = fixture(1); replace(files, "Doc_0/Document.xml", "0 0 20 20", "0 0 40 40"); return files;
    }
    private static Map<String, byte[]> profileFixture(int count, byte[] profile) throws IOException {
        var files = drawingFixture();
        var definitions = new StringBuilder(); var objects = new StringBuilder();
        for (int i = 0; i < count; i++) {
            int id = 30 + i;
            definitions.append("<ofd:ColorSpace ID=\"").append(id).append("\" Type=\"RGB\" Profile=\"srgb.icc\"/>");
            objects.append(rectangle("0 0 10 10", "", "<ofd:FillColor ColorSpace=\"" + id + "\" Value=\"255 0 0\"/>"));
        }
        resource(files, "ColorSpaces", definitions.toString());
        files.put("Doc_0/Res/srgb.icc", profile);
        page(files, 0, "", objects.toString()); return files;
    }
    private static void resource(Map<String, byte[]> files, String group, String content) {
        replace(files, "Doc_0/Resources.xml", "</ofd:Res>", "<ofd:" + group + ">" + content + "</ofd:" + group + "></ofd:Res>");
    }
    private static String vector(String boundary, String attributes, String children, String path) {
        return "<ofd:PathObject ID=\"20\" Boundary=\"" + boundary + "\" " + attributes + ">" + children + "<ofd:AbbreviatedData>" + path + "</ofd:AbbreviatedData></ofd:PathObject>";
    }
    private static String rectangle(String boundary, String attributes, String children) {
        return vector(boundary, "Fill=\"true\" Stroke=\"false\" " + attributes, children, "M 0 0 L 10 0 L 10 10 L 0 10 C");
    }
    private static String area(String attributes, String content) { return "<ofd:Area " + attributes + ">" + content + "</ofd:Area>"; }
    private static String clipRectangle(String boundary) {
        String[] box = boundary.split(" ");
        return "<ofd:Path Boundary=\"" + boundary + "\" Fill=\"true\" Stroke=\"false\"><ofd:AbbreviatedData>M 0 0 L " + box[2] + " 0 L " + box[2] + " " + box[3] + " L 0 " + box[3] + " C</ofd:AbbreviatedData></ofd:Path>";
    }
    private static double columnCoverage(java.awt.image.BufferedImage image, double x, double top, double bottom) {
        int column = (int) (x * 144 / 25.4); double result = 0;
        for (int row = (int) (top * 144 / 25.4); row < bottom * 144 / 25.4; row++) result += 1 - new Color(image.getRGB(column, row)).getRed() / 255.0;
        return result;
    }
}
