package io.agentflow.notification;

import io.agentflow.budget.BudgetAdjustmentReviewChanged;
import io.agentflow.budget.BudgetAdjustmentOperationChanged;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原预算事实、最小通知及外发意向共享事务，重复状态和旧事件不生成新的业务动作。
 * @author owlzhangfq@gmail.com
 */
@Service
public class BudgetAdjustmentNotificationService {
    private static final String SYSTEM_ACTOR = "system:budget-adjustments";
    private final InboxRepository inbox;
    private final BudgetAdjustmentNotificationAccess access;
    /** 接收人由原申请人和实际财务参与关系确定。 */
    public BudgetAdjustmentNotificationService(InboxRepository inbox, BudgetAdjustmentNotificationAccess access) { this.inbox = inbox; this.access = access; }
    /** 失败读取保持自身尝试，后来的可用证据不挂到旧消息上。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(BudgetAdjustmentReviewChanged event) {
        var current = event.current(); var notice = BudgetAdjustmentNotice.from(current).orElse(null); if (notice == null) return;
        var key = new BudgetAdjustmentNotice.Source(notice.sourceType(), current.input().id(), notice);
        var source = access.original(current.input().source().tenantId(), key);
        if (source != null && current.equals(source.review())) append(source, key, current.updatedAt());
    }
    /** 安全结束只依据实际决定通知；查询、未知与生效各自保留原事实。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(BudgetAdjustmentOperationChanged event) {
        var current = event.current(); var notice = event.retirement() == null ? BudgetAdjustmentNotice.from(current).orElse(null) : BudgetAdjustmentNotice.RETIRED;
        if (notice == null) return;
        var key = new BudgetAdjustmentNotice.Source(notice.sourceType(), current.command().id(), notice);
        var source = access.original(current.command().tenantId(), key);
        if (source == null || !current.equals(source.operation()) || !java.util.Objects.equals(event.retirement(), source.retirement())) return;
        append(source, key, event.retirement() == null ? current.updatedAt() : event.retirement().retiredAt());
    }
    private void append(BudgetAdjustmentNotificationAccess.Source source, BudgetAdjustmentNotice.Source key, Instant at) {
        var original = source.approved(); var app = source.application(); var notice = key.notice(); String event = notice.eventKey(key.id());
        for (String recipient : source.recipients()) {
            if (!access.eligible(original.tenantId(), recipient, source)) continue;
            UUID id = UUID.nameUUIDFromBytes((original.tenantId() + ":" + event + ":" + recipient).getBytes(StandardCharsets.UTF_8));
            inbox.append(event, new InboxMessage(id, original.tenantId(), recipient, app.id(), notice.title(), app.businessNo(), notice.kind(), SYSTEM_ACTOR,
                    null, null, original.round().roundNo(), at, null, notice.content()));
        }
    }
}
