package io.agentflow.notification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 外发轮询默认关闭，部署方配置收件绑定和服务器后显式启用。
 * @author owlzhangfq@gmail.com
 */
@Component
@ConditionalOnProperty(name = "agentflow.notifications.delivery-worker-enabled", havingValue = "true")
public class NotificationDeliveryScheduler {
    private final NotificationDeliveryWorker worker;

    /** 调度与发送分开，发送器通过事务代理禁止持有调用方事务。 */
    public NotificationDeliveryScheduler(NotificationDeliveryWorker worker) { this.worker = worker; }

    /** 按有限批次轮询新消息、明确临时失败和失效租约。 */
    @Scheduled(fixedDelayString = "${agentflow.notifications.poll-delay-ms:1000}")
    public void poll() { worker.runOnce(); }
}
