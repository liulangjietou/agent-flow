package io.agentflow.procurement;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 供应商应付预留独立调度，不占用审批或员工付款线程。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@ConditionalOnProperty(name = "agentflow.finance-gateway.enabled", havingValue = "true")
public class SupplierPayableHoldScheduling {

    /** 关闭消费保留原授权和未完成查询。 */
    @Bean
    @ConditionalOnProperty(name = "agentflow.supplier-payments.hold-worker-enabled", havingValue = "true", matchIfMissing = true)
    public Poller supplierPayableHoldPoller(SupplierPayableHoldWorker worker) { return new Poller(worker); }

    /**
     * 只消费已保存队列，不根据审批状态自动财务授权。
     * @author owlzhangfq@gmail.com
     */
    public static final class Poller {
        private final SupplierPayableHoldWorker worker;
        private Poller(SupplierPayableHoldWorker worker) { this.worker = worker; }
        /** 固定延迟避免同一调度线程重入。 */
        @Scheduled(fixedDelayString = "${agentflow.supplier-payments.hold-poll-delay-ms:1000}")
        public void poll() { worker.poll(); }
    }
}
