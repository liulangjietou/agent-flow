package io.agentflow.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;

/**
 * 领取和确认使用短事务，HTTP 在两次事务之间执行；崩溃由持久化租约恢复。
 * @author owlzhangfq@gmail.com
 */
@Component
@ConditionalOnProperty(name = "agentflow.webhooks.worker-enabled", havingValue = "true", matchIfMissing = true)
public class WebhookWorker {
    private static final Logger LOG = LoggerFactory.getLogger(WebhookWorker.class);
    private final JdbcWebhookStore store;
    private final WebhookTargets targets;
    private final WebhookTransport transport;

    /** worker 通过独立存储 bean 的事务代理领取与确认。 */
    public WebhookWorker(JdbcWebhookStore store, WebhookTargets targets, WebhookTransport transport) {
        this.store = store; this.targets = targets; this.transport = transport;
    }

    /** 每批最多十条，单条异常不阻塞其他投递；日志不输出远端响应或密钥。 */
    @Scheduled(fixedDelayString = "${agentflow.webhooks.poll-delay-ms:1000}")
    public void poll() {
        for (var id : store.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                var delivery = store.claim(id, Instant.now());
                if (delivery == null || delivery.progress().status() != DeliveryProgress.Status.IN_FLIGHT) continue;
                var target = targets.find(delivery.tenantId(), delivery.targetId()).orElse(null);
                DeliveryProgress.Outcome outcome;
                if (target == null || !target.enabled()) outcome = DeliveryProgress.Outcome.failed("TARGET_UNAVAILABLE", false);
                else if (!target.digest().equals(delivery.destinationDigest())) outcome = DeliveryProgress.Outcome.failed("TARGET_CHANGED", false);
                else outcome = transport.send(target, delivery.eventId(), delivery.body());
                store.finish(delivery, outcome, Instant.now());
            } catch (RuntimeException failure) {
                // 未确认结果保留租约，由后续领取标记为未知；异常内容可能含部署地址，禁止直接记录。
                LOG.error("Webhook delivery failed, errorCode={}, deliveryId={}", "WORKER_FAILURE", id);
            }
        }
    }
}
