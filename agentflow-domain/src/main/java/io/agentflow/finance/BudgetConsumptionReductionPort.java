package io.agentflow.finance;

/**
 * 已消费预算的独立差额写入与原号查询，真实完成前不释放本地报销资源。
 * @author owlzhangfq@gmail.com
 */
public interface BudgetConsumptionReductionPort {
    /** 固定原目标、原编号和完整差额；外部原子核对当前剩余额及版本。 */
    FinanceResult<BudgetConsumptionReductionObservation> execute(String targetDigest, BudgetConsumptionReductionCommand command);
    /** 未知结果只查询原指令，查无也不能自动产生另一个调整编号。 */
    FinanceResult<BudgetConsumptionReductionObservation> query(String targetDigest, BudgetConsumptionReductionCommand command);
}
