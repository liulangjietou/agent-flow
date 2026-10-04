package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.util.UUID;

/**
 * 一次真实人工同意的外部授权输入；只允许重试原拒绝命令，不允许授权链再次授权。
 * @author owlzhangfq@gmail.com
 */
public record BudgetExceptionApproval(UUID originalOperationId, String originalCommandDigest, String targetDigest,
        String policyReference, String offerReference, String taskId, String actorId, UUID auditEventId, Instant approvedAt) {
    /** JSON 恢复也必须保留完整的原操作、目标和真实审批依据。 */
    public BudgetExceptionApproval {
        if (originalOperationId == null || originalCommandDigest == null || !originalCommandDigest.matches("[a-f0-9]{64}")
                || targetDigest == null || !targetDigest.matches("[a-f0-9]{64}")
                || StringUtils.isBlank(policyReference) || policyReference.length() > 128
                || StringUtils.isBlank(offerReference) || offerReference.length() > 128
                || StringUtils.isBlank(taskId) || taskId.length() > 128 || StringUtils.isBlank(actorId) || actorId.length() > 128
                || auditEventId == null || approvedAt == null) throw invalid();
    }

    /** 调用方在原任务事务取得真实人员与审计号；领域只从已拒绝的原操作生成授权。 */
    public static BudgetExceptionApproval authorize(BudgetOperation original, BudgetExceptionPolicy policy,
            String taskId, String actorId, UUID auditEventId, Instant at) {
        if (original == null || original.status() != BudgetOperation.Status.REJECTED || policy == null || at == null
                || at.isBefore(original.updatedAt()) || original.input().command().exceptionApproval() != null
                || original.observation().rejection() != BudgetObservation.Rejection.BUDGET_EXCEPTION_REQUIRED
                || !policy.reference().equals(original.observation().exceptionOffer().policyReference())) throw invalid();
        var input = original.input(); var command = input.command();
        return new BudgetExceptionApproval(command.id(), command.digest(), input.targetDigest(), policy.reference(),
                original.observation().exceptionOffer().reference(), taskId, actorId, auditEventId, at);
    }

    /** 新命令必须与原拒绝保持同租户、单据、轮次、分摊、动作和前置台账。 */
    public boolean matches(String tenantId, BudgetCommand.Action action, BudgetPrecheckPort.Request position, BudgetCommand.Expected expected) {
        return originalCommandDigest.equals(new BudgetCommand(originalOperationId, tenantId, action, position, expected).digest());
    }

    private static DomainException invalid() {
        return new DomainException("INVALID_BUDGET_EXCEPTION_APPROVAL", "Budget exception approval requires the original refusal and matching policy");
    }
}
