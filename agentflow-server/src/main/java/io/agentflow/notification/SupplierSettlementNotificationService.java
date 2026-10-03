package io.agentflow.notification;

import io.agentflow.procurement.SupplierSettlementChanged;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原结算变化与通知共用事务，自动退避和重复回执不刷同类消息。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierSettlementNotificationService {
    private static final String SYSTEM_ACTOR = "system:supplier-settlements";
    private final InboxRepository inbox;
    private final SupplierSettlementNotificationAccess access;
    /** 原参与关系由持久准备和实际结束决定确定。 */
    public SupplierSettlementNotificationService(InboxRepository inbox, SupplierSettlementNotificationAccess access) { this.inbox = inbox; this.access = access; }
    /** 排队与正常准备读取保持安静，持久异常才生成事实。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void prepared(SupplierSettlementChanged.Preparation event) {
        var current = event.current(); var fact = SupplierSettlementNotice.from(current).orElse(null); if (fact == null) return;
        var tenant = current.input().payment().command().tenantId(); var source = access.original(tenant, current.input().id(), fact);
        if (source != null && source.preparation().equals(current)) append(tenant, source, fact, current.updatedAt());
    }
    /** 同事务已完成本地占用时只发完成事实，避免把中间 ERP 成功再发一次。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void operated(SupplierSettlementChanged.Operation event) {
        var current = event.current(); var fact = SupplierSettlementNotice.from(current).orElse(null); if (fact == null) return;
        var tenant = current.command().tenantId(); var source = access.original(tenant, current.command().id(), fact);
        if (source == null || !current.equals(source.operation()) || fact == SupplierSettlementNotice.ERP_SETTLED && source.completion() != null) return;
        append(tenant, source, fact, current.updatedAt());
    }
    /** 实际完成表和原占用第二版都已落库才可通知完成。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void completed(SupplierSettlementChanged.Completed event) {
        var source = access.original(event.tenantId(), event.completion().operationId(), SupplierSettlementNotice.COMPLETED);
        if (source != null && event.completion().equals(source.completion())) append(event.tenantId(), source, SupplierSettlementNotice.COMPLETED, event.completion().completedAt());
    }
    /** 原状态已经拒绝时仍需实际结束事件，不能依赖状态变化猜测释放。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void retired(SupplierSettlementChanged.Retired event) {
        var source = access.original(event.tenantId(), event.retirement().operationId(), SupplierSettlementNotice.RETIRED);
        if (source != null && event.retirement().equals(source.retirement())) append(event.tenantId(), source, SupplierSettlementNotice.RETIRED, event.retirement().retiredAt());
    }
    private void append(String tenant, SupplierSettlementNotificationAccess.Source source, SupplierSettlementNotice fact, Instant now) {
        var app = source.application(); var input = source.preparation().input(); var round = input.payment().command().holdCommand().authorization().source().reservation().source().round().roundNo();
        String key = fact.eventKey(input.id());
        for (String recipient : source.recipients()) {
            if (!access.eligible(tenant, recipient, source)) continue;
            UUID id = UUID.nameUUIDFromBytes((tenant + ":" + key + ":" + recipient).getBytes(StandardCharsets.UTF_8));
            inbox.append(key, new InboxMessage(id, tenant, recipient, app.id(), fact.title(), app.businessNo(), fact.kind(), SYSTEM_ACTOR, null, null, round, now, null, fact.content()));
        }
    }
}
