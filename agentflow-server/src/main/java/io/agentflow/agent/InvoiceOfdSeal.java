package io.agentflow.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import static io.agentflow.agent.InvoiceOfdXml.invalid;

/**
 * 只从 SES v1/v4 的明确 DER 结构读取印章图像，不验证签名、证书或图像内容的真实性。
 * @author owlzhangfq@gmail.com
 */
final class InvoiceOfdSeal {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_NODES = 4096;
    private static final int MAX_IMAGE_MILLIMETERS = 1000;
    private static final int SEQUENCE = 0x30;
    private static final int INTEGER = 0x02;
    private static final int BIT_STRING = 0x03;
    private static final int OCTET_STRING = 0x04;
    private static final int OBJECT_IDENTIFIER = 0x06;
    private static final int IA5_STRING = 0x16;
    private static final int GENERALIZED_TIME = 0x18;
    private static final int EXTENSION = 0xa0;
    private final byte[] bytes;
    private int nodes;
    private int offset;

    private InvoiceOfdSeal(byte[] bytes) { this.bytes = bytes; }

    /** signedValue 区分整个签章值与 Seal/BaseLoc；不能在证书或其他字段中搜索图片头。 */
    static Picture read(byte[] bytes, boolean signedValue) throws IOException {
        if (bytes.length < 1 || bytes.length > MAX_BYTES) throw invalid();
        var reader = new InvoiceOfdSeal(bytes);
        Node root = reader.node(bytes.length, 0);
        if (reader.offset != bytes.length) throw invalid();
        Node seal = signedValue ? reader.signature(root) : root;
        List<Node> fields = sequence(seal, 2, 4);
        List<Node> info = sequence(fields.get(0), 4, 5);
        List<Node> header = sequence(info.get(0), 3, 3);
        if (!reader.string(header.get(0)).equals("ES")) throw invalid();
        int version = reader.integer(header.get(1));
        reader.string(header.get(2)); reader.string(info.get(1));
        sequence(info.get(2), 1, MAX_NODES);
        if (version == 1) {
            if (fields.size() != 2) throw invalid();
            List<Node> signing = sequence(fields.get(1), 3, 3);
            tags(signing, OCTET_STRING, OBJECT_IDENTIFIER, BIT_STRING);
        } else if (version == 4) {
            if (fields.size() != 4) throw invalid();
            tags(fields.subList(1, 4), OCTET_STRING, OBJECT_IDENTIFIER, BIT_STRING);
        } else throw invalid();
        if (signedValue && reader.integer(sequence(sequence(root, 2, 5).get(0), 5, 7).get(0)) != version) throw invalid();
        if (info.size() == 5 && info.get(4).tag() != SEQUENCE && info.get(4).tag() != EXTENSION) throw invalid();
        List<Node> picture = sequence(info.get(3), 4, 4);
        String format = reader.string(picture.get(0)).toUpperCase(Locale.ROOT);
        if (format.equals("JPG")) format = "JPEG";
        if (!Set.of("PNG", "JPEG", "OFD").contains(format)) throw invalid();
        require(picture.get(1), OCTET_STRING);
        int width = reader.integer(picture.get(2)), height = reader.integer(picture.get(3));
        if (width < 1 || height < 1 || width > MAX_IMAGE_MILLIMETERS || height > MAX_IMAGE_MILLIMETERS) throw invalid();
        return new Picture(format, reader.value(picture.get(1)));
    }

    private Node signature(Node root) throws IOException {
        List<Node> fields = sequence(root, 2, 5);
        List<Node> body = sequence(fields.get(0), 5, 7);
        int version = integer(body.get(0));
        if (version == 1) {
            if (fields.size() != 2 || body.size() != 7) throw invalid();
            require(fields.get(1), BIT_STRING);
            for (int i : new int[] {2, 3}) if (body.get(i).tag() != BIT_STRING && body.get(i).tag() != OCTET_STRING) throw invalid();
            tags(body.subList(4, 7), IA5_STRING, OCTET_STRING, OBJECT_IDENTIFIER);
        } else if (version == 4) {
            if (fields.size() < 4 || body.size() > 6) throw invalid();
            tags(fields.subList(1, 4), OCTET_STRING, OBJECT_IDENTIFIER, BIT_STRING);
            tags(body.subList(2, 5), GENERALIZED_TIME, BIT_STRING, IA5_STRING);
            if (fields.size() == 5) require(fields.get(4), EXTENSION);
            if (body.size() == 6) require(body.get(5), EXTENSION);
        } else throw invalid();
        return body.get(1);
    }

    /** 确定长度、深度和节点限额先于任何字段提取；拒绝 BER 不定长、截断及尾随内容。 */
    private Node node(int end, int depth) throws IOException {
        if (depth > MAX_DEPTH || ++nodes > MAX_NODES || end - offset < 2) throw invalid();
        int tag = Byte.toUnsignedInt(bytes[offset++]);
        if ((tag & 0x1f) == 0x1f || tag == 0) throw invalid();
        int size = Byte.toUnsignedInt(bytes[offset++]);
        if ((size & 0x80) != 0) {
            int count = size & 0x7f;
            if (count < 1 || count > 3 || end - offset < count || bytes[offset] == 0) throw invalid();
            size = 0;
            for (int i = 0; i < count; i++) size = (size << 8) | Byte.toUnsignedInt(bytes[offset++]);
            if (size < 128) throw invalid();
        }
        if (size > end - offset) throw invalid();
        int start = offset, limit = offset + size;
        var children = new ArrayList<Node>();
        if ((tag & 0x20) != 0) {
            while (offset < limit) children.add(node(limit, depth + 1));
        } else offset = limit;
        return new Node(tag, start, limit, List.copyOf(children));
    }

    private int integer(Node node) throws IOException {
        require(node, INTEGER);
        int length = node.end() - node.start();
        if (length < 1 || length > 4 || bytes[node.start()] < 0
                || length > 1 && bytes[node.start()] == 0 && bytes[node.start() + 1] >= 0) throw invalid();
        int result = 0;
        for (int i = node.start(); i < node.end(); i++) result = (result << 8) | Byte.toUnsignedInt(bytes[i]);
        return result;
    }

    private String string(Node node) throws IOException {
        require(node, IA5_STRING);
        if (node.end() - node.start() > 256 || node.end() == node.start()) throw invalid();
        for (int i = node.start(); i < node.end(); i++) if (bytes[i] < 0x20 || bytes[i] > 0x7e) throw invalid();
        return new String(bytes, node.start(), node.end() - node.start(), StandardCharsets.US_ASCII);
    }

    private byte[] value(Node node) throws IOException {
        if (node.end() == node.start()) throw invalid();
        return Arrays.copyOfRange(bytes, node.start(), node.end());
    }

    private static List<Node> sequence(Node node, int min, int max) throws IOException {
        require(node, SEQUENCE);
        if (node.children().size() < min || node.children().size() > max) throw invalid();
        return node.children();
    }

    private static void tags(List<Node> nodes, int... tags) throws IOException {
        for (int i = 0; i < tags.length; i++) require(nodes.get(i), tags[i]);
    }
    private static void require(Node node, int tag) throws IOException { if (node.tag() != tag) throw invalid(); }

    /**
     * 有界图片字节只在隔离进程内流转；显示尺寸最终由 StampAnnot 的 Boundary 决定。
     * @author owlzhangfq@gmail.com
     */
    record Picture(String format, byte[] bytes) { }
    /**
     * @author owlzhangfq@gmail.com
     */
    private record Node(int tag, int start, int end, List<Node> children) { }
}
