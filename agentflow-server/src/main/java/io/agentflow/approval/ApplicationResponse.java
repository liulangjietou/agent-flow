package io.agentflow.approval;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;

import java.util.Map;
import java.util.UUID;

/** 面向客户端的申请读模型。 */
public record ApplicationResponse(UUID id, String tenantId, String businessNo, String processKey,
                                  long definitionVersion, String createdBy, String title,
                                  Map<String, Object> payload, ApplicationStatus status,
                                  int roundNo, long version) {
    /** 从领域聚合构建读模型。 */
    public static ApplicationResponse from(Application application) {
        return new ApplicationResponse(application.id(), application.tenantId(), application.businessNo(),
                application.processKey(), application.definitionVersion(), application.createdBy(), application.title(),
                application.payload(), application.status(), application.roundNo(), application.version());
    }
}
