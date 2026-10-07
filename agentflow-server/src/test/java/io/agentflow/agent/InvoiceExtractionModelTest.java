package io.agentflow.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.InvoiceOriginal;
import io.agentflow.expense.InvoiceOriginalFiles;
import io.agentflow.expense.JdbcInvoiceOriginalRepository;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 回环合成模型核对实际请求协议和拒绝边界，不把合成响应当作识别准确率证据。
 * @author owlzhangfq@gmail.com
 */
class InvoiceExtractionModelTest {
    private final JsonUtil json = new JsonUtil(new ObjectMapper().findAndRegisterModules());
    private final InvoiceOriginalFiles files = mock(InvoiceOriginalFiles.class);
    private final JdbcInvoiceOriginalRepository originals = mock(JdbcInvoiceOriginalRepository.class);
    private final InvoiceExtractionSources sources = new InvoiceExtractionSources(files);
    private final AssistConfiguration configuration = new AssistConfiguration();
    private final AtomicReference<Object> output = new AtomicReference<>();
    private final AtomicReference<JsonNode> request = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private HttpServer server;
    private ExecutorService threads;
    private InvoiceExtractionEngine extraction;

    @BeforeEach
    void startModel() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        threads = Executors.newFixedThreadPool(2); server.setExecutor(threads);
        server.createContext("/chat", exchange -> {
            calls.incrementAndGet();
            request.set(json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonNode.class));
            byte[] body = json.write(Map.of("model", "synthetic-vision-v1", "choices", List.of(Map.of("finish_reason", "stop",
                    "message", Map.of("role", "assistant", "content", json.write(output.get())))))).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start(); configuration.setEnabled(true); configuration.setProviderId("loopback-fixture");
        configuration.setModel("vision-fixture"); configuration.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/chat");
        extraction = new InvoiceExtractionEngine(originals, sources, new OpenAiCompatibleInvoiceExtractionModel(configuration, json),
                org.mockito.Mockito.mock(InvoiceExtractionEligibility.class));
    }

    @AfterEach
    void stopModel() { server.stop(0); threads.shutdownNow(); }

    @Test
    void pngAndJpegUseExactInlineBytesAndBoundEvidenceWithoutSendingIdentityOrFilename() throws Exception {
        for (var format : List.of(InvoiceOriginal.Format.PNG, InvoiceOriginal.Format.JPEG)) {
            byte[] bytes = image(format); var original = original(format, bytes); var context = context(original);
            output.set(result(original, "001234", "001234", 1));
            var suggestion = extraction.generate(context);
            assertThat(suggestion.proposals().get(0).value()).isEqualTo("001234");
            assertThat(suggestion.proposals().get(0).confidence()).isEqualTo(InvoiceExtractionSuggestion.Confidence.MEDIUM);
            assertThat(suggestion.providerId()).isEqualTo("loopback-fixture");
            assertThat(suggestion.processorVersion()).isEqualTo("synthetic-vision-v1");
            JsonNode body = request.get(), parts = body.path("messages").get(1).path("content");
            assertThat(body.path("store").asBoolean()).isFalse();
            assertThat(body.path("stream").asBoolean()).isFalse();
            assertThat(parts.size()).isEqualTo(2);
            assertThat(parts.get(1).path("image_url").path("url").asText())
                    .isEqualTo("data:" + format.mediaType() + ";base64," + Base64.getEncoder().encodeToString(bytes));
            assertThat(body.toString()).doesNotContain(original.filename(), original.ownerId(), original.tenantId(), original.invoiceId().toString());
            assertThat(body.toString()).doesNotContain("file_id", "tools");
        }
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void xmlUsesItsDeclaredEncodingAndRetainsNamespacedTextAndAttributes() throws Exception {
        byte[] bytes = "<?xml version='1.0' encoding='GB18030'?><i:Invoice xmlns:i='urn:invoice' buyer='甲公司'><i:Number>001234</i:Number></i:Invoice>".getBytes(Charset.forName("GB18030"));
        var original = original(InvoiceOriginal.Format.XML, bytes); output.set(result(original, "001234", "001234", 1));
        var suggestion = extraction.generate(context(original));
        assertThat(suggestion.proposals()).hasSize(1);
        var text = request.get().path("messages").get(1).path("content").get(1).path("text").asText();
        assertThat(text).contains("i:Invoice", "buyer=\"甲公司\"", "xmlns:i=\"urn:invoice\"", "001234");
        assertThat(request.get().toString()).doesNotContain("image_url");
        output.set(result(original, "999999", "不存在的票面文字", 1));
        assertFailure(() -> extraction.generate(context(original)), AssistRun.Failure.INVALID_MODEL_OUTPUT);
    }

    @Test
    void malformedSourcesXmlExternalEntitiesAndOversizedInputNeverReachTheModel() throws Exception {
        var badPng = original(InvoiceOriginal.Format.PNG, new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 13, 10, 26, 10});
        assertFailure(() -> extraction.generate(context(badPng)), AssistRun.Failure.INPUT_UNAVAILABLE);
        byte[] bomb = image(InvoiceOriginal.Format.PNG); ByteBuffer.wrap(bomb).putInt(16, 20_000).putInt(20, 20_000);
        var crc = new CRC32(); crc.update(bomb, 12, 17); ByteBuffer.wrap(bomb).putInt(29, (int) crc.getValue());
        var oversized = original(InvoiceOriginal.Format.PNG, bomb);
        assertFailure(() -> extraction.generate(context(oversized)), AssistRun.Failure.INPUT_UNAVAILABLE);
        String external = "<!DOCTYPE invoice [<!ENTITY external SYSTEM '" + configuration.getEndpoint() + "'>]><invoice>&external;</invoice>";
        for (String xml : List.of(external, "<invoice>" + "字".repeat(30_000) + "</invoice>")) {
            var value = original(InvoiceOriginal.Format.XML, xml.getBytes(StandardCharsets.UTF_8));
            assertFailure(() -> extraction.generate(context(value)), AssistRun.Failure.INPUT_UNAVAILABLE);
        }
        assertThat(calls.get()).isZero();
    }

