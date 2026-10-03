package io.agentflow.agent;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.JsonUtil;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/**
 * 一次渲染内的字体所有者：包内字体优先，部署字体只能来自调用方指定的可信清单。
 * @author owlzhangfq@gmail.com
 */
final class InvoiceOfdFonts implements Closeable {
    private static final int MAX_CATALOG_BYTES = 64 * 1024;
    private static final int MAX_MAPPINGS = 64;
    private static final int MAX_LOADED_FONTS = 64;
    private static final int MAX_LOADED_BYTES = 64 * 1024 * 1024;
    private static final int MAX_NAME_LENGTH = 256;
    private static final int MAX_PATH_LENGTH = 4096;
    private static final int COLLECTION_TAG = 0x74746366;
    private static final Set<String> MAPPING_FIELDS = Set.of("name", "bold", "italic", "file", "face", "sha256");
    private static final JsonUtil JSON = new JsonUtil(new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(MAX_PATH_LENGTH).build()).build()));
    private final InvoiceOfdArchive archive;
    private final Map<Declaration, Mapping> deployed;
    private final Map<Source, InvoiceOfdFont> loaded = new HashMap<>();
    private int loadedBytes;
    private boolean closed;

    private InvoiceOfdFonts(InvoiceOfdArchive archive, Map<Declaration, Mapping> deployed) {
        this.archive = archive;
        this.deployed = deployed;
    }

    /** 清单由渲染调用方给出；null 表示仅允许包内字体，不扫描系统目录。 */
    static InvoiceOfdFonts open(InvoiceOfdArchive archive, Path catalog) throws IOException {
        return new InvoiceOfdFonts(archive, catalog == null ? Map.of() : readCatalog(catalog));
    }

    /** 按已解析的包内文件缓存；集合字体仍须与文档指定的 PostScript 名称精确匹配。 */
    InvoiceOfdFont embedded(String file, String name) throws IOException {
        requireOpen();
        var key = new Source(true, file, name);
        var cached = loaded.get(key);
        if (cached != null) return cached;
        reserve(archive.size(file));
        byte[] bytes;
        try (var input = archive.open(file)) { bytes = input.readAllBytes(); }
        String face = bytes.length >= Integer.BYTES && ByteBuffer.wrap(bytes).getInt() == COLLECTION_TAG ? name : null;
        return remember(key, bytes, face);
    }

    /** 名称及粗斜体提示只能匹配显式映射；不按字族、相似名称或文档路径寻找字体。 */
    InvoiceOfdFont deployed(String name, boolean bold, boolean italic) throws IOException {
        requireOpen();
        Mapping mapping = deployed.get(new Declaration(name, bold, italic));
        if (mapping == null) throw new IOException("OFD deployed font mapping is absent");
        var key = new Source(false, mapping.file().toString(), mapping.face());
        var cached = loaded.get(key);
        if (cached != null) return cached;
        if (loaded.size() >= MAX_LOADED_FONTS) throw limitExceeded();
        // 限制实际读取量后校验摘要，再交给字体解析器；同次渲染后续页复用已核验的字体。
        byte[] bytes = readHostFile(mapping.file(), Math.min(InvoiceOfdFont.MAX_FONT_BYTES, MAX_LOADED_BYTES - loadedBytes));
        if (!mapping.sha256().equals(sha256(bytes))) throw new IOException("OFD deployed font digest does not match");
        reserve(bytes.length);
        return remember(key, bytes, mapping.face());
    }

    private InvoiceOfdFont remember(Source key, byte[] bytes, String face) throws IOException {
        var font = InvoiceOfdFont.load(bytes, face);
        loaded.put(key, font);
        return font;
    }

    private void reserve(int bytes) throws IOException {
        if (bytes < 1 || bytes > InvoiceOfdFont.MAX_FONT_BYTES || loaded.size() >= MAX_LOADED_FONTS
                || bytes > MAX_LOADED_BYTES - loadedBytes) throw limitExceeded();
        // 同一集合中的不同字体面分别持有解析器资源，按实际加载次数计费。
        loadedBytes += bytes;
    }

    private static Map<Declaration, Mapping> readCatalog(Path file) throws IOException {
        try {
            String raw = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(readHostFile(file, MAX_CATALOG_BYTES))).toString();
            JsonNode root = JSON.readStrict(raw, JsonNode.class);
            fields(root, Set.of("fonts"));
            JsonNode fonts = root.get("fonts");
            if (fonts == null || !fonts.isArray() || fonts.size() > MAX_MAPPINGS) throw invalidCatalog();
            var result = new HashMap<Declaration, Mapping>();
            var hashes = new HashMap<Path, String>();
            for (JsonNode font : fonts) {
                fields(font, MAPPING_FIELDS);
                var declaration = new Declaration(string(font, "name", MAX_NAME_LENGTH), bool(font, "bold"), bool(font, "italic"));
                Path location = Path.of(string(font, "file", MAX_PATH_LENGTH));
                if (!location.isAbsolute()) throw invalidCatalog();
                location = location.normalize();
                String face = string(font, "face", MAX_NAME_LENGTH), hash = string(font, "sha256", 64);
                if (!hash.matches("[0-9a-f]{64}")) throw invalidCatalog();
                String previous = hashes.putIfAbsent(location, hash);
                if (previous != null && !previous.equals(hash)) throw invalidCatalog();
                if (result.putIfAbsent(declaration, new Mapping(location, face, hash)) != null) throw invalidCatalog();
            }
            return Map.copyOf(result);
        } catch (RuntimeException malformed) { throw new IOException("OFD font catalog is invalid", malformed); }
    }

    private static void fields(JsonNode node, Set<String> allowed) throws IOException {
        if (node == null || !node.isObject()) throw invalidCatalog();
        var names = node.fieldNames();
        while (names.hasNext()) if (!allowed.contains(names.next())) throw invalidCatalog();
    }

    private static String string(JsonNode node, String field, int maximum) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw invalidCatalog();
        String text = value.textValue();
        if (text.isBlank() || text.length() > maximum || text.codePoints().anyMatch(Character::isISOControl)) throw invalidCatalog();
        return text;
    }

    private static boolean bool(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null) return false;
        if (!value.isBoolean()) throw invalidCatalog();
        return value.booleanValue();
    }

    /** 只读取部署方指定的普通文件；以打开后的实际长度限制内存，不跟随文件本身的符号链接。 */
    private static byte[] readHostFile(Path file, int maximum) throws IOException {
        if (!file.isAbsolute() || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw invalidCatalog();
        try (var channel = Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size < 1 || size > maximum) throw limitExceeded();
            byte[] bytes = Channels.newInputStream(channel).readNBytes((int) size + 1);
            if (bytes.length != size) throw new IOException("OFD font file changed while reading");
            return bytes;
        }
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 is unavailable", unavailable); }
    }

    private void requireOpen() throws IOException { if (closed) throw new IOException("OFD font session is already closed"); }
    private static IOException invalidCatalog() { return new IOException("OFD font catalog is invalid"); }
    private static IOException limitExceeded() { return new IOException("OFD font resources exceed loading limits"); }

    /** 整次渲染成功或失败后释放所有字体；一个关闭异常不能阻断其余资源释放。 */
    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        for (var font : loaded.values()) {
            try { font.close(); }
            catch (IOException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        }
        loaded.clear();
        if (failure != null) throw failure;
    }

    /**
     * 字体声明中的粗斜体属于选字提示，不在此处合成字形变换。
     * @author owlzhangfq@gmail.com
     */
    private record Declaration(String name, boolean bold, boolean italic) { }

    /**
     * 可信部署清单指定的文件、字体面及预期文件摘要。
     * @author owlzhangfq@gmail.com
     */
    private record Mapping(Path file, String face, String sha256) { }

    /**
     * 包内文件与部署文件使用独立身份；不同资源 ID 可共享同一字体实例。
     * @author owlzhangfq@gmail.com
     */
    private record Source(boolean embedded, String file, String face) { }
}
