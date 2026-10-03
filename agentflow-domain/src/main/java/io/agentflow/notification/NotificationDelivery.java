package io.agentflow.notification;

import java.time.Instant;
import java.util.UUID;

/** 原收件人、同意代次和目的地身份一经排队固定，不包含业务正文或外部账号。
 * @author owlzhangfq@gmail.com
 */
public record NotificationDelivery(UUID id, String tenantId, String recipient, UUID inboxId,
                                   NotificationChannel channel, long consentGeneration,
                                   String bindingId, String destinationDigest, Instant createdAt,
                                   NotificationDeliveryProgress progress) {
    /** 状态变化保留原始投递身份和消息来源。 */
    public NotificationDelivery withProgress(NotificationDeliveryProgress next) {
        return new NotificationDelivery(id, tenantId, recipient, inboxId, channel, consentGeneration,
                bindingId, destinationDigest, createdAt, next);
    }
}
