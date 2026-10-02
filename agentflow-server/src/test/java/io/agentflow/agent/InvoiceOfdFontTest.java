package io.agentflow.agent;

import java.awt.geom.GeneralPath;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 自造字体证明明确选择集合字体面、Unicode 码点与字形失败边界，避免依赖主机字体。
 * @author owlzhangfq@gmail.com
 */
class InvoiceOfdFontTest {
    @Test
    void retainsActualOutlineUnitsForBmpAndSupplementaryCharactersAndBlankSpace() throws Exception {
        try (var font = InvoiceOfdFont.load(bytes("a.ttf"), "AgentFlowSyntheticA")) {
            assertThat(font.name()).isEqualTo("AgentFlowSyntheticA");
            assertWidth(font.unicode('A'), .6); assertWidth(font.unicode('中'), .6); assertWidth(font.unicode(0x20000), .6);
            assertThat(font.unicode(' ').getBounds2D().isEmpty()).isTrue();
        }
        try (var font = InvoiceOfdFont.load(bytes("b.ttf"), null)) { assertWidth(font.unicode('A'), .15); }
    }

    @Test
    void selectsNamedCollectionFaceInsteadOfTheFirstFontOrASimilarFamily() throws Exception {
        try (var font = InvoiceOfdFont.load(bytes("two-faces.ttc"), "AgentFlowSyntheticB")) {
            assertThat(font.name()).isEqualTo("AgentFlowSyntheticB"); assertWidth(font.unicode('中'), .15);
        }
        for (String name : new String[]{null, "", "AgentFlowSynthetic", "missing"}) {
            assertThatThrownBy(() -> InvoiceOfdFont.load(bytes("two-faces.ttc"), name)).isInstanceOf(IOException.class);
        }
        assertThatThrownBy(() -> InvoiceOfdFont.load(bytes("ambiguous-faces.ttc"), "AgentFlowSyntheticA")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> InvoiceOfdFont.load(bytes("a.ttf"), "AgentFlowSyntheticB")).isInstanceOf(IOException.class);
    }

    @Test
    void cffOutlinesAndExplicitGlyphIndicesUseTheSameEmScale() throws Exception {
        try (var font = InvoiceOfdFont.load(bytes("outline.otf"), "AgentFlowSyntheticCFF")) {
            assertWidth(font.unicode('A'), .55); assertWidth(font.unicode(0x20000), .55); assertWidth(font.glyph(2), .55);
            assertThat(font.glyph(1).getBounds2D().isEmpty()).isTrue();
        }
    }

    @Test
    void rejectsMissingGlyphsInvalidCodePointsAndOutOfRangeGlyphIndicesInsteadOfDrawingNotdef() throws Exception {
        try (var font = InvoiceOfdFont.load(bytes("a.ttf"), null)) {
            for (int code : new int[]{'B', 0, -1, 0xD800, 0x110000}) {
                assertThatThrownBy(() -> font.unicode(code)).isInstanceOf(IOException.class);
            }
            for (int index : new int[]{-1, 0, 5, Integer.MAX_VALUE}) {
                assertThatThrownBy(() -> font.glyph(index)).isInstanceOf(IOException.class);
            }
        }
    }

    @Test
    void refusesLegacyCmapAsUnicodeButCanUseItsExplicitGlyph() throws Exception {
        try (var font = InvoiceOfdFont.load(bytes("legacy-cmap.ttf"), null)) {
            assertThatThrownBy(() -> font.unicode('A')).isInstanceOf(IOException.class);
            assertWidth(font.glyph(2), .6);
        }
    }

    @Test
    void refusesTruncatedFontsUnboundedCollectionsAndMalformedOffsets() throws Exception {
        for (byte[] malformed : new byte[][]{new byte[0], new byte[]{1, 2, 3}, new byte[32 * 1024 * 1024 + 1]}) {
            assertThatThrownBy(() -> InvoiceOfdFont.load(malformed, null)).isInstanceOf(IOException.class);
        }
        byte[] tooMany = bytes("two-faces.ttc"); ByteBuffer.wrap(tooMany).putInt(8, Integer.MAX_VALUE);
        assertThatThrownBy(() -> InvoiceOfdFont.load(tooMany, "AgentFlowSyntheticA")).isInstanceOf(IOException.class);
        byte[] offset = bytes("two-faces.ttc"); ByteBuffer.wrap(offset).putInt(12, -1);
        assertThatThrownBy(() -> InvoiceOfdFont.load(offset, "AgentFlowSyntheticA")).isInstanceOf(IOException.class);
    }

    @Test
    void closeIsIdempotentAndReturnedPathsCannotMutateCachedGlyphs() throws Exception {
        var font = InvoiceOfdFont.load(bytes("a.ttf"), null);
        try { GeneralPath path = font.unicode('A'); path.reset(); assertWidth(font.unicode('A'), .6); }
        finally { font.close(); font.close(); }
        assertThatThrownBy(() -> font.unicode('A')).isInstanceOf(IOException.class);
    }

    private static void assertWidth(GeneralPath path, double expected) { assertThat(path.getBounds2D().getWidth()).isCloseTo(expected, within(.0001)); }
    static byte[] bytes(String filename) throws IOException {
        try (var input = InvoiceOfdFontTest.class.getResourceAsStream("/ofd-fonts/" + filename)) {
            if (input == null) throw new IOException("Synthetic font fixture is absent");
            return input.readAllBytes();
        }
    }
}
