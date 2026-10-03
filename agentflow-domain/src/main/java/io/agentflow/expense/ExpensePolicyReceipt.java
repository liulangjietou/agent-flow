package io.agentflow.expense;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;

/**
 * 企业制度执行回执明确声明采用的配置、命中规则和职级等匹配事实来源，不由平台生成通过结论。
 * @author owlzhangfq@gmail.com
 */
public record ExpensePolicyReceipt(ExpensePolicySelection selection, String ruleKey, String factSourceReference) {
    /** 来源引用用于追溯，不承载未经权限检查的远端原始正文。 */
    public ExpensePolicyReceipt {
        if (selection == null || StringUtils.isBlank(ruleKey) || ruleKey.length() > 64 || StringUtils.isBlank(factSourceReference)
                || factSourceReference.length() > 128) throw new DomainException("INVALID_EXPENSE_POLICY_RECEIPT", "Managed expense policy receipt must identify the executed rule and fact source");
    }
}
