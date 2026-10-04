package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceResult;
import io.agentflow.finance.PaymentObservation;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 银行执行的短事务边界，可能发送先落库，失联恢复始终只查询原不可变命令。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierPaymentService {
    private final SupplierPaymentSources sources;
    private final JdbcSupplierPaymentOperationRepository payments;
    private final Duration lease;
    private final ApplicationEventPublisher events;

    /** 领取与外发分开，工作进程崩溃后可通过版本和租约恢复。 */
    public SupplierPaymentService(SupplierPaymentSources sources, JdbcSupplierPaymentOperationRepository payments,
            ApplicationEventPublisher events, @Value("${agentflow.supplier-payments.payment-lease-seconds:90}") int leaseSeconds) {
        if (leaseSeconds < 15 || leaseSeconds > 300) throw new IllegalArgumentException("Supplier payment lease must be between 15 and 300 seconds");
        this.sources = sources; this.payments = payments; this.lease = Duration.ofSeconds(leaseSeconds); this.events = events;
    }

    /** 只有新发送复核当前资格；未知银行结果即使批准或人员变化仍继续查询。 */
    @Transactional
    public SupplierPaymentOperation claim(String tenant, UUID id, Instant now) {
        var current = locked(tenant, id); if (current == null) return null; now = time(now);
        if (current.leaseExpired(now)) { complete(current, current.expireLease(now)); return null; }
        if (current.running() || current.nextAttemptAt() == null || now.isBefore(current.nextAttemptAt())) return null;
        if (current.status() == SupplierPaymentOperation.Status.QUEUED && now.isBefore(current.command().holdCommand().authorization().expiresAt()) && !eligible(current, now)) return null;
        var claimed = current.claim(now, lease); complete(current, claimed); return claimed.running() ? claimed : null;
    }

    /** 本次读取、真实批准和当前人员同时成立，才在事务内记下可能发送及发送次数。 */
    @Transactional
    public SupplierPaymentOperation ready(SupplierPaymentOperation claimed, FinanceResult<SupplierPaymentEvidenceReader.Snapshot> result, Instant now) {
        var current = currentClaim(claimed); if (current == null || current.status() != SupplierPaymentOperation.Status.CHECKING) return null; now = time(now);
        if (current.leaseExpired(now)) { complete(current, current.expireLease(now)); return null; }
        if (!now.isBefore(current.command().holdCommand().authorization().expiresAt())) {
            complete(current, current.readyToSend(null, now)); return null;
        }
        if (!eligible(current, now)) return null;
        if (!(result instanceof FinanceResult.Success<SupplierPaymentEvidenceReader.Snapshot> success)) {
            complete(current, result instanceof FinanceResult.Unavailable<SupplierPaymentEvidenceReader.Snapshot> unavailable
                    ? current.unavailableBeforeSend(SupplierPaymentOperation.Failure.valueOf(unavailable.failure().name()), now)
                    : current.voidBeforeSend(SupplierPaymentOperation.Failure.EVIDENCE_CHANGED, now)); return null;
        }
        SupplierPaymentEvidence proof;
        try {
            var read = success.value(); var hold = sources.held(current.command().holdCommand().authorization());
            if (!current.command().matchesCurrentHold(hold, read.held(), now)) throw conflict();
            proof = SupplierPaymentEvidence.checked(current.command(), read.directory(), read.payable(), read.held(), now);
        } catch (DomainException changed) { complete(current, current.voidBeforeSend(SupplierPaymentOperation.Failure.EVIDENCE_CHANGED, now)); return null; }
        var sending = current.readyToSend(proof, now); complete(current, sending); return sending;
    }

    /** 迟到结果不会覆盖新领取，银行观察按原命令及单调版本接受或进入争议。 */
    @Transactional
    public void finish(SupplierPaymentOperation claimed, FinanceResult<PaymentObservation> result, Instant now) {
        var current = currentClaim(claimed); if (current != null) complete(current, current.complete(result, time(now)));
    }

    /** 只读失败恢复复查；可能发送后失败必须转为原交易查询。 */
    @Transactional
    public void fail(SupplierPaymentOperation claimed, Instant now) {
        var current = currentClaim(claimed); if (current == null) return; now = time(now);
        complete(current, current.status() == SupplierPaymentOperation.Status.CHECKING
                ? current.unavailableBeforeSend(SupplierPaymentOperation.Failure.INTERNAL_ERROR, now)
                : current.unavailable(SupplierPaymentOperation.Failure.INTERNAL_ERROR, now));
    }

    /** 当前权限入口发起的明确查询保留历史结果及争议，不重建银行指令。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public SupplierPaymentOperation query(String tenant, UUID id, long expectedVersion, Instant now) {
        var current = version(tenant, id, expectedVersion); var next = current.requestQuery(time(now)); complete(current, next); return next;
    }
    /** 已认证回调只恢复原号查询，同时使尚未外发的查无重试领取失效。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public SupplierPaymentOperation callbackQuery(String tenant, UUID id, long expectedVersion, Instant now) {
        var current = version(tenant, id, expectedVersion); var next = current.requestCallbackQuery(time(now)); complete(current, next); return next;
    }

    /** 权威查无后的人工重试仍受当前批准、原人员和原账户约束。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public SupplierPaymentOperation resend(String tenant, UUID id, long expectedVersion, Instant now) {
        var current = version(tenant, id, expectedVersion); now = time(now);
        sources.requireCurrent(current.command().holdCommand().authorization(), current.command().cashier(), now);
        sources.held(current.command().holdCommand().authorization());
        var next = current.retryNotFound(now); complete(current, next); return next;
    }

    private boolean eligible(SupplierPaymentOperation current, Instant now) {
        var authorization = current.command().holdCommand().authorization();
        try { sources.requireCurrent(authorization, current.command().cashier(), now); }
        catch (DomainException changed) { complete(current, current.voidBeforeSend(SupplierPaymentOperation.Failure.SOURCE_CHANGED, now)); return false; }
        try { sources.held(authorization); }
        catch (DomainException changed) { complete(current, current.voidBeforeSend(SupplierPaymentOperation.Failure.EVIDENCE_CHANGED, now)); return false; }
        return true;
    }
    private SupplierPaymentOperation locked(String tenant, UUID id) {
        var current = payments.find(tenant, id).orElse(null); if (current == null) return null;
        sources.lock(tenant, id); return payments.find(tenant, id).orElseThrow(SupplierPaymentService::conflict);
    }
    private SupplierPaymentOperation currentClaim(SupplierPaymentOperation claimed) {
        var current = locked(claimed.command().tenantId(), claimed.command().id());
        return current != null && current.running() && current.version() == claimed.version() && current.status() == claimed.status() && current.command().equals(claimed.command()) ? current : null;
    }
    private SupplierPaymentOperation version(String tenant, UUID id, long version) {
        var current = locked(tenant, id); if (current == null || current.version() != version) throw conflict(); return current;
    }
    private void complete(SupplierPaymentOperation previous, SupplierPaymentOperation current) {
        payments.update(current); events.publishEvent(new SupplierPaymentChanged(previous, current));
    }
    private static Instant time(Instant value) { return value.truncatedTo(ChronoUnit.MICROS); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Original supplier bank operation or evidence changed"); }
}
