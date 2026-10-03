package io.agentflow.expense;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 本地核销不依赖网关当前可用性，预算外发继续由原预算执行器处理。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "agentflow.expenses.settlement-worker-enabled", havingValue = "true", matchIfMissing = true)
public class ExpenseSettlementScheduling {
    /** 本地消费只占一条独立调度线程，多进程由原报销锁和版本串行。 */
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskScheduler expenseSettlementTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("expense-settlement-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false); return scheduler;
    }
    /** 停止调度不删除结算队列或历史。 */
    @Bean public Poller expenseSettlementPoller(ExpenseSettlementWorker worker) { return new Poller(worker); }

    /**
     * 只执行已获资金或零应付依据的原核销，不自动作财务授权。
     * @author owlzhangfq@gmail.com
     */
    public static final class Poller {
        private final ExpenseSettlementWorker worker;
        private Poller(ExpenseSettlementWorker worker) { this.worker = worker; }
        /** 重启由数据库原状态恢复。 */
        @Scheduled(fixedDelayString = "${agentflow.expenses.settlement-poll-delay-ms:1000}", scheduler = "expenseSettlementTaskScheduler")
        public void poll() { worker.poll(); }
    }
}
