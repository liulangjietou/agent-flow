package io.agentflow.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.JsonUtil;
import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.agentflow.agent.InvoiceOfdArchiveTest.zip;
import static io.agentflow.agent.InvoiceOfdRendererTest.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 外部字体必须由可信清单明确指定，文档名称不能变成主机路径或近似字体回退。
 * @author owlzhangfq@gmail.com
 */
class InvoiceOfdDeployedFontsTest {
    private static final JsonUtil JSON = new JsonUtil(new ObjectMapper());
    private static final String GLYPH = "<ofd:TextCode X=\"1\" Y=\"6\">A</ofd:TextCode>";
    @TempDir Path directory;

    @Test
    void rendersAnExplicitCollectionFaceForAnExactDocumentNameAcrossPages() throws Exception {
        var files = external(2, "票据字体", "");
        Path catalog = catalog(List.of(binding("票据字体", "two-faces.ttc", "AgentFlowSyntheticB")));
        var pages = render(files, catalog);
        assertThat(pages).hasSize(2);
        for (byte[] page : pages) {
            assertThat(pixel(page, 1.3, 5)).isEqualTo(Color.BLACK.getRGB());
            assertThat(pixel(page, 2, 5)).isEqualTo(Color.WHITE.getRGB());
        }
    }

    @Test
    void matchesBoldAndItalicDeclarationsToExplicitFacesWithoutSynthesizingStyles() throws Exception {
        var regular = binding("票据字体", "two-faces.ttc", "AgentFlowSyntheticA");
        var styled = binding("票据字体", "two-faces.ttc", "AgentFlowSyntheticB");
        styled.put("bold", true); styled.put("italic", true);
        Path catalog = catalog(List.of(regular, styled));
        assertThat(pixel(render(external(1, "票据字体", ""), catalog).get(0), 2, 5)).isEqualTo(Color.BLACK.getRGB());
        assertThat(pixel(render(external(1, "票据字体", "Bold=\"true\" Italic=\"true\""), catalog).get(0), 2, 5)).isEqualTo(Color.WHITE.getRGB());
        assertThatThrownBy(() -> render(external(1, "票据字体", "Bold=\"true\""), catalog)).isInstanceOf(IOException.class);
    }

    @Test
    void keepsEmbeddedFontsAuthoritativeAndNeverReplacesBrokenEmbeddedData() throws Exception {
        var files = fixture(1); page(files, 0, "", text("", GLYPH));
        Path catalog = catalog(List.of(binding("AgentFlowSyntheticA", "b.ttf", "AgentFlowSyntheticB")));
        assertThat(pixel(render(files, catalog).get(0), 2, 5)).isEqualTo(Color.BLACK.getRGB());
        files.put("Doc_0/Res/a.ttf", new byte[]{1, 2, 3});
        assertThatThrownBy(() -> render(files, catalog)).isInstanceOf(IOException.class);
    }

    @Test
    void refusesFamilyCaseAndHostPathFallbacks() throws Exception {
        var definition = binding("Allowed", "a.ttf", "AgentFlowSyntheticA");
        Path catalog = catalog(List.of(definition));
        for (String name : List.of("allowed", "Absent", definition.get("file").toString())) {
            var files = external(1, name, "FamilyName=\"Allowed\"");
            assertThatThrownBy(() -> render(files, catalog)).isInstanceOf(IOException.class);
        }
        assertThatThrownBy(() -> render(external(1, "Allowed", ""), null)).isInstanceOf(IOException.class);
    }

    @Test
    void failsTheEntireResultOnMissingGlyphInTheLastPage() throws Exception {
        var files = external(2, "Allowed", "");
        page(files, 1, "", text("", GLYPH.replace(">A<", ">B<")));
        Path catalog = catalog(List.of(binding("Allowed", "a.ttf", "AgentFlowSyntheticA")));
        assertThatThrownBy(() -> render(files, catalog)).isInstanceOf(IOException.class);
    }

    @Test
    void rejectsMalformedCatalogsEvenWhenTheDocumentOnlyUsesEmbeddedFonts() throws Exception {
        var files = fixture(1); page(files, 0, "", text("", GLYPH));
        String entry = JSON.write(binding("Allowed", "a.ttf", "AgentFlowSyntheticA"));
        for (String raw : List.of("null", "[]", "{}", "{\"fonts\":null}", "{\"fonts\":[],\"unknown\":1}",
                "{\"fonts\":[],\"fonts\":[]}", "{\"fonts\":[]} {}", "{\"fonts\":[" + entry + "," + entry + "]}")) {
            Path catalog = Files.writeString(directory.resolve(UUID.randomUUID() + ".json"), raw);
            assertThatThrownBy(() -> render(files, catalog)).as(raw).isInstanceOf(IOException.class);
        }
    }

