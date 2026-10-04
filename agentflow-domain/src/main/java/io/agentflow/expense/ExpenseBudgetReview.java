package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.BudgetCommand;
import io.agentflow.finance.BudgetExceptionApproval;
import io.agentflow.finance.BudgetExceptionPolicy;
import io.agentflow.finance.BudgetOperation;
import io.agentflow.finance.BudgetObservation;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 每轮预算结果与真实例外决策的控制记录，金额事实仍由原预算操作持有。
 * @author owlzhangfq@gmail.com
 */
public record ExpenseBudgetReview(Input input, long version, Status status, Instant submittedAt, Instant updatedAt,
        BudgetExceptionApproval approval, UUID authorizedOperationId, AutomaticPass automaticPass, Closure closure) {
    /** 状态形状不能把人工同意、系统通过和原轮次关闭混为一体。 */
    public ExpenseBudgetReview {
        if (input == null || status == null || version < 1 || submittedAt == null || updatedAt == null || updatedAt.isBefore(submittedAt)
                || (approval == null) != (authorizedOperationId == null) || (status == Status.CLOSED) != (closure != null)
                || status == Status.WAITING_BUDGET && (version != 1 || approval != null || automaticPass != null || !submittedAt.equals(updatedAt))
                || status != Status.WAITING_BUDGET && version < 2
                || status == Status.REVIEW_REQUIRED && (input.policy() == null || input.budgetNodeId() == null || approval != null)
                || status == Status.AUTHORIZED && approval == null
                || approval != null && (input.policy() == null || input.budgetNodeId() == null
                    || !input.policy().reference().equals(approval.policyReference()) || !input.targetDigest().equals(approval.targetDigest())
                    || !input.originalOperationId().equals(approval.originalOperationId()) || authorizedOperationId.equals(input.originalOperationId())
                    || approval.approvedAt().isBefore(submittedAt) || approval.approvedAt().isAfter(updatedAt))
                || automaticPass != null && (approval != null || input.budgetNodeId() == null
                    || status != Status.CONFIRMED && status != Status.CLOSED
                    || automaticPass.passedAt().isBefore(submittedAt) || automaticPass.passedAt().isAfter(updatedAt))) throw invalid();
    }
    /** 正式提交只建立等待结果的记录。 */
    public static ExpenseBudgetReview submitted(Input input, Instant at) {
        return new ExpenseBudgetReview(input, 1, Status.WAITING_BUDGET, at, at, null, null, null, null);
    }
    /** 原预算结果驱动本轮控制状态。 */
    public ExpenseBudgetReview observe(BudgetOperation operation, Instant at) {
        requireOperation(operation); requireTime(at);
        if (at.isBefore(operation.updatedAt())) throw invalid();
        if (status == Status.CLOSED || !operation.terminal()) return this;
        if (status == Status.WAITING_BUDGET) {
            var next = operation.status() == BudgetOperation.Status.APPLIED ? Status.CONFIRMED : reviewable(operation) ? Status.REVIEW_REQUIRED : Status.REJECTED;
            return changed(next, at, null, null, null, null);
        }
        if (status == Status.AUTHORIZED && operation.input().command().id().equals(authorizedOperationId)) {
            return changed(operation.status() == BudgetOperation.Status.APPLIED ? Status.CONFIRMED : Status.REJECTED, at, approval, authorizedOperationId, null, null);
        }
        return this;
    }
    /** 真实审批决定只授权一次原拒绝命令。 */
    public ExpenseBudgetReview authorize(BudgetOperation original, UUID operationId, String taskId, String actor, UUID auditId, Instant at) {
        if (status != Status.REVIEW_REQUIRED) throw conflict();
        requireOriginal(original); requireTime(at);
        var proof = BudgetExceptionApproval.authorize(original, input.policy(), taskId, actor, auditId, at);
        return changed(Status.AUTHORIZED, at, proof, operationId, null, null);
    }
    /** 恢复固定编号的原授权命令，不重新分配操作号或更改原分摊。 */
    public BudgetCommand retryCommand(BudgetOperation original) {
        requireOriginal(original);
        if (approval == null || !approval.equals(BudgetExceptionApproval.authorize(original, input.policy(), approval.taskId(),
                approval.actorId(), approval.auditEventId(), approval.approvedAt()))) throw invalid();
        var command = original.input().command();
        return new BudgetCommand(authorizedOperationId, command.tenantId(), command.action(), command.position(), command.expected(), approval);
    }
    /** 已冻结时保存原生预算节点的系统通过依据。 */
    public ExpenseBudgetReview pass(String taskId, UUID auditId, Instant at) {
        if (status != Status.CONFIRMED || approval != null || automaticPass != null || input.budgetNodeId() == null) throw conflict();
        requireTime(at);
        return changed(status, at, null, null, new AutomaticPass(taskId, auditId, at), null);
    }
    /** 结束轮次后保留历史，不复活已关闭的授权。 */
    public ExpenseBudgetReview close(Closure reason, Instant at) {
        if (reason == null) throw invalid(); requireTime(at);
        if (status == Status.CLOSED) return this;
        return changed(Status.CLOSED, at, approval, authorizedOperationId, automaticPass, reason);
    }

    /** 恢复时核对不可变原操作及授权后操作，不能只相信状态 JSON 中的结论。 */
    public void requireSources(BudgetOperation original, BudgetOperation authorized) {
        requireOriginal(original);
        if (approval == null ? authorized != null : authorized == null || !retryCommand(original).equals(authorized.input().command())
                || !input.targetDigest().equals(authorized.input().targetDigest())) throw invalid();
        var active = approval == null ? original : authorized;
        if (status == Status.REVIEW_REQUIRED && !reviewable(original)
                || status == Status.CONFIRMED && active.status() != BudgetOperation.Status.APPLIED
                || status == Status.REJECTED && (active.status() != BudgetOperation.Status.REJECTED || approval == null && reviewable(original))
                || automaticPass != null && original.status() != BudgetOperation.Status.APPLIED) throw invalid();
    }

    private boolean reviewable(BudgetOperation original) {
        return original.status() == BudgetOperation.Status.REJECTED && input.policy() != null && input.budgetNodeId() != null
                && original.observation().rejection() == BudgetObservation.Rejection.BUDGET_EXCEPTION_REQUIRED
                && input.policy().reference().equals(original.observation().exceptionOffer().policyReference());
    }
    private void requireOriginal(BudgetOperation operation) {
        requireOperation(operation);
        if (!operation.input().command().id().equals(input.originalOperationId())) throw invalid();
    }
    private void requireOperation(BudgetOperation operation) {
        if (operation == null) throw invalid();
        var command = operation.input().command(); var position = command.position();
        boolean original = input.originalOperationId().equals(command.id());
        if (!input.tenantId().equals(command.tenantId()) || !input.reportId().equals(position.reportId())
                || !input.employeeId().equals(position.employeeId()) || input.roundNo() != position.roundNo()
                || input.submittedFinancialVersion() != position.financialVersion() || !input.targetDigest().equals(operation.input().targetDigest())
                || command.action() != BudgetCommand.Action.FREEZE && command.action() != BudgetCommand.Action.ADJUST
                || original && (command.exceptionApproval() != null || !operation.createdAt().equals(submittedAt))
                || !original && (approval == null || !Objects.equals(authorizedOperationId, command.id()) || !approval.equals(command.exceptionApproval()))) throw invalid();
    }
    private void requireTime(Instant at) { if (at == null || at.isBefore(updatedAt)) throw invalid(); }
    private ExpenseBudgetReview changed(Status next, Instant at, BudgetExceptionApproval proof, UUID operationId, AutomaticPass passed, Closure reason) {
        return new ExpenseBudgetReview(input, Math.incrementExact(version), next, submittedAt, at, proof, operationId, passed, reason);
    }
    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_BUDGET_REVIEW", "Expense budget review and original sources are inconsistent"); }
    private static DomainException conflict() { return new DomainException("EXPENSE_BUDGET_REVIEW_NOT_ALLOWED", "Budget review is not awaiting this decision"); }

    /**
     * 只保存原提交与原操作身份，不复制金额台账。
     * @author owlzhangfq@gmail.com
     */
    public record Input(String tenantId, UUID reportId, UUID applicationId, String employeeId, int roundNo,
            long submittedFinancialVersion, UUID precheckId, String budgetNodeId, BudgetExceptionPolicy policy,
            UUID originalOperationId, String targetDigest) {
        /** 柔性政策必须同时绑定独立预算节点；刚性历史定义允许没有节点。 */
        public Input {
            if (StringUtils.isBlank(tenantId) || tenantId.length() > 64 || reportId == null || applicationId == null
                    || StringUtils.isBlank(employeeId) || employeeId.length() > 128 || roundNo < 1 || submittedFinancialVersion < 2
                    || precheckId == null || originalOperationId == null || targetDigest == null || !targetDigest.matches("[a-f0-9]{64}")
                    || budgetNodeId != null && (StringUtils.isBlank(budgetNodeId) || budgetNodeId.length() > 128)
                    || policy != null && budgetNodeId == null) throw invalid();
        }
    }
    /**
     * 人工同意与预算已确认是两个不同状态。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { WAITING_BUDGET, REVIEW_REQUIRED, AUTHORIZED, CONFIRMED, REJECTED, CLOSED }
    /**
     * 无需例外时的实际原生任务与系统审计。
     * @author owlzhangfq@gmail.com
     */
    public record AutomaticPass(String taskId, UUID auditEventId, Instant passedAt) {
        /** 不允许没有原生任务或审计编号的系统通过。 */
        public AutomaticPass {
            if (StringUtils.isBlank(taskId) || taskId.length() > 128 || auditEventId == null || passedAt == null) throw invalid();
        }
    }
    /**
     * 原轮次结束的原因，均不改变外部预算事实。
     * @author owlzhangfq@gmail.com
     */
    public enum Closure { WITHDRAWN, RETURNED, REJECTED, CANCELLED }
}
