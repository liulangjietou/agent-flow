package io.agentflow.approval.process;

import io.agentflow.approval.SubprocessStartService;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.organization.InitiatorContext;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.flowable.bpmn.model.CallActivity;
import org.flowable.engine.interceptor.StartProcessInstanceAfterContext;
import org.flowable.engine.interceptor.StartProcessInstanceBeforeContext;
import org.flowable.engine.interceptor.StartProcessInstanceInterceptor;
import org.flowable.engine.interceptor.StartSubProcessInstanceAfterContext;
import org.flowable.engine.interceptor.StartSubProcessInstanceBeforeContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * 官方原生启动钩子只处理执行上下文转换，申请和文件事务归属应用服务。
 * @author owlzhangfq@gmail.com
 */
@Component
public class FlowableSubprocessStartInterceptor implements StartProcessInstanceInterceptor {
    private static final String PREPARED = "agentflowPreparedSubprocess";
    private final ObjectProvider<SubprocessStartService> starts;
    private final JsonUtil json;

    /** 延迟获取应用服务，避免配置引擎时反向初始化运行端口。 */
    public FlowableSubprocessStartInterceptor(ObjectProvider<SubprocessStartService> starts, JsonUtil json) {
        this.starts = starts; this.json = json;
    }

    /** 根申请仍由现有提交用例负责。 */
    @Override public void beforeStartProcessInstance(StartProcessInstanceBeforeContext context) { }

    /** 根申请轮次继续在原提交事务中保存。 */
    @Override public void afterStartProcessInstance(StartProcessInstanceAfterContext context) { }

    /** 子变量全部显式构造，父表单、节点局部变量和瞬态数据不得整体继承。 */
    @Override
    @SuppressWarnings("unchecked")
    public void beforeStartSubProcessInstance(StartSubProcessInstanceBeforeContext context) {
        var execution = context.getCallActivityExecution(); var parent = execution.getProcessInstance();
        Object applicationId = parent.getVariableLocal("applicationId");
        if (applicationId == null) return;
        if (!(applicationId instanceof String id) || !(parent.getVariableLocal("tenantId") instanceof String tenant)
                || !(parent.getVariableLocal("roundNo") instanceof Integer round)
                || !(parent.getVariableLocal("formData") instanceof Map<?, ?> payload)
                || !tenant.equals(execution.getTenantId()) || !tenant.equals(context.getProcessDefinition().getTenantId())) throw invalidContext();
        if (!(execution.getCurrentFlowElement() instanceof CallActivity activity)
                || !"id".equals(activity.getCalledElementType())
                || !context.getProcessDefinition().getId().equals(activity.getCalledElement())
                || context.isInheritVariables() || activity.isInheritBusinessKey() || activity.isCompleteAsync()
                || !Boolean.FALSE.equals(activity.getFallbackToDefaultTenant())
                || !CollectionUtils.isEmpty(context.getInParameters()) || !CollectionUtils.isEmpty(activity.getOutParameters())
                || StringUtils.hasText(activity.getBusinessKey()) || StringUtils.hasText(activity.getProcessInstanceIdVariableName())) {
            throw new DomainException("SUBPROCESS_VARIABLE_BINDING_INVALID", "Platform subprocesses require explicit isolated variables and a fixed definition id");
        }
        UUID parentId;
        try { parentId = UUID.fromString(id); }
        catch (IllegalArgumentException invalid) { throw invalidContext(); }
        Object frozen = parent.getVariableLocal(FlowableProcessRuntimeAdapter.INITIATOR_CONTEXT);
        if (frozen != null && !(frozen instanceof String)) throw invalidContext();
        var initiator = frozen == null ? null : json.read((String) frozen, InitiatorContext.class);
        var source = new SubprocessStartService.Parent(tenant, parentId, round, parent.getId(), execution.getProcessDefinitionId(),
                activity.getId(), execution.getId(), (Map<String, Object>) payload, initiator);
        var prepared = starts.getObject().prepare(source, context.getProcessDefinition().getId());
        var child = prepared.child(); var variables = new HashMap<String, Object>();
        variables.put("tenantId", tenant); variables.put("applicationId", child.id().toString());
        variables.put("businessNo", child.businessNo()); variables.put("roundNo", child.roundNo());
        variables.put("formData", child.payload());
        if (child.formSchema() != null) variables.put("formFieldTypes", child.formSchema().fieldTypes());
        if (initiator != null) variables.put(FlowableProcessRuntimeAdapter.INITIATOR_CONTEXT, json.write(initiator));
        context.setBusinessKey(child.businessNo()); context.setProcessInstanceName(child.title());
        context.setVariables(variables); context.setTransientVariables(Map.of());
        execution.setTransientVariableLocal(PREPARED, prepared);
    }

    /** 首个子活动调度前保存业务身份；失败随原生创建和父提交一起回滚。 */
    @Override
    public void afterStartSubProcessInstance(StartSubProcessInstanceAfterContext context) {
        var execution = context.getCallActivityExecution();
        var prepared = execution.getTransientVariableLocal(PREPARED);
        if (prepared == null) {
            if (execution.getProcessInstance().getVariableLocal("applicationId") != null) throw invalidContext();
            return;
        }
        execution.removeTransientVariableLocal(PREPARED);
        starts.getObject().persist((SubprocessStartService.Prepared) prepared, context.getProcessInstance().getId());
    }

    private static DomainException invalidContext() {
        return new DomainException("SUBPROCESS_CONTEXT_INVALID", "Native subprocess context does not identify a platform application");
    }
}
