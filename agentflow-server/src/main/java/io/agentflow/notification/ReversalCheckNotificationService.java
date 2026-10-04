package io.agentflow.notification;

import io.agentflow.finance.VoucherReversalCheckChanged;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 核对状态、最小站内消息与外发意向共用原事务，不能提前声称人工登记完成。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ReversalCheckNotificationService {
    private static final String SYSTEM_ACTOR = "system:reversal-checks";
    private final InboxRepository inbox;
    private final ReversalCheckNotificationAccess access;
    /** 领域判断财务事实，本层只编排原参与人及通知去重。 */
    public ReversalCheckNotificationService(InboxRepository inbox, ReversalCheckNotificationAccess access) { this.inbox = inbox; this.access = access; }
    /** 旧租约、幂等登记和重复事件都不能为同一核对重复产生同类消息。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(VoucherReversalCheckChanged event) {
        var check = event.current(); var notice = ReversalCheckNotice.from(check).orElse(null); if (notice == null) return;
        var input = check.input(); var source = access.original(input.tenantId(), input.id()); if (source == null) return;
        var application = source.original().application(); String key = notice.eventKey(input.id());
        for (String recipient : source.recipients()) {
            if (!access.eligible(input.tenantId(), recipient, source)) continue;
            UUID id = UUID.nameUUIDFromBytes((input.tenantId() + ":" + key + ":" + recipient).getBytes(StandardCharsets.UTF_8));
            inbox.append(key, new InboxMessage(id, input.tenantId(), recipient, application.id(), notice.title(), application.businessNo(), notice.kind(), SYSTEM_ACTOR,
                    null, null, source.original().roundNo(), check.updatedAt(), null, notice.content()));
        }
    }
}
