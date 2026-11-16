package io.agentflow.procurement;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 采购付款申请查询使用独立线程，不占用审批期限、通知或验票调度线程。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@ConditionalOnProperty(name = "agentflow.finance-gateway.enabled", havingValue = "true")
public class ProcurementPaymentCheckScheduling {
    /** 关闭领取保留持久任务及原租约。 */
    @Bean
    @ConditionalOnProperty(name = "agentflow.procurement-payments.precheck-worker-enabled", havingValue = "true", matchIfMissing = true)
    public Poller procurementPaymentCheckPoller(ProcurementPaymentCheckWorker worker) { return new Poller(worker); }

    /**
     * 测试或恢复工具可以直接调用执行器，无需等待定时器。
     * @author owlzhangfq@gmail.com
     */
    public static final class Poller {
        private final ProcurementPaymentCheckWorker worker;
        private Poller(ProcurementPaymentCheckWorker worker) { this.worker = worker; }
        /** 固定延迟保证同线程不会重入。 */
        @Scheduled(fixedDelayString = "${agentflow.procurement-payments.precheck-poll-delay-ms:1000}")
        public void poll() { worker.poll(); }
    }
}
