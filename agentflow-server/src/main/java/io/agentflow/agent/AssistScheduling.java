package io.agentflow.agent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 每类助手独立轮询，慢模型不阻塞其他助手、审批期限或集成投递。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "agentflow.assist.enabled", havingValue = "true")
public class AssistScheduling {
    private static final int WORKER_TYPES = 5;
    /** 有界并发；多进程仍由数据库租约串行化单次运行。 */
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskScheduler assistTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(WORKER_TYPES); scheduler.setThreadNamePrefix("assist-worker-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false); return scheduler;
    }

    /** 测试和运维可禁用自动轮询，已保存任务保持不变。 */
    @Bean
    @ConditionalOnProperty(name = "agentflow.assist.worker-enabled", havingValue = "true", matchIfMissing = true)
    public Poller assistPoller(AssistWorker worker, DraftAssistWorker drafts, PrecheckExplanationWorker explanations,
                              ExpenseDraftAssistWorker expenseDrafts, ExpenseRiskWorker expenseRisks) {
        return new Poller(worker, drafts, explanations, expenseDrafts, expenseRisks);
    }

    /**
     * 调度与工作用例分离，测试可直接执行持久队列而无需等待定时器。
     * @author owlzhangfq@gmail.com
     */
    public static final class Poller {
        private final AssistWorker worker;
        private final DraftAssistWorker drafts;
        private final PrecheckExplanationWorker explanations;
        private final ExpenseDraftAssistWorker expenseDrafts;
        private final ExpenseRiskWorker expenseRisks;
        private Poller(AssistWorker worker, DraftAssistWorker drafts, PrecheckExplanationWorker explanations,
                       ExpenseDraftAssistWorker expenseDrafts, ExpenseRiskWorker expenseRisks) {
            this.worker = worker; this.drafts = drafts; this.explanations = explanations; this.expenseDrafts = expenseDrafts; this.expenseRisks = expenseRisks;
        }
        /** 固定延时避免同类重入，持久租约继续协调不同实例。 */
        @Scheduled(fixedDelayString = "${agentflow.assist.poll-delay-ms:1000}", scheduler = "assistTaskScheduler")
        public void pollSummaries() { worker.poll(); }

        /** 普通草稿独立等待模型，不能推迟费用补正。 */
        @Scheduled(fixedDelayString = "${agentflow.assist.poll-delay-ms:1000}", scheduler = "assistTaskScheduler")
        public void pollDrafts() { drafts.poll(); }

        /** 预检解释独立领取原持久任务。 */
        @Scheduled(fixedDelayString = "${agentflow.assist.poll-delay-ms:1000}", scheduler = "assistTaskScheduler")
        public void pollExplanations() { explanations.poll(); }

        /** 费用填报独立领取原持久任务。 */
        @Scheduled(fixedDelayString = "${agentflow.assist.poll-delay-ms:1000}", scheduler = "assistTaskScheduler")
        public void pollExpenseDrafts() { expenseDrafts.poll(); }

        /** 审批风险不再等待前四类助手完成整批模型请求。 */
        @Scheduled(fixedDelayString = "${agentflow.assist.poll-delay-ms:1000}", scheduler = "assistTaskScheduler")
        public void pollExpenseRisks() { expenseRisks.poll(); }
    }
}
