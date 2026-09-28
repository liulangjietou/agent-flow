package io.agentflow.finance;

import org.springframework.stereotype.Component;
import java.time.Instant;

/**
 * 预算可用性属于企业事实源，成功证据须精确绑定本次整单分摊。
 * @author owlzhangfq@gmail.com
 */
@Component
public class GatewayBudgetPrecheck implements BudgetPrecheckPort {
    private final FinanceGatewayClient client;
    /** 复用严格租户传输和事务禁止，不扩展任意远端操作。 */
    public GatewayBudgetPrecheck(FinanceGatewayClient client) { this.client = client; }

    /** 不接受金额相同但科目、期间、人员或版本不同的预算通过结果。 */
    @Override public FinanceResult<Assessment> precheck(String tenantId, Request request) {
        return client.read(tenantId, FinanceGatewayClient.Operation.BUDGET_PRECHECK, request, Assessment.class,
                value -> value.request().equals(request) && !value.checkedAt().isAfter(Instant.now()) && value.validUntil().isAfter(Instant.now()));
    }
}
