package io.agentflow.notification;

import io.agentflow.expense.AdvanceDisbursementReturnChanged;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原银行核对和实际登记与最小通知共享事务，金额与资源调整状态不外发。
 * @author owlzhangfq@gmail.com
 */
@Service
public class DisbursementReturnNotificationService {
    private static final String SYSTEM_ACTOR = "system:disbursement-returns";
    private final InboxRepository inbox;
    private final DisbursementReturnNotificationAccess access;
    /** 接收人只来自原申请人和本次实际核对财务。 */
    public DisbursementReturnNotificationService(InboxRepository inbox, DisbursementReturnNotificationAccess access) { this.inbox = inbox; this.access = access; }
    /** 读取完成只通知明确疑点，实际登记有独立事实且重复事件不重复通知。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(AdvanceDisbursementReturnChanged event) {
        var current = event.current(); var notice = DisbursementReturnNotice.from(current).orElse(null); if (notice == null) return;
        var input = current.input(); var source = access.original(input.tenantId(), input.id());
        if (source == null || !source.check().equals(current)) return;
        var app = source.application(); var round = input.request().command().binding().roundNo();
        String key = notice.eventKey(input.id());
        for (String recipient : source.recipients()) {
            if (!access.eligible(input.tenantId(), recipient, source)) continue;
            UUID id = UUID.nameUUIDFromBytes((input.tenantId() + ":" + key + ":" + recipient).getBytes(StandardCharsets.UTF_8));
            inbox.append(key, new InboxMessage(id, input.tenantId(), recipient, app.id(), notice.title(), app.businessNo(), notice.kind(), SYSTEM_ACTOR,
                    null, null, round, current.updatedAt(), null, notice.content()));
        }
    }
}
