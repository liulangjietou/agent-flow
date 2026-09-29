package io.agentflow.finance;

import org.springframework.stereotype.Component;
import java.time.Instant;

/**
 * 报销资金退回只读适配器，外部必须返回原付款及独立应付贷方原件。
 * @author owlzhangfq@gmail.com
 */
@Component
public class GatewayExpensePaymentReturn implements ExpensePaymentReturnPort {
    private final FinanceGatewayClient client;
    public GatewayExpensePaymentReturn(FinanceGatewayClient client) { this.client = client; }
    /** 固定原目标并复核回执身份与有效期，查询不会生成资金命令。 */
    @Override public FinanceResult<Receipt> query(String tenantId, String targetDigest, Request request) {
        return client.queryExpensePaymentReturn(tenantId, targetDigest, request, Receipt.class, value -> value.matches(request, Instant.now()));
    }
}