    @Test
    void ownerSourceAndDestinationChangesFailBeforeAnyExternalRequest() throws Exception {
        var original = original(InvoiceOriginal.Format.PNG, image(InvoiceOriginal.Format.PNG));
        var context = context(original);
        var admin = new InvoiceExtractionRun.Context(context.id(), context.tenantId(), "admin", context.createdAt(), context.input(), context.method(), context.targetDigest());
        clearInvocations(files);
        assertFailure(() -> extraction.generate(admin), AssistRun.Failure.INPUT_UNAVAILABLE); verifyNoInteractions(files);
        var changed = new InvoiceExtractionInput(original.invoiceId(), original.id(), "b".repeat(64), original.format(), original.size(), 1);
        assertFailure(() -> extraction.generate(new InvoiceExtractionRun.Context(context.id(), context.tenantId(), context.requestedBy(), context.createdAt(), changed, context.method(), context.targetDigest())), AssistRun.Failure.INPUT_UNAVAILABLE);
        configuration.setModel("another-model");
        assertFailure(() -> extraction.generate(context), AssistRun.Failure.MODEL_UNAVAILABLE);
        configuration.setModel("vision-fixture"); configuration.setEnabled(false);
        assertFailure(() -> extraction.generate(context), AssistRun.Failure.MODEL_UNAVAILABLE);
        assertThat(calls.get()).isZero();
    }

    @Test
    void modelCannotInventPagesFieldsSourceIdsOrFinancialCommands() throws Exception {
        var original = original(InvoiceOriginal.Format.PNG, image(InvoiceOriginal.Format.PNG)); var context = context(original);
        for (Object malformed : List.of(
                result(original, "001234", "001234", 2),
                Map.of("proposals", List.of(Map.of("field", "INVOICE_NUMBER", "value", 1234, "confidence", "HIGH", "evidence", List.of(evidence(original, "001234", 1))))),
                Map.of("proposals", List.of(Map.of("field", "VERIFIED", "value", "true", "confidence", "HIGH", "evidence", List.of(evidence(original, "001234", 1))))),
                Map.of("proposals", List.of(), "action", "PAY"),
                Map.of("proposals", List.of(Map.of("field", "INVOICE_NUMBER", "value", "001234", "evidence", List.of(evidence(original, "001234", 1))))),
                Map.of("proposals", List.of(Map.of("field", "INVOICE_NUMBER", "value", "001234", "confidence", "GUARANTEED", "evidence", List.of(evidence(original, "001234", 1))))),
                Map.of("proposals", List.of(Map.of("field", "INVOICE_NUMBER", "value", "001234", "confidence", "HIGH", "evidence", List.of(Map.of(
                        "originalId", UUID.randomUUID(), "originalDigest", original.sha256(), "page", 1, "quote", "001234"))))))) {
            output.set(malformed);
            assertFailure(() -> extraction.generate(context), AssistRun.Failure.INVALID_MODEL_OUTPUT);
        }
    }

