package io.agentflow.notification;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static io.agentflow.notification.NotificationDeliveryProgress.*;

/** 领取和确认各自使用短事务，渠道发送在两次事务之间执行。
 * @author owlzhangfq@gmail.com
 */
@Component
public class NotificationDeliveryWorker {
    private static final Logger LOG = LoggerFactory.getLogger(NotificationDeliveryWorker.class);
    private final JdbcNotificationDeliveryStore store;
    private final SmtpNotificationTransport smtp;
    private final WeComNotificationTransport wecom;
    private final NotificationDeliveryService deliveries;

    /** 通过独立 bean 的事务代理执行领取和确认。 */
    public NotificationDeliveryWorker(JdbcNotificationDeliveryStore store, SmtpNotificationTransport smtp,
                                      WeComNotificationTransport wecom, NotificationDeliveryService deliveries) {
        this.store = store; this.smtp = smtp; this.wecom = wecom; this.deliveries = deliveries;
    }

    /** 一批最多十条；崩溃后租约变为未知，不能因为未保存回执而自动重复发送。 */
    @Transactional(propagation = Propagation.NEVER)
    public void runOnce() {
        for (var id : store.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                var claimed = deliveries.claim(id, Instant.now());
                if (claimed == null) continue;
                var outcome = switch (claimed.delivery().channel()) {
                    case EMAIL -> smtp.send(claimed.destination(), claimed.delivery());
                    case ENTERPRISE_IM -> wecom.send(claimed.destination());
                };
                deliveries.finish(claimed.delivery(), outcome, Instant.now());
            } catch (RuntimeException failure) {
                // 异常可能包含收件账号或连接凭据，仅记录内部投递标识和固定错误码。
                LOG.error("Notification delivery failed, errorCode={}, deliveryId={}", "NOTIFICATION_WORKER_FAILURE", id);
            }
        }
    }
}
