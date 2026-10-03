package io.agentflow.approval.process;

import io.agentflow.common.DomainException;
import io.agentflow.servicetask.ServiceTaskActivationService;
import org.flowable.bpmn.model.ReceiveTask;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.ExecutionListener;
import org.flowable.engine.impl.util.CommandContextUtil;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * 白名单服务节点原生等待；固定监听器只登记本地命令，绝不在引擎事务里访问远端。
 * @author owlzhangfq@gmail.com
 */
@Component("flowableServiceTaskArrival")
public class FlowableServiceTaskArrival implements ExecutionListener {
    private final ObjectProvider<ServiceTaskActivationService> activations;
    /** 延迟解析用例，避免引擎创建与应用服务互相初始化。 */
    public FlowableServiceTaskArrival(ObjectProvider<ServiceTaskActivationService> activations) { this.activations = activations; }

    /** 只读取流程实例级可信上下文，不接受用户字段或节点局部变量覆盖原身份。 */
    @Override
    @SuppressWarnings("unchecked")
    public void notify(DelegateExecution execution) {
        var instance = CommandContextUtil.getExecutionEntityManager().findById(execution.getProcessInstanceId());
        if (instance == null || !EVENTNAME_START.equals(execution.getEventName()) || !(execution.getCurrentFlowElement() instanceof ReceiveTask)
                || !(instance.getVariableLocal("tenantId") instanceof String tenant) || !tenant.equals(execution.getTenantId())
                || !(instance.getVariableLocal("applicationId") instanceof String id)
                || !(instance.getVariableLocal("roundNo") instanceof Integer round)
                || !(instance.getVariableLocal("formData") instanceof Map<?, ?> values)) throw invalid();
        UUID applicationId;
        try { applicationId = UUID.fromString(id); }
        catch (IllegalArgumentException exception) { throw invalid(); }
        activations.getObject().activate(tenant, applicationId, round, execution.getProcessDefinitionId(), instance.getId(), execution.getId(),
                execution.getCurrentActivityId(), (Map<String, Object>) values);
    }

    private static DomainException invalid() { return new DomainException("SERVICE_TASK_CONTEXT_INVALID", "Native service task execution requires the original platform context"); }
}
