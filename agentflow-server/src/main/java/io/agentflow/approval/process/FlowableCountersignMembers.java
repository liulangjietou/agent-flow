package io.agentflow.approval.process;

import io.agentflow.approval.service.TaskRecipientDirectory;
import io.agentflow.common.DomainException;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * 在会签节点激活时解析身份目录，并把名单冻结在该多实例根执行中。
 * @author owlzhangfq@gmail.com
 */
@Component("flowableCountersignMembers")
public class FlowableCountersignMembers {
    static final String MEMBERS = "agentflowCountersignMembers";
    static final String TOTAL = "nrOfInstances";
    static final String COMPLETED = "nrOfCompletedInstances";
    private final TaskRecipientDirectory directory;
    private final FlowableOrganizationMembers organization;

    /** 注入当前租户的有效审批账号来源。 */
    public FlowableCountersignMembers(TaskRecipientDirectory directory, FlowableOrganizationMembers organization) { this.directory = directory; this.organization = organization; }

    /** Flowable 会为基数和每个子执行反复求集合，本轮始终复用根执行中的首次快照。 */
    public List<String> resolve(DelegateExecution execution, String encodedRule) {
        DelegateExecution root = execution;
        while (!root.isMultiInstanceRoot() && root.getParent() != null) root = root.getParent();
        String tenant = root.getTenantId();
        if (!root.isMultiInstanceRoot() || tenant == null || !tenant.equals(root.getVariable("tenantId"))) {
            throw new DomainException("COUNTERSIGN_STATE_INVALID", "Countersign execution tenant or scope is invalid");
        }
        if (root.getVariableLocal(MEMBERS) instanceof List<?> snapshot) return snapshot.stream().map(String.class::cast).toList();
        String rule = new String(Base64.getDecoder().decode(encodedRule), StandardCharsets.UTF_8);
        Set<String> users = rule.startsWith("user:") ? Set.of(rule.substring("user:".length())) : Set.of();
        Set<String> roles = rule.startsWith("role:") ? Set.of(rule.substring("role:".length())) : Set.of();
        List<String> members = new ArrayList<>((io.agentflow.organization.LocalOrganizationDirectory.isLocalRule(rule)
                ? organization.resolve(root, encodedRule) : directory.members(tenant, users, roles)).stream().distinct().sorted().toList());
        if (members.isEmpty()) throw new DomainException("COUNTERSIGN_NO_MEMBERS", "No active approvers are available for the countersign node");
        root.setVariableLocal(MEMBERS, members);
        return members;
    }
}
