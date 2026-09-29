package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceResult;
import io.agentflow.finance.PaymentPersonnel;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 授权和 ERP 预留的短事务编排，真实批准及人员守卫与网络传输分开。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierPayableHoldService {
    private final ApprovedSupplierPaymentSources sources;
    private final JdbcSupplierPaymentAuthorizationRepository authorizations;
    private final JdbcSupplierPayableHoldRepository operations;
    private final PaymentPersonnel personnel;
    private final Duration lease;

    /** 租约覆盖一次有界网关请求，超期通过原命令恢复。 */
    public SupplierPayableHoldService(ApprovedSupplierPaymentSources sources, JdbcSupplierPaymentAuthorizationRepository authorizations,
                                      JdbcSupplierPayableHoldRepository operations, PaymentPersonnel personnel,
                                      @Value("${agentflow.supplier-payments.hold-lease-seconds:90}") int leaseSeconds) {
        if (leaseSeconds < 15 || leaseSeconds > 300) throw new IllegalArgumentException("Supplier hold lease must be between 15 and 300 seconds");
        this.sources = sources; this.authorizations = authorizations; this.operations = operations; this.personnel = personnel; this.lease = Duration.ofSeconds(leaseSeconds);
    }

    /** 已通过财务岗位与原轮次字段权限的入口，将决定与队列原子保存。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public SupplierPayableHoldOperation register(SupplierPaymentAuthorization authorization, Instant now) {
        sources.lock(authorization); requireSource(authorization);
        var command = new SupplierPayableHoldCommand(authorization);
        var existing = operations.find(command.tenantId(), command.id()).orElse(null);
        if (existing != null) {
            if (!existing.command().equals(command)) throw conflict();
            return existing;
        }
        var queued = SupplierPayableHoldOperation.queue(command, time(now)); authorizations.create(authorization); operations.create(queued); return queued;
    }

    /** 原批准和财务人员只约束新预留；恢复查询不因撤销或停用人员而丢失外部事实。 */
    @Transactional
    public SupplierPayableHoldOperation claim(String tenant, UUID id, Instant now) {
        var current = locked(tenant, id); if (current == null) return null; now = time(now);
        if (current.expired(now)) { operations.update(current.expire(now)); return null; }
        if (current.running() || current.nextAttemptAt() == null || now.isBefore(current.nextAttemptAt())) return null;
        if (current.status() == SupplierPayableHoldOperation.Status.QUEUED) {
            try { requireSource(current.command().authorization()); }
            catch (DomainException changed) { operations.update(current.voidBeforeSend(now)); return null; }
        }
        var claimed = current.claim(now, lease); operations.update(claimed); return claimed.running() ? claimed : null;
    }

    /** 当前领取版本下保存原响应，迟到进程不能覆盖其他领取或已确认结果。 */
    @Transactional
    public void finish(SupplierPayableHoldOperation claimed, FinanceResult<SupplierPayableHoldObservation> result, Instant now) {
        var current = currentClaim(claimed); if (current != null) operations.update(current.complete(result, time(now)));
    }

    /** 已领取后不能用本地异常宣称未预留，下一步只查询原命令。 */
    @Transactional
    public void fail(SupplierPayableHoldOperation claimed, SupplierPayableHoldOperation.Failure reason, Instant now) {
        var current = currentClaim(claimed); if (current != null) operations.update(current.unavailable(reason, time(now)));
    }

    /** 已通过公开入口权限核验的明确查询，仍使用原版本、原目标和原编号。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public SupplierPayableHoldOperation query(String tenant, UUID id, long expectedVersion, Instant now) {
        var current = currentVersion(tenant, id, expectedVersion); var next = current.requestQuery(time(now)); operations.update(next); return next;
    }

    /** 权威查无后的人工重试也重新核验实际批准与财务任职，不能扩大金额或替换账户。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public SupplierPayableHoldOperation resend(String tenant, UUID id, long expectedVersion, Instant now) {
        var current = currentVersion(tenant, id, expectedVersion); requireSource(current.command().authorization());
        var next = current.retryNotFound(time(now)); operations.update(next); return next;
    }

    private SupplierPayableHoldOperation locked(String tenant, UUID id) {
        var current = operations.find(tenant, id).orElse(null); if (current == null) return null;
        sources.lock(current.command().authorization()); return operations.find(tenant, id).orElseThrow(SupplierPayableHoldService::conflict);
    }
    private SupplierPayableHoldOperation currentClaim(SupplierPayableHoldOperation claimed) {
        var current = locked(claimed.command().tenantId(), claimed.command().id());
        return current != null && current.running() && current.version() == claimed.version() && current.status() == claimed.status() && current.command().equals(claimed.command()) ? current : null;
    }
    private SupplierPayableHoldOperation currentVersion(String tenant, UUID id, long version) {
        var current = locked(tenant, id); if (current == null || current.version() != version) throw conflict(); return current;
    }
    private void requireSource(SupplierPaymentAuthorization authorization) {
        sources.requireCurrent(authorization); var source = authorization.source().reservation().source();
        personnel.requireEligible(source.tenantId(), authorization.authorizedBy(), source.round().content().legalEntityId());
    }
    private static Instant time(Instant value) { return value.truncatedTo(ChronoUnit.MICROS); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Original supplier payment authorization or hold version changed"); }
}
