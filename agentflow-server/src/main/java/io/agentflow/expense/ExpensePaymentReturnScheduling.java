package io.agentflow.expense;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 原报销付款复核查询使用独立有界线程，不阻塞付款及对账执行。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "agentflow.finance-gateway.enabled", havingValue = "true")
public class ExpensePaymentReturnScheduling {
    /** 停机不会等候外部读取，遗留租约在恢复后明确超时。 */
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskScheduler expensePaymentReturnTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("expense-payment-return-"); scheduler.setWaitForTasksToCompleteOnShutdown(false); return scheduler;
    }
    /** 默认处理已持久的人工请求，配置可停用而不丢失意图。 */
    @Bean
    @ConditionalOnProperty(name = "agentflow.expenses.payment-return-worker-enabled", havingValue = "true", matchIfMissing = true)
    public Poller expensePaymentReturnPoller(ExpensePaymentReturnWorker worker) { return new Poller(worker); }
    /**
     * 调度只读取原报销付款退回证据，不创建退款命令或会计凭证。
     * @author owlzhangfq@gmail.com
     */
    public static final class Poller {
        private final ExpensePaymentReturnWorker worker;
        private Poller(ExpensePaymentReturnWorker worker) { this.worker = worker; }
        /** 查询传输失败需要人工建立新查询，不无限重试。 */
        @Scheduled(fixedDelayString = "${agentflow.expenses.payment-return-poll-delay-ms:1000}", scheduler = "expensePaymentReturnTaskScheduler")
        public void poll() { worker.poll(); }
    }
}
