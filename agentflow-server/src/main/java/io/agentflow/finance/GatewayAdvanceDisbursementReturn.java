package io.agentflow.finance;

import org.springframework.stereotype.Component;
import java.time.Instant;

/**
 * 固定原资金系统中的银行退回与借款贷方调整，传输成功不代替独立财务确认。
 * @author owlzhangfq@gmail.com
 */
@Component
public class GatewayAdvanceDisbursementReturn implements AdvanceDisbursementReturnPort {
    private final FinanceGatewayClient client;
    /** 复用有界、严格解析、事务外的财务只读协议。 */
    public GatewayAdvanceDisbursementReturn(FinanceGatewayClient client) { this.client = client; }
    /** 未配置、目标变化或凭据不完整均返回不可用，不伪造退回事实。 */
    @Override public FinanceResult<Receipt> query(String tenantId, String targetDigest, Request request) {
        return client.queryDisbursementReturn(tenantId, targetDigest, request, Receipt.class, value -> value.matches(request, Instant.now()));
    }
}
