package io.agentflow.notification;

import io.agentflow.finance.VoucherOperationChanged;
import io.agentflow.finance.VoucherPreparationChanged;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原会计状态、最小站内提示和外发意向加入同一事务，不在业务事务中发送通知。
 * @author owlzhangfq@gmail.com
 */
@Service
public class VoucherNotificationService {
    private static final String SYSTEM_ACTOR = "system:vouchers";
    private final InboxRepository inbox;
    private final VoucherNotificationAccess access;
    /** 凭证聚合管理会计事实，通知层管理接收关系和去重。 */
    public VoucherNotificationService(InboxRepository inbox, VoucherNotificationAccess access) { this.inbox = inbox; this.access = access; }

    /** 准备失败与实际过账结果分别表达，不能把没有命令说成过账失败。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(VoucherPreparationChanged event) {
        var current = event.current();
        VoucherNotice.from(current).ifPresent(notice -> append(current.input().source().tenantId(), current.input().id(), notice, current.completedAt()));
    }

    /** 状态恢复、对账与重复查询共用原编号事实键，不重复提示相同事实。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(VoucherOperationChanged event) {
        var current = event.current();
        VoucherNotice.from(current).ifPresent(notice -> append(current.input().command().tenantId(), current.input().command().id(), notice, current.updatedAt()));
    }

    private void append(String tenant, UUID originalId, VoucherNotice notice, Instant at) {
        var source = access.original(tenant, originalId);
        if (source == null) return;
        var application = source.application(); String eventKey = notice.eventKey(originalId);
        for (String recipient : source.recipients()) {
            if (!access.eligible(tenant, recipient, source)) continue;
            UUID id = UUID.nameUUIDFromBytes((tenant + ":" + eventKey + ":" + recipient).getBytes(StandardCharsets.UTF_8));
            inbox.append(eventKey, new InboxMessage(id, tenant, recipient, application.id(), notice.title(), application.businessNo(), notice.kind(),
                    SYSTEM_ACTOR, null, null, source.roundNo(), at, null, notice.content()));
        }
    }
}