    @Test
    void anEmptyExtractionIsRecordedAsNoCandidates() throws Exception {
        var original = original(InvoiceOriginal.Format.PNG, image(InvoiceOriginal.Format.PNG));
        output.set(Map.of("proposals", List.of()));
        assertThat(extraction.generate(context(original)).proposals()).isEmpty();
    }

    @Test
    void recognizedXmlUsesLocalExtractionWithoutModelConfigurationOrExternalReferenceResolution() throws Exception {
        String endpoint = configuration.getEndpoint();
        String xml = InvoiceExtractionXmlTest.XML
                .replace("<EInvoice>", "<EInvoice xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance' xsi:noNamespaceSchemaLocation='" + endpoint + "'>")
                .replace("<TaxBureauSignature>", "<TaxBureauSignature><Reference URI='" + endpoint + "'/>")
                .replace("</EInvoice>", "<xi:include xmlns:xi='http://www.w3.org/2001/XInclude' href='" + endpoint + "'/></EInvoice>");
        var original = original(InvoiceOriginal.Format.XML, xml.getBytes(StandardCharsets.UTF_8));
        configuration.setEnabled(false); configuration.setEndpoint(""); configuration.setModel(""); configuration.setApiKey("");
        var context = localContext(original);
        var result = extraction.generate(context);
        assertThat(result.method()).isEqualTo(InvoiceExtractionSuggestion.Method.STRUCTURED_XML);
        assertThat(result.proposals()).hasSize(9);
        assertThat(result.providerId()).isEqualTo("local-xml");
        var prepared = sources.prepare(original);
        assertThat(prepared.method()).isEqualTo(InvoiceExtractionSuggestion.Method.STRUCTURED_XML);
        assertThat(prepared.parts()).isEmpty();
        assertThat(calls.get()).isZero();
        var run = new InvoiceExtractionRun(context); run.start(1, context.createdAt()); run.complete(2, result, context.createdAt());
        var restoredContext = json.read(json.write(context), InvoiceExtractionRun.Context.class);
        var restoredState = json.read(json.write(run.state()), InvoiceExtractionRun.State.class);
        assertThat(InvoiceExtractionRun.restore(restoredContext, restoredState).state()).isEqualTo(run.state());
    }

    @Test
    void localModeFailureAndUnknownXmlNeverSwitchToTheEnabledModel() throws Exception {
        for (var xml : List.of(InvoiceExtractionXmlTest.XML.replace("0.31", "0.32"), "<Invoice><Number>001234</Number></Invoice>",
                InvoiceExtractionXmlTest.XML.replace("</TaxSupervisionInfo>", "<InvoiceNumber>9999</InvoiceNumber></TaxSupervisionInfo>"))) {
            var original = original(InvoiceOriginal.Format.XML, xml.getBytes(StandardCharsets.UTF_8));
            assertFailure(() -> extraction.generate(localContext(original)), AssistRun.Failure.INPUT_UNAVAILABLE);
        }
        var known = original(InvoiceOriginal.Format.XML, InvoiceExtractionXmlTest.XML.getBytes(StandardCharsets.UTF_8));
        // 创建时应采用已解析出的本地方式；旧模型方式的任务不能因代码能力改变而外发或换一种结果。
        assertFailure(() -> extraction.generate(context(known)), AssistRun.Failure.INPUT_UNAVAILABLE);
        assertThat(calls.get()).isZero();
    }

    @Test
    void localExtractionStillChecksOwnerAndOriginalIdentityBeforeReturningAnyValues() throws Exception {
        var original = original(InvoiceOriginal.Format.XML, InvoiceExtractionXmlTest.XML.getBytes(StandardCharsets.UTF_8));
        var context = localContext(original);
        clearInvocations(files);
        var admin = new InvoiceExtractionRun.Context(context.id(), context.tenantId(), "admin", context.createdAt(), context.input(), context.method(), null);
        assertFailure(() -> extraction.generate(admin), AssistRun.Failure.INPUT_UNAVAILABLE);
        verifyNoInteractions(files);
        var input = context.input();
        var changed = new InvoiceExtractionInput(input.invoiceId(), UUID.randomUUID(), input.originalDigest(), input.format(), input.originalBytes(), 1);
        assertFailure(() -> extraction.generate(new InvoiceExtractionRun.Context(context.id(), context.tenantId(), context.requestedBy(),
                context.createdAt(), changed, context.method(), null)), AssistRun.Failure.INPUT_UNAVAILABLE);
        assertThat(calls.get()).isZero();
    }

