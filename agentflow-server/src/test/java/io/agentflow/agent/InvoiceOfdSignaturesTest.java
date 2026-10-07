package io.agentflow.agent;

import io.agentflow.common.DomainException;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
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
 * 通过最终页像素验证签章外观；夹具只描述结构，不伪造任何验签成功结论。
 * @author owlzhangfq@gmail.com
 */
class InvoiceOfdSignaturesTest {
    @TempDir Path directory;

    @Test
    void placesAllSignedValueStampsByPageAndPreservesTransparencyAndLocalClip() throws Exception {
        var files = fixture(2);
        page(files, 0, "", path("0 0 20 20", "0 0 255", ""));
        page(files, 1, "", path("0 0 20 20", "0 255 0", ""));
        signed(files, stamp(1, 2, "2 2 8 8", "") + stamp(2, 1, "4 3 8 8", "Clip=\"0 0 3 8\""),
                signature(4, "PNG", picture()), false);
        var pages = render(files);
        assertThat(pages).hasSize(2);
        assertThat(pixel(pages.get(0), 5, 5)).isEqualTo(Color.RED.getRGB());
        assertThat(pixel(pages.get(0), 7.5, 5)).isEqualTo(Color.BLUE.getRGB());
        assertThat(pixel(pages.get(1), 3, 4)).isEqualTo(Color.RED.getRGB());
        assertThat(pixel(pages.get(1), 8, 4)).isEqualTo(Color.GREEN.getRGB());
    }

    @Test
    void readsVersionOneStandaloneSealAndSignedValueAppearances() throws Exception {
        var files = fixture(1);
        signed(files, stamp(1, 1, "3 3 8 8", ""), seal(1, "PNG", picture()), true);
        assertThat(pixel(render(files).get(0), 4, 5)).isEqualTo(Color.RED.getRGB());
        files.put("Doc_0/Signs/Value.dat", signature(1, "PNG", picture()));
        assertThat(pixel(render(files).get(0), 4, 5)).isEqualTo(Color.RED.getRGB());
        replace(files, "Doc_0/Signs/Signature.xml", "<ofd:Seal><ofd:BaseLoc>Seal.dat</ofd:BaseLoc></ofd:Seal>", "");
        assertThat(pixel(render(files).get(0), 4, 5)).isEqualTo(Color.RED.getRGB());
        files.put("Doc_0/Signs/Value.dat", signature(1, "PNG", new byte[] {1, 2, 3}));
        assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
    }

    @Test
    void retainsUnsignedPageAppearanceForSignaturesWithoutStampsAndEmptyIndex() throws Exception {
        var files = fixture(1);
        page(files, 0, "", path("0 0 20 20", "0 0 255", ""));
        signed(files, "", new byte[] {1, 2, 3}, false);
        assertThat(pixel(render(files).get(0), 4, 5)).isEqualTo(Color.BLUE.getRGB());
        files.put("Doc_0/Signs/Index.xml", xml("Signatures", ""));
        assertThat(pixel(render(files).get(0), 4, 5)).isEqualTo(Color.BLUE.getRGB());
    }

    @Test
    void acceptsXmlSignatureAndStampIdsWithoutChangingNumericPageReferences() throws Exception {
        for (String identifier : List.of("s001", "签章_1", "signature-1.2")) {
            for (boolean appearance : List.of(false, true)) {
                var files = fixture(1);
                page(files, 0, "", path("0 0 20 20", "0 0 255", ""));
                signed(files, appearance ? stamp(1, 1, "3 3 8 8", "") : "", signature(4, "PNG", picture()), false);
                replace(files, "Doc_0/Signs/Index.xml", "<ofd:MaxSignId>1</ofd:MaxSignId>", "<ofd:MaxSignId>" + identifier + "</ofd:MaxSignId>");
                replace(files, "Doc_0/Signs/Index.xml", "ID=\"1\"", "ID=\"" + identifier + "\"");
                if (appearance) replace(files, "Doc_0/Signs/Signature.xml", "StampAnnot ID=\"1\"", "StampAnnot ID=\"" + identifier + "\"");
                assertThat(pixel(render(files).get(0), 4, 5)).isEqualTo((appearance ? Color.RED : Color.BLUE).getRGB());
            }
        }
    }

