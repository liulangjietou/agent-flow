package io.agentflow.budget;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 预算调整申请查询使用独立线程，不占用审批期限、通知或验票调度线程。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "agentflow.finance-gateway.enabled", havingValue = "true")
public class BudgetAdjustmentCheckScheduling {
    /** 单进程串行处理，跨进程由数据库协调唯一领取。 */
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskScheduler budgetAdjustmentCheckTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("budget-adjustment-check-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false); return scheduler;
    }
    /** 关闭领取保留持久任务及原租约。 */
    @Bean
    @ConditionalOnProperty(name = "agentflow.budget-adjustments.precheck-worker-enabled", havingValue = "true", matchIfMissing = true)
    public Poller budgetAdjustmentCheckPoller(BudgetAdjustmentCheckWorker worker) { return new Poller(worker); }

    /**
     * 测试或恢复工具可以直接调用执行器，无需等待定时器。
     * @author owlzhangfq@gmail.com
     */
    public static final class Poller {
        private final BudgetAdjustmentCheckWorker worker;
        private Poller(BudgetAdjustmentCheckWorker worker) { this.worker = worker; }
        /** 固定延迟保证同线程不会重入。 */
        @Scheduled(fixedDelayString = "${agentflow.budget-adjustments.precheck-poll-delay-ms:1000}", scheduler = "budgetAdjustmentCheckTaskScheduler")
        public void poll() { worker.poll(); }
    }
}
