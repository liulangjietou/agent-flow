package io.agentflow.approval.service;

/**
 * 审批任务审计持久化端口。
 * @author owlzhangfq@gmail.com
 */
public interface TaskAuditPort {
    /** 记录一次任务动作并返回幂等事件标识。 */
    String record(String tenantId, String taskId, long aggregateVersion, String actor,
                 String action, String comment);
}
