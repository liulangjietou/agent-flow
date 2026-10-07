package io.agentflow.agent;

import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.agentflow.agent.InvoiceOfdArchiveTest.zip;
import static io.agentflow.agent.InvoiceOfdRendererTest.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 复现公开票面实际使用的容器差异，兼容时仍逐页验证像素和严格的失败边界。
 * @author owlzhangfq@gmail.com
 */
class InvoiceOfdCompatibilityTest {
    private static final String CURRENT_NAMESPACE = "http://www.ofdspec.org/2016";
    private static final String LEGACY_NAMESPACE = "http://www.ofdspec.org";
    @TempDir Path directory;

    @Test
    void rendersLegacyNamespaceAcrossTheWholeDocumentAndItsResources() throws Exception {
        var files = fixture(2);
        page(files, 0, "", path("0 0 10 10", "255 0 0", ""));
        page(files, 1, "", text("", "<ofd:TextCode X=\"1\" Y=\"6\">A</ofd:TextCode>"));
        files.replaceAll((name, bytes) -> name.endsWith(".xml")
                ? new String(bytes, StandardCharsets.UTF_8).replace(CURRENT_NAMESPACE, LEGACY_NAMESPACE).getBytes(StandardCharsets.UTF_8) : bytes);
        var pages = render(files);
        assertThat(pages).hasSize(2);
        assertThat(pixel(pages.get(0), 5, 5)).isEqualTo(Color.RED.getRGB());
        assertThat(pixel(pages.get(1), 2, 4)).isEqualTo(Color.BLACK.getRGB());
    }

    @Test
    void rejectsMixedNamespacesInsideOneXmlAndUnknownDrawingNamespaces() throws Exception {
        var files = fixture(2);
        page(files, 1, "", path("0 0 10 10", "255 0 0", "")
                .replace("<ofd:PathObject ", "<ofd:PathObject xmlns:ofd=\"" + LEGACY_NAMESPACE + "\" "));
        assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
        replace(files, "Doc_0/Pages/P1.xml", LEGACY_NAMESPACE + "\"", "https://example.invalid/ofd\"");
        assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
    }

    @Test
    void rendersExplicitlySupportedNamespacesChosenIndependentlyByReferencedFiles() throws Exception {
        var files = fixture(2);
        page(files, 0, "", path("0 0 10 10", "255 0 0", ""));
        page(files, 1, "", text("", "<ofd:TextCode X=\"1\" Y=\"6\">A</ofd:TextCode>"));
        for (String file : List.of("OFD.xml", "Doc_0/Document.xml", "Doc_0/Pages/P0.xml", "Doc_0/Pages/P1.xml")) {
            replace(files, file, CURRENT_NAMESPACE, LEGACY_NAMESPACE);
        }
        // 旧票面可以引用使用 2016 命名空间的独立资源文件；各文件内仍只有明确的一种版本。
        var pages = render(files);
        assertThat(pixel(pages.get(0), 5, 5)).isEqualTo(Color.RED.getRGB());
        assertThat(pixel(pages.get(1), 2, 4)).isEqualTo(Color.BLACK.getRGB());
    }

    @Test
    void acceptsOnlyKnownUnqualifiedNonvisualAnnotationParameters() throws Exception {
        var files = fixture(1);
        replace(files, "Doc_0/Document.xml", "</ofd:Document>", "<ofd:Annotations>Annots.xml</ofd:Annotations></ofd:Document>");
        files.put("Doc_0/Annots.xml", xml("Annotations", "<ofd:Page PageID=\"1\"><ofd:FileLoc>PageAnnot.xml</ofd:FileLoc></ofd:Page>"));
        files.put("Doc_0/PageAnnot.xml", xml("PageAnnot", "<ofd:Annot ID=\"501\" Type=\"Watermark\">"
                + "<Parameters><Parameter Name=\"Location\">https://example.invalid/never-follow</Parameter></Parameters>"
                + "<ofd:Appearance Boundary=\"4 3 5 5\">" + path("0 0 10 10", "255 0 0", "") + "</ofd:Appearance></ofd:Annot>"));
        assertThat(pixel(render(files).get(0), 5, 4)).isEqualTo(Color.RED.getRGB());
        replace(files, "Doc_0/PageAnnot.xml", "ofd:PathObject", "PathObject");
        assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
    }

    @Test
    void rendersStaticAnnotationWithoutAbsentNonvisualMetadata() throws Exception {
        var files = fixture(1);
        replace(files, "Doc_0/Document.xml", "</ofd:Document>", "<ofd:Annotations>Annots.xml</ofd:Annotations></ofd:Document>");
        files.put("Doc_0/Annots.xml", xml("Annotations", "<ofd:Page PageID=\"1\"><ofd:FileLoc>PageAnnot.xml</ofd:FileLoc></ofd:Page>"));
        files.put("Doc_0/PageAnnot.xml", xml("PageAnnot", "<ofd:Annot ID=\"501\" Type=\"Watermark\" NoRotate=\"true\">"
                + "<ofd:Appearance Boundary=\"4 3 5 5\">" + path("0 0 10 10", "255 0 0", "") + "</ofd:Appearance></ofd:Annot>"));
        var png = render(files).get(0);
        assertThat(pixel(png, 5, 4)).isEqualTo(Color.RED.getRGB());
        assertThat(pixel(png, 3, 4)).isEqualTo(Color.WHITE.getRGB());
        replace(files, "Doc_0/PageAnnot.xml", "NoRotate=\"true\"", "LastModDate=\"2026-02-30\"");
        assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
    }

    @Test
    void acceptsOnlyRedundantCropBoxEqualToThePagesOwnPhysicalBox() throws Exception {
        var files = fixture(2);
        page(files, 1, "<ofd:Area><ofd:PhysicalBox>5 6 20 20</ofd:PhysicalBox><ofd:CropBox>5 6 20 20</ofd:CropBox></ofd:Area>",
                path("5 6 10 10", "0 0 255", ""));
        assertThat(pixel(render(files).get(1), 2, 2)).isEqualTo(Color.BLUE.getRGB());
        replace(files, "Doc_0/Pages/P1.xml", "<ofd:CropBox>5 6 20 20", "<ofd:CropBox>5 6 10 10");
        assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
        page(files, 1, "<ofd:Area><ofd:CropBox>0 0 20 20</ofd:CropBox></ofd:Area>", "");
        assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
    }

    private List<byte[]> render(Map<String, byte[]> files) throws IOException {
        Path source = Files.write(directory.resolve(UUID.randomUUID() + ".ofd"), zip(files));
        return InvoiceOfdRenderer.render(InvoiceOfdArchive.read(source));
    }
}
