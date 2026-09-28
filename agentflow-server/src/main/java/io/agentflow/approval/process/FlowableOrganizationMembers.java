package io.agentflow.approval.process;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.organization.OrganizationAssigneeResolver;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * 发布器生成的受控表达式仅调用本组件，组织规则不包含脚本或身份源主体表达式。
 * @author owlzhangfq@gmail.com
 */
@Component("flowableOrganizationMembers")
public class FlowableOrganizationMembers {
    public static final String SNAPSHOT_PREFIX = "agentflowOrganizationMembers_";
    private final OrganizationAssigneeResolver resolver;
    private final JsonUtil json;

    /** 解析属于组织应用服务，执行作用域快照属于引擎适配器。 */
    public FlowableOrganizationMembers(OrganizationAssigneeResolver resolver, JsonUtil json) { this.resolver = resolver; this.json = json; }

    /** 单人节点冻结候选名单，会签节点将同一快照拆为实际个人任务。 */
    public List<String> resolve(DelegateExecution execution, String encodedRule) {
        String tenant = execution.getTenantId();
        if (tenant == null || !tenant.equals(execution.getVariable("tenantId"))) {
            throw new DomainException("ORGANIZATION_SCOPE_INVALID", "Organization execution tenant is invalid");
        }
        String rule = new String(Base64.getDecoder().decode(encodedRule), StandardCharsets.UTF_8);
        Object snapshot = execution.getVariable(FlowableProcessRuntimeAdapter.INITIATOR_CONTEXT);
        var context = snapshot instanceof String value ? json.read(value, io.agentflow.organization.InitiatorContext.class) : null;
        var selection = resolver.resolve(tenant, rule, context);
        execution.setVariableLocal(SNAPSHOT_PREFIX + execution.getCurrentActivityId(), json.write(selection));
        return selection.subjects();
    }
}
