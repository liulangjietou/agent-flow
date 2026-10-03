package io.agentflow.finance;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 仅调度已经持久的准备与明确授权，独立线程不阻塞原凭证队列。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "agentflow.finance-gateway.enabled", havingValue = "true")
public class VoucherReversalExecutionScheduling {
    /** 停机保留未完成租约，重启后查询原编号。 */
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskScheduler voucherReversalExecutionTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("reversal-execution-"); scheduler.setWaitForTasksToCompleteOnShutdown(false); return scheduler;
    }
    /** 配置可暂停处理而不删除准备、授权或命令。 */
    @Bean
    @ConditionalOnProperty(name = "agentflow.vouchers.reversal-execution-worker-enabled", havingValue = "true", matchIfMissing = true)
    public Poller voucherReversalExecutionPoller(VoucherReversalExecutionWorker worker) { return new Poller(worker); }
    /**
     * 只读准备不会自动授权，写队列只消费已明确授权的命令。
     * @author owlzhangfq@gmail.com
     */
    public static final class Poller {
        private final VoucherReversalExecutionWorker worker;
        private Poller(VoucherReversalExecutionWorker worker) { this.worker = worker; }
        /** 外部写入未知按持久退避查询，查无和矛盾停止自动处理。 */
        @Scheduled(fixedDelayString = "${agentflow.vouchers.reversal-execution-poll-delay-ms:1000}", scheduler = "voucherReversalExecutionTaskScheduler")
        public void poll() { worker.poll(); }
    }
}
