package io.agentflow.agent;

import io.agentflow.observability.DiagnosticContext;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 草稿生成在事务外执行，失败不自动重发，也不会调用申请提交入口。
 * @author owlzhangfq@gmail.com
 */
@Service
public class DraftAssistWorker {
    private static final Logger LOG = LoggerFactory.getLogger(DraftAssistWorker.class);
    private static final String TRACE_SOURCE = "draft-assist";
    private final JdbcDraftAssistRunRepository runs;
    private final DraftAssistService service;
    private final DraftAssistModelPort model;
    private final AgentExecutionTelemetry telemetry;
    /** 独立事务负责领取和结算，模型端口仅处理冻结输入。 */
    public DraftAssistWorker(JdbcDraftAssistRunRepository runs, DraftAssistService service, DraftAssistModelPort model, AgentExecutionTelemetry telemetry) {
        this.runs = runs; this.service = service; this.model = model; this.telemetry = telemetry;
    }
    /** 每批最多十项；崩溃租约由领取服务结算超时。 */
    public void poll() {
        for (var candidate : runs.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try (var trace = DiagnosticContext.restored(candidate.traceId(), candidate.tenantId(),
                    TRACE_SOURCE, candidate.id().toString())
                    .withBusiness(candidate.businessNo(), null, null).open()) {
                try {
                    Instant claimedAt = Instant.now();
                    var context = service.claim(candidate.tenantId(), candidate.id(), claimedAt);
                    if (context == null) continue;
                    LOG.info("Agent execution claimed, errorCode={}, runId={}", "NONE", candidate.id());
                    DraftSuggestion suggestion = null; AssistRun.Failure failure = null;
                    try { suggestion = telemetry.execute(candidate.tenantId(), context.requestedBy(), AgentExecutionUsage.Kind.DRAFT, context.id(), claimedAt, () -> model.generate(context)); }
                    catch (AssistModelPort.ModelFailure unavailable) { failure = unavailable.failure(); }
                    service.finish(context.tenantId(), context.id(), suggestion, failure, Instant.now());
                } catch (RuntimeException failure) {
                    LOG.error("Draft assist execution failed, errorCode={}, runId={}", "WORKER_FAILURE", candidate.id());
                }
            }
        }
    }
}
