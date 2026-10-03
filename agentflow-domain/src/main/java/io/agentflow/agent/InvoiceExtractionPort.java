package io.agentflow.agent;

/**
 * 票据抽取只产生有来源的候选值；本地解析与模型执行都遵守创建时固定的方式。
 * @author owlzhangfq@gmail.com
 */
public interface InvoiceExtractionPort {
    /** 在事务外重新核对本人原件和执行方式；只有明确冻结模型目的地才允许外发。 */
    InvoiceExtractionSuggestion generate(InvoiceExtractionRun.Context context);
}
