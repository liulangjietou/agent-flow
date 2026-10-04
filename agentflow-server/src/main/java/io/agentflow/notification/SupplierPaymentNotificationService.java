package io.agentflow.notification;

import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.procurement.JdbcSupplierPaymentAuthorizationRepository;
import io.agentflow.procurement.JdbcSupplierPaymentExecutionRepository;
import io.agentflow.procurement.SupplierPaymentAuthorization;
import io.agentflow.procurement.SupplierPaymentChanged;
import io.agentflow.procurement.SupplierPaymentExecutionChanged;
import io.agentflow.procurement.SupplierPaymentExecutionRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原供应商付款事件投影为最小通知，原财务状态、消息和外发意向同事务提交。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierPaymentNotificationService {
    private static final String SYSTEM_ACTOR = "system";
    private final InboxRepository inbox;
    private final ApplicationRepository applications;
    private final JdbcSupplierPaymentAuthorizationRepository authorizations;
    private final JdbcSupplierPaymentExecutionRepository requests;
    private final SupplierPaymentNotificationAccess access;

    /** 金融事实由原聚合决定，通知层只处理当前接收关系及固定原记录定位。 */
    public SupplierPaymentNotificationService(InboxRepository inbox, ApplicationRepository applications, JdbcSupplierPaymentAuthorizationRepository authorizations,
            JdbcSupplierPaymentExecutionRepository requests, SupplierPaymentNotificationAccess access) {
        this.inbox = inbox; this.applications = applications; this.authorizations = authorizations; this.requests = requests; this.access = access;
    }
    /** 银行通知绑定实际建立该命令的原请求，不从最近选择推断出纳。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(SupplierPaymentChanged event) {
        var payment = event.current(); var notice = SupplierPaymentNotice.from(payment).orElse(null); if (notice == null) return;
        var command = payment.command(); var request = requests.registered(command.tenantId(), command.id()).orElseThrow();
        append(command.holdCommand().authorization(), request, notice, payment.updatedAt());
    }
    /** 登记前异常与同一原选择后续银行检查的同类事实共用稳定事件键。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(SupplierPaymentExecutionChanged event) {
        var request = event.current(); var notice = SupplierPaymentNotice.from(request).orElse(null); if (notice == null) return;
        var authorization = authorizations.find(request.input().tenantId(), request.input().authorizationId()).orElseThrow();
        append(authorization, request, notice, request.updatedAt());
    }
    private void append(SupplierPaymentAuthorization authorization, SupplierPaymentExecutionRequest request, SupplierPaymentNotice notice, Instant at) {
        var source = authorization.source().reservation().source(); var tenant = source.tenantId();
        var application = applications.findById(tenant, source.applicationId()).orElseThrow(); var eventKey = notice.eventKey(authorization.id(), request.input().id());
        for (String recipient : List.of(source.employeeId(), authorization.authorizedBy(), request.input().cashier())) {
            if (!access.eligible(tenant, recipient, source.employeeId(), source.round().content().legalEntityId())) continue;
            var id = UUID.nameUUIDFromBytes((tenant + ":" + eventKey + ":" + recipient).getBytes(StandardCharsets.UTF_8));
            inbox.append(eventKey, new InboxMessage(id, tenant, recipient, source.applicationId(), notice.title(), application.businessNo(), notice.kind(),
                    SYSTEM_ACTOR, null, null, source.round().roundNo(), at, null, notice.content()));
        }
    }
}
