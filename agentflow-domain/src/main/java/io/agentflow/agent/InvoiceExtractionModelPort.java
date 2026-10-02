package io.agentflow.agent;

/**
 * 票据抽取模型只产生有来源的候选值，读取原件和协议转换由基础设施适配器完成。
 * @author owlzhangfq@gmail.com
 */
public interface InvoiceExtractionModelPort {
    /** 在事务外重新核对完整原件与目的地，失败只返回稳定分类。 */
    InvoiceExtractionSuggestion generate(InvoiceExtractionRun.Context context);
}
