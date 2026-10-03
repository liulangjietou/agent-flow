package io.agentflow.finance;

/**
 * 预算变更与原操作查询；目的地固定为排队时选定的租户系统，外部调用不参加本地数据库事务。
 * @author owlzhangfq@gmail.com
 */
public interface BudgetSystemPort {
    /** 一次发送固定命令；未知结果不能用新幂等号重发，适配器也不自动重试。 */
    FinanceResult<BudgetObservation> execute(String targetDigest, BudgetCommand command);

    /** 查询原幂等号，超时或进程崩溃后先恢复事实，再决定是否重发完全相同的命令。 */
    FinanceResult<BudgetObservation> query(String targetDigest, BudgetCommand command);
}
