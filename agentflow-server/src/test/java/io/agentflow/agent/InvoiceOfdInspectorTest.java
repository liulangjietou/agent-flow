package io.agentflow.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.agentflow.agent.InvoiceOfdArchiveTest.zip;
import static io.agentflow.agent.InvoiceOfdRendererTest.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 通过真实子进程验证有序整页结果、失败清理与超时后接续。
 * @author owlzhangfq@gmail.com
 */
class InvoiceOfdInspectorTest {
    @TempDir Path directory;

    @Test
    void rendersAllPagesInOrderAndPreservesFilesOutsideItsOwnDirectory() throws Exception {
        Path marker = Files.writeString(directory.resolve("keep.txt"), "owned by caller");
        var files = fixture(3);
        String[] colors = {"255 0 0", "0 255 0", "0 0 255"};
        for (int i = 0; i < colors.length; i++) page(files, i, "", path("0 0 20 20", colors[i], ""));
        List<byte[]> pages = render(zip(files), null, Duration.ofSeconds(30));
        assertThat(pages).hasSize(3);
        assertThat(pixel(pages.get(0), 5, 5)).isEqualTo(Color.RED.getRGB());
        assertThat(pixel(pages.get(1), 5, 5)).isEqualTo(Color.GREEN.getRGB());
        assertThat(pixel(pages.get(2), 5, 5)).isEqualTo(Color.BLUE.getRGB());
        assertThat(Files.readString(marker)).isEqualTo("owned by caller");
        try (var paths = Files.list(directory)) { assertThat(paths.toList()).containsExactly(marker); }
    }

    @Test
    void aLaterUnsupportedPageRejectsTheEntireResultAndRemovesItsTemporaryOriginal() throws Exception {
        var files = fixture(2);
        page(files, 0, "", path("0 0 20 20", "0 0 0", ""));
        page(files, 1, "", "<ofd:VideoObject ID=\"20\" Boundary=\"0 0 20 20\"/>");
        assertThatThrownBy(() -> render(zip(files), null, Duration.ofSeconds(30)))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo("INVOICE_EXTRACTION_SOURCE_UNAVAILABLE"));
        try (var paths = Files.list(directory)) { assertThat(paths.toList()).isEmpty(); }
    }

    @Test
    void timeoutTerminatesTheWorkerAndLetsTheNextRenderProceed() throws Exception {
        byte[] bytes = zip(fixture(1));
        var before = ProcessHandle.current().children().map(ProcessHandle::pid).toList();
        assertThatThrownBy(() -> render(bytes, null, Duration.ofMillis(1)))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo("INVOICE_EXTRACTION_SOURCE_UNAVAILABLE"));
        assertThat(ProcessHandle.current().children().filter(ProcessHandle::isAlive).map(ProcessHandle::pid).toList()).isSubsetOf(before);
        assertThat(render(bytes, null, Duration.ofSeconds(30))).hasSize(1);
        try (var paths = Files.list(directory)) { assertThat(paths.toList()).isEmpty(); }
    }

    @Test
    void usesEmbeddedCidFontsAtThePageLimitAndRejectsAnExtraPage() throws Exception {
        var files = fixture(10); files.put("Doc_0/Res/a.ttf", InvoiceOfdFontTest.bytes("cid-matrices.otf"));
        page(files, 0, "", text("", "<ofd:TextCode X=\"1\" Y=\"6\">A</ofd:TextCode>"));
        var pages = render(zip(files), null, Duration.ofSeconds(30));
        assertThat(pages).hasSize(10);
        assertThat(pixel(pages.get(0), 2, 4)).isEqualTo(Color.BLACK.getRGB());
        assertThat(pixel(pages.get(0), 3, 4)).isEqualTo(Color.WHITE.getRGB());
        assertThat(pixel(pages.get(9), 2, 4)).isEqualTo(Color.WHITE.getRGB());
        assertThatThrownBy(() -> render(zip(fixture(11)), null, Duration.ofSeconds(30)))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo("INVOICE_EXTRACTION_SOURCE_UNAVAILABLE"));
        try (var paths = Files.list(directory)) { assertThat(paths.toList()).isEmpty(); }
    }

    @Test
    void passesAnExplicitCatalogWithSpacesAndRevalidatesItsFontInTheNextProcess() throws Exception {
        Path fonts = Files.createDirectory(directory.resolve("fonts with spaces"));
        Path font = Files.write(fonts.resolve("two faces.ttc"), InvoiceOfdFontTest.bytes("two-faces.ttc"));
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(font)));
        Path catalog = Files.writeString(fonts.resolve("font catalog.json"), new JsonUtil(new ObjectMapper()).write(Map.of("fonts", List.of(Map.of(
                "name", "AgentFlowSyntheticA", "file", font.toString(), "face", "AgentFlowSyntheticB", "sha256", hash)))));
        var files = fixture(1); replace(files, "Doc_0/Resources.xml", "<ofd:FontFile>a.ttf</ofd:FontFile>", "");
        page(files, 0, "", text("", "<ofd:TextCode X=\"1\" Y=\"6\">A</ofd:TextCode>"));
        byte[] image = render(zip(files), catalog, Duration.ofSeconds(30)).get(0);
        assertThat(pixel(image, 1.4, 5)).isEqualTo(Color.BLACK.getRGB());
        assertThat(pixel(image, 2, 5)).isEqualTo(Color.WHITE.getRGB());
        Files.writeString(font, "synthetic changed file");
        assertThatThrownBy(() -> render(zip(files), catalog, Duration.ofSeconds(30)))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo("INVOICE_EXTRACTION_SOURCE_UNAVAILABLE"));
        try (var paths = Files.list(directory)) { assertThat(paths.toList()).containsExactly(fonts); }
    }

    private List<byte[]> render(byte[] bytes, Path catalog, Duration timeout) {
        return new InvoiceOfdInspector(directory, timeout).render(bytes, catalog);
    }
}
