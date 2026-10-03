package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.*;
import java.time.Instant;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 独立部分调整保存两侧操作及本地完成；各侧可分时授权，成功的一侧不得重新过账。
 * @author owlzhangfq@gmail.com
 */
public record ExpensePartialAdjustment(Input input, long version, BudgetConsumptionReductionOperation budget,
        ExpenseAccrualReductionOperation accrual, Completion completion, Retirement retirement, String issue, int resolutionCount, Instant updatedAt) {
    /** 旧快照和未曾裁决的调用保留零条决定，不虚构历史证明。 */
    public ExpensePartialAdjustment(Input input, long version, BudgetConsumptionReductionOperation budget,
            ExpenseAccrualReductionOperation accrual, Completion completion, Retirement retirement, String issue, Instant updatedAt) {
        this(input, version, budget, accrual, completion, retirement, issue, 0, updatedAt);
    }
    /** 恢复快照也核对原意图、两侧操作及不可变完成事实，不凭总状态推断已释放资源。 */
    public ExpensePartialAdjustment {
        if (input == null || version < 1 || resolutionCount < 0 || resolutionCount >= version || updatedAt == null || updatedAt.isBefore(input.createdAt())
                || issue != null && !issue.matches("[A-Z][A-Z0-9_]{0,63}")) throw invalid();
        if (budget != null) {
            input.basis().requireBudget(input.id(), budget.input());
            if (budget.createdAt().isBefore(input.createdAt()) || budget.updatedAt().isAfter(updatedAt)) throw invalid();
        }
        if (accrual != null) {
            input.basis().requireAccrual(input.id(), accrual.input());
            if (accrual.createdAt().isBefore(input.createdAt()) || accrual.updatedAt().isAfter(updatedAt)
                    || budget != null && budget.input().command().id().equals(accrual.input().command().id())) throw invalid();
        }
        if (completion != null) {
            if (budget == null || accrual == null || retirement != null || completion.at().isAfter(updatedAt)
                    || completion.budgetVersion() > budget.version() || completion.accrualVersion() > accrual.version()
                    || !completion.budget().matches(budget.input().command(), true, completion.at())
                    || !completion.accrual().matches(accrual.input().command(), true, completion.at())) throw invalid();
        }
        if (retirement != null) {
            input.basis().funding().requireAuthorization(retirement.actor(), retirement.at());
            if (retirement.at().isBefore(input.createdAt()) || retirement.at().isAfter(updatedAt) || issue != null
                    || budget != null && (!budget.safelyUnexecuted() || budget.status() == BudgetConsumptionReductionOperation.Status.QUEUED)
                    || accrual != null && (accrual.retirementBasis() == null || accrual.status() == ExpenseAccrualReductionOperation.Status.QUEUED)) throw invalid();
        }
    }

    /** 先保存固定意图和原来源，不在建立调整时创建或发送任一外部写命令。 */
    public static ExpensePartialAdjustment begin(Input input) { return new ExpensePartialAdjustment(input, 1, null, null, null, null, null, input.createdAt()); }

    /** 预算仅接收新排队授权；旧授权须已明确停止且证明未执行，已成功会计保持。 */
    public ExpensePartialAdjustment authorizeBudget(BudgetConsumptionReductionOperation value, Instant at) {
        requireAuthorizationTime(at);
        if (value == null || !value.equals(BudgetConsumptionReductionOperation.queue(value.input(), at))
                || budget != null && (!budget.safelyUnexecuted() || budget.status() == BudgetConsumptionReductionOperation.Status.QUEUED
                    || budget.input().command().id().equals(value.input().command().id()))) throw conflict();
        return changed(value, accrual, null, null, null, at);
    }

    /** 挂账独立使用当前期间和新原件证据，旧成功或未决操作不能被新编号替换。 */
    public ExpensePartialAdjustment authorizeAccrual(ExpenseAccrualReductionOperation value, Instant at) {
        requireAuthorizationTime(at);
        if (value == null || !value.equals(ExpenseAccrualReductionOperation.queue(value.input(), at))
                || accrual != null && (accrual.retirementBasis() == null || accrual.status() == ExpenseAccrualReductionOperation.Status.QUEUED
                    || accrual.input().command().id().equals(value.input().command().id()))) throw conflict();
        return changed(budget, value, null, null, null, at);
    }

    /** 只接受原操作的一步合法转换，查询或争议不会清除已经接受的资源完成证明。 */
    public ExpensePartialAdjustment withBudget(BudgetConsumptionReductionOperation value, Instant at) {
        requireOpenTime(at);
        if (budget == null || value == null || !value.updatedAt().equals(at) || !budget.acceptsSuccessor(value)) throw conflict();
        if (value.status() == BudgetConsumptionReductionOperation.Status.EXECUTING && issue != null) throw conflict();
        var nextIssue = issue;
        if (completion != null && value.status() != BudgetConsumptionReductionOperation.Status.APPLIED && nextIssue == null) nextIssue = "BUDGET_RESULT_UNCONFIRMED";
        return changed(value, accrual, completion, null, nextIssue, at);
    }

    /** ERP 保留最高修订和原接受事实，不能由快照跳过发送、查询或争议处理。 */
    public ExpensePartialAdjustment withAccrual(ExpenseAccrualReductionOperation value, Instant at) {
        requireOpenTime(at);
        if (accrual == null || value == null || !value.updatedAt().equals(at) || !accrual.acceptsSuccessor(value)) throw conflict();
        if (value.status() == ExpenseAccrualReductionOperation.Status.POSTING && issue != null) throw conflict();
        var nextIssue = issue;
        if (completion != null && value.status() != ExpenseAccrualReductionOperation.Status.POSTED && nextIssue == null) nextIssue = "ACCRUAL_RESULT_UNCONFIRMED";
        return changed(budget, value, completion, null, nextIssue, at);
    }

    /** 仓储须与完整资源差额及回款消费同事务保存，只有两侧实际成功才能形成该完成事实。 */
    public ExpensePartialAdjustment completeResources(Instant at) {
        requireOpenTime(at); if (status() != Status.READY) throw conflict();
        return changed(budget, accrual, new Completion(budget.version(), budget.observation(), accrual.version(), accrual.observation(), at), null, null, at);
    }

    /** 后续来源变化只追加问题，已过账、已减预算及已释放资源各自保持。 */
    public ExpensePartialAdjustment requireReview(String code, Instant at) {
        requireOpenTime(at); if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,63}")) throw conflict();
        if (code.equals(issue)) return this;
        return changed(budget, accrual, completion, null, code, at);
    }

    /** 应用服务完成原来源复核后明确确认；已经完成时只能确认原事实，不能换成另一张凭证。 */
    public ExpensePartialAdjustment confirmCurrent(Instant at) {
        requireOpenTime(at); if (issue == null || !financeReady() || completion != null && !sameCompletion()) throw conflict();
        return changed(budget, accrual, completion, null, null, at);
    }

    /** 预算裁决单独增加决定计数，已有资源完成和来源复核问题均保留。 */
    public ExpensePartialAdjustment resolveBudget(BudgetConsumptionReductionObservation.Status outcome,
            BudgetConsumptionReductionOperation.ResolutionHistory history, Instant at) {
        requireOpenTime(at); if (budget == null) throw conflict();
        var resolved = budget.resolveDispute(outcome, history, at);
        if (completion != null && !completion.budget().posting().equals(resolved.observation().posting())) throw conflict();
        return new ExpensePartialAdjustment(input, Math.incrementExact(version), resolved, accrual, completion, null, issue, Math.incrementExact(resolutionCount), at);
    }

    /** ERP 裁决不能替换本地完成已采用的实际反向凭证，另一侧继续独立办理。 */
    public ExpensePartialAdjustment resolveAccrual(ExpenseAccrualReductionObservation.Status outcome,
            ExpenseAccrualReductionOperation.ResolutionHistory history, Instant at) {
        requireOpenTime(at); if (accrual == null) throw conflict();
        var resolved = accrual.resolveDispute(outcome, history, at);
        if (completion != null && !sameAccrual(completion.accrual(), resolved.observation())) throw conflict();
        return new ExpensePartialAdjustment(input, Math.incrementExact(version), budget, resolved, completion, null, issue, Math.incrementExact(resolutionCount), at);
    }

    /** 两侧均未产生效果才允许具名结束；停止尚未发送的队列和结束事实必须一起提交。 */
    public ExpensePartialAdjustment retire(String actor, String evidence, String reason, Instant at) {
        requireOpenTime(at); input.basis().funding().requireAuthorization(actor, at);
        if (completion != null || budget != null && !budget.safelyUnexecuted() || accrual != null && accrual.retirementBasis() == null) throw conflict();
        var stoppedBudget = budget != null && budget.status() == BudgetConsumptionReductionOperation.Status.QUEUED ? budget.voidBeforeSend(at) : budget;
        var stoppedAccrual = accrual == null ? null : accrual.stopForRetirement(at);
        return changed(stoppedBudget, stoppedAccrual, null, new Retirement(actor, evidence, reason, at), null, at);
    }

    /** 完成和当前可继续办理分别判断，任何未确认的后续查询都阻止下一次调整。 */
    public Status status() {
        if (retirement != null) return Status.RETIRED;
        if (issue != null || completion != null && (!financeReady() || !sameCompletion())) return Status.REVIEW_REQUIRED;
        if (completion != null) return Status.APPLIED;
        return financeReady() ? Status.READY : Status.WAITING_FINANCE;
    }
    public UUID id() { return input.id(); }
    private boolean financeReady() { return budget != null && budget.status() == BudgetConsumptionReductionOperation.Status.APPLIED
            && accrual != null && accrual.status() == ExpenseAccrualReductionOperation.Status.POSTED; }
    private boolean sameCompletion() {
        if (!financeReady()) return false;
        return completion.budget().posting().equals(budget.observation().posting()) && sameAccrual(completion.accrual(), accrual.observation());
    }
    private static boolean sameAccrual(ExpenseAccrualReductionObservation original, ExpenseAccrualReductionObservation current) {
        var before = original.posting(); var after = current.posting();
        return after != null && before.voucher().equals(after.voucher())
                && before.adjustmentRevision() == after.adjustmentRevision() && before.beforeDigest().equals(after.beforeDigest()) && before.afterDigest().equals(after.afterDigest())
                && after.original().revision() >= before.original().revision() && !after.original().observedAt().isBefore(before.original().observedAt());
    }
    private ExpensePartialAdjustment changed(BudgetConsumptionReductionOperation nextBudget, ExpenseAccrualReductionOperation nextAccrual,
            Completion nextCompletion, Retirement nextRetirement, String nextIssue, Instant at) {
        return new ExpensePartialAdjustment(input, Math.incrementExact(version), nextBudget, nextAccrual, nextCompletion, nextRetirement, nextIssue, resolutionCount, at);
    }
    private void requireOpenTime(Instant at) { if (at == null || at.isBefore(updatedAt) || retirement != null) throw conflict(); }
    private void requireAuthorizationTime(Instant at) { requireOpenTime(at); if (completion != null || issue != null) throw conflict(); }
    private static boolean text(String value, int max) { return StringUtils.isNotBlank(value) && value.length() <= max && value.equals(value.trim()) && value.chars().noneMatch(Character::isISOControl); }
    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_PARTIAL_ADJUSTMENT", "Partial adjustment must preserve exact sources, separate external operations and immutable completion"); }
    private static DomainException conflict() { return new DomainException("EXPENSE_PARTIAL_ADJUSTMENT_CONFLICT", "Partial adjustment state or original operation no longer permits this transition"); }
    @Override public String toString() { return "ExpensePartialAdjustment[id=" + id() + ", version=" + version + ", status=" + status() + "]"; }

    /**
     * 报销和金额不由后续授权替换；财务在各侧发送前分别确认新鲜期间及写命令。
     * @author owlzhangfq@gmail.com
     */
    public record Input(UUID id, ExpensePartialAdjustmentBasis basis, String requestedBy, String evidenceReference, String reason, Instant createdAt) {
        /** 建立意图仍要求独立财务，后继必须发生在前次真实完成之后。 */
        public Input {
            if (id == null || basis == null || !text(requestedBy, 128) || !text(evidenceReference, 128) || !text(reason, 2000) || createdAt == null
                    || basis.previous() != null && (basis.previous().id().equals(id) || createdAt.isBefore(basis.previous().completedAt()))) throw invalid();
            basis.funding().requireAuthorization(requestedBy, createdAt);
        }
        @Override public String toString() { return "ExpensePartialAdjustmentInput[id=" + id + ", reportId=" + basis.reportId() + "]"; }
    }
    /**
     * 本地资源实际完成时固定接受的两侧修订，后续查询只增加当前状态而不改写它。
     * @author owlzhangfq@gmail.com
     */
    public record Completion(long budgetVersion, BudgetConsumptionReductionObservation budget, long accrualVersion, ExpenseAccrualReductionObservation accrual, Instant at) {
        /** 两侧成功必须属于同一调整且先于本地完成，排队和准备修订不能冒充成功。 */
        public Completion {
            if (budgetVersion < 3 || accrualVersion < 3 || budget == null || accrual == null || at == null
                    || budget.status() != BudgetConsumptionReductionObservation.Status.APPLIED || accrual.status() != ExpenseAccrualReductionObservation.Status.POSTED
                    || !budget.adjustmentId().equals(accrual.adjustmentId()) || budget.operationId().equals(accrual.operationId())
                    || budget.observedAt().isAfter(at) || accrual.observedAt().isAfter(at)) throw invalid();
        }
    }
    /**
     * 明确结束保留办理人和证据，不表示曾发生的外部效果被撤销。
     * @author owlzhangfq@gmail.com
     */
    public record Retirement(String actor, String evidenceReference, String reason, Instant at) {
        /** 原申请人和出纳隔离由调整来源统一核对。 */
        public Retirement { if (!text(actor, 128) || !text(evidenceReference, 128) || !text(reason, 2000) || at == null) throw invalid(); }
    }
    /**
     * 当前办理状态和不可变完成证明分离，争议不会清除已发生事实。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { WAITING_FINANCE, READY, APPLIED, REVIEW_REQUIRED, RETIRED }
}
