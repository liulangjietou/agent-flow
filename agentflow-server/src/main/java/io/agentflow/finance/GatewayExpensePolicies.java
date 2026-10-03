package io.agentflow.finance;

import io.agentflow.expense.ExpensePolicyPort;
import org.springframework.stereotype.Component;
import java.time.Instant;

/**
 * 费用标准与税务口径由外部制度事实源判定，本地核对核算输入的一致性。
 * @author owlzhangfq@gmail.com
 */
@Component
public class GatewayExpensePolicies implements ExpensePolicyPort {
    private final FinanceGatewayClient client;

    /** 具体企业额度、税率与例外制度不写入适配器常量。 */
    public GatewayExpensePolicies(FinanceGatewayClient client) { this.client = client; }

    /** 政策版本可以给出拒绝或超标，但不允许改换待核算行金额或增加票面税额。 */
    @Override public FinanceResult<Assessment> assess(String tenantId, Request request) {
        return client.read(tenantId, FinanceGatewayClient.Operation.EXPENSE_POLICY, request, Assessment.class,
                value -> value.validUntil().isAfter(Instant.now())
                        && value.policy().assessedGross().equals(request.exchangeRate().convert(request.line().claimedGross()))
                        && value.deductibleTax().compareTo(request.exchangeRate().convert(request.line().claimedTax())) <= 0
                        && matchesManagedPolicy(request, value));
    }

    private static boolean matchesManagedPolicy(Request request, Assessment assessment) {
        var managed = request.managedPolicy(); var receipt = assessment.policy().managedPolicy();
        if (managed == null) return receipt == null;
        if (receipt == null || !receipt.selection().equals(managed.selection())) return false;
        var rule = managed.definition().rules().stream().filter(value -> value.key().equals(receipt.ruleKey())).findFirst().orElse(null);
        if (rule == null || !rule.match().acceptsKnownFacts(request.legalEntityId(), request.line())
                || rule.constraints().priorRequestRequired() != assessment.priorRequestRequired()) return false;
        if (rule.constraints().effect() == io.agentflow.expense.ExpensePolicyDefinition.Effect.DENY) return assessment.policy().decision() == io.agentflow.expense.ExpensePolicySnapshot.Decision.DENIED;
        return assessment.policy().exceptionReasons().stream().allMatch(reason -> switch (reason) {
            case AMOUNT -> rule.constraints().unitPriceLimit() != null;
            case SERVICE_LEVEL -> !rule.constraints().allowedServiceLevels().isEmpty();
            case INVOICE_AGE -> rule.constraints().invoiceAgeAction() == io.agentflow.expense.ExpensePolicyDefinition.AgeAction.REQUIRE_REASON;
        });
    }
}
