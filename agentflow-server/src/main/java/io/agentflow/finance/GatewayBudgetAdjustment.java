package io.agentflow.finance;

import io.agentflow.budget.BudgetAdjustmentCommand;
import io.agentflow.budget.BudgetAdjustmentObservation;
import io.agentflow.budget.BudgetAdjustmentPort;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 预算额度调整使用独立原子命令，固定原批准的目的地，不复用报销占用接口。
 * @author owlzhangfq@gmail.com
 */
@Component
public class GatewayBudgetAdjustment implements BudgetAdjustmentPort {
    private final FinanceGatewayClient client;

    /** 传输复用严格网关，完整两端事实由预算领域核对。 */
    public GatewayBudgetAdjustment(FinanceGatewayClient client) { this.client = client; }

    /** 原系统按持久编号与完整摘要去重，过期授权不能触发新的写入。 */
    @Override public FinanceResult<BudgetAdjustmentObservation> execute(BudgetAdjustmentCommand command) {
        command.requireSendAt(Instant.now());
        return client.executeBudgetAdjustment(command.tenantId(), command.targetDigest(), command.id(), new Execute(command, command.digest()),
                BudgetAdjustmentObservation.class, value -> value.matches(command, false, Instant.now()));
    }

    /** 只读恢复继续查询原编号，原授权过期不阻断已经发送的指令核对。 */
    @Override public FinanceResult<BudgetAdjustmentObservation> query(BudgetAdjustmentCommand command) {
        return client.queryBudgetAdjustment(command.tenantId(), command.targetDigest(), new Query(command.id(), command.digest()),
                BudgetAdjustmentObservation.class, value -> value.matches(command, true, Instant.now()));
    }

    /**
     * 原批准、财务复核及精确额度变化共同构成固定指令。
     * @author owlzhangfq@gmail.com
     */
    private record Execute(BudgetAdjustmentCommand command, String commandDigest) { }

    /**
     * 业务编号与每次查询的传输关联号分离。
     * @author owlzhangfq@gmail.com
     */
    private record Query(UUID operationId, String commandDigest) { }
}
