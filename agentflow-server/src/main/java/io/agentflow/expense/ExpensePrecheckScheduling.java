package io.agentflow.expense;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 多行财务检查使用独立线程，不占用审批期限、通知或验票调度线程。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@ConditionalOnProperty(name = "agentflow.finance-gateway.enabled", havingValue = "true")
public class ExpensePrecheckScheduling {
    /** 关闭领取保留持久任务及原租约。 */
    @Bean
    @ConditionalOnProperty(name = "agentflow.expenses.precheck-worker-enabled", havingValue = "true", matchIfMissing = true)
    public Poller expensePrecheckPoller(ExpensePrecheckWorker worker) { return new Poller(worker); }

    /**
     * 测试或恢复工具可以直接调用执行器，无需等待定时器。
     * @author owlzhangfq@gmail.com
     */
    public static final class Poller {
        private final ExpensePrecheckWorker worker;
        private Poller(ExpensePrecheckWorker worker) { this.worker = worker; }
        /** 固定延迟保证同线程不会重入。 */
        @Scheduled(fixedDelayString = "${agentflow.expenses.precheck-poll-delay-ms:1000}")
        public void poll() { worker.poll(); }
    }
}
