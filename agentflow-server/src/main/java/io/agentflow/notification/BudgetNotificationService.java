package io.agentflow.notification;

import io.agentflow.finance.BudgetOperationChanged;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原预算事实、最小站内提示和外发意向原子保存，业务事务内不发送外部消息。
 * @author owlzhangfq@gmail.com
 */
@Service
public class BudgetNotificationService {
    private static final String SYSTEM_ACTOR = "system:budgets";
    private final InboxRepository inbox;
    private final BudgetNotificationAccess access;
    /** 预算聚合管理操作状态，通知层负责原参与关系及事实去重。 */
    public BudgetNotificationService(InboxRepository inbox, BudgetNotificationAccess access) { this.inbox = inbox; this.access = access; }

    /** 反复失败、查询恢复及迟到结果共用原命令事实键。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(BudgetOperationChanged event) {
        var operation = event.current(); var command = operation.input().command();
        BudgetNotice.from(operation).ifPresent(notice -> {
            var source = access.original(command.tenantId(), command.id());
            if (source == null) return;
            var application = source.application(); String key = notice.eventKey(command.id());
            for (String recipient : source.recipients()) {
                if (!access.eligible(command.tenantId(), recipient, source)) continue;
                UUID id = UUID.nameUUIDFromBytes((command.tenantId() + ":" + key + ":" + recipient).getBytes(StandardCharsets.UTF_8));
                inbox.append(key, new InboxMessage(id, command.tenantId(), recipient, application.id(), notice.title(), application.businessNo(), notice.kind(),
                        SYSTEM_ACTOR, null, null, command.position().roundNo(), operation.updatedAt(), null, notice.content()));
            }
        });
    }
}
