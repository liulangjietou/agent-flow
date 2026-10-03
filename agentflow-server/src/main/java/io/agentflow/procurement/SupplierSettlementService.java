package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceResult;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 核销的可能发送先持久化，未知结果只查询；实际 ERP 成功与本地占用完成共享原申请事务。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierSettlementService {
    private final SupplierSettlementSources sources;
    private final JdbcSupplierPayableSettlementRepository settlements;
    private final JdbcProcurementPayableReservationRepository reservations;
    private final Duration lease;

    /** 短事务只编排实际来源和持久状态，不执行 ERP 或银行 HTTP。 */
    public SupplierSettlementService(SupplierSettlementSources sources, JdbcSupplierPayableSettlementRepository settlements,
            JdbcProcurementPayableReservationRepository reservations, @Value("${agentflow.supplier-payments.settlement-lease-seconds:90}") int leaseSeconds) {
        if (leaseSeconds < 15 || leaseSeconds > 300) throw new IllegalArgumentException("Supplier settlement lease must be between 15 and 300 seconds");
        this.sources = sources; this.settlements = settlements; this.reservations = reservations; this.lease = Duration.ofSeconds(leaseSeconds);
    }

    /** 新发送核对当前资格，未知查询不受会计期间、原授权到期或人员变化阻断。 */
    @Transactional
    public SupplierPayableSettlementOperation claim(String tenant, UUID id, Instant now) {
        var current = locked(tenant, id); if (current == null) return null; now = time(now);
        if (current.leaseExpired(now)) { settlements.update(current.expireLease(now)); return null; }
        if (current.running() || current.nextAttemptAt() == null || now.isBefore(current.nextAttemptAt())) return null;
        if (current.status() == SupplierPayableSettlementOperation.Status.QUEUED && !eligible(current, now)) return null;
        var claimed = current.claim(now, lease); settlements.update(claimed); return claimed;
    }

    /** 新鲜三项复查与锁后实际事实一致才记录可能核销，原指令和记账日期不会更新。 */
    @Transactional
    public SupplierPayableSettlementOperation ready(SupplierPayableSettlementOperation claimed, FinanceResult<SupplierSettlementEvidenceReader.Snapshot> result, Instant now) {
        var current = currentClaim(claimed); if (current == null || current.status() != SupplierPayableSettlementOperation.Status.CHECKING) return null; now = time(now);
        if (current.leaseExpired(now)) { settlements.update(current.expireLease(now)); return null; }
        if (!eligible(current, now)) return null;
        if (!(result instanceof FinanceResult.Success<SupplierSettlementEvidenceReader.Snapshot> success)) {
            settlements.update(result instanceof FinanceResult.Unavailable<SupplierSettlementEvidenceReader.Snapshot> unavailable
                    ? current.unavailableBeforeSend(SupplierPayableSettlementOperation.Failure.valueOf(unavailable.failure().name()), now)
                    : current.voidBeforeSend(SupplierPayableSettlementOperation.Failure.EVIDENCE_CHANGED, now)); return null;
        }
        SupplierPayableSettlementOperation sending;
        try {
            var evidence = success.value().evidence(); sources.requireEvidence(current.command(), evidence, now); sending = current.readyToSend(evidence, now);
        } catch (DomainException changed) { settlements.update(current.voidBeforeSend(SupplierPayableSettlementOperation.Failure.EVIDENCE_CHANGED, now)); return null; }
        settlements.update(sending); return sending;
    }

    /** 实际核销回执先按领域单调接受；银行暂在查询时保留 ERP 成功并等待本地补全。 */
    @Transactional
    public void finish(SupplierPayableSettlementOperation claimed, FinanceResult<SupplierPayableSettlementObservation> result, Instant now) {
        var current = currentClaim(claimed); if (current == null) return; now = time(now);
        var next = current.complete(result, now); settlements.update(next); completeReservation(next, now);
    }

    /** 只读失败重读，可能写入后的异常只进入原号查询恢复。 */
    @Transactional
    public void fail(SupplierPayableSettlementOperation claimed, Instant now) {
        var current = currentClaim(claimed); if (current == null) return; now = time(now);
        settlements.update(current.status() == SupplierPayableSettlementOperation.Status.CHECKING
                ? current.unavailableBeforeSend(SupplierPayableSettlementOperation.Failure.INTERNAL_ERROR, now)
                : current.unavailable(SupplierPayableSettlementOperation.Failure.INTERNAL_ERROR, now));
    }

    /** 已核销后的本地补全不再次扣应付或重建银行付款。 */
    @Transactional
    public void completeLocal(String tenant, UUID id, Instant now) {
        var current = locked(tenant, id); if (current != null) completeReservation(current, time(now));
    }

    /** 明确的原号查询保留已有成功或争议，不接收新命令正文。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public SupplierPayableSettlementOperation query(String tenant, UUID id, long expectedVersion, Instant now) {
        var current = version(tenant, id, expectedVersion); var next = current.requestQuery(time(now)); settlements.update(next); return next;
    }

    /** 仅权威查无允许明确重试，仍沿用原财务、原日期和命令，发送前重新复查。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public SupplierPayableSettlementOperation resend(String tenant, UUID id, long expectedVersion, Instant now) {
        var current = version(tenant, id, expectedVersion); now = time(now); sources.requireCurrent(current.command().payment(), current.command().financeActor(), now);
        var next = current.retryNotFound(now); settlements.update(next); return next;
    }

    /** 从未发送或真实拒绝才可具名结束，停止领取与解除独占在同一事务中完成。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public SupplierSettlementRetirement retire(String tenant, UUID id, long expectedVersion, String finance, Instant now) {
        var current = version(tenant, id, expectedVersion); now = time(now); sources.requireFinance(current.command().payment(), finance);
        var stopped = current.stopForRetirement(now); if (!stopped.equals(current)) settlements.update(stopped);
        var decision = SupplierSettlementRetirement.from(stopped, finance, now); settlements.retire(tenant, decision); return decision;
    }

    private void completeReservation(SupplierPayableSettlementOperation current, Instant now) {
        if (!current.settled()) return;
        var command = current.command(); var bank = sources.payment(command.tenantId(), command.payment().id());
        if (!bank.settleable() || sources.returnReviewRequired(command.payment())) return;
        var original = command.payment().holdCommand().authorization().source().reservation();
        if (!reservations.active(command.tenantId(), original.source().requestId()).filter(original::equals).isPresent()) return;
        reservations.complete(current, bank, now);
    }
    private boolean eligible(SupplierPayableSettlementOperation current, Instant now) {
        try { sources.requireCurrent(current.command().payment(), current.command().financeActor(), now); return true; }
        catch (DomainException changed) { settlements.update(current.voidBeforeSend(SupplierPayableSettlementOperation.Failure.SOURCE_CHANGED, now)); return false; }
    }
    private SupplierPayableSettlementOperation locked(String tenant, UUID id) {
        var current = settlements.find(tenant, id).orElse(null); if (current == null) return null;
        sources.lock(tenant, current.command().payment().id());
        if (settlements.retirement(tenant, id).isPresent()) return null;
        return settlements.find(tenant, id).orElseThrow(SupplierSettlementService::conflict);
    }
    private SupplierPayableSettlementOperation currentClaim(SupplierPayableSettlementOperation claimed) {
        var current = locked(claimed.command().tenantId(), claimed.command().id());
        return current != null && current.running() && current.status() == claimed.status() && current.version() == claimed.version() && current.command().equals(claimed.command()) ? current : null;
    }
    private SupplierPayableSettlementOperation version(String tenant, UUID id, long version) {
        var current = locked(tenant, id); if (current == null || current.version() != version) throw conflict(); return current;
    }
    private static Instant time(Instant value) { return value.truncatedTo(ChronoUnit.MICROS); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Original supplier settlement changed or was safely retired"); }
}
