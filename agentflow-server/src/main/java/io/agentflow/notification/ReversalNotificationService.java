package io.agentflow.notification;

import io.agentflow.finance.VoucherReversalOperationChanged;
import io.agentflow.finance.VoucherReversalPreparationChanged;
import io.agentflow.finance.VoucherReversalRetired;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 独立冲销的最小消息与外发意向加入原事务，通知层不改会计或资金状态。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ReversalNotificationService {
    private static final String SYSTEM_ACTOR = "system:reversals";
    private final InboxRepository inbox;
    private final ReversalNotificationAccess access;
    /** 业务聚合决定状态，通知层管理原参与关系及去重。 */
    public ReversalNotificationService(InboxRepository inbox, ReversalNotificationAccess access) { this.inbox = inbox; this.access = access; }

    /** 尚未登记命令的准备异常不能写成 ERP 冲销失败。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(VoucherReversalPreparationChanged event) {
        var value = event.current();
        ReversalNotice.from(value).ifPresent(notice -> append(value.input().source().command().tenantId(), value.input().id(), notice, value.updatedAt()));
    }
    /** 查询及重复恢复只保留原编号同类事实的一条消息。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(VoucherReversalOperationChanged event) {
        var value = event.current();
        ReversalNotice.from(value).ifPresent(notice -> append(value.input().command().source().command().tenantId(), value.input().command().id(), notice, value.updatedAt()));
    }
    /** 安全结束以持久结束记录为依据，不由 VOIDED 或 FAILED 状态推断。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void retired(VoucherReversalRetired event) {
        var value = event.retirement(); append(value.tenantId(), value.reversalId(), ReversalNotice.RETIRED, value.retiredAt());
    }
    private void append(String tenant, UUID id, ReversalNotice notice, Instant at) {
        var source = access.original(tenant, id); if (source == null) return;
        var application = source.original().application(); String key = notice.eventKey(id);
        for (String recipient : source.recipients(notice)) {
            if (!access.eligible(tenant, recipient, source, notice)) continue;
            UUID messageId = UUID.nameUUIDFromBytes((tenant + ":" + key + ":" + recipient).getBytes(StandardCharsets.UTF_8));
            inbox.append(key, new InboxMessage(messageId, tenant, recipient, application.id(), notice.title(), application.businessNo(), notice.kind(), SYSTEM_ACTOR,
                    null, null, source.original().roundNo(), at, null, notice.content()));
        }
    }
}
