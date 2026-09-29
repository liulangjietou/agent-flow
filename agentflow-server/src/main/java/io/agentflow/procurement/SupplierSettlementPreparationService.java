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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 财务原付款和日期意图先落库，事务外复查后在短事务中登记原结算及 READY。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierSettlementPreparationService {
    private final SupplierSettlementSources sources;
    private final JdbcSupplierSettlementPreparationRepository preparations;
    private final JdbcSupplierPayableSettlementRepository settlements;
    private final Duration lease;

    /** 准备租约覆盖三项有界读取，实际来源锁不跨网络调用。 */
    public SupplierSettlementPreparationService(SupplierSettlementSources sources, JdbcSupplierSettlementPreparationRepository preparations,
            JdbcSupplierPayableSettlementRepository settlements, @Value("${agentflow.supplier-payments.settlement-preparation-lease-seconds:90}") int leaseSeconds) {
        if (leaseSeconds < 15 || leaseSeconds > 300) throw new IllegalArgumentException("Supplier settlement preparation lease must be between 15 and 300 seconds");
        this.sources = sources; this.preparations = preparations; this.settlements = settlements; this.lease = Duration.ofSeconds(leaseSeconds);
    }

    /** 公开入口确认当前财务权限，金额、账户和原回单全部从实际成功银行修订派生。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public SupplierSettlementPreparation register(String tenant, UUID paymentId, long expectedVersion, String finance, LocalDate accountingDate, Instant now) {
        var payment = sources.lock(tenant, paymentId); now = time(now);
        if (payment.version() != expectedVersion) throw conflict(); sources.requireCurrent(payment.command(), finance, now);
        var value = SupplierSettlementPreparation.queue(UUID.randomUUID(), payment, finance, accountingDate, now); preparations.create(value); return value;
    }

    /** 旧只读租约先恢复原输入；当前来源失效只停止准备，不制造新的核销。 */
    @Transactional
    public SupplierSettlementPreparation claim(String tenant, UUID id, Instant now) {
        var current = locked(tenant, id); if (current == null) return null; now = time(now);
        if (current.leaseExpired(now)) { preparations.update(current.expireLease(now)); return null; }
        if (current.status() != SupplierSettlementPreparation.Status.QUEUED || now.isBefore(current.nextAttemptAt()) || !eligible(current, now)) return null;
        var claimed = current.claim(now, lease); preparations.update(claimed); return claimed;
    }

    /** 三项原读取、当前资格及实际领取同时匹配，才能原子登记不可替换的结算命令。 */
    @Transactional
    public void finish(SupplierSettlementPreparation claimed, FinanceResult<SupplierSettlementEvidenceReader.Snapshot> result, Instant now) {
        var current = currentClaim(claimed); if (current == null) return; now = time(now);
        if (current.leaseExpired(now)) { preparations.update(current.expireLease(now)); return; }
        if (!eligible(current, now)) return;
        if (!(result instanceof FinanceResult.Success<SupplierSettlementEvidenceReader.Snapshot> success)) {
            if (result instanceof FinanceResult.Unavailable<SupplierSettlementEvidenceReader.Snapshot> unavailable) {
                preparations.update(current.unavailable(SupplierSettlementPreparation.Issue.valueOf(unavailable.failure().name()), now));
            } else preparations.update(current.block(result instanceof FinanceResult.Rejected<SupplierSettlementEvidenceReader.Snapshot> rejected && rejected.reason() == FinanceResult.Reason.ACCOUNTING_PERIOD_CLOSED
                    ? SupplierSettlementPreparation.Issue.ACCOUNTING_PERIOD_REJECTED : SupplierSettlementPreparation.Issue.EVIDENCE_CHANGED, now));
            return;
        }
        SupplierPayableSettlementCommand command;
        try {
            var evidence = success.value().evidence(); command = current.input().command(evidence, now); sources.requireEvidence(command, evidence, now);
        } catch (DomainException changed) { preparations.update(current.block(SupplierSettlementPreparation.Issue.EVIDENCE_CHANGED, now)); return; }
        settlements.create(current, SupplierPayableSettlementOperation.queue(command, now)); preparations.update(current.ready(command, now));
    }

    /** 未登记命令前的异常只保留意图退避，不伪造 ERP 已受理事实。 */
    @Transactional
    public void fail(SupplierSettlementPreparation claimed, Instant now) {
        var current = currentClaim(claimed); if (current != null) preparations.update(current.unavailable(SupplierSettlementPreparation.Issue.INTERNAL_ERROR, time(now)));
    }
    private boolean eligible(SupplierSettlementPreparation current, Instant now) {
        try { sources.requireCurrent(current.input().payment().command(), current.input().financeActor(), now); return true; }
        catch (DomainException changed) { preparations.update(current.voidSource(now)); return false; }
    }
    private SupplierSettlementPreparation locked(String tenant, UUID id) {
        var before = preparations.find(tenant, id).orElse(null); if (before == null) return null;
        sources.lock(tenant, before.input().payment().command().id()); return preparations.find(tenant, id).orElseThrow(SupplierSettlementPreparationService::conflict);
    }
    private SupplierSettlementPreparation currentClaim(SupplierSettlementPreparation claimed) {
        var current = locked(claimed.input().payment().command().tenantId(), claimed.input().id());
        return current != null && current.status() == SupplierSettlementPreparation.Status.RUNNING && current.version() == claimed.version() && current.input().equals(claimed.input()) ? current : null;
    }
    private static Instant time(Instant value) { return value.truncatedTo(ChronoUnit.MICROS); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Original paid bank or settlement preparation changed"); }
}
