package io.agentflow.agent;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 模型调用在领取和保存事务之间执行，进程中断后由原租约结算，不自动重发。
 * @author owlzhangfq@gmail.com
 */
@Service
public class PrecheckExplanationWorker {
    private static final Logger LOG = LoggerFactory.getLogger(PrecheckExplanationWorker.class);
    private final JdbcPrecheckExplanationRepository runs;
    private final PrecheckExplanationService service;
    private final PrecheckExplanationModelPort model;
    /** 持久队列统一协调多进程领取，模型只接收冻结的授权来源。 */
    public PrecheckExplanationWorker(JdbcPrecheckExplanationRepository runs, PrecheckExplanationService service, PrecheckExplanationModelPort model) {
        this.runs = runs; this.service = service; this.model = model;
    }
    /** 每批最多十项，已失效或已被其他进程领取的记录不会外发。 */
    public void poll() {
        for (var candidate : runs.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                var context = service.claim(candidate.tenantId(), candidate.id(), Instant.now());
                if (context == null || !service.sendable(context, Instant.now())) continue;
                PrecheckExplanationSuggestion suggestion = null; AssistRun.Failure failure = null;
                try { suggestion = model.generate(context); }
                catch (AssistModelPort.ModelFailure unavailable) { failure = unavailable.failure(); }
                service.finish(context.tenantId(), context.id(), suggestion, failure, Instant.now());
            } catch (RuntimeException failure) {
                LOG.error("Precheck explanation execution failed, errorCode={}, runId={}, exceptionType={}",
                        "WORKER_FAILURE", candidate.id(), failure.getClass().getSimpleName());
            }
        }
    }
}
