package io.agentflow.agent;

import io.agentflow.common.DomainException;
import io.agentflow.expense.InvoiceOriginal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

/**
 * 票面候选值及其生产来源；这里没有真实性、核销、审批或付款状态。
 * @author owlzhangfq@gmail.com
 */
public record InvoiceExtractionSuggestion(Method method, String providerId, String processorVersion,
                                         String contractVersion, List<Proposal> proposals) {
    public static final int MAX_PROPOSALS = Field.values().length;
    public static final int MAX_EVIDENCE_PER_FIELD = 4;
    public static final int MAX_QUOTE_LENGTH = 512;
    private static final int MAX_XML_PATH_LENGTH = 512;
    private static final int MAX_PARTY_NAME_LENGTH = 256;
    private static final int MAX_TAX_IDENTIFIER_LENGTH = 64;

    /** 缺乏依据的字段必须省略；空列表如实表示未识别，不要求模型补造字段。 */
    public InvoiceExtractionSuggestion {
        if (method == null || !identifier(providerId) || !identifier(processorVersion) || !identifier(contractVersion)
                || proposals == null || proposals.size() > MAX_PROPOSALS
                || proposals.stream().anyMatch(java.util.Objects::isNull)
                || proposals.stream().map(Proposal::field).distinct().count() != proposals.size()) throw invalid();
        proposals = List.copyOf(proposals);
        for (var proposal : proposals) for (var evidence : proposal.evidence()) {
            if ((method == Method.STRUCTURED_XML) != (evidence.xmlPath() != null)) throw invalid();
        }
    }

    /** 模型和人工值使用相同的票面格式边界，来源归属由冻结原件决定。 */
    public void requireMatches(InvoiceExtractionInput input) {
        if (method == Method.STRUCTURED_XML && input.format() != InvoiceOriginal.Format.XML) throw invalid();
        for (var proposal : proposals) proposal.evidence().forEach(input::requireEvidence);
    }

    /**
     * 本地结构化提取与外部模型结果分别标记，不能将解析器版本当成模型名称。
     * @author owlzhangfq@gmail.com
     */
    public enum Method { STRUCTURED_XML, MODEL }

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
     * XML 路径只供对照，不执行 XPath；模型摘录不具有机器验证过的元素位置。
     * @author owlzhangfq@gmail.com
     */
    public record Evidence(UUID originalId, String originalDigest, int page, String quote, String xmlPath) {
        public Evidence {
            if (originalId == null || originalDigest == null || !originalDigest.matches("[a-f0-9]{64}")
                    || page < 1 || page > InvoiceExtractionInput.MAX_PAGES
                    || StringUtils.isBlank(quote) || quote.length() > MAX_QUOTE_LENGTH
                    || xmlPath != null && (xmlPath.length() > MAX_XML_PATH_LENGTH
                    || !xmlPath.matches("(/[A-Za-z_][A-Za-z0-9_.-]*\\[1\\]){1,8}"))) throw invalid();
        }

        /** 模型只能引用实际页和摘录，不能生成服务端解析的结构位置。 */
        public Evidence(UUID originalId, String originalDigest, int page, String quote) {
            this(originalId, originalDigest, page, quote, null);
        }
    }

    /**
     * 模型使用自评把握；结构化解析的 HIGH 表示直接读取明确字段，两者都不是准确率或真实性。
     * @author owlzhangfq@gmail.com
     */
    public enum Confidence { LOW, MEDIUM, HIGH }

    /**
     * 每项候选值单独绑定来源，提取原值与后续人工修订分别保留。
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
