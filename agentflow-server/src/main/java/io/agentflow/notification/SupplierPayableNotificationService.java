package io.agentflow.notification;

import io.agentflow.procurement.SupplierPayableChanged;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原应付事实、站内消息与投递意向共享事务，重复与迟到事件不触发新的财务动作。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierPayableNotificationService {
    private static final String SYSTEM_ACTOR = "system:supplier-payables";
    private final InboxRepository inbox;
    private final SupplierPayableNotificationAccess access;
    /** 实际参与人和原修订由受控来源统一提供。 */
    public SupplierPayableNotificationService(InboxRepository inbox, SupplierPayableNotificationAccess access) { this.inbox = inbox; this.access = access; }
    /** 复核只向发起该次读取的财务提醒，不向其他财务或申请人分享待授权证据。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(SupplierPayableChanged.Review event) {
        var current = event.current(); var notice = SupplierPayableNotice.from(current).orElse(null); if (notice == null) return;
        var key = new SupplierPayableNotice.Source(notice.sourceType(), current.input().id(), notice);
        var source = access.original(current.input().source().reservation().source().tenantId(), key);
        if (source != null && current.equals(source.review())) append(source, key, current.updatedAt());
    }
    /** 只有实际结束记录能生成结束消息，普通发送停止不冒充具名财务决定。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(SupplierPayableChanged.Hold event) {
        var current = event.current(); var notice = event.retirement() == null ? SupplierPayableNotice.from(current).orElse(null) : SupplierPayableNotice.RETIRED;
        if (notice == null) return;
        var key = new SupplierPayableNotice.Source(notice.sourceType(), current.command().id(), notice);
        var source = access.original(current.command().tenantId(), key);
        if (source == null || !current.equals(source.operation()) || !Objects.equals(event.retirement(), source.retirement())) return;
        append(source, key, event.retirement() == null ? current.updatedAt() : event.retirement().retiredAt());
    }
    private void append(SupplierPayableNotificationAccess.Source source, SupplierPayableNotice.Source key, Instant at) {
        var original = source.approved().reservation().source(); var app = source.application(); var notice = key.notice(); String event = notice.eventKey(key.id());
        for (String recipient : source.recipients()) {
            if (!access.eligible(original.tenantId(), recipient, source)) continue;
            UUID id = UUID.nameUUIDFromBytes((original.tenantId() + ":" + event + ":" + recipient).getBytes(StandardCharsets.UTF_8));
            inbox.append(event, new InboxMessage(id, original.tenantId(), recipient, app.id(), notice.title(), app.businessNo(), notice.kind(), SYSTEM_ACTOR,
                    null, null, original.round().roundNo(), at, null, notice.content()));
        }
    }
}
