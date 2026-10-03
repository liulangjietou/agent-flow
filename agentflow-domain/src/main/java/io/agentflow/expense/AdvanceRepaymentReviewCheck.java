package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.AdvanceRepaymentAdjustmentPort;
import io.agentflow.finance.FinanceResult;
import org.apache.commons.lang3.StringUtils;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 原还款复核的持久只读任务；完成查询不自动解除冻结或增加未还款。
 * @author owlzhangfq@gmail.com
 */
public record AdvanceRepaymentReviewCheck(Input input, long version, Status status, Instant updatedAt, Instant leaseUntil,
        AdvanceRepaymentAdjustmentPort.Receipt receipt, UUID resolutionId, Issue issue) {
    /** 保存租约与结果的明确边界，查无或不完整依据不能伪装成已调整。 */
    public AdvanceRepaymentReviewCheck {
        if (input == null || version < 1 || status == null || updatedAt == null || updatedAt.isBefore(input.requestedAt())) throw invalid();
        if (status == Status.RUNNING ? leaseUntil == null || !leaseUntil.isAfter(updatedAt) : leaseUntil != null) throw invalid();
        boolean observed = status == Status.CHECKED || status == Status.RESOLVED;
        if (observed ? receipt == null || !receipt.matches(input.request(), updatedAt) || issue != null : receipt != null) throw invalid();
        if (status == Status.RESOLVED ? resolutionId == null || receipt.status() == AdvanceRepaymentAdjustmentPort.Status.UNRESOLVED : resolutionId != null) throw invalid();
        if ((status == Status.UNAVAILABLE || status == Status.VOIDED) != (issue != null)) throw invalid();
    }
    /** 先持久化原还款和固定目标，后台才能调用外部读取。 */
    public static AdvanceRepaymentReviewCheck queue(Input input) { return new AdvanceRepaymentReviewCheck(input, 1, Status.QUEUED, input.requestedAt(), null, null, null, null); }
    /** 单次领取释放事务后查询，超时的旧执行者不能覆盖新意图。 */
    public AdvanceRepaymentReviewCheck claim(Instant at, Duration lease) {
        requireTime(at); if (status != Status.QUEUED || lease == null || lease.isNegative() || lease.isZero()) throw conflict();
        return new AdvanceRepaymentReviewCheck(input, version + 1, Status.RUNNING, at, at.plus(lease), null, null, null);
    }
    /** 外部成功只形成待确认的复核证据，不在此处修改借款余额。 */
    public AdvanceRepaymentReviewCheck complete(FinanceResult<AdvanceRepaymentAdjustmentPort.Receipt> result, Instant at) {
        requireRunning(at); if (expired(at)) return fail(Issue.TIMEOUT, at);
        if (result instanceof FinanceResult.Success<AdvanceRepaymentAdjustmentPort.Receipt> success) {
            if (!success.value().matches(input.request(), at)) return fail(Issue.INVALID_RESPONSE, at);
            return new AdvanceRepaymentReviewCheck(input, version + 1, Status.CHECKED, at, null, success.value(), null, null);
        }
        if (result instanceof FinanceResult.Rejected<AdvanceRepaymentAdjustmentPort.Receipt>) return fail(Issue.SOURCE_UNAVAILABLE, at);
        return fail(result instanceof FinanceResult.Unavailable<AdvanceRepaymentAdjustmentPort.Receipt> unavailable ? Issue.valueOf(unavailable.failure().name()) : Issue.INVALID_RESPONSE, at);
    }
    /** 超时和传输失败不带财务结论，财务可明确发起新复核。 */
    public AdvanceRepaymentReviewCheck fail(Issue reason, Instant at) {
        requireRunning(at); if (reason == null) throw invalid();
        return new AdvanceRepaymentReviewCheck(input, version + 1, Status.UNAVAILABLE, at, null, null, null, reason);
    }
    /** 原来源或操作者已失效时停止本次只读任务，旧依据不能续期。 */
    public AdvanceRepaymentReviewCheck voidSource(Instant at) {
        requireTime(at); if (!active()) throw conflict();
        return new AdvanceRepaymentReviewCheck(input, version + 1, Status.VOIDED, at, null, null, null, Issue.SOURCE_CHANGED);
    }
    /** 本次查询只供发起复核的财务消费一次，决定必须引用精确外部原件。 */
    public AdvanceRepaymentReviewCheck resolve(AdvanceRepaymentResolution decision, Instant at) {
        requireTime(at);
        if (!usable(at) || decision == null || !input.id().equals(decision.checkId()) || !input.tenantId().equals(decision.tenantId())
                || !input.requestedBy().equals(decision.resolvedBy()) || !at.equals(decision.resolvedAt()) || !receipt.equals(decision.receipt())) throw conflict();
        return new AdvanceRepaymentReviewCheck(input, version + 1, Status.RESOLVED, at, null, receipt, decision.id(), null);
    }
    /** 原还款仍有效或真实退款完整入账才可确认，其余仅展示未核清。 */
    public boolean usable(Instant at) { return status == Status.CHECKED && receipt.status() != AdvanceRepaymentAdjustmentPort.Status.UNRESOLVED && receipt.matches(input.request(), at); }
    public boolean active() { return status == Status.QUEUED || status == Status.RUNNING; }
    public boolean expired(Instant at) { return status == Status.RUNNING && !at.isBefore(leaseUntil); }
    private void requireTime(Instant at) { if (at == null || at.isBefore(updatedAt)) throw conflict(); }
    private void requireRunning(Instant at) { requireTime(at); if (status != Status.RUNNING) throw conflict(); }
    private static DomainException invalid() { return new DomainException("INVALID_REPAYMENT_REVIEW_CHECK", "Repayment review must preserve original receipt, lease and evidence"); }
    private static DomainException conflict() { return new DomainException("REPAYMENT_REVIEW_CHECK_CONFLICT", "Repayment review no longer permits this transition"); }
    @Override public String toString() { return "RepaymentReviewCheck[id=" + input.id() + ", status=" + status + "]"; }

    /**
     * 原还款及网关目标不可由前端或外部回执替换。
     * @author owlzhangfq@gmail.com
     */
    public record Input(UUID id, String tenantId, String targetDigest, AdvanceRepaymentAdjustmentPort.Request request, String requestedBy, Instant requestedAt) {
        /** 复核仅由独立财务发起，完整原还款从本地已确认记录取得。 */
        public Input {
            if (id == null || StringUtils.isBlank(tenantId) || tenantId.length() > 64 || targetDigest == null || !targetDigest.matches("[a-f0-9]{64}")
                    || request == null || StringUtils.isBlank(requestedBy) || requestedBy.length() > 128 || requestedBy.equals(request.original().request().employeeId()) || requestedAt == null) throw invalid();
        }
        @Override public String toString() { return "RepaymentReviewInput[id=" + id + "]"; }
    }
    /**
     * 只读任务与真正人工裁决分开保存。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { QUEUED, RUNNING, CHECKED, RESOLVED, UNAVAILABLE, VOIDED }
    /**
     * 固定错误分类不保留外部响应正文。
     * @author owlzhangfq@gmail.com
     */
    public enum Issue { NOT_CONFIGURED, TARGET_CHANGED, TIMEOUT, CONNECTION, AUTHENTICATION, REMOTE_FAILURE, INVALID_RESPONSE, RESPONSE_TOO_LARGE, INTERNAL_ERROR, SOURCE_UNAVAILABLE, SOURCE_CHANGED }
}
