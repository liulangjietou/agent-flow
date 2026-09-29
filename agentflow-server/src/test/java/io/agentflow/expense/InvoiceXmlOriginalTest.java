package io.agentflow.expense;

import com.sun.net.httpserver.HttpServer;
import io.agentflow.common.DomainException;
import io.agentflow.storage.LocalDocumentStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;

/**
 * 原件结构校验不执行 XML 内容；覆盖编码、外部访问、资源边界和不可变存储。
 * @author owlzhangfq@gmail.com
 */
class InvoiceXmlOriginalTest {
    private final Path directory = Path.of("/fyoung/tmp/agentflow-invoice-xml-" + UUID.randomUUID());
    private final InvoiceOriginalFiles files = new InvoiceOriginalFiles(new LocalDocumentStore(directory.toString(), InvoiceVerificationPort.Request.MAX_ORIGINAL_BYTES));

    @AfterEach void cleanOwnedFixture() throws Exception {
        try (var paths = Files.list(directory)) { for (Path path : paths.toList()) Files.deleteIfExists(path); }
        Files.deleteIfExists(directory);
    }

    @Test
    void utf8BomUtf16NamespacesAndSignatureRemainExactlyTheOriginalBytes() throws Exception {
        String xml = "<i:Invoice xmlns:i='urn:synthetic' xmlns:ds='http://www.w3.org/2000/09/xmldsig#'><i:Name>合成票据 &amp; 原件</i:Name><!--原注释--><i:Memo><![CDATA[<raw>]]></i:Memo><ds:Signature><ds:SignatureValue>fixture</ds:SignatureValue></ds:Signature></i:Invoice>";
        for (byte[] bytes : List.of(xml.getBytes(StandardCharsets.UTF_8), ("\ufeff" + xml).getBytes(StandardCharsets.UTF_8),
                ("<?xml version='1.0' encoding='UTF-16'?>" + xml).getBytes(StandardCharsets.UTF_16))) {
            var original = original(bytes);
            for (int attempt = 0; attempt < 2; attempt++) {
                Path staged = files.stage(original, new ByteArrayInputStream(bytes));
                try { files.publish(original, staged); } finally { files.discard(staged); }
            }
            assertThat(files.read(original)).isEqualTo(bytes);
            var request = new InvoiceVerificationPort.Request("alice", UUID.randomUUID(), original.id(), original.sha256(), original.format().mediaType(), files.read(original));
            assertThat(request.mediaType()).isEqualTo("application/xml");
            assertThat(request.original()).isEqualTo(bytes);
            request.original()[0] = 0;
            assertThat(request.original()).isEqualTo(bytes);
        }
        assertNoStagedFiles();
    }

    @Test
    void malformedDocumentsAndAnyDoctypeNeverBecomeStoredOriginals() throws Exception {
        for (String xml : List.of("<Invoice>", "<Invoice/><Other/>", "text", "<Invoice>&undefined;</Invoice>",
                "<Invoice amount='1' amount='2'/>", "<!DOCTYPE Invoice><Invoice/>",
                "<!DOCTYPE Invoice [<!ENTITY item 'payload'>]><Invoice>&item;</Invoice>",
                "<!DOCTYPE Invoice [<!ENTITY a '123'><!ENTITY b '&a;&a;'><!ENTITY c '&b;&b;'>]><Invoice>&c;</Invoice>")) {
            reject(xml);
        }
        try (var paths = Files.list(directory)) { assertThat(paths.toList()).isEmpty(); }
    }

