package io.agentflow.expense;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 原件校验使用独立线程，不阻塞短事务结算和资金查询调度。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@ConditionalOnProperty(name = "agentflow.expenses.archive-worker-enabled", havingValue = "true", matchIfMissing = true)
public class ExpenseArchiveScheduling {
    /** 关闭调度保留既有档案、阻塞原因和下载入口。 */
    @Bean public Poller expenseArchivePoller(ExpenseArchiveWorker worker) { return new Poller(worker); }
    /**
     * 周期读取本地实际结算事实，无外部副作用。
     * @author owlzhangfq@gmail.com
     */
    public static final class Poller {
        private final ExpenseArchiveWorker worker;
        private Poller(ExpenseArchiveWorker worker) { this.worker = worker; }
        /** 阻塞原因修复后下一轮自动复核，不提供绕过缺件的人工强制归档。 */
        @Scheduled(fixedDelayString = "${agentflow.expenses.archive-poll-delay-ms:15000}")
        public void poll() { worker.poll(); }
    }
}
