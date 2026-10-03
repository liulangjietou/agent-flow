package io.agentflow.agent;

import java.awt.geom.GeneralPath;
import java.awt.geom.AffineTransform;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import org.apache.fontbox.cff.CFFCIDFont;
import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.CmapTable;
import org.apache.fontbox.ttf.OTFParser;
import org.apache.fontbox.ttf.OpenTypeFont;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;

/**
 * OFD 图片渲染所需的明确字体面及字形；不扫描系统字体或选择相似字体。
 * @author owlzhangfq@gmail.com
 */
final class InvoiceOfdFont implements Closeable {
    static final int MAX_FONT_BYTES = 32 * 1024 * 1024;
    private static final int MAX_FACES = 32;
    private static final int MAX_TABLES = 128;
    private static final int COLLECTION = 0x74746366;
    private static final int TRUETYPE = 0x00010000;
    private static final int APPLE_TRUETYPE = 0x74727565;
    private static final int OPENTYPE = 0x4f54544f;
    private final TrueTypeFont font;
    private final Closeable owner;
    private final AffineTransform toEm;
    private final InvoiceOfdCidFont cidFont;
    private final int glyphCount;
    private boolean closed;

    private InvoiceOfdFont(TrueTypeFont font, Closeable owner) throws IOException {
        this.font = font; this.owner = owner;
        glyphCount = font.getNumberOfGlyphs();
        if (glyphCount < 1 || glyphCount > 65_535) throw invalid();
        if (font instanceof OpenTypeFont otf && otf.isPostScript()) {
            var cff = otf.getCFF().getFont();
            cidFont = cff instanceof CFFCIDFont cid ? InvoiceOfdCidFont.read(cid, glyphCount) : null;
            toEm = cidFont == null ? InvoiceOfdCffData.matrix(cff.getFontMatrix()) : null;
        } else {
            int units = font.getUnitsPerEm();
            if (units < 16 || units > 16_384 || font.getGlyph() == null) throw invalid();
            toEm = AffineTransform.getScaleInstance(1.0 / units, 1.0 / units);
            cidFont = null;
        }
    }

    /** 单字体可按嵌入文件选取，集合字体必须明确唯一 PostScript 名称；调用方应位于隔离进程。 */
    static InvoiceOfdFont load(byte[] bytes, String postscriptFace) throws IOException {
        requireDirectory(bytes);
        Closeable owner = null;
        try {
            TrueTypeFont font;
            if (ByteBuffer.wrap(bytes).getInt() == COLLECTION) {
                if (postscriptFace == null || postscriptFace.isBlank()) throw invalid();
                var collection = new TrueTypeCollection(new ByteArrayInputStream(bytes)); owner = collection;
                var selected = new ArrayList<TrueTypeFont>();
                collection.processAllFonts(candidate -> { if (postscriptFace.equals(candidate.getName())) selected.add(candidate); });
                if (selected.size() != 1) throw invalid();
                font = selected.get(0);
            } else {
                var input = new RandomAccessReadBuffer(bytes); owner = input;
                font = new OTFParser().parse(input); owner = font;
                if (postscriptFace != null && !postscriptFace.equals(font.getName())) throw invalid();
            }
            return new InvoiceOfdFont(font, owner);
        } catch (IOException | RuntimeException failed) {
            if (owner != null) {
                try { owner.close(); } catch (IOException closeFailure) { failed.addSuppressed(closeFailure); }
            }
            throw new IOException("OFD font is unavailable, ambiguous or invalid", failed);
        }
    }

    String name() throws IOException { requireOpen(); return font.getName(); }

    /** 按完整 Unicode 码点找字形，拒绝缺字与非 Unicode 字符表，不能把占位轮廓当成正文。 */
    GeneralPath unicode(int codePoint) throws IOException {
        requireOpen();
        if (!Character.isValidCodePoint(codePoint) || codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) throw invalid();
        return glyph(unicodeMap().getGlyphId(codePoint));
    }

