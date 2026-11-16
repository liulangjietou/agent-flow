package io.agentflow.finance;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 预算等待使用独立线程，不阻塞审批期限、验票或预检。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@ConditionalOnProperty(name = "agentflow.finance-gateway.enabled", havingValue = "true")
public class BudgetOperationScheduling {
    /** 关闭外发保留所有待定操作和租约，恢复后按原状态继续。 */
    @Bean
    @ConditionalOnProperty(name = "agentflow.budgets.worker-enabled", havingValue = "true", matchIfMissing = true)
    public Poller budgetOperationPoller(BudgetOperationWorker worker) { return new Poller(worker); }

    /**
     * 固定延迟避免同一进程重复执行，任务自身保存下一次重试时间。
     * @author owlzhangfq@gmail.com
     */
    public static final class Poller {
        private final BudgetOperationWorker worker;
        private Poller(BudgetOperationWorker worker) { this.worker = worker; }
        /** 调度器不生成重试命令，只有执行器可以领取已有任务。 */
        @Scheduled(fixedDelayString = "${agentflow.budgets.poll-delay-ms:1000}")
        public void poll() { worker.poll(); }
    }
}
