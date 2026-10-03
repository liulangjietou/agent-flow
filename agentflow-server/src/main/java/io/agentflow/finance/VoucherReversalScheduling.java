package io.agentflow.finance;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 冲销凭证查询使用独立有界线程，不阻塞原凭证执行。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "agentflow.finance-gateway.enabled", havingValue = "true")
public class VoucherReversalScheduling {
    /** 停机不会等候外部读取，遗留租约在恢复后明确超时。 */
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskScheduler voucherReversalTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("reversal-"); scheduler.setWaitForTasksToCompleteOnShutdown(false); return scheduler;
    }
    /** 默认处理已持久的人工请求，配置可停用而不丢失意图。 */
    @Bean
    @ConditionalOnProperty(name = "agentflow.vouchers.reversal-worker-enabled", havingValue = "true", matchIfMissing = true)
    public Poller voucherReversalPoller(VoucherReversalWorker worker) { return new Poller(worker); }
    /**
     * 调度只读取已有反向分录，不创建会计凭证或发送付款。
     * @author owlzhangfq@gmail.com
     */
    public static final class Poller {
        private final VoucherReversalWorker worker;
        private Poller(VoucherReversalWorker worker) { this.worker = worker; }
        /** 查询传输失败需要人工建立新查询，不无限重试。 */
        @Scheduled(fixedDelayString = "${agentflow.vouchers.reversal-poll-delay-ms:1000}", scheduler = "voucherReversalTaskScheduler")
        public void poll() { worker.poll(); }
    }
}
