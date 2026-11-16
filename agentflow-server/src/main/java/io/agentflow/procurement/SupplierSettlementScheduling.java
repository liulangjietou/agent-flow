package io.agentflow.procurement;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 财务准备与原结算恢复独立调度，缓慢只读依赖不会占住未知结果查询线程。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@ConditionalOnProperty(name = "agentflow.finance-gateway.enabled", havingValue = "true")
public class SupplierSettlementScheduling {
    /** 停止消费保留已保存的财务日期意图。 */
    @Bean
    @ConditionalOnProperty(name = "agentflow.supplier-payments.settlement-preparation-worker-enabled", havingValue = "true", matchIfMissing = true)
    public PreparationPoller supplierSettlementPreparationPoller(SupplierSettlementPreparationWorker worker) { return new PreparationPoller(worker); }
    /** 停止消费保留可能发送与未知结果供恢复。 */
    @Bean
    @ConditionalOnProperty(name = "agentflow.supplier-payments.settlement-worker-enabled", havingValue = "true", matchIfMissing = true)
    public SettlementPoller supplierSettlementPoller(SupplierSettlementWorker worker) { return new SettlementPoller(worker); }

    /**
     * 明确财务意图的只读复核。
     * @author owlzhangfq@gmail.com
     */
    public static final class PreparationPoller {
        private final SupplierSettlementPreparationWorker worker;
        private PreparationPoller(SupplierSettlementPreparationWorker worker) { this.worker = worker; }
        /** 固定延迟避免本地重入。 */
        @Scheduled(fixedDelayString = "${agentflow.supplier-payments.settlement-preparation-poll-delay-ms:1000}")
        public void poll() { worker.poll(); }
    }

    /**
     * 核销、原号查询及本地完成由实际持久状态决定。
     * @author owlzhangfq@gmail.com
     */
    public static final class SettlementPoller {
        private final SupplierSettlementWorker worker;
        private SettlementPoller(SupplierSettlementWorker worker) { this.worker = worker; }
        /** 固定延迟扫描有界队列。 */
        @Scheduled(fixedDelayString = "${agentflow.supplier-payments.settlement-poll-delay-ms:1000}")
        public void poll() { worker.poll(); }
    }
}
