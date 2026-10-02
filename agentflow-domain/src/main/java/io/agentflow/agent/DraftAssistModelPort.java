package io.agentflow.agent;

/**
 * 生成草稿字段建议，端口无权修改申请或审批状态。
 * @author owlzhangfq@gmail.com
 */
public interface DraftAssistModelPort {
    /** 事务外发送已冻结来源与字段契约，返回等待人工确认的值。 */
    DraftSuggestion generate(DraftAssistRun.Context context);
}
