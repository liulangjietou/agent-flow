package io.agentflow.finance;

import io.agentflow.procurement.SupplierPaymentReturnPort;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * 供应商资金退回只读适配器，沿原付款目标取得公司实际入款。
 * @author owlzhangfq@gmail.com
 */
@Component
public class GatewaySupplierPaymentReturn implements SupplierPaymentReturnPort {
    private final FinanceGatewayClient client;

    /** 复用固定目标、严格响应校验与事务外传输，不在查询中发送资金或 ERP 指令。 */
    public GatewaySupplierPaymentReturn(FinanceGatewayClient client) { this.client = client; }

    /** 已过期原付款仍可查询，目的地或原交易变化则拒绝采用结果。 */
    @Override public FinanceResult<Receipt> query(Request request) {
        var command = request.command();
        return client.querySupplierPaymentReturn(command.tenantId(), command.targetDigest(), request, Receipt.class,
                value -> value.matches(request, Instant.now()));
    }
}
