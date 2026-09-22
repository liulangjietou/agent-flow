package io.agentflow.approval.service;

import java.util.UUID;

/**
 * 申请级审计端口，记录不依附于某个人工任务的申请操作。
 * @author owlzhangfq@gmail.com
 */
public interface ApplicationAuditPort {
    /** 记录发起人的撤回决定及其实际终止的流程实例。 */
    void recordWithdrawal(String tenantId, UUID applicationId, long aggregateVersion, int roundNo,
                          String processInstanceId, String actor, String comment);
}
