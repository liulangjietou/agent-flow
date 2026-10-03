package io.agentflow.expense;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.util.UUID;

/**
 * 不可变费用制度发布物；原草稿修订、类别修订及发布人均成为可追溯身份。
 * @author owlzhangfq@gmail.com
 */
public record PublishedExpensePolicy(UUID policyId, String tenantId, String key, long version, long draftRevision,
                                     long categoryRevision, ExpensePolicyDefinition definition, String publishedBy,
                                     Instant publishedAt, String comment) {
    /** 发布记录不允许缺失来源版本或审计理由。 */
    public PublishedExpensePolicy {
        if (policyId == null || StringUtils.isBlank(tenantId) || tenantId.length() > 64 || !ExpensePolicyDraft.validKey(key)
                || version < 1 || draftRevision < version || categoryRevision < 1 || definition == null || definition.rules().isEmpty()
                || StringUtils.isBlank(publishedBy) || publishedBy.length() > 128 || publishedAt == null
                || StringUtils.isBlank(comment) || comment.length() > 2000) throw new DomainException("INVALID_EXPENSE_POLICY_PUBLICATION", "Expense policy publication requires immutable source versions and audit facts");
        comment = comment.strip();
    }
}
