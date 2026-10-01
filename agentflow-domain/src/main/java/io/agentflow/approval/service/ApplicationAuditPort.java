package io.agentflow.approval.service;

import io.agentflow.approval.model.ApplicationStatus;

import java.util.UUID;

/**
 * 申请级审计端口，记录不依附于某个人工任务的申请操作。
 * @author owlzhangfq@gmail.com
 */
public interface ApplicationAuditPort {
    /** 在申请变更的同一事务中保存实际发生的操作。 */
    void record(ApplicationOperation operation);

    /**
     * 申请生命周期操作，不承载表单原文。
     * @author owlzhangfq@gmail.com
     */
    record ApplicationOperation(String tenantId, UUID applicationId, long aggregateVersion, int roundNo,
                                String processInstanceId, String actor, Action action,
                                ApplicationStatus previousStatus, ApplicationStatus currentStatus,
                                String comment) { }

    /**
     * 由申请应用服务执行的生命周期动作。
     * @author owlzhangfq@gmail.com
     */
    enum Action { CREATE, REVISE, SUBMIT, WITHDRAW, CANCEL, RETURN, TIMER_ELAPSED, TIMER_FAILED, TIMER_RETRY, INSTANCE_PAUSE, INSTANCE_RESUME, EVENT_RECEIVED, SUBPROCESS_COMPLETED }
}
