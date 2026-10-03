package io.agentflow.notification;

import io.agentflow.expense.ExpenseSettlementChanged;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 只通知原申请人和可确证的原财务参与人，结算与消息使用同一事务。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseSettlementNotificationService {
    private static final String SYSTEM_ACTOR = "system:expense-settlements";
    private final InboxRepository inbox;
    private final ExpenseSettlementNotificationAccess access;
    /** 原关系由持久财务来源确定，不枚举当前角色猜测接收人。 */
    public ExpenseSettlementNotificationService(InboxRepository inbox, ExpenseSettlementNotificationAccess access) { this.inbox = inbox; this.access = access; }
    /** 同修订重复事件不会重复通知；后续恢复与再次阻塞保留各自事实。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(ExpenseSettlementChanged event) {
        var current = event.current(); var notice = ExpenseSettlementNotice.from(current).orElse(null); if (notice == null) return;
        var binding = current.input().source(); var source = access.original(binding.tenantId(), binding.businessId(), current.version());
        if (source == null) return;
        var application = source.application(); String key = notice.eventKey(binding.businessId(), current.version());
        for (String recipient : source.recipients()) {
            if (!access.eligible(binding.tenantId(), recipient, source)) continue;
            UUID id = UUID.nameUUIDFromBytes((binding.tenantId() + ":" + key + ":" + recipient).getBytes(StandardCharsets.UTF_8));
            inbox.append(key, new InboxMessage(id, binding.tenantId(), recipient, application.id(), notice.title(), application.businessNo(), notice.kind(), SYSTEM_ACTOR,
                    null, null, binding.roundNo(), current.updatedAt(), null, notice.content()));
        }
    }
}
