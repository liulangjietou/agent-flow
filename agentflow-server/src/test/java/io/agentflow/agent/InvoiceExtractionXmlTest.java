package io.agentflow.agent;

import io.agentflow.common.DomainException;
import io.agentflow.expense.InvoiceOriginal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static io.agentflow.agent.InvoiceExtractionSuggestion.Field.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 使用自造票面验证经文档核对的字段路径；不复制真实票据，也不把解析视作验签或查验。
 * @author owlzhangfq@gmail.com
 */
class InvoiceExtractionXmlTest {
    static final String XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <EInvoice>
              <Header><EIid>99999999999999999999</EIid><Version>0.31</Version></Header>
              <EInvoiceData>
                <SellerInformation><SellerName>销售测试 &amp; 服务公司</SellerName><SellerIdNum>SELLER-TEST</SellerIdNum></SellerInformation>
                <BuyerInformation><BuyerName>购买测试公司</BuyerName><BuyerIdNum>BUYER-TEST</BuyerIdNum></BuyerInformation>
                <BasicInformation>
                  <TotalAmWithoutTax>-9007199254740993.00</TotalAmWithoutTax>
                  <TotalTaxAm>-1.00</TotalTaxAm><TotalTax-includedAmount>-9007199254740994.00</TotalTax-includedAmount>
                  <RequestTime>2024-02-28 23:59:59</RequestTime>
                </BasicInformation>
                <IssuItemInformation><Amount>555.00</Amount><ComTaxAm>22.00</ComTaxAm><TotaltaxIncludedAmount>577.00</TotaltaxIncludedAmount></IssuItemInformation>
                <AdditionalInformation><InvoiceNumber>88888888888888888888</InvoiceNumber><Currency>CNY</Currency></AdditionalInformation>
              </EInvoiceData>
              <TaxSupervisionInfo><InvoiceNumber>00000000000000001234</InvoiceNumber><IssueTime>2024-02-29</IssueTime></TaxSupervisionInfo>
              <TaxBureauSignature><SignatureValue>synthetic-not-a-signature</SignatureValue></TaxBureauSignature>
            </EInvoice>
            """;

    @Test
    void exactPathsKeepSourceDigestLeadingZerosRedAmountsAndActualIssueDate() throws Exception {
        byte[] bytes = XML.getBytes(StandardCharsets.UTF_8); var input = input(bytes);
        var content = InvoiceExtractionXml.read(input, bytes); var result = content.structured();
        assertThat(content.modelText()).isNull();
        assertThat(result.method()).isEqualTo(InvoiceExtractionSuggestion.Method.STRUCTURED_XML);
        assertThat(result.providerId()).isEqualTo("local-xml");
        assertThat(result.processorVersion()).isEqualTo("einvoice-0.31-v1");
        assertThat(result.contractVersion()).isEqualTo(InvoiceExtractionRun.CONTRACT_VERSION);
        assertThat(values(result)).containsExactlyInAnyOrderEntriesOf(Map.of(
                INVOICE_NUMBER, "00000000000000001234", ISSUE_DATE, "2024-02-29",
                BUYER_NAME, "购买测试公司", BUYER_TAX_ID, "BUYER-TEST", SELLER_NAME, "销售测试 & 服务公司", SELLER_TAX_ID, "SELLER-TEST",
                NET_AMOUNT, "-9007199254740993.00", TAX_AMOUNT, "-1.00", GROSS_AMOUNT, "-9007199254740994.00"));
        result.requireMatches(input);
        for (var proposal : result.proposals()) {
            assertThat(proposal.confidence()).isEqualTo(InvoiceExtractionSuggestion.Confidence.HIGH);
            assertThat(proposal.evidence()).hasSize(1);
            var evidence = proposal.evidence().get(0);
            assertThat(evidence.originalId()).isEqualTo(input.originalId());
            assertThat(evidence.originalDigest()).isEqualTo(input.originalDigest());
            assertThat(evidence.page()).isOne();
            assertThat(evidence.quote()).isEqualTo(proposal.value());
            assertThat(evidence.xmlPath()).startsWith("/EInvoice[1]/");
        }
        assertThat(result.proposals().stream().filter(p -> p.field() == INVOICE_NUMBER).findFirst().orElseThrow().evidence().get(0).xmlPath())
                .isEqualTo("/EInvoice[1]/TaxSupervisionInfo[1]/InvoiceNumber[1]");
    }

    @Test
    void missingFieldsAreOmittedAndNoInvoiceCodeOrCurrencyIsInferred() throws Exception {
        var result = read("<EInvoice><Header><Version>0.31</Version></Header><TaxSupervisionInfo><InvoiceNumber>00001234</InvoiceNumber></TaxSupervisionInfo></EInvoice>").structured();
        assertThat(values(result)).containsExactlyInAnyOrderEntriesOf(Map.of(INVOICE_NUMBER, "00001234"));
        assertThat(read("<EInvoice><Header><Version>0.31</Version></Header></EInvoice>").structured().proposals()).isEmpty();
    }

    @Test
    void gb18030AndUtf16BomRetainChineseValuesAndXmlCharacterData() throws Exception {
        for (var charset : List.of(Charset.forName("GB18030"), StandardCharsets.UTF_16)) {
            byte[] bytes = XML.replace("UTF-8", charset.name()).getBytes(charset);
            var result = InvoiceExtractionXml.read(input(bytes), bytes).structured();
            assertThat(values(result)).containsEntry(SELLER_NAME, "销售测试 & 服务公司");
        }
        var cdata = read(XML.replace("购买测试公司", "<![CDATA[购买测试公司]]>")).structured();
        assertThat(values(cdata)).containsEntry(BUYER_NAME, "购买测试公司");
    }

    @Test
    void unknownVersionsNamespacesWrappersAndAccountingXbrlDoNotClaimOriginalXmlSupport() throws Exception {
        for (var xml : List.of(XML.replace("0.31", "0.32"), XML.replace("<Version>0.31</Version>", ""),
                XML.replace("<EInvoice>", "<EInvoice xmlns='urn:unknown'>"),
                "<Envelope>" + XML.substring(XML.indexOf("<EInvoice>")) + "</Envelope>",
                "<x:xbrl xmlns:x='http://www.xbrl.org/2003/instance'><InvoiceNumber>001234</InvoiceNumber></x:xbrl>")) {
            var result = read(xml);
            assertThat(result.structured()).isNull();
            assertThat(result.modelText()).isNotBlank();
        }
    }

    @Test
    void duplicateScalarsAndGroupsCannotBeMergedEvenIfTheirValuesAgree() {
        for (var xml : List.of(XML.replace("<Version>0.31</Version>", "<Version>0.31</Version><Version>0.31</Version>"),
                XML.replace("</TaxSupervisionInfo>", "<InvoiceNumber>00000000000000001234</InvoiceNumber></TaxSupervisionInfo>"),
                XML.replace("</TaxSupervisionInfo>", "</TaxSupervisionInfo><TaxSupervisionInfo><IssueTime>2024-02-29</IssueTime></TaxSupervisionInfo>"),
                XML.replace("</EInvoiceData>", "</EInvoiceData><EInvoiceData><BuyerInformation/></EInvoiceData>"))) assertInvalid(xml);
    }

    @Test
    void foreignNamespacesMixedMarkupAndNilAttributesCannotMasqueradeAsKnownFields() {
        for (var xml : List.of(XML.replace("<BuyerInformation>", "<BuyerInformation xmlns='urn:forged'>"),
                XML.replace("<BuyerName>", "<BuyerName xmlns='urn:forged'>"),
                XML.replace("购买测试公司", "<b>购买测试公司</b>"),
                XML.replace("<BuyerName>", "<BuyerName xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance' xsi:nil='true'>"))) assertInvalid(xml);
    }

    @Test
    void malformedKnownValuesFailWithoutRoundingTruncationOrGuessing() {
        for (var xml : List.of(XML.replace("2024-02-29", "2025-02-29"), XML.replace("2024-02-29", "2024-02-29 12:00:00"),
                XML.replace("00000000000000001234", "12ab"), XML.replace("-1.00", "-1.001"),
                XML.replace("-1.00", "免税"), XML.replace("购买测试公司", "甲".repeat(513)))) assertInvalid(xml);
    }

    @Test
    void localParsingIsIndependentOfModelTextLimitButFallbackCountsCompleteUtf8() throws Exception {
        var large = XML.replace("synthetic-not-a-signature", "a".repeat(128 * 1024));
        assertThat(read(large).structured().proposals()).hasSize(9);
        assertInvalid(large.replace("0.31", "0.32"));
        assertInvalid("<Invoice>" + "甲".repeat(22_000) + "</Invoice>");
        assertInvalid("<Invoice>" + "😀".repeat(17_000) + "</Invoice>");
        assertThat(read("<Invoice>" + "甲".repeat(100) + "</Invoice>").modelText()).contains("甲".repeat(100));
    }

    @Test
    void dtdEntitiesTruncationAndUnboundedStructuresFailBeforeReturningCandidates() {
        assertInvalid("<!DOCTYPE EInvoice [<!ENTITY x SYSTEM 'file:///must-not-read'>]><EInvoice>&x;</EInvoice>");
        assertInvalid(XML.replace("</EInvoice>", ""));
        assertInvalid("<a>".repeat(65) + "</a>".repeat(65));
        assertInvalid("<a>" + "<b/>".repeat(100_000) + "</a>");
        var attributes = new StringBuilder("<a");
        for (int i = 0; i < 65; i++) attributes.append(" a").append(i).append("='x'");
        assertInvalid(attributes + "/>");
    }

    private static InvoiceExtractionXml.Content read(String xml) throws Exception {
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        return InvoiceExtractionXml.read(input(bytes), bytes);
    }
    private static InvoiceExtractionInput input(byte[] bytes) throws Exception {
        return new InvoiceExtractionInput(UUID.randomUUID(), UUID.randomUUID(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                InvoiceOriginal.Format.XML, bytes.length, 1);
    }
    private static Map<InvoiceExtractionSuggestion.Field, String> values(InvoiceExtractionSuggestion result) {
        return result.proposals().stream().collect(Collectors.toMap(InvoiceExtractionSuggestion.Proposal::field, InvoiceExtractionSuggestion.Proposal::value));
    }
    private static void assertInvalid(String xml) {
        assertThatThrownBy(() -> read(xml)).isInstanceOfSatisfying(DomainException.class,
                failure -> assertThat(failure.code()).isEqualTo("INVOICE_EXTRACTION_SOURCE_UNAVAILABLE"));
    }
}
