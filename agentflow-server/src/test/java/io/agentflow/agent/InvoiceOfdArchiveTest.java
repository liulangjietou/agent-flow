package io.agentflow.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/**
 * 自造 ZIP 验证 OFD 包的完整性、解压上限、路径语义和私有目录写入。
 * @author owlzhangfq@gmail.com
 */
class InvoiceOfdArchiveTest {
    @TempDir Path directory;

    @Test
    void readsEveryFileAndMaterializesItsOriginalBytesInANewDirectory() throws Exception {
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("OFD.xml", bytes("synthetic manifest"));
        entries.put("Doc_0/Pages/Page_0/Content.xml", bytes("synthetic page"));
        entries.put("Doc_0/Res/测试图片.png", new byte[]{1, 3, 5});
        byte[] original = zip(entries);
        Path source = save(original);
        var archive = InvoiceOfdArchive.read(source);
        assertThat(archive.files()).containsExactlyInAnyOrderElementsOf(entries.keySet());
        Path destination = directory.resolve("unpacked");
        archive.materialize(destination);
        for (var entry : entries.entrySet()) {
            assertThat(Files.readAllBytes(destination.resolve(entry.getKey()))).isEqualTo(entry.getValue());
            try (var in = archive.open(entry.getKey())) { assertThat(in.readAllBytes()).isEqualTo(entry.getValue()); }
        }
        assertThat(Files.readAllBytes(source)).isEqualTo(original);
    }

    @Test
    void containerAbsoluteAndBalancedParentReferencesStayInsideTheSamePackage() throws Exception {
        var archive = read(Map.of("OFD.xml", bytes("manifest"), "Doc_0/Res/font.ttf", new byte[]{1},
                "Doc_0/Pages/Content.xml", bytes("page")));
        assertThat(archive.file("Doc_0/Pages", "../Res/font.ttf")).isEqualTo("Doc_0/Res/font.ttf");
        assertThat(archive.file("Doc_0/Pages", "/Doc_0/Res/font.ttf")).isEqualTo("Doc_0/Res/font.ttf");
        assertThat(archive.directory("Doc_0", "Res")).isEqualTo("Doc_0/Res");
        assertThat(archive.directory("Doc_0", ".")).isEqualTo("Doc_0");
        assertThat(archive.directory("Doc_0", "/")).isEmpty();
        for (String reference : new String[]{"../../../etc/passwd", "//example.test/font.ttf", "file:/etc/passwd",
                "https://example.test/font.ttf", "C:/font.ttf", "..\\Res\\font.ttf", "../Res/missing.ttf", "../Res/font.ttf#fragment"}) {
            assertThatThrownBy(() -> archive.file("Doc_0/Pages", reference)).isInstanceOf(IOException.class);
        }
    }

    @Test
    void traversalAndAmbiguousArchiveNamesAreRejectedBeforeWritingAnything() throws Exception {
        for (String name : new String[]{"../escape", "/absolute", "C:/escape", "Doc/../escape", "Doc//page", "Doc/./page",
                "Doc\\page", "Doc/page ", "Doc/page.", "Doc/a?b", "Doc/a#b", "Doc/a%2fb", "Doc/\u0001bad"}) {
            assertThatThrownBy(() -> read(Map.of("OFD.xml", new byte[]{1}, name, new byte[]{2})))
                    .as(name).isInstanceOf(IOException.class);
        }
        assertThat(Files.exists(directory.resolve("escape"))).isFalse();
    }

