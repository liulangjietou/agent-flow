package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.AdvanceRepaymentPort;
import io.agentflow.finance.FinanceResult;
import org.apache.commons.lang3.StringUtils;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 已保存的还款查询意图与独立财务确认分离，读取成功不会自行冲减余额。
 * @author owlzhangfq@gmail.com
 */
public record AdvanceRepaymentCheck(Input input, long version, Status status, Instant updatedAt, Instant leaseUntil,
                                    AdvanceRepaymentPort.Receipt receipt, UUID repaymentId, Issue issue) {
    /** 状态恢复保持租约和外部原件，不把排队、超时或查无伪装成已还款。 */
    public AdvanceRepaymentCheck {
        if (input == null || version < 1 || status == null || updatedAt == null || updatedAt.isBefore(input.requestedAt())) throw invalid();
        if (status == Status.RUNNING ? leaseUntil == null || !leaseUntil.isAfter(updatedAt) : leaseUntil != null) throw invalid();
        boolean observed = status == Status.CHECKED || status == Status.RECORDED;
        if (observed ? receipt == null || !receipt.matches(input.request(), updatedAt) || issue != null : receipt != null) throw invalid();
        if (status == Status.RECORDED ? repaymentId == null || receipt.status() != AdvanceRepaymentPort.Status.CONFIRMED : repaymentId != null) throw invalid();
        if ((status == Status.UNAVAILABLE || status == Status.VOIDED) != (issue != null)) throw invalid();
    }
    /** 仅冻结查询目标与原放款身份，金额由后续外部回执取得。 */
    public static AdvanceRepaymentCheck queue(Input input) { return new AdvanceRepaymentCheck(input, 1, Status.QUEUED, input.requestedAt(), null, null, null, null); }
    /** 同一任务只领取一次，崩溃超时结束本次查询，人工可建立新查询。 */
    public AdvanceRepaymentCheck claim(Instant now, Duration lease) {
        requireTime(now);
        if (status != Status.QUEUED || lease == null || lease.isZero() || lease.isNegative()) throw conflict();
        return new AdvanceRepaymentCheck(input, version + 1, Status.RUNNING, now, now.plus(lease), null, null, null);
    }
    /** 外部成功仅保存只读事实，真正冲减仍需要确认当前余额版本。 */
    public AdvanceRepaymentCheck complete(FinanceResult<AdvanceRepaymentPort.Receipt> result, Instant now) {
        requireRunning(now);
        if (expired(now)) return fail(Issue.TIMEOUT, now);
        if (result instanceof FinanceResult.Success<AdvanceRepaymentPort.Receipt> success) {
            if (!success.value().matches(input.request(), now)) return fail(Issue.INVALID_RESPONSE, now);
            return new AdvanceRepaymentCheck(input, version + 1, Status.CHECKED, now, null, success.value(), null, null);
        }
        if (result instanceof FinanceResult.Rejected<AdvanceRepaymentPort.Receipt>) return fail(Issue.SOURCE_UNAVAILABLE, now);
        return fail(result instanceof FinanceResult.Unavailable<AdvanceRepaymentPort.Receipt> unavailable ? Issue.valueOf(unavailable.failure().name()) : Issue.INVALID_RESPONSE, now);
    }
    /** 暂时失败保留原请求，不续期旧事实，不产生资金含义。 */
    public AdvanceRepaymentCheck fail(Issue reason, Instant now) {
        requireRunning(now); if (reason == null) throw invalid();
        return new AdvanceRepaymentCheck(input, version + 1, Status.UNAVAILABLE, now, null, null, null, reason);
    }
    /** 来源或人员失效使未完成查询结束，历史查询仍保留。 */
    public AdvanceRepaymentCheck voidSource(Instant now) {
        requireTime(now); if (!active()) throw conflict();
        return new AdvanceRepaymentCheck(input, version + 1, Status.VOIDED, now, null, null, null, Issue.SOURCE_CHANGED);
    }
    /** 已展示的收款事实单次消费，确认者必须是发起本次查询的独立财务。 */
    public AdvanceRepaymentCheck record(AdvanceRepayment repayment, Instant now) {
        requireTime(now);
        if (!usable(now) || repayment == null || !repayment.checkId().equals(input.id()) || !repayment.tenantId().equals(input.tenantId())
                || !repayment.recordedBy().equals(input.requestedBy()) || !repayment.recordedAt().equals(now) || !repayment.receipt().equals(receipt)) throw conflict();
        return new AdvanceRepaymentCheck(input, version + 1, Status.RECORDED, now, null, receipt, repayment.id(), null);
    }
    /** 有效期不因读取页面自动延长，待确认的原件必须仍在五分钟窗口内。 */
    public boolean usable(Instant now) { return status == Status.CHECKED && receipt.status() == AdvanceRepaymentPort.Status.CONFIRMED && receipt.matches(input.request(), now); }
    public boolean active() { return status == Status.QUEUED || status == Status.RUNNING; }
    public boolean expired(Instant now) { return status == Status.RUNNING && !now.isBefore(leaseUntil); }
    private void requireTime(Instant now) { if (now == null || now.isBefore(updatedAt)) throw conflict(); }
    private void requireRunning(Instant now) { requireTime(now); if (status != Status.RUNNING) throw conflict(); }
    private static DomainException invalid() { return new DomainException("INVALID_ADVANCE_REPAYMENT_CHECK", "Repayment check must preserve original source, lease and evidence"); }
    private static DomainException conflict() { return new DomainException("ADVANCE_REPAYMENT_CHECK_CONFLICT", "Repayment check no longer permits this transition"); }
    @Override public String toString() { return "AdvanceRepaymentCheck[id=" + input.id() + ", status=" + status + "]"; }

    /**
     * 原放款的成功修订和财务目标不可修改，外部凭据必须指向这笔借款。
     * @author owlzhangfq@gmail.com
     */
    public record Input(UUID id, String tenantId, UUID paymentId, long paymentVersion, String targetDigest,
                        AdvanceRepaymentPort.Request request, String requestedBy, Instant requestedAt) {
        /** 排队输入只由原放款和认证主体派生。 */
        public Input {
            if (id == null || StringUtils.isBlank(tenantId) || tenantId.length() > 64 || paymentId == null || paymentVersion < 1
                    || targetDigest == null || !targetDigest.matches("[a-f0-9]{64}") || request == null || StringUtils.isBlank(requestedBy)
                    || requestedBy.length() > 128 || requestedBy.equals(request.employeeId()) || requestedAt == null) throw invalid();
        }
        @Override public String toString() { return "AdvanceRepaymentCheckInput[id=" + id + "]"; }
    }
    /**
     * CHECKED 包含查无和未入账状态，只有明确确认才到 RECORDED。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { QUEUED, RUNNING, CHECKED, RECORDED, UNAVAILABLE, VOIDED }
    /**
     * 固定错误分类不包含外部响应正文。
     * @author owlzhangfq@gmail.com
     */
    public enum Issue { NOT_CONFIGURED, TARGET_CHANGED, TIMEOUT, CONNECTION, AUTHENTICATION, REMOTE_FAILURE, INVALID_RESPONSE,
        RESPONSE_TOO_LARGE, INTERNAL_ERROR, SOURCE_UNAVAILABLE, SOURCE_CHANGED }
}
