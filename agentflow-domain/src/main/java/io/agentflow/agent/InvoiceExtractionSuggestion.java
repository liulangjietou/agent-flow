package io.agentflow.agent;

import io.agentflow.common.DomainException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

/**
 * 票面候选值及模型引用；这里没有真实性、核销、审批或付款状态。
 * @author owlzhangfq@gmail.com
 */
public record InvoiceExtractionSuggestion(String providerId, String modelVersion, String promptVersion, List<Proposal> proposals) {
    public static final int MAX_PROPOSALS = Field.values().length;
    public static final int MAX_EVIDENCE_PER_FIELD = 4;
    public static final int MAX_QUOTE_LENGTH = 512;
    private static final int MAX_PARTY_NAME_LENGTH = 256;
    private static final int MAX_TAX_IDENTIFIER_LENGTH = 64;

    /** 缺乏依据的字段必须省略；空列表如实表示未识别，不要求模型补造字段。 */
    public InvoiceExtractionSuggestion {
        if (!identifier(providerId) || !identifier(modelVersion) || !identifier(promptVersion)
                || proposals == null || proposals.size() > MAX_PROPOSALS
                || proposals.stream().anyMatch(java.util.Objects::isNull)
                || proposals.stream().map(Proposal::field).distinct().count() != proposals.size()) throw invalid();
        proposals = List.copyOf(proposals);
    }

    /** 模型和人工值使用相同的票面格式边界，来源归属由冻结原件决定。 */
    public void requireMatches(InvoiceExtractionInput input) {
        for (var proposal : proposals) proposal.evidence().forEach(input::requireEvidence);
    }

    /**
     * 封闭的票面字段；负数红字金额可保留，字段间是否相符留给人工核对及正式查验。
     * @author owlzhangfq@gmail.com
     */
    public enum Field {
        INVOICE_CODE, INVOICE_NUMBER, ISSUE_DATE, BUYER_NAME, BUYER_TAX_ID, SELLER_NAME, SELLER_TAX_ID,
        CURRENCY, NET_AMOUNT, TAX_AMOUNT, GROSS_AMOUNT;

        /** 金额和票号使用字符串保存，保留精度与前导零，不进行浮点转换。 */
        public void requireValue(String value) {
            if (StringUtils.isBlank(value) || !value.equals(value.strip()) || value.codePoints().anyMatch(Character::isISOControl)) throw invalid();
            boolean valid = switch (this) {
                case INVOICE_CODE, INVOICE_NUMBER -> value.matches("[0-9]{1,32}");
                case ISSUE_DATE -> date(value);
                case BUYER_NAME, SELLER_NAME -> value.length() <= MAX_PARTY_NAME_LENGTH;
                case BUYER_TAX_ID, SELLER_TAX_ID -> value.length() <= MAX_TAX_IDENTIFIER_LENGTH;
                case CURRENCY -> currency(value);
                case NET_AMOUNT, TAX_AMOUNT, GROSS_AMOUNT -> value.matches("-?(0|[1-9][0-9]{0,15})(\\.[0-9]{1,2})?");
            };
            if (!valid) throw invalid();
        }
        private static boolean date(String value) {
            if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return false;
            try { LocalDate.parse(value); return true; } catch (DateTimeParseException invalid) { return false; }
        }
        private static boolean currency(String value) {
            if (!value.matches("[A-Z]{3}")) return false;
            try { Currency.getInstance(value); return true; } catch (IllegalArgumentException invalid) { return false; }
        }
    }

    /**
     * 摘录是模型声称的票面文字，必须与原件人工核对，不作为机器查验事实。
     * @author owlzhangfq@gmail.com
     */
    public record Evidence(UUID originalId, String originalDigest, int page, String quote) {
        public Evidence {
            if (originalId == null || originalDigest == null || !originalDigest.matches("[a-f0-9]{64}")
                    || page < 1 || page > InvoiceExtractionInput.MAX_PAGES
                    || StringUtils.isBlank(quote) || quote.length() > MAX_QUOTE_LENGTH) throw invalid();
        }
    }

    /**
     * 模型自评的识别把握，不是实测准确率，更不是发票真实性结论。
     * @author owlzhangfq@gmail.com
     */
    public enum Confidence { LOW, MEDIUM, HIGH }

    /**
     * 每项候选值单独绑定来源，模型原文与后续人工修订分别保留。
     * @author owlzhangfq@gmail.com
     */
    public record Proposal(Field field, String value, Confidence confidence, List<Evidence> evidence) {
        public Proposal {
            if (field == null || confidence == null || CollectionUtils.isEmpty(evidence) || evidence.size() > MAX_EVIDENCE_PER_FIELD
                    || evidence.stream().anyMatch(java.util.Objects::isNull) || new HashSet<>(evidence).size() != evidence.size()) throw invalid();
            field.requireValue(value);
            evidence = List.copyOf(evidence);
        }
    }

    /**
     * 本人明确选中的修订值；不创建已查验票面或更新财务资源。
     * @author owlzhangfq@gmail.com
     */
    public record Selection(Field field, String value) {
        public Selection {
            if (field == null) throw invalid();
            field.requireValue(value);
        }
    }

    private static boolean identifier(String value) {
        return StringUtils.isNotBlank(value) && value.length() <= AssistSuggestion.MAX_VERSION_LENGTH;
    }
    private static DomainException invalid() { return new DomainException("INVALID_AGENT_OUTPUT", "Invoice extraction value or evidence is invalid"); }
}
