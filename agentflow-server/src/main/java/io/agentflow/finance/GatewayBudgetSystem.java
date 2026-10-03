package io.agentflow.finance;

import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.UUID;

/**
 * 预算系统适配器核对操作事实；传输成功只能证明取得结果，APPLIED 才能证明预算已经变更。
 * @author owlzhangfq@gmail.com
 */
@Component
public class GatewayBudgetSystem implements BudgetSystemPort {
    private final FinanceGatewayClient client;
    /** 与其他财务端口共用有界传输，写入路径要求持久化的幂等号。 */
    public GatewayBudgetSystem(FinanceGatewayClient client) { this.client = client; }

    /** 固定编号、摘要及完整分摊一起发送，适配器不主动重试任何写操作。 */
    @Override public FinanceResult<BudgetObservation> execute(String targetDigest, BudgetCommand command) {
        return client.executeBudget(command.tenantId(), targetDigest, command.id(), new Execute(command, command.digest()),
                BudgetObservation.class, value -> value.matches(command, false, Instant.now()));
    }

    /** 查询只携带原编号及摘要，不能隐式创建、释放或再次冻结预算。 */
    @Override public FinanceResult<BudgetObservation> query(String targetDigest, BudgetCommand command) {
        return client.queryBudget(command.tenantId(), targetDigest, new Query(command.id(), command.digest()),
                BudgetObservation.class, value -> value.matches(command, true, Instant.now()));
    }

    /**
     * 服务端必须先校验摘要，并在相同编号不同摘要时拒绝执行，不能返回另一个命令的结果。
     * @author owlzhangfq@gmail.com
     */
    private record Execute(BudgetCommand command, String commandDigest) { }

    /**
     * 每次查询有新的传输请求号，但查询对象始终为原预算操作。
     * @author owlzhangfq@gmail.com
     */
    private record Query(UUID operationId, String commandDigest) { }
}
