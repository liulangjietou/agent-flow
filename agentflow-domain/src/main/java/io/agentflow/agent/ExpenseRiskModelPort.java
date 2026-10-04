package io.agentflow.agent;

/**
 * 费用风险解释端口，仅解释选定的本地观察，不访问账本或执行模型动作。
 * @author owlzhangfq@gmail.com
 */
public interface ExpenseRiskModelPort {
    /** 网络调用必须位于数据库事务之外；本地上下文身份不得发给模型。 */
    ExpenseRiskSuggestion generate(ExpenseRiskRun.Context context);
}
