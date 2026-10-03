package io.agentflow.agent;

import io.agentflow.common.DomainException;
import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.agentflow.agent.InvoiceOfdArchiveTest.zip;
import static io.agentflow.agent.InvoiceOfdRendererTest.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 用完整文件包与最终像素验证批注归属、局部坐标、叠加及独立进程的整份失败。
 * @author owlzhangfq@gmail.com
 */
class InvoiceOfdAnnotationsTest {
    @TempDir Path directory;

    @Test
    void overlaysAnnotatedPagesByIdAndClipsLocalAppearanceInOrder() throws Exception {
        var files = fixture(2);
        page(files, 0, "", path("0 0 20 20", "0 0 255", ""));
        replace(files, "Doc_0/Pages/P0.xml", "<ofd:Layer ID=", "<ofd:Layer Type=\"Foreground\" ID=");
        page(files, 1, "", path("0 0 20 20", "255 255 0", ""));
        index(files, entry(2, "Second.xml") + entry(1, "First.xml"));
        file(files, "First.xml", annot(500, "Path", "", "4 3 6 5", path("0 0 10 10", "0 255 0", ""))
                + annot(501, "Highlight", "", "6 4 3 3", path("0 0 10 10", "255 0 0", "")));
        file(files, "Second.xml", annot(502, "Watermark", "", "3 3 4 4", path("0 0 10 10", "0 0 255", "")));
        var pages = render(files);
        assertThat(pages).hasSize(2);
        assertThat(pixel(pages.get(0), 5, 4)).isEqualTo(Color.GREEN.getRGB());
        assertThat(pixel(pages.get(0), 7, 5)).isEqualTo(Color.RED.getRGB());
        assertThat(pixel(pages.get(0), 3, 4)).isEqualTo(Color.BLUE.getRGB());
        assertThat(pixel(pages.get(0), 9, 9)).isEqualTo(Color.BLUE.getRGB());
        assertThat(pixel(pages.get(1), 4, 4)).isEqualTo(Color.BLUE.getRGB());
        assertThat(pixel(pages.get(1), 8, 4)).isEqualTo(Color.YELLOW.getRGB());
    }

    @Test
    void usesPagePrivateResourcesAndKeepsSameIdsIndependentAcrossDocuments() throws Exception {
        var files = fixture(1);
        page(files, 0, "<ofd:PageRes>Local/Resources.xml</ofd:PageRes>", "");
        files.put("Doc_0/Pages/Local/Resources.xml", xml("Res", "<ofd:Fonts><ofd:Font ID=\"11\" FontName=\"AgentFlowSyntheticA\"><ofd:FontFile>/Doc_0/Res/a.ttf</ofd:FontFile></ofd:Font></ofd:Fonts>"));
        index(files, entry(1, "First.xml"));
        file(files, "First.xml", annot(500, "Stamp", "", "0 0 20 20",
                text("", "<ofd:TextCode X=\"1\" Y=\"6\">A</ofd:TextCode>").replace("Font=\"10\"", "Font=\"11\"")));
        var second = new LinkedHashMap<String, byte[]>();
        for (var e : files.entrySet()) if (e.getKey().startsWith("Doc_0/")) {
            byte[] bytes = e.getValue();
            if (e.getKey().endsWith(".xml")) bytes = new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                    .replace("Doc_0/", "Doc_1/").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            second.put(e.getKey().replace("Doc_0/", "Doc_1/"), bytes);
        }
        files.putAll(second);
        files.put("Doc_1/Res/two.ttc", InvoiceOfdFontTest.bytes("two-faces.ttc"));
        replace(files, "Doc_1/Pages/Local/Resources.xml", "AgentFlowSyntheticA", "AgentFlowSyntheticB");
        replace(files, "Doc_1/Pages/Local/Resources.xml", "a.ttf", "two.ttc");
        replace(files, "OFD.xml", "</ofd:OFD>", "<ofd:DocBody><ofd:DocRoot>Doc_1/Document.xml</ofd:DocRoot></ofd:DocBody></ofd:OFD>");
        var pages = render(files);
        assertThat(pages).hasSize(2);
        assertThat(pixel(pages.get(0), 2, 4)).isEqualTo(Color.BLACK.getRGB());
        assertThat(pixel(pages.get(1), 2, 4)).isEqualTo(Color.WHITE.getRGB());
        assertThat(pixel(pages.get(1), 1.4, 5)).isEqualTo(Color.BLACK.getRGB());
    }

