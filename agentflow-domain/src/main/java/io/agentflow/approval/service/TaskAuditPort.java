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
     * 动作发生当时的任务事实；目标人员只能来自转交或委托请求。
     * @author owlzhangfq@gmail.com
     */
    record TaskOperation(String tenantId, String taskId, UUID applicationId, long aggregateVersion,
                         int roundNo, String processInstanceId, String actor, String action,
                         String comment, String targetUser, String nodeId, String nodeName,
                         ApplicationStatus previousStatus, ApplicationStatus currentStatus) { }
}
