package io.agentflow.budget;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceCatalog;
import java.time.Instant;
import java.util.List;
import org.apache.commons.lang3.StringUtils;

/**
 * 审批轮次固定调整意图、财务目标与原台账；审批通过本身不表示额度已经生效。
 * @author owlzhangfq@gmail.com
 */
public record BudgetAdjustmentRound(int roundNo, long submittedRequestVersion, String submittedBy, Instant submittedAt,
                                    BudgetAdjustmentContent content, FinanceCatalog.LegalEntity legalEntity,
                                    String catalogVersion, String targetDigest, BudgetLedgerPort.Snapshot ledger) {
    /** 恢复历史时按原提交时间校验，保留过期但当时有效的审批证据。 */
    public BudgetAdjustmentRound {
        if (roundNo < 1 || submittedRequestVersion < 1 || StringUtils.isBlank(submittedBy) || submittedBy.length() > 128
                || submittedAt == null || content == null || legalEntity == null || !legalEntity.id().equals(content.legalEntityId())
                || StringUtils.isBlank(catalogVersion) || catalogVersion.length() > 128 || targetDigest == null
                || !targetDigest.matches("[a-f0-9]{64}") || ledger == null || !ledger.matches(content.ledgerRequest(submittedBy), submittedAt)) {
            throw new DomainException("INVALID_BUDGET_ADJUSTMENT_ROUND", "Budget adjustment round must preserve original applicant, destination and current ledger evidence");
        }
        if (!content.amount().currency().equals(legalEntity.baseCurrency())) {
            throw new DomainException("BUDGET_BASE_CURRENCY_REQUIRED", "Budget adjustment must use the legal entity base currency");
        }
        content.changes(ledger);
    }

    /** 每次展示均从同一份冻结依据派生，不读取当前额度替换审批前金额。 */
    public List<BudgetAdjustmentContent.Change> changes() { return content.changes(ledger); }

    /** 不在默认对象日志中展开原预算余额和审批内容。 */
    @Override public String toString() { return "BudgetAdjustmentRound[roundNo=" + roundNo + ", submittedRequestVersion=" + submittedRequestVersion + "]"; }
}