    @Test
    void rejectsMalformedOrRepeatedStringIdsWhilePageReferencesRemainNumeric() throws Exception {
        for (String identifier : List.of("", "bad id", "prefix:name", "a/b", "s".repeat(257))) {
            var files = fixture(1);
            signed(files, stamp(1, 1, "3 3 8 8", ""), signature(4, "PNG", picture()), false);
            replace(files, "Doc_0/Signs/Index.xml", "ID=\"1\"", "ID=\"" + identifier + "\"");
            assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
        }
        var duplicate = fixture(1);
        signed(duplicate, stamp(1, 1, "3 3 8 8", "") + stamp(2, 1, "3 3 8 8", ""), signature(4, "PNG", picture()), false);
        replace(duplicate, "Doc_0/Signs/Signature.xml", "StampAnnot ID=\"1\"", "StampAnnot ID=\"s001\"");
        replace(duplicate, "Doc_0/Signs/Signature.xml", "StampAnnot ID=\"2\"", "StampAnnot ID=\"s001\"");
        assertThatThrownBy(() -> render(duplicate)).isInstanceOf(IOException.class);
        var wrongPage = fixture(1);
        signed(wrongPage, stamp(1, 1, "3 3 8 8", ""), signature(4, "PNG", picture()), false);
        replace(wrongPage, "Doc_0/Signs/Signature.xml", "PageRef=\"1\"", "PageRef=\"s001\"");
        assertThatThrownBy(() -> render(wrongPage)).isInstanceOf(IOException.class);
    }

    @Test
    void rendersTheCompleteNestedSealWithoutWhiteBackgroundOrResourceCacheCollision() throws Exception {
        var files = fixture(1);
        page(files, 0, "", path("0 0 20 20", "0 0 255", "").replace("L 10 0 L 10 10 L 0 10", "L 20 0 L 20 20 L 0 20"));
        var nested = fixture(1);
        page(nested, 0, "", path("0 0 10 20", "255 0 0", "")
                + text("", "<ofd:TextCode X=\"12\" Y=\"6\">A</ofd:TextCode>"));
        signed(files, stamp(1, 1, "4 3 10 10", ""), signature(4, "ofd", zip(nested)), false);
        var png = render(files).get(0);
        assertThat(pixel(png, 5, 5)).isEqualTo(Color.RED.getRGB());
        assertThat(pixel(png, 12, 10)).isEqualTo(Color.BLUE.getRGB());
        assertThat(pixel(png, 10.5, 5)).isEqualTo(Color.BLACK.getRGB());
    }

    @Test
    void rejectsNestedMultiPageImagesInsteadOfSilentlyUsingOnlyTheirFirstPage() throws Exception {
        var files = fixture(1);
        signed(files, stamp(1, 1, "0 0 10 10", ""), signature(4, "ofd", zip(fixture(2))), false);
        assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
    }

    @Test
    void limitsRecursiveSealDepthAndSharesTheBodyObjectBudget() throws Exception {
        var nested = fixture(1);
        page(nested, 0, "", path("0 0 10 10", "255 0 0", ""));
        for (int i = 0; i < 4; i++) {
            var parent = fixture(1);
            signed(parent, stamp(1, 1, "0 0 20 20", ""), signature(4, "ofd", zip(nested)), false);
            nested = parent;
        }
        assertThat(pixel(render(nested).get(0), 5, 5)).isEqualTo(Color.RED.getRGB());
        var excessive = fixture(1);
        signed(excessive, stamp(1, 1, "0 0 20 20", ""), signature(4, "ofd", zip(nested)), false);
        assertThatThrownBy(() -> render(excessive)).isInstanceOf(IOException.class);
        var many = fixture(1);
        page(many, 0, "", "<ofd:PageBlock/>".repeat(19_999));
        var outer = fixture(1);
        page(outer, 0, "", "<ofd:PageBlock/>");
        signed(outer, stamp(1, 1, "0 0 10 10", ""), signature(4, "ofd", zip(many)), false);
        assertThatThrownBy(() -> render(outer)).isInstanceOf(IOException.class);
    }

