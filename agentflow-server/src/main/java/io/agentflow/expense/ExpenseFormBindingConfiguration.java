package io.agentflow.expense;

import io.agentflow.approval.BusinessFormBinding;
import io.agentflow.approval.model.BusinessReference;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 报销、费用计划与借款申请向审批核心登记表单契约。
 * @author owlzhangfq@gmail.com
 */
@Configuration(proxyBeanMethods = false)
public class ExpenseFormBindingConfiguration {
    /** 报销单。 */
    @Bean
    BusinessFormBinding expenseFormBinding() {
        return new BusinessFormBinding(BusinessReference.Type.EXPENSE, ExpenseFormContract::structured, ExpenseFormContract::requireSchema);
    }

    /** 费用计划。 */
    @Bean
    BusinessFormBinding expensePlanFormBinding() {
        return new BusinessFormBinding(BusinessReference.Type.EXPENSE_PLAN, ExpensePlanFormContract::structured, ExpensePlanFormContract::requireSchema);
    }

    /** 借款申请。 */
    @Bean
    BusinessFormBinding advanceRequestFormBinding() {
        return new BusinessFormBinding(BusinessReference.Type.ADVANCE_REQUEST, AdvanceRequestFormContract::structured, AdvanceRequestFormContract::requireSchema);
    }
}
