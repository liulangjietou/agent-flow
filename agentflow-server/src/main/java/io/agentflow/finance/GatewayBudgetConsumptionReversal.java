package io.agentflow.finance;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 预算实际占用冲正使用独立路径、固定外发目标及原命令查询，不复用普通预算释放。
 * @author owlzhangfq@gmail.com
 */
@Component
public class GatewayBudgetConsumptionReversal implements BudgetConsumptionReversalPort {
    private final FinanceGatewayClient client;
    /** 严格传输共用既有网关边界，冲正业务原件由本适配器核对。 */
    public GatewayBudgetConsumptionReversal(FinanceGatewayClient client) { this.client = client; }
    /** 过期授权禁止新发送，外部必须按编号和完整摘要去重。 */
    @Override public FinanceResult<BudgetConsumptionReversalObservation> execute(String targetDigest, BudgetConsumptionReversalCommand command) {
        command.requireSendAt(Instant.now());
        return client.executeBudgetReversal(command.source().tenantId(), targetDigest, command.id(), new Execute(command, command.digest()),
                BudgetConsumptionReversalObservation.class, value -> value.matches(command, false, Instant.now()));
    }
    /** 查询不带写入幂等头，原编号与摘要在授权过期后仍保持。 */
    @Override public FinanceResult<BudgetConsumptionReversalObservation> query(String targetDigest, BudgetConsumptionReversalCommand command) {
        return client.queryBudgetReversal(command.source().tenantId(), targetDigest, new Query(command.id(), command.digest()),
                BudgetConsumptionReversalObservation.class, value -> value.matches(command, true, Instant.now()));
    }
    /**
     * 原消费和独立冲正完整快照由外部按摘要逐项验证。
     * @author owlzhangfq@gmail.com
     */
    private record Execute(BudgetConsumptionReversalCommand command, String commandDigest) { }
    /**
     * 原操作只读查询，相关号与命令编号分别使用。
     * @author owlzhangfq@gmail.com
     */
    private record Query(UUID operationId, String commandDigest) { }
}