    @Test
    void rejectsWrongPagesRepeatedStampIdsAndUnsupportedPlacementAttributes() throws Exception {
        for (String appearance : List.of(stamp(1, 99, "0 0 10 10", ""),
                stamp(1, 1, "0 0 10 10", "") + stamp(1, 1, "0 0 10 10", ""),
                stamp(1, 1, "0 0 -1 10", ""), stamp(1, 1, "0 0 10 10", "CTM=\"1 0 0 1 0 0\""),
                stamp(1, 1, "0 0 10 10", "Clip=\"0 0 -1 3\""))) {
            var files = fixture(1);
            signed(files, appearance, signature(4, "PNG", picture()), false);
            assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
        }
    }

    @Test
    void countsNestedArchiveEntriesAndXmlBytesAgainstTheOriginalSourceBudget() throws Exception {
        var nested = fixture(1);
        for (int i = 0; i < 510; i++) nested.put("unused-" + i + ".bin", new byte[] {1});
        var files = fixture(1);
        for (int i = 0; i < 510; i++) files.put("unused-" + i + ".bin", new byte[] {1});
        signed(files, stamp(1, 1, "0 0 10 10", ""), signature(4, "OFD", zip(nested)), false);
        assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
        var xmlNested = fixture(1);
        var xmlOuter = fixture(1);
        byte[] metadata = ("<Metadata><!--" + "a".repeat(1_500_000) + "--></Metadata>").getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 3; i++) {
            xmlNested.put("metadata-" + i + ".xml", metadata);
            xmlOuter.put("metadata-" + i + ".xml", metadata);
        }
        signed(xmlOuter, stamp(1, 1, "0 0 10 10", ""), signature(4, "OFD", zip(xmlNested)), false);
        assertThatThrownBy(() -> render(xmlOuter)).isInstanceOf(IOException.class);
    }

    @Test
    void rejectsMalformedDerInsteadOfScanningForAnEmbeddedPicture() throws Exception {
        byte[] valid = signature(4, "PNG", picture());
        byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
        byte[] nested = tlv(4, picture());
        for (int i = 0; i < 33; i++) nested = sequence(nested);
        for (byte[] value : List.of(picture(), trailing, Arrays.copyOf(valid, valid.length - 1), nested,
                signature(4, "GIF", picture()), signature(3, "PNG", picture()), signature(4, "PNG", new byte[] {1, 2, 3}))) {
            var files = fixture(1);
            signed(files, stamp(1, 1, "0 0 10 10", ""), value, false);
            assertThatThrownBy(() -> render(files)).isInstanceOf(IOException.class);
        }
    }

    @Test
    void failsTheWholeWorkerResultWhenALaterStampIsInvalidAndCleansItsDirectory() throws Exception {
        var files = fixture(2);
        signed(files, stamp(1, 1, "0 0 10 10", ""), signature(4, "PNG", picture()), false);
        var worker = new InvoiceOfdInspector(directory, Duration.ofSeconds(30));
        assertThat(worker.render(zip(files), null)).hasSize(2);
        replace(files, "Doc_0/Signs/Index.xml", "</ofd:Signatures>", "<ofd:Signature ID=\"2\" BaseLoc=\"Broken.xml\"/></ofd:Signatures>");
        files.put("Doc_0/Signs/Broken.xml", xml("Signature", "<ofd:SignedInfo>" + stamp(2, 2, "0 0 10 10", "")
                + "</ofd:SignedInfo><ofd:SignedValue>Broken.dat</ofd:SignedValue>"));
        files.put("Doc_0/Signs/Broken.dat", new byte[] {1, 2, 3});
        assertThatThrownBy(() -> worker.render(zip(files), null)).isInstanceOfSatisfying(DomainException.class,
                error -> assertThat(error.code()).isEqualTo("INVOICE_EXTRACTION_SOURCE_UNAVAILABLE"));
        try (var entries = Files.list(directory)) { assertThat(entries.toList()).isEmpty(); }
    }

    static void signed(Map<String, byte[]> files, String stamps, byte[] value, boolean standalone) {
        replace(files, "OFD.xml", "</ofd:DocBody>", "<ofd:Signatures>Doc_0/Signs/Index.xml</ofd:Signatures></ofd:DocBody>");
        files.put("Doc_0/Signs/Index.xml", xml("Signatures", "<ofd:MaxSignId>1</ofd:MaxSignId><ofd:Signature ID=\"1\" Type=\"Seal\" BaseLoc=\"Signature.xml\"/>"));
        files.put("Doc_0/Signs/Signature.xml", xml("Signature", "<ofd:SignedInfo><ofd:Provider ProviderName=\"Synthetic\" Version=\"1\"/>"
                + "<ofd:SignatureMethod>1.2.156.10197.1.501</ofd:SignatureMethod><ofd:SignatureDateTime>2026-10-07T00:00:00Z</ofd:SignatureDateTime>"
                + "<ofd:References CheckMethod=\"1.2.156.10197.1.401\"><ofd:Reference FileRef=\"/OFD.xml\"><ofd:CheckValue>AA==</ofd:CheckValue></ofd:Reference></ofd:References>"
                + stamps + (standalone ? "<ofd:Seal><ofd:BaseLoc>Seal.dat</ofd:BaseLoc></ofd:Seal>" : "")
                + "</ofd:SignedInfo><ofd:SignedValue>Value.dat</ofd:SignedValue>"));
        files.put("Doc_0/Signs/Value.dat", standalone ? new byte[] {1, 2, 3} : value);
        if (standalone) files.put("Doc_0/Signs/Seal.dat", value);
    }

    static String stamp(int id, int page, String boundary, String attributes) {
        return "<ofd:StampAnnot ID=\"" + id + "\" PageRef=\"" + page + "\" Boundary=\"" + boundary + "\" " + attributes + "/>";
    }

    static byte[] signature(int version, String format, byte[] picture) {
        byte[] common = sequence(integer(version), seal(version, format, picture),
                version == 1 ? tlv(3, new byte[] {0, 1}) : tlv(0x18, ascii("20261007000000Z")),
                tlv(3, new byte[] {0, 1}), tlv(0x16, ascii("synthetic")),
                version == 1 ? concat(tlv(4, new byte[] {1}), tlv(6, new byte[] {42, 3})) : new byte[0]);
        return version == 1 ? sequence(common, tlv(3, new byte[] {0, 1}))
                : sequence(common, tlv(4, new byte[] {1}), tlv(6, new byte[] {42, 3}), tlv(3, new byte[] {0, 1}));
    }

    static byte[] seal(int version, String format, byte[] picture) {
        byte[] info = sequence(sequence(tlv(0x16, ascii("ES")), integer(version), tlv(0x16, ascii("synthetic"))),
                tlv(0x16, ascii("synthetic-seal")), sequence(integer(1)),
                sequence(tlv(0x16, ascii(format)), tlv(4, picture), integer(20), integer(20)));
        return version == 1 ? sequence(info, sequence(tlv(4, new byte[] {1}), tlv(6, new byte[] {42, 3}), tlv(3, new byte[] {0, 1})))
                : sequence(info, tlv(4, new byte[] {1}), tlv(6, new byte[] {42, 3}), tlv(3, new byte[] {0, 1}));
    }

    private static byte[] picture() throws IOException {
        var image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 4; y++) for (int x = 0; x < 2; x++) image.setRGB(x, y, Color.RED.getRGB());
        var bytes = new ByteArrayOutputStream(); ImageIO.write(image, "PNG", bytes); image.flush(); return bytes.toByteArray();
    }

    private static byte[] ascii(String text) { return text.getBytes(StandardCharsets.US_ASCII); }
    private static byte[] integer(int value) { return tlv(2, new byte[] {(byte) value}); }
    private static byte[] sequence(byte[]... values) { return tlv(0x30, concat(values)); }
    private static byte[] concat(byte[]... values) {
        var bytes = new ByteArrayOutputStream(); for (byte[] value : values) bytes.writeBytes(value); return bytes.toByteArray();
    }
    private static byte[] tlv(int tag, byte[] value) {
        var bytes = new ByteArrayOutputStream(); bytes.write(tag);
        if (value.length < 128) bytes.write(value.length);
        else {
            int lengthBytes = (Integer.SIZE - Integer.numberOfLeadingZeros(value.length) + 7) / 8;
            bytes.write(0x80 | lengthBytes);
            for (int shift = (lengthBytes - 1) * 8; shift >= 0; shift -= 8) bytes.write(value.length >>> shift);
        }
        bytes.writeBytes(value); return bytes.toByteArray();
    }
    private List<byte[]> render(Map<String, byte[]> files) throws IOException {
        Path source = Files.write(directory.resolve(UUID.randomUUID() + ".ofd"), zip(files));
        return InvoiceOfdRenderer.render(InvoiceOfdArchive.read(source));
    }
}
