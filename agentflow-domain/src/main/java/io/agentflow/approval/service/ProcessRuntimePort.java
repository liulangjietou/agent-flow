package io.agentflow.approval.service;

import java.util.UUID;
import java.util.Map;

/** 流程运行时防腐层，领域应用层不依赖 Flowable 类型。 */
public interface ProcessRuntimePort {
    /** 启动与申请版本绑定的流程实例。 */
    StartedProcess start(StartProcessCommand command);

    /** 完成当前人工任务。 */
    CompletedTask complete(CompleteTaskCommand command);

    /** 终止退回或驳回对应的整个流程实例，不执行当前节点的出线。 */
    void terminate(TerminateProcessCommand command);

    record StartProcessCommand(String tenantId, UUID applicationId, String processKey,
                               long definitionVersion, int roundNo, String businessNo,
                               Map<String, Object> payload) {
        public StartProcessCommand(String tenantId, UUID applicationId, String processKey,
                                   long definitionVersion, int roundNo, String businessNo) {
            this(tenantId, applicationId, processKey, definitionVersion, roundNo, businessNo, Map.of());
        }
    }

    record StartedProcess(String processInstanceId, String firstTaskId) { }

    record CompleteTaskCommand(String tenantId, String taskId, String action, String comment) { }

    record CompletedTask(String taskId, boolean processEnded) { }

    record TerminateProcessCommand(String tenantId, String processInstanceId, String reason) { }
}
