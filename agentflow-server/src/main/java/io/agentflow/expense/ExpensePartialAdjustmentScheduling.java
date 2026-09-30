package io.agentflow.expense;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 独立线程推进部分报销调整，队列和租约在数据库中，停机不删除原财务授权。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "agentflow.expenses.partial-adjustment-worker-enabled", havingValue = "true", matchIfMissing = true)
public class ExpensePartialAdjustmentScheduling {
    /** 网络等待不占用原付款、凭证或全额取消的调度线程。 */
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskScheduler expensePartialAdjustmentTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("expense-partial-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false); return scheduler;
    }
    /** 关闭调度只停止自动领取，原操作仍可在重启后沿原号恢复。 */
    @Bean
    public Poller expensePartialAdjustmentPoller(ExpensePartialAdjustmentWorker worker) { return new Poller(worker); }
    /**
     * 固定延迟与数据库租约共同限制重复发送，调度不代替独立财务决定。
     * @author owlzhangfq@gmail.com
     */
    public static final class Poller {
        private final ExpensePartialAdjustmentWorker worker;
        private Poller(ExpensePartialAdjustmentWorker worker) { this.worker = worker; }
        /** 多实例同时扫描由原报销锁及操作修订串行领取。 */
        @Scheduled(fixedDelayString = "${agentflow.expenses.partial-adjustment-poll-delay-ms:1000}", scheduler = "expensePartialAdjustmentTaskScheduler")
        public void poll() { worker.poll(); }
    }
}
