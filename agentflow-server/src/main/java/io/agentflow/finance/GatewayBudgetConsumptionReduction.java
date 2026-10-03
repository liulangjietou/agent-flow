package io.agentflow.finance;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 独立预算差额使用固定目的地、编号和精确分摊；传输共用网关，业务回执由本适配器核对。
 * @author owlzhangfq@gmail.com
 */
@Component
public class GatewayBudgetConsumptionReduction implements BudgetConsumptionReductionPort {
    private final FinanceGatewayClient client;

    /** 复用事务外、严格 JSON 与有界响应的既有传输。 */
    public GatewayBudgetConsumptionReduction(FinanceGatewayClient client) { this.client = client; }

    /** 发送前检查授权时效，原消费、前次结果和本次差额固定在同一命令中。 */
    @Override public FinanceResult<BudgetConsumptionReductionObservation> execute(String targetDigest, BudgetConsumptionReductionCommand command) {
        command.requireSendAt(Instant.now());
        return client.executeBudgetReduction(command.source().tenantId(), targetDigest, command.id(), new Execute(command, command.digest()),
                BudgetConsumptionReductionObservation.class, value -> value.matches(command, false, Instant.now()));
    }

    /** 查询不带写入幂等头，授权过期后也不替换原编号及摘要。 */
    @Override public FinanceResult<BudgetConsumptionReductionObservation> query(String targetDigest, BudgetConsumptionReductionCommand command) {
        return client.queryBudgetReduction(command.source().tenantId(), targetDigest, new Query(command.id(), command.digest()),
                BudgetConsumptionReductionObservation.class, value -> value.matches(command, true, Instant.now()));
    }

    /**
     * 写入携带完整不可变指令，外部必须在应用差额的同一事务保存幂等结果。
     * @author owlzhangfq@gmail.com
     */
    private record Execute(BudgetConsumptionReductionCommand command, String commandDigest) { }

    /**
     * 恢复只读取原操作；请求关联号与持久指令号分开。
     * @author owlzhangfq@gmail.com
     */
    private record Query(UUID operationId, String commandDigest) { }
}
