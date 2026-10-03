package io.agentflow.agent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 本地 XML 抽取不依赖模型总开关；独立单线程调度仍由持久租约控制跨进程竞争。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "agentflow.invoices.extraction-worker-enabled", havingValue = "true", matchIfMissing = true)
public class InvoiceExtractionScheduling {
    /** 文件解析和模型等待不阻塞审批、通知或其他财务调度。 */
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskScheduler invoiceExtractionTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("invoice-extraction-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false); return scheduler;
    }
    /** 运维可单独关闭轮询，关闭模型不影响本地结构化 XML。 */
    @Bean
    public Poller invoiceExtractionPoller(InvoiceExtractionWorker worker) { return new Poller(worker); }
    /**
     * 自动轮询与可直接测试的持久工作用例分开。
     * @author owlzhangfq@gmail.com
     */
    public static final class Poller {
        private final InvoiceExtractionWorker worker;
        private Poller(InvoiceExtractionWorker worker) { this.worker = worker; }
        /** 固定延时防止同一调度线程重入。 */
        @Scheduled(fixedDelayString = "${agentflow.invoices.extraction-poll-delay-ms:1000}", scheduler = "invoiceExtractionTaskScheduler")
        public void poll() { worker.poll(); }
    }
}
