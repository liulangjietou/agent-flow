package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.BudgetPrecheckPort;
import io.agentflow.finance.FinanceCatalog;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 提交候选事实；预检不修改单据、发票、额度或预算，正式提交仍须复核这些版本。
 * @author owlzhangfq@gmail.com
 */
public record ExpensePrecheckEvidence(String catalogVersion, FinanceCatalog.LegalEntity legalEntity, LocalDate rateDate,
        BudgetPrecheckPort.Assessment budget, ExpenseRound preview, List<ResourceVersion> resources,
        List<InvoiceReceipt> invoices, Instant validUntil) {
    /** 有效期取全部事实的最早时点；预览不可作为实际提交历史保存。 */
    public ExpensePrecheckEvidence {
        if (org.apache.commons.lang3.StringUtils.isBlank(catalogVersion) || catalogVersion.length() > 128
                || legalEntity == null || rateDate == null || budget == null || preview == null || validUntil == null
                || !legalEntity.id().equals(preview.content().legalEntityId()) || !legalEntity.baseCurrency().equals(preview.baseCurrency())
                || !preview.adjustments().isEmpty() || validUntil.isAfter(budget.validUntil())) throw invalid();
        resources = List.copyOf(resources); invoices = List.copyOf(invoices);
        if (resources.stream().map(value -> value.kind() + ":" + value.id()).distinct().count() != resources.size()
                || invoices.stream().map(InvoiceReceipt::invoiceId).distinct().count() != invoices.size()) throw invalid();
    }

    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_PRECHECK", "Expense precheck evidence is inconsistent"); }

    /**
     * 所有当前和上一轮资源都记录输入版本，包括本次准备释放的资源。
     * @author owlzhangfq@gmail.com
     */
    public record ResourceVersion(ResourceKind kind, UUID id, long version) {
        /** 版本绑定到具体种类，不能用同 UUID 的其他聚合代替。 */
        public ResourceVersion { if (kind == null || id == null || version < 1) throw invalid(); }
    }
    /**
     * 财务预检真正读取的三类聚合。
     * @author owlzhangfq@gmail.com
     */
    public enum ResourceKind { INVOICE, PRIOR_REQUEST, ADVANCE }
    /**
     * 当前发票版本的成功任务和票面事实；原件字节不复制进预检数据库。
     * @author owlzhangfq@gmail.com
     */
    public record InvoiceReceipt(UUID invoiceId, long invoiceVersion, UUID verificationId, UUID originalId, Invoice.VerifiedFacts facts) {
        /** 成功回执必须指向已查验的版本和真实原件。 */
        public InvoiceReceipt {
            Objects.requireNonNull(facts);
            if (invoiceId == null || invoiceVersion < 2 || verificationId == null || originalId == null) throw invalid();
        }
    }
}
