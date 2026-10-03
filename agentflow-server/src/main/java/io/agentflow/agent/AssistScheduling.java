package io.agentflow.agent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 模型执行使用独立单线程调度池，网络等待不阻塞审批期限或集成投递。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "agentflow.assist.enabled", havingValue = "true")
public class AssistScheduling {
    /** 有界并发；多进程仍由数据库租约串行化单次运行。 */
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskScheduler assistTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("assist-worker-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false); return scheduler;
    }

    /** 测试和运维可禁用自动轮询，已保存任务保持不变。 */
    @Bean
    @ConditionalOnProperty(name = "agentflow.assist.worker-enabled", havingValue = "true", matchIfMissing = true)
    public Poller assistPoller(AssistWorker worker, DraftAssistWorker drafts, PrecheckExplanationWorker explanations) {
        return new Poller(worker, drafts, explanations);
    }

    /**
     * 调度与工作用例分离，测试可直接执行持久队列而无需等待定时器。
     * @author owlzhangfq@gmail.com
     */
    public static final class Poller {
        private final AssistWorker worker;
        private final DraftAssistWorker drafts;
        private final PrecheckExplanationWorker explanations;
        private Poller(AssistWorker worker, DraftAssistWorker drafts, PrecheckExplanationWorker explanations) {
            this.worker = worker; this.drafts = drafts; this.explanations = explanations;
        }
        /** 固定延时避免同一调度线程重入。 */
        @Scheduled(fixedDelayString = "${agentflow.assist.poll-delay-ms:1000}", scheduler = "assistTaskScheduler")
        public void poll() { worker.poll(); drafts.poll(); explanations.poll(); }
    }
}
