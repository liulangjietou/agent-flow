package io.agentflow.agent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 办理循环使用独立有界线程，不能阻塞审批期限或其他模型助手。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@ConditionalOnProperty(name = "agentflow.assist.enabled", havingValue = "true")
public class ExpenseAgentScheduling {
    /** 可独立停用领取，原授权及原任务记录仍保留。 */
    @Bean
    @ConditionalOnProperty(name = "agentflow.expense-agent.worker-enabled", havingValue = "true", matchIfMissing = true)
    public Poller expenseAgentPoller(ExpenseAgentWorker worker) { return new Poller(worker); }
    /**
     * 明确调度器，长模型请求不会占用公共调度线程。
     * @author owlzhangfq@gmail.com
     */
    public static final class Poller {
        private final ExpenseAgentWorker worker;
        private Poller(ExpenseAgentWorker worker) { this.worker = worker; }
        /** 固定延时避免同一实例重入。 */
        @Scheduled(fixedDelayString = "${agentflow.expense-agent.poll-delay-ms:1000}")
        public void poll() { worker.poll(); }
    }
}
