package io.agentflow.procurement;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 财务应付读取与外部预留分别调度，读取故障不占用审批或其他资金线程。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "agentflow.finance-gateway.enabled", havingValue = "true")
public class SupplierPayableReviewScheduling {
    /** 单进程串行、跨进程按原申请及读取版本仲裁领取。 */
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskScheduler supplierPayableReviewTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("supplier-payable-review-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false); return scheduler;
    }

    /** 关闭消费保留未完成的读取及原租约。 */
    @Bean
    @ConditionalOnProperty(name = "agentflow.supplier-payments.review-worker-enabled", havingValue = "true", matchIfMissing = true)
    public Poller supplierPayableReviewPoller(SupplierPayableReviewWorker worker) { return new Poller(worker); }

    /**
     * 定时器只执行已有财务读取意图，不创建或消费授权。
     * @author owlzhangfq@gmail.com
     */
    public static final class Poller {
        private final SupplierPayableReviewWorker worker;
        private Poller(SupplierPayableReviewWorker worker) { this.worker = worker; }
        /** 固定延迟不重入同一个工作线程。 */
        @Scheduled(fixedDelayString = "${agentflow.supplier-payments.review-poll-delay-ms:1000}", scheduler = "supplierPayableReviewTaskScheduler")
        public void poll() { worker.poll(); }
    }
}
