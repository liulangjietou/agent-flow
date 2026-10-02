package io.agentflow.agent;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 事务外读取原件和执行抽取，未知异常保留原租约，重启后结算超时而不自动重发。
 * @author owlzhangfq@gmail.com
 */
@Service
public class InvoiceExtractionWorker {
    private static final Logger LOG = LoggerFactory.getLogger(InvoiceExtractionWorker.class);
    private final JdbcInvoiceExtractionRunRepository runs;
    private final InvoiceExtractionService service;
    private final InvoiceExtractionPort extraction;
    /** 领取和结算依赖事务代理，模型端口不能控制数据库状态。 */
    public InvoiceExtractionWorker(JdbcInvoiceExtractionRunRepository runs, InvoiceExtractionService service, InvoiceExtractionPort extraction) {
        this.runs = runs; this.service = service; this.extraction = extraction;
    }
    /** 每批最多十项，线程中断后不再领取新的原件。 */
    public void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Invoice extraction worker must run outside a transaction");
        for (var candidate : runs.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                var context = service.claim(candidate.tenantId(), candidate.id(), Instant.now());
                if (context == null) continue;
                InvoiceExtractionSuggestion suggestion = null; InvoiceExtractionRun.Failure failure = null;
                try { suggestion = extraction.generate(context); }
                catch (AssistModelPort.ModelFailure rejected) {
                    failure = switch (rejected.failure()) {
                        case MODEL_UNAVAILABLE -> InvoiceExtractionRun.Failure.MODEL_UNAVAILABLE;
                        case MODEL_TIMEOUT -> InvoiceExtractionRun.Failure.EXECUTION_TIMEOUT;
                        case INVALID_MODEL_OUTPUT -> InvoiceExtractionRun.Failure.INVALID_RESULT;
                        case INPUT_UNAVAILABLE -> InvoiceExtractionRun.Failure.INPUT_UNAVAILABLE;
                    };
                }
                service.finish(context.tenantId(), context.id(), suggestion, failure, Instant.now());
            } catch (RuntimeException failed) {
                // 异常可能包含票面或外部正文，日志仅记录稳定分类和任务标识。
                LOG.error("Invoice extraction execution failed, errorCode={}, runId={}", "WORKER_FAILURE", candidate.id());
            }
        }
    }
}
