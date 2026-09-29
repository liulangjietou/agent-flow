package io.agentflow.finance;

/**
 * 已消费预算的独立冲正写入和原操作查询，不能通过普通释放冻结回退实际占用。
 * @author owlzhangfq@gmail.com
 */
public interface BudgetConsumptionReversalPort {
    /** 固定原消费、分摊、授权和幂等号发送一次，传输失败不自动重试。 */
    FinanceResult<BudgetConsumptionReversalObservation> execute(String targetDigest, BudgetConsumptionReversalCommand command);
    /** 未知结果和失效授权继续查询原操作，查无才能决定是否重发同一命令。 */
    FinanceResult<BudgetConsumptionReversalObservation> query(String targetDigest, BudgetConsumptionReversalCommand command);
}
