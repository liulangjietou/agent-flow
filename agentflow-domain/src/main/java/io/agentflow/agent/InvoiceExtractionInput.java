package io.agentflow.agent;

import io.agentflow.common.DomainException;
import io.agentflow.expense.InvoiceOriginal;
import io.agentflow.expense.InvoiceVerificationPort;
import java.util.UUID;

/**
 * 票据抽取绑定完整原件及实际页数，不以文件名、模型推测或查验版本代替内容身份。
 * @author owlzhangfq@gmail.com
 */
public record InvoiceExtractionInput(UUID invoiceId, UUID originalId, String originalDigest,
                                     InvoiceOriginal.Format format, long originalBytes, int pageCount) {
    public static final int MAX_PAGES = 10;

    /** 页数由本地内容适配器读取；图片和 XML 均只有一个来源单元。 */
    public InvoiceExtractionInput {
        if (invoiceId == null || originalId == null || originalDigest == null || !originalDigest.matches("[a-f0-9]{64}")
                || format == null || originalBytes < 1 || originalBytes > InvoiceVerificationPort.Request.MAX_ORIGINAL_BYTES
                || pageCount < 1 || pageCount > MAX_PAGES
                || (format == InvoiceOriginal.Format.PNG || format == InvoiceOriginal.Format.JPEG || format == InvoiceOriginal.Format.XML)
                && pageCount != 1) {
            throw new DomainException("INVALID_AGENT_INPUT", "Invoice extraction source is invalid or exceeds its limits");
        }
    }

    /** 来源引用必须属于完整原件和实际存在的页，不能替换其他票据或虚构页码。 */
    public void requireEvidence(InvoiceExtractionSuggestion.Evidence evidence) {
        if (!originalId.equals(evidence.originalId()) || !originalDigest.equals(evidence.originalDigest())
                || evidence.page() > pageCount) {
            throw new DomainException("INVALID_AGENT_OUTPUT", "Invoice evidence does not match the authorized original");
        }
    }
}
