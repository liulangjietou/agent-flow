package io.agentflow.expense;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 验票使用专有有界调度池，网络延迟不阻塞审批期限或模型任务。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "agentflow.finance-gateway.enabled", havingValue = "true")
public class InvoiceVerificationScheduling {
    /** 单进程串行外发，多进程由发票行锁和活动任务唯一键协调。 */
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskScheduler invoiceVerificationTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("invoice-verification-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false); return scheduler;
    }
    /** 运维暂停只停止领取，数据库中的任务和租约保持可恢复。 */
    @Bean
    @ConditionalOnProperty(name = "agentflow.invoices.verification-worker-enabled", havingValue = "true", matchIfMissing = true)
    public Poller invoiceVerificationPoller(InvoiceVerificationWorker worker) { return new Poller(worker); }

    /**
     * 独立调度包装，测试可以主动轮询而不等待定时器。
     * @author owlzhangfq@gmail.com
     */
    public static final class Poller {
        private final InvoiceVerificationWorker worker;
        private Poller(InvoiceVerificationWorker worker) { this.worker = worker; }
        /** 固定延迟避免同一调度线程重入。 */
        @Scheduled(fixedDelayString = "${agentflow.invoices.verification-poll-delay-ms:1000}", scheduler = "invoiceVerificationTaskScheduler")
        public void poll() { worker.poll(); }
    }
}
