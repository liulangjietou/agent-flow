package io.agentflow.expense;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 独立线程处理已持久的资源调整，不阻塞原付款和凭证队列。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "agentflow.expenses.resource-adjustment-worker-enabled", havingValue = "true", matchIfMissing = true)
public class ExpenseResourceAdjustmentScheduling {
    /** 停机保留未完成租约，重启后使用原预算操作查询。 */
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskScheduler expenseResourceAdjustmentTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("expense-adjustment-"); scheduler.setWaitForTasksToCompleteOnShutdown(false); return scheduler;
    }
    /** 关闭调度保留原准备、授权与命令，恢复不会生成新编号。 */
    @Bean
    public Poller expenseResourceAdjustmentPoller(ExpenseResourceAdjustmentWorker worker) { return new Poller(worker); }
    /**
     * 读取准备和外发命令各有独立持久状态，调度器不代替财务授权。
     * @author owlzhangfq@gmail.com
     */
    public static final class Poller {
        private final ExpenseResourceAdjustmentWorker worker;
        private Poller(ExpenseResourceAdjustmentWorker worker) { this.worker = worker; }
        /** 固定延迟配合数据库版本领取，跨进程不会重复采纳同一结果。 */
        @Scheduled(fixedDelayString = "${agentflow.expenses.resource-adjustment-poll-delay-ms:1000}", scheduler = "expenseResourceAdjustmentTaskScheduler")
        public void poll() { worker.poll(); }
    }
}
