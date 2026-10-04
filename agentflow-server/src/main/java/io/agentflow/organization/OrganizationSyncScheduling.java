package io.agentflow.organization;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 组织读取使用独立单线程，网络等待不占用审批期限和其他集成调度器。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "agentflow.organization-sync.enabled", havingValue = "true")
public class OrganizationSyncScheduling {
    /** 进程内只有一个轮询线程，跨进程仍由数据库领取保证互斥。 */
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskScheduler organizationSyncTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("organization-sync-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false); return scheduler;
    }
    /** 运维可停止自动轮询并保留所有持久批次。 */
    @Bean
    @ConditionalOnProperty(name = "agentflow.organization-sync.worker-enabled", havingValue = "true", matchIfMissing = true)
    public Poller organizationSyncPoller(OrganizationSyncWorker worker) { return new Poller(worker); }

    /**
     * 固定延时避免重入，测试可直接驱动相同工作器。
     * @author owlzhangfq@gmail.com
     */
    public static final class Poller {
        private final OrganizationSyncWorker worker;
        private Poller(OrganizationSyncWorker worker) { this.worker = worker; }
        /** 只执行持久队列，不定时隐式发起新的来源读取。 */
        @Scheduled(fixedDelayString = "${agentflow.organization-sync.poll-delay-ms:1000}", scheduler = "organizationSyncTaskScheduler")
        public void poll() { worker.poll(); }
    }
}
