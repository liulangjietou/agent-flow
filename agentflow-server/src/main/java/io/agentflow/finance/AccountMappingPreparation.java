package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.expense.JdbcExpenseConfigurationRepository;
import org.springframework.stereotype.Component;

/**
 * 只读准备的配置选择与登记守卫；事务由准备服务拥有，已登记凭证不再重新选择。
 * @author owlzhangfq@gmail.com
 */
@Component
public class AccountMappingPreparation {
    private final AccountMappingConfigurationService configurations;
    private final JdbcExpenseConfigurationRepository locks;
    private final FinanceGatewayConfiguration gateway;

    /** 复用发布的租户配置锁，首次启用和换版采用相同串行边界。 */
    public AccountMappingPreparation(AccountMappingConfigurationService configurations, JdbcExpenseConfigurationRepository locks, FinanceGatewayConfiguration gateway) {
        this.configurations = configurations; this.locks = locks; this.gateway = gateway;
    }

    /** 调用方先持有申请和财务聚合锁；选择随领取提交，锁不会跨越 ERP 读取。 */
    public AccountMappingPort.Request select(String tenant, AccountMappingPort.Request request, String targetDigest) {
        var destination = gateway.destination(tenant);
        if (destination.isEmpty() || targetDigest == null) throw failure("NOT_CONFIGURED");
        if (!destination.get().digest(tenant).equals(targetDigest)) throw failure("TARGET_CHANGED");
        locks.lock(tenant);
        var current = configurations.current(tenant, request.legalEntityId(), request.currency());
        if (current.activeMapping() == null) return request;
        if (!current.activeMapping().targetDigest().equals(targetDigest)) throw failure("TARGET_CHANGED");
        return current.activeMapping().request(request, current.activeRevision());
    }

    /** 在同一登记事务中复核原选择和回执；首次发布也不能穿过无管理配置的旧领取。 */
    public void requireCurrent(VoucherPreparation preparation, VoucherCommand command) {
        var original = preparation.mappingRequest();
        if (original == null) throw failure("ACCOUNT_MAPPING_SELECTION_MISSING");
        if (!original.equals(command.mapping().request())) throw failure("ACCOUNT_MAPPING_EVIDENCE_MISMATCH");
        var request = new AccountMappingPort.Request(original.legalEntityId(), original.currency(), original.keys());
        var current = select(preparation.input().source().tenantId(), request, preparation.input().targetDigest());
        if (!original.equals(current)) throw failure("ACCOUNT_MAPPING_CHANGED");
    }

    private static DomainException failure(String code) { return new DomainException(code, "Voucher preparation requires its original current account mapping and target"); }
}
