package io.agentflow.approval.copy;

import io.agentflow.approval.process.FlowableProcessRuntimeAdapter;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.organization.InitiatorContext;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * 受控 BPMN 的抄送触发适配器；不把引擎类型传入收件和权限领域。
 * @author owlzhangfq@gmail.com
 */
@Component("flowableCopyRecipients")
public class FlowableCopyRecipients {
    private final CopyDeliveryService service;
    private final JsonUtil json;
    /** 运行时只转交服务端定义的参数。 */
    public FlowableCopyRecipients(CopyDeliveryService service, JsonUtil json) { this.service = service; this.json = json; }

    /** 节点本身不产生待办、审批意见或外部网络请求。 */
    public void deliver(DelegateExecution execution, String encodedRule) {
        String tenant = execution.getTenantId();
        if (tenant == null || !tenant.equals(execution.getVariable("tenantId"))) {
            throw new DomainException("COPY_SCOPE_INVALID", "Copy execution tenant is invalid");
        }
        var raw = execution.getVariable(FlowableProcessRuntimeAdapter.INITIATOR_CONTEXT);
        var context = raw instanceof String value ? json.read(value, InitiatorContext.class) : null;
        service.deliver(tenant, UUID.fromString((String) execution.getVariable("applicationId")),
                ((Number) execution.getVariable("roundNo")).intValue(), execution.getProcessInstanceId(),
                execution.getCurrentActivityId(), execution.getCurrentFlowElement().getName(),
                new String(Base64.getDecoder().decode(encodedRule), StandardCharsets.UTF_8), context);
    }
}
