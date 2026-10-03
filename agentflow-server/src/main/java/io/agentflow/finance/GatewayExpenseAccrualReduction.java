package io.agentflow.finance;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 报销挂账差额以独立 ERP 路径发送，原科目、前后余额和本次分录全部进入严格核对。
 * @author owlzhangfq@gmail.com
 */
@Component
public class GatewayExpenseAccrualReduction implements ExpenseAccrualReductionPort {
    private final FinanceGatewayClient client;

    /** 复用既有固定租户目的地、有界响应及事务外 HTTP 传输。 */
    public GatewayExpenseAccrualReduction(FinanceGatewayClient client) { this.client = client; }

    /** ERP 原子核对原挂账与累计版本，差额只来自固定命令，不另取科目映射。 */
    @Override public FinanceResult<ExpenseAccrualReductionObservation> post(String targetDigest, ExpenseAccrualReductionCommand command) {
        command.requireSendAt(Instant.now());
        return client.postExpenseAccrualReduction(command.source().command().tenantId(), targetDigest, command.id(), new Post(command, command.lines(), command.digest()),
                ExpenseAccrualReductionObservation.class, value -> value.matches(command, false, Instant.now()));
    }

    /** 过期授权仍可查原结果，查询不携带写入幂等头。 */
    @Override public FinanceResult<ExpenseAccrualReductionObservation> query(String targetDigest, ExpenseAccrualReductionCommand command) {
        return client.queryExpenseAccrualReduction(command.source().command().tenantId(), targetDigest, new Query(command.id(), command.digest()),
                ExpenseAccrualReductionObservation.class, value -> value.matches(command, true, Instant.now()));
    }

    /**
     * 服务端派生的分录与完整前后位置同时发送，不能由 ERP 静默改写日期或金额。
     * @author owlzhangfq@gmail.com
     */
    private record Post(ExpenseAccrualReductionCommand command, List<VoucherReversalCommand.Line> lines, String commandDigest) { }

    /**
     * 只读查询固定原指令，不重新授权也不创建新的调整。
     * @author owlzhangfq@gmail.com
     */
    private record Query(UUID operationId, String commandDigest) { }
}
