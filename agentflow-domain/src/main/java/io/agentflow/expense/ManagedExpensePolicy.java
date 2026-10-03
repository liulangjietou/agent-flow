package io.agentflow.expense;

import io.agentflow.common.DomainException;

/**
 * 传给企业判定服务的已发布完整规则和本行类别；企业源提供职级、城市等级和真实票据事实。
 * @author owlzhangfq@gmail.com
 */
public record ManagedExpensePolicy(ExpensePolicySelection selection, ExpensePolicyDefinition definition, ExpenseCategoryCatalog.Category category) {
    /** 只发送已启用类别和非空制度，不允许网关把不完整配置当成旧制度继续执行。 */
    public ManagedExpensePolicy {
        if (selection == null || definition == null || definition.rules().isEmpty() || category == null || !category.active()) {
            throw new DomainException("INVALID_EXPENSE_POLICY_SELECTION", "Managed expense policy requires a published definition and active category");
        }
    }
}
