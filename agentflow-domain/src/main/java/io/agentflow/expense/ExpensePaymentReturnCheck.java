package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.ExpensePaymentReturnPort;
import io.agentflow.finance.FinanceResult;
import org.apache.commons.lang3.StringUtils;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 原报销付款复核的持久只读任务；查询不自动登记退回或恢复结算。
 * @author owlzhangfq@gmail.com
 */
public record ExpensePaymentReturnCheck(Input input, long version, Status status, Instant updatedAt, Instant leaseUntil,
        ExpensePaymentReturnPort.Receipt receipt, UUID resolutionId, Issue issue) {
    /** 保存租约与结果的明确边界，查无或不完整依据不能伪装成已登记。 */
    public ExpensePaymentReturnCheck {
        if (input == null || version < 1 || status == null || updatedAt == null || updatedAt.isBefore(input.requestedAt())) throw invalid();
        if (status == Status.RUNNING ? leaseUntil == null || !leaseUntil.isAfter(updatedAt) : leaseUntil != null) throw invalid();
        boolean observed = status == Status.CHECKED || status == Status.RESOLVED;
        if (observed ? receipt == null || !receipt.matches(input.request(), updatedAt) || issue != null : receipt != null) throw invalid();
        if (status == Status.RESOLVED ? resolutionId == null || receipt.status() == ExpensePaymentReturnPort.Status.UNRESOLVED : resolutionId != null) throw invalid();
        if ((status == Status.UNAVAILABLE || status == Status.VOIDED) != (issue != null)) throw invalid();
    }
    /** 先持久化原报销付款和固定目标，后台才能调用外部读取。 */
    public static ExpensePaymentReturnCheck queue(Input input) { return new ExpensePaymentReturnCheck(input, 1, Status.QUEUED, input.requestedAt(), null, null, null, null); }
    /** 单次领取释放事务后查询，超时的旧执行者不能覆盖新意图。 */
    public ExpensePaymentReturnCheck claim(Instant at, Duration lease) {
        requireTime(at); if (status != Status.QUEUED || lease == null || lease.isNegative() || lease.isZero()) throw conflict();
        return new ExpensePaymentReturnCheck(input, version + 1, Status.RUNNING, at, at.plus(lease), null, null, null);
    }
    /** 外部成功只形成待确认的复核证据，不在此处登记资金退回。 */
    public ExpensePaymentReturnCheck complete(FinanceResult<ExpensePaymentReturnPort.Receipt> result, Instant at) {
        requireRunning(at); if (expired(at)) return fail(Issue.TIMEOUT, at);
        if (result instanceof FinanceResult.Success<ExpensePaymentReturnPort.Receipt> success) {
            if (!success.value().matches(input.request(), at)) return fail(Issue.INVALID_RESPONSE, at);
            return new ExpensePaymentReturnCheck(input, version + 1, Status.CHECKED, at, null, success.value(), null, null);
        }
        if (result instanceof FinanceResult.Rejected<ExpensePaymentReturnPort.Receipt>) return fail(Issue.SOURCE_UNAVAILABLE, at);
        return fail(result instanceof FinanceResult.Unavailable<ExpensePaymentReturnPort.Receipt> unavailable ? Issue.valueOf(unavailable.failure().name()) : Issue.INVALID_RESPONSE, at);
    }
    /** 超时和传输失败不带财务结论，财务可明确发起新复核。 */
    public ExpensePaymentReturnCheck fail(Issue reason, Instant at) {
        requireRunning(at); if (reason == null) throw invalid();
        return new ExpensePaymentReturnCheck(input, version + 1, Status.UNAVAILABLE, at, null, null, null, reason);
    }
    /** 原来源或操作者已失效时停止本次只读任务，旧依据不能续期。 */
    public ExpensePaymentReturnCheck voidSource(Instant at) {
        requireTime(at); if (!active()) throw conflict();
        return new ExpensePaymentReturnCheck(input, version + 1, Status.VOIDED, at, null, null, null, Issue.SOURCE_CHANGED);
    }
    /** 本次查询只供发起复核的财务消费一次，决定必须引用精确外部原件。 */
    public ExpensePaymentReturnCheck resolve(ExpensePaymentReturn decision, Instant at) {
        requireTime(at);
        if (!usable(at) || decision == null || !input.id().equals(decision.checkId()) || !input.tenantId().equals(decision.tenantId())
                || !input.requestedBy().equals(decision.registeredBy()) || !at.equals(decision.registeredAt()) || !receipt.equals(decision.receipt())) throw conflict();
        return new ExpensePaymentReturnCheck(input, version + 1, Status.RESOLVED, at, null, receipt, decision.id(), null);
    }
    /** 原报销付款仍有效或真实银行退回完整入账才可确认，其余仅展示未核清。 */
    public boolean usable(Instant at) { return status == Status.CHECKED && receipt.status() != ExpensePaymentReturnPort.Status.UNRESOLVED && receipt.matches(input.request(), at); }
    public boolean active() { return status == Status.QUEUED || status == Status.RUNNING; }
    public boolean expired(Instant at) { return status == Status.RUNNING && !at.isBefore(leaseUntil); }
    private void requireTime(Instant at) { if (at == null || at.isBefore(updatedAt)) throw conflict(); }
    private void requireRunning(Instant at) { requireTime(at); if (status != Status.RUNNING) throw conflict(); }
    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_PAYMENT_RETURN_CHECK", "ExpensePayment review must preserve original receipt, lease and evidence"); }
    private static DomainException conflict() { return new DomainException("EXPENSE_PAYMENT_RETURN_CHECK_CONFLICT", "ExpensePayment review no longer permits this transition"); }
    @Override public String toString() { return "ExpensePaymentReturnCheck[id=" + input.id() + ", status=" + status + "]"; }

    /**
     * 原报销付款及网关目标不可由前端或外部回执替换。
     * @author owlzhangfq@gmail.com
     */
    public record Input(UUID id, String tenantId, String targetDigest, long paymentVersion, ExpensePaymentReturnPort.Request request, String requestedBy, Instant requestedAt) {
        /** 复核仅由独立财务发起，完整原报销付款从本地已确认记录取得。 */
        public Input {
            if (id == null || StringUtils.isBlank(tenantId) || tenantId.length() > 64 || targetDigest == null || !targetDigest.matches("[a-f0-9]{64}")
                    || paymentVersion < 1 || request == null || !tenantId.equals(request.command().tenantId()) || StringUtils.isBlank(requestedBy) || requestedBy.length() > 128 || requestedBy.equals(request.command().payee().employeeId()) || requestedBy.equals(request.command().authorization().executedBy()) || requestedAt == null) throw invalid();
        }
        @Override public String toString() { return "ExpensePaymentReturnInput[id=" + id + "]"; }
    }
    /**
     * 只读任务与真正人工登记分开保存。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { QUEUED, RUNNING, CHECKED, RESOLVED, UNAVAILABLE, VOIDED }
    /**
     * 固定错误分类不保留外部响应正文。
     * @author owlzhangfq@gmail.com
     */
    public enum Issue { NOT_CONFIGURED, TARGET_CHANGED, TIMEOUT, CONNECTION, AUTHENTICATION, REMOTE_FAILURE, INVALID_RESPONSE, RESPONSE_TOO_LARGE, INTERNAL_ERROR, SOURCE_UNAVAILABLE, SOURCE_CHANGED }
}
