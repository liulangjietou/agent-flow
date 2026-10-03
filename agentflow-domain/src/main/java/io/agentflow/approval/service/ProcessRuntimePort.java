package io.agentflow.approval.service;

import io.agentflow.approval.model.SubmissionRisk;
import java.util.UUID;
import io.agentflow.form.FormSchema;
import io.agentflow.organization.InitiatorContext;
import java.util.Map;

/**
 * 流程运行时防腐层，领域应用层不依赖 Flowable 类型。
 * @author owlzhangfq@gmail.com
 */
public interface ProcessRuntimePort {
    /** 按明确来源解析实际定义标识；内置与租户定义之间禁止自动替换。 */
    String resolveDefinition(String tenantId, String processKey, long definitionVersion, boolean bundled);

    /** 只读地检查原绑定版本及其固定后代是否需要任职，不选择人员或启动流程。 */
    boolean requiresInitiatorAppointment(DefinitionBinding binding);

    /**
     * 原申请和历史实例共同确定定义来源，供启动和发起提示复用。
     * @author owlzhangfq@gmail.com
     */
    record DefinitionBinding(String tenantId, UUID applicationId, String processKey, long definitionVersion,
                             String runtimeDefinitionId, String previousProcessInstanceId) { }

    /** 启动与申请版本绑定的流程实例。 */
    StartedProcess start(StartProcessCommand command);

    /** 核定业务金额变化仅更新当前唯一实例的派生路由，不启动或结束任务。 */
    void updateBusinessPayload(UpdateBusinessPayload command);

    /**
     * 精确绑定实际审批轮次，不能把核减写到另一个或已结束的实例。
     * @author owlzhangfq@gmail.com
     */
    record UpdateBusinessPayload(String tenantId, UUID applicationId, int roundNo, String processInstanceId,
                                 Map<String, Object> payload) { }

    /** 完成当前人工任务。 */
    CompletedTask complete(CompleteTaskCommand command);

    /** 终止退回或驳回对应的整个流程实例，不执行当前节点的出线。 */
    void terminate(TerminateProcessCommand command);

    /** 精确定位申请当前轮次的唯一活跃实例并终止；缺失、重复或绑定不符必须失败。 */
    String withdraw(WithdrawProcessCommand command);

    /**
     * @author owlzhangfq@gmail.com
     */
    record StartProcessCommand(String tenantId, UUID applicationId, String processKey,
                               long definitionVersion, int roundNo, String businessNo,
                               Map<String, Object> payload, FormSchema formSchema,
                               String runtimeDefinitionId, String previousProcessInstanceId, InitiatorContext initiatorContext) {
        /** 提取实际定义来源；查询不需要访问表单内容或发起人的任职资料。 */
        public DefinitionBinding definitionBinding() {
            return new DefinitionBinding(tenantId, applicationId, processKey, definitionVersion, runtimeDefinitionId, previousProcessInstanceId);
        }
        /** 未选择任职的旧调用不补造上下文。 */
        public StartProcessCommand(String tenantId, UUID applicationId, String processKey, long definitionVersion,
                                   int roundNo, String businessNo, Map<String, Object> payload, FormSchema formSchema,
                                   String runtimeDefinitionId, String previousProcessInstanceId) {
            this(tenantId, applicationId, processKey, definitionVersion, roundNo, businessNo, payload, formSchema,
                    runtimeDefinitionId, previousProcessInstanceId, null);
        }
        /** 兼容尚未保存实际定义标识的旧调用。 */
        public StartProcessCommand(String tenantId, UUID applicationId, String processKey, long definitionVersion,
                                   int roundNo, String businessNo, Map<String, Object> payload, FormSchema formSchema) {
            this(tenantId, applicationId, processKey, definitionVersion, roundNo, businessNo, payload, formSchema, null, null);
        }

        /** 旧申请继续使用原有条件求值语义。 */
        public StartProcessCommand(String tenantId, UUID applicationId, String processKey,
                                   long definitionVersion, int roundNo, String businessNo, Map<String, Object> payload) {
            this(tenantId, applicationId, processKey, definitionVersion, roundNo, businessNo, payload, null);
        }

        public StartProcessCommand(String tenantId, UUID applicationId, String processKey,
                                   long definitionVersion, int roundNo, String businessNo) {
            this(tenantId, applicationId, processKey, definitionVersion, roundNo, businessNo, Map.of());
        }
    }

    /**
     * @author owlzhangfq@gmail.com
     */
    record StartedProcess(String processInstanceId, String firstTaskId, SubmissionRisk risk) {
        /** 未提供策略的旧运行适配器保持未评估。 */
        public StartedProcess(String processInstanceId, String firstTaskId) {
            this(processInstanceId, firstTaskId, SubmissionRisk.unassessed());
        }
    }

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

    /**
     * 已有快照提供实例绑定；旧申请允许实例标识为空，由运行时精确核对申请和轮次。
     * @author owlzhangfq@gmail.com
     */
    record WithdrawProcessCommand(String tenantId, UUID applicationId, int roundNo,
                                  String expectedProcessInstanceId, String reason) { }
}
