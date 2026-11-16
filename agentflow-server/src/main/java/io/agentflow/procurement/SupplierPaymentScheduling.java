package io.agentflow.procurement;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 出纳准备和银行查询独立运行，缓慢只读依赖不会占用银行未知结果的恢复线程。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@ConditionalOnProperty(name = "agentflow.finance-gateway.enabled", havingValue = "true")
public class SupplierPaymentScheduling {
    /** 关闭准备消费仍保留已提交的出纳选择。 */
    @Bean
    @ConditionalOnProperty(name = "agentflow.supplier-payments.execution-worker-enabled", havingValue = "true", matchIfMissing = true)
    public PreparationPoller supplierPaymentExecutionPoller(SupplierPaymentExecutionWorker worker) { return new PreparationPoller(worker); }
    /** 关闭银行消费不会删除原命令、发送记录或未知交易。 */
    @Bean
    @ConditionalOnProperty(name = "agentflow.supplier-payments.payment-worker-enabled", havingValue = "true", matchIfMissing = true)
    public PaymentPoller supplierPaymentPoller(SupplierPaymentWorker worker) { return new PaymentPoller(worker); }

    /**
     * 只消费明确的出纳选择，财务授权本身不会触发银行指令。
     * @author owlzhangfq@gmail.com
     */
    public static final class PreparationPoller {
        private final SupplierPaymentExecutionWorker worker;
        private PreparationPoller(SupplierPaymentExecutionWorker worker) { this.worker = worker; }
        /** 固定延迟避免本地重入。 */
        @Scheduled(fixedDelayString = "${agentflow.supplier-payments.execution-poll-delay-ms:1000}")
        public void poll() { worker.poll(); }
    }
    /**
     * 首次发送和原交易恢复由持久状态区分，不使用调度次数推断是否付款。
     * @author owlzhangfq@gmail.com
     */
    public static final class PaymentPoller {
        private final SupplierPaymentWorker worker;
        private PaymentPoller(SupplierPaymentWorker worker) { this.worker = worker; }
        /** 固定延迟扫描有界队列。 */
        @Scheduled(fixedDelayString = "${agentflow.supplier-payments.payment-poll-delay-ms:1000}")
        public void poll() { worker.poll(); }
    }
}
