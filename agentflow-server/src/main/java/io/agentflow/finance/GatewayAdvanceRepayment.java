package io.agentflow.finance;

import org.springframework.stereotype.Component;
import java.time.Instant;

/**
 * 借款还款只读适配器，固定目的地并校验原借款、收款与 ERP 分录的一致性。
 * @author owlzhangfq@gmail.com
 */
@Component
public class GatewayAdvanceRepayment implements AdvanceRepaymentPort {
    private final FinanceGatewayClient client;
    /** 共用有界严格协议，不允许请求指定外部地址或手填到账金额。 */
    public GatewayAdvanceRepayment(FinanceGatewayClient client) { this.client = client; }
    /** 读取必须在事务外执行；没有配置或回执不一致时保持不可用。 */
    @Override public FinanceResult<Receipt> query(String tenantId, String targetDigest, Request request) {
        return client.queryAdvanceRepayment(tenantId, targetDigest, request, Receipt.class, value -> value.matches(request, Instant.now()));
    }
}
