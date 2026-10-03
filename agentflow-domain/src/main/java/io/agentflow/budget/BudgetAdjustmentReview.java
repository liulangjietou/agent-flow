package io.agentflow.budget;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceResult;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 财务在批准后读取台账，证据仅能由同一人明确消费一次，读取本身不授权额度写入。
 * @author owlzhangfq@gmail.com
 */
public record BudgetAdjustmentReview(Input input, long version, Status status, Instant updatedAt, Instant startedAt,
        Instant leaseUntil, BudgetLedgerPort.Snapshot ledger, Instant checkedAt, UUID consumedOperationId, Issue issue) {
    /** 单次读取只有排队、领取、结论与消费四版，恢复历史按当时证据窗口校验。 */
    public BudgetAdjustmentReview {
        if (input == null || status == null || version < 1 || version > 4 || updatedAt == null || updatedAt.isBefore(input.requestedAt())) throw invalid();
        if (status == Status.QUEUED) {
            if (version != 1 || !updatedAt.equals(input.requestedAt()) || startedAt != null || leaseUntil != null || issue != null) throw invalid();
        } else if (status == Status.VOIDED && startedAt == null) {
            if (version != 2 || leaseUntil != null || issue != Issue.SOURCE_CHANGED) throw invalid();
        } else {
            if (startedAt == null || startedAt.isBefore(input.requestedAt()) || startedAt.isAfter(updatedAt) || leaseUntil == null || !leaseUntil.isAfter(startedAt)) throw invalid();
            if (status == Status.RUNNING && (version != 2 || !updatedAt.equals(startedAt) || issue != null)) throw invalid();
            if (status != Status.RUNNING && version != (status == Status.CONSUMED ? 4 : 3)) throw invalid();
        }
        if (status == Status.READY || status == Status.CONSUMED) {
            if (ledger == null || checkedAt == null || checkedAt.isBefore(startedAt) || !checkedAt.isBefore(leaseUntil)
                    || checkedAt.isAfter(updatedAt) || issue != null) throw invalid();
            input.source().requireCurrentLedger(ledger, checkedAt); input.source().requireCurrentLedger(ledger, updatedAt);
            if (status == Status.READY && (!checkedAt.equals(updatedAt) || consumedOperationId != null)
                    || status == Status.CONSUMED && consumedOperationId == null) throw invalid();
        } else if (ledger != null || checkedAt != null || consumedOperationId != null) throw invalid();
        if ((status == Status.BLOCKED || status == Status.UNAVAILABLE) && issue == null
                || status == Status.VOIDED && issue != Issue.SOURCE_CHANGED) throw invalid();
    }

    /** 固定实际批准、具名财务与递增读取序号，不允许手填当前台账。 */
    public static BudgetAdjustmentReview queue(Input input) {
        return new BudgetAdjustmentReview(input, 1, Status.QUEUED, input.requestedAt(), null, null, null, null, null, null);
    }

    /** 一个读取只领取一次，进程失联后保留超时结论，财务另行发起新读取。 */
    public BudgetAdjustmentReview claim(Instant now, Duration lease) {
        requireTime(now);
        if (status != Status.QUEUED || lease == null || lease.isZero() || lease.isNegative()) throw conflict();
        return new BudgetAdjustmentReview(input, 2, Status.RUNNING, now, now, now.plus(lease), null, null, null, null);
    }

    /** 只有新鲜原范围台账进入可用状态，错误期间和不足余额保留为阻断。 */
    public BudgetAdjustmentReview complete(FinanceResult<BudgetLedgerPort.Snapshot> result, Instant now) {
        requireRunning(now);
        if (!now.isBefore(leaseUntil)) return terminal(Status.UNAVAILABLE, Issue.TIMEOUT, now);
        if (result instanceof FinanceResult.Success<BudgetLedgerPort.Snapshot> success) {
            var observed = success.value();
            if (!observed.matches(input.source().round().content().ledgerRequest(input.source().employeeId()), now)) return fail(Issue.INVALID_RESPONSE, now);
            try { input.source().requireCurrentLedger(observed, now); }
            catch (DomainException rejected) { return terminal(Status.BLOCKED, Issue.LEDGER_CHANGED, now); }
            return new BudgetAdjustmentReview(input, 3, Status.READY, now, startedAt, leaseUntil, observed, now, null, null);
        }
        if (result instanceof FinanceResult.Rejected<BudgetLedgerPort.Snapshot>) return terminal(Status.BLOCKED, Issue.LEDGER_REJECTED, now);
        return fail(result instanceof FinanceResult.Unavailable<BudgetLedgerPort.Snapshot> problem ? Issue.valueOf(problem.failure().name()) : Issue.INVALID_RESPONSE, now);
    }

    /** 网络异常或到期只结束读取，不伪造成功，也不在后台生成预算授权。 */
    public BudgetAdjustmentReview fail(Issue problem, Instant now) {
        requireRunning(now); if (problem == null) throw invalid();
        return terminal(Status.UNAVAILABLE, now.isBefore(leaseUntil) ? problem : Issue.TIMEOUT, now);
    }

    /** 尚在读取的原批准或财务身份失效时停止本次读取。 */
    public BudgetAdjustmentReview voidSource(Instant now) {
        requireTime(now); if (!active()) throw conflict();
        return terminal(Status.VOIDED, Issue.SOURCE_CHANGED, now);
    }

    /** 明确授权和证据消费同事务保存，过期、不同人员或换台账均不能消费。 */
    public BudgetAdjustmentReview consume(BudgetAdjustmentCommand command, Instant now) {
        requireTime(now);
        if (!usable(now) || command == null || !command.authorizedAt().equals(now) || !matches(command)) throw unavailable();
        return new BudgetAdjustmentReview(input, 4, Status.CONSUMED, now, startedAt, leaseUntil, ledger, checkedAt, command.id(), null);
    }

    /** 历史消费始终对应原指令，不因当前台账变化重写其依据。 */
    public boolean supports(BudgetAdjustmentCommand command) {
        return status == Status.CONSUMED && command != null && consumedOperationId.equals(command.id())
                && updatedAt.equals(command.authorizedAt()) && matches(command);
    }

    /** 本地接收时间不延长原系统给出的有效窗口。 */
    public boolean usable(Instant now) {
        return status == Status.READY && now != null && !now.isBefore(updatedAt)
                && ledger.matches(input.source().round().content().ledgerRequest(input.source().employeeId()), now);
    }
    public boolean active() { return status == Status.QUEUED || status == Status.RUNNING; }
    private boolean matches(BudgetAdjustmentCommand command) {
        return input.source().equals(command.source()) && input.requestedBy().equals(command.authorizedBy()) && ledger.equals(command.ledger());
    }
    private BudgetAdjustmentReview terminal(Status next, Issue problem, Instant now) {
        return new BudgetAdjustmentReview(input, version + 1, next, now, startedAt, leaseUntil, null, null, null, problem);
    }
    private void requireRunning(Instant now) { requireTime(now); if (status != Status.RUNNING) throw conflict(); }
    private void requireTime(Instant now) { if (now == null || now.isBefore(updatedAt)) throw conflict(); }
    private static DomainException invalid() { return new DomainException("INVALID_BUDGET_ADJUSTMENT_REVIEW", "Budget review must preserve original source, named finance actor and one evidence consumption"); }
    private static DomainException conflict() { return new DomainException("BUDGET_ADJUSTMENT_REVIEW_CONFLICT", "Budget review no longer permits this transition"); }
    private static DomainException unavailable() { return new DomainException("BUDGET_ADJUSTMENT_REVIEW_UNAVAILABLE", "A fresh original ledger review for the authorizing finance actor is required"); }
    /** 日志不打印完整台账或审批人员。 */
    @Override public String toString() { return "BudgetAdjustmentReview[id=" + input.id() + ", status=" + status + "]"; }

    /**
     * 序号用于判断同一财务的新旧读取，不依靠 UUID 排序。
     * @author owlzhangfq@gmail.com
     */
    public record Input(UUID id, ApprovedBudgetAdjustment source, String requestedBy, long attempt, Instant requestedAt) {
        /** 申请人不能充当本次独立财务，读取不得早于原批准。 */
        public Input {
            if (id == null || source == null || StringUtils.isBlank(requestedBy) || requestedBy.length() > 128 || !requestedBy.equals(requestedBy.trim())
                    || requestedBy.chars().anyMatch(Character::isISOControl) || requestedBy.equals(source.employeeId()) || attempt < 1
                    || requestedAt == null || requestedAt.isBefore(source.approval().approvedAt())) throw invalid();
        }
        /** 默认日志只保留读取标识。 */
        @Override public String toString() { return "BudgetAdjustmentReviewInput[id=" + id + "]"; }
    }
    /**
     * 可用证据与单次明确消费分开，消费也不等于预算已经生效。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { QUEUED, RUNNING, READY, CONSUMED, BLOCKED, UNAVAILABLE, VOIDED }
    /**
     * 外部异常仅保留稳定分类，不持久化其响应正文。
     * @author owlzhangfq@gmail.com
     */
    public enum Issue { NOT_CONFIGURED, TARGET_CHANGED, TIMEOUT, CONNECTION, AUTHENTICATION, REMOTE_FAILURE, INVALID_RESPONSE,
        RESPONSE_TOO_LARGE, INTERNAL_ERROR, SOURCE_CHANGED, LEDGER_CHANGED, LEDGER_REJECTED }
}
