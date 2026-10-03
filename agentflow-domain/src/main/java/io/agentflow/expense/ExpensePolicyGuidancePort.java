package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceResult;
import org.apache.commons.lang3.StringUtils;

/**
 * 向同一企业制度事实源读取填报规则，不伪造发票、税额、汇率或报销通过结论。
 * @author owlzhangfq@gmail.com
 */
public interface ExpensePolicyGuidancePort {
    /** 查询无财务副作用，后续提交仍须执行完整制度预检。 */
    FinanceResult<ExpensePolicyGuidance> guidance(String tenantId, Request request);

    /**
     * 员工取自认证身份，平台配置由服务端固定；外部源负责员工职级与城市等级匹配。
     * @author owlzhangfq@gmail.com
     */
    record Request(String employeeId, ExpensePolicyGuidance.Context context, ManagedExpensePolicy managedPolicy) {
        /** 请求的启用类别及单位必须与本行输入一致。 */
        public Request {
            if (StringUtils.isBlank(employeeId) || employeeId.length() > 128 || context == null
                    || managedPolicy != null && (!managedPolicy.category().code().equals(context.categoryCode())
                    || !managedPolicy.category().units().contains(context.unit()))) {
                throw new DomainException("INVALID_EXPENSE_POLICY_GUIDANCE_REQUEST", "Expense policy guidance identity and category must match");
            }
        }
    }
}
