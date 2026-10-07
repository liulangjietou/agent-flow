package io.agentflow.agent;

import io.agentflow.expense.InvoiceVerificationPort;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * OFD 内容处理进程使用的有界只读文件包；尚不表示页面渲染能力已经开放。
 * @author owlzhangfq@gmail.com
 */
final class InvoiceOfdArchive {
    private static final int MAX_ENTRIES = 1024;
    private static final int MAX_ENTRY_BYTES = 8 * 1024 * 1024;
    private static final long MAX_EXPANDED_BYTES = 64L * 1024 * 1024;
    private static final int MAX_PATH_LENGTH = 512;
    private static final int MAX_PATH_DEPTH = 32;
    private static final int BUFFER_BYTES = 8192;
    private final Map<String, byte[]> contents;
    private final ExpansionBudget budget;

    private InvoiceOfdArchive(Map<String, byte[]> contents, ExpansionBudget budget) {
        this.contents = Map.copyOf(contents);
        this.budget = budget;
    }

    /** 完整读取并核对包内每个文件，不按不可信 ZIP 名称访问宿主文件。 */
    static InvoiceOfdArchive read(Path source) throws IOException {
        return read(source, new ExpansionBudget());
    }

    private static InvoiceOfdArchive read(Path source, ExpansionBudget budget) throws IOException {
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) throw invalid();
        long size = Files.size(source);
        if (size < 1 || size > InvoiceVerificationPort.Request.MAX_ORIGINAL_BYTES) throw invalid();
        var contents = new HashMap<String, byte[]>();
        var directories = new HashSet<String>(); directories.add("");
        var declaredNames = new HashSet<String>();
        var canonicalNames = new HashMap<String, String>();
        try (var zip = new ZipFile(source.toFile())) {
            var entries = zip.entries();
            byte[] buffer = new byte[BUFFER_BYTES];
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (++budget.entries > MAX_ENTRIES || (entry.getMethod() != ZipEntry.STORED && entry.getMethod() != ZipEntry.DEFLATED)) throw invalid();
                String name = archiveName(entry);
                if (!declaredNames.add(name)) throw invalid();
                registerPath(name, entry.isDirectory(), contents.keySet(), directories, canonicalNames);
                if (entry.isDirectory()) {
                    if (entry.getSize() != 0 || entry.getCrc() != 0) throw invalid();
                    continue;
                }
                if (entry.getSize() < 0 || entry.getSize() > MAX_ENTRY_BYTES || entry.getCompressedSize() < 0) throw invalid();
                var output = new ByteArrayOutputStream(BUFFER_BYTES);
                var crc = new CRC32();
                try (var input = zip.getInputStream(entry)) {
                    int length;
                    while ((length = input.read(buffer)) != -1) {
                        budget.bytes += length;
                        if ((long) output.size() + length > MAX_ENTRY_BYTES || budget.bytes > MAX_EXPANDED_BYTES) throw invalid();
                        output.write(buffer, 0, length); crc.update(buffer, 0, length);
                    }
                }
                // ZipFile 依赖中央目录；必须另核对实际解压长度和 CRC，不能只信元数据。
                if (output.size() != entry.getSize() || crc.getValue() != entry.getCrc()) throw invalid();
                contents.put(name, output.toByteArray());
            }
        } catch (IllegalArgumentException malformedName) { throw invalid(); }
        if (!contents.containsKey("OFD.xml")) throw invalid();
        return new InvoiceOfdArchive(contents, budget);
    }

    /** 嵌套原件仍经 ZipFile 中央目录及 CRC 检查；只写一个私有临时容器，不展开不可信文件名。 */
    InvoiceOfdArchive nested(byte[] source) throws IOException {
        if (source.length < 1 || source.length > InvoiceVerificationPort.Request.MAX_ORIGINAL_BYTES) throw invalid();
        Path file = Files.createTempFile("agentflow-ofd-seal-", ".ofd");
        try {
            Files.write(file, source, StandardOpenOption.TRUNCATE_EXISTING);
            return read(file, budget);
        } finally { Files.deleteIfExists(file); }
    }

    Set<String> files() { return contents.keySet(); }
    int size(String file) throws IOException { return content(file).length; }
    InputStream open(String file) throws IOException { return new ByteArrayInputStream(content(file)); }

    /** OFD 的前导斜线表示包根；相对引用允许在包内返回父目录，但不允许越过包根。 */
    String file(String baseDirectory, String reference) throws IOException {
        String resolved = resolve(baseDirectory, reference);
        if (!contents.containsKey(resolved)) throw invalid();
        return resolved;
    }

    String directory(String baseDirectory, String reference) throws IOException {
        String resolved = resolve(baseDirectory, reference);
        // BaseLoc 是相对资源的定位基准，没有嵌入文件时该目录可以不存在。
        if (contents.containsKey(resolved)) throw invalid();
        return resolved;
    }

    /** 只写入新建的本次专用目录，不合并已有目录或恢复 ZIP 文件类型、权限。 */
    void materialize(Path destination) throws IOException {
        Files.createDirectory(destination);
        for (var entry : contents.entrySet()) {
            Path output = destination.resolve(entry.getKey());
            Files.createDirectories(output.getParent());
            Files.write(output, entry.getValue(), StandardOpenOption.CREATE_NEW);
        }
    }

    private byte[] content(String file) throws IOException {
        byte[] bytes = contents.get(file);
        if (bytes == null) throw invalid();
        return bytes;
    }

    private String resolve(String baseDirectory, String reference) throws IOException {
        if (baseDirectory == null || baseDirectory.length() > MAX_PATH_LENGTH || reference == null) throw invalid();
        if (!baseDirectory.isEmpty()) {
            for (String part : baseDirectory.split("/", -1)) requireSegment(part);
        }
        String value = reference.trim();
        if (value.isEmpty() || value.length() > MAX_PATH_LENGTH || value.startsWith("//")) throw invalid();
        if (value.equals("/")) return "";
        var parts = new ArrayDeque<String>();
        if (!value.startsWith("/") && !baseDirectory.isEmpty()) {
            for (String part : baseDirectory.split("/")) parts.addLast(part);
        }
        String relative = value.startsWith("/") ? value.substring(1) : value;
        for (String part : relative.split("/", -1)) {
            if (part.equals(".")) continue;
            if (part.equals("..")) {
                if (parts.isEmpty()) throw invalid();
                parts.removeLast();
            } else {
                requireSegment(part); parts.addLast(part);
            }
        }
        String resolved = String.join("/", parts);
        if (parts.size() > MAX_PATH_DEPTH || resolved.length() > MAX_PATH_LENGTH) throw invalid();
        return resolved;
    }

    private static String archiveName(ZipEntry entry) throws IOException {
        String name = entry.getName();
        if (name.length() > MAX_PATH_LENGTH) throw invalid();
        if (entry.isDirectory()) name = name.substring(0, name.length() - 1);
        String[] segments = name.split("/", -1);
        if (segments.length > MAX_PATH_DEPTH) throw invalid();
        for (String part : segments) requireSegment(part);
        return name;
    }

    private static void requireSegment(String part) throws IOException {
        if (part.isBlank() || !part.equals(part.trim()) || part.endsWith(".") || part.equals("..")) throw invalid();
        for (int i = 0; i < part.length(); i++) {
            char value = part.charAt(i);
            if (Character.isISOControl(value) || "\\:*?\"<>|#%".indexOf(value) >= 0) throw invalid();
        }
    }

    private static void registerPath(String path, boolean directory, Set<String> files, Set<String> directories,
                                     Map<String, String> canonicalNames) throws IOException {
        String[] parts = path.split("/");
        String current = "";
        for (int i = 0; i < parts.length; i++) {
            current = current.isEmpty() ? parts[i] : current + "/" + parts[i];
            String key = Normalizer.normalize(current, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
            String previous = canonicalNames.putIfAbsent(key, current);
            if (previous != null && !previous.equals(current)) throw invalid();
            boolean isDirectory = i < parts.length - 1 || directory;
            if (isDirectory) {
                if (files.contains(current)) throw invalid();
                directories.add(current);
            } else if (directories.contains(current)) throw invalid();
        }
    }

    private static IOException invalid() { return new IOException("OFD archive is invalid, ambiguous or exceeds its limits"); }

    /**
     * 正文与所有嵌套容器累计计算解压量及条目数，不能通过每层重新读包重置额度。
     * @author owlzhangfq@gmail.com
     */
    private static final class ExpansionBudget {
        private long bytes;
        private int entries;
    }
}
