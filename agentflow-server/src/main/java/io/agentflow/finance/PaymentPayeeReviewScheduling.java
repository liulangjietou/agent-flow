package io.agentflow.finance;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 本人账户复核单独调度，外部账户等待不阻塞付款事实查询。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "agentflow.finance-gateway.enabled", havingValue = "true")
public class PaymentPayeeReviewScheduling {
    /** 业务锁及版本比较协调多进程，同进程按有界批次执行。 */
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskScheduler paymentPayeeReviewTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("payee-review-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false); return scheduler;
    }
    /** 暂停调度仍保留已经登记的人工复核意图。 */
    @Bean
    @ConditionalOnProperty(name = "agentflow.payments.payee-review-worker-enabled", havingValue = "true", matchIfMissing = true)
    public Poller paymentPayeeReviewPoller(PaymentPayeeReviewWorker worker) { return new Poller(worker); }
    /**
     * 只消费已有读取请求，成功后仍等待财务明确授权。
     * @author owlzhangfq@gmail.com
     */
    public static final class Poller {
        private final PaymentPayeeReviewWorker worker;
        private Poller(PaymentPayeeReviewWorker worker) { this.worker = worker; }
        /** 读取失败需要新的人工复核，不无限重试外部主数据。 */
        @Scheduled(fixedDelayString = "${agentflow.payments.payee-review-poll-delay-ms:1000}", scheduler = "paymentPayeeReviewTaskScheduler")
        public void poll() { worker.poll(); }
    }
}
