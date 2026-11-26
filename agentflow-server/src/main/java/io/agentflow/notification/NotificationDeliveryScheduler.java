package io.agentflow.notification;

import io.agentflow.config.PollingBackoffRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 外发轮询默认关闭，部署方配置收件绑定和服务器后显式启用；空闲时按退避策略降频，新消息入队后即时唤醒。
 * @author owlzhangfq@gmail.com
 */
@Component
@ConditionalOnProperty(name = "agentflow.notifications.delivery-worker-enabled", havingValue = "true")
public class NotificationDeliveryScheduler {
    static final String CHANNEL = "notification-delivery";
    private final NotificationDeliveryWorker worker;
    private final PollingBackoffRegistry backoff;

    /** 兼容单测直接构造。 */
    public NotificationDeliveryScheduler(NotificationDeliveryWorker worker) { this(worker, null); }

    /** 调度与发送分开，发送器通过事务代理禁止持有调用方事务。 */
    @Autowired
    public NotificationDeliveryScheduler(NotificationDeliveryWorker worker, ObjectProvider<PollingBackoffRegistry> backoffProvider) {
        this.worker = worker;
        this.backoff = backoffProvider == null ? null : backoffProvider.getIfAvailable();
    }

    /** 按有限批次轮询新消息、明确临时失败和失效租约。 */
    @Scheduled(fixedDelayString = "${agentflow.notifications.poll-delay-ms:1000}")
    public void poll() {
        if (backoff != null && !backoff.shouldPoll(CHANNEL)) return;
        int processed = worker.runBatch();
        if (backoff != null) backoff.record(CHANNEL, processed > 0);
    }
}
