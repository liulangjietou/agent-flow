package io.agentflow.expense;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 还款查询使用独立有界线程，不阻塞付款及对账执行。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "agentflow.finance-gateway.enabled", havingValue = "true")
public class AdvanceRepaymentScheduling {
    /** 停机不会等候外部读取，遗留租约在恢复后明确超时。 */
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskScheduler advanceRepaymentTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("advance-repayment-"); scheduler.setWaitForTasksToCompleteOnShutdown(false); return scheduler;
    }
    /** 默认处理已持久的人工请求，配置可停用而不丢失意图。 */
    @Bean
    @ConditionalOnProperty(name = "agentflow.advances.repayment-worker-enabled", havingValue = "true", matchIfMissing = true)
    public Poller advanceRepaymentPoller(AdvanceRepaymentWorker worker) { return new Poller(worker); }
    /**
     * 调度只读取，不创建借款还款或付款命令。
     * @author owlzhangfq@gmail.com
     */
    public static final class Poller {
        private final AdvanceRepaymentWorker worker;
        private Poller(AdvanceRepaymentWorker worker) { this.worker = worker; }
        /** 查询传输失败需要人工建立新查询，不无限重试。 */
        @Scheduled(fixedDelayString = "${agentflow.advances.repayment-poll-delay-ms:1000}", scheduler = "advanceRepaymentTaskScheduler")
        public void poll() { worker.poll(); }
    }
}
