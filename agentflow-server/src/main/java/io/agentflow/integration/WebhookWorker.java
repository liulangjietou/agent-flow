package io.agentflow.integration;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.common.JsonUtil;
import io.agentflow.observability.DiagnosticContext;
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
    private final JsonUtil json;

    /** worker 通过独立存储 bean 的事务代理领取与确认。 */
    public WebhookWorker(JdbcWebhookStore store, WebhookTargets targets, WebhookTransport transport, JsonUtil json) {
        this.store = store; this.targets = targets; this.transport = transport; this.json = json;
    }

    /** 每批最多十条，单条异常不阻塞其他投递；日志不输出远端响应或密钥。 */
    @Scheduled(fixedDelayString = "${agentflow.webhooks.poll-delay-ms:1000}")
    public void poll() {
        for (var id : store.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try (var candidate = new DiagnosticContext(DiagnosticContext.legacyId("webhook-delivery", "", id.toString()), null).open()) {
                try {
                    var delivery = store.claim(id, Instant.now());
                    if (delivery != null && delivery.progress().status() == DeliveryProgress.Status.IN_FLIGHT) deliver(delivery);
                } catch (RuntimeException failure) {
                    // 领取失败时尚未读取可信租户；日志不输出底层异常正文。
                    LOG.error("Webhook claim failed, errorCode={}, deliveryId={}", "WORKER_FAILURE", id);
                }
            }
        }
    }

    private void deliver(JdbcWebhookStore.Delivery delivery) {
        try (var scope = context(delivery).open()) {
            try {
                var target = targets.find(delivery.tenantId(), delivery.targetId()).orElse(null);
                DeliveryProgress.Outcome outcome;
                if (target == null || !target.enabled()) outcome = DeliveryProgress.Outcome.failed("TARGET_UNAVAILABLE", false);
                else if (!target.digest().equals(delivery.destinationDigest())) outcome = DeliveryProgress.Outcome.failed("TARGET_CHANGED", false);
                else outcome = transport.send(target, delivery.eventId(), delivery.body());
                boolean recorded = store.finish(delivery, outcome, Instant.now());
                LOG.info("Webhook attempt completed, errorCode={}, deliveryId={}, eventId={}, attempt={}, recorded={}",
                        outcome.errorCode() == null ? "NONE" : outcome.errorCode(), delivery.id(), delivery.eventId(), delivery.progress().attempts(), recorded);
            } catch (RuntimeException failure) {
                // 未确认结果保留租约；在原上下文内记录分类，禁止输出正文和密钥。
                LOG.error("Webhook delivery failed, errorCode={}, deliveryId={}", "WORKER_FAILURE", delivery.id());
            }
        }
    }

    private DiagnosticContext context(JdbcWebhookStore.Delivery delivery) {
        String trace = null;
        Integer originalRound = null;
        String task = null;
        try {
            var envelope = json.read(delivery.body(), JsonNode.class);
            trace = envelope.path("traceId").asText();
            var payload = envelope.path("payload");
            var round = payload.path("roundNo");
            // 只有匹配持久化租户和申请的原信封，才能提供轮次及任务事实。
            if (delivery.tenantId().equals(envelope.path("tenantId").asText())
                    && delivery.applicationId().toString().equals(payload.path("applicationId").asText())
                    && round.isIntegralNumber() && round.canConvertToInt() && round.intValue() > 0) {
                originalRound = round.intValue();
                if (payload.path("taskId").isTextual()) task = payload.path("taskId").textValue();
            }
        }
        catch (RuntimeException ignored) { /* 旧记录没有可读追踪字段时，只补诊断关联，不重建原签名正文。 */ }
        if (!DiagnosticContext.validTrace(trace)) trace = DiagnosticContext.legacyId("webhook-event", delivery.tenantId(), delivery.eventId());
        // 租户只信任已持久化索引，不能从事件正文覆盖授权范围。
        var context = new DiagnosticContext(trace, delivery.tenantId());
        var business = store.businessContext(delivery, originalRound).orElse(null);
        return business == null ? context : context.withBusiness(business.businessNo(), business.processInstanceId(), task);
    }
}
