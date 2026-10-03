package io.agentflow.finance;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * ERP 独立调度线程，过账等待不阻塞审批、预算和查验任务。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "agentflow.finance-gateway.enabled", havingValue = "true")
public class VoucherOperationScheduling {
    /** 同进程串行领取，跨进程由申请锁和领取版本协调。 */
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskScheduler voucherOperationTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("voucher-operation-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false); return scheduler;
    }
    /** 关闭执行保留任务、原命令和恢复事实。 */
    @Bean
    @ConditionalOnProperty(name = "agentflow.vouchers.worker-enabled", havingValue = "true", matchIfMissing = true)
    public Poller voucherOperationPoller(VoucherOperationWorker worker) { return new Poller(worker); }

    /**
     * 固定延迟只触发已有任务消费，不自行生成凭证或换号重发。
     * @author owlzhangfq@gmail.com
     */
    public static final class Poller {
        private final VoucherOperationWorker worker;
        private Poller(VoucherOperationWorker worker) { this.worker = worker; }
        /** 具体重试时刻在数据库中保存。 */
        @Scheduled(fixedDelayString = "${agentflow.vouchers.poll-delay-ms:1000}", scheduler = "voucherOperationTaskScheduler")
        public void poll() { worker.poll(); }
    }
}
