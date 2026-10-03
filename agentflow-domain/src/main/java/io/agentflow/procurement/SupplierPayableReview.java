package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceResult;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 财务针对实际批准采购读取原应付，短期证据只能由同一财务用于一次明确授权。
 * @author owlzhangfq@gmail.com
 */
public record SupplierPayableReview(Input input, long version, Status status, int attempts, Instant updatedAt, Instant leaseUntil,
                                     ProcurementPayablePort.Payable payable, Instant checkedAt, UUID consumedAuthorizationId, Issue issue) {
    /** 恢复历史仍核对读取时的批准来源与证据窗口，过期不会把旧结果变成新的可用余额。 */
    public SupplierPayableReview {
        if (input == null || version < 1 || status == null || attempts < 0 || updatedAt == null || updatedAt.isBefore(input.requestedAt())) throw invalid();
        if (status == Status.RUNNING ? attempts == 0 || leaseUntil == null || !leaseUntil.isAfter(updatedAt) || issue != null : leaseUntil != null) throw invalid();
        if (status == Status.READY || status == Status.CONSUMED) {
            if (attempts == 0 || payable == null || checkedAt == null || checkedAt.isBefore(input.requestedAt()) || checkedAt.isAfter(updatedAt) || issue != null) throw invalid();
            input.source().requireCurrentPayable(payable, checkedAt); input.source().requireCurrentPayable(payable, updatedAt);
            if (status == Status.READY && (!checkedAt.equals(updatedAt) || consumedAuthorizationId != null)
                    || status == Status.CONSUMED && consumedAuthorizationId == null) throw invalid();
        } else if (payable != null || checkedAt != null || consumedAuthorizationId != null) throw invalid();
        if ((status == Status.BLOCKED || status == Status.UNAVAILABLE || status == Status.VOIDED) && issue == null
                || (status == Status.BLOCKED || status == Status.UNAVAILABLE) && attempts == 0
                || status == Status.QUEUED && (attempts == 0 ? version != 1 || issue != null || !updatedAt.equals(input.requestedAt()) : issue != Issue.LEASE_EXPIRED)) throw invalid();
    }

    /** 只保存实际批准、财务身份和读取意图，不生成预留或付款授权。 */
    public static SupplierPayableReview queue(UUID id, ApprovedProcurementPayment source, String finance, Instant now) {
        return new SupplierPayableReview(new Input(id, source, finance, now), 1, Status.QUEUED, 0, now, null, null, null, null, null);
    }

    /** 后台领取只读请求，原输入与审批依据不会随配置变化而重建。 */
    public SupplierPayableReview claim(Instant now, Duration lease) {
        requireTime(now);
        if (status != Status.QUEUED || lease == null || lease.isZero() || lease.isNegative()) throw conflict();
        return new SupplierPayableReview(input, Math.incrementExact(version), Status.RUNNING, Math.incrementExact(attempts), now, now.plus(lease), null, null, null, null);
    }

    /** 同一原应付的匹配与账户必须保持，新的已付余额不能低于本次批准金额。 */
    public SupplierPayableReview complete(FinanceResult<ProcurementPayablePort.Payable> result, Instant now) {
        requireRunning(now); if (leaseExpired(now)) return expireLease(now);
        if (result instanceof FinanceResult.Success<ProcurementPayablePort.Payable> success) {
            var observed = success.value();
            if (!observed.matches(input.source().reservation().source().round().payable().request(), now)) return fail(Issue.INVALID_RESPONSE, now);
            try { input.source().requireCurrentPayable(observed, now); }
            catch (DomainException changed) { return changed(Status.BLOCKED, now, Issue.PAYABLE_CHANGED); }
            return new SupplierPayableReview(input, Math.incrementExact(version), Status.READY, attempts, now, null, observed, now, null, null);
        }
        if (result instanceof FinanceResult.Rejected<ProcurementPayablePort.Payable>) return changed(Status.BLOCKED, now, Issue.PAYABLE_REJECTED);
        return fail(result instanceof FinanceResult.Unavailable<ProcurementPayablePort.Payable> problem ? Issue.valueOf(problem.failure().name()) : Issue.INVALID_RESPONSE, now);
    }

    /** 暂时读取故障只记录失败分类，财务可发起新的读取而不能手填余额。 */
    public SupplierPayableReview fail(Issue problem, Instant now) {
        requireRunning(now); if (leaseExpired(now)) return expireLease(now); if (problem == null) throw invalid();
        return changed(Status.UNAVAILABLE, now, problem);
    }

    /** 只读请求没有预留副作用，过期租约重领原请求，迟到数据不能成为授权依据。 */
    public SupplierPayableReview expireLease(Instant now) {
        requireTime(now); if (!leaseExpired(now)) throw conflict(); return changed(Status.QUEUED, now, Issue.LEASE_EXPIRED);
    }

    /** 后台复核实际批准或当前财务人员失效时，只保留终止的读取意图。 */
    public SupplierPayableReview voidSource(Instant now) {
        requireTime(now); if (!active()) throw conflict(); return changed(Status.VOIDED, now, Issue.SOURCE_CHANGED);
    }

    /** 财务明确决定与单次证据消费同事务保存，不能替换来源、操作者或采用过期余额。 */
    public SupplierPayableReview consume(SupplierPaymentAuthorization authorization, Instant now) {
        requireTime(now);
        if (!usable(now) || authorization == null || !authorization.authorizedAt().equals(now) || !matchesAuthorization(authorization)) throw unavailable();
        return new SupplierPayableReview(input, Math.incrementExact(version), Status.CONSUMED, attempts, now, null, payable, checkedAt, authorization.id(), null);
    }

    /** 后续读取可证明这个原授权来自该次明确消费，证据到期不改写历史。 */
    public boolean supports(SupplierPaymentAuthorization authorization) {
        return status == Status.CONSUMED && authorization != null && consumedAuthorizationId.equals(authorization.id())
                && updatedAt.equals(authorization.authorizedAt()) && matchesAuthorization(authorization);
    }

    /** 有效期使用 ERP 原观察时刻，不能因本地刚收到响应再延长五分钟。 */
    public boolean usable(Instant now) {
        return status == Status.READY && now != null && !now.isBefore(updatedAt) && payable.matches(input.source().reservation().source().round().payable().request(), now);
    }
    public boolean active() { return status == Status.QUEUED || status == Status.RUNNING; }
    public boolean leaseExpired(Instant now) { return status == Status.RUNNING && !now.isBefore(leaseUntil); }

    private boolean matchesAuthorization(SupplierPaymentAuthorization authorization) {
        return authorization.source().equals(input.source()) && authorization.payable().equals(payable) && authorization.authorizedBy().equals(input.requestedBy());
    }
    private SupplierPayableReview changed(Status next, Instant now, Issue problem) {
        return new SupplierPayableReview(input, Math.incrementExact(version), next, attempts, now, null, null, null, null, problem);
    }
    private void requireRunning(Instant now) { requireTime(now); if (status != Status.RUNNING) throw conflict(); }
    private void requireTime(Instant now) { if (now == null || now.isBefore(updatedAt)) throw conflict(); }
    private static DomainException invalid() { return new DomainException("INVALID_SUPPLIER_PAYABLE_REVIEW", "Payable review must retain exact approved source, named finance actor and single-use evidence"); }
    private static DomainException conflict() { return new DomainException("SUPPLIER_PAYABLE_REVIEW_STATE_CONFLICT", "Payable review no longer allows this transition"); }
    private static DomainException unavailable() { return new DomainException("SUPPLIER_PAYABLE_REVIEW_UNAVAILABLE", "Fresh payable review for the current finance actor is required"); }

    /** 日志不展开供应商、原应付、账户或人员。 */
    @Override public String toString() { return "SupplierPayableReview[id=" + input.id() + ", status=" + status + "]"; }

    /**
     * 身份和目标来自实际批准申请，不能由财务输入一个任意 ERP 地址或替代账户。
     * @author owlzhangfq@gmail.com
     */
    public record Input(UUID id, ApprovedProcurementPayment source, String requestedBy, Instant requestedAt) {
        /** 财务与申请人分离，读取不得早于原审批实际批准时间。 */
        public Input {
            if (id == null || source == null || StringUtils.isBlank(requestedBy) || requestedBy.length() > 128 || !requestedBy.equals(requestedBy.trim())
                    || requestedBy.chars().anyMatch(Character::isISOControl) || requestedBy.equals(source.reservation().source().employeeId())
                    || requestedAt == null || requestedAt.isBefore(source.approval().approvedAt())) throw invalid();
        }
        /** 原批准内容仅供受控编排和持久化。 */
        @Override public String toString() { return "SupplierPayableReviewInput[id=" + id + "]"; }
    }

    /**
     * 可用读取与财务消费分别保留，消费不是 ERP 已预留或银行已支付。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { QUEUED, RUNNING, READY, CONSUMED, BLOCKED, UNAVAILABLE, VOIDED }

    /**
     * 对外可解释的封闭读取分类，不透传 ERP 正文。
     * @author owlzhangfq@gmail.com
     */
    public enum Issue { NOT_CONFIGURED, TARGET_CHANGED, TIMEOUT, CONNECTION, AUTHENTICATION, REMOTE_FAILURE, INVALID_RESPONSE,
        RESPONSE_TOO_LARGE, INTERNAL_ERROR, LEASE_EXPIRED, SOURCE_CHANGED, PAYABLE_REJECTED, PAYABLE_CHANGED }
}
