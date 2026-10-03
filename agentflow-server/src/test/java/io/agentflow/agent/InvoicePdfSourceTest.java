package io.agentflow.agent;

import io.agentflow.common.DomainException;
import io.agentflow.expense.InvoiceOriginal;
import io.agentflow.expense.InvoiceOriginalFiles;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Consumer;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 自造 PDF 验证真实页数、原字节和畸形输入边界；模型识别质量另行验收。
 * @author owlzhangfq@gmail.com
 */
class InvoicePdfSourceTest {
    private final InvoiceOriginalFiles files = mock(InvoiceOriginalFiles.class);
    private final InvoiceExtractionSources sources = new InvoiceExtractionSources(files);

    @Test
    void pdfUsesAllOriginalBytesAndActualPageCountWithAFixedNonPrivateFilename() throws Exception {
        byte[] bytes = pdf(2, doc -> doc.getDocumentInformation().setTitle("synthetic embedded metadata"));
        var prepared = prepare(bytes);
        assertThat(prepared.input().pageCount()).isEqualTo(2);
        assertThat(prepared.method()).isEqualTo(InvoiceExtractionSuggestion.Method.MODEL);
        assertThat(prepared.parts()).hasSize(1);
        assertThat(prepared.parts().get(0)).containsEntry("type", "file");
        assertThat(prepared.parts().get(0).get("file")).isEqualTo(java.util.Map.of("filename", "invoice.pdf",
                "file_data", "data:application/pdf;base64," + Base64.getEncoder().encodeToString(bytes)));
        assertThat(prepared.input().originalBytes()).isEqualTo(bytes.length);
    }

    @Test
    void zeroOrMoreThanTenPagesAreRejectedInsteadOfSendingAPartialDocument() throws Exception {
        assertUnavailable(pdf(0, doc -> { }));
        assertUnavailable(pdf(InvoiceExtractionInput.MAX_PAGES + 1, doc -> { }));
        assertThat(prepare(pdf(InvoiceExtractionInput.MAX_PAGES, doc -> { })).input().pageCount()).isEqualTo(InvoiceExtractionInput.MAX_PAGES);
    }

    @Test
    void forgedCountAndMissingOrDuplicatePagesDoNotBecomeInventedEvidencePages() throws Exception {
        assertUnavailable(pdf(2, doc -> doc.getPages().getCOSObject().setInt(COSName.COUNT, 1)));
        assertUnavailable(pdf(2, doc -> doc.getPage(1).getCOSObject().setName(COSName.TYPE, "UnknownPage")));
        assertUnavailable(pdf(2, doc -> doc.getPages().getCOSObject().getCOSArray(COSName.KIDS).set(1, doc.getPage(0).getCOSObject())));
    }

    @Test
    void encryptedDocumentsAreNotOpenedEvenWithAnEmptyUserPassword() throws Exception {
        try (var doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.A4));
            doc.protect(new StandardProtectionPolicy("synthetic-owner-password", "", new AccessPermission()));
            var out = new ByteArrayOutputStream(); doc.save(out);
            assertUnavailable(out.toByteArray());
        }
    }

    @Test
    void brokenAndUnboundedPageGeometryFailsWithoutTruncationOrRepair() throws Exception {
        assertUnavailable("%PDF-1.7\nnot a document".getBytes(StandardCharsets.US_ASCII));
        assertUnavailable(pdf(1, doc -> doc.getPage(0).setMediaBox(new PDRectangle(0, 10))));
        assertUnavailable(pdf(1, doc -> doc.getPage(0).setMediaBox(new PDRectangle(100_000, 100_000))));
    }

    private InvoiceExtractionSources.Prepared prepare(byte[] bytes) throws Exception {
        var original = new InvoiceOriginal(UUID.randomUUID(), UUID.randomUUID(), "demo", "alice", "private-alice-invoice.pdf", bytes.length,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), InvoiceOriginal.Format.PDF, Instant.now(), InvoiceOriginal.Status.READY);
        when(files.read(original)).thenReturn(bytes);
        return sources.prepare(original);
    }
    private void assertUnavailable(byte[] bytes) {
        assertThatThrownBy(() -> prepare(bytes)).isInstanceOfSatisfying(DomainException.class,
                error -> assertThat(error.code()).isEqualTo("INVOICE_EXTRACTION_SOURCE_UNAVAILABLE"));
    }
    static byte[] pdf(int pages, Consumer<PDDocument> change) throws Exception {
        try (var doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) doc.addPage(new PDPage(PDRectangle.A4));
            change.accept(doc);
            var out = new ByteArrayOutputStream(); doc.save(out); return out.toByteArray();
        }
    }
}
