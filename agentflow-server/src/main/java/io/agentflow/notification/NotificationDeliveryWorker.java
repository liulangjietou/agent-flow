package io.agentflow.notification;

import io.agentflow.observability.DiagnosticContext;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private static final String TRACE_SOURCE = "notification-delivery";
    private final JdbcNotificationDeliveryStore store;
    private final SmtpNotificationTransport smtp;
    private final WeComNotificationTransport wecom;
    private final FeishuNotificationTransport feishu;
    private final DingTalkNotificationTransport dingtalk;
    private final NotificationDeliveryService deliveries;

    /** 兼容既有测试构造器。 */
    public NotificationDeliveryWorker(JdbcNotificationDeliveryStore store, SmtpNotificationTransport smtp,
                                      WeComNotificationTransport wecom, NotificationDeliveryService deliveries) {
        this(store, smtp, wecom, null, null, deliveries);
    }

    /** 通过独立 bean 的事务代理执行领取和确认，支持企微、飞书与钉钉企业 IM 发送器。 */
    @Autowired
    public NotificationDeliveryWorker(JdbcNotificationDeliveryStore store, SmtpNotificationTransport smtp,
                                      WeComNotificationTransport wecom, FeishuNotificationTransport feishu,
                                      DingTalkNotificationTransport dingtalk, NotificationDeliveryService deliveries) {
        this.store = store; this.smtp = smtp; this.wecom = wecom;
        this.feishu = feishu; this.dingtalk = dingtalk; this.deliveries = deliveries;
    }

    /** 一批最多十条；崩溃后租约变为未知，不能因为未保存回执而自动重复发送。 */
    @Transactional(propagation = Propagation.NEVER)
    public void runOnce() { runBatch(); }

    /** 执行单批投递并返回扫描到的到期任务数，供调度器判断是否进入空转退避。 */
    @Transactional(propagation = Propagation.NEVER)
    public int runBatch() {
        var candidates = store.due(Instant.now());
        for (var candidate : candidates) {
            if (Thread.currentThread().isInterrupted()) return candidates.size();
            try (var trace = DiagnosticContext.restored(candidate.traceId(), candidate.tenantId(), TRACE_SOURCE, candidate.id().toString())
                    .withBusiness(candidate.businessNo(), candidate.processInstanceId(), candidate.taskId()).open()) {
                try {
                    var claimed = deliveries.claim(candidate.id(), Instant.now());
                    if (claimed == null) continue;
                    LOG.info("Integration execution started, errorCode={}, source={}, operationId={}", "NONE", TRACE_SOURCE, candidate.id());
                    var outcome = switch (claimed.delivery().channel()) {
                        case EMAIL -> smtp.send(claimed.destination(), claimed.delivery());
                        case ENTERPRISE_IM -> sendEnterpriseIm(claimed.destination());
                    };
                    deliveries.finish(claimed.delivery(), outcome, Instant.now());
                } catch (RuntimeException failure) {
                    // 异常可能包含收件账号或连接凭据，仅记录内部投递标识和固定错误码。
                    LOG.error("Notification delivery failed, errorCode={}, deliveryId={}", "NOTIFICATION_WORKER_FAILURE", candidate.id());
                }
            }
        }
        return candidates.size();
    }

    private Outcome sendEnterpriseIm(NotificationDestinations.Destination destination) {
        if (destination.feishuApp() != null && feishu != null) return feishu.send(destination);
        if (destination.dingtalkApp() != null && dingtalk != null) return dingtalk.send(destination);
        return wecom.send(destination);
    }
}