    @Test
    void respectsVisibilityWhileKeepingRemarkFlagsOutOfStaticAppearance() throws Exception {
        var files = fixture(1);
        index(files, entry(1, "First.xml"));
        String flags = "Print=\"false\" NoZoom=\"true\" NoRotate=\"1\" ReadOnly=\"0\" Subtype=\"Synthetic\"";
        String shown = annot(500, "Link", flags, "0 0 10 10", path("0 0 10 10", "255 0 0", "Alpha=\"128\""))
                .replace("<ofd:Appearance", "<ofd:Remark>Human note</ofd:Remark><ofd:Parameters><ofd:Parameter Name=\"URI\">https://example.invalid/never-follow</ofd:Parameter></ofd:Parameters><ofd:Appearance");
        file(files, "First.xml", shown + annot(501, "Stamp", "Visible=\"false\"", "0 0 10 10", path("0 0 10 10", "0 0 255", "")));
        var color = new Color(pixel(render(files).get(0), 4, 4));
        assertThat(color.getRed()).isEqualTo(255);
        assertThat(color.getGreen()).isBetween(126, 128);
        assertThat(color.getBlue()).isBetween(126, 128);
    }

    @Test
    void acceptsEmptyIndexAndPageRelativeAppearanceWithoutOptionalBoundary() throws Exception {
        var files = fixture(1);
        index(files, "");
        assertThat(pixel(render(files).get(0), 5, 5)).isEqualTo(Color.WHITE.getRGB());
        files.put("Doc_0/Annots/Index.xml", xml("Annotations", entry(1, "First.xml")));
        file(files, "First.xml", annot(500, "Path", "", null, path("4 3 3 3", "255 0 0", "")));
        assertThat(pixel(render(files).get(0), 5, 4)).isEqualTo(Color.RED.getRGB());
        assertThat(pixel(render(files).get(0), 3, 4)).isEqualTo(Color.WHITE.getRGB());
    }

    @Test
    void passesWholeAnnotatedDocumentThroughWorkerAndRejectsUnsupportedLaterPage() throws Exception {
        var files = fixture(2);
        index(files, entry(1, "First.xml") + entry(2, "Second.xml"));
        file(files, "First.xml", annot(500, "Path", "", "4 3 3 3", path("0 0 10 10", "255 0 0", "")));
        file(files, "Second.xml", annot(501, "Path", "", "4 3 3 3", path("0 0 10 10", "0 0 255", "")));
        var inspector = new InvoiceOfdInspector(directory, Duration.ofSeconds(30));
        var result = inspector.render(zip(files), null);
        assertThat(result).hasSize(2);
        assertThat(pixel(result.get(0), 5, 4)).isEqualTo(Color.RED.getRGB());
        assertThat(pixel(result.get(1), 5, 4)).isEqualTo(Color.BLUE.getRGB());
        file(files, "Second.xml", annot(501, "Path", "", "4 3 3 3", "<ofd:VideoObject ID=\"900\" Boundary=\"0 0 2 2\"/>"));
        assertThatThrownBy(() -> inspector.render(zip(files), null)).isInstanceOfSatisfying(DomainException.class,
                error -> assertThat(error.code()).isEqualTo("INVOICE_EXTRACTION_SOURCE_UNAVAILABLE"));
        try (var entries = Files.list(directory)) { assertThat(entries.toList()).isEmpty(); }
    }

    @Test
    void keepsPageOriginAndNestedClipsInsideTheAppearanceBoundary() throws Exception {
        var files = fixture(1);
        replace(files, "Doc_0/Document.xml", "0 0 20 20", "5 6 20 20");
        index(files, entry(1, "First.xml"));
        String clip = "<ofd:Clips><ofd:Clip><ofd:Area><ofd:Path Boundary=\"0 0 2 4\" Fill=\"true\" Stroke=\"false\">"
                + "<ofd:AbbreviatedData>M 0 0 L 2 0 L 2 4 L 0 4 C</ofd:AbbreviatedData></ofd:Path></ofd:Area></ofd:Clip></ofd:Clips>";
        String red = path("0 0 10 10", "255 0 0", "CTM=\"2 0 0 2 0 0\"").replace("</ofd:PathObject>", clip + "</ofd:PathObject>");
        file(files, "First.xml", annot(500, "Path", "", "6 7 4 4", "<ofd:PageBlock>" + red + "</ofd:PageBlock>")
                + annot(501, "Path", "", "6 7 0 5", path("0 0 10 10", "0 0 255", "")));
        byte[] image = render(files).get(0);
        assertThat(pixel(image, 2, 2)).isEqualTo(Color.RED.getRGB());
        assertThat(pixel(image, 3.5, 2)).isEqualTo(Color.WHITE.getRGB());
        assertThat(pixel(image, 0.5, 2)).isEqualTo(Color.WHITE.getRGB());
        assertThat(pixel(image, 2, 5.5)).isEqualTo(Color.WHITE.getRGB());
    }

