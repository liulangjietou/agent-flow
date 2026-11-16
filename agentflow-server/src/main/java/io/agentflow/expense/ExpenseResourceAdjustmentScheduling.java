package io.agentflow.expense;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 独立线程处理已持久的资源调整，不阻塞原付款和凭证队列。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@ConditionalOnProperty(name = "agentflow.expenses.resource-adjustment-worker-enabled", havingValue = "true", matchIfMissing = true)
public class ExpenseResourceAdjustmentScheduling {
    /** 关闭调度保留原准备、授权与命令，恢复不会生成新编号。 */
    @Bean
    public Poller expenseResourceAdjustmentPoller(ExpenseResourceAdjustmentWorker worker) { return new Poller(worker); }
    /**
     * 读取准备和外发命令各有独立持久状态，调度器不代替财务授权。
     * @author owlzhangfq@gmail.com
     */
    public static final class Poller {
        private final ExpenseResourceAdjustmentWorker worker;
        private Poller(ExpenseResourceAdjustmentWorker worker) { this.worker = worker; }
        /** 固定延迟配合数据库版本领取，跨进程不会重复采纳同一结果。 */
        @Scheduled(fixedDelayString = "${agentflow.expenses.resource-adjustment-poll-delay-ms:1000}")
        public void poll() { worker.poll(); }
    }
}
