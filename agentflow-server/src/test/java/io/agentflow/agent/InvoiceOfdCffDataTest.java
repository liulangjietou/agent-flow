package io.agentflow.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import static io.agentflow.agent.InvoiceOfdCffData.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 原始 CFF 元数据的边界测试，避免字体库的容错解析掩盖编号或矩阵错误。
 * @author owlzhangfq@gmail.com
 */
class InvoiceOfdCffDataTest {
    @Test
    void preservesSignedIntegersRealsAndTheAbsenceOfTheTopMatrix() throws Exception {
        var data = new InvoiceOfdCffData(cff(32, 247, 0, 251, 0, 28, 128, 0,
                29, 127, 255, 255, 255, 30, 0xe2, 0xa2, 0x5f, 12, 7)).top();
        assertThat(data.get(FONT_MATRIX)).containsExactly(-107, 108, -108, -32768, Integer.MAX_VALUE, -2.25);
        assertThat(new InvoiceOfdCffData(cff(30, 0xa1, 0xc3, 0xff, 5)).top().get(5))
                .containsExactly(.0001);
        assertThat(new InvoiceOfdCffData(cff(139, 17)).top()).doesNotContainKey(FONT_MATRIX);
    }

    @Test
    void rejectsTruncatedReversedAndOverflowingIndexOffsets() throws Exception {
        for (byte[] bytes : new byte[][]{
                {0, 1, 0, 0}, {0, 1, 5, 0}, {0, 1, 1, 0, 1}, {0, 2, 1, 1, 3, 2, 0},
                {0, 1, 1, 1, 4, 0}, {0, 1, 4, 0, 0, 0, 1, -1, -1, -1, -1}}) {
            assertThatThrownBy(() -> new InvoiceOfdCffData(bytes).index(0, 2)).isInstanceOf(IOException.class);
        }
        assertThatThrownBy(() -> new InvoiceOfdCffData(cff(139, 17)).index(Integer.MAX_VALUE, 1))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> new InvoiceOfdCffData(cff(139, 17)).index(4, 0)).isInstanceOf(IOException.class);
    }

    @Test
    void rejectsAmbiguousDictionariesReservedTokensAndUnconsumedOperands() {
        for (int[] dictionary : new int[][]{
                {139, 17, 140, 17}, {139}, {17}, {139, 12}, {22}, {31}, {255}, {28, 1}, {29, 1, 2}}) {
            rejects(dictionary);
        }
    }

    @Test
    void boundsDictionarySizeOperandStackAndRealLexemes() throws Exception {
        int[] operands = new int[49]; Arrays.fill(operands, 139); operands[48] = 5;
        assertThat(new InvoiceOfdCffData(cff(operands)).top().get(5)).hasSize(48);
        int[] overflow = new int[50]; Arrays.fill(overflow, 139); overflow[49] = 5;
        rejects(overflow);
        int[] huge = new int[65_537]; Arrays.fill(huge, 139); huge[huge.length - 1] = 5;
        rejects(huge);
        int[] longReal = new int[36]; Arrays.fill(longReal, 0x11);
        longReal[0] = 30; longReal[34] = 0xff; longReal[35] = 5; rejects(longReal);
    }

    @Test
    void rejectsMalformedAndNonFiniteRealNumbersInsteadOfDefaultingToZero() {
        for (int[] dictionary : new int[][]{
                {30, 0xff, 5}, {30, 0x1d, 0xff, 5}, {30, 0x1f, 5, 5}, {30, 0xf0, 5},
                {30, 0x1a, 0xaf, 5}, {30, 0x1b, 0xff, 5}, {30, 0x1b, 0x99, 0x9f, 5},
                {30, 0x11}}) rejects(dictionary);
    }

    @Test
    void rejectsRealOffsetsAndInvalidMatrixComponents() throws Exception {
        var data = new InvoiceOfdCffData(cff(30, 0x1f, 17)).top();
        assertThatThrownBy(() -> offset(data, 17)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> offset(data, FD_ARRAY)).isInstanceOf(IOException.class);
        for (List<Number> values : List.<List<Number>>of(List.of(1, 0, 0, 0, 0, 0),
                List.of(1, 0, 0, 1, Double.NaN, 0), List.of(1, 0, 0, 1, 0, Double.POSITIVE_INFINITY),
                List.of(Double.MAX_VALUE, 0, 0, Double.MAX_VALUE, 0, 0), List.of(1, 0, 0, 1))) {
            assertThatThrownBy(() -> matrix(values)).isInstanceOf(IOException.class);
        }
    }

    @Test
    void requiresCffOneHeaderAndASingleOpenTypeName() {
        byte[] version = cff(139, 17); version[0] = 2;
        byte[] headerSize = cff(139, 17); headerSize[2] = 3;
        byte[] offsetSize = cff(139, 17); offsetSize[3] = 5;
        byte[] names = cff(139, 17); names[5] = 2;
        for (byte[] bytes : new byte[][]{version, headerSize, offsetSize, names}) {
            assertThatThrownBy(() -> new InvoiceOfdCffData(bytes).top()).isInstanceOf(IOException.class);
        }
    }

    private static void rejects(int... dictionary) {
        assertThatThrownBy(() -> new InvoiceOfdCffData(cff(dictionary)).top()).isInstanceOf(IOException.class);
    }

    private static byte[] cff(int... dictionary) {
        var bytes = new ByteArrayOutputStream();
        bytes.writeBytes(new byte[]{1, 0, 4, 1, 0, 1, 1, 1, 2, 'A', 0, 1, 4});
        bytes.writeBytes(ByteBuffer.allocate(8).putInt(1).putInt(dictionary.length + 1).array());
        for (int value : dictionary) bytes.write(value);
        return bytes.toByteArray();
    }
}
