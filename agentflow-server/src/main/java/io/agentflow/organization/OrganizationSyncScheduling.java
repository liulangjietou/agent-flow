package io.agentflow.organization;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 组织读取使用独立单线程，网络等待不占用审批期限和其他集成调度器。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@ConditionalOnProperty(name = "agentflow.organization-sync.enabled", havingValue = "true")
public class OrganizationSyncScheduling {
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
        @Scheduled(fixedDelayString = "${agentflow.organization-sync.poll-delay-ms:1000}")
        public void poll() { worker.poll(); }
    }
}