    @Test
    void rejectsInvalidMappingTypesAndIncompleteOrAmbiguousFileBindings() throws Exception {
        var files = fixture(1); page(files, 0, "", text("", GLYPH));
        var entry = binding("Allowed", "a.ttf", "AgentFlowSyntheticA");
        for (var change : Map.<String, Object>of("name", " ", "file", "relative.ttf", "face", "", "sha256", "bad", "bold", 1, "italic", "false", "unknown", true).entrySet()) {
            var invalid = new HashMap<>(entry); invalid.put(change.getKey(), change.getValue());
            Path catalog = catalog(List.of(invalid));
            assertThatThrownBy(() -> render(files, catalog)).as(change.getKey()).isInstanceOf(IOException.class);
        }
        for (String absent : List.of("name", "file", "face", "sha256")) {
            var invalid = new HashMap<>(entry); invalid.remove(absent);
            Path catalog = catalog(List.of(invalid));
            assertThatThrownBy(() -> render(files, catalog)).as(absent).isInstanceOf(IOException.class);
        }
        var conflicting = new HashMap<>(entry); conflicting.put("name", "Other"); conflicting.put("sha256", "0".repeat(64));
        Path catalog = catalog(List.of(entry, conflicting));
        assertThatThrownBy(() -> render(files, catalog)).isInstanceOf(IOException.class);
    }

    @Test
    void rejectsChangedMissingDirectoryAndSymlinkFontFiles() throws Exception {
        var files = external(1, "Allowed", "");
        var entry = binding("Allowed", "a.ttf", "AgentFlowSyntheticA");
        Path target = Path.of(entry.get("file").toString());
        Path catalog = catalog(List.of(entry));
        Files.write(target, InvoiceOfdFontTest.bytes("b.ttf"));
        assertThatThrownBy(() -> render(files, catalog)).isInstanceOf(IOException.class);
        Files.delete(target);
        assertThatThrownBy(() -> render(files, catalog)).isInstanceOf(IOException.class);
        Files.createDirectory(target);
        assertThatThrownBy(() -> render(files, catalog)).isInstanceOf(IOException.class);
        Files.delete(target);
        Path actual = Files.write(directory.resolve("actual.ttf"), InvoiceOfdFontTest.bytes("a.ttf"));
        Files.createSymbolicLink(target, actual);
        assertThatThrownBy(() -> render(files, catalog)).isInstanceOf(IOException.class);
    }

    @Test
    void refusesAbsentAmbiguousAndIncorrectPostscriptFaces() throws Exception {
        for (String fixture : List.of("a.ttf", "two-faces.ttc", "ambiguous-faces.ttc")) {
            Path catalog = catalog(List.of(binding("Allowed", fixture, fixture.equals("ambiguous-faces.ttc") ? "AgentFlowSyntheticA" : "Absent")));
            assertThatThrownBy(() -> render(external(1, "Allowed", ""), catalog)).isInstanceOf(IOException.class);
        }
    }

    @Test
    void rejectsMalformedFontBooleanHintsBeforeUsingTheEmbeddedOutline() throws Exception {
        for (String hint : List.of("Bold", "Italic", "Serif", "FixedWidth")) {
            var files = fixture(1); page(files, 0, "", text("", GLYPH));
            replace(files, "Doc_0/Resources.xml", "FontName=\"AgentFlowSyntheticA\"", "FontName=\"AgentFlowSyntheticA\" " + hint + "=\"invalid\"");
            assertThatThrownBy(() -> render(files, null)).as(hint).isInstanceOf(IOException.class);
        }
    }

    @Test
    void boundsCatalogBytesAndEntriesAndRejectsNonRegularCatalogFiles() throws Exception {
        var files = fixture(1); page(files, 0, "", text("", GLYPH));
        Path oversized = Files.writeString(directory.resolve("large.json"), " ".repeat(64 * 1024) + "{\"fonts\":[]}");
        assertThatThrownBy(() -> render(files, oversized)).isInstanceOf(IOException.class);
        var entries = new ArrayList<Map<String, Object>>();
        var entry = binding("Allowed", "a.ttf", "AgentFlowSyntheticA");
        for (int i = 0; i < 65; i++) { var next = new HashMap<>(entry); next.put("name", "Font" + i); entries.add(next); }
        Path many = catalog(entries);
        assertThatThrownBy(() -> render(files, many)).isInstanceOf(IOException.class);
        Path regular = catalog(List.of());
        Path link = Files.createSymbolicLink(directory.resolve("catalog-link.json"), regular);
        assertThatThrownBy(() -> render(files, link)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> render(files, directory)).isInstanceOf(IOException.class);
    }

