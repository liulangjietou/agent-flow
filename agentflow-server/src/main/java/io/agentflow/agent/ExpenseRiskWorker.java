package io.agentflow.agent;

import io.agentflow.observability.DiagnosticContext;

import io.agentflow.common.DomainException;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 模型只在领取和发送检查事务结束后调用，权限检查失败用新事务结算，执行中断不自动重发。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseRiskWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ExpenseRiskWorker.class);
    private static final String TRACE_SOURCE = "expense-risk";
    private final JdbcExpenseRiskRepository runs;
    private final ExpenseRiskService service;
    private final ExpenseRiskModelPort model;

    /** 每批候选仅含定位，认证及来源由服务在即将外发时重新核对。 */
    public ExpenseRiskWorker(JdbcExpenseRiskRepository runs, ExpenseRiskService service, ExpenseRiskModelPort model) {
        this.runs = runs; this.service = service; this.model = model;
    }

    /** 禁止调用者把模型 HTTP 包进事务；已到期租约只记失败，不再次请求提供者。 */
    @Transactional(propagation = Propagation.NEVER)
    public void poll() {
        for (var candidate : runs.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try (var trace = DiagnosticContext.restored(candidate.traceId(), candidate.tenantId(),
                    TRACE_SOURCE, candidate.id().toString()).open()) {
                try {
                    var context = service.claim(candidate.tenantId(), candidate.id(), Instant.now());
                    if (context == null) continue;
                    try { if (!service.sendable(context, Instant.now())) continue; }
                    catch (DomainException unavailable) {
                        // 发送检查事务已回滚；独立失败事务不能被其 rollback-only 标记一起撤销。
                        service.finish(context.tenantId(), context.id(), null, AssistRun.Failure.INPUT_UNAVAILABLE, Instant.now());
                        continue;
                    }
                    ExpenseRiskSuggestion suggestion = null; AssistRun.Failure failure = null;
                    try { suggestion = model.generate(context); }
                    catch (AssistModelPort.ModelFailure unavailable) { failure = unavailable.failure(); }
                    service.finish(context.tenantId(), context.id(), suggestion, failure, Instant.now());
                } catch (RuntimeException failure) {
                    LOG.error("Expense risk execution failed, errorCode={}, runId={}, exceptionType={}",
                            "WORKER_FAILURE", candidate.id(), failure.getClass().getSimpleName());
                }
            }
        }
    }
}