    @Test
    void rejectsUnknownPagesDuplicateMappingsAndForeignDocumentPageIds() throws Exception {
        for (String entries : List.of(entry(99, "First.xml"), entry(1, "First.xml") + entry(1, "First.xml"),
                entry(1, "First.xml") + entry(1, "First.xml").replace("PageID=\"1\"", "PageID=\"01\""))) {
            var files = fixture(1); index(files, entries);
            file(files, "First.xml", annot(500, "Path", "", null, ""));
            assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
        }
        var files = fixture(1); index(files, entry(99, "First.xml"));
        file(files, "First.xml", annot(500, "Path", "", null, ""));
        files.put("Doc_1/P.xml", xml("Page", ""));
        files.put("Doc_1/Document.xml", xml("Document", "<ofd:CommonData><ofd:MaxUnitID>100</ofd:MaxUnitID>"
                + "<ofd:PageArea><ofd:PhysicalBox>0 0 20 20</ofd:PhysicalBox></ofd:PageArea></ofd:CommonData>"
                + "<ofd:Pages><ofd:Page ID=\"99\" BaseLoc=\"P.xml\"/></ofd:Pages>"));
        replace(files, "OFD.xml", "</ofd:OFD>", "<ofd:DocBody><ofd:DocRoot>Doc_1/Document.xml</ofd:DocRoot></ofd:DocBody></ofd:OFD>");
        assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
    }

    @Test
    void rejectsDuplicateAnnotationIdsAcrossPagesAndEmptyPageAnnotationFiles() throws Exception {
        var files = fixture(2); index(files, entry(1, "First.xml") + entry(2, "Second.xml"));
        String content = annot(500, "Path", "", null, "");
        file(files, "First.xml", content); file(files, "Second.xml", content);
        assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
        file(files, "Second.xml", "");
        assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
    }

    @Test
    void rejectsAmbiguousMetadataInvalidDatesAndUnknownAppearanceAttributes() throws Exception {
        String valid = annot(500, "Path", "", "0 0 10 10", "");
        for (String bad : List.of(valid.replace("Type=\"Path\"", "Type=\"Unknown\""),
                valid.replace(" Creator=\"Synthetic\"", ""), valid.replace("2026-10-02", "2026-02-30"),
                valid.replace("2026-10-02", "2026-10-02T12:00:00"),
                valid.replace("<ofd:Appearance", "<ofd:Remark>A</ofd:Remark><ofd:Remark>B</ofd:Remark><ofd:Appearance"),
                valid.replace("<ofd:Appearance", "<ofd:Parameters><ofd:Parameter Name=\"same\">A</ofd:Parameter><ofd:Parameter Name=\"same\">B</ofd:Parameter></ofd:Parameters><ofd:Appearance"),
                valid.replace("<ofd:Appearance", "<ofd:Appearance CTM=\"2 0 0 2 0 0\""),
                valid.replace("0 0 10 10", "0 0 -1 10"), valid.replace("<ofd:Appearance Boundary=\"0 0 10 10\"></ofd:Appearance>", ""))) {
            var files = fixture(1); index(files, entry(1, "First.xml")); file(files, "First.xml", bad);
            assertThatThrownBy(() -> render(files)).as(bad).isInstanceOf(IOException.class);
        }
        for (String name : List.of("Visible", "Print", "NoZoom", "NoRotate", "ReadOnly")) {
            var files = fixture(1); index(files, entry(1, "First.xml"));
            file(files, "First.xml", annot(500, "Path", name + "=\"yes\"", null, ""));
            assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
        }
        var files = fixture(1); index(files, entry(1, "First.xml"));
        file(files, "First.xml", valid.replace("2026-10-02", "2026-10-02+08:00"));
        assertThat(render(files)).hasSize(1);
    }

    @Test
    void doesNotBorrowAnotherPagesPrivateFontForAnAnnotation() throws Exception {
        var files = fixture(2);
        page(files, 0, "<ofd:PageRes>Local.xml</ofd:PageRes>", "");
        files.put("Doc_0/Pages/Local.xml", xml("Res", "<ofd:Fonts><ofd:Font ID=\"11\" FontName=\"AgentFlowSyntheticA\"><ofd:FontFile>/Doc_0/Res/a.ttf</ofd:FontFile></ofd:Font></ofd:Fonts>"));
        index(files, entry(1, "First.xml") + entry(2, "Second.xml"));
        String a = text("", "<ofd:TextCode X=\"1\" Y=\"6\">A</ofd:TextCode>").replace("Font=\"10\"", "Font=\"11\"");
        file(files, "First.xml", annot(500, "Path", "", null, a));
        file(files, "Second.xml", annot(501, "Path", "", null, a));
        assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
    }

    @Test
    void sharesTheObjectBudgetWithBodyContentIncludingHiddenAppearances() throws Exception {
        var files = fixture(1); index(files, entry(1, "First.xml"));
        file(files, "First.xml", annot(500, "Path", "Visible=\"false\"", null, path("0 0 10 10", "0 0 0", "")));
        page(files, 0, "", "<ofd:PageBlock/>".repeat(19_998));
        assertThat(render(files)).hasSize(1);
        page(files, 0, "", "<ofd:PageBlock/>".repeat(19_999));
        assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
    }