    @Test
    void reusesVerifiedBytesAcrossScopesAndClosesTheSharedFontOnce() throws Exception {
        var definition = binding("Allowed", "a.ttf", "AgentFlowSyntheticA");
        var alias = new HashMap<>(definition); alias.put("name", "Alias");
        Path catalog = catalog(List.of(definition, alias));
        var archive = archive(external(1, "Allowed", ""));
        var fonts = InvoiceOfdFonts.open(archive, catalog);
        InvoiceOfdFont selected;
        try {
            var contents = InvoiceOfdDocument.read(archive);
            selected = fonts.deployed("Allowed", false, false);
            Files.write(Path.of(definition.get("file").toString()), InvoiceOfdFontTest.bytes("b.ttf"));
            for (int i = 0; i < 70; i++) {
                var scope = new InvoiceOfdResources.Scopes(archive, contents, "Doc_0/Document.xml", fonts);
                assertThat(scope.page("Doc_0/Pages/P0.xml").font(10)).isSameAs(selected);
                assertThat(fonts.deployed("Alias", false, false)).isSameAs(selected);
            }
            assertThat(selected.unicode('A').getBounds2D().getWidth()).isCloseTo(.6, within(.0001));
            assertThatThrownBy(() -> render(external(1, "Allowed", ""), catalog)).isInstanceOf(IOException.class);
        } finally { fonts.close(); fonts.close(); }
        assertThatThrownBy(() -> selected.unicode('A')).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> fonts.deployed("Allowed", false, false)).isInstanceOf(IOException.class);
    }

    @Test
    void preservesFontIdIsolationAcrossTemplatesPagesAndDocuments() throws Exception {
        var files = external(2, "Wide", "");
        replace(files, "Doc_0/Document.xml", "</ofd:CommonData>",
                "<ofd:TemplatePage ID=\"900\" BaseLoc=\"Templates/T.xml\"/></ofd:CommonData>");
        files.put("Doc_0/Templates/T.xml", xml("Page", "<ofd:PageRes>Res.xml</ofd:PageRes><ofd:Content><ofd:Layer>"
                + text("", GLYPH).replace("Font=\"10\"", "Font=\"30\"").replace("ID=\"21\"", "ID=\"901\"") + "</ofd:Layer></ofd:Content>"));
        files.put("Doc_0/Templates/Res.xml", xml("Res", "<ofd:Fonts><ofd:Font ID=\"30\" FontName=\"Narrow\"/></ofd:Fonts>"));
        page(files, 1, "<ofd:Template TemplateID=\"900\"/>", "");
        replace(files, "OFD.xml", "</ofd:OFD>", "<ofd:DocBody><ofd:DocRoot>Doc_1/Document.xml</ofd:DocRoot></ofd:DocBody></ofd:OFD>");
        files.put("Doc_1/Document.xml", xml("Document", "<ofd:CommonData><ofd:MaxUnitID>100</ofd:MaxUnitID><ofd:PageArea><ofd:PhysicalBox>0 0 20 20</ofd:PhysicalBox></ofd:PageArea><ofd:PublicRes>Res.xml</ofd:PublicRes></ofd:CommonData><ofd:Pages><ofd:Page ID=\"1\" BaseLoc=\"P.xml\"/></ofd:Pages>"));
        files.put("Doc_1/Res.xml", xml("Res", "<ofd:Fonts><ofd:Font ID=\"10\" FontName=\"Narrow\"/></ofd:Fonts>"));
        files.put("Doc_1/P.xml", xml("Page", "<ofd:Content><ofd:Layer>" + text("", GLYPH) + "</ofd:Layer></ofd:Content>"));
        Path catalog = catalog(List.of(binding("Wide", "a.ttf", "AgentFlowSyntheticA"), binding("Narrow", "b.ttf", "AgentFlowSyntheticB")));
        var pages = render(files, catalog);
        assertThat(pages).hasSize(3);
        assertThat(pixel(pages.get(0), 2, 5)).isEqualTo(Color.BLACK.getRGB());
        for (int i : new int[]{1, 2}) {
            assertThat(pixel(pages.get(i), 1.3, 5)).isEqualTo(Color.BLACK.getRGB());
            assertThat(pixel(pages.get(i), 2, 5)).isEqualTo(Color.WHITE.getRGB());
        }
    }

    @Test
    void boundsTotalFontInstancesAcrossTheEntireRenderSessionAndStillAllowsCacheHitsAtTheLimit() throws Exception {
        var files = fixture(1);
        byte[] font = InvoiceOfdFontTest.bytes("a.ttf");
        for (int i = 0; i < 65; i++) files.put("Doc_0/Res/f" + i + ".ttf", font);
        var fonts = InvoiceOfdFonts.open(archive(files), null);
        InvoiceOfdFont first;
        try {
            first = fonts.embedded("Doc_0/Res/f0.ttf", "A");
            for (int i = 1; i < 64; i++) fonts.embedded("Doc_0/Res/f" + i + ".ttf", "A");
            assertThat(fonts.embedded("Doc_0/Res/f0.ttf", "A")).isSameAs(first);
            assertThatThrownBy(() -> fonts.embedded("Doc_0/Res/f64.ttf", "A")).isInstanceOf(IOException.class);
        } finally { fonts.close(); }
        assertThatThrownBy(() -> first.unicode('A')).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> fonts.embedded("Doc_0/Res/f0.ttf", "A")).isInstanceOf(IOException.class);
    }

    @Test
    void sharesTheByteBudgetBetweenDeployedAndEmbeddedFontsAndBoundsSingleFiles() throws Exception {
        var one = binding("One", "a.ttf", "AgentFlowSyntheticA");
        var two = binding("Two", "b.ttf", "AgentFlowSyntheticB");
        for (var entry : List.of(one, two)) {
            Path file = Path.of(entry.get("file").toString());
            byte[] padded = Arrays.copyOf(Files.readAllBytes(file), 32 * 1024 * 1024);
            Files.write(file, padded); entry.put("sha256", sha256(padded));
        }
        Path catalog = catalog(List.of(one, two));
        try (var fonts = InvoiceOfdFonts.open(archive(fixture(1)), catalog)) {
            assertThat(fonts.deployed("One", false, false).name()).isEqualTo("AgentFlowSyntheticA");
            assertThat(fonts.deployed("Two", false, false).name()).isEqualTo("AgentFlowSyntheticB");
            assertThatThrownBy(() -> fonts.embedded("Doc_0/Res/a.ttf", "A")).isInstanceOf(IOException.class);
        }
        Path file = Path.of(one.get("file").toString());
        Files.write(file, new byte[]{0}, java.nio.file.StandardOpenOption.APPEND);
        one.put("sha256", sha256(Files.readAllBytes(file)));
        Path oversized = catalog(List.of(one));
        assertThatThrownBy(() -> render(external(1, "One", ""), oversized)).isInstanceOf(IOException.class);
    }

    private Map<String, Object> binding(String name, String fixture, String face) throws Exception {
        Path file = Files.write(directory.resolve(fixture), InvoiceOfdFontTest.bytes(fixture));
        return new HashMap<>(Map.of("name", name, "file", file.toString(), "face", face,
                "sha256", sha256(Files.readAllBytes(file))));
    }

    private static String sha256(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }

    private Path catalog(List<Map<String, Object>> fonts) throws IOException {
        return Files.writeString(directory.resolve(UUID.randomUUID() + ".json"), JSON.write(Map.of("fonts", fonts)));
    }

    private static Map<String, byte[]> external(int pages, String name, String extra) throws IOException {
        var files = fixture(pages);
        replace(files, "Doc_0/Resources.xml", "<ofd:Font ID=\"10\" FontName=\"AgentFlowSyntheticA\"><ofd:FontFile>a.ttf</ofd:FontFile></ofd:Font>",
                "<ofd:Font ID=\"10\" FontName=\"" + name + "\" " + extra + "/>");
        for (int i = 0; i < pages; i++) page(files, i, "", text("", GLYPH));
        return files;
    }

    private List<byte[]> render(Map<String, byte[]> files, Path catalog) throws Exception {
        return InvoiceOfdRenderer.render(archive(files), catalog);
    }

    private InvoiceOfdArchive archive(Map<String, byte[]> files) throws IOException {
        return InvoiceOfdArchive.read(Files.write(directory.resolve(UUID.randomUUID() + ".ofd"), zip(files)));
    }
}
