package io.agentflow.expense;

import io.agentflow.common.DomainException;
import java.time.Instant;

/**
 * 检查结论的解释有效性依据；不能用它构造提交候选或替代原权威检查。
 * @author owlzhangfq@gmail.com
 */
public record ExpensePrecheckObservation(Instant observedAt, Instant validUntil,
                                        ExpensePolicySelection policySelection, String dependencyDigest) {
    public static final long MAX_VALIDITY_SECONDS = 300;

    /** 旧事实可能已经过期，仍保留历史；观察本身不能延长任何外部事实的有效期。 */
    public ExpensePrecheckObservation {
        if (observedAt == null || validUntil == null || validUntil.isAfter(observedAt.plusSeconds(MAX_VALIDITY_SECONDS))
                || dependencyDigest == null || !dependencyDigest.matches("[a-f0-9]{64}")) {
            throw new DomainException("INVALID_EXPENSE_PRECHECK", "Expense precheck observation is invalid");
        }
    }

    /** 使用左闭右开的有效区间，时钟回拨也不能把尚未发生的观察作为当前事实。 */
    public boolean currentAt(Instant now) { return !now.isBefore(observedAt) && now.isBefore(validUntil); }
}
