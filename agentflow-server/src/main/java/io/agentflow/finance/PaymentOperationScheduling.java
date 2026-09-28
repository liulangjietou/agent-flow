package io.agentflow.finance;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 资金调用独立调度，网络等待不阻塞审批、凭证和账户预检之外的工作。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "agentflow.finance-gateway.enabled", havingValue = "true")
public class PaymentOperationScheduling {
    /** 进程内串行处理，跨进程由原申请锁和领取版本约束。 */
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskScheduler paymentOperationTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("payment-operation-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false); return scheduler;
    }
    /** 关闭消费不会删除授权、原命令或待对账事实。 */
    @Bean
    @ConditionalOnProperty(name = "agentflow.payments.worker-enabled", havingValue = "true", matchIfMissing = true)
    public Poller paymentOperationPoller(PaymentOperationWorker worker) { return new Poller(worker); }

    /**
     * 调度只消费已登记的操作，不自动批准或创建新付款授权。
     * @author owlzhangfq@gmail.com
     */
    public static final class Poller {
        private final PaymentOperationWorker worker;
        private Poller(PaymentOperationWorker worker) { this.worker = worker; }
        /** 到期和重试时刻以数据库原记录为准。 */
        @Scheduled(fixedDelayString = "${agentflow.payments.poll-delay-ms:1000}", scheduler = "paymentOperationTaskScheduler")
        public void poll() { worker.poll(); }
    }
}
