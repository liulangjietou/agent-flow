package io.agentflow.approval.service;

import io.agentflow.common.Actor;

import java.util.UUID;

/**
 * 查询审批申请的参与者关系，屏蔽流程引擎的任务和历史模型。
 * @author owlzhangfq@gmail.com
 */
public interface ApplicationParticipantPort {
    /** 判断主体是否参与过或正在参与指定申请。 */
    boolean isParticipant(String tenantId, UUID applicationId, Actor actor);
}
