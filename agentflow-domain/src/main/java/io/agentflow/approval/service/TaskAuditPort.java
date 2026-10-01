package io.agentflow.approval.service;

import io.agentflow.approval.model.ApplicationStatus;

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
                         ApplicationStatus previousStatus, ApplicationStatus currentStatus, MembershipChange membershipChange) {
        /** 既有任务动作没有会签增减事实，保留原调用及历史语义。 */
        public TaskOperation(String tenantId, String taskId, UUID applicationId, long aggregateVersion,
                             int roundNo, String processInstanceId, String actor, String action,
                             String comment, String targetUser, String nodeId, String nodeName,
                             ApplicationStatus previousStatus, ApplicationStatus currentStatus) {
            this(tenantId, taskId, applicationId, aggregateVersion, roundNo, processInstanceId, actor, action,
                    comment, targetUser, nodeId, nodeName, previousStatus, currentStatus, null);
        }
    }

    /**
     * 实际多实例和被增减任务的身份；减签保持 completed，不产生新的同意票。
     * @author owlzhangfq@gmail.com
     */
    record MembershipChange(String executionId, String targetTaskId, int totalBefore, int totalAfter, int completed) { }
}
