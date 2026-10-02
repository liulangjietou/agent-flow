package io.agentflow.agent;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
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
import static io.agentflow.agent.InvoiceOfdArchiveTest.bytes;
import static io.agentflow.agent.InvoiceOfdArchiveTest.zip;
import static org.assertj.core.api.Assertions.*;

/**
 * 从真实 ZIP、XML、字体和图片读到最终像素，验证全部页面和错误时不交付部分结果。
 * @author owlzhangfq@gmail.com
 */
class InvoiceOfdRendererTest {
    private static final String NS = "http://www.ofdspec.org/2016";
    @TempDir Path directory;

    @Test
    void preservesAllDocumentAndPageOrderAndPhysicalOrigins() throws Exception {
        var files = fixture(2);
        page(files, 0, "<ofd:Area><ofd:PhysicalBox>5 7 20 20</ofd:PhysicalBox></ofd:Area>", path("5 7 10 10", "255 0 0", ""));
        page(files, 1, "", path("0 0 10 10", "0 0 255", ""));
        replace(files, "OFD.xml", "</ofd:OFD>", "<ofd:DocBody><ofd:DocRoot>Doc_1/Document.xml</ofd:DocRoot></ofd:DocBody></ofd:OFD>");
        files.put("Doc_1/Document.xml", xml("Document", "<ofd:CommonData><ofd:MaxUnitID>100</ofd:MaxUnitID><ofd:PageArea><ofd:PhysicalBox>0 0 30 10</ofd:PhysicalBox></ofd:PageArea></ofd:CommonData><ofd:Pages><ofd:Page ID=\"1\" BaseLoc=\"P.xml\"/></ofd:Pages>"));
        files.put("Doc_1/P.xml", xml("Page", "<ofd:Content><ofd:Layer ID=\"2\">"+path("0 0 10 10", "0 255 0", "")+"</ofd:Layer></ofd:Content>"));
        var pages = render(files); assertThat(pages).hasSize(3);
        assertThat(pixel(pages.get(0), 2, 2)).isEqualTo(Color.RED.getRGB());
        assertThat(pixel(pages.get(1), 2, 2)).isEqualTo(Color.BLUE.getRGB());
        assertThat(pixel(pages.get(2), 2, 2)).isEqualTo(Color.GREEN.getRGB());
        assertThat(decode(pages.get(2)).getWidth()).isEqualTo(171);
        assertThat(decode(pages.get(2)).getHeight()).isEqualTo(57);
    }

    @Test
    void clipsAtBoundaryBeforeObjectTransformAndDoesNotLeakClipOrColorToSibling() throws Exception {
        var files = fixture(1);
        page(files, 0, "", path("5 5 5 5", "255 0 0", "CTM=\"2 0 0 2 0 0\"") + path("12 12 5 5", "0 0 255", ""));
        byte[] image=render(files).get(0);
        assertThat(pixel(image, 6, 6)).isEqualTo(Color.RED.getRGB());
        assertThat(pixel(image, 4, 6)).isEqualTo(Color.WHITE.getRGB());
        assertThat(pixel(image, 11, 6)).isEqualTo(Color.WHITE.getRGB());
        assertThat(pixel(image, 13, 13)).isEqualTo(Color.BLUE.getRGB());
    }

    @Test
    void positionsUnicodeCodePointsAndInheritsPreviousTextCodeOrigin() throws Exception {
        var files = fixture(1);
        page(files, 0, "", text("", "<ofd:TextCode X=\"1\" Y=\"6\" DeltaX=\"g 2 4\">A中𠀀</ofd:TextCode><ofd:TextCode Y=\"12\">A</ofd:TextCode>"));
        byte[] image=render(files).get(0);
        for (int x : new int[]{2,6,10}) assertThat(pixel(image,x,4)).isEqualTo(Color.BLACK.getRGB());
        assertThat(pixel(image,2,10)).isEqualTo(Color.BLACK.getRGB());
        assertThat(pixel(image,10,10)).isEqualTo(Color.WHITE.getRGB());
    }

    @Test
    void decodesEscapedSurrogatesAndRotatesTheGlyphWithoutRotatingItsOrigin() throws Exception {
        var files = fixture(1);
        page(files, 0, "", text("HScale=\"0.5\" CharDirection=\"90\"", "<ofd:TextCode X=\"5\" Y=\"5\" DeltaX=\"5\">\\0041\\D840\\DC00</ofd:TextCode>"));
        byte[] image=render(files).get(0);
        assertThat(pixel(image,6,5.5)).isEqualTo(Color.BLACK.getRGB());
        assertThat(pixel(image,11,5.5)).isEqualTo(Color.BLACK.getRGB());
        assertThat(pixel(image,5.5,3)).isEqualTo(Color.WHITE.getRGB());
    }

