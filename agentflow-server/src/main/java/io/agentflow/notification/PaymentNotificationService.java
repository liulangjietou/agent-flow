package io.agentflow.notification;

import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.finance.PaymentOperationChanged;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原付款状态投影为站内事实和外发意向；所有保存加入原事务，外部发送仍由通知后台执行。
 * @author owlzhangfq@gmail.com
 */
@Service
public class PaymentNotificationService {
    private static final String SYSTEM_ACTOR = "system:payments";
    private final InboxRepository inbox;
    private final ApplicationRepository applications;
    private final PaymentNotificationAccess access;

    /** 通知负责接收人和最小内容，付款与结算状态继续由原聚合负责。 */
    public PaymentNotificationService(InboxRepository inbox, ApplicationRepository applications, PaymentNotificationAccess access) {
        this.inbox = inbox; this.applications = applications; this.access = access;
    }

    /** 重复回调、相同事实的更高查询版本和事件重放共用稳定事件键。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(PaymentOperationChanged event) {
        var current = event.current(); var notice = PaymentNotice.from(current).orElse(null);
        if (notice == null) return;
        var command = current.input().command(); var binding = command.binding();
        var application = applications.findById(command.tenantId(), binding.applicationId()).orElseThrow();
        String eventKey = notice.eventKey(command.id());
        for (String recipient : List.of(command.payee().employeeId(), command.authorization().authorizedBy(), command.authorization().executedBy())) {
            if (!access.eligible(command.tenantId(), recipient, command.payee().employeeId(), command.payee().legalEntityId())) continue;
            UUID id = UUID.nameUUIDFromBytes((command.tenantId() + ":" + eventKey + ":" + recipient).getBytes(StandardCharsets.UTF_8));
            inbox.append(eventKey, new InboxMessage(id, command.tenantId(), recipient, binding.applicationId(), notice.title(),
                    application.businessNo(), notice.kind(), SYSTEM_ACTOR, null, null, binding.roundNo(), current.updatedAt(), null, notice.content()));
        }
    }
}
