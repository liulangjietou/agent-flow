package io.agentflow.expense;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 预算节点的本地恢复使用独立线程，不占用外部预算调用或期限调度。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@ConditionalOnProperty(name = "agentflow.finance-gateway.enabled", havingValue = "true")
public class ExpenseBudgetReviewScheduling {

    /** 关闭扫描不会删除原结果，恢复后继续处理原轮次。 */
    @Bean
    @ConditionalOnProperty(name = "agentflow.expenses.budget-review-worker-enabled", havingValue = "true", matchIfMissing = true)
    public Poller expenseBudgetReviewPoller(ExpenseBudgetReviewWorker worker) { return new Poller(worker); }

    /**
     * 调度与执行分开，运行验收可明确驱动一次本地恢复。
     * @author owlzhangfq@gmail.com
     */
    public static final class Poller {
        private final ExpenseBudgetReviewWorker worker;
        private Poller(ExpenseBudgetReviewWorker worker) { this.worker = worker; }
        /** 固定延迟避免同一线程交错推进。 */
        @Scheduled(fixedDelayString = "${agentflow.expenses.budget-review-poll-delay-ms:1000}")
        public void poll() { worker.poll(); }
    }
}
