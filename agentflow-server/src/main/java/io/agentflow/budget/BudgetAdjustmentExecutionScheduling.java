package io.agentflow.budget;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 财务台账复核与原子调整分别调度，读取延迟不会占用原指令的恢复线程。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "agentflow.finance-gateway.enabled", havingValue = "true")
public class BudgetAdjustmentExecutionScheduling {
    /** 单进程各自串行，跨进程由持久申请锁及版本仲裁。 */
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskScheduler budgetAdjustmentReviewTaskScheduler() { return scheduler("budget-adjustment-review-"); }
    /** 原号查询使用独立线程，关闭进程不删除已有租约。 */
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskScheduler budgetAdjustmentExecutionTaskScheduler() { return scheduler("budget-adjustment-execution-"); }
    /** 读取开关只影响后台领取，保留全部原始证据。 */
    @Bean
    @ConditionalOnProperty(name = "agentflow.budget-adjustments.review-worker-enabled", havingValue = "true", matchIfMissing = true)
    public ReviewPoller budgetAdjustmentReviewPoller(BudgetAdjustmentReviewWorker worker) { return new ReviewPoller(worker); }
    /** 执行开关关闭时保留原指令，重新开启仍按原状态恢复。 */
    @Bean
    @ConditionalOnProperty(name = "agentflow.budget-adjustments.execution-worker-enabled", havingValue = "true", matchIfMissing = true)
    public ExecutionPoller budgetAdjustmentExecutionPoller(BudgetAdjustmentExecutionWorker worker) { return new ExecutionPoller(worker); }
    private static ThreadPoolTaskScheduler scheduler(String prefix) {
        var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1); scheduler.setThreadNamePrefix(prefix);
        scheduler.setWaitForTasksToCompleteOnShutdown(false); return scheduler;
    }
    /**
     * 定时器不创建财务授权，只执行已经登记的台账读取。
     * @author owlzhangfq@gmail.com
     */
    public static final class ReviewPoller {
        private final BudgetAdjustmentReviewWorker worker;
        private ReviewPoller(BudgetAdjustmentReviewWorker worker) { this.worker = worker; }
        /** 固定延迟避免同一线程重入。 */
        @Scheduled(fixedDelayString = "${agentflow.budget-adjustments.review-poll-delay-ms:1000}", scheduler = "budgetAdjustmentReviewTaskScheduler")
        public void poll() { worker.poll(); }
    }
    /**
     * 定时器不改变原指令，只按持久状态发送或查询。
     * @author owlzhangfq@gmail.com
     */
    public static final class ExecutionPoller {
        private final BudgetAdjustmentExecutionWorker worker;
        private ExecutionPoller(BudgetAdjustmentExecutionWorker worker) { this.worker = worker; }
        /** 固定延迟与数据库领取共同限制重复执行。 */
        @Scheduled(fixedDelayString = "${agentflow.budget-adjustments.execution-poll-delay-ms:1000}", scheduler = "budgetAdjustmentExecutionTaskScheduler")
        public void poll() { worker.poll(); }
    }
}
