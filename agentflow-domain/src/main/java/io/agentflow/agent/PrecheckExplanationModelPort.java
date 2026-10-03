package io.agentflow.agent;

/**
 * 预检解释模型端口，使用已冻结来源生成待人工核对的文字。
 * @author owlzhangfq@gmail.com
 */
public interface PrecheckExplanationModelPort {
    /** 事务外调用，不读取外部账本、不执行模型动作。 */
    PrecheckExplanationSuggestion generate(PrecheckExplanationRun.Context context);
}
