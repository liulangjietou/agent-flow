package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 独立冲销凭证核验的持久只读任务；完成查询不自动登记，也不调整资金。
 * @author owlzhangfq@gmail.com
 */
public record VoucherReversalCheck(Input input, long version, Status status, Instant updatedAt, Instant leaseUntil,
        VoucherReversalPort.Receipt receipt, UUID recordId, Issue issue) {
    /** 保存租约与结果的明确边界，查无或不完整反向凭证不能伪装成已登记。 */
    public VoucherReversalCheck {
        if (input == null || version < 1 || status == null || updatedAt == null || updatedAt.isBefore(input.requestedAt())) throw invalid();
        if (status == Status.RUNNING ? leaseUntil == null || !leaseUntil.isAfter(updatedAt) : leaseUntil != null) throw invalid();
        boolean observed = status == Status.CHECKED || status == Status.RECORDED;
        if (observed ? receipt == null || !receipt.matches(input.request(), updatedAt) || issue != null : receipt != null) throw invalid();
        if (status == Status.RECORDED ? recordId == null || receipt.status() == VoucherReversalPort.Status.UNRESOLVED : recordId != null) throw invalid();
        if ((status == Status.UNAVAILABLE || status == Status.VOIDED) != (issue != null)) throw invalid();
    }
    /** 先固定原凭证修订及财务目标，后台才能读取独立反向凭证。 */
    public static VoucherReversalCheck queue(Input input) { return new VoucherReversalCheck(input, 1, Status.QUEUED, input.requestedAt(), null, null, null, null); }
    /** 单次领取释放事务后查询，超时的旧执行者不能覆盖新意图。 */
    public VoucherReversalCheck claim(Instant at, Duration lease) {
        requireTime(at); if (status != Status.QUEUED || lease == null || lease.isNegative() || lease.isZero()) throw conflict();
        return new VoucherReversalCheck(input, version + 1, Status.RUNNING, at, at.plus(lease), null, null, null);
    }
    /** 外部成功只形成待确认的复核证据，不在此处登记冲销或改变原凭证。 */
    public VoucherReversalCheck complete(FinanceResult<VoucherReversalPort.Receipt> result, Instant at) {
        requireRunning(at); if (expired(at)) return fail(Issue.TIMEOUT, at);
        if (result instanceof FinanceResult.Success<VoucherReversalPort.Receipt> success) {
            if (!success.value().matches(input.request(), at)) return fail(Issue.INVALID_RESPONSE, at);
            return new VoucherReversalCheck(input, version + 1, Status.CHECKED, at, null, success.value(), null, null);
        }
        if (result instanceof FinanceResult.Rejected<VoucherReversalPort.Receipt>) return fail(Issue.SOURCE_UNAVAILABLE, at);
        return fail(result instanceof FinanceResult.Unavailable<VoucherReversalPort.Receipt> unavailable ? Issue.valueOf(unavailable.failure().name()) : Issue.INVALID_RESPONSE, at);
    }
    /** 超时和传输失败不带财务结论，财务可明确发起新复核。 */
    public VoucherReversalCheck fail(Issue reason, Instant at) {
        requireRunning(at); if (reason == null) throw invalid();
        return new VoucherReversalCheck(input, version + 1, Status.UNAVAILABLE, at, null, null, null, reason);
    }
    /** 原来源或操作者已失效时停止本次只读任务，旧依据不能续期。 */
    public VoucherReversalCheck voidSource(Instant at) {
        requireTime(at); if (!active()) throw conflict();
        return new VoucherReversalCheck(input, version + 1, Status.VOIDED, at, null, null, null, Issue.SOURCE_CHANGED);
    }
    /** 本次查询只供发起复核的财务消费一次，决定必须引用精确外部原件。 */
    public VoucherReversalCheck record(VoucherReversalRecord decision, Instant at) {
        requireTime(at);
        if (!usable(at) || decision == null || decision.operationVersion() < input.originalVersion() || !input.id().equals(decision.checkId()) || !input.tenantId().equals(decision.tenantId())
                || !input.requestedBy().equals(decision.recordedBy()) || !at.equals(decision.recordedAt()) || !receipt.equals(decision.receipt())) throw conflict();
        return new VoucherReversalCheck(input, version + 1, Status.RECORDED, at, null, receipt, decision.id(), null);
    }
    /** 只有完整的反向过账事实才能登记，其余仅展示未核清。 */
    public boolean usable(Instant at) { return status == Status.CHECKED && receipt.status() != VoucherReversalPort.Status.UNRESOLVED && receipt.matches(input.request(), at); }
    public boolean active() { return status == Status.QUEUED || status == Status.RUNNING; }
    public boolean expired(Instant at) { return status == Status.RUNNING && !at.isBefore(leaseUntil); }
    private void requireTime(Instant at) { if (at == null || at.isBefore(updatedAt)) throw conflict(); }
    private void requireRunning(Instant at) { requireTime(at); if (status != Status.RUNNING) throw conflict(); }
    private static DomainException invalid() { return new DomainException("INVALID_VOUCHER_REVERSAL_CHECK", "Voucher reversal check must preserve original receipt, lease and evidence"); }
    private static DomainException conflict() { return new DomainException("VOUCHER_REVERSAL_CHECK_CONFLICT", "Voucher reversal check no longer permits this transition"); }
    @Override public String toString() { return "VoucherReversalCheck[id=" + input.id() + ", status=" + status + "]"; }

    /**
     * 原凭证及网关目标不可由前端或外部回执替换。
     * @author owlzhangfq@gmail.com
     */
    public record Input(UUID id, String tenantId, String targetDigest, long originalVersion, VoucherReversalPort.Request request, String requestedBy, Instant requestedAt) {
        /** 复核仅由独立财务发起，完整原凭证从本地已接受的修订取得。 */
        public Input {
            if (id == null || StringUtils.isBlank(tenantId) || tenantId.length() > 64 || targetDigest == null || !targetDigest.matches("[a-f0-9]{64}")
                    || originalVersion < 1 || request == null || !tenantId.equals(request.command().tenantId()) || StringUtils.isBlank(requestedBy) || requestedBy.length() > 128 || !VoucherDisputeResolution.independent(request.command(), requestedBy) || requestedAt == null) throw invalid();
        }
        @Override public String toString() { return "VoucherReversalInput[id=" + id + "]"; }
    }
    /**
     * 只读任务与真正人工登记分开保存。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { QUEUED, RUNNING, CHECKED, RECORDED, UNAVAILABLE, VOIDED }
    /**
     * 固定错误分类不保留外部响应正文。
     * @author owlzhangfq@gmail.com
     */
    public enum Issue { NOT_CONFIGURED, TARGET_CHANGED, TIMEOUT, CONNECTION, AUTHENTICATION, REMOTE_FAILURE, INVALID_RESPONSE, RESPONSE_TOO_LARGE, INTERNAL_ERROR, SOURCE_UNAVAILABLE, SOURCE_CHANGED }
}
