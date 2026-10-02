package io.agentflow.agent;

import io.agentflow.common.DomainException;
import io.agentflow.expense.JdbcInvoiceOriginalRepository;
import org.springframework.stereotype.Component;

/**
 * 核对本人原件后选择确定的内容适配结果，不因本地失败而隐式发送到模型。
 * @author owlzhangfq@gmail.com
 */
@Component
public class InvoiceExtractionEngine implements InvoiceExtractionPort {
    private final JdbcInvoiceOriginalRepository originals;
    private final InvoiceExtractionSources sources;
    private final OpenAiCompatibleInvoiceExtractionModel model;

    /** 复用原件仓储和模型传输，跨资源读取由此基础设施编排统一完成。 */
    public InvoiceExtractionEngine(JdbcInvoiceOriginalRepository originals, InvoiceExtractionSources sources,
                                   OpenAiCompatibleInvoiceExtractionModel model) {
        this.originals = originals; this.sources = sources; this.model = model;
    }

    /** 来源或方式变化直接失败，本地识别结果不会触发模型传输。 */
    @Override
    public InvoiceExtractionSuggestion generate(InvoiceExtractionRun.Context context) {
        InvoiceExtractionSources.Prepared source;
        try {
            var original = originals.find(context.tenantId(), context.input().invoiceId())
                    .filter(value -> context.requestedBy().equals(value.ownerId())).orElseThrow(InvoiceExtractionEngine::unavailable);
            source = sources.prepare(original);
            if (!context.input().equals(source.input()) || context.method() != source.method()) throw unavailable();
        } catch (DomainException rejected) { throw unavailable(); }
        if (source.method() == InvoiceExtractionSuggestion.Method.STRUCTURED_XML) return source.structured();
        return model.generate(context, source);
    }

    private static AssistModelPort.ModelFailure unavailable() {
        return new AssistModelPort.ModelFailure(AssistRun.Failure.INPUT_UNAVAILABLE);
    }
}
