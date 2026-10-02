package io.agentflow.agent;

import java.awt.Color;
import java.awt.geom.GeneralPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.agentflow.agent.InvoiceOfdArchiveTest.zip;
import static io.agentflow.agent.InvoiceOfdFontTest.bytes;
import static io.agentflow.agent.InvoiceOfdRendererTest.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 非连续 CID、自造轮廓及独立字典矩阵，防止把 GID 当 CID 或复用首字典矩阵。
 * @author owlzhangfq@gmail.com
 */
class InvoiceOfdCidFontTest {
    @TempDir Path directory;

    @Test
    void resolvesUnicodeAndExplicitGidsThroughNonIdentityCidCharset() throws Exception {
        try (var font = InvoiceOfdFont.load(bytes("cid-subset.otf"), "AgentFlowSyntheticCID")) {
            bounds(font.unicode('A'), 0, 0, .3, .7);
            bounds(font.unicode('中'), 0, 0, .6, .7);
            bounds(font.unicode(0x20000), 0, 0, .8, .7);
            bounds(font.glyph(2), 0, 0, .3, .7);
            assertThat(font.unicode(' ').getBounds2D().isEmpty()).isTrue();
            assertThatThrownBy(() -> font.unicode('B')).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> font.glyph(100)).isInstanceOf(IOException.class);
        }
    }

    @Test
    void concatenatesTheSelectedFontDictionaryBeforeTheTopMatrixIncludingTranslations() throws Exception {
        try (var font = InvoiceOfdFont.load(bytes("cid-matrices.otf"), null)) {
            bounds(font.glyph(2), .1, .2, .3, .7);
            bounds(font.glyph(3), .2, .4, .3, 1.05);
            bounds(font.unicode(0x20000), .2, .4, .4, 1.05);
            bounds(font.glyph(2), .1, .2, .3, .7);
        }
    }

    @Test
    void usesEachFontDictionaryDirectlyWhenTheTopMatrixIsAbsent() throws Exception {
        try (var font = InvoiceOfdFont.load(bytes("cid-fd-only.otf"), null)) {
            bounds(font.glyph(2), 0, 0, .3, .7);
            GeneralPath sheared = font.glyph(3);
            bounds(sheared, .05, .15, .44, 1.4);
            assertThat(sheared.contains(.1, 1.3)).isFalse();
            assertThat(sheared.contains(.38, 1.35)).isTrue();
        }
    }

    @Test
    void usesTheTopMatrixWhenFontDictionariesOmitTheirMatrices() throws Exception {
        try (var font = InvoiceOfdFont.load(bytes("cid-top-only.otf"), null)) {
            bounds(font.glyph(2), .2, -.1, .6, 2.1);
            bounds(font.glyph(3), .2, -.1, 1.2, 2.1);
        }
    }

    @Test
    void rejectsInvalidFontDictionarySelectorsInsteadOfUsingDictionaryZeroOrDefaultPrivateValues() throws Exception {
        for (String file : new String[]{"cid-invalid-fd.otf", "cid-invalid-range-start.otf", "cid-invalid-range-sentinel.otf"}) reject(file);
    }

    @Test
    void rejectsInvalidSecondaryMatricesAndMissingPrivateDictionaries() throws Exception {
        reject("cid-singular-matrix.otf"); reject("cid-missing-private.otf");
    }

    @Test
    void rejectsAmbiguousCidMappingAndInconsistentGlyphCounts() throws Exception {
        reject("cid-duplicate-cid.otf"); reject("cid-inconsistent-count.otf");
    }

    @Test
    void handlesBothCharsetRangeFormatsAndRejectsRangesOutsideTheirGlyphAndCidBounds() throws Exception {
        for (String file : new String[]{"cid-range1.otf", "cid-range2.otf"}) {
            try (var font = InvoiceOfdFont.load(bytes(file), null)) {
                bounds(font.unicode('A'), 0, 0, .3, .7);
                bounds(font.unicode(0x20000), 0, 0, .8, .7);
                assertThat(font.unicode(' ').getBounds2D().isEmpty()).isTrue();
                if (file.equals("cid-range2.otf")) bounds(font.glyph(260), 0, 0, .8, .7);
            }
        }
        reject("cid-range-overflow.otf"); reject("cid-range-cid-overflow.otf");
    }

    @Test
    void rendersUnicodeAndCgTransformThroughTheSameCidAndMatrixMapping() throws Exception {
        var files = fixture(1); files.put("Doc_0/Res/a.ttf", bytes("cid-matrices.otf"));
        page(files, 0, "", text("", "<ofd:TextCode X=\"1\" Y=\"6\" DeltaX=\"4\">A中</ofd:TextCode>"
                + "<ofd:CGTransform CodePosition=\"0\"><ofd:Glyphs>2</ofd:Glyphs></ofd:CGTransform><ofd:TextCode X=\"9\" Y=\"6\">B</ofd:TextCode>"));
        var source = InvoiceOfdArchive.read(Files.write(directory.resolve("cid.ofd"), zip(files)));
        byte[] image = InvoiceOfdRenderer.render(source).get(0);
        for (double x : new double[]{2, 10}) assertThat(pixel(image, x, 4)).isEqualTo(Color.BLACK.getRGB());
        for (double x : new double[]{3, 11}) assertThat(pixel(image, x, 4)).isEqualTo(Color.WHITE.getRGB());
        assertThat(pixel(image, 6, 1)).isEqualTo(Color.BLACK.getRGB());
        assertThat(pixel(image, 7.5, 1)).isEqualTo(Color.WHITE.getRGB());
    }

    private static void bounds(GeneralPath path, double x, double y, double width, double height) {
        var box = path.getBounds2D();
        assertThat(box.getX()).isCloseTo(x, within(.00001));
        assertThat(box.getY()).isCloseTo(y, within(.00001));
        assertThat(box.getWidth()).isCloseTo(width, within(.00001));
        assertThat(box.getHeight()).isCloseTo(height, within(.00001));
    }

    private static void reject(String file) {
        assertThatThrownBy(() -> { try (var ignored = InvoiceOfdFont.load(bytes(file), null)) { } })
                .as(file).isInstanceOf(IOException.class);
    }
}
