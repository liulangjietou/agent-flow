package io.agentflow.expense;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 原还款复核查询使用独立有界线程，不阻塞付款及对账执行。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@ConditionalOnProperty(name = "agentflow.finance-gateway.enabled", havingValue = "true")
public class AdvanceRepaymentReviewScheduling {
    /** 默认处理已持久的人工请求，配置可停用而不丢失意图。 */
    @Bean
    @ConditionalOnProperty(name = "agentflow.advances.repayment-review-worker-enabled", havingValue = "true", matchIfMissing = true)
    public Poller advanceRepaymentReviewPoller(AdvanceRepaymentReviewWorker worker) { return new Poller(worker); }
    /**
     * 调度只读取原还款调整证据，不创建退款命令或会计凭证。
     * @author owlzhangfq@gmail.com
     */
    public static final class Poller {
        private final AdvanceRepaymentReviewWorker worker;
        private Poller(AdvanceRepaymentReviewWorker worker) { this.worker = worker; }
        /** 查询传输失败需要人工建立新查询，不无限重试。 */
        @Scheduled(fixedDelayString = "${agentflow.advances.repayment-review-poll-delay-ms:1000}")
        public void poll() { worker.poll(); }
    }
}
