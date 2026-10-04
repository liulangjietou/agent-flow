package io.agentflow.notification;

import io.agentflow.expense.ExpensePartialAdjustment;
import io.agentflow.expense.ExpensePartialAdjustmentChanged;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原财务事实与消息同事务提交，同一操作同类事实只追加一次。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpensePartialAdjustmentNotificationService {
    private static final String SYSTEM_ACTOR = "system:expense-partial-adjustments";
    private final InboxRepository inbox;
    private final ExpensePartialAdjustmentNotificationAccess access;
    /** 当前接收人资格与实际原件由访问服务统一核对。 */
    public ExpensePartialAdjustmentNotificationService(InboxRepository inbox, ExpensePartialAdjustmentNotificationAccess access) { this.inbox = inbox; this.access = access; }
    /** 正常准备不通知，失败保留准确准备编号及所选侧。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void prepared(ExpensePartialAdjustmentChanged.Preparation event) {
        var v = event.current(); var fact = ExpensePartialAdjustmentNotice.from(v).orElse(null); if (fact == null) return;
        var source = original(v.input().adjustment(), v.input().id(), fact);
        if (source != null && v.equals(source.preparation())) append(source, v.updatedAt());
    }
    /** 预算结果独立于挂账及资源完成。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void budget(ExpensePartialAdjustmentChanged.Budget event) {
        var v = event.current(); var op = v.budget(); var fact = ExpensePartialAdjustmentNotice.from(op).orElse(null); if (fact == null || v.retirement() != null) return;
        if (fact == ExpensePartialAdjustmentNotice.BUDGET_APPLIED && v.completion() != null) return;
        changed(v, op.input().command().id(), fact, op.updatedAt());
    }
    /** 原挂账结果只属于原会计命令。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void accrual(ExpensePartialAdjustmentChanged.Accrual event) {
        var v = event.current(); var op = v.accrual(); var fact = ExpensePartialAdjustmentNotice.from(op).orElse(null); if (fact == null || v.retirement() != null) return;
        if (fact == ExpensePartialAdjustmentNotice.ACCRUAL_POSTED && v.completion() != null) return;
        changed(v, op.input().command().id(), fact, op.updatedAt());
    }
    /** 只有资源实际保存才能宣告本次完成。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void resources(ExpensePartialAdjustmentChanged.Resources event) {
        var v = event.current(); var fact = ExpensePartialAdjustmentNotice.from(v).orElse(null); if (fact != null) changed(v, v.id(), fact, v.updatedAt());
    }
    /** 安全结束不额外发送停止未执行命令的异常。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void retired(ExpensePartialAdjustmentChanged.Retired event) {
        var v = event.current(); if (v.retirement() != null) changed(v, v.id(), ExpensePartialAdjustmentNotice.RETIRED, v.retirement().at());
    }
    /** 必须匹配数据库中的准确裁决，不能只凭操作终态推断裁决。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void resolved(ExpensePartialAdjustmentChanged.Resolved event) {
        var source = original(event.current(), event.decision().id(), ExpensePartialAdjustmentNotice.DISPUTE_RESOLVED);
        if (source != null && source.current().equals(event.current()) && event.decision().equals(source.decision())) append(source, event.decision().resolvedAt());
    }
    private void changed(ExpensePartialAdjustment value, UUID id, ExpensePartialAdjustmentNotice fact, Instant at) {
        var source = original(value, id, fact); if (source != null && value.equals(source.current())) append(source, at);
    }
    private ExpensePartialAdjustmentNotificationAccess.Source original(ExpensePartialAdjustment value, UUID id, ExpensePartialAdjustmentNotice fact) {
        return access.original(value.input().basis().tenantId(), new ExpensePartialAdjustmentNotice.Source(value.id(), fact.sourceType(), id, fact));
    }
    private void append(ExpensePartialAdjustmentNotificationAccess.Source source, Instant at) {
        var key = source.key(); var fact = key.notice(); var binding = source.current().input().basis().funding().financial().settlement().input().source();
        var eventKey = fact.eventKey(key.adjustmentId(), key.sourceId());
        for (var recipient : source.recipients()) {
            if (!access.eligible(binding.tenantId(), recipient, source)) continue;
            var id = UUID.nameUUIDFromBytes((binding.tenantId() + ":" + eventKey + ":" + recipient).getBytes(StandardCharsets.UTF_8));
            inbox.append(eventKey, new InboxMessage(id, binding.tenantId(), recipient, source.application().id(), fact.title(), source.application().businessNo(), fact.kind(),
                    SYSTEM_ACTOR, null, null, binding.roundNo(), at, null, fact.content()));
        }
    }
}
