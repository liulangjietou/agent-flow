package io.agentflow.finance;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 出纳选择的账户复查独立消费，恢复时不阻塞资金交易查询。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "agentflow.finance-gateway.enabled", havingValue = "true")
public class PaymentExecutionRequestScheduling {
    /** 多进程以原申请锁和领取版本协调，同进程有界串行。 */
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskScheduler paymentExecutionRequestTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("payment-request-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false); return scheduler;
    }
    /** 暂停只读检查仍保留出纳选择，不影响已有付款的独立对账。 */
    @Bean
    @ConditionalOnProperty(name = "agentflow.payments.request-worker-enabled", havingValue = "true", matchIfMissing = true)
    public Poller paymentExecutionRequestPoller(PaymentExecutionRequestWorker worker) { return new Poller(worker); }
    /**
     * 只消费已有人工请求，不自动选择出款账户。
     * @author owlzhangfq@gmail.com
     */
    public static final class Poller {
        private final PaymentExecutionRequestWorker worker;
        private Poller(PaymentExecutionRequestWorker worker) { this.worker = worker; }
        /** 具体退避时刻保存在原请求中。 */
        @Scheduled(fixedDelayString = "${agentflow.payments.request-poll-delay-ms:1000}", scheduler = "paymentExecutionRequestTaskScheduler")
        public void poll() { worker.poll(); }
    }
}