    @Test
    void pdfUsesCompleteInlineFileAndModelEvidenceCannotExceedTheInspectedPageCount() throws Exception {
        byte[] bytes = InvoicePdfSourceTest.pdf(2, doc -> { });
        var original = original(InvoiceOriginal.Format.PDF, bytes);
        output.set(result(original, "001234", "001234", 2));
        var suggestion = extraction.generate(context(original, 2));
        assertThat(suggestion.proposals().get(0).evidence().get(0).page()).isEqualTo(2);
        var content = request.get().path("messages").get(1).path("content");
        assertThat(content.size()).isEqualTo(2);
        assertThat(content.get(1).path("type").asText()).isEqualTo("file");
        assertThat(content.get(1).path("file").path("filename").asText()).isEqualTo("invoice.pdf");
        assertThat(content.get(1).path("file").path("file_data").asText())
                .isEqualTo("data:application/pdf;base64," + Base64.getEncoder().encodeToString(bytes));
        assertThat(request.get().toString()).doesNotContain(original.filename(), original.ownerId(), original.tenantId());
        output.set(result(original, "001234", "001234", 3));
        assertFailure(() -> extraction.generate(context(original, 2)), AssistRun.Failure.INVALID_MODEL_OUTPUT);
    }

    @Test
    void invalidPdfAndChangedRealPageCountNeverReachTheModel() throws Exception {
        var invalid = original(InvoiceOriginal.Format.PDF, "%PDF-1.7 invalid".getBytes(StandardCharsets.US_ASCII));
        assertFailure(() -> extraction.generate(context(invalid)), AssistRun.Failure.INPUT_UNAVAILABLE);
        var twoPages = original(InvoiceOriginal.Format.PDF, InvoicePdfSourceTest.pdf(2, doc -> { }));
        assertFailure(() -> extraction.generate(context(twoPages)), AssistRun.Failure.INPUT_UNAVAILABLE);
        assertThat(calls.get()).isZero();
    }

    @Test
    void ofdSendsEveryPageInOrderAndLimitsEvidenceToTheInspectedOriginal() throws Exception {
        var contents = InvoiceOfdRendererTest.fixture(2);
        InvoiceOfdRendererTest.page(contents, 0, "", InvoiceOfdRendererTest.path("0 0 10 10", "255 0 0", ""));
        InvoiceOfdRendererTest.page(contents, 1, "", InvoiceOfdRendererTest.path("0 0 10 10", "0 0 255", ""));
        byte[] bytes = InvoiceOfdArchiveTest.zip(contents);
        var original = original(InvoiceOriginal.Format.OFD, bytes);
        output.set(result(original, "001234", "001234", 2));
        var suggestion = extraction.generate(context(original, 2));
        assertThat(suggestion.proposals().get(0).evidence().get(0).page()).isEqualTo(2);
        var content = request.get().path("messages").get(1).path("content");
        assertThat(content.size()).isEqualTo(5);
        assertThat(content.get(0).path("text").asText()).contains(original.sha256(), "OFD", "pageCount");
        for (int page = 1; page <= 2; page++) {
            assertThat(content.get(page * 2 - 1).path("text").asText()).isEqualTo("OFD page " + page + " of 2");
            var part = content.get(page * 2);
            assertThat(part.path("type").asText()).isEqualTo("image_url");
            String url = part.path("image_url").path("url").asText();
            assertThat(url).startsWith("data:image/png;base64,");
            byte[] png = Base64.getDecoder().decode(url.substring(url.indexOf(',') + 1));
            assertThat(InvoiceOfdRendererTest.pixel(png, 5, 5)).isEqualTo((page == 1 ? java.awt.Color.RED : java.awt.Color.BLUE).getRGB());
        }
        assertThat(request.get().toString()).doesNotContain("file_data", original.filename(), original.ownerId(), original.tenantId(), Base64.getEncoder().encodeToString(bytes));
        output.set(result(original, "001234", "001234", 3));
        assertFailure(() -> extraction.generate(context(original, 2)), AssistRun.Failure.INVALID_MODEL_OUTPUT);
    }

