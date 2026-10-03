package io.agentflow.notification;

import io.agentflow.procurement.SupplierAdjustmentChanged;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原应付调整变化与通知共用事务，自动退避和重复回执不刷同类消息。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierAdjustmentNotificationService {
    private static final String SYSTEM_ACTOR = "system:supplier-adjustments";
    private final InboxRepository inbox;
    private final SupplierAdjustmentNotificationAccess access;
    /** 原参与关系由持久准备和实际结束决定确定。 */
    public SupplierAdjustmentNotificationService(InboxRepository inbox, SupplierAdjustmentNotificationAccess access) { this.inbox = inbox; this.access = access; }
    /** 排队与正常准备读取保持安静，持久异常才生成事实。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void prepared(SupplierAdjustmentChanged.Preparation event) {
        var current = event.current(); var fact = SupplierAdjustmentNotice.from(current).orElse(null); if (fact == null) return;
        var tenant = current.input().source().returns().request().command().tenantId(); var source = access.original(tenant, current.input().id(), fact);
        if (source != null && source.preparation().equals(current)) append(tenant, source, fact, current.updatedAt());
    }
    /** ERP 实际结果先提交并通知；本地完成由之后的独立事务提供证明。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void operated(SupplierAdjustmentChanged.Operation event) {
        var current = event.current(); var fact = SupplierAdjustmentNotice.from(current).orElse(null); if (fact == null) return;
        var tenant = current.command().tenantId(); var source = access.original(tenant, current.command().id(), fact);
        // 已经完成后的原号重查不能向恢复任职的人员补发过时的中间结果。
        if (source == null || !current.equals(source.operation()) || fact == SupplierAdjustmentNotice.ERP_ADJUSTED && source.completion() != null) return;
        append(tenant, source, fact, current.updatedAt());
    }
    /** 实际完成表、资金分录、账本与占用都已落库才可通知完成。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void completed(SupplierAdjustmentChanged.Completed event) {
        var source = access.original(event.tenantId(), event.completion().operation().command().id(), SupplierAdjustmentNotice.COMPLETED);
        if (source != null && event.completion().equals(source.completion())) append(event.tenantId(), source, SupplierAdjustmentNotice.COMPLETED, event.completion().completedAt());
    }
    /** 原状态已经拒绝时仍需实际结束事件，不能依赖状态变化猜测释放。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void retired(SupplierAdjustmentChanged.Retired event) {
        var source = access.original(event.tenantId(), event.retirement().operationId(), SupplierAdjustmentNotice.RETIRED);
        if (source != null && event.retirement().equals(source.retirement())) append(event.tenantId(), source, SupplierAdjustmentNotice.RETIRED, event.retirement().retiredAt());
    }
    private void append(String tenant, SupplierAdjustmentNotificationAccess.Source source, SupplierAdjustmentNotice fact, Instant now) {
        var app = source.application(); var input = source.preparation().input(); var round = input.source().returns().request().command().holdCommand().authorization().source().reservation().source().round().roundNo();
        String key = fact.eventKey(input.id());
        for (String recipient : source.recipients()) {
            if (!access.eligible(tenant, recipient, source)) continue;
            UUID id = UUID.nameUUIDFromBytes((tenant + ":" + key + ":" + recipient).getBytes(StandardCharsets.UTF_8));
            inbox.append(key, new InboxMessage(id, tenant, recipient, app.id(), fact.title(), app.businessNo(), fact.kind(), SYSTEM_ACTOR, null, null, round, now, null, fact.content()));
        }
    }
}
