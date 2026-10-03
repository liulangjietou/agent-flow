package io.agentflow.finance.callback;

import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceGatewayConfiguration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 接收、查询登记与回调状态原子提交；唯一键冲突在失败事务外识别相同事件。
 * @author owlzhangfq@gmail.com
 */
@Service
public class PaymentCallbackService {
    private final JdbcPaymentCallbackRepository callbacks;
    private final PaymentCallbackSources sources;
    private final FinanceGatewayConfiguration gateway;
    private final TransactionTemplate transaction;

    /** 显式事务用于并发重复接收后的安全回读，不在已回滚事务里吞数据库异常。 */
    public PaymentCallbackService(JdbcPaymentCallbackRepository callbacks, PaymentCallbackSources sources,
            FinanceGatewayConfiguration gateway, PlatformTransactionManager transactions) {
        this.callbacks = callbacks; this.sources = sources; this.gateway = gateway; this.transaction = new TransactionTemplate(transactions);
    }

    /** 相同事件重复投递返回同一持久记录，正文变化或原目标不一致则拒绝。 */
    public PaymentCallback accept(PaymentCallbackVerifier.Verified input, Instant now) {
        try {
            return transaction.execute(status -> {
                var existing = callbacks.byEvent(input.signal().tenantId(), input.eventId()).orElse(null);
                if (existing != null) return same(existing, input);
                var source = sources.lock(input.signal());
                if (!source.commandDigest().equals(input.signal().commandDigest()) || !source.targetDigest().equals(input.targetDigest())) {
                    throw new DomainException("PAYMENT_CALLBACK_SOURCE_CONFLICT", "Payment callback does not match the original command and gateway");
                }
                // 同源并发者可能在等待原申请锁时已经提交。
                existing = callbacks.byEvent(input.signal().tenantId(), input.eventId()).orElse(null);
                if (existing != null) return same(existing, input);
                var received = PaymentCallback.receive(input, time(now)); callbacks.create(received); return received;
            });
        } catch (DuplicateKeyException concurrent) {
            return same(callbacks.byEvent(input.signal().tenantId(), input.eventId()).orElseThrow(() -> concurrent), input);
        }
    }

    /** 先锁原付款再锁收件箱；原号查询与回调完成标记必须一起成功或一起回滚。 */
    public void process(JdbcPaymentCallbackRepository.Candidate candidate, Instant now) {
        transaction.executeWithoutResult(status -> {
            var initial = callbacks.get(candidate.tenantId(), candidate.id()); if (!initial.pending()) return;
            var source = sources.lock(initial.input().signal());
            var current = callbacks.lock(candidate.tenantId(), candidate.id());
            Instant at = time(now); if (!current.pending() || at.isBefore(current.nextAttemptAt())) return;
            var input = current.input(); var configured = gateway.destination(input.signal().tenantId());
            if (!source.targetDigest().equals(input.targetDigest()) || configured.isEmpty()
                    || !configured.get().digest(input.signal().tenantId()).equals(input.targetDigest())) {
                callbacks.update(current.review(PaymentCallback.Reason.TARGET_CHANGED, at)); return;
            }
            if (!source.commandDigest().equals(input.signal().commandDigest())) {
                callbacks.update(current.review(PaymentCallback.Reason.SOURCE_MISSING, at)); return;
            }
            if (source.dispatches() == 0) { callbacks.update(current.review(PaymentCallback.Reason.NEVER_DISPATCHED, at)); return; }
            if (source.busy()) { callbacks.update(current.waitForOperation(at)); return; }
            long version = sources.query(input.signal(), source.version(), at);
            callbacks.update(current.queried(version, input.signal().sourceRevision() <= source.highestRevision(), at));
        });
    }

    /** 处理事务已回滚后才保存失败，过期执行者不能改写已完成或已重试的事件。 */
    public void failed(JdbcPaymentCallbackRepository.Candidate candidate, long expectedVersion, Instant now) {
        transaction.executeWithoutResult(status -> {
            var current = callbacks.lock(candidate.tenantId(), candidate.id());
            if (current.pending() && current.version() == expectedVersion) callbacks.update(current.failed(time(now)));
        });
    }

    /** 管理员的重试只重新排队同一事件，操作身份写入追加历史。 */
    public PaymentCallback retry(Actor actor, UUID id, long expectedVersion, String reason, Instant now) {
        return transaction.execute(status -> {
            var current = callbacks.lock(actor.tenantId(), id);
            if (current.version() != expectedVersion) throw new DomainException("CONCURRENCY_CONFLICT", "Payment callback version changed");
            var next = current.retry(actor.userId(), reason, time(now)); callbacks.update(next); return next;
        });
    }
    private static PaymentCallback same(PaymentCallback existing, PaymentCallbackVerifier.Verified input) {
        if (!existing.input().equals(input)) throw new DomainException("PAYMENT_CALLBACK_EVENT_CONFLICT", "Payment callback event ID was reused with different content");
        return existing;
    }
    private static Instant time(Instant value) { return value.truncatedTo(ChronoUnit.MICROS); }
}
