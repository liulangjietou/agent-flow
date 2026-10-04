package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 填报阶段适用规则及来源；没有查验票据、税额和可报金额，不构成提交判定。
 * @author owlzhangfq@gmail.com
 */
public record ExpensePolicyGuidance(UUID policyId, long policyVersion, String policyName, String ruleKey,
        String ruleName, ExpensePolicyDefinition.Constraints constraints, String factSourceReference,
        Instant validUntil, ExpensePolicySelection selection) {
    /** 提示必须可追溯到实际规则，管理制度的身份与版本不能被远端替换。 */
    public ExpensePolicyGuidance {
        if (policyId == null || policyVersion < 1 || !text(policyName, 128) || !text(ruleKey, 64)
                || !text(ruleName, 128) || constraints == null || !text(factSourceReference, 128) || validUntil == null
                || selection != null && (!selection.policyId().equals(policyId) || selection.policyVersion() != policyVersion)) {
            throw new DomainException("INVALID_EXPENSE_POLICY_GUIDANCE", "Expense policy guidance and its versioned source must be consistent");
        }
    }

    /** 应用只可缩短来源有效期，不能延长已确认事实。 */
    public ExpensePolicyGuidance validThrough(Instant until) {
        if (until == null || until.isAfter(validUntil)) throw new IllegalArgumentException("Guidance validity cannot extend its source");
        return new ExpensePolicyGuidance(policyId, policyVersion, policyName, ruleKey, ruleName, constraints, factSourceReference, until, selection);
    }

    private static boolean text(String value, int maximum) {
        return StringUtils.isNotBlank(value) && value.length() <= maximum && value.equals(value.strip()) && value.chars().noneMatch(Character::isISOControl);
    }

    private static boolean supportedCurrency(String currency) {
        try { Money.zero(currency); return true; }
        catch (DomainException unsupported) { return false; }
    }

    /**
     * 仅包含匹配所需的用户输入；职级、城市等级和规则结论均不能由申请人指定。
     * @author owlzhangfq@gmail.com
     */
    public record Context(UUID legalEntityId, ExpenseContent.Type reportType, String categoryCode, String cityCode,
                          LocalDate incurredOn, String currency, ExpenseLine.Unit unit, LocalDate endedOn) {
        /** 普通费用继续支持只指定发生日的提示请求。 */
        public Context(UUID legalEntityId, ExpenseContent.Type reportType, String categoryCode, String cityCode,
                       LocalDate incurredOn, String currency, ExpenseLine.Unit unit) {
            this(legalEntityId, reportType, categoryCode, cityCode, incurredOn, currency, unit, null);
        }
        /** 只读输入在入口统一校验，完整费用行的金额与分摊由保存服务负责。 */
        public Context {
            if (legalEntityId == null || reportType == null || !text(categoryCode, 64) || !text(cityCode, 128)
                    || incurredOn == null || endedOn != null && endedOn.isBefore(incurredOn) || !supportedCurrency(currency) || unit == null) {
                throw new DomainException("INVALID_EXPENSE_GUIDANCE_QUERY", "Expense policy guidance dimensions are invalid");
            }
        }
    }
}