    @Test
    void bindsGlyphTransformGroupsToTheFollowingTextCodeAndUsesResultingGlyphPositions() throws Exception {
        var files = fixture(1);
        page(files, 0, "", text("", "<ofd:CGTransform CodePosition=\"0\" CodeCount=\"2\" GlyphCount=\"3\"><ofd:Glyphs>2 3 4</ofd:Glyphs></ofd:CGTransform><ofd:TextCode X=\"1\" Y=\"6\" DeltaX=\"4 4\">BB</ofd:TextCode>"
                + "<ofd:CGTransform CodePosition=\"0\"><ofd:Glyphs>1</ofd:Glyphs></ofd:CGTransform><ofd:TextCode Y=\"12\" DeltaX=\"4\">BA</ofd:TextCode>"));
        byte[] image=render(files).get(0);
        for (int x : new int[]{2,6,10}) assertThat(pixel(image,x,4)).isEqualTo(Color.BLACK.getRGB());
        assertThat(pixel(image,2,10)).isEqualTo(Color.WHITE.getRGB());
        assertThat(pixel(image,6,10)).isEqualTo(Color.BLACK.getRGB());
    }

    @Test
    void honorsZeroAndPartialAlphaWithoutChangingLaterObjects() throws Exception {
        var files = fixture(1);
        page(files, 0, "", path("0 0 5 5", "255 0 0", "Alpha=\"0\"") + path("6 0 5 5", "0 0 0", "Alpha=\"128\"") + path("12 0 5 5", "0 0 255", ""));
        byte[] image=render(files).get(0);
        assertThat(pixel(image,2,2)).isEqualTo(Color.WHITE.getRGB());
        Color mixed=new Color(pixel(image,8,2));
        assertThat(mixed.getRed()).isBetween(126,128); assertThat(mixed.getGreen()).isEqualTo(mixed.getRed());
        assertThat(pixel(image,14,2)).isEqualTo(Color.BLUE.getRGB());
    }