    @Test
    void sharesThePageIccLoadBudgetWithAnnotationPaints() throws Exception {
        var files = fixture(1); index(files, entry(1, "First.xml"));
        var definitions = new StringBuilder(); var body = new StringBuilder();
        for (int id = 30; id <= 62; id++) {
            definitions.append("<ofd:ColorSpace ID=\"").append(id).append("\" Type=\"RGB\" Profile=\"srgb.icc\"/>");
            if (id < 62) body.append(path("0 0 10 10", "255 0 0", "").replace("<ofd:FillColor", "<ofd:FillColor ColorSpace=\"" + id + "\""));
        }
        replace(files, "Doc_0/Resources.xml", "</ofd:Res>", "<ofd:ColorSpaces>" + definitions + "</ofd:ColorSpaces></ofd:Res>");
        files.put("Doc_0/Res/srgb.icc", java.awt.color.ICC_Profile.getInstance(java.awt.color.ColorSpace.CS_sRGB).getData());
        page(files, 0, "", body.toString());
        file(files, "First.xml", annot(500, "Path", "", null, ""));
        assertThat(pixel(render(files).get(0), 4, 4)).isEqualTo(Color.RED.getRGB());
        file(files, "First.xml", annot(500, "Path", "", null, path("0 0 10 10", "255 0 0", "").replace("<ofd:FillColor", "<ofd:FillColor ColorSpace=\"62\"")));
        assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
    }

    @Test
    void boundsTheTotalAnnotationCountAcrossPagesIncludingEmptyAppearances() throws Exception {
        var files = fixture(2); index(files, entry(1, "First.xml") + entry(2, "Second.xml"));
        var first = new StringBuilder(); var second = new StringBuilder();
        for (int i = 0; i < 512; i++) {
            first.append(annot(1000 + i, "Path", "", null, ""));
            second.append(annot(2000 + i, "Path", "", null, ""));
        }
        file(files, "First.xml", first.toString()); file(files, "Second.xml", second.toString());
        assertThat(render(files)).hasSize(2);
        file(files, "Second.xml", second + annot(3000, "Path", "", null, ""));
        assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
    }

    @Test
    void validatesHiddenContentAndNeverFollowsLinkParameters() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/never-follow", request -> { calls.incrementAndGet(); request.sendResponseHeaders(204, -1); request.close(); });
        server.start();
        try {
            var files = fixture(1); index(files, entry(1, "First.xml"));
            String content = annot(500, "Link", "", null, path("0 0 10 10", "255 0 0", ""))
                    .replace("<ofd:Appearance", "<ofd:Parameters><ofd:Parameter Name=\"URI\">http://127.0.0.1:" + server.getAddress().getPort() + "/never-follow</ofd:Parameter></ofd:Parameters><ofd:Appearance");
            file(files, "First.xml", content);
            assertThat(pixel(render(files).get(0), 4, 4)).isEqualTo(Color.RED.getRGB());
            assertThat(calls).hasValue(0);
            file(files, "First.xml", annot(500, "Path", "Visible=\"false\"", null, "<ofd:VideoObject ID=\"901\"/>"));
            assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
        } finally { server.stop(0); }
    }

    private List<byte[]> render(Map<String, byte[]> files) throws IOException {
        Path source = Files.write(directory.resolve(UUID.randomUUID() + ".ofd"), zip(files));
        return InvoiceOfdRenderer.render(InvoiceOfdArchive.read(source));
    }

    static void index(Map<String, byte[]> files, String entries) {
        replace(files, "Doc_0/Document.xml", "</ofd:Document>", "<ofd:Annotations>Annots/Index.xml</ofd:Annotations></ofd:Document>");
        files.put("Doc_0/Annots/Index.xml", xml("Annotations", entries));
    }

    static String entry(int pageId, String file) {
        return "<ofd:Page PageID=\"" + pageId + "\"><ofd:FileLoc>Pages/" + file + "</ofd:FileLoc></ofd:Page>";
    }

    static void file(Map<String, byte[]> files, String name, String annotations) {
        files.put("Doc_0/Annots/Pages/" + name, xml("PageAnnot", annotations));
    }

    static String annot(int id, String type, String extra, String boundary, String content) {
        return "<ofd:Annot ID=\"" + id + "\" Type=\"" + type + "\" Creator=\"Synthetic\" LastModDate=\"2026-10-02\" " + extra + ">"
                + "<ofd:Appearance" + (boundary == null ? "" : " Boundary=\"" + boundary + "\"") + ">" + content + "</ofd:Appearance></ofd:Annot>";
    }
}
