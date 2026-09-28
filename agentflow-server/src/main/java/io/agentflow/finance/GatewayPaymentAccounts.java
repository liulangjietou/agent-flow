package io.agentflow.finance;

import org.springframework.stereotype.Component;
import java.time.Instant;

/**
 * 付款账户只读协议与原付款目的地绑定，账户读取成功不代表已授权或已支付。
 * @author owlzhangfq@gmail.com
 */
@Component
public class GatewayPaymentAccounts implements PaymentAccountsPort {
    private final FinanceGatewayClient client;
    /** 共用严格 JSON、有界响应和事务外 HTTP，拒绝客户端指定地址。 */
    public GatewayPaymentAccounts(FinanceGatewayClient client) { this.client = client; }

    /** 出款账户必须对原出纳、法人和币种有效，空目录保持为空。 */
    @Override public FinanceResult<Directory> debitAccounts(String tenantId, String targetDigest, Request request) {
        return client.readPaymentAccounts(tenantId, targetDigest, FinanceGatewayClient.Operation.DEBIT_ACCOUNTS, request,
                Directory.class, value -> value.matches(request, Instant.now()));
    }

    /** 复用员工账户协议，但付款执行中的读取不能随新配置改换财务系统。 */
    @Override public FinanceResult<EmployeeAccountPort.Account> currentPayee(String tenantId, String targetDigest, PayeeRequest request) {
        return client.readPaymentAccounts(tenantId, targetDigest, FinanceGatewayClient.Operation.EMPLOYEE_ACCOUNT, request,
                EmployeeAccountPort.Account.class, value -> value.snapshot().legalEntityId().equals(request.legalEntityId())
                        && value.snapshot().employeeId().equals(request.employeeId()) && value.validUntil().isAfter(Instant.now()));
    }
}
