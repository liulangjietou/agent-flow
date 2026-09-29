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
 * 出纳意图的短事务编排，复查成功后原子登记唯一银行队列及 READY 状态。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierPaymentExecutionService {
    private final SupplierPaymentSources sources;
    private final JdbcSupplierPaymentExecutionRepository requests;
    private final JdbcSupplierPaymentOperationRepository payments;
    private final Duration lease;

    /** 三次有界读取共用一次领取租约，超时只能继续原出纳选择。 */
    public SupplierPaymentExecutionService(SupplierPaymentSources sources, JdbcSupplierPaymentExecutionRepository requests,
            JdbcSupplierPaymentOperationRepository payments, @Value("${agentflow.supplier-payments.execution-lease-seconds:90}") int leaseSeconds) {
        if (leaseSeconds < 15 || leaseSeconds > 300) throw new IllegalArgumentException("Supplier execution lease must be between 15 and 300 seconds");
        this.sources = sources; this.requests = requests; this.payments = payments; this.lease = Duration.ofSeconds(leaseSeconds);
    }

    /** 已通过公开入口出纳权限的明确选择，金额和收款账户始终从原授权取得。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public SupplierPaymentExecutionRequest register(String tenant, UUID authorizationId, long holdVersion, String cashier,
            String debitReference, String debitVersion, Instant now) {
        var authorization = sources.lock(tenant, authorizationId); now = time(now); sources.requireCurrent(authorization, cashier, now);
        var hold = sources.held(authorization); if (hold.version() != holdVersion) throw conflict();
        if (payments.find(tenant, authorizationId).isPresent()) throw new DomainException("SUPPLIER_PAYMENT_ALREADY_REGISTERED", "Original supplier authorization already has a bank command");
        if (requests.owner(tenant, authorizationId).isPresent()) throw new DomainException("SUPPLIER_PAYMENT_EXECUTION_PENDING", "Original supplier authorization already has a cashier execution owner");
        var request = SupplierPaymentExecutionRequest.queue(UUID.randomUUID(), hold, cashier, debitReference, debitVersion, now);
        requests.create(request); return request;
    }

    /** 领取前复核当前来源；过期只读租约恢复同一选择，不建立可能发送事实。 */
    @Transactional
    public SupplierPaymentExecutionRequest claim(String tenant, UUID id, Instant now) {
        var current = locked(tenant, id); if (current == null) return null; now = time(now);
        if (current.leaseExpired(now)) { requests.update(current.expireLease(now)); return null; }
        if (current.status() != SupplierPaymentExecutionRequest.Status.QUEUED || now.isBefore(current.nextAttemptAt())) return null;
        if (!eligible(current, now)) return null;
        var claimed = current.claim(now, lease); requests.update(claimed); return claimed;
    }

    /** 数据库保存真实领取时取得的授权，网关依照原来源读取，不能由输入自证。 */
    public SupplierPaymentAuthorization authorization(SupplierPaymentExecutionRequest request) {
        return paymentsAuthorization(request);
    }

    /** 只接受仍持有租约的结果，队列与 READY 同时提交或同时回滚。 */
    @Transactional
    public void finish(SupplierPaymentExecutionRequest claimed, FinanceResult<SupplierPaymentEvidenceReader.Snapshot> result, Instant now) {
        var current = currentClaim(claimed); if (current == null) return; now = time(now);
        if (current.leaseExpired(now)) { requests.update(current.expireLease(now)); return; }
        if (!eligible(current, now)) return;
        if (!(result instanceof FinanceResult.Success<SupplierPaymentEvidenceReader.Snapshot> success)) {
            requests.update(result instanceof FinanceResult.Unavailable<SupplierPaymentEvidenceReader.Snapshot> unavailable
                    ? current.unavailable(SupplierPaymentExecutionRequest.Failure.valueOf(unavailable.failure().name()), now) : current.block(now)); return;
        }
        SupplierPaymentCommand command; SupplierPayableHoldOperation hold;
        try {
            hold = sources.held(paymentsAuthorization(current)); var read = success.value();
            command = current.register(hold, read.held(), read.directory(), read.payable(), now);
        } catch (DomainException changed) { requests.update(current.block(now)); return; }
        var queued = SupplierPaymentOperation.queue(command, now); payments.create(current, hold, queued); requests.update(current.ready(command, now));
    }

    /** 读取异常仅延迟原选择；不会给尚未建立的银行命令添加未知状态。 */
    @Transactional
    public void fail(SupplierPaymentExecutionRequest claimed, Instant now) {
        var current = currentClaim(claimed); if (current != null) requests.update(current.unavailable(SupplierPaymentExecutionRequest.Failure.INTERNAL_ERROR, time(now)));
    }

    private boolean eligible(SupplierPaymentExecutionRequest current, Instant now) {
        var authorization = paymentsAuthorization(current);
        if (!now.isBefore(authorization.expiresAt())) { requests.update(current.expireAuthorization(now)); return false; }
        try { sources.requireCurrent(authorization, current.input().cashier(), now); }
        catch (DomainException changed) { requests.update(current.voidSource(now)); return false; }
        try { sources.held(authorization); }
        catch (DomainException changed) { requests.update(current.block(now)); return false; }
        return true;
    }
    private SupplierPaymentAuthorization paymentsAuthorization(SupplierPaymentExecutionRequest current) {
        return sources.authorization(current.input().tenantId(), current.input().authorizationId());
    }
    private SupplierPaymentExecutionRequest locked(String tenant, UUID id) {
        var current = requests.find(tenant, id).orElse(null); if (current == null) return null;
        sources.lock(tenant, current.input().authorizationId()); return requests.find(tenant, id).orElseThrow(SupplierPaymentExecutionService::conflict);
    }
    private SupplierPaymentExecutionRequest currentClaim(SupplierPaymentExecutionRequest claimed) {
        var current = locked(claimed.input().tenantId(), claimed.input().id());
        return current != null && current.status() == SupplierPaymentExecutionRequest.Status.RUNNING && current.version() == claimed.version() && current.input().equals(claimed.input()) ? current : null;
    }
    private static Instant time(Instant value) { return value.truncatedTo(ChronoUnit.MICROS); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Original supplier hold or cashier request changed"); }
}
