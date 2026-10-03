package io.agentflow.finance;

import io.agentflow.expense.Invoice;
import io.agentflow.expense.InvoiceVerificationPort;
import org.springframework.stereotype.Component;
import java.time.Instant;

/**
 * 通过受控财务网关查验原件；被取消、过期或错配的响应不能成为已查验事实。
 * @author owlzhangfq@gmail.com
 */
@Component
public class GatewayInvoiceVerification implements InvoiceVerificationPort {
    private final FinanceGatewayClient client;

    /** 原件只发送给该租户配置的目的地，不允许页面任意指定服务。 */
    public GatewayInvoiceVerification(FinanceGatewayClient client) { this.client = client; }

    /** 成功事实同时绑定法人、原件摘要和查验时间区间。 */
    @Override public FinanceResult<Invoice.VerifiedFacts> verify(String tenantId, Request request) {
        return client.read(tenantId, FinanceGatewayClient.Operation.INVOICE_VERIFICATION, request, Invoice.VerifiedFacts.class,
                value -> request.legalEntityId().equals(value.legalEntityId()) && request.originalDigest().equals(value.originalDigest())
                        && !value.verifiedAt().isAfter(Instant.now()) && value.validUntil().isAfter(Instant.now()));
    }
}
