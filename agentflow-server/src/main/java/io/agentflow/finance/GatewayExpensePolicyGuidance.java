package io.agentflow.finance;

import io.agentflow.expense.ExpensePolicyGuidance;
import io.agentflow.expense.ExpensePolicyGuidancePort;
import org.springframework.stereotype.Component;
import java.time.Instant;

/**
 * 填报提示使用固定财务协议，管理规则正文与预检采用同一选择，响应只含适用规则。
 * @author owlzhangfq@gmail.com
 */
@Component
public class GatewayExpensePolicyGuidance implements ExpensePolicyGuidancePort {
    private final FinanceGatewayClient client;

    /** 与正式制度判定共用传输和企业目标，不增设另一个标准源。 */
    public GatewayExpensePolicyGuidance(FinanceGatewayClient client) { this.client = client; }

    /** 拒绝过期、跨币种、其他版本或被改写的规则提示。 */
    @Override public FinanceResult<ExpensePolicyGuidance> guidance(String tenantId, Request request) {
        return client.read(tenantId, FinanceGatewayClient.Operation.EXPENSE_POLICY_GUIDANCE, request, ExpensePolicyGuidance.class,
                result -> result.validUntil().isAfter(Instant.now()) && matches(request, result));
    }

    private boolean matches(Request request, ExpensePolicyGuidance result) {
        var context = request.context(); var cap = result.constraints().unitPriceLimit();
        if (cap != null && !cap.currency().equals(context.currency())) return false;
        var managed = request.managedPolicy();
        if (managed == null) return result.selection() == null;
        if (!managed.selection().equals(result.selection()) || !managed.definition().name().equals(result.policyName())) return false;
        var rule = managed.definition().rules().stream().filter(candidate -> candidate.key().equals(result.ruleKey())).findFirst().orElse(null);
        return rule != null && rule.name().equals(result.ruleName()) && rule.constraints().equals(result.constraints())
                && rule.match().acceptsKnownFacts(context.legalEntityId(), context.categoryCode(), context.incurredOn(), context.currency());
    }
}