    @Test
    void invalidOrChangedOfdNeverSendsPartialContentToTheModel() throws Exception {
        assertThat(sources.supportedFormats()).containsExactly(InvoiceOriginal.Format.PNG, InvoiceOriginal.Format.JPEG,
                InvoiceOriginal.Format.XML, InvoiceOriginal.Format.PDF, InvoiceOriginal.Format.OFD);
        var malformed = original(InvoiceOriginal.Format.OFD, new byte[]{1});
        assertFailure(() -> extraction.generate(context(malformed)), AssistRun.Failure.INPUT_UNAVAILABLE);
        var contents = InvoiceOfdRendererTest.fixture(2);
        InvoiceOfdRendererTest.page(contents, 1, "", "<ofd:UnknownObject/>");
        var laterFailure = original(InvoiceOriginal.Format.OFD, InvoiceOfdArchiveTest.zip(contents));
        assertFailure(() -> extraction.generate(context(laterFailure, 2)), AssistRun.Failure.INPUT_UNAVAILABLE);
        var twoPages = original(InvoiceOriginal.Format.OFD, InvoiceOfdArchiveTest.zip(InvoiceOfdRendererTest.fixture(2)));
        assertFailure(() -> extraction.generate(context(twoPages)), AssistRun.Failure.INPUT_UNAVAILABLE);
        assertThat(calls.get()).isZero();
    }

    @Test
    void sourcePreparationRefusesToReadFilesWhileHoldingAnApplicationTransaction() throws Exception {
        var original = original(InvoiceOriginal.Format.PNG, image(InvoiceOriginal.Format.PNG));
        clearInvocations(files);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> sources.prepare(original)).isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(files);
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        assertThat(calls.get()).isZero();
    }

    private InvoiceOriginal original(InvoiceOriginal.Format format, byte[] bytes) throws Exception {
        var original = new InvoiceOriginal(UUID.randomUUID(), UUID.randomUUID(), "private-tenant", "private-owner", "private-original." + format.name().toLowerCase(),
                bytes.length, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), format, Instant.now(), InvoiceOriginal.Status.READY);
        when(files.read(original)).thenReturn(bytes);
        when(originals.find(original.tenantId(), original.invoiceId())).thenReturn(Optional.of(original));
        return original;
    }
    private InvoiceExtractionRun.Context context(InvoiceOriginal original) {
        return context(original, 1);
    }
    private InvoiceExtractionRun.Context context(InvoiceOriginal original, int pageCount) {
        var input = new InvoiceExtractionInput(original.invoiceId(), original.id(), original.sha256(), original.format(), original.size(), pageCount);
        return new InvoiceExtractionRun.Context(UUID.randomUUID(), original.tenantId(), original.ownerId(), Instant.now(), input, InvoiceExtractionSuggestion.Method.MODEL, configuration.targetDigest(InvoiceExtractionRun.CONTRACT_VERSION));
    }
    private InvoiceExtractionRun.Context localContext(InvoiceOriginal original) {
        var input = new InvoiceExtractionInput(original.invoiceId(), original.id(), original.sha256(), original.format(), original.size(), 1);
        return new InvoiceExtractionRun.Context(UUID.randomUUID(), original.tenantId(), original.ownerId(), Instant.now(), input,
                InvoiceExtractionSuggestion.Method.STRUCTURED_XML, null);
    }
    private static byte[] image(InvoiceOriginal.Format format) throws Exception {
        var bytes = new ByteArrayOutputStream(); var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        assertThat(ImageIO.write(image, format.name(), bytes)).isTrue(); image.flush(); return bytes.toByteArray();
    }
    private static Object result(InvoiceOriginal original, String value, String quote, int page) {
        return Map.of("proposals", List.of(Map.of("field", "INVOICE_NUMBER", "value", value, "confidence", "MEDIUM", "evidence", List.of(evidence(original, quote, page)))));
    }
    private static Map<String, Object> evidence(InvoiceOriginal original, String quote, int page) {
        return Map.of("originalId", original.id(), "originalDigest", original.sha256(), "page", page, "quote", quote);
    }
    private static void assertFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, AssistRun.Failure expected) {
        assertThatThrownBy(action).isInstanceOfSatisfying(AssistModelPort.ModelFailure.class, failure -> assertThat(failure.failure()).isEqualTo(expected));
    }
}
