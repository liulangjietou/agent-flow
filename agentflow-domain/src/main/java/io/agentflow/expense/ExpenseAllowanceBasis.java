package io.agentflow.expense;

import io.agentflow.common.DomainException;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 与费用行一起保存的补贴计算依据，关联实际发布选择和匹配事实来源。
 * @author owlzhangfq@gmail.com
 */
public record ExpenseAllowanceBasis(ExpensePolicyReceipt policy, ExpenseAllowanceRule.Calculation calculation) {
    /** 发布版本与完整等式缺一不可，不能仅保存一个可被重新解释的金额。 */
    public ExpenseAllowanceBasis {
        if (policy == null || calculation == null) {
            throw new DomainException("INVALID_ALLOWANCE_BASIS", "Allowance calculation requires a published policy receipt");
        }
    }

    /** 一个计算段必须全部处于同一匹配规则的生效日期内，跨版本区间需拆分行程。 */
    public static ExpenseAllowanceBasis calculate(ExpensePolicyReceipt receipt, ExpensePolicyDefinition.Rule rule,
                                                  UUID legalEntityId, ExpenseLine line) {
        return calculate(receipt, rule, legalEntityId, line.categoryCode(), line.claimedGross().currency(), line.incurredOn(), line.endedOn());
    }

    /** 填报预览与实际保存采用同一等式，不以虚构金额、分摊或发票构造费用行。 */
    public static ExpenseAllowanceBasis calculate(ExpensePolicyReceipt receipt, ExpensePolicyDefinition.Rule rule,
            UUID legalEntityId, String categoryCode, String currency, LocalDate startsOn, LocalDate endsOn) {
        var allowance = rule.constraints().fixedAllowance();
        if (allowance == null || !receipt.ruleKey().equals(rule.key())) {
            throw new DomainException("ALLOWANCE_RULE_REQUIRED", "The selected rule does not define a fixed allowance");
        }
        var calculation = allowance.calculate(startsOn, endsOn);
        if (!rule.match().acceptsKnownFacts(legalEntityId, categoryCode, startsOn, currency)
                || !rule.match().acceptsKnownFacts(legalEntityId, categoryCode, endsOn, currency)) {
            throw new DomainException("ALLOWANCE_POLICY_PERIOD_MISMATCH", "The whole allowance itinerary must match one effective policy rule");
        }
        return new ExpenseAllowanceBasis(receipt, calculation);
    }

    /** 新预检可刷新事实来源，但保存时确认的制度选择、规则和计算结果必须仍然一致。 */
    public void requireCurrent(ExpenseAllowanceBasis current) {
        if (!policy.selection().equals(current.policy.selection()) || !policy.ruleKey().equals(current.policy.ruleKey())
                || !calculation.equals(current.calculation)) {
            throw new DomainException("ALLOWANCE_RECALCULATION_REQUIRED", "Allowance itinerary or published rule changed and must be recalculated");
        }
    }
}
