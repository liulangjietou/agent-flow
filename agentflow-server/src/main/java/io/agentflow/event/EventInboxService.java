package io.agentflow.event;

import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.process.ApprovalCompletionService;
import io.agentflow.approval.process.EventWaitService;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 接收与消费分开，消费标记和原生消息推进必须在同一业务事务内完成。
 * @author owlzhangfq@gmail.com
 */
@Service
public class EventInboxService {
    private final EventInboxRepository inbox;
    private final EventContractRepository contracts;
    private final ApplicationRepository applications;
    private final ApprovalCompletionService completion;
    private final EventWaitService waits;
    private final EventIngressVerifier sources;
    private final TransactionTemplate transaction;

    /** 显式事务允许唯一键竞争回滚后重新读取原接收事实。 */
    public EventInboxService(EventInboxRepository inbox, EventContractRepository contracts, ApplicationRepository applications,
            ApprovalCompletionService completion, EventWaitService waits, EventIngressVerifier sources, PlatformTransactionManager manager) {
        this.inbox = inbox; this.contracts = contracts; this.applications = applications; this.completion = completion;
        this.waits = waits; this.sources = sources; this.transaction = new TransactionTemplate(manager);
    }

    /** 验签后的事件按来源去重，允许暂时停用的原契约留在收件箱等待恢复。 */
    public EventInboxItem accept(ReceivedEvent input, Instant now) {
        var signal = input.signal();
        try {
            return transaction.execute(status -> {
                var existing = inbox.byEvent(signal.tenantId(), signal.sourceKey(), input.eventId()).orElse(null);
                if (existing != null) return same(existing, input);
                lockApplication(signal);
                var contract = contracts.lockVersion(signal.tenantId(), signal.contractKey(), signal.contractVersion())
                        .orElseThrow(() -> new DomainException("EVENT_CONTRACT_UNAVAILABLE", "Event contract version is unavailable")).contract();
                if (!contract.sourceKey().equals(signal.sourceKey()) || !contract.eventType().equals(signal.eventType())
                        || contract.envelopeVersion() != signal.envelopeVersion()) throw new DomainException("EVENT_SOURCE_CONFLICT", "Event does not match its published contract");
                existing = inbox.byEvent(signal.tenantId(), signal.sourceKey(), input.eventId()).orElse(null);
                if (existing != null) return same(existing, input);
                var item = EventInboxItem.receive(input, time(now)); inbox.create(item); return item;
            });
        } catch (DuplicateKeyException concurrent) {
            return same(inbox.byEvent(signal.tenantId(), signal.sourceKey(), input.eventId()).orElseThrow(() -> concurrent), input);
        }
    }

    /** 先锁申请及财务聚合，再锁收件；后台、管理员恢复及重复投递不会交换锁顺序。 */
    public void process(EventInboxRepository.Candidate candidate, Instant now) {
        transaction.executeWithoutResult(status -> {
            var initial = inbox.get(candidate.tenantId(), candidate.id()); if (!initial.pending()) return;
            lockApplication(initial.input().signal());
            var current = inbox.lock(candidate.tenantId(), candidate.id()); Instant at = time(now);
            if (!current.pending() || at.isBefore(current.nextAttemptAt())) return;
            var available = sources.availability(current.input());
            if (available == EventIngressVerifier.Availability.CHANGED) { inbox.update(current.sourceChanged(at)); return; }
            if (available == EventIngressVerifier.Availability.DISABLED) { inbox.update(current.waitFor(EventInboxItem.Reason.SOURCE_DISABLED, at)); return; }
            var signal = current.input().signal();
            var command = new EventWaitService.Command(signal.tenantId(), signal.sourceKey(), signal.eventType(), signal.envelopeVersion(), current.input().eventId(),
                    signal.applicationId(), signal.roundNo(), signal.waitId(), signal.contractKey(), signal.contractVersion());
            var next = switch (waits.advance(command)) {
                case ADVANCED -> current.consumed(at);
                case STALE -> current.ignored(EventInboxItem.Reason.TARGET_STALE, at);
                case MISMATCH -> current.ignored(EventInboxItem.Reason.CONTRACT_MISMATCH, at);
                case PAUSED -> current.waitFor(EventInboxItem.Reason.PAUSED, at);
                case CONTRACT_UNAVAILABLE -> current.waitFor(EventInboxItem.Reason.CONTRACT_DISABLED, at);
            };
            inbox.update(next);
        });
    }

    /** 失败事务回滚后记录原修订；迟到的失败不能覆盖新处理或人工恢复。 */
    public void failed(EventInboxRepository.Candidate candidate, long expectedVersion, Instant now) {
        transaction.executeWithoutResult(status -> {
            var current = inbox.lock(candidate.tenantId(), candidate.id());
            if (current.pending() && current.version() == expectedVersion) inbox.update(current.failed(time(now)));
        });
    }

    /** 管理员只能对待检查的原事件重新排队，理由与操作者进入同一追加历史。 */
    public EventInboxItem retry(Actor actor, UUID id, long expectedVersion, String reason, Instant now) {
        actor.requireRole("ADMIN");
        return transaction.execute(status -> {
            var current = inbox.lock(actor.tenantId(), id);
            if (current.version() != expectedVersion) throw new DomainException("CONCURRENCY_CONFLICT", "Event inbox version changed");
            var next = current.retry(actor.userId(), reason.strip(), time(now)); inbox.update(next); return next;
        });
    }
    private void lockApplication(EventSignal signal) {
        var application = applications.findById(signal.tenantId(), signal.applicationId())
                .orElseThrow(() -> new DomainException("NOT_FOUND", "Event application was not found"));
        completion.lock(application);
    }
    private static EventInboxItem same(EventInboxItem existing, ReceivedEvent input) {
        if (!existing.input().equals(input)) throw new DomainException("EVENT_ID_CONFLICT", "Event ID was reused with different content or trust revision");
        return existing;
    }
    private static Instant time(Instant value) { return value.truncatedTo(ChronoUnit.MICROS); }
}
