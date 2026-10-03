package io.agentflow.notification;

import io.agentflow.expense.ExpenseAdjustmentChanged;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 实际调整事实与最小站内消息共同提交，失败回滚保持原业务可恢复。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseAdjustmentNotificationService {
    private static final String SYSTEM_ACTOR = "system:expense-adjustments";
    private final InboxRepository inbox;
    private final ExpenseAdjustmentNotificationAccess access;
    /** 接收人只取原申请、原办理财务和实际结束决定。 */
    public ExpenseAdjustmentNotificationService(InboxRepository inbox, ExpenseAdjustmentNotificationAccess access) { this.inbox = inbox; this.access = access; }
    /** 正常准备保持安静，原只读失败不伪装成已授权指令。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void prepared(ExpenseAdjustmentChanged.Preparation event) {
        var current = event.current(); var fact = ExpenseAdjustmentNotice.from(current).orElse(null); if (fact == null) return;
        var source = access.original(current.input().basis().tenantId(), current.input().id(), fact);
        if (source != null && source.preparation().equals(current)) append(source, fact, current.updatedAt());
    }
    /** 预算成功与资源完成分别表达；旧回执不能补发已结束操作的中间状态。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void budget(ExpenseAdjustmentChanged.Budget event) {
        var current = event.current(); var fact = ExpenseAdjustmentNotice.from(current).orElse(null); if (fact == null) return;
        var command = current.input().command(); var source = access.original(command.source().tenantId(), command.adjustmentId(), fact);
        if (source == null || !current.equals(source.budget()) || source.retirement() != null
                || fact == ExpenseAdjustmentNotice.BUDGET_APPLIED && source.completion() != null) return;
        append(source, fact, current.updatedAt());
    }
    /** 完成要求实际反向资源凭据，重复事件不重复通知。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void resources(ExpenseAdjustmentChanged.Resources event) {
        var current = event.current(); var fact = ExpenseAdjustmentNotice.from(current).orElse(null); if (fact == null) return;
        var source = access.original(current.input().basis().tenantId(), current.id(), fact);
        if (source != null && current.equals(source.adjustment())) append(source, fact, current.updatedAt());
    }
    /** 安全结束由独立记录证明，停止未发送命令不额外制造失败通知。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void retired(ExpenseAdjustmentChanged.Retired event) {
        var value = event.retirement(); var source = access.original(value.before().input().basis().tenantId(), value.before().id(), ExpenseAdjustmentNotice.RETIRED);
        if (source != null && value.equals(source.retirement())) append(source, ExpenseAdjustmentNotice.RETIRED, value.retiredAt());
    }
    private void append(ExpenseAdjustmentNotificationAccess.Source source, ExpenseAdjustmentNotice fact, Instant at) {
        var input = source.preparation().input(); var binding = input.basis().settlement().input().source(); var tenant = binding.tenantId();
        var key = fact.eventKey(input.id());
        for (var recipient : source.recipients()) {
            if (!access.eligible(tenant, recipient, source)) continue;
            var id = UUID.nameUUIDFromBytes((tenant + ":" + key + ":" + recipient).getBytes(StandardCharsets.UTF_8));
            inbox.append(key, new InboxMessage(id, tenant, recipient, source.application().id(), fact.title(), source.application().businessNo(), fact.kind(),
                    SYSTEM_ACTOR, null, null, binding.roundNo(), at, null, fact.content()));
        }
    }
}
