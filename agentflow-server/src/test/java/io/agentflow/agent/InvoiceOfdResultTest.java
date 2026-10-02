package io.agentflow.agent;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/**
 * 子进程结果不是可信文件路径或无限字节流，父服务必须先校验封装和资源边界。
 * @author owlzhangfq@gmail.com
 */
class InvoiceOfdResultTest {
    @TempDir Path directory;

    @Test
    void keepsEveryPngByteInOrderAndReturnsAnImmutablePageList() throws Exception {
        byte[] first = png(2, 3), second = png(3, 2);
        var result = InvoiceOfdResult.read(write(List.of(first, second)));
        assertThat(result).hasSize(2);
        assertThat(result.get(0)).containsExactly(first); assertThat(result.get(1)).containsExactly(second);
        assertThatThrownBy(() -> result.add(first)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsWrongMagicPageCountsLengthsTruncationAndTrailingBytes() throws Exception {
        byte[] valid = Files.readAllBytes(write(List.of(png(2, 3))));
        for (int[] change : new int[][]{{0, 0}, {4, 0}, {4, 11}, {8, -1}, {8, Integer.MAX_VALUE}, {8, 44}}) {
            byte[] broken = valid.clone(); ByteBuffer.wrap(broken).putInt(change[0], change[1]); reject(broken);
        }
        reject(new byte[0]); reject(Arrays.copyOf(valid, valid.length - 1));
        reject(Arrays.copyOf(valid, valid.length + 1));
    }

    @Test
    void rejectsInvalidPngHeadersChecksumsAndEndMarkersIncludingALaterPage() throws Exception {
        byte[] page = png(2, 3);
        for (int location : new int[]{0, 8, 12, 24, 25, 26, 27, 28, 29, page.length - 1}) {
            byte[] broken = page.clone(); broken[location] ^= 1;
            assertThatThrownBy(() -> InvoiceOfdResult.read(write(List.of(page, broken)))).isInstanceOf(IOException.class);
        }
    }

    @Test
    void boundsEachCanvasAndTheSumWithoutDecodingCompressedPixels() throws Exception {
        byte[] page = png(2, 3);
        for (int[] size : new int[][]{{0, 3}, {-1, 3}, {2, 0}, {Integer.MAX_VALUE, Integer.MAX_VALUE}, {4001, 2000}}) {
            assertThatThrownBy(() -> InvoiceOfdResult.read(write(List.of(dimensions(page, size[0], size[1]))))).isInstanceOf(IOException.class);
        }
        byte[] largeHeader = dimensions(page, 4000, 2000);
        assertThatThrownBy(() -> InvoiceOfdResult.read(write(java.util.Collections.nCopies(6, largeHeader)))).isInstanceOf(IOException.class);
    }

    @Test
    void rejectsNonRegularLinkedAndOversizedResultFiles() throws Exception {
        assertThatThrownBy(() -> InvoiceOfdResult.read(directory)).isInstanceOf(IOException.class);
        Path target = write(List.of(png(2, 3)));
        Path link = Files.createSymbolicLink(directory.resolve("linked.bin"), target);
        assertThatThrownBy(() -> InvoiceOfdResult.read(link)).isInstanceOf(IOException.class);
        try (var sparse = new RandomAccessFile(directory.resolve("oversized.bin").toFile(), "rw")) {
            sparse.setLength(InvoiceOfdResult.MAX_PNG_BYTES + 49L);
        }
        assertThatThrownBy(() -> InvoiceOfdResult.read(directory.resolve("oversized.bin"))).isInstanceOf(IOException.class);
    }

    private Path write(List<byte[]> pages) throws IOException {
        Path path = directory.resolve(UUID.randomUUID() + ".bin"); InvoiceOfdResult.write(path, pages); return path;
    }

    private void reject(byte[] bytes) throws IOException {
        Path path = Files.write(directory.resolve(UUID.randomUUID() + ".bin"), bytes);
        assertThatThrownBy(() -> InvoiceOfdResult.read(path)).isInstanceOf(IOException.class);
    }

    private static byte[] dimensions(byte[] png, int width, int height) {
        byte[] changed = png.clone(); var data = ByteBuffer.wrap(changed);
        data.putInt(16, width); data.putInt(20, height);
        var crc = new CRC32(); crc.update(changed, 12, 17); data.putInt(29, (int) crc.getValue());
        return changed;
    }

    private static byte[] png(int width, int height) throws IOException {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        try (var bytes = new ByteArrayOutputStream()) {
            ImageIO.write(image, "PNG", bytes); return bytes.toByteArray();
        } finally { image.flush(); }
    }
}
