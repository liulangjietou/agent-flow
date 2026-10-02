package io.agentflow.agent;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.agentflow.agent.InvoiceOfdArchiveTest.bytes;
import static io.agentflow.agent.InvoiceOfdArchiveTest.zip;
import static org.assertj.core.api.Assertions.*;

/**
 * 按 OFD 包内文档和资源的实际关系验证页码来源、外部访问和 XML 上限。
 * @author owlzhangfq@gmail.com
 */
class InvoiceOfdDocumentTest {
    private static final String NS = "http://www.ofdspec.org/2016";
    @TempDir Path directory;

    @Test
    void retainsManifestOrderInheritedGeometryAndPageOverridesWithInternalResources() throws Exception {
        var files = fixture(2);
        files.put("Doc_0/Pages/P1.xml", xml("Page", "<ofd:Area><ofd:PhysicalBox>1 2 100 80</ofd:PhysicalBox></ofd:Area>"
                + "<ofd:PageRes>../PageRes.xml</ofd:PageRes><ofd:Content/>"));
        files.put("Doc_0/PageRes.xml", xmlRoot("Res", " BaseLoc=\"Res\"", "<ofd:Fonts><ofd:Font ID=\"20\" FontName=\"Test\"><ofd:FontFile>font.ttf</ofd:FontFile></ofd:Font></ofd:Fonts>"));
        files.put("Doc_0/Res/font.ttf", new byte[]{1, 2, 3});
        List<InvoiceOfdDocument.Page> pages = inspect(files);
        assertThat(pages).containsExactly(
                new InvoiceOfdDocument.Page("Doc_0/Document.xml", "Doc_0/Pages/P0.xml", 0, 0, 210, 297),
                new InvoiceOfdDocument.Page("Doc_0/Document.xml", "Doc_0/Pages/P1.xml", 1, 2, 100, 80));
    }

    @Test
    void includesEveryDocumentBodyInsteadOfSilentlySendingOnlyTheFirstDocument() throws Exception {
        var files = fixture(1);
        files.put("OFD.xml", xmlRoot("OFD", " Version=\"1.0\" DocType=\"OFD\"", "<ofd:DocBody><ofd:DocRoot>Doc_0/Document.xml</ofd:DocRoot></ofd:DocBody>"
                + "<ofd:DocBody><ofd:DocRoot>/Doc_1/Document.xml</ofd:DocRoot></ofd:DocBody>"));
        files.put("Doc_1/Document.xml", xml("Document", common() + "<ofd:Pages><ofd:Page ID=\"1\" BaseLoc=\"P.xml\"/></ofd:Pages>"));
        files.put("Doc_1/P.xml", xml("Page", "<ofd:Content/>"));
        assertThat(inspect(files)).extracting(InvoiceOfdDocument.Page::documentFile)
                .containsExactly("Doc_0/Document.xml", "Doc_1/Document.xml");
    }

    @Test
    void enforcesActualPageLimitAndUniqueIdsAndFilesWithinEachDocument() throws Exception {
        assertInvalid(fixture(0)); assertInvalid(fixture(11));
        assertThat(inspect(fixture(10))).hasSize(10);
        var ids = fixture(2); replace(ids, "Doc_0/Document.xml", "ID=\"2\"", "ID=\"1\""); assertInvalid(ids);
        var normalizedIds = fixture(2); replace(normalizedIds, "Doc_0/Document.xml", "ID=\"2\"", "ID=\"001\""); assertInvalid(normalizedIds);
        var paths = fixture(2); replace(paths, "Doc_0/Document.xml", "Pages/P1.xml", "Pages/P0.xml"); assertInvalid(paths);
        var missing = fixture(1); missing.remove("Doc_0/Pages/P0.xml"); assertInvalid(missing);
    }

    @Test
    void rejectsUnknownManifestRootsVersionsAndAmbiguousCriticalElements() throws Exception {
        var namespace = fixture(1); replace(namespace, "OFD.xml", NS, "urn:forged"); assertInvalid(namespace);
        var version = fixture(1); replace(version, "OFD.xml", "Version=\"1.0\"", "Version=\"9.0\""); assertInvalid(version);
        var type = fixture(1); replace(type, "OFD.xml", "DocType=\"OFD\"", "DocType=\"OTHER\""); assertInvalid(type);
        var duplicate = fixture(1); replace(duplicate, "OFD.xml", "</ofd:DocRoot>", "</ofd:DocRoot><ofd:DocRoot>Doc_0/Document.xml</ofd:DocRoot>"); assertInvalid(duplicate);
        var foreignArea = fixture(1); replace(foreignArea, "Doc_0/Pages/P0.xml", "<ofd:Content/>", "<x:Area xmlns:x=\"urn:forged\"><x:PhysicalBox>0 0 1 1</x:PhysicalBox></x:Area><ofd:Content/>"); assertInvalid(foreignArea);
        var mixed = fixture(1); replace(mixed, "OFD.xml", "Doc_0/Document.xml</ofd:DocRoot>", "Doc_0/<ofd:Other/>Document.xml</ofd:DocRoot>"); assertInvalid(mixed);
    }

