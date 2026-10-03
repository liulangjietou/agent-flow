package io.agentflow.procurement;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 采购原应付办理视图明确区分读取、财务授权和 ERP 预留，不以其中任一步声称银行到账。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierFinanceWorkspace {
    private final CurrentActor actors;
    private final SupplierPaymentAccess access;
    private final JdbcSupplierPayableReviewRepository reviews;
    private final JdbcSupplierPaymentAuthorizationRepository authorizations;
    private final JdbcSupplierPayableHoldRepository holds;

    /** 只返回必要投影，不序列化完整授权、命令摘要及外部凭据。 */
    public SupplierFinanceWorkspace(CurrentActor actors, SupplierPaymentAccess access, JdbcSupplierPayableReviewRepository reviews,
                                     JdbcSupplierPaymentAuthorizationRepository authorizations, JdbcSupplierPayableHoldRepository holds) {
        this.actors = actors; this.access = access; this.reviews = reviews; this.authorizations = authorizations; this.holds = holds;
    }

    /** 读取同一数据库快照，按钮只是提示，实际操作重新检查原轮次及版本。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View get(UUID requestId, Map<String, String> parameters) {
        if (!Set.of("roundNo").containsAll(parameters.keySet())) throw invalid();
        Integer round = null;
        if (parameters.containsKey("roundNo")) {
            try { if (!parameters.get("roundNo").matches("[1-9][0-9]{0,8}")) throw invalid(); round = Integer.parseInt(parameters.get("roundNo")); }
            catch (IllegalArgumentException failure) { throw invalid(); }
        }
        var context = access.read(requestId, round); var view = context.view(); var actor = actors.actor(); var now = Instant.now();
        var authorization = authorizations.forRequest(actor.tenantId(), requestId).filter(value -> value.source().approval().roundNo() == view.roundNo()).orElse(null);
        var active = authorizations.activeForRequest(actor.tenantId(), requestId).orElse(null);
        var review = context.finance() ? reviews.latest(actor.tenantId(), requestId, actor.userId()).filter(value -> value.input().source().approval().roundNo() == view.roundNo()).orElse(null) : null;
        var hold = authorization == null ? null : holds.find(actor.tenantId(), authorization.id()).orElse(null);
        var retirement = authorization == null ? null : authorizations.retirement(actor.tenantId(), authorization.id()).orElse(null);
        boolean source = context.finance() && view.status() == ApplicationStatus.APPROVED && view.approval() != null
                && view.approval().applicationVersion() == view.applicationVersion() && active == null;
        boolean actionable = context.finance() && hold != null && retirement == null;
        return new View(requestId, view.applicationId(), view.roundNo(), view.applicationVersion(), view.requestVersion(), view.approval() == null ? null : view.content().amount(),
                review == null ? null : new Review(review.input().id(), review.version(), review.status(), review.input().requestedAt(), review.checkedAt(),
                        deadline(review), review.payable() == null ? null : review.payable().settled(), review.payable() == null ? null : review.payable().outstanding(),
                        review.payable() == null ? null : review.payable().account().maskedAccount(), review.issue()),
                authorization == null ? null : new Authorization(authorization.id(), authorization.authorizedBy(), authorization.authorizedAt(), authorization.expiresAt(),
                        authorization.payable().account().maskedAccount(), retirement == null ? null : retirement.retiredAt(), retirement == null ? null : retirement.basis()),
                hold == null ? null : new Hold(hold.version(), hold.status(), hold.updatedAt(), hold.observation() == null ? null : hold.observation().observedAt(), hold.failure()),
                new Actions(source && (review == null || !review.active()), source && review != null && review.usable(now)
                        && review.input().source().approvedRequestVersion() == view.requestVersion(),
                        actionable && !hold.running() && hold.status() != SupplierPayableHoldOperation.Status.QUEUED && hold.dispatches() > 0,
                        actionable && hold.status() == SupplierPayableHoldOperation.Status.NOT_FOUND && hold.highestRevision() == 0 && hold.conflictingObservation() == null && now.isBefore(hold.command().sendDeadline()),
                        actionable && hold.retirementBasis() != null));
    }
    private static Instant deadline(SupplierPayableReview review) {
        if (review.payable() == null) return null;
        var observedLimit = review.payable().observedAt().plus(ProcurementPayablePort.MAX_EVIDENCE_AGE);
        return review.payable().validUntil().isBefore(observedLimit) ? review.payable().validUntil() : observedLimit;
    }
    private static DomainException invalid() { return new DomainException("INVALID_SUPPLIER_PAYMENT_QUERY", "Only a positive roundNo is accepted for supplier payment status"); }

    /**
     * 待授权的 ERP 读取只向发起该复核且仍有权限的财务展示。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record View(UUID requestId, UUID applicationId, int roundNo, long applicationVersion, long requestVersion, Money approvedAmount,
                       Review review, Authorization authorization, Hold hold, Actions actions) { }
    /**
     * 余额与掩码用于本次财务决定，隐藏内部账户及查验凭据。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Review(UUID id, long version, SupplierPayableReview.Status status, Instant requestedAt, Instant checkedAt, Instant validUntil,
                         Money settled, Money outstanding, String maskedAccount, SupplierPayableReview.Issue issue) { }
    /**
     * 结束是原授权的独立历史事实，不代表真实 ERP 预留被释放。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Authorization(UUID id, String authorizedBy, Instant authorizedAt, Instant expiresAt, String maskedAccount,
                                Instant retiredAt, SupplierPayableHoldOperation.RetirementBasis retirementBasis) { }
    /**
     * 预留状态不包含可用于重新执行的完整命令。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Hold(long version, SupplierPayableHoldOperation.Status status, Instant updatedAt, Instant observedAt, SupplierPayableHoldOperation.Failure failure) { }
    /**
     * 操作能力来自当前字段可读性、岗位、任职及实际状态。
     * @author owlzhangfq@gmail.com
     */
    public record Actions(boolean review, boolean authorize, boolean query, boolean retry, boolean retire) { }
}
