package io.agentflow.approval.process;

import io.agentflow.approval.service.TaskRecipientDirectory;
import io.agentflow.common.DomainException;
import io.agentflow.definition.ApprovalPolicy;
import io.agentflow.definition.DefinitionModels.ApprovalMode;
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
    static final String MODE = "agentflowCountersignMode";
    static final String PERCENTAGE = "agentflowCountersignPercentage";
    static final String REQUIRED = "agentflowCountersignRequired";
    private final TaskRecipientDirectory directory;
    private final FlowableOrganizationMembers organization;
    private final FlowableApprovalResponsibilities responsibilities;

    /** 注入当前租户的有效审批账号来源。 */
    public FlowableCountersignMembers(TaskRecipientDirectory directory, FlowableOrganizationMembers organization,
                                     FlowableApprovalResponsibilities responsibilities) {
        this.directory = directory; this.organization = organization; this.responsibilities = responsibilities;
    }

    /** Flowable 会为基数和每个子执行反复求集合，本轮始终复用根执行中的首次快照。 */
    public List<String> resolve(DelegateExecution execution, String encodedRule) {
        return resolve(execution, encodedRule, ApprovalMode.ALL.name(), null);
    }

    /** 任一及比例方式同时冻结人数门槛，后续目录变化不能改变当前节点的分母。 */
    public List<String> resolve(DelegateExecution execution, String encodedRule, String mode, Integer percentage) {
        return resolveMembers(execution, encodedRule, mode, percentage, false, null);
    }

    /** 新规则在冻结名单和分母之前执行，旧发布表达式不受新增规则影响。 */
    public List<String> resolveWithResponsibilities(DelegateExecution execution, String encodedRule, String mode,
                                                   Integer percentage, boolean excludeApplicant, String encodedReferences) {
        return resolveMembers(execution, encodedRule, mode, percentage, excludeApplicant, encodedReferences);
    }

    private List<String> resolveMembers(DelegateExecution execution, String encodedRule, String mode, Integer percentage,
                                        boolean excludeApplicant, String encodedReferences) {
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
        List<String> selected = encodedReferences != null
                ? responsibilities.resolve(root, encodedRule, excludeApplicant, encodedReferences)
                : io.agentflow.organization.LocalOrganizationDirectory.isLocalRule(rule)
                    ? organization.resolve(root, encodedRule) : directory.members(tenant, users, roles);
        List<String> members = new ArrayList<>(selected.stream().distinct().sorted().toList());
        if (members.isEmpty()) throw new DomainException("COUNTERSIGN_NO_MEMBERS", "No active approvers are available for the countersign node");
        ApprovalPolicy policy = new ApprovalPolicy(ApprovalMode.valueOf(mode), percentage);
        if (policy.mode() != ApprovalMode.ALL) {
            root.setVariableLocal(MODE, policy.mode().name());
            if (policy.percentage() != null) root.setVariableLocal(PERCENTAGE, policy.percentage());
            root.setVariableLocal(REQUIRED, policy.requiredApprovals(members.size()));
        }
        root.setVariableLocal(MEMBERS, members);
        return members;
    }
}
