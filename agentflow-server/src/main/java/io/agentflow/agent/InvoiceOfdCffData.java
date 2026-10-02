package io.agentflow.agent;

import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 只读取 CFF1 字典、索引和映射元数据；字形程序仍交给 FontBox 解释。
 * @author owlzhangfq@gmail.com
 */
final class InvoiceOfdCffData {
    static final int FONT_MATRIX = 0x0c07;
    static final int FD_ARRAY = 0x0c24;
    static final int FD_SELECT = 0x0c25;
    static final int CHARSET = 15;
    static final int CHAR_STRINGS = 17;
    static final int PRIVATE = 18;
    private static final int MAX_DICT_BYTES = 64 * 1024;
    private static final int MAX_OPERANDS = 48;
    private static final int MAX_REAL_CHARACTERS = 64;
    private static final Pattern REAL = Pattern.compile("-?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:E-?[0-9]+)?");
    private final byte[] bytes;

    InvoiceOfdCffData(byte[] bytes) throws IOException {
        if (bytes.length < 4 || bytes.length > InvoiceOfdFont.MAX_FONT_BYTES) throw invalid();
        this.bytes = bytes;
    }

    /** OpenType 的 CFF 表只含一个字体，保留原始顶层矩阵是否存在这一事实。 */
    Map<Integer, List<Number>> top() throws IOException {
        var header = range(0, 4);
        int major = unsigned(header, 1), minor = unsigned(header, 1);
        int size = unsigned(header, 1), offsetSize = unsigned(header, 1);
        if (major != 1 || minor != 0 || size < 4 || offsetSize < 1 || offsetSize > 4) throw invalid();
        var names = index(size, 1);
        var dictionaries = index(names.end(), 1);
        return dictionary(dictionaries, 0);
    }

    Index index(int offset, int maximum) throws IOException {
        var input = range(offset, bytes.length - offset);
        int count = unsigned(input, 2);
        if (count < 1 || count > maximum) throw invalid();
        int size = unsigned(input, 1);
        if (size < 1 || size > 4) throw invalid();
        long data = (long) offset + input.position() + (long) (count + 1) * size;
        if (data > bytes.length) throw invalid();
        int[] starts = new int[count + 1];
        long previous = 1;
        for (int i = 0; i <= count; i++) {
            long value = Integer.toUnsignedLong(unsigned(input, size));
            if (value < previous || i == 0 && value != 1 || data + value - 1 > bytes.length) throw invalid();
            starts[i] = (int) (data + value - 1); previous = value;
        }
        return new Index(starts);
    }

    Map<Integer, List<Number>> dictionary(Index index, int entry) throws IOException {
        int start = index.starts()[entry], length = index.starts()[entry + 1] - start;
        if (length < 1 || length > MAX_DICT_BYTES) throw invalid();
        var input = range(start, length);
        var values = new HashMap<Integer, List<Number>>();
        var operands = new ArrayList<Number>();
        while (input.hasRemaining()) {
            int token = unsigned(input, 1);
            if (token <= 21) {
                int operator = token == 12 ? 0x0c00 | unsigned(input, 1) : token;
                if (operands.isEmpty() || values.putIfAbsent(operator, List.copyOf(operands)) != null) throw invalid();
                operands.clear();
            } else {
                if (operands.size() >= MAX_OPERANDS) throw invalid();
                operands.add(number(input, token));
            }
        }
        if (!operands.isEmpty()) throw invalid();
        return values;
    }

    ByteBuffer range(int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || (long) offset + length > bytes.length) throw invalid();
        return ByteBuffer.wrap(bytes, offset, length).slice();
    }

    ByteBuffer from(int offset) throws IOException { return range(offset, bytes.length - offset); }

    static int offset(Map<Integer, List<Number>> dictionary, int key) throws IOException {
        var value = dictionary.get(key);
        if (value == null || value.size() != 1) throw invalid();
        return integer(value.get(0));
    }

    static int integer(Number number) throws IOException {
        if (!(number instanceof Integer value) || value < 0) throw invalid();
        return value;
    }

    static int unsigned(ByteBuffer input, int count) throws IOException {
        if (input.remaining() < count) throw invalid();
        int value = 0;
        for (int i = 0; i < count; i++) value = (value << 8) | Byte.toUnsignedInt(input.get());
        return value;
    }

    static AffineTransform matrix(List<? extends Number> matrix) throws IOException {
        if (matrix == null || matrix.size() != 6) throw invalid();
        double[] values = new double[6];
        for (int i = 0; i < values.length; i++) values[i] = matrix.get(i).doubleValue();
        return validMatrix(new AffineTransform(values));
    }

    static AffineTransform validMatrix(AffineTransform matrix) throws IOException {
        double[] values = new double[6]; matrix.getMatrix(values);
        for (double value : values) if (!Double.isFinite(value)) throw invalid();
        if (matrix.getDeterminant() == 0 || !Double.isFinite(matrix.getDeterminant())) throw invalid();
        return matrix;
    }

    private static Number number(ByteBuffer input, int token) throws IOException {
        if (token >= 32 && token <= 246) return token - 139;
        if (token >= 247 && token <= 250) return (token - 247) * 256 + unsigned(input, 1) + 108;
        if (token >= 251 && token <= 254) return -(token - 251) * 256 - unsigned(input, 1) - 108;
        if (token == 28) return (int) (short) unsigned(input, 2);
        if (token == 29) return unsigned(input, 4);
        if (token != 30) throw invalid();
        var text = new StringBuilder();
        while (text.length() <= MAX_REAL_CHARACTERS) {
            int pair = unsigned(input, 1);
            for (int shift = 4; shift >= 0; shift -= 4) {
                int nibble = pair >> shift & 15;
                if (nibble == 15) {
                    if (shift == 4 && (pair & 15) != 15 || text.length() > MAX_REAL_CHARACTERS || !REAL.matcher(text).matches()) throw invalid();
                    double value = Double.parseDouble(text.toString());
                    if (!Double.isFinite(value)) throw invalid();
                    return value;
                }
                if (nibble <= 9) text.append((char) ('0' + nibble));
                else text.append(switch (nibble) { case 10 -> "."; case 11 -> "E"; case 12 -> "E-"; case 14 -> "-"; default -> throw invalid(); });
            }
        }
        throw invalid();
    }

    static IOException invalid() { return new IOException("OFD CFF metadata is invalid, ambiguous or exceeds its limits"); }

    /**
     * 不复制索引内容，只保存已经过边界验证的绝对起止位置。
     * @author owlzhangfq@gmail.com
     */
    record Index(int[] starts) {
        int count() { return starts.length - 1; }
        int end() { return starts[starts.length - 1]; }
    }
}
