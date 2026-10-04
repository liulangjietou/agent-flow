package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceResult;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 明确财务意图先持久保存，事务外读取后登记同一来源和日期的独立调整命令。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierAdjustmentPreparationService {
    private final JdbcSupplierAdjustmentSources facts;
    private final SupplierAdjustmentSources sources;
    private final JdbcSupplierAdjustmentPreparationRepository preparations;
    private final JdbcSupplierPayableAdjustmentRepository operations;
    private final Duration lease;
    private final ApplicationEventPublisher events;

    /** 准备租约覆盖有界只读依赖，业务锁不会跨越外部读取。 */
    public SupplierAdjustmentPreparationService(JdbcSupplierAdjustmentSources facts, SupplierAdjustmentSources sources,
            JdbcSupplierAdjustmentPreparationRepository preparations, JdbcSupplierPayableAdjustmentRepository operations, ApplicationEventPublisher events,
            @Value("${agentflow.supplier-payments.adjustment-preparation-lease-seconds:90}") int leaseSeconds) {
        if (leaseSeconds < 15 || leaseSeconds > 300) throw new IllegalArgumentException("Supplier adjustment preparation lease must be between 15 and 300 seconds");
        this.facts = facts; this.sources = sources; this.preparations = preparations; this.operations = operations; this.events = events; this.lease = Duration.ofSeconds(leaseSeconds);
    }

    /** 对外入口先检查原轮次字段权限，这里从精确版本派生资金与原账务并再次核对独立财务。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public SupplierAdjustmentPreparation register(String tenant, UUID paymentId, long paymentVersion, long returnVersion, String finance, LocalDate date, Instant now) {
        var source = facts.current(tenant, paymentId, paymentVersion, returnVersion); sources.requireCurrent(source, finance);
        var value = SupplierAdjustmentPreparation.queue(UUID.randomUUID(), source, finance, date, time(now)); preparations.create(value); return value;
    }

    /** 只读租约过期保持原意图恢复，来源或人员变化则停止未登记的准备。 */
    @Transactional
    public SupplierAdjustmentPreparation claim(String tenant, UUID id, Instant now) {
        var current = locked(tenant, id); if (current == null) return null; now = time(now);
        if (current.leaseExpired(now)) { save(current.expireLease(now)); return null; }
        if (current.status() != SupplierAdjustmentPreparation.Status.QUEUED || now.isBefore(current.nextAttemptAt()) || !eligible(current, now)) return null;
        var claimed = current.claim(now, lease); save(claimed); return claimed;
    }

    /** 迟到读取不能替换当前领取；命令与 READY 在同一事务保存。 */
    @Transactional
    public void finish(SupplierAdjustmentPreparation claimed, FinanceResult<SupplierAdjustmentEvidenceReader.Snapshot> result, Instant now) {
        var current = currentClaim(claimed); if (current == null) return; now = time(now);
        if (current.leaseExpired(now)) { save(current.expireLease(now)); return; }
        if (!eligible(current, now)) return;
        if (!(result instanceof FinanceResult.Success<SupplierAdjustmentEvidenceReader.Snapshot> success)) {
            if (result instanceof FinanceResult.Unavailable<SupplierAdjustmentEvidenceReader.Snapshot> unavailable) {
                save(current.unavailable(SupplierAdjustmentPreparation.Issue.valueOf(unavailable.failure().name()), now));
            } else save(current.block(result instanceof FinanceResult.Rejected<SupplierAdjustmentEvidenceReader.Snapshot> rejected
                    && rejected.reason() == FinanceResult.Reason.ACCOUNTING_PERIOD_CLOSED ? SupplierAdjustmentPreparation.Issue.ACCOUNTING_PERIOD_REJECTED : SupplierAdjustmentPreparation.Issue.EVIDENCE_CHANGED, now));
            return;
        }
        SupplierPayableAdjustmentCommand command;
        try {
            var evidence = success.value().evidence(); command = current.input().command(evidence, now);
            sources.requireEvidence(current.input().source(), evidence, now);
        } catch (DomainException changed) { save(current.block(SupplierAdjustmentPreparation.Issue.EVIDENCE_CHANGED, now)); return; }
        operations.create(current, SupplierPayableAdjustmentOperation.queue(command, now)); save(current.ready(command, now));
    }

    /** 尚未发送的异常只退避重读，不能伪造 ERP 已受理。 */
    @Transactional
    public void fail(SupplierAdjustmentPreparation claimed, Instant now) {
        var current = currentClaim(claimed); if (current != null) save(current.unavailable(SupplierAdjustmentPreparation.Issue.INTERNAL_ERROR, time(now)));
    }

    private void save(SupplierAdjustmentPreparation value) {
        preparations.update(value); events.publishEvent(new SupplierAdjustmentChanged.Preparation(value));
    }
    private boolean eligible(SupplierAdjustmentPreparation current, Instant now) {
        try { sources.requireCurrent(current.input().source(), current.input().financeActor()); return true; }
        catch (DomainException changed) { save(current.voidSource(now)); return false; }
    }
    private SupplierAdjustmentPreparation locked(String tenant, UUID id) {
        var before = preparations.find(tenant, id).orElse(null); if (before == null) return null;
        facts.lock(tenant, before.input().source().returns().request().command().id()); return preparations.find(tenant, id).orElseThrow(SupplierAdjustmentPreparationService::conflict);
    }
    private SupplierAdjustmentPreparation currentClaim(SupplierAdjustmentPreparation claimed) {
        var current = locked(claimed.input().source().returns().request().command().tenantId(), claimed.input().id());
        return current != null && current.status() == SupplierAdjustmentPreparation.Status.RUNNING && current.version() == claimed.version() && current.input().equals(claimed.input()) ? current : null;
    }
    private static Instant time(Instant value) { return value.truncatedTo(ChronoUnit.MICROS); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Original supplier return source or adjustment preparation changed"); }
}