    @Test
    void duplicateCaseAliasesAndFileDirectoryCollisionsAreRejected() throws Exception {
        assertThatThrownBy(() -> read(Map.of("OFD.xml", new byte[]{1}, "ofd.xml", new byte[]{2}))).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> read(Map.of("OFD.xml", new byte[]{1}, "Doc/File", new byte[]{2}, "doc/Other", new byte[]{3}))).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> read(Map.of("OFD.xml", new byte[]{1}, "Doc", new byte[]{2}, "Doc/Page", new byte[]{3}))).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> read(Map.of("OFD.xml", new byte[]{1}, "Doc/é", new byte[]{2}, "Doc/e\u0301", new byte[]{3}))).isInstanceOf(IOException.class);
        byte[] duplicate = zip(Map.of("OFD.xml", new byte[]{1}, "a.xml", new byte[]{2}, "b.xml", new byte[]{3}));
        replace(duplicate, bytes("b.xml"), bytes("a.xml"));
        assertThatThrownBy(() -> InvoiceOfdArchive.read(save(duplicate))).isInstanceOf(IOException.class);
    }

    @Test
    void corruptCrcTruncationAndMissingRootAreRejected() throws Exception {
        byte[] corrupt = zip(Map.of("OFD.xml", bytes("manifest")));
        int central = indexOf(corrupt, new byte[]{0x50, 0x4b, 0x01, 0x02}, 0);
        assertThat(central).isPositive();
        corrupt[central + 16] ^= 1;
        assertThatThrownBy(() -> InvoiceOfdArchive.read(save(corrupt))).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> InvoiceOfdArchive.read(save(new byte[]{0x50, 0x4b, 0x03, 0x04}))).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> read(Map.of("Doc/Document.xml", bytes("document")))).isInstanceOf(IOException.class);
    }

    @Test
    void limitsActualInflatedBytesInsteadOfTrustingSmallCompressedSize() throws Exception {
        byte[] overEntry = zip(Map.of("OFD.xml", new byte[]{1}, "oversized.bin", new byte[8 * 1024 * 1024 + 1]));
        assertThat(overEntry.length).isLessThan(20_000);
        assertThatThrownBy(() -> InvoiceOfdArchive.read(save(overEntry))).isInstanceOf(IOException.class);
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("OFD.xml", new byte[]{1});
        byte[] chunk = new byte[8 * 1024 * 1024];
        for (int i = 0; i < 9; i++) entries.put("chunk-" + i + ".bin", chunk);
        assertThatThrownBy(() -> read(entries)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> InvoiceOfdArchive.read(save(new byte[20 * 1024 * 1024 + 1]))).isInstanceOf(IOException.class);
    }

    @Test
    void limitsEntryCountIncludingDirectoriesAndOverlongPaths() throws Exception {
        var entries = new LinkedHashMap<String, byte[]>(); entries.put("OFD.xml", new byte[]{1});
        for (int i = 0; i < 1024; i++) entries.put("dir-" + i + "/", new byte[0]);
        assertThatThrownBy(() -> read(entries)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> read(Map.of("OFD.xml", new byte[]{1}, "x".repeat(513), new byte[]{1}))).isInstanceOf(IOException.class);
    }

    @Test
    void neverMergesAnExistingDirectoryOrFollowsADestinationSymlink() throws Exception {
        var archive = read(Map.of("OFD.xml", bytes("manifest")));
        Path existing = Files.createDirectory(directory.resolve("existing"));
        Path sentinel = Files.writeString(existing.resolve("sentinel"), "keep");
        assertThatThrownBy(() -> archive.materialize(existing)).isInstanceOf(IOException.class);
        Path link = Files.createSymbolicLink(directory.resolve("linked"), existing);
        assertThatThrownBy(() -> archive.materialize(link)).isInstanceOf(IOException.class);
        assertThat(Files.readString(sentinel)).isEqualTo("keep");
        assertThat(Files.exists(existing.resolve("OFD.xml"))).isFalse();
    }

    private InvoiceOfdArchive read(Map<String, byte[]> entries) throws IOException { return InvoiceOfdArchive.read(save(zip(entries))); }
    private Path save(byte[] value) throws IOException { return Files.write(directory.resolve(UUID.randomUUID() + ".ofd"), value); }
    static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    static byte[] zip(Map<String, byte[]> entries) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
    private static void replace(byte[] bytes, byte[] before, byte[] after) {
        int offset = 0, found;
        while ((found = indexOf(bytes, before, offset)) >= 0) {
            System.arraycopy(after, 0, bytes, found, after.length); offset = found + before.length;
        }
    }
    private static int indexOf(byte[] bytes, byte[] needle, int start) {
        for (int i = start; i <= bytes.length - needle.length; i++) {
            int j = 0; while (j < needle.length && bytes[i + j] == needle[j]) j++;
            if (j == needle.length) return i;
        }
        return -1;
    }
}
