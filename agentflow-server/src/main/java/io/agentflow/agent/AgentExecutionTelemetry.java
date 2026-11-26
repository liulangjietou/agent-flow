package io.agentflow.agent;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final MeterRegistry meters;

    /** 兼容单测直接构造，不强制要求监控注册表。 */
    public AgentExecutionTelemetry(AgentUsageRepository repository) { this(repository, (MeterRegistry) null); }

    /** 持久观测与只含封闭枚举标签的 Micrometer 指标独立于模型网络和业务事务。 */
    @Autowired
    public AgentExecutionTelemetry(AgentUsageRepository repository, ObjectProvider<MeterRegistry> metersProvider) {
        this(repository, metersProvider == null ? null : metersProvider.getIfAvailable());
    }

    AgentExecutionTelemetry(AgentUsageRepository repository, MeterRegistry meters) {
        this.repository = repository;
        this.meters = meters;
    }

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
            recordMetrics(kind, outcome, value.executionMillis(), usage);
            try { repository.finish(tenant, value); }
            catch (RuntimeException failure) {
                LOG.error("Agent usage recording failed, errorCode={}, runId={}, exceptionType={}", "AGENT_USAGE_RECORD_FAILED", runId, failure.getClass().getSimpleName());
            }
        }
    }

    /** 仅使用封闭枚举值作为指标标签，避免高基数及敏感单据、租户或用户标识泄露。 */
    private void recordMetrics(AgentExecutionUsage.Kind kind, String outcome, long durationMillis, OpenAiTextClient.Usage usage) {
        if (meters == null) return;
        String kindTag = kind.name();
        Counter.builder("agentflow.agent.executions").tag("kind", kindTag).tag("outcome", outcome).register(meters).increment();
        Timer.builder("agentflow.agent.execution.duration").tag("kind", kindTag).tag("outcome", outcome).register(meters)
                .record(durationMillis, TimeUnit.MILLISECONDS);
        if (usage.status() == OpenAiTextClient.UsageStatus.REPORTED) {
            if (usage.inputTokens() != null && usage.inputTokens() > 0) {
                Counter.builder("agentflow.agent.tokens").tag("kind", kindTag).tag("direction", "input").register(meters).increment(usage.inputTokens());
            }
            if (usage.outputTokens() != null && usage.outputTokens() > 0) {
                Counter.builder("agentflow.agent.tokens").tag("kind", kindTag).tag("direction", "output").register(meters).increment(usage.outputTokens());
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
            current.usage = current.usage == null ? usage : combine(current.usage, usage);
        }
    }

    /**
     * 同一领取内的多次调用（如协议修复重试）合计用量：全部已报告才相加；任一次格式错误记为 INVALID，
     * 任一次未报告记为 NOT_REPORTED，不把缺失部分当作零。
     */
    static OpenAiTextClient.Usage combine(OpenAiTextClient.Usage first, OpenAiTextClient.Usage second) {
        if (first.status() == OpenAiTextClient.UsageStatus.INVALID || second.status() == OpenAiTextClient.UsageStatus.INVALID) {
            return new OpenAiTextClient.Usage(OpenAiTextClient.UsageStatus.INVALID, null, null, null);
        }
        if (first.status() == OpenAiTextClient.UsageStatus.NOT_REPORTED || second.status() == OpenAiTextClient.UsageStatus.NOT_REPORTED) {
            return new OpenAiTextClient.Usage(OpenAiTextClient.UsageStatus.NOT_REPORTED, null, null, null);
        }
        try {
            return new OpenAiTextClient.Usage(OpenAiTextClient.UsageStatus.REPORTED, Math.addExact(first.inputTokens(), second.inputTokens()),
                    Math.addExact(first.outputTokens(), second.outputTokens()), Math.addExact(first.totalTokens(), second.totalTokens()));
        } catch (ArithmeticException overflow) {
            return new OpenAiTextClient.Usage(OpenAiTextClient.UsageStatus.INVALID, null, null, null);
        }
    }

    /**
     * 当前领取内的响应观测；最终业务结果由外层适配器的严格校验决定。
     * @author owlzhangfq@gmail.com
     */
    private static final class Observation {
        private String promptVersion;
        private String providerId;
        private String modelVersion;
        private OpenAiTextClient.Usage usage;
    }
}
