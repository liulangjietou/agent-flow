package io.agentflow.agent;

/**
 * 费用填报建议的只读模型端口，无费用、补贴或审批写入能力。
 * @author owlzhangfq@gmail.com
 */
public interface ExpenseDraftModelPort {
    /** 在事务外发送已经冻结的行程和目录，返回等待逐项人工确认的建议。 */
    ExpenseDraftSuggestion generate(ExpenseDraftAssistRun.Context context);
}
