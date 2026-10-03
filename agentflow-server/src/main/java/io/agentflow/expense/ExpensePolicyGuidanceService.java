package io.agentflow.expense;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceGatewayConfiguration;
import io.agentflow.finance.FinanceMasterDataPort;
import org.springframework.stereotype.Service;
import java.time.Instant;

/**
 * 本人目录授权与同版制度提示的编排；网络调用不进入数据库事务，提示不写入任何审批事实。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpensePolicyGuidanceService {
    private static final int MAX_VALID_SECONDS = 300;
    private final CurrentActor actors;
    private final FinanceMasterDataPort catalogs;
    private final ExpensePolicyGuidancePort policies;
    private final ExpensePolicyConfiguration configuration;
    private final FinanceGatewayConfiguration gateway;

    /** 目录与制度采用同一财务目标，平台版本只由服务端选择。 */
    public ExpensePolicyGuidanceService(CurrentActor actors, FinanceMasterDataPort catalogs, ExpensePolicyGuidancePort policies,
                                       ExpensePolicyConfiguration configuration, FinanceGatewayConfiguration gateway) {
        this.actors = actors; this.catalogs = catalogs; this.policies = policies; this.configuration = configuration; this.gateway = gateway;
    }

    /** 返回前再次检查发布选择和目标，网络等待期间的换版不得被展示为当前标准。 */
    public View read(ExpensePolicyGuidance.Context context) {
        var actor = actors.actor(); String target = target(actor.tenantId()); var snapshot = configuration.snapshot(actor.tenantId());
        var catalog = catalogs.catalog(actor.tenantId(), actor.userId()).requireValue();
        if (catalog.legalEntities().stream().noneMatch(entity -> entity.id().equals(context.legalEntityId()))
                || catalog.categories().stream().noneMatch(category -> category.code().equals(context.categoryCode()) && category.units().contains(context.unit()))
                || catalog.cities().stream().noneMatch(city -> city.code().equals(context.cityCode()))) {
            throw new DomainException("EXPENSE_GUIDANCE_CONTEXT_UNAVAILABLE", "Guidance dimensions must belong to the current employee catalog");
        }
        var request = new ExpensePolicyGuidancePort.Request(actor.userId(), context, snapshot.forCategory(context.categoryCode(), context.unit()));
        var advice = policies.guidance(actor.tenantId(), request).requireValue();
        if (!configuration.current(actor.tenantId(), snapshot.selection())) throw new DomainException("POLICY_CONFIGURATION_CHANGED", "Expense policy changed while reading guidance");
        if (!target.equals(target(actor.tenantId()))) throw new DomainException("FINANCE_TARGET_CHANGED", "Finance destination changed while reading guidance");
        Instant until = Instant.now().plusSeconds(MAX_VALID_SECONDS);
        if (until.isAfter(advice.validUntil())) until = advice.validUntil();
        if (until.isAfter(catalog.validUntil())) until = catalog.validUntil();
        if (!until.isAfter(Instant.now())) throw new DomainException("FACTS_EXPIRED", "Expense policy guidance facts expired");
        return new View(context, advice.validThrough(until));
    }

    private String target(String tenant) {
        return gateway.destination(tenant).orElseThrow(() -> new DomainException("FINANCE_GATEWAY_UNAVAILABLE", "NOT_CONFIGURED")).digest(tenant);
    }

    /**
     * 回显匹配输入用于页面核对，避免迟到结果覆盖另一条费用条件。
     * @author owlzhangfq@gmail.com
     */
    public record View(ExpensePolicyGuidance.Context context, ExpensePolicyGuidance guidance) { }
}
