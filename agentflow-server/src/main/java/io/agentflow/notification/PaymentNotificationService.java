package io.agentflow.notification;

import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.finance.PaymentOperationChanged;
import io.agentflow.finance.PaymentExecutionRequestChanged;
import io.agentflow.finance.JdbcPaymentAuthorizationRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
    private final JdbcPaymentAuthorizationRepository authorizations;

    /** 通知负责接收人和最小内容，付款与结算状态继续由原聚合负责。 */
    public PaymentNotificationService(InboxRepository inbox, ApplicationRepository applications, PaymentNotificationAccess access,
                                      JdbcPaymentAuthorizationRepository authorizations) {
        this.inbox = inbox; this.applications = applications; this.access = access; this.authorizations = authorizations;
    }

    /** 重复回调、相同事实的更高查询版本和事件重放共用稳定事件键。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(PaymentOperationChanged event) {
        var current = event.current(); var notice = PaymentNotice.from(current).orElse(null);
        if (notice == null) return;
        var command = current.input().command();
        append(command.tenantId(), command.id(), command.authorization().executedBy(), notice, current.updatedAt());
    }

    /** 登记前账户检查只产生异常提示，与其后同一授权的同类事实共用去重身份。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(PaymentExecutionRequestChanged event) {
        var current = event.current(); var notice = PaymentNotice.from(current).orElse(null);
        if (notice == null) return;
        var input = current.input();
        append(input.tenantId(), input.authorizationId(), input.cashier(), notice, current.updatedAt());
    }

    private void append(String tenant, UUID authorizationId, String cashier, PaymentNotice notice, Instant at) {
        var authorization = authorizations.find(tenant, authorizationId).orElseThrow();
        var terms = authorization.terms(); var binding = terms.binding(); var payee = terms.payee();
        var application = applications.findById(tenant, binding.applicationId()).orElseThrow();
        String eventKey = notice.eventKey(authorizationId);
        for (String recipient : List.of(payee.employeeId(), authorization.decision().authorizedBy(), cashier)) {
            if (!access.eligible(tenant, recipient, payee.employeeId(), payee.legalEntityId())) continue;
            UUID id = UUID.nameUUIDFromBytes((tenant + ":" + eventKey + ":" + recipient).getBytes(StandardCharsets.UTF_8));
            inbox.append(eventKey, new InboxMessage(id, tenant, recipient, binding.applicationId(), notice.title(),
                    application.businessNo(), notice.kind(), SYSTEM_ACTOR, null, null, binding.roundNo(), at, null, notice.content()));
        }
    }
}
