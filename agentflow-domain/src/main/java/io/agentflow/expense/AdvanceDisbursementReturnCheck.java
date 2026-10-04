package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.AdvanceDisbursementReturnPort;
import io.agentflow.finance.FinanceResult;
import org.apache.commons.lang3.StringUtils;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 原放款复核的持久只读任务；完成查询不自动解除冻结或减少未还款。
 * @author owlzhangfq@gmail.com
 */
public record AdvanceDisbursementReturnCheck(Input input, long version, Status status, Instant updatedAt, Instant leaseUntil,
        AdvanceDisbursementReturnPort.Receipt receipt, UUID resolutionId, Issue issue, Boolean reviewRequired) {
    /** 保存租约与结果的明确边界，查无或不完整依据不能伪装成已调整。 */
    public AdvanceDisbursementReturnCheck {
        if (input == null || version < 1 || status == null || updatedAt == null || updatedAt.isBefore(input.requestedAt())) throw invalid();
        if (status == Status.RUNNING ? leaseUntil == null || !leaseUntil.isAfter(updatedAt) : leaseUntil != null) throw invalid();
        boolean observed = status == Status.CHECKED || status == Status.RESOLVED;
        if (observed ? receipt == null || !receipt.matches(input.request(), updatedAt) || issue != null : receipt != null) throw invalid();
        if (status == Status.RESOLVED ? resolutionId == null || receipt.status() == AdvanceDisbursementReturnPort.Status.UNRESOLVED : resolutionId != null) throw invalid();
        if ((status == Status.UNAVAILABLE || status == Status.VOIDED) != (issue != null)) throw invalid();
        if (reviewRequired != null && !observed) throw invalid();
    }
    /** 先持久化原放款和固定目标，后台才能调用外部读取。 */
    public static AdvanceDisbursementReturnCheck queue(Input input) { return new AdvanceDisbursementReturnCheck(input, 1, Status.QUEUED, input.requestedAt(), null, null, null, null, null); }
    /** 单次领取释放事务后查询，超时的旧执行者不能覆盖新意图。 */
    public AdvanceDisbursementReturnCheck claim(Instant at, Duration lease) {
        requireTime(at); if (status != Status.QUEUED || lease == null || lease.isNegative() || lease.isZero()) throw conflict();
        return new AdvanceDisbursementReturnCheck(input, version + 1, Status.RUNNING, at, at.plus(lease), null, null, null, null);
    }
    /** 外部成功只形成待确认的复核证据，不在此处修改借款余额。 */
    public AdvanceDisbursementReturnCheck complete(FinanceResult<AdvanceDisbursementReturnPort.Receipt> result, Instant at) {
        requireRunning(at); if (expired(at)) return fail(Issue.TIMEOUT, at);
        if (result instanceof FinanceResult.Success<AdvanceDisbursementReturnPort.Receipt> success) {
            if (!success.value().matches(input.request(), at)) return fail(Issue.INVALID_RESPONSE, at);
            return new AdvanceDisbursementReturnCheck(input, version + 1, Status.CHECKED, at, null, success.value(), null, null, null);
        }
        if (result instanceof FinanceResult.Rejected<AdvanceDisbursementReturnPort.Receipt>) return fail(Issue.SOURCE_UNAVAILABLE, at);
        return fail(result instanceof FinanceResult.Unavailable<AdvanceDisbursementReturnPort.Receipt> unavailable ? Issue.valueOf(unavailable.failure().name()) : Issue.INVALID_RESPONSE, at);
    }
    /** 超时和传输失败不带财务结论，财务可明确发起新复核。 */
    public AdvanceDisbursementReturnCheck fail(Issue reason, Instant at) {
        requireRunning(at); if (reason == null) throw invalid();
        return new AdvanceDisbursementReturnCheck(input, version + 1, Status.UNAVAILABLE, at, null, null, null, reason, null);
    }
    /** 原来源或操作者已失效时停止本次只读任务，旧依据不能续期。 */
    public AdvanceDisbursementReturnCheck voidSource(Instant at) {
        requireTime(at); if (!active()) throw conflict();
        return new AdvanceDisbursementReturnCheck(input, version + 1, Status.VOIDED, at, null, null, null, Issue.SOURCE_CHANGED, null);
    }
    /** 保存本次与历史的比较结论；空值兼容旧查询，已确认的比较结果不得改写。 */
    public AdvanceDisbursementReturnCheck withReviewRequirement(boolean required) {
        if (status != Status.CHECKED || reviewRequired != null && reviewRequired != required) throw conflict();
        return new AdvanceDisbursementReturnCheck(input, version, status, updatedAt, leaseUntil, receipt, resolutionId, issue, required);
    }
    /** 本次查询只供发起复核的财务消费一次，决定必须引用精确外部原件。 */
    public AdvanceDisbursementReturnCheck resolve(AdvanceDisbursementReturn decision, Instant at) {
        requireTime(at);
        if (!usable(at) || decision == null || !input.id().equals(decision.checkId()) || !input.tenantId().equals(decision.tenantId())
                || !input.requestedBy().equals(decision.resolvedBy()) || !at.equals(decision.resolvedAt()) || !receipt.equals(decision.receipt())) throw conflict();
        return new AdvanceDisbursementReturnCheck(input, version + 1, Status.RESOLVED, at, null, receipt, decision.id(), null, reviewRequired);
    }
    /** 原放款仍有效或真实银行退回完整入账才可确认，其余仅展示未核清。 */
    public boolean usable(Instant at) { return status == Status.CHECKED && receipt.status() != AdvanceDisbursementReturnPort.Status.UNRESOLVED && receipt.matches(input.request(), at); }
    public boolean active() { return status == Status.QUEUED || status == Status.RUNNING; }
    public boolean expired(Instant at) { return status == Status.RUNNING && !at.isBefore(leaseUntil); }
    private void requireTime(Instant at) { if (at == null || at.isBefore(updatedAt)) throw conflict(); }
    private void requireRunning(Instant at) { requireTime(at); if (status != Status.RUNNING) throw conflict(); }
    private static DomainException invalid() { return new DomainException("INVALID_DISBURSEMENT_RETURN_CHECK", "Disbursement review must preserve original receipt, lease and evidence"); }
    private static DomainException conflict() { return new DomainException("DISBURSEMENT_RETURN_CHECK_CONFLICT", "Disbursement review no longer permits this transition"); }
    @Override public String toString() { return "DisbursementReturnCheck[id=" + input.id() + ", status=" + status + "]"; }

    /**
     * 原放款及网关目标不可由前端或外部回执替换。
     * @author owlzhangfq@gmail.com
     */
    public record Input(UUID id, String tenantId, String targetDigest, long paymentVersion, AdvanceDisbursementReturnPort.Request request, String requestedBy, Instant requestedAt) {
        /** 复核仅由独立财务发起，完整原放款从本地已确认记录取得。 */
        public Input {
            if (id == null || StringUtils.isBlank(tenantId) || tenantId.length() > 64 || targetDigest == null || !targetDigest.matches("[a-f0-9]{64}")
                    || paymentVersion < 1 || request == null || !tenantId.equals(request.command().tenantId()) || StringUtils.isBlank(requestedBy) || requestedBy.length() > 128 || requestedBy.equals(request.command().payee().employeeId()) || requestedBy.equals(request.command().authorization().executedBy()) || requestedAt == null) throw invalid();
        }
        @Override public String toString() { return "DisbursementReturnInput[id=" + id + "]"; }
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
