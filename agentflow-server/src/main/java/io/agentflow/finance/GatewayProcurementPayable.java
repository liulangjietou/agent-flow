package io.agentflow.finance;

import io.agentflow.procurement.ProcurementPayablePort;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * 原供应商应付的严格只读适配器；可信 ERP 负责提供实际匹配和已付事实。
 * @author owlzhangfq@gmail.com
 */
@Component
public class GatewayProcurementPayable implements ProcurementPayablePort {
    private final FinanceGatewayClient client;

    /** 沿用固定租户目的地、严格金额解码与事务外传输。 */
    public GatewayProcurementPayable(FinanceGatewayClient client) { this.client = client; }

    /** 空余额可以是真实已付完事实，未配置与匹配失败均不能伪造为成功。 */
    @Override public FinanceResult<Payable> payable(String tenantId, String targetDigest, Request request) {
        return client.readProcurementPayable(tenantId, targetDigest, request, Payable.class, value -> value.matches(request, Instant.now()));
    }
}
