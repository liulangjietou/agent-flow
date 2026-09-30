package io.agentflow.finance;

import io.agentflow.budget.BudgetLedgerPort;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * 原预算台账的只读 HTTP 适配器；缺配置或无权限均不返回虚构额度。
 * @author owlzhangfq@gmail.com
 */
@Component
public class GatewayBudgetLedger implements BudgetLedgerPort {
    private final FinanceGatewayClient client;

    /** 复用固定目的地和严格金额解码，事务边界由共用传输统一约束。 */
    public GatewayBudgetLedger(FinanceGatewayClient client) { this.client = client; }

    /** 每次响应必须对应原申请人、日期及预算集合，并仍在证据有效窗口内。 */
    @Override public FinanceResult<Snapshot> read(String tenantId, String targetDigest, Request request) {
        return client.readBudgetLedger(tenantId, targetDigest, request, Snapshot.class, value -> value.matches(request, Instant.now()));
    }
}
