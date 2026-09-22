package io.agentflow.approval.service;

import java.util.UUID;
import java.util.Map;

/**
 * 流程运行时防腐层，领域应用层不依赖 Flowable 类型。
 * @author owlzhangfq@gmail.com
 */
public interface ProcessRuntimePort {
    /** 启动与申请版本绑定的流程实例。 */
    StartedProcess start(StartProcessCommand command);

    /** 完成当前人工任务。 */
    CompletedTask complete(CompleteTaskCommand command);

    /** 终止退回或驳回对应的整个流程实例，不执行当前节点的出线。 */
    void terminate(TerminateProcessCommand command);

    /**
     * @author owlzhangfq@gmail.com
     */
    record StartProcessCommand(String tenantId, UUID applicationId, String processKey,
                               long definitionVersion, int roundNo, String businessNo,
                               Map<String, Object> payload) {
        public StartProcessCommand(String tenantId, UUID applicationId, String processKey,
                                   long definitionVersion, int roundNo, String businessNo) {
            this(tenantId, applicationId, processKey, definitionVersion, roundNo, businessNo, Map.of());
        }
    }

    /**
     * @author owlzhangfq@gmail.com
     */
    record StartedProcess(String processInstanceId, String firstTaskId) { }

    /**
     * @author owlzhangfq@gmail.com
     */
    record CompleteTaskCommand(String tenantId, String taskId, String action, String comment) { }

    /**
     * @author owlzhangfq@gmail.com
     */
    record CompletedTask(String taskId, boolean processEnded) { }

    /**
     * @author owlzhangfq@gmail.com
     */
    record TerminateProcessCommand(String tenantId, String processInstanceId, String reason) { }
}
