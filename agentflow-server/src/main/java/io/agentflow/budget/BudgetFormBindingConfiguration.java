package io.agentflow.budget;

import io.agentflow.approval.BusinessFormBinding;
import io.agentflow.approval.model.BusinessReference;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 预算调整向审批核心登记表单契约。
 * @author owlzhangfq@gmail.com
 */
@Configuration(proxyBeanMethods = false)
public class BudgetFormBindingConfiguration {
    /** 预算调整申请。 */
    @Bean
    BusinessFormBinding budgetAdjustmentFormBinding() {
        return new BusinessFormBinding(BusinessReference.Type.BUDGET_ADJUSTMENT, BudgetAdjustmentFormContract::structured,
                BudgetAdjustmentFormContract::requireSchema);
    }
}