    @Test
    void rejectsDtdAndExternalEntitiesWithoutContactingTheReferencedServer() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/entity", exchange -> {
            calls.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close();
        });
        server.start();
        try {
            var files = fixture(1);
            String declaration = "<!DOCTYPE OFD [<!ENTITY external SYSTEM \"http://127.0.0.1:" + server.getAddress().getPort() + "/entity\">]>";
            files.put("extra.xml", bytes(declaration + "<OFD>&external;</OFD>"));
            assertInvalid(files); assertThat(calls.get()).isZero();
        } finally { server.stop(0); }
    }

    @Test
    void boundsAllXmlIncludingUnreferencedFilesAndRejectsMalformedXml() throws Exception {
        var malformed = fixture(1); malformed.put("extra.xml", bytes("<broken>")); assertInvalid(malformed);
        var deep = fixture(1); deep.put("extra.xml", bytes("<n>".repeat(65) + "</n>".repeat(65))); assertInvalid(deep);
        var many = fixture(1); many.put("extra.xml", bytes("<root>" + "<n/>".repeat(100_001) + "</root>")); assertInvalid(many);
        var large = fixture(1); large.put("extra.xml", bytes("<root>" + "a".repeat(2 * 1024 * 1024) + "</root>")); assertInvalid(large);
        var aggregate = fixture(1);
        byte[] chunk = bytes("<root>" + "a".repeat(2 * 1024 * 1024 - 32) + "</root>");
        for (int i = 0; i < 5; i++) aggregate.put("extra-" + i + ".xml", chunk);
        assertInvalid(aggregate);
        var attributes = fixture(1); var opening = new StringBuilder("<root");
        for (int i = 0; i < 65; i++) opening.append(" a").append(i).append("=\"x\"");
        attributes.put("extra.xml", bytes(opening + "/>")); assertInvalid(attributes);
    }

    @Test
    void refusesExternalMissingOrEscapingResourcesAndResolvesAbsoluteContainerResources() throws Exception {
        for (String path : new String[]{"../../../outside.ttf", "https://example.test/font.ttf", "file:/etc/passwd", "missing.ttf"}) {
            var files = withFont(path); assertInvalid(files);
        }
        var valid = withFont("/shared/font.ttf"); valid.put("shared/font.ttf", new byte[]{1});
        assertThat(inspect(valid)).hasSize(1);
        var escapedBase = withFont("font.ttf"); replace(escapedBase, "Doc_0/Res.xml", "BaseLoc=\".\"", "BaseLoc=\"../../\""); assertInvalid(escapedBase);
    }

    @Test
    void rejectsMissingNonFiniteOrUnboundedPhysicalBoxes() throws Exception {
        for (String box : new String[]{"0 0 0 10", "0 0 -1 10", "0 0 NaN 10", "0 0 Infinity 10", "0 0 1001 100", "0 0 10"}) {
            var files = fixture(1); replace(files, "Doc_0/Document.xml", "0 0 210 297", box); assertInvalid(files);
        }
        var missing = fixture(1); replace(missing, "Doc_0/Document.xml", "<ofd:PhysicalBox>0 0 210 297</ofd:PhysicalBox>", ""); assertInvalid(missing);
    }

    @Test
    void validatesResourcesEvenWhenTheirFilenamesDoNotHaveAnXmlSuffix() throws Exception {
        var files = fixture(1);
        replace(files, "Doc_0/Document.xml", "</ofd:CommonData>", "<ofd:DocumentRes>resources.bin</ofd:DocumentRes></ofd:CommonData>");
        files.put("Doc_0/resources.bin", xmlRoot("Res", " BaseLoc=\".\"", "<ofd:Fonts><ofd:Font ID=\"9\"><ofd:FontFile>../../outside.ttf</ofd:FontFile></ofd:Font></ofd:Fonts>"));
        assertInvalid(files);
    }

    @Test
    void unusedResourceBaseDirectoriesNeedNotHaveAStoredZipEntry() throws Exception {
        var files = fixture(1);
        replace(files, "Doc_0/Document.xml", "</ofd:CommonData>", "<ofd:PublicRes>Res.xml</ofd:PublicRes></ofd:CommonData>");
        files.put("Doc_0/Res.xml", xmlRoot("Res", " BaseLoc=\"Res\"", "<ofd:Fonts><ofd:Font ID=\"20\" FontName=\"Test\"/></ofd:Fonts>"));
        assertThat(inspect(files)).hasSize(1);
        files.put("Doc_0/Res.xml", xmlRoot("Res", " BaseLoc=\"Res\"", "<ofd:Fonts><ofd:Font ID=\"20\"><ofd:FontFile>../font.ttf</ofd:FontFile></ofd:Font></ofd:Fonts>"));
        files.put("Doc_0/font.ttf", new byte[]{1});
        assertThat(inspect(files)).hasSize(1);
    }

    @Test
    void metadataDocumentRootsAreNotConfusedWithSameNamedLocationElements() throws Exception {
        var files = fixture(1);
        replace(files, "OFD.xml", "</ofd:DocBody>", "<ofd:Signatures>Doc_0/Signatures.xml</ofd:Signatures></ofd:DocBody>");
        replace(files, "Doc_0/Document.xml", "</ofd:Document>", "<ofd:Attachments>Attachments.xml</ofd:Attachments><ofd:Annotations>Annotations.xml</ofd:Annotations></ofd:Document>");
        files.put("Doc_0/Attachments.xml", xml("Attachments", "<ofd:Attachment ID=\"8\" Name=\"test\"><ofd:FileLoc>value.dat</ofd:FileLoc></ofd:Attachment>"));
        files.put("Doc_0/Annotations.xml", xml("Annotations", "<ofd:Page PageID=\"1\"><ofd:FileLoc>Annotation.xml</ofd:FileLoc></ofd:Page>"));
        files.put("Doc_0/Annotation.xml", xml("PageAnnot", ""));
        files.put("Doc_0/Signatures.xml", xml("Signatures", "<ofd:Signature ID=\"9\" BaseLoc=\"Signature.xml\"/>"));
        files.put("Doc_0/Signature.xml", xml("Signature", "<ofd:SignedValue>value.dat</ofd:SignedValue>"));
        files.put("Doc_0/value.dat", new byte[]{1});
        assertThat(inspect(files)).hasSize(1);
    }

    @Test
    void acceptsObservedVersionOnePointOneWithTheSameOfdNamespace() throws Exception {
        var files = fixture(1); replace(files, "OFD.xml", "Version=\"1.0\"", "Version=\"1.1\"");
        assertThat(inspect(files)).hasSize(1);
    }

    private List<InvoiceOfdDocument.Page> inspect(Map<String, byte[]> files) throws IOException {
        Path source = Files.write(directory.resolve(UUID.randomUUID() + ".ofd"), zip(files));
        return InvoiceOfdDocument.inspect(InvoiceOfdArchive.read(source));
    }
    private void assertInvalid(Map<String, byte[]> files) { assertThatThrownBy(() -> inspect(files)).isInstanceOf(IOException.class); }
    private static Map<String, byte[]> withFont(String fontFile) {
        var files = fixture(1);
        replace(files, "Doc_0/Document.xml", "</ofd:CommonData>", "<ofd:PublicRes>Res.xml</ofd:PublicRes></ofd:CommonData>");
        files.put("Doc_0/Res.xml", xmlRoot("Res", " BaseLoc=\".\"", "<ofd:Fonts><ofd:Font ID=\"9\"><ofd:FontFile>" + fontFile + "</ofd:FontFile></ofd:Font></ofd:Fonts>"));
        return files;
    }
    static Map<String, byte[]> fixture(int count) {
        var files = new LinkedHashMap<String, byte[]>();
        files.put("OFD.xml", xmlRoot("OFD", " Version=\"1.0\" DocType=\"OFD\"", "<ofd:DocBody><ofd:DocRoot>Doc_0/Document.xml</ofd:DocRoot></ofd:DocBody>"));
        var pages = new StringBuilder();
        for (int i = 0; i < count; i++) {
            pages.append("<ofd:Page ID=\"").append(i + 1).append("\" BaseLoc=\"Pages/P").append(i).append(".xml\"/>");
            files.put("Doc_0/Pages/P" + i + ".xml", xml("Page", "<ofd:Content/>"));
        }
        files.put("Doc_0/Document.xml", xml("Document", common() + "<ofd:Pages>" + pages + "</ofd:Pages>"));
        return files;
    }
    private static String common() { return "<ofd:CommonData><ofd:MaxUnitID>100</ofd:MaxUnitID><ofd:PageArea><ofd:PhysicalBox>0 0 210 297</ofd:PhysicalBox></ofd:PageArea></ofd:CommonData>"; }
    private static byte[] xml(String name, String content) { return xmlRoot(name, "", content); }
    private static byte[] xmlRoot(String name, String attributes, String content) { return bytes("<ofd:" + name + " xmlns:ofd=\"" + NS + "\"" + attributes + ">" + content + "</ofd:" + name + ">"); }
    private static void replace(Map<String, byte[]> files, String name, String before, String after) {
        String value = new String(files.get(name), java.nio.charset.StandardCharsets.UTF_8);
        if (!value.contains(before)) throw new IllegalArgumentException("Synthetic fixture replacement did not match");
        files.put(name, bytes(value.replace(before, after)));
    }
}
