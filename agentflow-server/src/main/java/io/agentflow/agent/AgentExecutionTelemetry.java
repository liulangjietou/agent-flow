package io.agentflow.agent;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 执行器显式绑定已领取运行，传输层只填写用量；作用域结束清理线程状态，不能提供业务授权。
 * @author owlzhangfq@gmail.com
 */
@Component
public class AgentExecutionTelemetry {
    private static final Logger LOG = LoggerFactory.getLogger(AgentExecutionTelemetry.class);
    private static final ThreadLocal<Observation> CURRENT = new ThreadLocal<>();
    private final AgentUsageRepository repository;

    /** 持久观测独立于模型网络与原运行结算事务。 */
    public AgentExecutionTelemetry(AgentUsageRepository repository) { this.repository = repository; }

    /** 每次领取最多执行一次；记录失败不能诱发再次调用模型或掩盖原业务失败。 */
    public <T> T execute(String tenant, String user, AgentExecutionUsage.Kind kind, UUID runId, Instant claimedAt, Supplier<T> operation) {
        if (TransactionSynchronizationManager.isActualTransactionActive() || CURRENT.get() != null) {
            throw new IllegalStateException("Agent execution must have one transaction-free observation scope");
        }
        var initial = repository.begin(tenant, user, kind, runId, claimedAt.truncatedTo(ChronoUnit.MILLIS));
        var observed = new Observation(); CURRENT.set(observed);
        String outcome = "EXECUTION_FAILED";
        try { T result = operation.get(); outcome = "SUCCEEDED"; return result; }
        catch (AssistModelPort.ModelFailure failure) { outcome = failure.failure().name(); throw failure; }
        catch (RuntimeException failure) { outcome = "EXECUTION_FAILED"; throw failure; }
        finally {
            CURRENT.remove();
            Instant completed = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            var usage = observed.usage == null ? new OpenAiTextClient.Usage(OpenAiTextClient.UsageStatus.NOT_REPORTED, null, null, null) : observed.usage;
            var value = new AgentExecutionUsage(runId, kind, initial.subjectId(), initial.queuedAt(), initial.startedAt(), completed,
                    initial.queueMillis(), Math.max(0, Duration.between(initial.startedAt(), completed).toMillis()), outcome,
                    observed.providerId, observed.modelVersion,
                    observed.promptVersion, usage.status().name(), usage.inputTokens(), usage.outputTokens(), usage.totalTokens());
            try { repository.finish(tenant, value); }
            catch (RuntimeException failure) {
                LOG.error("Agent usage recording failed, errorCode={}, runId={}, exceptionType={}", "AGENT_USAGE_RECORD_FAILED", runId, failure.getClass().getSimpleName());
            }
        }
    }

    /** 仅接受当前同步执行的传输回执，不读取 MDC 作为身份，也不记录输入或响应正文。 */
    static void received(String promptVersion, OpenAiTextClient.Reply reply) {
        received(promptVersion, reply.providerId(), reply.modelVersion(), reply.usage());
    }

    /** 供应商已返回用量时，即使正文稍后校验失败也保留这次观测。 */
    static void received(String promptVersion, String providerId, String modelVersion, OpenAiTextClient.Usage usage) {
        var current = CURRENT.get();
        if (current != null) {
            current.promptVersion = promptVersion;
            current.providerId = providerId;
            current.modelVersion = modelVersion;
            current.usage = usage;
        }
    }

    /**
     * 当前调用只保留一次响应；最终业务结果由外层适配器的严格校验决定。
     * @author owlzhangfq@gmail.com
     */
    private static final class Observation {
        private String promptVersion;
        private String providerId;
        private String modelVersion;
        private OpenAiTextClient.Usage usage;
    }
}
