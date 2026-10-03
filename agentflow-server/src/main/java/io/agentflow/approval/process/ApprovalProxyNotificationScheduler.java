package io.agentflow.approval.process;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 有界扫描已生效代理；每条通知独立提交，重启后按持久来源去重。
 * @author owlzhangfq@gmail.com
 */
@Component
@ConditionalOnProperty(name = "agentflow.notifications.proxy-reminders-enabled", havingValue = "true", matchIfMissing = true)
public class ApprovalProxyNotificationScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(ApprovalProxyNotificationScheduler.class);
    private final FlowableApprovalProxyNotifications notifications;
    private FlowableApprovalProxyNotifications.Candidate after;

    /** 扫描没有业务锁，单条处理通过应用服务的事务代理执行。 */
    public ApprovalProxyNotificationScheduler(FlowableApprovalProxyNotifications notifications) { this.notifications = notifications; }

    /** 失败不阻塞后续候选，遍历结束回到开头以重试失败或刚开始的代理。 */
    @Scheduled(initialDelayString = "${agentflow.notifications.proxy-reminder-delay-ms:30000}", fixedDelayString = "${agentflow.notifications.proxy-reminder-delay-ms:30000}")
    public void poll() {
        try {
            var candidates = notifications.candidates(Instant.now(), after);
            for (var candidate : candidates) {
                try { notifications.pending(candidate); }
                catch (RuntimeException failure) {
                    LOG.error("Approval proxy notification failed, errorCode={}, taskId={}, proxyId={}",
                            "APPROVAL_PROXY_NOTIFICATION_FAILED", candidate.taskId(), candidate.proxyId(), failure);
                }
            }
            after = candidates.size() == FlowableApprovalProxyNotifications.BATCH_SIZE ? candidates.get(candidates.size() - 1) : null;
        } catch (RuntimeException failure) {
            LOG.error("Approval proxy notification scan failed, errorCode={}", "APPROVAL_PROXY_NOTIFICATION_SCAN_FAILED", failure);
        }
    }
}
