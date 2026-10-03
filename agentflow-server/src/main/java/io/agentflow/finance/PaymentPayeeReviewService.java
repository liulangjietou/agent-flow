package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 原付款结束后的账户读取和人工消费使用短事务，跨聚合来源在原业务锁内核对。
 * @author owlzhangfq@gmail.com
 */
@Service
public class PaymentPayeeReviewService {
    private final ApprovedPaymentSources sources;
    private final JdbcPaymentAuthorizationRepository authorizations;
    private final JdbcPaymentPayeeReviewRepository reviews;
    private final PaymentPersonnel personnel;
    private final Duration lease;

    /** 租约只控制只读账户请求，恢复不能产生付款授权。 */
    public PaymentPayeeReviewService(ApprovedPaymentSources sources, JdbcPaymentAuthorizationRepository authorizations,
                                    JdbcPaymentPayeeReviewRepository reviews,
                                    PaymentPersonnel personnel, @Value("${agentflow.payments.payee-review-lease-seconds:90}") int leaseSeconds) {
        if (leaseSeconds < 15 || leaseSeconds > 300) throw new IllegalArgumentException("Payee review lease must be between 15 and 300 seconds");
        this.sources = sources; this.authorizations = authorizations;
        this.reviews = reviews; this.personnel = personnel; this.lease = Duration.ofSeconds(leaseSeconds);
    }

    /** 财务确认的展示版本和审计与读取意图一起保存，接口不读取外部账户。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentPayeeReview register(PaymentAuthorization expected, long voucherVersion, String finance, Instant now) {
        sources.lock(expected); now = time(now);
        var original = authorization(expected.terms().tenantId(), expected.terms().id());
        if (!original.equals(expected)) throw conflict();
        if (original.status() == PaymentAuthorization.Status.AUTHORIZED && !now.isBefore(original.decision().expiresAt())) {
            original = original.expire(now); authorizations.update(original);
        }
        var voucher = requireSource(original, finance, now);
        if (voucher.version() != voucherVersion) throw conflict();
        var previous = reviews.latest(original.terms().tenantId(), original.terms().id(), finance).orElse(null);
        if (previous != null && previous.active()) throw new DomainException("PAYMENT_PAYEE_REVIEW_PENDING", "The current finance actor already has a pending payee review");
        var review = PaymentPayeeReview.queue(UUID.randomUUID(), original, voucher, finance, now);
        reviews.create(review); return review;
    }

    /** 领取前重读人员、原授权、业务占用和凭证；过期租约先恢复原记录。 */
    @Transactional
    public PaymentPayeeReview claim(String tenant, UUID id, Instant now) {
        var current = locked(tenant, id); if (current == null || !current.active()) return null; now = time(now);
        if (current.leaseExpired(now)) { reviews.update(current.expireLease(now)); return null; }
        if (current.status() == PaymentPayeeReview.Status.RUNNING || !available(current, now)) return null;
        var claimed = current.claim(now, lease); reviews.update(claimed); return claimed;
    }

    /** 外部读取结束只形成待财务确认的证据，不直接签发或执行新授权。 */
    @Transactional
    public void finish(PaymentPayeeReview claimed, FinanceResult<EmployeeAccountPort.Account> result, Instant now) {
        var current = currentClaim(claimed); if (current == null) return; now = time(now);
        if (current.leaseExpired(now)) { reviews.update(current.expireLease(now)); return; }
        if (!available(current, now)) return;
        if (result instanceof FinanceResult.Success<EmployeeAccountPort.Account> success) {
            result = new FinanceResult.Success<>(new EmployeeAccountPort.Account(success.value().snapshot(), time(success.value().validUntil())));
        }
        reviews.update(current.complete(result, now));
    }

    /** 未取得外部事实的异常仅记录读取失败，保留全部历史版本。 */
    @Transactional
    public void fail(PaymentPayeeReview claimed, Instant now) {
        var current = currentClaim(claimed); if (current == null) return; now = time(now);
        if (current.leaseExpired(now)) { reviews.update(current.expireLease(now)); return; }
        if (available(current, now)) reviews.update(current.fail(PaymentPayeeReview.Issue.INTERNAL_ERROR, now));
    }

