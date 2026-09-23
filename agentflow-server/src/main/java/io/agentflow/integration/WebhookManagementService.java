package io.agentflow.integration;

import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;

/**
 * 人工重试编排部署目的地核对与持久化，投递自身状态由领域对象判断。
 * @author owlzhangfq@gmail.com
 */
@Service
public class WebhookManagementService {
    private final WebhookTargets targets;
    private final JdbcWebhookStore store;

    /** 注入当前部署目录与投递存储。 */
    public WebhookManagementService(WebhookTargets targets, JdbcWebhookStore store) {
        this.targets = targets; this.store = store;
    }

    /** 入口完成管理员校验后，按其认证租户重新排队并追加操作记录。 */
    @Transactional
    public JdbcWebhookStore.Summary retry(Actor actor, UUID id, long expectedVersion) {
        var delivery = store.get(actor.tenantId(), id);
        targets.find(actor.tenantId(), delivery.targetId()).filter(WebhookTargets.Destination::enabled)
                .filter(value -> value.digest().equals(delivery.destinationDigest()))
                .orElseThrow(() -> new DomainException("WEBHOOK_TARGET_UNAVAILABLE", "Webhook target is unavailable or has changed"));
        return store.retry(delivery, expectedVersion, actor.userId(), Instant.now()).summary();
    }
}
