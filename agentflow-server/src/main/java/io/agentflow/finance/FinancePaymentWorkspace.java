package io.agentflow.finance;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 申请人和财务沿用原轮次完整财务字段权限读取付款，审批通过或凭证过账都不能冒充到账。
 * @author owlzhangfq@gmail.com
 */
@Service
public class FinancePaymentWorkspace {
    private final CurrentActor actors;
    private final VoucherAccess access;
    private final PaymentPersonnel personnel;
    private final JdbcVoucherOperationRepository vouchers;
    private final JdbcPaymentAuthorizationRepository authorizations;
    private final JdbcPaymentExecutionRequestRepository requests;
    private final JdbcPaymentOperationRepository operations;
    private final JdbcPaymentPayeeReviewRepository reviews;
    /** 全部查询共享一个本地快照，是否可新授权只作为页面提示。 */
    public FinancePaymentWorkspace(CurrentActor actors, VoucherAccess access, PaymentPersonnel personnel, JdbcVoucherOperationRepository vouchers,
                                    JdbcPaymentAuthorizationRepository authorizations, JdbcPaymentExecutionRequestRepository requests, JdbcPaymentOperationRepository operations,
                                    JdbcPaymentPayeeReviewRepository reviews) {
        this.actors = actors; this.access = access; this.personnel = personnel; this.vouchers = vouchers;
        this.authorizations = authorizations; this.requests = requests; this.operations = operations; this.reviews = reviews;
    }
    /** 查询严格限定一个可读审批轮次，未知参数直接拒绝。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View get(UUID applicationId, Map<String, String> parameters) {
        if (!Set.of("roundNo").containsAll(parameters.keySet())) throw invalid();
        Integer round = null;
        if (parameters.containsKey("roundNo")) {
            try { if (!parameters.get("roundNo").matches("[1-9][0-9]{0,8}")) throw invalid(); round = Integer.parseInt(parameters.get("roundNo")); }
            catch (IllegalArgumentException failure) { throw invalid(); }
        }
        var context = access.read(applicationId, round); var app = context.application(); var tenant = app.tenantId(); var now = Instant.now();
        var voucher = vouchers.forRound(tenant, applicationId, context.roundNo(), context.kind()).orElse(null);
        var authorization = authorizations.latest(tenant, applicationId, context.roundNo()).orElse(null);
        var active = authorizations.active(tenant, app.businessReference().type(), app.businessReference().id()).orElse(null);
        var request = authorization == null ? null : requests.forAuthorization(tenant, authorization.terms().id()).orElse(null);
        var operation = authorization == null ? null : operations.find(tenant, authorization.terms().id()).orElse(null);
        UUID entity = voucher != null ? voucher.input().command().legalEntityId() : authorization != null ? authorization.terms().payee().legalEntityId() : null;
        boolean finance = context.finance() && entity != null && personnel.eligible(tenant, actors.actor().userId(), entity);
        boolean window = authorization != null && authorization.status() == PaymentAuthorization.Status.AUTHORIZED && now.isBefore(authorization.decision().expiresAt());
        boolean available = active == null || active.status() == PaymentAuthorization.Status.AUTHORIZED && !now.isBefore(active.decision().expiresAt());
        boolean authorize = finance && available && app.status() == ApplicationStatus.APPROVED && app.roundNo() == context.roundNo() && voucher != null && voucher.usablePosted()
                && voucher.input().command().totals().payable().value().signum() > 0
                && voucher.input().command().binding().applicationVersion() == app.version() && voucher.input().command().binding().businessVersion() == context.businessVersion();
        var review = finance && authorization != null ? reviews.latest(tenant, authorization.terms().id(), actors.actor().userId()).orElse(null) : null;
        boolean reviewSource = authorize && authorization != null && authorization.matchesVoucher(voucher, now)
                && (PaymentPayeeReview.ended(authorization) || authorization.status() == PaymentAuthorization.Status.AUTHORIZED && !window);
        return new View(applicationId, app.businessReference().id(), context.roundNo(), app.version(), context.businessVersion(),
                voucher == null ? null : voucher.input().command().id(), voucher == null ? null : voucher.version(),
                voucher == null ? null : voucher.input().command().totals().payable(),
                authorization == null ? null : PaymentView.of(authorization, request, operation),
                review == null ? null : new PayeeReview(review.input().id(), review.version(), review.status().name(), review.input().requestedAt(), review.checkedAt(),
                        review.account() == null ? null : review.account().validUntil(), review.account() == null ? null : review.account().snapshot().maskedAccount(), review.issue() == null ? null : review.issue().name()),
                new Actions(authorize, finance && window, finance && PaymentView.queryable(authorization, operation),
                        finance && authorization != null && authorization.canRetire(operation, actors.actor().userId()),
                        reviewSource && (review == null || !review.active()), reviewSource && review != null && review.usable(now) && review.matchesSource(authorization, voucher, now)));
    }
    private static DomainException invalid() { return new DomainException("INVALID_PAYMENT_QUERY", "Only a positive roundNo is accepted for application payment status"); }
    /**
     * 双版本及凭证版本让用户的人工授权绑定其刚刚查看的事实。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record View(UUID applicationId, UUID businessId, int roundNo, long applicationVersion, long businessVersion, UUID voucherOperationId, Long voucherVersion,
                       Money payable, PaymentView payment, PayeeReview payeeReview, Actions actions) { }
    /**
     * 写入入口始终重新验证，提示不能代替实际权限。
     * @author owlzhangfq@gmail.com
     */
    public record Actions(boolean authorize, boolean voidAuthorization, boolean query, boolean retire, boolean reviewAccount, boolean authorizeReviewed) { }
    /**
     * 财务只获得新账户掩码和证据期限，申请人及出纳不获得这次未授权的账户读取结果。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PayeeReview(UUID id, long version, String status, Instant requestedAt, Instant checkedAt, Instant validUntil, String maskedAccount, String issue) { }
}
