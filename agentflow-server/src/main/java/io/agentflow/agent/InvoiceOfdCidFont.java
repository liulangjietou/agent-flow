package io.agentflow.agent;

import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.apache.fontbox.cff.CFFCIDFont;
import static io.agentflow.agent.InvoiceOfdCffData.*;

/**
 * 将 OpenType 的 GID 转为 FontBox CID 接口的编号，并按 GID 选择独立字典矩阵。
 * @author owlzhangfq@gmail.com
 */
final class InvoiceOfdCidFont {
    private static final int MAX_DICTIONARIES = 256;
    private static final int MAX_CID = 65_535;
    private static final double DEFAULT_SCALE = .001;
    private final int[] cids;
    private final int[] dictionaries;
    private final AffineTransform[] matrices;

    private InvoiceOfdCidFont(int[] cids, int[] dictionaries, AffineTransform[] matrices) {
        this.cids = cids; this.dictionaries = dictionaries; this.matrices = matrices;
    }

    /** FontBox 会把首个 FD 矩阵折入顶层结果，故必须从原始 CFF 保留并组合各自矩阵。 */
    static InvoiceOfdCidFont read(CFFCIDFont font, int glyphCount) throws IOException {
        var data = new InvoiceOfdCffData(font.getData());
        var top = data.top();
        var strings = data.index(offset(top, CHAR_STRINGS), glyphCount);
        if (strings.count() != glyphCount || font.getNumCharStrings() != glyphCount) throw invalid();
        for (int i = 0; i < glyphCount; i++) if (strings.starts()[i] == strings.starts()[i + 1]) throw invalid();
        var array = data.index(offset(top, FD_ARRAY), MAX_DICTIONARIES);
        AffineTransform topMatrix = top.containsKey(FONT_MATRIX) ? matrix(top.get(FONT_MATRIX)) : null;
        var matrices = new AffineTransform[array.count()];
        for (int i = 0; i < matrices.length; i++) {
            var dictionary = data.dictionary(array, i);
            var privateRange = dictionary.get(PRIVATE);
            if (privateRange == null || privateRange.size() != 2) throw invalid();
            data.range(integer(privateRange.get(1)), integer(privateRange.get(0)));
            AffineTransform local = dictionary.containsKey(FONT_MATRIX) ? matrix(dictionary.get(FONT_MATRIX)) : null;
            // 先从字形坐标进入 CID 字体坐标，再进入 em；缺省顶层时 FD 矩阵本身即为最终变换。
            matrices[i] = combine(topMatrix, local);
        }
        int[] dictionaries = select(data.from(offset(top, FD_SELECT)), glyphCount, matrices.length);
        int charsetOffset = offset(top, CHARSET);
        if (charsetOffset <= 2) throw invalid();
        int[] cids = charset(data.from(charsetOffset), glyphCount);
        for (int gid = 0; gid < glyphCount; gid++) {
            if (font.getCharset().getCIDForGID(gid) != cids[gid]
                    || font.getCharset().getGIDForCID(cids[gid]) != gid) throw invalid();
        }
        return new InvoiceOfdCidFont(cids, dictionaries, matrices);
    }

    int cid(int glyph) { return cids[glyph]; }

    AffineTransform matrixFor(int glyph) { return matrices[dictionaries[glyph]]; }

    private static AffineTransform combine(AffineTransform top, AffineTransform local) throws IOException {
        if (top == null) return local == null ? AffineTransform.getScaleInstance(DEFAULT_SCALE, DEFAULT_SCALE) : local;
        var result = new AffineTransform(top);
        if (local != null) result.concatenate(local);
        return validMatrix(result);
    }

    private static int[] select(ByteBuffer input, int glyphCount, int dictionaryCount) throws IOException {
        int format = unsigned(input, 1);
        var result = new int[glyphCount];
        if (format == 0) {
            for (int i = 0; i < glyphCount; i++) result[i] = unsigned(input, 1);
        } else if (format == 3) {
            int ranges = unsigned(input, 2), start = unsigned(input, 2);
            if (ranges < 1 || ranges > glyphCount || start != 0) throw invalid();
            for (int i = 0; i < ranges; i++) {
                int dictionary = unsigned(input, 1), next = unsigned(input, 2);
                if (next <= start || next > glyphCount || i == ranges - 1 && next != glyphCount) throw invalid();
                Arrays.fill(result, start, next, dictionary); start = next;
            }
        } else throw invalid();
        for (int value : result) if (value >= dictionaryCount) throw invalid();
        return result;
    }

    private static int[] charset(ByteBuffer input, int glyphCount) throws IOException {
        int format = unsigned(input, 1);
        if (format > 2) throw invalid();
        var result = new int[glyphCount];
        var seen = new boolean[MAX_CID + 1]; seen[0] = true;
        for (int gid = 1; gid < glyphCount;) {
            int first = unsigned(input, 2), left = format == 0 ? 0 : unsigned(input, format);
            if (first + left > MAX_CID || gid + left >= glyphCount) throw invalid();
            for (int cid = first; cid <= first + left; cid++) {
                if (seen[cid]) throw invalid();
                seen[cid] = true; result[gid++] = cid;
            }
        }
        return result;
    }
}