    /** 字形编号必须来自实际选定的字体；返回独立的 em 单位轮廓，空格允许为空轮廓。 */
    GeneralPath glyph(int index) throws IOException {
        requireOpen();
        if (index <= 0 || index >= glyphCount) throw invalid();
        GeneralPath outline;
        if (font instanceof OpenTypeFont otf && otf.isPostScript()) {
            var glyph = otf.getCFF().getFont().getType2CharString(cidFont == null ? index : cidFont.cid(index));
            if (glyph == null) throw invalid();
            outline = glyph.getPath();
        } else {
            var glyph = font.getGlyph().getGlyph(index);
            if (glyph == null) throw invalid();
            outline = glyph.getPath();
        }
        if (outline == null) throw invalid();
        var result = new GeneralPath(outline); result.transform(cidFont == null ? toEm : cidFont.matrixFor(index));
        double[] points = new double[6];
        for (var path = result.getPathIterator(null); !path.isDone(); path.next()) {
            path.currentSegment(points);
            for (double value : points) if (!Double.isFinite(value)) throw invalid();
        }
        return result;
    }

    private CmapLookup unicodeMap() throws IOException {
        CmapTable table = font.getCmap();
        if (table == null) throw invalid();
        // 优先完整 Unicode；不采用 Symbol、Mac Roman 或库的任意首表回退。
        for (int encoding : new int[]{6, 4, 3, 2, 1, 0}) {
            var candidate = table.getSubtable(CmapTable.PLATFORM_UNICODE, encoding);
            if (candidate != null) return candidate;
            if (encoding == 4) {
                candidate = table.getSubtable(CmapTable.PLATFORM_WINDOWS, CmapTable.ENCODING_WIN_UNICODE_FULL);
                if (candidate != null) return candidate;
            }
        }
        var candidate = table.getSubtable(CmapTable.PLATFORM_WINDOWS, CmapTable.ENCODING_WIN_UNICODE_BMP);
        if (candidate == null) throw invalid();
        return candidate;
    }

    /** 在第三方解析前限定字体集合与表目录，避免超大分配或容错解析截断表。 */
    private static void requireDirectory(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 12 || bytes.length > MAX_FONT_BYTES) throw invalid();
        var input = ByteBuffer.wrap(bytes);
        if (input.getInt(0) != COLLECTION) { requireFace(input, 0); return; }
        int version = input.getInt(4), count = input.getInt(8);
        if (version != 0x00010000 && version != 0x00020000 || count < 1 || count > MAX_FACES || 12 + count * 4 > bytes.length) throw invalid();
        var offsets = new HashSet<Integer>();
        for (int i = 0; i < count; i++) {
            int offset = input.getInt(12 + i * 4);
            if (offset < 12 + count * 4 || offset > bytes.length - 12 || !offsets.add(offset)) throw invalid();
            requireFace(input, offset);
        }
    }

    private static void requireFace(ByteBuffer input, int offset) throws IOException {
        int type = input.getInt(offset), count = Short.toUnsignedInt(input.getShort(offset + 4));
        if (type != TRUETYPE && type != APPLE_TRUETYPE && type != OPENTYPE || count < 1 || count > MAX_TABLES || (long) offset + 12 + count * 16 > input.capacity()) throw invalid();
        var tags = new HashSet<Integer>();
        for (int i = 0; i < count; i++) {
            int entry = offset + 12 + i * 16;
            long start = Integer.toUnsignedLong(input.getInt(entry + 8)), size = Integer.toUnsignedLong(input.getInt(entry + 12));
            if (!tags.add(input.getInt(entry)) || start + size > input.capacity()) throw invalid();
        }
    }

    private void requireOpen() throws IOException { if (closed) throw new IOException("OFD font is already closed"); }
    private static IOException invalid() { return new IOException("OFD font or glyph is invalid or exceeds its limits"); }

    /** 释放实际字体或集合的输入，重复关闭不再次访问底层资源。 */
    @Override public void close() throws IOException { if (!closed) { closed = true; owner.close(); } }
}
