package io.agentflow.finance;

import io.agentflow.expense.ExpensePolicyConfiguration;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 主数据适配器确认员工与法人绑定，目录和账户都不能跨主体复用。
 * @author owlzhangfq@gmail.com
 */
@Component
public class GatewayFinanceMasterData implements FinanceMasterDataPort, EmployeeAccountPort {
    private final FinanceGatewayClient client;
    private final ExpensePolicyConfiguration policies;

    /** 同一真实主数据服务承担目录与员工账户读取。 */
    public GatewayFinanceMasterData(FinanceGatewayClient client, ExpensePolicyConfiguration policies) { this.client = client; this.policies = policies; }

    /** 目录只服务当前员工，并在到达平台时重新核对有效期。 */
    @Override public FinanceResult<FinanceCatalog> catalog(String tenantId, String employeeId) {
        var result = client.read(tenantId, FinanceGatewayClient.Operation.CATALOG, Map.of("employeeId", employeeId), FinanceCatalog.class,
                value -> employeeId.equals(value.employeeId()) && value.validUntil().isAfter(Instant.now()));
        if (result instanceof FinanceResult.Success<FinanceCatalog> success) return new FinanceResult.Success<>(policies.filterCatalog(tenantId, success.value()));
        return result;
    }

    /** 完整卡号不能作为掩码进入存储、页面或付款授权材料。 */
    @Override public FinanceResult<Account> primaryAccount(String tenantId, String employeeId, UUID legalEntityId) {
        return client.read(tenantId, FinanceGatewayClient.Operation.EMPLOYEE_ACCOUNT,
                Map.of("employeeId", employeeId, "legalEntityId", legalEntityId), Account.class,
                value -> employeeId.equals(value.snapshot().employeeId()) && legalEntityId.equals(value.snapshot().legalEntityId())
                        && value.validUntil().isAfter(Instant.now()));
    }
}
