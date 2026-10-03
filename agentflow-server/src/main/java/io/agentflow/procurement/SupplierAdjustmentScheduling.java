package io.agentflow.procurement;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 准备读取和原号执行恢复使用独立调度线程，停止消费不删除持久意图。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "agentflow.finance-gateway.enabled", havingValue = "true")
public class SupplierAdjustmentScheduling {
    /** 准备在进程内串行，跨进程由原申请锁和领取版本仲裁。 */
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskScheduler supplierAdjustmentPreparationTaskScheduler() { return scheduler("supplier-adjustment-preparation-"); }
    /** 可能写入后的恢复拥有独立资源，不等待新准备结束。 */
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskScheduler supplierAdjustmentTaskScheduler() { return scheduler("supplier-adjustment-"); }
    /** 暂停读取时保留原财务意图和会计日期。 */
    @Bean
    @ConditionalOnProperty(name = "agentflow.supplier-payments.adjustment-preparation-worker-enabled", havingValue = "true", matchIfMissing = true)
    public PreparationPoller supplierAdjustmentPreparationPoller(SupplierAdjustmentPreparationWorker worker) { return new PreparationPoller(worker); }
    /** 暂停发送时保留未知结果供之后查询恢复。 */
    @Bean
    @ConditionalOnProperty(name = "agentflow.supplier-payments.adjustment-worker-enabled", havingValue = "true", matchIfMissing = true)
    public ExecutionPoller supplierAdjustmentPoller(SupplierAdjustmentWorker worker) { return new ExecutionPoller(worker); }
    private static ThreadPoolTaskScheduler scheduler(String prefix) {
        var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1); scheduler.setThreadNamePrefix(prefix); scheduler.setWaitForTasksToCompleteOnShutdown(false); return scheduler;
    }

    /**
     * 已授权财务意图的只读准备。
     * @author owlzhangfq@gmail.com
     */
    public static final class PreparationPoller {
        private final SupplierAdjustmentPreparationWorker worker;
        private PreparationPoller(SupplierAdjustmentPreparationWorker worker) { this.worker = worker; }
        /** 固定延迟避免同进程重入。 */
        @Scheduled(fixedDelayString = "${agentflow.supplier-payments.adjustment-preparation-poll-delay-ms:1000}", scheduler = "supplierAdjustmentPreparationTaskScheduler")
        public void poll() { worker.poll(); }
    }

    /**
     * 调整、原号查询和本地完成由实际持久状态决定。
     * @author owlzhangfq@gmail.com
     */
    public static final class ExecutionPoller {
        private final SupplierAdjustmentWorker worker;
        private ExecutionPoller(SupplierAdjustmentWorker worker) { this.worker = worker; }
        /** 本轮网络调用结束之后才开始下一轮。 */
        @Scheduled(fixedDelayString = "${agentflow.supplier-payments.adjustment-poll-delay-ms:1000}", scheduler = "supplierAdjustmentTaskScheduler")
        public void poll() { worker.poll(); }
    }
}