    /** 新授权只能消费当前财务最近一次读取的同一凭证结果，旧结果不自动续期。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentPayeeReview requireReady(String tenant, UUID id, long version, VoucherOperation voucher, String finance, Instant now) {
        var review = reviews.find(tenant, id).orElseThrow(PaymentPayeeReviewService::unavailable);
        if (review.version() != version || !review.usable(now) || !review.input().requestedBy().equals(finance)
                || !review.input().original().voucherOperationId().equals(voucher.input().command().id())) throw unavailable();
        var original = authorization(tenant, review.input().original().id());
        sources.lock(original);
        var currentVoucher = requireSource(original, finance, now);
        if (!currentVoucher.equals(voucher) || !review.matchesSource(original, currentVoucher, now) || !latest(review)) throw unavailable();
        return review;
    }

    /** 调用方已持有原业务锁，新授权和证据单次消费必须一起提交。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(PaymentPayeeReview expected, PaymentAuthorization authorization, Instant now) {
        var current = reviews.find(expected.input().original().tenantId(), expected.input().id()).orElseThrow(PaymentPayeeReviewService::conflict);
        if (!current.equals(expected)) throw conflict(); reviews.update(current.consume(authorization, now));
    }

    private VoucherOperation requireSource(PaymentAuthorization original, String finance, Instant now) {
        var terms = original.terms(); var binding = terms.binding();
        if (!PaymentPayeeReview.ended(original)
                || !authorizations.latest(terms.tenantId(), binding.applicationId(), binding.roundNo()).filter(original::equals).isPresent()
                || authorizations.active(terms.tenantId(), JdbcPaymentAuthorizationRepository.businessType(terms.purpose()), binding.businessId()).isPresent()) throw sourceChanged();
        var voucher = sources.requireCurrent(original, now);
        personnel.requireEligible(terms.tenantId(), finance, terms.payee().legalEntityId());
        return voucher;
    }
    private boolean available(PaymentPayeeReview review, Instant now) {
        try {
            var original = authorization(review.input().original().tenantId(), review.input().original().id());
            if (!review.matchesSource(original, requireSource(original, review.input().requestedBy(), now), now) || !latest(review)) throw sourceChanged();
            return true;
        } catch (DomainException changed) { reviews.update(review.voidSource(now)); return false; }
    }
    private boolean latest(PaymentPayeeReview review) {
        var input = review.input();
        return reviews.latest(input.original().tenantId(), input.original().id(), input.requestedBy()).map(value -> value.input().id().equals(input.id())).orElse(false);
    }
    private PaymentPayeeReview locked(String tenant, UUID id) {
        var initial = reviews.find(tenant, id).orElse(null); if (initial == null) return null;
        sources.lock(authorization(tenant, initial.input().original().id()));
        return reviews.find(tenant, id).orElseThrow(PaymentPayeeReviewService::conflict);
    }
    private PaymentPayeeReview currentClaim(PaymentPayeeReview claimed) {
        var current = locked(claimed.input().original().tenantId(), claimed.input().id());
        return current != null && current.equals(claimed) && current.status() == PaymentPayeeReview.Status.RUNNING ? current : null;
    }
    private PaymentAuthorization authorization(String tenant, UUID id) { return authorizations.find(tenant, id).orElseThrow(PaymentPayeeReviewService::sourceChanged); }
    private static Instant time(Instant value) { return value.truncatedTo(ChronoUnit.MICROS); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Payee review or original source version changed"); }
    private static DomainException sourceChanged() { return new DomainException("PAYMENT_SOURCE_CHANGED", "Only the latest safely ended authorization with current approved voucher allows account review"); }
    private static DomainException unavailable() { return new DomainException("PAYMENT_PAYEE_REVIEW_UNAVAILABLE", "The current finance actor requires fresh latest account review evidence"); }
}