    @Test
    void externalDtdEntitiesStylesheetsSchemasAndIncludesCannotFetchAnything() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { calls.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/sensitive";
            Path local = directory.resolve("sensitive.txt"); Files.writeString(local, "private-test-marker");
            reject("<!DOCTYPE Invoice SYSTEM '" + url + "'><Invoice/>");
            reject("<!DOCTYPE Invoice [<!ENTITY x SYSTEM '" + url + "'>]><Invoice>&x;</Invoice>");
            reject("<!DOCTYPE Invoice [<!ENTITY % x SYSTEM '" + url + "'>%x;]><Invoice/>");
            reject("<!DOCTYPE Invoice [<!ENTITY x SYSTEM '" + local.toUri() + "'>]><Invoice>&x;</Invoice>");
            // 引用仅作为原文保留；不会加载样式表、验证外部 schema 或展开 XInclude。
            String passive = "<?xml-stylesheet type='text/xsl' href='" + url + "'?>"
                    + "<Invoice xmlns:xi='http://www.w3.org/2001/XInclude' xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance' xsi:noNamespaceSchemaLocation='" + url + "'>"
                    + "<xi:include href='" + url + "' parse='text'/></Invoice>";
            byte[] bytes = passive.getBytes(StandardCharsets.UTF_8);
            Path staged = files.stage(original(bytes), new ByteArrayInputStream(bytes)); files.discard(staged);
            assertThat(calls.get()).isZero();
            assertThat(Files.readString(local)).isEqualTo("private-test-marker");
        } finally { server.stop(0); }
    }

    @Test
    void depthElementAndAttributeLimitsIncludeNamespaceDeclarations() throws Exception {
        accept("<n>".repeat(64) + "</n>".repeat(64));
        reject("<n>".repeat(65) + "</n>".repeat(65));
        accept("<Invoice>" + "<n/>".repeat(99_999) + "</Invoice>");
        reject("<Invoice>" + "<n/>".repeat(100_000) + "</Invoice>");
        accept("<Invoice" + attributes(64, false) + "/>");
        reject("<Invoice" + attributes(65, false) + "/>");
        accept("<Invoice" + attributes(64, true) + "/>");
        reject("<Invoice" + attributes(65, true) + "/>");
        assertNoStagedFiles();
    }

    @Test
    void invalidRepeatedUploadCannotModifyPublishedOriginal() throws Exception {
        byte[] bytes = "<Invoice>原文</Invoice>".getBytes(StandardCharsets.UTF_8);
        var original = original(bytes); Path staged = files.stage(original, new ByteArrayInputStream(bytes));
        try { files.publish(original, staged); } finally { files.discard(staged); }
        assertThatThrownBy(() -> files.stage(original, new ByteArrayInputStream("<Invoice>变更</Invoice>".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOfSatisfying(DomainException.class, failure -> assertThat(failure.code()).isEqualTo("FILE_CONTENT_MISMATCH"));
        assertThat(files.read(original)).isEqualTo(bytes);
        assertNoStagedFiles();
    }

    private void accept(String xml) throws Exception {
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8); Path staged = files.stage(original(bytes), new ByteArrayInputStream(bytes)); files.discard(staged);
    }
    private void reject(String xml) throws Exception {
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8); var original = original(bytes);
        assertThatThrownBy(() -> files.stage(original, new ByteArrayInputStream(bytes))).isInstanceOfSatisfying(DomainException.class, failure -> {
            assertThat(failure.code()).isEqualTo("INVOICE_ORIGINAL_FORMAT_MISMATCH");
            assertThat(failure.getMessage()).doesNotContain("private-test-marker", "sensitive", xml);
        });
        assertThat(Files.exists(directory.resolve(original.id() + ".bin"))).isFalse(); assertNoStagedFiles();
    }
    private InvoiceOriginal original(byte[] bytes) throws Exception {
        return new InvoiceOriginal(UUID.randomUUID(), UUID.randomUUID(), "demo", "alice", "合成.xml", bytes.length,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), InvoiceOriginal.Format.XML, Instant.now(), InvoiceOriginal.Status.UPLOADING);
    }
    private static String attributes(int count, boolean namespaces) {
        return IntStream.range(0, count).mapToObj(index -> (namespaces ? " xmlns:p" : " a") + index + "='urn:" + index + "'").collect(java.util.stream.Collectors.joining());
    }
    private void assertNoStagedFiles() throws Exception {
        try (var paths = Files.list(directory)) { assertThat(paths.noneMatch(path -> path.getFileName().toString().endsWith(".part"))).isTrue(); }
    }
}
