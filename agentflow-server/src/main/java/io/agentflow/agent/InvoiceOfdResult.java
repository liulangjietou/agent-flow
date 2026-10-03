package io.agentflow.agent;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * 有界的进程结果协议；只检查封装及 PNG 固定头尾，不在父服务解码压缩图像。
 * @author owlzhangfq@gmail.com
 */
final class InvoiceOfdResult {
    static final String FILE = "pages.bin";
    static final long MAX_PAGE_PIXELS = 8_000_000;
    static final long MAX_TOTAL_PIXELS = 40_000_000;
    static final int MAX_PNG_BYTES = 20 * 1024 * 1024;
    private static final int MAGIC = 0x41464f31;
    private static final int HEADER_BYTES = 2 * Integer.BYTES;
    private static final int PNG_HEADER_BYTES = 33;
    private static final int PNG_END_BYTES = 12;
    private static final long PNG_MAGIC = 0x89504e470d0a1a0aL;
    private static final int IHDR = 0x49484452;
    private static final long IEND_WITH_CRC = 0x49454e44ae426082L;
    private InvoiceOfdResult() { }

    /** 渲染器已校验完整页面和总额度，成功后才写协议；任何写入失败由进程退出码否定。 */
    static void write(Path file, List<byte[]> pages) throws IOException {
        try (var output = new DataOutputStream(Files.newOutputStream(file, StandardOpenOption.CREATE_NEW))) {
            output.writeInt(MAGIC); output.writeInt(pages.size());
            for (byte[] page : pages) { output.writeInt(page.length); output.write(page); }
        }
    }

    /** 在分配每页数组之前核对长度和剩余额度；页数、尺寸、尾随数据均不能由结果文件放宽。 */
    static List<byte[]> read(Path file) throws IOException {
        long maximum = HEADER_BYTES + (long) Integer.BYTES * InvoiceExtractionInput.MAX_PAGES + MAX_PNG_BYTES;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > maximum) throw invalid();
        try (var input = new DataInputStream(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS))) {
            if (input.readInt() != MAGIC) throw invalid();
            int count = input.readInt();
            if (count < 1 || count > InvoiceExtractionInput.MAX_PAGES) throw invalid();
            int bytes = 0;
            long pixels = 0;
            var pages = new ArrayList<byte[]>();
            for (int i = 0; i < count; i++) {
                int size = input.readInt();
                if (size < PNG_HEADER_BYTES + PNG_END_BYTES || size > MAX_PNG_BYTES - bytes) throw invalid();
                byte[] page = input.readNBytes(size);
                if (page.length != size) throw invalid();
                pixels += pixels(page); bytes += size;
                if (pixels > MAX_TOTAL_PIXELS) throw invalid();
                pages.add(page);
            }
            if (input.read() != -1) throw invalid();
            return List.copyOf(pages);
        }
    }

    private static long pixels(byte[] png) throws IOException {
        var data = ByteBuffer.wrap(png);
        if (data.getLong() != PNG_MAGIC || data.getInt() != 13 || data.getInt() != IHDR) throw invalid();
        int width = data.getInt(), height = data.getInt();
        if (width <= 0 || height <= 0 || (long) width * height > MAX_PAGE_PIXELS) throw invalid();
        // 渲染端固定输出 8 位 RGB、标准压缩和过滤、非交错 PNG；这里不读取任何 IDAT 数据。
        if (data.get() != 8 || data.get() != 2 || data.get() != 0 || data.get() != 0 || data.get() != 0) throw invalid();
        var crc = new CRC32(); crc.update(png, 12, 17);
        if (Integer.toUnsignedLong(data.getInt()) != crc.getValue()
                || data.getInt(png.length - PNG_END_BYTES) != 0 || data.getLong(png.length - Long.BYTES) != IEND_WITH_CRC) throw invalid();
        return (long) width * height;
    }

    private static IOException invalid() { return new IOException("OFD worker result is invalid or exceeds its limits"); }
}
