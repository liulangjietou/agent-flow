package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.BudgetConsumptionReversalOperation;
import java.time.Instant;
import org.apache.commons.lang3.StringUtils;

/**
 * 独立财务对无副作用调整的明确结束，保留原授权和已停止预算命令的具体修订。
 * @author owlzhangfq@gmail.com
 */
public record ExpenseResourceAdjustmentRetirement(ExpenseResourceAdjustment before, BudgetConsumptionReversalOperation stoppedBudget,
        String retiredBy, String evidenceReference, String reason, Instant retiredAt) {
    /** 当前财务资格在应用层复核，申请人和原出纳不能结束自己的原报销调整。 */
    public ExpenseResourceAdjustmentRetirement {
        if (before == null || stoppedBudget == null || !text(retiredBy, 128) || !text(evidenceReference, 128) || !text(reason, 2000)) throw invalid();
        before.input().basis().requireAuthorization(retiredBy, retiredAt);
        before.retire(stoppedBudget, retiredAt);
    }
    /** 原子的后状态由同一份结束证据派生，不能额外编辑预算或资源。 */
    public ExpenseResourceAdjustment after() { return before.retire(stoppedBudget, retiredAt); }
    private static boolean text(String value, int max) { return StringUtils.isNotBlank(value) && value.equals(value.trim()) && value.length() <= max && value.chars().noneMatch(Character::isISOControl); }
    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_ADJUSTMENT_RETIREMENT", "Adjustment retirement requires independent finance and a stopped, safely unexecuted budget operation"); }
}
