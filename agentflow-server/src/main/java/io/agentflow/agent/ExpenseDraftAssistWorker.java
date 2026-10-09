package io.agentflow.agent;

import io.agentflow.observability.DiagnosticContext;

import io.agentflow.common.DomainException;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 领取、目录复核、模型请求和结果保存分离；任何进程中断都不自动重发原模型请求。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseDraftAssistWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ExpenseDraftAssistWorker.class);
    private static final String TRACE_SOURCE = "expense-draft";
    private final JdbcExpenseDraftAssistRepository runs;
    private final ExpenseDraftAssistService service;
    private final ExpenseDraftAssistPreparation preparation;
    private final ExpenseDraftModelPort model;
    private final AgentExecutionTelemetry telemetry;

    /** 网络调用发生在两次持久状态事务之间，目录和模型目的地均来自部署配置。 */
    public ExpenseDraftAssistWorker(JdbcExpenseDraftAssistRepository runs, ExpenseDraftAssistService service,
            ExpenseDraftAssistPreparation preparation, ExpenseDraftModelPort model, AgentExecutionTelemetry telemetry) {
        this.runs = runs; this.service = service; this.preparation = preparation; this.model = model; this.telemetry = telemetry;
    }

    /** 原队列最多扫描十项；失效或已领取项不会转发给模型。 */
    public void poll() {
        for (var candidate : runs.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try (var trace = DiagnosticContext.restored(candidate.traceId(), candidate.tenantId(),
                    TRACE_SOURCE, candidate.id().toString())
                    .withBusiness(candidate.businessNo(), null, null).open()) {
                try {
                    Instant claimedAt = Instant.now();
                    var context = service.claim(candidate.tenantId(), candidate.id(), claimedAt);
                    if (context == null || !service.sendable(context, Instant.now())) continue;
                    LOG.info("Agent execution claimed, errorCode={}, runId={}", "NONE", candidate.id());
                    try { preparation.refresh(context); }
                    catch (DomainException unavailable) {
                        service.finish(context.tenantId(), context.id(), null, AssistRun.Failure.INPUT_UNAVAILABLE, Instant.now()); continue;
                    }
                    if (!service.sendable(context, Instant.now())) continue;
                    ExpenseDraftSuggestion suggestion = null; AssistRun.Failure failure = null;
                    try { suggestion = telemetry.execute(candidate.tenantId(), context.requestedBy(), AgentExecutionUsage.Kind.EXPENSE_DRAFT, context.id(), claimedAt, () -> model.generate(context)); }
                    catch (AssistModelPort.ModelFailure unavailable) { failure = unavailable.failure(); }
                    service.finish(context.tenantId(), context.id(), suggestion, failure, Instant.now());
                } catch (RuntimeException failure) {
                    LOG.error("Expense draft assistance failed, errorCode={}, runId={}, exceptionType={}",
                            "WORKER_FAILURE", candidate.id(), failure.getClass().getSimpleName());
                }
            }
        }
    }
}
