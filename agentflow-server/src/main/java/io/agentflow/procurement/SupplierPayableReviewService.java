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
 * 财务应付复核使用短事务保存读取意图，实际授权与证据消费原子提交。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierPayableReviewService {
    private final ApprovedSupplierPaymentSources sources;
    private final JdbcSupplierPayableReviewRepository reviews;
    private final JdbcSupplierPaymentAuthorizationRepository authorizations;
    private final SupplierPayableHoldService holds;
    private final PaymentPersonnel personnel;
    private final Duration lease;

    /** 领域、原批准及财务任职在事务中核验，ERP 读取留给独立执行器。 */
    public SupplierPayableReviewService(ApprovedSupplierPaymentSources sources, JdbcSupplierPayableReviewRepository reviews,
                                        JdbcSupplierPaymentAuthorizationRepository authorizations, SupplierPayableHoldService holds, PaymentPersonnel personnel,
                                        @Value("${agentflow.supplier-payments.review-lease-seconds:90}") int leaseSeconds) {
        if (leaseSeconds < 15 || leaseSeconds > 300) throw new IllegalArgumentException("Supplier review lease must be between 15 and 300 seconds");
        this.sources = sources; this.reviews = reviews; this.authorizations = authorizations; this.holds = holds; this.personnel = personnel; this.lease = Duration.ofSeconds(leaseSeconds);
    }

    /** 已获财务岗位及字段权限的入口固定展示的实际批准版本，只建立读取队列。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public SupplierPayableReview register(ApprovedProcurementPayment expected, String finance, Instant now) {
        sources.lock(expected); requireSource(expected, finance);
        var review = SupplierPayableReview.queue(UUID.randomUUID(), expected, finance, time(now)); reviews.create(review); return review;
    }

    /** 只读租约到期可恢复原请求，实际来源或活动授权变化则停止读取。 */
    @Transactional
    public SupplierPayableReview claim(String tenant, UUID id, Instant now) {
        var current = locked(tenant, id); if (current == null || !current.active()) return null; now = time(now);
        if (current.leaseExpired(now)) { reviews.update(current.expireLease(now)); return null; }
        if (current.status() == SupplierPayableReview.Status.RUNNING || !available(current, now)) return null;
        var claimed = current.claim(now, lease); reviews.update(claimed); return claimed;
    }

    /** ERP 读取成功只产生等待财务决定的证据，不自动签发授权。 */
    @Transactional
    public void finish(SupplierPayableReview claimed, FinanceResult<ProcurementPayablePort.Payable> result, Instant now) {
        var current = currentClaim(claimed); if (current == null) return; now = time(now);
        if (current.leaseExpired(now)) { reviews.update(current.expireLease(now)); return; }
        if (available(current, now)) reviews.update(current.complete(result, now));
    }

    /** 读取异常仅保存稳定失败分类，后继必须重新取得真实应付而非手填余额。 */
    @Transactional
    public void fail(SupplierPayableReview claimed, Instant now) {
        var current = currentClaim(claimed); if (current == null) return; now = time(now);
        if (current.leaseExpired(now)) { reviews.update(current.expireLease(now)); return; }
        if (available(current, now)) reviews.update(current.fail(SupplierPayableReview.Issue.INTERNAL_ERROR, now));
    }

    /** 同一财务在证据窗口内明确授权，授权、预留队列和单次消费必须一起成功。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public SupplierPayableHoldOperation authorize(String tenant, UUID id, long expectedVersion, String finance, Instant now) {
        var current = locked(tenant, id); now = time(now);
        if (current == null || current.version() != expectedVersion || !current.input().requestedBy().equals(finance) || !current.usable(now)) throw unavailable();
        requireSource(current.input().source(), finance);
        var authorization = new SupplierPaymentAuthorization(UUID.randomUUID(), current.input().source(), current.payable(), finance, now, now.plus(SupplierPaymentAuthorization.MAX_VALIDITY));
        var operation = holds.register(authorization, now); reviews.update(current.consume(authorization, now)); return operation;
    }

    private SupplierPayableReview locked(String tenant, UUID id) {
        var current = reviews.find(tenant, id).orElse(null); if (current == null) return null;
        sources.lock(current.input().source()); return reviews.find(tenant, id).orElseThrow(SupplierPayableReviewService::unavailable);
    }
    private SupplierPayableReview currentClaim(SupplierPayableReview claimed) {
        var current = locked(claimed.input().source().reservation().source().tenantId(), claimed.input().id());
        return current != null && current.status() == SupplierPayableReview.Status.RUNNING && current.equals(claimed) ? current : null;
    }
    private boolean available(SupplierPayableReview current, Instant now) {
        try { requireSource(current.input().source(), current.input().requestedBy()); return true; }
        catch (DomainException changed) { reviews.update(current.voidSource(now)); return false; }
    }
    private void requireSource(ApprovedProcurementPayment expected, String finance) {
        var source = expected.reservation().source();
        if (!expected.equals(sources.derive(source.tenantId(), source.requestId()))) throw unavailable();
        personnel.requireEligible(source.tenantId(), finance, source.round().content().legalEntityId());
        if (authorizations.activeForRequest(source.tenantId(), source.requestId()).isPresent()) throw new DomainException("SUPPLIER_PAYMENT_ALREADY_AUTHORIZED", "Original supplier authorization must be safely ended before a new review");
    }
    private static Instant time(Instant value) { return value.truncatedTo(ChronoUnit.MICROS); }
    private static DomainException unavailable() { return new DomainException("SUPPLIER_PAYABLE_REVIEW_UNAVAILABLE", "Fresh payable review for this finance actor and unchanged approved source is required"); }
}
