package io.agentflow.finance;

import org.springframework.stereotype.Component;
import java.time.Instant;

/**
 * 固定财务系统中的原还款复核，退回资金和借方调整必须共同成立。
 * @author owlzhangfq@gmail.com
 */
@Component
public class GatewayAdvanceRepaymentAdjustment implements AdvanceRepaymentAdjustmentPort {
    private final FinanceGatewayClient client;
    /** 共用有界、严格、事务外的财务传输协议。 */
    public GatewayAdvanceRepaymentAdjustment(FinanceGatewayClient client) { this.client = client; }
    /** 未配置或证据不完整保持不可用，不发送退款或记账命令。 */
    @Override public FinanceResult<Receipt> query(String tenantId, String targetDigest, Request request) {
        return client.queryRepaymentAdjustment(tenantId, targetDigest, request, Receipt.class, value -> value.matches(request, Instant.now()));
    }
}
