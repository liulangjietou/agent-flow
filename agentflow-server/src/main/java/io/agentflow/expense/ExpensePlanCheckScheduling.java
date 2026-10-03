package io.agentflow.expense;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 事前申请查询使用独立线程，不占用审批期限、通知或验票调度线程。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "agentflow.finance-gateway.enabled", havingValue = "true")
public class ExpensePlanCheckScheduling {
    /** 单进程串行处理，跨进程由数据库协调唯一领取。 */
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskScheduler expensePlanCheckTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("expense-plan-check-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false); return scheduler;
    }
    /** 关闭领取保留持久任务及原租约。 */
    @Bean
    @ConditionalOnProperty(name = "agentflow.expense-plans.precheck-worker-enabled", havingValue = "true", matchIfMissing = true)
    public Poller expensePlanCheckPoller(ExpensePlanCheckWorker worker) { return new Poller(worker); }

    /**
     * 测试或恢复工具可以直接调用执行器，无需等待定时器。
     * @author owlzhangfq@gmail.com
     */
    public static final class Poller {
        private final ExpensePlanCheckWorker worker;
        private Poller(ExpensePlanCheckWorker worker) { this.worker = worker; }
        /** 固定延迟保证同线程不会重入。 */
        @Scheduled(fixedDelayString = "${agentflow.expense-plans.precheck-poll-delay-ms:1000}", scheduler = "expensePlanCheckTaskScheduler")
        public void poll() { worker.poll(); }
    }
}
