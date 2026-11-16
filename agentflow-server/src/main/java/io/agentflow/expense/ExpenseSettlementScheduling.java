package io.agentflow.expense;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 本地核销不依赖网关当前可用性，预算外发继续由原预算执行器处理。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@ConditionalOnProperty(name = "agentflow.expenses.settlement-worker-enabled", havingValue = "true", matchIfMissing = true)
public class ExpenseSettlementScheduling {
    /** 停止调度不删除结算队列或历史。 */
    @Bean public Poller expenseSettlementPoller(ExpenseSettlementWorker worker) { return new Poller(worker); }

    /**
     * 只执行已获资金或零应付依据的原核销，不自动作财务授权。
     * @author owlzhangfq@gmail.com
     */
    public static final class Poller {
        private final ExpenseSettlementWorker worker;
        private Poller(ExpenseSettlementWorker worker) { this.worker = worker; }
        /** 重启由数据库原状态恢复。 */
        @Scheduled(fixedDelayString = "${agentflow.expenses.settlement-poll-delay-ms:1000}")
        public void poll() { worker.poll(); }
    }
}
