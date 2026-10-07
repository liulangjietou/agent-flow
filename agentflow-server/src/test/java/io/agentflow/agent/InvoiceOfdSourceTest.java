package io.agentflow.agent;

import io.agentflow.common.DomainException;
import io.agentflow.expense.InvoiceOriginal;
import io.agentflow.expense.InvoiceOriginalFiles;
import java.awt.Color;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static io.agentflow.agent.InvoiceOfdArchiveTest.zip;
import static io.agentflow.agent.InvoiceOfdRendererTest.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 从公开来源适配器验证全部页图片、原件身份及整份失败，未调用真实外部模型。
 * @author owlzhangfq@gmail.com
 */
class InvoiceOfdSourceTest {
    private final InvoiceOriginalFiles files = mock(InvoiceOriginalFiles.class);
    private final InvoiceExtractionSources sources = new InvoiceExtractionSources(files);

    @Test
    void preparesAllPagesInOrderAndBindsEvidenceToTheCompleteOriginal() throws Exception {
        var contents = fixture(2);
        page(contents, 0, "", path("0 0 10 10", "255 0 0", ""));
        page(contents, 1, "", path("0 0 10 10", "0 0 255", ""));
        byte[] bytes = zip(contents);
        var original = original(bytes);
        var prepared = sources.prepare(original);
        assertThat(sources.supportedFormats()).contains(InvoiceOriginal.Format.OFD);
        assertThat(prepared.input().originalDigest()).isEqualTo(original.sha256());
        assertThat(prepared.input().originalBytes()).isEqualTo(bytes.length);
        assertThat(prepared.input().pageCount()).isEqualTo(2);
        assertThat(prepared.method()).isEqualTo(InvoiceExtractionSuggestion.Method.MODEL);
        assertThat(prepared.parts()).hasSize(4);
        assertThat(prepared.parts().get(0)).containsEntry("text", "OFD page 1 of 2");
        assertThat(prepared.parts().get(2)).containsEntry("text", "OFD page 2 of 2");
        assertThat(pixel(image(prepared.parts().get(1)), 5, 5)).isEqualTo(Color.RED.getRGB());
        assertThat(pixel(image(prepared.parts().get(3)), 5, 5)).isEqualTo(Color.BLUE.getRGB());
        assertThat(prepared.parts().toString()).doesNotContain(original.filename(), original.ownerId(), "file_data", Base64.getEncoder().encodeToString(bytes));
    }

    @Test
    void acceptsTheBoundedFullPageCountAndRejectsAnExcessPageInsteadOfTruncating() throws Exception {
        assertThat(sources.prepare(original(zip(fixture(InvoiceExtractionInput.MAX_PAGES)))).parts()).hasSize(2 * InvoiceExtractionInput.MAX_PAGES);
        assertUnavailable(zip(fixture(InvoiceExtractionInput.MAX_PAGES + 1)));
    }

    @Test
    void refusesMalformedLaterContentMissingFontsAndInvalidArchivesAsWholeSources() throws Exception {
        assertUnavailable(new byte[] {1, 2, 3});
        var contents = fixture(2);
        page(contents, 1, "", "<ofd:UnknownObject/>");
        assertUnavailable(zip(contents));
        contents = fixture(1);
        page(contents, 0, "", text("", "<ofd:TextCode X=\"1\" Y=\"6\">A</ofd:TextCode>"));
        replace(contents, "Doc_0/Resources.xml", "<ofd:FontFile>a.ttf</ofd:FontFile>", "");
        assertUnavailable(zip(contents));
    }

    @Test
    void deploymentCatalogIsExplicitAndChangedFontBytesFailBeforeSending() throws Exception {
        var contents = fixture(1);
        page(contents, 0, "", text("", "<ofd:TextCode X=\"1\" Y=\"6\">A</ofd:TextCode>"));
        replace(contents, "Doc_0/Resources.xml", "<ofd:FontFile>a.ttf</ofd:FontFile>", "");
        var original = original(zip(contents));
        var directory = java.nio.file.Files.createTempDirectory(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")), "ofd-source-fonts-");
        try {
            var font = java.nio.file.Files.write(directory.resolve("a.ttf"), contents.get("Doc_0/Res/a.ttf"));
            var catalog = directory.resolve("fonts.json");
            var json = new io.agentflow.common.JsonUtil(new com.fasterxml.jackson.databind.ObjectMapper());
            java.nio.file.Files.writeString(catalog, json.write(Map.of("fonts", java.util.List.of(Map.of(
                    "name", "AgentFlowSyntheticA", "bold", false, "italic", false, "file", font.toString(),
                    "face", "AgentFlowSyntheticA", "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(font))))))));
            var configured = new InvoiceExtractionSources(files, catalog.toString());
            assertThat(configured.prepare(original).parts()).hasSize(2);
            java.nio.file.Files.write(font, new byte[] {1});
            assertThatThrownBy(() -> configured.prepare(original)).isInstanceOfSatisfying(DomainException.class,
                    failure -> assertThat(failure.code()).isEqualTo("INVOICE_EXTRACTION_SOURCE_UNAVAILABLE"));
            assertThatThrownBy(() -> new InvoiceExtractionSources(files, "fonts.json")).isInstanceOf(IllegalArgumentException.class);
        } finally {
            try (var paths = java.nio.file.Files.walk(directory)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(path);
            }
        }
    }

    private InvoiceOriginal original(byte[] bytes) throws Exception {
        var original = new InvoiceOriginal(UUID.randomUUID(), UUID.randomUUID(), "demo", "alice", "private-invoice.ofd", bytes.length,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), InvoiceOriginal.Format.OFD, Instant.now(), InvoiceOriginal.Status.READY);
        when(files.read(original)).thenReturn(bytes);
        return original;
    }
    private void assertUnavailable(byte[] bytes) {
        assertThatThrownBy(() -> sources.prepare(original(bytes))).isInstanceOfSatisfying(DomainException.class,
                failure -> assertThat(failure.code()).isEqualTo("INVOICE_EXTRACTION_SOURCE_UNAVAILABLE"));
    }
    private byte[] image(Map<String, Object> part) {
        assertThat(part.get("type")).isEqualTo("image_url");
        var image = (Map<?, ?>) part.get("image_url");
        assertThat(image.get("detail")).isEqualTo("high");
        String url = (String) image.get("url");
        assertThat(url).startsWith("data:image/png;base64,");
        return Base64.getDecoder().decode(url.substring(url.indexOf(',') + 1));
    }
}
