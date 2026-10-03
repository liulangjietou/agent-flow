package io.agentflow.finance;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 即使 ERP 未配置也将准备任务转为可见不可用，后续显式重试才绑定新目标。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@EnableScheduling
public class VoucherPreparationScheduling {
    /** 查询不占用过账、通知或审批期限调度线程。 */
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskScheduler voucherPreparationTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("voucher-preparation-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false); return scheduler;
    }
    /** 关闭后台领取保留已批准后的持久任务。 */
    @Bean
    @ConditionalOnProperty(name = "agentflow.vouchers.preparation-worker-enabled", havingValue = "true", matchIfMissing = true)
    public Poller voucherPreparationPoller(VoucherPreparationWorker worker) { return new Poller(worker); }
    /**
     * 批准与外部查询的时序由队列提交保证。
     * @author owlzhangfq@gmail.com
     */
    public static final class Poller {
        private final VoucherPreparationWorker worker;
        private Poller(VoucherPreparationWorker worker) { this.worker = worker; }
        /** 固定延迟只处理已经提交的准备任务。 */
        @Scheduled(fixedDelayString = "${agentflow.vouchers.preparation-poll-delay-ms:1000}", scheduler = "voucherPreparationTaskScheduler")
        public void poll() { worker.poll(); }
    }
}
