package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceResult;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 独立调整先保存可能发送事实，未知只查原号，ERP 成功独立于后续本地记账保存。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierAdjustmentService {
    private final JdbcSupplierAdjustmentSources facts;
    private final SupplierAdjustmentSources sources;
    private final SupplierSettlementSources finance;
    private final JdbcSupplierPayableAdjustmentRepository operations;
    private final Duration lease;
    private final ApplicationEventPublisher events;

    /** 所有方法均为短事务，外部银行和 ERP 调用由工作器执行。 */
    public SupplierAdjustmentService(JdbcSupplierAdjustmentSources facts, SupplierAdjustmentSources sources, SupplierSettlementSources finance,
            JdbcSupplierPayableAdjustmentRepository operations, ApplicationEventPublisher events, @Value("${agentflow.supplier-payments.adjustment-lease-seconds:90}") int leaseSeconds) {
        if (leaseSeconds < 15 || leaseSeconds > 300) throw new IllegalArgumentException("Supplier adjustment lease must be between 15 and 300 seconds");
        this.facts = facts; this.sources = sources; this.finance = finance; this.operations = operations; this.events = events; this.lease = Duration.ofSeconds(leaseSeconds);
    }

    /** 新发送依赖当前资格；原号恢复不受原财务离职、会计期间或付款授权到期阻断。 */
    @Transactional
    public SupplierPayableAdjustmentOperation claim(String tenant, UUID id, Instant now) {
        var current = locked(tenant, id); if (current == null) return null; now = time(now);
        if (current.leaseExpired(now)) { save(current.expireLease(now)); return null; }
        if (current.running() || current.nextAttemptAt() == null || now.isBefore(current.nextAttemptAt())) return null;
        if (current.status() == SupplierPayableAdjustmentOperation.Status.QUEUED && !eligible(current, now)) return null;
        var claimed = current.claim(now, lease); save(claimed); return claimed;
    }

    /** 重新锁定来源和当前人员后才记录可能发送，复查不能替换任何命令字节。 */
    @Transactional
    public SupplierPayableAdjustmentOperation ready(SupplierPayableAdjustmentOperation claimed, FinanceResult<SupplierAdjustmentEvidenceReader.Snapshot> result, Instant now) {
        var current = currentClaim(claimed); if (current == null || current.status() != SupplierPayableAdjustmentOperation.Status.CHECKING) return null; now = time(now);
        if (current.leaseExpired(now)) { save(current.expireLease(now)); return null; }
        if (!eligible(current, now)) return null;
        if (!(result instanceof FinanceResult.Success<SupplierAdjustmentEvidenceReader.Snapshot> success)) {
            save(result instanceof FinanceResult.Unavailable<SupplierAdjustmentEvidenceReader.Snapshot> unavailable
                    ? current.unavailableBeforeSend(SupplierPayableAdjustmentOperation.Failure.valueOf(unavailable.failure().name()), now)
                    : current.voidBeforeSend(SupplierPayableAdjustmentOperation.Failure.EVIDENCE_CHANGED, now)); return null;
        }
        SupplierPayableAdjustmentOperation sending;
        try {
            var evidence = success.value().evidence(); sources.requireEvidence(current.command().source(), evidence, now); sending = current.readyToSend(evidence, now);
        } catch (DomainException changed) { save(current.voidBeforeSend(SupplierPayableAdjustmentOperation.Failure.EVIDENCE_CHANGED, now)); return null; }
        save(sending); return sending;
    }

    /** 先提交 ERP 实际结果；本地记账必须在之后重新取得银行原件，失败不能回滚本次成功。 */
    @Transactional
    public void finish(SupplierPayableAdjustmentOperation claimed, FinanceResult<SupplierPayableAdjustmentObservation> result, Instant now) {
        var current = currentClaim(claimed); if (current != null) save(current.complete(result, time(now)));
    }

    /** 只读故障可重读，可能外发的异常只进入原号查询。 */
    @Transactional
    public void fail(SupplierPayableAdjustmentOperation claimed, Instant now) {
        var current = currentClaim(claimed); if (current == null) return; now = time(now);
        save(current.status() == SupplierPayableAdjustmentOperation.Status.CHECKING
                ? current.unavailableBeforeSend(SupplierPayableAdjustmentOperation.Failure.INTERNAL_ERROR, now)
                : current.unavailable(SupplierPayableAdjustmentOperation.Failure.INTERNAL_ERROR, now));
    }

    /** 明确查询保留成功和争议；已经本地完成的旧操作也不能重占活动位置。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public SupplierPayableAdjustmentOperation query(String tenant, UUID id, long expectedVersion, Instant now) {
        var current = version(tenant, id, expectedVersion); var next = current.requestQuery(time(now)); save(next); return next;
    }

    /** 只有权威查无可明确重发，同一财务、原日期和全部资金来源保持。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public SupplierPayableAdjustmentOperation resend(String tenant, UUID id, long expectedVersion, Instant now) {
        var current = version(tenant, id, expectedVersion); sources.requireCurrent(current.command().source(), current.command().financeActor());
        var next = current.retryNotFound(time(now)); save(next); return next;
    }

    /** 独立财务只能结束从未发送或实际无影响拒绝；原资金和历史保持。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public SupplierAdjustmentRetirement retire(String tenant, UUID id, long expectedVersion, String actor, Instant now) {
        var current = version(tenant, id, expectedVersion); now = time(now); finance.requireFinance(current.command().source().returns().request().command(), actor);
        var stopped = current.stopForRetirement(now); if (!stopped.equals(current)) operations.update(stopped);
        var decision = SupplierAdjustmentRetirement.from(stopped, actor, now); operations.retire(tenant, decision); events.publishEvent(new SupplierAdjustmentChanged.Retired(tenant, decision)); return decision;
    }

    private void save(SupplierPayableAdjustmentOperation value) {
        operations.update(value); events.publishEvent(new SupplierAdjustmentChanged.Operation(value));
    }
    private boolean eligible(SupplierPayableAdjustmentOperation current, Instant now) {
        try { sources.requireCurrent(current.command().source(), current.command().financeActor()); return true; }
        catch (DomainException changed) { save(current.voidBeforeSend(SupplierPayableAdjustmentOperation.Failure.SOURCE_CHANGED, now)); return false; }
    }
    private SupplierPayableAdjustmentOperation locked(String tenant, UUID id) {
        var initial = operations.find(tenant, id).orElse(null); if (initial == null) return null;
        facts.lock(tenant, initial.command().source().returns().request().command().id());
        if (operations.retirement(tenant, id).isPresent()) return null;
        return operations.find(tenant, id).orElseThrow(SupplierAdjustmentService::conflict);
    }
    private SupplierPayableAdjustmentOperation currentClaim(SupplierPayableAdjustmentOperation claimed) {
        var current = locked(claimed.command().tenantId(), claimed.command().id());
        return current != null && current.running() && current.status() == claimed.status() && current.version() == claimed.version() && current.command().equals(claimed.command()) ? current : null;
    }
    private SupplierPayableAdjustmentOperation version(String tenant, UUID id, long version) {
        var current = locked(tenant, id); if (current == null || current.version() != version) throw conflict(); return current;
    }
    private static Instant time(Instant value) { return value.truncatedTo(ChronoUnit.MICROS); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Original supplier adjustment changed or was safely retired"); }
}
