package io.agentflow.finance;

import org.springframework.stereotype.Component;
import java.time.Instant;

/**
 * 固定原 ERP 目标读取反向凭证，严格核对原行、科目和借贷方向。
 * @author owlzhangfq@gmail.com
 */
@Component
public class GatewayVoucherReversal implements VoucherReversalPort {
    private final FinanceGatewayClient client;
    /** 复用有界、严格解析、事务外的财务读取协议。 */
    public GatewayVoucherReversal(FinanceGatewayClient client) { this.client = client; }
    /** 未配置或原件不完整返回不可用，不伪造独立冲销分录。 */
    @Override public FinanceResult<Receipt> query(String tenantId, String targetDigest, Request request) {
        return client.queryVoucherReversal(tenantId, targetDigest, request, Receipt.class, value -> value.matches(request, Instant.now()));
    }
}
