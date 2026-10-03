package io.agentflow.budget;

import io.agentflow.finance.FinanceResult;

/**
 * 独立预算额度执行端口，追加、调减和调拨均使用一个固定编号，不能逐端分别写入。
 * @author owlzhangfq@gmail.com
 */
public interface BudgetAdjustmentPort {
    /** 原系统须原子核对全部原版本、期间和余额，并按编号与完整摘要幂等执行。 */
    FinanceResult<BudgetAdjustmentObservation> execute(BudgetAdjustmentCommand command);
    /** 查询原编号和摘要，授权过期也不改变原目标或生成替代命令。 */
    FinanceResult<BudgetAdjustmentObservation> query(BudgetAdjustmentCommand command);
}