    @Test
    void decodesPackageImageUsingNormalizedImageCoordinates() throws Exception {
        var files = fixture(1); var image=new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB);
        image.setRGB(0,0,Color.RED.getRGB()); image.setRGB(1,0,Color.BLUE.getRGB()); image.setRGB(0,1,Color.GREEN.getRGB()); image.setRGB(1,1,Color.BLACK.getRGB());
        var encoded=new ByteArrayOutputStream(); ImageIO.write(image,"PNG",encoded); image.flush();
        files.put("Doc_0/Res/picture.png",encoded.toByteArray());
        replace(files,"Doc_0/Resources.xml","</ofd:Res>","<ofd:MultiMedias><ofd:MultiMedia ID=\"11\" Type=\"Image\" Format=\"PNG\"><ofd:MediaFile>picture.png</ofd:MediaFile></ofd:MultiMedia></ofd:MultiMedias></ofd:Res>");
        page(files,0,"","<ofd:ImageObject ID=\"20\" ResourceID=\"11\" Boundary=\"2 2 10 10\" CTM=\"10 0 0 10 0 0\"/>");
        byte[] result=render(files).get(0);
        assertThat(pixel(result,3,3)).isEqualTo(Color.RED.getRGB());
        assertThat(pixel(result,11,3)).isEqualTo(Color.BLUE.getRGB());
        assertThat(pixel(result,3,11)).isEqualTo(Color.GREEN.getRGB());
        assertThat(pixel(result,11,11)).isEqualTo(Color.BLACK.getRGB());
    }

    @Test
    void failsWholeOutputWhenALaterPageContainsInvalidGeometryOrMissingGlyph() throws Exception {
        for (String invalid : List.of(path("0 0 10 10","0 0 0","").replace("L 10 0","Z 10 0"),text("","<ofd:TextCode X=\"1\" Y=\"6\">B</ofd:TextCode>"))) {
            var files=fixture(2);page(files,0,"",path("0 0 10 10","255 0 0",""));page(files,1,"",invalid);
            assertThatThrownBy(()->render(files)).isInstanceOf(IOException.class);
        }
    }

    @Test
    void rejectsUnknownVisualObjectsUnsupportedAttributesAndAmbiguousResources() throws Exception {
        for (String invalid : List.of("<ofd:VideoObject ID=\"20\" Boundary=\"0 0 10 10\"/>", path("0 0 10 10","0 0 0","UnknownPaint=\"true\""), text("Italic=\"true\"","<ofd:TextCode X=\"1\" Y=\"6\">A</ofd:TextCode>"))) {
            var files=fixture(1);page(files,0,"",invalid);assertThatThrownBy(()->render(files)).isInstanceOf(IOException.class);
        }
        var duplicate=fixture(1);replace(duplicate,"Doc_0/Resources.xml","</ofd:Fonts>","<ofd:Font ID=\"10\" FontName=\"AgentFlowSyntheticA\"><ofd:FontFile>a.ttf</ofd:FontFile></ofd:Font></ofd:Fonts>");
        assertThatThrownBy(()->render(duplicate)).isInstanceOf(IOException.class);
    }

    @Test
    void rejectsBrokenTextPositionsMalformedTransformsAndUnboundedRepetition() throws Exception {
        for(String content:List.of("<ofd:TextCode Y=\"6\">A</ofd:TextCode>","<ofd:TextCode X=\"1\" Y=\"6\" DeltaX=\"g 999999999 4\">AA</ofd:TextCode>","<ofd:TextCode X=\"1\" Y=\"6\" DeltaX=\"1\">AAA</ofd:TextCode>","<ofd:TextCode X=\"1\" Y=\"6\">\\D800</ofd:TextCode>","<ofd:CGTransform CodePosition=\"0\" GlyphCount=\"2\"><ofd:Glyphs>2</ofd:Glyphs></ofd:CGTransform><ofd:TextCode X=\"1\" Y=\"6\">A</ofd:TextCode>","<ofd:TextCode X=\"1\" Y=\"6\">A</ofd:TextCode><ofd:CGTransform CodePosition=\"0\"><ofd:Glyphs>2</ofd:Glyphs></ofd:CGTransform>")) {
            var files=fixture(1);page(files,0,"",text("",content));assertThatThrownBy(()->render(files)).isInstanceOf(IOException.class);
        }
    }

    @Test
    void boundsRasterAllocationAndRejectsReferencedButUnreadableFontData() throws Exception {
        var large=fixture(1);replace(large,"Doc_0/Document.xml","0 0 20 20","0 0 1000 1000");
        assertThatThrownBy(()->render(large)).isInstanceOf(IOException.class);
        var font=fixture(1);font.put("Doc_0/Res/a.ttf",new byte[]{1,2,3});page(font,0,"",text("","<ofd:TextCode X=\"1\" Y=\"6\">A</ofd:TextCode>"));
        assertThatThrownBy(()->render(font)).isInstanceOf(IOException.class);
    }

    @Test
    void paintsOrderedLayersAndNestedPageBlocks() throws Exception {
        var files = fixture(1);
        files.put("Doc_0/Pages/P0.xml", xml("Page", "<ofd:Content>"
                + "<ofd:Layer ID=\"100\" Type=\"Foreground\">" + path("0 0 5 5", "0 0 255", "").replace("ID=\"20\"", "ID=\"200\"") + "</ofd:Layer>"
                + "<ofd:Layer ID=\"101\"><ofd:PageBlock ID=\"201\">" + path("0 0 8 8", "0 255 0", "").replace("ID=\"20\"", "ID=\"202\"") + "</ofd:PageBlock></ofd:Layer>"
                + "<ofd:Layer ID=\"102\" Type=\"Background\">" + path("0 0 10 10", "255 0 0", "").replace("ID=\"20\"", "ID=\"203\"") + "</ofd:Layer>"
                + "</ofd:Content>"));
        byte[] image = render(files).get(0);
        assertThat(pixel(image, 2, 2)).isEqualTo(Color.BLUE.getRGB());
        assertThat(pixel(image, 6, 6)).isEqualTo(Color.GREEN.getRGB());
        assertThat(pixel(image, 9, 9)).isEqualTo(Color.RED.getRGB());
    }

    @Test
    void appliesPathFillRuleAndCombinesColorAlphaWithObjectAlpha() throws Exception {
        var files = fixture(1);
        String rings = "M 0 0 L 8 0 L 8 8 L 0 8 C M 2 2 L 6 2 L 6 6 L 2 6 C";
        String original = "M 0 0 L 10 0 L 10 10 L 0 10 C";
        page(files, 0, "", path("0 0 8 8", "255 0 0", "Rule=\"NonZero\"").replace(original, rings)
                + path("10 0 8 8", "255 0 0", "Rule=\"Even-Odd\"").replace(original, rings)
                + path("0 10 8 8", "0 0 0", "Alpha=\"128\"").replace("Value=\"0 0 0\"", "Value=\"0 0 0\" Alpha=\"128\""));
        byte[] image = render(files).get(0);
        assertThat(pixel(image, 4, 4)).isEqualTo(Color.RED.getRGB());
        assertThat(pixel(image, 14, 4)).isEqualTo(Color.WHITE.getRGB());
        assertThat(new Color(pixel(image, 4, 14)).getRed()).isBetween(190, 192);
    }

    @Test
    void resolvesPageResourcesRelativeToTheirOwnResourceFile() throws Exception {
        var files = fixture(1);
        files.put("Doc_0/Pages/Local/Resources.xml", bytes("<ofd:Res xmlns:ofd=\"" + NS + "\" BaseLoc=\"../Fonts\"><ofd:Fonts><ofd:Font ID=\"11\" FontName=\"AgentFlowSyntheticA\"><ofd:FontFile>a.ttf</ofd:FontFile></ofd:Font></ofd:Fonts></ofd:Res>"));
        files.put("Doc_0/Pages/Fonts/a.ttf", InvoiceOfdFontTest.bytes("a.ttf"));
        page(files, 0, "<ofd:PageRes>Local/Resources.xml</ofd:PageRes>", text("", "<ofd:TextCode X=\"1\" Y=\"6\">A</ofd:TextCode>").replace("Font=\"10\"", "Font=\"11\""));
        assertThat(pixel(render(files).get(0), 2, 4)).isEqualTo(Color.BLACK.getRGB());
        replace(files, "Doc_0/Pages/Local/Resources.xml", "ID=\"11\"", "ID=\"10\"");
        assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
    }

    @Test
    void rejectsUnimplementedVisualFeaturesBeforeReturningPages() throws Exception {
        for (String content : List.of(path("0 0 5 5", "0 0 0", "DrawParam=\"10\""),
                path("0 0 5 5", "0 0 0", "").replace("</ofd:PathObject>", "<ofd:Clips/></ofd:PathObject>"))) {
            var files = fixture(1); page(files, 0, "", content);
            assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
        }
        var template = fixture(1); page(template, 0, "<ofd:Template TemplateID=\"9\"/>", path("0 0 5 5", "0 0 0", ""));
        assertThatThrownBy(() -> render(template)).isInstanceOf(IOException.class);
        var annotations = fixture(1);
        replace(annotations, "Doc_0/Document.xml", "</ofd:Document>", "<ofd:Annotations>Annotations.xml</ofd:Annotations></ofd:Document>");
        annotations.put("Doc_0/Annotations.xml", xml("Annotations", ""));
        assertThatThrownBy(() -> render(annotations)).isInstanceOf(IOException.class);
        var signatures = fixture(1);
        replace(signatures, "OFD.xml", "</ofd:DocBody>", "<ofd:Signatures>Signatures.xml</ofd:Signatures></ofd:DocBody>");
        signatures.put("Signatures.xml", xml("Signatures", ""));
        assertThatThrownBy(() -> render(signatures)).isInstanceOf(IOException.class);
    }

    @Test
    void boundsTotalPagePixelsAndRejectsExcessiveTransformedCoordinates() throws Exception {
        var files = fixture(8); replace(files, "Doc_0/Document.xml", "0 0 20 20", "0 0 400 400");
        assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
        var transformed = fixture(1);
        // 使用合法 XML 和各自有界的 Size、HScale，结果坐标仍然必须受限。
        page(transformed, 0, "", text("HScale=\"1000000\"", "<ofd:TextCode X=\"1\" Y=\"6\">A</ofd:TextCode>"));
        assertThatThrownBy(() -> render(transformed)).isInstanceOf(IOException.class);
    }

    @Test
    void rejectsNonAsciiCharactersInsideHexadecimalEscapes() throws Exception {
        var files = fixture(1); page(files, 0, "", text("", "<ofd:TextCode X=\"1\" Y=\"6\">\\００４１</ofd:TextCode>"));
        assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
    }

    @Test
    void boundsOutlineExpansionWithinOneTextObject() throws Exception {
        var files = fixture(1); page(files, 0, "", text("", "<ofd:TextCode X=\"1\" Y=\"6\">" + "A".repeat(25_000) + "</ofd:TextCode>"));
        assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
    }

    @Test
    void rejectsTruncatedImagesEvenWhenTheDecoderReturnsPartialPixels() throws Exception {
        var source = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        var encoded = new ByteArrayOutputStream();
        assertThat(ImageIO.write(source, "JPEG", encoded)).isTrue(); source.flush();
        byte[] jpeg = encoded.toByteArray();
        assertThat(pixel(render(imageFixture(jpeg, "JPEG")).get(0), 3, 3)).isEqualTo(Color.BLACK.getRGB());
        byte[] truncated = java.util.Arrays.copyOf(jpeg, jpeg.length - 2);
        assertThatThrownBy(() -> render(imageFixture(truncated, "JPEG"))).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> render(imageFixture(jpeg, "PNG"))).isInstanceOf(IOException.class);
    }

    private static Map<String, byte[]> imageFixture(byte[] bytes, String format) throws IOException {
        var files = fixture(1);
        files.put("Doc_0/Res/picture.dat", bytes);
        replace(files, "Doc_0/Resources.xml", "</ofd:Res>", "<ofd:MultiMedias><ofd:MultiMedia ID=\"11\" Type=\"Image\" Format=\"" + format + "\"><ofd:MediaFile>picture.dat</ofd:MediaFile></ofd:MultiMedia></ofd:MultiMedias></ofd:Res>");
        page(files, 0, "", "<ofd:ImageObject ID=\"20\" ResourceID=\"11\" Boundary=\"2 2 10 10\" CTM=\"10 0 0 10 0 0\"/>");
        return files;
    }

    private List<byte[]> render(Map<String,byte[]> files) throws IOException {
        Path source=Files.write(directory.resolve(UUID.randomUUID()+".ofd"),zip(files));return InvoiceOfdRenderer.render(InvoiceOfdArchive.read(source));
    }
    private static BufferedImage decode(byte[] png) throws IOException { return ImageIO.read(new ByteArrayInputStream(png)); }
    static int pixel(byte[] png,double x,double y) throws IOException {
        var image=decode(png);try{return image.getRGB((int)Math.floor(x*144/25.4),(int)Math.floor(y*144/25.4));}finally{image.flush();}
    }
    static Map<String,byte[]> fixture(int pages) throws IOException {
        var files=InvoiceOfdDocumentTest.fixture(pages);replace(files,"Doc_0/Document.xml","210 297","20 20");
        replace(files,"Doc_0/Document.xml","<ofd:MaxUnitID>100</ofd:MaxUnitID>","<ofd:MaxUnitID>100000</ofd:MaxUnitID>");
        replace(files,"Doc_0/Document.xml","</ofd:CommonData>","<ofd:PublicRes>Resources.xml</ofd:PublicRes></ofd:CommonData>");
        files.put("Doc_0/Resources.xml",bytes("<ofd:Res xmlns:ofd=\""+NS+"\" BaseLoc=\"Res\"><ofd:Fonts><ofd:Font ID=\"10\" FontName=\"AgentFlowSyntheticA\"><ofd:FontFile>a.ttf</ofd:FontFile></ofd:Font></ofd:Fonts></ofd:Res>"));
        files.put("Doc_0/Res/a.ttf",InvoiceOfdFontTest.bytes("a.ttf"));return files;
    }
    static void page(Map<String,byte[]> files,int index,String before,String objects){
        var identifiers=java.util.regex.Pattern.compile("\\bID=\"(?:20|21)\"").matcher(objects);
        var counter=new java.util.concurrent.atomic.AtomicInteger(200+index*100);
        String distinct=identifiers.replaceAll(match->"ID=\""+counter.getAndIncrement()+"\"");
        files.put("Doc_0/Pages/P"+index+".xml",xml("Page",before+"<ofd:Content><ofd:Layer ID=\""+(100+index)+"\">"+distinct+"</ofd:Layer></ofd:Content>"));
    }
    static String path(String boundary,String color,String extra){return "<ofd:PathObject ID=\"20\" Boundary=\""+boundary+"\" Fill=\"true\" Stroke=\"false\" "+extra+"><ofd:FillColor Value=\""+color+"\"/><ofd:AbbreviatedData>M 0 0 L 10 0 L 10 10 L 0 10 C</ofd:AbbreviatedData></ofd:PathObject>";}
    static String text(String extra,String content){return "<ofd:TextObject ID=\"21\" Font=\"10\" Size=\"4\" Boundary=\"0 0 20 20\" "+extra+">"+content+"</ofd:TextObject>";}
    static byte[] xml(String name,String body){return bytes("<ofd:"+name+" xmlns:ofd=\""+NS+"\">"+body+"</ofd:"+name+">");}
    static void replace(Map<String,byte[]> files,String file,String from,String to){String value=new String(files.get(file),java.nio.charset.StandardCharsets.UTF_8);if(!value.contains(from))throw new IllegalArgumentException("Fixture replacement missing");files.put(file,bytes(value.replace(from,to)));}
}
