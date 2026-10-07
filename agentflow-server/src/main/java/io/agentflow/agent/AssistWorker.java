package io.agentflow.agent;

import io.agentflow.observability.DiagnosticContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.time.Instant;

/**
 * 领取与结算通过独立事务代理，模型执行期间不持有数据库连接或锁。
 * @author owlzhangfq@gmail.com
 */
@Service
public class AssistWorker {
    private static final Logger LOG = LoggerFactory.getLogger(AssistWorker.class);
    private static final String TRACE_SOURCE = "assist-run";
    private final JdbcAssistJobRepository jobs;
    private final AssistExecutionService execution;
    private final AssistModelPort model;

    /** 原始输入只交给模型端口，不写入运行日志。 */
    public AssistWorker(JdbcAssistJobRepository jobs, AssistExecutionService execution, AssistModelPort model) {
        this.jobs = jobs; this.execution = execution; this.model = model;
    }

    /** 每批最多十项；进程异常保留租约，后续记录超时而不盲目重发。 */
    public void poll() {
        for (var candidate : jobs.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try (var trace = DiagnosticContext.restored(candidate.traceId(), candidate.tenantId(),
                    TRACE_SOURCE, candidate.runId().toString())
                    .withBusiness(candidate.businessNo(), candidate.processInstanceId(), candidate.taskId()).open()) {
                try {
                    var job = execution.claim(candidate.tenantId(), candidate.runId(), Instant.now());
                    if (job == null) continue;
                    LOG.info("Agent execution claimed, errorCode={}, runId={}", "NONE", candidate.runId());
                    AssistSuggestion suggestion = null; AssistRun.Failure failure = null;
                    try { suggestion = model.generate(AssistConfiguration.PROMPT_VERSION, job.sources()); }
                    catch (AssistModelPort.ModelFailure modelFailure) { failure = modelFailure.failure(); }
                    execution.finish(job, suggestion, failure, Instant.now());
                } catch (RuntimeException failure) {
                    // 原始异常可能含远端地址或业务输入，日志只记录稳定分类与运行编号。
                    LOG.error("Assist execution failed, errorCode={}, runId={}", "WORKER_FAILURE", candidate.runId());
                }
            }
        }
    }
}
