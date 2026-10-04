package io.agentflow.approval.service;

import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.organization.ApprovalProxyUse;

import java.util.UUID;

/**
 * 审批任务审计持久化端口。
 * @author owlzhangfq@gmail.com
 */
public interface TaskAuditPort {
    /** 记录实际任务动作并返回审计事件标识。 */
    String record(TaskOperation operation);

    /**
     * 动作发生当时的任务事实；目标人员来自已校验的转交/委派请求，回交接收人来自原责任人。
     * @author owlzhangfq@gmail.com
     */
    record TaskOperation(String tenantId, String taskId, UUID applicationId, long aggregateVersion,
                         int roundNo, String processInstanceId, String actor, String action,
                         String comment, String targetUser, String nodeId, String nodeName,
                         ApplicationStatus previousStatus, ApplicationStatus currentStatus, MembershipChange membershipChange,
                         ApprovalProxyUse proxyUse, DuplicateApproval duplicateApproval, BudgetConfirmation budgetConfirmation) {
        /** 原人工和重复审批审计不补造外部预算依据。 */
        public TaskOperation(String tenantId, String taskId, UUID applicationId, long aggregateVersion,
                             int roundNo, String processInstanceId, String actor, String action,
                             String comment, String targetUser, String nodeId, String nodeName,
                             ApplicationStatus previousStatus, ApplicationStatus currentStatus, MembershipChange membershipChange,
                             ApprovalProxyUse proxyUse, DuplicateApproval duplicateApproval) {
            this(tenantId, taskId, applicationId, aggregateVersion, roundNo, processInstanceId, actor, action, comment,
                    targetUser, nodeId, nodeName, previousStatus, currentStatus, membershipChange, proxyUse, duplicateApproval, null);
        }
        /** 原人工动作保留调用契约，自动动作另带不可变的来源依据。 */
        public TaskOperation(String tenantId, String taskId, UUID applicationId, long aggregateVersion,
                             int roundNo, String processInstanceId, String actor, String action,
                             String comment, String targetUser, String nodeId, String nodeName,
                             ApplicationStatus previousStatus, ApplicationStatus currentStatus, MembershipChange membershipChange,
                             ApprovalProxyUse proxyUse) {
            this(tenantId, taskId, applicationId, aggregateVersion, roundNo, processInstanceId, actor, action,
                    comment, targetUser, nodeId, nodeName, previousStatus, currentStatus, membershipChange, proxyUse, null);
        }
        /** 会签增减仍只记录成员变更，不补造代理依据。 */
        public TaskOperation(String tenantId, String taskId, UUID applicationId, long aggregateVersion,
                             int roundNo, String processInstanceId, String actor, String action,
                             String comment, String targetUser, String nodeId, String nodeName,
                             ApplicationStatus previousStatus, ApplicationStatus currentStatus, MembershipChange membershipChange) {
            this(tenantId, taskId, applicationId, aggregateVersion, roundNo, processInstanceId, actor, action,
                    comment, targetUser, nodeId, nodeName, previousStatus, currentStatus, membershipChange, null);
        }
        /** 既有任务动作没有会签增减事实，保留原调用及历史语义。 */
        public TaskOperation(String tenantId, String taskId, UUID applicationId, long aggregateVersion,
                             int roundNo, String processInstanceId, String actor, String action,
                             String comment, String targetUser, String nodeId, String nodeName,
                             ApplicationStatus previousStatus, ApplicationStatus currentStatus) {
            this(tenantId, taskId, applicationId, aggregateVersion, roundNo, processInstanceId, actor, action,
                    comment, targetUser, nodeId, nodeName, previousStatus, currentStatus, null, null);
        }
    }

    /**
     * 实际多实例和被增减任务的身份；减签保持 completed，不产生新的同意票。
     * @author owlzhangfq@gmail.com
     */
    record MembershipChange(String executionId, String targetTaskId, int totalBefore, int totalAfter, int completed) { }

    /**
     * 自动通过依据明确指向本轮的实际源任务，不把系统动作冒充本人再次批准。
     * @author owlzhangfq@gmail.com
     */
    record DuplicateApproval(int ruleVersion, UUID definitionId, long definitionVersion, String sourceNodeId,
                             java.util.List<String> sourceTaskIds, String subject) {
        public DuplicateApproval { sourceTaskIds = java.util.List.copyOf(sourceTaskIds); }
    }

    /**
     * 系统通过指向本轮已经实际完成的预算操作，不在通用任务审计复制敏感分摊和外部凭据。
     * @author owlzhangfq@gmail.com
     */
    record BudgetConfirmation(UUID operationId, String commandDigest) { }
}
