package io.agentflow.procurement;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 供应商回款复核使用单独有界线程，持久意图可在重启后恢复。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "agentflow.finance-gateway.enabled", havingValue = "true")
public class SupplierPaymentReturnScheduling {
    /** 停机保留租约，由恢复后的任务明确处理超时。 */
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskScheduler supplierPaymentReturnTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("supplier-payment-return-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false); return scheduler;
    }

    /** 默认处理人工保存的意图，可独立关闭调度且不丢失任务。 */
    @Bean
    @ConditionalOnProperty(name = "agentflow.supplier-payments.return-worker-enabled", havingValue = "true", matchIfMissing = true)
    public Poller supplierPaymentReturnPoller(SupplierPaymentReturnWorker worker) { return new Poller(worker); }

    /**
     * 定时器仅触发原号读取，不自动登记银行资金或调整 ERP。
     * @author owlzhangfq@gmail.com
     */
    public static final class Poller {
        private final SupplierPaymentReturnWorker worker;
        private Poller(SupplierPaymentReturnWorker worker) { this.worker = worker; }
        /** 查询失败由财务明确发起新任务，不无限重试外部系统。 */
        @Scheduled(fixedDelayString = "${agentflow.supplier-payments.return-poll-delay-ms:1000}", scheduler = "supplierPaymentReturnTaskScheduler")
        public void poll() { worker.poll(); }
    }
}
