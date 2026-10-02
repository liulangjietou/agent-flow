package io.agentflow.agent;

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
    private final JdbcDraftAssistRunRepository runs;
    private final DraftAssistService service;
    private final DraftAssistModelPort model;
    /** 独立事务负责领取和结算，模型端口仅处理冻结输入。 */
    public DraftAssistWorker(JdbcDraftAssistRunRepository runs, DraftAssistService service, DraftAssistModelPort model) {
        this.runs = runs; this.service = service; this.model = model;
    }
    /** 每批最多十项；崩溃租约由领取服务结算超时。 */
    public void poll() {
        for (var candidate : runs.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                var context = service.claim(candidate.tenantId(), candidate.id(), Instant.now());
                if (context == null) continue;
                DraftSuggestion suggestion = null; AssistRun.Failure failure = null;
                try { suggestion = model.generate(context); }
                catch (AssistModelPort.ModelFailure unavailable) { failure = unavailable.failure(); }
                service.finish(context.tenantId(), context.id(), suggestion, failure, Instant.now());
            } catch (RuntimeException failure) {
                LOG.error("Draft assist execution failed, errorCode={}, runId={}", "WORKER_FAILURE", candidate.id());
            }
        }
    }
}
